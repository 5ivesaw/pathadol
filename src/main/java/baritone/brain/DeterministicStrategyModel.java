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

import baritone.bedwars.BedwarsMatchState;
import baritone.bedwars.BedwarsTacticalHelper.InventorySummary;
import baritone.brain.BrainActionSpace.BrainDecision;
import baritone.brain.BrainActionSpace.CombatMode;
import baritone.brain.BrainActionSpace.Maneuver;
import baritone.brain.BrainActionSpace.StyleSliders;
import baritone.brain.BrainSchema.ObsSnapshot;
import baritone.brain.BrainSchema.ThreatFeature;

import java.util.Arrays;

/**
 * Principled Expected-Value (EV) and Risk-Adjusted Utility Engine for deterministic BedWars macro
 * decision making. Replaces brittle threshold cascades with continuous carry-risk valuation,
 * forge-breakpoint ETA math, travel/break-time objective scoring, spend-before-stash ordering,
 * and anti-flicker hysteresis.
 */
public final class DeterministicStrategyModel {

    private static final double NEG_INF = -1e6;
    private static final double AVG_BRIDGE_RUN_BPS = 4.2;
    private static final double IRON_GEN_RATE_PER_SEC = 1.45;
    private static final double GOLD_GEN_RATE_PER_SEC = 0.28;

    private DeterministicStrategyModel() {}

    public record StrategyEvaluation(
            Maneuver bestManeuver,
            double[] utilityScores,
            double carryRiskValue,
            double nextBreakpointEtaSec,
            String rationale
    ) {}

