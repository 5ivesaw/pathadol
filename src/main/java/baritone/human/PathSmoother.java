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
import baritone.api.pathing.calc.IPath;
import baritone.api.pathing.movement.IMovement;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.IPlayerContext;
import baritone.api.utils.Rotation;
import baritone.pathing.movement.CalculationContext;
import baritone.pathing.movement.Movement;
import baritone.pathing.movement.MovementHelper;
import baritone.pathing.movement.movements.MovementDiagonal;
import baritone.pathing.movement.movements.MovementTheta;
import baritone.pathing.movement.movements.MovementTraverse;
import baritone.utils.BlockStateInterface;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiPredicate;

/**
 * Any-angle corridor string-pulling (Theta*-style line-of-sight pruning) and
 * pure-pursuit continuous steering over flat walkable segments of an {@link IPath}.
 */
public final class PathSmoother {

    /**
     * Player hitbox half-width (0.3) plus generous corner clearance margin (0.15) to avoid wall scrapes.
     */
    public static final double DEFAULT_CLEARANCE_RADIUS = 0.45;

    private final Set<BetterBlockPos> activeCorridorCells = new HashSet<>();
    private int smoothedTargetIndex = -1;
    private Float activeSteeringYaw = null;

    /**
     * Result of evaluating a smoothed steering step at the current tick.
     */
    public record SteeringResult(
            boolean active,
            int targetMovementIndex,
            int advanceToIndex,
            float desiredMoveYaw,
            Vec3 pursuitTarget,
            Vec3 gazeTarget,
            Set<BetterBlockPos> corridorCells
    ) {}

