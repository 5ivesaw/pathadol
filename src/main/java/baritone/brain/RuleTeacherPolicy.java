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

import baritone.bedwars.BedwarsTacticalHelper.InventorySummary;
import baritone.brain.BrainActionSpace.BrainDecision;
import baritone.brain.BrainActionSpace.CombatMode;
import baritone.brain.BrainActionSpace.Maneuver;
import baritone.brain.BrainActionSpace.StyleSliders;
import baritone.brain.BrainSchema.ObsSnapshot;
import baritone.brain.BrainSchema.ThreatFeature;

/**
 * High-caliber scripted Bedwars &amp; PvP tactical teacher policy.
 * Serves simultaneously as:
 * <ul>
 *   <li>Default Day-1 Brain fallback when no trained {@code .pbrain} checkpoint is loaded</li>
 *   <li>Macro decision engine for {@code #bws auto}</li>
 *   <li>Supervision reference tagger during {@code #brain record}</li>
 * </ul>
 */
public final class RuleTeacherPolicy {

    private RuleTeacherPolicy() {}

    public static BrainDecision decide(ObsSnapshot s, boolean[] mask) {
        if (s == null) {
            return new BrainDecision(Maneuver.HOLD_HOME_GEN, StyleSliders.BALANCED, CombatMode.BALANCED, 0.0f, 1.0f);
        }
        InventorySummary inv = s.inventory();
        int iron = inv != null ? inv.iron() : 0;
        int gold = inv != null ? inv.gold() : 0;
        int diamonds = inv != null ? inv.diamonds() : 0;
        int emeralds = inv != null ? inv.emeralds() : 0;
        int blocks = inv != null ? inv.bridgeBlockCount() : 0;
        int swordTier = inv != null ? inv.swordTier() : 1;
        double armor = inv != null ? inv.armorPoints() : 0.0;
        double threatDist = s.nearestThreatDist();

        CombatMode combatMode = chooseCombatMode(s, inv);
        StyleSliders sliders = chooseSliders(s, inv, threatDist);
        Maneuver chosen = chooseRawManeuver(s, inv, iron, gold, diamonds, emeralds, blocks, swordTier, armor, threatDist, mask);

        if (mask != null && chosen.ordinal() < mask.length && !mask[chosen.ordinal()]) {
            chosen = fallbackValid(mask);
        }
        return new BrainDecision(chosen, sliders, combatMode, 0.0f, 1.0f);
    }

    /**
     * Detects when an enemy threatens us while bridging or near a void edge so the bot halts
     * backward bridging and fights/knocks them off before entering lethal knockback range.
     */
    public static boolean isBridgeConfrontation(ObsSnapshot s) {
        if (s == null || s.threats() == null || s.threats().isEmpty()) {
            return false;
        }
        ThreatFeature t0 = s.threats().get(0);
        if (!t0.present()) {
            return false;
        }
        boolean exposedOnBridge = s.voidSurroundFraction() >= 0.25 || s.frontVoidEdgeDist() < 4.5;
        if (!exposedOnBridge) {
            return false;
        }
        return t0.dist() <= 13.5
                || (t0.dist() <= 17.5 && (t0.radialClosingSpeed() >= 0.04 || t0.dangerScore() >= 0.36));
    }