    /**
     * Evaluates every maneuver in {@link BrainActionSpace.Maneuver} using expected-value utility
     * math and returns the highest-scoring legal maneuver along with diagnostic scores.
     */
    public static StrategyEvaluation evaluate(ObsSnapshot s, boolean[] mask, BedwarsMatchState matchState) {
        double[] scores = new double[BrainActionSpace.NUM_MANEUVERS];
        Arrays.fill(scores, NEG_INF);

        if (s == null) {
            scores[Maneuver.HOLD_HOME_GEN.ordinal()] = 1.0;
            return new StrategyEvaluation(Maneuver.HOLD_HOME_GEN, scores, 0.0, 0.0, "null_obs_fallback");
        }

        InventorySummary inv = s.inventory();
        int iron = inv != null ? inv.iron() : 0;
        int gold = inv != null ? inv.gold() : 0;
        int diamonds = inv != null ? inv.diamonds() : 0;
        int emeralds = inv != null ? inv.emeralds() : 0;
        int blocks = inv != null ? inv.bridgeBlockCount() : 0;
        int hardBlocks = inv != null ? inv.hardDefenseCount() : 0;
        int swordTier = inv != null ? inv.swordTier() : 1;
        double armor = inv != null ? inv.armorPoints() : 0.0;
        int shears = inv != null ? inv.shearsTier() : 0;
        int gapples = inv != null ? inv.gapples() : 0;
        int fireballs = inv != null ? inv.fireballs() : 0;
        double threatDist = s.nearestThreatDist();

        double carryValue = computeCarryValue(inv, s.homeBedAlive(), s.health());
        double etaSec = estimateNextBreakpointEtaSec(inv);
        boolean atIsland = s.nearHomeShopOrForge() || s.homeBedDist() <= 22.0;

        boolean bridgeThreat = RuleTeacherPolicy.isBridgeConfrontation(s)
                || (threatDist <= 14.5 && (s.frontVoidEdgeDist() < 5.0 || s.voidSurroundFraction() >= 0.20));
        boolean homeIntruder = s.hasEnemyNearHomeBed() && s.homeBedDist() <= 24.0 && threatDist <= 22.0;
        boolean immediateThreat = threatDist <= 9.5 || bridgeThreat || homeIntruder
                || (s.hurtTime() > 0 && threatDist <= 16.0);

        // 1. Emergency Void Recall (fast respawn to defend bed when carrying low-risk loot)
        if (isAllowed(mask, Maneuver.TACTICAL_VOID_RECALL)) {
            if (s.hasEnemyNearHomeBed() && s.homeBedAlive() && s.homeBedDist() > 26.0
                    && diamonds == 0 && emeralds == 0 && gold < 6 && iron < 24) {
                scores[Maneuver.TACTICAL_VOID_RECALL.ordinal()] = 120.0;
            } else {
                scores[Maneuver.TACTICAL_VOID_RECALL.ordinal()] = -20.0;
            }
        }

        // 2. Combat & Defensive Retreat EV
        if (isAllowed(mask, Maneuver.ENGAGE_THREAT_COMBAT)) {
            if (immediateThreat) {
                double combatEv = 95.0;
                if (homeIntruder) combatEv += 15.0;
                if (bridgeThreat) combatEv += 12.0;
                if (threatDist <= 5.0) combatEv += 10.0;
                scores[Maneuver.ENGAGE_THREAT_COMBAT.ordinal()] = combatEv;
            } else if (s.livingEnemyBedCount() == 0 && threatDist <= 36.0) {
                // Final elimination hunt
                scores[Maneuver.ENGAGE_THREAT_COMBAT.ordinal()] = 32.0 + Math.max(0.0, (36.0 - threatDist) * 0.4);
            } else if (threatDist <= 15.0 && s.health() >= 14.0f && swordTier >= 2) {
                scores[Maneuver.ENGAGE_THREAT_COMBAT.ordinal()] = 18.0 + (15.0 - threatDist) * 0.7;
            } else {
                scores[Maneuver.ENGAGE_THREAT_COMBAT.ordinal()] = 1.0;
            }
        }

        if (isAllowed(mask, Maneuver.RETREAT_HOME)) {
            double retreatEv = 0.5;
            if (immediateThreat && s.health() < 7.5f && threatDist > 5.0
                    && s.homeBedDist() > 14.0 && s.homeBedDist() < 38.0
                    && !homeIntruder && gapples == 0) {
                retreatEv = 112.0;
            } else if (s.hasEnemyNearHomeBed() && s.homeBedAlive() && s.homeBedDist() > 24.0) {
                retreatEv = 88.0 + carryValue * 2.0;
            } else if (carryValue >= 5.5 && s.homeBedDist() > 18.0 && s.homeBedDist() < 58.0 && threatDist > 8.0) {
                // Bring rich mid/diamond haul safely back home to spend/stash
                retreatEv = 22.0 + carryValue * 1.4;
            }
            scores[Maneuver.RETREAT_HOME.ordinal()] = retreatEv;
        }

        // 3. Base Economy: Spend-Before-Stash Shopping & Upgrades
        boolean canShopEss = isAllowed(mask, Maneuver.SHOP_BUY_ESSENTIALS);
        boolean canShopUpg = isAllowed(mask, Maneuver.SHOP_BUY_COMBAT_UPGRADES);

        boolean needTrapOrUpgradeDia = diamonds >= 2
                || (diamonds == 1 && (matchState == null || matchState.getActiveTrapCount() == 0 || !matchState.hasHealPool()));
        boolean affordChainArmor = iron >= 40 && armor < 9.0;
        boolean savingIronForChain = armor < 9.0 && iron >= 28 && iron < 40 && blocks >= 16;
        boolean affordIronArmor = gold >= 12 && armor < 12.0;
        boolean affordIronSword = gold >= 7 && swordTier < 3;
        boolean affordEmGear = emeralds >= 4;
        boolean affordGapple = gold >= 3 && gapples == 0
                && (armor >= 12.0 || !s.homeBedAlive() || threatDist < 20.0);

        boolean wantUpgradeVisit = atIsland && canShopUpg && (
                (needTrapOrUpgradeDia && (s.homeBedAlive() || diamonds >= 4) && (blocks >= 16 || !canShopEss))
                        || affordChainArmor
                        || affordIronArmor
                        || affordIronSword
                        || affordEmGear
                        || affordGapple
        );

        boolean urgentBlockNeed = s.hasEnemyNearHomeBed() || !s.homeBedAlive() || threatDist < 22.0;
        boolean alreadyShopping = s.activeManeuverOrdinal() == Maneuver.SHOP_BUY_ESSENTIALS.ordinal();
        // At spawn/base with 0 blocks & no urgent intruder, wait at gen for >=8 iron (32+ wool) instead of a 4-iron trip;
        // once already shopping or topping up from >=16 blocks, spend every remaining 4 iron on wool!
        boolean needsEmergencyBlocks = blocks < 16 && (urgentBlockNeed || alreadyShopping ? iron >= 4 : iron >= 8);
        boolean needsBulkBlocks = blocks < 64 && (alreadyShopping || blocks >= 16 ? iron >= 4 : iron >= 8)
                && !savingIronForChain && !affordChainArmor;
        boolean needsStoneSword = swordTier < 2 && iron >= 10 && gold < 7 && !affordChainArmor;
        boolean needsShears = shears == 0 && iron >= 20 && !savingIronForChain && !affordChainArmor;

        if (canShopEss) {
            double essEv = 0.0;
            if (atIsland) {
                if (needsEmergencyBlocks) {
                    essEv = 76.0;
                } else if (needsStoneSword) {
                    essEv = 66.0;
                } else if (needsBulkBlocks) {
                    essEv = 58.0 + (64 - blocks) * 0.22;
                } else if (needsShears) {
                    essEv = 52.0;
                }
            }
            scores[Maneuver.SHOP_BUY_ESSENTIALS.ordinal()] = essEv;
        }

        if (canShopUpg) {
            double upgEv = 0.0;
            if (wantUpgradeVisit) {
                upgEv = 68.0;
                if (needTrapOrUpgradeDia && blocks >= 16) upgEv = 74.0;
                if (affordEmGear) upgEv = Math.max(upgEv, 73.0);
                if (affordIronArmor || affordChainArmor) upgEv = Math.max(upgEv, 71.0);
                if (affordIronSword) upgEv = Math.max(upgEv, 69.0);
            }
            scores[Maneuver.SHOP_BUY_COMBAT_UPGRADES.ordinal()] = upgEv;
        }

        // 4. Proactive Chest Stashing (strictly AFTER spendable upgrades/gear at base)
        if (isAllowed(mask, Maneuver.STASH_VALUABLES)) {
            double stashEv = 0.0;
            boolean hasUnspentValuables = (diamonds >= 1 && !wantUpgradeVisit)
                    || (emeralds >= 1 && !affordEmGear)
                    || (gold >= 6 && !affordIronArmor && !affordIronSword);
            if (s.homeBedDist() < 26.0 && blocks >= 16 && hasUnspentValuables) {
                stashEv = 44.0 + Math.min(10.0, carryValue * 1.6);
            }
            scores[Maneuver.STASH_VALUABLES.ordinal()] = stashEv;
        }

        // 5. Fast Complete Bed Defense (finish full 8/8 Layer 1 shell cleanly instead of leaving half-exposed at 0.60)
        if (isAllowed(mask, Maneuver.DEFEND_HOME_BED)) {
            double defEv = 0.0;
            boolean finishingDef = s.activeManeuverOrdinal() == Maneuver.DEFEND_HOME_BED.ordinal()
                    && s.homeBedAlive() && s.homeBedDefenseRatio() < 0.90 && (blocks >= 1 || hardBlocks >= 1);
            if (atIsland && s.homeBedAlive()) {
                if (finishingDef) {
                    defEv = 80.0;
                } else if (s.homeBedDefenseRatio() < 0.75 && (blocks >= 24 || hardBlocks >= 4)) {
                    defEv = 42.0;
                }
            }
            scores[Maneuver.DEFEND_HOME_BED.ordinal()] = defEv;
        }

        // 6. Home Forge Wait (NEVER abort an active off-island rush at 15 wool — wait at base for 48 wool before leaving!)
        if (isAllowed(mask, Maneuver.HOLD_HOME_GEN)) {
            double holdEv = 2.0;
            boolean midRushOrGen = s.activeManeuverOrdinal() == Maneuver.RUSH_NEAREST_BED.ordinal()
                    || s.activeManeuverOrdinal() == Maneuver.RUSH_WEAKEST_BED.ordinal()
                    || s.activeManeuverOrdinal() == Maneuver.FARM_DIAMONDS.ordinal()
                    || s.activeManeuverOrdinal() == Maneuver.FARM_EMERALDS.ordinal();
            if (blocks < 4 && (iron < 4 || !canShopEss || !atIsland)) {
                holdEv = 58.0;
            } else if (atIsland && blocks < 16 && (iron < 8 || !canShopEss)) {
                holdEv = 56.0;
            } else if (!midRushOrGen && blocks < 12 && (iron < 4 || !canShopEss || !atIsland)) {
                holdEv = 52.0;
            } else if (atIsland && !s.hasEnemyNearHomeBed() && etaSec > 0.0 && etaSec <= 2.8 && blocks < 48) {
                holdEv = 34.0 - etaSec * 4.0;
            }
            scores[Maneuver.HOLD_HOME_GEN.ordinal()] = holdEv;
        }

        // 7. Objective Map Control: Bed Rushes, Diamonds, Emeralds, Fireball Jump
        boolean preferEmeralds = matchState != null && matchState.shouldPrioritizeEmeralds(s.livingEnemyBedCount());
        boolean preferDiamonds = matchState != null && matchState.shouldPrioritizeDiamonds(s.homeBedAlive());

        if (s.livingEnemyBedCount() > 0 && blocks >= 12) {
            double travelSec = Math.max(2.0, s.nearestEnemyBedDist() / AVG_BRIDGE_RUN_BPS);
            double nearBreak = Math.max(0.5, s.nearestEnemyBedBreakSec());
            double weakBreak = Math.max(0.5, s.weakestEnemyBedBreakSec());

            if (isAllowed(mask, Maneuver.RUSH_NEAREST_BED)) {
                double rushEv = 28.0 + (22.0 / (travelSec + nearBreak)) + (blocks >= 40 ? 4.0 : 0.0);
                scores[Maneuver.RUSH_NEAREST_BED.ordinal()] = rushEv;
            }
            if (isAllowed(mask, Maneuver.RUSH_WEAKEST_BED)) {
                double weakBonus = (weakBreak + 1.8 < nearBreak) ? 5.5 : -1.0;
                double weakEv = 28.0 + (22.0 / (travelSec + weakBreak)) + weakBonus + (blocks >= 40 ? 4.0 : 0.0);
                scores[Maneuver.RUSH_WEAKEST_BED.ordinal()] = weakEv;
            }
            if (isAllowed(mask, Maneuver.FIREBALL_JUMP)
                    && fireballs > 0 && s.onGround() && s.frontVoidEdgeDist() < 2.2
                    && s.nearestEnemyBedDist() > 26.0 && threatDist > 16.0) {
                scores[Maneuver.FIREBALL_JUMP.ordinal()] = 46.0;
            }
        } else if (s.livingEnemyBedCount() > 0 && blocks >= 4 && isAllowed(mask, Maneuver.RUSH_WEAKEST_BED)) {
            scores[Maneuver.RUSH_WEAKEST_BED.ordinal()] = 14.0;
        }

        if (isAllowed(mask, Maneuver.FARM_DIAMONDS) && blocks >= 8 && s.diamondGenDist() < 48.0 && diamonds < 4) {
            double diaTravel = Math.max(2.0, s.diamondGenDist() / AVG_BRIDGE_RUN_BPS);
            double diaNeed = (matchState == null || matchState.getSharpnessTier() == 0) ? 1.35 : 0.95;
            if (preferDiamonds) diaNeed += 0.35;
            double diaEv = 24.0 + (18.0 * diaNeed / diaTravel);
            if (s.nearestEnemyBedDist() > 28.0 && threatDist > 12.0 && (armor < 13.0 || preferDiamonds)) {
                diaEv += 6.0;
            }
            scores[Maneuver.FARM_DIAMONDS.ordinal()] = diaEv;
        }

        if (isAllowed(mask, Maneuver.FARM_EMERALDS) && blocks >= 8 && s.emeraldGenDist() < 64.0 && emeralds < 6) {
            double emTravel = Math.max(3.0, s.emeraldGenDist() / AVG_BRIDGE_RUN_BPS);
            double emNeed = (armor < 15.0 || swordTier < 4) ? 1.35 : 0.70;
            if (preferEmeralds) emNeed += 0.85;
            double emEv = 22.0 + (22.0 * emNeed / emTravel);
            if (preferEmeralds && threatDist > 12.0) {
                emEv += 14.0;
            }
            scores[Maneuver.FARM_EMERALDS.ordinal()] = emEv;
        }

        // 8. Smooth Anti-Flicker Hysteresis (skip during immediate threat or recall emergencies)
        int activeOrd = s.activeManeuverOrdinal();
        if (!immediateThreat && !s.hasEnemyNearHomeBed()
                && activeOrd >= 0 && activeOrd < scores.length
                && isAllowed(mask, Maneuver.fromIndex(activeOrd))
                && scores[activeOrd] > 10.0
                && s.maneuverHoldTicks() < 45) {
            scores[activeOrd] += 1.25;
        }

        // Pick argmax valid maneuver
        int bestIdx = -1;
        double bestScore = NEG_INF;
        for (int i = 0; i < scores.length; i++) {
            if (isAllowed(mask, Maneuver.fromIndex(i)) && scores[i] > bestScore) {
                bestScore = scores[i];
                bestIdx = i;
            }
        }
        Maneuver chosen = bestIdx >= 0 ? Maneuver.fromIndex(bestIdx) : fallbackValid(mask);
        String rationale = String.format(
                java.util.Locale.ROOT,
                "ev=%.1f,carry=%.2f,eta=%.1fs,blk=%d,td=%.1f",
                bestScore, carryValue, etaSec, blocks, threatDist
        );
        return new StrategyEvaluation(chosen, scores, carryValue, etaSec, rationale);
    }

