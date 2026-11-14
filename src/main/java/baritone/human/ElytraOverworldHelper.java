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
 * Pure Overworld Elytra flight planning, altitude resolution, and flat-ground rocket takeoff helper.
 * Kept free of Minecraft static block/item initializers so unit tests execute cleanly headless.
 */
public final class ElytraOverworldHelper {

    public record SurfaceTakeoffAction(
            boolean moveForward,
            boolean sprint,
            boolean jumpDown,
            boolean fireRocket,
            float desiredPitchDeg,
            boolean needClearSkyWalk
    ) {}

    private ElytraOverworldHelper() {}

    /**
     * Resolves an appropriate cruise/destination Y coordinate for {@code GoalXZ} in any dimension.
     * Nether continues using Y=64 cavern elevation; Overworld targets safe sky altitude above
     * loaded terrain heightmaps or sea level + cruise buffer.
     */
    public static int resolveGoalXZTargetY(
            boolean isNether,
            int seaLevel,
            int minY,
            int maxY,
            int playerY,
            Integer loadedSurfaceY
    ) {
        if (isNether) {
            return 64;
        }
        int safeFloor = Math.max(minY + 20, seaLevel + 16);
        int ceiling = Math.max(safeFloor + 8, maxY - 12);
        if (loadedSurfaceY != null) {
            return clampInt(Math.max(loadedSurfaceY + 20, safeFloor), safeFloor, ceiling);
        }
        int heuristic = Math.max(seaLevel + 36, playerY + 18);
        return clampInt(heuristic, safeFloor, Math.max(safeFloor + 8, maxY - 24));
    }

    /**
     * Resolves the climb/takeoff goal altitude when {@code elytraAutoJump} searches for launch terrain.
     * Avoids the Nether-specific underground Y=31 target in Overworld dimensions.
     */
    public static int resolveTakeoffGoalY(
            boolean isNether,
            int seaLevel,
            int minY,
            int maxY,
            int playerY
    ) {
        if (isNether) {
            return 31;
        }
        int floor = Math.max(minY + 16, seaLevel + 10);
        int ceiling = Math.max(floor + 8, maxY - 16);
        int desired = Math.max(seaLevel + 18, playerY + 8);
        return clampInt(desired, floor, ceiling);
    }

    /**
     * Computes human-timed sprint-jump + rising-edge wing deployment + firework boost actions
     * for launching an Elytra from Overworld ground terrain without requiring a cliff.
     */
    public static SurfaceTakeoffAction stepSurfaceTakeoff(
            boolean onGround,
            boolean isFallFlying,
            boolean wasJumpDown,
            boolean skyClearAbove,
            boolean hasFireworks,
            int airTicks
    ) {
        if (isFallFlying) {
            return new SurfaceTakeoffAction(false, false, false, hasFireworks, -32.0f, false);
        }
        if (!skyClearAbove) {
            return new SurfaceTakeoffAction(false, false, false, false, -12.0f, true);
        }
        if (onGround) {
            // Release jump if it was left pressed from previous frame so ground jump cleanly triggers
            boolean jumpPress = !wasJumpDown;
            return new SurfaceTakeoffAction(true, true, jumpPress, false, -28.0f, false);
        }
        // Vanilla LocalPlayer.aiStep requires a rising edge on Input.jump() after leaving the ground
        if (airTicks < 2) {
            return new SurfaceTakeoffAction(true, true, false, false, -32.0f, false);
        }
        boolean jumpRisingPulse = !wasJumpDown;
        return new SurfaceTakeoffAction(true, true, jumpRisingPulse, false, -35.0f, false);
    }

    /**
     * Determines whether autonomous Overworld navigation should switch to Elytra flight.
     */
    public static boolean shouldAutoEngageOverworldElytra(
            boolean isOverworld,
            boolean featureEnabled,
            boolean alreadyFallFlying,
            boolean hasUsableElytra,
            int fireworkCount,
            double goalDistanceXZ,
            int minAutoDistance
    ) {
        if (!isOverworld || !hasUsableElytra) {
            return false;
        }
        if (alreadyFallFlying) {
            return goalDistanceXZ >= 28.0;
        }
        if (!featureEnabled) {
            return false;
        }
        return fireworkCount >= 2 && goalDistanceXZ >= Math.max(32, minAutoDistance);
    }

    private static int clampInt(int v, int lo, int hi) {
        if (hi < lo) {
            return lo;
        }
        return Math.max(lo, Math.min(hi, v));
    }
}
