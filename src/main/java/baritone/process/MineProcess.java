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
import baritone.api.pathing.goals.*;
import baritone.api.process.IMineProcess;
import baritone.api.process.PathingCommand;
import baritone.api.process.PathingCommandType;
import baritone.api.utils.*;
import baritone.api.utils.input.Input;
import baritone.behavior.look.ForkableRandom;
import baritone.cache.CachedChunk;
import baritone.human.HumanProfile;
import baritone.human.MouseModel;
import baritone.human.PerceptionGate;
import baritone.pathing.movement.CalculationContext;
import baritone.pathing.movement.MovementHelper;
import baritone.utils.BaritoneProcessHelper;
import baritone.utils.BlockStateInterface;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.AirBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.*;
import java.util.stream.Collectors;

import static baritone.api.pathing.movement.ActionCosts.COST_INF;

/**
 * Mine blocks of a certain type
 *
 * @author leijurv
 */
public final class MineProcess extends BaritoneProcessHelper implements IMineProcess {

    private static final double DIRECT_MINE_MAX_EYE_DIST_SQ = 3.3 * 3.3;

    private BlockOptionalMetaLookup filter;
    private List<BlockPos> knownOreLocations;
    private List<BlockPos> blacklist; // inaccessible
    private Map<BlockPos, Long> anticipatedDrops;
    private BlockPos branchPoint;
    private GoalRunAway branchPointRunaway;
    private BlockPos activeDirectMinePos;
    private BlockPos justBrokenPos;
    private int waitDropTicks;
    private int preBreakTargetItemCount = -1;
    private int airborneDirectBreakTicks;
    private int trackedDropId = -1;
    private int trackedDropTicks = 0;
    private Map<Integer, Long> blacklistedDropUntil = new HashMap<>();
    private int desiredQuantity;
    private int tickCount;
    private final PerceptionGate perceptionGate = new PerceptionGate(new ForkableRandom());

    public MineProcess(Baritone baritone) {
        super(baritone);
    }

    @Override
    public boolean isActive() {
        return filter != null;
    }

    public boolean isActivelyDirectMining() {
        return isActive() && this.activeDirectMinePos != null;
    }

    @Override
    public PathingCommand onTick(boolean calcFailed, boolean isSafeToCancel) {
        BlockOptionalMetaLookup qtyFilter = filterFilter();
        if (desiredQuantity > 0) {
            BlockOptionalMetaLookup countFilter = qtyFilter != null ? qtyFilter : filter;
            int curr = countMatchingItems(countFilter);
            boolean nearbyDropRemaining = qtyFilter != null && (findNearestTargetDrop(qtyFilter, 5.5) != null || waitDropTicks > 0);
            if (curr >= desiredQuantity && !nearbyDropRemaining) {
                logDirect("Have " + curr + " valid items");
                cancel();
                return null;
            }
        }
        if (calcFailed) {
            if (!knownOreLocations.isEmpty() && Baritone.settings().blacklistClosestOnFailure.value) {
                logDirect("Unable to find any path to " + filter + ", blacklisting presumably unreachable closest instance...");
                if (Baritone.settings().notificationOnMineFail.value) {
                    logNotification("Unable to find any path to " + filter + ", blacklisting presumably unreachable closest instance...", true);
                }
                knownOreLocations.stream().min(Comparator.comparingDouble(ctx.playerFeet()::distSqr)).ifPresent(blacklist::add);
                knownOreLocations.removeIf(blacklist::contains);
            } else {
                logDirect("Unable to find any path to " + filter + ", canceling mine");
                if (Baritone.settings().notificationOnMineFail.value) {
                    logNotification("Unable to find any path to " + filter + ", canceling mine", true);
                }
                cancel();
                return null;
            }
        }

        updateLoucaSystem();
        perceptionGate.tick();
        int mineGoalUpdateInterval = Baritone.settings().mineGoalUpdateInterval.value;
        List<BlockPos> curr = new ArrayList<>(knownOreLocations);
        if (mineGoalUpdateInterval != 0 && tickCount++ % mineGoalUpdateInterval == 0) { // big brain
            CalculationContext context = new CalculationContext(baritone, true);
            Baritone.getExecutor().execute(() -> rescan(curr, context));
        }
        if (Baritone.settings().legitMine.value) {
            if (!addNearby()) {
                cancel();
                return null;
            }
        }
        BlockOptionalMetaLookup activeFilter = filterFilter();
        if (this.activeDirectMinePos != null) {
            if (!ctx.player().onGround()) {
                this.airborneDirectBreakTicks++;
            } else {
                this.airborneDirectBreakTicks = 0;
            }
            if (!isValidDirectMineTarget(this.activeDirectMinePos, activeFilter)) {
                BlockState brokenState = BlockStateInterface.get(ctx, this.activeDirectMinePos);
                if (brokenState.getBlock() instanceof AirBlock) {
                    this.justBrokenPos = this.activeDirectMinePos;
                    BlockPos immediateNext = findDirectMineTarget(curr, activeFilter);
                    double brokenDistSq = ctx.player().position().distanceToSqr(Vec3.atBottomCenterOf(this.activeDirectMinePos));
                    this.waitDropTicks = (immediateNext != null && brokenDistSq <= 6.25) ? 0 : 2;
                    baritone.getInputOverrideHandler().getClickModel().cancelOverHold();
                } else {
                    this.preBreakTargetItemCount = -1;
                }
                baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_LEFT, false);
                this.activeDirectMinePos = null;
                this.airborneDirectBreakTicks = 0;
            }
        } else {
            this.airborneDirectBreakTicks = 0;
        }

