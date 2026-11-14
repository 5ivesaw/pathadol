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
import baritone.api.pathing.goals.GoalComposite;
import baritone.api.pathing.goals.GoalNear;
import baritone.api.pathing.goals.GoalXZ;
import baritone.api.pathing.movement.IMovement;
import baritone.api.pathing.path.IPathExecutor;
import baritone.api.process.PathingCommand;
import baritone.api.process.PathingCommandType;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.Rotation;
import baritone.api.utils.RotationUtils;
import baritone.api.utils.input.Input;
import baritone.human.BoatNavigationCore;
import baritone.human.BoatNavigationCore.CellSurface;
import baritone.human.BoatNavigationCore.ControlCommand;
import baritone.human.BoatNavigationCore.RayProbe;
import baritone.human.BoatNavigationCore.TraversalAssessment;
import baritone.human.CraftingHelper;
import baritone.human.CraftingHelper.RecipeKind;
import baritone.human.CraftingHelper.StepResult;
import baritone.human.MouseModel;
import baritone.pathing.movement.MovementHelper;
import baritone.utils.BaritoneProcessHelper;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.vehicle.boat.AbstractBoat;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.SwingAnimation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Autonomous Overworld water and ice boat controller.
 * Evaluates upcoming terrain across open oceans or ice sheets, acquires/crafts a boat from matching wood,
 * places it on clear 3x3 water or ice tiles, steers smoothly with PD yaw damping and multi-ray
 * obstacle avoidance, disembarks cleanly onto shores, and recovers the boat.
 */
public final class BoatProcess extends BaritoneProcessHelper {

    public enum State {
        INACTIVE,
        ACQUIRING_BOAT,
        MOVING_TO_LAUNCH,
        PLACING_BOAT,
        BOARDING_BOAT,
        SAILING,
        DISEMBARKING,
        RECOVERING_BOAT
    }

    private static final double PLACE_REACH_SQ = 17.0;
    private static final double ENTITY_REACH_SQ = 14.0;

    private State state = State.INACTIVE;
    private Goal standaloneGoal;
    private Goal cachedTargetGoal;

    private BlockPos launchTile;
    private BlockPos placedTableConfirmPos;
    private int actionCooldown;
    private int stateTicks;
    private int stallTicks;
    private int reverseUnstickTicks;
    private float prevBoatYaw;
    private boolean hasPrevBoatYaw;
    private int lastBoatEntityId = -1;

    private long cooldownUntilGameTime;
    private long lastProbeGameTime = Long.MIN_VALUE;

    public BoatProcess(Baritone baritone) {
        super(baritone);
    }

    /**
     * Explicitly navigates using boat assistance to the supplied destination goal.
     */
    public void startTo(Goal goal) {
        this.standaloneGoal = goal;
        this.cachedTargetGoal = goal;
        this.cooldownUntilGameTime = 0L;
        resetTransientState();
        this.state = ctx.player() != null && ctx.player().getVehicle() instanceof AbstractBoat
                ? State.SAILING
                : State.MOVING_TO_LAUNCH;
    }

    public State getState() {
        return state;
    }

    @Override
    public double priority() {
        return 2.0; // Above Elytra (0) and CustomGoal/Explore (-1), below Combat (4) and Speedrun (10)
    }

    @Override
    public boolean isTemporary() {
        // When auto-assisting CustomGoalProcess/ExploreProcess, preserve their goal across water crossings
        return this.standaloneGoal == null;
    }

    @Override
    public String displayName0() {
        return "Boat - " + this.state;
    }

    @Override
    public void onLostControl() {
        if (this.standaloneGoal != null || this.state != State.INACTIVE) {
            baritone.getInputOverrideHandler().clearAllKeys();
            this.standaloneGoal = null;
            this.cachedTargetGoal = null;
            resetTransientState();
            this.state = State.INACTIVE;
        }
    }

    @Override
    public boolean isActive() {
        LocalPlayer p = ctx.player();
        Level world = ctx.world();
        if (p == null || world == null) {
            return false;
        }
        if (!Baritone.settings().allowBoatTraversal.value && this.standaloneGoal == null) {
            if (this.state != State.INACTIVE) {
                resetSession(60L);
            }
            return false;
        }
        if (world.dimension() != Level.OVERWORLD) {
            if (this.state != State.INACTIVE) {
                resetSession(60L);
            }
            return false;
        }
        if (p.isFallFlying() || baritone.getElytraProcess().isActive()
                || baritone.getCombatProcess().isActive()
                || baritone.getBedwarsProcess().isActive()) {
            return false;
        }

        if (this.state == State.DISEMBARKING || this.state == State.RECOVERING_BOAT) {
            return true;
        }

        if (this.standaloneGoal != null) {
            return true;
        }

        if (baritone.getMineProcess().isActive() || baritone.getBuilderProcess().isActive()) {
            return false;
        }
        if (baritone.getSpeedrunProcess() != null && baritone.getSpeedrunProcess().isActive()
                && !p.isPassenger()
                && !baritone.getSpeedrunProcess().isOverworldTravelDeferAllowed()) {
            return false;
        }

        Goal underlying = resolveUnderlyingGoal();
        if (underlying == null || underlying.isInGoal(ctx.playerFeet())) {
            if (this.state != State.INACTIVE) {
                resetSession(40L);
            }
            return false;
        }
        this.cachedTargetGoal = underlying;

        if (p.getVehicle() instanceof AbstractBoat) {
            if (this.state == State.INACTIVE) {
                resetTransientState();
                this.state = State.SAILING;
            }
            return true;
        }

        if (this.state != State.INACTIVE) {
            return true;
        }

        long now = world.getGameTime();
        if (now < this.cooldownUntilGameTime || now - this.lastProbeGameTime < 8L) {
            return false;
        }
        this.lastProbeGameTime = now;

        TargetPoint tp = extractNearestTarget(underlying, p.getX(), p.getZ());
        if (tp == null) {
            return false;
        }
        double distXZ = Math.hypot(tp.x - p.getX(), tp.z - p.getZ());
        if (distXZ < 12.0) {
            return false;
        }

        TraversalAssessment assessment = evaluateWaterOrIceNeed(p, tp, distXZ);
        if (!assessment.shouldUseBoat()) {
            return false;
        }

        if (!hasUsableOrCraftableBoat(p)) {
            return false;
        }

        resetTransientState();
        this.state = State.MOVING_TO_LAUNCH;
        return true;
    }

