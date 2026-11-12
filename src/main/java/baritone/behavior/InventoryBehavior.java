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

package baritone.behavior;

import baritone.Baritone;
import baritone.api.event.events.TickEvent;
import baritone.api.pathing.calc.IPath;
import baritone.api.utils.Helper;
import baritone.pathing.movement.Movement;
import baritone.pathing.movement.movements.MovementDiagonal;
import baritone.pathing.movement.movements.MovementTraverse;
import baritone.pathing.path.PathExecutor;
import baritone.utils.BlockStateInterface;
import baritone.utils.ToolSet;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.OptionalInt;
import java.util.Random;
import java.util.function.Predicate;

public final class InventoryBehavior extends Behavior implements Helper {

    int ticksSinceLastInventoryMove;
    int[] lastTickRequestedMove; // not everything asks every tick, so remember the request while coming to a halt
    private int lastObservedHotbarSlot = -1;
    private int ticksSinceHotbarChange = 10;

    public InventoryBehavior(Baritone baritone) {
        super(baritone);
    }

    @Override
    public void onTick(TickEvent event) {
        if (event.getType() == TickEvent.Type.OUT || ctx.player() == null) {
            return;
        }
        int currentHotbarSlot = ctx.player().getInventory().getSelectedSlot();
        if (lastObservedHotbarSlot != currentHotbarSlot) {
            lastObservedHotbarSlot = currentHotbarSlot;
            ticksSinceHotbarChange = 0;
        } else if (ticksSinceHotbarChange < 100) {
            ticksSinceHotbarChange++;
        }

        if (Baritone.settings().humanMode.value && baritone.getPathingBehavior().isPathing()) {
            preSwapForUpcomingMovement();
        }

        if (!Baritone.settings().allowInventory.value) {
            return;
        }
        if (ctx.player().containerMenu != ctx.player().inventoryMenu) {
            // we have a crafting table or a chest or something open
            return;
        }
        ticksSinceLastInventoryMove++;
        if (firstValidThrowaway() >= 9) { // aka there are none on the hotbar, but there are some in main inventory
            requestSwapWithHotBar(firstValidThrowaway(), 8);
        }
        int pick = bestToolAgainst(Blocks.STONE);
        if (pick >= 9) {
            requestSwapWithHotBar(pick, 0);
        }
        if (lastTickRequestedMove != null) {
            logDebug("Remembering to move " + lastTickRequestedMove[0] + " " + lastTickRequestedMove[1] + " from a previous tick");
            requestSwapWithHotBar(lastTickRequestedMove[0], lastTickRequestedMove[1]);
        }
    }

    public boolean attemptToPutOnHotbar(int inMainInvy, Predicate<Integer> disallowedHotbar) {
        OptionalInt destination = getTempHotbarSlot(disallowedHotbar);
        if (destination.isPresent()) {
            if (!requestSwapWithHotBar(inMainInvy, destination.getAsInt())) {
                return false;
            }
        }
        return true;
    }

    public OptionalInt getTempHotbarSlot(Predicate<Integer> disallowedHotbar) {
        // we're using 0 and 8 for pickaxe and throwaway
        ArrayList<Integer> candidates = new ArrayList<>();
        for (int i = 1; i < 8; i++) {
            if (ctx.player().getInventory().getNonEquipmentItems().get(i).isEmpty() && !disallowedHotbar.test(i)) {
                candidates.add(i);
            }
        }
        if (candidates.isEmpty()) {
            for (int i = 1; i < 8; i++) {
                if (!disallowedHotbar.test(i)) {
                    candidates.add(i);
                }
            }
        }
        if (candidates.isEmpty()) {
            return OptionalInt.empty();
        }
        return OptionalInt.of(candidates.get(new Random().nextInt(candidates.size())));
    }

    /**
     * Returns whether enough finger reaction ticks (at least 2) have elapsed since the last hotbar
     * slot switch before allowing a left/right click when {@code humanMode} is enabled.
     */
    public boolean isHotbarReadyForAction() {
        if (!Baritone.settings().humanMode.value || ctx.player() == null) {
            return true;
        }
        int currentHotbarSlot = ctx.player().getInventory().getSelectedSlot();
        if (lastObservedHotbarSlot != -1 && lastObservedHotbarSlot != currentHotbarSlot) {
            lastObservedHotbarSlot = currentHotbarSlot;
            ticksSinceHotbarChange = 0;
            return false;
        }
        return ticksSinceHotbarChange >= 2;
    }