    /**
     * Full BrainDecision wrapper matching {@link RuleTeacherPolicy#decide}.
     */
    public static BrainDecision decide(ObsSnapshot s, boolean[] mask, BedwarsMatchState matchState) {
        if (s == null) {
            return new BrainDecision(Maneuver.HOLD_HOME_GEN, StyleSliders.BALANCED, CombatMode.BALANCED, 0.0f, 1.0f);
        }
        StrategyEvaluation eval = evaluate(s, mask, matchState);
        InventorySummary inv = s.inventory();
        CombatMode combatMode = chooseCombatMode(s);
        StyleSliders sliders = chooseSliders(s, inv, s.nearestThreatDist(), eval.carryRiskValue());
        return new BrainDecision(eval.bestManeuver(), sliders, combatMode, (float) Math.tanh(eval.utilityScores()[eval.bestManeuver().ordinal()] / 50.0), 1.0f);
    }

    /**
     * Continuous expected-loss valuation of held currencies and blocks.
     */
    public static double computeCarryValue(InventorySummary inv, boolean homeBedAlive, float health) {
        if (inv == null) {
            return 0.0;
        }
        double raw = inv.iron() * 0.03
                + inv.gold() * 0.25
                + inv.diamonds() * 1.15
                + inv.emeralds() * 1.85
                + Math.min(inv.bridgeBlockCount(), 64) * 0.015;
        double bedMult = homeBedAlive ? 1.0 : 1.45;
        double hpMult = 1.0 + 0.50 * Math.max(0.0, (12.0 - health) / 12.0);
        return raw * bedMult * hpMult;
    }

