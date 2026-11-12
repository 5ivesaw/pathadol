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

import java.util.EnumMap;
import java.util.Map;

/**
 * Stateful human keyboard controller:
 * <ul>
 *   <li>Decouples feet travel direction ({@code desiredMoveYaw}) from current camera yaw</li>
 *   <li>Uses an 8-sector W/A/S/D solver with angular deadband hysteresis to prevent 1-tick key chatter</li>
 *   <li>Enforces minimum key hold duration during active movement transitions</li>
 *   <li>Enforces the vanilla sprint forward-cone constraint</li>
 * </ul>
 */
public final class KeyboardModel {

    private static final float SECTOR_HALF_WIDTH_DEG = 22.5f;
    private static final float HYSTERESIS_DEG = 3.5f;

    private static final Input[] MOVEMENT_KEYS = {
            Input.MOVE_FORWARD,
            Input.MOVE_BACK,
            Input.MOVE_LEFT,
            Input.MOVE_RIGHT
    };

    private final EnumMap<Input, Boolean> activeState = new EnumMap<>(Input.class);
    private final EnumMap<Input, Integer> holdTicks = new EnumMap<>(Input.class);
    private int currentOctant = 0;
    private boolean hasActiveOctant = false;
    private Float desiredMoveYaw = null;

    public KeyboardModel() {
        for (Input input : Input.values()) {
            activeState.put(input, false);
            holdTicks.put(input, 100);
        }
    }

    public void setDesiredMoveYaw(Float yaw) {
        this.desiredMoveYaw = yaw;
    }

    public Float getDesiredMoveYaw() {
        return this.desiredMoveYaw;
    }

    public void clearDesiredMoveYaw() {
        this.desiredMoveYaw = null;
        this.hasActiveOctant = false;
    }

    /**
     * Updates the persistent keyboard state for one tick.
     *
     * @param requestedRaw     raw inputs requested by Baritone movements/processes
     * @param currentCameraYaw actual camera yaw after {@link MouseModel} step
     * @param profile          active human profile
     * @return resolved human keyboard state for this tick
     */
    public Map<Input, Boolean> step(
            Map<Input, Boolean> requestedRaw,
            float currentCameraYaw,
            HumanProfile profile
    ) {
        EnumMap<Input, Boolean> target = new EnumMap<>(Input.class);
        for (Input input : Input.values()) {
            target.put(input, Boolean.TRUE.equals(requestedRaw.get(input)));
        }

        boolean wantsAnyMove = target.get(Input.MOVE_FORWARD)
                || target.get(Input.MOVE_BACK)
                || target.get(Input.MOVE_LEFT)
                || target.get(Input.MOVE_RIGHT);

        // Omnidirectional strafe decoupling when desiredMoveYaw is provided and forward movement is requested
        if (wantsAnyMove && desiredMoveYaw != null && !target.get(Input.MOVE_BACK)) {
            float relYaw = Rotation.normalizeYaw(desiredMoveYaw - currentCameraYaw);
            int octant = resolveOctantWithHysteresis(relYaw);
            applyOctantKeys(octant, target);

            // Vanilla sprint requires forward movement and camera within the forward sprint cone
            if (Math.abs(relYaw) > profile.sprintConeDeg() || !target.get(Input.MOVE_FORWARD)) {
                target.put(Input.SPRINT, false);
            }
        } else {
            hasActiveOctant = false;
        }

        int minHold = profile.minKeyHoldTicks();

        // If movement stopped completely or sneaking started, release movement keys immediately for safety at ledges
        if (!wantsAnyMove) {
            for (Input k : MOVEMENT_KEYS) {
                activeState.put(k, false);
                holdTicks.put(k, minHold);
            }
        } else {
            // Apply minimum hold duration hysteresis across active directional transitions
            for (Input k : MOVEMENT_KEYS) {
                boolean cur = activeState.get(k);
                boolean des = target.get(k);
                int held = holdTicks.get(k);
                if (cur == des) {
                    holdTicks.put(k, held + 1);
                } else if (held >= minHold) {
                    activeState.put(k, des);
                    holdTicks.put(k, 1);
                } else {
                    holdTicks.put(k, held + 1);
                }
            }
        }

        // Non-directional keys (JUMP, SNEAK, SPRINT, CLICK_LEFT, CLICK_RIGHT)
        for (Input k : new Input[]{Input.JUMP, Input.SNEAK, Input.SPRINT, Input.CLICK_LEFT, Input.CLICK_RIGHT}) {
            boolean cur = activeState.get(k);
            boolean des = target.get(k);
            if (cur == des) {
                holdTicks.put(k, holdTicks.get(k) + 1);
            } else {
                activeState.put(k, des);
                holdTicks.put(k, 1);
            }
        }

        // Prevent impossible simultaneous opposite keys (W+S or A+D)
        if (activeState.get(Input.MOVE_FORWARD) && activeState.get(Input.MOVE_BACK)) {
            if (target.get(Input.MOVE_BACK)) {
                activeState.put(Input.MOVE_FORWARD, false);
            } else {
                activeState.put(Input.MOVE_BACK, false);
            }
        }
        if (activeState.get(Input.MOVE_LEFT) && activeState.get(Input.MOVE_RIGHT)) {
            if (target.get(Input.MOVE_RIGHT)) {
                activeState.put(Input.MOVE_LEFT, false);
            } else {
                activeState.put(Input.MOVE_RIGHT, false);
            }
        }

        return new EnumMap<>(activeState);
    }

    /**
     * Maps a relative yaw angle in [-180, 180] to one of 8 octants (-3..4) with deadband hysteresis.
     */
    public int resolveOctantWithHysteresis(float relYawDeg) {
        if (hasActiveOctant) {
            float center = currentOctant * 45.0f;
            float diff = Math.abs(Rotation.normalizeYaw(relYawDeg - center));
            if (diff <= SECTOR_HALF_WIDTH_DEG + HYSTERESIS_DEG) {
                return currentOctant;
            }
        }
        int oct = Math.round(relYawDeg / 45.0f);
        if (oct <= -4) {
            oct = 4;
        }
        this.currentOctant = oct;
        this.hasActiveOctant = true;
        return oct;
    }

    private static void applyOctantKeys(int octant, EnumMap<Input, Boolean> out) {
        out.put(Input.MOVE_FORWARD, false);
        out.put(Input.MOVE_BACK, false);
        out.put(Input.MOVE_LEFT, false);
        out.put(Input.MOVE_RIGHT, false);

        switch (octant) {
            case 0 -> out.put(Input.MOVE_FORWARD, true);
            case 1 -> {
                out.put(Input.MOVE_FORWARD, true);
                out.put(Input.MOVE_RIGHT, true);
            }
            case 2 -> out.put(Input.MOVE_RIGHT, true);
            case 3 -> {
                out.put(Input.MOVE_BACK, true);
                out.put(Input.MOVE_RIGHT, true);
            }
            case -1 -> {
                out.put(Input.MOVE_FORWARD, true);
                out.put(Input.MOVE_LEFT, true);
            }
            case -2 -> out.put(Input.MOVE_LEFT, true);
            case -3 -> {
                out.put(Input.MOVE_BACK, true);
                out.put(Input.MOVE_LEFT, true);
            }
            default -> out.put(Input.MOVE_BACK, true);
        }
    }

    public boolean isDown(Input input) {
        return Boolean.TRUE.equals(activeState.get(input));
    }
}
