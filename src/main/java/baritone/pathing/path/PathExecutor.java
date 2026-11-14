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

package baritone.pathing.path;

import baritone.Baritone;
import baritone.api.pathing.calc.IPath;
import baritone.api.pathing.movement.ActionCosts;
import baritone.api.pathing.movement.IMovement;
import baritone.api.pathing.movement.MovementStatus;
import baritone.api.pathing.path.IPathExecutor;
import baritone.api.utils.*;
import baritone.api.utils.input.Input;
import baritone.behavior.PathingBehavior;
import baritone.human.GazeDirector;
import baritone.human.PathSmoother;
import baritone.pathing.calc.AbstractNodeCostSearch;
import baritone.pathing.movement.CalculationContext;
import baritone.pathing.movement.Movement;
import baritone.pathing.movement.MovementHelper;
import baritone.pathing.movement.movements.*;
import baritone.utils.BlockStateInterface;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.world.phys.Vec3;
import java.util.*;

import static baritone.api.pathing.movement.MovementStatus.*;

/**
 * Behavior to execute a precomputed path
 *
 * @author leijurv
 */
public class PathExecutor implements IPathExecutor, Helper {

    private static final double MAX_MAX_DIST_FROM_PATH = 3;
    private static final double MAX_DIST_FROM_PATH = 2;

    /**
     * Default value is equal to 10 seconds. It's find to decrease it, but it must be at least 5.5s (110 ticks).
     * For more information, see issue #102.
     *
     * @see <a href="https://github.com/cabaletta/baritone/issues/102">Issue #102</a>
     * @see <a href="https://i.imgur.com/5s5GLnI.png">Anime</a>
     */
    private static final double MAX_TICKS_AWAY = 200;

    private final IPath path;
    private int pathPosition;
    private int ticksAway;
    private int ticksOnCurrent;
    private int wallPinTicks;
    private Double currentMovementOriginalCostEstimate;
    private Integer costEstimateIndex;
    private boolean failed;
    private boolean recalcBP = true;
    private HashSet<BlockPos> toBreak = new HashSet<>();
    private HashSet<BlockPos> toPlace = new HashSet<>();
    private HashSet<BlockPos> toWalkInto = new HashSet<>();

    private final PathingBehavior behavior;
    private final IPlayerContext ctx;

    private boolean sprintNextTick;
    private final PathSmoother pathSmoother = new PathSmoother();
    private final GazeDirector gazeDirector = new GazeDirector();

    public PathExecutor(PathingBehavior behavior, IPath path) {
        this.behavior = behavior;
        this.ctx = behavior.ctx;
        this.path = path;
        this.pathPosition = 0;
    }

    public boolean isInSmoothedCorridor(BetterBlockPos pos) {
        return pathSmoother.isInSmoothedCorridor(pos);
    }

    /**
     * Tick this executor
     *
     * @return True if a movement just finished (and the player is therefore in a "stable" state, like,
     * not sneaking out over lava), false otherwise
     */
    public boolean onTick() {
        return onTick(0);
    }

