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

package baritone.brain;

import baritone.bedwars.BedwarsDefensePlanner;
import baritone.bedwars.BedwarsTacticalHelper.InventorySummary;
import baritone.brain.BrainSchema.ObsSnapshot;

import java.util.Arrays;

/**
 * Multi-head parameterized Bedwars + PvP action contract mapping Neural Policy / Teacher outputs
 * onto Pathadol processes and live movement sliders without tick-to-tick chattering.
 */
public final class BrainActionSpace {

    public static final int NUM_MANEUVERS = Maneuver.values().length; // 13
    public static final int NUM_SLIDERS = 4;
    public static final int NUM_COMBAT_MODES = CombatMode.values().length; // 4

    public enum Maneuver {
        HOLD_HOME_GEN,
        SHOP_BUY_ESSENTIALS,
        SHOP_BUY_COMBAT_UPGRADES,
        DEFEND_HOME_BED,
        RUSH_NEAREST_BED,
        RUSH_WEAKEST_BED,
        FARM_DIAMONDS,
        FARM_EMERALDS,
        STASH_VALUABLES,
        ENGAGE_THREAT_COMBAT,
        RETREAT_HOME,
        FIREBALL_JUMP,
        TACTICAL_VOID_RECALL;

        public static Maneuver fromIndex(int idx) {
            Maneuver[] vals = values();
            if (idx < 0 || idx >= vals.length) {
                return HOLD_HOME_GEN;
            }
            return vals[idx];
        }
    }

    public enum CombatMode {
        BALANCED,
        VOID_KNOCKBACK,
        CRIT_DUEL,
        DEFENSIVE_KITE;

        public static CombatMode fromIndex(int idx) {
            CombatMode[] vals = values();
            if (idx < 0 || idx >= vals.length) {
                return BALANCED;
            }
            return vals[idx];
        }
    }

    public record StyleSliders(
            float riskAppetite,
            float bridgeSpeed,
            float voidCaution,
            float combatAggression
    ) {
        public static final StyleSliders BALANCED = new StyleSliders(0.55f, 0.72f, 0.45f, 0.55f);

        public static StyleSliders fromArray(float[] arr) {
            if (arr == null || arr.length < NUM_SLIDERS) {
                return BALANCED;
            }
            return new StyleSliders(clamp01(arr[0]), clamp01(arr[1]), clamp01(arr[2]), clamp01(arr[3]));
        }

        public float[] toArray() {
            return new float[]{riskAppetite, bridgeSpeed, voidCaution, combatAggression};
        }

        private static float clamp01(float v) {
            return Float.isFinite(v) ? Math.max(0.0f, Math.min(1.0f, v)) : 0.5f;
        }
    }

    public record BrainDecision(
            Maneuver maneuver,
            StyleSliders sliders,
            CombatMode combatMode,
            float valueEstimate,
            float confidence
    ) {}

    /**
     * Enforces minimum decision hold commitment so the Brain doesn't oscillate every 50ms tick
     * while allowing immediate interrupts for melee ambushes, damage spikes, or falling hazards.
     */
    public static final class AntiChatterCommitter {

        private Maneuver committedManeuver = Maneuver.HOLD_HOME_GEN;
        private CombatMode committedCombatMode = CombatMode.BALANCED;
        private StyleSliders smoothedSliders = StyleSliders.BALANCED;
        private int ticksHeld = 0;
        private boolean initialized = false;

        public void reset() {
            this.committedManeuver = Maneuver.HOLD_HOME_GEN;
            this.committedCombatMode = CombatMode.BALANCED;
            this.smoothedSliders = StyleSliders.BALANCED;
            this.ticksHeld = 0;
            this.initialized = false;
        }

        public Maneuver getCommittedManeuver() {
            return committedManeuver;
        }

        public int getTicksHeld() {
            return ticksHeld;
        }