    private void preSwapForUpcomingMovement() {
        PathExecutor exec = baritone.getPathingBehavior().getCurrent();
        if (exec == null) {
            return;
        }
        IPath path = exec.getPath();
        int pos = exec.getPosition();
        if (path == null || pos < 0 || pos >= path.movements().size()) {
            return;
        }
        BlockStateInterface bsi = new BlockStateInterface(ctx);
        Movement current = (Movement) path.movements().get(pos);
        // Trap 3 fix: Only pre-swap during pure traversing when not actively mining/placing or in ClickModel post-break over-hold
        if (baritone.getInputOverrideHandler().getClickModel().isOverHolding()
                || baritone.getInputOverrideHandler().isInputForcedDown(baritone.api.utils.input.Input.CLICK_LEFT)
                || baritone.getInputOverrideHandler().isInputForcedDown(baritone.api.utils.input.Input.CLICK_RIGHT)) {
            return;
        }
        if (!(current instanceof MovementTraverse || current instanceof MovementDiagonal)) {
            return;
        }
        if (!current.toBreak(bsi).isEmpty() || !current.toPlace(bsi).isEmpty()) {
            return;
        }
        int maxLookahead = Math.min(path.movements().size() - 1, pos + 2);
        for (int i = pos + 1; i <= maxLookahead; i++) {
            Movement upcoming = (Movement) path.movements().get(i);
            var breaks = upcoming.toBreak(bsi);
            if (!breaks.isEmpty()) {
                BlockPos target = breaks.get(0);
                int bestSlot = new ToolSet(ctx.player()).getBestSlot(bsi.get0(target).getBlock(), false);
                if (bestSlot >= 0 && bestSlot < 9 && bestSlot != ctx.player().getInventory().getSelectedSlot()) {
                    ctx.player().getInventory().setSelectedSlot(bestSlot);
                    lastObservedHotbarSlot = bestSlot;
                    ticksSinceHotbarChange = 0;
                }
                return;
            }
            var places = upcoming.toPlace(bsi);
            if (!places.isEmpty()) {
                BlockPos target = places.get(0);
                selectThrowawayForLocation(true, target.getX(), target.getY(), target.getZ());
                return;
            }
        }
    }

    /**
     * Fitts'-law cursor travel delay in ticks between two inventory slots in a 9-column container grid.
     */
    public static int computeFittsSlotDelayTicks(int fromSlot, int toSlot) {
        int r1 = Math.max(0, fromSlot) / 9;
        int c1 = Math.max(0, fromSlot) % 9;
        int r2 = Math.max(0, toSlot) / 9;
        int c2 = Math.max(0, toSlot) % 9;
        double dist = Math.hypot(c2 - c1, r2 - r1);
        double id = Math.log(dist + 1.0) / Math.log(2.0);
        return Math.max(2, Math.min(7, (int) Math.round(2.0 + 1.4 * id)));
    }

    private boolean requestSwapWithHotBar(int inInventory, int inHotbar) {
        lastTickRequestedMove = new int[]{inInventory, inHotbar};
        int requiredTicks = Baritone.settings().ticksBetweenInventoryMoves.value;
        if (Baritone.settings().humanMode.value) {
            requiredTicks = Math.max(requiredTicks, computeFittsSlotDelayTicks(inInventory, inHotbar + 27));
        }
        if (ticksSinceLastInventoryMove < requiredTicks) {
            logDebug("Inventory move requested but delaying " + ticksSinceLastInventoryMove + " " + requiredTicks);
            return false;
        }
        if (Baritone.settings().inventoryMoveOnlyIfStationary.value && !baritone.getInventoryPauserProcess().stationaryForInventoryMove()) {
            logDebug("Inventory move requested but delaying until stationary");
            return false;
        }
        ctx.playerController().windowClick(ctx.player().inventoryMenu.containerId, inInventory < 9 ? inInventory + 36 : inInventory, inHotbar, ContainerInput.SWAP, ctx.player());
        ticksSinceLastInventoryMove = 0;
        lastTickRequestedMove = null;
        return true;
    }

    private int firstValidThrowaway() { // TODO offhand idk
        NonNullList<ItemStack> invy = ctx.player().getInventory().getNonEquipmentItems();
        for (int i = 0; i < invy.size(); i++) {
            if (Baritone.settings().acceptableThrowawayItems.value.contains(invy.get(i).getItem())) {
                return i;
            }
        }
        return -1;
    }

