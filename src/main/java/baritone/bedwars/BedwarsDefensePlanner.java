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

package baritone.bedwars;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;

/**
 * Pure-Java Bedwars tactical solvers:
 * <ul>
 *   <li>Edge Case C: Diamond-shell defense scanner, raycast blocker solver, and X-Ray break vector evaluator</li>
 *   <li>Edge Case D: Void exposure &amp; fireball/bow threat multiplier on 1-wide air bridges</li>
 *   <li>Edge Case F: Safe Tactical Voiding check before `#bws recall`</li>
 * </ul>
 */
public final class BedwarsDefensePlanner {

    @FunctionalInterface
    public interface BlockKindClassifier {
        DefenseBlockKind classify(int x, int y, int z);
    }

    public record BedShellScan(
            int layersDetected,
            Map<DefenseBlockKind, Integer> counts,
            DefenseBlockKind hardestBlock,
            double estimatedClearTicks,
            int innerSolidShellBlocks
    ) {}

    public record RayBlockHit(int x, int y, int z, DefenseBlockKind kind) {}

    public record RayTargetResult(
            int mineX,
            int mineY,
            int mineZ,
            boolean mineIsBed,
            double estimatedCostTicks,
            double distanceSq
    ) {}

    public enum DefenseBlockKind {
        AIR,
        WOOL,
        GLASS,
        LADDER_SLAB,
        WOOD,
        TERRACOTTA,
        ENDSTONE,
        OBSIDIAN,
        WATER_COVERED,
        ORIGINAL_MAP
    }

    /**
     * Best player tool tiers available in inventory ({@code 0 = none, 1 = wood, 2 = stone, 3 = iron, 4 = diamond}).
     */
    public record ToolProfile(int shearsTier, int pickaxeTier, int axeTier) {
        public static ToolProfile bareHand() {
            return new ToolProfile(0, 0, 0);
        }
    }

    public record BreakVectorCandidate(
            int standX,
            int standY,
            int standZ,
            int targetX,
            int targetY,
            int targetZ,
            List<DefenseBlockKind> layersToBed,
            double extraApproachTicks
    ) {}

    public record BreakVectorDecision(
            BreakVectorCandidate bestVector,
            double estimatedBreakTicks,
            double totalScoreTicks
    ) {}

    private BedwarsDefensePlanner() {}

    /**
     * Estimates breaking time (in game ticks) of a single bed-defense block using held tools.
     */
    public static double estimateLayerTicks(DefenseBlockKind kind, ToolProfile tools) {
        if (kind == null) {
            return 0.0;
        }
        return switch (kind) {
            case AIR -> 0.0;
            case GLASS -> 6.0;
            case LADDER_SLAB -> 8.0;
            case WOOL -> tools.shearsTier() > 0 ? 5.0 : (tools.axeTier() > 0 ? 18.0 : 24.0);
            case WOOD -> switch (tools.axeTier()) {
                case 4 -> 8.0;
                case 3 -> 10.0;
                case 2 -> 15.0;
                case 1 -> 23.0;
                default -> 60.0;
            };
            case TERRACOTTA -> switch (tools.pickaxeTier()) {
                case 4 -> 6.0;
                case 3 -> 8.0;
                case 2 -> 12.0;
                case 1 -> 19.0;
                default -> 65.0;
            };
            case ENDSTONE -> switch (tools.pickaxeTier()) {
                case 4 -> 10.0;
                case 3 -> 15.0;
                case 2 -> 24.0;
                case 1 -> 45.0;
                default -> 150.0;
            };
            case OBSIDIAN -> tools.pickaxeTier() >= 4 ? 83.0 : 450.0;
            case WATER_COVERED -> 35.0;
            case ORIGINAL_MAP -> 1e9;
        };
    }

    /**
     * Edge Case C: Chooses the lowest-cost X-Ray break vector into an enemy bed defense.
     */
    public static BreakVectorDecision chooseWeakestBreakVector(List<BreakVectorCandidate> candidates, ToolProfile tools) {
        BreakVectorCandidate best = null;
        double bestBreak = Double.POSITIVE_INFINITY;
        double bestTotal = Double.POSITIVE_INFINITY;
        ToolProfile safeTools = tools != null ? tools : ToolProfile.bareHand();
        for (BreakVectorCandidate cand : candidates) {
            double breakTicks = 0.0;
            for (DefenseBlockKind k : cand.layersToBed()) {
                breakTicks += estimateLayerTicks(k, safeTools);
            }
            double total = breakTicks + cand.extraApproachTicks();
            if (total < bestTotal) {
                bestTotal = total;
                bestBreak = breakTicks;
                best = cand;
            }
        }
        return new BreakVectorDecision(best, bestBreak, bestTotal);
    }