    @Override
    public PathingCommand onTick(boolean calcFailed, boolean isSafeToCancel) {
        if (this.actionCooldown > 0) {
            this.actionCooldown--;
        }
        this.stateTicks++;

        LocalPlayer p = ctx.player();
        Level world = ctx.world();
        if (p == null || world == null) {
            resetSession(40L);
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }

        Goal goal = this.standaloneGoal != null ? this.standaloneGoal : resolveUnderlyingGoal();
        if (goal == null && this.cachedTargetGoal != null) {
            goal = this.cachedTargetGoal;
        }
        if (goal == null && this.state != State.DISEMBARKING && this.state != State.RECOVERING_BOAT) {
            resetSession(40L);
            return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
        }
        if (this.standaloneGoal != null && goal != null && goal.isInGoal(ctx.playerFeet())) {
            if (p.getVehicle() instanceof AbstractBoat) {
                this.state = State.DISEMBARKING;
                this.stateTicks = 0;
            } else {
                baritone.getInputOverrideHandler().clearAllKeys();
                onLostControl();
                return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
            }
        }

        if (p.getVehicle() instanceof AbstractBoat boat
                && this.state != State.DISEMBARKING
                && this.state != State.RECOVERING_BOAT) {
            this.state = State.SAILING;
            this.lastBoatEntityId = boat.getId();
        }

        if (calcFailed && (this.state == State.MOVING_TO_LAUNCH || this.state == State.RECOVERING_BOAT)) {
            baritone.getInputOverrideHandler().clearAllKeys();
            resetSession(100L);
            return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
        }

        return switch (this.state) {
            case ACQUIRING_BOAT -> tickAcquireBoat(p);
            case MOVING_TO_LAUNCH -> tickMoveToLaunch(p, goal);
            case PLACING_BOAT -> tickPlaceBoat(p, goal);
            case BOARDING_BOAT -> tickBoardBoat(p, goal);
            case SAILING -> tickSailing(p, world, goal);
            case DISEMBARKING -> tickDisembark(p, world);
            case RECOVERING_BOAT -> tickRecoverBoat(p, world);
            case INACTIVE -> new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        };
    }

    private PathingCommand tickAcquireBoat(LocalPlayer p) {
        if (CraftingHelper.countBoats(p) > 0 || findNearbyEmptyBoat(10.0) != null) {
            CraftingHelper.safeCloseContainer(ctx);
            this.state = State.MOVING_TO_LAUNCH;
            this.stateTicks = 0;
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }
        if (!Baritone.settings().autoCraftBoat.value || this.stateTicks > 240) {
            CraftingHelper.safeCloseContainer(ctx);
            resetSession(120L);
            return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
        }

        boolean hasTableNearby = CraftingHelper.findNearbyBlock(ctx, b -> b == Blocks.CRAFTING_TABLE, 5) != null;
        boolean hasTableItem = CraftingHelper.countAccessibleMatching(p, st -> st.is(Items.CRAFTING_TABLE)) > 0;
        boolean tableAvailable = hasTableNearby || hasTableItem;

        Item readyPlank = CraftingHelper.findBestSingleWoodPlankInPlayer(p, 5);
        int readyCount = readyPlank != null ? CraftingHelper.countItem(p, readyPlank) : 0;
        int totalPlanks = CraftingHelper.countAccessibleMatching(p, st -> CraftingHelper.isPlankItem(st.getItem()));

        // Ensure we preserve >= 5 matching boat planks even after spending 4 planks for a crafting table
        boolean needPlanksForTable = !tableAvailable && (readyPlank == null || (readyCount < 9 && (totalPlanks - readyCount) < 4));
        if (readyPlank == null || needPlanksForTable) {
            int minLogsNeeded = (!tableAvailable && readyPlank == null) ? 3 : 2;
            if (CraftingHelper.findBestBoatLogInPlayer(p, minLogsNeeded) == null
                    && CraftingHelper.findBestBoatLogInPlayer(p, 1) == null) {
                CraftingHelper.safeCloseContainer(ctx);
                resetSession(120L);
                return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
            }
            if (!CraftingHelper.safeCloseContainer(ctx)) {
                return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
            }
            baritone.getInputOverrideHandler().clearMovementKeys();
            StepResult res = CraftingHelper.stepCraft(ctx, RecipeKind.PLANKS_FROM_LOG);
            if (res == StepResult.MISSING_INGREDIENTS) {
                resetSession(120L);
                return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
            }
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }

        if (!(p.containerMenu instanceof CraftingMenu)) {
            if (!CraftingHelper.safeCloseContainer(ctx)) {
                return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
            }
            BlockPos tablePos = CraftingHelper.findNearbyBlock(ctx, b -> b == Blocks.CRAFTING_TABLE, 5);
            if (tablePos == null) {
                if (!hasTableItem) {
                    baritone.getInputOverrideHandler().clearMovementKeys();
                    StepResult res = CraftingHelper.stepCraft(ctx, RecipeKind.CRAFTING_TABLE);
                    if (res == StepResult.MISSING_INGREDIENTS) {
                        resetSession(120L);
                        return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
                    }
                    return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
                }
                return placeCraftingTableNearby(p);
            }
            return openCraftingTableStep(tablePos);
        }

        baritone.getInputOverrideHandler().clearMovementKeys();
        StepResult craftRes = CraftingHelper.stepCraft(ctx, RecipeKind.BOAT);
        if (craftRes == StepResult.COMPLETED && CraftingHelper.countBoats(p) > 0) {
            CraftingHelper.safeCloseContainer(ctx);
            this.state = State.MOVING_TO_LAUNCH;
            this.stateTicks = 0;
            this.actionCooldown = 3;
        } else if (craftRes == StepResult.MISSING_INGREDIENTS) {
            CraftingHelper.safeCloseContainer(ctx);
            resetSession(120L);
            return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
        }
        return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
    }

