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

import baritone.api.utils.input.Input;
import net.minecraft.client.player.ClientInput;
import net.minecraft.world.phys.Vec2;

import java.util.Map;

public class PlayerMovementInput extends ClientInput {

    private final InputOverrideHandler handler;

    PlayerMovementInput(InputOverrideHandler handler) {
        this.handler = handler;
    }

    @Override
    public void tick() {
        float currentYaw = handler.ctx.player() != null
                ? handler.ctx.player().getYRot()
                : handler.ctx.playerRotations().getYaw();
        Map<Input, Boolean> resolved = handler.resolveMovementInputs(currentYaw);

        float leftImpulse = 0.0F;
        float forwardImpulse = 0.0F;
        boolean jumping = Boolean.TRUE.equals(resolved.get(Input.JUMP)); // oppa gangnam style

        boolean up = Boolean.TRUE.equals(resolved.get(Input.MOVE_FORWARD));
        if (up) {
            forwardImpulse++;
        }

        boolean down = Boolean.TRUE.equals(resolved.get(Input.MOVE_BACK));
        if (down) {
            forwardImpulse--;
        }

        boolean left = Boolean.TRUE.equals(resolved.get(Input.MOVE_LEFT));
        if (left) {
            leftImpulse++;
        }

        boolean right = Boolean.TRUE.equals(resolved.get(Input.MOVE_RIGHT));
        if (right) {
            leftImpulse--;
        }

        boolean sneaking = Boolean.TRUE.equals(resolved.get(Input.SNEAK));
        if (sneaking) {
            leftImpulse *= 0.3D;
            forwardImpulse *= 0.3D;
        }
        this.moveVector = new Vec2(leftImpulse, forwardImpulse);

        boolean sprinting = Boolean.TRUE.equals(resolved.get(Input.SPRINT));

        this.keyPresses = new net.minecraft.world.entity.player.Input(up, down, left, right, jumping, sneaking, sprinting);
    }
}