    /**
     * Edge Case D: Computes additional A* cost penalty when stepping onto an exposed 1-wide air bridge
     * while an enemy threat within 30 blocks has a shot line / approach vector onto the bridge.
     */
    public static double computeBridgeThreatPenalty(
            double x,
            double y,
            double z,
            boolean exposedAirBridge,
            double[] threatCoordsXYZ,
            int threatCount
    ) {
        if (!exposedAirBridge || threatCoordsXYZ == null || threatCount <= 0) {
            return 0.0;
        }
        double worstPenalty = 0.0;
        for (int i = 0; i < threatCount; i++) {
            int base = i * 3;
            double tx = threatCoordsXYZ[base];
            double ty = threatCoordsXYZ[base + 1];
            double tz = threatCoordsXYZ[base + 2];
            double dx = x - tx;
            double dy = y - ty;
            double dz = z - tz;
            double distSq = dx * dx + dy * dy + dz * dz;
            if (distSq > 900.0) { // > 30 blocks
                continue;
            }
            double dist = Math.max(2.0, Math.sqrt(distSq));
            double penalty = 26.0 * (1.0 - (dist / 34.0));
            // Higher enemy on bridge/platform poses extra bow/fireball knockback risk on flat 1-wide lines
            if (ty >= y - 1.0) {
                penalty += 6.0;
            }
            if (penalty > worstPenalty) {
                worstPenalty = penalty;
            }
        }
        return worstPenalty;
    }

    /**
     * Edge Case F: Returns {@code true} when tactical voiding ({@code #bws recall}) sacrifices no valuable resources.
     */
    public static boolean isSafeForTacticalVoid(int iron, int gold, int diamonds, int emeralds) {
        return diamonds == 0 && emeralds == 0 && gold < 6 && iron < 24;
    }

    /**
     * Diamond-shell BFS defense scanner extending from both bed cells up to {@code maxLayers}
     * (stopping when 2 consecutive shells are &gt; 80% air) to classify defense depth and hardness.
     */
    public static BedShellScan scanBedDefenseShell(
            int headX, int headY, int headZ,
            int footX, int footY, int footZ,
            int maxLayers,
            ToolProfile tools,
            BlockKindClassifier classifier
    ) {
        Map<DefenseBlockKind, Integer> counts = new EnumMap<>(DefenseBlockKind.class);
        if (classifier == null || maxLayers <= 0) {
            return new BedShellScan(0, counts, DefenseBlockKind.AIR, 10.0, 0);
        }
        ToolProfile safeTools = tools != null ? tools : ToolProfile.bareHand();
        int bedMinY = Math.min(headY, footY);

        Set<Long> visited = new HashSet<>();
        Queue<int[]> frontier = new ArrayDeque<>();
        long headKey = packBlockCoord(headX, headY, headZ);
        long footKey = packBlockCoord(footX, footY, footZ);
        visited.add(headKey);
        visited.add(footKey);
        frontier.add(new int[]{headX, headY, headZ});
        frontier.add(new int[]{footX, footY, footZ});

        int[][] dirs = {{1, 0, 0}, {-1, 0, 0}, {0, 0, 1}, {0, 0, -1}, {0, 1, 0}, {0, -1, 0}};
        int layersDetected = 0;
        int innerSolid = 0;
        int consecutiveAirDominant = 0;
        DefenseBlockKind hardest = DefenseBlockKind.AIR;
        double minLayerTicksSum = 0.0;

        for (int layer = 1; layer <= maxLayers && !frontier.isEmpty(); layer++) {
            int layerSize = frontier.size();
            Queue<int[]> nextFrontier = new ArrayDeque<>();
            int layerTotal = 0;
            int layerAir = 0;
            double minLayerBlockCost = Double.POSITIVE_INFINITY;

            for (int q = 0; q < layerSize; q++) {
                int[] cur = frontier.poll();
                for (int[] d : dirs) {
                    int nx = cur[0] + d[0];
                    int ny = cur[1] + d[1];
                    int nz = cur[2] + d[2];
                    if (ny < bedMinY) {
                        continue;
                    }
                    long key = packBlockCoord(nx, ny, nz);
                    if (!visited.add(key)) {
                        continue;
                    }
                    DefenseBlockKind kind = classifier.classify(nx, ny, nz);
                    layerTotal++;
                    if (kind == DefenseBlockKind.AIR || kind == DefenseBlockKind.ORIGINAL_MAP) {
                        layerAir++;
                        // Still traverse outward across empty air gaps up to maxLayers in case an outer shell exists
                        nextFrontier.add(new int[]{nx, ny, nz});
                        continue;
                    }
                    if (layer == 1) {
                        innerSolid++;
                    }
                    counts.merge(kind, 1, Integer::sum);
                    if (isHarderDefenseKind(kind, hardest)) {
                        hardest = kind;
                    }
                    double ticks = estimateLayerTicks(kind, safeTools);
                    if (ticks < minLayerBlockCost) {
                        minLayerBlockCost = ticks;
                    }
                    nextFrontier.add(new int[]{nx, ny, nz});
                }
            }

            if (layerTotal == 0) {
                break;
            }
            double airRatio = (double) layerAir / (double) layerTotal;
            if (airRatio > 0.80) {
                consecutiveAirDominant++;
                if (consecutiveAirDominant >= 2) {
                    break;
                }
            } else {
                consecutiveAirDominant = 0;
                layersDetected = layer;
                if (Double.isFinite(minLayerBlockCost)) {
                    minLayerTicksSum += minLayerBlockCost;
                }
            }
            frontier = nextFrontier;
        }
        // Bed break time + weakest estimated outer-to-inner shell break sum
        double totalEstimated = 8.0 + minLayerTicksSum;
        return new BedShellScan(layersDetected, counts, hardest, totalEstimated, innerSolid);
    }