    /**
     * Evaluates the current path segment starting at {@code pathPosition}, performing
     * 2D swept-hitbox string-pulling across consecutive flat {@link MovementTraverse}
     * and {@link MovementDiagonal} steps (plus smooth curve-cutting pure pursuit ahead).
     */
    public SteeringResult evaluate(
            IPath path,
            int pathPosition,
            IPlayerContext ctx,
            CalculationContext calcContext,
            BlockStateInterface bsi
    ) {
        activeCorridorCells.clear();
        smoothedTargetIndex = -1;
        activeSteeringYaw = null;

        if (!Baritone.settings().humanMode.value || !Baritone.settings().pathSmoothing.value) {
            return new SteeringResult(false, pathPosition, pathPosition, 0f, null, null, Collections.emptySet());
        }
        if (path == null || pathPosition < 0 || pathPosition >= path.movements().size()) {
            return new SteeringResult(false, pathPosition, pathPosition, 0f, null, null, Collections.emptySet());
        }

        List<IMovement> movements = path.movements();
        IMovement current = movements.get(pathPosition);
        if (!isSmoothableFlatWalk(current, calcContext, bsi)) {
            return new SteeringResult(false, pathPosition, pathPosition, 0f, null, null, Collections.emptySet());
        }

        int baseY = current.getSrc().y;
        Vec3 playerPos = ctx.player().position();
        // Keep continuous steering active across sprint-jump arcs over flat terrain
        if (Math.abs(playerPos.y - baseY) > 1.35) {
            return new SteeringResult(false, pathPosition, pathPosition, 0f, null, null, Collections.emptySet());
        }

        int maxLookaheadSteps = Math.max(8, (int) Math.ceil(Baritone.settings().steeringLookahead.value * 5.0));
        int runEnd = pathPosition;
        for (int i = pathPosition + 1; i < movements.size() && i <= pathPosition + maxLookaheadSteps; i++) {
            IMovement candidate = movements.get(i);
            if (candidate.getSrc().y != baseY || candidate.getDest().y != baseY) {
                break;
            }
            if (!isSmoothableFlatWalk(candidate, calcContext, bsi)) {
                break;
            }
            runEnd = i;
        }

        if (runEnd == pathPosition) {
            return new SteeringResult(false, pathPosition, pathPosition, 0f, null, null, Collections.emptySet());
        }

        double smoothRadius = 0.38;
        Map<Long, Boolean> walkableCache = new HashMap<>();
        BiPredicate<Integer, Integer> cellWalkable = (bx, bz) -> walkableCache.computeIfAbsent(
                ((long) bx << 32) ^ (bz & 0xFFFFFFFFL),
                k -> isSafeCorridorCell(calcContext, bx, baseY, bz)
        );

        // Find the furthest movement destination in [pathPosition .. runEnd] that has swept-hitbox LOS from player
        int bestIndex = pathPosition;
        Set<BetterBlockPos> bestCorridor = new HashSet<>();
        bestCorridor.add(current.getSrc());
        bestCorridor.add(current.getDest());

        for (int k = runEnd; k > pathPosition; k--) {
            BetterBlockPos dest = movements.get(k).getDest();
            double tx = dest.x + 0.5;
            double tz = dest.z + 0.5;
            Set<BetterBlockPos> sweptCells = new HashSet<>();
            if (hasSweptLineOfSight(playerPos.x, playerPos.z, tx, tz, baseY, smoothRadius, cellWalkable, sweptCells)) {
                bestIndex = k;
                bestCorridor.addAll(sweptCells);
                for (int m = pathPosition; m <= k; m++) {
                    bestCorridor.add(movements.get(m).getSrc());
                    bestCorridor.add(movements.get(m).getDest());
                }
                break;
            }
        }

        if (bestIndex == pathPosition) {
            return new SteeringResult(false, pathPosition, pathPosition, 0f, null, null, Collections.emptySet());
        }

        // Determine if we have already passed intermediate movements along the smoothed corridor
        BetterBlockPos playerFeet = ctx.playerFeet();
        int advanceTo = pathPosition;
        for (int k = pathPosition; k < bestIndex; k++) {
            Movement step = (Movement) movements.get(k);
            Movement nextStep = (Movement) movements.get(k + 1);
            if (step instanceof MovementTheta) {
                double rem = Math.hypot(playerPos.x - (step.getDest().x + 0.5), playerPos.z - (step.getDest().z + 0.5));
                if (rem > 1.25) {
                    break;
                }
            }
            if (!canAdvancePastStep(
                    playerFeet,
                    playerPos.x,
                    playerPos.z,
                    step.getDest(),
                    nextStep.getDest(),
                    step.getValidPositions(),
                    nextStep.getValidPositions()
            )) {
                break;
            }
            advanceTo = k + 1;
        }

        // Build full string-pulled waypoint polyline from bestIndex through runEnd so turns curve smoothly ahead
        List<Vec3> curveWaypoints = new ArrayList<>();
        curveWaypoints.add(new Vec3(playerPos.x, baseY, playerPos.z));
        BetterBlockPos anchorBlock = movements.get(bestIndex).getDest();
        curveWaypoints.add(new Vec3(anchorBlock.x + 0.5, baseY, anchorBlock.z + 0.5));
        if (bestIndex < runEnd) {
            List<BetterBlockPos> tail = new ArrayList<>();
            tail.add(anchorBlock);
            for (int k = bestIndex + 1; k <= runEnd; k++) {
                tail.add(movements.get(k).getDest());
            }
            List<BetterBlockPos> pulledTail = smoothGridPolyline(tail, cellWalkable, smoothRadius);
            for (int i = 1; i < pulledTail.size(); i++) {
                BetterBlockPos wp = pulledTail.get(i);
                curveWaypoints.add(new Vec3(wp.x + 0.5, baseY, wp.z + 0.5));
            }
        }
        // Optionally extend lookahead smoothly into the next non-flat movement destination for fluid stair/parkour alignment
        if (runEnd + 1 < movements.size()) {
            BetterBlockPos nextDest = movements.get(runEnd + 1).getDest();
            curveWaypoints.add(new Vec3(nextDest.x + 0.5, nextDest.y, nextDest.z + 0.5));
        }

        // Pure-pursuit circle intersection along the curve polyline for fluid inside corner cutting
        double baseLookahead = Math.max(1.8, Baritone.settings().steeringLookahead.value * (ctx.player().isSprinting() ? 1.28 : 1.0));
        Vec3 pursuitTarget = samplePurePursuitAlongPolyline(playerPos, curveWaypoints, baseLookahead, baseY, cellWalkable);
        Vec3 gazeTarget = samplePurePursuitAlongPolyline(playerPos, curveWaypoints, Math.max(5.5, baseLookahead * 2.4), baseY, null);

        float desiredYaw = computeYawTo(playerPos.x, playerPos.z, pursuitTarget.x, pursuitTarget.z);

        this.activeCorridorCells.addAll(bestCorridor);
        this.smoothedTargetIndex = bestIndex;
        this.activeSteeringYaw = desiredYaw;

        return new SteeringResult(true, bestIndex, advanceTo, desiredYaw, pursuitTarget, gazeTarget, Collections.unmodifiableSet(activeCorridorCells));
    }

