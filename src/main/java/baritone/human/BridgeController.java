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
import baritone.pathing.movement.MovementHelper;
import baritone.pathing.movement.MovementState;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import java.util.Locale;

/**
 * Humanized bridging and pillaring controller.
 * Supports {@code safe} (continuous crouch back-bridge),
 * {@code medium} (ninja un-sneak between block edges with 28-deg side angle), and
 * {@code sweaty} (fast back-turned straight 37.5-deg &amp; steady diagonal ninja speedbridge).
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
     * Executes one tick of humanized back-placing from {@code src} toward {@code dest}
     * over open void without sneaking forward slowly.
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
     * Executes one tick of fast back-turned ninja bridging along an optional corridor yaw
     * so straight bridges hold a steady god/ninja side angle and diagonal bridges stay locked
     * onto the inverse corridor vector while alternating {@code S+A}/{@code S+D}.
     */
    public static MovementState stepBackBridge(
            IPlayerContext ctx,
            MovementState state,
            BetterBlockPos src,
            BetterBlockPos dest,
            Float corridorYawHint
    ) {
        BridgeSkill skill = resolveEffectiveSkill();
        double faceX = (dest.getX() + src.getX() + 1.0D) * 0.5D;
        double faceY = (dest.getY() + src.getY() - 1.0D) * 0.5D;
        double faceZ = (dest.getZ() + src.getZ() + 1.0D) * 0.5D;

        long seed = dest.asLong() ^ 0x517CC1B727220A95L;
        double verticalJitter = (((seed & 0xFFL) / 255.0) - 0.5) * 0.10;
        double lateralJitter = ((((seed >>> 8) & 0xFFL) / 255.0) - 0.5) * 0.08;
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

        Vec3 pos = ctx.player().position();
        double signedProgress = computeSignedProgress(pos, src, dirX, dirZ);

        float camYaw = chooseBridgeCameraYaw(skill, corridorYawHint, backYaw, curYaw, backToFace.getYaw(), src, dirX, dirZ, signedProgress);
        float camPitch = resolveBridgeFacePitch(ctx.playerHead(), camYaw, src, faceX, faceZ, dirX, dirZ, verticalJitter, backToFace.getPitch());

        if (!ctx.isLookingAt(goalLook) && signedProgress >= 0.42) {
            float toFaceDelta = Rotation.normalizeYaw(backToFace.getYaw() - camYaw);
            float nudge = Math.max(-14.0f, Math.min(14.0f, toFaceDelta));
            camYaw = Rotation.normalizeYaw(camYaw + nudge);
            camPitch = resolveBridgeFacePitch(ctx.playerHead(), camYaw, src, faceX, faceZ, dirX, dirZ, verticalJitter, backToFace.getPitch());
        }

        state.setTarget(new MovementState.MovementTarget(new Rotation(camYaw, camPitch), true));

        float stepMoveYaw = PathSmoother.computeYawTo(
                pos.x,
                pos.z,
                dest.getX() + 0.5,
                dest.getZ() + 0.5
        );
        float relMove = Rotation.normalizeYaw(stepMoveYaw - curYaw);
        boolean turnedBackward = Math.abs(relMove) >= 112.0f;

        boolean lookingAtGoal = ctx.isLookingAt(goalLook);
        boolean shouldSneak;
        if (!turnedBackward) {
            // Turning backward on initial pad: stay crouched until solidly rotated backward or near edge
            shouldSneak = skill == BridgeSkill.SAFE
                    || signedProgress >= 0.14
                    || !MovementHelper.canWalkOn(ctx, ctx.playerFeet().below());
        } else {
            shouldSneak = shouldSneakAtProgress(skill, signedProgress, lookingAtGoal, seed);
        }
        state.setInput(Input.SNEAK, shouldSneak);

        if (!turnedBackward) {
            // Freeze forward push past src center while rotating backward
            if (signedProgress < 0.14) {
                state.setInput(Input.MOVE_FORWARD, true);
            }
        } else {
            applyBackStrafeKeys(state, relMove);
        }

        boolean blockNeeded = !MovementHelper.canWalkOn(ctx, dest.below());
        long gameTick = ctx.world() != null ? ctx.world().getGameTime() : 0L;
        if (shouldPulseRightClick(blockNeeded, lookingAtGoal, shouldSneak || ctx.player().isCrouching(), signedProgress, gameTick, seed)) {
            return state.setInput(Input.CLICK_RIGHT, true);
        }
        return state;
    }

    /**
     * Continues backing briskly (un-sneaked when safe) onto {@code dest} immediately after
     * placing {@code dest.below()} so the camera never whips 180 deg forward for 1-2 leftover ticks.
     */
    public static MovementState stepContinueBack(
            IPlayerContext ctx,
            MovementState state,
            BetterBlockPos src,
            BetterBlockPos dest,
            Float corridorYawHint
    ) {
        BridgeSkill skill = resolveEffectiveSkill();
        Vec3 pos = ctx.player().position();
        double dirX = dest.getX() - src.getX();
        double dirZ = dest.getZ() - src.getZ();
        double signedProgress = computeSignedProgress(pos, src, dirX, dirZ);

        float curYaw = ctx.playerRotations().getYaw();
        float backYaw = RotationUtils.calcRotationFromVec3d(VecUtils.getBlockPosCenter(dest), ctx.playerHead(), ctx.playerRotations()).getYaw();
        float camYaw = chooseBridgeCameraYaw(skill, corridorYawHint, backYaw, curYaw, backYaw, src, dirX, dirZ, signedProgress);
        float camPitch = Math.max(73.5f, Math.min(81.5f, ctx.playerRotations().getPitch()));

        state.setTarget(new MovementState.MovementTarget(new Rotation(camYaw, camPitch), false));
        state.setInput(Input.SNEAK, skill == BridgeSkill.SAFE);

        float stepMoveYaw = PathSmoother.computeYawTo(pos.x, pos.z, dest.getX() + 0.5, dest.getZ() + 0.5);
        float relMove = Rotation.normalizeYaw(stepMoveYaw - curYaw);
        applyBackStrafeKeys(state, relMove);
        return state;
    }

    private static void applyBackStrafeKeys(MovementState state, float relMove) {
        if (Math.abs(relMove) >= 104.0f) {
            state.setInput(Input.MOVE_BACK, true);
        }
        if (relMove >= 18.0f && relMove <= 165.0f) {
            state.setInput(Input.MOVE_RIGHT, true);
        } else if (relMove <= -18.0f && relMove >= -165.0f) {
            state.setInput(Input.MOVE_LEFT, true);
        }
    }

    static double computeSignedProgress(Vec3 pos, BetterBlockPos src, double dirX, double dirZ) {
        double dx = pos.x - (src.getX() + 0.5D);
        double dz = pos.z - (src.getZ() + 0.5D);
        return dx * dirX + dz * dirZ;
    }

    private static BridgeSkill resolveEffectiveSkill() {
        String raw = Baritone.settings().humanBridgeSkill.value;
        if ("learned".equalsIgnoreCase(Baritone.settings().humanProfile.value)) {
            HumanStyleLearner.LearnedState st = HumanStyleLearner.activeLearnedState();
            if (st != null && st.trialsLearned > 0 && st.bridgeSkill != null) {
                raw = st.bridgeSkill;
            }
        }
        return BridgeSkill.fromSetting(raw);
    }

    /**
     * Picks one steady bridge camera yaw: along the opposite of diagonal lookahead corridors
     * (no 90-deg staircase whipping), or locked consistently at a ninja angle for straight bridges.
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
            double signedProgress
    ) {
        if (corridorYawHint != null) {
            float steadyBack = Rotation.normalizeYaw(corridorYawHint + 180.0f);
            float dev = Math.abs(Rotation.normalizeYaw(steadyBack - backYaw));
            if (dev >= 12.0f && dev <= 62.0f) {
                return steadyBack;
            }
        }
        if (skill == BridgeSkill.SWEATY || skill == BridgeSkill.MEDIUM) {
            float rel = Rotation.normalizeYaw(curYaw - backYaw);
            float sideSign;
            if (Math.abs(rel) >= 8.0f) {
                sideSign = rel >= 0f ? 1.0f : -1.0f;
            } else {
                int lineParity = Math.abs(dirX) > Math.abs(dirZ) ? src.getZ() : src.getX();
                sideSign = (lineParity & 1) == 0 ? 1.0f : -1.0f;
            }
            float angleDeg = skill == BridgeSkill.SWEATY ? 37.5f : 27.5f;
            return Rotation.normalizeYaw(backYaw + sideSign * angleDeg);
        }
        return backYaw;
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
            double targetY = src.getY() - 0.35 + verticalJitter;
            double dy = eyePos.y - targetY;
            return Rotation.clampPitch((float) Math.toDegrees(Math.atan2(dy, t)));
        }
        return Rotation.clampPitch(Math.max(73.5f, Math.min(82.0f, fallbackPitch)));
    }

    /**
     * Determines whether sneak should be held using signed edge progress ({@code <= 0.5} on src,
     * {@code > 0.5} cantilevered toward dest), allowing un-sneak sprint/walk bursts between blocks.
     */
    public static boolean shouldSneakAtProgress(
            BridgeSkill skill,
            double signedProgress,
            boolean lookingAtFace,
            long seed
    ) {
        if (skill == BridgeSkill.SAFE) {
            return true;
        }
        double jitter = ((((seed >>> 16) & 0x3FL) / 63.0) - 0.5) * 0.035;
        double edgeTrigger = (skill == BridgeSkill.SWEATY ? 0.41 : 0.35) + jitter;
        if (signedProgress >= edgeTrigger) {
            return true;
        }
        return lookingAtFace && signedProgress >= edgeTrigger - (skill == BridgeSkill.SWEATY ? 0.04 : 0.05);
    }

    /**
     * Gates bridge placement clicks to a natural ~10 CPS alternating pulse (instead of 20 CPS hold-spam)
     * with a single-tick safety override only if already cantilevered past the block edge.
     */
    public static boolean shouldPulseRightClick(
            boolean blockNeeded,
            boolean lookingAtGoal,
            boolean crouched,
            double signedProgress,
            long gameTick,
            long seed
    ) {
        if (!blockNeeded || !lookingAtGoal) {
            return false;
        }
        if (!crouched && signedProgress < 0.44) {
            return false;
        }
        boolean urgencyEdge = crouched && signedProgress >= 0.56;
        boolean pulseTick = ((gameTick + (seed & 1L)) & 1L) == 0L;
        return pulseTick || urgencyEdge;
    }

    /**
     * Kept for callers/tests querying unsigned edge distance from block face.
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
