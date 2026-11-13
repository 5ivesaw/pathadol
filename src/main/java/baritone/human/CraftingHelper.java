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

import baritone.api.utils.IPlayerContext;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.AbstractFurnaceMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.function.Predicate;

/**
 * Deterministic grid-clicking helper for 2x2 Inventory crafting, 3x3 Crafting Table recipes,
 * and Furnace smelting in MC 26.3 without flaky recipe-book state assumptions.
 */
public final class CraftingHelper {

    public enum RecipeKind {
        PLANKS_FROM_LOG(false, 4),
        STICKS(false, 4),
        CRAFTING_TABLE(false, 1),
        FLINT_AND_STEEL(false, 1),
        BLAZE_POWDER(false, 2),
        ENDER_EYE(false, 1),
        WOODEN_PICKAXE(true, 1),
        STONE_PICKAXE(true, 1),
        STONE_AXE(true, 1),
        STONE_SWORD(true, 1),
        FURNACE(true, 1),
        BUCKET(true, 1),
        IRON_PICKAXE(true, 1),
        IRON_AXE(true, 1),
        IRON_SWORD(true, 1),
        SHIELD(true, 1),
        IRON_CHESTPLATE(true, 1),
        IRON_HELMET(true, 1),
        IRON_LEGGINGS(true, 1),
        IRON_BOOTS(true, 1),
        GOLD_HELMET(true, 1),
        BREAD(true, 1),
        BOW(true, 1);

        public final boolean needsTable;
        public final int outputCount;

        RecipeKind(boolean needsTable, int outputCount) {
            this.needsTable = needsTable;
            this.outputCount = outputCount;
        }
    }

    public enum StepResult {
        IN_PROGRESS,
        COMPLETED,
        NEED_CRAFTING_TABLE,
        MISSING_INGREDIENTS
    }

    private CraftingHelper() {}

    public static boolean isLogItem(Item item) {
        if (item == null || item == Items.AIR) {
            return false;
        }
        String p = BuiltInRegistries.ITEM.getKey(item).getPath();
        return (p.endsWith("_log") || p.endsWith("_stem")) && !p.startsWith("stripped_") || p.contains("_log");
    }

    public static boolean isPlankItem(Item item) {
        if (item == null || item == Items.AIR) {
            return false;
        }
        return BuiltInRegistries.ITEM.getKey(item).getPath().endsWith("_planks");
    }

    public static boolean isCobbleMaterial(Item item) {
        return item == Items.COBBLESTONE || item == Items.COBBLED_DEEPSLATE || item == Items.BLACKSTONE;
    }

    public static int countMatching(LocalPlayer player, Predicate<ItemStack> filter) {
        int total = 0;
        var items = player.getInventory().getNonEquipmentItems();
        for (ItemStack st : items) {
            if (!st.isEmpty() && filter.test(st)) {
                total += st.getCount();
            }
        }
        return total;
    }

    public static int countItem(LocalPlayer player, Item item) {
        return countMatching(player, st -> st.is(item));
    }