    /**
     * Samples a continuous point along {@code polyline} at horizontal arc/radial distance {@code lookahead}
     * from {@code playerPos}. When {@code cellWalkable} is provided, ensures direct swept LOS from the player
     * to the curved shortcut point before accepting a corner cut.
     */
    private static Vec3 samplePurePursuitAlongPolyline(
            Vec3 playerPos,
            List<Vec3> polyline,
            double lookahead,
            int baseY,
            BiPredicate<Integer, Integer> cellWalkable
    ) {
        if (polyline.size() < 2) {
            return polyline.isEmpty() ? playerPos : polyline.get(0);
        }
        Vec3 bestSafe = polyline.get(1);
        double remaining = lookahead;
        Vec3 cursor = polyline.get(0);
        for (int i = 1; i < polyline.size(); i++) {
            Vec3 segEnd = polyline.get(i);
            double segDx = segEnd.x - cursor.x;
            double segDz = segEnd.z - cursor.z;
            double segLen = Math.hypot(segDx, segDz);
            if (segLen < 1e-4) {
                cursor = segEnd;
                continue;
            }
            if (segLen >= remaining) {
                double t = remaining / segLen;
                Vec3 candidate = new Vec3(
                        cursor.x + segDx * t,
                        cursor.y + (segEnd.y - cursor.y) * t,
                        cursor.z + segDz * t
                );
                if (cellWalkable == null || (Math.abs(candidate.y - baseY) <= 0.15
                        && hasSweptLineOfSight(playerPos.x, playerPos.z, candidate.x, candidate.z, baseY, 0.38, cellWalkable, null))) {
                    return candidate;
                }
                return bestSafe;
            }
            if (cellWalkable == null || (Math.abs(segEnd.y - baseY) <= 0.15
                    && hasSweptLineOfSight(playerPos.x, playerPos.z, segEnd.x, segEnd.z, baseY, 0.38, cellWalkable, null))) {
                bestSafe = segEnd;
            }
            remaining -= segLen;
            cursor = segEnd;
        }
        return bestSafe;
    }

    /**
     * Checks whether a 2D swept hitbox of radius {@code clearanceRadius} can travel in a straight line
     * from {@code (x0, z0)} to {@code (x1, z1)} at height {@code y} without intersecting any non-walkable cell.
     */
    public static boolean hasSweptLineOfSight(
            double x0,
            double z0,
            double x1,
            double z1,
            int y,
            double clearanceRadius,
            BiPredicate<Integer, Integer> cellWalkable,
            Set<BetterBlockPos> visitedOut
    ) {
        double dx = x1 - x0;
        double dz = z1 - z0;
        double dist = Math.hypot(dx, dz);
        int steps = Math.max(3, (int) Math.ceil(dist / 0.14));

        double[] offsets = new double[]{-clearanceRadius, -clearanceRadius * 0.5, 0.0, clearanceRadius * 0.5, clearanceRadius};

        for (int i = 0; i <= steps; i++) {
            double t = (double) i / (double) steps;
            double cx = x0 + dx * t;
            double cz = z0 + dz * t;

            for (double ox : offsets) {
                for (double oz : offsets) {
                    int bx = (int) Math.floor(cx + ox);
                    int bz = (int) Math.floor(cz + oz);
                    if (!cellWalkable.test(bx, bz)) {
                        return false;
                    }
                    if (visitedOut != null) {
                        visitedOut.add(new BetterBlockPos(bx, y, bz));
                    }
                }
            }
        }
        return true;
    }

    /**
     * Pure-geometric Theta*-style polyline string-puller used for unit testing and offline path smoothing.
     */
    public static List<BetterBlockPos> smoothGridPolyline(
            List<BetterBlockPos> points,
            BiPredicate<Integer, Integer> cellWalkable,
            double clearanceRadius
    ) {
        if (points == null || points.size() <= 2) {
            return points == null ? Collections.emptyList() : new ArrayList<>(points);
        }
        List<BetterBlockPos> result = new ArrayList<>();
        int anchor = 0;
        result.add(points.get(anchor));

        while (anchor < points.size() - 1) {
            int furthest = anchor + 1;
            BetterBlockPos from = points.get(anchor);
            for (int candidate = points.size() - 1; candidate > anchor + 1; candidate--) {
                BetterBlockPos to = points.get(candidate);
                if (from.y != to.y) {
                    continue;
                }
                if (hasSweptLineOfSight(
                        from.x + 0.5,
                        from.z + 0.5,
                        to.x + 0.5,
                        to.z + 0.5,
                        from.y,
                        clearanceRadius,
                        cellWalkable,
                        null
                )) {
                    furthest = candidate;
                    break;
                }
            }
            result.add(points.get(furthest));
            anchor = furthest;
        }
        return result;
    }

