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

import baritone.Baritone;
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.pathing.goals.GoalNear;
import baritone.api.process.PathingCommand;
import baritone.api.process.PathingCommandType;
import baritone.api.utils.Rotation;
import baritone.api.utils.RotationUtils;
import baritone.api.utils.input.Input;
import baritone.bedwars.BedwarsDefensePlanner.BreakVectorCandidate;
import baritone.bedwars.BedwarsDefensePlanner.BreakVectorDecision;
import baritone.bedwars.BedwarsDefensePlanner.DefenseBlockKind;
import baritone.bedwars.BedwarsDefensePlanner.ToolProfile;
import baritone.bedwars.BedwarsMapCore.BedPOI;
import baritone.bedwars.BedwarsMapCore.BwsRuleset;
import baritone.bedwars.BedwarsMapCore.GenPOI;
import baritone.bedwars.BedwarsMapCore.GenType;
import baritone.bedwars.BedwarsMapCore.ShopNPC;
import baritone.bedwars.BedwarsMapCore.SpawnPOI;
import baritone.bedwars.BedwarsMapCore.TeamColor;
import baritone.bedwars.BedwarsShopPlanner.ShopClickDecision;
import baritone.bedwars.BedwarsShopPlanner.ShopPreset;
import baritone.bedwars.BedwarsShopPlanner.ShopSlotInfo;
import baritone.bedwars.BedwarsTacticalHelper.DefensePlacement;
import baritone.bedwars.BedwarsTacticalHelper.FireballJumpPlan;
import baritone.bedwars.BedwarsTacticalHelper.InventorySummary;
import baritone.brain.BrainActionSpace.Maneuver;
import baritone.human.CraftingHelper;
import baritone.human.MouseModel;
import baritone.pathing.movement.MovementHelper;
import baritone.utils.BaritoneProcessHelper;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.AbstractFurnaceMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.AirBlock;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Autonomous Bedwars tactical executor powering {@code #bws rush|diamonds|emeralds|gen|stash|recall}.
 * Uses {@link BedwarsArenaMemory} for T=0 symmetry/cache topology and {@link BedwarsDefensePlanner}
 * for Edge Case C (X-Ray bed break vector), Edge Case E (lagback clutch), and Edge Case F (safe void recall).
 */
public final class BedwarsProcess extends BaritoneProcessHelper {

    public enum TaskMode {
        NONE,
        RUSH,
        DIAMONDS,
        EMERALDS,
        HOME_GEN,
        STASH,
        RECALL,
        BUY,
        DEFEND,
        BLOCK_IN,
        FIREBALL_JUMP,
        RETREAT
    }

    private TaskMode mode = TaskMode.NONE;
    private TeamColor rushFilter = TeamColor.UNKNOWN;
    private boolean rushWeakestMode = false;
    private boolean recallForce = false;
    private int actionCooldown = 0;
    private long lastClutchAttemptTick = -100L;

    private ShopPreset buyPreset = ShopPreset.AUTO;
    private boolean buyCategorySwitched = false;
    private int buyClicksDone = 0;
    private int buyStallTicks = 0;
    private boolean defendButterfly = false;
    private float fireballTargetYaw = 0.0f;
    private boolean fireballYawExplicit = false;
    private int fireballAirTicks = 0;
    private int fireballTimeoutTicks = 0;

    public BedwarsProcess(Baritone baritone) {
        super(baritone);
    }

    public TaskMode getMode() {
        return mode;
    }

    public synchronized String startRush(String selector) {
        ensureBedwarsActiveAndScanned();
        if (selector != null && ("weakest".equalsIgnoreCase(selector) || "weak".equalsIgnoreCase(selector) || "easiest".equalsIgnoreCase(selector))) {
            this.rushWeakestMode = true;
            this.rushFilter = TeamColor.UNKNOWN;
        } else {
            this.rushWeakestMode = false;
            if (selector == null || selector.isBlank() || "next".equalsIgnoreCase(selector) || "side".equalsIgnoreCase(selector)) {
                this.rushFilter = TeamColor.UNKNOWN;
            } else {
                this.rushFilter = TeamColor.fromName(selector);
            }
        }
        this.mode = TaskMode.RUSH;
        this.actionCooldown = 0;
        BedPOI target = selectTargetBed();
        if (target == null) {
            return "Bedwars rush active (awaiting living enemy bed discovery).";
        }
        return "Bedwars rushing " + (rushWeakestMode ? "weakest " : "") + target.team().name()
                + " bed at " + target.x() + "," + target.y() + "," + target.z();
    }

    public synchronized String startWeakestRush() {
        return startRush("weakest");
    }

    public synchronized String startBuy(String presetName) {
        ensureBedwarsActiveAndScanned();
        this.buyPreset = ShopPreset.fromString(presetName);
        this.buyCategorySwitched = false;
        this.buyClicksDone = 0;
        this.buyStallTicks = 0;
        this.mode = TaskMode.BUY;
        this.actionCooldown = 0;
        CraftingHelper.resetGuiPacer();
        return "Bedwars autonomous shop run started (preset=" + buyPreset.name() + ").";
    }

    public synchronized String startDefend(boolean butterfly) {
        ensureBedwarsActiveAndScanned();
        this.defendButterfly = butterfly;
        this.mode = TaskMode.DEFEND;
        this.actionCooldown = 0;
        return "Bedwars home bed defense builder active (" + (butterfly ? "butterfly" : "standard") + ").";
    }

    public synchronized String startBlockIn() {
        ensureBedwarsActiveAndScanned();
        this.mode = TaskMode.BLOCK_IN;
        this.actionCooldown = 0;
        return "Bedwars defensive block-in cocoon active.";
    }

    public synchronized String startFireballJump(Float optionalYaw) {
        ensureBedwarsActiveAndScanned();
        this.fireballYawExplicit = optionalYaw != null;
        this.fireballTargetYaw = optionalYaw != null
                ? optionalYaw
                : (ctx.player() != null ? ctx.player().getYRot() : 0.0f);
        this.fireballAirTicks = 0;
        this.fireballTimeoutTicks = 0;
        this.mode = TaskMode.FIREBALL_JUMP;
        this.actionCooldown = 0;
        return "Bedwars fireball jump queued.";
    }

    public synchronized String startRetreat() {
        ensureBedwarsActiveAndScanned();
        this.mode = TaskMode.RETREAT;
        this.actionCooldown = 0;
        return "Bedwars retreating to home base.";
    }

    /**
     * Dispatches a high-level {@link Maneuver} selected by {@link baritone.brain.BrainProcess}
     * without resetting ongoing progress when the same maneuver continues across ticks.
     */
    public synchronized void executeBrainManeuver(Maneuver maneuver) {
        if (maneuver == null) {
            return;
        }
        switch (maneuver) {
            case HOLD_HOME_GEN -> {
                if (mode != TaskMode.HOME_GEN) {
                    startGeneratorRun(TaskMode.HOME_GEN);
                }
            }
            case SHOP_BUY_ESSENTIALS -> {
                if (mode != TaskMode.BUY || buyPreset != ShopPreset.AUTO) {
                    startBuy("auto");
                }
            }
            case SHOP_BUY_COMBAT_UPGRADES -> {
                ShopPreset wanted = ShopPreset.COMBAT;
                if (ctx.player() != null) {
                    boolean modern = baritone.getBedwarsArenaMemory().getActiveRuleset() == BwsRuleset.MODERN_26_3;
                    InventorySummary inv = BedwarsTacticalHelper.summarizeInventory(ctx.player(), modern);
                    if (inv.diamonds() >= 2) {
                        wanted = ShopPreset.UPGRADES;
                    }
                }
                if (mode != TaskMode.BUY || buyPreset != wanted) {
                    startBuy(wanted == ShopPreset.UPGRADES ? "upgrades" : "combat");
                }
            }
            case DEFEND_HOME_BED -> {
                if (mode != TaskMode.DEFEND) {
                    startDefend(false);
                }
            }
            case RUSH_NEAREST_BED -> {
                if (mode != TaskMode.RUSH || rushWeakestMode) {
                    startRush("next");
                }
            }
            case RUSH_WEAKEST_BED -> {
                if (mode != TaskMode.RUSH || !rushWeakestMode) {
                    startRush("weakest");
                }
            }
            case FARM_DIAMONDS -> {
                if (mode != TaskMode.DIAMONDS) {
                    startGeneratorRun(TaskMode.DIAMONDS);
                }
            }
            case FARM_EMERALDS -> {
                if (mode != TaskMode.EMERALDS) {
                    startGeneratorRun(TaskMode.EMERALDS);
                }
            }
            case STASH_VALUABLES -> {
                if (mode != TaskMode.STASH) {
                    startStash();
                }
            }
            case RETREAT_HOME -> {
                if (mode != TaskMode.RETREAT) {
                    startRetreat();
                }
            }
            case FIREBALL_JUMP -> {
                if (mode != TaskMode.FIREBALL_JUMP) {
                    startFireballJump(null);
                }
            }
            case TACTICAL_VOID_RECALL -> {
                if (mode != TaskMode.RECALL) {
                    startRecall(false);
                }
            }
            default -> {
            }
        }
    }

    public synchronized String startGeneratorRun(TaskMode genMode) {
        ensureBedwarsActiveAndScanned();
        this.mode = genMode;
        this.actionCooldown = 0;
        return "Bedwars task started: " + genMode.name();
    }

    public synchronized String startStash() {
        ensureBedwarsActiveAndScanned();
        this.mode = TaskMode.STASH;
        this.actionCooldown = 0;
        CraftingHelper.resetGuiPacer();
        return "Bedwars stashing excess resources to Ender/Team chest.";
    }

    public synchronized String startRecall(boolean force) {
        ensureBedwarsActiveAndScanned();
        LocalPlayer p = ctx.player();
        if (p != null && !force) {
            int iron = countItem(p, Items.IRON_INGOT);
            int gold = countItem(p, Items.GOLD_INGOT);
            int diamonds = countItem(p, Items.DIAMOND);
            int emeralds = countItem(p, Items.EMERALD);
            if (!BedwarsDefensePlanner.isSafeForTacticalVoid(iron, gold, diamonds, emeralds)) {
                return String.format(
                        Locale.ROOT,
                        "Aborted #bws recall (carrying diamonds=%d, emeralds=%d, gold=%d, iron=%d) -> use '#bws stash' or '#bws recall force'.",
                        diamonds, emeralds, gold, iron
                );
            }
        }
        this.recallForce = force;
        this.mode = TaskMode.RECALL;
        this.actionCooldown = 0;
        return "Bedwars tactical void recall initiated.";
    }

    @Override
    public boolean isActive() {
        return mode != TaskMode.NONE;
    }

    public void cancel() {
        mode = TaskMode.NONE;
        rushFilter = TeamColor.UNKNOWN;
        rushWeakestMode = false;
        recallForce = false;
        actionCooldown = 0;
        buyPreset = ShopPreset.AUTO;
        buyCategorySwitched = false;
        buyClicksDone = 0;
        buyStallTicks = 0;
        defendButterfly = false;
        fireballYawExplicit = false;
        fireballAirTicks = 0;
        fireballTimeoutTicks = 0;
        CraftingHelper.resetGuiPacer();
        baritone.getInputOverrideHandler().clearAllKeys();
    }

    @Override
    public void onLostControl() {
        cancel();
    }

    @Override
    public double priority() {
        return 3.5;
    }

    @Override
    public String displayName0() {
        return "Bedwars[" + mode.name() + "]";
    }

    @Override
    public PathingCommand onTick(boolean calcFailed, boolean isSafeToCancel) {
        if (ctx.player() == null || ctx.world() == null || mode == TaskMode.NONE) {
            return null;
        }
        if (actionCooldown > 0) {
            actionCooldown--;
        }

        // Edge Case E: rubberband / bridge lagback stabilization + downward wool clutch
        if (handleLagbackEmergencyClutch()) {
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }

        return switch (mode) {
            case RUSH -> tickRush(calcFailed);
            case DIAMONDS -> tickGenerator(GenType.DIAMOND);
            case EMERALDS -> tickGenerator(GenType.EMERALD);
            case HOME_GEN -> tickGenerator(GenType.HOME_FORGE);
            case STASH -> tickStash();
            case RECALL -> tickRecall();
            case BUY -> tickBuy();
            case DEFEND -> tickDefend();
            case BLOCK_IN -> tickBlockIn();
            case FIREBALL_JUMP -> tickFireballJump();
            case RETREAT -> tickRetreat();
            case NONE -> null;
        };
    }

    private PathingCommand tickRush(boolean calcFailed) {
        BedPOI targetBed = selectTargetBed();
        if (targetBed == null) {
            logDirect("All tracked enemy beds destroyed or out of scan! Stopping rush.");
            cancel();
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }

        BlockPos bedHead = new BlockPos(targetBed.x(), targetBed.y(), targetBed.z());
        BlockState headState = ctx.world().getBlockState(bedHead);
        if (ctx.world().isLoaded(bedHead) && !(headState.getBlock() instanceof BedBlock)) {
            BlockPos bedFoot = new BlockPos(targetBed.footX(), targetBed.footY(), targetBed.footZ());
            if (!(ctx.world().getBlockState(bedFoot).getBlock() instanceof BedBlock)) {
                BedwarsMapCore core = baritone.getBedwarsArenaMemory().getActiveCore();
                if (core != null) {
                    core.markBedDestroyedAt(bedHead.getX(), bedHead.getY(), bedHead.getZ());
                }
                return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
            }
        }

        ToolProfile tools = inspectToolProfile(ctx.player());
        BreakVectorDecision decision = evaluateBestBedBreakVector(targetBed, tools);

        if (decision.bestVector() == null) {
            return new PathingCommand(new GoalNear(bedHead, 3), PathingCommandType.SET_GOAL_AND_PATH);
        }

        BreakVectorCandidate best = decision.bestVector();
        BlockPos standPos = new BlockPos(best.standX(), best.standY(), best.standZ());
        double reachSq = Math.pow(ctx.playerController().getBlockReachDistance(), 2);
        BlockPos firstBlocking = findFirstSolidAlongBreakVector(targetBed, best);

        // Close-threat tactical block-in when digging into a defended bed while an enemy approaches
        if (firstBlocking != null
                && !(ctx.world().getBlockState(firstBlocking).getBlock() instanceof BedBlock)
                && ctx.playerFeet().distSqr(standPos) <= 6.25
                && hasNearbyEnemyWithin(9.0)) {
            if (tryPlaceNextBlockInStep(bedHead)) {
                return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
            }
        }

        if (firstBlocking != null && ctx.playerHead().distanceToSqr(Vec3.atCenterOf(firstBlocking)) <= reachSq) {
            baritone.getInputOverrideHandler().clearMovementKeys();
            BlockState targetState = ctx.world().getBlockState(firstBlocking);
            MovementHelper.switchToBestToolFor(ctx, targetState);
            Rotation aim = RotationUtils.calcRotationFromVec3d(ctx.playerHead(), Vec3.atCenterOf(firstBlocking), ctx.playerRotations());
            baritone.getLookBehavior().updateTarget(aim, true, true);
            if (ctx.isLookingAt(firstBlocking) || MouseModel.angularDistance(ctx.playerRotations(), aim) <= 9.0f) {
                baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_LEFT, true);
            }
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }

        Goal goal = calcFailed ? new GoalNear(bedHead, 2) : new GoalBlock(standPos);
        return new PathingCommand(goal, PathingCommandType.SET_GOAL_AND_PATH);
    }

    private PathingCommand tickGenerator(GenType type) {
        BedwarsMapCore core = baritone.getBedwarsArenaMemory().getActiveCore();
        if (core == null) {
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }
        LocalPlayer p = ctx.player();
        GenPOI chosen = null;
        double bestScore = Double.POSITIVE_INFINITY;
        for (GenPOI g : core.getGens()) {
            if (g.type() != type) {
                continue;
            }
            double distSq = p.blockPosition().distSqr(new BlockPos(g.x(), g.y() + 1, g.z()));
            if (type == GenType.HOME_FORGE) {
                for (BedPOI b : core.getBeds()) {
                    if (b.isHome()) {
                        distSq = b.x() == 0 && b.z() == 0 ? distSq : Math.hypot(g.x() - b.x(), g.z() - b.z());
                        break;
                    }
                }
            }
            if (distSq < bestScore) {
                bestScore = distSq;
                chosen = g;
            }
        }
        if (chosen == null) {
            logDirect("No " + type.name() + " generator indexed yet; run '#bws scan'.");
            cancel();
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }
        BlockPos stand = new BlockPos(chosen.x(), chosen.y() + 1, chosen.z());
        if (p.blockPosition().distSqr(stand) <= 1.6) {
            baritone.getInputOverrideHandler().clearMovementKeys();
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }
        return new PathingCommand(new GoalNear(stand, 0), PathingCommandType.SET_GOAL_AND_PATH);
    }

    private PathingCommand tickStash() {
        LocalPlayer p = ctx.player();
        AbstractContainerMenu menu = p.containerMenu;
        if (menu != p.inventoryMenu && !(menu instanceof CraftingMenu) && !(menu instanceof AbstractFurnaceMenu) && menu.slots.size() >= 63) {
            boolean deposited = CraftingHelper.stepContainerQuickDeposit(
                    ctx,
                    BedwarsProcess::isStashableValuable,
                    item -> item == Items.IRON_INGOT ? 16 : 0
            );
            if (!deposited) {
                if (CraftingHelper.safeCloseContainer(ctx)) {
                    logDirect("Bedwars chest stash complete.");
                    cancel();
                }
            }
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }

        BlockPos chestPos = CraftingHelper.findNearbyBlock(ctx, b -> b == Blocks.ENDER_CHEST, 28);
        if (chestPos == null) {
            chestPos = CraftingHelper.findNearbyBlock(ctx, b -> b == Blocks.CHEST || b == Blocks.TRAPPED_CHEST, 22);
        }
        if (chestPos == null) {
            logDirect("No nearby Ender Chest or Team Chest found to stash resources.");
            cancel();
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }
        final BlockPos targetChest = chestPos;
        if (ctx.playerFeet().distSqr(targetChest) > 12.25) {
            return new PathingCommand(new GoalNear(targetChest, 2), PathingCommandType.SET_GOAL_AND_PATH);
        }
        baritone.getInputOverrideHandler().clearMovementKeys();
        Rotation rot = RotationUtils.reachable(ctx, targetChest)
                .orElseGet(() -> RotationUtils.calcRotationFromVec3d(ctx.playerHead(), Vec3.atCenterOf(targetChest), ctx.playerRotations()));
        baritone.getLookBehavior().updateTarget(rot, true, true);
        if (actionCooldown == 0 && (ctx.isLookingAt(targetChest) || MouseModel.angularDistance(ctx.playerRotations(), rot) <= 6.0f)) {
            baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_RIGHT, true);
            actionCooldown = 6;
        }
        return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
    }

    private PathingCommand tickRecall() {
        LocalPlayer p = ctx.player();
        BedwarsMapCore core = baritone.getBedwarsArenaMemory().getActiveCore();
        int voidFloor = core != null ? core.voidFloorY() : (p.getBlockY() - 20);
        if (!p.onGround() && p.getY() <= voidFloor + 2) {
            cancel();
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }

        if (!recallForce && !BedwarsDefensePlanner.isSafeForTacticalVoid(
                countItem(p, Items.IRON_INGOT),
                countItem(p, Items.GOLD_INGOT),
                countItem(p, Items.DIAMOND),
                countItem(p, Items.EMERALD)
        )) {
            logDirect("Valuable resources picked up — cancelling tactical recall.");
            cancel();
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }

        BlockPos edgeDrop = findNearestVoidDropTarget(p.blockPosition(), voidFloor);
        if (edgeDrop == null) {
            logDirect("No immediate void drop edge found nearby.");
            cancel();
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }
        if (p.position().distanceToSqr(edgeDrop.getX() + 0.5, p.getY(), edgeDrop.getZ() + 0.5) <= 2.25) {
            Rotation look = RotationUtils.calcRotationFromVec3d(
                    ctx.playerHead(),
                    new Vec3(edgeDrop.getX() + 0.5, p.getY() + 1.2, edgeDrop.getZ() + 0.5),
                    ctx.playerRotations()
            );
            baritone.getLookBehavior().updateTarget(look, true, true);
            baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_FORWARD, true);
            baritone.getInputOverrideHandler().setInputForceState(Input.SPRINT, true);
            if (p.onGround()) {
                baritone.getInputOverrideHandler().setInputForceState(Input.JUMP, true);
            }
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }
        return new PathingCommand(new GoalNear(edgeDrop, 1), PathingCommandType.SET_GOAL_AND_PATH);
    }

    private boolean handleLagbackEmergencyClutch() {
        BedwarsArenaMemory memory = baritone.getBedwarsArenaMemory();
        if (!memory.isInLagbackCooldown()) {
            return false;
        }
        LocalPlayer p = ctx.player();
        if (p == null || ctx.world() == null) {
            return false;
        }
        long now = ctx.world().getGameTime();
        // Release forward momentum and S-tap briefly after server teleport/lagback
        baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_FORWARD, false);
        baritone.getInputOverrideHandler().setInputForceState(Input.SPRINT, false);
        baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_BACK, true);
        baritone.getInputOverrideHandler().setInputForceState(Input.SNEAK, true);

        if (!p.onGround() && p.getDeltaMovement().y < -0.18 && (now - lastClutchAttemptTick) >= 2L) {
            BlockHitResult sideHit = findClutchPlacementHit(p.blockPosition());
            if (sideHit != null && baritone.getInventoryBehavior().selectThrowawayForLocation(true,
                    sideHit.getBlockPos().getX(), sideHit.getBlockPos().getY(), sideHit.getBlockPos().getZ())) {
                Rotation clutchRot = RotationUtils.calcRotationFromVec3d(ctx.playerHead(), sideHit.getLocation(), ctx.playerRotations());
                baritone.getLookBehavior().updateTarget(clutchRot, true, true);
                if (MouseModel.angularDistance(ctx.playerRotations(), clutchRot) <= 18.0f) {
                    baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_RIGHT, true);
                    lastClutchAttemptTick = now;
                }
            }
        }
        return true;
    }

    private BlockHitResult findClutchPlacementHit(BlockPos feet) {
        for (int dy = -1; dy >= -2; dy--) {
            BlockPos targetAir = feet.offset(0, dy, 0);
            if (!ctx.world().getBlockState(targetAir).canBeReplaced()) {
                continue;
            }
            for (Direction dir : Direction.values()) {
                if (dir == Direction.UP) continue;
                BlockPos anchor = targetAir.relative(dir);
                if (MovementHelper.canWalkOn(ctx, anchor)) {
                    Vec3 hitVec = Vec3.atCenterOf(anchor).add(
                            -dir.getStepX() * 0.5,
                            -dir.getStepY() * 0.5,
                            -dir.getStepZ() * 0.5
                    );
                    return new BlockHitResult(hitVec, dir.getOpposite(), anchor, false);
                }
            }
        }
        return null;
    }

    private void ensureBedwarsActiveAndScanned() {
        BedwarsArenaMemory mem = baritone.getBedwarsArenaMemory();
        if (!mem.isBedwarsActive()) {
            mem.applyRuleset(BedwarsMapCore.BwsRuleset.OLD_1_8);
        }
        if (mem.getActiveCore() == null && ctx.world() != null && ctx.player() != null) {
            mem.captureSnapshotT0();
        }
    }

    private BedPOI selectTargetBed() {
        BedwarsMapCore core = baritone.getBedwarsArenaMemory().getActiveCore();
        if (core == null || ctx.player() == null) {
            return null;
        }
        BedPOI best = null;
        double bestScore = Double.POSITIVE_INFINITY;
        BlockPos me = ctx.playerFeet();
        ToolProfile tools = inspectToolProfile(ctx.player());
        for (BedPOI b : core.getBeds()) {
            if (b.isHome() || b.destroyed()) {
                continue;
            }
            if (rushFilter != TeamColor.UNKNOWN && b.team() != rushFilter) {
                continue;
            }
            double dSq = me.distSqr(new BlockPos(b.x(), b.y(), b.z()));
            double score = dSq;
            if (rushWeakestMode && rushFilter == TeamColor.UNKNOWN) {
                BreakVectorDecision dec = evaluateBestBedBreakVector(b, tools);
                score = dec.totalScoreTicks() + dSq * 0.015;
            }
            if (score < bestScore) {
                bestScore = score;
                best = b;
            }
        }
        return best;
    }

    /**
     * Edge Case C: Builds 6-sided + top-down X-Ray bed layer break vectors across head &amp; foot cells.
     */
    private BreakVectorDecision evaluateBestBedBreakVector(BedPOI bed, ToolProfile tools) {
        List<BreakVectorCandidate> candidates = new ArrayList<>(12);
        BlockPos head = new BlockPos(bed.x(), bed.y(), bed.z());
        BlockPos foot = new BlockPos(bed.footX(), bed.footY(), bed.footZ());
        Direction[] approachDirs = {Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST, Direction.UP};
        BedwarsMapCore core = baritone.getBedwarsArenaMemory().getActiveCore();

        for (BlockPos bedCell : new BlockPos[]{head, foot}) {
            for (Direction d : approachDirs) {
                List<DefenseBlockKind> layers = new ArrayList<>(3);
                BlockPos outerPos = bedCell;
                boolean blockedOriginal = false;
                for (int r = 1; r <= 3; r++) {
                    BlockPos layerPos = bedCell.relative(d, r);
                    BlockState st = ctx.world().getBlockState(layerPos);
                    if (st.getBlock() instanceof BedBlock) {
                        continue;
                    }
                    if (core != null && core.isProtectedMapOrHomeBed(layerPos.getX(), layerPos.getY(), layerPos.getZ())) {
                        blockedOriginal = true;
                        break;
                    }
                    DefenseBlockKind kind = BedwarsArenaMemory.classifyDefenseBlock(st.getBlock());
                    if (kind == DefenseBlockKind.ORIGINAL_MAP) {
                        blockedOriginal = true;
                        break;
                    }
                    if (kind != DefenseBlockKind.AIR) {
                        layers.add(kind);
                        outerPos = layerPos;
                    }
                }
                if (blockedOriginal) {
                    continue;
                }
                BlockPos standPos = outerPos.relative(d);
                if (d == Direction.UP) {
                    standPos = outerPos.above();
                } else if (!MovementHelper.canWalkOn(ctx, standPos.below())) {
                    // Check 1 block closer if outer layer was air
                    BlockPos innerStand = bedCell.relative(d, 2);
                    if (MovementHelper.canWalkOn(ctx, innerStand.below())) {
                        standPos = innerStand;
                    }
                }
                double distCost = Math.sqrt(Math.max(1.0, ctx.playerFeet().distSqr(standPos))) * 1.4;
                candidates.add(new BreakVectorCandidate(
                        standPos.getX(), standPos.getY(), standPos.getZ(),
                        outerPos.getX(), outerPos.getY(), outerPos.getZ(),
                        layers,
                        distCost
                ));
            }
        }
        return BedwarsDefensePlanner.chooseWeakestBreakVector(candidates, tools);
    }

    private BlockPos findFirstSolidAlongBreakVector(BedPOI bed, BreakVectorCandidate vector) {
        BlockPos head = new BlockPos(bed.x(), bed.y(), bed.z());
        BlockPos foot = new BlockPos(bed.footX(), bed.footY(), bed.footZ());
        BlockPos stand = new BlockPos(vector.standX(), vector.standY(), vector.standZ());

        // Find outermost non-air block between standPos and bed cell along step vector
        BlockPos targetBedCell = head.distSqr(stand) <= foot.distSqr(stand) ? head : foot;
        int dx = Integer.signum(targetBedCell.getX() - stand.getX());
        int dy = Integer.signum(targetBedCell.getY() - stand.getY());
        int dz = Integer.signum(targetBedCell.getZ() - stand.getZ());
        for (int step = 1; step <= 4; step++) {
            BlockPos check = stand.offset(dx * step, dy * step, dz * step);
            BlockState st = ctx.world().getBlockState(check);
            if (!(st.getBlock() instanceof AirBlock) && st.getFluidState().isEmpty()) {
                return check;
            }
            if (check.equals(targetBedCell)) {
                return targetBedCell;
            }
        }
        if (!(ctx.world().getBlockState(new BlockPos(vector.targetX(), vector.targetY(), vector.targetZ())).getBlock() instanceof AirBlock)) {
            return new BlockPos(vector.targetX(), vector.targetY(), vector.targetZ());
        }
        return ctx.world().getBlockState(head).getBlock() instanceof BedBlock ? head : foot;
    }

    private BlockPos findNearestVoidDropTarget(BlockPos origin, int voidFloorY) {
        BlockPos best = null;
        double bestDistSq = Double.POSITIVE_INFINITY;
        for (int r = 1; r <= 18; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.abs(dx) != r && Math.abs(dz) != r) {
                        continue;
                    }
                    BlockPos cand = origin.offset(dx, 0, dz);
                    if (!ctx.world().getBlockState(cand).canBeReplaced() || !ctx.world().getBlockState(cand.above()).canBeReplaced()) {
                        continue;
                    }
                    boolean hasFloorBelow = false;
                    for (int drop = 1; drop <= Math.max(8, origin.getY() - voidFloorY + 4); drop++) {
                        if (!ctx.world().getBlockState(cand.below(drop)).canBeReplaced()) {
                            hasFloorBelow = true;
                            break;
                        }
                    }
                    if (!hasFloorBelow) {
                        double dSq = origin.distSqr(cand);
                        if (dSq < bestDistSq) {
                            bestDistSq = dSq;
                            best = cand;
                        }
                    }
                }
            }
            if (best != null) {
                return best;
            }
        }
        return null;
    }

    private static boolean isStashableValuable(ItemStack stack) {
        Item item = stack.getItem();
        return item == Items.DIAMOND
                || item == Items.EMERALD
                || item == Items.GOLD_INGOT
                || item == Items.IRON_INGOT
                || item == Items.ENDER_PEARL
                || item == Items.TNT
                || item == Items.FIRE_CHARGE
                || item == Items.GOLDEN_APPLE;
    }

    private static ToolProfile inspectToolProfile(LocalPlayer p) {
        if (p == null) {
            return ToolProfile.bareHand();
        }
        int shears = 0;
        int pick = 0;
        int axe = 0;
        NonNullList<ItemStack> inv = p.getInventory().getNonEquipmentItems();
        for (ItemStack st : inv) {
            if (st.isEmpty()) continue;
            if (st.is(Items.SHEARS)) {
                shears = Math.max(shears, 1);
                continue;
            }
            String path = BuiltInRegistries.ITEM.getKey(st.getItem()).getPath();
            int tier = toolTierFromPath(path);
            if (path.endsWith("_pickaxe")) {
                pick = Math.max(pick, tier);
            } else if (path.endsWith("_axe")) {
                axe = Math.max(axe, tier);
            }
        }
        return new ToolProfile(shears, pick, axe);
    }

    private static int toolTierFromPath(String path) {
        if (path.startsWith("diamond_") || path.startsWith("netherite_")) return 4;
        if (path.startsWith("iron_") || path.startsWith("golden_")) return 3;
        if (path.startsWith("stone_")) return 2;
        if (path.startsWith("wooden_")) return 1;
        return 0;
    }

    private static int countItem(LocalPlayer p, Item target) {
        int sum = 0;
        for (ItemStack st : p.getInventory().getNonEquipmentItems()) {
            if (!st.isEmpty() && st.is(target)) {
                sum += st.getCount();
            }
        }
        return sum;
    }

    private PathingCommand tickBuy() {
        LocalPlayer p = ctx.player();
        BedwarsArenaMemory memory = baritone.getBedwarsArenaMemory();
        BedwarsMapCore core = memory.getActiveCore();
        boolean modern = memory.getActiveRuleset() == BwsRuleset.MODERN_26_3;
        AbstractContainerMenu menu = p.containerMenu;

        if (menu != p.inventoryMenu && !(menu instanceof CraftingMenu) && !(menu instanceof AbstractFurnaceMenu) && menu.slots.size() > 36) {
            int containerCount = menu.slots.size() - 36;
            List<ShopSlotInfo> offers = new ArrayList<>(containerCount);
            for (int i = 0; i < containerCount; i++) {
                Slot slot = menu.getSlot(i);
                ItemStack st = slot.getItem();
                if (st.isEmpty()) {
                    continue;
                }
                String itemPath = BuiltInRegistries.ITEM.getKey(st.getItem()).getPath();
                String display = BedwarsArenaMemory.stripColors(st.getHoverName().getString());
                StringBuilder loreSb = new StringBuilder();
                var lore = st.get(DataComponents.LORE);
                if (lore != null) {
                    for (Component line : lore.lines()) {
                        if (!loreSb.isEmpty()) {
                            loreSb.append('\n');
                        }
                        loreSb.append(BedwarsArenaMemory.stripColors(line.getString()));
                    }
                }
                offers.add(new ShopSlotInfo(i, itemPath, display, loreSb.toString(), st.getCount()));
            }

            InventorySummary inv = BedwarsTacticalHelper.summarizeInventory(p, modern);
            BedPOI homeBed = findHomeBed(core);
            boolean homeAlive = homeBed != null && !homeBed.destroyed();
            double defRatio = computeBedCoverageRatio(homeBed);
            double nearestEnemy = findNearestEnemyDistance();
            ShopPreset effective = (buyPreset == ShopPreset.AUTO)
                    ? BedwarsShopPlanner.recommendPreset(inv, homeAlive, defRatio, nearestEnemy)
                    : buyPreset;

            if (buyClicksDone >= 10) {
                if (CraftingHelper.safeCloseContainer(ctx)) {
                    logDirect("Bedwars shop trip finished (" + buyClicksDone + " purchases).");
                    cancel();
                }
                return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
            }

            boolean inUpgradeShop = (buyPreset == ShopPreset.UPGRADES)
                    || offers.stream().anyMatch(o -> {
                        String up = o.displayName().toUpperCase(Locale.ROOT);
                        return up.contains("SHARPENED") || up.contains("REINFORCED ARMOR") || up.contains("MANIAC MINER") || up.contains("TRAP");
                    });
            ShopClickDecision click = BedwarsShopPlanner.chooseNextShopClick(
                    offers, effective, inv, inUpgradeShop, homeAlive, defRatio, nearestEnemy, buyCategorySwitched
            );
            if (click == null) {
                if (CraftingHelper.safeCloseContainer(ctx)) {
                    logDirect("Bedwars shop complete (" + buyClicksDone + " purchases).");
                    cancel();
                }
                return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
            }

            if (actionCooldown > 0) {
                return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
            }
            if (!CraftingHelper.advanceAndReadyGui(ctx, click.slotIndex())) {
                return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
            }
            ctx.playerController().windowClick(menu.containerId, click.slotIndex(), 0, ContainerInput.PICKUP, p);
            if (click.categorySwitch()) {
                buyCategorySwitched = true;
            } else {
                buyClicksDone++;
            }
            actionCooldown = Baritone.settings().humanMode.value ? 3 : 1;
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }

        boolean wantUpgrades = buyPreset == ShopPreset.UPGRADES
                || (buyPreset == ShopPreset.AUTO && BedwarsTacticalHelper.summarizeInventory(p, modern).diamonds() >= 2);
        Entity shopEntity = findBestShopEntity(core, wantUpgrades);
        BlockPos shopPos = findBestShopPos(core, shopEntity, wantUpgrades);
        if (shopPos == null) {
            logDirect("No Bedwars Shop/Upgrade NPC discovered nearby.");
            cancel();
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }

        if (shopEntity != null && p.distanceToSqr(shopEntity) <= 13.5) {
            baritone.getInputOverrideHandler().clearMovementKeys();
            Vec3 aimVec = shopEntity.getEyePosition();
            Rotation aim = RotationUtils.calcRotationFromVec3d(ctx.playerHead(), aimVec, ctx.playerRotations());
            baritone.getLookBehavior().updateTarget(aim, true, true);
            if (actionCooldown == 0 && MouseModel.angularDistance(ctx.playerRotations(), aim) <= 10.0f && ctx.minecraft().gameMode != null) {
                ctx.minecraft().gameMode.interact(p, shopEntity, new EntityHitResult(shopEntity, aimVec), InteractionHand.MAIN_HAND);
                actionCooldown = 6;
            }
            if (++buyStallTicks > 100) {
                logDirect("Timed out waiting for Bedwars shop menu.");
                cancel();
            }
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }

        if (++buyStallTicks > 240) {
            logDirect("Could not reach Bedwars shop NPC in time.");
            cancel();
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }
        return new PathingCommand(new GoalNear(shopPos, 2), PathingCommandType.SET_GOAL_AND_PATH);
    }

    private PathingCommand tickDefend() {
        BedwarsMapCore core = baritone.getBedwarsArenaMemory().getActiveCore();
        BedPOI homeBed = findHomeBed(core);
        if (homeBed == null || homeBed.destroyed()) {
            logDirect("No surviving home bed found to defend.");
            cancel();
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }
        List<DefensePlacement> plan = BedwarsTacticalHelper.buildHomeBedDefensePlan(
                homeBed.x(), homeBed.y(), homeBed.z(),
                homeBed.footX(), homeBed.footY(), homeBed.footZ(),
                defendButterfly,
                (x, y, z) -> !ctx.world().getBlockState(new BlockPos(x, y, z)).canBeReplaced()
                        || (core != null && core.isInNoPlaceZone(x, y, z))
        );
        if (plan.isEmpty()) {
            logDirect("Home bed defense shell complete.");
            cancel();
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }
        DefensePlacement next = plan.get(0);
        BlockPos targetPos = new BlockPos(next.x(), next.y(), next.z());
        if (!hasAnyDefenseBlock(next.hardPreferred())) {
            logDirect("Out of defense blocks for home bed.");
            cancel();
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }
        double distSq = ctx.player().position().distanceToSqr(targetPos.getX() + 0.5, targetPos.getY() + 0.5, targetPos.getZ() + 0.5);
        if (distSq > 16.0) {
            return new PathingCommand(new GoalNear(targetPos, 2), PathingCommandType.SET_GOAL_AND_PATH);
        }
        if (ctx.player().getBoundingBox().intersects(
                targetPos.getX() + 0.05, targetPos.getY() + 0.05, targetPos.getZ() + 0.05,
                targetPos.getX() + 0.95, targetPos.getY() + 0.95, targetPos.getZ() + 0.95)) {
            return new PathingCommand(new GoalNear(new BlockPos(homeBed.x(), homeBed.y(), homeBed.z()).offset(2, 0, 2), 1), PathingCommandType.SET_GOAL_AND_PATH);
        }
        placeDefensePlacement(targetPos, next.hardPreferred());
        return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
    }

    private PathingCommand tickBlockIn() {
        BedPOI nearestEnemy = findNearestLivingEnemyBed();
        BlockPos ref = nearestEnemy != null
                ? new BlockPos(nearestEnemy.x(), nearestEnemy.y(), nearestEnemy.z())
                : ctx.playerFeet().relative(ctx.player().getDirection(), 3);
        if (!tryPlaceNextBlockInStep(ref)) {
            logDirect("Block-in complete or out of blocks.");
            cancel();
        }
        return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
    }

    private boolean tryPlaceNextBlockInStep(BlockPos bedOrRef) {
        BedwarsMapCore core = baritone.getBedwarsArenaMemory().getActiveCore();
        BlockPos feet = ctx.playerFeet();
        List<DefensePlacement> plan = BedwarsTacticalHelper.buildBlockInPlan(
                feet.getX(), feet.getY(), feet.getZ(),
                bedOrRef.getX(), bedOrRef.getZ(),
                (x, y, z) -> !ctx.world().getBlockState(new BlockPos(x, y, z)).canBeReplaced()
                        || (core != null && core.isInNoPlaceZone(x, y, z))
        );
        if (plan.isEmpty() || !hasAnyDefenseBlock(false)) {
            return false;
        }
        BlockPos target = new BlockPos(plan.get(0).x(), plan.get(0).y(), plan.get(0).z());
        return placeDefensePlacement(target, false);
    }

    private PathingCommand tickFireballJump() {
        LocalPlayer p = ctx.player();
        int fbSlot = findHotbarSlotForItem(p, Items.FIRE_CHARGE);
        if (fbSlot < 0) {
            logDirect("No Fire Charge in hotbar for fireball jump.");
            cancel();
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }
        if (!baritone.getInventoryBehavior().safeSelectHotbarSlot(fbSlot)) {
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }
        float travelYaw = fireballYawExplicit ? fireballTargetYaw : p.getYRot();
        if (!p.onGround()) {
            fireballAirTicks++;
        } else if (fireballAirTicks > 3) {
            cancel();
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }

        FireballJumpPlan plan = BedwarsTacticalHelper.computeFireballJumpPlan(travelYaw, p.onGround(), fireballAirTicks);
        Rotation launchRot = new Rotation(plan.launchYaw(), plan.launchPitch());
        baritone.getLookBehavior().updateTarget(launchRot, true, true);
        baritone.getInputOverrideHandler().setDesiredMovementYaw(travelYaw);
        baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_FORWARD, true);
        baritone.getInputOverrideHandler().setInputForceState(Input.SPRINT, true);

        if (plan.readyToJump() && MouseModel.angularDistance(ctx.playerRotations(), launchRot) <= 16.0f) {
            baritone.getInputOverrideHandler().setInputForceState(Input.JUMP, true);
        }
        if (plan.readyToFire() && actionCooldown == 0 && MouseModel.angularDistance(ctx.playerRotations(), launchRot) <= 18.0f) {
            baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_RIGHT, true);
            actionCooldown = 4;
            fireballAirTicks = 4;
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }
        if (++fireballTimeoutTicks > 40) {
            cancel();
        }
        return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
    }

    private PathingCommand tickRetreat() {
        BedwarsMapCore core = baritone.getBedwarsArenaMemory().getActiveCore();
        BlockPos home = findHomeAnchorPos(core);
        if (home == null) {
            cancel();
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }
        if (ctx.playerFeet().distSqr(home) <= 42.0) {
            logDirect("Arrived safely at home base.");
            cancel();
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }
        return new PathingCommand(new GoalNear(home, 3), PathingCommandType.SET_GOAL_AND_PATH);
    }

    private boolean placeDefensePlacement(BlockPos targetPos, boolean hardPreferred) {
        if (!ctx.world().getBlockState(targetPos).canBeReplaced()) {
            return false;
        }
        int slot = selectDefenseBlockSlot(ctx.player(), hardPreferred);
        if (slot >= 0) {
            if (!baritone.getInventoryBehavior().safeSelectHotbarSlot(slot)) {
                return false;
            }
        } else if (!baritone.getInventoryBehavior().throwaway(true, st -> isDefenseStack(st, hardPreferred), true)
                && (!hardPreferred || !baritone.getInventoryBehavior().throwaway(true, st -> isDefenseStack(st, false), true))) {
            return false;
        }
        BlockPos bestAnchor = null;
        Direction bestDir = null;
        BlockPos bedFallbackAnchor = null;
        Direction bedFallbackDir = null;
        for (Direction dir : Direction.values()) {
            BlockPos adj = targetPos.relative(dir);
            BlockState adjState = ctx.world().getBlockState(adj);
            if (!adjState.canBeReplaced()) {
                if (adjState.getBlock() instanceof BedBlock) {
                    if (bedFallbackAnchor == null) {
                        bedFallbackAnchor = adj;
                        bedFallbackDir = dir.getOpposite();
                    }
                } else {
                    bestAnchor = adj;
                    bestDir = dir.getOpposite();
                    break;
                }
            }
        }
        if (bestAnchor == null) {
            bestAnchor = bedFallbackAnchor;
            bestDir = bedFallbackDir;
        }
        if (bestAnchor == null || bestDir == null) {
            return false;
        }
        baritone.getInputOverrideHandler().clearMovementKeys();
        if (ctx.world().getBlockState(bestAnchor).getBlock() instanceof BedBlock) {
            baritone.getInputOverrideHandler().setInputForceState(Input.SNEAK, true);
        }
        Vec3 hitVec = Vec3.atCenterOf(bestAnchor).add(
                bestDir.getStepX() * 0.49,
                bestDir.getStepY() * 0.49,
                bestDir.getStepZ() * 0.49
        );
        Rotation rot = RotationUtils.calcRotationFromVec3d(ctx.playerHead(), hitVec, ctx.playerRotations());
        baritone.getLookBehavior().updateTarget(rot, true, true);
        if (actionCooldown == 0 && MouseModel.angularDistance(ctx.playerRotations(), rot) <= 11.0f) {
            baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_RIGHT, true);
            actionCooldown = Baritone.settings().humanMode.value ? 2 : 1;
        }
        return true;
    }

    private boolean hasAnyDefenseBlock(boolean hardPreferred) {
        LocalPlayer p = ctx.player();
        if (p == null) {
            return false;
        }
        for (ItemStack st : p.getInventory().getNonEquipmentItems()) {
            if (isDefenseStack(st, false)) {
                return true;
            }
        }
        return isDefenseStack(p.getOffhandItem(), false);
    }

    private static boolean isDefenseStack(ItemStack st, boolean hardOnly) {
        if (st == null || st.isEmpty()) {
            return false;
        }
        Item item = st.getItem();
        if (item == Items.END_STONE || item == Items.OBSIDIAN) {
            return true;
        }
        String path = BuiltInRegistries.ITEM.getKey(item).getPath();
        if (path.endsWith("terracotta") || path.endsWith("_planks")) {
            return true;
        }
        return !hardOnly && path.endsWith("_wool");
    }

    private int selectDefenseBlockSlot(LocalPlayer p, boolean hardPreferred) {
        int slot = findBestDefenseSlot(p, hardPreferred);
        if (slot < 0 && hardPreferred) {
            slot = findBestDefenseSlot(p, false);
        }
        return slot;
    }

    private int findBestDefenseSlot(LocalPlayer p, boolean hardOnly) {
        NonNullList<ItemStack> inv = p.getInventory().getNonEquipmentItems();
        int fallbackWool = -1;
        for (int i = 0; i < 9; i++) {
            ItemStack st = inv.get(i);
            if (st.isEmpty()) continue;
            Item item = st.getItem();
            if (item == Items.END_STONE || item == Items.OBSIDIAN) {
                return i;
            }
            String path = BuiltInRegistries.ITEM.getKey(item).getPath();
            if (path.endsWith("terracotta") || path.endsWith("_planks")) {
                return i;
            }
            if (!hardOnly && path.endsWith("_wool") && fallbackWool < 0) {
                fallbackWool = i;
            }
        }
        return fallbackWool;
    }

    private static int findHotbarSlotForItem(LocalPlayer p, Item target) {
        if (p == null) return -1;
        NonNullList<ItemStack> inv = p.getInventory().getNonEquipmentItems();
        for (int i = 0; i < 9; i++) {
            if (!inv.get(i).isEmpty() && inv.get(i).is(target)) {
                return i;
            }
        }
        return -1;
    }

    private Entity findBestShopEntity(BedwarsMapCore core, boolean wantUpgrades) {
        LocalPlayer me = ctx.player();
        Entity best = null;
        double bestScore = Double.POSITIVE_INFINITY;
        for (Entity e : ctx.entities()) {
            if (!(e instanceof LivingEntity le) || e == me || !e.isAlive()) {
                continue;
            }
            String typeId = BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).getPath();
            boolean isArmorStand = "armor_stand".equals(typeId);
            boolean isVillager = "villager".equals(typeId) || "wandering_trader".equals(typeId) || "blaze".equals(typeId);
            boolean isNpcPlayer = (e instanceof Player pl) && (pl.getUUID().version() == 2 || pl.isAlliedTo(me) == false && BedwarsArenaMemory.stripColors(pl.getName().getString()).toUpperCase(Locale.ROOT).contains("NPC"));
            String cleanName = BedwarsArenaMemory.stripColors(e.getName().getString()).toUpperCase(Locale.ROOT);
            boolean namedShop = cleanName.contains("SHOP") || cleanName.contains("UPGRADE");
            if (!isVillager && !isNpcPlayer && !namedShop) {
                continue;
            }
            if (e instanceof Player pl && !pl.getUUID().toString().isEmpty() && isEnemyBedwarsPlayer(me, pl) && !namedShop) {
                continue;
            }
            double distSq = me.distanceToSqr(e);
            if (distSq > 4096.0) {
                continue;
            }
            double penalty = isArmorStand ? 250.0 : 0.0;
            boolean matchesUpgrades = cleanName.contains("UPGRADE") || isNearUpgradePoi(core, e.blockPosition());
            if (wantUpgrades && !matchesUpgrades) {
                penalty += 900.0;
            } else if (!wantUpgrades && matchesUpgrades) {
                penalty += 900.0;
            }
            double score = distSq + penalty;
            if (score < bestScore) {
                bestScore = score;
                best = e;
            }
        }
        return best;
    }

    private boolean isNearUpgradePoi(BedwarsMapCore core, BlockPos pos) {
        if (core == null) return false;
        for (ShopNPC npc : core.getShopNpcs()) {
            if (npc.label().toUpperCase(Locale.ROOT).contains("UPGRADE")) {
                if (pos.distSqr(new BlockPos(npc.x(), npc.y(), npc.z())) <= 16.0) {
                    return true;
                }
            }
        }
        return false;
    }

    private BlockPos findBestShopPos(BedwarsMapCore core, Entity shopEntity, boolean wantUpgrades) {
        if (shopEntity != null) {
            return shopEntity.blockPosition();
        }
        if (core == null || ctx.player() == null) {
            return null;
        }
        BlockPos best = null;
        double bestScore = Double.POSITIVE_INFINITY;
        BlockPos me = ctx.playerFeet();
        for (ShopNPC npc : core.getShopNpcs()) {
            BlockPos p = new BlockPos(npc.x(), npc.y(), npc.z());
            double penalty = (wantUpgrades != npc.label().toUpperCase(Locale.ROOT).contains("UPGRADE")) ? 900.0 : 0.0;
            double score = me.distSqr(p) + penalty;
            if (score < bestScore) {
                bestScore = score;
                best = p;
            }
        }
        return best;
    }

    private BedPOI findHomeBed(BedwarsMapCore core) {
        if (core == null) return null;
        for (BedPOI b : core.getBeds()) {
            if (b.isHome()) {
                return b;
            }
        }
        return null;
    }

    private BedPOI findNearestLivingEnemyBed() {
        BedwarsMapCore core = baritone.getBedwarsArenaMemory().getActiveCore();
        if (core == null || ctx.player() == null) return null;
        BlockPos me = ctx.playerFeet();
        BedPOI best = null;
        double bestSq = Double.POSITIVE_INFINITY;
        for (BedPOI b : core.getBeds()) {
            if (b.isHome() || b.destroyed()) continue;
            double d = me.distSqr(new BlockPos(b.x(), b.y(), b.z()));
            if (d < bestSq) {
                bestSq = d;
                best = b;
            }
        }
        return best;
    }

    private BlockPos findHomeAnchorPos(BedwarsMapCore core) {
        BedPOI homeBed = findHomeBed(core);
        if (homeBed != null) {
            return new BlockPos(homeBed.x(), homeBed.y(), homeBed.z());
        }
        if (core != null) {
            for (SpawnPOI sp : core.getSpawns()) {
                if (sp.team() == core.homeTeam()) {
                    return new BlockPos(sp.x(), sp.y(), sp.z());
                }
            }
            for (GenPOI g : core.getGens()) {
                if (g.type() == GenType.HOME_FORGE) {
                    return new BlockPos(g.x(), g.y(), g.z());
                }
            }
        }
        return null;
    }

    private double computeBedCoverageRatio(BedPOI bed) {
        if (bed == null || ctx.world() == null) {
            return 0.0;
        }
        return BedwarsTacticalHelper.computeBedShellCoverageRatio(
                bed.x(), bed.y(), bed.z(),
                bed.footX(), bed.footY(), bed.footZ(),
                (x, y, z) -> !ctx.world().getBlockState(new BlockPos(x, y, z)).canBeReplaced()
        );
    }

    private double findNearestEnemyDistance() {
        LocalPlayer me = ctx.player();
        if (me == null) return 99.0;
        double best = 99.0;
        for (Entity e : ctx.entities()) {
            if (e instanceof Player pl && isEnemyBedwarsPlayer(me, pl)) {
                double d = me.distanceTo(pl);
                if (d < best) {
                    best = d;
                }
            }
        }
        return best;
    }

    private boolean hasNearbyEnemyWithin(double radius) {
        return findNearestEnemyDistance() <= radius;
    }

    private static boolean isEnemyBedwarsPlayer(LocalPlayer me, Entity e) {
        if (me == null || !(e instanceof Player other) || other == me || !other.isAlive() || other.isSpectator()) {
            return false;
        }
        if (me.isAlliedTo(other) || other.getUUID().version() == 2) {
            return false;
        }
        String clean = BedwarsArenaMemory.stripColors(other.getName().getString()).toUpperCase(Locale.ROOT);
        return !clean.contains("[NPC]") && !clean.contains("SHOP") && !clean.contains("UPGRADE");
    }
}
