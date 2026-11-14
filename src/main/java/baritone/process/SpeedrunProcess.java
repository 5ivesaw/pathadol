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

package baritone.process;

import baritone.Baritone;
import baritone.api.BaritoneAPI;
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.pathing.goals.GoalNear;
import baritone.api.pathing.goals.GoalRunAway;
import baritone.api.pathing.goals.GoalXZ;
import baritone.human.SpeedrunTaskTree.ThreatContext;
import baritone.human.SpeedrunTaskTree.ThreatMode;
import net.minecraft.world.entity.monster.Creeper;
import java.util.ArrayList;
import baritone.api.process.PathingCommand;
import baritone.api.process.PathingCommandType;
import baritone.api.utils.IPlayerContext;
import baritone.api.utils.Rotation;
import baritone.api.utils.RotationUtils;
import baritone.api.utils.input.Input;
import baritone.human.CraftingHelper;
import baritone.human.CraftingHelper.RecipeKind;
import baritone.human.CraftingHelper.StepResult;
import baritone.human.MouseModel;
import baritone.human.SpeedrunTaskTree;
import baritone.human.SpeedrunTaskTree.InventorySnapshot;
import baritone.human.SpeedrunTaskTree.TaskAction;
import baritone.human.SpeedrunTaskTree.TaskResolution;
import baritone.pathing.movement.MovementHelper;
import baritone.utils.BaritoneProcessHelper;
import baritone.utils.BlockStateInterface;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.AreaEffectCloud;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.projectile.EyeOfEnder;
import net.minecraft.world.inventory.AbstractFurnaceMenu;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.SwingAnimation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EndPortalFrameBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Autonomous Any% Ender Dragon speedrun orchestrator following an AltoClef-inspired hierarchical
 * milestone pipeline while delegating locomotion, mining, exploration, and combat to Baritone's engines.
 */
public final class SpeedrunProcess extends BaritoneProcessHelper {

    public enum Stage {
        WOOD("Gather Wood & Table"),
        STONE_AND_FOOD("Stone Tools, Furnace & Food"),
        IRON_AND_BUCKET("Iron Gear, Water Bucket & Flint"),
        NETHER_PORTAL("Build / Light Nether Portal"),
        NETHER_BLAZES("Fortress Blaze Rods"),
        NETHER_PEARLS("Ender Pearls & Return"),
        STRONGHOLD_FIND("Eyes Triangulation & Stronghold"),
        END_DRAGON("End Crystals & Ender Dragon"),
        COMPLETED("Speedrun Complete");

        public final String label;

        Stage(String label) {
            this.label = label;
        }
    }

    public record StrongholdEstimate(double x, double z, boolean valid) {}

    public record ResourceSnapshot(
            boolean inNether,
            boolean inEnd,
            int woodLogsAndPlanksValue,
            int cobbleCount,
            boolean hasWoodenPickOrBetter,
            boolean hasStonePickOrBetter,
            boolean hasIronPick,
            boolean hasBucketOrWaterBucket,
            boolean hasWaterBucket,
            boolean hasFlintAndSteel,
            int ironIngotsAndOre,
            int blazeRods,
            int pearlsAndEyes,
            boolean endPortalLitNearby,
            boolean dragonDead
    ) {}

    private static volatile boolean pendingAutoStart = false;

    private static final String[] LOG_BLOCK_NAMES = {
            "oak_log", "birch_log", "spruce_log", "dark_oak_log",
            "acacia_log", "jungle_log", "pale_oak_log", "cherry_log", "mangrove_log"
    };
    private static final Set<String> FOOD_MOBS = Set.of("cow", "pig", "sheep", "chicken", "rabbit");
    private static final Set<String> RANGED_OR_OUTPOST_SHOOTERS = Set.of(
            "pillager", "skeleton", "bogged", "stray", "witch", "breeze", "blaze", "ghast", "evoker", "illusioner"
    );
    private static final Set<String> HEAVY_THREAT_MOBS = Set.of(
            "vindicator", "evoker", "ravager", "piglin_brute", "warden", "elder_guardian", "witch", "enderman", "wither"
    );

    private boolean active = false;
    private Stage currentStage = Stage.WOOD;
    private long startMs = 0L;
    private long tickCounter = 0L;
    private int pendingAutoStartDelay = 0;

    // Crafting/interaction pacing & world checkpoints
    private int actionCooldown = 0;
    private String activeCombatMode = "";
    private long lastHurtGameTick = -100L;
    private int selfDefenseTicks = 0;
    private int evadeCooldownTicks = 0;
    private int evadeFleeTicks = 0;
    private int foodHuntTargetId = -1;
    private int foodHuntTicks = 0;
    private final Map<Integer, Long> blacklistedFoodMobsUntil = new HashMap<>();
    private int smeltProgressTicks = 0;
    private int bowDrawTicks = 0;
    private String activeSubTaskKey = "";
    private BlockPos placedUtilityPos = null;
    private int reclaimAttemptTicks = 0;
    private int stoneBatchTargetCobble = 0;
    private long lastWorldRepackTick = -1000L;
    private BlockPos portalOriginPos = null;
    private int portalBuildStep = 0;
    private BlockPos netherReturnPortalPos = null;

    private BlockPos pendingUtilityConfirmPos = null;
    private int pendingUtilityTicks = 0;
    private int trackedSpeedrunDropId = -1;
    private int trackedSpeedrunDropTicks = 0;
    private final Map<Integer, Long> speedrunDropBlacklistUntil = new HashMap<>();

    // Stronghold 2-throw triangulation state
    private Vec3 eyeOrigin1 = null;
    private float eyeYaw1 = Float.NaN;
    private Vec3 eyeOrigin2 = null;
    private float eyeYaw2 = Float.NaN;
    private StrongholdEstimate strongholdEstimate = null;
    private int waitingEyeTicks = 0;
    private Vec3 pendingEyeStart = null;
    private BlockPos suspectedPortalRoomPos = null;

    public SpeedrunProcess(Baritone baritone) {
        super(baritone);
    }

    public static void scheduleAutoStart(boolean enabled) {
        pendingAutoStart = enabled;
    }

    public static boolean isAutoStartScheduled() {
        return pendingAutoStart;
    }

    public void checkPendingAutoStart(IPlayerContext context) {
        if (!pendingAutoStart || context == null || context.player() == null || context.world() == null) {
            return;
        }
        if (pendingAutoStartDelay < 25) {
            pendingAutoStartDelay++;
            return;
        }
        pendingAutoStart = false;
        pendingAutoStartDelay = 0;
        start();
    }

    public void start() {
        resetRunState();
        this.pendingUtilityConfirmPos = null;
        this.pendingUtilityTicks = 0;
        this.stoneBatchTargetCobble = 0;
        this.lastWorldRepackTick = -1000L;
        this.trackedSpeedrunDropId = -1;
        this.trackedSpeedrunDropTicks = 0;
        this.speedrunDropBlacklistUntil.clear();
        this.active = true;
        this.startMs = System.currentTimeMillis();
        logDirect("[Speedrun] Starting Autonomous Any% Ender Dragon Run!");
    }

    public void cancel() {
        this.active = false;
        this.pendingUtilityConfirmPos = null;
        this.pendingUtilityTicks = 0;
        this.stoneBatchTargetCobble = 0;
        this.lastWorldRepackTick = -1000L;
        this.trackedSpeedrunDropId = -1;
        this.trackedSpeedrunDropTicks = 0;
        this.speedrunDropBlacklistUntil.clear();
        CraftingHelper.safeCloseContainer(ctx);
        cancelSubProcesses();
        baritone.getInputOverrideHandler().clearAllKeys();
    }

    public String getStatusSummary() {
        long secs = Math.max(0L, (System.currentTimeMillis() - startMs) / 1000L);
        return String.format(Locale.ROOT, "[%s] %02d:%02d", currentStage.name(), secs / 60L, secs % 60L);
    }

    public Stage getCurrentStage() {
        return currentStage;
    }

    @Override
    public boolean isActive() {
        return active;
    }

    @Override
    public double priority() {
        return 10.0;
    }

    @Override
    public boolean isTemporary() {
        return true;
    }

    @Override
    public void onLostControl() {
        cancel();
    }

    @Override
    public String displayName0() {
        return "Speedrun (" + currentStage.name() + ")";
    }

    @Override
    public PathingCommand onTick(boolean calcFailed, boolean isSafeToCancel) {
        if (!active || ctx.player() == null || ctx.world() == null) {
            return null;
        }
        baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_RIGHT, false);
        tickCounter++;
        if (actionCooldown > 0) {
            actionCooldown--;
        }
        if (evadeCooldownTicks > 0) {
            evadeCooldownTicks--;
        }
        if (ctx.player().hurtTime > 0) {
            lastHurtGameTick = tickCounter;
        }
        if (pendingUtilityConfirmPos != null) {
            Block b = ctx.world().getBlockState(pendingUtilityConfirmPos).getBlock();
            if (b == Blocks.CRAFTING_TABLE || b == Blocks.FURNACE) {
                placedUtilityPos = pendingUtilityConfirmPos;
                pendingUtilityConfirmPos = null;
                pendingUtilityTicks = 0;
            } else if (++pendingUtilityTicks > 10) {
                pendingUtilityConfirmPos = null;
                pendingUtilityTicks = 0;
            }
        }

        // Let survival emergencies (MLG clutch, auto-eat, fire quench) take precedence cleanly
        if (baritone.getSurvivalBehavior() != null && baritone.getSurvivalBehavior().isUsingSurvivalReflex()) {
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }

