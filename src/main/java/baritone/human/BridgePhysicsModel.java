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

import baritone.human.BridgeController.BridgeSkill;

/**
 * Exact Minecraft horizontal ground kinematics and predictive braking model for bridging.
 * Models vanilla tick friction ({@code v_{t+1} = 0.546 * v_t + a}) so Ninja bridging un-sneaks
 * freely across solid footing up to ~0.10-0.115 b/t and engages SNEAK at the exact tick needed
 * to glide smoothly onto the cantilevered edge (~0.040-0.045 b/t) without micro-creep stalls
 * ({@code <0.03 b/t}) or premature void slips.
 */
public final class BridgePhysicsModel {

    /** Standard ground friction multiplier on non-ice blocks (0.6 * 0.91). */
    public static final double GROUND_DRAG = 0.546;
    /** Base backward/walk horizontal acceleration per tick on ground. */
    public static final double WALK_ACCEL = 0.098 * 0.52;
    /** Sneaking horizontal acceleration per tick (~0.3x walk). */
    public static final double SNEAK_ACCEL = WALK_ACCEL * 0.30;
    /** Steady-state sneak speed on ground (b/t). */
    public static final double STEADY_SNEAK_SPEED = SNEAK_ACCEL / (1.0 - GROUND_DRAG);
    /** Target cantilever edge progress where vanilla sneak catches and side-face raycast locks. */
    public static final double EDGE_CATCH_PROGRESS = 0.52;

    private BridgePhysicsModel() {}

    /**
     * Advances horizontal speed by one Minecraft ground tick under the given input state.
     */
    public static double stepGroundSpeed(double curSpeed, boolean moving, boolean sneaking) {
        double dragged = Math.max(0.0, curSpeed) * GROUND_DRAG;
        if (!moving) {
            return dragged;
        }
        return dragged + (sneaking ? SNEAK_ACCEL : WALK_ACCEL);
    }

    /**
     * Predicts how many blocks a player moving at {@code currentEdgeSpeed} (b/t) will travel
     * while decelerating under SNEAK until settling near steady-state sneak speed ({@code <= 0.044 b/t}),
     * accounting for {@code latencyTicks} of reaction/network margin.
     */
    public static double predictSneakDecelDistance(double currentEdgeSpeed, int latencyTicks) {
        double v = Math.max(0.0, Math.min(0.28, currentEdgeSpeed));
        double dist = 0.0;
        for (int i = 0; i < Math.max(0, latencyTicks); i++) {
            v = stepGroundSpeed(v, true, false);
            dist += v;
        }
        for (int t = 0; t < 10 && v > 0.044; t++) {
            v = stepGroundSpeed(v, true, true);
            dist += v;
        }
        return dist;
    }

    /**
     * Deterministic velocity-aware predictive Ninja sneak controller.
     * Un-sneaks across the solid body of each block and presses SNEAK exactly when remaining
     * distance to the cantilevered edge drops within {@code predictSneakDecelDistance(v)}.
     */
    public static boolean shouldEngagePredictiveSneak(
            BridgeSkill skill,
            double signedProgress,
            double velocityAlongBridge,
            boolean lookingAtFace,
            boolean nextBlockAir,
            long seed
    ) {
        if (skill == BridgeSkill.SAFE) {
            return true;
        }
        if (!nextBlockAir) {
            return false;
        }
        double jitter = ((((seed >>> 16) & 0x3FL) / 63.0) - 0.5) * 0.020;
        double v = Math.max(0.0, velocityAlongBridge);
        double stopDist = predictSneakDecelDistance(v, 1);
        double safetyMargin = (skill == BridgeSkill.SWEATY ? 0.015 : 0.045) + jitter;
        double triggerProgress = Math.max(0.24, Math.min(0.46, EDGE_CATCH_PROGRESS - stopDist - safetyMargin));
        if (signedProgress >= triggerProgress) {
            return true;
        }
        return lookingAtFace && signedProgress >= Math.max(0.30, triggerProgress - 0.03);
    }

    /**
     * Verifies deterministic block reserve before committing to a bridge span or vertical tower
     * so the bot never towers up and runs out of blocks mid-air.
     */
    public static boolean hasSufficientBlocksForSpan(int blocksHeld, int horizontalSpan, int verticalClimb) {
        int rawNeeded = Math.max(0, horizontalSpan) + Math.max(0, verticalClimb);
        if (rawNeeded == 0) {
            return true;
        }
        int safeReserve = (int) Math.ceil(rawNeeded * 1.15) + 2;
        return blocksHeld >= safeReserve;
    }
}