    /**
     * Estimates seconds until the next meaningful gear/block forge breakpoint, or {@code 99.0} if none is close.
     */
    public static double estimateNextBreakpointEtaSec(InventorySummary inv) {
        if (inv == null) {
            return 0.0;
        }
        double best = 99.0;
        if (inv.bridgeBlockCount() < 16 && inv.iron() < 4) {
            best = Math.min(best, (4 - inv.iron()) / IRON_GEN_RATE_PER_SEC);
        }
        if (inv.swordTier() < 2 && inv.iron() < 10 && inv.gold() < 7) {
            best = Math.min(best, (10 - inv.iron()) / IRON_GEN_RATE_PER_SEC);
        }
        if (inv.armorPoints() < 9.0 && inv.iron() >= 34 && inv.iron() < 40) {
            best = Math.min(best, (40 - inv.iron()) / IRON_GEN_RATE_PER_SEC);
        }
        if (inv.swordTier() < 3 && inv.gold() >= 5 && inv.gold() < 7) {
            best = Math.min(best, (7 - inv.gold()) / GOLD_GEN_RATE_PER_SEC);
        }
        if (inv.armorPoints() < 12.0 && inv.gold() >= 10 && inv.gold() < 12) {
            best = Math.min(best, (12 - inv.gold()) / GOLD_GEN_RATE_PER_SEC);
        }
        return best;
    }

