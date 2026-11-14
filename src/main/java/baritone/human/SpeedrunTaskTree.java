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

/**
 * Pure inventory-truth goal-conditioned dependency solver for autonomous speedrunning.
 * Every decision is derived strictly from the live {@link InventorySnapshot} on the current tick
 * with zero mutable step counters, and strictly gates mining tasks behind prerequisite tool tiers.
 */
public final class SpeedrunTaskTree {

    private SpeedrunTaskTree() {}

    public enum GoalTarget {
        WOODEN_PICKAXE,
        STONE_PICKAXE,
        STONE_AXE,
        STONE_SWORD,
        FURNACE,
        IRON_PICKAXE,
        BUCKET,
        FLINT_AND_STEEL,
        WATER_BUCKET,
        ANY_PERCENT_DRAGON
    }

    public enum TaskAction {
        DONE,
        PICKUP_DROP,
        MINE_LOGS,
        CRAFT_PLANKS,
        CRAFT_STICKS,
        CRAFT_TABLE,
        PLACE_OR_OPEN_TABLE,
        RECLAIM_CRAFTING_TABLE,
        CRAFT_WOODEN_PICKAXE,
        MINE_COBBLESTONE,
        CRAFT_STONE_PICKAXE,
        CRAFT_STONE_AXE,
        CRAFT_STONE_SWORD,
        CRAFT_FURNACE,
        GATHER_FOOD,
        MINE_IRON_ORE,
        MINE_COAL_FUEL,
        SMELT_IRON,
        CRAFT_IRON_PICKAXE,
        CRAFT_BUCKET,
        MINE_FLINT,
        CRAFT_FLINT_AND_STEEL,
        FILL_WATER_BUCKET,
        NETHER_PORTAL_AND_ENTER,
        HUNT_BLAZES,
        HUNT_PEARLS,
        RETURN_FROM_NETHER,
        CRAFT_BLAZE_POWDER,
        CRAFT_ENDER_EYES,
        STRONGHOLD_AND_ENTER_END,
        END_CRYSTALS_AND_DRAGON
    }

    public record InventorySnapshot(
            int logs,
            int planks,
            int sticks,
            int craftingTablesInInv,
            boolean hasNearbyTable,
            boolean tableScreenOpen,
            int furnacesInInv,
            boolean hasNearbyFurnace,
            boolean furnaceScreenOpen,
            int cobblestone,
            boolean hasWoodenPickaxe,
            boolean hasStonePickaxe,
            boolean hasStoneAxe,
            boolean hasStoneSword,
            boolean hasIronPickaxe,
            boolean hasIronAxe,
            boolean hasIronSword,
            boolean hasBucket,
            boolean hasWaterBucket,
            boolean hasLavaBucket,
            int ironIngots,
            int rawIron,
            int coal,
            int flint,
            boolean hasFlintAndSteel,
            int edibleNutrition,
            int blazeRods,
            int blazePowder,
            int enderPearls,
            int enderEyes,
            boolean inNether,
            boolean inEnd,
            boolean nearbyNetherPortalLit,
            boolean nearbyEndPortalLit,
            boolean dragonDead,
            boolean matchingDropNearby
    ) {
        public static InventorySnapshot empty() {
            return new InventorySnapshot(
                    0, 0, 0, 0, false, false,
                    0, false, false, 0,
                    false, false, false, false,
                    false, false, false,
                    false, false, false,
                    0, 0, 0, 0, false,
                    0, 0, 0, 0, 0,
                    false, false, false, false, false, false
            );
        }

        public boolean hasAnyPickaxe() {
            return hasWoodenPickaxe || hasStonePickaxe || hasIronPickaxe;
        }

        public boolean hasStonePickOrBetter() {
            return hasStonePickaxe || hasIronPickaxe;
        }

        public boolean hasAnyBucket() {
            return hasBucket || hasWaterBucket || hasLavaBucket;
        }

        public boolean hasTableAvailable() {
            return tableScreenOpen || hasNearbyTable || craftingTablesInInv > 0;
        }

        public boolean hasFurnaceAvailable() {
            return furnaceScreenOpen || hasNearbyFurnace || furnacesInInv > 0;
        }

        public int totalFuelEquivalent() {
            return coal + (planks / 2);
        }
    }

