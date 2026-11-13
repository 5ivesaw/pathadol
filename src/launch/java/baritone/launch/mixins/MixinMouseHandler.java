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
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.input.MouseButtonInfo;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Captures sub-tick mouse button press/release events and raw cursor deltas without lambdas.
 */
@Mixin(MouseHandler.class)
public class MixinMouseHandler {

    @Shadow
    @Final
    private Minecraft minecraft;

    @Shadow
    private double accumulatedDX;

    @Shadow
    private double accumulatedDY;

    @Inject(method = "onButton", at = @At("HEAD"))
    private void pathadol$onMouseButton(long window, MouseButtonInfo buttonInfo, int action, CallbackInfo ci) {
        if (buttonInfo == null) {
            return;
        }
        boolean inWorld = this.minecraft != null
                && this.minecraft.gui != null
                && this.minecraft.gui.screen() == null
                && !this.minecraft.isPaused();
        HumanTelemetryHooks.onMouseButton(buttonInfo.button(), action, inWorld);
    }

    @Inject(method = "turnPlayer", at = @At("HEAD"))
    private void pathadol$onTurnPlayer(double movementTime, CallbackInfo ci) {
        boolean inWorld = this.minecraft != null
                && this.minecraft.gui != null
                && this.minecraft.gui.screen() == null
                && !this.minecraft.isPaused();
        HumanTelemetryHooks.onRawMouseDelta(this.accumulatedDX, this.accumulatedDY, inWorld);
    }
}