        public BrainDecision filter(
                BrainDecision raw,
                ObsSnapshot snap,
                boolean[] validMask,
                int minHoldTicks,
                boolean subTaskCompleted
        ) {
            if (raw == null) {
                return null;
            }
            ticksHeld++;

            boolean committedStillValid = validMask == null
                    || validMask.length <= committedManeuver.ordinal()
                    || validMask[committedManeuver.ordinal()];

            boolean exposedOnBridge = snap != null
                    && (snap.voidSurroundFraction() >= 0.25 || snap.frontVoidEdgeDist() < 4.5);
            boolean urgentCombatInterrupt = snap != null
                    && (snap.nearestThreatDist() <= 5.5 || (exposedOnBridge && snap.nearestThreatDist() <= 15.5))
                    && raw.maneuver() == Maneuver.ENGAGE_THREAT_COMBAT
                    && committedManeuver != Maneuver.ENGAGE_THREAT_COMBAT;
            boolean urgentDamageOrBaseThreat = snap != null
                    && (snap.hurtTime() >= 8 || (snap.hasEnemyNearHomeBed() && snap.homeBedAlive() && snap.homeBedDist() > 26.0))
                    && (raw.maneuver() == Maneuver.ENGAGE_THREAT_COMBAT
                        || raw.maneuver() == Maneuver.TACTICAL_VOID_RECALL
                        || raw.maneuver() == Maneuver.RETREAT_HOME);

            boolean allowSwitch = !initialized
                    || !committedStillValid
                    || subTaskCompleted
                    || urgentCombatInterrupt
                    || urgentDamageOrBaseThreat
                    || ticksHeld >= Math.max(4, minHoldTicks);

            if (!initialized) {
                initialized = true;
                committedManeuver = raw.maneuver();
                ticksHeld = 0;
            } else if (allowSwitch && raw.maneuver() != committedManeuver) {
                committedManeuver = raw.maneuver();
                ticksHeld = 0;
            }

            // Smooth continuous sliders gently so bridge/parkour toggles don't flicker mid-jump
            StyleSliders target = raw.sliders() != null ? raw.sliders() : StyleSliders.BALANCED;
            float alpha = allowSwitch ? 0.45f : 0.20f;
            smoothedSliders = new StyleSliders(
                    lerp(smoothedSliders.riskAppetite(), target.riskAppetite(), alpha),
                    lerp(smoothedSliders.bridgeSpeed(), target.bridgeSpeed(), alpha),
                    lerp(smoothedSliders.voidCaution(), target.voidCaution(), alpha),
                    lerp(smoothedSliders.combatAggression(), target.combatAggression(), alpha)
            );
            committedCombatMode = raw.combatMode() != null ? raw.combatMode() : CombatMode.BALANCED;

            return new BrainDecision(
                    committedManeuver,
                    smoothedSliders,
                    committedCombatMode,
                    raw.valueEstimate(),
                    raw.confidence()
            );
        }

        private static float lerp(float a, float b, float alpha) {
            return a + (b - a) * alpha;
        }
    }

    private BrainActionSpace() {}

    /**
     * Masks out infeasible maneuvers for the current inventory/arena state.
     * Guarantees at least one fallback maneuver ({@link Maneuver#HOLD_HOME_GEN}) remains valid.
     */
    public static boolean[] computeValidManeuverMask(ObsSnapshot s) {
        boolean[] mask = new boolean[NUM_MANEUVERS];
        Arrays.fill(mask, true);
        if (s == null) {
            return mask;
        }
        InventorySummary inv = s.inventory();
        int iron = inv != null ? inv.iron() : 0;
        int gold = inv != null ? inv.gold() : 0;
        int diamonds = inv != null ? inv.diamonds() : 0;
        int emeralds = inv != null ? inv.emeralds() : 0;
        int blocks = inv != null ? inv.bridgeBlockCount() : 0;
        int fireballs = inv != null ? inv.fireballs() : 0;
        boolean enemiesAlive = s.threats() != null && !s.threats().isEmpty() && s.threats().get(0).present();
        boolean bedsLeft = s.livingEnemyBedCount() > 0;

        // Shopping checks: require base currency & avoid shopping mid-melee
        boolean closeMelee = s.nearestThreatDist() < 4.5;
        mask[Maneuver.SHOP_BUY_ESSENTIALS.ordinal()] = !closeMelee && iron >= 4;
        mask[Maneuver.SHOP_BUY_COMBAT_UPGRADES.ordinal()] = !closeMelee && (gold >= 3 || diamonds >= 2 || iron >= 40 || emeralds >= 4);

        // Defending home bed requires surviving bed + placeable blocks + incomplete defense
        mask[Maneuver.DEFEND_HOME_BED.ordinal()] = s.homeBedAlive()
                && s.homeBedDefenseRatio() < 0.95
                && (blocks > 0 || (inv != null && inv.hardDefenseCount() > 0));

        // Rushing beds requires living enemy beds
        mask[Maneuver.RUSH_NEAREST_BED.ordinal()] = bedsLeft && blocks >= 4;
        mask[Maneuver.RUSH_WEAKEST_BED.ordinal()] = bedsLeft && blocks >= 4;

        // Stashing requires stashable valuables
        boolean hasValuables = diamonds > 0 || emeralds > 0 || gold >= 6 || iron >= 24;
        mask[Maneuver.STASH_VALUABLES.ordinal()] = hasValuables && s.homeBedDist() < 90.0;

        // Combat requires nearby known enemy
        mask[Maneuver.ENGAGE_THREAT_COMBAT.ordinal()] = enemiesAlive && s.nearestThreatDist() <= 32.0;

        // Fireball jump requires held fireball + solid footing
        mask[Maneuver.FIREBALL_JUMP.ordinal()] = fireballs > 0 && s.onGround() && !closeMelee;

        // Tactical void recall only allowed when home bed is alive, far from base, and no diamonds/emeralds/rich gold lost
        mask[Maneuver.TACTICAL_VOID_RECALL.ordinal()] = s.homeBedAlive()
                && s.homeBedDist() > 26.0
                && BedwarsDefensePlanner.isSafeForTacticalVoid(iron, gold, diamonds, emeralds);

        // Retreat home unnecessary when already right at home gen
        mask[Maneuver.RETREAT_HOME.ordinal()] = s.homeBedDist() > 14.0 || (enemiesAlive && s.nearestThreatDist() < 12.0 && s.health() < 11.0f);

        return mask;
    }
}