    private boolean onTick(int recursionDepth) {
        if (recursionDepth >= 16) {
            logDebug("PathExecutor onTick recursion limit reached at pathPosition=" + pathPosition);
            return pathPosition < path.length() && path.movements().get(pathPosition).safeToCancel();
        }
        if (pathPosition == path.length() - 1) {
            pathPosition++;
        }
        if (pathPosition >= path.length()) {
            return true; // stop bugging me, I'm done
        }
        Movement movement = (Movement) path.movements().get(pathPosition);
        BetterBlockPos whereAmI = ctx.playerFeet();
        if (!movement.getValidPositions().contains(whereAmI)) {
            for (int i = 0; i < pathPosition && i < path.length(); i++) {//this happens for example when you lag out and get teleported back a couple blocks
                if (((Movement) path.movements().get(i)).getValidPositions().contains(whereAmI)) {
                    int previousPos = pathPosition;
                    pathPosition = i;
                    for (int j = pathPosition; j <= previousPos; j++) {
                        path.movements().get(j).reset();
                    }
                    onChangeInPathPosition();
                    onTick(recursionDepth + 1);
                    return false;
                }
            }
            for (int i = pathPosition + 3; i < path.length() - 1; i++) { //dont check pathPosition+1. the movement tells us when it's done (e.g. sneak placing)
                // also don't check pathPosition+2 because reasons
                if (((Movement) path.movements().get(i)).getValidPositions().contains(whereAmI)) {
                    if (i - pathPosition > 2) {
                        logDebug("Skipping forward " + (i - pathPosition) + " steps, to " + i);
                    }
                    //System.out.println("Double skip sundae");
                    pathPosition = i - 1;
                    onChangeInPathPosition();
                    onTick(recursionDepth + 1);
                    return false;
                }
            }
        }
        Pair<Double, BlockPos> status = closestPathPos(path);
        if (possiblyOffPath(status, MAX_DIST_FROM_PATH)) {
            ticksAway++;
            System.out.println("FAR AWAY FROM PATH FOR " + ticksAway + " TICKS. Current distance: " + status.first() + ". Threshold: " + MAX_DIST_FROM_PATH);
            if (ticksAway > MAX_TICKS_AWAY) {
                logDebug("Too far away from path for too long, cancelling path");
                cancel();
                return false;
            }
        } else {
            ticksAway = 0;
        }
        if (possiblyOffPath(status, MAX_MAX_DIST_FROM_PATH)) { // ok, stop right away, we're way too far.
            logDebug("too far from path");
            cancel();
            return false;
        }
        //long start = System.nanoTime() / 1000000L;
        BlockStateInterface bsi = new BlockStateInterface(ctx);
        for (int i = pathPosition - 10; i < pathPosition + 10; i++) {
            if (i < 0 || i >= path.movements().size()) {
                continue;
            }
            Movement m = (Movement) path.movements().get(i);
            List<BlockPos> prevBreak = m.toBreak(bsi);
            List<BlockPos> prevPlace = m.toPlace(bsi);
            List<BlockPos> prevWalkInto = m.toWalkInto(bsi);
            m.resetBlockCache();
            if (!prevBreak.equals(m.toBreak(bsi))) {
                recalcBP = true;
            }
            if (!prevPlace.equals(m.toPlace(bsi))) {
                recalcBP = true;
            }
            if (!prevWalkInto.equals(m.toWalkInto(bsi))) {
                recalcBP = true;
            }
        }
        if (recalcBP) {
            HashSet<BlockPos> newBreak = new HashSet<>();
            HashSet<BlockPos> newPlace = new HashSet<>();
            HashSet<BlockPos> newWalkInto = new HashSet<>();
            for (int i = pathPosition; i < path.movements().size(); i++) {
                Movement m = (Movement) path.movements().get(i);
                newBreak.addAll(m.toBreak(bsi));
                newPlace.addAll(m.toPlace(bsi));
                newWalkInto.addAll(m.toWalkInto(bsi));
            }
            toBreak = newBreak;
            toPlace = newPlace;
            toWalkInto = newWalkInto;
            recalcBP = false;
        }
        /*long end = System.nanoTime() / 1000000L;
        if (end - start > 0) {
            System.out.println("Recalculating break and place took " + (end - start) + "ms");
        }*/
        if (pathPosition < path.movements().size() - 1) {
            IMovement next = path.movements().get(pathPosition + 1);
            if (!behavior.baritone.bsi.worldContainsLoadedChunk(next.getDest().x, next.getDest().z)) {
                logDebug("Pausing since destination is at edge of loaded chunks");
                clearKeys();
                return true;
            }
        }
        boolean canCancel = movement.safeToCancel();
        if (costEstimateIndex == null || costEstimateIndex != pathPosition) {
            costEstimateIndex = pathPosition;
            // do this only once, when the movement starts, and deliberately get the cost as cached when this path was calculated, not the cost as it is right now
            currentMovementOriginalCostEstimate = movement.getCost();
            for (int i = 1; i < Baritone.settings().costVerificationLookahead.value && pathPosition + i < path.length() - 1; i++) {
                if (((Movement) path.movements().get(pathPosition + i)).calculateCost(behavior.secretInternalGetCalculationContext()) >= ActionCosts.COST_INF && canCancel) {
                    logDebug("Something has changed in the world and a future movement has become impossible. Cancelling.");
                    cancel();
                    return true;
                }
            }
        }
        double currentCost = movement.recalculateCost(behavior.secretInternalGetCalculationContext());
        if (currentCost >= ActionCosts.COST_INF && canCancel) {
            logDebug("Something has changed in the world and this movement has become impossible. Cancelling.");
            cancel();
            return true;
        }
        if (!movement.calculatedWhileLoaded() && currentCost - currentMovementOriginalCostEstimate > Baritone.settings().maxCostIncrease.value && canCancel) {
            // don't do this if the movement was calculated while loaded
            // that means that this isn't a cache error, it's just part of the path interfering with a later part
            logDebug("Original cost " + currentMovementOriginalCostEstimate + " current cost " + currentCost + ". Cancelling.");
            cancel();
            return true;
        }
        if (shouldPause()) {
            logDebug("Pausing since current best path is a backtrack");
            clearKeys();
            return true;
        }
        PathSmoother.SteeringResult smoothed = pathSmoother.evaluate(
                path,
                pathPosition,
                ctx,
                behavior.secretInternalGetCalculationContext(),
                bsi
        );
        if (recursionDepth == 0
                && smoothed.active()
                && smoothed.advanceToIndex() > pathPosition
                && smoothed.advanceToIndex() < path.movements().size()) {
            pathPosition = smoothed.advanceToIndex();
            onChangeInPathPosition();
            movement = (Movement) path.movements().get(pathPosition);
        }
        if (movement instanceof MovementTraverse mt) {
            if (Baritone.settings().humanMode.value) {
                applyBridgeCorridorContext(mt, path, pathPosition, bsi);
            } else {
                mt.setBridgeContext(null, 1, 4);
            }
        }
        MovementStatus movementStatus = movement.update();
        if (movementStatus == UNREACHABLE || movementStatus == FAILED) {
            logDebug("Movement returns status " + movementStatus);
            cancel();
            return true;
        }
        if (movementStatus == SUCCESS) {
            //System.out.println("Movement done, next path");
            pathPosition++;
            onChangeInPathPosition();
            onTick(recursionDepth + 1);
            return true;
        } else {
            sprintNextTick = shouldSprintNextTick(recursionDepth);
            if (!sprintNextTick) {
                ctx.player().setSprinting(false); // letting go of control doesn't make you stop sprinting actually
            }
            if (Baritone.settings().humanMode.value) {
                boolean bridgeAnchor = behavior.baritone.getLookBehavior().isBridgeAnchorMode();
                Vec3 velNow = ctx.player().getDeltaMovement();
                double hSpeedNow = Math.hypot(velNow.x, velNow.z);
                boolean passiveMove = movement.toBreak(bsi).isEmpty() && movement.toPlace(bsi).isEmpty();
                boolean activelyPushing = behavior.baritone.getInputOverrideHandler().isInputForcedDown(Input.MOVE_FORWARD)
                        || behavior.baritone.getInputOverrideHandler().isInputForcedDown(Input.MOVE_LEFT)
                        || behavior.baritone.getInputOverrideHandler().isInputForcedDown(Input.MOVE_RIGHT)
                        || behavior.baritone.getInputOverrideHandler().getDesiredMovementYaw() != null;
                boolean pinnedThisTick = !bridgeAnchor && passiveMove && (
                        (ctx.player().horizontalCollision && hSpeedNow < 0.045)
                        || (activelyPushing && ctx.player().onGround() && hSpeedNow < 0.018)
                );
                if (pinnedThisTick) {
                    wallPinTicks++;
                } else if (wallPinTicks > 0) {
                    wallPinTicks--;
                }
                if (wallPinTicks >= 16) {
                    logDebug("Pinned against wall for " + wallPinTicks + " ticks; recalculating path");
                    wallPinTicks = 0;
                    cancel();
                    return true;
                }

                if (bridgeAnchor) {
                    // Clear steered move yaw while BridgeController drives relative S/S+A/S+D edge keys directly
                    behavior.baritone.getInputOverrideHandler().setDesiredMovementYaw(null);
                } else {
                    boolean pinningWall = wallPinTicks >= 3;
                    BetterBlockPos dest = movement.getDest();
                    boolean backingUp = behavior.baritone.getInputOverrideHandler().isInputForcedDown(Input.MOVE_BACK);
                    float destYawNow = PathSmoother.computeYawTo(ctx.player().getX(), ctx.player().getZ(), dest.x + 0.5, dest.z + 0.5);
                    boolean isParkour = movement instanceof MovementParkour || movement instanceof MovementParkourOffset;
                    boolean parkourTurnaround = isParkour
                            && ctx.playerFeet().equals(movement.getSrc())
                            && Math.abs(Rotation.normalizeYaw(destYawNow - ctx.playerRotations().getYaw())) > 45.0f;
                    double parkourRemForward = 1.0;
                    float parkourJumpYaw = destYawNow;
                    if (isParkour) {
                        BetterBlockPos pSrc = movement.getSrc();
                        double jdx = dest.x - pSrc.x;
                        double jdz = dest.z - pSrc.z;
                        double jLen = Math.hypot(jdx, jdz);
                        if (jLen > 1e-4) {
                            parkourJumpYaw = PathSmoother.computeYawTo(pSrc.x + 0.5, pSrc.z + 0.5, dest.x + 0.5, dest.z + 0.5);
                            parkourRemForward = ((dest.x + 0.5) - ctx.player().getX()) * (jdx / jLen)
                                    + ((dest.z + 0.5) - ctx.player().getZ()) * (jdz / jLen);
                        }
                    }

                    if (backingUp || parkourTurnaround) {
                        behavior.baritone.getInputOverrideHandler().setDesiredMovementYaw(null);
                    } else if (isParkour && !ctx.playerFeet().equals(movement.getSrc()) && parkourRemForward <= 0.35) {
                        float forwardYaw = parkourJumpYaw;
                        if (pathPosition + 1 < path.movements().size()) {
                            BetterBlockPos nextDest = path.movements().get(pathPosition + 1).getDest();
                            double ndx = (nextDest.x + 0.5) - ctx.player().getX();
                            double ndz = (nextDest.z + 0.5) - ctx.player().getZ();
                            if (ndx * ndx + ndz * ndz >= 0.04) {
                                float nextYaw = PathSmoother.computeYawTo(ctx.player().getX(), ctx.player().getZ(), nextDest.x + 0.5, nextDest.z + 0.5);
                                if (Math.abs(Rotation.normalizeYaw(nextYaw - parkourJumpYaw)) <= 75.0f) {
                                    forwardYaw = nextYaw;
                                }
                            }
                        }
                        behavior.baritone.getInputOverrideHandler().setDesiredMovementYaw(forwardYaw);
                    } else if (smoothed.active() && !pinningWall) {
                        behavior.baritone.getInputOverrideHandler().setDesiredMovementYaw(smoothed.desiredMoveYaw());
                    } else if (!pinningWall && movement instanceof MovementTheta
                            && behavior.baritone.getInputOverrideHandler().getDesiredMovementYaw() != null) {
                        // Preserve MovementTheta's 16-ray context-steered & ice-compensated movement yaw
                    } else {
                        // When not string-pulling (or when brushing a wall corner), steer directly toward movement dest
                        double dx = (dest.x + 0.5) - ctx.player().getX();
                        double dz = (dest.z + 0.5) - ctx.player().getZ();
                        if ((dx * dx + dz * dz) >= 0.020) {
                            float destYaw = destYawNow;
                            if (pinningWall) {
                                // Nudge past the 22.5-deg W-sector hysteresis cone so KeyboardModel sidesteps off the corner
                                float rel = Rotation.normalizeYaw(destYaw - ctx.playerRotations().getYaw());
                                if (Math.abs(rel) > 5.0f && Math.abs(rel) < 25.0f) {
                                    destYaw += Math.copySign(24.0f, rel);
                                }
                            }
                            behavior.baritone.getInputOverrideHandler().setDesiredMovementYaw(destYaw);
                        } else {
                            behavior.baritone.getInputOverrideHandler().setDesiredMovementYaw(null);
                        }
                    }

                    Rotation gaze;
                    if (pinningWall || parkourTurnaround) {
                        Vec3 centerEye = new Vec3(dest.x + 0.5, parkourTurnaround ? (dest.y + 0.5) : ctx.playerHead().y, dest.z + 0.5);
                        gaze = RotationUtils.calcRotationFromVec3d(ctx.playerHead(), centerEye, ctx.playerRotations());
                    } else {
                        gaze = gazeDirector.computeGazeRotation(
                                path,
                                pathPosition,
                                ctx,
                                bsi,
                                behavior.baritone.getInputOverrideHandler().getDesiredMovementYaw(),
                                sprintNextTick,
                                smoothed.active() ? smoothed.gazeTarget() : null
                        );
                    }
                    if (gaze != null) {
                        // Guard against mid-air backward/downward camera whips when flying past a dest block
                        if (!ctx.player().onGround() && movement.toBreak(bsi).isEmpty() && movement.toPlace(bsi).isEmpty()) {
                            Vec3 vel = ctx.player().getDeltaMovement();
                            if (Math.hypot(vel.x, vel.z) > 0.14) {
                                float velYaw = PathSmoother.computeYawTo(0.0, 0.0, vel.x, vel.z);
                                gaze = GazeDirector.clampGazeToMoveCone(gaze, velYaw, 60.0f);
                            }
                            if (isParkour) {
                                gaze = GazeDirector.clampGazeToMoveCone(gaze, parkourJumpYaw, 48.0f);
                            }
                            if (gaze.getPitch() > 14.0f) {
                                gaze = new Rotation(gaze.getYaw(), 14.0f);
                            }
                        }
                        behavior.baritone.getLookBehavior().updateTarget(gaze, false);
                    }
                }
            }
            ticksOnCurrent++;
            if (ticksOnCurrent > currentMovementOriginalCostEstimate + Baritone.settings().movementTimeoutTicks.value) {
                // only cancel if the total time has exceeded the initial estimate
                // as you break the blocks required, the remaining cost goes down, to the point where
                // ticksOnCurrent is greater than recalculateCost + 100
                // this is why we cache cost at the beginning, and don't recalculate for this comparison every tick
                logDebug("This movement has taken too long (" + ticksOnCurrent + " ticks, expected " + currentMovementOriginalCostEstimate + "). Cancelling.");
                cancel();
                return true;
            }
        }
        return canCancel; // movement is in progress, but if it reports cancellable, PathingBehavior is good to cut onto the next path
    }

