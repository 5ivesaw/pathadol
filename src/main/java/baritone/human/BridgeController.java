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
        return stepBackBridge(ctx, state, src, dest, null);
    }

    /**
     * Executes one tick of humanized back-placing along an optional lookahead bridge corridor yaw
     * so diagonal staircase bridging maintains one steady diagonal yaw and alternates {@code S+A}/{@code S+D}.
     */
    public static MovementState stepBackBridge(
            IPlayerContext ctx,
            MovementState state,
            BetterBlockPos src,
            BetterBlockPos dest,
            Float corridorYawHint
    ) {
        BridgeSkill skill = BridgeSkill.fromSetting(Baritone.settings().humanBridgeSkill.value);
        double faceX = (dest.getX() + src.getX() + 1.0D) * 0.5D;
        double faceY = (dest.getY() + src.getY() - 1.0D) * 0.5D;
        double faceZ = (dest.getZ() + src.getZ() + 1.0D) * 0.5D;

        // Deterministic slight off-center vertical & lateral offset per placed block so aim is never identically 0.500
        long seed = dest.asLong() ^ 0x517CC1B727220A95L;
        double verticalJitter = (((seed & 0xFFL) / 255.0) - 0.5) * 0.12;
        double lateralJitter = ((((seed >>> 8) & 0xFFL) / 255.0) - 0.5) * 0.10;
        double dirX = dest.getX() - src.getX();
        double dirZ = dest.getZ() - src.getZ();

        Vec3 aimPoint = new Vec3(
                faceX + (-dirZ) * lateralJitter,
                faceY + verticalJitter,
                faceZ + dirX * lateralJitter
        );

        BlockPos goalLook = src.below();
        Rotation backToFace = RotationUtils.calcRotationFromVec3d(ctx.playerHead(), aimPoint, ctx.playerRotations());
        float backYaw = RotationUtils.calcRotationFromVec3d(VecUtils.getBlockPosCenter(dest), ctx.playerHead(), ctx.playerRotations()).getYaw();
        float curYaw = ctx.playerRotations().getYaw();

        double distFromEdge = Math.max(
                Math.abs(ctx.player().position().x - faceX),
                Math.abs(ctx.player().position().z - faceZ)
        );

        boolean shouldSneak = shouldSneakAtEdge(skill, distFromEdge, ctx.isLookingAt(goalLook), seed);
        state.setInput(Input.SNEAK, shouldSneak);

        float camYaw = chooseBridgeCameraYaw(skill, corridorYawHint, backYaw, curYaw, backToFace.getYaw(), src, dirX, dirZ, distFromEdge);
        float camPitch = resolveBridgeFacePitch(ctx.playerHead(), camYaw, src, faceX, faceZ, dirX, dirZ, verticalJitter, backToFace.getPitch());

        if (!ctx.isLookingAt(goalLook) && distFromEdge < 0.28) {
            float toFaceDelta = Rotation.normalizeYaw(backToFace.getYaw() - camYaw);
            float nudge = Math.max(-18.0f, Math.min(18.0f, toFaceDelta));
            camYaw = Rotation.normalizeYaw(camYaw + nudge);
            camPitch = resolveBridgeFacePitch(ctx.playerHead(), camYaw, src, faceX, faceZ, dirX, dirZ, verticalJitter, backToFace.getPitch());
        }

        state.setTarget(new MovementState.MovementTarget(new Rotation(camYaw, camPitch), true));

        // Drive backward/strafe keys relative to the actual camera orientation so steady diagonal look keeps S+A/S+D natural
        float stepMoveYaw = PathSmoother.computeYawTo(
                ctx.player().position().x,
                ctx.player().position().z,
                dest.getX() + 0.5,
                dest.getZ() + 0.5
        );
        float relMove = Rotation.normalizeYaw(stepMoveYaw - curYaw);
        if (Math.abs(relMove) < 96.0f) {
            // Still turned forward toward the void drop; hold sneak steady while rotating backward
            state.setInput(Input.SNEAK, true);
        } else {
            if (Math.abs(relMove) >= 106.0f) {
                state.setInput(Input.MOVE_BACK, true);
            }
            if (relMove >= 20.0f && relMove <= 164.0f) {
                state.setInput(Input.MOVE_RIGHT, true);
            } else if (relMove <= -20.0f && relMove >= -164.0f) {
                state.setInput(Input.MOVE_LEFT, true);
            }
        }

        if (ctx.isLookingAt(goalLook) && (shouldSneak || ctx.player().isCrouching())) {
            return state.setInput(Input.CLICK_RIGHT, true);
        }
        return state;
    }

    /**
     * Picks one steady bridge camera yaw: along the opposite of diagonal lookahead corridors
     * (no 90-deg staircase whipping), or locked consistently to one side for straight bridges.
     */
    static float chooseBridgeCameraYaw(
            BridgeSkill skill,
            Float corridorYawHint,
            float backYaw,
            float curYaw,
            float faceYaw,
            BetterBlockPos src,
            double dirX,
            double dirZ,
            double distFromEdge
    ) {
        if (corridorYawHint != null) {
            float steadyDiagBack = Rotation.normalizeYaw(corridorYawHint + 180.0f);
            float dev = Math.abs(Rotation.normalizeYaw(steadyDiagBack - backYaw));
            if (dev >= 12.0f && dev <= 62.0f) {
                return steadyDiagBack;
            }
        }
        if (skill == BridgeSkill.SWEATY) {
            float rel = Rotation.normalizeYaw(curYaw - backYaw);
            float sideSign;
            if (Math.abs(rel) >= 8.0f) {
                sideSign = rel >= 0f ? 1.0f : -1.0f;
            } else {
                int lineParity = Math.abs(dirX) > Math.abs(dirZ) ? src.getZ() : src.getX();
                sideSign = (lineParity & 1) == 0 ? 1.0f : -1.0f;
            }
            return Rotation.normalizeYaw(backYaw + sideSign * 37.0f);
        }
        if (distFromEdge < 0.29) {
            float rel = Rotation.normalizeYaw(curYaw - backYaw);
            float sideSign = Math.abs(rel) >= 4.0f ? (rel >= 0f ? 1.0f : -1.0f) : 1.0f;
            float subtleBias = (skill == BridgeSkill.MEDIUM ? 12.0f : 6.0f) * sideSign;
            return Rotation.normalizeYaw(backYaw + subtleBias);
        }
        return faceYaw;
    }

    /**
     * Computes exact vertical aim pitch where the view ray at {@code camYaw} intersects
     * the exposed vertical face plane of {@code src.below()}.
     */
    static float resolveBridgeFacePitch(
            Vec3 eyePos,
            float camYaw,
            BetterBlockPos src,
            double faceX,
            double faceZ,
            double dirX,
            double dirZ,
            double verticalJitter,
            float fallbackPitch
    ) {
        double rad = Math.toRadians(camYaw);
        double lx = -Math.sin(rad);
        double lz = Math.cos(rad);
        double t = -1.0;
        double hitOther = 0.0;
        double minOther = 0.0;
        double maxOther = 0.0;
        if (Math.abs(dirX) > 0.5 && Math.abs(lx) > 0.15) {
            t = (faceX - eyePos.x) / lx;
            hitOther = eyePos.z + t * lz;
            minOther = src.getZ() + 0.06;
            maxOther = src.getZ() + 0.94;
        } else if (Math.abs(dirZ) > 0.5 && Math.abs(lz) > 0.15) {
            t = (faceZ - eyePos.z) / lz;
            hitOther = eyePos.x + t * lx;
            minOther = src.getX() + 0.06;
            maxOther = src.getX() + 0.94;
        }
        if (t > 0.05 && t < 1.8 && hitOther >= minOther && hitOther <= maxOther) {
            double targetY = src.getY() - 0.38 + verticalJitter;
            double dy = eyePos.y - targetY;
            return Rotation.clampPitch((float) Math.toDegrees(Math.atan2(dy, t)));
        }
        return Rotation.clampPitch(Math.max(71.0f, Math.min(83.5f, fallbackPitch)));
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
