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

package baritone.human;

import baritone.api.utils.Rotation;
import baritone.api.utils.input.Input;
import baritone.pathing.movement.MovementState;

import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;

/**
 * Contract between high-level Baritone movements/processes and the Human Motor Layer.
 */
public final class MotorIntent {

    public enum LookPriority {
        /**
         * Crosshair must arrive on target before block/entity interaction clicks fire.
         */
        MUST_HIT,
        /**
         * Guiding movement gaze along path; feet move independently via strafe decoupling.
         */
        GUIDE,
        /**
         * Gaze is free to scan or coast.
         */
        FREE
    }

    public enum SpeedClass {
        SNEAK,
        WALK,
        SPRINT
    }

    private final Rotation lookTarget;
    private final LookPriority lookPriority;
    private final float targetAngularWidthDeg;
    private final Float desiredMoveYaw;
    private final SpeedClass speedClass;
    private final EnumMap<Input, Boolean> actions;

    public MotorIntent(
            Rotation lookTarget,
            LookPriority lookPriority,
            float targetAngularWidthDeg,
            Float desiredMoveYaw,
            SpeedClass speedClass,
            Map<Input, Boolean> actions
    ) {
        this.lookTarget = lookTarget;
        this.lookPriority = lookPriority == null ? LookPriority.FREE : lookPriority;
        this.targetAngularWidthDeg = Math.max(1.5f, targetAngularWidthDeg);
        this.desiredMoveYaw = desiredMoveYaw;
        this.speedClass = speedClass == null ? SpeedClass.WALK : speedClass;
        this.actions = new EnumMap<>(Input.class);
        if (actions != null) {
            this.actions.putAll(actions);
        }
    }

    /**
     * Adapts a legacy {@link MovementState} into a {@link MotorIntent}.
     */
    public static MotorIntent fromLegacy(MovementState state) {
        Optional<Rotation> rotOpt = state.getTarget().getRotation();
        boolean forceRot = state.getTarget().hasToForceRotations();
        LookPriority priority = rotOpt.isEmpty()
                ? LookPriority.FREE
                : (forceRot ? LookPriority.MUST_HIT : LookPriority.GUIDE);
        float widthDeg = forceRot ? 7.5f : 16.0f;

        Map<Input, Boolean> inputs = state.getInputStates();
        boolean sneaking = Boolean.TRUE.equals(inputs.get(Input.SNEAK));
        boolean sprinting = Boolean.TRUE.equals(inputs.get(Input.SPRINT));
        SpeedClass speed = sneaking ? SpeedClass.SNEAK : (sprinting ? SpeedClass.SPRINT : SpeedClass.WALK);

        Float moveYaw = state.getDesiredMoveYaw();
        if (moveYaw == null && !forceRot && rotOpt.isPresent()
                && Boolean.TRUE.equals(inputs.get(Input.MOVE_FORWARD))
                && !Boolean.TRUE.equals(inputs.get(Input.MOVE_BACK))
                && !Boolean.TRUE.equals(inputs.get(Input.MOVE_LEFT))
                && !Boolean.TRUE.equals(inputs.get(Input.MOVE_RIGHT))) {
            moveYaw = rotOpt.get().getYaw();
        }

        return new MotorIntent(
                rotOpt.orElse(null),
                priority,
                widthDeg,
                moveYaw,
                speed,
                inputs
        );
    }

    public Optional<Rotation> lookTarget() {
        return Optional.ofNullable(lookTarget);
    }

    public LookPriority lookPriority() {
        return lookPriority;
    }

    public float targetAngularWidthDeg() {
        return targetAngularWidthDeg;
    }

    public Optional<Float> desiredMoveYaw() {
        return Optional.ofNullable(desiredMoveYaw);
    }

    public SpeedClass speedClass() {
        return speedClass;
    }

    public boolean isActionRequested(Input input) {
        return Boolean.TRUE.equals(actions.get(input));
    }

    public Map<Input, Boolean> actions() {
        return actions;
    }
}
