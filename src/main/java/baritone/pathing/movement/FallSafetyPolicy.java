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

package baritone.pathing.movement;

/**
 * Pure fall-height and fall-damage cost policy separating aggressive Bedwars traversal
 * from fall-damage-averse Normal Survival pathfinding.
 */
public final class FallSafetyPolicy {

    private FallSafetyPolicy() {
    }

    /**
     * Resolves the maximum unprotected vertical fall height allowed without a water bucket.
     */
    public static int computeEffectiveMaxFallNoWater(
            int settingMaxFall,
            boolean humanMode,
            boolean assumeWalkOnWater,
            boolean bedwarsMode,
            boolean avoidSurvivalFallDamage,
            float effectiveHp
    ) {
        int baseMaxFall = settingMaxFall;
        if (bedwarsMode) {
            if (humanMode && !assumeWalkOnWater) {
                if (effectiveHp >= 15.0f) {
                    return Math.max(baseMaxFall, 6);
                }
                if (effectiveHp >= 12.0f) {
                    return Math.max(baseMaxFall, 4);
                }
            }
            return baseMaxFall;
        }
        if (!avoidSurvivalFallDamage) {
            return baseMaxFall;
        }
        // Normal Survival: avoid fall damage via A* cost penalty while still keeping shallow 4-5 block
        // drops available as a heavily penalized last resort when healthy.
        if (effectiveHp >= 15.0f) {
            return Math.max(baseMaxFall, 5);
        }
        if (effectiveHp >= 10.0f) {
            return Math.max(baseMaxFall, 4);
        }
        return Math.min(baseMaxFall, 3);
    }

    /**
     * Returns additional path cost in ticks for taking non-zero fall damage in Normal Survival.
     * In {@code MovementDescend}, {@code unprotectedFallHeight <= 4} is a drop of at most 3 blocks
     * (0 fall damage); each increment above 4 adds 1 HP (0.5 heart) of fall damage.
     */
    public static double survivalFallDamagePenalty(
            int unprotectedFallHeight,
            boolean bedwarsMode,
            boolean avoidSurvivalFallDamage
    ) {
        if (bedwarsMode || !avoidSurvivalFallDamage) {
            return 0.0;
        }
        int damageHp = unprotectedFallHeight - 4;
        if (damageHp <= 0) {
            return 0.0;
        }
        return 105.0 * damageHp + 35.0 * damageHp * damageHp;
    }
}
