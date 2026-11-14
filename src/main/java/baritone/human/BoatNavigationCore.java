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

import java.util.List;

/**
 * Pure Java boat decision planner, launch-spot scorer, and damped obstacle-avoiding steering controller
 * for water and ice navigation (matching MC 26.3 {@code AbstractBoat} physics without static MC imports).
 */
public final class BoatNavigationCore {

    public enum CellSurface {
        LAND,
        WATER,
        ICE,
        BLOCKED
    }

    public record TraversalAssessment(
            boolean shouldUseBoat,
            boolean mostlyIce,
            int waterCells,
            int iceCells
    ) {}

    public record RayProbe(
            float probeYawDeg,
            double freeDistance,
            double minWidthClearance,
            boolean hitsDryWalkableShore,
            boolean hitsHardWall
    ) {}

    public record ControlCommand(
            boolean forward,
            boolean back,
            boolean left,
            boolean right,
            float desiredLookYawDeg,
            float chosenHeadingYawDeg,
            boolean shouldDisembark,
            boolean emergencyBrake
    ) {}

    public static final double BOAT_HALF_WIDTH = 0.6875;
    public static final double SAFE_HALF_CORRIDOR = 0.85;

    private BoatNavigationCore() {}

    /**
     * Evaluates whether a path's upcoming surfaces justify launching/using a boat for
     * large open water crossings or fast ice transit.
     */
    public static TraversalAssessment assessSurfaceRun(
            List<CellSurface> lookaheadCells,
            double remainingGoalDist
    ) {
        if (lookaheadCells == null || lookaheadCells.isEmpty()) {
            return new TraversalAssessment(false, false, 0, 0);
        }
        int waterCells = 0;
        int iceCells = 0;
        int maxWaterRun = 0;
        int maxIceRun = 0;
        int curWaterRun = 0;
        int curIceRun = 0;
        int firstNavIdx = -1;

        for (int i = 0; i < lookaheadCells.size(); i++) {
            CellSurface c = lookaheadCells.get(i);
            if (c == CellSurface.WATER) {
                waterCells++;
                curWaterRun++;
                curIceRun = 0;
                maxWaterRun = Math.max(maxWaterRun, curWaterRun);
                if (firstNavIdx < 0) {
                    firstNavIdx = i;
                }
            } else if (c == CellSurface.ICE) {
                iceCells++;
                curIceRun++;
                curWaterRun = 0;
                maxIceRun = Math.max(maxIceRun, curIceRun);
                if (firstNavIdx < 0) {
                    firstNavIdx = i;
                }
            } else {
                curWaterRun = 0;
                curIceRun = 0;
            }
        }

        boolean startSoon = firstNavIdx >= 0 && firstNavIdx <= 12;
        boolean longWater = startSoon && remainingGoalDist >= 12.0 && (maxWaterRun >= 12 || waterCells >= 14);
        boolean longIce = startSoon && remainingGoalDist >= 14.0 && (maxIceRun >= 10 || iceCells >= 12);
        return new TraversalAssessment(longWater || longIce, longIce && !longWater, waterCells, iceCells);
    }

    /**
     * Scores candidate launch tiles on water/ice so boat placement has full {@code 3x3}
     * collision clearance around the {@code 1.375}-block-wide hull and >= 2 air blocks headroom.
     */
    public static double scoreLaunchCandidate(
            double playerDistSq,
            double goalDistSq,
            int openNeighbors3x3,
            int headroomAirBlocks,
            boolean isIce
    ) {
        if (openNeighbors3x3 < 7 || headroomAirBlocks < 2 || playerDistSq > 17.5 || playerDistSq < 1.2) {
            return Double.NEGATIVE_INFINITY;
        }
        double score = openNeighbors3x3 * 6.0;
        if (openNeighbors3x3 == 8) {
            score += 8.0;
        }
        if (isIce) {
            score += 2.0;
        }
        score -= Math.abs(Math.sqrt(playerDistSq) - 2.4) * 3.0;
        score -= Math.sqrt(Math.max(0.0, goalDistSq)) * 0.35;
        return score;
    }

