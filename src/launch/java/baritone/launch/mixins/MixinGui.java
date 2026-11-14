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
import net.minecraft.client.MouseHandler;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Delegates container screen rendering to {@link BotInventoryUi} (mini corner PiP in spectator
 * camera mode + smooth bot cursor) and avoids releasing mouse grab during Diorama playback.
 * Zero lambdas permitted in Mixin classes.
 */
@Mixin(Gui.class)
public abstract class MixinGui {

    @Redirect(
            method = "extractRenderState",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/screens/Screen;extractRenderStateWithTooltipAndSubtitles(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIF)V"
            )
    )
    private void pathadol$extractScreenRenderState(
            Screen screen,
            GuiGraphicsExtractor gfx,
            int mouseX,
            int mouseY,
            float partialTicks
    ) {
        BotInventoryUi.extractScreenRenderState(screen, gfx, mouseX, mouseY, partialTicks);
    }

    @Redirect(
            method = "setScreen",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/MouseHandler;releaseMouse()V"
            )
    )
    private void pathadol$maybeReleaseMouse(MouseHandler mouseHandler) {
        if (!BotInventoryUi.shouldKeepMouseGrab()) {
            mouseHandler.releaseMouse();
        }
    }
}