    private int bestToolAgainst(Block against) {
        NonNullList<ItemStack> invy = ctx.player().getInventory().getNonEquipmentItems();
        int bestInd = -1;
        double bestSpeed = -1;
        for (int i = 0; i < invy.size(); i++) {
            ItemStack stack = invy.get(i);
            if (stack.isEmpty()) {
                continue;
            }
            if (Baritone.settings().itemSaver.value && (stack.getDamageValue() + Baritone.settings().itemSaverThreshold.value) >= stack.getMaxDamage() && stack.getMaxDamage() > 1) {
                continue;
            }
            if (stack.getItem().components().has(DataComponents.TOOL)) {
                double speed = ToolSet.calculateSpeedVsBlock(stack, against.defaultBlockState()); // takes into account enchants
                if (speed > bestSpeed) {
                    bestSpeed = speed;
                    bestInd = i;
                }
            }
        }
        return bestInd;
    }

    public boolean hasGenericThrowaway() {
        for (Item item : Baritone.settings().acceptableThrowawayItems.value) {
            if (throwaway(false, stack -> item.equals(stack.getItem()))) {
                return true;
            }
        }
        return false;
    }

    public boolean selectThrowawayForLocation(boolean select, int x, int y, int z) {
        BlockState maybe = baritone.getBuilderProcess().placeAt(x, y, z, baritone.bsi.get0(x, y, z));
        if (maybe != null && throwaway(select, stack -> stack.getItem() instanceof BlockItem && maybe.equals(((BlockItem) stack.getItem()).getBlock().getStateForPlacement(new BlockPlaceContext(new UseOnContext(ctx.world(), ctx.player(), InteractionHand.MAIN_HAND, stack, new BlockHitResult(new Vec3(ctx.player().position().x, ctx.player().position().y, ctx.player().position().z), Direction.UP, ctx.playerFeet(), false)) {}))))) {
            return true; // gotem
        }
        if (maybe != null && throwaway(select, stack -> stack.getItem() instanceof BlockItem && ((BlockItem) stack.getItem()).getBlock().equals(maybe.getBlock()))) {
            return true;
        }
        for (Item item : Baritone.settings().acceptableThrowawayItems.value) {
            if (throwaway(select, stack -> item.equals(stack.getItem()))) {
                return true;
            }
        }
        return false;
    }

    public boolean throwaway(boolean select, Predicate<? super ItemStack> desired) {
        return throwaway(select, desired, Baritone.settings().allowInventory.value);
    }

    public boolean throwaway(boolean select, Predicate<? super ItemStack> desired, boolean allowInventory) {
        LocalPlayer p = ctx.player();
        NonNullList<ItemStack> inv = p.getInventory().getNonEquipmentItems();
        for (int i = 0; i < 9; i++) {
            ItemStack item = inv.get(i);
            // this usage of settings() is okay because it's only called once during pathing
            // (while creating the CalculationContext at the very beginning)
            // and then it's called during execution
            // since this function is never called during cost calculation, we don't need to migrate
            // acceptableThrowawayItems to the CalculationContext
            if (desired.test(item)) {
                if (select) {
                    p.getInventory().setSelectedSlot(i);
                }
                return true;
            }
        }
        if (desired.test(p.getItemBySlot(EquipmentSlot.OFFHAND))) {
            // main hand takes precedence over off hand
            // that means that if we have block A selected in main hand and block B in off hand, right clicking places block B
            // we've already checked above ^ and the main hand can't possible have an acceptablethrowawayitem
            // so we need to select in the main hand something that doesn't right click
            // so not a shovel, not a hoe, not a block, etc
            for (int i = 0; i < 9; i++) {
                ItemStack item = inv.get(i);
                if (item.isEmpty() || item.getItem().components().has(DataComponents.TOOL)) {
                    if (select) {
                        p.getInventory().setSelectedSlot(i);
                    }
                    return true;
                }
            }
        }

        if (allowInventory) {
            for (int i = 9; i < 36; i++) {
                if (desired.test(inv.get(i))) {
                    if (select) {
                        requestSwapWithHotBar(i, 7);
                        p.getInventory().setSelectedSlot(7);
                    }
                    return true;
                }
            }
        }

        return false;
    }
}
