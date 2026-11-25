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

        public void advanceTick() {
            if (initialized && ticksHeld < Integer.MAX_VALUE) {
                ticksHeld++;
            }
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
            boolean committedStillValid = validMask == null
                    || validMask.length <= committedManeuver.ordinal()
                    || validMask[committedManeuver.ordinal()];

            boolean exposedOnBridge = snap != null
                    && (snap.voidSurroundFraction() >= 0.25 || snap.frontVoidEdgeDist() < 4.5);
            boolean inBaseUtility = committedManeuver == Maneuver.SHOP_BUY_ESSENTIALS
                    || committedManeuver == Maneuver.SHOP_BUY_COMBAT_UPGRADES
                    || committedManeuver == Maneuver.STASH_VALUABLES
                    || committedManeuver == Maneuver.DEFEND_HOME_BED
                    || committedManeuver == Maneuver.HOLD_HOME_GEN;
            boolean urgentCombatInterrupt = snap != null
                    && (snap.nearestThreatDist() <= 5.5
                        || (inBaseUtility && !baritone.bedwars.BedwarsTacticalHelper.isSafeForBaseUtility(snap.nearestThreatDist(), snap.hurtTime()))
                        || (exposedOnBridge && snap.nearestThreatDist() <= 15.5))
                    && raw.maneuver() == Maneuver.ENGAGE_THREAT_COMBAT
                    && committedManeuver != Maneuver.ENGAGE_THREAT_COMBAT;
            boolean urgentDamageOrBaseThreat = snap != null
                    && (snap.hurtTime() >= 8
                        || (raw.maneuver() == Maneuver.RETREAT_HOME && DeterministicStrategyModel.shouldDisengageForSurvival(snap))
                        || (snap.hasEnemyNearHomeBed() && snap.homeBedAlive() && snap.homeBedDist() > 24.0)
                        || (snap.trapTriggeredRecently() && snap.homeBedAlive() && snap.homeBedDist() > 24.0))
                    && (raw.maneuver() == Maneuver.ENGAGE_THREAT_COMBAT
                        || raw.maneuver() == Maneuver.TACTICAL_VOID_RECALL
                        || raw.maneuver() == Maneuver.RETREAT_HOME);

            boolean activeDefense = committedManeuver == Maneuver.DEFEND_HOME_BED && committedStillValid && !subTaskCompleted;
            boolean defenseCombatInterrupt = activeDefense && snap != null
                    && raw.maneuver() == Maneuver.ENGAGE_THREAT_COMBAT
                    && (snap.hurtTime() > 0 || snap.nearestThreatDist() <= 9.5
                        || (snap.hasEnemyNearHomeBed() && snap.homeBedDist() <= 24.0 && snap.nearestThreatDist() <= 22.0));

            boolean midAirHold = snap != null
                    && !snap.onGround()
                    && committedStillValid
                    && !urgentCombatInterrupt
                    && !urgentDamageOrBaseThreat;

            boolean allowSwitch = !initialized
                    || !committedStillValid
                    || (!activeDefense && !midAirHold && (subTaskCompleted || ticksHeld >= Math.max(4, minHoldTicks)))
                    || urgentCombatInterrupt
                    || urgentDamageOrBaseThreat
                    || defenseCombatInterrupt;

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
        int hardBlocks = inv != null ? inv.hardDefenseCount() : 0;
        int fireballs = inv != null ? inv.fireballs() : 0;
        boolean enemiesAlive = s.threats() != null && !s.threats().isEmpty() && s.threats().get(0).present();
        boolean bedsLeft = s.livingEnemyBedCount() > 0;

        // Unified single-source base-utility safety check (shared with BedwarsProcess.tickBuy/tickStash/tickDefend)
        // Guarantees Brain NEVER picks SHOP/STASH/DEFEND inside the 9.0m combat zone where BedwarsProcess pauses/cancels!
        boolean safeForBaseUtility = baritone.bedwars.BedwarsTacticalHelper.isSafeForBaseUtility(s.nearestThreatDist(), s.hurtTime());
        mask[Maneuver.SHOP_BUY_ESSENTIALS.ordinal()] = safeForBaseUtility && iron >= 4;
        mask[Maneuver.SHOP_BUY_COMBAT_UPGRADES.ordinal()] = safeForBaseUtility && (gold >= 3 || diamonds >= 1 || iron >= 40 || emeralds >= 4);

        // Defending home bed requires surviving bed + placeable blocks + either incomplete Layer 1 or Layer 2 reinforcement
        boolean needsLayer1 = s.homeBedDefenseRatio() < 0.95;
        boolean needsLayer2 = s.homeBedOuterDefenseRatio() > 0.0 && s.homeBedOuterDefenseRatio() < 0.85
                || (s.homeBedDefenseRatio() >= 0.95 && s.homeBedOuterDefenseRatio() < 0.85 && (hardBlocks >= 4 || blocks >= 24));
        mask[Maneuver.DEFEND_HOME_BED.ordinal()] = safeForBaseUtility
                && s.homeBedAlive()
                && (needsLayer1 || needsLayer2)
                && (blocks > 0 || hardBlocks > 0);

        // Path-aware reachability masking: when live BFS confirms an existing connected bridge or
        // <=3-block jumpable gap (blocksNeeded == 0), allow moving/rushing/farming even with 0 blocks!
        // When connectivity is unsampled (< 0, e.g., legacy synthetic snapshots), fall back cleanly.
        mask[Maneuver.FARM_DIAMONDS.ordinal()] = canAffordRoute(blocks, s.blocksNeededDiamond(), s.diamondGenDist() <= 10.0);
        mask[Maneuver.FARM_EMERALDS.ordinal()] = canAffordRoute(blocks, s.blocksNeededEmerald(), s.emeraldGenDist() <= 10.0);
        mask[Maneuver.RUSH_NEAREST_BED.ordinal()] = bedsLeft
                && canAffordRoute(blocks, s.blocksNeededNearestBed(), s.nearestEnemyBedDist() <= 8.0);
        mask[Maneuver.RUSH_WEAKEST_BED.ordinal()] = bedsLeft
                && canAffordRoute(blocks, s.blocksNeededWeakestBed(), s.weakestEnemyBedDist() <= 8.0);

        // Stashing requires stashable valuables and no close melee enemy
        boolean hasValuables = diamonds > 0 || emeralds > 0 || gold >= 6 || iron >= 24;
        mask[Maneuver.STASH_VALUABLES.ordinal()] = safeForBaseUtility && hasValuables && s.homeBedDist() < 90.0;

        // Combat requires nearby known enemy (unified 36m range with DeterministicStrategyModel)
        mask[Maneuver.ENGAGE_THREAT_COMBAT.ordinal()] = enemiesAlive && s.nearestThreatDist() <= 36.0;

        // Fireball jump requires held fireball + solid footing
        mask[Maneuver.FIREBALL_JUMP.ordinal()] = fireballs > 0 && s.onGround() && safeForBaseUtility;

        // Tactical void recall allowed when home bed is alive and either far from base with low-risk loot
        // OR physically stranded on a disconnected island with broken bridge home (< 4 blocks & zero diamonds/emeralds).
        boolean safeLoot = BedwarsDefensePlanner.isSafeForTacticalVoid(iron, gold, diamonds, emeralds);
        boolean strandedDisconnected = s.homeBedDist() > 22.0
                && blocks < 4
                && s.blocksNeededHome() > Math.max(4, blocks)
                && (safeLoot || (diamonds == 0 && emeralds == 0));
        mask[Maneuver.TACTICAL_VOID_RECALL.ordinal()] = s.homeBedAlive()
                && ((s.homeBedDist() > 26.0 && safeLoot) || strandedDisconnected);

        // Retreat home unnecessary when already right at home gen
        mask[Maneuver.RETREAT_HOME.ordinal()] = s.homeBedDist() > 14.0
                || (enemiesAlive && (DeterministicStrategyModel.shouldDisengageForSurvival(s)
                        || (s.nearestThreatDist() < 12.0 && s.health() < 11.0f)));

        return mask;
    }

    private static boolean canAffordRoute(int blocks, int required, boolean nearby) {
        return required >= 0 ? blocks >= required : required == -1 && (nearby || blocks >= 4);
    }
}