    /**
     * Multi-point raycast from {@code eye} across samples on both bed cells to choose either an
     * exposed bed cell directly or the single outermost block along the lowest-cost clearable ray.
     */
    public static RayTargetResult findWeakestRaycastTarget(
            double eyeX, double eyeY, double eyeZ,
            int headX, int headY, int headZ,
            int footX, int footY, int footZ,
            double maxReach,
            ToolProfile tools,
            BlockKindClassifier classifier
    ) {
        if (classifier == null) {
            return null;
        }
        ToolProfile safeTools = tools != null ? tools : ToolProfile.bareHand();
        int[][] bedCells = {{headX, headY, headZ}, {footX, footY, footZ}};
        double[] sampleCoords = {0.20, 0.50, 0.80};

        RayTargetResult bestDirectBed = null;
        RayTargetResult bestBlocker = null;

        for (int[] cell : bedCells) {
            for (double sx : sampleCoords) {
                for (double sy : sampleCoords) {
                    for (double sz : sampleCoords) {
                        // Sample outer surfaces + center (skip purely interior combinations except center)
                        boolean onBoundary = sx != 0.50 || sy != 0.50 || sz != 0.50;
                        if (onBoundary && sx == 0.50 && sy == 0.50 && sz == 0.50) {
                            continue;
                        }
                        double tx = cell[0] + sx;
                        double ty = cell[1] + (cell[1] == headY ? sy * 0.56 : sy * 0.56);
                        double tz = cell[2] + sz;

                        List<RayBlockHit> hits = traceRayBlocksToBed(
                                eyeX, eyeY, eyeZ, tx, ty, tz,
                                headX, headY, headZ, footX, footY, footZ,
                                maxReach + 2.2, classifier
                        );
                        if (hits == null) {
                            continue; // Blocked by unbreakable map geometry
                        }
                        if (hits.isEmpty()) {
                            double distSq = distSq3(eyeX, eyeY, eyeZ, tx, ty, tz);
                            if (distSq <= maxReach * maxReach
                                    && (bestDirectBed == null || distSq < bestDirectBed.distanceSq())) {
                                bestDirectBed = new RayTargetResult(
                                        cell[0], cell[1], cell[2], true, 6.0 + distSq * 0.1, distSq
                                );
                            }
                        } else {
                            RayBlockHit outer = hits.get(0);
                            double outerDistSq = distSq3(eyeX, eyeY, eyeZ, outer.x() + 0.5, outer.y() + 0.5, outer.z() + 0.5);
                            double cost = 0.0;
                            for (RayBlockHit hit : hits) {
                                cost += estimateLayerTicks(hit.kind(), safeTools);
                            }
                            // Slightly prioritize blockers already inside block reach and fewer total layers
                            cost += outerDistSq * 0.35 + (hits.size() - 1) * 1.5;
                            if (bestBlocker == null || cost < bestBlocker.estimatedCostTicks()) {
                                bestBlocker = new RayTargetResult(
                                        outer.x(), outer.y(), outer.z(), false, cost, outerDistSq
                                );
                            }
                        }
                    }
                }
            }
        }
        return bestDirectBed != null ? bestDirectBed : bestBlocker;
    }

