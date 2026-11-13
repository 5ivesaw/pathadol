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
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.WaterFluid;

import java.util.HashSet;
import java.util.Set;

public class MovementParkour extends Movement {

    private static final BetterBlockPos[] EMPTY = new BetterBlockPos[]{};

    private final Direction direction;
    private final int dist;
    private final int yDelta;
    private int parkourHesitateTicks = -1;

    private MovementParkour(IBaritone baritone, BetterBlockPos src, int dist, Direction dir, int yDelta) {
        super(baritone, src, src.relative(dir, dist).above(yDelta), EMPTY, src.relative(dir, dist).above(yDelta - 1));
        this.direction = dir;
        this.dist = dist;
        this.yDelta = yDelta;
    }

    @Override
    public void reset() {
        super.reset();
        this.parkourHesitateTicks = -1;
    }

    public static MovementParkour cost(CalculationContext context, BetterBlockPos src, Direction direction) {
        MutableMoveResult res = new MutableMoveResult();
        cost(context, src.x, src.y, src.z, direction, res);
        int dist = Math.abs(res.x - src.x) + Math.abs(res.z - src.z);
        return new MovementParkour(context.getBaritone(), src, dist, direction, res.y - src.y);
    }

    public static void cost(CalculationContext context, int x, int y, int z, Direction dir, MutableMoveResult res) {
        if (!context.allowParkour) {
            return;
        }
        if (!context.allowJumpAtBuildLimit && y >= context.world.getMaxY()) {
            return;
        }
        int xDiff = dir.getStepX();
        int zDiff = dir.getStepZ();
        if (!MovementHelper.fullyPassable(context, x + xDiff, y, z + zDiff)) {
            // most common case at the top -- the adjacent block isn't air
            return;
        }
        BlockState adj = context.get(x + xDiff, y - 1, z + zDiff);
        if (MovementHelper.canWalkOn(context, x + xDiff, y - 1, z + zDiff, adj)) { // don't parkour if we could just traverse (for now)
            // second most common case -- we could just traverse not parkour
            return;
        }
        if (MovementHelper.avoidWalkingInto(adj) && !(adj.getFluidState().getType() instanceof WaterFluid)) { // magma sucks
            return;
        }
        if (!MovementHelper.fullyPassable(context, x + xDiff, y + 1, z + zDiff)) {
            return;
        }
        if (!MovementHelper.fullyPassable(context, x + xDiff, y + 2, z + zDiff)) {
            return;
        }
        if (!MovementHelper.fullyPassable(context, x, y + 2, z)) {
            return;
        }
        BlockState standingOn = context.get(x, y - 1, z);
        if (MovementHelper.isClimbable(standingOn.getBlock()) || standingOn.getBlock() instanceof StairBlock || MovementHelper.isBottomSlab(standingOn)) {
            return;
        }
        // we can't jump from (frozen) water with assumeWalkOnWater because we can't be sure it will be frozen
        if (context.assumeWalkOnWater && !standingOn.getFluidState().isEmpty()) {
            return;
        }
        if (!context.get(x, y, z).getFluidState().isEmpty()) {
            return; // can't jump out of water
        }
        int maxJump;
        Block standingBlock = standingOn.getBlock();
        if (context.allowWalkOnMagmaBlocks && standingOn.is(Blocks.MAGMA_BLOCK)) {
            maxJump = 2;
        } else if (standingBlock == Blocks.SOUL_SAND) {
            maxJump = 2; // 1 block gap
        } else if (context.canSprint) {
            if (standingBlock == Blocks.BLUE_ICE) {
                maxJump = 6;
            } else if (standingBlock == Blocks.ICE || standingBlock == Blocks.PACKED_ICE) {
                maxJump = 5;
            } else {
                maxJump = 4;
            }
        } else {
            maxJump = 3;
        }

        // check parkour jumps from smallest to largest for obstacles/walls and landing positions
        int verifiedMaxJump = 1; // i - 1 (when i = 2)
        for (int i = 2; i <= maxJump; i++) {
            int destX = x + xDiff * i;
            int destZ = z + zDiff * i;

            // check head/feet
            if (!MovementHelper.fullyPassable(context, destX, y + 1, destZ)) {
                break;
            }
            if (!MovementHelper.fullyPassable(context, destX, y + 2, destZ)) {
                break;
            }

            // check for ascend landing position
            BlockState destInto = context.bsi.get0(destX, y, destZ);
            if (!MovementHelper.fullyPassable(context, destX, y, destZ, destInto)) {
                if (i <= 3 && context.allowParkourAscend && context.canSprint && MovementHelper.canWalkOn(context, destX, y, destZ, destInto) && checkOvershootSafety(context.bsi, destX + xDiff, y + 1, destZ + zDiff)) {
                    res.x = destX;
                    res.y = y + 1;
                    res.z = destZ;
                    res.cost = i * SPRINT_ONE_BLOCK_COST + context.jumpPenalty;
                    return;
                }
                break;
            }

            // check for flat landing position
            BlockState landingOn = context.bsi.get0(destX, y - 1, destZ);
            // farmland needs to be canWalkOn otherwise farm can never work at all, but we want to specifically disallow ending a jump on farmland haha
            // frostwalker works here because we can't jump from possibly unfrozen water
            if ((landingOn.getBlock() != Blocks.FARMLAND && MovementHelper.canWalkOn(context, destX, y - 1, destZ, landingOn))
                    || (Math.min(16, context.frostWalker + 2) >= i && MovementHelper.canUseFrostWalker(context, landingOn))
            ) {
                if (checkOvershootSafety(context.bsi, destX + xDiff, y, destZ + zDiff)) {
                    res.x = destX;
                    res.y = y;
                    res.z = destZ;
                    res.cost = costFromJumpDistance(i) + context.jumpPenalty;
                    return;
                }
                break;
            }

            // check for -1Y descending parkour landing across a gap
            if (i <= (context.canSprint ? 5 : 3)
                    && (i >= 3 || !MovementHelper.canWalkOn(context, x + xDiff, y - 2, z + zDiff))
                    && MovementHelper.fullyPassable(context, destX, y - 1, destZ, landingOn)) {
                BlockState descendOn = context.bsi.get0(destX, y - 2, destZ);
                if (descendOn.getBlock() != Blocks.FARMLAND
                        && MovementHelper.canWalkOn(context, destX, y - 2, destZ, descendOn)
                        && checkOvershootSafety(context.bsi, destX + xDiff, y - 1, destZ + zDiff)) {
                    res.x = destX;
                    res.y = y - 1;
                    res.z = destZ;
                    res.cost = costFromJumpDistance(i) + context.jumpPenalty + 0.5;
                    return;
                }
            }

            if (!MovementHelper.fullyPassable(context, destX, y + 3, destZ)) {
                // Allow a 2-block flat headhitter sprint jump under a y+3 ceiling (3.0m headroom),
                // since only 3-block (i=4) jumps require peak clearance above y+3.
                if (i == 2 && context.canSprint && !MovementHelper.avoidWalkingInto(context.get(destX, y + 3, destZ))) {
                    verifiedMaxJump = i;
                    continue;
                }
                break;
            }

            verifiedMaxJump = i;
        }

        // parkour place starts here
        if (!context.allowParkourPlace) {
            return;
        }
        // check parkour jumps from largest to smallest for positions to place blocks
        for (int i = verifiedMaxJump; i > 1; i--) {
            int destX = x + i * xDiff;
            int destZ = z + i * zDiff;
            BlockState toReplace = context.get(destX, y - 1, destZ);
            double placeCost = context.costOfPlacingAt(destX, y - 1, destZ, toReplace);
            if (placeCost >= COST_INF) {
                continue;
            }
            if (!MovementHelper.isReplaceable(destX, y - 1, destZ, toReplace, context.bsi)) {
                continue;
            }
            if (!checkOvershootSafety(context.bsi, destX + xDiff, y, destZ + zDiff)) {
                continue;
            }
            for (int j = 0; j < 5; j++) {
                int againstX = destX + HORIZONTALS_BUT_ALSO_DOWN_____SO_EVERY_DIRECTION_EXCEPT_UP[j].getStepX();
                int againstY = y - 1 + HORIZONTALS_BUT_ALSO_DOWN_____SO_EVERY_DIRECTION_EXCEPT_UP[j].getStepY();
                int againstZ = destZ + HORIZONTALS_BUT_ALSO_DOWN_____SO_EVERY_DIRECTION_EXCEPT_UP[j].getStepZ();
                if (againstX == destX - xDiff && againstZ == destZ - zDiff) { // we can't turn around that fast
                    continue;
                }
                if (MovementHelper.canPlaceAgainst(context.bsi, againstX, againstY, againstZ)) {
                    res.x = destX;
                    res.y = y;
                    res.z = destZ;
                    res.cost = costFromJumpDistance(i) + placeCost + context.jumpPenalty;
                    return;
                }
            }
        }
    }

