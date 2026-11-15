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
import baritone.api.utils.Rotation;
import baritone.api.utils.RotationUtils;
import baritone.api.utils.input.Input;
import baritone.pathing.movement.MovementHelper;
import baritone.utils.BlockStateInterface;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.item.equipment.Equippable;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Autonomous general survival reflexes (MLG water/hay clutch, fire quench, auto-eat,
 * best-armor equip, and offhand shield attach) active when Pathadol controls Survival play
 * outside the scripted 60-stage proving ground.
 */
public final class SurvivalBehavior extends Behavior {

    private static boolean isUnsafeFood(Item item) {
        return item == Items.ROTTEN_FLESH
                || item == Items.SPIDER_EYE
                || item == Items.PUFFERFISH
                || item == Items.POISONOUS_POTATO
                || item == Items.CHORUS_FRUIT;
    }

    private BlockPos mlgPlacedWaterPos = null;
    private int mlgPickupDelay = 0;
    private int eatCooldownTicks = 0;
    private int armorCheckCooldown = 0;
    private boolean activelyEating = false;
    private int preEatSlot = -1;

    public SurvivalBehavior(Baritone baritone) {
        super(baritone);
    }

    @Override
    public void onTick(TickEvent event) {
        if (event.getType() == TickEvent.Type.OUT || ctx.player() == null || ctx.world() == null) {
            return;
        }
        LocalPlayer player = ctx.player();
        if (player.isCreative() || player.isSpectator()
                || baritone.getInputOverrideHandler().getAutoTestRunner().isRunning()) {
            activelyEating = false;
            mlgPlacedWaterPos = null;
            return;
        }
        if (!Baritone.settings().allowSurvivalReflexes.value || baritone.getBedwarsArenaMemory().isInWaitingLobby()) {
            activelyEating = false;
            return;
        }
        var mc = ctx.minecraft();
        if (mc != null && ((mc.gui != null && mc.gui.screen() != null) || mc.isPaused())) {
            activelyEating = false;
            return;
        }
        // Only apply reflexes while Pathadol is actively executing a task/process
        boolean botDriving = baritone.getPathingBehavior().isPathing()
                || baritone.getCombatProcess().isActive()
                || baritone.getSpeedrunProcess().isActive()
                || baritone.getBedwarsProcess().isActive()
                || baritone.getMineProcess().isActive()
                || baritone.getExploreProcess().isActive();
        if (!botDriving) {
            activelyEating = false;
            return;
        }
        if (baritone.getBedwarsArenaMemory().isInLagbackCooldown()
                && !baritone.getBedwarsProcess().isActive()
                && !baritone.getCombatProcess().isActive()) {
            baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_FORWARD, false);
            baritone.getInputOverrideHandler().setInputForceState(Input.SPRINT, false);
            baritone.getInputOverrideHandler().setInputForceState(Input.SNEAK, true);
        }

