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

package baritone.pathing.movement.movements;

import baritone.Baritone;
import baritone.api.IBaritone;
import baritone.api.pathing.movement.ActionCosts;
import baritone.api.pathing.movement.MovementStatus;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.IPlayerContext;
import baritone.api.utils.Rotation;
import baritone.api.utils.RotationUtils;
import baritone.api.utils.input.Input;
import baritone.human.HumanStyleLearner;
import baritone.human.PathSmoother;
import baritone.pathing.movement.CalculationContext;
import baritone.pathing.movement.Movement;
import baritone.pathing.movement.MovementHelper;
import baritone.pathing.movement.MovementState;
import baritone.utils.BlockStateInterface;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.HashSet;
import java.util.Set;

/**
 * Any-angle continuous ground traversal segment produced by Lazy Theta* path planning.
 *
 * <p>Executes straight-line steering across swept walkable corridors with continuous yaw,
 * corner-clearance guarantees, and high-speed sprint-jumping over unobstructed flat ice lanes.
 */
public class MovementTheta extends Movement {

    public static final double MAX_THETA_SEGMENT_DIST = 28.0;
    public static final double SWEPT_HALF_WIDTH = 0.34;
    public static final double SAMPLE_STEP = 0.25;

    public static final double BLUE_ICE_COST_FACTOR = 0.62;
    public static final double PACKED_ICE_COST_FACTOR = 0.72;
    public static final double ICE_COST_FACTOR = 0.78;

    public record LosResult(boolean clear, double cost, boolean allIce, boolean blueIce, boolean jumpHeadroom) {
        public static final LosResult BLOCKED = new LosResult(false, ActionCosts.COST_INF, false, false, false);
    }

    /**
     * Pure grid interface enabling Lazy Theta* swept LOS verification and cost calculation
     * without requiring a live Minecraft world in unit tests.
     */
    public interface WalkableGridProbe {
        boolean isLoaded(int x, int z);
        boolean isWalkableCell(int x, int y, int z);
        boolean isJumpHeadroomClear(int x, int y, int z);
        double terrainCostFactor(int x, int y, int z);
    }

    public MovementTheta(IBaritone baritone, BetterBlockPos src, BetterBlockPos dest) {
        super(baritone, src, dest, new BetterBlockPos[0], null);
    }

    @Override
    public double calculateCost(CalculationContext context) {
        LosResult res = hasWalkableLineOfSight(context, src.x, src.y, src.z, dest.x, dest.y, dest.z);
        return res.clear() ? res.cost() : ActionCosts.COST_INF;
    }

    @Override
    protected Set<BetterBlockPos> calculateValidPositions() {
        Set<BetterBlockPos> set = new HashSet<>();
        set.add(src);
        set.add(src.above());
        set.add(dest);
        set.add(dest.above());

        double x0 = src.x + 0.5;
        double z0 = src.z + 0.5;
        double x1 = dest.x + 0.5;
        double z1 = dest.z + 0.5;
        double dx = x1 - x0;
        double dz = z1 - z0;
        double dist = Math.hypot(dx, dz);
        if (dist < 1e-4) {
            return set;
        }
        double nx = -dz / dist;
        double nz = dx / dist;
        int steps = Math.max(2, (int) Math.ceil(dist / 0.25));
        double[] offsets = {-0.45, 0.0, 0.45};
        for (int i = 0; i <= steps; i++) {
            double t = (double) i / steps;
            double px = x0 + dx * t;
            double pz = z0 + dz * t;
            for (double w : offsets) {
                int bx = (int) Math.floor(px + nx * w);
                int bz = (int) Math.floor(pz + nz * w);
                set.add(new BetterBlockPos(bx, src.y, bz));
                set.add(new BetterBlockPos(bx, src.y + 1, bz));
            }
        }
        return set;
    }

    @Override
    public MovementState updateState(MovementState state) {
        super.updateState(state);
        if (state.getStatus() != MovementStatus.RUNNING) {
            return state;
        }

        BlockPos feet = ctx.playerFeet();
        if (feet.getY() < src.getY() - 1) {
            return state.setStatus(MovementStatus.UNREACHABLE);
        }

        Vec3 pos = ctx.player().position();
        double tx = dest.getX() + 0.5;
        double tz = dest.getZ() + 0.5;
        double dx = tx - pos.x;
        double dz = tz - pos.z;
        double horizDist = Math.hypot(dx, dz);

        if ((feet.equals(dest) && horizDist <= 0.68)
                || (Math.abs(pos.y - dest.getY()) <= 1.35 && horizDist <= 0.45)) {
            return state.setStatus(MovementStatus.SUCCESS);
        }

        float moveYaw = PathSmoother.computeYawTo(pos.x, pos.z, tx, tz);
        // Project gaze slightly ahead past dest when close so head pitch/yaw stays rock-steady near endpoints
        double gazeDist = Math.max(4.5, horizDist);
        double ux = horizDist > 1e-4 ? dx / horizDist : 0.0;
        double uz = horizDist > 1e-4 ? dz / horizDist : 0.0;
        Vec3 gazeTarget = new Vec3(pos.x + ux * gazeDist, dest.getY() + ctx.player().getEyeHeight(), pos.z + uz * gazeDist);
        Rotation lookRot = RotationUtils.calcRotationFromVec3d(ctx.playerHead(), gazeTarget, ctx.playerRotations());

        state.setTarget(new MovementState.MovementTarget(lookRot, false));
        state.setDesiredMoveYaw(moveYaw);
        state.setInput(Input.MOVE_FORWARD, true);

        if (Baritone.settings().allowSprint.value) {
            state.setInput(Input.SPRINT, true);
        }

        if (shouldIceSprintJump(ctx, src.getY(), pos, ux, uz, horizDist)) {
            state.setInput(Input.JUMP, true);
        }

        return state;
    }