    private static boolean checkOvershootSafety(BlockStateInterface bsi, int x, int y, int z) {
        // we're going to walk into these two blocks after the landing of the parkour anyway, so make sure they aren't avoidWalkingInto
        return !MovementHelper.avoidWalkingInto(bsi.get0(x, y, z)) && !MovementHelper.avoidWalkingInto(bsi.get0(x, y + 1, z));
    }

    public static double costFromJumpDistance(int dist) {
        switch (dist) {
            case 2:
                return WALK_ONE_BLOCK_COST * 2; // IDK LOL
            case 3:
                return WALK_ONE_BLOCK_COST * 3;
            case 4:
                return SPRINT_ONE_BLOCK_COST * 4;
            case 5:
                return SPRINT_ONE_BLOCK_COST * 5;
            case 6:
                return SPRINT_ONE_BLOCK_COST * 6;
            default:
                throw new IllegalStateException("LOL " + dist);
        }
    }


    @Override
    public double calculateCost(CalculationContext context) {
        MutableMoveResult res = new MutableMoveResult();
        cost(context, src.x, src.y, src.z, direction, res);
        if (res.x != dest.x || res.y != dest.y || res.z != dest.z) {
            return COST_INF;
        }
        return res.cost;
    }

    @Override
    protected Set<BetterBlockPos> calculateValidPositions() {
        Set<BetterBlockPos> set = new HashSet<>();
        int minY = yDelta < 0 ? -1 : 0;
        int maxY = yDelta > 0 ? 2 : 1;
        for (int i = 0; i <= dist; i++) {
            for (int y = minY; y <= maxY; y++) {
                set.add(src.relative(direction, i).above(y));
            }
        }
        return set;
    }