        // Priority 1: Walk directly over dropped target ItemEntities before chaining distant blocks
        // or abandoning broken tree trunks. Prevents broken blocks/drops being left behind!
        if (this.activeDirectMinePos == null && activeFilter != null
                && !baritone.getInputOverrideHandler().getBlockBreakHelper().isHitting()) {
            ItemEntity nearestDrop = findNearestTargetDrop(activeFilter, 12.0);
            boolean immediateAdjacentTarget = findDirectMineTarget(curr, activeFilter) != null;
            if (nearestDrop != null && (!immediateAdjacentTarget || ctx.player().position().distanceToSqr(nearestDrop.position()) > 2.25)) {
                this.waitDropTicks = 0;
                this.justBrokenPos = null;
                if (this.trackedDropId == nearestDrop.getId()) {
                    this.trackedDropTicks++;
                } else {
                    this.trackedDropId = nearestDrop.getId();
                    this.trackedDropTicks = 0;
                }
                long nowTick = ctx.world() != null ? ctx.world().getGameTime() : 0L;
                if (this.trackedDropTicks > 65) {
                    this.blacklistedDropUntil.put(nearestDrop.getId(), nowTick + 160L);
                    this.trackedDropId = -1;
                    this.trackedDropTicks = 0;
                } else {
                    baritone.getInputOverrideHandler().getClickModel().cancelOverHold();
                    baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_LEFT, false);
                    BlockPos dropBlock = nearestDrop.blockPosition();
                    double distSq = ctx.player().position().distanceToSqr(nearestDrop.position());
                    if (distSq <= 9.0) {
                        Rotation lookDrop = RotationUtils.calcRotationFromVec3d(
                                ctx.playerHead(),
                                nearestDrop.position().add(0.0, 0.15, 0.0),
                                ctx.playerRotations()
                        );
                        baritone.getLookBehavior().updateTarget(lookDrop, false);
                    }
                    if (isSafeToCancel && dropBlock.equals(ctx.playerFeet())
                            && Math.abs(nearestDrop.getY() - ctx.player().getY()) <= 1.25
                            && distSq > 0.25) {
                        baritone.getInputOverrideHandler().clearAllKeys();
                        Rotation lookDrop = RotationUtils.calcRotationFromVec3d(
                                ctx.playerHead(),
                                nearestDrop.position().add(0.0, 0.15, 0.0),
                                ctx.playerRotations()
                        );
                        baritone.getLookBehavior().updateTarget(lookDrop, true);
                        baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_FORWARD, true);
                        return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
                    }
                    baritone.getInputOverrideHandler().clearAllKeys();
                    return new PathingCommand(new GoalBlock(dropBlock), PathingCommandType.SET_GOAL_AND_PATH);
                }
            } else {
                this.trackedDropId = -1;
                this.trackedDropTicks = 0;
            }
            if (isSafeToCancel && this.waitDropTicks > 0 && this.justBrokenPos != null) {
                if (this.preBreakTargetItemCount >= 0 && countMatchingItems(activeFilter) > this.preBreakTargetItemCount) {
                    this.waitDropTicks = 0;
                    this.justBrokenPos = null;
                    this.preBreakTargetItemCount = -1;
                } else {
                    this.waitDropTicks--;
                    baritone.getInputOverrideHandler().getClickModel().cancelOverHold();
                    baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_LEFT, false);
                    double dSq = ctx.player().position().distanceToSqr(Vec3.atBottomCenterOf(this.justBrokenPos));
                    if (dSq > 3.0) {
                        return new PathingCommand(new GoalBlock(this.justBrokenPos), PathingCommandType.SET_GOAL_AND_PATH);
                    }
                    return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
                }
            }
        }

        if (this.activeDirectMinePos == null && isSafeToCancel) {
            this.activeDirectMinePos = findDirectMineTarget(curr, activeFilter);
            if (this.activeDirectMinePos != null) {
                this.preBreakTargetItemCount = countMatchingItems(activeFilter);
            }
        }
        if (this.activeDirectMinePos != null
                && (isSafeToCancel || baritone.getInputOverrideHandler().getBlockBreakHelper().isHitting())
                && (ctx.player().onGround() || this.airborneDirectBreakTicks <= 2)) {
            BlockPos pos = this.activeDirectMinePos;
            Optional<Rotation> rot = RotationUtils.reachable(ctx, pos);
            if (rot.isPresent()) {
                baritone.getInputOverrideHandler().clearMovementKeys();
                baritone.getLookBehavior().updateTarget(rot.get(), true, true);
                boolean waitingForPostBreakRelease = Baritone.settings().humanMode.value
                        && (baritone.getInputOverrideHandler().getClickModel().isOverHolding()
                        || !baritone.getInputOverrideHandler().canSwitchFromAttackToBreak());
                boolean activelyHitting = baritone.getInputOverrideHandler().getBlockBreakHelper().isHitting();
                int slotBefore = ctx.player().getInventory().getSelectedSlot();
                if (!waitingForPostBreakRelease && !activelyHitting && baritone.getInventoryBehavior().isSlotSwitchSafe()) {
                    MovementHelper.switchToBestToolFor(ctx, ctx.world().getBlockState(pos));
                }
                boolean switchedThisTick = ctx.player().getInventory().getSelectedSlot() != slotBefore;
                boolean hotbarReady = baritone.getInventoryBehavior().isHotbarReadyForAction();
                boolean alignedToBlock = ctx.isLookingAt(pos)
                        || ctx.playerRotations().isReallyCloseTo(rot.get())
                        || MouseModel.angularDistance(ctx.playerRotations(), rot.get()) <= (activelyHitting ? 11.5f : 3.5f);
                boolean shouldClick = !waitingForPostBreakRelease && !switchedThisTick && hotbarReady && alignedToBlock;
                if (shouldClick && Baritone.settings().humanMode.value) {
                    HitResult trace = ctx.objectMouseOver();
                    if (trace != null && trace.getType() == HitResult.Type.ENTITY) {
                        shouldClick = false;
                    }
                }
                baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_LEFT, shouldClick);
                return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
            }
            this.activeDirectMinePos = null;
        }
        baritone.getInputOverrideHandler().clearAllKeys();
        PathingCommand command = updateGoal();
        if (command == null) {
            // none in range
            // maybe say something in chat? (ahem impact)
            cancel();
            return null;
        }
        return command;
    }

    private boolean isValidDirectMineTarget(BlockPos pos, BlockOptionalMetaLookup activeFilter) {
        if (pos == null || blacklist.contains(pos)) {
            return false;
        }
        if (!ctx.player().onGround() && this.airborneDirectBreakTicks > 2) {
            return false;
        }
        if (pos.getY() < ctx.playerFeet().getY()) {
            return false;
        }
        BlockState state = BlockStateInterface.get(ctx, pos);
        if (state.getBlock() instanceof AirBlock) {
            return false;
        }
        if (activeFilter != null && !activeFilter.has(state)) {
            return false;
        }
        if (MovementHelper.avoidBreaking(baritone.bsi, pos.getX(), pos.getY(), pos.getZ(), state)) {
            return false;
        }
        boolean inPlayerColumn = pos.getX() == ctx.playerFeet().getX() && pos.getZ() == ctx.playerFeet().getZ();
        if (!inPlayerColumn) {
            if (ctx.player().isUnderWater()) {
                return false;
            }
            if (ctx.playerHead().distanceToSqr(VecUtils.calculateBlockCenter(ctx.world(), pos)) > DIRECT_MINE_MAX_EYE_DIST_SQ) {
                return false;
            }
        }
        return RotationUtils.reachable(ctx, pos).isPresent();
    }

    private BlockPos findDirectMineTarget(List<BlockPos> curr, BlockOptionalMetaLookup activeFilter) {
        if (!ctx.player().onGround()) {
            return null;
        }
        Optional<BlockPos> shaft = curr.stream()
                .filter(pos -> pos.getX() == ctx.playerFeet().getX() && pos.getZ() == ctx.playerFeet().getZ())
                .filter(pos -> isValidDirectMineTarget(pos, activeFilter))
                .min(Comparator.comparingDouble(ctx.playerFeet().above()::distSqr));
        if (shaft.isPresent()) {
            return shaft.get();
        }
        if (ctx.player().isUnderWater()) {
            return null;
        }
        return curr.stream()
                .filter(pos -> isValidDirectMineTarget(pos, activeFilter))
                .min(Comparator.comparingDouble(pos -> ctx.playerHead().distanceToSqr(VecUtils.calculateBlockCenter(ctx.world(), pos))))
                .orElse(null);
    }


    private void updateLoucaSystem() {
        Map<BlockPos, Long> copy = new HashMap<>(anticipatedDrops);
        ctx.getSelectedBlock().ifPresent(pos -> {
            if (knownOreLocations.contains(pos)) {
                copy.put(pos, System.currentTimeMillis() + Baritone.settings().mineDropLoiterDurationMSThanksLouca.value);
            }
        });
        // elaborate dance to avoid concurrentmodificationexcepption since rescan thread reads this
        // don't want to slow everything down with a gross lock do we now
        for (BlockPos pos : anticipatedDrops.keySet()) {
            if (copy.get(pos) < System.currentTimeMillis()) {
                copy.remove(pos);
            }
        }
        anticipatedDrops = copy;
    }

    @Override
    public void onLostControl() {
        mine(0, (BlockOptionalMetaLookup) null);
    }

    @Override
    public String displayName0() {
        return "Mine " + filter;
    }

    private PathingCommand updateGoal() {
        BlockOptionalMetaLookup filter = filterFilter();
        if (filter == null) {
            return null;
        }

        boolean legit = Baritone.settings().legitMine.value;
        List<BlockPos> dropped = droppedItemsScan();
        List<BlockPos> locs = knownOreLocations;
        if (!locs.isEmpty() || !dropped.isEmpty()) {
            CalculationContext context = new CalculationContext(baritone);
            List<BlockPos> combined = new ArrayList<>(locs);
            combined.addAll(dropped);
            List<BlockPos> locs2 = prune(context, combined, filter, Baritone.settings().mineMaxOreLocationsCount.value, blacklist, dropped);
            Set<BlockPos> dropSet = new HashSet<>(dropped);
            // Map dropped ItemEntities to exact GoalBlock so Baritone actually walks through them instead of GoalThreeBlocks below
            Goal goal = new GoalComposite(locs2.stream()
                    .map(loc -> dropSet.contains(loc) ? new GoalBlock(loc) : coalesce(loc, locs2, context))
                    .toArray(Goal[]::new));
            knownOreLocations = locs2;
            return new PathingCommand(goal, legit ? PathingCommandType.FORCE_REVALIDATE_GOAL_AND_PATH : PathingCommandType.REVALIDATE_GOAL_AND_PATH);
        }
        // we don't know any ore locations at the moment
        if (!legit && !Baritone.settings().exploreForBlocks.value) {
            return null;
        }
        // only when we should explore for blocks or are in legit mode we do this
        int y = Baritone.settings().legitMineYLevel.value;
        if (branchPoint == null) {
            /*if (!baritone.getPathingBehavior().isPathing() && playerFeet().y == y) {
                // cool, path is over and we are at desired y
                branchPoint = playerFeet();
                branchPointRunaway = null;
            } else {
                return new GoalYLevel(y);
            }*/
            branchPoint = ctx.playerFeet();
        }
        // TODO shaft mode, mine 1x1 shafts to either side
        // TODO also, see if the GoalRunAway with maintain Y at 11 works even from the surface
        if (branchPointRunaway == null) {
            branchPointRunaway = new GoalRunAway(1, y, branchPoint) {
                @Override
                public boolean isInGoal(int x, int y, int z) {
                    return false;
                }

                @Override
                public double heuristic() {
                    return Double.NEGATIVE_INFINITY;
                }
            };
        }
        return new PathingCommand(branchPointRunaway, PathingCommandType.REVALIDATE_GOAL_AND_PATH);
    }

    private void rescan(List<BlockPos> already, CalculationContext context) {
        BlockOptionalMetaLookup filter = filterFilter();
        if (filter == null) {
            return;
        }
        if (Baritone.settings().legitMine.value) {
            return;
        }
        List<BlockPos> dropped = droppedItemsScan();
        List<BlockPos> locs = searchWorld(context, filter, Baritone.settings().mineMaxOreLocationsCount.value, already, blacklist, dropped);
        locs.addAll(dropped);
        if (locs.isEmpty() && !Baritone.settings().exploreForBlocks.value) {
            logDirect("No locations for " + filter + " known, cancelling");
            if (Baritone.settings().notificationOnMineFail.value) {
                logNotification("No locations for " + filter + " known, cancelling", true);
            }
            cancel();
            return;
        }
        knownOreLocations = locs;
    }

    private boolean internalMiningGoal(BlockPos pos, CalculationContext context, List<BlockPos> locs) {
        // Here, BlockStateInterface is used because the position may be in a cached chunk (the targeted block is one that is kept track of)
        if (locs.contains(pos)) {
            return true;
        }
        BlockState state = context.bsi.get0(pos);
        if (Baritone.settings().internalMiningAirException.value && state.getBlock() instanceof AirBlock) {
            return true;
        }
        return filter.has(state) && plausibleToBreak(context, pos);
    }

    private Goal coalesce(BlockPos loc, List<BlockPos> locs, CalculationContext context) {
        boolean assumeVerticalShaftMine = !(baritone.bsi.get0(loc.above()).getBlock() instanceof FallingBlock);
        if (!Baritone.settings().forceInternalMining.value) {
            if (assumeVerticalShaftMine) {
                // we can get directly below the block
                return new GoalThreeBlocks(loc);
            } else {
                // we need to get feet or head into the block
                return new GoalTwoBlocks(loc);
            }
        }
        boolean upwardGoal = internalMiningGoal(loc.above(), context, locs);
        boolean downwardGoal = internalMiningGoal(loc.below(), context, locs);
        boolean doubleDownwardGoal = internalMiningGoal(loc.below(2), context, locs);
        if (upwardGoal == downwardGoal) { // symmetric
            if (doubleDownwardGoal && assumeVerticalShaftMine) {
                // we have a checkerboard like pattern
                // this one, and the one two below it
                // therefore it's fine to path to immediately below this one, since your feet will be in the doubleDownwardGoal
                // but only if assumeVerticalShaftMine
                return new GoalThreeBlocks(loc);
            } else {
                // this block has nothing interesting two below, but is symmetric vertically so we can get either feet or head into it
                return new GoalTwoBlocks(loc);
            }
        }
        if (upwardGoal) {
            // downwardGoal known to be false
            // ignore the gap then potential doubleDownward, because we want to path feet into this one and head into upwardGoal
            return new GoalBlock(loc);
        }
        // upwardGoal known to be false, downwardGoal known to be true
        if (doubleDownwardGoal && assumeVerticalShaftMine) {
            // this block and two below it are goals
            // path into the center of the one below, because that includes directly below this one
            return new GoalTwoBlocks(loc.below());
        }
        // upwardGoal false, downwardGoal true, doubleDownwardGoal false
        // just this block and the one immediately below, no others
        return new GoalBlock(loc.below());
    }

    private static class GoalThreeBlocks extends GoalTwoBlocks {

        public GoalThreeBlocks(BlockPos pos) {
            super(pos);
        }

        @Override
        public boolean isInGoal(int x, int y, int z) {
            return x == this.x && (y == this.y || y == this.y - 1 || y == this.y - 2) && z == this.z;
        }

        @Override
        public double heuristic(int x, int y, int z) {
            int xDiff = x - this.x;
            int yDiff = y - this.y;
            int zDiff = z - this.z;
            return GoalBlock.calculate(xDiff, yDiff < -1 ? yDiff + 2 : yDiff == -1 ? 0 : yDiff, zDiff);
        }

        @Override
        public boolean equals(Object o) {
            return super.equals(o);
        }

        @Override
        public int hashCode() {
            return super.hashCode() * 393857768;
        }

        @Override
        public String toString() {
            return String.format(
                    "GoalThreeBlocks{x=%s,y=%s,z=%s}",
                    SettingsUtil.maybeCensor(x),
                    SettingsUtil.maybeCensor(y),
                    SettingsUtil.maybeCensor(z)
            );
        }
    }

    private ItemEntity findNearestTargetDrop(BlockOptionalMetaLookup activeFilter, double maxDist) {
        if (!Baritone.settings().mineScanDroppedItems.value || activeFilter == null || ctx.world() == null || ctx.player() == null) {
            return null;
        }
        long nowTick = ctx.world().getGameTime();
        ItemEntity best = null;
        double bestDistSq = maxDist * maxDist;
        for (Entity entity : ((ClientLevel) ctx.world()).entitiesForRendering()) {
            if (entity instanceof ItemEntity ei && ei.isAlive() && !ei.getItem().isEmpty()) {
                Long expiry = blacklistedDropUntil.get(ei.getId());
                if (expiry != null && nowTick < expiry) {
                    continue;
                }
                BlockPos bp = ei.blockPosition();
                if (blacklist.contains(bp) || ctx.world().getBlockState(bp).is(Blocks.LAVA)) {
                    continue;
                }
                if (Math.abs(ei.getY() - ctx.player().getY()) > 4.5) {
                    continue;
                }
                if (matchesFilterOrDrop(activeFilter, ei.getItem())) {
                    double dSq = ei.distanceToSqr(ctx.player());
                    if (dSq < bestDistSq) {
                        bestDistSq = dSq;
                        best = ei;
                    }
                }
            }
        }
        return best;
    }

    public List<BlockPos> droppedItemsScan() {
        if (!Baritone.settings().mineScanDroppedItems.value || filter == null || ctx.world() == null) {
            return Collections.emptyList();
        }
        List<BlockPos> ret = new ArrayList<>();
        for (Entity entity : ((ClientLevel) ctx.world()).entitiesForRendering()) {
            if (entity instanceof ItemEntity ei && ei.isAlive() && !ei.getItem().isEmpty()) {
                if (matchesFilterOrDrop(filter, ei.getItem())) {
                    ret.add(entity.blockPosition());
                }
            }
        }
        return ret;
    }

    private int countMatchingItems(BlockOptionalMetaLookup activeFilter) {
        var player = ctx.player();
        if (player == null || activeFilter == null) {
            return 0;
        }
        int total = 0;
        for (var stack : player.getInventory().getNonEquipmentItems()) {
            if (!stack.isEmpty() && matchesFilterOrDrop(activeFilter, stack)) {
                total += stack.getCount();
            }
        }
        var offhand = player.getOffhandItem();
        if (!offhand.isEmpty() && matchesFilterOrDrop(activeFilter, offhand)) {
            total += offhand.getCount();
        }
        return total;
    }

    private static boolean matchesFilterOrDrop(
            BlockOptionalMetaLookup activeFilter,
            net.minecraft.world.item.ItemStack stack
    ) {
        if (activeFilter == null || stack == null || stack.isEmpty()) {
            return false;
        }
        if (activeFilter.has(stack)) {
            return true;
        }
        var blocks = DropSourceHolder.SOURCES.get(stack.getItem());
        if (blocks == null) {
            return false;
        }
        for (var block : blocks) {
            if (activeFilter.has(block)) {
                return true;
            }
        }
        return false;
    }

    private static final class DropSourceHolder {
        private static final java.util.Map<net.minecraft.world.item.Item, java.util.List<net.minecraft.world.level.block.Block>> SOURCES =
                java.util.Map.ofEntries(
                        java.util.Map.entry(Items.COBBLESTONE, java.util.List.of(net.minecraft.world.level.block.Blocks.STONE)),
                        java.util.Map.entry(Items.COBBLED_DEEPSLATE, java.util.List.of(net.minecraft.world.level.block.Blocks.DEEPSLATE)),
                        java.util.Map.entry(Items.RAW_IRON, java.util.List.of(net.minecraft.world.level.block.Blocks.IRON_ORE, net.minecraft.world.level.block.Blocks.DEEPSLATE_IRON_ORE)),
                        java.util.Map.entry(Items.COAL, java.util.List.of(net.minecraft.world.level.block.Blocks.COAL_ORE, net.minecraft.world.level.block.Blocks.DEEPSLATE_COAL_ORE)),
                        java.util.Map.entry(Items.RAW_GOLD, java.util.List.of(net.minecraft.world.level.block.Blocks.GOLD_ORE, net.minecraft.world.level.block.Blocks.DEEPSLATE_GOLD_ORE)),
                        java.util.Map.entry(Items.RAW_COPPER, java.util.List.of(net.minecraft.world.level.block.Blocks.COPPER_ORE, net.minecraft.world.level.block.Blocks.DEEPSLATE_COPPER_ORE)),
                        java.util.Map.entry(Items.DIAMOND, java.util.List.of(net.minecraft.world.level.block.Blocks.DIAMOND_ORE, net.minecraft.world.level.block.Blocks.DEEPSLATE_DIAMOND_ORE)),
                        java.util.Map.entry(Items.EMERALD, java.util.List.of(net.minecraft.world.level.block.Blocks.EMERALD_ORE, net.minecraft.world.level.block.Blocks.DEEPSLATE_EMERALD_ORE)),
                        java.util.Map.entry(Items.LAPIS_LAZULI, java.util.List.of(net.minecraft.world.level.block.Blocks.LAPIS_ORE, net.minecraft.world.level.block.Blocks.DEEPSLATE_LAPIS_ORE)),
                        java.util.Map.entry(Items.REDSTONE, java.util.List.of(net.minecraft.world.level.block.Blocks.REDSTONE_ORE, net.minecraft.world.level.block.Blocks.DEEPSLATE_REDSTONE_ORE)),
                        java.util.Map.entry(Items.GOLD_NUGGET, java.util.List.of(net.minecraft.world.level.block.Blocks.NETHER_GOLD_ORE)),
                        java.util.Map.entry(Items.QUARTZ, java.util.List.of(net.minecraft.world.level.block.Blocks.NETHER_QUARTZ_ORE)),
                        java.util.Map.entry(Items.FLINT, java.util.List.of(net.minecraft.world.level.block.Blocks.GRAVEL))
                );
    }

    public static List<BlockPos> searchWorld(CalculationContext ctx, BlockOptionalMetaLookup filter, int max, List<BlockPos> alreadyKnown, List<BlockPos> blacklist, List<BlockPos> dropped) {
        List<BlockPos> locs = new ArrayList<>();
        List<Block> untracked = new ArrayList<>();
        for (BlockOptionalMeta bom : filter.blocks()) {
            Block block = bom.getBlock();
            if (CachedChunk.BLOCKS_TO_KEEP_TRACK_OF.contains(block)) {
                BetterBlockPos pf = ctx.baritone.getPlayerContext().playerFeet();

                // maxRegionDistanceSq 2 means adjacent directly or adjacent diagonally; nothing further than that
                locs.addAll(ctx.worldData.getCachedWorld().getLocationsOf(
                        BlockUtils.blockToString(block),
                        Baritone.settings().maxCachedWorldScanCount.value,
                        pf.x,
                        pf.z,
                        2
                ));
            } else {
                untracked.add(block);
            }
        }

        locs = prune(ctx, locs, filter, max, blacklist, dropped);

        if (!untracked.isEmpty() || (Baritone.settings().extendCacheOnThreshold.value && locs.size() < max)) {
            locs.addAll(BaritoneAPI.getProvider().getWorldScanner().scanChunkRadius(
                    ctx.getBaritone().getPlayerContext(),
                    filter,
                    max,
                    10,
                    32
            )); // maxSearchRadius is NOT sq
        }

        locs.addAll(alreadyKnown);

        return prune(ctx, locs, filter, max, blacklist, dropped);
    }

    private boolean addNearby() {
        List<BlockPos> dropped = droppedItemsScan();
        knownOreLocations.addAll(dropped);
        BlockPos playerFeet = ctx.playerFeet();
        BlockStateInterface bsi = new BlockStateInterface(ctx);


        BlockOptionalMetaLookup filter = filterFilter();
        if (filter == null) {
            return false;
        }

        int searchDist = 10;
        double fakedBlockReachDistance = 20; // at least 10 * sqrt(3) with some extra space to account for positioning within the block
        for (int x = playerFeet.getX() - searchDist; x <= playerFeet.getX() + searchDist; x++) {
            for (int y = playerFeet.getY() - searchDist; y <= playerFeet.getY() + searchDist; y++) {
                for (int z = playerFeet.getZ() - searchDist; z <= playerFeet.getZ() + searchDist; z++) {
                    // crucial to only add blocks we can see because otherwise this
                    // is an x-ray and it'll get caught
                    if (filter.has(bsi.get0(x, y, z))) {
                        BlockPos pos = new BlockPos(x, y, z);
                        boolean adjacentVein = Baritone.settings().legitMineIncludeDiagonals.value
                                && knownOreLocations.stream().anyMatch(ore -> ore.distSqr(pos) <= 2 /* sq means this is pytha dist <= sqrt(2) */);
                        boolean visible = Baritone.settings().humanMode.value
                                ? perceptionGate.canPerceiveBlock(ctx, pos, fakedBlockReachDistance, HumanProfile.fromSettings())
                                : RotationUtils.reachable(ctx, pos, fakedBlockReachDistance).isPresent();
                        if (adjacentVein || visible) {
                            knownOreLocations.add(pos);
                        }
                    }
                }
            }
        }
        knownOreLocations = prune(new CalculationContext(baritone), knownOreLocations, filter, Baritone.settings().mineMaxOreLocationsCount.value, blacklist, dropped);
        return true;
    }

    private static List<BlockPos> prune(CalculationContext ctx, List<BlockPos> locs2, BlockOptionalMetaLookup filter, int max, List<BlockPos> blacklist, List<BlockPos> dropped) {
        // Keep dropped items even when directly underneath/adjacent to target blocks (e.g. felled tree trunks)
        List<BlockPos> locs = locs2
                .stream()
                .distinct()

                // remove any that are within loaded chunks that aren't actually what we want
                .filter(pos -> !ctx.bsi.worldContainsLoadedChunk(pos.getX(), pos.getZ()) || filter.has(ctx.get(pos.getX(), pos.getY(), pos.getZ())) || dropped.contains(pos))

                // remove any that are implausible to mine (encased in bedrock, or touching lava)
                .filter(pos -> MineProcess.plausibleToBreak(ctx, pos))

                .filter(pos -> {
                    if (Baritone.settings().allowOnlyExposedOres.value) {
                        return isNextToAir(ctx, pos);
                    } else {
                        return true;
                    }
                })

                .filter(pos -> pos.getY() >= Baritone.settings().minYLevelWhileMining.value + ctx.world.dimensionType().minY())

                .filter(pos -> pos.getY() <= Baritone.settings().maxYLevelWhileMining.value)

                .filter(pos -> !blacklist.contains(pos))

                .sorted(Comparator.comparingDouble(ctx.getBaritone().getPlayerContext().player().blockPosition()::distSqr))
                .collect(Collectors.toList());

        if (locs.size() > max) {
            return locs.subList(0, max);
        }
        return locs;
    }

    public static boolean isNextToAir(CalculationContext ctx, BlockPos pos) {
        int radius = Baritone.settings().allowOnlyExposedOresDistance.value;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (Math.abs(dx) + Math.abs(dy) + Math.abs(dz) <= radius
                            && MovementHelper.isTransparent(ctx.getBlock(pos.getX() + dx, pos.getY() + dy, pos.getZ() + dz))) {
                        return true;
                    }
                }
            }
        }
        return false;
    }


    public static boolean plausibleToBreak(CalculationContext ctx, BlockPos pos) {
        BlockState state = ctx.bsi.get0(pos);
        if (MovementHelper.getMiningDurationTicks(ctx, pos.getX(), pos.getY(), pos.getZ(), state, true) >= COST_INF) {
            return false;
        }
        if (MovementHelper.avoidBreaking(ctx.bsi, pos.getX(), pos.getY(), pos.getZ(), state)) {
            return false;
        }

        // bedrock above and below makes it implausible, otherwise we're good
        return !(ctx.bsi.get0(pos.above()).getBlock() == Blocks.BEDROCK && ctx.bsi.get0(pos.below()).getBlock() == Blocks.BEDROCK);
    }

    @Override
    public void mineByName(int quantity, String... blocks) {
        mine(quantity, new BlockOptionalMetaLookup(blocks));
    }

    @Override
    public void mine(int quantity, BlockOptionalMetaLookup filter) {
        this.filter = filter;
        if (this.filterFilter() == null) {
            this.filter = null;
        }
        this.desiredQuantity = quantity;
        this.knownOreLocations = new ArrayList<>();
        this.blacklist = new ArrayList<>();
        this.branchPoint = null;
        this.branchPointRunaway = null;
        this.activeDirectMinePos = null;
        this.justBrokenPos = null;
        this.waitDropTicks = 0;
        this.preBreakTargetItemCount = -1;
        this.airborneDirectBreakTicks = 0;
        this.trackedDropId = -1;
        this.trackedDropTicks = 0;
        this.blacklistedDropUntil.clear();
        this.anticipatedDrops = new HashMap<>();
        if (filter != null) {
            rescan(new ArrayList<>(), new CalculationContext(baritone));
        }
    }

    private BlockOptionalMetaLookup filterFilter() {
        if (this.filter == null) {
            return null;
        }
        if (!Baritone.settings().allowBreak.value) {
            BlockOptionalMetaLookup f = new BlockOptionalMetaLookup(this.filter.blocks()
                    .stream()
                    .filter(e -> Baritone.settings().allowBreakAnyway.value.contains(e.getBlock()))
                    .toArray(BlockOptionalMeta[]::new));
            if (f.blocks().isEmpty()) {
                logDirect("Unable to mine when allowBreak is false and target block is not in allowBreakAnyway!");
                return null;
            }
            return f;
        }
        return filter;
    }
}