        InventorySnapshot invSnapshot = buildInventorySnapshot();
        TaskResolution resolution = SpeedrunTaskTree.resolveAnyPercent(invSnapshot);
        int remainingStoneNeed = SpeedrunTaskTree.computeRemainingStoneBatchRequirement(invSnapshot);
        if (remainingStoneNeed <= 0
                || !invSnapshot.hasAnyPickaxe()
                || invSnapshot.tableScreenOpen()
                || invSnapshot.cobblestone() >= stoneBatchTargetCobble
                || (calcFailed && invSnapshot.cobblestone() >= remainingStoneNeed)
                || resolution.action() == TaskAction.DONE) {
            stoneBatchTargetCobble = 0;
        }
        if (resolution.action() == TaskAction.MINE_COBBLESTONE && remainingStoneNeed > 0
                && stoneBatchTargetCobble < remainingStoneNeed) {
            stoneBatchTargetCobble = remainingStoneNeed + 5;
        }
        if (stoneBatchTargetCobble > 0
                && invSnapshot.cobblestone() < stoneBatchTargetCobble
                && !invSnapshot.tableScreenOpen()
                && resolution.action() != TaskAction.PICKUP_DROP
                && resolution.action() != TaskAction.MINE_LOGS
                && resolution.action() != TaskAction.CRAFT_PLANKS
                && resolution.action() != TaskAction.CRAFT_STICKS
                && resolution.action() != TaskAction.CRAFT_TABLE) {
            resolution = new TaskResolution(
                    TaskAction.MINE_COBBLESTONE,
                    invSnapshot.hasStonePickOrBetter()
                            ? resolution.goal()
                            : SpeedrunTaskTree.GoalTarget.STONE_PICKAXE,
                    "Gather stone batch with escape buffer (" + invSnapshot.cobblestone() + "/" + stoneBatchTargetCobble + ")"
            );
        }

        if (resolution.action() == TaskAction.DONE || currentStage == Stage.COMPLETED) {
            long elapsedSec = Math.max(1L, (System.currentTimeMillis() - startMs) / 1000L);
            logDirect(String.format(Locale.ROOT, "[Speedrun COMPLETE] Ender Dragon defeated in %02d:%02d!", elapsedSec / 60L, elapsedSec % 60L));
            cancel();
            return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
        }

        // Keep legacy stage indicator synchronized cleanly from inventory truth
        Stage inferredStage = mapActionToStage(resolution.action(), invSnapshot);
        if (inferredStage != currentStage) {
            onStageTransition(currentStage, inferredStage);
            currentStage = inferredStage;
        }

        // Constrained threat evaluation: bare-handed, low-HP, outnumbered, or outpost threats trigger GoalRunAway evasion instead of suiciding
        boolean suppressOverworldThreat = invSnapshot.inNether() || invSnapshot.inEnd()
                || resolution.action() == TaskAction.HUNT_BLAZES
                || resolution.action() == TaskAction.HUNT_PEARLS
                || resolution.action() == TaskAction.RETURN_FROM_NETHER
                || resolution.action() == TaskAction.END_CRYSTALS_AND_DRAGON
                || (resolution.action() == TaskAction.NETHER_PORTAL_AND_ENTER
                && CraftingHelper.findNearbyBlock(ctx, b -> b == Blocks.NETHER_PORTAL, 6) != null);
        if (!suppressOverworldThreat) {
            PathingCommand threatCommand = evaluateAndExecuteThreatResponse(calcFailed);
            if (threatCommand != null) {
                return threatCommand;
            }
        } else {
            selfDefenseTicks = 0;
            evadeFleeTicks = 0;
        }
        if (baritone.getCombatProcess().isActive()
                && ("self_defend".equals(activeCombatMode)
                || (resolution.action() != TaskAction.HUNT_BLAZES
                && resolution.action() != TaskAction.HUNT_PEARLS
                && resolution.action() != TaskAction.END_CRYSTALS_AND_DRAGON
                && resolution.action() != TaskAction.GATHER_FOOD))) {
            baritone.getCombatProcess().cancel();
            activeCombatMode = "";
        }

