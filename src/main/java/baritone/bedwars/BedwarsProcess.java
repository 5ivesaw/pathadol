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
import baritone.bedwars.BedwarsMapCore.GenPOI;
import baritone.bedwars.BedwarsMapCore.GenType;
import baritone.bedwars.BedwarsMapCore.TeamColor;
import baritone.human.CraftingHelper;
import baritone.human.MouseModel;
import baritone.pathing.movement.MovementHelper;
import baritone.utils.BaritoneProcessHelper;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.NonNullList;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.AbstractFurnaceMenu;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.AirBlock;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
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
        RECALL
    }

    private TaskMode mode = TaskMode.NONE;
    private TeamColor rushFilter = TeamColor.UNKNOWN;
    private boolean recallForce = false;
    private int actionCooldown = 0;
    private long lastClutchAttemptTick = -100L;

    public BedwarsProcess(Baritone baritone) {
        super(baritone);
    }

    public synchronized String startRush(String selector) {
        ensureBedwarsActiveAndScanned();
        if (selector == null || selector.isBlank() || "next".equalsIgnoreCase(selector) || "side".equalsIgnoreCase(selector)) {
            this.rushFilter = TeamColor.UNKNOWN;
        } else {
            this.rushFilter = TeamColor.fromName(selector);
        }
        this.mode = TaskMode.RUSH;
        this.actionCooldown = 0;
        BedPOI target = selectTargetBed();
        if (target == null) {
            return "Bedwars rush active (awaiting living enemy bed discovery).";
        }
        return "Bedwars rushing " + target.team().name() + " bed at " + target.x() + "," + target.y() + "," + target.z();
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
        recallForce = false;
        actionCooldown = 0;
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
        double bestDistSq = Double.POSITIVE_INFINITY;
        BlockPos me = ctx.playerFeet();
        for (BedPOI b : core.getBeds()) {
            if (b.isHome() || b.destroyed()) {
                continue;
            }
            if (rushFilter != TeamColor.UNKNOWN && b.team() != rushFilter) {
                continue;
            }
            double dSq = me.distSqr(new BlockPos(b.x(), b.y(), b.z()));
            if (dSq < bestDistSq) {
                bestDistSq = dSq;
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
}