    private PathingCommand tickMoveToLaunch(LocalPlayer p, Goal goal) {
        if (!CraftingHelper.safeCloseContainer(ctx)) {
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }
        AbstractBoat existing = findNearbyEmptyBoat(10.0);
        if (existing != null) {
            this.lastBoatEntityId = existing.getId();
            this.state = State.BOARDING_BOAT;
            this.stateTicks = 0;
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }
        if (CraftingHelper.countBoats(p) <= 0) {
            if (hasCraftableBoatMaterials(p)) {
                this.state = State.ACQUIRING_BOAT;
                this.stateTicks = 0;
                return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
            }
            resetSession(100L);
            return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
        }

        TargetPoint tp = extractNearestTarget(goal, p.getX(), p.getZ());
        double tx = tp != null ? tp.x : p.getX();
        double tz = tp != null ? tp.z : p.getZ();

        BlockPos candidate = findBestLaunchTile(p, tx, tz, 4);
        if (candidate != null) {
            this.launchTile = candidate;
            this.state = State.PLACING_BOAT;
            this.stateTicks = 0;
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }

        BlockPos wideSearch = findBestLaunchTile(p, tx, tz, 14);
        if (wideSearch != null) {
            if (this.stateTicks > 180) {
                resetSession(90L);
                return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
            }
            return new PathingCommand(new GoalNear(wideSearch, 2), PathingCommandType.SET_GOAL_AND_PATH);
        }

        resetSession(80L);
        return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
    }