    public record TaskResolution(
            TaskAction action,
            GoalTarget activeSubgoal,
            String reason
    ) {}

    /**
     * Pure strict tool-gating rule matching Baritone's A* / mining legality check:
     * returns {@code Double.POSITIVE_INFINITY} when a block requires a tool tier the player lacks.
     */
    public static double evaluateStrictToolCost(boolean strictToolGating, boolean requiresCorrectTool, boolean hasCorrectTool, double normalTicks) {
        if (strictToolGating && requiresCorrectTool && !hasCorrectTool) {
            return Double.POSITIVE_INFINITY;
        }
        return normalTicks;
    }

    /**
     * Resolves the immediate leaf action required to obtain {@code target} given live {@code inv}.
     */
    public static TaskResolution resolveGoal(GoalTarget target, InventorySnapshot inv) {
        if (target == GoalTarget.ANY_PERCENT_DRAGON) {
            return resolveAnyPercent(inv);
        }
        return switch (target) {
            case WOODEN_PICKAXE -> resolveWoodenPickaxe(inv);
            case STONE_PICKAXE -> resolveStonePickaxe(inv);
            case STONE_AXE -> resolveStoneTool(inv, GoalTarget.STONE_AXE, TaskAction.CRAFT_STONE_AXE, inv.hasStoneAxe() || inv.hasIronAxe());
            case STONE_SWORD -> resolveStoneSword(inv);
            case FURNACE -> resolveFurnace(inv);
            case IRON_PICKAXE -> resolveIronPickaxe(inv);
            case BUCKET -> resolveBucket(inv);
            case FLINT_AND_STEEL -> resolveFlintAndSteel(inv);
            case WATER_BUCKET -> resolveWaterBucket(inv);
            case ANY_PERCENT_DRAGON -> resolveAnyPercent(inv);
        };
    }

    /**
     * Resolves the full Any% Ender Dragon progression strictly from current inventory and dimension state.
     */
    public static TaskResolution resolveAnyPercent(InventorySnapshot inv) {
        if (inv.dragonDead()) {
            return new TaskResolution(TaskAction.DONE, GoalTarget.ANY_PERCENT_DRAGON, "Dragon defeated");
        }
        if (inv.inEnd()) {
            return new TaskResolution(TaskAction.END_CRYSTALS_AND_DRAGON, GoalTarget.ANY_PERCENT_DRAGON, "Finish crystals and Ender Dragon");
        }
        if (inv.nearbyEndPortalLit()) {
            return new TaskResolution(TaskAction.STRONGHOLD_AND_ENTER_END, GoalTarget.ANY_PERCENT_DRAGON, "Enter End portal");
        }

        int effectiveBlazePowder = inv.blazePowder() + inv.blazeRods() * 2;
        int totalPearlsAndEyes = inv.enderPearls() + inv.enderEyes();
        if (inv.inNether()) {
            if (inv.blazeRods() < 6 && effectiveBlazePowder < 12) {
                return new TaskResolution(TaskAction.HUNT_BLAZES, GoalTarget.ANY_PERCENT_DRAGON, "Gather blaze rods in Nether Fortress");
            }
            if (totalPearlsAndEyes < 12) {
                return new TaskResolution(TaskAction.HUNT_PEARLS, GoalTarget.ANY_PERCENT_DRAGON, "Gather ender pearls");
            }
            return new TaskResolution(TaskAction.RETURN_FROM_NETHER, GoalTarget.ANY_PERCENT_DRAGON, "Return to Overworld");
        }

        if (totalPearlsAndEyes >= 12 && (effectiveBlazePowder >= 12 || inv.enderEyes() >= 12)) {
            if (inv.blazeRods() > 0 && inv.blazePowder() < inv.enderPearls()) {
                return new TaskResolution(TaskAction.CRAFT_BLAZE_POWDER, GoalTarget.ANY_PERCENT_DRAGON, "Craft blaze powder");
            }
            if (inv.enderEyes() < 12 && inv.enderPearls() > 0 && inv.blazePowder() > 0) {
                return new TaskResolution(TaskAction.CRAFT_ENDER_EYES, GoalTarget.ANY_PERCENT_DRAGON, "Craft eyes of ender");
            }
            return new TaskResolution(TaskAction.STRONGHOLD_AND_ENTER_END, GoalTarget.ANY_PERCENT_DRAGON, "Triangulate stronghold and enter End");
        }

        // Phase A: Strict wood -> table -> wooden pickaxe -> stone pickaxe -> stone combat/utility gear
        if (!inv.hasAnyPickaxe()) {
            return resolveGoal(GoalTarget.WOODEN_PICKAXE, inv);
        }
        if (!inv.hasStonePickOrBetter()) {
            return resolveGoal(GoalTarget.STONE_PICKAXE, inv);
        }
        if (!inv.hasStoneAxe() && !inv.hasIronAxe()) {
            return resolveGoal(GoalTarget.STONE_AXE, inv);
        }
        if (!inv.hasStoneSword() && !inv.hasIronSword()) {
            return resolveGoal(GoalTarget.STONE_SWORD, inv);
        }
        if (!inv.hasFurnaceAvailable()) {
            return resolveGoal(GoalTarget.FURNACE, inv);
        }
        if (inv.edibleNutrition() < 12) {
            return new TaskResolution(TaskAction.GATHER_FOOD, GoalTarget.ANY_PERCENT_DRAGON, "Gather surface food");
        }

        // Phase B: Strict Iron Pickaxe, Bucket, Flint & Steel, Water Bucket
        if (!inv.hasIronPickaxe()) {
            return resolveGoal(GoalTarget.IRON_PICKAXE, inv);
        }
        if (!inv.hasAnyBucket()) {
            return resolveGoal(GoalTarget.BUCKET, inv);
        }
        if (!inv.hasFlintAndSteel()) {
            return resolveGoal(GoalTarget.FLINT_AND_STEEL, inv);
        }
        if (!inv.hasWaterBucket()) {
            return resolveGoal(GoalTarget.WATER_BUCKET, inv);
        }

        // Phase C: Portal -> Fortress -> Stronghold -> End
        return new TaskResolution(TaskAction.NETHER_PORTAL_AND_ENTER, GoalTarget.ANY_PERCENT_DRAGON, "Build/light Nether portal and enter");
    }