        if (handleEmergencyMlgClutch(player)) {
            return;
        }
        if (handleWaterPickupAfterMlg(player)) {
            return;
        }
        if (handleFireQuench(player)) {
            return;
        }
        if (handleAutoEat(player)) {
            return;
        }
        if (armorCheckCooldown-- <= 0) {
            armorCheckCooldown = 16;
            equipBestArmorAndOffhandShield(player);
        }
    }

    public boolean isUsingSurvivalReflex() {
        return activelyEating || mlgPlacedWaterPos != null;
    }

    private boolean handleEmergencyMlgClutch(LocalPlayer player) {
        if (player.isInWater() || player.onGround() || player.getAbilities().flying) {
            return false;
        }
        Vec3 vel = player.getDeltaMovement();
        if (player.fallDistance < 3.6f || vel.y > -0.45) {
            return false;
        }
        boolean nether = ctx.world().dimension() == Level.NETHER;
        int clutchSlot = !nether ? findItemHotbarOrMove(Items.WATER_BUCKET) : -1;
        boolean usingWater = clutchSlot >= 0;
        if (clutchSlot < 0) {
            clutchSlot = findItemHotbarOrMove(Items.HAY_BLOCK);
        }
        if (clutchSlot < 0) {
            return false;
        }

        Vec3 eye = ctx.playerHead();
        Vec3 downEnd = player.position().add(0.0, -4.5, 0.0);
        BlockHitResult hit = ctx.world().clip(new ClipContext(eye, downEnd, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
        if (hit == null || hit.getType() != HitResult.Type.BLOCK) {
            return false;
        }
        double impactDist = player.position().y - hit.getLocation().y;
        if (impactDist > 4.2 || impactDist < 0.05) {
            return false;
        }

        if (!baritone.getInventoryBehavior().safeSelectHotbarSlot(clutchSlot) && player.getInventory().getSelectedSlot() != clutchSlot) {
            player.getInventory().setSelectedSlot(clutchSlot);
        }
        Rotation lookDown = RotationUtils.calcRotationFromVec3d(eye, hit.getLocation(), ctx.playerRotations());
        baritone.getLookBehavior().updateTarget(new Rotation(lookDown.getYaw(), Math.max(82.0f, lookDown.getPitch())), true, true);
        if (impactDist <= 3.6) {
            baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_RIGHT, true);
            if (usingWater) {
                mlgPlacedWaterPos = hit.getBlockPos().relative(hit.getDirection());
                mlgPickupDelay = 4;
            }
        }
        return true;
    }

    private boolean handleWaterPickupAfterMlg(LocalPlayer player) {
        if (mlgPlacedWaterPos == null) {
            return false;
        }
        if (mlgPickupDelay-- > 0) {
            return false;
        }
        if (!ctx.world().getBlockState(mlgPlacedWaterPos).is(Blocks.WATER)) {
            mlgPlacedWaterPos = null;
            return false;
        }
        int emptyBucketSlot = findItemHotbarOrMove(Items.BUCKET);
        if (emptyBucketSlot < 0) {
            mlgPlacedWaterPos = null;
            return false;
        }
        if (!baritone.getInventoryBehavior().safeSelectHotbarSlot(emptyBucketSlot) && player.getInventory().getSelectedSlot() != emptyBucketSlot) {
            return false;
        }
        Rotation rot = RotationUtils.calcRotationFromVec3d(
                ctx.playerHead(),
                Vec3.atCenterOf(mlgPlacedWaterPos),
                ctx.playerRotations()
        );
        baritone.getLookBehavior().updateTarget(rot, true, true);
        baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_RIGHT, true);
        mlgPlacedWaterPos = null;
        return true;
    }

    private boolean handleFireQuench(LocalPlayer player) {
        if (!player.isOnFire() || player.fireImmune() || ctx.world().dimension() == Level.NETHER
                || !player.onGround() || player.isInWater()) {
            return false;
        }
        int bucketSlot = findItemHotbarOrMove(Items.WATER_BUCKET);
        if (bucketSlot < 0) {
            return false;
        }
        if (!baritone.getInventoryBehavior().safeSelectHotbarSlot(bucketSlot)) {
            return false;
        }
        BlockPos under = player.blockPosition();
        Rotation rot = RotationUtils.calcRotationFromVec3d(
                ctx.playerHead(),
                Vec3.atBottomCenterOf(under),
                ctx.playerRotations()
        );
        baritone.getLookBehavior().updateTarget(new Rotation(rot.getYaw(), 87.0f), true, true);
        baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_RIGHT, true);
        mlgPlacedWaterPos = under;
        mlgPickupDelay = 3;
        return true;
    }

    private boolean handleAutoEat(LocalPlayer player) {
        if (eatCooldownTicks > 0) {
            eatCooldownTicks--;
        }
        int foodLevel = player.getFoodData().getFoodLevel();
        float health = player.getHealth();
        float absorption = player.getAbsorptionAmount();
        boolean hungry = foodLevel <= 16 || (foodLevel < 20 && health < 15.0f);
        double nearestThreatDist = findNearestEnemyDistance(player);
        boolean bedwarsOrCombat = Baritone.settings().bedwarsMode.value
                || baritone.getBedwarsProcess().isActive()
                || baritone.getCombatProcess().isActive();
        boolean healthNeed = shouldHealWithGoldenApple(
                bedwarsOrCombat, health, absorption, foodLevel, nearestThreatDist, activelyEating,
                player.isUsingItem() ? player.getUseItemRemainingTicks() : 32
        );
        if ((!hungry && !healthNeed) || player.containerMenu != player.inventoryMenu) {
            if (activelyEating) {
                activelyEating = false;
                baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_RIGHT, false);
                if (preEatSlot >= 0 && preEatSlot < 9 && baritone.getInventoryBehavior().isSlotSwitchSafe()) {
                    baritone.getInventoryBehavior().safeSelectHotbarSlot(preEatSlot);
                }
                preEatSlot = -1;
                eatCooldownTicks = 10;
            }
            return false;
        }

        int bestFoodSlot = healthNeed && !hungry
                ? findBestHealGappleSlot(player)
                : findBestFoodSlot(player, foodLevel);
        if (bestFoodSlot < 0) {
            if (activelyEating) {
                activelyEating = false;
                baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_RIGHT, false);
                preEatSlot = -1;
            }
            return false;
        }
        if (!activelyEating) {
            if (eatCooldownTicks > 0 || !baritone.getInventoryBehavior().isSlotSwitchSafe()) {
                return false;
            }
            preEatSlot = player.getInventory().getSelectedSlot();
            if (!baritone.getInventoryBehavior().safeSelectHotbarSlot(bestFoodSlot)) {
                return false;
            }
            activelyEating = true;
        } else if (player.getInventory().getSelectedSlot() != bestFoodSlot) {
            baritone.getInventoryBehavior().safeSelectHotbarSlot(bestFoodSlot);
        }
        baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_LEFT, false);
        baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_RIGHT, true);
        baritone.getInputOverrideHandler().setInputForceState(Input.SPRINT, false);
        return true;
    }

    /**
     * Decides whether eating a Golden Apple to heal / pre-gap is warranted even at full hunger (20/20).
     */
    public static boolean shouldHealWithGoldenApple(
            boolean bedwarsOrCombat,
            float health,
            float absorption,
            int foodLevel,
            double nearestEnemyDist,
            boolean alreadyEating,
            int remainingUseTicks
    ) {
        if (health <= 0.0f) {
            return false;
        }
        // Abort gapple if an enemy enters close melee exchange unless HP is critical or chew is almost complete
        if (nearestEnemyDist <= 3.35 && health > 8.0f && (!alreadyEating || remainingUseTicks > 8)) {
            return false;
        }
        if (health <= 10.5f && absorption <= 0.0f) {
            return true;
        }
        if (!bedwarsOrCombat) {
            return false;
        }
        // Emergency or post-fight healing in Bedwars regardless of 20/20 hunger
        if (health <= 13.5f) {
            return absorption <= 0.0f || health <= 9.0f;
        }
        // Pre-gap approaching a fight (4.5..15m away) when no absorption shield is active
        if (absorption <= 0.0f && nearestEnemyDist >= 4.5 && nearestEnemyDist <= 15.0 && health <= 17.5f) {
            return true;
        }
        // Finish an in-progress gapple if nearly done chewing and still missing absorption or HP
        return alreadyEating && remainingUseTicks <= 12 && (health <= 16.0f || absorption <= 0.0f);
    }

    private double findNearestEnemyDistance(LocalPlayer player) {
        if (player == null || ctx.world() == null) {
            return 99.0;
        }
        double best = 99.0;
        for (net.minecraft.world.entity.Entity e : ctx.entities()) {
            if (!(e instanceof net.minecraft.world.entity.player.Player other)
                    || other == player || !other.isAlive() || other.isSpectator()
                    || player.isAlliedTo(other) || other.getUUID().version() == 2) {
                continue;
            }
            double d = player.distanceTo(other);
            if (d < best) {
                best = d;
            }
        }
        return best;
    }

    private int findBestHealGappleSlot(LocalPlayer player) {
        var inv = player.getInventory().getNonEquipmentItems();
        int normalGapple = -1;
        for (int i = 0; i < 9; i++) {
            ItemStack st = inv.get(i);
            if (st.isEmpty()) continue;
            if (st.is(Items.ENCHANTED_GOLDEN_APPLE)) {
                return i;
            }
            if (st.is(Items.GOLDEN_APPLE) && normalGapple < 0) {
                normalGapple = i;
            }
        }
        return normalGapple;
    }

    private void equipBestArmorAndOffhandShield(LocalPlayer player) {
        if (player.containerMenu != player.inventoryMenu || player.isUsingItem()
                || baritone.getInventoryPauserProcess().stationaryForInventoryMove() == false) {
            return;
        }
        EquipmentSlot[] armorSlots = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};
        int[] menuArmorSlotIds = {5, 6, 7, 8};

        for (int a = 0; a < armorSlots.length; a++) {
            EquipmentSlot eqSlot = armorSlots[a];
            double currentScore = scoreArmor(player.getItemBySlot(eqSlot), eqSlot);
            int bestIdx = -1;
            double bestScore = currentScore;
            var items = player.getInventory().getNonEquipmentItems();
            for (int i = 0; i < items.size(); i++) {
                ItemStack st = items.get(i);
                Equippable eq = st.getItem().components().get(DataComponents.EQUIPPABLE);
                if (eq == null || eq.slot() != eqSlot) {
                    continue;
                }
                double s = scoreArmor(st, eqSlot);
                if (s > bestScore + 0.05) {
                    bestScore = s;
                    bestIdx = i;
                }
            }
            if (bestIdx >= 0) {
                int menuSlot = bestIdx < 9 ? bestIdx + 36 : bestIdx;
                swapSlotsInPlayerInventory(player, menuSlot, menuArmorSlotIds[a]);
                return;
            }
        }

        if (player.getItemBySlot(EquipmentSlot.OFFHAND).isEmpty()) {
            var items = player.getInventory().getNonEquipmentItems();
            for (int i = 0; i < items.size(); i++) {
                if (items.get(i).is(Items.SHIELD)) {
                    int menuSlot = i < 9 ? i + 36 : i;
                    swapSlotsInPlayerInventory(player, menuSlot, 45);
                    return;
                }
            }
        }
    }

    private void swapSlotsInPlayerInventory(LocalPlayer player, int fromMenuSlot, int toMenuSlot) {
        if (Baritone.settings().humanMode.value || Baritone.settings().bedwarsMode.value) {
            var mc = ctx.minecraft();
            if (!Baritone.settings().allowInventory.value
                    || mc == null || mc.gui == null || !(mc.gui.screen() instanceof AbstractContainerScreen<?>)) {
                return;
            }
        }
        int id = player.inventoryMenu.containerId;
        ctx.playerController().windowClick(id, fromMenuSlot, 0, ContainerInput.PICKUP, player);
        ctx.playerController().windowClick(id, toMenuSlot, 0, ContainerInput.PICKUP, player);
        if (!player.inventoryMenu.getCarried().isEmpty()) {
            ctx.playerController().windowClick(id, fromMenuSlot, 0, ContainerInput.PICKUP, player);
        }
    }

    public static double scoreFood(ItemStack stack, int currentFoodLevel) {
        if (stack.isEmpty()) {
            return -1.0;
        }
        FoodProperties fp = stack.getItem().components().get(DataComponents.FOOD);
        if (fp == null) {
            return -1.0;
        }
        if (isUnsafeFood(stack.getItem()) && currentFoodLevel >= 6) {
            return -1.0;
        }
        return fp.nutrition() + fp.saturation() * 0.5;
    }

    public static double scoreArmor(ItemStack stack, EquipmentSlot slot) {
        if (stack.isEmpty()) {
            return 0.0;
        }
        ItemAttributeModifiers mods = stack.getItem().components().getOrDefault(DataComponents.ATTRIBUTE_MODIFIERS, ItemAttributeModifiers.EMPTY);
        double armor = mods.compute(Attributes.ARMOR, 0.0, slot);
        double tough = mods.compute(Attributes.ARMOR_TOUGHNESS, 0.0, slot);
        return armor + tough * 0.5 + (stack.isEnchanted() ? 0.25 : 0.0);
    }

    private int findBestFoodSlot(LocalPlayer player, int foodLevel) {
        var inv = player.getInventory().getNonEquipmentItems();
        int bestHotbar = -1;
        double bestHotbarScore = 0.0;
        for (int i = 0; i < 9; i++) {
            double s = scoreFood(inv.get(i), foodLevel);
            if (s > bestHotbarScore) {
                bestHotbarScore = s;
                bestHotbar = i;
            }
        }
        if (bestHotbar >= 0) {
            return bestHotbar;
        }
        int bestInv = -1;
        double bestInvScore = 0.0;
        for (int i = 9; i < inv.size(); i++) {
            double s = scoreFood(inv.get(i), foodLevel);
            if (s > bestInvScore) {
                bestInvScore = s;
                bestInv = i;
            }
        }
        if (bestInv >= 0 && baritone.getInventoryBehavior().attemptToPutOnHotbar(bestInv, s -> s == 0 || s == 8)) {
            for (int i = 0; i < 9; i++) {
                if (scoreFood(inv.get(i), foodLevel) > 0.0) {
                    return i;
                }
            }
        }
        return -1;
    }

    private int findItemHotbarOrMove(Item target) {
        var inv = ctx.player().getInventory().getNonEquipmentItems();
        for (int i = 0; i < 9; i++) {
            if (inv.get(i).is(target)) {
                return i;
            }
        }
        if (Baritone.settings().humanMode.value || Baritone.settings().bedwarsMode.value) {
            var mc = ctx.minecraft();
            if (!Baritone.settings().allowInventory.value
                    || mc == null || mc.gui == null || !(mc.gui.screen() instanceof AbstractContainerScreen<?>)) {
                return -1;
            }
        }
        for (int i = 9; i < inv.size(); i++) {
            if (inv.get(i).is(target)) {
                int hotbarTarget = 7;
                ctx.playerController().windowClick(ctx.player().inventoryMenu.containerId, i, hotbarTarget, ContainerInput.SWAP, ctx.player());
                return hotbarTarget;
            }
        }
        return -1;
    }
}