    @Override
    public boolean safeToCancel(MovementState state) {
        // once this movement is instantiated, the state is default to PREPPING
        // but once it's ticked for the first time it changes to RUNNING
        // since we don't really know anything about momentum, it suffices to say Parkour can only be canceled on the 0th tick
        return state.getStatus() != MovementStatus.RUNNING;
    }

    @Override
    public MovementState updateState(MovementState state) {
        super.updateState(state);
        if (state.getStatus() != MovementStatus.RUNNING) {
            return state;
        }
        if (ctx.playerFeet().y < src.y + Math.min(0, yDelta)) {
            // we have fallen
            logDebug("sorry");
            return state.setStatus(MovementStatus.UNREACHABLE);
        }
        Block srcFloor = BlockStateInterface.getBlock(ctx, src.below());
        boolean onIce = srcFloor == Blocks.ICE || srcFloor == Blocks.PACKED_ICE || srcFloor == Blocks.BLUE_ICE;
        if (dist >= 4 || yDelta != 0 || onIce) {
            state.setInput(Input.SPRINT, true);
        }
        if (Baritone.settings().allowWalkOnMagmaBlocks.value && ctx.world().getBlockState(ctx.playerFeet().below()).is(Blocks.MAGMA_BLOCK)) {
            state.setInput(Input.SNEAK, true);
        }

        double hSpd = Math.hypot(ctx.player().getDeltaMovement().x, ctx.player().getDeltaMovement().z);
        if (Baritone.settings().humanMode.value && ctx.playerFeet().equals(src) && !onIce && hSpd < 0.10) {
            Rotation toDest = RotationUtils.calcRotationFromVec3d(
                    ctx.playerHead(),
                    VecUtils.getBlockPosCenter(dest),
                    ctx.playerRotations()
            );
            float yawErr = Math.abs(Rotation.yawDistanceFromOffset(ctx.playerRotations().getYaw(), toDest.getYaw()));
            if (yawErr > 48.0f) {
                state.setTarget(new MovementState.MovementTarget(new Rotation(toDest.getYaw(), Math.min(18.0f, toDest.getPitch())), false));
                state.setInput(Input.MOVE_FORWARD, false);
                state.setInput(Input.SPRINT, false);
                state.setInput(Input.SNEAK, true);
                return state;
            }
        }

        if (Baritone.settings().humanMode.value && dist == 3 && yDelta == 0 && !onIce && ctx.playerFeet().equals(src)) {
            if (parkourHesitateTicks < 0) {
                long h = src.asLong() * 0x9E3779B97F4A7C15L;
                double roll = ((h >>> 16) & 0xFFL) / 255.0;
                double chance = Math.min(0.45, Baritone.settings().humanMistakeRate.value * 6.0 + 0.06);
                parkourHesitateTicks = roll < chance ? 1 : 0;
            }
            if (parkourHesitateTicks > 0) {
                parkourHesitateTicks--;
                // Glance slightly downward toward the jump gap while sizing it up
                MovementHelper.moveTowards(ctx, state, dest);
                state.setInput(Input.MOVE_FORWARD, false);
                state.setInput(Input.SPRINT, false);
                return state;
            }
        }

        MovementHelper.moveTowards(ctx, state, dest);
        double progress = (ctx.player().position().x - (src.x + 0.5)) * direction.getStepX()
                + (ctx.player().position().z - (src.z + 0.5)) * direction.getStepZ();
        if (ctx.playerFeet().equals(dest)) {
            Block d = BlockStateInterface.getBlock(ctx, dest);
            if (d == Blocks.VINE || d == Blocks.LADDER) {
                // it physically hurt me to add support for parkour jumping onto a vine
                // but i did it anyway
                return state.setStatus(MovementStatus.SUCCESS);
            }
            if (ctx.player().position().y - ctx.playerFeet().getY() < 0.094) { // lilypads
                state.setStatus(MovementStatus.SUCCESS);
            }
        } else if (ctx.playerFeet().equals(src)) {
            if (onIce && ctx.player().onGround() && progress >= 0.25) {
                state.setInput(Input.JUMP, true);
            }
        } else {
            if (ctx.playerFeet().equals(src.relative(direction)) || ctx.player().position().y - src.y > 0.0001) {
                if (Baritone.settings().allowPlace.value // see PR #3775
                        && ((Baritone) baritone).getInventoryBehavior().hasGenericThrowaway()
                        && !MovementHelper.canWalkOn(ctx, dest.below())
                        && !ctx.player().onGround()
                        && MovementHelper.attemptToPlaceABlock(state, baritone, dest.below(), true, false) == PlaceResult.READY_TO_PLACE
                ) {
                    // go in the opposite order to check DOWN before all horizontals -- down is preferable because you don't have to look to the side while in midair, which could mess up the trajectory
                    state.setInput(Input.CLICK_RIGHT, true);
                }
                // prevent jumping too late by checking for ascend
                if (dist == 3 && yDelta == 0 && !onIce) { // this is a 2 block gap, dest = src + direction * 3
                    double xDiff = (src.x + 0.5) - ctx.player().position().x;
                    double zDiff = (src.z + 0.5) - ctx.player().position().z;
                    double distFromStart = Math.max(Math.abs(xDiff), Math.abs(zDiff));
                    if (distFromStart < 0.7) {
                        return state;
                    }
                }

                state.setInput(Input.JUMP, true);
            } else if (ctx.player().onGround()
                    && progress < 0.20
                    && !onIce
                    && yDelta == 0
                    && dist >= 4
                    && !ctx.playerFeet().equals(dest.relative(direction, -1))
                    && MovementHelper.canWalkOn(ctx, src.relative(direction, -1).below())) {
                state.setInput(Input.SPRINT, false);
                if (ctx.playerFeet().equals(src.relative(direction, -1))) {
                    MovementHelper.moveTowards(ctx, state, src);
                } else {
                    MovementHelper.moveTowards(ctx, state, src.relative(direction, -1));
                }
            }
        }
        return state;
    }
}