    private static TaskResolution resolveWoodenPickaxe(InventorySnapshot inv) {
        if (inv.hasAnyPickaxe()) {
            return new TaskResolution(TaskAction.DONE, GoalTarget.WOODEN_PICKAXE, "Already have pickaxe");
        }
        int planksForPick = 3;
        int planksForSticks = inv.sticks() >= 2 ? 0 : 2;
        int planksForTable = inv.hasTableAvailable() ? 0 : 4;
        int planksNeeded = planksForPick + planksForSticks + planksForTable;

        TaskResolution planksStep = ensurePlanks(inv, planksNeeded, GoalTarget.WOODEN_PICKAXE);
        if (planksStep != null) {
            return planksStep;
        }
        if (inv.sticks() < 2) {
            return new TaskResolution(TaskAction.CRAFT_STICKS, GoalTarget.WOODEN_PICKAXE, "Craft sticks for wooden pickaxe");
        }
        if (!inv.tableScreenOpen()) {
            if (!inv.hasNearbyTable() && inv.craftingTablesInInv() < 1) {
                return new TaskResolution(TaskAction.CRAFT_TABLE, GoalTarget.WOODEN_PICKAXE, "Craft crafting table");
            }
            return new TaskResolution(TaskAction.PLACE_OR_OPEN_TABLE, GoalTarget.WOODEN_PICKAXE, "Place/open crafting table");
        }
        return new TaskResolution(TaskAction.CRAFT_WOODEN_PICKAXE, GoalTarget.WOODEN_PICKAXE, "Craft wooden pickaxe");
    }

    private static TaskResolution resolveStonePickaxe(InventorySnapshot inv) {
        if (inv.hasStonePickOrBetter()) {
            return new TaskResolution(TaskAction.DONE, GoalTarget.STONE_PICKAXE, "Already have stone+ pickaxe");
        }
        return resolveStoneRecipe(inv, GoalTarget.STONE_PICKAXE, 3, 2, TaskAction.CRAFT_STONE_PICKAXE, "stone pickaxe");
    }

