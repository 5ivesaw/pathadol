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
import baritone.api.utils.IPlayerContext;
import baritone.behavior.InventoryBehavior;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.CraftingScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.AbstractFurnaceMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.BoatItem;
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
        GOLDEN_HELMET(true, 1),
        BREAD(true, 1),
        BOW(true, 1),
        BOAT(true, 1);

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

    private static int guiLastSlot = 18;
    private static int guiCooldownTicks = 0;
    private static long lastTickWorldTime = Long.MIN_VALUE;
    private static int awaitingOutputTicks = 0;
    private static RecipeKind lastAwaitingRecipe = null;

    private CraftingHelper() {}

    /**
     * Enforces P6 Fitts'-law human cursor travel pacing across GUI slots (maximum 1 click per human beat)
     * and smoothly steers the visible bot cursor in {@link BotInventoryUi} when a container screen is open.
     */
    public static boolean advanceAndReadyGui(IPlayerContext ctx, int nextSlot) {
        if (ctx != null) {
            BotInventoryUi.onTickClient(ctx);
        }
        Screen sc = (ctx != null && ctx.minecraft() != null && ctx.minecraft().gui != null)
                ? ctx.minecraft().gui.screen()
                : null;
        if (sc instanceof AbstractContainerScreen<?> cs) {
            AbstractContainerMenu menu = cs.getMenu();
            Slot targetSlot = (menu != null && nextSlot >= 0 && nextSlot < menu.slots.size())
                    ? menu.getSlot(nextSlot)
                    : null;
            if (targetSlot != null) {
                int travelTicks = !Baritone.settings().humanMode.value
                        ? 1
                        : Math.max(1, InventoryBehavior.computeFittsSlotDelayTicks(guiLastSlot, Math.max(0, nextSlot)));
                long now = ctx.world() != null ? ctx.world().getGameTime() : (lastTickWorldTime + 1L);
                long seed = now * 1315423911L + guiLastSlot * 31L + nextSlot;
                if (!BotInventoryUi.moveCursorToSlot(cs, targetSlot, travelTicks, seed)) {
                    return false;
                }
                guiLastSlot = Math.max(0, nextSlot);
                BotInventoryUi.notifySlotClicked();
                return true;
            }
        }
        if (!Baritone.settings().humanMode.value) {
            return true;
        }
        long now = ctx.world() != null ? ctx.world().getGameTime() : (lastTickWorldTime + 1L);
        if (now != lastTickWorldTime) {
            lastTickWorldTime = now;
            if (guiCooldownTicks > 0) {
                guiCooldownTicks--;
            }
        }
        if (guiCooldownTicks > 0) {
            return false;
        }
        guiCooldownTicks = Math.max(1, InventoryBehavior.computeFittsSlotDelayTicks(guiLastSlot, Math.max(0, nextSlot)) - 1);
        guiLastSlot = Math.max(0, nextSlot);
        return true;
    }

    /**
     * Smoothly hovers the visible bot cursor over {@code targetMenuSlot} without clicking yet.
     */
    public static void hoverGuiSlot(IPlayerContext ctx, int targetMenuSlot) {
        if (ctx == null || ctx.minecraft() == null || ctx.minecraft().gui == null) {
            return;
        }
        BotInventoryUi.onTickClient(ctx);
        if (ctx.minecraft().gui.screen() instanceof AbstractContainerScreen<?> cs && cs.getMenu() != null) {
            if (targetMenuSlot >= 0 && targetMenuSlot < cs.getMenu().slots.size()) {
                int travelTicks = !Baritone.settings().humanMode.value
                        ? 1
                        : Math.max(1, InventoryBehavior.computeFittsSlotDelayTicks(guiLastSlot, targetMenuSlot));
                long now = ctx.world() != null ? ctx.world().getGameTime() : 1L;
                BotInventoryUi.moveCursorToSlot(cs, cs.getMenu().getSlot(targetMenuSlot), travelTicks, now ^ targetMenuSlot);
            }
        }
    }

    public static void resetGuiPacer() {
        guiCooldownTicks = 0;
        guiLastSlot = 18;
        awaitingOutputTicks = 0;
        lastAwaitingRecipe = null;
    }

    public static boolean isBotGuiActive() {
        return BotInventoryUi.isBotCursorActive();
    }

    /**
     * Safely returns any carried or crafting-grid item back to storage before closing any open container
     * or inventory screen.
     */
    public static boolean safeCloseContainer(IPlayerContext ctx) {
        if (ctx == null) {
            return true;
        }
        BotInventoryUi.onTickClient(ctx);
        LocalPlayer p = ctx.player();
        if (p == null) {
            return true;
        }
        AbstractContainerMenu menu = p.containerMenu;
        boolean hasContainerScreen = ctx.minecraft() != null
                && ctx.minecraft().gui != null
                && ctx.minecraft().gui.screen() instanceof AbstractContainerScreen<?>;
        if (menu == p.inventoryMenu && !hasContainerScreen) {
            BotInventoryUi.resetCursorState();
            return true;
        }
        if (menu != null) {
            boolean inTable = menu instanceof CraftingMenu;
            boolean inInv = menu == p.inventoryMenu;
            int storageStart = inTable ? 10 : (inInv ? 9 : 3);
            int storageEnd = inTable ? 45 : (inInv ? 44 : 38);
            if (!menu.getCarried().isEmpty()) {
                int dest = findReturnStorageMenuSlot(menu, storageStart, storageEnd, menu.getCarried());
                if (dest >= 0) {
                    if (!advanceAndReadyGui(ctx, dest)) {
                        return false;
                    }
                    ctx.playerController().windowClick(menu.containerId, dest, 0, ContainerInput.PICKUP, p);
                    return false;
                }
            }
            if (inTable || inInv) {
                int gridEnd = inTable ? 9 : 4;
                for (int g = 1; g <= gridEnd && g < menu.slots.size(); g++) {
                    if (!menu.getSlot(g).getItem().isEmpty()) {
                        if (!advanceAndReadyGui(ctx, g)) {
                            return false;
                        }
                        ctx.playerController().windowClick(menu.containerId, g, 0, ContainerInput.QUICK_MOVE, p);
                        return false;
                    }
                }
            }
        }
        BotInventoryUi.closeBotScreen(ctx);
        resetGuiPacer();
        return true;
    }

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

    public static boolean isBoatWoodPlankItem(Item item) {
        if (!isPlankItem(item)) {
            return false;
        }
        String path = BuiltInRegistries.ITEM.getKey(item).getPath();
        return !path.equals("crimson_planks") && !path.equals("warped_planks");
    }

    public static boolean isBoatItem(Item item) {
        if (item == null || item == Items.AIR) {
            return false;
        }
        String path = BuiltInRegistries.ITEM.getKey(item).getPath();
        if (path.endsWith("_chest_boat") || path.endsWith("_chest_raft")) {
            return false;
        }
        return item instanceof BoatItem || path.endsWith("_boat") || path.equals("bamboo_raft");
    }

    public static int countBoats(LocalPlayer player) {
        if (player == null) {
            return 0;
        }
        int total = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack st = player.getInventory().getItem(i);
            if (!st.isEmpty() && isBoatItem(st.getItem())) {
                total += st.getCount();
            }
        }
        if (isBoatItem(player.getOffhandItem().getItem())) {
            total += player.getOffhandItem().getCount();
        }
        return total;
    }

    public static int findBoatInventorySlot(LocalPlayer player) {
        if (player == null) {
            return -1;
        }
        for (int i = 0; i < 36; i++) {
            ItemStack st = player.getInventory().getItem(i);
            if (!st.isEmpty() && isBoatItem(st.getItem())) {
                return i;
            }
        }
        return -1;
    }

    public static boolean hasBoatCraftPlanks(LocalPlayer player) {
        return findBestSingleWoodPlankInPlayer(player, 5) != null;
    }

    public static Item findBestSingleWoodPlankInPlayer(LocalPlayer player, int minCount) {
        if (player == null) {
            return null;
        }
        Item best = null;
        int bestCount = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack st = player.getInventory().getItem(i);
            if (st.isEmpty() || !isBoatWoodPlankItem(st.getItem())) {
                continue;
            }
            Item candidate = st.getItem();
            int total = countItem(player, candidate);
            if (total >= minCount && total > bestCount) {
                best = candidate;
                bestCount = total;
            }
        }
        return best;
    }

    public static Item findBestBoatLogInPlayer(LocalPlayer player, int minLogsNeeded) {
        if (player == null) {
            return null;
        }
        Item best = null;
        int bestScore = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack st = player.getInventory().getItem(i);
            if (st.isEmpty() || !isLogItem(st.getItem())) {
                continue;
            }
            String path = BuiltInRegistries.ITEM.getKey(st.getItem()).getPath();
            if (path.endsWith("_stem")) {
                continue; // Nether stems cannot craft boats
            }
            Item candidate = st.getItem();
            int total = countItem(player, candidate);
            if (total >= minLogsNeeded && total > bestScore) {
                best = candidate;
                bestScore = total;
            }
        }
        return best;
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
     * Pure integer sum for unit-tested inventory accounting across storage, carried cursor, and grid.
     */
    public static int computeTotalAccessibleCount(int storedCount, int carriedCount, int gridCount) {
        return Math.max(0, storedCount) + Math.max(0, carriedCount) + Math.max(0, gridCount);
    }

    /**
     * Counts matching items across main inventory, hotbar, active crafting/furnace input slots,
     * and the cursor-carried stack so goal evaluation stays accurate mid-click.
     */
    public static int countAccessibleMatching(LocalPlayer player, Predicate<ItemStack> filter) {
        if (player == null) {
            return 0;
        }
        int total = countMatching(player, filter);
        AbstractContainerMenu menu = player.containerMenu;
        if (menu != null) {
            ItemStack carried = menu.getCarried();
            if (!carried.isEmpty() && filter.test(carried)) {
                total += carried.getCount();
            }
            if (menu == player.inventoryMenu) {
                for (int s = 1; s <= 4 && s < menu.slots.size(); s++) {
                    ItemStack st = menu.getSlot(s).getItem();
                    if (!st.isEmpty() && filter.test(st)) {
                        total += st.getCount();
                    }
                }
            } else if (menu instanceof CraftingMenu) {
                for (int s = 1; s <= 9 && s < menu.slots.size(); s++) {
                    ItemStack st = menu.getSlot(s).getItem();
                    if (!st.isEmpty() && filter.test(st)) {
                        total += st.getCount();
                    }
                }
            } else if (menu instanceof AbstractFurnaceMenu) {
                for (int s = 0; s <= 2 && s < menu.slots.size(); s++) {
                    ItemStack st = menu.getSlot(s).getItem();
                    if (!st.isEmpty() && filter.test(st)) {
                        total += st.getCount();
                    }
                }
            }
        }
        return total;
    }

    /**
     * Returns {@code true} if the player currently holds items on the UI cursor or inside a crafting grid.
     */
    public static boolean hasCarriedOrGridItems(LocalPlayer player) {
        if (player == null || player.containerMenu == null) {
            return false;
        }
        AbstractContainerMenu menu = player.containerMenu;
        if (!menu.getCarried().isEmpty()) {
            return true;
        }
        if (menu == player.inventoryMenu) {
            for (int s = 1; s <= 4 && s < menu.slots.size(); s++) {
                if (!menu.getSlot(s).getItem().isEmpty()) {
                    return true;
                }
            }
        } else if (menu instanceof CraftingMenu) {
            for (int s = 1; s <= 9 && s < menu.slots.size(); s++) {
                if (!menu.getSlot(s).getItem().isEmpty()) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Visually opens the inventory (if needed) and moves a matching item from main storage onto the
     * hotbar using animated cursor pickup + placement rather than silent packet swapping.
     */
    public static StepResult stepMoveToHotbar(IPlayerContext ctx, Predicate<ItemStack> matcher, int preferredHotbarIndex) {
        if (ctx == null || ctx.player() == null) {
            return StepResult.MISSING_INGREDIENTS;
        }
        BotInventoryUi.onTickClient(ctx);
        LocalPlayer player = ctx.player();
        Screen current = (ctx.minecraft() != null && ctx.minecraft().gui != null)
                ? ctx.minecraft().gui.screen()
                : null;
        if (!(current instanceof InventoryScreen) && !(current instanceof CraftingScreen)) {
            BotInventoryUi.ensureInventoryScreenOpen(ctx);
            return StepResult.IN_PROGRESS;
        }

        AbstractContainerMenu menu = player.containerMenu;
        boolean inTable = menu instanceof CraftingMenu;
        if (current instanceof InventoryScreen && menu != player.inventoryMenu) {
            return StepResult.IN_PROGRESS;
        }
        int storageStart = inTable ? 10 : 9;
        int mainEnd = inTable ? 36 : 35;
        int hotbarStart = inTable ? 37 : 36;
        int hotbarEnd = hotbarStart + 8;

        int existingHotbar = findMatchingStorageSlot(menu, hotbarStart, hotbarEnd, matcher);
        ItemStack carried = menu.getCarried();
        if (existingHotbar >= 0 && carried.isEmpty()) {
            player.getInventory().setSelectedSlot(existingHotbar - hotbarStart);
            return StepResult.COMPLETED;
        }

        if (!carried.isEmpty()) {
            if (matcher.test(carried)) {
                int emptyHotbar = findEmptyStorageMenuSlot(menu, hotbarStart, hotbarEnd);
                int targetHotbarMenu = emptyHotbar >= 0
                        ? emptyHotbar
                        : hotbarStart + Math.max(0, Math.min(8, preferredHotbarIndex));
                if (advanceAndReadyGui(ctx, targetHotbarMenu)) {
                    ctx.playerController().windowClick(menu.containerId, targetHotbarMenu, 0, ContainerInput.PICKUP, player);
                }
                return StepResult.IN_PROGRESS;
            }
            int ret = findReturnStorageMenuSlot(menu, storageStart, hotbarEnd, carried);
            if (ret >= 0) {
                if (advanceAndReadyGui(ctx, ret)) {
                    ctx.playerController().windowClick(menu.containerId, ret, 0, ContainerInput.PICKUP, player);
                }
                return StepResult.IN_PROGRESS;
            }
            return StepResult.MISSING_INGREDIENTS;
        }

        int srcMain = findMatchingStorageSlot(menu, storageStart, mainEnd, matcher);
        if (srcMain < 0) {
            return StepResult.MISSING_INGREDIENTS;
        }
        if (advanceAndReadyGui(ctx, srcMain)) {
            ctx.playerController().windowClick(menu.containerId, srcMain, 0, ContainerInput.PICKUP, player);
        }
        return StepResult.IN_PROGRESS;
    }

    /**
     * Advances one crafting step in either the visible 3x3 {@link CraftingScreen} or 2x2 {@link InventoryScreen}.
     */
    public static StepResult stepCraft(IPlayerContext ctx, RecipeKind recipe) {
        if (ctx == null || ctx.player() == null) {
            return StepResult.MISSING_INGREDIENTS;
        }
        BotInventoryUi.onTickClient(ctx);
        LocalPlayer player = ctx.player();
        Screen currentScreen = (ctx.minecraft() != null && ctx.minecraft().gui != null)
                ? ctx.minecraft().gui.screen()
                : null;
        AbstractContainerMenu menu = player.containerMenu;
        boolean inTable = menu instanceof CraftingMenu && currentScreen instanceof CraftingScreen;
        if (recipe.needsTable && !inTable) {
            awaitingOutputTicks = 0;
            return StepResult.NEED_CRAFTING_TABLE;
        }
        if (!inTable) {
            if (!(currentScreen instanceof InventoryScreen) || menu != player.inventoryMenu) {
                BotInventoryUi.ensureInventoryScreenOpen(ctx);
                return StepResult.IN_PROGRESS;
            }
            if (!BotInventoryUi.ensureInventoryScreenOpen(ctx)) {
                return StepResult.IN_PROGRESS;
            }
        }

        if (lastAwaitingRecipe != recipe) {
            awaitingOutputTicks = 0;
            lastAwaitingRecipe = recipe;
        }

        int gridStart = 1;
        int gridCount = inTable ? 9 : 4;
        int storageStart = inTable ? 10 : 9;
        int storageEnd = inTable ? 45 : 44;

        IngredientSlot[] pattern = buildPattern(recipe, inTable, menu, storageStart, storageEnd);
        if (pattern == null) {
            return StepResult.MISSING_INGREDIENTS;
        }

        // First collect completed output slot 0 if cursor is clear
        ItemStack resultStack = menu.getSlot(0).getItem();
        if (!resultStack.isEmpty() && matchesExpectedResult(resultStack, recipe)) {
            awaitingOutputTicks = 0;
            if (!menu.getCarried().isEmpty()) {
                int returnSlot = findReturnStorageMenuSlot(menu, storageStart, storageEnd, menu.getCarried());
                if (returnSlot >= 0) {
                    if (advanceAndReadyGui(ctx, returnSlot)) {
                        ctx.playerController().windowClick(menu.containerId, returnSlot, 0, ContainerInput.PICKUP, player);
                    }
                    return StepResult.IN_PROGRESS;
                }
                return StepResult.MISSING_INGREDIENTS;
            }
            if (!advanceAndReadyGui(ctx, 0)) {
                return StepResult.IN_PROGRESS;
            }
            ctx.playerController().windowClick(menu.containerId, 0, 0, ContainerInput.QUICK_MOVE, player);
            return StepResult.COMPLETED;
        }

        // If cursor is holding a stack, keep placing right-click single items directly into any missing matching slots!
        ItemStack carried = menu.getCarried();
        if (!carried.isEmpty()) {
            for (IngredientSlot req : pattern) {
                ItemStack cur = menu.getSlot(req.gridSlot).getItem();
                if (cur.isEmpty() && req.matcher.test(carried)) {
                    if (advanceAndReadyGui(ctx, req.gridSlot)) {
                        ctx.playerController().windowClick(menu.containerId, req.gridSlot, 1, ContainerInput.PICKUP, player);
                    }
                    return StepResult.IN_PROGRESS;
                }
            }
            // Excess or foreign item on cursor: return cleanly to an inventory slot (merge partial stacks first)
            int returnSlot = findReturnStorageMenuSlot(menu, storageStart, storageEnd, carried);
            if (returnSlot >= 0) {
                if (advanceAndReadyGui(ctx, returnSlot)) {
                    ctx.playerController().windowClick(menu.containerId, returnSlot, 0, ContainerInput.PICKUP, player);
                }
                return StepResult.IN_PROGRESS;
            }
            return StepResult.MISSING_INGREDIENTS;
        }

        // Clear wrong/foreign items out of grid slots before laying down the next recipe
        for (int g = gridStart; g < gridStart + gridCount; g++) {
            ItemStack inGrid = menu.getSlot(g).getItem();
            if (!inGrid.isEmpty() && !slotMatchesPattern(g, inGrid, pattern)) {
                if (advanceAndReadyGui(ctx, g)) {
                    ctx.playerController().windowClick(menu.containerId, g, 0, ContainerInput.QUICK_MOVE, player);
                }
                return StepResult.IN_PROGRESS;
            }
        }

        // Pick up next missing pattern ingredient onto cursor (1 Fitts-paced click per beat)
        for (IngredientSlot req : pattern) {
            ItemStack cur = menu.getSlot(req.gridSlot).getItem();
            if (!cur.isEmpty() && req.matcher.test(cur)) {
                continue;
            }
            int sourceMenuSlot = findMatchingStorageSlot(menu, storageStart, storageEnd, req.matcher);
            if (sourceMenuSlot < 0) {
                return StepResult.MISSING_INGREDIENTS;
            }
            if (advanceAndReadyGui(ctx, sourceMenuSlot)) {
                ctx.playerController().windowClick(menu.containerId, sourceMenuSlot, 0, ContainerInput.PICKUP, player);
            }
            return StepResult.IN_PROGRESS;
        }

        // All pattern slots filled; hover over output slot 0 while server syncs resultStack
        hoverGuiSlot(ctx, 0);
        awaitingOutputTicks++;
        if (awaitingOutputTicks > 14) {
            for (int g = gridStart; g < gridStart + gridCount; g++) {
                if (!menu.getSlot(g).getItem().isEmpty()) {
                    if (advanceAndReadyGui(ctx, g)) {
                        ctx.playerController().windowClick(menu.containerId, g, 0, ContainerInput.QUICK_MOVE, player);
                        awaitingOutputTicks = 0;
                    }
                    return StepResult.IN_PROGRESS;
                }
            }
            awaitingOutputTicks = 0;
        }
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
        if (ctx == null) {
            return false;
        }
        BotInventoryUi.onTickClient(ctx);
        LocalPlayer player = ctx.player();
        if (player == null || !(player.containerMenu instanceof AbstractFurnaceMenu menu)) {
            return false;
        }
        if (!menu.getCarried().isEmpty()) {
            int empty = findReturnStorageMenuSlot(menu, 3, 38, menu.getCarried());
            if (empty >= 0 && advanceAndReadyGui(ctx, empty)) {
                ctx.playerController().windowClick(menu.containerId, empty, 0, ContainerInput.PICKUP, player);
            }
            return true;
        }

        ItemStack out = menu.getSlot(2).getItem();
        if (!out.isEmpty()) {
            if (advanceAndReadyGui(ctx, 2)) {
                ctx.playerController().windowClick(menu.containerId, 2, 0, ContainerInput.QUICK_MOVE, player);
            }
            return true;
        }

        ItemStack curInput = menu.getSlot(0).getItem();
        if (curInput.isEmpty() || inputMatcher.test(curInput)) {
            int src = findMatchingStorageSlot(menu, 3, 38, inputMatcher);
            if (src >= 0 && curInput.getCount() < 16) {
                if (advanceAndReadyGui(ctx, src)) {
                    ctx.playerController().windowClick(menu.containerId, src, 0, ContainerInput.QUICK_MOVE, player);
                }
                return true;
            }
        }

        ItemStack curFuel = menu.getSlot(1).getItem();
        if (curFuel.isEmpty() && !menu.getSlot(0).getItem().isEmpty()) {
            int fuelSlot = findMatchingStorageSlot(menu, 3, 38, fuelMatcher);
            if (fuelSlot >= 0) {
                if (advanceAndReadyGui(ctx, fuelSlot)) {
                    ctx.playerController().windowClick(menu.containerId, fuelSlot, 0, ContainerInput.QUICK_MOVE, player);
                }
                return true;
            }
        }
        boolean active = !menu.getSlot(0).getItem().isEmpty();
        if (active) {
            hoverGuiSlot(ctx, 2);
        }
        return active;
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

    private static IngredientSlot[] buildPattern(
            RecipeKind r,
            boolean inTable,
            AbstractContainerMenu menu,
            int storageStart,
            int storageEnd
    ) {
        Predicate<ItemStack> log = st -> isLogItem(st.getItem());
        Predicate<ItemStack> plank = st -> isPlankItem(st.getItem());
        Predicate<ItemStack> stick = st -> st.is(Items.STICK);
        Predicate<ItemStack> cobble = st -> isCobbleMaterial(st.getItem());
        Predicate<ItemStack> iron = st -> st.is(Items.IRON_INGOT);
        Predicate<ItemStack> gold = st -> st.is(Items.GOLD_INGOT);

        return switch (r) {
            case PLANKS_FROM_LOG -> {
                Item dominantLog = findDominantItemInMenu(menu, inTable, storageStart, storageEnd, CraftingHelper::isLogItem, 1);
                Predicate<ItemStack> logMatcher = dominantLog != null ? st -> st.getItem() == dominantLog : log;
                yield new IngredientSlot[]{new IngredientSlot(1, logMatcher)};
            }
            case STICKS -> inTable
                    ? new IngredientSlot[]{new IngredientSlot(1, plank), new IngredientSlot(4, plank)}
                    : new IngredientSlot[]{new IngredientSlot(1, plank), new IngredientSlot(3, plank)};
            case CRAFTING_TABLE -> {
                Predicate<ItemStack> tablePlank = buildTablePlankPredicate(menu, storageStart, storageEnd, plank);
                yield inTable
                        ? new IngredientSlot[]{new IngredientSlot(1, tablePlank), new IngredientSlot(2, tablePlank), new IngredientSlot(4, tablePlank), new IngredientSlot(5, tablePlank)}
                        : new IngredientSlot[]{new IngredientSlot(1, tablePlank), new IngredientSlot(2, tablePlank), new IngredientSlot(3, tablePlank), new IngredientSlot(4, tablePlank)};
            }
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
            case GOLDEN_HELMET -> new IngredientSlot[]{
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
            case BOAT -> {
                if (!inTable) {
                    yield null;
                }
                Item singleWoodPlank = findDominantItemInMenu(
                        menu,
                        true,
                        storageStart,
                        storageEnd,
                        CraftingHelper::isBoatWoodPlankItem,
                        5
                );
                if (singleWoodPlank == null) {
                    yield null;
                }
                Predicate<ItemStack> samePlank = st -> st.getItem() == singleWoodPlank;
                yield new IngredientSlot[]{
                        new IngredientSlot(1, samePlank), new IngredientSlot(3, samePlank),
                        new IngredientSlot(4, samePlank), new IngredientSlot(5, samePlank), new IngredientSlot(6, samePlank)
                };
            }
        };
    }

    private static Item findDominantItemInMenu(
            AbstractContainerMenu menu,
            boolean inTable,
            int storageStart,
            int storageEnd,
            Predicate<Item> itemFilter,
            int minTotalCount
    ) {
        if (menu == null) {
            return null;
        }
        int gridEnd = inTable ? 9 : 4;
        // Prefer an item already committed into the crafting grid if enough total items exist
        for (int g = 1; g <= gridEnd && g < menu.slots.size(); g++) {
            ItemStack st = menu.getSlot(g).getItem();
            if (!st.isEmpty() && itemFilter.test(st.getItem())) {
                int total = countItemInMenuRange(menu, st.getItem(), 1, storageEnd);
                if (total >= minTotalCount) {
                    return st.getItem();
                }
            }
        }
        Item best = null;
        int bestTotal = 0;
        for (int s = storageStart; s <= storageEnd && s < menu.slots.size(); s++) {
            ItemStack st = menu.getSlot(s).getItem();
            if (st.isEmpty() || !itemFilter.test(st.getItem())) {
                continue;
            }
            Item candidate = st.getItem();
            int total = countItemInMenuRange(menu, candidate, 1, storageEnd);
            if (total >= minTotalCount && total > bestTotal) {
                best = candidate;
                bestTotal = total;
            }
        }
        return best;
    }

    private static int countItemInMenuRange(AbstractContainerMenu menu, Item item, int start, int end) {
        int count = 0;
        for (int s = start; s <= end && s < menu.slots.size(); s++) {
            ItemStack st = menu.getSlot(s).getItem();
            if (!st.isEmpty() && st.getItem() == item) {
                count += st.getCount();
            }
        }
        return count;
    }

    private static Predicate<ItemStack> buildTablePlankPredicate(
            AbstractContainerMenu menu,
            int storageStart,
            int storageEnd,
            Predicate<ItemStack> fallbackPlank
    ) {
        if (menu == null) {
            return fallbackPlank;
        }
        // If one boat-wood stack has 5..8 planks and remaining other planks already total >= 4,
        // consume the other planks first so the 5 matching boat planks stay intact.
        Item reservedBoatPlank = findDominantItemInMenu(menu, false, storageStart, storageEnd, CraftingHelper::isBoatWoodPlankItem, 5);
        if (reservedBoatPlank == null) {
            return fallbackPlank;
        }
        int reservedCount = countItemInMenuRange(menu, reservedBoatPlank, 1, storageEnd);
        if (reservedCount >= 9) {
            return fallbackPlank;
        }
        int nonReservedPlanks = 0;
        for (int s = 1; s <= storageEnd && s < menu.slots.size(); s++) {
            ItemStack st = menu.getSlot(s).getItem();
            if (!st.isEmpty() && isPlankItem(st.getItem()) && st.getItem() != reservedBoatPlank) {
                nonReservedPlanks += st.getCount();
            }
        }
        if (nonReservedPlanks >= 4) {
            return st -> isPlankItem(st.getItem()) && st.getItem() != reservedBoatPlank;
        }
        return fallbackPlank;
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
            case GOLDEN_HELMET -> result.is(Items.GOLDEN_HELMET);
            case BREAD -> result.is(Items.BREAD);
            case BOW -> result.is(Items.BOW);
            case BOAT -> isBoatItem(result.getItem());
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

    /**
     * Deposits matching items from player inventory slots into an open chest or ender-chest container
     * using P6 Fitts'-law human cursor pacing (at most one quick-move click per human beat).
     *
     * @return {@code true} while items were deposited or waiting on human GUI cooldown; {@code false} when nothing else matches
     */
    public static boolean stepContainerQuickDeposit(
            IPlayerContext ctx,
            java.util.function.Predicate<ItemStack> matcher,
            java.util.function.ToIntFunction<net.minecraft.world.item.Item> keepMinimumCount
    ) {
        LocalPlayer p = ctx.player();
        if (p == null) {
            return false;
        }
        AbstractContainerMenu menu = p.containerMenu;
        if (menu == null || menu == p.inventoryMenu || menu instanceof CraftingMenu || menu instanceof AbstractFurnaceMenu) {
            return false;
        }
        int totalSlots = menu.slots.size();
        int playerStart = Math.max(0, totalSlots - 36);
        int containerSlots = playerStart;
        if (containerSlots < 27) {
            return false;
        }
        // Only deposit into chest storage if at least one empty/mergeable slot exists
        boolean hasContainerRoom = false;
        for (int c = 0; c < containerSlots; c++) {
            if (menu.getSlot(c).getItem().isEmpty()) {
                hasContainerRoom = true;
                break;
            }
        }

        // Count total of each item currently in player inventory slots
        for (int s = playerStart; s < totalSlots; s++) {
            ItemStack st = menu.getSlot(s).getItem();
            if (st.isEmpty() || !matcher.test(st)) {
                continue;
            }
            int keep = keepMinimumCount != null ? Math.max(0, keepMinimumCount.applyAsInt(st.getItem())) : 0;
            if (keep > 0) {
                int totalHeld = 0;
                for (int k = playerStart; k < totalSlots; k++) {
                    ItemStack ks = menu.getSlot(k).getItem();
                    if (!ks.isEmpty() && ks.getItem() == st.getItem()) {
                        totalHeld += ks.getCount();
                    }
                }
                if (totalHeld <= keep) {
                    continue;
                }
            }
            if (!hasContainerRoom) {
                boolean mergeable = false;
                for (int c = 0; c < containerSlots; c++) {
                    ItemStack cs = menu.getSlot(c).getItem();
                    if (!cs.isEmpty() && cs.getItem() == st.getItem() && cs.getCount() < cs.getMaxStackSize()) {
                        mergeable = true;
                        break;
                    }
                }
                if (!mergeable) {
                    continue;
                }
            }
            if (!advanceAndReadyGui(ctx, s)) {
                return true;
            }
            ctx.playerController().windowClick(menu.containerId, s, 0, ContainerInput.QUICK_MOVE, p);
            return true;
        }
        return false;
    }

    private static int findReturnStorageMenuSlot(AbstractContainerMenu menu, int start, int end, ItemStack carried) {
        if (carried != null && !carried.isEmpty()) {
            for (int s = start; s <= end && s < menu.slots.size(); s++) {
                ItemStack existing = menu.getSlot(s).getItem();
                if (!existing.isEmpty()
                        && ItemStack.isSameItemSameComponents(existing, carried)
                        && existing.getCount() < existing.getMaxStackSize()) {
                    return s;
                }
            }
        }
        return findEmptyStorageMenuSlot(menu, start, end);
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
