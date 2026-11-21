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
        boolean down = Boolean.TRUE.equals(resolved.get(Input.MOVE_BACK));
        boolean left = Boolean.TRUE.equals(resolved.get(Input.MOVE_LEFT));
        boolean right = Boolean.TRUE.equals(resolved.get(Input.MOVE_RIGHT));

        boolean sneaking = Boolean.TRUE.equals(resolved.get(Input.SNEAK));
        this.moveVector = computeNormalizedMoveVector(up, down, left, right);

        boolean sprinting = Boolean.TRUE.equals(resolved.get(Input.SPRINT)) && !sneaking && up && !down;
        if ((!sprinting || sneaking) && handler.ctx.player() != null && handler.ctx.player().isSprinting()) {
            // MC 26.3 LocalPlayer.shouldStopRunSprinting() checks unscaled ClientInput.hasForwardImpulse()
            // before modifyInput() applies 0.3f sneak scaling; clear sprint state before physics & packets!
            handler.ctx.player().setSprinting(false);
        }

        this.keyPresses = new net.minecraft.world.entity.player.Input(up, down, left, right, jumping, sneaking, sprinting);
    }

    /**
     * Vanilla {@code KeyboardInput.tick()} normalizes the raw WASD vector without sneak scaling;
     * {@code LocalPlayer.modifyInput} applies {@code SNEAKING_SPEED (0.3f)} once later in tick.
     */
    public static Vec2 computeNormalizedMoveVector(boolean up, boolean down, boolean left, boolean right) {
        float leftImpulse = (left ? 1.0F : 0.0F) - (right ? 1.0F : 0.0F);
        float forwardImpulse = (up ? 1.0F : 0.0F) - (down ? 1.0F : 0.0F);
        return new Vec2(leftImpulse, forwardImpulse).normalized();
    }
}