    private static TaskResolution resolveStoneTool(InventorySnapshot inv, GoalTarget goal, TaskAction craftAction, boolean alreadyHas) {
        if (alreadyHas) {
            return new TaskResolution(TaskAction.DONE, goal, "Already crafted " + goal.name());
        }
        return resolveStoneRecipe(inv, goal, 3, 2, craftAction, goal.name().toLowerCase());
    }

    private static TaskResolution resolveStoneSword(InventorySnapshot inv) {
        if (inv.hasStoneSword() || inv.hasIronSword()) {
            return new TaskResolution(TaskAction.DONE, GoalTarget.STONE_SWORD, "Already have stone+ sword");
        }
        return resolveStoneRecipe(inv, GoalTarget.STONE_SWORD, 2, 1, TaskAction.CRAFT_STONE_SWORD, "stone sword");
    }

    private static TaskResolution resolveFurnace(InventorySnapshot inv) {
        if (inv.hasFurnaceAvailable()) {
            return new TaskResolution(TaskAction.DONE, GoalTarget.FURNACE, "Already have furnace");
        }
        return resolveStoneRecipe(inv, GoalTarget.FURNACE, 8, 0, TaskAction.CRAFT_FURNACE, "furnace");
    }

    private static TaskResolution resolveStoneRecipe(
            InventorySnapshot inv,
            GoalTarget subgoal,
            int cobbleRequired,
            int sticksRequired,
            TaskAction craftAction,
            String label
    ) {
        // Strict tool dependency: cannot obtain cobblestone cleanly without at least a wooden pickaxe!
        if (inv.cobblestone() < cobbleRequired) {
            if (!inv.hasAnyPickaxe()) {
                return resolveWoodenPickaxe(inv);
            }
            return new TaskResolution(TaskAction.MINE_COBBLESTONE, subgoal, "Mine cobblestone for " + label);
        }
        if (sticksRequired > 0 && inv.sticks() < sticksRequired) {
            TaskResolution planksStep = ensurePlanks(inv, 2 + (inv.hasTableAvailable() ? 0 : 4), subgoal);
            if (planksStep != null) {
                return planksStep;
            }
            return new TaskResolution(TaskAction.CRAFT_STICKS, subgoal, "Craft sticks for " + label);
        }
        if (!inv.tableScreenOpen()) {
            if (!inv.hasNearbyTable() && inv.craftingTablesInInv() < 1) {
                TaskResolution planksStep = ensurePlanks(inv, 4, subgoal);
                if (planksStep != null) {
                    return planksStep;
                }
                return new TaskResolution(TaskAction.CRAFT_TABLE, subgoal, "Craft table for " + label);
            }
            return new TaskResolution(TaskAction.PLACE_OR_OPEN_TABLE, subgoal, "Open table for " + label);
        }
        return new TaskResolution(craftAction, subgoal, "Craft " + label);
    }

    private static TaskResolution resolveIronPickaxe(InventorySnapshot inv) {
        if (inv.hasIronPickaxe()) {
            return new TaskResolution(TaskAction.DONE, GoalTarget.IRON_PICKAXE, "Already have iron pickaxe");
        }
        int neededIron = 3;
        if (inv.ironIngots() < neededIron) {
            return resolveSmeltIron(inv, neededIron, GoalTarget.IRON_PICKAXE);
        }
        if (inv.sticks() < 2) {
            TaskResolution planksStep = ensurePlanks(inv, 2 + (inv.hasTableAvailable() ? 0 : 4), GoalTarget.IRON_PICKAXE);
            if (planksStep != null) {
                return planksStep;
            }
            return new TaskResolution(TaskAction.CRAFT_STICKS, GoalTarget.IRON_PICKAXE, "Craft sticks for iron pickaxe");
        }
        if (!inv.tableScreenOpen()) {
            if (!inv.hasNearbyTable() && inv.craftingTablesInInv() < 1) {
                TaskResolution planksStep = ensurePlanks(inv, 4, GoalTarget.IRON_PICKAXE);
                if (planksStep != null) {
                    return planksStep;
                }
                return new TaskResolution(TaskAction.CRAFT_TABLE, GoalTarget.IRON_PICKAXE, "Craft table for iron pickaxe");
            }
            return new TaskResolution(TaskAction.PLACE_OR_OPEN_TABLE, GoalTarget.IRON_PICKAXE, "Open table for iron pickaxe");
        }
        return new TaskResolution(TaskAction.CRAFT_IRON_PICKAXE, GoalTarget.IRON_PICKAXE, "Craft iron pickaxe");
    }