    private static Maneuver chooseRawManeuver(
            ObsSnapshot s,
            InventorySummary inv,
            int iron,
            int gold,
            int diamonds,
            int emeralds,
            int blocks,
            int swordTier,
            double armor,
            double threatDist,
            boolean[] mask
    ) {
        // 1. Tactical Void Recall if base is threatened and we carry no valuables
        if (s.hasEnemyNearHomeBed() && s.homeBedAlive() && s.homeBedDist() > 26.0
                && diamonds == 0 && emeralds == 0 && gold < 6 && iron < 24) {
            return Maneuver.TACTICAL_VOID_RECALL;
        }

        // 2. Immediate self-defense, bridge confrontation intercept, or defensive retreat
        boolean bridgeThreat = isBridgeConfrontation(s);
        if (threatDist <= 9.5 || bridgeThreat || (s.hurtTime() > 0 && threatDist <= 16.0)) {
            if (s.health() < 7.5f && threatDist > 5.0 && s.homeBedDist() > 14.0 && s.homeBedDist() < 38.0
                    && (inv == null || inv.gapples() == 0)) {
                return Maneuver.RETREAT_HOME;
            }
            return Maneuver.ENGAGE_THREAT_COMBAT;
        }

        // 3. Stash rich diamonds/emeralds when returning near base
        if ((diamonds >= 4 || emeralds >= 4) && s.homeBedDist() < 26.0) {
            return Maneuver.STASH_VALUABLES;
        }

        // 4. Shopping priority when at/near island shop or sorely under-equipped
        boolean atIsland = s.nearHomeShopOrForge() || s.homeBedDist() <= 22.0;
        boolean canShopEss = mask == null || mask.length <= Maneuver.SHOP_BUY_ESSENTIALS.ordinal()
                || mask[Maneuver.SHOP_BUY_ESSENTIALS.ordinal()];
        boolean canShopUpg = mask == null || mask.length <= Maneuver.SHOP_BUY_COMBAT_UPGRADES.ordinal()
                || mask[Maneuver.SHOP_BUY_COMBAT_UPGRADES.ordinal()];
        if (atIsland) {
            if (canShopUpg && diamonds >= 2 && s.homeBedAlive()) {
                return Maneuver.SHOP_BUY_COMBAT_UPGRADES;
            }
            boolean needsBlocks = blocks < 20 && iron >= 4;
            boolean needsSword = swordTier < 2 && iron >= 10;
            boolean needsShears = (inv == null || inv.shearsTier() == 0) && iron >= 20;
            if (canShopEss && (needsBlocks || needsSword || needsShears)) {
                return Maneuver.SHOP_BUY_ESSENTIALS;
            }
            if (canShopUpg && (gold >= 12 && armor < 13.0 || emeralds >= 4 || (gold >= 7 && swordTier < 3) || (gold >= 3 && (inv == null || inv.gapples() == 0)))) {
                return Maneuver.SHOP_BUY_COMBAT_UPGRADES;
            }
            if (s.homeBedAlive() && s.homeBedDefenseRatio() < 0.72 && (blocks >= 16 || (inv != null && inv.hardDefenseCount() >= 4))) {
                return Maneuver.DEFEND_HOME_BED;
            }
            if (blocks < 16 && iron < 12) {
                return Maneuver.HOLD_HOME_GEN;
            }
        }

        // 5. Mid/late game home defense emergency if enemy threatens bed while we're far with valuables
        if (s.hasEnemyNearHomeBed() && s.homeBedAlive() && s.homeBedDist() > 24.0) {
            return Maneuver.RETREAT_HOME;
        }

        // 6. Rushing / Objectives
        if (s.livingEnemyBedCount() > 0 && blocks >= 12) {
            // Use fireball jump selectively if holding fireball, grounded, long gap/enemy bed > 25b away & no bridge rusher close
            if (inv != null && inv.fireballs() > 0 && s.onGround() && s.frontVoidEdgeDist() < 2.2
                    && s.nearestEnemyBedDist() > 26.0 && threatDist > 16.0) {
                return Maneuver.FIREBALL_JUMP;
            }
            // Prefer weakest bed when another bed has notably faster defense break time
            if (s.weakestEnemyBedBreakSec() + 2.0 < s.nearestEnemyBedBreakSec()) {
                return Maneuver.RUSH_WEAKEST_BED;
            }
            return Maneuver.RUSH_NEAREST_BED;
        }

        // 7. Diamond / Emerald scaling when mid-game blocks or gear need boost
        if (s.diamondGenDist() < 48.0 && diamonds < 4 && armor < 13.0) {
            return Maneuver.FARM_DIAMONDS;
        }
        if (s.emeraldGenDist() < 64.0 && emeralds < 4) {
            return Maneuver.FARM_EMERALDS;
        }
        if (s.livingEnemyBedCount() > 0 && blocks >= 4) {
            return Maneuver.RUSH_WEAKEST_BED;
        }
        return blocks < 12 ? Maneuver.HOLD_HOME_GEN : Maneuver.FARM_DIAMONDS;
    }

    private static CombatMode chooseCombatMode(ObsSnapshot s, InventorySummary inv) {
        if (s.health() <= 6.5f || (s.threats() != null && s.threats().size() > 1 && s.threats().get(1).dist() < 6.0)) {
            return CombatMode.DEFENSIVE_KITE;
        }
        if (isBridgeConfrontation(s) || s.voidSurroundFraction() >= 0.30 || s.frontVoidEdgeDist() <= 3.8) {
            return CombatMode.VOID_KNOCKBACK;
        }
        if (s.modernRuleset() && s.health() >= 13.0f && s.voidSurroundFraction() < 0.2) {
            return CombatMode.CRIT_DUEL;
        }
        return CombatMode.BALANCED;
    }

    private static StyleSliders chooseSliders(ObsSnapshot s, InventorySummary inv, double threatDist) {
        boolean rich = inv != null && (inv.diamonds() >= 2 || inv.emeralds() >= 2);
        float voidCaution = (float) Math.max(0.15, Math.min(0.95,
                0.35 + (rich ? 0.30 : 0.0) + (threatDist < 22.0 ? 0.25 : 0.0) + (!s.homeBedAlive() ? 0.20 : 0.0)));
        float bridgeSpeed = (threatDist > 20.0 && !rich && s.homeBedAlive()) ? 0.86f : 0.58f;
        float riskAppetite = (!s.homeBedAlive() || rich) ? 0.30f : 0.68f;
        float aggression = threatDist < 12.0 ? 0.82f : (s.livingEnemyBedCount() > 0 ? 0.50f : 0.90f);
        if (s.threats() != null && !s.threats().isEmpty()) {
            ThreatFeature t0 = s.threats().get(0);
            if (t0.present() && t0.dangerScore() > 0.7 && s.health() < 10.0f) {
                aggression = 0.25f;
            }
        }
        return new StyleSliders(riskAppetite, bridgeSpeed, voidCaution, aggression);
    }

    private static Maneuver fallbackValid(boolean[] mask) {
        Maneuver[] preferred = {
                Maneuver.HOLD_HOME_GEN,
                Maneuver.RETREAT_HOME,
                Maneuver.RUSH_NEAREST_BED,
                Maneuver.FARM_DIAMONDS,
                Maneuver.ENGAGE_THREAT_COMBAT
        };
        for (Maneuver m : preferred) {
            if (mask[m.ordinal()]) {
                return m;
            }
        }
        for (int i = 0; i < mask.length; i++) {
            if (mask[i]) {
                return Maneuver.fromIndex(i);
            }
        }
        return Maneuver.HOLD_HOME_GEN;
    }
}
