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
import baritone.api.pathing.movement.MovementStatus;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.Rotation;
import baritone.api.utils.RotationUtils;
import baritone.api.utils.VecUtils;
import baritone.api.utils.input.Input;
import baritone.pathing.movement.CalculationContext;
import baritone.pathing.movement.Movement;
import baritone.pathing.movement.MovementHelper;
import baritone.pathing.movement.MovementState;
import baritone.utils.BlockStateInterface;
import baritone.utils.pathing.MutableMoveResult;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.HashSet;
import java.util.Set;

/**
 * Any-angle offset parkour leap (diagonal 2x2 / 3x3, knight's-move 2x1, skewed 3x1, and optional +1Y step-up).
 */
public class MovementParkourOffset extends Movement {

    private static final BetterBlockPos[] EMPTY = new BetterBlockPos[]{};
    private static final double MAX_FLAT_JUMP_SQ = 18.5; // covers (3,3) -> 18.0
    private static final double MAX_ASCEND_JUMP_SQ = 10.5; // covers (3,1) -> 10.0

    private final int dx;
    private final int dz;
    private final boolean ascend;
    private final double horizontalLen;
    private int parkourHesitateTicks = -1;

    private MovementParkourOffset(IBaritone baritone, BetterBlockPos src, int dx, int dz, boolean ascend) {
        super(baritone, src, new BetterBlockPos(src.x + dx, src.y + (ascend ? 1 : 0), src.z + dz), EMPTY);
        this.dx = dx;
        this.dz = dz;
        this.ascend = ascend;
        this.horizontalLen = Math.hypot(dx, dz);
    }

    @Override
    public void reset() {
        super.reset();
        this.parkourHesitateTicks = -1;
    }

    public static MovementParkourOffset cost(CalculationContext context, BetterBlockPos src, int dx, int dz) {
        MutableMoveResult res = new MutableMoveResult();
        cost(context, src.x, src.y, src.z, dx, dz, res);
        return new MovementParkourOffset(context.getBaritone(), src, dx, dz, res.y > src.y);
    }

    public static void cost(CalculationContext context, int x, int y, int z, int dx, int dz, MutableMoveResult res) {
        if (!context.allowParkour) {
            return;
        }
        int distSq = dx * dx + dz * dz;
        if (distSq > MAX_FLAT_JUMP_SQ) {
            return;
        }
        boolean needSprint = distSq >= 8;
        if (needSprint && !context.canSprint) {
            return;
        }
        int destX = x + dx;
        int destZ = z + dz;

        // Quick O(1) landing floor probe before scanning swept trajectory
        BlockState flatLanding = context.bsi.get0(destX, y - 1, destZ);
        boolean canLandFlat = flatLanding.getBlock() != Blocks.FARMLAND
                && MovementHelper.canWalkOn(context, destX, y - 1, destZ, flatLanding);

        BlockState ascendBlock = context.bsi.get0(destX, y, destZ);
        boolean canLandAscend = !canLandFlat
                && distSq <= MAX_ASCEND_JUMP_SQ
                && context.allowParkourAscend
                && context.canSprint
                && MovementHelper.canWalkOn(context, destX, y, destZ, ascendBlock);

        if (!canLandFlat && !canLandAscend) {
            return;
        }

        if (!context.allowJumpAtBuildLimit && y >= context.world.getMaxY()) {
            return;
        }

        // Takeoff ground and headroom validation
        BlockState standingOn = context.get(x, y - 1, z);
        if (MovementHelper.isClimbable(standingOn.getBlock())
                || standingOn.getBlock() instanceof StairBlock
                || MovementHelper.isBottomSlab(standingOn)
                || (context.allowWalkOnMagmaBlocks && standingOn.is(Blocks.MAGMA_BLOCK))
                || standingOn.getBlock() == Blocks.SOUL_SAND) {
            return;
        }
        if (context.assumeWalkOnWater && !standingOn.getFluidState().isEmpty()) {
            return;
        }
        if (!context.get(x, y, z).getFluidState().isEmpty()) {
            return;
        }
        if (!MovementHelper.fullyPassable(context, x, y + 2, z)) {
            return;
        }

        // Destination clearance and landing overshoot safety
        if (canLandFlat) {
            if (!MovementHelper.fullyPassable(context, destX, y, destZ)
                    || !MovementHelper.fullyPassable(context, destX, y + 1, destZ)
                    || !MovementHelper.fullyPassable(context, destX, y + 2, destZ)) {
                return;
            }
        } else {
            if (!MovementHelper.fullyPassable(context, destX, y + 1, destZ)
                    || !MovementHelper.fullyPassable(context, destX, y + 2, destZ)) {
                return;
            }
        }

        int sx = Integer.compare(dx, 0);
        int sz = Integer.compare(dz, 0);
        if (MovementHelper.avoidWalkingInto(context.bsi.get0(destX + sx, canLandAscend ? y + 1 : y, destZ + sz))
                || MovementHelper.avoidWalkingInto(context.bsi.get0(destX + sx, canLandAscend ? y + 2 : y + 1, destZ + sz))) {
            return;
        }

        // Verify at least one gap cell below y (don't use offset parkour across flat solid ground) and clear swept flight corridor
        boolean hasGap = false;
        double startCx = x + 0.5;
        double startCz = z + 0.5;
        int samples = 6;
        double[] cornerOffsets = new double[]{-0.28, 0.28};
        for (int s = 1; s < samples; s++) {
            double t = (double) s / samples;
            double cx = startCx + dx * t;
            double cz = startCz + dz * t;
            int midBx = (int) Math.floor(cx);
            int midBz = (int) Math.floor(cz);
            if (!MovementHelper.canWalkOn(context, midBx, y - 1, midBz)) {
                hasGap = true;
            }
            for (double ox : cornerOffsets) {
                for (double oz : cornerOffsets) {
                    int bx = (int) Math.floor(cx + ox);
                    int bz = (int) Math.floor(cz + oz);
                    if (bx == destX && bz == destZ && canLandAscend) {
                        continue;
                    }
                    if (!MovementHelper.fullyPassable(context, bx, y, bz)
                            || !MovementHelper.fullyPassable(context, bx, y + 1, bz)
                            || !MovementHelper.fullyPassable(context, bx, y + 2, bz)) {
                        return;
                    }
                    if (s >= 2 && s <= 4 && !MovementHelper.fullyPassable(context, bx, y + 3, bz) && distSq >= 12) {
                        return;
                    }
                }
            }
        }
        if (!hasGap) {
            return;
        }

        double len = Math.sqrt(distSq);
        double stepCost = needSprint ? SPRINT_ONE_BLOCK_COST : WALK_ONE_BLOCK_COST;
        res.x = destX;
        res.y = canLandAscend ? y + 1 : y;
        res.z = destZ;
        res.cost = len * stepCost + context.jumpPenalty + (canLandAscend ? 1.6 : 0.45);
    }