    /**
     * Checks whether sprint-jumping on flat unobstructed ice is safe and beneficial along {@code (ux, uz)}.
     */
    public static boolean shouldIceSprintJump(
            IPlayerContext ctx,
            int groundY,
            Vec3 pos,
            double ux,
            double uz,
            double remainingDist
    ) {
        if (!Baritone.settings().allowSprint.value || remainingDist < 2.1) {
            return false;
        }
        if (Baritone.settings().humanMode.value
                && "learned".equalsIgnoreCase(Baritone.settings().humanProfile.value)) {
            HumanStyleLearner.LearnedState st = HumanStyleLearner.activeLearnedState();
            if (st != null && st.trialsLearned > 0 && !st.iceSprintJumpPreferred) {
                return false;
            }
        }
        var player = ctx.player();
        if (!player.onGround() || player.isInWater() || player.isShiftKeyDown()) {
            return false;
        }
        BlockStateInterface bsi = new BlockStateInterface(ctx);
        int curX = (int) Math.floor(pos.x);
        int curZ = (int) Math.floor(pos.z);
        Block under = bsi.get0(curX, groundY - 1, curZ).getBlock();
        if (!isIceBlock(under)) {
            return false;
        }
        // Check ceiling + floor along landing zone (~1.0 to 3.5 blocks ahead) so we never bonk or jump into holes
        double checkMax = Math.min(remainingDist - 0.3, 3.6);
        for (double d = 0.6; d <= checkMax; d += 0.6) {
            int bx = (int) Math.floor(pos.x + ux * d);
            int bz = (int) Math.floor(pos.z + uz * d);
            BlockState floor = bsi.get0(bx, groundY - 1, bz);
            if (!MovementHelper.canWalkOn(bsi, bx, groundY - 1, bz, floor) || floor.getFluidState().isEmpty() == false) {
                return false;
            }
            if (!MovementHelper.fullyPassable(ctx, new BlockPos(bx, groundY + 2, bz))
                    || !MovementHelper.fullyPassable(ctx, new BlockPos(bx, groundY + 1, bz))
                    || !MovementHelper.fullyPassable(ctx, new BlockPos(bx, groundY, bz))) {
                return false;
            }
        }
        return true;
    }

    public static LosResult hasWalkableLineOfSight(
            CalculationContext context,
            int x0, int y0, int z0,
            int x1, int y1, int z1
    ) {
        if (y0 != y1) {
            return LosResult.BLOCKED;
        }
        WalkableGridProbe probe = new WalkableGridProbe() {
            @Override
            public boolean isLoaded(int x, int z) {
                return context.isLoaded(x, z);
            }

            @Override
            public boolean isWalkableCell(int x, int y, int z) {
                return isThetaWalkableCell(context, x, y, z);
            }

            @Override
            public boolean isJumpHeadroomClear(int x, int y, int z) {
                return MovementHelper.fullyPassable(context, x, y + 2, z);
            }

            @Override
            public double terrainCostFactor(int x, int y, int z) {
                return blockSpeedCostFactor(context.getBlock(x, y - 1, z));
            }
        };
        double basePerBlock = context.canSprint ? ActionCosts.SPRINT_ONE_BLOCK_COST : ActionCosts.WALK_ONE_BLOCK_COST;
        return hasWalkableLineOfSight(probe, x0, y0, z0, x1, y1, z1, basePerBlock);
    }

