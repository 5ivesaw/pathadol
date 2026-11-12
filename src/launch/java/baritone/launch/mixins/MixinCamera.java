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

import baritone.human.DioramaCameraController;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Camera.class)
public abstract class MixinCamera {

    @Shadow
    private Entity entity;

    @Shadow
    private boolean detached;

    @Shadow
    protected abstract void setPosition(double x, double y, double z);

    @Shadow
    protected abstract void setRotation(float yaw, float pitch);

    @Shadow
    public abstract float getCameraEntityPartialTicks(DeltaTracker deltaTracker);

    @Inject(
            method = "update",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/Camera;alignWithEntity(F)V",
                    shift = At.Shift.AFTER
            )
    )
    private void pathadol$applyDioramaCamera(DeltaTracker deltaTracker, CallbackInfo ci) {
        // Keep zero lambdas or inner classes in Mixin classes to avoid Sponge Mixin ClassLoader crashes
        double[] state = DioramaCameraController.computeCameraState(this.entity, this.getCameraEntityPartialTicks(deltaTracker));
        if (state != null) {
            this.detached = true;
            this.setPosition(state[0], state[1], state[2]);
            this.setRotation((float) state[3], (float) state[4]);
        }
    }

    @Inject(method = "isDetached", at = @At("RETURN"), cancellable = true)
    private void pathadol$forceDetachedInDiorama(CallbackInfoReturnable<Boolean> cir) {
        if (DioramaCameraController.isCameraOverrideActive()) {
            cir.setReturnValue(true);
        }
    }
}
