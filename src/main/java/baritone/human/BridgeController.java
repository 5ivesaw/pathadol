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
import baritone.api.utils.RayTraceUtils;
import baritone.api.utils.Rotation;
import baritone.api.utils.RotationUtils;
import baritone.api.utils.VecUtils;
import baritone.api.utils.input.Input;
import baritone.pathing.movement.MovementHelper;
import baritone.pathing.movement.MovementState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.Locale;

/**
 * Humanized bridging and pillaring controller with complete technique arsenal:
 * {@code SAFE}, {@code NINJA}, {@code MOONWALK}, {@code BREEZILY},
 * {@code GODBRIDGE}, {@code TELLY}, and {@code JUMP}, plus {@code AUTO}
 * situational selection based on corridor geometry, headroom, and skill profile.
 */
public final class BridgeController {

    private BridgeController() {}

    /** Cached on live ticks so pure headless callers/unit tests never force {@link Baritone#settings()} initialization. */
    private static volatile String cachedBedwarsRuleset = "modern";

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
                case "sweaty", "god", "telly" -> SWEATY;
                case "medium", "fast", "ninja", "normal" -> MEDIUM;
                default -> MEDIUM;
            };
        }
    }

    public enum BridgeStyle {
        AUTO,
        SAFE,
        NINJA,
        MOONWALK,
        BREEZILY,
        GODBRIDGE,
        TELLY,
        JUMP;

        public static BridgeStyle fromSetting(String value) {
            if (value == null) {
                return AUTO;
            }
            return switch (value.toLowerCase(Locale.ROOT).trim()) {
                case "safe" -> SAFE;
                case "ninja", "speed", "speedbridge" -> NINJA;
                case "moonwalk" -> MOONWALK;
                case "breezily" -> BREEZILY;
                case "god", "godbridge" -> GODBRIDGE;
                case "telly" -> TELLY;
                case "jump", "jumpbridge" -> JUMP;
                default -> AUTO;
            };
        }
    }

    /**
     * Executes one tick of humanized bridging from {@code src} toward {@code dest}.
     */
    public static MovementState stepBackBridge(
            IPlayerContext ctx,
            MovementState state,
            BetterBlockPos src,
            BetterBlockPos dest
    ) {
        return stepBackBridge(ctx, state, src, dest, null, 1, 4);
    }

    /**
     * Executes one tick of fast back-turned bridging along an optional corridor yaw hint.
     */
    public static MovementState stepBackBridge(
            IPlayerContext ctx,
            MovementState state,
            BetterBlockPos src,
            BetterBlockPos dest,
            Float corridorYawHint
    ) {
        return stepBackBridge(ctx, state, src, dest, corridorYawHint, 1, 4);
    }

    /**
     * Executes one tick of situational or forced bridging along a corridor with run-length &amp; headroom info.
     */
    public static MovementState stepBackBridge(
            IPlayerContext ctx,
            MovementState state,
            BetterBlockPos src,
            BetterBlockPos dest,
            Float corridorYawHint,
            int corridorSteps,
            int headroomBlocks
    ) {
        BridgeSkill skill = resolveEffectiveSkill();
        double dirX = dest.getX() - src.getX();
        double dirZ = dest.getZ() - src.getZ();
        double faceX = (dest.getX() + src.getX() + 1.0D) * 0.5D;
        double faceY = (dest.getY() + src.getY() - 1.0D) * 0.5D;
        double faceZ = (dest.getZ() + src.getZ() + 1.0D) * 0.5D;

        long seed = dest.asLong() ^ 0x517CC1B727220A95L;
        double verticalJitter = (((seed & 0xFFL) / 255.0) - 0.5) * 0.08;
        double lateralJitter = ((((seed >>> 8) & 0xFFL) / 255.0) - 0.5) * 0.06;

        Vec3 aimPoint = new Vec3(
                faceX + (-dirZ) * lateralJitter,
                faceY + verticalJitter,
                faceZ + dirX * lateralJitter
        );

        BlockPos goalLook = src.below();
        Vec3 bridgeEyePos = effectiveBridgeEyePos(ctx);
        float forwardYaw = PathSmoother.computeYawTo(src.getX() + 0.5, src.getZ() + 0.5, dest.getX() + 0.5, dest.getZ() + 0.5);
        float backYaw = Rotation.normalizeYaw(forwardYaw + 180.0f);
        float curYaw = ctx.playerRotations().getYaw();
        boolean diagonalCorridor = isDiagonalCorridor(corridorYawHint, backYaw);
        double healthFrac = ctx.player() != null
                ? ctx.player().getHealth() / Math.max(1.0f, ctx.player().getMaxHealth())
                : 1.0;

        BridgeStyle style = resolveEffectiveStyle(
                skill,
                diagonalCorridor,
                corridorSteps,
                headroomBlocks,
                false,
                healthFrac,
                src
        );

        Vec3 pos = ctx.player().position();
        double signedProgress = computeSignedProgress(pos, src, dirX, dirZ);
        Vec3 overhangEyePos = projectOverhangEyePos(bridgeEyePos, src, dirX, dirZ, signedProgress);
        Rotation backToFace = RotationUtils.calcRotationFromVec3d(overhangEyePos, aimPoint, ctx.playerRotations());
        long gameTick = ctx.world() != null ? ctx.world().getGameTime() : 0L;
        boolean onGround = ctx.player().onGround();
        Vec3 vel = ctx.player().getDeltaMovement();
        boolean lookingAtGoal = isLookingAtBridgeFace(ctx, goalLook, dest.below(), dirX, dirZ);
        boolean blockNeeded = !MovementHelper.canWalkOn(ctx, dest.below());
        boolean holdingBlock = ctx.player() != null
                && ctx.player().getMainHandItem().getItem() instanceof net.minecraft.world.item.BlockItem;

        // Telly bridge phase: only sprint-jump forward when current runway is solid and block is held
        if (style == BridgeStyle.TELLY && headroomBlocks >= 4 && !blockNeeded && holdingBlock) {
            boolean canLaunchForward = onGround && signedProgress < 0.34
                    && MovementHelper.canWalkOn(ctx, ctx.playerFeet().below());
            if (canLaunchForward) {
                state.setTarget(new MovementState.MovementTarget(new Rotation(forwardYaw, 12.0f), true));
                state.setInput(Input.MOVE_FORWARD, true);
                state.setInput(Input.SPRINT, true);
                state.setInput(Input.JUMP, true);
                state.setInput(Input.SNEAK, false);
                return state;
            }
        }

        float sideSign = chooseSteadySideSign(backYaw, curYaw, dirX, dirZ, src);
        float camYaw = chooseStyleCameraYaw(
                style, skill, corridorYawHint, backYaw, curYaw, src, dirX, dirZ, sideSign, gameTick
        );
        // Always converge camYaw onto src.below()'s placement face interior whenever preferredYaw would miss
        // (removing !lookingAtGoal toggle eliminates 132deg<->72deg yaw oscillation and blocked clicks).
        camYaw = clampYawToBridgeFace(
                overhangEyePos, camYaw, backYaw, src, faceX, faceY + verticalJitter, faceZ, dirX, dirZ,
                blockNeeded
        );
        float camPitch = resolveBridgeFacePitch(
                overhangEyePos, camYaw, src, faceX, faceZ, dirX, dirZ, verticalJitter, backToFace.getPitch()
        );

        state.setTarget(new MovementState.MovementTarget(new Rotation(camYaw, camPitch), true));

        float relMove = Rotation.normalizeYaw(forwardYaw - curYaw);
        boolean turnedBackward = Math.abs(relMove) >= 112.0f;
        double edgeCatchProgress = isOldBedwarsPhysics() ? 0.48 : 0.46;
        // Cantilevered players (signedProgress >= 0.50) have playerFeet() inside dest (air below) even while
        // safely supported on ground by src.below() via vanilla crouch edge-protection.
        boolean standingSolid = MovementHelper.canWalkOn(ctx, ctx.playerFeet().below());
        boolean supportedOnBridge = onGround && (standingSolid || MovementHelper.canWalkOn(ctx, src.below()));
        boolean shouldSneak = resolveBridgeSneak(
                style, skill, turnedBackward, holdingBlock, blockNeeded, standingSolid, onGround,
                lookingAtGoal, signedProgress, edgeCatchProgress, vel, dirX, dirZ, seed
        );
        boolean edgeBlockedPause = shouldPauseBackForEdge(
                blockNeeded, lookingAtGoal, holdingBlock, supportedOnBridge, shouldSneak, signedProgress, edgeCatchProgress
        );
        state.setInput(Input.SNEAK, shouldSneak);

        if (!holdingBlock && blockNeeded) {
            // Never walk off a void ledge holding shears/sword — freeze on edge until block hotbar swap settles
            state.setInput(Input.MOVE_BACK, false);
            state.setInput(Input.MOVE_FORWARD, false);
            state.setInput(Input.SPRINT, false);
            return state;
        }

        if (!turnedBackward) {
            if (Math.abs(relMove) < 68.0f) {
                if (!edgeBlockedPause) {
                    state.setInput(Input.MOVE_FORWARD, true);
                }
            } else if (!edgeBlockedPause) {
                // Smooth pivot onto backward corridor instead of zero-key crouch-freezing
                state.setInput(relMove > 0.0f ? Input.MOVE_RIGHT : Input.MOVE_LEFT, true);
                if (Math.abs(relMove) >= 88.0f) {
                    state.setInput(Input.MOVE_BACK, true);
                }
            }
        } else {
            applyStyleStrafeKeys(state, style, relMove, diagonalCorridor, sideSign, gameTick);
            if (!applyBridgeLateralCentering(state, pos, src, dirX, dirZ, curYaw, blockNeeded)) {
                if (edgeBlockedPause && blockNeeded) {
                    state.setInput(Input.MOVE_BACK, false);
                    if (!lookingAtGoal) {
                        state.setInput(Input.MOVE_LEFT, false);
                        state.setInput(Input.MOVE_RIGHT, false);
                    }
                } else if (blockNeeded && !lookingAtGoal && supportedOnBridge && shouldSneak && signedProgress >= 0.44) {
                    // While safely crouched with blocks in hand, keep backing gently onto the overhang so the side face is exposed
                    state.setInput(Input.MOVE_BACK, true);
                }
            }
            if (style == BridgeStyle.JUMP
                    && onGround
                    && headroomBlocks >= 3
                    && signedProgress >= 0.34
                    && signedProgress <= 0.54
                    && !shouldSneak
                    && (!blockNeeded || lookingAtGoal)) {
                state.setInput(Input.JUMP, true);
            }
        }

        if (shouldPulseRightClickForStyle(style, blockNeeded, lookingAtGoal, shouldSneak || ctx.player().isCrouching(), onGround, signedProgress, gameTick, seed)) {
            return state.setInput(Input.CLICK_RIGHT, true);
        }
        return state;
    }

    private static boolean isLookingAtBridgeFace(
            IPlayerContext ctx, BlockPos goalLook, BlockPos placeTarget, double dirX, double dirZ
    ) {
        Direction expectedFace = Math.abs(dirX) > 0.5
                ? (dirX > 0 ? Direction.EAST : Direction.WEST)
                : (Math.abs(dirZ) > 0.5 ? (dirZ > 0 ? Direction.SOUTH : Direction.NORTH) : null);
        HitResult liveHit = ctx.objectMouseOver();
        return liveHit instanceof BlockHitResult bhr
                && bhr.getType() == HitResult.Type.BLOCK
                && ((bhr.getBlockPos().equals(goalLook) && (expectedFace == null || bhr.getDirection() == expectedFace))
                    || bhr.getBlockPos().relative(bhr.getDirection()).equals(placeTarget));
    }

    private static boolean resolveBridgeSneak(
            BridgeStyle style, BridgeSkill skill, boolean turnedBackward, boolean holdingBlock,
            boolean blockNeeded, boolean standingSolid, boolean onGround, boolean lookingAtGoal,
            double signedProgress, double edgeCatchProgress, Vec3 vel, double dirX, double dirZ, long seed
    ) {
        boolean emergencyEdgeCatch = !turnedBackward
                || !holdingBlock
                || (blockNeeded && signedProgress >= edgeCatchProgress && (onGround || vel.y < -0.04));
        if (emergencyEdgeCatch) {
            if (!holdingBlock && blockNeeded) {
                return !standingSolid || signedProgress >= 0.24;
            }
            if (!turnedBackward) {
                return !standingSolid || (blockNeeded && (signedProgress >= 0.26 || skill == BridgeSkill.SAFE));
            }
            return skill == BridgeSkill.SAFE || signedProgress >= 0.24 || !standingSolid;
        }
        double edgeVel = Math.max(0.0, vel.x * dirX + vel.z * dirZ);
        if (style == BridgeStyle.NINJA) {
            return BridgePhysicsModel.shouldEngagePredictiveSneak(
                    skill, signedProgress, edgeVel, lookingAtGoal, blockNeeded, seed
            );
        }
        return shouldSneakForStyle(style, skill, signedProgress, lookingAtGoal, onGround, blockNeeded, seed);
    }

    private static boolean applyBridgeLateralCentering(
            MovementState state, Vec3 pos, BetterBlockPos src, double dirX, double dirZ, float curYaw, boolean blockNeeded
    ) {
        double latErr = Math.abs(dirX) > 0.5
                ? (pos.z - (src.getZ() + 0.5D))
                : (pos.x - (src.getX() + 0.5D));
        if (!blockNeeded || Math.abs(latErr) <= 0.30) {
            return false;
        }
        double corrX = Math.abs(dirX) > 0.5 ? 0.0 : -Math.signum(latErr);
        double corrZ = Math.abs(dirX) > 0.5 ? -Math.signum(latErr) : 0.0;
        double curRad = Math.toRadians(curYaw);
        double fwdX = -Math.sin(curRad);
        double fwdZ = Math.cos(curRad);
        double rightX = -fwdZ;
        double rightZ = fwdX;
        double latDotRight = corrX * rightX + corrZ * rightZ;
        state.setInput(Input.SNEAK, true);
        state.setInput(Input.MOVE_LEFT, latDotRight < -0.15);
        state.setInput(Input.MOVE_RIGHT, latDotRight > 0.15);
        if (Math.abs(latErr) > 0.42) {
            double latDotFwd = corrX * fwdX + corrZ * fwdZ;
            state.setInput(Input.MOVE_FORWARD, latDotFwd > 0.25);
            state.setInput(Input.MOVE_BACK, latDotFwd < -0.25);
        }
        return true;
    }

    /**
     * Checks whether an already-solid step ({@code dest.below()} walkable) should remain turned
     * backward to chain straight into an upcoming bridge gap, instead of creeping backward across
     * pre-built bridges or finished island approaches.
     */
    public static boolean shouldStayInBackwardBridge(
            IPlayerContext ctx,
            BetterBlockPos src,
            BetterBlockPos dest,
            boolean wasAlwaysThere
    ) {
        if (ctx == null || src == null || dest == null || ctx.world() == null) {
            return false;
        }
        int stepX = Integer.compare(dest.getX(), src.getX());
        int stepZ = Integer.compare(dest.getZ(), src.getZ());
        if (stepX == 0 && stepZ == 0) {
            return false;
        }
        BlockPos next1 = dest.below().offset(stepX, 0, stepZ);
        if (!MovementHelper.canWalkOn(ctx, next1)) {
            if (wasAlwaysThere) {
                // On a pre-built diagonal bridge the path turns 90 deg onto solid perpendicular blocks
                BlockPos leftTurn = dest.below().offset(-stepZ, 0, stepX);
                BlockPos rightTurn = dest.below().offset(stepZ, 0, -stepX);
                if (MovementHelper.canWalkOn(ctx, leftTurn) || MovementHelper.canWalkOn(ctx, rightTurn)) {
                    return false;
                }
            }
            return true;
        }
        if (wasAlwaysThere) {
            return false;
        }
        BlockPos next2 = next1.offset(stepX, 0, stepZ);
        return !MovementHelper.canWalkOn(ctx, next2);
    }

    /**
     * Continues backing briskly (or sprinting forward for Telly chains) onto {@code dest} immediately after
     * placing {@code dest.below()} so the camera never whips needlessly for leftover ticks.
     */
    public static MovementState stepContinueBack(
            IPlayerContext ctx,
            MovementState state,
            BetterBlockPos src,
            BetterBlockPos dest,
            Float corridorYawHint
    ) {
        return stepContinueBack(ctx, state, src, dest, corridorYawHint, 1, 4);
    }

    public static MovementState stepContinueBack(
            IPlayerContext ctx,
            MovementState state,
            BetterBlockPos src,
            BetterBlockPos dest,
            Float corridorYawHint,
            int corridorSteps,
            int headroomBlocks
    ) {
        BridgeSkill skill = resolveEffectiveSkill();
        Vec3 pos = ctx.player().position();
        double dirX = dest.getX() - src.getX();
        double dirZ = dest.getZ() - src.getZ();
        double signedProgress = computeSignedProgress(pos, src, dirX, dirZ);

        float curYaw = ctx.playerRotations().getYaw();
        float forwardYaw = PathSmoother.computeYawTo(src.getX() + 0.5, src.getZ() + 0.5, dest.getX() + 0.5, dest.getZ() + 0.5);
        float backYaw = Rotation.normalizeYaw(forwardYaw + 180.0f);
        boolean diagonalCorridor = isDiagonalCorridor(corridorYawHint, backYaw);
        double healthFrac = ctx.player() != null
                ? ctx.player().getHealth() / Math.max(1.0f, ctx.player().getMaxHealth())
                : 1.0;

        BridgeStyle style = resolveEffectiveStyle(skill, diagonalCorridor, corridorSteps, headroomBlocks, false, healthFrac, src);
        long gameTick = ctx.world() != null ? ctx.world().getGameTime() : 0L;

        if (style == BridgeStyle.TELLY && headroomBlocks >= 4 && Math.abs(Rotation.normalizeYaw(forwardYaw - curYaw)) < 68.0f) {
            state.setTarget(new MovementState.MovementTarget(new Rotation(forwardYaw, 10.0f), false));
            state.setInput(Input.SNEAK, false);
            state.setInput(Input.MOVE_FORWARD, true);
            state.setInput(Input.SPRINT, true);
            if (ctx.player().onGround() && signedProgress < 0.35) {
                state.setInput(Input.JUMP, true);
            }
            return state;
        }

        float sideSign = chooseSteadySideSign(backYaw, curYaw, dirX, dirZ, src);
        float camYaw = chooseStyleCameraYaw(
                style, skill, corridorYawHint, backYaw, curYaw, src, dirX, dirZ, sideSign, gameTick
        );
        float camPitch = Math.max(79.2f, Math.min(82.8f, ctx.playerRotations().getPitch()));

        state.setTarget(new MovementState.MovementTarget(new Rotation(camYaw, camPitch), false));
        // Un-sneak briskly across placed dest.below() on straight runs, but keep sneak across open diagonal inside corners
        boolean standingSafe = MovementHelper.canWalkOn(ctx, ctx.playerFeet().below());
        int stepX = Integer.compare(dest.getX(), src.getX());
        int stepZ = Integer.compare(dest.getZ(), src.getZ());
        boolean nextNeedsBlock = (stepX != 0 || stepZ != 0)
                && !MovementHelper.canWalkOn(ctx, dest.below().offset(stepX, 0, stepZ));
        boolean openDiagonalCorner = diagonalCorridor
                && signedProgress <= 0.82
                && (!MovementHelper.canWalkOn(ctx, src.below().offset(stepZ, 0, stepX))
                || !MovementHelper.canWalkOn(ctx, src.below().offset(-stepZ, 0, -stepX)));
        boolean preemptiveNextEdge = nextNeedsBlock && signedProgress >= 0.88;
        state.setInput(Input.SNEAK, !standingSafe || openDiagonalCorner || preemptiveNextEdge);

        float relMove = Rotation.normalizeYaw(forwardYaw - curYaw);
        applyStyleStrafeKeys(state, style, relMove, diagonalCorridor, sideSign, gameTick);

        // Classic Godbridge periodic vanilla micro-hop on every 7th block keeps stride centered
        if (style == BridgeStyle.GODBRIDGE
                && ctx.player().onGround()
                && headroomBlocks >= 3
                && signedProgress < 0.24
                && ((Math.abs(dest.getX() + dest.getZ()) % 7) == 0)) {
            state.setInput(Input.JUMP, true);
        }
        return state;
    }

    /**
     * Resolves the active bridging technique using {@code humanBridgeStyle} override or {@code AUTO} situational heuristics.
     */
    public static BridgeStyle selectAutoBridgeStyle(
            BridgeSkill skill,
            boolean diagonalCorridor,
            int straightRunLen,
            int headroomBlocks,
            boolean urgentThreat,
            double healthFrac
    ) {
        if (skill == BridgeSkill.SAFE || (healthFrac < 0.30 && !urgentThreat)) {
            return BridgeStyle.SAFE;
        }
        // Fast speed/ninja bridging across all runs keeps edge-sneak deterministic without un-sneaked void falls
        return BridgeStyle.NINJA;
    }

    private static BridgeStyle resolveEffectiveStyle(
            BridgeSkill skill,
            boolean diagonalCorridor,
            int corridorSteps,
            int headroomBlocks,
            boolean urgentThreat,
            double healthFrac,
            BetterBlockPos src
    ) {
        String rawStyle = Baritone.settings().humanBridgeStyle.value;
        BridgeStyle requested = BridgeStyle.fromSetting(rawStyle);
        if (requested != BridgeStyle.AUTO) {
            if (requested == BridgeStyle.TELLY && (headroomBlocks < 4 || diagonalCorridor)) {
                return BridgeStyle.NINJA;
            }
            if ((requested == BridgeStyle.MOONWALK || requested == BridgeStyle.BREEZILY || requested == BridgeStyle.GODBRIDGE)
                    && diagonalCorridor) {
                return BridgeStyle.NINJA;
            }
            return requested;
        }
        return selectAutoBridgeStyle(skill, diagonalCorridor, corridorSteps, headroomBlocks, urgentThreat, healthFrac);
    }

    private static float chooseSteadySideSign(float backYaw, float curYaw, double dirX, double dirZ, BetterBlockPos src) {
        float rel = Rotation.normalizeYaw(curYaw - backYaw);
        if (Math.abs(rel) >= 12.0f) {
            return rel >= 0.0f ? 1.0f : -1.0f;
        }
        return (Math.abs(dirX) >= Math.abs(dirZ) ? dirX >= 0.0 : dirZ >= 0.0) ? 1.0f : -1.0f;
    }

    private static float computeDeterministicSideSign(double dirX, double dirZ, BetterBlockPos src) {
        return (Math.abs(dirX) >= Math.abs(dirZ) ? dirX >= 0.0 : dirZ >= 0.0) ? 1.0f : -1.0f;
    }

    private static float chooseStyleCameraYaw(
            BridgeStyle style,
            BridgeSkill skill,
            Float corridorYawHint,
            float backYaw,
            float curYaw,
            BetterBlockPos src,
            double dirX,
            double dirZ,
            float deterministicSideSign,
            long gameTick
    ) {
        if (corridorYawHint != null) {
            float steadyBack = Rotation.normalizeYaw(corridorYawHint + 180.0f);
            float dev = Math.abs(Rotation.normalizeYaw(steadyBack - backYaw));
            if (dev >= 12.0f && dev <= 68.0f) {
                return steadyBack;
            }
        }
        return switch (style) {
            case SAFE -> backYaw;
            case BREEZILY -> {
                float microWeave = (((gameTick / 3L) & 1L) == 0L ? 1.5f : -1.5f);
                yield Rotation.normalizeYaw(backYaw + microWeave);
            }
            case MOONWALK, GODBRIDGE -> Rotation.normalizeYaw(backYaw + deterministicSideSign * 44.5f);
            case TELLY -> Rotation.normalizeYaw(backYaw + deterministicSideSign * 18.0f);
            case JUMP, NINJA, AUTO -> {
                float angleDeg = skill == BridgeSkill.SWEATY ? 42.0f : 36.0f;
                yield Rotation.normalizeYaw(backYaw + deterministicSideSign * angleDeg);
            }
        };
    }

    private static void applyStyleStrafeKeys(
            MovementState state,
            BridgeStyle style,
            float relMove,
            boolean diagonalCorridor,
            float sideSign,
            long gameTick
    ) {
        if (Math.abs(relMove) >= 104.0f) {
            state.setInput(Input.MOVE_BACK, true);
        }
        if (style == BridgeStyle.BREEZILY && !diagonalCorridor) {
            boolean goRight = ((gameTick / 3L) & 1L) == 0L;
            state.setInput(goRight ? Input.MOVE_RIGHT : Input.MOVE_LEFT, true);
            return;
        }
        if (style == BridgeStyle.MOONWALK && !diagonalCorridor) {
            // Rhythmic 2-of-4 tick counter-strafe pulse aligns diagonal 45-deg backwards stride onto the centerline
            if ((gameTick % 4L) < 2L) {
                state.setInput(sideSign >= 0 ? Input.MOVE_LEFT : Input.MOVE_RIGHT, true);
            }
            float driftFromBack = 180.0f - Math.abs(relMove);
            if (driftFromBack >= 52.0f) {
                state.setInput(relMove > 0.0f ? Input.MOVE_RIGHT : Input.MOVE_LEFT, true);
            }
            return;
        }
        // On a steady 45-deg diagonal corridor keep threshold wider to avoid L-stair wobble; straight bridges stay centered
        float strafeThreshold = diagonalCorridor ? 24.0f : 11.5f;
        float driftFromBack = 180.0f - Math.abs(relMove);
        if (driftFromBack >= strafeThreshold && Math.abs(relMove) >= 18.0f) {
            if (relMove > 0.0f) {
                state.setInput(Input.MOVE_RIGHT, true);
            } else {
                state.setInput(Input.MOVE_LEFT, true);
            }
        }
    }

    static boolean shouldSneakForStyle(
            BridgeStyle style,
            BridgeSkill skill,
            double signedProgress,
            boolean lookingAtGoal,
            boolean onGround,
            boolean blockNeeded,
            long seed
    ) {
        if (style == BridgeStyle.SAFE) {
            return blockNeeded || !onGround;
        }
        if (style == BridgeStyle.GODBRIDGE || style == BridgeStyle.BREEZILY || style == BridgeStyle.MOONWALK) {
            // No planned edge sneak — only emergency safety catch if dangerously un-placed at extreme cantilever
            return blockNeeded && !onGround && signedProgress >= 0.56;
        }
        if (style == BridgeStyle.TELLY || style == BridgeStyle.JUMP) {
            // Do not crouch while airborne/launching; only edge catch if grounded right at drop-off without block
            return blockNeeded && onGround && signedProgress >= 0.55;
        }
        return shouldSneakAtProgress(skill, signedProgress, lookingAtGoal, seed);
    }

    /**
     * Only pauses backward movement at a bridge edge when un-crouched or missing blocks;
     * when crouched on solid footing with blocks in hand, vanilla sneak edge-protection
     * carries the player smoothly at full sneak speed without {@code <0.03 b/t} stalls.
     */
    public static boolean shouldPauseBackForEdge(
            boolean blockNeeded,
            boolean lookingAtGoal,
            boolean holdingBlock,
            boolean standingSolid,
            boolean shouldSneak,
            double signedProgress,
            double edgeCatchProgress
    ) {
        if (!blockNeeded) {
            return false;
        }
        if (!holdingBlock && signedProgress >= 0.32) {
            return true;
        }
        if (shouldSneak && standingSolid && holdingBlock) {
            return false;
        }
        return !lookingAtGoal && signedProgress >= (edgeCatchProgress - 0.04);
    }

    static boolean shouldPulseRightClickForStyle(
            BridgeStyle style,
            boolean blockNeeded,
            boolean lookingAtGoal,
            boolean crouched,
            boolean onGround,
            double signedProgress,
            long gameTick,
            long seed
    ) {
        if (!blockNeeded || !lookingAtGoal) {
            return false;
        }
        if (style == BridgeStyle.GODBRIDGE || style == BridgeStyle.BREEZILY || style == BridgeStyle.MOONWALK
                || style == BridgeStyle.TELLY || style == BridgeStyle.JUMP) {
            if (!onGround || signedProgress >= 0.36) {
                return true;
            }
        }
        return shouldPulseRightClick(blockNeeded, lookingAtGoal, crouched, signedProgress, gameTick, seed);
    }

    static boolean isDiagonalCorridor(Float corridorYawHint, float backYaw) {
        if (corridorYawHint == null) {
            return false;
        }
        float steadyBack = Rotation.normalizeYaw(corridorYawHint + 180.0f);
        float dev = Math.abs(Rotation.normalizeYaw(steadyBack - backYaw));
        return dev >= 18.0f && dev <= 68.0f;
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
            if (dev >= 12.0f && dev <= 68.0f) {
                return steadyBack;
            }
        }
        if (skill == BridgeSkill.SWEATY || skill == BridgeSkill.MEDIUM) {
            float rel = Rotation.normalizeYaw(curYaw - backYaw);
            float sideSign = Math.abs(rel) >= 18.0f
                    ? (rel >= 0f ? 1.0f : -1.0f)
                    : computeDeterministicSideSign(dirX, dirZ, src);
            float angleDeg = skill == BridgeSkill.SWEATY ? 37.5f : 27.5f;
            return Rotation.normalizeYaw(backYaw + sideSign * angleDeg);
        }
        return backYaw;
    }

    /**
     * Projects the eye position to at least {@code signedProgress = 0.56} past {@code src}'s edge
     * along {@code (dirX, dirZ)} so face-aim vectors never invert 180 degrees forward while
     * {@code signedProgress < 0.50}.
     */
    static Vec3 projectOverhangEyePos(
            Vec3 eyePos,
            BetterBlockPos src,
            double dirX,
            double dirZ,
            double signedProgress
    ) {
        double minOverhang = 0.56;
        if (signedProgress >= minOverhang) {
            return eyePos;
        }
        double push = minOverhang - signedProgress;
        return new Vec3(eyePos.x + dirX * push, eyePos.y, eyePos.z + dirZ * push);
    }

    /**
     * Keeps {@code preferredYaw} steady whenever its ray intersects the safe interior {@code [0.14, 0.86]}
     * of {@code src.below()}'s placement face; only nudges onto {@code [0.22, 0.78]} of the face when
     * lateral drift or corner alignment would otherwise miss the placement face.
     */
    static float clampYawToBridgeFace(
            Vec3 overhangEyePos,
            float preferredYaw,
            float backYaw,
            BetterBlockPos src,
            double faceX,
            double targetY,
            double faceZ,
            double dirX,
            double dirZ,
            boolean forceConverge
    ) {
        double rad = Math.toRadians(preferredYaw);
        double lx = -Math.sin(rad);
        double lz = Math.cos(rad);
        double margin = forceConverge ? 0.10 : 0.06;
        if (Math.abs(dirX) > 0.5) {
            if (Math.abs(lx) > 0.15) {
                double t = (faceX - overhangEyePos.x) / lx;
                double hitZ = overhangEyePos.z + t * lz;
                if (t > 0.03 && t < 1.80 && hitZ >= src.getZ() + margin && hitZ <= src.getZ() + 1.0 - margin) {
                    return preferredYaw;
                }
                if (forceConverge) {
                    double clampedZ = Math.max(src.getZ() + 0.14, Math.min(src.getZ() + 0.86, t > 0.0 ? hitZ : overhangEyePos.z));
                    float nudged = PathSmoother.computeYawTo(overhangEyePos.x, overhangEyePos.z, faceX, clampedZ);
                    float relBack = Rotation.normalizeYaw(nudged - backYaw);
                    return Rotation.normalizeYaw(backYaw + Math.max(-62.0f, Math.min(62.0f, relBack)));
                }
            }
        } else if (Math.abs(dirZ) > 0.5) {
            if (Math.abs(lz) > 0.15) {
                double t = (faceZ - overhangEyePos.z) / lz;
                double hitX = overhangEyePos.x + t * lx;
                if (t > 0.03 && t < 1.80 && hitX >= src.getX() + margin && hitX <= src.getX() + 1.0 - margin) {
                    return preferredYaw;
                }
                if (forceConverge) {
                    double clampedX = Math.max(src.getX() + 0.14, Math.min(src.getX() + 0.86, t > 0.0 ? hitX : overhangEyePos.x));
                    float nudged = PathSmoother.computeYawTo(overhangEyePos.x, overhangEyePos.z, clampedX, faceZ);
                    float relBack = Rotation.normalizeYaw(nudged - backYaw);
                    return Rotation.normalizeYaw(backYaw + Math.max(-62.0f, Math.min(62.0f, relBack)));
                }
            }
        }
        return preferredYaw;
    }

    /**
     * Computes exact vertical aim pitch where the view ray at {@code camYaw} intersects
     * the exposed vertical face plane of {@code src.below()}.
     */
    public static float resolveBridgeFacePitch(
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
            minOther = src.getZ() + 0.01;
            maxOther = src.getZ() + 0.99;
        } else if (Math.abs(dirZ) > 0.5 && Math.abs(lz) > 0.15) {
            t = (faceZ - eyePos.z) / lz;
            hitOther = eyePos.x + t * lx;
            minOther = src.getX() + 0.01;
            maxOther = src.getX() + 0.99;
        }
        if (t > 0.05 && t < 1.8 && hitOther >= minOther && hitOther <= maxOther) {
            double targetY = src.getY() - 0.42 + verticalJitter;
            double dy = eyePos.y - targetY;
            float computedPitch = (float) Math.toDegrees(Math.atan2(dy, t));
            return Rotation.clampPitch(Math.max(74.5f, Math.min(85.2f, computedPitch)));
        }
        return Rotation.clampPitch(Math.max(74.5f, Math.min(83.5f, fallbackPitch)));
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
        double jitter = ((((seed >>> 16) & 0x3FL) / 63.0) - 0.5) * 0.025;
        double edgeTrigger = (skill == BridgeSkill.SWEATY ? 0.36 : 0.32) + jitter;
        if (signedProgress >= edgeTrigger) {
            return true;
        }
        return lookingAtFace && signedProgress >= edgeTrigger - 0.04;
    }

    /**
     * Gates bridge placement clicks cleanly at the cantilevered edge without dropping valid placement ticks.
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
        if (!crouched && signedProgress < 0.42) {
            return false;
        }
        // Once crouched & raycast hits the exposed side face at the cantilevered edge (>= 0.48), lock clicks cleanly
        if (crouched && signedProgress >= 0.48) {
            return true;
        }
        return ((gameTick + (seed & 1L)) & 1L) == 0L;
    }

    public static double computeCrouchEdgeThreshold(BridgeSkill skill, String bedwarsRuleset) {
        if ("old".equalsIgnoreCase(bedwarsRuleset)) {
            return 0.30;
        }
        return skill == BridgeSkill.SWEATY ? 0.22 : 0.27;
    }

    private static boolean isOldBedwarsPhysics() {
        String ruleset = Baritone.settings().bedwarsRuleset.value;
        cachedBedwarsRuleset = ruleset != null ? ruleset : "modern";
        return "old".equalsIgnoreCase(cachedBedwarsRuleset);
    }

    private static Vec3 effectiveBridgeEyePos(IPlayerContext ctx) {
        // Always match the live client camera eye position used by mc.pick() (1.27f crouch on MC 26.3)
        // so computed bridge face pitches land squarely on the exposed vertical block face.
        return ctx.playerHead();
    }

    /**
     * Kept for callers/tests querying unsigned edge distance from block face.
     */
    public static boolean shouldSneakAtEdge(BridgeSkill skill, double distFromEdge, boolean lookingAtFace, long seed) {
        return shouldSneakAtEdge(skill, distFromEdge, lookingAtFace, seed, cachedBedwarsRuleset);
    }

    public static boolean shouldSneakAtEdge(BridgeSkill skill, double distFromEdge, boolean lookingAtFace, long seed, String bedwarsRuleset) {
        if (skill == BridgeSkill.SAFE) {
            return true;
        }
        double jitter = ((((seed >>> 16) & 0x3FL) / 63.0) - 0.5) * 0.04;
        double edgeThreshold = computeCrouchEdgeThreshold(skill, bedwarsRuleset) + jitter;
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