    private static CombatMode chooseCombatMode(ObsSnapshot s) {
        if (s.health() <= 6.5f || (s.threats() != null && s.threats().size() > 1 && s.threats().get(1).dist() < 6.0)) {
            return CombatMode.DEFENSIVE_KITE;
        }
        if (RuleTeacherPolicy.isBridgeConfrontation(s) || s.voidSurroundFraction() >= 0.30 || s.frontVoidEdgeDist() <= 3.8) {
            return CombatMode.VOID_KNOCKBACK;
        }
        if (s.modernRuleset() && s.health() >= 13.0f && s.voidSurroundFraction() < 0.2) {
            return CombatMode.CRIT_DUEL;
        }
        return CombatMode.BALANCED;
    }

    private static StyleSliders chooseSliders(ObsSnapshot s, InventorySummary inv, double threatDist, double carryValue) {
        boolean rich = carryValue >= 3.0 || (inv != null && (inv.diamonds() >= 2 || inv.emeralds() >= 2));
        float voidCaution = (float) Math.max(0.15, Math.min(0.95,
                0.35 + (rich ? 0.30 : 0.0) + (threatDist < 22.0 ? 0.25 : 0.0) + (!s.homeBedAlive() ? 0.20 : 0.0)));
        float bridgeSpeed = (threatDist > 20.0 && !rich && s.homeBedAlive()) ? 0.88f : 0.62f;
        float riskAppetite = (!s.homeBedAlive() || rich) ? 0.30f : 0.72f;
        float aggression = threatDist < 12.0 ? 0.84f : (s.livingEnemyBedCount() > 0 ? 0.56f : 0.92f);
        if (s.threats() != null && !s.threats().isEmpty()) {
            ThreatFeature t0 = s.threats().get(0);
            if (t0.present() && t0.dangerScore() > 0.7 && s.health() < 10.0f) {
                aggression = 0.25f;
            }
        }
        return new StyleSliders(riskAppetite, bridgeSpeed, voidCaution, aggression);
    }

    private static boolean isAllowed(boolean[] mask, Maneuver m) {
        return mask == null || m.ordinal() >= mask.length || mask[m.ordinal()];
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
            if (isAllowed(mask, m)) {
                return m;
            }
        }
        if (mask != null) {
            for (int i = 0; i < mask.length; i++) {
                if (mask[i]) {
                    return Maneuver.fromIndex(i);
                }
            }
        }
        return Maneuver.HOLD_HOME_GEN;
    }
}
