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

import baritone.human.AutoTestRunner;
import baritone.human.PathadolTesterScreen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(TitleScreen.class)
public abstract class MixinTitleScreen extends Screen {

    protected MixinTitleScreen(Component title) {
        super(title);
    }

    @Inject(method = "createNormalMenuOptions(II)I", at = @At("RETURN"))
    private void pathadol$addAutoTesterButton(int y, int rowHeight, CallbackInfoReturnable<Integer> cir) {
        AutoTestRunner.scheduleAutoStart(null);
        int btnX = this.width / 2 + 104;
        this.addRenderableWidget(Button.builder(Component.literal("Auto Tester"), button -> {
            if (this.minecraft != null) {
                this.minecraft.gui.setScreen(new PathadolTesterScreen(this));
            }
        }).bounds(btnX, y, 88, 20).tooltip(Tooltip.create(Component.literal(
                "Open Pathadol Extreme Mirror Proving Ground & Flat World Auto Tester"))).build());
    }
}