    /**
     * Advances one crafting step in either the open 3x3 {@link CraftingMenu} or 2x2 {@code inventoryMenu}.
     */
    public static StepResult stepCraft(IPlayerContext ctx, RecipeKind recipe) {
        LocalPlayer player = ctx.player();
        if (player == null) {
            return StepResult.MISSING_INGREDIENTS;
        }
        AbstractContainerMenu menu = player.containerMenu;
        boolean inTable = menu instanceof CraftingMenu;
        if (recipe.needsTable && !inTable) {
            return StepResult.NEED_CRAFTING_TABLE;
        }

        // First put away anything currently held on the cursor
        if (!menu.getCarried().isEmpty()) {
            int emptyInv = findEmptyStorageMenuSlot(menu, inTable ? 10 : 9, inTable ? 45 : 44);
            if (emptyInv >= 0) {
                ctx.playerController().windowClick(menu.containerId, emptyInv, 0, ContainerInput.PICKUP, player);
                return StepResult.IN_PROGRESS;
            }
            return StepResult.MISSING_INGREDIENTS;
        }

        // If the desired craft output is already sitting in result slot 0, collect it!
        ItemStack resultStack = menu.getSlot(0).getItem();
        if (!resultStack.isEmpty() && matchesExpectedResult(resultStack, recipe)) {
            ctx.playerController().windowClick(menu.containerId, 0, 0, ContainerInput.QUICK_MOVE, player);
            return StepResult.COMPLETED;
        }

        int gridStart = 1;
        int gridCount = inTable ? 9 : 4;
        int storageStart = inTable ? 10 : 9;
        int storageEnd = inTable ? 45 : 44;

        IngredientSlot[] pattern = buildPattern(recipe, inTable);
        if (pattern == null) {
            return StepResult.MISSING_INGREDIENTS;
        }

        // Clear wrong items out of grid slots if a previous interrupted craft left debris
        for (int g = gridStart; g < gridStart + gridCount; g++) {
            ItemStack inGrid = menu.getSlot(g).getItem();
            if (!inGrid.isEmpty() && !slotMatchesPattern(g, inGrid, pattern)) {
                ctx.playerController().windowClick(menu.containerId, g, 0, ContainerInput.QUICK_MOVE, player);
                return StepResult.IN_PROGRESS;
            }
        }

        // Fill next missing slot in the pattern using single right-click placement
        for (IngredientSlot req : pattern) {
            ItemStack cur = menu.getSlot(req.gridSlot).getItem();
            if (!cur.isEmpty() && req.matcher.test(cur)) {
                continue;
            }
            // For wood planks, keep same wood type if another grid slot already placed one
            int sourceMenuSlot = findMatchingStorageSlot(menu, storageStart, storageEnd, req.matcher);
            if (sourceMenuSlot < 0) {
                return StepResult.MISSING_INGREDIENTS;
            }
            ctx.playerController().windowClick(menu.containerId, sourceMenuSlot, 0, ContainerInput.PICKUP, player);
            ctx.playerController().windowClick(menu.containerId, req.gridSlot, 1, ContainerInput.PICKUP, player);
            if (!menu.getCarried().isEmpty()) {
                ctx.playerController().windowClick(menu.containerId, sourceMenuSlot, 0, ContainerInput.PICKUP, player);
            }
            return StepResult.IN_PROGRESS;
        }

        // All ingredients present; result will appear and get picked up cleanly on next tick
        return StepResult.IN_PROGRESS;
    }

    /**
     * Feeds ore + fuel and extracts smelted items inside an open {@link AbstractFurnaceMenu}.
     */
    public static boolean stepFurnaceSmelt(
            IPlayerContext ctx,
            Predicate<ItemStack> inputMatcher,
            Predicate<ItemStack> fuelMatcher
    ) {
        LocalPlayer player = ctx.player();
        if (player == null || !(player.containerMenu instanceof AbstractFurnaceMenu menu)) {
            return false;
        }
        if (!menu.getCarried().isEmpty()) {
            int empty = findEmptyStorageMenuSlot(menu, 3, 38);
            if (empty >= 0) {
                ctx.playerController().windowClick(menu.containerId, empty, 0, ContainerInput.PICKUP, player);
            }
            return true;
        }

        ItemStack out = menu.getSlot(2).getItem();
        if (!out.isEmpty()) {
            ctx.playerController().windowClick(menu.containerId, 2, 0, ContainerInput.QUICK_MOVE, player);
            return true;
        }

        ItemStack curInput = menu.getSlot(0).getItem();
        if (curInput.isEmpty() || inputMatcher.test(curInput)) {
            int src = findMatchingStorageSlot(menu, 3, 38, inputMatcher);
            if (src >= 0 && curInput.getCount() < 16) {
                ctx.playerController().windowClick(menu.containerId, src, 0, ContainerInput.QUICK_MOVE, player);
                return true;
            }
        }

        ItemStack curFuel = menu.getSlot(1).getItem();
        if (curFuel.isEmpty() && !menu.getSlot(0).getItem().isEmpty()) {
            int fuelSlot = findMatchingStorageSlot(menu, 3, 38, fuelMatcher);
            if (fuelSlot >= 0) {
                ctx.playerController().windowClick(menu.containerId, fuelSlot, 0, ContainerInput.QUICK_MOVE, player);
                return true;
            }
        }
        return !menu.getSlot(0).getItem().isEmpty();
    }

