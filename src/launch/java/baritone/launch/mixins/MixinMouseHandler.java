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

import baritone.human.FreecamController;
import baritone.human.HumanTelemetryHooks;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Captures sub-tick mouse button press/release events and raw cursor deltas without lambdas,
 * and isolates client-only {@link FreecamController} look/click input from the real player &amp; bot.
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

    @Inject(method = "onButton", at = @At("HEAD"), cancellable = true)
    private void pathadol$onMouseButton(long window, MouseButtonInfo buttonInfo, int action, CallbackInfo ci) {
        if (buttonInfo == null) {
            return;
        }
        boolean inWorld = this.minecraft != null
                && this.minecraft.gui != null
                && this.minecraft.gui.screen() == null
                && !this.minecraft.isPaused();
        if (FreecamController.isActive() && window == minecraft.getWindow().handle()) {
            FreecamController.onMouseButton(buttonInfo.button(), action != 0);
        }
        if (pathadol$blocksMouseActions()) {
            ci.cancel();
            return;
        }
        HumanTelemetryHooks.onMouseButton(buttonInfo.button(), action, inWorld);
    }

    @Inject(method = "turnPlayer", at = @At("HEAD"), cancellable = true)
    private void pathadol$onTurnPlayer(double movementTime, CallbackInfo ci) {
        boolean inWorld = this.minecraft != null
                && this.minecraft.gui != null
                && this.minecraft.gui.screen() == null
                && !this.minecraft.isPaused();
        if (FreecamController.isActive()) {
            if (FreecamController.acceptsCameraInput(this.minecraft)) {
                FreecamController.onMouseDelta(this.accumulatedDX, this.accumulatedDY, this.minecraft);
            }
            this.accumulatedDX = 0.0;
            this.accumulatedDY = 0.0;
            ci.cancel();
            return;
        }
        HumanTelemetryHooks.onRawMouseDelta(this.accumulatedDX, this.accumulatedDY, inWorld);
    }

    @Inject(method = "onScroll", at = @At("HEAD"), cancellable = true)
    private void pathadol$onScroll(long window, double xOffset, double yOffset, CallbackInfo ci) {
        if (pathadol$blocksMouseActions()) {
            ci.cancel();
        }
    }

    private boolean pathadol$blocksMouseActions() {
        return FreecamController.isActive() && minecraft.player != null
                && (minecraft.gui.screen() == null || minecraft.gui.screen() instanceof AbstractContainerScreen<?>);
    }

    @Redirect(method = "grabMouse", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/KeyMapping;setAll()V"))
    private void pathadol$restoreHardwareKeys() {
        if (!FreecamController.isActive()) {
            KeyMapping.setAll();
        }
    }

    @Redirect(method = "grabMouse", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/Gui;setScreen(Lnet/minecraft/client/gui/screens/Screen;)V"))
    private void pathadol$closeScreenOnGrab(Gui gui, Screen screen) {
        if (!FreecamController.isActive() || !(gui.screen() instanceof AbstractContainerScreen<?>)) {
            gui.setScreen(screen);
        }
    }
}