        return executeTaskTreeAction(resolution, invSnapshot);
    }

    /**
     * Pure milestone evaluator separating stage transition logic cleanly for headless JUnit verification.
     */
    public static Stage recommendStageFromCounts(ResourceSnapshot r) {
        if (r.dragonDead()) {
            return Stage.COMPLETED;
        }
        if (r.inEnd()) {
            return Stage.END_DRAGON;
        }
        if (r.inNether()) {
            if (r.blazeRods() < 7) {
                return Stage.NETHER_BLAZES;
            }
            if (r.pearlsAndEyes() < 12) {
                return Stage.NETHER_PEARLS;
            }
            return Stage.NETHER_PEARLS;
        }
        if (r.endPortalLitNearby() || (r.blazeRods() * 2 >= 12 && r.pearlsAndEyes() >= 12)
                || r.pearlsAndEyes() >= 12 && hasAtLeastEyeComponents(r)) {
            return Stage.STRONGHOLD_FIND;
        }
        if (!r.hasWoodenPickOrBetter()) {
            return Stage.WOOD;
        }
        if (!r.hasStonePickOrBetter()) {
            return Stage.STONE_AND_FOOD;
        }
        if (!r.hasIronPick() || !r.hasBucketOrWaterBucket() || !r.hasFlintAndSteel()) {
            return Stage.IRON_AND_BUCKET;
        }
        if (r.blazeRods() < 7) {
            return Stage.NETHER_PORTAL;
        }
        return Stage.STRONGHOLD_FIND;
    }

    private static boolean hasAtLeastEyeComponents(ResourceSnapshot r) {
        return r.blazeRods() >= 6 && r.pearlsAndEyes() >= 12;
    }

    /**
     * Triangulates two Eye of Ender ray observations (position + Minecraft yaw deg) into Stronghold XZ coordinates.
     * In Minecraft, yaw=0 faces +Z and yaw=-90 faces +X, so unit direction = (-sin(yaw), cos(yaw)).
     */
    public static StrongholdEstimate triangulateStronghold(
            double x1,
            double z1,
            float yaw1Deg,
            double x2,
            double z2,
            float yaw2Deg
    ) {
        double r1 = Math.toRadians(yaw1Deg);
        double r2 = Math.toRadians(yaw2Deg);
        double dx1 = -Math.sin(r1);
        double dz1 = Math.cos(r1);
        double dx2 = -Math.sin(r2);
        double dz2 = Math.cos(r2);

        double det = dx1 * (-dz2) - dz1 * (-dx2);
        if (Math.abs(det) < 0.06) {
            return new StrongholdEstimate(x2 + dx2 * 800.0, z2 + dz2 * 800.0, false);
        }
        double rx = x2 - x1;
        double rz = z2 - z1;
        double t1 = (rx * (-dz2) - rz * (-dx2)) / det;
        double t2 = (dx1 * rz - dz1 * rx) / det;
        if (t1 < 20.0 || t2 < 20.0 || t1 > 4500.0 || t2 > 4500.0) {
            return new StrongholdEstimate(x2 + dx2 * 700.0, z2 + dz2 * 700.0, false);
        }
        return new StrongholdEstimate(x1 + t1 * dx1, z1 + t1 * dz1, true);
    }

    private static int countAccessibleItem(LocalPlayer p, net.minecraft.world.item.Item item) {
        return CraftingHelper.countAccessibleMatching(p, st -> st.is(item));
    }

    private InventorySnapshot buildInventorySnapshot() {
        LocalPlayer p = ctx.player();
        var dim = ctx.world().dimension();
        boolean inNether = dim == Level.NETHER;
        boolean inEnd = dim == Level.END;
        int logs = CraftingHelper.countAccessibleMatching(p, st -> CraftingHelper.isLogItem(st.getItem()));
        int planks = CraftingHelper.countAccessibleMatching(p, st -> CraftingHelper.isPlankItem(st.getItem()));
        int sticks = countAccessibleItem(p, Items.STICK);
        int tablesInv = countAccessibleItem(p, Items.CRAFTING_TABLE);
        boolean tableNearby = CraftingHelper.findNearbyBlock(ctx, b -> b == Blocks.CRAFTING_TABLE, 5) != null;
        boolean tableOpen = p.containerMenu instanceof CraftingMenu;
        int furnacesInv = countAccessibleItem(p, Items.FURNACE);
        boolean furnaceNearby = CraftingHelper.findNearbyBlock(ctx, b -> b == Blocks.FURNACE, 6) != null;
        boolean furnaceOpen = p.containerMenu instanceof AbstractFurnaceMenu;
        int cobble = CraftingHelper.countAccessibleMatching(p, st -> CraftingHelper.isCobbleMaterial(st.getItem()));
        boolean hasWoodPick = CraftingHelper.countAccessibleMatching(p, st -> isPickaxeAtLeast(st, false, false)) > 0;
        boolean hasStonePick = CraftingHelper.countAccessibleMatching(p, st -> isPickaxeAtLeast(st, true, false)) > 0;
        boolean hasIronPick = CraftingHelper.countAccessibleMatching(p, st -> isPickaxeAtLeast(st, true, true)) > 0;
        boolean hasStoneAxe = CraftingHelper.countAccessibleMatching(p, st -> st.is(Items.STONE_AXE)) > 0;
        boolean hasIronAxe = CraftingHelper.countAccessibleMatching(p, st -> st.is(Items.IRON_AXE) || st.is(Items.DIAMOND_AXE)) > 0;
        boolean hasStoneSword = CraftingHelper.countAccessibleMatching(p, st -> st.is(Items.STONE_SWORD)) > 0;
        boolean hasIronSword = CraftingHelper.countAccessibleMatching(p, st -> st.is(Items.IRON_SWORD) || st.is(Items.DIAMOND_SWORD)) > 0;
        boolean hasBucket = countAccessibleItem(p, Items.BUCKET) > 0;
        boolean hasWaterBucket = countAccessibleItem(p, Items.WATER_BUCKET) > 0;
        boolean hasLavaBucket = countAccessibleItem(p, Items.LAVA_BUCKET) > 0;
        int ingots = countAccessibleItem(p, Items.IRON_INGOT);
        int rawIron = countAccessibleItem(p, Items.RAW_IRON) + countAccessibleItem(p, Items.IRON_ORE);
        int coal = countAccessibleItem(p, Items.COAL) + countAccessibleItem(p, Items.CHARCOAL);
        int flint = countAccessibleItem(p, Items.FLINT);
        boolean hasFlintSteel = countAccessibleItem(p, Items.FLINT_AND_STEEL) > 0;
        int nutrition = countEdibleNutrition(p);
        int blazeRods = countAccessibleItem(p, Items.BLAZE_ROD);
        int blazePowder = countAccessibleItem(p, Items.BLAZE_POWDER);
        int pearls = countAccessibleItem(p, Items.ENDER_PEARL);
        int eyes = countAccessibleItem(p, Items.ENDER_EYE);
        boolean netherPortalLit = !inEnd && CraftingHelper.findNearbyBlock(ctx, b -> b == Blocks.NETHER_PORTAL, 16) != null;
        boolean endPortalLit = !inNether && !inEnd && CraftingHelper.findNearbyBlock(ctx, b -> b == Blocks.END_PORTAL, 18) != null;
        boolean dragonDead = (currentStage == Stage.END_DRAGON && !inEnd)
                || (inEnd && currentStage == Stage.END_DRAGON && isDragonDefeatedAndExitReady());

        Screen activeScreen = (ctx.minecraft() != null && ctx.minecraft().gui != null)
                ? ctx.minecraft().gui.screen()
                : null;
        boolean inUiSession = activeScreen instanceof AbstractContainerScreen<?>
                || CraftingHelper.hasCarriedOrGridItems(p)
                || tableOpen
                || furnaceOpen;
        boolean matchingDropNearby = !inUiSession
                && !baritone.getMineProcess().isActive()
                && !baritone.getInputOverrideHandler().getBlockBreakHelper().isHitting()
                && findNearestSpeedrunDrop(p, 10.5) != null;

        return new InventorySnapshot(
                logs,
                planks,
                sticks,
                tablesInv,
                tableNearby,
                tableOpen,
                furnacesInv,
                furnaceNearby,
                furnaceOpen,
                cobble,
                hasWoodPick,
                hasStonePick,
                hasStoneAxe,
                hasStoneSword,
                hasIronPick,
                hasIronAxe,
                hasIronSword,
                hasBucket,
                hasWaterBucket,
                hasLavaBucket,
                ingots,
                rawIron,
                coal,
                flint,
                hasFlintSteel,
                nutrition,
                blazeRods,
                blazePowder,
                pearls,
                eyes,
                inNether,
                inEnd,
                netherPortalLit,
                endPortalLit,
                dragonDead,
                matchingDropNearby
        );
    }

    private static Stage mapActionToStage(TaskAction action, InventorySnapshot inv) {
        if (inv.dragonDead() || action == TaskAction.DONE) {
            return Stage.COMPLETED;
        }
        if (inv.inEnd() || action == TaskAction.END_CRYSTALS_AND_DRAGON) {
            return Stage.END_DRAGON;
        }
        if (inv.inNether()) {
            return action == TaskAction.HUNT_BLAZES ? Stage.NETHER_BLAZES : Stage.NETHER_PEARLS;
        }
        return switch (action) {
            case MINE_LOGS, CRAFT_PLANKS, CRAFT_STICKS, CRAFT_TABLE, PLACE_OR_OPEN_TABLE, CRAFT_WOODEN_PICKAXE ->
                    !inv.hasAnyPickaxe() ? Stage.WOOD : Stage.STONE_AND_FOOD;
            case MINE_COBBLESTONE, CRAFT_STONE_PICKAXE, CRAFT_STONE_AXE, CRAFT_STONE_SWORD, CRAFT_FURNACE, GATHER_FOOD ->
                    Stage.STONE_AND_FOOD;
            case MINE_IRON_ORE, MINE_COAL_FUEL, SMELT_IRON, CRAFT_IRON_PICKAXE, CRAFT_BUCKET, MINE_FLINT, CRAFT_FLINT_AND_STEEL, FILL_WATER_BUCKET ->
                    Stage.IRON_AND_BUCKET;
            case NETHER_PORTAL_AND_ENTER -> Stage.NETHER_PORTAL;
            case HUNT_BLAZES -> Stage.NETHER_BLAZES;
            case HUNT_PEARLS, RETURN_FROM_NETHER -> Stage.NETHER_PEARLS;
            case CRAFT_BLAZE_POWDER, CRAFT_ENDER_EYES, STRONGHOLD_AND_ENTER_END -> Stage.STRONGHOLD_FIND;
            case END_CRYSTALS_AND_DRAGON -> Stage.END_DRAGON;
            default -> Stage.WOOD;
        };
    }

    private PathingCommand executeTaskTreeAction(TaskResolution res, InventorySnapshot inv) {
        LocalPlayer p = ctx.player();
        return switch (res.action()) {
            case PICKUP_DROP -> {
                if (!CraftingHelper.safeCloseContainer(ctx)) {
                    yield new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
                }
                ItemEntity drop = findNearestSpeedrunDrop(p, 11.5);
                if (drop == null) {
                    trackedSpeedrunDropId = -1;
                    trackedSpeedrunDropTicks = 0;
                    yield new PathingCommand(null, PathingCommandType.DEFER);
                }
                cancelSubProcesses();
                long nowTick = ctx.world() != null ? ctx.world().getGameTime() : 0L;
                if (trackedSpeedrunDropId == drop.getId()) {
                    trackedSpeedrunDropTicks++;
                } else {
                    trackedSpeedrunDropId = drop.getId();
                    trackedSpeedrunDropTicks = 0;
                }
                if (trackedSpeedrunDropTicks > 65) {
                    speedrunDropBlacklistUntil.put(drop.getId(), nowTick + 160L);
                    trackedSpeedrunDropId = -1;
                    trackedSpeedrunDropTicks = 0;
                    yield new PathingCommand(null, PathingCommandType.DEFER);
                }
                double distSq = p.position().distanceToSqr(drop.position());
                if (distSq <= 9.0) {
                    Rotation lookDrop = RotationUtils.calcRotationFromVec3d(
                            ctx.playerHead(),
                            drop.position().add(0.0, 0.15, 0.0),
                            ctx.playerRotations()
                    );
                    baritone.getLookBehavior().updateTarget(lookDrop, false);
                }
                BlockPos dropPos = drop.blockPosition();
                if (dropPos.equals(ctx.playerFeet()) && Math.abs(drop.getY() - p.getY()) <= 1.25 && distSq > 0.25) {
                    baritone.getInputOverrideHandler().clearAllKeys();
                    Rotation lookDrop = RotationUtils.calcRotationFromVec3d(
                            ctx.playerHead(),
                            drop.position().add(0.0, 0.15, 0.0),
                            ctx.playerRotations()
                    );
                    baritone.getLookBehavior().updateTarget(lookDrop, true);
                    baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_FORWARD, true);
                    yield new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
                }
                yield new PathingCommand(new GoalBlock(dropPos), PathingCommandType.SET_GOAL_AND_PATH);
            }
            case MINE_LOGS -> {
                if (!CraftingHelper.safeCloseContainer(ctx)) {
                    yield new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
                }
                int desiredLogs = Math.max(inv.logs() + 1, !inv.hasAnyPickaxe() ? 4 : 3);
                yield runMineTask("wood_logs", desiredLogs, LOG_BLOCK_NAMES);
            }
            case CRAFT_PLANKS -> executeInventoryCraftOnly(RecipeKind.PLANKS_FROM_LOG);
            case CRAFT_STICKS -> executeInventoryCraftOnly(RecipeKind.STICKS);
            case CRAFT_TABLE -> executeInventoryCraftOnly(RecipeKind.CRAFTING_TABLE);
            case PLACE_OR_OPEN_TABLE -> {
                cancelSubProcesses();
                yield openOrPlaceCraftingTable();
            }
            case CRAFT_WOODEN_PICKAXE -> executeTableCraft(RecipeKind.WOODEN_PICKAXE);
            case MINE_COBBLESTONE -> {
                // Hard tool-tier guard: NEVER mine stone/cobble without a wooden+ pickaxe in inventory
                if (CraftingHelper.countAccessibleMatching(p, st -> isPickaxeAtLeast(st, false, false)) < 1) {
                    yield executeTaskTreeAction(SpeedrunTaskTree.resolveGoal(SpeedrunTaskTree.GoalTarget.WOODEN_PICKAXE, inv), inv);
                }
                if (!ensureOnHotbar(st -> isPickaxeAtLeast(st, false, false), 8)) {
                    yield new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
                }
                if (!CraftingHelper.safeCloseContainer(ctx)) {
                    yield new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
                }
                int batchNeed = SpeedrunTaskTree.computeRemainingStoneBatchRequirement(inv);
                int targetCobble = batchNeed > 0
                        ? Math.max(batchNeed + 5, Math.max(stoneBatchTargetCobble, inv.cobblestone() + 1))
                        : Math.max(inv.cobblestone() + 1, 10);
                yield runMineTask("stone_cobble", targetCobble, "stone", "cobblestone", "cobbled_deepslate");
            }
            case CRAFT_STONE_PICKAXE -> executeTableCraft(RecipeKind.STONE_PICKAXE);
            case CRAFT_STONE_AXE -> executeTableCraft(RecipeKind.STONE_AXE);
            case CRAFT_STONE_SWORD -> executeTableCraft(RecipeKind.STONE_SWORD);
            case CRAFT_FURNACE -> executeTableCraft(RecipeKind.FURNACE);
            case GATHER_FOOD -> {
                LivingEntity animal = findNearestEntity(
                        e -> e instanceof LivingEntity le && le.isAlive()
                                && FOOD_MOBS.contains(entityId(e))
                                && !isFoodMobBlacklisted(e.getId()),
                        24.0
                );
                if (animal != null) {
                    if (foodHuntTargetId == animal.getId()) {
                        foodHuntTicks++;
                    } else {
                        foodHuntTargetId = animal.getId();
                        foodHuntTicks = 0;
                    }
                    if (foodHuntTicks <= 140) {
                        if (!CraftingHelper.safeCloseContainer(ctx)) {
                            yield new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
                        }
                        activateCombatFilter("food_hunt", e -> e.equals(animal));
                        yield new PathingCommand(null, PathingCommandType.DEFER);
                    }
                    blacklistedFoodMobsUntil.put(animal.getId(), tickCounter + 320L);
                    foodHuntTargetId = -1;
                    foodHuntTicks = 0;
                    if ("food_hunt".equals(activeCombatMode) && baritone.getCombatProcess().isActive()) {
                        baritone.getCombatProcess().cancel();
                        activeCombatMode = "";
                    }
                }
                // No food mobs nearby right now: advance to iron ore rather than stalling indefinitely
                TaskResolution ironRes = SpeedrunTaskTree.resolveGoal(SpeedrunTaskTree.GoalTarget.IRON_PICKAXE, inv);
                if (ironRes.action() != TaskAction.GATHER_FOOD && ironRes.action() != TaskAction.DONE) {
                    yield executeTaskTreeAction(ironRes, inv);
                }
                yield runExploreTask("find_food_or_iron");
            }
            case MINE_IRON_ORE -> {
                // Hard tool-tier guard: NEVER mine iron ore without a stone+ pickaxe in inventory
                if (CraftingHelper.countAccessibleMatching(p, st -> isPickaxeAtLeast(st, true, false)) < 1) {
                    yield executeTaskTreeAction(SpeedrunTaskTree.resolveGoal(SpeedrunTaskTree.GoalTarget.STONE_PICKAXE, inv), inv);
                }
                if (!ensureOnHotbar(st -> isPickaxeAtLeast(st, true, false), 8)) {
                    yield new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
                }
                if (!CraftingHelper.safeCloseContainer(ctx)) {
                    yield new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
                }
                PathingCommand reclaim = tryReclaimPlacedTable(inv);
                if (reclaim != null) {
                    yield reclaim;
                }
                yield runMineTask("iron_ore", Math.max(4, inv.rawIron() + 2), "iron_ore", "deepslate_iron_ore");
            }
            case MINE_COAL_FUEL -> {
                if (CraftingHelper.countAccessibleMatching(p, st -> isPickaxeAtLeast(st, false, false)) < 1) {
                    yield executeTaskTreeAction(SpeedrunTaskTree.resolveGoal(SpeedrunTaskTree.GoalTarget.WOODEN_PICKAXE, inv), inv);
                }
                if (!ensureOnHotbar(st -> isPickaxeAtLeast(st, false, false), 8)) {
                    yield new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
                }
                if (!CraftingHelper.safeCloseContainer(ctx)) {
                    yield new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
                }
                yield runMineTask("coal_fuel", Math.max(4, inv.coal() + 2), "coal_ore", "deepslate_coal_ore");
            }
            case SMELT_IRON -> executeFurnaceSmelt(
                    st -> st.is(Items.RAW_IRON) || st.is(Items.IRON_ORE),
                    st -> st.is(Items.COAL) || st.is(Items.CHARCOAL) || CraftingHelper.isPlankItem(st.getItem()) || CraftingHelper.isLogItem(st.getItem())
            );
            case CRAFT_IRON_PICKAXE -> executeTableCraft(RecipeKind.IRON_PICKAXE);
            case CRAFT_BUCKET -> executeTableCraft(RecipeKind.BUCKET);
            case MINE_FLINT -> {
                if (!CraftingHelper.safeCloseContainer(ctx)) {
                    yield new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
                }
                yield runMineTask("flint_gravel", Math.max(4, inv.flint() + 1), "gravel");
            }
            case CRAFT_FLINT_AND_STEEL -> executeInventoryCraftOnly(RecipeKind.FLINT_AND_STEEL);
            case FILL_WATER_BUCKET -> {
                CraftingHelper.safeCloseContainer(ctx);
                yield fillWaterBucketStep();
            }
            case NETHER_PORTAL_AND_ENTER -> {
                if (!CraftingHelper.safeCloseContainer(ctx)) {
                    yield new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
                }
                PathingCommand reclaim = tryReclaimPlacedTable(inv);
                if (reclaim != null) {
                    yield reclaim;
                }
                yield tickNetherPortalStage();
            }
            case HUNT_BLAZES -> {
                CraftingHelper.safeCloseContainer(ctx);
                yield tickNetherBlazesStage();
            }
            case HUNT_PEARLS, RETURN_FROM_NETHER -> {
                CraftingHelper.safeCloseContainer(ctx);
                yield tickNetherPearlsStage();
            }
            case CRAFT_BLAZE_POWDER -> executeInventoryCraftOnly(RecipeKind.BLAZE_POWDER);
            case CRAFT_ENDER_EYES -> executeInventoryCraftOnly(RecipeKind.ENDER_EYE);
            case STRONGHOLD_AND_ENTER_END -> {
                CraftingHelper.safeCloseContainer(ctx);
                yield tickStrongholdStage();
            }
            case END_CRYSTALS_AND_DRAGON -> {
                CraftingHelper.safeCloseContainer(ctx);
                yield tickEndDragonStage();
            }
            case RECLAIM_CRAFTING_TABLE, DONE -> new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
        };
    }

    private PathingCommand executeInventoryCraftOnly(RecipeKind recipe) {
        cancelSubProcesses();
        LocalPlayer p = ctx.player();
        if (p.containerMenu != p.inventoryMenu && !(p.containerMenu instanceof CraftingMenu)) {
            if (!CraftingHelper.safeCloseContainer(ctx)) {
                return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
            }
        }
        return executeCraftStep(recipe);
    }

    private PathingCommand openOrPlaceCraftingTable() {
        LocalPlayer p = ctx.player();
        if (p.containerMenu instanceof CraftingMenu) {
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }
        if (p.containerMenu != p.inventoryMenu) {
            CraftingHelper.safeCloseContainer(ctx);
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }
        BlockPos tablePos = CraftingHelper.findNearbyBlock(ctx, b -> b == Blocks.CRAFTING_TABLE, 5);
        if (tablePos == null) {
            if (CraftingHelper.countAccessibleMatching(p, st -> st.is(Items.CRAFTING_TABLE)) < 1) {
                return executeInventoryCraftOnly(RecipeKind.CRAFTING_TABLE);
            }
            return placeUtilityBlockNearby(Items.CRAFTING_TABLE);
        }
        if (!CraftingHelper.safeCloseContainer(ctx)) {
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }
        return interactWithBlockStep(tablePos);
    }

    private static boolean isPickaxeAtLeast(ItemStack st, boolean stoneOrBetter, boolean ironOrBetter) {
        if (st.is(Items.DIAMOND_PICKAXE) || st.is(Items.NETHERITE_PICKAXE) || st.is(Items.IRON_PICKAXE)) {
            return true;
        }
        if (!ironOrBetter && (st.is(Items.STONE_PICKAXE) || st.is(Items.GOLDEN_PICKAXE))) {
            return true;
        }
        return !stoneOrBetter && !ironOrBetter && st.is(Items.WOODEN_PICKAXE);
    }

    private PathingCommand tickNetherPortalStage() {
        // Look for already-lit Nether Portal nearby
        BlockPos portalBlock = CraftingHelper.findNearbyBlock(ctx, b -> b == Blocks.NETHER_PORTAL, 36);
        if (portalBlock != null) {
            cancelSubProcesses();
            return new PathingCommand(new GoalBlock(portalBlock), PathingCommandType.SET_GOAL_AND_PATH);
        }

        // Look for an obsidian bottom frame to ignite
        BlockPos obsidianNear = CraftingHelper.findNearbyBlock(ctx, b -> b == Blocks.OBSIDIAN, 24);
        if (obsidianNear != null && isBottomPortalCandidate(obsidianNear)) {
            BlockPos airInside = obsidianNear.above();
            if (ctx.playerFeet().distSqr(airInside) > 9.0) {
                return new PathingCommand(new GoalNear(airInside, 2), PathingCommandType.SET_GOAL_AND_PATH);
            }
            if (selectHotbarOrSwap(st -> st.is(Items.FLINT_AND_STEEL))) {
                Rotation rot = RotationUtils.calcRotationFromVec3d(ctx.playerHead(), Vec3.atCenterOf(obsidianNear).add(0, 0.48, 0), ctx.playerRotations());
                ((baritone.behavior.LookBehavior) baritone.getLookBehavior()).updateTarget(rot, true, true);
                baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_RIGHT, true);
                return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
            }
        }

        // Otherwise locate a surface lava pool and speedrun bucket-cast a 4x5 Obsidian frame
        BlockPos lavaPool = CraftingHelper.findNearbyBlock(ctx, b -> b == Blocks.LAVA, 40);
        if (lavaPool == null) {
            return runExploreTask("find_lava_pool");
        }
        cancelSubProcesses();
        if (portalOriginPos == null) {
            portalOriginPos = chooseSafeFlatPortalOrigin(lavaPool);
            portalBuildStep = 0;
        }
        return runPortalLavaCastStep(lavaPool);
    }

    private PathingCommand tickNetherBlazesStage() {
        if (netherReturnPortalPos == null) {
            BlockPos portal = CraftingHelper.findNearbyBlock(ctx, b -> b == Blocks.NETHER_PORTAL, 16);
            netherReturnPortalPos = portal != null ? portal : ctx.playerFeet();
        }
        LivingEntity blaze = findNearestEntity(e -> e instanceof LivingEntity && "blaze".equals(entityId(e)), 30.0);
        if (blaze != null) {
            activateCombatFilter("nether_blaze", e -> "blaze".equals(entityId(e)) || (e instanceof Enemy && e.distanceTo(ctx.player()) < 10.0));
            return new PathingCommand(null, PathingCommandType.DEFER);
        }
        BlockPos spawnerOrFortress = CraftingHelper.findNearbyBlock(
                ctx,
                b -> b == Blocks.SPAWNER || b == Blocks.NETHER_BRICKS || b == Blocks.NETHER_BRICK_FENCE,
                48
        );
        if (spawnerOrFortress != null) {
            return new PathingCommand(new GoalNear(spawnerOrFortress, 3), PathingCommandType.SET_GOAL_AND_PATH);
        }
        return runExploreTask("find_fortress");
    }

    private PathingCommand tickNetherPearlsStage() {
        LocalPlayer p = ctx.player();
        int pearlsAndEyes = CraftingHelper.countItem(p, Items.ENDER_PEARL) + CraftingHelper.countItem(p, Items.ENDER_EYE);
        if (pearlsAndEyes >= 12) {
            BlockPos portal = CraftingHelper.findNearbyBlock(ctx, b -> b == Blocks.NETHER_PORTAL, 40);
            BlockPos target = portal != null ? portal : netherReturnPortalPos;
            if (target != null) {
                cancelSubProcesses();
                return new PathingCommand(new GoalBlock(target), PathingCommandType.SET_GOAL_AND_PATH);
            }
            return runExploreTask("find_return_portal");
        }

        LivingEntity enderman = findNearestEntity(e -> e instanceof LivingEntity && "enderman".equals(entityId(e)), 30.0);
        if (enderman != null) {
            activateCombatFilter("nether_pearl", e -> "enderman".equals(entityId(e)) || (e instanceof Enemy && e.distanceTo(ctx.player()) < 8.0));
            return new PathingCommand(null, PathingCommandType.DEFER);
        }
        BlockPos warpedBiome = CraftingHelper.findNearbyBlock(ctx, b -> b == Blocks.WARPED_NYLIUM || b == Blocks.WARPED_WART_BLOCK, 48);
        if (warpedBiome != null) {
            return new PathingCommand(new GoalNear(warpedBiome, 4), PathingCommandType.SET_GOAL_AND_PATH);
        }
        return runExploreTask("find_warped_forest");
    }

    private PathingCommand tickStrongholdStage() {
        LocalPlayer p = ctx.player();
        BlockPos endPortal = CraftingHelper.findNearbyBlock(ctx, b -> b == Blocks.END_PORTAL, 20);
        if (endPortal != null) {
            cancelSubProcesses();
            return new PathingCommand(new GoalBlock(endPortal), PathingCommandType.SET_GOAL_AND_PATH);
        }

        BlockPos emptyFrame = CraftingHelper.findNearbyBlock(
                ctx,
                b -> b == Blocks.END_PORTAL_FRAME,
                20
        );
        if (emptyFrame != null) {
            BlockState frameState = ctx.world().getBlockState(emptyFrame);
            if (frameState.getBlock() instanceof EndPortalFrameBlock && !frameState.getValue(EndPortalFrameBlock.HAS_EYE)) {
                if (CraftingHelper.countItem(p, Items.ENDER_EYE) > 0) {
                    if (ctx.playerFeet().distSqr(emptyFrame) > 9.0) {
                        return new PathingCommand(new GoalNear(emptyFrame, 2), PathingCommandType.SET_GOAL_AND_PATH);
                    }
                    if (selectHotbarOrSwap(st -> st.is(Items.ENDER_EYE))) {
                        Rotation rot = RotationUtils.calcRotationFromVec3d(ctx.playerHead(), Vec3.atCenterOf(emptyFrame), ctx.playerRotations());
                        ((baritone.behavior.LookBehavior) baritone.getLookBehavior()).updateTarget(rot, true, true);
                        baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_RIGHT, true);
                        return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
                    }
                }
            }
        }

        // Craft blaze powder + Eyes of Ender before throwing
        int eyes = CraftingHelper.countItem(p, Items.ENDER_EYE);
        int pearls = CraftingHelper.countItem(p, Items.ENDER_PEARL);
        int powder = CraftingHelper.countItem(p, Items.BLAZE_POWDER);
        int rods = CraftingHelper.countItem(p, Items.BLAZE_ROD);
        if (powder < pearls && rods > 0) {
            return executeCraftStep(RecipeKind.BLAZE_POWDER);
        }
        if (eyes < 12 && pearls > 0 && powder > 0) {
            return executeCraftStep(RecipeKind.ENDER_EYE);
        }

        // Sample flying EyeOfEnder if recently thrown
        if (waitingEyeTicks > 0) {
            waitingEyeTicks--;
            EyeOfEnder eyeEntity = findNearestEyeEntity();
            if (eyeEntity != null && pendingEyeStart != null) {
                double dx = eyeEntity.getX() - pendingEyeStart.x;
                double dz = eyeEntity.getZ() - pendingEyeStart.z;
                if (Math.hypot(dx, dz) > 1.2) {
                    float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
                    if (eyeOrigin1 == null) {
                        eyeOrigin1 = pendingEyeStart;
                        eyeYaw1 = yaw;
                        logDirect(String.format(Locale.ROOT, "[Speedrun] Eye #1 bearing %.1f deg — shifting offset to triangulate", yaw));
                    } else {
                        eyeOrigin2 = pendingEyeStart;
                        eyeYaw2 = yaw;
                        strongholdEstimate = triangulateStronghold(
                                eyeOrigin1.x, eyeOrigin1.z, eyeYaw1,
                                eyeOrigin2.x, eyeOrigin2.z, eyeYaw2
                        );
                        logDirect(String.format(Locale.ROOT, "[Speedrun] Stronghold triangulated near X=%.0f Z=%.0f", strongholdEstimate.x(), strongholdEstimate.z()));
                    }
                    waitingEyeTicks = 0;
                    pendingEyeStart = null;
                }
            }
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }

        if (strongholdEstimate == null) {
            if (eyeOrigin1 == null && eyes > 0) {
                return throwEyeStep();
            }
            double perpRad = Math.toRadians(eyeYaw1 + 90.0);
            double targetX = eyeOrigin1.x + (-Math.sin(perpRad)) * 180.0;
            double targetZ = eyeOrigin1.z + Math.cos(perpRad) * 180.0;
            double distToSecondThrow = Math.hypot(p.getX() - targetX, p.getZ() - targetZ);
            if (distToSecondThrow > 18.0) {
                return new PathingCommand(new GoalXZ((int) Math.round(targetX), (int) Math.round(targetZ)), PathingCommandType.SET_GOAL_AND_PATH);
            }
            return throwEyeStep();
        }

        double distEst = Math.hypot(p.getX() - strongholdEstimate.x(), p.getZ() - strongholdEstimate.z());
        if (distEst > 22.0) {
            return new PathingCommand(new GoalXZ((int) Math.round(strongholdEstimate.x()), (int) Math.round(strongholdEstimate.z())), PathingCommandType.SET_GOAL_AND_PATH);
        }
        // At triangulated chunk: descend to stronghold height y=24 looking for End Portal Room
        if (p.getY() > 42.0) {
            return new PathingCommand(new GoalBlock((int) Math.round(strongholdEstimate.x()), 28, (int) Math.round(strongholdEstimate.z())), PathingCommandType.SET_GOAL_AND_PATH);
        }
        return runExploreTask("stronghold_portal_room");
    }

    private PathingCommand tickEndDragonStage() {
        LocalPlayer p = ctx.player();
        // Dodge Acid breath clouds cleanly
        for (Entity e : (Iterable<Entity>) ctx.entitiesStream()::iterator) {
            if (e instanceof AreaEffectCloud && e.distanceTo(p) < 4.5) {
                BlockPos escape = new BlockPos(0, ctx.playerFeet().y, 0);
                return new PathingCommand(new GoalNear(escape, 6), PathingCommandType.SET_GOAL_AND_PATH);
            }
        }

        // Priority 1: Destroy remaining End Crystals (Bow shot first if clear LOS, otherwise pillar/break)
        EndCrystal nearestCrystal = findNearestEndCrystal();
        if (nearestCrystal != null) {
            double dist = p.distanceTo(nearestCrystal);
            if (dist <= 48.0 && p.hasLineOfSight(nearestCrystal) && hasBowAndArrows(p)) {
                if (selectHotbarOrSwap(st -> st.is(Items.BOW))) {
                    Vec3 eye = ctx.playerHead();
                    double dx = nearestCrystal.getX() - eye.x;
                    double dy = (nearestCrystal.getY() + 0.6) - eye.y;
                    double dz = nearestCrystal.getZ() - eye.z;
                    float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
                    float pitch = CombatProcess.computeBowPitchDegrees(Math.hypot(dx, dz), dy, 2.85);
                    ((baritone.behavior.LookBehavior) baritone.getLookBehavior()).updateTarget(new Rotation(yaw, pitch), true, true);
                    if (bowDrawTicks < 20) {
                        bowDrawTicks++;
                        baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_RIGHT, true);
                    } else {
                        bowDrawTicks = 0;
                    }
                    return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
                }
            }
            if (dist <= 4.2) {
                Rotation rot = RotationUtils.calcRotationFromVec3d(ctx.playerHead(), nearestCrystal.position().add(0, 0.5, 0), ctx.playerRotations());
                ((baritone.behavior.LookBehavior) baritone.getLookBehavior()).updateTarget(rot, true, true);
                ctx.minecraft().gameMode.attack(p, nearestCrystal);
                p.swing(net.minecraft.world.InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, false);
                return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
            }
            return new PathingCommand(new GoalNear(nearestCrystal.blockPosition(), 3), PathingCommandType.SET_GOAL_AND_PATH);
        }
        bowDrawTicks = 0;

        // Priority 2: Attack perched / swooping Ender Dragon around center island fountain (0, y, 0)
        EnderDragon dragon = findEnderDragon();
        if (dragon != null) {
            activateCombatFilter(e -> e.equals(dragon));
            if (dragon.distanceTo(p) <= 12.0) {
                return new PathingCommand(null, PathingCommandType.DEFER);
            }
        }
        BlockPos fountainPerch = new BlockPos(0, Math.max(60, ctx.playerFeet().y), 0);
        return new PathingCommand(new GoalNear(fountainPerch, 4), PathingCommandType.SET_GOAL_AND_PATH);
    }

    private PathingCommand tryReclaimPlacedTable(InventorySnapshot inv) {
        if (placedUtilityPos == null || inv.craftingTablesInInv() > 0) {
            reclaimAttemptTicks = 0;
            return null;
        }
        if (!ctx.world().getBlockState(placedUtilityPos).is(Blocks.CRAFTING_TABLE)) {
            placedUtilityPos = null;
            reclaimAttemptTicks = 0;
            return null;
        }
        if (ctx.playerFeet().distSqr(placedUtilityPos) > 20.0 || reclaimAttemptTicks > 40) {
            placedUtilityPos = null;
            reclaimAttemptTicks = 0;
            return null;
        }
        reclaimAttemptTicks++;
        if (ctx.playerFeet().distSqr(placedUtilityPos) > 9.0) {
            return new PathingCommand(new GoalNear(placedUtilityPos, 2), PathingCommandType.SET_GOAL_AND_PATH);
        }
        baritone.getInputOverrideHandler().clearMovementKeys();
        selectHotbarOrSwap(st -> st.is(Items.IRON_AXE) || st.is(Items.STONE_AXE) || st.is(Items.WOODEN_AXE));
        Optional<Rotation> rot = RotationUtils.reachable(ctx, placedUtilityPos);
        if (rot.isPresent()) {
            ((baritone.behavior.LookBehavior) baritone.getLookBehavior()).updateTarget(rot.get(), true, true);
            if (ctx.isLookingAt(placedUtilityPos) || MouseModel.angularDistance(ctx.playerRotations(), rot.get()) <= 6.0f) {
                baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_LEFT, true);
            }
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }
        placedUtilityPos = null;
        reclaimAttemptTicks = 0;
        return null;
    }

    private PathingCommand runMineTask(String key, int qty, String... blockNames) {
        if (!key.equals(activeSubTaskKey) || !baritone.getMineProcess().isActive()) {
            cancelSubProcesses();
            activeSubTaskKey = key;
            if (lastWorldRepackTick < 0L || (tickCounter - lastWorldRepackTick) >= 400L) {
                BaritoneAPI.getProvider().getWorldScanner().repack(ctx);
                lastWorldRepackTick = tickCounter;
            }
            baritone.getMineProcess().mineByName(qty, blockNames);
        }
        return new PathingCommand(null, PathingCommandType.DEFER);
    }

    private PathingCommand runExploreTask(String key) {
        if (!key.equals(activeSubTaskKey) || !baritone.getExploreProcess().isActive()) {
            cancelSubProcesses();
            activeSubTaskKey = key;
            baritone.getExploreProcess().explore(ctx.playerFeet().x, ctx.playerFeet().z);
        }
        return new PathingCommand(null, PathingCommandType.DEFER);
    }

    private void activateCombatFilter(Predicate<Entity> filter) {
        activateCombatFilter("default", filter);
    }

    private void activateCombatFilter(String modeTag, Predicate<Entity> filter) {
        if (!baritone.getCombatProcess().isActive() || !modeTag.equals(this.activeCombatMode)) {
            this.activeCombatMode = modeTag;
            baritone.getCombatProcess().attack(filter);
        }
    }

    private void cancelSubProcesses() {
        activeSubTaskKey = "";
        if (baritone.getMineProcess().isActive()) {
            baritone.getMineProcess().cancel();
        }
        if (baritone.getExploreProcess().isActive()) {
            baritone.getExploreProcess().onLostControl();
        }
        if (baritone.getGetToBlockProcess().isActive()) {
            baritone.getGetToBlockProcess().onLostControl();
        }
    }

    private PathingCommand executeCraftStep(RecipeKind recipe) {
        StepResult res = CraftingHelper.stepCraft(ctx, recipe);
        if (res == StepResult.COMPLETED) {
            actionCooldown = 2;
        }
        return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
    }

    private PathingCommand executeTableCraft(RecipeKind recipe) {
        if (ctx.player().containerMenu instanceof CraftingMenu) {
            return executeCraftStep(recipe);
        }
        return openOrPlaceCraftingTable();
    }

    private PathingCommand executeFurnaceSmelt(Predicate<ItemStack> inPred, Predicate<ItemStack> fuelPred) {
        if (ctx.player().containerMenu instanceof AbstractFurnaceMenu) {
            CraftingHelper.stepFurnaceSmelt(ctx, inPred, fuelPred);
            smeltProgressTicks++;
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }
        BlockPos furnacePos = CraftingHelper.findNearbyBlock(ctx, b -> b == Blocks.FURNACE, 6);
        if (furnacePos == null) {
            if (CraftingHelper.countAccessibleMatching(ctx.player(), st -> st.is(Items.FURNACE)) < 1) {
                return executeTableCraft(RecipeKind.FURNACE);
            }
            return placeUtilityBlockNearby(Items.FURNACE);
        }
        if (!CraftingHelper.safeCloseContainer(ctx)) {
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }
        return interactWithBlockStep(furnacePos);
    }

    private PathingCommand placeUtilityBlockNearby(net.minecraft.world.item.Item utilityItem) {
        baritone.getInputOverrideHandler().clearMovementKeys();
        BlockPos feet = ctx.playerFeet();
        BlockPos chosenFloor = null;
        Rotation chosenRot = null;
        int[][] offsets = {{1, 0}, {0, 1}, {-1, 0}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}, {2, 0}, {0, 2}};
        double reach = ctx.playerController().getBlockReachDistance();
        for (int[] off : offsets) {
            BlockPos cand = feet.offset(off[0], 0, off[1]);
            BlockPos base = cand.below();
            if (ctx.world().getBlockState(cand).canBeReplaced()
                    && MovementHelper.canWalkOn(ctx, base)) {
                Optional<Rotation> rot = RotationUtils.reachableOffset(ctx, base, Vec3.atCenterOf(base).add(0.0, 0.5, 0.0), reach, false);
                if (rot.isPresent()) {
                    chosenFloor = cand;
                    chosenRot = rot.get();
                    break;
                }
            }
        }
        if (chosenFloor == null) {
            return new PathingCommand(new GoalNear(feet.offset(2, 0, 2), 1), PathingCommandType.SET_GOAL_AND_PATH);
        }
        if (!selectHotbarOrSwap(st -> st.is(utilityItem))) {
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }
        if (!CraftingHelper.safeCloseContainer(ctx)) {
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }
        ((baritone.behavior.LookBehavior) baritone.getLookBehavior()).updateTarget(chosenRot, true, true);
        BlockPos supportBase = chosenFloor.below();
        boolean aligned = ctx.isLookingAt(supportBase) || MouseModel.angularDistance(ctx.playerRotations(), chosenRot) <= 3.0f;
        if (actionCooldown == 0 && aligned && baritone.getInventoryBehavior().isHotbarReadyForAction()) {
            baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_RIGHT, true);
            pendingUtilityConfirmPos = chosenFloor;
            pendingUtilityTicks = 0;
            actionCooldown = 3;
        }
        return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
    }

    private PathingCommand interactWithBlockStep(BlockPos pos) {
        if (ctx.playerFeet().distSqr(pos) > 12.25) {
            return new PathingCommand(new GoalNear(pos, 2), PathingCommandType.SET_GOAL_AND_PATH);
        }
        baritone.getInputOverrideHandler().clearMovementKeys();
        Optional<Rotation> rotOpt = RotationUtils.reachable(ctx, pos);
        Rotation rot = rotOpt.orElseGet(() -> RotationUtils.calcRotationFromVec3d(ctx.playerHead(), Vec3.atCenterOf(pos), ctx.playerRotations()));
        ((baritone.behavior.LookBehavior) baritone.getLookBehavior()).updateTarget(rot, true, true);
        if (actionCooldown == 0 && (ctx.isLookingAt(pos) || MouseModel.angularDistance(ctx.playerRotations(), rot) <= 4.5f)) {
            baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_RIGHT, true);
            actionCooldown = 5;
        }
        return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
    }

    private PathingCommand fillWaterBucketStep() {
        BlockPos water = CraftingHelper.findNearbyBlock(ctx, b -> b == Blocks.WATER, 32);
        if (water == null) {
            return runExploreTask("find_water");
        }
        if (ctx.playerFeet().distSqr(water) > 12.25) {
            return new PathingCommand(new GoalNear(water, 2), PathingCommandType.SET_GOAL_AND_PATH);
        }
        if (selectHotbarOrSwap(st -> st.is(Items.BUCKET))) {
            Rotation rot = RotationUtils.calcRotationFromVec3d(ctx.playerHead(), Vec3.atCenterOf(water), ctx.playerRotations());
            ((baritone.behavior.LookBehavior) baritone.getLookBehavior()).updateTarget(rot, true, true);
            baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_RIGHT, true);
            actionCooldown = 4;
        }
        return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
    }

    private PathingCommand runPortalLavaCastStep(BlockPos lavaPool) {
        if (ctx.playerFeet().distSqr(lavaPool) > 25.0) {
            return new PathingCommand(new GoalNear(lavaPool, 3), PathingCommandType.SET_GOAL_AND_PATH);
        }
        // Minimal 10-obsidian cast sequence relative to portalOriginPos (facing North/South along X)
        BlockPos[] obsidianTargets = {
                portalOriginPos.offset(1, 0, 0),
                portalOriginPos.offset(2, 0, 0),
                portalOriginPos.offset(0, 1, 0),
                portalOriginPos.offset(0, 2, 0),
                portalOriginPos.offset(0, 3, 0),
                portalOriginPos.offset(3, 1, 0),
                portalOriginPos.offset(3, 2, 0),
                portalOriginPos.offset(3, 3, 0),
                portalOriginPos.offset(1, 4, 0),
                portalOriginPos.offset(2, 4, 0)
        };
        while (portalBuildStep < obsidianTargets.length
                && ctx.world().getBlockState(obsidianTargets[portalBuildStep]).is(Blocks.OBSIDIAN)) {
            portalBuildStep++;
        }
        if (portalBuildStep >= obsidianTargets.length) {
            if (selectHotbarOrSwap(st -> st.is(Items.FLINT_AND_STEEL))) {
                Rotation rot = RotationUtils.calcRotationFromVec3d(
                        ctx.playerHead(),
                        Vec3.atCenterOf(obsidianTargets[0]).add(0, 0.5, 0),
                        ctx.playerRotations()
                );
                ((baritone.behavior.LookBehavior) baritone.getLookBehavior()).updateTarget(rot, true, true);
                baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_RIGHT, true);
            }
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }

        BlockPos targetObs = obsidianTargets[portalBuildStep];
        if (ctx.playerFeet().distSqr(targetObs) > 14.0) {
            return new PathingCommand(new GoalNear(targetObs, 2), PathingCommandType.SET_GOAL_AND_PATH);
        }
        boolean hasLava = CraftingHelper.countItem(ctx.player(), Items.LAVA_BUCKET) > 0;
        if (!hasLava) {
            BlockPos sourceLava = findSourceLavaNear(lavaPool);
            if (sourceLava == null) {
                portalOriginPos = null;
                return runExploreTask("next_lava_pool");
            }
            if (ctx.playerFeet().distSqr(sourceLava) > 14.0) {
                return new PathingCommand(new GoalNear(sourceLava, 2), PathingCommandType.SET_GOAL_AND_PATH);
            }
            if (selectHotbarOrSwap(st -> st.is(Items.BUCKET))) {
                Rotation rot = RotationUtils.calcRotationFromVec3d(ctx.playerHead(), Vec3.atCenterOf(sourceLava), ctx.playerRotations());
                ((baritone.behavior.LookBehavior) baritone.getLookBehavior()).updateTarget(rot, true, true);
                baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_RIGHT, true);
                actionCooldown = 3;
            }
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }

        // Ensure water adjacent to targetObs then pour lava into targetObs to harden into obsidian
        BlockPos waterMoldPos = targetObs.offset(0, 0, 1);
        if (!ctx.world().getBlockState(waterMoldPos).is(Blocks.WATER) && selectHotbarOrSwap(st -> st.is(Items.WATER_BUCKET))) {
            Rotation rot = RotationUtils.calcRotationFromVec3d(ctx.playerHead(), Vec3.atCenterOf(waterMoldPos), ctx.playerRotations());
            ((baritone.behavior.LookBehavior) baritone.getLookBehavior()).updateTarget(rot, true, true);
            baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_RIGHT, true);
            actionCooldown = 3;
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }
        if (selectHotbarOrSwap(st -> st.is(Items.LAVA_BUCKET))) {
            Rotation rot = RotationUtils.calcRotationFromVec3d(ctx.playerHead(), Vec3.atCenterOf(targetObs), ctx.playerRotations());
            ((baritone.behavior.LookBehavior) baritone.getLookBehavior()).updateTarget(rot, true, true);
            baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_RIGHT, true);
            actionCooldown = 3;
        }
        return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
    }

    private PathingCommand throwEyeStep() {
        if (actionCooldown > 0) {
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }
        if (!selectHotbarOrSwap(st -> st.is(Items.ENDER_EYE))) {
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }
        pendingEyeStart = ctx.player().position();
        baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_RIGHT, true);
        waitingEyeTicks = 12;
        actionCooldown = 14;
        return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
    }

    private void resetRunState() {
        currentStage = Stage.WOOD;
        tickCounter = 0L;
        actionCooldown = 0;
        smeltProgressTicks = 0;
        bowDrawTicks = 0;
        activeSubTaskKey = "";
        placedUtilityPos = null;
        reclaimAttemptTicks = 0;
        portalOriginPos = null;
        portalBuildStep = 0;
        netherReturnPortalPos = null;
        eyeOrigin1 = null;
        eyeYaw1 = Float.NaN;
        eyeOrigin2 = null;
        eyeYaw2 = Float.NaN;
        strongholdEstimate = null;
        waitingEyeTicks = 0;
        pendingEyeStart = null;
        suspectedPortalRoomPos = null;
    }

    private void onStageTransition(Stage prev, Stage next) {
        cancelSubProcesses();
        if (baritone.getCombatProcess().isActive()) {
            baritone.getCombatProcess().cancel();
        }
        long secs = Math.max(0L, (System.currentTimeMillis() - startMs) / 1000L);
        logDirect(String.format(Locale.ROOT, "[Speedrun %02d:%02d] %s -> %s", secs / 60L, secs % 60L, prev.name(), next.label));
    }

    private PathingCommand evaluateAndExecuteThreatResponse(boolean calcFailed) {
        LocalPlayer p = ctx.player();
        double nearestDist = Double.POSITIVE_INFINITY;
        LivingEntity urgentHostile = null;
        boolean nearestHasLos = false;
        boolean nearestIsRanged = false;
        boolean heavyDanger = false;
        boolean swellingCreeperClose = false;
        int hostilesWithin14 = 0;
        int rangedWithin16 = 0;
        List<BlockPos> threatPositions = new ArrayList<>();

        for (Entity e : (Iterable<Entity>) ctx.entitiesStream()::iterator) {
            if (!(e instanceof LivingEntity le) || !le.isAlive() || le.equals(p) || !(e instanceof Enemy)) {
                continue;
            }
            double dist = le.distanceTo(p);
            if (dist > 20.0) {
                continue;
            }
            threatPositions.add(le.blockPosition());
            boolean hasLos = p.hasLineOfSight(le);
            String id = entityId(le);
            boolean ranged = RANGED_OR_OUTPOST_SHOOTERS.contains(id);
            boolean heavy = HEAVY_THREAT_MOBS.contains(id);
            boolean swellingCreeper = le instanceof Creeper creeper
                    && (creeper.getSwellDir() > 0 || creeper.getSwelling(0.0f) > 0.15f);
            if (dist <= 5.5 && swellingCreeper) {
                swellingCreeperClose = true;
            }
            if (dist <= 12.0 && heavy && (hasLos || (tickCounter - lastHurtGameTick) <= 35L)) {
                heavyDanger = true;
            }
            if (dist <= 16.0 && ranged) {
                rangedWithin16++;
            }
            if (dist <= 14.0) {
                hostilesWithin14++;
            }
            if (dist < nearestDist) {
                nearestDist = dist;
                urgentHostile = le;
                nearestHasLos = hasLos;
                nearestIsRanged = ranged;
            }
        }

        int weaponTier = computeHotbarWeaponTier();
        boolean hurtRecently = p.hurtTime > 0 || (tickCounter - lastHurtGameTick) <= 35L;
        ThreatMode mode = SpeedrunTaskTree.evaluateOverworldThreat(new ThreatContext(
                p.getHealth(),
                weaponTier,
                p.getArmorValue(),
                nearestDist,
                nearestHasLos,
                nearestIsRanged,
                heavyDanger || (urgentHostile != null && HEAVY_THREAT_MOBS.contains(entityId(urgentHostile))),
                swellingCreeperClose,
                hostilesWithin14,
                rangedWithin16,
                hurtRecently,
                selfDefenseTicks,
                evadeCooldownTicks
        ));

        if (mode == ThreatMode.EVADE_FLEE) {
            CraftingHelper.safeCloseContainer(ctx);
            if (selfDefenseTicks > 0 || evadeFleeTicks == 0) {
                evadeCooldownTicks = Math.max(evadeCooldownTicks, 120);
            }
            selfDefenseTicks = 0;
            evadeFleeTicks++;
            if (baritone.getCombatProcess().isActive()) {
                baritone.getCombatProcess().cancel();
                activeCombatMode = "";
            }
            cancelSubProcesses();
            if (threatPositions.isEmpty()) {
                threatPositions.add(ctx.playerFeet());
            }
            if (evadeFleeTicks == 1) {
                logDirect(String.format(
                        Locale.ROOT,
                        "[Speedrun] Evading threats (hostiles=%d, ranged=%d, weaponTier=%d, hp=%.1f)",
                        hostilesWithin14, rangedWithin16, weaponTier, p.getHealth()
                ));
            }
            double fleeDist = calcFailed ? 16.0 : 34.0;
            return new PathingCommand(new GoalRunAway(fleeDist, threatPositions.toArray(new BlockPos[0])), PathingCommandType.SET_GOAL_AND_PATH);
        }

        if (mode == ThreatMode.SELF_DEFEND && urgentHostile != null) {
            evadeFleeTicks = 0;
            selfDefenseTicks++;
            CraftingHelper.safeCloseContainer(ctx);
            cancelSubProcesses();
            LivingEntity primary = urgentHostile;
            activateCombatFilter("self_defend", e -> (e instanceof LivingEntity le && le.equals(primary))
                    || (e instanceof Enemy && !(e instanceof Creeper) && e.distanceTo(p) <= 6.0 && p.hasLineOfSight(e)));
            return new PathingCommand(null, PathingCommandType.DEFER);
        }

        evadeFleeTicks = 0;
        selfDefenseTicks = 0;
        return null;
    }

    private int computeHotbarWeaponTier() {
        var inv = ctx.player().getInventory().getNonEquipmentItems();
        int tier = hasBowAndArrows(ctx.player()) ? 2 : 0;
        for (int i = 0; i < 9; i++) {
            ItemStack st = inv.get(i);
            if (st.isEmpty()) {
                continue;
            }
            if (st.is(Items.IRON_SWORD) || st.is(Items.IRON_AXE)
                    || st.is(Items.DIAMOND_SWORD) || st.is(Items.DIAMOND_AXE)
                    || st.is(Items.NETHERITE_SWORD) || st.is(Items.NETHERITE_AXE)) {
                return 2;
            }
            if (st.is(Items.STONE_SWORD) || st.is(Items.STONE_AXE)) {
                tier = Math.max(tier, 1);
            }
        }
        return tier;
    }

    private boolean isFoodMobBlacklisted(int entityId) {
        Long until = blacklistedFoodMobsUntil.get(entityId);
        if (until == null) {
            return false;
        }
        if (tickCounter >= until) {
            blacklistedFoodMobsUntil.remove(entityId);
            return false;
        }
        return true;
    }

    private LivingEntity findNearestEntity(Predicate<Entity> pred, double maxDist) {
        LocalPlayer p = ctx.player();
        LivingEntity best = null;
        double bestDist = maxDist;
        for (Entity e : (Iterable<Entity>) ctx.entitiesStream()::iterator) {
            if (e instanceof LivingEntity le && le.isAlive() && !le.equals(p) && pred.test(le)) {
                double d = le.distanceTo(p);
                if (d < bestDist) {
                    bestDist = d;
                    best = le;
                }
            }
        }
        return best;
    }

    private EndCrystal findNearestEndCrystal() {
        LocalPlayer p = ctx.player();
        EndCrystal best = null;
        double bestDist = Double.POSITIVE_INFINITY;
        for (Entity e : (Iterable<Entity>) ctx.entitiesStream()::iterator) {
            if (e instanceof EndCrystal ec && ec.isAlive()) {
                double d = ec.distanceTo(p);
                if (d < bestDist) {
                    bestDist = d;
                    best = ec;
                }
            }
        }
        return best;
    }

    private EnderDragon findEnderDragon() {
        for (Entity e : (Iterable<Entity>) ctx.entitiesStream()::iterator) {
            if (e instanceof EnderDragon ed && ed.isAlive()) {
                return ed;
            }
        }
        return null;
    }

    private EyeOfEnder findNearestEyeEntity() {
        LocalPlayer p = ctx.player();
        for (Entity e : (Iterable<Entity>) ctx.entitiesStream()::iterator) {
            if (e instanceof EyeOfEnder eye && eye.isAlive() && eye.distanceTo(p) < 28.0) {
                return eye;
            }
        }
        return null;
    }

    private boolean isDragonDefeatedAndExitReady() {
        return findEnderDragon() == null
                && CraftingHelper.findNearbyBlock(ctx, b -> b == Blocks.END_PORTAL, 64) != null;
    }

    private ItemEntity findNearestSpeedrunDrop(LocalPlayer p, double maxDist) {
        if (p == null || ctx.world() == null) {
            return null;
        }
        long nowTick = ctx.world().getGameTime();
        ItemEntity best = null;
        double bestDistSq = maxDist * maxDist;
        for (Entity entity : ((ClientLevel) ctx.world()).entitiesForRendering()) {
            if (!(entity instanceof ItemEntity ei) || !ei.isAlive() || ei.getItem().isEmpty()) {
                continue;
            }
            Long expiry = speedrunDropBlacklistUntil.get(ei.getId());
            if (expiry != null && nowTick < expiry) {
                continue;
            }
            BlockPos bp = ei.blockPosition();
            if (ctx.world().getBlockState(bp).is(Blocks.LAVA) || Math.abs(ei.getY() - p.getY()) > 4.5) {
                continue;
            }
            if (!isValuableSpeedrunDrop(ei.getItem())) {
                continue;
            }
            double dSq = ei.distanceToSqr(p);
            if (dSq < bestDistSq) {
                bestDistSq = dSq;
                best = ei;
            }
        }
        return best;
    }

    private static boolean isValuableSpeedrunDrop(ItemStack st) {
        if (st == null || st.isEmpty()) {
            return false;
        }
        var item = st.getItem();
        if (CraftingHelper.isLogItem(item) || CraftingHelper.isPlankItem(item) || CraftingHelper.isCobbleMaterial(item)) {
            return true;
        }
        if (st.is(Items.CRAFTING_TABLE) || st.is(Items.FURNACE) || st.is(Items.STICK)
                || st.is(Items.IRON_INGOT) || st.is(Items.RAW_IRON) || st.is(Items.IRON_ORE)
                || st.is(Items.COAL) || st.is(Items.CHARCOAL) || st.is(Items.FLINT)
                || st.is(Items.FLINT_AND_STEEL) || st.is(Items.BUCKET) || st.is(Items.WATER_BUCKET)
                || st.is(Items.BLAZE_ROD) || st.is(Items.BLAZE_POWDER) || st.is(Items.ENDER_PEARL)
                || st.is(Items.WOODEN_PICKAXE) || st.is(Items.STONE_PICKAXE) || st.is(Items.IRON_PICKAXE)
                || st.is(Items.STONE_AXE) || st.is(Items.IRON_AXE) || st.is(Items.STONE_SWORD)
                || st.is(Items.IRON_SWORD)) {
            return true;
        }
        var fp = item.components().get(DataComponents.FOOD);
        return fp != null && !st.is(Items.ROTTEN_FLESH) && !st.is(Items.SPIDER_EYE);
    }

    private boolean ensureOnHotbar(Predicate<ItemStack> pred, int preferredHotbarIndex) {
        var inv = ctx.player().getInventory().getNonEquipmentItems();
        for (int i = 0; i < 9; i++) {
            if (pred.test(inv.get(i))) {
                return true;
            }
        }
        for (int i = 9; i < inv.size(); i++) {
            if (pred.test(inv.get(i))) {
                StepResult moveRes = CraftingHelper.stepMoveToHotbar(ctx, pred, preferredHotbarIndex);
                return moveRes == StepResult.COMPLETED;
            }
        }
        return false;
    }

    private boolean selectHotbarOrSwap(Predicate<ItemStack> pred) {
        var inv = ctx.player().getInventory().getNonEquipmentItems();
        int cur = ctx.player().getInventory().getSelectedSlot();
        if (cur >= 0 && cur < 9 && pred.test(inv.get(cur))) {
            return true;
        }
        for (int i = 0; i < 9; i++) {
            if (pred.test(inv.get(i))) {
                return baritone.getInventoryBehavior().safeSelectHotbarSlot(i)
                        || ctx.player().getInventory().getSelectedSlot() == i;
            }
        }
        for (int i = 9; i < inv.size(); i++) {
            if (pred.test(inv.get(i))) {
                StepResult moveRes = CraftingHelper.stepMoveToHotbar(ctx, pred, 1);
                return moveRes == StepResult.COMPLETED;
            }
        }
        return false;
    }

    private static boolean hasItemMatching(LocalPlayer p, Predicate<ItemStack> pred) {
        for (ItemStack st : p.getInventory().getNonEquipmentItems()) {
            if (!st.isEmpty() && pred.test(st)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasBowAndArrows(LocalPlayer p) {
        return hasItemMatching(p, st -> st.is(Items.BOW))
                && hasItemMatching(p, st -> st.is(Items.ARROW) || st.is(Items.SPECTRAL_ARROW) || st.is(Items.TIPPED_ARROW));
    }

    private static int countEdibleNutrition(LocalPlayer p) {
        int sum = 0;
        for (ItemStack st : p.getInventory().getNonEquipmentItems()) {
            if (st.isEmpty()) {
                continue;
            }
            var fp = st.getItem().components().get(DataComponents.FOOD);
            if (fp != null && !st.is(Items.ROTTEN_FLESH) && !st.is(Items.SPIDER_EYE)) {
                sum += fp.nutrition() * st.getCount();
            }
        }
        return sum;
    }

    private static String entityId(Entity e) {
        return BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).getPath();
    }

    private BlockPos chooseSafeFlatPortalOrigin(BlockPos lavaPool) {
        return new BlockPos(lavaPool.getX() + 2, lavaPool.getY() + 1, lavaPool.getZ() + 2);
    }

    private boolean isBottomPortalCandidate(BlockPos obsidianPos) {
        BlockState above = ctx.world().getBlockState(obsidianPos.above());
        return above.isAir() && (ctx.world().getBlockState(obsidianPos.east()).is(Blocks.OBSIDIAN)
                || ctx.world().getBlockState(obsidianPos.south()).is(Blocks.OBSIDIAN));
    }

    private BlockPos findSourceLavaNear(BlockPos center) {
        for (int r = 0; r <= 6; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    BlockPos c = center.offset(dx, 0, dz);
                    BlockState bs = ctx.world().getBlockState(c);
                    if (bs.is(Blocks.LAVA) && bs.getFluidState().isSource()) {
                        return c;
                    }
                }
            }
        }
        return null;
    }
}