    /**
     * Computes damped, human-like MC boat controls from multi-ray clearance probes so the boat
     * carves cleanly around shorelines, islands, and pillars without hitting blocks.
     */
    public static ControlCommand stepSteering(
            double boatX,
            double boatZ,
            float boatYawDeg,
            float yawRateDegPerTick,
            double velX,
            double velZ,
            double targetX,
            double targetZ,
            boolean goalIsOnDryLand,
            boolean isOnIce,
            List<RayProbe> probes
    ) {
        float goalYawDeg = worldYawTo(boatX, boatZ, targetX, targetZ);
        double distToGoal = Math.hypot(targetX - boatX, targetZ - boatZ);
        double speed = Math.hypot(velX, velZ);

        double noseClear = findClosestProbeClearance(probes, boatYawDeg);
        boolean nearFinalLandGoal = goalIsOnDryLand && distToGoal <= 5.5;
        boolean reachedTargetBank = goalIsOnDryLand && distToGoal <= 16.0
                && hasDryShoreDirectlyAhead(probes, goalYawDeg, 2.75)
                && !hasClearOpenCorridorNearGoal(probes, goalYawDeg, 7.5);

        if (nearFinalLandGoal || reachedTargetBank || distToGoal <= 2.75) {
            if (speed > 0.085) {
                return new ControlCommand(false, true, false, false, goalYawDeg, goalYawDeg, false, true);
            }
            return new ControlCommand(false, false, false, false, goalYawDeg, goalYawDeg, true, false);
        }

        double minSafeDist = isOnIce ? Math.max(4.5, speed * 14.0 + 3.0) : Math.max(2.8, speed * 9.0 + 2.2);
        float chosenHeading = chooseBestCorridorYaw(probes, goalYawDeg, boatYawDeg, minSafeDist, isOnIce);

        // If goal has no clear water/ice route and all forward sectors hit walkable dry land close by,
        // stop cleanly instead of grinding into the bank.
        if (goalIsOnDryLand && distToGoal <= 32.0 && noseClear < 2.1
                && hasDryShoreDirectlyAhead(probes, goalYawDeg, 2.5)
                && !hasAnyWideCorridor(probes, 6.0)) {
            if (speed > 0.085) {
                return new ControlCommand(false, true, false, false, goalYawDeg, goalYawDeg, false, true);
            }
            return new ControlCommand(false, false, false, false, goalYawDeg, goalYawDeg, true, false);
        }

        float yawErr = normalizeYaw(chosenHeading - boatYawDeg);
        float lookaheadTicks = isOnIce ? 2.6f : 3.2f;
        float predictedErr = yawErr - lookaheadTicks * yawRateDegPerTick;

        boolean turnRight = predictedErr > 3.0f;
        boolean turnLeft = predictedErr < -3.0f;
        if (turnLeft && turnRight) {
            turnLeft = false;
            turnRight = false;
        }

        boolean emergencyBrake = noseClear < (isOnIce ? Math.max(2.6, speed * 8.5) : 2.1) && speed > 0.08;
        boolean sharpPivotBrake = Math.abs(yawErr) > 78.0f && speed > 0.14;
        boolean back = emergencyBrake || sharpPivotBrake;

        boolean forward = false;
        if (!back) {
            double alignLimit = isOnIce ? 34.0 : 48.0;
            double minThrustClear = isOnIce ? Math.max(3.5, speed * 10.5) : 2.4;
            forward = Math.abs(yawErr) <= alignLimit && noseClear > minThrustClear;
        }

        float leadDelta = normalizeYaw(chosenHeading - boatYawDeg);
        leadDelta = Math.max(-60.0f, Math.min(60.0f, leadDelta * 0.55f));
        float desiredLookYaw = normalizeYaw(boatYawDeg + leadDelta);

        return new ControlCommand(forward, back, turnLeft, turnRight, desiredLookYaw, chosenHeading, false, emergencyBrake);
    }

