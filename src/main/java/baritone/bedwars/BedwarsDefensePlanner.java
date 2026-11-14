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

import java.util.List;

/**
 * Pure-Java Bedwars tactical solvers:
 * <ul>
 *   <li>Edge Case C: X-Ray bed defense path evaluator across layers weighted by held shears/pickaxe/axe tiers</li>
 *   <li>Edge Case D: Void exposure &amp; fireball/bow threat multiplier on 1-wide air bridges</li>
 *   <li>Edge Case F: Safe Tactical Voiding check before `#bws recall`</li>
 * </ul>
 */
public final class BedwarsDefensePlanner {

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
}
