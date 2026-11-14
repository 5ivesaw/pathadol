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

import baritone.human.BotInventoryUi;
import baritone.utils.accessor.IAbstractContainerScreen;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Exposes container screen coordinates and suppresses overflowing item tooltips when drawn in
 * Diorama mini-PiP mode. Zero lambdas permitted in Mixin classes.
 */
@Mixin(AbstractContainerScreen.class)
public abstract class MixinAbstractContainerScreen implements IAbstractContainerScreen {

    @Shadow
    protected int leftPos;

    @Shadow
    protected int topPos;

    @Shadow
    @Final
    protected int imageWidth;

    @Shadow
    @Final
    protected int imageHeight;

    @Override
    public int getLeftPos() {
        return this.leftPos;
    }

    @Override
    public int getTopPos() {
        return this.topPos;
    }

    @Override
    public int getImageWidth() {
        return this.imageWidth;
    }

    @Override
    public int getImageHeight() {
        return this.imageHeight;
    }

    @Inject(method = "extractTooltip", at = @At("HEAD"), cancellable = true)
    private void pathadol$suppressMiniTooltip(GuiGraphicsExtractor gfx, int mouseX, int mouseY, CallbackInfo ci) {
        if (BotInventoryUi.shouldSuppressTooltip()) {
            ci.cancel();
        }
    }
}
