/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Baritone is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>.
 */

package baritone.human;

import baritone.Baritone;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.IPlayerContext;
import baritone.api.utils.Rotation;
import baritone.api.utils.RotationUtils;
import baritone.api.utils.VecUtils;
import baritone.api.utils.input.Input;
import baritone.pathing.movement.MovementState;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import java.util.Locale;

/**
 * Humanized bridging and pillaring controller.
 * Supports {@code safe} (sneak back-bridge with organic off-center aim),
 * {@code medium} (ninja/speedbridge edge-sneak release), and
 * {@code sweaty} (diagonal speedbridge with tight edge sneak timing).
 */
public final class BridgeController {

    private BridgeController() {}

    public enum BridgeSkill {
        SAFE,
        MEDIUM,
        SWEATY;

        public static BridgeSkill fromSetting(String value) {
            if (value == null) {
                return MEDIUM;
            }
            return switch (value.toLowerCase(Locale.ROOT).trim()) {
                case "safe" -> SAFE;
                case "sweaty" -> SWEATY;
                default -> MEDIUM;
            };
        }
    }

    /**
     * Executes one tick of humanized back-placing from {@code src} into {@code dest}
     * when the player is hovering over the edge at {@code feet == dest}.
     */
    public static MovementState stepBackBridge(
            IPlayerContext ctx,
            MovementState state,
            BetterBlockPos src,
            BetterBlockPos dest
    ) {
        BridgeSkill skill = BridgeSkill.fromSetting(Baritone.settings().humanBridgeSkill.value);
        double faceX = (dest.getX() + src.getX() + 1.0D) * 0.5D;
        double faceY = (dest.getY() + src.getY() - 1.0D) * 0.5D;
        double faceZ = (dest.getZ() + src.getZ() + 1.0D) * 0.5D;

        // Deterministic slight off-center vertical & lateral offset per placed block so aim is never identically 0.500
        long seed = dest.asLong() ^ 0x517CC1B727220A95L;
        double verticalJitter = (((seed & 0xFFL) / 255.0) - 0.5) * 0.14;
        double lateralJitter = ((((seed >>> 8) & 0xFFL) / 255.0) - 0.5) * 0.12;
        double dirX = dest.getX() - src.getX();
        double dirZ = dest.getZ() - src.getZ();

        Vec3 aimPoint = new Vec3(
                faceX + (-dirZ) * lateralJitter,
                faceY + verticalJitter,
                faceZ + dirX * lateralJitter
        );

        BlockPos goalLook = src.below();
        Rotation backToFace = RotationUtils.calcRotationFromVec3d(ctx.playerHead(), aimPoint, ctx.playerRotations());
        float pitch = backToFace.getPitch();

        double distFromEdge = Math.max(
                Math.abs(ctx.player().position().x - faceX),
                Math.abs(ctx.player().position().z - faceZ)
        );

        boolean shouldSneak = shouldSneakAtEdge(skill, distFromEdge, ctx.isLookingAt(goalLook), seed);
        state.setInput(Input.SNEAK, shouldSneak);

        if (skill == BridgeSkill.SWEATY) {
            // Diagonal fast bridge: turn ~38 deg off cardinal axis and hold S + A/D
            float backYaw = RotationUtils.calcRotationFromVec3d(VecUtils.getBlockPosCenter(dest), ctx.playerHead(), ctx.playerRotations()).getYaw();
            float diagOffset = ((seed & 1L) == 0L) ? 36.0f : -36.0f;
            float camYaw = Rotation.normalizeYaw(backYaw + diagOffset);
            state.setTarget(new MovementState.MovementTarget(new Rotation(camYaw, pitch), true));
            state.setInput(Input.MOVE_BACK, true);
            if (diagOffset > 0) {
                state.setInput(Input.MOVE_RIGHT, true);
            } else {
                state.setInput(Input.MOVE_LEFT, true);
            }
        } else if (distFromEdge < 0.29) {
            float yaw = RotationUtils.calcRotationFromVec3d(VecUtils.getBlockPosCenter(dest), ctx.playerHead(), ctx.playerRotations()).getYaw();
            float subtleYawBias = skill == BridgeSkill.MEDIUM ? 12.0f : 6.0f;
            if ((seed & 1L) != 0L) {
                subtleYawBias = -subtleYawBias;
            }
            state.setTarget(new MovementState.MovementTarget(new Rotation(Rotation.normalizeYaw(yaw + subtleYawBias), pitch), true));
            state.setInput(Input.MOVE_BACK, true);
        } else {
            state.setTarget(new MovementState.MovementTarget(backToFace, true));
            state.setInput(Input.MOVE_BACK, true);
        }

        if (ctx.isLookingAt(goalLook) && (shouldSneak || ctx.player().isCrouching())) {
            return state.setInput(Input.CLICK_RIGHT, true);
        }
        if (ctx.playerRotations().isReallyCloseTo(state.getTarget().rotation)) {
            state.setInput(Input.CLICK_LEFT, true);
        }
        return state;
    }

    /**
     * Determines whether sneak should be held based on bridge skill and distance to block edge.
     */
    public static boolean shouldSneakAtEdge(BridgeSkill skill, double distFromEdge, boolean lookingAtFace, long seed) {
        if (skill == BridgeSkill.SAFE) {
            return true;
        }
        double jitter = ((((seed >>> 16) & 0x3FL) / 63.0) - 0.5) * 0.04;
        double edgeThreshold = (skill == BridgeSkill.SWEATY ? 0.22 : 0.27) + jitter;
        return lookingAtFace || distFromEdge <= edgeThreshold;
    }

    /**
     * Computes a humanized downward pillar rotation near 86-88.5 degrees rather than exact 90.0.
     */
    public static Rotation humanizedPillarRotation(IPlayerContext ctx, BlockPos targetPos, long tickSeed) {
        Vec3 center = VecUtils.getBlockPosCenter(targetPos);
        double ox = (((tickSeed & 0xFFL) / 255.0) - 0.5) * 0.14;
        double oz = ((((tickSeed >>> 8) & 0xFFL) / 255.0) - 0.5) * 0.14;
        if (Math.abs(ox) < 0.03 && Math.abs(oz) < 0.03) {
            ox = 0.045;
        }
        Rotation rot = RotationUtils.calcRotationFromVec3d(
                ctx.playerHead(),
                center.add(ox, 0.0, oz),
                ctx.playerRotations()
        );
        float clampedPitch = Math.min(88.6f, Math.max(82.0f, rot.getPitch()));
        return new Rotation(rot.getYaw(), clampedPitch);
    }
}