    @Override
    public double calculateCost(CalculationContext context) {
        MutableMoveResult res = new MutableMoveResult();
        cost(context, src.x, src.y, src.z, dx, dz, res);
        if (res.x != dest.x || res.y != dest.y || res.z != dest.z) {
            return COST_INF;
        }
        return res.cost;
    }

    @Override
    protected Set<BetterBlockPos> calculateValidPositions() {
        Set<BetterBlockPos> set = new HashSet<>();
        int steps = Math.max(4, (int) Math.ceil(horizontalLen * 2.5));
        double[] offsets = new double[]{-0.35, 0.0, 0.35};
        for (int s = 0; s <= steps; s++) {
            double t = (double) s / steps;
            double cx = src.x + 0.5 + dx * t;
            double cz = src.z + 0.5 + dz * t;
            for (double ox : offsets) {
                for (double oz : offsets) {
                    int bx = (int) Math.floor(cx + ox);
                    int bz = (int) Math.floor(cz + oz);
                    for (int dy = 0; dy <= 2; dy++) {
                        set.add(new BetterBlockPos(bx, src.y + dy, bz));
                    }
                }
            }
        }
        return set;
    }

    @Override
    public boolean safeToCancel(MovementState state) {
        return state.getStatus() != MovementStatus.RUNNING;
    }

    @Override
    public MovementState updateState(MovementState state) {
        super.updateState(state);
        if (state.getStatus() != MovementStatus.RUNNING) {
            return state;
        }
        if (ctx.player().position().y < src.y - 0.85) {
            return state.setStatus(MovementStatus.UNREACHABLE);
        }

        if (Baritone.settings().humanMode.value && ctx.playerFeet().equals(src)) {
            Rotation toDest = RotationUtils.calcRotationFromVec3d(
                    ctx.playerHead(),
                    VecUtils.getBlockPosCenter(dest),
                    ctx.playerRotations()
            );
            float yawErr = Math.abs(Rotation.yawDistanceFromOffset(ctx.playerRotations().getYaw(), toDest.getYaw()));
            if (yawErr > 45.0f) {
                state.setTarget(new MovementState.MovementTarget(new Rotation(toDest.getYaw(), Math.min(18.0f, toDest.getPitch())), false));
                state.setInput(Input.MOVE_FORWARD, false);
                state.setInput(Input.SPRINT, false);
                state.setInput(Input.SNEAK, true);
                return state;
            }
            if (horizontalLen >= 2.8) {
                if (parkourHesitateTicks < 0) {
                    long h = src.asLong() * 0x9E3779B97F4A7C15L;
                    double roll = ((h >>> 16) & 0xFFL) / 255.0;
                    double chance = Math.min(0.55, Baritone.settings().humanMistakeRate.value * 8.0 + 0.12);
                    parkourHesitateTicks = roll < chance ? 1 : 0;
                }
                if (parkourHesitateTicks > 0) {
                    parkourHesitateTicks--;
                    MovementHelper.moveTowards(ctx, state, dest);
                    state.setInput(Input.MOVE_FORWARD, false);
                    state.setInput(Input.SPRINT, false);
                    return state;
                }
            }
        }

        if (horizontalLen >= 2.4 || ascend) {
            state.setInput(Input.SPRINT, true);
        }

        MovementHelper.moveTowards(ctx, state, dest);

        if (ctx.playerFeet().equals(dest)) {
            Block d = BlockStateInterface.getBlock(ctx, dest);
            if (d == Blocks.VINE || d == Blocks.LADDER) {
                return state.setStatus(MovementStatus.SUCCESS);
            }
            if (ctx.player().onGround() || ctx.player().position().y - dest.y < 0.094) {
                return state.setStatus(MovementStatus.SUCCESS);
            }
        } else if (ctx.player().onGround()) {
            double ux = dx / horizontalLen;
            double uz = dz / horizontalLen;
            double offX = ctx.player().position().x - (src.x + 0.5);
            double offZ = ctx.player().position().z - (src.z + 0.5);
            double proj = offX * ux + offZ * uz;
            // Exact exit boundary of the takeoff block's 1x1 footprint along jump direction
            double tExit = 0.5 / Math.max(Math.abs(ux), Math.abs(uz));
            if (!ctx.playerFeet().equals(src) || proj >= tExit - 0.14) {
                state.setInput(Input.JUMP, true);
            }
        }
        return state;
    }
}