    private static float chooseBestCorridorYaw(
            List<RayProbe> probes,
            float goalYawDeg,
            float boatYawDeg,
            double minSafeDist,
            boolean isOnIce
    ) {
        if (probes == null || probes.isEmpty()) {
            return goalYawDeg;
        }
        float bestYaw = goalYawDeg;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (RayProbe p : probes) {
            double clear = p.freeDistance();
            double sideClear = p.minWidthClearance();
            double diffFromGoal = Math.abs(normalizeYaw(p.probeYawDeg() - goalYawDeg));
            double diffFromBoat = Math.abs(normalizeYaw(p.probeYawDeg() - boatYawDeg));

            double score = Math.cos(Math.toRadians(diffFromGoal)) * 12.0;
            score -= diffFromBoat * 0.04;
            score += Math.min(clear, minSafeDist * 1.8) * (isOnIce ? 1.4 : 0.85);

            if (clear < 2.0) {
                score -= 900.0 - clear * 40.0;
            } else if (clear < minSafeDist) {
                score -= (minSafeDist - clear) * (isOnIce ? 26.0 : 18.0);
            }

            if (sideClear < SAFE_HALF_CORRIDOR) {
                score -= (SAFE_HALF_CORRIDOR - sideClear) * 85.0 + 30.0;
            } else if (sideClear >= 1.15) {
                score += 3.0;
            }

            if (p.hitsHardWall() && clear < minSafeDist * 1.25) {
                score -= 22.0;
            }

            if (score > bestScore) {
                bestScore = score;
                bestYaw = p.probeYawDeg();
            }
        }
        return bestYaw;
    }

    private static double findClosestProbeClearance(List<RayProbe> probes, float headingYawDeg) {
        if (probes == null || probes.isEmpty()) {
            return 16.0;
        }
        double best = Double.POSITIVE_INFINITY;
        double fallback = 16.0;
        float minAngle = Float.MAX_VALUE;
        for (RayProbe p : probes) {
            float d = Math.abs(normalizeYaw(p.probeYawDeg() - headingYawDeg));
            if (d < minAngle) {
                minAngle = d;
                fallback = p.freeDistance();
            }
            if (d <= 20.0f) {
                best = Math.min(best, p.freeDistance());
            }
        }
        return Double.isFinite(best) ? best : fallback;
    }

    private static boolean hasDryShoreDirectlyAhead(List<RayProbe> probes, float goalYawDeg, double maxDist) {
        if (probes == null) {
            return false;
        }
        for (RayProbe p : probes) {
            if (Math.abs(normalizeYaw(p.probeYawDeg() - goalYawDeg)) <= 24.0f
                    && p.hitsDryWalkableShore()
                    && p.freeDistance() <= maxDist) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasClearOpenCorridorNearGoal(List<RayProbe> probes, float goalYawDeg, double minFreeDist) {
        if (probes == null) {
            return false;
        }
        for (RayProbe p : probes) {
            if (Math.abs(normalizeYaw(p.probeYawDeg() - goalYawDeg)) <= 45.0f
                    && p.freeDistance() >= minFreeDist
                    && p.minWidthClearance() >= SAFE_HALF_CORRIDOR) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasAnyWideCorridor(List<RayProbe> probes, double minFreeDist) {
        if (probes == null) {
            return false;
        }
        for (RayProbe p : probes) {
            if (p.freeDistance() >= minFreeDist && p.minWidthClearance() >= SAFE_HALF_CORRIDOR) {
                return true;
            }
        }
        return false;
    }

    public static float worldYawTo(double fromX, double fromZ, double toX, double toZ) {
        double dx = toX - fromX;
        double dz = toZ - fromZ;
        if (dx * dx + dz * dz < 1e-8) {
            return 0.0f;
        }
        return normalizeYaw((float) Math.toDegrees(Math.atan2(-dx, dz)));
    }

    public static float normalizeYaw(float yaw) {
        float y = yaw % 360.0f;
        if (y >= 180.0f) {
            y -= 360.0f;
        }
        if (y < -180.0f) {
            y += 360.0f;
        }
        return y;
    }
}
