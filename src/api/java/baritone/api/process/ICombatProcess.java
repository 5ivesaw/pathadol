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

package baritone.api.process;

import net.minecraft.world.entity.Entity;

import java.util.function.Predicate;

/**
 * Humanized combat process supporting 1.8 CPS/W-tap PvP and modern cooldown PvP/PvE.
 */
public interface ICombatProcess extends IBaritoneProcess {

    /**
     * Engages entities matching {@code filter}.
     */
    void attack(Predicate<Entity> filter);

    /**
     * Stops targeting and disengages combat.
     */
    void cancel();
}