    /**
     * Performs a swept capsule line-of-sight check from center of {@code (x0,y0,z0)} to
     * {@code (x1,y1,z1)} across [-0.34, 0, +0.34] lateral clearance and 0.25-block steps.
     */
    public static LosResult hasWalkableLineOfSight(
            WalkableGridProbe probe,
            int x0, int y0, int z0,
            int x1, int y1, int z1,
            double baseCostPerBlock
    ) {
        if (y0 != y1) {
            return LosResult.BLOCKED;
        }
        double dx = x1 - x0;
        double dz = z1 - z0;
        double dist = Math.hypot(dx, dz);
        if (dist < 1e-4 || dist > MAX_THETA_SEGMENT_DIST) {
            return LosResult.BLOCKED;
        }
        if (!probe.isLoaded(x0, z0) || !probe.isLoaded(x1, z1)) {
            return LosResult.BLOCKED;
        }

        double ux = dx / dist;
        double uz = dz / dist;
        double nx = -uz;
        double nz = ux;
        double ax = x0 + 0.5;
        double az = z0 + 0.5;

        int steps = Math.max(2, (int) Math.ceil(dist / SAMPLE_STEP));
        int lastCenterX = Integer.MIN_VALUE;
        int lastCenterZ = Integer.MIN_VALUE;
        double factorSum = 0.0;
        int centerCells = 0;
        boolean allIce = true;
        boolean anyBlueIce = false;
        boolean headroom = true;

        double[] lateralOffsets = {0.0, -SWEPT_HALF_WIDTH, SWEPT_HALF_WIDTH};
        for (int i = 0; i <= steps; i++) {
            double t = dist * i / steps;
            double cx = ax + ux * t;
            double cz = az + uz * t;

            for (int lane = 0; lane < lateralOffsets.length; lane++) {
                double w = lateralOffsets[lane];
                int bx = (int) Math.floor(cx + nx * w);
                int bz = (int) Math.floor(cz + nz * w);
                if (!probe.isLoaded(bx, bz) || !probe.isWalkableCell(bx, y0, bz)) {
                    return LosResult.BLOCKED;
                }
                if (lane == 0 && (bx != lastCenterX || bz != lastCenterZ)) {
                    lastCenterX = bx;
                    lastCenterZ = bz;
                    double f = probe.terrainCostFactor(bx, y0, bz);
                    factorSum += f;
                    centerCells++;
                    if (f >= 0.99) {
                        allIce = false;
                    }
                    if (f <= BLUE_ICE_COST_FACTOR + 0.01) {
                        anyBlueIce = true;
                    }
                    if (headroom && !probe.isJumpHeadroomClear(bx, y0, bz)) {
                        headroom = false;
                    }
                }
            }
        }

        double avgFactor = centerCells > 0 ? (factorSum / centerCells) : 1.0;
        double cost = Math.max(0.05, dist * baseCostPerBlock * avgFactor);
        return new LosResult(true, cost, allIce, anyBlueIce, headroom);
    }

    /**
     * Quick lower-bound Euclidean cost estimate between {@code (x0,z0)} and {@code (x1,z1)}
     * for Lazy Theta* optimistic push without executing full swept raycasts.
     */
    public static double optimisticCost(CalculationContext context, int x0, int y, int z0, int x1, int z1) {
        double dist = Math.hypot(x1 - x0, z1 - z0);
        if (dist < 1e-4 || dist > MAX_THETA_SEGMENT_DIST) {
            return ActionCosts.COST_INF;
        }
        double base = context.canSprint ? ActionCosts.SPRINT_ONE_BLOCK_COST : ActionCosts.WALK_ONE_BLOCK_COST;
        int mx = (x0 + x1) >> 1;
        int mz = (z0 + z1) >> 1;
        double f0 = blockSpeedCostFactor(context.getBlock(x0, y - 1, z0));
        double fm = blockSpeedCostFactor(context.getBlock(mx, y - 1, mz));
        double f1 = blockSpeedCostFactor(context.getBlock(x1, y - 1, z1));
        double minFactor = Math.min(f0, Math.min(fm, f1));
        return dist * base * minFactor;
    }

    public static boolean isThetaWalkableCell(CalculationContext context, int x, int y, int z) {
        BlockState floor = context.get(x, y - 1, z);
        if (!MovementHelper.canWalkOn(context, x, y - 1, z, floor)) {
            return false;
        }
        Block fb = floor.getBlock();
        if (MovementHelper.isWater(floor) || MovementHelper.isLava(floor)
                || fb == Blocks.MAGMA_BLOCK || fb == Blocks.SOUL_SAND
                || fb == Blocks.HONEY_BLOCK || MovementHelper.isClimbable(fb)) {
            return false;
        }
        BlockState feet = context.get(x, y, z);
        BlockState head = context.get(x, y + 1, z);
        if (!MovementHelper.fullyPassable(context, x, y, z, feet)
                || !MovementHelper.fullyPassable(context, x, y + 1, z, head)) {
            return false;
        }
        if (feet.getBlock() instanceof DoorBlock || feet.getBlock() instanceof FenceGateBlock
                || head.getBlock() instanceof DoorBlock || head.getBlock() instanceof FenceGateBlock) {
            return false;
        }
        return !MovementHelper.avoidWalkingInto(feet) && !MovementHelper.avoidWalkingInto(head);
    }

    public static double blockSpeedCostFactor(Block block) {
        if (block == Blocks.BLUE_ICE) {
            return BLUE_ICE_COST_FACTOR;
        }
        if (block == Blocks.PACKED_ICE) {
            return PACKED_ICE_COST_FACTOR;
        }
        if (block == Blocks.ICE || block == Blocks.FROSTED_ICE) {
            return ICE_COST_FACTOR;
        }
        return 1.0;
    }

    public static boolean isIceBlock(Block block) {
        return block == Blocks.ICE
                || block == Blocks.PACKED_ICE
                || block == Blocks.BLUE_ICE
                || block == Blocks.FROSTED_ICE;
    }
}
