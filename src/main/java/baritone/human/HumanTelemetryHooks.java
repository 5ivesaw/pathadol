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

/**
 * Thread-safe, lambda-free per-tick accumulator for raw mouse, click-pulse, and world interaction
 * events from Minecraft mixins and input handlers.
 *
 * <p>All methods take primitives or booleans so unit tests can exercise every pulse/reset path
 * without bootstrapping Minecraft or GLFW.
 */
public final class HumanTelemetryHooks {

    private static final int GLFW_RELEASE = 0;
    private static final int GLFW_PRESS = 1;
    private static final int MOUSE_BUTTON_LEFT = 0;
    private static final int MOUSE_BUTTON_RIGHT = 1;

    private static final Object LOCK = new Object();

    private static int lmbPresses;
    private static int rmbPresses;
    private static boolean lmbPhysicallyDown;
    private static boolean rmbPhysicallyDown;

    private static int startAttackCalls;
    private static int startAttackHits;
    private static int startUseItemCalls;
    private static int continueAttackActiveTicks;

    private static int attackEntityCalls;
    private static int useItemOnCalls;
    private static int useItemOnConsumed;
    private static int useItemCalls;
    private static int useItemConsumed;
    private static int startDestroyCalls;
    private static int continueDestroyCalls;
    private static int blocksDestroyed;

    private static double rawMouseDx;
    private static double rawMouseDy;
    private static int rawMouseSamples;

    private HumanTelemetryHooks() {
    }

    public static void onMouseButton(int button, int action, boolean inWorld) {
        synchronized (LOCK) {
            if (action == GLFW_RELEASE) {
                if (button == MOUSE_BUTTON_LEFT) {
                    lmbPhysicallyDown = false;
                } else if (button == MOUSE_BUTTON_RIGHT) {
                    rmbPhysicallyDown = false;
                }
                return;
            }
            if (!inWorld) {
                return;
            }
            if (action == GLFW_PRESS) {
                if (button == MOUSE_BUTTON_LEFT) {
                    lmbPresses++;
                    lmbPhysicallyDown = true;
                } else if (button == MOUSE_BUTTON_RIGHT) {
                    rmbPresses++;
                    rmbPhysicallyDown = true;
                }
            }
        }
    }

    public static void onRawMouseDelta(double dx, double dy, boolean inWorld) {
        if (!inWorld || (dx == 0.0 && dy == 0.0)) {
            return;
        }
        synchronized (LOCK) {
            rawMouseDx += dx;
            rawMouseDy += dy;
            rawMouseSamples++;
        }
    }

    public static void onStartAttack(boolean hit) {
        synchronized (LOCK) {
            startAttackCalls++;
            if (hit) {
                startAttackHits++;
            }
        }
    }

    public static void onStartUseItem() {
        synchronized (LOCK) {
            startUseItemCalls++;
        }
    }

    public static void onContinueAttack(boolean holdingLeftClick) {
        if (!holdingLeftClick) {
            return;
        }
        synchronized (LOCK) {
            continueAttackActiveTicks++;
        }
    }

    public static void onAttackEntity() {
        synchronized (LOCK) {
            attackEntityCalls++;
        }
    }

    public static void onUseItemOn(boolean consumed) {
        synchronized (LOCK) {
            useItemOnCalls++;
            if (consumed) {
                useItemOnConsumed++;
            }
        }
    }

    public static void onUseItem(boolean consumed) {
        synchronized (LOCK) {
            useItemCalls++;
            if (consumed) {
                useItemConsumed++;
            }
        }
    }

    public static void onStartDestroyBlock(boolean started) {
        if (!started) {
            return;
        }
        synchronized (LOCK) {
            startDestroyCalls++;
        }
    }

    public static void onContinueDestroyBlock(boolean progressed) {
        if (!progressed) {
            return;
        }
        synchronized (LOCK) {
            continueDestroyCalls++;
        }
    }

    public static void onDestroyBlock(boolean destroyed) {
        if (!destroyed) {
            return;
        }
        synchronized (LOCK) {
            blocksDestroyed++;
        }
    }

    /**
     * Drains per-tick pulse/action/raw-delta counters into an immutable snapshot while retaining
     * physical button-held state until GLFW release.
     */
    public static TickSnapshot consumeTickSnapshot() {
        synchronized (LOCK) {
            TickSnapshot snapshot = new TickSnapshot(
                    lmbPresses,
                    rmbPresses,
                    lmbPhysicallyDown,
                    rmbPhysicallyDown,
                    startAttackCalls,
                    startAttackHits,
                    startUseItemCalls,
                    continueAttackActiveTicks,
                    attackEntityCalls,
                    useItemOnCalls,
                    useItemOnConsumed,
                    useItemCalls,
                    useItemConsumed,
                    startDestroyCalls,
                    continueDestroyCalls,
                    blocksDestroyed,
                    rawMouseDx,
                    rawMouseDy,
                    rawMouseSamples
            );
            clearPerTickCountersLocked();
            return snapshot;
        }
    }

    /**
     * Clears per-tick accumulators without draining into telemetry (used on pause/world-exit).
     */
    public static void resetAll() {
        synchronized (LOCK) {
            clearPerTickCountersLocked();
            lmbPhysicallyDown = false;
            rmbPhysicallyDown = false;
        }
    }

    private static void clearPerTickCountersLocked() {
        lmbPresses = 0;
        rmbPresses = 0;
        startAttackCalls = 0;
        startAttackHits = 0;
        startUseItemCalls = 0;
        continueAttackActiveTicks = 0;
        attackEntityCalls = 0;
        useItemOnCalls = 0;
        useItemOnConsumed = 0;
        useItemCalls = 0;
        useItemConsumed = 0;
        startDestroyCalls = 0;
        continueDestroyCalls = 0;
        blocksDestroyed = 0;
        rawMouseDx = 0.0;
        rawMouseDy = 0.0;
        rawMouseSamples = 0;
    }

    public record TickSnapshot(
            int lmbPresses,
            int rmbPresses,
            boolean lmbPhysicallyDown,
            boolean rmbPhysicallyDown,
            int startAttackCalls,
            int startAttackHits,
            int startUseItemCalls,
            int continueAttackActiveTicks,
            int attackEntityCalls,
            int useItemOnCalls,
            int useItemOnConsumed,
            int useItemCalls,
            int useItemConsumed,
            int startDestroyCalls,
            int continueDestroyCalls,
            int blocksDestroyed,
            double rawMouseDx,
            double rawMouseDy,
            int rawMouseSamples
    ) {
        public static final TickSnapshot EMPTY = new TickSnapshot(
                0, 0, false, false, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0.0, 0.0, 0
        );

        public boolean anyLeftImpulse() {
            return lmbPresses > 0 || startAttackCalls > 0 || attackEntityCalls > 0 || startDestroyCalls > 0;
        }

        public boolean anyRightImpulse() {
            return rmbPresses > 0 || startUseItemCalls > 0 || useItemOnCalls > 0 || useItemCalls > 0;
        }

        public int totalUseConsumed() {
            return useItemOnConsumed + useItemConsumed;
        }
    }
}
