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
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.pathing.goals.GoalNear;
import baritone.api.pathing.goals.GoalXZ;
import baritone.api.process.PathingCommand;
import baritone.api.process.PathingCommandType;
import baritone.api.utils.IPlayerContext;
import baritone.api.utils.Rotation;
import baritone.api.utils.RotationUtils;
import baritone.api.utils.input.Input;
import baritone.human.CraftingHelper;
import baritone.human.CraftingHelper.RecipeKind;
import baritone.human.CraftingHelper.StepResult;
import baritone.pathing.movement.MovementHelper;
import baritone.utils.BaritoneProcessHelper;
import baritone.utils.BlockStateInterface;
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
import java.util.List;
import java.util.Locale;
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

    private boolean active = false;
    private Stage currentStage = Stage.WOOD;
    private long startMs = 0L;
    private long tickCounter = 0L;
    private int pendingAutoStartDelay = 0;

    // Crafting/interaction pacing & world checkpoints
    private int actionCooldown = 0;
    private int smeltProgressTicks = 0;
    private int bowDrawTicks = 0;
    private String activeSubTaskKey = "";
    private BlockPos placedUtilityPos = null;
    private BlockPos portalOriginPos = null;
    private int portalBuildStep = 0;
    private BlockPos netherReturnPortalPos = null;

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
        this.active = true;
        this.startMs = System.currentTimeMillis();
        logDirect("[Speedrun] Starting Autonomous Any% Ender Dragon Run!");
    }

    public void cancel() {
        this.active = false;
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
        tickCounter++;
        if (actionCooldown > 0) {
            actionCooldown--;
        }

        // Let survival emergencies (MLG clutch, auto-eat, fire quench) take precedence cleanly
        if (baritone.getSurvivalBehavior() != null && baritone.getSurvivalBehavior().isUsingSurvivalReflex()) {
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }

        Stage nextStage = evaluateStage();
        if (nextStage != currentStage) {
            onStageTransition(currentStage, nextStage);
            currentStage = nextStage;
        }

        if (currentStage == Stage.COMPLETED) {
            long elapsedSec = Math.max(1L, (System.currentTimeMillis() - startMs) / 1000L);
            logDirect(String.format(Locale.ROOT, "[Speedrun COMPLETE] Ender Dragon defeated in %02d:%02d!", elapsedSec / 60L, elapsedSec % 60L));
            cancel();
            return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
        }

        // Defend against immediate close melee/ranged threats (hostiles, blazes, endermen when targeting pearls)
        LivingEntity urgentHostile = findUrgentThreat();
        if (urgentHostile != null && currentStage != Stage.NETHER_PORTAL) {
            activateCombatFilter(e -> e instanceof LivingEntity le && le.equals(urgentHostile)
                    || (e instanceof Enemy && e.distanceTo(ctx.player()) <= 14.0));
            return new PathingCommand(null, PathingCommandType.DEFER);
        } else if (baritone.getCombatProcess().isActive()
                && currentStage != Stage.NETHER_BLAZES
                && currentStage != Stage.NETHER_PEARLS
                && currentStage != Stage.END_DRAGON) {
            baritone.getCombatProcess().cancel();
        }

        return switch (currentStage) {
            case WOOD -> tickWoodStage();
            case STONE_AND_FOOD -> tickStoneAndFoodStage();
            case IRON_AND_BUCKET -> tickIronAndBucketStage();
            case NETHER_PORTAL -> tickNetherPortalStage();
            case NETHER_BLAZES -> tickNetherBlazesStage();
            case NETHER_PEARLS -> tickNetherPearlsStage();
            case STRONGHOLD_FIND -> tickStrongholdStage();
            case END_DRAGON -> tickEndDragonStage();
            case COMPLETED -> new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
        };
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
        if (!r.hasWoodenPickOrBetter() && r.woodLogsAndPlanksValue() < 20) {
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

    private Stage evaluateStage() {
        LocalPlayer p = ctx.player();
        var dim = ctx.world().dimension();
        boolean inNether = dim == Level.NETHER;
        boolean inEnd = dim == Level.END;
        int logs = CraftingHelper.countMatching(p, st -> CraftingHelper.isLogItem(st.getItem()));
        int planks = CraftingHelper.countMatching(p, st -> CraftingHelper.isPlankItem(st.getItem()));
        int cobble = CraftingHelper.countMatching(p, st -> CraftingHelper.isCobbleMaterial(st.getItem()));
        int blazeRods = CraftingHelper.countItem(p, Items.BLAZE_ROD);
        int blazePowder = CraftingHelper.countItem(p, Items.BLAZE_POWDER);
        int pearls = CraftingHelper.countItem(p, Items.ENDER_PEARL);
        int eyes = CraftingHelper.countItem(p, Items.ENDER_EYE);

        boolean hasWoodPick = hasAnyTierPick(p, false, false);
        boolean hasStonePick = hasAnyTierPick(p, true, false);
        boolean hasIronPick = hasAnyTierPick(p, true, true);
        boolean hasWaterBucket = CraftingHelper.countItem(p, Items.WATER_BUCKET) > 0;
        boolean hasAnyBucket = hasWaterBucket || CraftingHelper.countItem(p, Items.BUCKET) > 0
                || CraftingHelper.countItem(p, Items.LAVA_BUCKET) > 0;
        boolean hasFlintSteel = CraftingHelper.countItem(p, Items.FLINT_AND_STEEL) > 0;
        int ironAll = CraftingHelper.countItem(p, Items.IRON_INGOT)
                + CraftingHelper.countItem(p, Items.RAW_IRON)
                + CraftingHelper.countItem(p, Items.IRON_ORE);

        boolean dragonDead = inEnd && currentStage == Stage.END_DRAGON && isDragonDefeatedAndExitReady();
        boolean portalLit = !inNether && !inEnd && CraftingHelper.findNearbyBlock(ctx, b -> b == Blocks.END_PORTAL, 18) != null;

        if (currentStage == Stage.END_DRAGON && !inEnd) {
            return Stage.COMPLETED;
        }
        ResourceSnapshot snap = new ResourceSnapshot(
                inNether,
                inEnd,
                logs * 4 + planks,
                cobble,
                hasWoodPick,
                hasStonePick,
                hasIronPick,
                hasAnyBucket,
                hasWaterBucket,
                hasFlintSteel,
                ironAll,
                blazeRods + blazePowder / 2,
                pearls + eyes,
                portalLit,
                dragonDead
        );
        return recommendStageFromCounts(snap);
    }

    private PathingCommand tickWoodStage() {
        LocalPlayer p = ctx.player();
        int logs = CraftingHelper.countMatching(p, st -> CraftingHelper.isLogItem(st.getItem()));
        int planks = CraftingHelper.countMatching(p, st -> CraftingHelper.isPlankItem(st.getItem()));
        int sticks = CraftingHelper.countItem(p, Items.STICK);
        int tables = CraftingHelper.countItem(p, Items.CRAFTING_TABLE);

        if ((logs * 4 + planks) < 20 && logs < 5) {
            p.closeContainer();
            return runMineTask("wood_logs", 6, LOG_BLOCK_NAMES);
        }
        cancelSubProcesses();

        if (planks < 16 && logs > 0) {
            return executeCraftStep(RecipeKind.PLANKS_FROM_LOG);
        }
        if (tables < 1 && CraftingHelper.findNearbyBlock(ctx, b -> b == Blocks.CRAFTING_TABLE, 5) == null) {
            return executeCraftStep(RecipeKind.CRAFTING_TABLE);
        }
        if (sticks < 4) {
            return executeCraftStep(RecipeKind.STICKS);
        }
        return executeTableCraft(RecipeKind.WOODEN_PICKAXE);
    }

    private PathingCommand tickStoneAndFoodStage() {
        LocalPlayer p = ctx.player();
        int cobble = CraftingHelper.countMatching(p, st -> CraftingHelper.isCobbleMaterial(st.getItem()));
        int sticks = CraftingHelper.countItem(p, Items.STICK);
        int planks = CraftingHelper.countMatching(p, st -> CraftingHelper.isPlankItem(st.getItem()));
        int logs = CraftingHelper.countMatching(p, st -> CraftingHelper.isLogItem(st.getItem()));

        if (cobble < 14) {
            p.closeContainer();
            return runMineTask("stone_cobble", 16, "stone", "cobblestone", "cobbled_deepslate");
        }
        if (sticks < 6) {
            if (planks < 4 && logs > 0) {
                return executeCraftStep(RecipeKind.PLANKS_FROM_LOG);
            }
            if (planks >= 2) {
                return executeCraftStep(RecipeKind.STICKS);
            }
            return runMineTask("more_wood", 3, LOG_BLOCK_NAMES);
        }
        cancelSubProcesses();
        if (!hasAnyTierPick(p, true, false)) {
            return executeTableCraft(RecipeKind.STONE_PICKAXE);
        }
        if (!hasItemMatching(p, st -> st.is(Items.STONE_AXE) || st.is(Items.IRON_AXE))) {
            return executeTableCraft(RecipeKind.STONE_AXE);
        }
        if (!hasItemMatching(p, st -> st.is(Items.STONE_SWORD) || st.is(Items.IRON_SWORD))) {
            return executeTableCraft(RecipeKind.STONE_SWORD);
        }
        if (CraftingHelper.countItem(p, Items.FURNACE) < 1
                && CraftingHelper.findNearbyBlock(ctx, b -> b == Blocks.FURNACE, 6) == null) {
            return executeTableCraft(RecipeKind.FURNACE);
        }
        // Gather quick surface food if low before heading underground
        int foodTotal = countEdibleNutrition(p);
        if (foodTotal < 16) {
            LivingEntity animal = findNearestEntity(e -> e instanceof LivingEntity le && FOOD_MOBS.contains(entityId(e)), 28.0);
            if (animal != null) {
                activateCombatFilter(e -> e.equals(animal));
                return new PathingCommand(null, PathingCommandType.DEFER);
            }
        }
        return executeTableCraft(RecipeKind.STONE_PICKAXE);
    }

    private PathingCommand tickIronAndBucketStage() {
        LocalPlayer p = ctx.player();
        int ingots = CraftingHelper.countItem(p, Items.IRON_INGOT);
        int rawIron = CraftingHelper.countItem(p, Items.RAW_IRON) + CraftingHelper.countItem(p, Items.IRON_ORE);
        int flint = CraftingHelper.countItem(p, Items.FLINT);
        boolean hasIronPick = hasAnyTierPick(p, true, true);
        boolean hasBucket = CraftingHelper.countItem(p, Items.BUCKET) > 0 || CraftingHelper.countItem(p, Items.WATER_BUCKET) > 0;
        boolean hasWaterBucket = CraftingHelper.countItem(p, Items.WATER_BUCKET) > 0;
        boolean hasFlintAndSteel = CraftingHelper.countItem(p, Items.FLINT_AND_STEEL) > 0;

        int ironNeeded = (hasIronPick ? 0 : 3) + (hasBucket ? 0 : 3) + (hasFlintAndSteel ? 0 : 1);
        if (ingots < ironNeeded && (ingots + rawIron) < ironNeeded) {
            p.closeContainer();
            return runMineTask("iron_ore", Math.max(4, ironNeeded - ingots + 2), "iron_ore", "deepslate_iron_ore");
        }
        if (!hasFlintAndSteel && flint < 1) {
            p.closeContainer();
            return runMineTask("flint_gravel", 4, "gravel");
        }
        if (ingots < ironNeeded && rawIron > 0) {
            int fuelCount = CraftingHelper.countItem(p, Items.COAL) + CraftingHelper.countItem(p, Items.CHARCOAL)
                    + CraftingHelper.countMatching(p, st -> CraftingHelper.isPlankItem(st.getItem()));
            if (fuelCount < 2) {
                p.closeContainer();
                return runMineTask("coal_fuel", 6, "coal_ore", "deepslate_coal_ore");
            }
            return executeFurnaceSmelt(
                    st -> st.is(Items.RAW_IRON) || st.is(Items.IRON_ORE),
                    st -> st.is(Items.COAL) || st.is(Items.CHARCOAL) || CraftingHelper.isPlankItem(st.getItem()) || CraftingHelper.isLogItem(st.getItem())
            );
        }
        p.closeContainer();
        cancelSubProcesses();

        if (!hasIronPick && ingots >= 3) {
            if (CraftingHelper.countItem(p, Items.STICK) < 2) {
                return executeCraftStep(RecipeKind.STICKS);
            }
            return executeTableCraft(RecipeKind.IRON_PICKAXE);
        }
        if (!hasBucket && ingots >= 3) {
            return executeTableCraft(RecipeKind.BUCKET);
        }
        if (!hasFlintAndSteel && ingots >= 1 && flint >= 1) {
            return executeCraftStep(RecipeKind.FLINT_AND_STEEL);
        }
        if (!hasWaterBucket && CraftingHelper.countItem(p, Items.BUCKET) > 0) {
            return fillWaterBucketStep();
        }
        // Extra defense: craft shield if 1 iron & planks remain
        if (CraftingHelper.countItem(p, Items.SHIELD) < 1
                && p.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.OFFHAND).isEmpty()
                && ingots >= 1
                && CraftingHelper.countMatching(p, st -> CraftingHelper.isPlankItem(st.getItem())) >= 6) {
            return executeTableCraft(RecipeKind.SHIELD);
        }
        return new PathingCommand(null, PathingCommandType.DEFER);
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
            activateCombatFilter(e -> "blaze".equals(entityId(e)) || (e instanceof Enemy && e.distanceTo(ctx.player()) < 10.0));
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
            activateCombatFilter(e -> "enderman".equals(entityId(e)) || (e instanceof Enemy && e.distanceTo(ctx.player()) < 8.0));
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

    private PathingCommand runMineTask(String key, int qty, String... blockNames) {
        if (!key.equals(activeSubTaskKey) || !baritone.getMineProcess().isActive()) {
            cancelSubProcesses();
            activeSubTaskKey = key;
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
        if (!baritone.getCombatProcess().isActive()) {
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
        if (actionCooldown > 0) {
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }
        StepResult res = CraftingHelper.stepCraft(ctx, recipe);
        actionCooldown = 2;
        if (res == StepResult.COMPLETED) {
            actionCooldown = 3;
        }
        return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
    }

    private PathingCommand executeTableCraft(RecipeKind recipe) {
        if (ctx.player().containerMenu instanceof CraftingMenu) {
            return executeCraftStep(recipe);
        }
        BlockPos tablePos = CraftingHelper.findNearbyBlock(ctx, b -> b == Blocks.CRAFTING_TABLE, 5);
        if (tablePos == null) {
            if (CraftingHelper.countItem(ctx.player(), Items.CRAFTING_TABLE) < 1) {
                if (CraftingHelper.countMatching(ctx.player(), st -> CraftingHelper.isPlankItem(st.getItem())) >= 4) {
                    return executeCraftStep(RecipeKind.CRAFTING_TABLE);
                }
                return runMineTask("wood_for_table", 2, LOG_BLOCK_NAMES);
            }
            return placeUtilityBlockNearby(Items.CRAFTING_TABLE);
        }
        return interactWithBlockStep(tablePos);
    }

    private PathingCommand executeFurnaceSmelt(Predicate<ItemStack> inPred, Predicate<ItemStack> fuelPred) {
        if (ctx.player().containerMenu instanceof AbstractFurnaceMenu) {
            if (actionCooldown > 0) {
                return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
            }
            CraftingHelper.stepFurnaceSmelt(ctx, inPred, fuelPred);
            actionCooldown = 4;
            smeltProgressTicks++;
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }
        BlockPos furnacePos = CraftingHelper.findNearbyBlock(ctx, b -> b == Blocks.FURNACE, 6);
        if (furnacePos == null) {
            if (CraftingHelper.countItem(ctx.player(), Items.FURNACE) < 1) {
                return executeTableCraft(RecipeKind.FURNACE);
            }
            return placeUtilityBlockNearby(Items.FURNACE);
        }
        return interactWithBlockStep(furnacePos);
    }

    private PathingCommand placeUtilityBlockNearby(net.minecraft.world.item.Item utilityItem) {
        BlockPos targetFloor = ctx.playerFeet().offset(1, 0, 0);
        if (!ctx.world().getBlockState(targetFloor).canBeReplaced()
                || !MovementHelper.canWalkOn(ctx, targetFloor.below())) {
            targetFloor = ctx.playerFeet().offset(0, 0, 1);
        }
        if (!ctx.world().getBlockState(targetFloor).canBeReplaced()
                || !MovementHelper.canWalkOn(ctx, targetFloor.below())) {
            return new PathingCommand(new GoalNear(ctx.playerFeet().offset(2, 0, 2), 1), PathingCommandType.SET_GOAL_AND_PATH);
        }
        if (!selectHotbarOrSwap(st -> st.is(utilityItem))) {
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }
        Rotation rot = RotationUtils.calcRotationFromVec3d(ctx.playerHead(), Vec3.atCenterOf(targetFloor.below()).add(0, 0.5, 0), ctx.playerRotations());
        ((baritone.behavior.LookBehavior) baritone.getLookBehavior()).updateTarget(rot, true, true);
        baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_RIGHT, true);
        placedUtilityPos = targetFloor;
        actionCooldown = 3;
        return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
    }

    private PathingCommand interactWithBlockStep(BlockPos pos) {
        if (ctx.playerFeet().distSqr(pos) > 12.25) {
            return new PathingCommand(new GoalNear(pos, 2), PathingCommandType.SET_GOAL_AND_PATH);
        }
        Rotation rot = RotationUtils.calcRotationFromVec3d(ctx.playerHead(), Vec3.atCenterOf(pos), ctx.playerRotations());
        ((baritone.behavior.LookBehavior) baritone.getLookBehavior()).updateTarget(rot, true, true);
        if (actionCooldown == 0 && ctx.playerRotations().isReallyCloseTo(rot)) {
            baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_RIGHT, true);
            actionCooldown = 4;
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

    private LivingEntity findUrgentThreat() {
        LocalPlayer p = ctx.player();
        for (Entity e : (Iterable<Entity>) ctx.entitiesStream()::iterator) {
            if (!(e instanceof LivingEntity le) || !le.isAlive() || le.equals(p)) {
                continue;
            }
            if (e instanceof Enemy && le.distanceTo(p) <= 7.5 && p.hasLineOfSight(le)) {
                return le;
            }
        }
        return null;
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

    private boolean selectHotbarOrSwap(Predicate<ItemStack> pred) {
        var inv = ctx.player().getInventory().getNonEquipmentItems();
        for (int i = 0; i < 9; i++) {
            if (pred.test(inv.get(i))) {
                return baritone.getInventoryBehavior().safeSelectHotbarSlot(i)
                        || ctx.player().getInventory().getSelectedSlot() == i;
            }
        }
        for (int i = 9; i < inv.size(); i++) {
            if (pred.test(inv.get(i))) {
                return baritone.getInventoryBehavior().attemptToPutOnHotbar(i, s -> s == 0 || s == 8);
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

    private static boolean hasAnyTierPick(LocalPlayer p, boolean stoneOrBetter, boolean ironOrBetter) {
        return hasItemMatching(p, st -> {
            if (st.is(Items.DIAMOND_PICKAXE) || st.is(Items.NETHERITE_PICKAXE) || st.is(Items.IRON_PICKAXE)) {
                return true;
            }
            if (!ironOrBetter && (st.is(Items.STONE_PICKAXE) || st.is(Items.GOLDEN_PICKAXE))) {
                return true;
            }
            return !stoneOrBetter && !ironOrBetter && st.is(Items.WOODEN_PICKAXE);
        });
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