    private static TaskResolution resolveBucket(InventorySnapshot inv) {
        if (inv.hasAnyBucket()) {
            return new TaskResolution(TaskAction.DONE, GoalTarget.BUCKET, "Already have bucket");
        }
        if (inv.ironIngots() < 3) {
            return resolveSmeltIron(inv, 3, GoalTarget.BUCKET);
        }
        if (!inv.tableScreenOpen()) {
            if (!inv.hasNearbyTable() && inv.craftingTablesInInv() < 1) {
                TaskResolution planksStep = ensurePlanks(inv, 4, GoalTarget.BUCKET);
                if (planksStep != null) {
                    return planksStep;
                }
                return new TaskResolution(TaskAction.CRAFT_TABLE, GoalTarget.BUCKET, "Craft table for bucket");
            }
            return new TaskResolution(TaskAction.PLACE_OR_OPEN_TABLE, GoalTarget.BUCKET, "Open table for bucket");
        }
        return new TaskResolution(TaskAction.CRAFT_BUCKET, GoalTarget.BUCKET, "Craft bucket");
    }

    private static TaskResolution resolveFlintAndSteel(InventorySnapshot inv) {
        if (inv.hasFlintAndSteel()) {
            return new TaskResolution(TaskAction.DONE, GoalTarget.FLINT_AND_STEEL, "Already have flint and steel");
        }
        if (inv.flint() < 1) {
            return new TaskResolution(TaskAction.MINE_FLINT, GoalTarget.FLINT_AND_STEEL, "Mine gravel for flint");
        }
        if (inv.ironIngots() < 1) {
            return resolveSmeltIron(inv, 1, GoalTarget.FLINT_AND_STEEL);
        }
        return new TaskResolution(TaskAction.CRAFT_FLINT_AND_STEEL, GoalTarget.FLINT_AND_STEEL, "Craft flint and steel");
    }

    private static TaskResolution resolveWaterBucket(InventorySnapshot inv) {
        if (inv.hasWaterBucket()) {
            return new TaskResolution(TaskAction.DONE, GoalTarget.WATER_BUCKET, "Already have water bucket");
        }
        if (!inv.hasBucket()) {
            return resolveBucket(inv);
        }
        return new TaskResolution(TaskAction.FILL_WATER_BUCKET, GoalTarget.WATER_BUCKET, "Fill water bucket");
    }

    private static TaskResolution resolveSmeltIron(InventorySnapshot inv, int targetIngots, GoalTarget subgoal) {
        if (!inv.hasStonePickOrBetter()) {
            return resolveStonePickaxe(inv);
        }
        int stillNeeded = Math.max(0, targetIngots - inv.ironIngots());
        if (inv.rawIron() < stillNeeded) {
            return new TaskResolution(TaskAction.MINE_IRON_ORE, subgoal, "Mine iron ore with stone+ pickaxe");
        }
        if (!inv.hasFurnaceAvailable()) {
            return resolveFurnace(inv);
        }
        if (!inv.furnaceScreenOpen() && inv.totalFuelEquivalent() < 1) {
            return new TaskResolution(TaskAction.MINE_COAL_FUEL, subgoal, "Mine coal for furnace smelting");
        }
        return new TaskResolution(TaskAction.SMELT_IRON, subgoal, "Smelt raw iron in furnace");
    }

    private static TaskResolution ensurePlanks(InventorySnapshot inv, int requiredPlanks, GoalTarget subgoal) {
        if (inv.planks() >= requiredPlanks) {
            return null;
        }
        int missing = requiredPlanks - inv.planks();
        int logsNeeded = (missing + 3) / 4;
        if (inv.logs() < logsNeeded) {
            return new TaskResolution(TaskAction.MINE_LOGS, subgoal, "Gather logs (" + inv.logs() + "/" + logsNeeded + ")");
        }
        return new TaskResolution(TaskAction.CRAFT_PLANKS, subgoal, "Craft planks from logs");
    }
}