    public static BlockPos findNearbyBlock(IPlayerContext ctx, Predicate<net.minecraft.world.level.block.Block> pred, int radius) {
        BlockPos feet = ctx.playerFeet();
        BlockPos best = null;
        double bestDistSq = Double.POSITIVE_INFINITY;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -2; dy <= 3; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    BlockPos p = feet.offset(dx, dy, dz);
                    if (pred.test(ctx.world().getBlockState(p).getBlock())) {
                        double d = p.distSqr(feet);
                        if (d < bestDistSq) {
                            bestDistSq = d;
                            best = p;
                        }
                    }
                }
            }
        }
        return best;
    }

    private record IngredientSlot(int gridSlot, Predicate<ItemStack> matcher) {}

    private static IngredientSlot[] buildPattern(RecipeKind r, boolean inTable) {
        Predicate<ItemStack> log = st -> isLogItem(st.getItem());
        Predicate<ItemStack> plank = st -> isPlankItem(st.getItem());
        Predicate<ItemStack> stick = st -> st.is(Items.STICK);
        Predicate<ItemStack> cobble = st -> isCobbleMaterial(st.getItem());
        Predicate<ItemStack> iron = st -> st.is(Items.IRON_INGOT);
        Predicate<ItemStack> gold = st -> st.is(Items.GOLD_INGOT);

        return switch (r) {
            case PLANKS_FROM_LOG -> new IngredientSlot[]{new IngredientSlot(1, log)};
            case STICKS -> inTable
                    ? new IngredientSlot[]{new IngredientSlot(1, plank), new IngredientSlot(4, plank)}
                    : new IngredientSlot[]{new IngredientSlot(1, plank), new IngredientSlot(3, plank)};
            case CRAFTING_TABLE -> inTable
                    ? new IngredientSlot[]{new IngredientSlot(1, plank), new IngredientSlot(2, plank), new IngredientSlot(4, plank), new IngredientSlot(5, plank)}
                    : new IngredientSlot[]{new IngredientSlot(1, plank), new IngredientSlot(2, plank), new IngredientSlot(3, plank), new IngredientSlot(4, plank)};
            case FLINT_AND_STEEL -> new IngredientSlot[]{
                    new IngredientSlot(1, iron),
                    new IngredientSlot(2, st -> st.is(Items.FLINT))
            };
            case BLAZE_POWDER -> new IngredientSlot[]{new IngredientSlot(1, st -> st.is(Items.BLAZE_ROD))};
            case ENDER_EYE -> new IngredientSlot[]{
                    new IngredientSlot(1, st -> st.is(Items.BLAZE_POWDER)),
                    new IngredientSlot(2, st -> st.is(Items.ENDER_PEARL))
            };
            case WOODEN_PICKAXE -> new IngredientSlot[]{
                    new IngredientSlot(1, plank), new IngredientSlot(2, plank), new IngredientSlot(3, plank),
                    new IngredientSlot(5, stick), new IngredientSlot(8, stick)
            };
            case STONE_PICKAXE -> new IngredientSlot[]{
                    new IngredientSlot(1, cobble), new IngredientSlot(2, cobble), new IngredientSlot(3, cobble),
                    new IngredientSlot(5, stick), new IngredientSlot(8, stick)
            };
            case STONE_AXE -> new IngredientSlot[]{
                    new IngredientSlot(1, cobble), new IngredientSlot(2, cobble),
                    new IngredientSlot(4, cobble), new IngredientSlot(5, stick), new IngredientSlot(8, stick)
            };
            case STONE_SWORD -> new IngredientSlot[]{
                    new IngredientSlot(2, cobble), new IngredientSlot(5, cobble), new IngredientSlot(8, stick)
            };
            case FURNACE -> new IngredientSlot[]{
                    new IngredientSlot(1, cobble), new IngredientSlot(2, cobble), new IngredientSlot(3, cobble),
                    new IngredientSlot(4, cobble), new IngredientSlot(6, cobble),
                    new IngredientSlot(7, cobble), new IngredientSlot(8, cobble), new IngredientSlot(9, cobble)
            };
            case BUCKET -> new IngredientSlot[]{
                    new IngredientSlot(4, iron), new IngredientSlot(6, iron), new IngredientSlot(8, iron)
            };
            case IRON_PICKAXE -> new IngredientSlot[]{
                    new IngredientSlot(1, iron), new IngredientSlot(2, iron), new IngredientSlot(3, iron),
                    new IngredientSlot(5, stick), new IngredientSlot(8, stick)
            };
            case IRON_AXE -> new IngredientSlot[]{
                    new IngredientSlot(1, iron), new IngredientSlot(2, iron),
                    new IngredientSlot(4, iron), new IngredientSlot(5, stick), new IngredientSlot(8, stick)
            };
            case IRON_SWORD -> new IngredientSlot[]{
                    new IngredientSlot(2, iron), new IngredientSlot(5, iron), new IngredientSlot(8, stick)
            };
            case SHIELD -> new IngredientSlot[]{
                    new IngredientSlot(1, plank), new IngredientSlot(2, iron), new IngredientSlot(3, plank),
                    new IngredientSlot(4, plank), new IngredientSlot(5, plank), new IngredientSlot(6, plank),
                    new IngredientSlot(8, plank)
            };
            case IRON_CHESTPLATE -> new IngredientSlot[]{
                    new IngredientSlot(1, iron), new IngredientSlot(3, iron),
                    new IngredientSlot(4, iron), new IngredientSlot(5, iron), new IngredientSlot(6, iron),
                    new IngredientSlot(7, iron), new IngredientSlot(8, iron), new IngredientSlot(9, iron)
            };
            case IRON_HELMET -> new IngredientSlot[]{
                    new IngredientSlot(1, iron), new IngredientSlot(2, iron), new IngredientSlot(3, iron),
                    new IngredientSlot(4, iron), new IngredientSlot(6, iron)
            };
            case IRON_LEGGINGS -> new IngredientSlot[]{
                    new IngredientSlot(1, iron), new IngredientSlot(2, iron), new IngredientSlot(3, iron),
                    new IngredientSlot(4, iron), new IngredientSlot(6, iron),
                    new IngredientSlot(7, iron), new IngredientSlot(9, iron)
            };
            case IRON_BOOTS -> new IngredientSlot[]{
                    new IngredientSlot(4, iron), new IngredientSlot(6, iron),
                    new IngredientSlot(7, iron), new IngredientSlot(9, iron)
            };
            case GOLD_HELMET -> new IngredientSlot[]{
                    new IngredientSlot(1, gold), new IngredientSlot(2, gold), new IngredientSlot(3, gold),
                    new IngredientSlot(4, gold), new IngredientSlot(6, gold)
            };
            case BREAD -> {
                Predicate<ItemStack> wheat = st -> st.is(Items.WHEAT);
                yield new IngredientSlot[]{
                        new IngredientSlot(4, wheat), new IngredientSlot(5, wheat), new IngredientSlot(6, wheat)
                };
            }
            case BOW -> {
                Predicate<ItemStack> string = st -> st.is(Items.STRING);
                yield new IngredientSlot[]{
                        new IngredientSlot(2, stick), new IngredientSlot(4, stick), new IngredientSlot(8, stick),
                        new IngredientSlot(3, string), new IngredientSlot(6, string), new IngredientSlot(9, string)
                };
            }
        };
    }

    private static boolean slotMatchesPattern(int gridSlot, ItemStack inSlot, IngredientSlot[] pattern) {
        for (IngredientSlot req : pattern) {
            if (req.gridSlot == gridSlot) {
                return req.matcher.test(inSlot);
            }
        }
        return false;
    }

    private static boolean matchesExpectedResult(ItemStack result, RecipeKind recipe) {
        return switch (recipe) {
            case PLANKS_FROM_LOG -> isPlankItem(result.getItem());
            case STICKS -> result.is(Items.STICK);
            case CRAFTING_TABLE -> result.is(Items.CRAFTING_TABLE);
            case FLINT_AND_STEEL -> result.is(Items.FLINT_AND_STEEL);
            case BLAZE_POWDER -> result.is(Items.BLAZE_POWDER);
            case ENDER_EYE -> result.is(Items.ENDER_EYE);
            case WOODEN_PICKAXE -> result.is(Items.WOODEN_PICKAXE);
            case STONE_PICKAXE -> result.is(Items.STONE_PICKAXE);
            case STONE_AXE -> result.is(Items.STONE_AXE);
            case STONE_SWORD -> result.is(Items.STONE_SWORD);
            case FURNACE -> result.is(Items.FURNACE);
            case BUCKET -> result.is(Items.BUCKET);
            case IRON_PICKAXE -> result.is(Items.IRON_PICKAXE);
            case IRON_AXE -> result.is(Items.IRON_AXE);
            case IRON_SWORD -> result.is(Items.IRON_SWORD);
            case SHIELD -> result.is(Items.SHIELD);
            case IRON_CHESTPLATE -> result.is(Items.IRON_CHESTPLATE);
            case IRON_HELMET -> result.is(Items.IRON_HELMET);
            case IRON_LEGGINGS -> result.is(Items.IRON_LEGGINGS);
            case IRON_BOOTS -> result.is(Items.IRON_BOOTS);
            case GOLD_HELMET -> result.is(Items.GOLD_HELMET);
            case BREAD -> result.is(Items.BREAD);
            case BOW -> result.is(Items.BOW);
        };
    }

    private static int findMatchingStorageSlot(AbstractContainerMenu menu, int start, int end, Predicate<ItemStack> matcher) {
        for (int s = start; s <= end && s < menu.slots.size(); s++) {
            ItemStack st = menu.getSlot(s).getItem();
            if (!st.isEmpty() && matcher.test(st)) {
                return s;
            }
        }
        return -1;
    }

    private static int findEmptyStorageMenuSlot(AbstractContainerMenu menu, int start, int end) {
        for (int s = start; s <= end && s < menu.slots.size(); s++) {
            if (menu.getSlot(s).getItem().isEmpty()) {
                return s;
            }
        }
        return -1;
    }
}