    private PathingCommand tickPlaceBoat(LocalPlayer p, Goal goal) {
        AbstractBoat existing = findNearbyEmptyBoat(7.5);
        if (existing != null) {
            this.lastBoatEntityId = existing.getId();
            this.state = State.BOARDING_BOAT;
            this.stateTicks = 0;
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }
        if (CraftingHelper.countBoats(p) <= 0) {
            this.state = State.MOVING_TO_LAUNCH;
            this.stateTicks = 0;
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }
        TargetPoint tp = extractNearestTarget(goal, p.getX(), p.getZ());
        if (this.launchTile == null || !isLaunchTileStillValid(this.launchTile)
                || p.position().distanceToSqr(Vec3.atCenterOf(this.launchTile)) > PLACE_REACH_SQ) {
            this.launchTile = findBestLaunchTile(p, tp != null ? tp.x : p.getX(), tp != null ? tp.z : p.getZ(), 4);
            if (this.launchTile == null) {
                this.state = State.MOVING_TO_LAUNCH;
                this.stateTicks = 0;
                return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
            }
        }

        if (this.stateTicks > 70) {
            resetSession(90L);
            return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
        }

        baritone.getInputOverrideHandler().clearMovementKeys();

        int hotbarBoatSlot = findHotbarBoatSlot(p);
        if (hotbarBoatSlot < 0) {
            StepResult move = CraftingHelper.stepMoveToHotbar(ctx, st -> CraftingHelper.isBoatItem(st.getItem()), 2);
            if (move == StepResult.MISSING_INGREDIENTS) {
                this.state = State.MOVING_TO_LAUNCH;
                this.stateTicks = 0;
            }
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }
        if (!baritone.getInventoryBehavior().safeSelectHotbarSlot(hotbarBoatSlot)
                && p.getInventory().getSelectedSlot() != hotbarBoatSlot) {
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }
        if (!baritone.getInventoryBehavior().isHotbarReadyForAction()) {
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }

        boolean isIce = isIceBlock(ctx.world().getBlockState(this.launchTile).getBlock());
        Vec3 aimPoint = new Vec3(
                this.launchTile.getX() + 0.5,
                this.launchTile.getY() + (isIce ? 1.0 : 0.88),
                this.launchTile.getZ() + 0.5
        );
        if (!hasClearLaunchAimRay(p, ctx.world(), this.launchTile, aimPoint)) {
            this.launchTile = findBestLaunchTile(p, tp != null ? tp.x : p.getX(), tp != null ? tp.z : p.getZ(), 4);
            this.state = State.MOVING_TO_LAUNCH;
            this.stateTicks = 0;
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }
        Rotation targetRot = RotationUtils.calcRotationFromVec3d(ctx.playerHead(), aimPoint, ctx.playerRotations());
        ((baritone.behavior.LookBehavior) baritone.getLookBehavior()).updateTarget(targetRot, true, true);

        float angErr = MouseModel.angularDistance(ctx.playerRotations(), targetRot);
        if (this.actionCooldown == 0 && angErr <= 4.5f && CraftingHelper.isBoatItem(p.getMainHandItem().getItem())) {
            ctx.playerController().processRightClick(p, ctx.world(), InteractionHand.MAIN_HAND);
            p.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, false);
            this.actionCooldown = 6;
            this.state = State.BOARDING_BOAT;
            this.stateTicks = 0;
        }
        return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
    }

    private PathingCommand tickBoardBoat(LocalPlayer p, Goal goal) {
        if (p.getVehicle() instanceof AbstractBoat boat) {
            this.lastBoatEntityId = boat.getId();
            this.state = State.SAILING;
            this.stateTicks = 0;
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }

        AbstractBoat targetBoat = findTargetBoat(p, 8.0);
        if (targetBoat == null) {
            if (this.stateTicks > 18) {
                this.state = State.MOVING_TO_LAUNCH;
                this.stateTicks = 0;
            }
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }
        if (this.stateTicks > 90) {
            resetSession(80L);
            return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
        }

        this.lastBoatEntityId = targetBoat.getId();
        Vec3 boatCenter = targetBoat.getBoundingBox().getCenter();
        double distSq = p.position().distanceToSqr(boatCenter);
        if (distSq > ENTITY_REACH_SQ) {
            BlockPos boatPos = targetBoat.blockPosition();
            if (p.isInWater()) {
                Rotation yawLook = RotationUtils.calcRotationFromVec3d(ctx.playerHead(), boatCenter, ctx.playerRotations());
                baritone.getLookBehavior().updateTarget(yawLook, false);
                baritone.getInputOverrideHandler().clearMovementKeys();
                baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_FORWARD, true);
                baritone.getInputOverrideHandler().setInputForceState(Input.JUMP, true);
                return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
            }
            return new PathingCommand(new GoalNear(boatPos, 2), PathingCommandType.SET_GOAL_AND_PATH);
        }

        baritone.getInputOverrideHandler().clearMovementKeys();
        Rotation lookBoat = RotationUtils.calcRotationFromVec3d(ctx.playerHead(), boatCenter, ctx.playerRotations());
        ((baritone.behavior.LookBehavior) baritone.getLookBehavior()).updateTarget(lookBoat, true, true);

        if (this.actionCooldown == 0 && MouseModel.angularDistance(ctx.playerRotations(), lookBoat) <= 6.0f
                && ctx.minecraft().gameMode != null) {
            ctx.minecraft().gameMode.interact(p, targetBoat, new EntityHitResult(targetBoat, boatCenter), InteractionHand.MAIN_HAND);
            p.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, false);
            this.actionCooldown = 5;
        }
        return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
    }

    private PathingCommand tickSailing(LocalPlayer p, Level world, Goal goal) {
        if (!(p.getVehicle() instanceof AbstractBoat boat)) {
            this.hasPrevBoatYaw = false;
            this.state = State.MOVING_TO_LAUNCH;
            this.stateTicks = 0;
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }

        TargetPoint tp = extractNearestTarget(goal, boat.getX(), boat.getZ());
        if (tp == null) {
            baritone.getInputOverrideHandler().clearAllKeys();
            this.state = State.DISEMBARKING;
            this.stateTicks = 0;
            return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
        }

        float boatYaw = BoatNavigationCore.normalizeYaw(boat.getYRot());
        float yawRate = this.hasPrevBoatYaw ? BoatNavigationCore.normalizeYaw(boatYaw - this.prevBoatYaw) : 0.0f;
        this.prevBoatYaw = boatYaw;
        this.hasPrevBoatYaw = true;

        Vec3 vel = boat.getDeltaMovement();
        double speed = Math.hypot(vel.x, vel.z);
        boolean onIce = isBoatOnIce(world, boat);
        boolean goalOnDryLand = isTargetDryLand(world, tp.x, tp.z);

        if (this.reverseUnstickTicks > 0) {
            this.reverseUnstickTicks--;
            baritone.getInputOverrideHandler().clearAllKeys();
            baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_BACK, true);
            baritone.getLookBehavior().updateTarget(new Rotation(boatYaw, 8.0f), false);
            return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
        }

        List<RayProbe> probes = probeWaterAndIceCorridors(world, boat, boatYaw,
                BoatNavigationCore.worldYawTo(boat.getX(), boat.getZ(), tp.x, tp.z), onIce);

        ControlCommand cmd = BoatNavigationCore.stepSteering(
                boat.getX(),
                boat.getZ(),
                boatYaw,
                yawRate,
                vel.x,
                vel.z,
                tp.x,
                tp.z,
                goalOnDryLand,
                onIce,
                probes
        );

        if (cmd.shouldDisembark()) {
            baritone.getInputOverrideHandler().clearAllKeys();
            this.state = State.DISEMBARKING;
            this.stateTicks = 0;
            return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
        }

        if (!cmd.emergencyBrake() && speed < 0.035) {
            this.stallTicks++;
            if (this.stallTicks > 32) {
                this.stallTicks = 0;
                if (hasDryShoreAdjacent(world, boat)) {
                    baritone.getInputOverrideHandler().clearAllKeys();
                    this.state = State.DISEMBARKING;
                    this.stateTicks = 0;
                    return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
                }
                this.reverseUnstickTicks = 8;
            }
        } else {
            this.stallTicks = 0;
        }

        baritone.getLookBehavior().updateTarget(new Rotation(cmd.desiredLookYawDeg(), 10.0f), false);

        baritone.getInputOverrideHandler().clearAllKeys();
        if (cmd.forward()) {
            baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_FORWARD, true);
        }
        if (cmd.back()) {
            baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_BACK, true);
        }
        if (cmd.left()) {
            baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_LEFT, true);
        }
        if (cmd.right()) {
            baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_RIGHT, true);
        }

        return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
    }

    private PathingCommand tickDisembark(LocalPlayer p, Level world) {
        if (!(p.getVehicle() instanceof AbstractBoat boat)) {
            baritone.getInputOverrideHandler().clearAllKeys();
            AbstractBoat landedBoat = findTargetBoat(p, 5.0);
            if (landedBoat != null && shouldRecoverBoat(world, landedBoat, p)) {
                this.lastBoatEntityId = landedBoat.getId();
                this.state = State.RECOVERING_BOAT;
                this.stateTicks = 0;
                return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
            }
            if (this.standaloneGoal != null) {
                onLostControl();
            } else {
                resetSession(60L);
            }
            return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
        }

        Vec3 vel = boat.getDeltaMovement();
        double speed = Math.hypot(vel.x, vel.z);
        baritone.getInputOverrideHandler().clearAllKeys();
        if (speed > 0.085 && this.stateTicks < 20) {
            baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_BACK, true);
            return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
        }
        baritone.getInputOverrideHandler().setInputForceState(Input.SNEAK, true);
        return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
    }

    private PathingCommand tickRecoverBoat(LocalPlayer p, Level world) {
        if (this.stateTicks > 50 || CraftingHelper.countBoats(p) > 0 && findDroppedBoatItem(p, 5.0) == null) {
            baritone.getInputOverrideHandler().clearAllKeys();
            if (this.standaloneGoal != null) {
                onLostControl();
            } else {
                resetSession(60L);
            }
            return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
        }

        ItemEntity droppedBoat = findDroppedBoatItem(p, 5.5);
        if (droppedBoat != null) {
            if (p.position().distanceToSqr(droppedBoat.position()) <= 1.2) {
                baritone.getInputOverrideHandler().clearAllKeys();
                resetSession(60L);
                return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
            }
            return new PathingCommand(new GoalBlock(droppedBoat.blockPosition()), PathingCommandType.SET_GOAL_AND_PATH);
        }

        AbstractBoat boat = findTargetBoat(p, 4.8);
        if (boat == null) {
            baritone.getInputOverrideHandler().clearAllKeys();
            resetSession(60L);
            return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
        }

        baritone.getInputOverrideHandler().clearMovementKeys();
        Vec3 center = boat.getBoundingBox().getCenter();
        Rotation look = RotationUtils.calcRotationFromVec3d(ctx.playerHead(), center, ctx.playerRotations());
        ((baritone.behavior.LookBehavior) baritone.getLookBehavior()).updateTarget(look, true, true);

        if (this.actionCooldown == 0 && MouseModel.angularDistance(ctx.playerRotations(), look) <= 8.0f
                && ctx.minecraft().gameMode != null) {
            ctx.minecraft().gameMode.attack(p, boat);
            p.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, false);
            this.actionCooldown = 3;
        }
        return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
    }

    private PathingCommand placeCraftingTableNearby(LocalPlayer p) {
        baritone.getInputOverrideHandler().clearMovementKeys();
        BlockPos feet = ctx.playerFeet();
        if (this.placedTableConfirmPos != null
                && ctx.world().getBlockState(this.placedTableConfirmPos).is(Blocks.CRAFTING_TABLE)) {
            return openCraftingTableStep(this.placedTableConfirmPos);
        }
        int[][] offsets = {{1, 0}, {0, 1}, {-1, 0}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}};
        double reach = ctx.playerController().getBlockReachDistance();
        for (int[] off : offsets) {
            BlockPos cand = feet.offset(off[0], 0, off[1]);
            BlockPos base = cand.below();
            if (ctx.world().getBlockState(cand).canBeReplaced() && MovementHelper.canWalkOn(ctx, base)) {
                Optional<Rotation> rot = RotationUtils.reachableOffset(ctx, base, Vec3.atCenterOf(base).add(0.0, 0.5, 0.0), reach, false);
                if (rot.isEmpty()) {
                    continue;
                }
                int slot = findHotbarItemSlot(p, Items.CRAFTING_TABLE);
                if (slot < 0) {
                    CraftingHelper.stepMoveToHotbar(ctx, st -> st.is(Items.CRAFTING_TABLE), 2);
                    return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
                }
                if (!baritone.getInventoryBehavior().safeSelectHotbarSlot(slot) && p.getInventory().getSelectedSlot() != slot) {
                    return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
                }
                ((baritone.behavior.LookBehavior) baritone.getLookBehavior()).updateTarget(rot.get(), true, true);
                if (this.actionCooldown == 0 && baritone.getInventoryBehavior().isHotbarReadyForAction()
                        && (ctx.isLookingAt(base) || MouseModel.angularDistance(ctx.playerRotations(), rot.get()) <= 3.5f)) {
                    baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_RIGHT, true);
                    this.placedTableConfirmPos = cand;
                    this.actionCooldown = 4;
                }
                return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
            }
        }
        return new PathingCommand(new GoalNear(feet.offset(2, 0, 2), 1), PathingCommandType.SET_GOAL_AND_PATH);
    }

    private PathingCommand openCraftingTableStep(BlockPos pos) {
        if (ctx.playerFeet().distSqr(pos) > 12.25) {
            return new PathingCommand(new GoalNear(pos, 2), PathingCommandType.SET_GOAL_AND_PATH);
        }
        baritone.getInputOverrideHandler().clearMovementKeys();
        Optional<Rotation> rotOpt = RotationUtils.reachable(ctx, pos);
        Rotation rot = rotOpt.orElseGet(() -> RotationUtils.calcRotationFromVec3d(ctx.playerHead(), Vec3.atCenterOf(pos), ctx.playerRotations()));
        ((baritone.behavior.LookBehavior) baritone.getLookBehavior()).updateTarget(rot, true, true);
        if (this.actionCooldown == 0 && (ctx.isLookingAt(pos) || MouseModel.angularDistance(ctx.playerRotations(), rot) <= 4.5f)) {
            baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_RIGHT, true);
            this.actionCooldown = 5;
        }
        return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
    }

    private TraversalAssessment evaluateWaterOrIceNeed(LocalPlayer p, TargetPoint tp, double distXZ) {
        List<CellSurface> cells = new ArrayList<>(36);
        IPathExecutor exec = baritone.getPathingBehavior().getCurrent();
        if (exec != null && exec.getPath() != null) {
            List<IMovement> moves = exec.getPath().movements();
            int start = Math.max(0, exec.getPosition());
            int end = Math.min(moves.size(), start + 36);
            for (int i = start; i < end; i++) {
                BetterBlockPos dest = moves.get(i).getDest();
                cells.add(classifySurfaceAt(ctx.world(), dest.x, dest.y, dest.z));
            }
        }
        TraversalAssessment fromPath = BoatNavigationCore.assessSurfaceRun(cells, distXZ);
        if (fromPath.shouldUseBoat()) {
            return fromPath;
        }

        // Fallback direct corridor scan (useful while A* stops short at ocean boundary or while swimming)
        List<CellSurface> rayCells = new ArrayList<>(32);
        double dx = tp.x - p.getX();
        double dz = tp.z - p.getZ();
        double len = Math.max(1e-6, Math.hypot(dx, dz));
        double ux = dx / len;
        double uz = dz / len;
        int steps = Math.min(32, (int) Math.floor(distXZ));
        int baseY = Mth.floor(p.getY());
        for (int s = 1; s <= steps; s++) {
            int bx = Mth.floor(p.getX() + ux * s);
            int bz = Mth.floor(p.getZ() + uz * s);
            rayCells.add(classifySurfaceAt(ctx.world(), bx, baseY, bz));
        }
        return BoatNavigationCore.assessSurfaceRun(rayCells, distXZ);
    }

    private static CellSurface classifySurfaceAt(Level world, int x, int approxY, int z) {
        if (world == null) {
            return CellSurface.LAND;
        }
        LevelChunk chunk = world.getChunkSource().getChunk(x >> 4, z >> 4, false);
        if (chunk == null || chunk.isEmpty()) {
            return CellSurface.LAND;
        }
        int surfY = world.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
        // MOTION_BLOCKING stops on ice/solid, while fluid water requires scanning below/near approxY
        for (int dy = 1; dy >= -3; dy--) {
            BlockPos pos = new BlockPos(x, approxY + dy, z);
            BlockState st = world.getBlockState(pos);
            if (st.getFluidState().is(FluidTags.WATER)) {
                BlockState above1 = world.getBlockState(pos.above());
                BlockState above2 = world.getBlockState(pos.above(2));
                if (above1.getCollisionShape(world, pos.above()).isEmpty()
                        && above2.getCollisionShape(world, pos.above(2)).isEmpty()
                        && above1.getFluidState().isEmpty()) {
                    return CellSurface.WATER;
                }
            }
        }
        BlockPos topFloor = new BlockPos(x, surfY - 1, z);
        BlockState floorSt = world.getBlockState(topFloor);
        if (isIceBlock(floorSt.getBlock())) {
            return CellSurface.ICE;
        }
        if (floorSt.getFluidState().is(FluidTags.WATER)) {
            return CellSurface.WATER;
        }
        return CellSurface.LAND;
    }

    private List<RayProbe> probeWaterAndIceCorridors(
            Level world,
            AbstractBoat boat,
            float boatYawDeg,
            float goalYawDeg,
            boolean onIce
    ) {
        List<RayProbe> probes = new ArrayList<>(19);
        double maxDist = onIce ? 26.0 : 18.0;
        int hullY = Mth.floor(boat.getY() + 0.15);
        int headY = Mth.floor(boat.getY() + 1.15);

        // Fan relative to goal yaw (-90..+90) plus boat yaw nose
        for (int offset = -105; offset <= 105; offset += 15) {
            float yaw = BoatNavigationCore.normalizeYaw(goalYawDeg + offset);
            probes.add(castBoatCorridorRay(world, boat.getX(), boat.getZ(), hullY, headY, yaw, maxDist));
        }
        probes.add(castBoatCorridorRay(world, boat.getX(), boat.getZ(), hullY, headY, boatYawDeg, maxDist));
        return probes;
    }

    private static RayProbe castBoatCorridorRay(
            Level world,
            double startX,
            double startZ,
            int hullY,
            int headY,
            float probeYawDeg,
            double maxDist
    ) {
        double rad = Math.toRadians(probeYawDeg);
        double dirX = -Math.sin(rad);
        double dirZ = Math.cos(rad);
        double perpX = dirZ;
        double perpZ = -dirX;

        double freeDist = maxDist;
        double minSideClear = 1.35;
        boolean hitsDryShore = false;
        boolean hitsHardWall = false;

        for (double d = 0.85; d <= maxDist; d += 0.5) {
            double cx = startX + dirX * d;
            double cz = startZ + dirZ * d;
            int chunkX = Mth.floor(cx) >> 4;
            int chunkZ = Mth.floor(cz) >> 4;
            LevelChunk chunk = world.getChunkSource().getChunk(chunkX, chunkZ, false);
            if (chunk == null || chunk.isEmpty()) {
                freeDist = Math.min(freeDist, d);
                break;
            }

            // Check central hull & side margins across boat width (half-width 0.6875)
            boolean centerBlocked = isBlockedAt(world, cx, cz, hullY, headY);
            if (centerBlocked) {
                freeDist = Math.max(0.0, d - 0.5);
                BlockPos hitPos = BlockPos.containing(cx, hullY, cz);
                if (isWalkableShoreColumn(world, hitPos)) {
                    hitsDryShore = true;
                } else {
                    hitsHardWall = true;
                }
                break;
            }

            double localSideClear = 1.35;
            double[] testMargins = {0.55, 0.85, 1.15};
            for (double margin : testMargins) {
                boolean leftHit = isBlockedAt(world, cx + perpX * margin, cz + perpZ * margin, hullY, headY);
                boolean rightHit = isBlockedAt(world, cx - perpX * margin, cz - perpZ * margin, hullY, headY);
                if (leftHit || rightHit) {
                    localSideClear = Math.min(localSideClear, margin - 0.15);
                    if (margin <= BoatNavigationCore.BOAT_HALF_WIDTH + 0.08) {
                        freeDist = Math.max(0.0, d - 0.45);
                        double hitSide = leftHit ? margin : -margin;
                        BlockPos hitPos = BlockPos.containing(cx + perpX * hitSide, hullY, cz + perpZ * hitSide);
                        if (isWalkableShoreColumn(world, hitPos)) {
                            hitsDryShore = true;
                        } else {
                            hitsHardWall = true;
                        }
                        break;
                    }
                }
            }
            if (d <= 8.5) {
                minSideClear = Math.min(minSideClear, localSideClear);
            }
            if (hitsDryShore || hitsHardWall) {
                break;
            }
        }

        return new RayProbe(probeYawDeg, freeDist, minSideClear, hitsDryShore, hitsHardWall);
    }

    private static boolean isBlockedAt(Level world, double x, double z, int hullY, int headY) {
        int bx = Mth.floor(x);
        int bz = Mth.floor(z);
        for (int y = hullY; y <= headY; y++) {
            BlockPos pos = new BlockPos(bx, y, bz);
            BlockState st = world.getBlockState(pos);
            if (!st.getCollisionShape(world, pos).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private static boolean isWalkableShoreColumn(Level world, BlockPos pos) {
        for (int dy = 1; dy >= -1; dy--) {
            BlockPos floor = pos.above(dy);
            BlockState floorSt = world.getBlockState(floor);
            if (!floorSt.getCollisionShape(world, floor).isEmpty() && floorSt.getFluidState().isEmpty()) {
                BlockState a1 = world.getBlockState(floor.above());
                BlockState a2 = world.getBlockState(floor.above(2));
                if (a1.getCollisionShape(world, floor.above()).isEmpty()
                        && a2.getCollisionShape(world, floor.above(2)).isEmpty()
                        && a1.getFluidState().isEmpty()) {
                    return true;
                }
            }
        }
        return false;
    }

    private BlockPos findBestLaunchTile(LocalPlayer p, double goalX, double goalZ, int radius) {
        Level world = ctx.world();
        if (world == null) {
            return null;
        }
        BetterBlockPos origin = ctx.playerFeet();
        BlockPos best = null;
        double bestScore = Double.NEGATIVE_INFINITY;

        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                int x = origin.x + dx;
                int z = origin.z + dz;
                for (int dy = -2; dy <= 1; dy++) {
                    int y = origin.y + dy;
                    BlockPos pos = new BlockPos(x, y, z);
                    BlockState st = world.getBlockState(pos);
                    boolean isWater = st.getFluidState().is(FluidTags.WATER);
                    boolean isIce = isIceBlock(st.getBlock());
                    if (!isWater && !isIce) {
                        continue;
                    }
                    if (!isLaunchTileStillValid(pos)) {
                        continue;
                    }
                    int open3x3 = countOpenNeighbors3x3(world, pos);
                    int headroom = countAirHeadroom(world, pos);
                    double playerDistSq = p.position().distanceToSqr(x + 0.5, y + 0.8, z + 0.5);
                    if (radius > 4) {
                        // In wide-search mode we just pick closest reachable 3x3 water/ice edge
                        double wideScore = open3x3 * 6.0 - Math.hypot(dx, dz) * 1.6 - Math.hypot(goalX - x, goalZ - z) * 0.25;
                        if (open3x3 >= 7 && headroom >= 2 && wideScore > bestScore) {
                            bestScore = wideScore;
                            best = pos;
                        }
                    } else {
                        double goalDistSq = (goalX - (x + 0.5)) * (goalX - (x + 0.5)) + (goalZ - (z + 0.5)) * (goalZ - (z + 0.5));
                        double score = BoatNavigationCore.scoreLaunchCandidate(playerDistSq, goalDistSq, open3x3, headroom, isIce);
                        if (score > bestScore) {
                            Vec3 aimTest = new Vec3(x + 0.5, y + (isIce ? 1.0 : 0.88), z + 0.5);
                            if (hasClearLaunchAimRay(p, world, pos, aimTest)) {
                                bestScore = score;
                                best = pos;
                            }
                        }
                    }
                }
            }
        }
        return best;
    }

    private boolean isLaunchTileStillValid(BlockPos tile) {
        Level world = ctx.world();
        if (world == null || tile == null) {
            return false;
        }
        BlockState st = world.getBlockState(tile);
        boolean isWater = st.getFluidState().is(FluidTags.WATER);
        boolean isIce = isIceBlock(st.getBlock());
        if (!isWater && !isIce) {
            return false;
        }
        BlockState above1 = world.getBlockState(tile.above());
        BlockState above2 = world.getBlockState(tile.above(2));
        if (!above1.getCollisionShape(world, tile.above()).isEmpty()
                || !above2.getCollisionShape(world, tile.above(2)).isEmpty()
                || !above1.getFluidState().isEmpty()) {
            return false;
        }
        return countOpenNeighbors3x3(world, tile) >= 7;
    }

    private static int countOpenNeighbors3x3(Level world, BlockPos center) {
        int open = 0;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) {
                    continue;
                }
                BlockPos adj = center.offset(dx, 0, dz);
                BlockState a1 = world.getBlockState(adj.above());
                BlockState a2 = world.getBlockState(adj.above(2));
                if (a1.getCollisionShape(world, adj.above()).isEmpty()
                        && a2.getCollisionShape(world, adj.above(2)).isEmpty()
                        && a1.getFluidState().isEmpty()) {
                    open++;
                }
            }
        }
        return open;
    }

    private static int countAirHeadroom(Level world, BlockPos base) {
        int air = 0;
        for (int dy = 1; dy <= 3; dy++) {
            BlockPos up = base.above(dy);
            BlockState st = world.getBlockState(up);
            if (st.getCollisionShape(world, up).isEmpty() && st.getFluidState().isEmpty()) {
                air++;
            } else {
                break;
            }
        }
        return air;
    }

    private boolean hasUsableOrCraftableBoat(LocalPlayer p) {
        if (CraftingHelper.countBoats(p) > 0 || findNearbyEmptyBoat(12.0) != null) {
            return true;
        }
        return Baritone.settings().autoCraftBoat.value && hasCraftableBoatMaterials(p);
    }

    private boolean hasCraftableBoatMaterials(LocalPlayer p) {
        boolean hasTable = CraftingHelper.findNearbyBlock(ctx, b -> b == Blocks.CRAFTING_TABLE, 5) != null
                || CraftingHelper.countAccessibleMatching(p, st -> st.is(Items.CRAFTING_TABLE)) > 0;
        Item singlePlank = CraftingHelper.findBestSingleWoodPlankInPlayer(p, 5);
        int totalPlanks = CraftingHelper.countAccessibleMatching(p, st -> CraftingHelper.isPlankItem(st.getItem()));
        if (singlePlank != null && (hasTable || totalPlanks >= 9)) {
            return true;
        }
        int neededLogs = (hasTable || totalPlanks >= 4) ? 2 : 3;
        return CraftingHelper.findBestBoatLogInPlayer(p, neededLogs) != null;
    }

    private AbstractBoat findNearbyEmptyBoat(double maxRange) {
        if (ctx.player() == null) {
            return null;
        }
        double maxSq = maxRange * maxRange;
        AbstractBoat best = null;
        double bestSq = Double.MAX_VALUE;
        for (Entity e : ctx.entities()) {
            if (e instanceof AbstractBoat boat && boat.isAlive() && boat.getPassengers().isEmpty()) {
                double dSq = ctx.player().distanceToSqr(boat);
                if (dSq <= maxSq && dSq < bestSq) {
                    bestSq = dSq;
                    best = boat;
                }
            }
        }
        return best;
    }

    private AbstractBoat findTargetBoat(LocalPlayer p, double maxRange) {
        double maxSq = maxRange * maxRange;
        AbstractBoat fallback = null;
        double fallbackSq = Double.MAX_VALUE;
        for (Entity e : ctx.entities()) {
            if (e instanceof AbstractBoat boat && boat.isAlive() && boat.getPassengers().isEmpty()) {
                double dSq = p.distanceToSqr(boat);
                if (dSq <= maxSq) {
                    if (boat.getId() == this.lastBoatEntityId) {
                        return boat;
                    }
                    if (dSq < fallbackSq) {
                        fallbackSq = dSq;
                        fallback = boat;
                    }
                }
            }
        }
        return fallback;
    }

    private ItemEntity findDroppedBoatItem(LocalPlayer p, double maxRange) {
        double maxSq = maxRange * maxRange;
        ItemEntity best = null;
        double bestSq = Double.MAX_VALUE;
        for (Entity e : ctx.entities()) {
            if (e instanceof ItemEntity item && item.isAlive() && CraftingHelper.isBoatItem(item.getItem().getItem())) {
                double dSq = p.distanceToSqr(item);
                if (dSq <= maxSq && dSq < bestSq) {
                    bestSq = dSq;
                    best = item;
                }
            }
        }
        return best;
    }

    private static boolean shouldRecoverBoat(Level world, AbstractBoat boat, LocalPlayer p) {
        if (p.distanceToSqr(boat) > 20.25) {
            return false;
        }
        BlockPos pos = boat.blockPosition();
        BlockState below = world.getBlockState(pos.below());
        // Always recover on ice, shallow shoreline, or when dry land is adjacent
        return !below.getFluidState().is(FluidTags.WATER) || hasDryShoreAdjacent(world, boat);
    }

    private static boolean hasDryShoreAdjacent(Level world, AbstractBoat boat) {
        BlockPos base = boat.blockPosition();
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                if (isWalkableShoreColumn(world, base.offset(dx, 0, dz))) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean isBoatOnIce(Level world, AbstractBoat boat) {
        if (boat.getGroundFriction() >= 0.95f) {
            return true;
        }
        BlockPos under = BlockPos.containing(boat.getX(), boat.getY() - 0.25, boat.getZ());
        return isIceBlock(world.getBlockState(under).getBlock());
    }

    private static boolean isIceBlock(Block block) {
        return block == Blocks.ICE || block == Blocks.PACKED_ICE || block == Blocks.BLUE_ICE || block == Blocks.FROSTED_ICE;
    }

    private static boolean isTargetDryLand(Level world, double x, double z) {
        int bx = Mth.floor(x);
        int bz = Mth.floor(z);
        LevelChunk chunk = world.getChunkSource().getChunk(bx >> 4, bz >> 4, false);
        if (chunk == null || chunk.isEmpty()) {
            return true;
        }
        int surfY = world.getHeight(Heightmap.Types.MOTION_BLOCKING, bx, bz);
        BlockPos floorPos = new BlockPos(bx, surfY - 1, bz);
        BlockState st = world.getBlockState(floorPos);
        if (isIceBlock(st.getBlock())) {
            return false;
        }
        return st.getFluidState().isEmpty() && !st.getCollisionShape(world, floorPos).isEmpty();
    }

    private Goal resolveUnderlyingGoal() {
        if (baritone.getCustomGoalProcess().isActive() && baritone.getCustomGoalProcess().getGoal() != null) {
            return baritone.getCustomGoalProcess().getGoal();
        }
        if (baritone.getExploreProcess().isActive() && baritone.getPathingBehavior().getGoal() != null) {
            return baritone.getPathingBehavior().getGoal();
        }
        return null;
    }

    private record TargetPoint(double x, double z) {}

    private static TargetPoint extractNearestTarget(Goal goal, double fromX, double fromZ) {
        if (goal instanceof GoalXZ xz) {
            return new TargetPoint(xz.getX() + 0.5, xz.getZ() + 0.5);
        }
        if (goal instanceof GoalBlock blk) {
            return new TargetPoint(blk.x + 0.5, blk.z + 0.5);
        }
        if (goal instanceof GoalNear near) {
            BlockPos pos = near.getGoalPos();
            return new TargetPoint(pos.getX() + 0.5, pos.getZ() + 0.5);
        }
        if (goal instanceof GoalComposite comp) {
            TargetPoint best = null;
            double bestDistSq = Double.MAX_VALUE;
            for (Goal child : comp.goals()) {
                TargetPoint candidate = extractNearestTarget(child, fromX, fromZ);
                if (candidate != null) {
                    double dSq = (candidate.x - fromX) * (candidate.x - fromX) + (candidate.z - fromZ) * (candidate.z - fromZ);
                    if (dSq < bestDistSq) {
                        bestDistSq = dSq;
                        best = candidate;
                    }
                }
            }
            return best;
        }
        return null;
    }

    private static boolean hasClearLaunchAimRay(LocalPlayer p, Level world, BlockPos tile, Vec3 aimPoint) {
        BlockHitResult hit = world.clip(new ClipContext(
                p.getEyePosition(),
                aimPoint,
                ClipContext.Block.OUTLINE,
                ClipContext.Fluid.ANY,
                p
        ));
        return hit != null && hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(tile);
    }

    private static int findHotbarBoatSlot(LocalPlayer p) {
        for (int i = 0; i < 9; i++) {
            if (CraftingHelper.isBoatItem(p.getInventory().getItem(i).getItem())) {
                return i;
            }
        }
        return -1;
    }

    private static int findHotbarItemSlot(LocalPlayer p, Item item) {
        for (int i = 0; i < 9; i++) {
            if (p.getInventory().getItem(i).is(item)) {
                return i;
            }
        }
        return -1;
    }

    private void resetTransientState() {
        this.launchTile = null;
        this.placedTableConfirmPos = null;
        this.actionCooldown = 0;
        this.stateTicks = 0;
        this.stallTicks = 0;
        this.reverseUnstickTicks = 0;
        this.hasPrevBoatYaw = false;
        this.lastBoatEntityId = -1;
    }

    private void resetSession(long cooldownTicks) {
        baritone.getInputOverrideHandler().clearAllKeys();
        this.standaloneGoal = null;
        this.cachedTargetGoal = null;
        resetTransientState();
        this.state = State.INACTIVE;
        if (ctx.world() != null) {
            this.cooldownUntilGameTime = ctx.world().getGameTime() + cooldownTicks;
        }
    }
}
