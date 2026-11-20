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
import baritone.api.pathing.goals.GoalComposite;
import baritone.api.pathing.goals.GoalNear;
import baritone.api.process.PathingCommand;
import baritone.api.process.PathingCommandType;
import baritone.api.utils.RayTraceUtils;
import baritone.api.utils.Rotation;
import baritone.api.utils.RotationUtils;
import baritone.api.utils.input.Input;
import baritone.bedwars.BedwarsDefensePlanner.BedShellScan;
import baritone.bedwars.BedwarsDefensePlanner.BreakVectorCandidate;
import baritone.bedwars.BedwarsDefensePlanner.BreakVectorDecision;
import baritone.bedwars.BedwarsDefensePlanner.DefenseBlockKind;
import baritone.bedwars.BedwarsDefensePlanner.RayTargetResult;
import baritone.bedwars.BedwarsDefensePlanner.ToolProfile;
import baritone.bedwars.BedwarsMapCore.BedPOI;
import baritone.bedwars.BedwarsMapCore.BwsRuleset;
import baritone.bedwars.BedwarsMapCore.CachedBedDefenseProfile;
import baritone.bedwars.BedwarsMapCore.GenPOI;
import baritone.bedwars.BedwarsMapCore.GenType;
import baritone.bedwars.BedwarsMapCore.ShopNPC;
import baritone.bedwars.BedwarsMapCore.SpawnPOI;
import baritone.bedwars.BedwarsMapCore.TeamColor;
import baritone.bedwars.BedwarsShopPlanner.EnemyDefenseIntel;
import baritone.bedwars.BedwarsShopPlanner.MenuKind;
import baritone.bedwars.BedwarsShopPlanner.ShopClickDecision;
import baritone.bedwars.BedwarsShopPlanner.ShopPreset;
import baritone.bedwars.BedwarsShopPlanner.ShopSlotInfo;
import baritone.bedwars.BedwarsTickContext.HologramInfo;
import baritone.bedwars.BedwarsTacticalHelper.ClutchCandidate;
import baritone.bedwars.BedwarsTacticalHelper.CombatTargetContext;
import baritone.bedwars.BedwarsTacticalHelper.DefensePlacement;
import baritone.bedwars.BedwarsTacticalHelper.FireballJumpPlan;
import baritone.bedwars.BedwarsTacticalHelper.FireballThreat;
import baritone.bedwars.BedwarsTacticalHelper.InventorySummary;
import baritone.brain.BrainActionSpace.CombatMode;
import baritone.brain.BrainActionSpace.Maneuver;
import baritone.brain.BrainContextExtractor;
import baritone.human.CraftingHelper;
import baritone.human.MouseModel;
import baritone.pathing.movement.MovementHelper;
import baritone.process.CombatProcess;
import baritone.utils.BaritoneProcessHelper;
import baritone.utils.BlockStateInterface;
import baritone.utils.accessor.IGuiScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.AbstractFurnaceMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.DyedItemColor;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.AirBlock;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

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

    private ShopPreset buyPreset = ShopPreset.AUTO;
    private boolean buyCategorySwitched = false;
    private int buyClicksDone = 0;
    private int buyStallTicks = 0;
    private int buyZeroMoveTicks = 0;
    private Vec3 buyLastPos = null;
    private int buyEmptyContainerTicks = 0;
    private boolean defendButterfly = false;
    private int defendVisitTicks = 0;
    private int defendNoPlaceTicks = 0;
    private int defendLastPlanSize = -1;
    private BlockPos committedDefendTarget = null;
    private int committedDefendTicks = 0;
    private BlockPos lockedGenStand = null;
    private GenType lockedGenType = null;
    private final Set<Long> fakeHomeForgePositions = new HashSet<>();
    private int homeGenWaitTicks = 0;
    private int homeGenWaitStartResources = -1;
    private float fireballTargetYaw = 0.0f;
    private boolean fireballYawExplicit = false;
    private int fireballAirTicks = 0;
    private int fireballTimeoutTicks = 0;

    // Shop/Upgrade NPC learning + zero-spam cooldowns + tactical retry state
    private final Map<Long, MenuKind> learnedNpcRoles = new HashMap<>();
    private final Set<Long> wrongNpcThisTrip = new HashSet<>();
    private BlockPos lastInteractedNpcPos = null;
    private int learnedCoreBedsSig = 0;
    private long shopCooldownUntilTick = 0L;
    private long upgradeCooldownUntilTick = 0L;
    private long defendEmptyCooldownUntilTick = 0L;
    private long stashMissingCooldownUntilTick = 0L;
    private long awaitingShopResponseUntilTick = 0L;
    private long lastTabSwitchTick = -100L;
    private long lastShopClickRevision = -1L;
    private String lastShopClickCurrencySig = "";
    private String lastShopClickItemSig = "";
    private boolean lastShopClickWasTab = false;
    private boolean awaitingShopClose = false;
    private int cooldownIron = 0;
    private int cooldownGold = 0;
    private int cooldownDiamonds = 0;
    private int cooldownEmeralds = 0;

    private final Map<Long, Long> recentlyBrokenBlocks = new HashMap<>();
    private BlockPos lastMiningPos = null;
    private final Map<Long, Long> failedPlaceCooldownUntil = new HashMap<>();
    private final Map<Long, Integer> defensePlaceFailCounts = new HashMap<>();
    private BlockPos lastPlaceAttemptPos = null;
    private long lastPlaceAttemptTick = -100L;

    private final VoidClutchState voidClutchState = new VoidClutchState();
    private long lastFireballPunchTick = -100L;
    private long lockedTargetBedHeadKey = Long.MIN_VALUE;
    private long retreatUtilityCooldownUntilTick = 0L;
    private long lastGenHologramSeenTick = 0L;

    public static final class VoidClutchState {
        int chainTicks = 0;
        int startY = Integer.MIN_VALUE;
        int airAttempts = 0;
        long lastAttemptTick = -100L;

        public void reset() {
            this.chainTicks = 0;
            this.startY = Integer.MIN_VALUE;
            this.airAttempts = 0;
        }
    }

    public void resetVoidClutchState() {
        this.voidClutchState.reset();
    }

    public BedwarsProcess(Baritone baritone) {
        super(baritone);
    }

    public TaskMode getMode() {
        return mode;
    }

    public boolean isRushWeakestMode() {
        return rushWeakestMode;
    }

    public boolean isPreferringWeakestBed() {
        return rushWeakestMode;
    }

    public ShopPreset getActiveShopPreset() {
        return buyPreset;
    }

    public boolean isUpgradeBuyPreset() {
        return buyPreset == ShopPreset.UPGRADES;
    }

    public boolean isRecallMode() {
        return mode == TaskMode.RECALL;
    }

    /**
     * Shared routing predicate matching {@link #executeBrainManeuver(Maneuver)} for
     * {@code SHOP_BUY_COMBAT_UPGRADES} so {@link baritone.brain.BrainProcess} and
     * {@link BedwarsProcess} NEVER disagree on upgrade-shop vs item-shop cooldowns.
     */
    public static boolean wantsUpgradeShopForCombatUpgrades(InventorySummary inv, BedwarsMatchState ms) {
        if (inv == null) {
            return false;
        }
        boolean needTrap = inv.diamonds() >= 1 && (ms == null || ms.getActiveTrapCount() == 0 || !ms.hasHealPool());
        boolean urgentCombat = (inv.gold() >= 7 && inv.swordTier() < 3) || (inv.iron() >= 40 && inv.armorPoints() < 9.0);
        return inv.diamonds() >= 2 || (needTrap && !urgentCombat);
    }

    public boolean isCombatUpgradesShopCooldownBlocking(InventorySummary inv, BedwarsMatchState ms) {
        return isShopCooldownBlocking(wantsUpgradeShopForCombatUpgrades(inv, ms));
    }

    public boolean isShopCooldownBlocking(boolean upgrades) {
        if (ctx.world() == null || ctx.player() == null) {
            return false;
        }
        long now = ctx.world().getGameTime();
        long until = upgrades ? upgradeCooldownUntilTick : shopCooldownUntilTick;
        boolean modern = baritone.getBedwarsArenaMemory().getActiveRuleset() == BwsRuleset.MODERN_26_3;
        InventorySummary inv = baritone.getBedwarsArenaMemory().getTickContext()
                .getInventorySummary(ctx.player(), modern, now);
        return isShopCooldownActive(now, until, inv, cooldownIron, cooldownGold, cooldownDiamonds, cooldownEmeralds);
    }

    public boolean isDefendCooldownBlocking() {
        return ctx.world() != null && ctx.world().getGameTime() < defendEmptyCooldownUntilTick;
    }

    public boolean isStashCooldownBlocking() {
        return ctx.world() != null && ctx.world().getGameTime() < stashMissingCooldownUntilTick;
    }

    public static boolean isShopCooldownActive(
            long nowTick,
            long cooldownUntilTick,
            InventorySummary inv,
            int snapIron,
            int snapGold,
            int snapDiamonds,
            int snapEmeralds
    ) {
        if (nowTick >= cooldownUntilTick) {
            return false;
        }
        if (inv == null) {
            return true;
        }
        // Reset early if player respawned / lost inventory value with low blocks
        int snapScore = snapIron + snapGold * 4 + snapDiamonds * 8 + snapEmeralds * 12;
        int curScore = inv.iron() + inv.gold() * 4 + inv.diamonds() * 8 + inv.emeralds() * 12;
        if (curScore + 4 < snapScore && inv.bridgeBlockCount() < 16) {
            return false;
        }
        // Unlock cooldown early if generator drops or kills brought meaningful fresh currency
        return inv.iron() < snapIron + 12
                && inv.gold() < snapGold + 4
                && inv.diamonds() < snapDiamonds + 2
                && inv.emeralds() < snapEmeralds + 2;
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
        this.buyZeroMoveTicks = 0;
        this.buyLastPos = null;
        this.buyEmptyContainerTicks = 0;
        this.awaitingShopResponseUntilTick = 0L;
        this.lastShopClickRevision = -1L;
        this.lastShopClickCurrencySig = "";
        this.lastShopClickItemSig = "";
        this.lastShopClickWasTab = false;
        this.awaitingShopClose = false;
        this.wrongNpcThisTrip.clear();
        this.lastInteractedNpcPos = null;
        this.mode = TaskMode.BUY;
        this.actionCooldown = 0;
        CraftingHelper.resetGuiPacer();
        return "Bedwars autonomous shop run started (preset=" + buyPreset.name() + ").";
    }

    public synchronized String startDefend(boolean butterfly) {
        ensureBedwarsActiveAndScanned();
        this.defendButterfly = butterfly;
        this.defendVisitTicks = 0;
        this.defendNoPlaceTicks = 0;
        this.defendLastPlanSize = -1;
        this.committedDefendTarget = null;
        this.committedDefendTicks = 0;
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
                if (isShopCooldownBlocking(false)) {
                    return;
                }
                if (mode != TaskMode.BUY || buyPreset != ShopPreset.AUTO) {
                    startBuy("auto");
                }
            }
            case SHOP_BUY_COMBAT_UPGRADES -> {
                ShopPreset wanted = ShopPreset.COMBAT;
                if (ctx.player() != null && ctx.world() != null) {
                    boolean modern = baritone.getBedwarsArenaMemory().getActiveRuleset() == BwsRuleset.MODERN_26_3;
                    InventorySummary inv = baritone.getBedwarsArenaMemory().getTickContext()
                            .getInventorySummary(ctx.player(), modern, ctx.world().getGameTime());
                    BedwarsMatchState ms = baritone.getBedwarsArenaMemory().getMatchState();
                    if (wantsUpgradeShopForCombatUpgrades(inv, ms)) {
                        wanted = ShopPreset.UPGRADES;
                    }
                }
                if (isShopCooldownBlocking(wanted == ShopPreset.UPGRADES)) {
                    return;
                }
                if (mode != TaskMode.BUY || buyPreset != wanted) {
                    startBuy(wanted == ShopPreset.UPGRADES ? "upgrades" : "combat");
                }
            }
            case DEFEND_HOME_BED -> {
                if (mode != TaskMode.DEFEND) {
                    boolean modern = baritone.getBedwarsArenaMemory().getActiveRuleset() == BwsRuleset.MODERN_26_3;
                    InventorySummary inv = (ctx.player() != null && ctx.world() != null)
                            ? baritone.getBedwarsArenaMemory().getTickContext()
                                    .getInventorySummary(ctx.player(), modern, ctx.world().getGameTime())
                            : null;
                    boolean twoLayer = inv != null && inv.hardDefenseCount() >= 4 && inv.bridgeBlockCount() >= 24;
                    startDefend(twoLayer);
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
        this.lockedGenStand = null;
        this.lockedGenType = null;
        this.homeGenWaitTicks = 0;
        this.homeGenWaitStartResources = -1;
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
        cancel("unspecified");
    }

    public void cancel(String reason) {
        TaskMode prevMode = mode;
        if (prevMode != TaskMode.NONE && baritone.getBedwarsBlackboxRecorder() != null
                && baritone.getBedwarsBlackboxRecorder().isRecording()) {
            baritone.getBedwarsBlackboxRecorder().recordEvent(
                    "BWS_CANCEL", prevMode.name() + " -> NONE (" + (reason != null ? reason : "unspecified") + ")"
            );
        }
        mode = TaskMode.NONE;
        lockedTargetBedHeadKey = Long.MIN_VALUE;
        rushFilter = TeamColor.UNKNOWN;
        rushWeakestMode = false;
        recallForce = false;
        actionCooldown = 0;
        buyPreset = ShopPreset.AUTO;
        buyCategorySwitched = false;
        buyClicksDone = 0;
        buyStallTicks = 0;
        buyZeroMoveTicks = 0;
        buyLastPos = null;
        buyEmptyContainerTicks = 0;
        awaitingShopResponseUntilTick = 0L;
        lastShopClickRevision = -1L;
        lastShopClickCurrencySig = "";
        lastShopClickItemSig = "";
        lastShopClickWasTab = false;
        awaitingShopClose = false;
        wrongNpcThisTrip.clear();
        lastInteractedNpcPos = null;
        defendButterfly = false;
        defendVisitTicks = 0;
        defendNoPlaceTicks = 0;
        defendLastPlanSize = -1;
        lockedGenStand = null;
        lockedGenType = null;
        homeGenWaitTicks = 0;
        homeGenWaitStartResources = -1;
        fireballYawExplicit = false;
        fireballAirTicks = 0;
        fireballTimeoutTicks = 0;
        lastMiningPos = null;
        lastPlaceAttemptPos = null;
        voidClutchState.reset();
        CraftingHelper.resetGuiPacer();
        baritone.getInputOverrideHandler().clearAllKeys();
    }

    @Override
    public void onLostControl() {
        cancel("lost_control");
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
        long tickStartNs = System.nanoTime();
        try {
            return onTickInternal(calcFailed, isSafeToCancel);
        } finally {
            long elapsedNs = System.nanoTime() - tickStartNs;
            if (elapsedNs > 1_000_000L && baritone.getBedwarsBlackboxRecorder() != null
                    && baritone.getBedwarsBlackboxRecorder().isRecording()) {
                baritone.getBedwarsBlackboxRecorder().recordEvent(
                        "PERF_WARN",
                        String.format(Locale.ROOT, "BedwarsProcess.onTick=%.2fms mode=%s",
                                elapsedNs / 1_000_000.0, mode.name())
                );
            }
        }
    }

    private PathingCommand onTickInternal(boolean calcFailed, boolean isSafeToCancel) {
        if (ctx.player() == null || ctx.world() == null || mode == TaskMode.NONE) {
            return null;
        }
        LocalPlayer me = ctx.player();
        if (!me.isAlive() || me.isDeadOrDying() || me.isSpectator() || ctx.world().dimension() == Level.NETHER) {
            // Reset stale shop/stash cooldowns on death/respawn so newly spawned gear purchases aren't blocked
            shopCooldownUntilTick = 0L;
            upgradeCooldownUntilTick = 0L;
            defendEmptyCooldownUntilTick = 0L;
            stashMissingCooldownUntilTick = 0L;
            awaitingShopResponseUntilTick = 0L;
            awaitingShopClose = false;
            lastShopClickItemSig = "";
            lastShopClickCurrencySig = "";
            wrongNpcThisTrip.clear();
            baritone.getInputOverrideHandler().clearAllKeys();
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }
        if (baritone.getBedwarsArenaMemory().isInWaitingLobby()) {
            baritone.getInputOverrideHandler().clearAllKeys();
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }
        if (baritone.getCombatProcess() instanceof CombatProcess cp) {
            cp.setContextualPriorityResolver(this::resolveCombatPriorityAdjustment);
        }
        long now = ctx.world().getGameTime();
        BedwarsMapCore activeCore = baritone.getBedwarsArenaMemory().getActiveCore();
        if (activeCore != null) {
            int bedsSig = activeCore.getBeds().size() * 31 + activeCore.homeTeam().ordinal();
            if (bedsSig != learnedCoreBedsSig) {
                learnedCoreBedsSig = bedsSig;
                learnedNpcRoles.clear();
                defensePlaceFailCounts.clear();
                fakeHomeForgePositions.clear();
            }
        }
        if (actionCooldown > 0) {
            actionCooldown--;
        }
        if (lastMiningPos != null && ctx.world().getBlockState(lastMiningPos).canBeReplaced()) {
            recentlyBrokenBlocks.put(lastMiningPos.asLong(), now + 12L);
            if (baritone.getBedwarsBlackboxRecorder() != null) {
                baritone.getBedwarsBlackboxRecorder().recordEvent("BED_SHELL_BREAK",
                        lastMiningPos.getX() + "," + lastMiningPos.getY() + "," + lastMiningPos.getZ());
            }
            lastMiningPos = null;
        }
        if (lastPlaceAttemptPos != null && now - lastPlaceAttemptTick >= 4L) {
            if (ctx.world().getBlockState(lastPlaceAttemptPos).canBeReplaced()) {
                long key = lastPlaceAttemptPos.asLong();
                int fails = defensePlaceFailCounts.getOrDefault(key, 0) + 1;
                defensePlaceFailCounts.put(key, fails);
                failedPlaceCooldownUntil.put(key, now + 30L);
                if (fails >= 4) {
                    baritone.getBedwarsArenaMemory().recordBlockedDefenseAndSave(
                            lastPlaceAttemptPos.getX(), lastPlaceAttemptPos.getY(), lastPlaceAttemptPos.getZ()
                    );
                }
            }
            lastPlaceAttemptPos = null;
        }

        // Never leave a shop/utility chest GUI open when Brain or combat switches away from BUY/STASH
        AbstractContainerMenu openMenu = me.containerMenu;
        if (mode != TaskMode.BUY && mode != TaskMode.STASH
                && openMenu != me.inventoryMenu
                && !(openMenu instanceof CraftingMenu)
                && !(openMenu instanceof AbstractFurnaceMenu)
                && openMenu.slots.size() > 36) {
            CraftingHelper.safeCloseContainer(ctx);
            baritone.getInputOverrideHandler().clearAllKeys();
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }
        Minecraft mcScreenCheck = ctx.minecraft();
        boolean botContainerUi = mcScreenCheck != null
                && mcScreenCheck.gui != null
                && mcScreenCheck.gui.screen() instanceof AbstractContainerScreen<?>
                && (mode == TaskMode.BUY || mode == TaskMode.STASH);
        if (mcScreenCheck != null && (((mcScreenCheck.gui != null && mcScreenCheck.gui.screen() != null) && !botContainerUi) || mcScreenCheck.isPaused())) {
            baritone.getInputOverrideHandler().clearAllKeys();
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }

        // Reactive void/fall & lagback clutch whenever airborne over dropoffs (except deliberate #bws recall)
        if (handleEmergencyClutch()) {
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }

        // Reactive legit AntiFireball punch deflection while bridging or rushing
        if (mode != TaskMode.FIREBALL_JUMP && tryAntiFireballDeflect()) {
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }

        // Yield interaction/aiming/pathing cleanly while SurvivalBehavior is eating Golden Apples or clutching
        if (baritone.getSurvivalBehavior() != null
                && baritone.getSurvivalBehavior().isUsingSurvivalReflex()
                && (mode == TaskMode.BUY || mode == TaskMode.STASH || mode == TaskMode.DEFEND
                    || mode == TaskMode.BLOCK_IN || mode == TaskMode.RUSH || mode == TaskMode.DIAMONDS
                    || mode == TaskMode.EMERALDS || mode == TaskMode.RETREAT)) {
            if (openMenu != me.inventoryMenu) {
                CraftingHelper.safeCloseContainer(ctx);
            }
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }

        // Manual #bws field modes (rush/diamonds/emeralds/retreat/defend): halt blind bridging when an
        // enemy confronts our bridge or enters close range, engage cleanly, then resume automatically.
        // Use isControlling() (NOT isActive()) so passive #brain record NEVER disables bridge self-defense!
        if (!baritone.getBrainProcess().isControlling()
                && (mode == TaskMode.RUSH || mode == TaskMode.DIAMONDS || mode == TaskMode.EMERALDS
                    || mode == TaskMode.RETREAT || mode == TaskMode.DEFEND)
                && tryEngageBridgeOrCloseThreat()) {
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }

        return switch (mode) {
            case RUSH -> tickRush(calcFailed);
            case DIAMONDS -> tickGenerator(GenType.DIAMOND, calcFailed);
            case EMERALDS -> tickGenerator(GenType.EMERALD, calcFailed);
            case HOME_GEN -> tickGenerator(GenType.HOME_FORGE, calcFailed);
            case STASH -> tickStash();
            case RECALL -> tickRecall();
            case BUY -> tickBuy(calcFailed);
            case DEFEND -> tickDefend();
            case BLOCK_IN -> tickBlockIn();
            case FIREBALL_JUMP -> tickFireballJump();
            case RETREAT -> tickRetreat();
            case NONE -> null;
        };
    }

    private PathingCommand tickRush(boolean calcFailed) {
        BedwarsArenaMemory memory = baritone.getBedwarsArenaMemory();
        BedwarsMapCore core = memory.getActiveCore();
        if (core == null) {
            ensureBedwarsActiveAndScanned();
            core = memory.getActiveCore();
            if (core == null) {
                return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
            }
        }
        LocalPlayer me = ctx.player();
        BedwarsMatchState ms = memory.getMatchState();
        long nowMs = System.currentTimeMillis();
        boolean modern = memory.getActiveRuleset() == BwsRuleset.MODERN_26_3;
        InventorySummary inv = memory.getTickContext().getInventorySummary(me, modern, ctx.world().getGameTime());

        // Abort rush cleanly on low HP with nearby enemy and no gapples, OR when home bed is under attack!
        if (!baritone.getBrainProcess().isControlling()) {
            BedPOI home = findHomeBed(core);
            boolean homeAlive = home != null && !home.destroyed();
            boolean baseThreat = homeAlive && (hasEnemyNearHomeBed(core, 26.0)
                    || (ms != null && ms.wasTrapTriggeredRecently(nowMs, 12_000L)));
            if (baseThreat) {
                double homeDistSq = home != null ? me.blockPosition().distSqr(new BlockPos(home.x(), home.y(), home.z())) : 0.0;
                if (homeDistSq > 26.0 * 26.0 && BedwarsDefensePlanner.isSafeForTacticalVoid(inv.iron(), inv.gold(), inv.diamonds(), inv.emeralds())) {
                    logDirect("Home bed under attack during rush — tactical recall!");
                    startRecall(false);
                } else {
                    logDirect("Home bed under attack during rush — retreating to defend!");
                    startRetreat();
                }
                return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
            }
            if (me.getHealth() <= 6.5f && inv.gapples() == 0 && hasNearbyEnemyWithin(14.0)) {
                logDirect("Low HP during rush — retreating home.");
                startRetreat();
                return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
            }
        }

        BedPOI targetBed = selectTargetBed();
        if (targetBed == null) {
            if (!memory.isSnapshotCapturedForMatch() || core.getBeds().isEmpty()) {
                return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
            }
            logDirect("All tracked enemy beds destroyed or out of scan! Stopping rush.");
            cancel("no_enemy_beds_left");
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }

        BlockPos bedHead = new BlockPos(targetBed.x(), targetBed.y(), targetBed.z());
        BlockState headState = ctx.world().getBlockState(bedHead);
        if (ctx.world().isLoaded(bedHead) && !(headState.getBlock() instanceof BedBlock)) {
            BlockPos bedFoot = new BlockPos(targetBed.footX(), targetBed.footY(), targetBed.footZ());
            if (!(ctx.world().getBlockState(bedFoot).getBlock() instanceof BedBlock)) {
                core.markBedDestroyedAt(bedHead.getX(), bedHead.getY(), bedHead.getZ());
                lockedTargetBedHeadKey = Long.MIN_VALUE;
                return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
            }
        }

        ToolProfile tools = inspectToolProfile(me);
        // If this bed is obsidian-encased and we lack a Diamond Pickaxe, abort attacking obsidian bare-handed!
        if (ctx.world().isLoaded(bedHead) && tools.pickaxeTier() < 4) {
            CachedBedDefenseProfile cached = memory.getOrScanBedDefense(core, targetBed, tools);
            if (cached != null && cached.hasObsidian() && cached.shellScan().estimatedClearTicks() >= 300.0) {
                // Only abort if we can't directly see an exposed face
                BedPOI altBed = selectAlternateNonObsidianBed(core, targetBed, tools);
                if (altBed != null) {
                    targetBed = altBed;
                    bedHead = new BlockPos(targetBed.x(), targetBed.y(), targetBed.z());
                    lockedTargetBedHeadKey = BlockPos.asLong(targetBed.x(), targetBed.y(), targetBed.z());
                } else if (!baritone.getBrainProcess().isControlling()) {
                    logDirect("Enemy bed has obsidian without Diamond Pickaxe — retreating to upgrade tools.");
                    startRetreat();
                    return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
                }
            }
        }

        BreakVectorDecision decision = evaluateBestBedBreakVector(targetBed, tools);

        if (decision.bestVector() == null) {
            return new PathingCommand(new GoalNear(bedHead, 3), PathingCommandType.SET_GOAL_AND_PATH);
        }

        BreakVectorCandidate best = decision.bestVector();
        BlockPos standPos = new BlockPos(best.standX(), best.standY(), best.standZ());
        double reach = ctx.playerController().getBlockReachDistance();
        double reachSq = reach * reach;

        BedwarsMapCore rushCore = baritone.getBedwarsArenaMemory().getActiveCore();
        long now = ctx.world().getGameTime();
        Vec3 eye = ctx.playerHead();
        BlockPos firstBlocking = null;
        if (eye.distanceToSqr(Vec3.atCenterOf(bedHead)) <= (reach + 3.0) * (reach + 3.0)) {
            RayTargetResult rayTarget = BedwarsDefensePlanner.findWeakestRaycastTarget(
                    eye.x, eye.y, eye.z,
                    bedHead.getX(), bedHead.getY(), bedHead.getZ(),
                    targetBed.footX(), targetBed.footY(), targetBed.footZ(),
                    reach,
                    tools,
                    (x, y, z) -> classifyDefenseAt(rushCore, x, y, z)
            );
            if (rayTarget != null) {
                BlockPos rp = new BlockPos(rayTarget.mineX(), rayTarget.mineY(), rayTarget.mineZ());
                Long cd = recentlyBrokenBlocks.get(rp.asLong());
                if ((cd == null || now >= cd) && !ctx.world().getBlockState(rp).canBeReplaced()) {
                    firstBlocking = rp;
                }
            }
        }
        if (firstBlocking == null) {
            firstBlocking = findFirstSolidAlongBreakVector(targetBed, best, now);
        }

        // Close-threat tactical smart block-in when digging into a defended bed while an enemy approaches
        if (firstBlocking != null
                && !(ctx.world().getBlockState(firstBlocking).getBlock() instanceof BedBlock)
                && ctx.playerFeet().distSqr(standPos) <= 6.25
                && hasNearbyEnemyWithin(9.0)) {
            if (tryPlaceNextBlockInStep(bedHead, targetBed)) {
                return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
            }
        }

        if (firstBlocking != null) {
            double[] hitArr = BedwarsDefensePlanner.closestPointOnUnitBox(
                    eye.x, eye.y, eye.z,
                    firstBlocking.getX(), firstBlocking.getY(), firstBlocking.getZ(),
                    0.15
            );
            Vec3 hitPt = new Vec3(hitArr[0], hitArr[1], hitArr[2]);
            if (eye.distanceToSqr(hitPt) <= reachSq) {
                baritone.getInputOverrideHandler().clearMovementKeys();
                BlockState targetState = ctx.world().getBlockState(firstBlocking);
                MovementHelper.switchToBestToolFor(ctx, targetState);
                Rotation aim = RotationUtils.calcRotationFromVec3d(eye, hitPt, ctx.playerRotations());
                baritone.getLookBehavior().updateTarget(aim, true, true);
                if (ctx.isLookingAt(firstBlocking) || MouseModel.angularDistance(ctx.playerRotations(), aim) <= 9.0f) {
                    lastMiningPos = firstBlocking;
                    baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_LEFT, true);
                }
                return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
            }
        }

        Goal goal = calcFailed ? new GoalNear(bedHead, 2) : new GoalBlock(standPos);
        return new PathingCommand(goal, PathingCommandType.SET_GOAL_AND_PATH);
    }

    public static int resolveStandBlockY(int genX, int genY, int genZ, boolean atGenReplaceable, boolean belowGenReplaceable) {
        if (!atGenReplaceable) {
            return genY + 1;
        }
        if (!belowGenReplaceable) {
            return genY;
        }
        return genY - 1;
    }

    private BlockPos resolveGeneratorStandPos(GenPOI chosen) {
        if (chosen == null || ctx.world() == null) {
            return null;
        }
        BlockPos genPos = new BlockPos(chosen.x(), chosen.y(), chosen.z());
        boolean atReplaceable = ctx.world().getBlockState(genPos).canBeReplaced();
        boolean belowReplaceable = ctx.world().getBlockState(genPos.below()).canBeReplaced();
        int standY = resolveStandBlockY(chosen.x(), chosen.y(), chosen.z(), atReplaceable, belowReplaceable);
        return new BlockPos(chosen.x(), standY, chosen.z());
    }

    private BlockPos findLiveGeneratorDropTarget(GenPOI chosen, LocalPlayer player, GenType type) {
        if (chosen == null || player == null || ctx.world() == null) {
            return null;
        }
        double gx = chosen.x() + 0.5;
        double gy = chosen.y() + 0.5;
        double gz = chosen.z() + 0.5;
        Entity bestDrop = null;
        double bestScore = Double.POSITIVE_INFINITY;
        for (Entity e : ctx.entities()) {
            if (!(e instanceof ItemEntity ie) || !e.isAlive()) {
                continue;
            }
            ItemStack st = ie.getItem();
            if (st.isEmpty()) {
                continue;
            }
            boolean relevant = switch (type) {
                case HOME_FORGE -> st.is(Items.IRON_INGOT) || st.is(Items.GOLD_INGOT)
                        || st.is(Items.DIAMOND) || st.is(Items.EMERALD);
                case DIAMOND -> st.is(Items.DIAMOND);
                case EMERALD -> st.is(Items.EMERALD);
            };
            if (!relevant) {
                continue;
            }
            double distToGenSq = ie.distanceToSqr(gx, gy, gz);
            if (distToGenSq > 49.0 || Math.abs(ie.getY() - gy) > 3.5) {
                continue;
            }
            double dMe = player.distanceToSqr(ie);
            if (dMe < bestScore) {
                bestScore = dMe;
                bestDrop = ie;
            }
        }
        if (bestDrop == null) {
            return null;
        }
        double dx = bestDrop.getX() - player.getX();
        double dz = bestDrop.getZ() - player.getZ();
        if (dx * dx + dz * dz <= 0.20 && Math.abs(bestDrop.getY() - player.getY()) <= 1.25) {
            return null;
        }
        return BlockPos.containing(bestDrop.position());
    }

    /**
     * Pure scoring helper for generator selection: strongly prioritizes hologram-verified generators
     * over raw decorative diamond/emerald blocks (e.g. statues/roofs on Skycrypt/Rooftop) and
     * penalizes large Y-plane mismatches so pathing targets the actual playable generator.
     */
    public static double scoreGeneratorCandidate(
            double distBlocks,
            boolean hologramVerified,
            int genY,
            int refY
    ) {
        double score = distBlocks;
        if (!hologramVerified) {
            score += 45.0;
        }
        int absDy = Math.abs(genY - refY);
        if (absDy > 4) {
            score += (absDy - 4) * 6.0;
        }
        return score;
    }

    private static boolean isHologramVerifiedGen(GenPOI g) {
        if (g == null || g.label() == null || g.label().isBlank()) {
            return false;
        }
        String l = g.label().trim().toLowerCase(Locale.ROOT);
        return !l.equals("diamond block") && !l.equals("emerald block") && !l.equals("forge");
    }

    /**
     * Resolves a walkable floor block beneath a floating generator hologram ({@link ArmorStand} or
     * {@link net.minecraft.world.entity.Display.TextDisplay}) so pathing walks ONTO the generator
     * instead of trying to bridge midair into the generator no-place zone!
     */
    private BlockPos resolveFloorUnderHologram(double hx, double hy, double hz) {
        if (ctx.world() == null) {
            return null;
        }
        int bx = (int) Math.floor(hx);
        int bz = (int) Math.floor(hz);
        int startY = (int) Math.floor(hy);
        BlockPos.MutableBlockPos mp = baritone.getBedwarsArenaMemory().getTickContext().scratchPos();
        for (int dy = 0; dy <= 5; dy++) {
            int y = startY - dy;
            BlockState st = ctx.world().getBlockState(mp.set(bx, y, bz));
            if (!st.canBeReplaced()) {
                // Stand directly ON TOP of this solid block (diamond/emerald block or floor slab)
                return new BlockPos(bx, y + 1, bz);
            }
        }
        return new BlockPos(bx, Math.max(ctx.world().getMinY() + 1, startY - 2), bz);
    }

    private BlockPos findNearbyLiveHologramOrDropGen(GenType type, LocalPlayer player, double maxRadius) {
        if (player == null || ctx.world() == null || type == GenType.HOME_FORGE) {
            return null;
        }
        String keyword = type == GenType.DIAMOND ? "diamond" : "emerald";
        Item dropItem = type == GenType.DIAMOND ? Items.DIAMOND : Items.EMERALD;
        double maxSq = maxRadius * maxRadius;
        BedwarsTickContext tc = baritone.getBedwarsArenaMemory().getTickContext();

        BlockPos bestDropPos = null;
        double bestDropDistSq = Double.POSITIVE_INFINITY;
        List<ItemEntity> drops = tc.currencyDrops();
        for (int i = 0, n = drops.size(); i < n; i++) {
            ItemEntity ie = drops.get(i);
            if (!ie.getItem().is(dropItem)) {
                continue;
            }
            double dSq = player.distanceToSqr(ie);
            if (dSq <= maxSq && dSq < bestDropDistSq) {
                bestDropDistSq = dSq;
                bestDropPos = BlockPos.containing(ie.position());
            }
        }
        if (bestDropPos != null) {
            return bestDropPos;
        }

        // Check BOTH ArmorStand and Display.TextDisplay holograms via BedwarsTickContext!
        BlockPos bestHolo = null;
        double bestHoloDistSq = Double.POSITIVE_INFINITY;
        List<HologramInfo> holos = tc.holograms();
        for (int i = 0, n = holos.size(); i < n; i++) {
            HologramInfo h = holos.get(i);
            String clean = h.lowerText();
            if (!clean.contains(keyword)) {
                continue;
            }
            if (!(clean.contains("tier") || clean.contains("spawn") || clean.contains("second") || clean.contains("gen") || clean.contains("drop"))) {
                continue;
            }
            double dx = h.x() - player.getX();
            double dy = h.y() - player.getY();
            double dz = h.z() - player.getZ();
            double dSq = dx * dx + dy * dy + dz * dz;
            if (dSq <= maxSq && dSq < bestHoloDistSq) {
                bestHoloDistSq = dSq;
                bestHolo = resolveFloorUnderHologram(h.x(), h.y(), h.z());
            }
        }
        return bestHolo;
    }

    private boolean hasNearbyGeneratorHologram(BlockPos stand, GenType type) {
        if (stand == null || ctx.world() == null || type == GenType.HOME_FORGE) {
            return false;
        }
        String keyword = type == GenType.DIAMOND ? "diamond" : "emerald";
        double sx = stand.getX() + 0.5;
        double sz = stand.getZ() + 0.5;
        List<HologramInfo> holos = baritone.getBedwarsArenaMemory().getTickContext().holograms();
        for (int i = 0, n = holos.size(); i < n; i++) {
            HologramInfo h = holos.get(i);
            double dx = h.x() - sx;
            double dz = h.z() - sz;
            if (dx * dx + dz * dz <= 25.0 && Math.abs(h.y() - stand.getY()) <= 6.0) {
                String clean = h.lowerText();
                if (clean.contains(keyword) || clean.contains("tier") || clean.contains("spawn") || clean.contains("second")) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean isRealGeneratorBlockUnder(BlockPos stand, GenType type) {
        if (stand == null || ctx.world() == null) {
            return false;
        }
        Block expected = type == GenType.DIAMOND ? Blocks.DIAMOND_BLOCK
                : (type == GenType.EMERALD ? Blocks.EMERALD_BLOCK : null);
        if (expected == null) {
            return false;
        }
        BlockPos.MutableBlockPos mp = baritone.getBedwarsArenaMemory().getTickContext().scratchPos();
        for (int dy = 0; dy >= -2; dy--) {
            if (ctx.world().getBlockState(mp.set(stand.getX(), stand.getY() + dy, stand.getZ())).getBlock() == expected) {
                return true;
            }
        }
        return false;
    }

    private BlockPos findAnyHomeIslandIngotDrop(BedPOI homeBed, LocalPlayer player) {
        if (player == null || ctx.world() == null) {
            return null;
        }
        double anchorX = homeBed != null ? homeBed.x() + 0.5 : player.getX();
        double anchorY = homeBed != null ? homeBed.y() + 0.5 : player.getY();
        double anchorZ = homeBed != null ? homeBed.z() + 0.5 : player.getZ();
        Entity bestDrop = null;
        double bestDistSq = Double.POSITIVE_INFINITY;
        List<ItemEntity> drops = baritone.getBedwarsArenaMemory().getTickContext().currencyDrops();
        for (int i = 0, n = drops.size(); i < n; i++) {
            ItemEntity ie = drops.get(i);
            ItemStack st = ie.getItem();
            if (!st.is(Items.IRON_INGOT) && !st.is(Items.GOLD_INGOT)) {
                continue;
            }
            double dx = ie.getX() - anchorX;
            double dz = ie.getZ() - anchorZ;
            if (dx * dx + dz * dz > 324.0 || Math.abs(ie.getY() - anchorY) > 3.5) {
                continue;
            }
            double dMe = player.distanceToSqr(ie);
            if (dMe < bestDistSq) {
                bestDistSq = dMe;
                bestDrop = ie;
            }
        }
        return bestDrop != null ? BlockPos.containing(bestDrop.position()) : null;
    }

    private PathingCommand tickGenerator(GenType type, boolean calcFailed) {
        BedwarsArenaMemory memory = baritone.getBedwarsArenaMemory();
        BedwarsMapCore core = memory.getActiveCore();
        if (core == null) {
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }
        LocalPlayer p = ctx.player();
        BedPOI homeBed = findHomeBed(core);
        int refY = homeBed != null ? homeBed.y() : p.blockPosition().getY();
        BedwarsMatchState ms = memory.getMatchState();
        long nowMs = System.currentTimeMillis();

        // Never tunnel-vision on Diamond/Emerald gen while an enemy heads for our living home bed!
        if ((type == GenType.DIAMOND || type == GenType.EMERALD) && !baritone.getBrainProcess().isControlling()) {
            boolean homeAlive = homeBed != null && !homeBed.destroyed();
            if (homeAlive && (hasEnemyNearHomeBed(core, 28.0) || (ms != null && ms.wasTrapTriggeredRecently(nowMs, 12_000L)))) {
                logDirect("Enemy approaching home bed — aborting " + type.name() + " gen run to defend!");
                startRetreat();
                return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
            }
        }

        if (calcFailed && lockedGenType == type) {
            lockedGenStand = null;
        }

        // For HOME_FORGE, prioritize real dropped iron/gold ingots on the home island floor
        if (type == GenType.HOME_FORGE) {
            BlockPos liveIslandIngot = findAnyHomeIslandIngotDrop(homeBed, p);
            if (liveIslandIngot != null) {
                memory.recordLearnedForgeAndSave(
                        liveIslandIngot.getX(), liveIslandIngot.getY(), liveIslandIngot.getZ());
                lockedGenType = GenType.HOME_FORGE;
                lockedGenStand = liveIslandIngot;
                homeGenWaitTicks = 0;
                double dx = (liveIslandIngot.getX() + 0.5) - p.getX();
                double dz = (liveIslandIngot.getZ() + 0.5) - p.getZ();
                if (dx * dx + dz * dz <= 0.25 && Math.abs(p.getY() - liveIslandIngot.getY()) <= 1.25) {
                    baritone.getInputOverrideHandler().clearMovementKeys();
                    return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
                }
                return new PathingCommand(new GoalBlock(liveIslandIngot), PathingCommandType.SET_GOAL_AND_PATH);
            }
        }

        // For DIAMOND/EMERALD, check live ground drops FIRST (walk right onto the item to pick it up!)
        if (type == GenType.DIAMOND || type == GenType.EMERALD) {
            Item dropItem = type == GenType.DIAMOND ? Items.DIAMOND : Items.EMERALD;
            List<ItemEntity> drops = memory.getTickContext().currencyDrops();
            ItemEntity nearestDrop = null;
            double bestDropDSq = 24.0 * 24.0;
            for (int i = 0, n = drops.size(); i < n; i++) {
                ItemEntity ie = drops.get(i);
                if (!ie.getItem().is(dropItem)) {
                    continue;
                }
                double dSq = p.distanceToSqr(ie);
                if (dSq < bestDropDSq) {
                    bestDropDSq = dSq;
                    nearestDrop = ie;
                }
            }
            if (nearestDrop != null) {
                BlockPos dropPos = BlockPos.containing(nearestDrop.position());
                homeGenWaitTicks = 0;
                double dx = nearestDrop.getX() - p.getX();
                double dz = nearestDrop.getZ() - p.getZ();
                if (dx * dx + dz * dz <= 0.30 && Math.abs(p.getY() - nearestDrop.getY()) <= 1.35) {
                    baritone.getInputOverrideHandler().clearMovementKeys();
                    return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
                }
                // Walk directly onto the dropped diamond/emerald (tight pickup range, no standing 1.2m away!)
                return new PathingCommand(new GoalBlock(dropPos), PathingCommandType.SET_GOAL_AND_PATH);
            }

            // Respect locked generator hysteresis before snapping to a different hologram gen!
            BlockPos liveHoloFloor = findNearbyLiveHologramOrDropGen(type, p, 24.0);
            if (liveHoloFloor != null) {
                boolean keepLocked = lockedGenType == type && lockedGenStand != null
                        && lockedGenStand.distSqr(liveHoloFloor) <= 36.0
                        && (hasNearbyGeneratorHologram(lockedGenStand, type) || isRealGeneratorBlockUnder(lockedGenStand, type));
                if (!keepLocked) {
                    lockedGenType = type;
                    lockedGenStand = liveHoloFloor;
                }
                lastGenHologramSeenTick = ctx.world().getGameTime();
            }
        }

        GenPOI chosen = null;
        BlockPos chosenStand = null;
        double bestScore = Double.POSITIVE_INFINITY;
        GenPOI lockedPoi = null;
        double lockedScore = Double.POSITIVE_INFINITY;
        GenPOI[] gens = core.getGensSnapshot();
        for (int gi = 0; gi < gens.length; gi++) {
            GenPOI g = gens[gi];
            if (g.type() != type) {
                continue;
            }
            BlockPos candidateStand = resolveGeneratorStandPos(g);
            BlockPos effectiveStand = candidateStand != null ? candidateStand : new BlockPos(g.x(), g.y(), g.z());
            if (type == GenType.HOME_FORGE && fakeHomeForgePositions.contains(effectiveStand.asLong())) {
                continue;
            }
            if (type != GenType.HOME_FORGE && fakeHomeForgePositions.contains(effectiveStand.asLong())
                    && !hasNearbyGeneratorHologram(effectiveStand, type)) {
                continue;
            }
            if (type == GenType.HOME_FORGE && !BedwarsArenaMemory.isFloorLevelForgeBlock(g.y(), refY)) {
                continue;
            }
            double dist = Math.sqrt(p.blockPosition().distSqr(effectiveStand));
            double score;
            if (type == GenType.HOME_FORGE && homeBed != null) {
                double horiz = Math.sqrt((g.x() - homeBed.x()) * (g.x() - homeBed.x()) + (g.z() - homeBed.z()) * (g.z() - homeBed.z()));
                String lbl = g.label() != null ? g.label().toLowerCase(Locale.ROOT) : "";
                boolean verifiedForge = lbl.contains("ingot") || lbl.contains("learned") || lbl.contains("spawn");
                score = horiz + Math.abs(effectiveStand.getY() - refY) * 14.0 - (verifiedForge ? 45.0 : 0.0);
            } else {
                boolean holoVerified = isHologramVerifiedGen(g) || hasNearbyGeneratorHologram(effectiveStand, type);
                score = scoreGeneratorCandidate(dist, holoVerified, effectiveStand.getY(), refY);
                // Prefer generator with shorter hologram countdown timer when known!
                if (ms != null) {
                    double spawnRem = ms.getClosestGenSpawnRemainingSec(type, effectiveStand.getX(), effectiveStand.getZ(), nowMs);
                    if (spawnRem >= 0.0) {
                        score += spawnRem * 0.45;
                    }
                }
            }
            if (lockedGenType == type && lockedGenStand != null && effectiveStand.distSqr(lockedGenStand) <= 9.0) {
                lockedPoi = g;
                lockedScore = score;
            }
            if (score < bestScore) {
                bestScore = score;
                chosen = g;
                chosenStand = effectiveStand;
            }
        }
        // Keep currently locked generator unless another candidate is meaningfully better (hysteresis!)
        if (lockedPoi != null && chosen != null && bestScore + 10.0 >= lockedScore) {
            chosen = lockedPoi;
            chosenStand = lockedGenStand;
        } else if (lockedGenType == type && lockedGenStand != null && chosen == null) {
            chosenStand = lockedGenStand;
        }
        if (chosen == null && chosenStand == null) {
            if (type == GenType.HOME_FORGE) {
                BlockPos homeAnchor = findHomeAnchorPos(core);
                if (homeAnchor != null) {
                    return new PathingCommand(new GoalNear(homeAnchor, 3), PathingCommandType.SET_GOAL_AND_PATH);
                }
            }
            logDirect("No " + type.name() + " generator indexed yet; run '#bws scan'.");
            cancel("no_generator_indexed");
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }
        lockedGenType = type;
        lockedGenStand = chosenStand != null ? chosenStand : new BlockPos(chosen.x(), chosen.y(), chosen.z());

        if (chosen != null) {
            BlockPos liveDropTarget = findLiveGeneratorDropTarget(chosen, p, type);
            if (type == GenType.HOME_FORGE && liveDropTarget != null) {
                memory.recordLearnedForgeAndSave(chosen.x(), chosen.y(), chosen.z());
            }
            if (liveDropTarget != null) {
                homeGenWaitTicks = 0;
                return new PathingCommand(new GoalBlock(liveDropTarget), PathingCommandType.SET_GOAL_AND_PATH);
            }
        }
        BlockPos stand = lockedGenStand;
        double dx = (stand.getX() + 0.5) - p.getX();
        double dz = (stand.getZ() + 0.5) - p.getZ();
        double horizSq = dx * dx + dz * dz;
        double dy = Math.abs(p.getY() - stand.getY());
        // Stand tightly on the generator (0.45 for forge, 0.72 for diamond/emerald so pickups never miss!)
        if (horizSq <= (type == GenType.HOME_FORGE ? 0.45 : 0.72) && dy <= 1.25) {
            int resNow = switch (type) {
                case HOME_FORGE -> countItem(p, Items.IRON_INGOT) + countItem(p, Items.GOLD_INGOT);
                case DIAMOND -> countItem(p, Items.DIAMOND);
                case EMERALD -> countItem(p, Items.EMERALD);
            };
            if (homeGenWaitTicks == 0 || resNow > homeGenWaitStartResources) {
                homeGenWaitStartResources = resNow;
                homeGenWaitTicks = 1;
            } else {
                homeGenWaitTicks++;
                boolean hasHolo = hasNearbyGeneratorHologram(stand, type);
                boolean hasRealBlock = isRealGeneratorBlockUnder(stand, type);
                if (type == GenType.HOME_FORGE && homeGenWaitTicks > 140) {
                    fakeHomeForgePositions.add(stand.asLong());
                    lockedGenStand = null;
                    homeGenWaitTicks = 0;
                    homeGenWaitStartResources = -1;
                    logDirect("Blacklisted non-producing decorative HOME_FORGE block at " + stand.toShortString());
                    return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
                }
                // NEVER blacklist real Tier I diamond/emerald gens after 4.5s (Tier I takes 30s/60s)!
                // Only blacklist after 650 ticks (32.5s) if there is NO hologram AND NO real diamond/emerald block!
                if (type != GenType.HOME_FORGE && !hasHolo && !hasRealBlock && homeGenWaitTicks > 650) {
                    fakeHomeForgePositions.add(stand.asLong());
                    lockedGenStand = null;
                    homeGenWaitTicks = 0;
                    homeGenWaitStartResources = -1;
                    logDirect("Blacklisted non-producing decorative " + type.name() + " block at " + stand.toShortString());
                    return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
                }
            }
            baritone.getInputOverrideHandler().clearMovementKeys();
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }
        homeGenWaitTicks = 0;
        // Walk directly onto the generator floor block (avoid GoalNear(1) stopping 1.2m out of pickup range!)
        Goal genGoal = !calcFailed ? new GoalBlock(stand) : new GoalNear(stand, 1);
        return new PathingCommand(genGoal, PathingCommandType.SET_GOAL_AND_PATH);
    }

    private PathingCommand tickStash() {
        LocalPlayer p = ctx.player();
        AbstractContainerMenu menu = p.containerMenu;
        if (p.hurtTime > 0 || hasNearbyEnemyWithin(9.5)) {
            if (menu != p.inventoryMenu) {
                CraftingHelper.safeCloseContainer(ctx);
            }
            if (!baritone.getBrainProcess().isControlling()) {
                logDirect("Intruder nearby — aborting Bedwars chest stash for combat.");
                cancel("stash_intruder_nearby");
            }
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }
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
            if (ctx.world() != null) {
                stashMissingCooldownUntilTick = ctx.world().getGameTime() + 200L;
            }
            logDirect("No nearby Ender Chest or Team Chest found to stash resources.");
            cancel();
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }
        final BlockPos targetChest = chestPos;
        if (ctx.playerFeet().distSqr(targetChest) > 8.2) {
            return new PathingCommand(new GoalNear(targetChest, 1), PathingCommandType.SET_GOAL_AND_PATH);
        }
        baritone.getInputOverrideHandler().clearMovementKeys();
        Rotation rot = RotationUtils.reachable(ctx, targetChest)
                .orElseGet(() -> RotationUtils.calcRotationFromVec3d(ctx.playerHead(), Vec3.atCenterOf(targetChest), ctx.playerRotations()));
        baritone.getLookBehavior().updateTarget(rot, true, true);
        if (actionCooldown == 0 && (ctx.isLookingAt(targetChest) || MouseModel.angularDistance(ctx.playerRotations(), rot) <= 6.0f)) {
            baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_RIGHT, true);
            actionCooldown = 12;
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

    private boolean handleEmergencyClutch() {
        return stepReactiveVoidClutch(true);
    }

    /**
     * Shared reactive void/lagback clutch handler used by both {@link BedwarsProcess} and
     * {@link CombatProcess} with unified attempt/fall-height bounds, horizontal steering onto
     * the placement column, and post-rotation crosshair ray verification.
     */
    public boolean stepReactiveVoidClutch(boolean allowLagbackRecovery) {
        LocalPlayer p = ctx.player();
        if (p == null || ctx.world() == null) {
            voidClutchState.reset();
            return false;
        }
        BedwarsArenaMemory memory = baritone.getBedwarsArenaMemory();
        long now = ctx.world().getGameTime();
        boolean inLagback = allowLagbackRecovery && memory.isInLagbackCooldown();
        if (inLagback) {
            // Release forward momentum and S-tap briefly after server teleport/lagback
            baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_FORWARD, false);
            baritone.getInputOverrideHandler().setInputForceState(Input.SPRINT, false);
            baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_BACK, true);
            baritone.getInputOverrideHandler().setInputForceState(Input.SNEAK, true);
        }

        if (p.onGround()) {
            voidClutchState.reset();
            return inLagback;
        }
        if (mode == TaskMode.RECALL) {
            voidClutchState.reset();
            return inLagback;
        }

        BlockStateInterface bsi = new BlockStateInterface(ctx);
        int px = ctx.playerFeet().x;
        int py = ctx.playerFeet().y;
        int pz = ctx.playerFeet().z;
        boolean floorWithinTwo = false;
        double voidClearance = 22.0;
        for (int dy = 1; dy <= 20; dy++) {
            if (MovementHelper.canWalkOn(bsi, px, py - dy, pz)) {
                voidClearance = dy - 1.0;
                if (dy <= 2) {
                    floorWithinTwo = true;
                }
                break;
            }
        }

        Vec3 pos = p.position();
        Vec3 vel = p.getDeltaMovement();
        boolean predictedVoid = vel.y <= 0.05
                && p.fallDistance < 20.0
                && BedwarsTacticalHelper.predictsVoidFall(
                        pos.x, pos.y, pos.z, vel.x, vel.y, vel.z,
                        (x, y, z) -> MovementHelper.canWalkOn(bsi, x, y, z) && !bsi.get0(x, y, z).canBeReplaced(),
                        14.0);
        boolean voidClutch = predictedVoid || voidClutchState.chainTicks > 0 || BedwarsTacticalHelper.shouldTriggerVoidClutch(
                p.onGround(),
                p.isFallFlying(),
                p.isInWater(),
                (float) p.fallDistance,
                vel.y,
                voidClearance,
                floorWithinTwo,
                false
        );
        if (!voidClutch && (!inLagback || vel.y >= -0.18)) {
            return inLagback;
        }

        if (voidClutchState.startY == Integer.MIN_VALUE) {
            voidClutchState.startY = py;
        }
        if (BedwarsTacticalHelper.isClutchCapExceeded(
                voidClutchState.startY,
                py,
                voidClutchState.airAttempts,
                BedwarsTacticalHelper.MAX_CLUTCH_FALL_BLOCKS,
                BedwarsTacticalHelper.MAX_CLUTCH_AIR_ATTEMPTS)) {
            voidClutchState.chainTicks = 0;
            return inLagback;
        }

        if (!baritone.getInventoryBehavior().autoSwapHotbarBridgeBlock(0)) {
            return inLagback;
        }
        List<ClutchCandidate> candidates = BedwarsTacticalHelper.rankClutchPlacements(
                pos.x, pos.y, pos.z,
                vel.x, vel.y, vel.z,
                (x, y, z) -> MovementHelper.canWalkOn(bsi, x, y, z) && !bsi.get0(x, y, z).canBeReplaced(),
                4.4
        );
        if (candidates.isEmpty()) {
            if (inLagback && (now - voidClutchState.lastAttemptTick) >= 2L) {
                BlockHitResult sideHit = findClutchPlacementHit(p.blockPosition());
                if (sideHit != null) {
                    Rotation clutchRot = RotationUtils.calcRotationFromVec3d(ctx.playerHead(), sideHit.getLocation(), ctx.playerRotations());
                    baritone.getLookBehavior().setUrgentClutchMode(true);
                    baritone.getLookBehavior().updateTarget(clutchRot, true, true);
                    ((baritone.behavior.LookBehavior) baritone.getLookBehavior()).applyClientPreTickRotationAndRepick();
                    HitResult liveOver = ctx.objectMouseOver();
                    if (liveOver instanceof BlockHitResult bhr
                            && bhr.getBlockPos().equals(sideHit.getBlockPos())
                            && MouseModel.angularDistance(ctx.playerRotations(), clutchRot) <= 18.0f) {
                        baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_RIGHT, true);
                        voidClutchState.lastAttemptTick = now;
                        voidClutchState.airAttempts++;
                    }
                }
            }
            return inLagback;
        }

        ClutchCandidate best = candidates.get(0);
        Vec3 hitVec = new Vec3(
                best.againstX() + 0.5 + best.faceDx() * 0.5,
                best.againstY() + 0.5 + best.faceDy() * 0.5,
                best.againstZ() + 0.5 + best.faceDz() * 0.5
        );
        Rotation clutchRot = RotationUtils.calcRotationFromVec3d(ctx.playerHead(), hitVec, ctx.playerRotations());
        baritone.getLookBehavior().setUrgentClutchMode(true);
        baritone.getLookBehavior().updateTarget(clutchRot, true, true);
        ((baritone.behavior.LookBehavior) baritone.getLookBehavior()).applyClientPreTickRotationAndRepick();
        if (!inLagback) {
            baritone.getInputOverrideHandler().clearAllKeys();
            double dxCell = (best.placeX() + 0.5) - pos.x;
            double dzCell = (best.placeZ() + 0.5) - pos.z;
            if (Math.hypot(dxCell, dzCell) > 0.20) {
                baritone.getInputOverrideHandler().setDesiredMovementYaw(
                        BedwarsTacticalHelper.computeClutchSteerYaw(pos.x, pos.z, best));
                baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_FORWARD, true);
            }
            // Never hold SNEAK while airborne during void clutch: sneaking cuts air acceleration by 70%,
            // preventing us from steering laterally back onto the clutch blocks we just placed!
            baritone.getInputOverrideHandler().setInputForceState(Input.SNEAK, false);
        }
        HitResult liveOver = ctx.objectMouseOver();
        boolean validClutchRay = liveOver instanceof BlockHitResult bhr
                && BedwarsTacticalHelper.isClutchHitResultValid(
                        bhr.getBlockPos().getX(),
                        bhr.getBlockPos().getY(),
                        bhr.getBlockPos().getZ(),
                        bhr.getDirection().getStepX(),
                        bhr.getDirection().getStepY(),
                        bhr.getDirection().getStepZ(),
                        best);
        long minClutchGap = vel.y < -0.45 ? 1L : 2L;
        if (validClutchRay
                && (now - voidClutchState.lastAttemptTick) >= minClutchGap
                && MouseModel.angularDistance(ctx.playerRotations(), clutchRot) <= 15.0f) {
            baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_RIGHT, true);
            voidClutchState.lastAttemptTick = now;
            voidClutchState.airAttempts++;
            voidClutchState.chainTicks = voidClutchState.chainTicks > 0
                    ? Math.max(0, voidClutchState.chainTicks - 1)
                    : 12;
            if (baritone.getBedwarsBlackboxRecorder() != null) {
                baritone.getBedwarsBlackboxRecorder().recordEvent("AUTOCLUTCH",
                        "cell=" + best.placeX() + "," + best.placeY() + "," + best.placeZ()
                                + ",attempts=" + voidClutchState.airAttempts);
            }
        } else if (voidClutchState.chainTicks > 0) {
            voidClutchState.chainTicks--;
        }
        return true;
    }

    private boolean tryAntiFireballDeflect() {
        LocalPlayer me = ctx.player();
        if (me == null || ctx.world() == null) {
            return false;
        }
        long now = ctx.world().getGameTime();
        if (now - lastFireballPunchTick < 2L) {
            return false;
        }
        double bestEnemyDist = Double.MAX_VALUE;
        boolean hasEnemy = false;
        double ex = 0.0, ey = 0.0, ez = 0.0;
        BedwarsTickContext tc = baritone.getBedwarsArenaMemory().getTickContext();
        List<Player> enemies = tc.enemies();
        for (int i = 0, n = enemies.size(); i < n; i++) {
            Player pl = enemies.get(i);
            double d = me.distanceToSqr(pl);
            if (d < bestEnemyDist) {
                bestEnemyDist = d;
                ex = pl.getX();
                ey = pl.getEyeY();
                ez = pl.getZ();
                hasEnemy = true;
            }
        }
        FireballThreat bestThreat = null;
        Entity bestEntity = null;
        Vec3 eye = ctx.playerHead();
        Vec3 pos = me.position();
        List<Entity> fireballs = tc.fireballs();
        for (int i = 0, n = fireballs.size(); i < n; i++) {
            Entity e = fireballs.get(i);
            Vec3 fv = e.getDeltaMovement();
            FireballThreat threat = BedwarsTacticalHelper.evaluateIncomingFireball(
                    e.getX(), e.getY(), e.getZ(),
                    fv.x, fv.y, fv.z,
                    pos.x, eye.y, pos.z,
                    ex, ey, ez, hasEnemy
            );
            if (threat != null && (bestThreat == null || threat.dist() < bestThreat.dist())) {
                bestThreat = threat;
                bestEntity = e;
            }
        }
        if (bestThreat == null || bestEntity == null) {
            return false;
        }
        Rotation aim = new Rotation(bestThreat.punchYaw(), bestThreat.punchPitch());
        baritone.getLookBehavior().updateTarget(aim, false, true);
        if (CombatProcess.clickAttackIfCrosshairMatches(ctx, bestEntity, 4.35 * 4.35)) {
            lastFireballPunchTick = now;
            if (baritone.getBedwarsBlackboxRecorder() != null) {
                baritone.getBedwarsBlackboxRecorder().recordEvent("ANTI_FIREBALL",
                        "dist=" + String.format("%.2f", bestThreat.dist()) + ",mode=" + bestThreat.aimMode());
            }
        }
        return true;
    }

    private DefenseBlockKind classifyDefenseAt(BedwarsMapCore core, int x, int y, int z) {
        if (core != null && core.isProtectedMapOrHomeBed(x, y, z)) {
            return DefenseBlockKind.ORIGINAL_MAP;
        }
        return BedwarsArenaMemory.classifyDefenseBlock(ctx.world().getBlockState(new BlockPos(x, y, z)).getBlock());
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

    private BedPOI selectAlternateNonObsidianBed(BedwarsMapCore core, BedPOI skipBed, ToolProfile tools) {
        if (core == null || ctx.player() == null) {
            return null;
        }
        BlockPos me = ctx.playerFeet();
        BedPOI best = null;
        double bestDSq = Double.POSITIVE_INFINITY;
        BedPOI[] beds = core.getBedsSnapshot();
        for (int i = 0; i < beds.length; i++) {
            BedPOI b = beds[i];
            if (b.isHome() || b.destroyed() || b == skipBed) {
                continue;
            }
            CachedBedDefenseProfile prof = baritone.getBedwarsArenaMemory().getOrScanBedDefense(core, b, tools);
            if (prof != null && prof.hasObsidian()) {
                continue;
            }
            double dx = b.x() - me.getX();
            double dy = b.y() - me.getY();
            double dz = b.z() - me.getZ();
            double dSq = dx * dx + dy * dy + dz * dz;
            if (dSq < bestDSq) {
                bestDSq = dSq;
                best = b;
            }
        }
        return best;
    }

    private BedPOI selectTargetBed() {
        BedwarsArenaMemory mem = baritone.getBedwarsArenaMemory();
        BedwarsMapCore core = mem.getActiveCore();
        if (core == null || ctx.player() == null) {
            return null;
        }
        BedwarsMatchState ms = mem.getMatchState();
        long nowMs = System.currentTimeMillis();
        BedPOI best = null;
        double bestScore = Double.POSITIVE_INFINITY;
        BedPOI lockedCand = null;
        double lockedCandScore = Double.POSITIVE_INFINITY;
        BlockPos me = ctx.playerFeet();
        ToolProfile tools = inspectToolProfile(ctx.player());
        BedPOI[] beds = core.getBedsSnapshot();
        for (int i = 0; i < beds.length; i++) {
            BedPOI b = beds[i];
            if (b.isHome() || b.destroyed()) {
                continue;
            }
            if (rushFilter != TeamColor.UNKNOWN && b.team() != rushFilter) {
                continue;
            }
            double dx = b.x() - me.getX();
            double dy = b.y() - me.getY();
            double dz = b.z() - me.getZ();
            double dSq = dx * dx + dy * dy + dz * dz;
            double score = dSq;
            if (rushWeakestMode && rushFilter == TeamColor.UNKNOWN) {
                BreakVectorDecision dec = evaluateBestBedBreakVector(b, tools);
                CachedBedDefenseProfile prof = mem.getOrScanBedDefense(core, b, tools);
                BedShellScan shell = prof != null ? prof.shellScan() : null;
                double estClear = shell != null ? shell.estimatedClearTicks() : dec.estimatedBreakTicks();
                int layers = shell != null ? shell.layersDetected() : 1;
                score = Math.min(dec.totalScoreTicks(), estClear + Math.sqrt(dSq) * 0.45)
                        + layers * 2.5 + dSq * 0.015;
            }
            // Big rush priority bonus if that enemy team's defender just died and is on 5s respawn timer!
            if (ms != null && ms.isTeamAnyMemberRespawning(b.team(), nowMs)) {
                score *= 0.72;
            }
            long headKey = BlockPos.asLong(b.x(), b.y(), b.z());
            if (headKey == lockedTargetBedHeadKey) {
                lockedCand = b;
                lockedCandScore = score;
            }
            if (score < bestScore) {
                bestScore = score;
                best = b;
            }
        }
        // Target-bed hysteresis: keep current living target unless a new target is >= 25% better
        if (lockedCand != null && best != null && lockedCand != best && bestScore * 1.25 >= lockedCandScore) {
            best = lockedCand;
        }
        if (best != null) {
            lockedTargetBedHeadKey = BlockPos.asLong(best.x(), best.y(), best.z());
        } else {
            lockedTargetBedHeadKey = Long.MIN_VALUE;
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

    private BlockPos findFirstSolidAlongBreakVector(BedPOI bed, BreakVectorCandidate vector, long nowTick) {
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
            Long cd = recentlyBrokenBlocks.get(check.asLong());
            if (cd != null && nowTick < cd) {
                continue;
            }
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

    private ToolProfile inspectToolProfile(LocalPlayer p) {
        if (p == null || ctx.world() == null) {
            return ToolProfile.bareHand();
        }
        boolean modern = baritone.getBedwarsArenaMemory().getActiveRuleset() == BwsRuleset.MODERN_26_3;
        InventorySummary inv = baritone.getBedwarsArenaMemory().getTickContext()
                .getInventorySummary(p, modern, ctx.world().getGameTime());
        return new ToolProfile(inv.shearsTier(), inv.pickaxeTier(), inv.axeTier());
    }

    private static int toolTierFromPath(String path) {
        return BedwarsTacticalHelper.toolTierFromPath(path);
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

    private PathingCommand tickBuy(boolean calcFailed) {
        LocalPlayer p = ctx.player();
        BedwarsArenaMemory memory = baritone.getBedwarsArenaMemory();
        BedwarsMapCore core = memory.getActiveCore();
        boolean modern = memory.getActiveRuleset() == BwsRuleset.MODERN_26_3;
        AbstractContainerMenu menu = p.containerMenu;
        long now = ctx.world().getGameTime();
        boolean hasContainerScreen = ctx.minecraft() != null
                && ctx.minecraft().gui != null
                && ctx.minecraft().gui.screen() instanceof AbstractContainerScreen<?>;
        boolean hasShopContainerOpen = (menu != p.inventoryMenu
                && !(menu instanceof CraftingMenu)
                && !(menu instanceof AbstractFurnaceMenu)
                && menu.slots.size() > 36)
                || (hasContainerScreen && menu != p.inventoryMenu);

        if (p.hurtTime > 0 || hasNearbyEnemyWithin(9.5)) {
            if (hasShopContainerOpen) {
                CraftingHelper.safeCloseContainer(ctx);
            }
            if (!baritone.getBrainProcess().isControlling()) {
                logDirect("Intruder nearby — aborting Bedwars shop interaction for combat.");
                cancel("buy_intruder_nearby");
            }
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }

        if (awaitingShopClose) {
            if (!hasShopContainerOpen) {
                awaitingShopClose = false;
                cancel("buy_complete_closed");
            } else {
                CraftingHelper.safeCloseContainer(ctx);
            }
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }

        InventorySummary curInv = memory.getTickContext().getInventorySummary(p, modern, now);
        boolean wantUpgrades = buyPreset == ShopPreset.UPGRADES
                || (buyPreset == ShopPreset.AUTO && wantsUpgradeShopForCombatUpgrades(curInv, memory.getMatchState()));

        if (menu != p.inventoryMenu && !(menu instanceof CraftingMenu) && !(menu instanceof AbstractFurnaceMenu) && menu.slots.size() > 36) {
            String menuTitle = memory.getOpenMenuTitle(menu.containerId);
            if ((menuTitle == null || menuTitle.isBlank()) && ctx.minecraft().gui != null
                    && ctx.minecraft().gui.screen() instanceof IGuiScreen gs) {
                Component titleComp = gs.pathadol$getTitle();
                if (titleComp != null) {
                    menuTitle = BedwarsArenaMemory.stripColors(titleComp.getString());
                }
            }
            if (BedwarsShopPlanner.isNonShopUiTitle(menuTitle)) {
                buyEmptyContainerTicks = 0;
                if (CraftingHelper.safeCloseContainer(ctx)) {
                    actionCooldown = 4;
                }
                return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
            }

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
            // Pika/multiplayer sends ClientboundOpenScreenPacket before ClientboundContainerSetContentPacket arrives
            if (offers.isEmpty() && ++buyEmptyContainerTicks < 12) {
                return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
            }
            buyEmptyContainerTicks = 0;

            MenuKind openedKind = BedwarsShopPlanner.classifyOpenMenu(menuTitle, offers);
            if (openedKind != MenuKind.UNKNOWN && lastInteractedNpcPos != null) {
                MenuKind prev = learnedNpcRoles.put(lastInteractedNpcPos.asLong(), openedKind);
                memory.recordLearnedNpcAndSave(lastInteractedNpcPos, openedKind);
                if (prev != openedKind && baritone.getBedwarsBlackboxRecorder() != null) {
                    baritone.getBedwarsBlackboxRecorder().recordEvent("NPC_LEARNED",
                            "pos=" + lastInteractedNpcPos.toShortString() + ",kind=" + openedKind.name() + ",title=" + menuTitle);
                }
            }

            boolean mismatch = (wantUpgrades && openedKind == MenuKind.ITEM_SHOP)
                    || (!wantUpgrades && openedKind == MenuKind.UPGRADE_SHOP);
            if (mismatch) {
                if (lastInteractedNpcPos != null) {
                    wrongNpcThisTrip.add(lastInteractedNpcPos.asLong());
                }
                if (CraftingHelper.safeCloseContainer(ctx)) {
                    Entity altEntity = findBestShopEntity(core, wantUpgrades);
                    BlockPos altPos = findBestShopPos(core, altEntity, wantUpgrades);
                    if (baritone.getBedwarsBlackboxRecorder() != null) {
                        baritone.getBedwarsBlackboxRecorder().recordEvent("SHOP_MISMATCH_SWITCH",
                                "wanted=" + (wantUpgrades ? "UPGRADE_SHOP" : "ITEM_SHOP")
                                        + ",opened=" + openedKind.name()
                                        + ",hasAlt=" + (altPos != null));
                    }
                    if (altPos != null) {
                        actionCooldown = 6;
                        buyStallTicks = 0;
                        lastInteractedNpcPos = null;
                    } else {
                        setShopCooldown(wantUpgrades, curInv, now, 100L);
                        awaitingShopClose = true;
                    }
                }
                return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
            }

            BedPOI homeBed = findHomeBed(core);
            boolean homeAlive = homeBed != null && !homeBed.destroyed();
            double defRatio = computeBedCoverageRatio(homeBed);
            double nearestEnemy = findNearestEnemyDistance();
            EnemyDefenseIntel defenseIntel = EnemyDefenseIntel.fromCore(core);
            ShopPreset effective = (buyPreset == ShopPreset.AUTO)
                    ? BedwarsShopPlanner.recommendPreset(curInv, homeAlive, defRatio, nearestEnemy, defenseIntel)
                    : buyPreset;

            long rev = memory.getContainerRevision();
            String curSig = curInv.iron() + ":" + curInv.gold() + ":" + curInv.diamonds() + ":" + curInv.emeralds();
            String itemSig = curInv.bridgeBlockCount() + ":" + curInv.hardDefenseCount() + ":" + curInv.swordTier()
                    + ":" + curInv.armorPoints() + ":" + curInv.shearsTier() + ":" + curInv.pickaxeTier()
                    + ":" + curInv.axeTier() + ":" + curInv.gapples() + ":" + curInv.fireballs()
                    + ":" + curInv.tntCount() + ":" + curInv.pearls();
            if (now < awaitingShopResponseUntilTick) {
                if (lastShopClickWasTab) {
                    if (rev == lastShopClickRevision) {
                        return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
                    }
                } else if (curSig.equals(lastShopClickCurrencySig) && itemSig.equals(lastShopClickItemSig)) {
                    return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
                }
            }
            if (memory.getMatchState().isInsufficientMaterialsCooldown(System.currentTimeMillis())) {
                setShopCooldown(wantUpgrades, curInv, now, 100L);
                memory.saveServerShopCatalogIfReady();
                if (baritone.getBedwarsBlackboxRecorder() != null) {
                    baritone.getBedwarsBlackboxRecorder().recordEvent("SHOP_INSUFFICIENT_CHAT_ABORT",
                            "kind=" + openedKind.name() + ",preset=" + effective.name());
                }
                awaitingShopClose = true;
                CraftingHelper.safeCloseContainer(ctx);
                return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
            }

            if (buyClicksDone >= 18) {
                setShopCooldown(wantUpgrades, curInv, now, 60L);
                memory.saveServerShopCatalogIfReady();
                logDirect("Bedwars shop trip finished (" + buyClicksDone + " purchases).");
                awaitingShopClose = true;
                CraftingHelper.safeCloseContainer(ctx);
                return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
            }

            // Wait for server inventory deduction & tab swap packets before planning/closing
            if (actionCooldown > 0) {
                return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
            }

            boolean inUpgradeShop = (openedKind == MenuKind.UPGRADE_SHOP)
                    || (openedKind == MenuKind.UNKNOWN && buyPreset == ShopPreset.UPGRADES);
            ShopClickDecision scanClick = memory.getShopCatalog().nextScanTabDecision(openedKind, offers);
            memory.saveServerShopCatalogIfReady();
            ShopClickDecision click = scanClick != null
                    ? scanClick
                    : BedwarsShopPlanner.chooseNextShopClick(
                            offers, effective, curInv, inUpgradeShop, homeAlive,
                            defRatio, nearestEnemy, buyCategorySwitched, memory.getMatchState(),
                            defenseIntel, memory.getShopCatalog()
                    );
            if (click == null) {
                if (lastShopClickWasTab && (now - lastTabSwitchTick) <= 14L) {
                    // Server tab-switch GUI_OPEN/items packet can take 6-12 ticks on high-ping servers; wait cleanly
                    return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
                }
                if (buyClicksDone == 0) {
                    setShopCooldown(wantUpgrades, curInv, now, 100L);
                    if (baritone.getBedwarsBlackboxRecorder() != null) {
                        baritone.getBedwarsBlackboxRecorder().recordEvent("SHOP_EMPTY_COOLDOWN",
                                "kind=" + openedKind.name() + ",preset=" + effective.name());
                    }
                } else {
                    setShopCooldown(wantUpgrades, curInv, now, 60L);
                }
                logDirect("Bedwars shop complete (" + buyClicksDone + " purchases).");
                awaitingShopClose = true;
                CraftingHelper.safeCloseContainer(ctx);
                return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
            }

            if (!CraftingHelper.advanceAndReadyGui(ctx, click.slotIndex())) {
                return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
            }
            ctx.playerController().windowClick(menu.containerId, click.slotIndex(), 0, ContainerInput.PICKUP, p);
            awaitingShopResponseUntilTick = now + 11L;
            boolean isWoolBuy = click.buyKey() != null && click.buyKey().toLowerCase(java.util.Locale.ROOT).contains("wool");
            if (!click.categorySwitch() && !isWoolBuy) {
                actionCooldown = Math.max(actionCooldown, 4);
            }
            lastShopClickRevision = rev;
            lastShopClickCurrencySig = curSig;
            lastShopClickItemSig = itemSig;
            lastShopClickWasTab = scanClick != null || click.categorySwitch();
            if (lastShopClickWasTab) {
                lastTabSwitchTick = now;
            }
            if (baritone.getBedwarsBlackboxRecorder() != null) {
                String costLabel = click.cost() != null ? (click.cost().amount() + " " + click.cost().currency().name()) : "";
                baritone.getBedwarsBlackboxRecorder().recordShopClick(
                        menu.containerId, click.slotIndex(), click.buyKey(), click.itemName(),
                        costLabel, curInv.iron(), curInv.gold(), curInv.diamonds(), curInv.emeralds()
                );
            }
            if (scanClick != null) {
                actionCooldown = Baritone.settings().humanMode.value ? 4 : 3;
            } else if (click.categorySwitch()) {
                buyCategorySwitched = true;
                actionCooldown = Baritone.settings().humanMode.value ? 5 : 4;
            } else {
                buyClicksDone++;
                actionCooldown = Baritone.settings().humanMode.value ? 5 : 3;
            }
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }
        buyEmptyContainerTicks = 0;

        Entity shopEntity = findBestShopEntity(core, wantUpgrades);
        BlockPos shopPos = findBestShopPos(core, shopEntity, wantUpgrades);
        if (shopPos == null) {
            logDirect("No Bedwars Shop/Upgrade NPC discovered nearby.");
            setShopCooldown(wantUpgrades, curInv, now, 80L);
            cancel();
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }

        if (shopEntity != null && p.distanceToSqr(shopEntity) <= 11.5) {
            buyZeroMoveTicks = 0;
            buyLastPos = p.position();
            baritone.getInputOverrideHandler().clearMovementKeys();
            Vec3 aimVec = shopEntity.getBoundingBox().getCenter();
            Rotation aim = RotationUtils.calcRotationFromVec3d(ctx.playerHead(), aimVec, ctx.playerRotations());
            baritone.getLookBehavior().updateTarget(aim, true, true);
            ((baritone.behavior.LookBehavior) baritone.getLookBehavior()).applyClientPreTickRotationAndRepick();
            if (actionCooldown == 0 && CombatProcess.clickUseIfCrosshairMatches(ctx, shopEntity, 11.5, true)) {
                lastInteractedNpcPos = shopEntity.blockPosition();
                actionCooldown = 14;
            }
            if (++buyStallTicks > 100) {
                logDirect("Timed out waiting for Bedwars shop menu.");
                setShopCooldown(wantUpgrades, curInv, now, 80L);
                cancel();
            }
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }

        Vec3 curPos = p.position();
        if (buyLastPos == null || curPos.distanceToSqr(buyLastPos) >= 0.09) {
            buyLastPos = curPos;
            buyZeroMoveTicks = 0;
        } else {
            buyZeroMoveTicks++;
        }

        if (++buyStallTicks > 140) {
            logDirect("Could not reach Bedwars shop NPC in time.");
            setShopCooldown(wantUpgrades, curInv, now, 80L);
            cancel();
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }

        // Escape recessed half-slab forge pits (e.g. Lighthouse y=64.5) when A* reports calcFailed or stalls
        if ((calcFailed || buyZeroMoveTicks >= 18) && curPos.distanceToSqr(Vec3.atCenterOf(shopPos)) <= 400.0) {
            BlockPos escapeTarget = shopPos;
            if (buyZeroMoveTicks >= 42) {
                BlockPos homeAnchor = findHomeAnchorPos(core);
                if (homeAnchor != null) {
                    escapeTarget = homeAnchor;
                }
            }
            if (tryDirectWalkTowardSafe(escapeTarget)) {
                return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
            }
        }
        int shopNearRange = (calcFailed || buyStallTicks >= 20) ? 3 : 2;
        return new PathingCommand(new GoalNear(shopPos, shopNearRange), PathingCommandType.SET_GOAL_AND_PATH);
    }

    /**
     * Safe void-checked direct step toward a nearby home-island target to exit recessed slab forge
     * pits where A* head-clearance checks reject every neighbor.
     */
    private boolean tryDirectWalkTowardSafe(BlockPos target) {
        LocalPlayer p = ctx.player();
        if (p == null || ctx.world() == null || target == null) {
            return false;
        }
        Vec3 pos = p.position();
        double dx = (target.getX() + 0.5) - pos.x;
        double dz = (target.getZ() + 0.5) - pos.z;
        double horiz = Math.hypot(dx, dz);
        if (horiz < 0.8) {
            return false;
        }
        double nx = dx / horiz;
        double nz = dz / horiz;
        // Never step directly toward void; verify walkable ground within 2 blocks down at +0.9m and +1.5m ahead
        for (double probe : new double[]{0.9, 1.5}) {
            int px = (int) Math.floor(pos.x + nx * probe);
            int pz = (int) Math.floor(pos.z + nz * probe);
            int py = (int) Math.floor(pos.y);
            boolean hasFloor = MovementHelper.canWalkOn(ctx, new BlockPos(px, py, pz))
                    || MovementHelper.canWalkOn(ctx, new BlockPos(px, py - 1, pz))
                    || MovementHelper.canWalkOn(ctx, new BlockPos(px, py - 2, pz));
            if (!hasFloor) {
                return false;
            }
        }
        Rotation look = RotationUtils.calcRotationFromVec3d(
                ctx.playerHead(),
                new Vec3(target.getX() + 0.5, pos.y + 1.2, target.getZ() + 0.5),
                ctx.playerRotations()
        );
        baritone.getInputOverrideHandler().clearMovementKeys();
        baritone.getLookBehavior().updateTarget(new Rotation(look.getYaw(), 6.0f), false, true);
        ((baritone.behavior.LookBehavior) baritone.getLookBehavior()).applyClientPreTickRotationAndRepick();
        baritone.getInputOverrideHandler().setDesiredMovementYaw(look.getYaw());
        baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_FORWARD, true);
        double fracY = pos.y - Math.floor(pos.y);
        boolean onHalfSlab = fracY > 0.35 && fracY < 0.65;
        if (p.onGround() && (p.horizontalCollision || (onHalfSlab && buyZeroMoveTicks % 6 == 0))) {
            baritone.getInputOverrideHandler().setInputForceState(Input.JUMP, true);
        }
        return true;
    }

    private void setShopCooldown(boolean upgrades, InventorySummary inv, long nowTick, long durationTicks) {
        long until = nowTick + Math.max(20L, durationTicks);
        if (upgrades) {
            upgradeCooldownUntilTick = Math.max(upgradeCooldownUntilTick, until);
        } else {
            shopCooldownUntilTick = Math.max(shopCooldownUntilTick, until);
        }
        if (inv != null) {
            cooldownIron = inv.iron();
            cooldownGold = inv.gold();
            cooldownDiamonds = inv.diamonds();
            cooldownEmeralds = inv.emeralds();
        }
    }

    private record DefensePlaceSolution(
            BlockPos targetPos,
            BlockPos placeAgainst,
            Direction face,
            Rotation aimRot,
            boolean hardPreferred,
            boolean againstBed
    ) {}

    private static Vec3[] defenseAabbSideMultipliers(Direction against) {
        return switch (against) {
            case UP -> new Vec3[]{new Vec3(0.5, 1.0, 0.5), new Vec3(0.2, 1.0, 0.5), new Vec3(0.8, 1.0, 0.5), new Vec3(0.5, 1.0, 0.2), new Vec3(0.5, 1.0, 0.8)};
            case DOWN -> new Vec3[]{new Vec3(0.5, 0.0, 0.5), new Vec3(0.2, 0.0, 0.5), new Vec3(0.8, 0.0, 0.5), new Vec3(0.5, 0.0, 0.2), new Vec3(0.5, 0.0, 0.8)};
            case NORTH, SOUTH, EAST, WEST -> {
                double x = against.getStepX() == 0 ? 0.5 : (1.0 + against.getStepX()) * 0.5;
                double z = against.getStepZ() == 0 ? 0.5 : (1.0 + against.getStepZ()) * 0.5;
                yield new Vec3[]{new Vec3(x, 0.5, z), new Vec3(x, 0.25, z), new Vec3(x, 0.75, z)};
            }
        };
    }

    private PathingCommand tickDefend() {
        LocalPlayer p = ctx.player();
        BedwarsArenaMemory memory = baritone.getBedwarsArenaMemory();
        BedwarsMapCore core = memory.getActiveCore();
        if (p.hurtTime > 0 || hasNearbyEnemyWithin(18.0) || hasEnemyNearHomeBed(core, 22.0)) {
            baritone.getInputOverrideHandler().clearAllKeys();
            committedDefendTarget = null;
            if (!baritone.getBrainProcess().isControlling()) {
                cancel("defend_intruder_nearby");
            }
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }
        baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_LEFT, false);

        BedPOI homeBed = findHomeBed(core);
        if (homeBed == null || homeBed.destroyed()) {
            committedDefendTarget = null;
            cancel();
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }
        long now = ctx.world().getGameTime();
        if (++defendVisitTicks > 150) {
            defendEmptyCooldownUntilTick = now + 120L;
            committedDefendTarget = null;
            cancel();
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }
        if (!hasAnyDefenseBlock(false)) {
            committedDefendTarget = null;
            cancel();
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }
        List<DefensePlacement> plan = BedwarsTacticalHelper.buildHomeBedDefensePlan(
                homeBed.x(), homeBed.y(), homeBed.z(),
                homeBed.footX(), homeBed.footY(), homeBed.footZ(),
                defendButterfly,
                (x, y, z) -> !ctx.world().getBlockState(new BlockPos(x, y, z)).canBeReplaced()
                        || (core != null && core.isBlockedForBedDefense(x, y, z))
                        || memory.isLearnedBlockedDefenseBlock(x, y, z)
        );
        if (defendLastPlanSize >= 0 && plan.size() < defendLastPlanSize) {
            defendNoPlaceTicks = 0;
            committedDefendTarget = null;
            committedDefendTicks = 0;
        } else {
            defendNoPlaceTicks++;
        }
        defendLastPlanSize = plan.size();
        if (plan.isEmpty()) {
            defendEmptyCooldownUntilTick = now + 80L;
            committedDefendTarget = null;
            cancel();
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }
        if (defendNoPlaceTicks > 65) {
            defendEmptyCooldownUntilTick = now + 120L;
            committedDefendTarget = null;
            cancel();
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }

        // BuilderProcess-inspired Step 1: scan ALL unplaced cells in the lowest incomplete layer and place
        // any cell already reachable with a verified VoxelShape raycast from our current standing spot!
        int minLayer = plan.get(0).layer();
        List<DefensePlacement> activeLayer = new ArrayList<>();
        for (DefensePlacement cand : plan) {
            if (cand.layer() > minLayer) break;
            BlockPos cp = new BlockPos(cand.x(), cand.y(), cand.z());
            if (now >= failedPlaceCooldownUntil.getOrDefault(cp.asLong(), 0L)) {
                activeLayer.add(cand);
            }
        }
        if (activeLayer.isEmpty()) {
            activeLayer.addAll(plan);
        }

        DefensePlaceSolution solution = searchForPlacableDefenseCell(p, activeLayer);
        if (solution != null) {
            if (!solution.targetPos().equals(committedDefendTarget)) {
                committedDefendTarget = solution.targetPos();
                committedDefendTicks = 0;
            } else if (++committedDefendTicks > 24) {
                failedPlaceCooldownUntil.put(solution.targetPos().asLong(), now + 45L);
                committedDefendTarget = null;
                committedDefendTicks = 0;
            }
            return executeDefensePlacementSolution(p, solution, now);
        }
        committedDefendTarget = null;
        committedDefendTicks = 0;

        // BuilderProcess-inspired Step 2: no unplaced cell is reachable from here (either too far, occluded
        // behind a wall/glass, or our hitbox overlaps the cell). Assemble a GoalComposite of verified walkable
        // vantage blocks strictly outside the bed & unplaced shell footprint!
        Goal vantageGoal = assembleDefenseVantageGoal(homeBed, activeLayer, p);
        if (vantageGoal != null) {
            return new PathingCommand(vantageGoal, PathingCommandType.SET_GOAL_AND_PATH);
        }
        DefensePlacement fallback = activeLayer.get(0);
        return new PathingCommand(new GoalNear(new BlockPos(fallback.x(), fallback.y(), fallback.z()), 2), PathingCommandType.SET_GOAL_AND_PATH);
    }

    private DefensePlaceSolution searchForPlacableDefenseCell(LocalPlayer p, List<DefensePlacement> candidates) {
        // Keep current committed target if it's still immediately placeable (prevents camera flip-flopping)
        if (committedDefendTarget != null) {
            for (DefensePlacement cand : candidates) {
                if (cand.x() == committedDefendTarget.getX()
                        && cand.y() == committedDefendTarget.getY()
                        && cand.z() == committedDefendTarget.getZ()) {
                    DefensePlaceSolution sol = findDefensePlaceSolutionForCell(p, cand);
                    if (sol != null) {
                        return sol;
                    }
                    break;
                }
            }
        }
        DefensePlaceSolution best = null;
        double bestScore = Double.POSITIVE_INFINITY;
        Rotation curRot = ctx.playerRotations();
        for (DefensePlacement cand : candidates) {
            DefensePlaceSolution sol = findDefensePlaceSolutionForCell(p, cand);
            if (sol == null) continue;
            // Prefer non-bed anchors and smallest angular sweep from current crosshair for smooth Taunahi-like flow
            double ang = MouseModel.angularDistance(curRot, sol.aimRot());
            double score = ang + (sol.againstBed() ? 45.0 : 0.0) + (cand.y() > p.getBlockY() ? 8.0 : 0.0);
            if (score < bestScore) {
                bestScore = score;
                best = sol;
            }
        }
        return best;
    }

    private DefensePlaceSolution findDefensePlaceSolutionForCell(LocalPlayer p, DefensePlacement cand) {
        BlockPos targetPos = new BlockPos(cand.x(), cand.y(), cand.z());
        if (!ctx.world().getBlockState(targetPos).canBeReplaced()) {
            return null;
        }
        // BuilderProcess placementPlausible check: never try to place inside our own hitbox!
        if (p.getBoundingBox().inflate(0.05).intersects(
                targetPos.getX(), targetPos.getY(), targetPos.getZ(),
                targetPos.getX() + 1.0, targetPos.getY() + 1.0, targetPos.getZ() + 1.0)) {
            return null;
        }
        Vec3 sneakEye = RayTraceUtils.inferSneakingEyePosition(p);
        double reach = Math.min(4.45, ctx.playerController().getBlockReachDistance());
        DefensePlaceSolution bedFallback = null;

        for (Direction against : Direction.values()) {
            BlockPos placeAgainstPos = targetPos.relative(against);
            BlockState placeAgainstState = ctx.world().getBlockState(placeAgainstPos);
            if (placeAgainstState.canBeReplaced()) {
                continue;
            }
            var shape = placeAgainstState.getShape(ctx.world(), placeAgainstPos);
            if (shape.isEmpty()) {
                continue;
            }
            net.minecraft.world.phys.AABB aabb = shape.bounds();
            Direction face = against.getOpposite();
            boolean isBed = placeAgainstState.getBlock() instanceof BedBlock;
            // BuilderProcess uses actual VoxelShape bounds + multi-point sampling on each face!
            // Crucial for BedBlock (maxY = 0.5625, NOT 1.0) so roof caps above the bed hit cleanly!
            for (Vec3 mult : defenseAabbSideMultipliers(face)) {
                double px = placeAgainstPos.getX() + aabb.minX * (1.0 - mult.x) + aabb.maxX * mult.x;
                double py = placeAgainstPos.getY() + aabb.minY * (1.0 - mult.y) + aabb.maxY * mult.y;
                double pz = placeAgainstPos.getZ() + aabb.minZ * (1.0 - mult.z) + aabb.maxZ * mult.z;
                Vec3 aimPt = new Vec3(px, py, pz);
                if (sneakEye.distanceToSqr(aimPt) > reach * reach) {
                    continue;
                }
                Rotation rot = RotationUtils.calcRotationFromVec3d(sneakEye, aimPt, ctx.playerRotations());
                HitResult hr = RayTraceUtils.rayTraceTowards(p, rot, reach, true);
                if (hr instanceof BlockHitResult bhr
                        && hr.getType() == HitResult.Type.BLOCK
                        && bhr.getBlockPos().equals(placeAgainstPos)
                        && bhr.getDirection() == face) {
                    DefensePlaceSolution sol = new DefensePlaceSolution(targetPos, placeAgainstPos, face, rot, cand.hardPreferred(), isBed);
                    if (!isBed) {
                        return sol;
                    }
                    if (bedFallback == null) {
                        bedFallback = sol;
                    }
                    break;
                }
            }
        }
        return bedFallback;
    }

    private PathingCommand executeDefensePlacementSolution(LocalPlayer p, DefensePlaceSolution sol, long now) {
        int slot = selectDefenseBlockSlot(p, sol.hardPreferred());
        if (slot >= 0) {
            if (!baritone.getInventoryBehavior().safeSelectHotbarSlot(slot)) {
                return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
            }
        } else if (!baritone.getInventoryBehavior().throwaway(true, st -> isDefenseStack(st, sol.hardPreferred()), Baritone.settings().allowInventory.value)
                && (!sol.hardPreferred() || !baritone.getInventoryBehavior().throwaway(true, st -> isDefenseStack(st, false), Baritone.settings().allowInventory.value))) {
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }
        baritone.getInputOverrideHandler().clearMovementKeys();
        // Always crouch while placing bed defense (like BuilderProcess): never opens/sleeps in bed and never slips off!
        baritone.getInputOverrideHandler().setInputForceState(Input.SNEAK, true);
        baritone.getLookBehavior().setBridgeAnchorMode(true);
        baritone.getLookBehavior().updateTarget(sol.aimRot(), true, true);
        ((baritone.behavior.LookBehavior) baritone.getLookBehavior()).applyClientPreTickRotationAndRepick();

        if (actionCooldown == 0 && MouseModel.angularDistance(ctx.playerRotations(), sol.aimRot()) <= 9.0f) {
            if (sol.againstBed() && !p.isCrouching()) {
                return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
            }
            HitResult liveOver = ctx.objectMouseOver();
            boolean liveHitValid = liveOver instanceof BlockHitResult bhr
                    && liveOver.getType() == HitResult.Type.BLOCK
                    && (bhr.getBlockPos().relative(bhr.getDirection()).equals(sol.targetPos())
                    || (bhr.getBlockPos().equals(sol.placeAgainst()) && bhr.getDirection() == sol.face()));
            if (liveHitValid) {
                baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_RIGHT, true);
                lastPlaceAttemptPos = sol.targetPos();
                lastPlaceAttemptTick = now;
                actionCooldown = Baritone.settings().humanMode.value ? 2 : 1;
            }
        }
        return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
    }

    private Goal assembleDefenseVantageGoal(BedPOI homeBed, List<DefensePlacement> unplaced, LocalPlayer p) {
        BlockStateInterface bsi = new BlockStateInterface(ctx);
        Set<Long> forbidden = new HashSet<>();
        forbidden.add(BlockPos.asLong(homeBed.x(), homeBed.y(), homeBed.z()));
        forbidden.add(BlockPos.asLong(homeBed.footX(), homeBed.footY(), homeBed.footZ()));
        for (DefensePlacement dp : unplaced) {
            forbidden.add(BlockPos.asLong(dp.x(), dp.y(), dp.z()));
        }
        int baseX = (homeBed.x() + homeBed.footX()) / 2;
        int baseY = homeBed.y();
        int baseZ = (homeBed.z() + homeBed.footZ()) / 2;
        List<Goal> vantageGoals = new ArrayList<>();
        for (int dx = -4; dx <= 4; dx++) {
            for (int dz = -4; dz <= 4; dz++) {
                int distSqXZ = dx * dx + dz * dz;
                if (distSqXZ < 3 || distSqXZ > 18) continue;
                for (int dy = -1; dy <= 1; dy++) {
                    int sx = baseX + dx;
                    int sy = baseY + dy;
                    int sz = baseZ + dz;
                    if (forbidden.contains(BlockPos.asLong(sx, sy, sz))) continue;
                    if (!MovementHelper.canWalkOn(bsi, sx, sy - 1, sz)) continue;
                    if (!MovementHelper.canWalkThrough(bsi, sx, sy, sz)
                            || !MovementHelper.canWalkThrough(bsi, sx, sy + 1, sz)) continue;
                    // Verify this stand spot has unobstructed sightline to at least one unplaced defense cell
                    Vec3 standEye = new Vec3(sx + 0.5, sy + 1.54, sz + 0.5);
                    boolean seesAny = false;
                    for (DefensePlacement dp : unplaced) {
                        Vec3 cellCenter = new Vec3(dp.x() + 0.5, dp.y() + 0.5, dp.z() + 0.5);
                        if (standEye.distanceToSqr(cellCenter) > 16.0) continue;
                        BlockHitResult clip = ctx.world().clip(new net.minecraft.world.level.ClipContext(
                                standEye, cellCenter,
                                net.minecraft.world.level.ClipContext.Block.COLLIDER,
                                net.minecraft.world.level.ClipContext.Fluid.NONE, p));
                        if (clip == null || clip.getType() == HitResult.Type.MISS
                                || clip.getBlockPos().distSqr(new BlockPos(dp.x(), dp.y(), dp.z())) <= 2.0) {
                            seesAny = true;
                            break;
                        }
                    }
                    if (seesAny) {
                        vantageGoals.add(new GoalBlock(sx, sy, sz));
                    }
                }
            }
        }
        return vantageGoals.isEmpty() ? null : new GoalComposite(vantageGoals.toArray(new Goal[0]));
    }

    private boolean hasEnemyNearHomeBed(BedwarsMapCore core, double radius) {
        LocalPlayer me = ctx.player();
        BedPOI homeBed = findHomeBed(core);
        if (me == null || homeBed == null || homeBed.destroyed()) {
            return false;
        }
        double rSq = radius * radius;
        double approachRSq = (radius + 10.0) * (radius + 10.0);
        double bx = homeBed.x() + 0.5;
        double bz = homeBed.z() + 0.5;
        List<Player> enemies = baritone.getBedwarsArenaMemory().getTickContext().enemies();
        for (int i = 0, n = enemies.size(); i < n; i++) {
            Player pl = enemies.get(i);
            double dx = pl.getX() - bx;
            double dz = pl.getZ() - bz;
            double dSq = dx * dx + dz * dz;
            if (dSq <= rSq) {
                return true;
            }
            if (dSq <= approachRSq) {
                Vec3 ov = pl.getDeltaMovement();
                double closingDot = -(ov.x * dx + ov.z * dz);
                if (closingDot > 0.08 * Math.sqrt(dSq)) {
                    return true;
                }
            }
        }
        return false;
    }

    private PathingCommand tickBlockIn() {
        BedPOI nearestEnemy = findNearestLivingEnemyBed();
        BlockPos ref = nearestEnemy != null
                ? new BlockPos(nearestEnemy.x(), nearestEnemy.y(), nearestEnemy.z())
                : ctx.playerFeet().relative(ctx.player().getDirection(), 3);
        if (!tryPlaceNextBlockInStep(ref, nearestEnemy)) {
            logDirect("Block-in complete or out of blocks.");
            cancel();
        }
        return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
    }

    private boolean tryPlaceNextBlockInStep(BlockPos bedOrRef, BedPOI optionalBed) {
        BedwarsMapCore core = baritone.getBedwarsArenaMemory().getActiveCore();
        BlockPos feet = ctx.playerFeet();
        int headX = optionalBed != null ? optionalBed.x() : bedOrRef.getX();
        int bedY = optionalBed != null ? optionalBed.y() : bedOrRef.getY();
        int headZ = optionalBed != null ? optionalBed.z() : bedOrRef.getZ();
        int footX = optionalBed != null ? optionalBed.footX() : bedOrRef.getX();
        int footZ = optionalBed != null ? optionalBed.footZ() : bedOrRef.getZ();

        long now = ctx.world() != null ? ctx.world().getGameTime() : 0L;
        Set<Long> activeSkip = new HashSet<>();
        for (Map.Entry<Long, Long> e : failedPlaceCooldownUntil.entrySet()) {
            if (now < e.getValue()) {
                activeSkip.add(e.getKey());
            }
        }
        for (Map.Entry<Long, Long> e : recentlyBrokenBlocks.entrySet()) {
            if (now < e.getValue()) {
                activeSkip.add(e.getKey());
            }
        }

        BlockPos.MutableBlockPos mp = baritone.getBedwarsArenaMemory().getTickContext().scratchPos();
        List<DefensePlacement> plan = BedwarsTacticalHelper.buildSmartBlockInPlan(
                feet.getX(), feet.getY(), feet.getZ(),
                headX, bedY, headZ, footX, footZ,
                ctx.player() != null ? ctx.player().getYRot() : 0.0f,
                (x, y, z) -> !ctx.world().getBlockState(mp.set(x, y, z)).canBeReplaced()
                        || (core != null && core.isBlockedForBedDefense(x, y, z)),
                activeSkip
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
        BedwarsArenaMemory memory = baritone.getBedwarsArenaMemory();
        BedwarsMapCore core = memory.getActiveCore();
        BlockPos home = findHomeAnchorPos(core);
        if (home == null) {
            cancel("retreat_no_home_anchor");
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }
        LocalPlayer p = ctx.player();
        double homeDistSq = ctx.playerFeet().distSqr(home);
        if (homeDistSq <= 42.0) {
            logDirect("Arrived safely at home base.");
            cancel("retreat_arrived_home");
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }

        long now = ctx.world().getGameTime();
        if (now >= retreatUtilityCooldownUntilTick && p.onGround()) {
            // Find closest chasing enemy
            Player chaser = null;
            double chaserDistSq = 12.0 * 12.0;
            List<Player> enemies = memory.getTickContext().enemies();
            for (int i = 0, n = enemies.size(); i < n; i++) {
                Player ep = enemies.get(i);
                double dSq = p.distanceToSqr(ep);
                if (dSq < chaserDistSq) {
                    chaserDistSq = dSq;
                    chaser = ep;
                }
            }

            // 1) Ender Pearl directly home when far (16..55m) and either low HP, chased, or home bed attacked
            boolean homeThreat = hasEnemyNearHomeBed(core, 26.0);
            int pearlSlot = findHotbarSlotForItem(p, Items.ENDER_PEARL);
            if (pearlSlot >= 0 && homeDistSq >= 256.0 && homeDistSq <= 3025.0
                    && (p.getHealth() <= 10.5f || chaser != null || homeThreat)) {
                double dx = (home.getX() + 0.5) - p.getX();
                double dy = (home.getY() + 1.0) - ctx.playerHead().y;
                double dz = (home.getZ() + 0.5) - p.getZ();
                double horiz = Math.sqrt(dx * dx + dz * dz);
                float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
                // Simple ballistic elevation arc for MC ender pearl (v0=1.5, g=0.03 -> ~pitch -14..-32 deg)
                float pitch = Rotation.clampPitch((float) (-Math.toDegrees(Math.atan2(dy + horiz * 0.16, Math.max(1.0, horiz))) - 4.0));
                Rotation aim = new Rotation(yaw, pitch);
                if (baritone.getInventoryBehavior().safeSelectHotbarSlot(pearlSlot)) {
                    baritone.getLookBehavior().updateTarget(aim, true, true);
                    if (MouseModel.angularDistance(ctx.playerRotations(), aim) <= 12.0f) {
                        baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_RIGHT, true);
                        retreatUtilityCooldownUntilTick = now + 28L;
                    }
                    return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
                }
            }

            // 2) Fireball at close chaser (< 10m) to blast them away / off the bridge while escaping
            int fbSlot = findHotbarSlotForItem(p, Items.FIRE_CHARGE);
            if (chaser != null && chaserDistSq >= 9.0 && chaserDistSq <= 100.0 && fbSlot >= 0) {
                Vec3 eye = ctx.playerHead();
                Vec3 chaserFeet = new Vec3(chaser.getX(), chaser.getY() + 0.35, chaser.getZ());
                Rotation aim = RotationUtils.calcRotationFromVec3d(eye, chaserFeet, ctx.playerRotations());
                if (baritone.getInventoryBehavior().safeSelectHotbarSlot(fbSlot)) {
                    baritone.getLookBehavior().updateTarget(aim, true, true);
                    if (MouseModel.angularDistance(ctx.playerRotations(), aim) <= 14.0f) {
                        baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_RIGHT, true);
                        retreatUtilityCooldownUntilTick = now + 18L;
                    }
                    return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
                }
            }

            // 3) Quick block-off between us and a close chaser (< 7.5m) to break pursuit!
            if (chaser != null && chaserDistSq <= 56.25 && hasAnyDefenseBlock(false)) {
                double dx = chaser.getX() - p.getX();
                double dz = chaser.getZ() - p.getZ();
                int stepX = Math.abs(dx) >= Math.abs(dz) ? (dx >= 0 ? 1 : -1) : 0;
                int stepZ = Math.abs(dz) > Math.abs(dx) ? (dz >= 0 ? 1 : -1) : 0;
                BlockPos feet = ctx.playerFeet();
                BlockPos blockOff = feet.offset(stepX, 0, stepZ);
                if (ctx.world().getBlockState(blockOff).canBeReplaced()
                        && (core == null || !core.isBlockedForBedDefense(blockOff.getX(), blockOff.getY(), blockOff.getZ()))) {
                    if (placeDefensePlacement(blockOff, false)) {
                        retreatUtilityCooldownUntilTick = now + 12L;
                        return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
                    }
                }
            }
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
        } else if (!baritone.getInventoryBehavior().throwaway(true, st -> isDefenseStack(st, hardPreferred), Baritone.settings().allowInventory.value)
                && (!hardPreferred || !baritone.getInventoryBehavior().throwaway(true, st -> isDefenseStack(st, false), Baritone.settings().allowInventory.value))) {
            return false;
        }
        baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_LEFT, false);
        BlockPos bestAnchor = null;
        Direction bestDir = null;
        Rotation bestRot = null;
        BlockPos bedFallbackAnchor = null;
        Direction bedFallbackDir = null;
        Rotation bedFallbackRot = null;
        Vec3 eyePos = ctx.playerHead();
        double reach = Math.min(4.5, ctx.playerController().getBlockReachDistance());
        for (Direction dir : Direction.values()) {
            BlockPos adj = targetPos.relative(dir);
            BlockState adjState = ctx.world().getBlockState(adj);
            if (!adjState.canBeReplaced()) {
                Direction placeFace = dir.getOpposite();
                Vec3 faceCenter = Vec3.atCenterOf(adj).add(
                        placeFace.getStepX() * 0.5,
                        placeFace.getStepY() * 0.5,
                        placeFace.getStepZ() * 0.5
                );
                if (eyePos.distanceToSqr(faceCenter) > reach * reach) {
                    continue;
                }
                Vec3 toEye = eyePos.subtract(faceCenter);
                boolean outwardVisible = (toEye.x * placeFace.getStepX()
                        + toEye.y * placeFace.getStepY()
                        + toEye.z * placeFace.getStepZ()) > -0.05;
                if (!outwardVisible) {
                    continue;
                }
                boolean isBedAnchor = adjState.getBlock() instanceof BedBlock;
                Vec3 aimPoint = Vec3.atCenterOf(adj).add(
                        placeFace.getStepX() * 0.49,
                        placeFace.getStepY() * 0.49,
                        placeFace.getStepZ() * 0.49
                );
                Rotation candRot = RotationUtils.calcRotationFromVec3d(eyePos, aimPoint, ctx.playerRotations());
                HitResult hr = RayTraceUtils.rayTraceTowards(ctx.player(), candRot, reach, isBedAnchor);
                boolean exactHit = hr instanceof BlockHitResult bhr
                        && hr.getType() == HitResult.Type.BLOCK
                        && bhr.getBlockPos().equals(adj)
                        && bhr.getDirection() == placeFace;
                if (!exactHit) {
                    continue;
                }
                if (isBedAnchor) {
                    if (bedFallbackAnchor == null) {
                        bedFallbackAnchor = adj;
                        bedFallbackDir = placeFace;
                        bedFallbackRot = candRot;
                    }
                } else {
                    bestAnchor = adj;
                    bestDir = placeFace;
                    bestRot = candRot;
                    break;
                }
            }
        }
        if (bestAnchor == null) {
            bestAnchor = bedFallbackAnchor;
            bestDir = bedFallbackDir;
            bestRot = bedFallbackRot;
        }
        if (bestAnchor == null || bestDir == null || bestRot == null) {
            if (ctx.world() != null) {
                failedPlaceCooldownUntil.put(targetPos.asLong(), ctx.world().getGameTime() + 25L);
            }
            return false;
        }
        baritone.getInputOverrideHandler().clearMovementKeys();
        boolean sneakForBed = ctx.world().getBlockState(bestAnchor).getBlock() instanceof BedBlock;
        if (sneakForBed) {
            baritone.getInputOverrideHandler().setInputForceState(Input.SNEAK, true);
        }
        baritone.getLookBehavior().updateTarget(bestRot, true, true);
        ((baritone.behavior.LookBehavior) baritone.getLookBehavior()).applyClientPreTickRotationAndRepick();
        long now = ctx.world() != null ? ctx.world().getGameTime() : 0L;
        if (actionCooldown == 0 && MouseModel.angularDistance(ctx.playerRotations(), bestRot) <= 9.0f) {
            // When placing directly against a BedBlock, wait until client is actually crouching
            // so right-click places the block instead of attempting to sleep in the bed!
            if (sneakForBed && !ctx.player().isCrouching()) {
                return true;
            }
            HitResult liveHr = RayTraceUtils.rayTraceTowards(ctx.player(), ctx.playerRotations(), reach, sneakForBed);
            if (liveHr instanceof BlockHitResult bhr
                    && liveHr.getType() == HitResult.Type.BLOCK
                    && bhr.getBlockPos().relative(bhr.getDirection()).equals(targetPos)) {
                baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_RIGHT, true);
                lastPlaceAttemptPos = targetPos;
                lastPlaceAttemptTick = now;
                actionCooldown = Baritone.settings().humanMode.value ? (4 + (int) ((now ^ targetPos.asLong()) & 1L)) : 2;
            }
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
        List<Entity> holograms = new ArrayList<>();
        for (Entity e : ctx.entities()) {
            if (e == null || !e.isAlive()) continue;
            String typeId = BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).getPath();
            if ("armor_stand".equals(typeId) || "text_display".equals(typeId)) {
                String txt = BedwarsArenaMemory.stripColors(e.getName().getString()).trim();
                if (!txt.isBlank() && !txt.equalsIgnoreCase("Armor Stand")) {
                    holograms.add(e);
                }
            }
        }
        Entity best = null;
        double bestScore = Double.POSITIVE_INFINITY;
        for (Entity e : ctx.entities()) {
            if (!(e instanceof LivingEntity) || e == me || !e.isAlive()) {
                continue;
            }
            String typeId = BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).getPath();
            boolean isArmorStand = "armor_stand".equals(typeId);
            boolean isVillager = "villager".equals(typeId) || "wandering_trader".equals(typeId) || "blaze".equals(typeId);
            boolean isNpcPlayer = (e instanceof Player pl) && (pl.getUUID().version() == 2 || (!pl.isAlliedTo(me) && BedwarsArenaMemory.stripColors(pl.getName().getString()).toUpperCase(Locale.ROOT).contains("NPC")));
            StringBuilder labelSb = new StringBuilder(BedwarsArenaMemory.stripColors(e.getName().getString()));
            if (!isArmorStand) {
                for (Entity h : holograms) {
                    double hdx = h.getX() - e.getX();
                    double hdz = h.getZ() - e.getZ();
                    double hdy = h.getY() - e.getY();
                    if (hdx * hdx + hdz * hdz <= 1.8 && hdy >= -0.3 && hdy <= 3.1) {
                        labelSb.append(' ').append(BedwarsArenaMemory.stripColors(h.getName().getString()));
                    }
                }
            }
            String cleanName = labelSb.toString().toUpperCase(Locale.ROOT);
            boolean namedShop = cleanName.contains("SHOP") || cleanName.contains("UPGRADE")
                    || cleanName.contains("QUICK BUY") || cleanName.contains("PERK");
            if (!isVillager && !isNpcPlayer && !namedShop) {
                continue;
            }
            if (e instanceof Player pl && isEnemyBedwarsPlayer(me, pl) && !namedShop && pl.getUUID().version() != 2) {
                continue;
            }
            double distSq = me.distanceToSqr(e);
            if (distSq > 4096.0) {
                continue;
            }
            if (isBlacklistedShopPos(e.blockPosition())) {
                continue;
            }
            double penalty = isArmorStand ? 250.0 : 0.0;
            MenuKind learned = getLearnedRoleNear(e.blockPosition());
            if (learned != null && learned != MenuKind.UNKNOWN) {
                boolean learnedUpg = learned == MenuKind.UPGRADE_SHOP;
                penalty += (wantUpgrades == learnedUpg) ? -2500.0 : 2500.0;
            } else {
                String poiRole = findShopPoiRoleNear(core, e.blockPosition());
                boolean matchesUpgrades = cleanName.contains("UPGRADE") || cleanName.contains("PERK")
                        || cleanName.contains("TEAM SHOP") || "UPGRADES".equals(poiRole);
                boolean matchesItemShop = ((cleanName.contains("SHOP") || cleanName.contains("QUICK BUY")) && !matchesUpgrades)
                        || "ITEM_SHOP".equals(poiRole);
                if (wantUpgrades) {
                    penalty += matchesUpgrades ? -900.0 : (matchesItemShop ? 900.0 : 400.0);
                } else {
                    penalty += matchesUpgrades ? 900.0 : (matchesItemShop ? -900.0 : 0.0);
                }
            }
            double score = distSq + penalty;
            if (score < bestScore) {
                bestScore = score;
                best = e;
            }
        }
        return best;
    }

    private boolean isBlacklistedShopPos(BlockPos pos) {
        if (pos == null || wrongNpcThisTrip.isEmpty()) {
            return false;
        }
        for (long packed : wrongNpcThisTrip) {
            if (BlockPos.of(packed).distSqr(pos) <= 6.25) {
                return true;
            }
        }
        return false;
    }

    private MenuKind getLearnedRoleNear(BlockPos pos) {
        if (pos == null || learnedNpcRoles.isEmpty()) {
            return null;
        }
        MenuKind closestKind = null;
        double closestDistSq = 6.25;
        for (Map.Entry<Long, MenuKind> entry : learnedNpcRoles.entrySet()) {
            double dSq = BlockPos.of(entry.getKey()).distSqr(pos);
            if (dSq <= closestDistSq) {
                closestDistSq = dSq;
                closestKind = entry.getValue();
            }
        }
        return closestKind;
    }

    private String findShopPoiRoleNear(BedwarsMapCore core, BlockPos pos) {
        if (core == null || pos == null) {
            return null;
        }
        String bestRole = null;
        double bestDistSq = 16.0;
        for (ShopNPC npc : core.getShopNpcs()) {
            double dSq = pos.distSqr(new BlockPos(npc.x(), npc.y(), npc.z()));
            if (dSq <= bestDistSq) {
                bestDistSq = dSq;
                bestRole = BedwarsMapCore.classifyShopNpcRole(npc.label());
            }
        }
        return bestRole;
    }

    private BlockPos findBestShopPos(BedwarsMapCore core, Entity shopEntity, boolean wantUpgrades) {
        if (shopEntity != null && !isBlacklistedShopPos(shopEntity.blockPosition())) {
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
            if (isBlacklistedShopPos(p)) {
                continue;
            }
            MenuKind learned = getLearnedRoleNear(p);
            double penalty;
            if (learned != null && learned != MenuKind.UNKNOWN) {
                penalty = (wantUpgrades == (learned == MenuKind.UPGRADE_SHOP)) ? -2500.0 : 2500.0;
            } else {
                String role = BedwarsMapCore.classifyShopNpcRole(npc.label());
                boolean isUpg = "UPGRADES".equals(role) || npc.label().toUpperCase(Locale.ROOT).contains("UPGRADE");
                boolean isItem = "ITEM_SHOP".equals(role);
                if (wantUpgrades) {
                    penalty = isUpg ? -900.0 : (isItem ? 900.0 : 300.0);
                } else {
                    penalty = isUpg ? 900.0 : (isItem ? -900.0 : 0.0);
                }
            }
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
        BlockPos anchor = null;
        for (GenPOI g : core.getGens()) {
            if (g.type() == GenType.HOME_FORGE) {
                anchor = new BlockPos(g.x(), g.y(), g.z());
                break;
            }
        }
        if (anchor == null) {
            for (SpawnPOI sp : core.getSpawns()) {
                if (sp.team() == core.homeTeam()) {
                    anchor = new BlockPos(sp.x(), sp.y(), sp.z());
                    break;
                }
            }
        }
        if (anchor == null && ctx.player() != null) {
            anchor = ctx.playerFeet();
        }
        BedPOI best = null;
        double bestScore = Double.POSITIVE_INFINITY;
        for (BedPOI b : core.getBeds()) {
            if (!b.isHome()) continue;
            double score = b.destroyed() ? 1_000_000.0 : 0.0;
            if (anchor != null) {
                score += anchor.distSqr(new BlockPos(b.x(), b.y(), b.z()));
            }
            if (score < bestScore) {
                bestScore = score;
                best = b;
            }
        }
        return best;
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
        BedwarsMapCore core = baritone.getBedwarsArenaMemory().getActiveCore();
        return BedwarsTacticalHelper.computeBedShellCoverageRatio(
                bed.x(), bed.y(), bed.z(),
                bed.footX(), bed.footY(), bed.footZ(),
                (x, y, z) -> !ctx.world().getBlockState(new BlockPos(x, y, z)).canBeReplaced()
                        || (core != null && core.isBlockedForBedDefense(x, y, z))
        );
    }

    /**
     * Evaluates Bedwars tactical priority adjustment for candidate combat targets (lower = higher
     * priority): prioritizes home-bed rushers and defenders belonging to a living enemy bed ahead
     * of third-party enemies on the same island.
     */
    public double resolveCombatPriorityAdjustment(LivingEntity candidate) {
        LocalPlayer me = ctx.player();
        if (me == null || !(candidate instanceof Player other)) {
            return 0.0;
        }
        BedwarsArenaMemory memory = baritone.getBedwarsArenaMemory();
        BedwarsMapCore core = memory.getActiveCore();
        TeamColor homeTeam = core != null && core.homeTeam() != TeamColor.UNKNOWN
                ? core.homeTeam()
                : memory.getMatchState().getHomeTeam();
        TeamColor candidateTeam = resolveEntityBedwarsTeam(other, memory);
        if (homeTeam != TeamColor.UNKNOWN && candidateTeam == homeTeam) {
            return 100.0;
        }

        BedPOI homeBed = findHomeBed(core);
        boolean homeAlive = homeBed != null && !homeBed.destroyed();
        double distToHomeBed = homeBed != null
                ? Math.sqrt(other.distanceToSqr(homeBed.x() + 0.5, homeBed.y() + 0.5, homeBed.z() + 0.5))
                : Double.POSITIVE_INFINITY;

        BedPOI focusedBed = findNearestLivingEnemyBed();
        boolean focusedAlive = focusedBed != null && !focusedBed.destroyed();
        TeamColor focusedTeam = focusedBed != null ? focusedBed.team() : TeamColor.UNKNOWN;
        double distToFocusedBed = focusedBed != null
                ? Math.sqrt(other.distanceToSqr(focusedBed.x() + 0.5, focusedBed.y() + 0.5, focusedBed.z() + 0.5))
                : Double.POSITIVE_INFINITY;

        boolean defenderPresent = false;
        double nearestDefDist = Double.POSITIVE_INFINITY;
        int nearestDefHurt = 0;
        if (focusedAlive && focusedTeam != TeamColor.UNKNOWN) {
            for (Entity e : ctx.entities()) {
                if (!(e instanceof Player pl) || pl == me || !pl.isAlive() || pl.isSpectator()) {
                    continue;
                }
                TeamColor t = resolveEntityBedwarsTeam(pl, memory);
                if (t != focusedTeam) {
                    continue;
                }
                double bedD = Math.sqrt(pl.distanceToSqr(focusedBed.x() + 0.5, focusedBed.y() + 0.5, focusedBed.z() + 0.5));
                if (bedD <= 18.0) {
                    defenderPresent = true;
                    double meD = Math.max(0.0, me.distanceTo(pl) - 0.6);
                    if (meD < nearestDefDist) {
                        nearestDefDist = meD;
                        nearestDefHurt = pl.hurtTime;
                    }
                }
            }
        }

        double boxDist = Math.max(0.0, me.distanceTo(other) - 0.6);
        Vec3 myVel = me.getDeltaMovement();
        Vec3 otherVel = other.getDeltaMovement();
        double dx = other.getX() - me.getX();
        double dz = other.getZ() - me.getZ();
        double horiz = Math.max(1e-4, Math.hypot(dx, dz));
        double closing = -(((otherVel.x - myVel.x) * (dx / horiz)) + ((otherVel.z - myVel.z) * (dz / horiz))) / 0.45;

        return BedwarsTacticalHelper.computeContextualTargetScoreAdjustment(new CombatTargetContext(
                candidateTeam,
                homeTeam,
                homeAlive,
                distToHomeBed,
                focusedTeam,
                focusedAlive,
                distToFocusedBed,
                boxDist,
                closing,
                other.hurtTime,
                defenderPresent,
                nearestDefDist,
                nearestDefHurt
        ));
    }

    private static TeamColor resolveEntityBedwarsTeam(Player player, BedwarsArenaMemory memory) {
        if (player == null) {
            return TeamColor.UNKNOWN;
        }
        if (memory != null && player.getGameProfile() != null) {
            TeamColor byRoster = memory.getMatchState().getPlayerTeam(player.getGameProfile().name());
            if (byRoster != TeamColor.UNKNOWN) {
                return byRoster;
            }
        }
        if (player.getTeam() != null) {
            var optColor = player.getTeam().getColor();
            if (optColor.isPresent()) {
                TeamColor mapped = mapMcTeamColor(optColor.get().name());
                if (mapped != TeamColor.UNKNOWN) {
                    return mapped;
                }
            }
            TeamColor byTeamName = TeamColor.fromName(player.getTeam().getName());
            if (byTeamName != TeamColor.UNKNOWN) {
                return byTeamName;
            }
        }
        ItemStack chest = player.getItemBySlot(EquipmentSlot.CHEST);
        if (!chest.isEmpty()) {
            int rgb = DyedItemColor.getOrDefault(chest, -1);
            if (rgb != -1) {
                TeamColor byChest = BedwarsTacticalHelper.inferTeamColorFromDyeRgb(rgb);
                if (byChest != TeamColor.UNKNOWN) {
                    return byChest;
                }
            }
        }
        ItemStack helm = player.getItemBySlot(EquipmentSlot.HEAD);
        if (!helm.isEmpty()) {
            int rgb = DyedItemColor.getOrDefault(helm, -1);
            if (rgb != -1) {
                return BedwarsTacticalHelper.inferTeamColorFromDyeRgb(rgb);
            }
        }
        return TeamColor.UNKNOWN;
    }

    private static TeamColor mapMcTeamColor(String raw) {
        if (raw == null || raw.isBlank()) {
            return TeamColor.UNKNOWN;
        }
        String u = raw.trim().toUpperCase(Locale.ROOT);
        return switch (u) {
            case "RED", "DARK_RED" -> TeamColor.RED;
            case "BLUE", "DARK_BLUE" -> TeamColor.BLUE;
            case "GREEN", "DARK_GREEN" -> TeamColor.GREEN;
            case "YELLOW", "GOLD" -> TeamColor.YELLOW;
            case "AQUA", "DARK_AQUA" -> TeamColor.AQUA;
            case "WHITE" -> TeamColor.WHITE;
            case "LIGHT_PURPLE", "DARK_PURPLE" -> TeamColor.PINK;
            case "GRAY", "DARK_GRAY" -> TeamColor.GRAY;
            default -> TeamColor.fromName(u);
        };
    }

    private boolean tryEngageBridgeOrCloseThreat() {
        LocalPlayer me = ctx.player();
        if (me == null || ctx.world() == null) {
            return false;
        }
        Player nearest = null;
        double bestDist = 99.0;
        for (Entity e : ctx.entities()) {
            if (e instanceof Player pl && isEnemyBedwarsPlayer(me, pl)) {
                double d = me.distanceTo(pl);
                if (d < bestDist) {
                    bestDist = d;
                    nearest = pl;
                }
            }
        }
        if (nearest == null || bestDist > 18.0) {
            return false;
        }
        Vec3 pos = me.position();
        Vec3 myVel = me.getDeltaMovement();
        Vec3 theirVel = nearest.getDeltaMovement();
        double dx = nearest.getX() - pos.x;
        double dz = nearest.getZ() - pos.z;
        double horiz = Math.max(1e-4, Math.hypot(dx, dz));
        double closing = -(((theirVel.x - myVel.x) * (dx / horiz)) + ((theirVel.z - myVel.z) * (dz / horiz))) / 0.45;
        double voidFrac = BrainContextExtractor.probeVoidSurroundFraction(ctx.world(), pos);
        double frontVoid = BrainContextExtractor.probeFrontVoidEdgeDist(ctx.world(), pos, me.getYRot());
        double absDy = Math.abs(nearest.getY() - me.getY());
        if (!shouldInterceptFieldThreat(bestDist, closing, voidFrac, frontVoid, absDy)) {
            return false;
        }
        baritone.getLookBehavior().setBridgeAnchorMode(false);
        baritone.getInputOverrideHandler().clearAllKeys();
        if (baritone.getCombatProcess() instanceof CombatProcess cp && !cp.isActive()) {
            boolean onBridge = voidFrac >= 0.25 || frontVoid < 4.5;
            cp.setBrainCombatMode(onBridge ? CombatMode.VOID_KNOCKBACK : CombatMode.BALANCED);
            cp.setContextualPriorityResolver(this::resolveCombatPriorityAdjustment);
            cp.attackTemporary(e -> isEnemyBedwarsPlayer(ctx.player(), e) && ctx.player() != null && ctx.player().distanceTo(e) <= 18.0, 14);
            return true;
        }
        return false;
    }

    /**
     * Pure tactical check: returns {@code true} when an approaching/nearby enemy should interrupt
     * manual bridging or island movement to fight instead of getting knocked into the void.
     */
    public static boolean shouldInterceptFieldThreat(
            double threatDist,
            double closingSpeed,
            double voidSurroundFrac,
            double frontVoidEdgeDist,
            double absDy
    ) {
        if (absDy > 7.5) {
            return false;
        }
        if (threatDist <= 9.0) {
            return true;
        }
        boolean exposedOnBridge = voidSurroundFrac >= 0.25 || frontVoidEdgeDist < 4.5;
        return exposedOnBridge && (threatDist <= 14.5 || (threatDist <= 17.5 && closingSpeed >= 0.04));
    }

    private double findNearestEnemyDistance() {
        LocalPlayer me = ctx.player();
        if (me == null) return 99.0;
        double bestSq = 99.0 * 99.0;
        List<Player> enemies = baritone.getBedwarsArenaMemory().getTickContext().enemies();
        for (int i = 0, n = enemies.size(); i < n; i++) {
            double dSq = me.distanceToSqr(enemies.get(i));
            if (dSq < bestSq) {
                bestSq = dSq;
            }
        }
        return Math.sqrt(bestSq);
    }

    private boolean hasNearbyEnemyWithin(double radius) {
        LocalPlayer me = ctx.player();
        if (me == null) return false;
        double rSq = radius * radius;
        List<Player> enemies = baritone.getBedwarsArenaMemory().getTickContext().enemies();
        for (int i = 0, n = enemies.size(); i < n; i++) {
            if (me.distanceToSqr(enemies.get(i)) <= rSq) {
                return true;
            }
        }
        return false;
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
