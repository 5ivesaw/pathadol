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
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.state.gui.GuiRenderState;
import net.minecraft.client.renderer.state.gui.pip.GuiEntityRenderState;
import net.minecraft.client.renderer.state.gui.pip.PictureInPictureRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Transforms GUI entity PiP bounds and scale when {@link BotInventoryUi} renders a scaled
 * mini corner container screen so the 3D player model scales inside its miniature slot frame.
 * Zero lambdas permitted in Mixin classes.
 */
@Mixin(GuiGraphicsExtractor.class)
public abstract class MixinGuiGraphicsExtractor {

    @Redirect(
            method = "entity",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/state/gui/GuiRenderState;addPicturesInPictureState(Lnet/minecraft/client/renderer/state/gui/pip/PictureInPictureRenderState;)V"
            )
    )
    private void pathadol$transformMiniEntityState(GuiRenderState guiRenderState, PictureInPictureRenderState state) {
        PictureInPictureRenderState submitted = state;
        if (BotInventoryUi.isMiniTransformActive() && state instanceof GuiEntityRenderState es) {
            submitted = new GuiEntityRenderState(
                    es.renderState(),
                    es.translation(),
                    es.rotation(),
                    es.overrideCameraAngle(),
                    BotInventoryUi.transformEntityX(es.x0()),
                    BotInventoryUi.transformEntityY(es.y0()),
                    BotInventoryUi.transformEntityX(es.x1()),
                    BotInventoryUi.transformEntityY(es.y1()),
                    BotInventoryUi.transformEntityScale(es.scale()),
                    es.scissorArea()
            );
        }
        guiRenderState.addPicturesInPictureState(submitted);
    }
}