    /**
     * Walks a segment from {@code eye} to {@code target} in 0.25-block increments and collects unique
     * defense blocks encountered before reaching either bed cell. Returns {@code null} if an
     * unbreakable {@link DefenseBlockKind#ORIGINAL_MAP} block intercepts the ray.
     */
    public static List<RayBlockHit> traceRayBlocksToBed(
            double eyeX, double eyeY, double eyeZ,
            double targetX, double targetY, double targetZ,
            int headX, int headY, int headZ,
            int footX, int footY, int footZ,
            double maxLength,
            BlockKindClassifier classifier
    ) {
        double dx = targetX - eyeX;
        double dy = targetY - eyeY;
        double dz = targetZ - eyeZ;
        double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len < 1e-4 || len > maxLength) {
            return null;
        }
        double invLen = 1.0 / len;
        double ux = dx * invLen;
        double uy = dy * invLen;
        double uz = dz * invLen;

        List<RayBlockHit> out = new ArrayList<>(6);
        Set<Long> seen = new HashSet<>();
        for (double t = 0.20; t <= len; t += 0.25) {
            int bx = (int) Math.floor(eyeX + ux * t);
            int by = (int) Math.floor(eyeY + uy * t);
            int bz = (int) Math.floor(eyeZ + uz * t);
            if ((bx == headX && by == headY && bz == headZ) || (bx == footX && by == footY && bz == footZ)) {
                return out;
            }
            long key = packBlockCoord(bx, by, bz);
            if (!seen.add(key)) {
                continue;
            }
            DefenseBlockKind kind = classifier.classify(bx, by, bz);
            if (kind == DefenseBlockKind.ORIGINAL_MAP) {
                return null;
            }
            if (kind != DefenseBlockKind.AIR) {
                out.add(new RayBlockHit(bx, by, bz, kind));
                if (out.size() >= 8) {
                    return null;
                }
            }
        }
        return out;
    }

    /**
     * Clamps {@code eye} to the nearest reachable point inside a block AABB (inset slightly from edges).
     */
    public static double[] closestPointOnUnitBox(
            double ex, double ey, double ez,
            int bx, int by, int bz,
            double inset
    ) {
        double lo = Math.max(0.0, Math.min(0.45, inset));
        double hi = 1.0 - lo;
        return new double[]{
                Math.max(bx + lo, Math.min(bx + hi, ex)),
                Math.max(by + lo, Math.min(by + hi, ey)),
                Math.max(bz + lo, Math.min(bz + hi, ez))
        };
    }

    /**
     * Fast ray-AABB intersection slab test used for fireball reflection &amp; visibility checks.
     */
    public static boolean rayIntersectsBox(
            double ox, double oy, double oz,
            double dx, double dy, double dz,
            double minX, double minY, double minZ,
            double maxX, double maxY, double maxZ,
            double maxDist
    ) {
        double tMin = 0.0;
        double tMax = maxDist;
        double[] origins = {ox, oy, oz};
        double[] dirs = {dx, dy, dz};
        double[] mins = {minX, minY, minZ};
        double[] maxs = {maxX, maxY, maxZ};
        for (int axis = 0; axis < 3; axis++) {
            double d = dirs[axis];
            if (Math.abs(d) < 1e-9) {
                if (origins[axis] < mins[axis] || origins[axis] > maxs[axis]) {
                    return false;
                }
            } else {
                double invD = 1.0 / d;
                double t1 = (mins[axis] - origins[axis]) * invD;
                double t2 = (maxs[axis] - origins[axis]) * invD;
                if (t1 > t2) {
                    double tmp = t1;
                    t1 = t2;
                    t2 = tmp;
                }
                tMin = Math.max(tMin, t1);
                tMax = Math.min(tMax, t2);
                if (tMin > tMax) {
                    return false;
                }
            }
        }
        return tMax >= 0.0 && tMin <= maxDist;
    }

    private static boolean isHarderDefenseKind(DefenseBlockKind candidate, DefenseBlockKind current) {
        return materialRank(candidate) > materialRank(current);
    }

    private static int materialRank(DefenseBlockKind kind) {
        return switch (kind) {
            case OBSIDIAN -> 7;
            case WATER_COVERED -> 6;
            case ENDSTONE -> 5;
            case TERRACOTTA -> 4;
            case WOOD -> 3;
            case GLASS -> 2;
            case WOOL, LADDER_SLAB -> 1;
            case AIR, ORIGINAL_MAP -> 0;
        };
    }

    private static double distSq3(double x1, double y1, double z1, double x2, double y2, double z2) {
        double dx = x1 - x2;
        double dy = y1 - y2;
        double dz = z1 - z2;
        return dx * dx + dy * dy + dz * dz;
    }

    private static long packBlockCoord(int x, int y, int z) {
        return ((x & 0x3FFFFFFL) << 38) | (((y + 512L) & 0xFFFL) << 26) | (z & 0x3FFFFFFL);
    }
}
