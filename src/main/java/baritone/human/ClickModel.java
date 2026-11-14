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

import baritone.behavior.look.ForkableRandom;

/**
 * Stochastic human click generator and reaction-tail controller:
 * <ul>
 *   <li>Gates block/entity interaction clicks until the smooth camera arrives on target</li>
 *   <li>Adds a 1..3 tick post-break over-hold reaction tail after breaking a block</li>
 *   <li>Generates non-deterministic right-click placement timings (hold, jitter, butterfly)</li>
 * </ul>
 */
public final class ClickModel {

    private final ForkableRandom rand;
    private int postBreakOverHoldTicks = 0;
    private int rightClickCooldownTicks = 0;
    private boolean butterflyAlternate = false;

    public ClickModel(ForkableRandom rand) {
        this.rand = rand;
    }

    /**
     * Called by {@link baritone.utils.BlockBreakHelper} when a block finishes breaking.
     * Humans cannot release left-click in 0ms; they hold for 1..3 additional ticks (50-150ms).
     */
    public void onBlockBroken() {
        this.postBreakOverHoldTicks = 1 + (int) Math.floor(rand.nextDouble() * 3.0);
    }

    /**
     * Immediately clears any remaining post-break reaction tail when transitioning away from
     * a finished break into item pickup or locomotion so the camera never stares at broken air.
     */
    public void cancelOverHold() {
        this.postBreakOverHoldTicks = 0;
    }

    /**
     * Resolves whether left-click (break/attack) should be active this tick.
     *
     * @param requested       whether the movement/process requested left-click
     * @param crosshairReady  whether the crosshair has arrived within tolerance of the target
     * @return whether left-click should be held this tick
     */
    public boolean stepLeftClick(boolean requested, boolean crosshairReady) {
        if (requested && crosshairReady) {
            return true;
        }
        if (postBreakOverHoldTicks > 0) {
            postBreakOverHoldTicks--;
            return true;
        }
        return false;
    }

    /**
     * Resolves whether a right-click placement pulse should fire this tick.
     *
     * @param requested       whether the movement/process requested right-click
     * @param crosshairReady  whether the crosshair has arrived on the target block face
     * @param profile         active human profile
     * @return whether right-click should fire on this tick
     */
    public boolean stepRightClick(boolean requested, boolean crosshairReady, HumanProfile profile) {
        if (rightClickCooldownTicks > 0) {
            rightClickCooldownTicks--;
        }
        if (!requested || !crosshairReady) {
            return false;
        }
        if (rightClickCooldownTicks > 0) {
            return false;
        }
        this.rightClickCooldownTicks = sampleNextRightClickDelayTicks(profile) - 1;
        return true;
    }

    /**
     * Samples the next inter-click interval in ticks (50ms per tick) from the profile's click distribution.
     */
    public int sampleNextRightClickDelayTicks(HumanProfile profile) {
        return switch (profile.clickStyle()) {
            case HOLD -> {
                // Vanilla held right-click repeats every 4 ticks, with occasional 3 or 5 tick human release variance
                double u = rand.nextDouble();
                yield u < 0.15 ? 3 : (u < 0.82 ? 4 : 5);
            }
            case BUTTERFLY -> {
                // Alternating double-finger rhythm (~11-15 CPS -> 1 to 3 ticks between clicks)
                butterflyAlternate = !butterflyAlternate;
                double meanTicks = 20.0 / Math.max(8.0, profile.cpsMean());
                double jitter = HumanProfile.nextGaussian(rand) * 0.45 + (butterflyAlternate ? -0.25 : 0.25);
                yield Math.max(1, Math.min(4, (int) Math.round(meanTicks + jitter)));
            }
            case JITTER -> {
                // Stochastic jitter distribution around cpsMean
                double cps = Math.max(4.0, profile.cpsMean() + HumanProfile.nextGaussian(rand) * profile.cpsStd());
                double ticks = 20.0 / cps;
                int base = (int) Math.floor(ticks);
                double frac = ticks - base;
                int sampled = base + (rand.nextDouble() < frac ? 1 : 0);
                yield Math.max(1, Math.min(6, sampled));
            }
        };
    }

    public int postBreakOverHoldTicks() {
        return postBreakOverHoldTicks;
    }

    public boolean isOverHolding() {
        return postBreakOverHoldTicks > 0;
    }
}
