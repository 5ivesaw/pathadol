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
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;

/**
 * Transforms GUI entity PiP bounds and scale when {@link BotInventoryUi} renders a scaled
 * mini corner container screen so the 3D player model scales inside its miniature slot frame.
 * Zero lambdas permitted in Mixin classes.
 */
@Mixin(GuiGraphicsExtractor.class)
public abstract class MixinGuiGraphicsExtractor {

    @ModifyArgs(method = "entity", at = @At("HEAD"))
    private void pathadol$transformMiniEntityArgs(Args args) {
        if (!BotInventoryUi.isMiniTransformActive() || args == null || args.size() < 9) {
            return;
        }
        args.set(1, Float.valueOf(BotInventoryUi.transformEntityScale((Float) args.get(1))));
        args.set(5, Integer.valueOf(BotInventoryUi.transformEntityX((Integer) args.get(5))));
        args.set(6, Integer.valueOf(BotInventoryUi.transformEntityY((Integer) args.get(6))));
        args.set(7, Integer.valueOf(BotInventoryUi.transformEntityX((Integer) args.get(7))));
        args.set(8, Integer.valueOf(BotInventoryUi.transformEntityY((Integer) args.get(8))));
    }
}