    private Pair<Double, BlockPos> closestPathPos(IPath path) {
        BetterBlockPos feet = ctx.playerFeet();
        if (pathSmoother.isInSmoothedCorridor(feet)) {
            return new Pair<>(0.0, feet);
        }
        double best = -1;
        BlockPos bestPos = null;
        for (IMovement movement : path.movements()) {
            for (BlockPos pos : ((Movement) movement).getValidPositions()) {
                double dist = VecUtils.entityDistanceToCenter(ctx.player(), pos);
                if (dist < best || best == -1) {
                    best = dist;
                    bestPos = pos;
                }
            }
        }
        return new Pair<>(best, bestPos);
    }

    private boolean shouldPause() {
        Optional<AbstractNodeCostSearch> current = behavior.getInProgress();
        if (!current.isPresent()) {
            return false;
        }
        if (!ctx.player().onGround()) {
            return false;
        }
        if (!MovementHelper.canWalkOn(ctx, ctx.playerFeet().below())) {
            // we're in some kind of sketchy situation, maybe parkouring
            return false;
        }
        if (!MovementHelper.canWalkThrough(ctx, ctx.playerFeet()) || !MovementHelper.canWalkThrough(ctx, ctx.playerFeet().above())) {
            // suffocating?
            return false;
        }
        if (!path.movements().get(pathPosition).safeToCancel()) {
            return false;
        }
        if (Baritone.settings().humanMode.value
                && behavior.baritone.getInputOverrideHandler().getBlockBreakHelper().isHitting()) {
            // Do not mid-swing interrupt an active block break for background path smoothing.
            return false;
        }
        Optional<IPath> currentBest = current.get().bestPathSoFar();
        if (!currentBest.isPresent()) {
            return false;
        }
        List<BetterBlockPos> positions = currentBest.get().positions();
        if (positions.size() < 3) {
            return false; // not long enough yet to justify pausing, its far from certain we'll actually take this route
        }
        // the first block of the next path will always overlap
        // no need to pause our very last movement when it would have otherwise cleanly exited with MovementStatus SUCCESS
        positions = positions.subList(1, positions.size());
        return positions.contains(ctx.playerFeet());
    }