    /**
     * Checks whether advancing past {@code stepDest} to {@code nextDest} is geometrically justified
     * and will not leave {@code playerFeet} stranded in {@code stepValid} without also being valid
     * for {@code nextValid} (which would trigger PathExecutor backtracking loops).
     */
    public static boolean canAdvancePastStep(
            BetterBlockPos playerFeet,
            double playerX,
            double playerZ,
            BetterBlockPos stepDest,
            BetterBlockPos nextDest,
            Set<BetterBlockPos> stepValid,
            Set<BetterBlockPos> nextValid
    ) {
        if (stepValid != null && stepValid.contains(playerFeet)
                && (nextValid == null || !nextValid.contains(playerFeet))) {
            return false;
        }
        double distToStepDest = Math.hypot((stepDest.x + 0.5) - playerX, (stepDest.z + 0.5) - playerZ);
        double distToNextDest = Math.hypot((nextDest.x + 0.5) - playerX, (nextDest.z + 0.5) - playerZ);
        double stepToNext = Math.hypot(nextDest.x - stepDest.x, nextDest.z - stepDest.z);
        return playerFeet.equals(stepDest)
                || distToStepDest <= 0.48
                || distToNextDest + 0.15 < stepToNext;
    }

    public static float computeYawTo(double fromX, double fromZ, double toX, double toZ) {
        double dx = toX - fromX;
        double dz = toZ - fromZ;
        return Rotation.normalizeYaw((float) Math.toDegrees(Math.atan2(-dx, dz)));
    }

    public boolean isInSmoothedCorridor(BetterBlockPos pos) {
        return activeCorridorCells.contains(pos);
    }

    public Float activeSteeringYaw() {
        return activeSteeringYaw;
    }

    public int smoothedTargetIndex() {
        return smoothedTargetIndex;
    }

    private static boolean isSmoothableFlatWalk(
            IMovement movement,
            CalculationContext calcContext,
            BlockStateInterface bsi
    ) {
        if (!(movement instanceof MovementTraverse || movement instanceof MovementDiagonal || movement instanceof MovementTheta)) {
            return false;
        }
        if (movement.getSrc().y != movement.getDest().y) {
            return false;
        }
        Movement m = (Movement) movement;
        if (!m.toBreak(bsi).isEmpty() || !m.toPlace(bsi).isEmpty() || !m.toWalkInto(bsi).isEmpty()) {
            return false;
        }
        BetterBlockPos src = m.getSrc();
        BetterBlockPos dest = m.getDest();
        return isSafeCorridorCell(calcContext, src.x, src.y, src.z)
                && isSafeCorridorCell(calcContext, dest.x, dest.y, dest.z);
    }

    private static boolean isSafeCorridorCell(CalculationContext context, int x, int y, int z) {
        BlockState floor = context.get(x, y - 1, z);
        if (!MovementHelper.canWalkOn(context, x, y - 1, z, floor)) {
            return false;
        }
        if (MovementHelper.isWater(floor) || MovementHelper.isLava(floor) || floor.is(Blocks.MAGMA_BLOCK) || floor.is(Blocks.SOUL_SAND)) {
            return false;
        }
        if (MovementHelper.isClimbable(floor.getBlock())) {
            return false;
        }
        BlockState feet = context.get(x, y, z);
        BlockState head = context.get(x, y + 1, z);
        if (!MovementHelper.fullyPassable(context, x, y, z, feet) || !MovementHelper.fullyPassable(context, x, y + 1, z, head)) {
            return false;
        }
        if (feet.getBlock() instanceof DoorBlock || feet.getBlock() instanceof FenceGateBlock
                || head.getBlock() instanceof DoorBlock || head.getBlock() instanceof FenceGateBlock) {
            return false;
        }
        return !MovementHelper.avoidWalkingInto(feet) && !MovementHelper.avoidWalkingInto(head);
    }
}
