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

package baritone.utils;

import baritone.Baritone;
import baritone.api.utils.IPlayerContext;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.component.SwingAnimation;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

public class BlockPlaceHelper {
    // base ticks between places caused by tick logic
    private static final int BASE_PLACE_DELAY = 1;

    private final IPlayerContext ctx;
    private int rightClickTimer;

    BlockPlaceHelper(IPlayerContext playerContext) {
        this.ctx = playerContext;
    }

    public void tick(boolean rightClickRequested) {
        tick(rightClickRequested, false);
    }

    public boolean tick(boolean rightClickRequested, boolean humanDriven) {
        if (!humanDriven) {
            if (rightClickTimer > 0) {
                rightClickTimer--;
                return false;
            }
        }
        if (!rightClickRequested || ctx.player() == null || ctx.world() == null) {
            return false;
        }
        if (ctx.player().isUsingItem()) {
            return true;
        }
        if (ctx.player().isHandsBusy()) {
            return false;
        }
        HitResult mouseOver = ctx.objectMouseOver();
        boolean blockHit = mouseOver != null && mouseOver.getType() == HitResult.Type.BLOCK;
        boolean holdingBlock = ctx.player().getMainHandItem().getItem() instanceof net.minecraft.world.item.BlockItem;

        if (blockHit) {
            if (!humanDriven) {
                rightClickTimer = Baritone.settings().rightClickSpeed.value - BASE_PLACE_DELAY;
            }
            for (InteractionHand hand : InteractionHand.values()) {
                if (ctx.playerController().processRightClickBlock(ctx.player(), ctx.world(), hand, (BlockHitResult) mouseOver).consumesAction()) {
                    ctx.player().swing(hand, SwingAnimation.DEFAULT, false);
                    return true;
                }
                if (humanDriven && hand == InteractionHand.MAIN_HAND && holdingBlock) {
                    return false;
                }
                var stack = ctx.player().getItemInHand(hand);
                if (!stack.isEmpty() && !(stack.getItem() instanceof net.minecraft.world.item.BlockItem)
                        && ctx.playerController().processRightClick(ctx.player(), ctx.world(), hand).consumesAction()) {
                    if (stack.getUseDuration(ctx.player()) <= 0) {
                        ctx.player().swing(hand, SwingAnimation.DEFAULT, false);
                    }
                    return true;
                }
            }
            return false;
        }

        // Non-block right-click (MISS or ENTITY): support consumables, throwables, bows, rods, pearls, fireballs, shields!
        if (!humanDriven) {
            rightClickTimer = Math.max(1, Baritone.settings().rightClickSpeed.value - BASE_PLACE_DELAY);
        }
        for (InteractionHand hand : InteractionHand.values()) {
            var stack = ctx.player().getItemInHand(hand);
            if (stack.isEmpty() || stack.getItem() instanceof net.minecraft.world.item.BlockItem) {
                continue;
            }
            if (ctx.playerController().processRightClick(ctx.player(), ctx.world(), hand).consumesAction()) {
                if (stack.getUseDuration(ctx.player()) <= 0) {
                    ctx.player().swing(hand, SwingAnimation.DEFAULT, false);
                }
                return true;
            }
        }
        return false;
    }
}