    private boolean possiblyOffPath(Pair<Double, BlockPos> status, double leniency) {
        double distanceFromPath = status.first();
        if (distanceFromPath > leniency) {
            // when we're midair in the middle of a fall, we're very far from both the beginning and the end, but we aren't actually off path
            if (path.movements().get(pathPosition) instanceof MovementFall) {
                BlockPos fallDest = path.positions().get(pathPosition + 1); // .get(pathPosition) is the block we fell off of
                return VecUtils.entityFlatDistanceToCenter(ctx.player(), fallDest) >= leniency; // ignore Y by using flat distance
            } else {
                return true;
            }
        } else {
            return false;
        }
    }

    /**
     * Regardless of current path position, snap to the current player feet if possible
     *
     * @return Whether or not it was possible to snap to the current player feet
     */
    public boolean snipsnapifpossible() {
        if (!ctx.player().onGround() && ctx.world().getFluidState(ctx.playerFeet()).isEmpty()) {
            // if we're falling in the air, and not in water, don't splice
            return false;
        } else {
            // we are either onGround or in liquid
            if (ctx.player().getDeltaMovement().y < -0.1) {
                // if we are strictly moving downwards (not stationary)
                // we could be falling through water, which could be unsafe to splice
                return false; // so don't
            }
        }
        int index = path.positions().indexOf(ctx.playerFeet());
        if (index == -1) {
            return false;
        }
        pathPosition = index; // jump directly to current position
        clearKeys();
        return true;
    }

