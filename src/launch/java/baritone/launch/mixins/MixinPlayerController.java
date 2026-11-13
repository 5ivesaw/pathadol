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

package baritone.launch.mixins;

import baritone.human.HumanTelemetryHooks;
import baritone.utils.accessor.IPlayerControllerMP;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(MultiPlayerGameMode.class)
public abstract class MixinPlayerController implements IPlayerControllerMP {

    @Accessor("isDestroying")
    @Override
    public abstract void setIsHittingBlock(boolean isHittingBlock);

    @Accessor("isDestroying")
    @Override
    public abstract boolean isHittingBlock();

    @Accessor("destroyBlockPos")
    @Override
    public abstract BlockPos getCurrentBlock();

    @Invoker("ensureHasSentCarriedItem")
    @Override
    public abstract void callSyncCurrentPlayItem();

    @Accessor("destroyDelay")
    @Override
    public abstract void setDestroyDelay(int destroyDelay);

    @Inject(method = "attack", at = @At("HEAD"))
    private void pathadol$onAttackEntity(Player player, Entity targetEntity, CallbackInfo ci) {
        HumanTelemetryHooks.onAttackEntity();
    }

    @Inject(method = "useItemOn", at = @At("RETURN"))
    private void pathadol$onUseItemOn(
            LocalPlayer player,
            InteractionHand hand,
            BlockHitResult hitResult,
            CallbackInfoReturnable<InteractionResult> cir
    ) {
        InteractionResult res = cir.getReturnValue();
        HumanTelemetryHooks.onUseItemOn(res != null && res.consumesAction());
    }

    @Inject(method = "useItem", at = @At("RETURN"))
    private void pathadol$onUseItem(
            Player player,
            InteractionHand hand,
            CallbackInfoReturnable<InteractionResult> cir
    ) {
        InteractionResult res = cir.getReturnValue();
        HumanTelemetryHooks.onUseItem(res != null && res.consumesAction());
    }

    @Inject(method = "startDestroyBlock", at = @At("RETURN"))
    private void pathadol$onStartDestroyBlock(BlockPos pos, Direction face, CallbackInfoReturnable<Boolean> cir) {
        HumanTelemetryHooks.onStartDestroyBlock(Boolean.TRUE.equals(cir.getReturnValue()));
    }

    @Inject(method = "continueDestroyBlock", at = @At("RETURN"))
    private void pathadol$onContinueDestroyBlock(BlockPos pos, Direction face, CallbackInfoReturnable<Boolean> cir) {
        HumanTelemetryHooks.onContinueDestroyBlock(Boolean.TRUE.equals(cir.getReturnValue()));
    }

    @Inject(method = "destroyBlock", at = @At("RETURN"))
    private void pathadol$onDestroyBlock(BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        HumanTelemetryHooks.onDestroyBlock(Boolean.TRUE.equals(cir.getReturnValue()));
    }
}