    private boolean shouldSprintNextTick(int recursionDepth) {
        boolean requested = behavior.baritone.getInputOverrideHandler().isInputForcedDown(Input.SPRINT);

        // we'll take it from here, no need for minecraft to see we're holding down control and sprint for us
        behavior.baritone.getInputOverrideHandler().setInputForceState(Input.SPRINT, false);

        // first and foremost, if allowSprint is off, or if we don't have enough hunger, don't try and sprint
        if (!new CalculationContext(behavior.baritone, false).canSprint) {
            return false;
        }
        IMovement current = path.movements().get(pathPosition);

        // traverse requests sprinting, so we need to do this check first
        if (current instanceof MovementTraverse && pathPosition < path.length() - 3) {
            IMovement next = path.movements().get(pathPosition + 1);
            if (next instanceof MovementAscend && sprintableAscend(ctx, (MovementTraverse) current, (MovementAscend) next, path.movements().get(pathPosition + 2))) {
                if (skipNow(ctx, current)) {
                    logDebug("Skipping traverse to straight ascend");
                    pathPosition++;
                    onChangeInPathPosition();
                    onTick(recursionDepth + 1);
                    behavior.baritone.getInputOverrideHandler().setInputForceState(Input.JUMP, true);
                    return true;
                } else {
                    logDebug("Too far to the side to safely sprint ascend");
                }
            }
        }

        // if the movement requested sprinting, then we're done
        if (requested) {
            return true;
        }

        // however, descend and ascend don't request sprinting, because they don't know the context of what movement comes after it
        if (current instanceof MovementDescend) {

            if (pathPosition < path.length() - 2) {
                // keep this out of onTick, even if that means a tick of delay before it has an effect
                IMovement next = path.movements().get(pathPosition + 1);
                if (MovementHelper.canUseFrostWalker(ctx, next.getDest().below())) {
                    // frostwalker only works if you cross the edge of the block on ground so in some cases we may not overshoot
                    // Since MovementDescend can't know the next movement we have to tell it
                    if (next instanceof MovementTraverse || next instanceof MovementParkour) {
                        boolean couldPlaceInstead = Baritone.settings().allowPlace.value && behavior.baritone.getInventoryBehavior().hasGenericThrowaway() && next instanceof MovementParkour; // traverse doesn't react fast enough
                        // this is true if the next movement does not ascend or descends and goes into the same cardinal direction (N-NE-E-SE-S-SW-W-NW) as the descend
                        // in that case current.getDirection() is e.g. (0, -1, 1) and next.getDirection() is e.g. (0, 0, 3) so the cross product of (0, 0, 1) and (0, 0, 3) is taken, which is (0, 0, 0) because the vectors are colinear (don't form a plane)
                        // since movements in exactly the opposite direction (e.g. descend (0, -1, 1) and traverse (0, 0, -1)) would also pass this check we also have to rule out that case
                        // we can do that by adding the directions because traverse is always 1 long like descend and parkour can't jump through current.getSrc().down()
                        boolean sameFlatDirection = !current.getDirection().above().offset(next.getDirection()).equals(BlockPos.ZERO)
                                && current.getDirection().above().cross(next.getDirection()).equals(BlockPos.ZERO); // here's why you learn maths in school
                        if (sameFlatDirection && !couldPlaceInstead) {
                            ((MovementDescend) current).forceSafeMode();
                        }
                    }
                }
            }
            if (((MovementDescend) current).safeMode() && !((MovementDescend) current).skipToAscend()) {
                logDebug("Sprinting would be unsafe");
                return false;
            }

            if (pathPosition < path.length() - 2) {
                IMovement next = path.movements().get(pathPosition + 1);
                if (next instanceof MovementAscend && current.getDirection().above().equals(next.getDirection().below())) {
                    // a descend then an ascend in the same direction
                    pathPosition++;
                    onChangeInPathPosition();
                    onTick(recursionDepth + 1);
                    // okay to skip clearKeys and / or onChangeInPathPosition here since this isn't possible to repeat, since it's asymmetric
                    logDebug("Skipping descend to straight ascend");
                    return true;
                }
                if (canSprintFromDescendInto(ctx, current, next)) {

                    if (next instanceof MovementDescend && pathPosition < path.length() - 3) {
                        IMovement next_next = path.movements().get(pathPosition + 2);
                        if (next_next instanceof MovementDescend && !canSprintFromDescendInto(ctx, next, next_next)) {
                            return false;
                        }

                    }
                    if (ctx.playerFeet().equals(current.getDest())) {
                        pathPosition++;
                        onChangeInPathPosition();
                        onTick(recursionDepth + 1);
                    }

                    return true;
                }
                //logDebug("Turning off sprinting " + movement + " " + next + " " + movement.getDirection() + " " + next.getDirection().down() + " " + next.getDirection().down().equals(movement.getDirection()));
            }
        }
        if (current instanceof MovementAscend && pathPosition != 0) {
            IMovement prev = path.movements().get(pathPosition - 1);
            if (prev instanceof MovementDescend && prev.getDirection().above().equals(current.getDirection().below())) {
                BlockPos center = current.getSrc().above();
                // playerFeet adds 0.1251 to account for soul sand
                // farmland is 0.9375
                // 0.07 is to account for farmland
                if (ctx.player().position().y >= center.getY() - 0.07) {
                    behavior.baritone.getInputOverrideHandler().setInputForceState(Input.JUMP, false);
                    return true;
                }
            }
            if (pathPosition < path.length() - 2 && prev instanceof MovementTraverse && sprintableAscend(ctx, (MovementTraverse) prev, (MovementAscend) current, path.movements().get(pathPosition + 1))) {
                return true;
            }
        }
        if (current instanceof MovementFall) {
            Pair<Vec3, BlockPos> data = overrideFall((MovementFall) current);
            if (data != null) {
                BetterBlockPos fallDest = new BetterBlockPos(data.second());
                if (!path.positions().contains(fallDest)) {
                    throw new IllegalStateException(String.format(
                            "Fall override at %s %s %s returned illegal destination %s %s %s",
                            current.getSrc(), fallDest));
                }
                if (ctx.playerFeet().equals(fallDest)) {
                    pathPosition = path.positions().indexOf(fallDest);
                    onChangeInPathPosition();
                    onTick(recursionDepth + 1);
                    return true;
                }
                clearKeys();
                behavior.baritone.getLookBehavior().updateTarget(RotationUtils.calcRotationFromVec3d(ctx.playerHead(), data.first(), ctx.playerRotations()), false);
                behavior.baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_FORWARD, true);
                return true;
            }
        }
        return false;
    }

    private Pair<Vec3, BlockPos> overrideFall(MovementFall movement) {
        Vec3i dir = movement.getDirection();
        if (dir.getY() < -3) {
            return null;
        }
        if (!movement.toBreakCached.isEmpty()) {
            return null; // it's breaking
        }
        Vec3i flatDir = new Vec3i(dir.getX(), 0, dir.getZ());
        int i;
        outer:
        for (i = pathPosition + 1; i < path.length() - 1 && i < pathPosition + 3; i++) {
            IMovement next = path.movements().get(i);
            if (!(next instanceof MovementTraverse)) {
                break;
            }
            if (!flatDir.equals(next.getDirection())) {
                break;
            }
            for (int y = next.getDest().y; y <= movement.getSrc().y + 1; y++) {
                BlockPos chk = new BlockPos(next.getDest().x, y, next.getDest().z);
                if (!MovementHelper.fullyPassable(ctx, chk)) {
                    break outer;
                }
            }
            if (!MovementHelper.canWalkOn(ctx, next.getDest().below())) {
                break;
            }
        }
        i--;
        if (i == pathPosition) {
            return null; // no valid extension exists
        }
        double len = i - pathPosition - 0.4;
        return new Pair<>(
                new Vec3(flatDir.getX() * len + movement.getDest().x + 0.5, movement.getDest().y, flatDir.getZ() * len + movement.getDest().z + 0.5),
                movement.getDest().offset(flatDir.getX() * (i - pathPosition), 0, flatDir.getZ() * (i - pathPosition)));
    }

    private static boolean skipNow(IPlayerContext ctx, IMovement current) {
        if (current instanceof Movement m && !m.getValidPositions().contains(ctx.playerFeet())) {
            return false;
        }
        double offTarget = Math.abs(current.getDirection().getX() * (current.getSrc().z + 0.5D - ctx.player().position().z)) + Math.abs(current.getDirection().getZ() * (current.getSrc().x + 0.5D - ctx.player().position().x));
        if (offTarget > 0.1) {
            return false;
        }
        // we are centered
        BlockPos headBonk = current.getSrc().subtract(current.getDirection()).above(2);
        if (MovementHelper.fullyPassable(ctx, headBonk)) {
            return true;
        }
        // wait 0.3
        double flatDist = Math.abs(current.getDirection().getX() * (headBonk.getX() + 0.5D - ctx.player().position().x)) + Math.abs(current.getDirection().getZ() * (headBonk.getZ() + 0.5 - ctx.player().position().z));
        return flatDist > 0.8;
    }

    private static boolean sprintableAscend(IPlayerContext ctx, MovementTraverse current, MovementAscend next, IMovement nextnext) {
        if (!Baritone.settings().sprintAscends.value) {
            return false;
        }
        if (!current.getDirection().equals(next.getDirection().below())) {
            return false;
        }
        if (nextnext.getDirection().getX() != next.getDirection().getX() || nextnext.getDirection().getZ() != next.getDirection().getZ()) {
            return false;
        }
        if (!MovementHelper.canWalkOn(ctx, current.getDest().below())) {
            return false;
        }
        if (!MovementHelper.canWalkOn(ctx, next.getDest().below())) {
            return false;
        }
        if (!next.toBreakCached.isEmpty()) {
            return false; // it's breaking
        }
        for (int x = 0; x < 2; x++) {
            for (int y = 0; y < 3; y++) {
                BlockPos chk = current.getSrc().above(y);
                if (x == 1) {
                    chk = chk.offset(current.getDirection());
                }
                if (!MovementHelper.fullyPassable(ctx, chk)) {
                    return false;
                }
            }
        }
        if (MovementHelper.avoidWalkingInto(ctx.world().getBlockState(current.getSrc().above(3)))) {
            return false;
        }
        return !MovementHelper.avoidWalkingInto(ctx.world().getBlockState(next.getDest().above(2))); // codacy smh my head
    }

    private static boolean canSprintFromDescendInto(IPlayerContext ctx, IMovement current, IMovement next) {
        if (next instanceof MovementDescend && next.getDirection().equals(current.getDirection())) {
            return true;
        }
        if (!MovementHelper.canWalkOn(ctx, current.getDest().offset(current.getDirection()))) {
            return false;
        }
        if (next instanceof MovementTraverse && next.getDirection().equals(current.getDirection())) {
            return true;
        }
        return next instanceof MovementDiagonal && Baritone.settings().allowOvershootDiagonalDescend.value;
    }

    private void onChangeInPathPosition() {
        clearKeys();
        ticksOnCurrent = 0;
        wallPinTicks = 0;
    }

    private void clearKeys() {
        // i'm just sick and tired of this snippet being everywhere lol
        behavior.baritone.getInputOverrideHandler().clearAllKeys();
    }

    private void cancel() {
        clearKeys();
        behavior.baritone.getInputOverrideHandler().getBlockBreakHelper().stopBreakingBlock();
        pathPosition = path.length() + 3;
        failed = true;
    }

    @Override
    public int getPosition() {
        return pathPosition;
    }

    public PathExecutor trySplice(PathExecutor next) {
        if (next == null) {
            return cutIfTooLong();
        }
        return SplicedPath.trySplice(path, next.path, false).map(path -> {
            if (!path.getDest().equals(next.getPath().getDest())) {
                throw new IllegalStateException(String.format(
                        "Path has end %s instead of %s after splicing",
                        path.getDest(), next.getPath().getDest()));
            }
            PathExecutor ret = new PathExecutor(behavior, path);
            ret.pathPosition = pathPosition;
            ret.currentMovementOriginalCostEstimate = currentMovementOriginalCostEstimate;
            ret.costEstimateIndex = costEstimateIndex;
            ret.ticksOnCurrent = ticksOnCurrent;
            return ret;
        }).orElseGet(this::cutIfTooLong); // dont actually call cutIfTooLong every tick if we won't actually use it, use a method reference
    }

    private PathExecutor cutIfTooLong() {
        if (pathPosition > Baritone.settings().maxPathHistoryLength.value) {
            int cutoffAmt = Baritone.settings().pathHistoryCutoffAmount.value;
            CutoffPath newPath = new CutoffPath(path, cutoffAmt, path.length() - 1);
            if (!newPath.getDest().equals(path.getDest())) {
                throw new IllegalStateException(String.format(
                        "Path has end %s instead of %s after trimming its start",
                        newPath.getDest(), path.getDest()));
            }
            logDebug("Discarding earliest segment movements, length cut from " + path.length() + " to " + newPath.length());
            PathExecutor ret = new PathExecutor(behavior, newPath);
            ret.pathPosition = pathPosition - cutoffAmt;
            ret.currentMovementOriginalCostEstimate = currentMovementOriginalCostEstimate;
            if (costEstimateIndex != null) {
                ret.costEstimateIndex = costEstimateIndex - cutoffAmt;
            }
            ret.ticksOnCurrent = ticksOnCurrent;
            return ret;
        }
        return this;
    }

    @Override
    public IPath getPath() {
        return path;
    }

    public boolean failed() {
        return failed;
    }

    public boolean finished() {
        return pathPosition >= path.length();
    }

    public Set<BlockPos> toBreak() {
        return Collections.unmodifiableSet(toBreak);
    }

    public Set<BlockPos> toPlace() {
        return Collections.unmodifiableSet(toPlace);
    }

    public Set<BlockPos> toWalkInto() {
        return Collections.unmodifiableSet(toWalkInto);
    }

    public boolean isSprinting() {
        return sprintNextTick;
    }

    private void applyBridgeCorridorContext(MovementTraverse mt, IPath path, int startIndex, BlockStateInterface bsi) {
        List<IMovement> movements = path.movements();
        if (startIndex < 0 || startIndex >= movements.size()) {
            mt.setBridgeContext(null, 1, 4);
            return;
        }
        IMovement first = movements.get(startIndex);
        if (!(first instanceof MovementTraverse)) {
            mt.setBridgeContext(null, 1, 4);
            return;
        }
        int baseY = first.getSrc().y;
        boolean curNeedsBridge = !MovementHelper.canWalkOn(bsi, first.getDest().x, first.getDest().y - 1, first.getDest().z);
        boolean firstIsBridged = first instanceof MovementTraverse firstMt && firstMt.isBridgePlacementStep();
        if (!curNeedsBridge && ((Movement) first).toPlace(bsi).isEmpty() && !firstIsBridged) {
            mt.setBridgeContext(null, 1, 4);
            return;
        }

        // Include up to 4 trailing bridged segments so the final diagonal blocks preserve a rock-steady angle
        int winStart = startIndex;
        for (int b = startIndex - 1; b >= Math.max(0, startIndex - 4); b--) {
            IMovement prev = movements.get(b);
            if (!(prev instanceof MovementTraverse prevMt) || prev.getSrc().y != baseY || prev.getDest().y != baseY) {
                break;
            }
            boolean prevIsBridge = !MovementHelper.canWalkOn(bsi, prev.getDest().x, prev.getDest().y - 1, prev.getDest().z)
                    || !((Movement) prev).toPlace(bsi).isEmpty()
                    || prevMt.isBridgePlacementStep();
            if (!prevIsBridge) {
                break;
            }
            winStart = b;
        }

        BetterBlockPos origin = movements.get(winStart).getSrc();
        BetterBlockPos endDest = first.getDest();
        int bridgeSteps = 0;
        int minHeadroom = 5;
        int limit = Math.min(movements.size(), startIndex + 10);
        for (int i = winStart; i < limit; i++) {
            IMovement m = movements.get(i);
            if (!(m instanceof MovementTraverse) || m.getSrc().y != baseY || m.getDest().y != baseY) {
                break;
            }
            BetterBlockPos mDest = m.getDest();
            boolean needsBridgeBlock = !MovementHelper.canWalkOn(bsi, mDest.x, mDest.y - 1, mDest.z);
            if (i > startIndex && !needsBridgeBlock) {
                break;
            }
            int clearance = 0;
            for (int dy = 0; dy < 5; dy++) {
                if (!MovementHelper.canWalkThrough(bsi, mDest.x, mDest.y + dy, mDest.z)) {
                    break;
                }
                clearance++;
            }
            minHeadroom = Math.min(minHeadroom, clearance);
            endDest = mDest;
            bridgeSteps++;
        }
        if (bridgeSteps < 1) {
            mt.setBridgeContext(null, 1, 4);
            return;
        }
        double dx = endDest.x - origin.x;
        double dz = endDest.z - origin.z;
        Float corridorYaw = (dx * dx + dz * dz >= 1.0)
                ? PathSmoother.computeYawTo(origin.x + 0.5, origin.z + 0.5, endDest.x + 0.5, endDest.z + 0.5)
                : null;
        mt.setBridgeContext(corridorYaw, bridgeSteps, minHeadroom);
    }
}
