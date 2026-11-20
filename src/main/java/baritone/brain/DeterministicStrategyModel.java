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
import baritone.bedwars.BedwarsTacticalHelper;
import baritone.bedwars.BedwarsTacticalHelper.FightEvaluation;
import baritone.bedwars.BedwarsTacticalHelper.InventorySummary;
import baritone.brain.BrainActionSpace.BrainDecision;
import baritone.brain.BrainActionSpace.CombatMode;
import baritone.brain.BrainActionSpace.Maneuver;
import baritone.brain.BrainActionSpace.StyleSliders;
import baritone.brain.BrainSchema.ObsSnapshot;
import baritone.brain.BrainSchema.ThreatFeature;

import java.util.Arrays;
import java.util.Locale;

/**
 * Infinite Axis Utility System (IAUS) macro decision engine with Dave Mark's compensation factor.
 * Scores every legal {@link Maneuver} smoothly in {@code [0.0, 1.0]}, eliminating brittle disjoint
 * threshold cliffs so anti-flicker hysteresis ({@code +0.06}) meaningfully stabilizes decisions while
 * preserving crisp emergency interrupts (home-bed threat, close melee, high-carry disengage).
 */
public final class DeterministicStrategyModel {

    private static final double NEG_INF = -1.0;
    private static final double AVG_BRIDGE_RUN_BPS = 4.2;
    private static final double BASE_IRON_GEN_RATE_PER_SEC = 1.45;
    private static final double BASE_GOLD_GEN_RATE_PER_SEC = 0.28;
    private static final double HYSTERESIS_BONUS = 0.06;

    private DeterministicStrategyModel() {}

    public record StrategyEvaluation(
            Maneuver bestManeuver,
            double[] utilityScores,
            double carryRiskValue,
            double nextBreakpointEtaSec,
            String rationale
    ) {}

    /**
     * Evaluates every maneuver in {@link BrainActionSpace.Maneuver} on a unified smooth {@code [0, 1]}
     * IAUS utility scale using Dave Mark's compensation factor and returns the highest-scoring legal maneuver.
     */
    public static StrategyEvaluation evaluate(ObsSnapshot s, boolean[] mask, BedwarsMatchState matchState) {
        double[] scores = new double[BrainActionSpace.NUM_MANEUVERS];
        Arrays.fill(scores, NEG_INF);

        if (s == null) {
            scores[Maneuver.HOLD_HOME_GEN.ordinal()] = 0.50;
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

        int forgeTier = matchState != null ? matchState.getForgeTier() : s.forgeTier();
        int activeTraps = matchState != null ? matchState.getActiveTrapCount() : s.activeTrapCount();
        boolean hasHealPool = matchState != null && matchState.hasHealPool();
        int sharpTier = matchState != null ? matchState.getSharpnessTier() : s.sharpnessTier();
        boolean trapPopped = s.trapTriggeredRecently()
                || (matchState != null && matchState.wasTrapTriggeredRecently(System.currentTimeMillis(), 12_000L));
        boolean baseUnderAttack = (s.hasEnemyNearHomeBed() || trapPopped) && s.homeBedAlive();

        double carryValue = computeCarryValue(inv, s.homeBedAlive(), s.health());
        double etaSec = estimateNextBreakpointEtaSec(inv, forgeTier);
        boolean atIsland = s.nearHomeShopOrForge() || s.homeBedDist() <= 22.0;

        boolean bridgeThreat = RuleTeacherPolicy.isBridgeConfrontation(s)
                || (threatDist <= 14.5 && (s.frontVoidEdgeDist() < 5.0 || s.voidSurroundFraction() >= 0.20));
        boolean homeIntruder = baseUnderAttack && s.homeBedDist() <= 24.0 && threatDist <= 22.0;
        boolean immediateThreat = threatDist <= 9.5 || bridgeThreat || homeIntruder
                || (s.hurtTime() > 0 && threatDist <= 16.0);

        // Deterministic fight win-probability & high-carry disengage check (e.g., 6 emeralds in a 50/50 fight)
        double fightWinProb = s.fightWinProb();
        FightEvaluation carryFightEval = BedwarsTacticalHelper.evaluateFightWinProbability(
                s.health() + s.absorption(),
                armor + (matchState != null ? matchState.getProtectionTier() : s.protectionTier()) * 1.6,
                Math.max(1, swordTier),
                sharpTier,
                gapples,
                s.voidSurroundFraction(),
                20.0,
                armor, // conservative baseline if enemy gear not explicitly sampled
                Math.max(1, swordTier),
                0,
                0.25,
                diamonds,
                emeralds,
                gold,
                s.homeBedAlive()
        );
        // Use live-observed enemy win probability when a threat is actually tracked; keep carry-risk threshold
        double effectiveWinProb = (s.threats() != null && !s.threats().isEmpty() && s.threats().get(0).present())
                ? fightWinProb
                : carryFightEval.winProbability();
        boolean highCarryDisengage = !homeIntruder
                && threatDist > 3.8 && threatDist <= 20.0
                && ((emeralds >= 4 && effectiveWinProb < 0.78)
                    || (carryFightEval.shouldDisengageForLoot() && effectiveWinProb < 0.65));

        // 1. Emergency Void Recall (fast respawn to defend bed when carrying low-risk loot)
        if (isAllowed(mask, Maneuver.TACTICAL_VOID_RECALL)) {
            if (baseUnderAttack && s.homeBedDist() > 26.0
                    && diamonds == 0 && emeralds == 0 && gold < 6 && iron < 24) {
                double distFactor = linearCurve(s.homeBedDist(), 24.0, 64.0);
                scores[Maneuver.TACTICAL_VOID_RECALL.ordinal()] = 0.94 + 0.04 * distFactor;
            } else {
                scores[Maneuver.TACTICAL_VOID_RECALL.ordinal()] = 0.01;
            }
        }

        // 2. Combat & Defensive Retreat IAUS Curves
        if (isAllowed(mask, Maneuver.ENGAGE_THREAT_COMBAT)) {
            double combatUtil;
            if (highCarryDisengage && !bridgeThreat) {
                // Disengage/run home with rich emerald/diamond haul instead of taking a coinflip fight
                combatUtil = 0.34 * effectiveWinProb;
            } else if (immediateThreat) {
                double prox = invLinearCurve(threatDist, 2.5, 22.0);
                double urgency = homeIntruder ? 0.98 : (bridgeThreat ? 0.94 : 0.86);
                double winMod = 0.82 + 0.18 * effectiveWinProb;
                combatUtil = 0.84 + 0.12 * iaus(prox, urgency, winMod);
                if (homeIntruder) {
                    combatUtil = Math.max(combatUtil, 0.92);
                } else if (bridgeThreat) {
                    combatUtil = Math.max(combatUtil, 0.89);
                }
            } else if (s.livingEnemyBedCount() == 0 && threatDist <= 36.0) {
                // Final elimination hunt (unified 36m range with BrainActionSpace)
                double huntProx = invLinearCurve(threatDist, 4.0, 36.0);
                combatUtil = 0.56 + 0.16 * iaus(huntProx, 0.65 + 0.35 * effectiveWinProb);
            } else if (threatDist <= 16.0 && s.health() >= 14.0f && swordTier >= 2 && effectiveWinProb >= 0.55) {
                double oppProx = invLinearCurve(threatDist, 4.0, 16.0);
                combatUtil = 0.44 + 0.14 * iaus(oppProx, effectiveWinProb);
            } else {
                combatUtil = 0.06;
            }
            scores[Maneuver.ENGAGE_THREAT_COMBAT.ordinal()] = clamp01(combatUtil);
        }

        if (isAllowed(mask, Maneuver.RETREAT_HOME)) {
            double retreatUtil = 0.04;
            if (highCarryDisengage && s.homeBedDist() > 14.0) {
                // Rich loot in unfavorable/even fight -> disengage & escape home cleanly!
                double riskFactor = linearCurve(carryValue, 3.0, 14.0);
                retreatUtil = Math.max(retreatUtil, 0.88 + 0.08 * riskFactor);
            }
            if (immediateThreat && s.health() < 7.5f && threatDist > 5.0
                    && s.homeBedDist() > 14.0 && s.homeBedDist() < 38.0
                    && !homeIntruder && gapples == 0) {
                double lowHpUrgency = invLinearCurve(s.health(), 2.0, 8.0);
                retreatUtil = Math.max(retreatUtil, 0.91 + 0.06 * lowHpUrgency);
            } else if (baseUnderAttack && s.homeBedDist() > 22.0) {
                // Enemy approaching/at our home bed while we're away (e.g. at diamond gen) -> rush back immediately!
                double distUrgency = linearCurve(s.homeBedDist(), 18.0, 56.0);
                retreatUtil = Math.max(retreatUtil, 0.86 + 0.06 * distUrgency);
            } else if (carryValue >= 5.5 && s.homeBedDist() > 18.0 && s.homeBedDist() < 64.0 && threatDist > 8.0) {
                // Bring rich mid/diamond haul safely back home to spend/stash
                double haulFactor = linearCurve(carryValue, 4.0, 14.0);
                double homeNear = invLinearCurve(s.homeBedDist(), 18.0, 72.0);
                retreatUtil = Math.max(retreatUtil, 0.54 + 0.16 * iaus(haulFactor, homeNear));
            }
            scores[Maneuver.RETREAT_HOME.ordinal()] = clamp01(retreatUtil);
        }

        // 3. Base Economy: Spend-Before-Stash Shopping & Upgrades
        boolean canShopEss = isAllowed(mask, Maneuver.SHOP_BUY_ESSENTIALS);
        boolean canShopUpg = isAllowed(mask, Maneuver.SHOP_BUY_COMBAT_UPGRADES);

        boolean needTrapOrUpgradeDia = diamonds >= 2
                || (diamonds == 1 && (activeTraps == 0 || !hasHealPool));
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

        boolean urgentBlockNeed = baseUnderAttack || !s.homeBedAlive() || threatDist < 22.0;
        boolean alreadyShopping = s.activeManeuverOrdinal() == Maneuver.SHOP_BUY_ESSENTIALS.ordinal();
        boolean needsEmergencyBlocks = blocks < 16 && (urgentBlockNeed || alreadyShopping ? iron >= 4 : iron >= 8);
        boolean needsBulkBlocks = blocks < 64 && (alreadyShopping || blocks >= 16 ? iron >= 4 : iron >= 8)
                && !savingIronForChain && !affordChainArmor;
        boolean needsStoneSword = swordTier < 2 && iron >= 10 && gold < 7 && !affordChainArmor;
        boolean needsShears = shears == 0 && iron >= 20 && !savingIronForChain && !affordChainArmor;
        boolean needsHardBedTools = s.livingEnemyBedCount() > 0 && s.weakestEnemyBedBreakSec() >= 3.2
                && iron >= 10 && inv != null && (inv.pickaxeTier() < 2 || inv.axeTier() < 2)
                && !savingIronForChain && !affordChainArmor;

        if (canShopEss) {
            double essUtil = 0.02;
            if (atIsland) {
                if (needsEmergencyBlocks) {
                    double blockDeficit = invLinearCurve(blocks, 0.0, 16.0);
                    essUtil = 0.74 + 0.06 * blockDeficit;
                } else if (needsStoneSword) {
                    essUtil = 0.72;
                } else if (needsBulkBlocks) {
                    double bulkNeed = invLinearCurve(blocks, 16.0, 64.0);
                    essUtil = 0.65 + 0.08 * bulkNeed;
                } else if (needsHardBedTools) {
                    essUtil = 0.67;
                } else if (needsShears) {
                    essUtil = 0.64;
                }
            }
            scores[Maneuver.SHOP_BUY_ESSENTIALS.ordinal()] = clamp01(essUtil);
        }

        if (canShopUpg) {
            double upgUtil = 0.02;
            if (wantUpgradeVisit) {
                upgUtil = 0.70;
                if (needTrapOrUpgradeDia && blocks >= 16) upgUtil = Math.max(upgUtil, 0.80);
                if (affordEmGear) upgUtil = Math.max(upgUtil, 0.79);
                if (affordIronArmor || affordChainArmor) upgUtil = Math.max(upgUtil, 0.77);
                if (affordIronSword) upgUtil = Math.max(upgUtil, 0.75);
            }
            scores[Maneuver.SHOP_BUY_COMBAT_UPGRADES.ordinal()] = clamp01(upgUtil);
        }

        // 4. Proactive Chest Stashing (strictly AFTER spendable upgrades/gear at base)
        if (isAllowed(mask, Maneuver.STASH_VALUABLES)) {
            double stashUtil = 0.02;
            boolean hasUnspentValuables = (diamonds >= 1 && !wantUpgradeVisit)
                    || (emeralds >= 1 && !affordEmGear)
                    || (gold >= 6 && !affordIronArmor && !affordIronSword);
            if (s.homeBedDist() < 26.0 && blocks >= 16 && hasUnspentValuables) {
                double valueScore = linearCurve(carryValue, 1.0, 8.0);
                stashUtil = 0.60 + 0.07 * valueScore;
            }
            scores[Maneuver.STASH_VALUABLES.ordinal()] = clamp01(stashUtil);
        }

        // 5. Fast Complete Bed Defense (smooth completion without mid-defense abort)
        if (isAllowed(mask, Maneuver.DEFEND_HOME_BED)) {
            double defUtil = 0.02;
            boolean finishingDef = s.activeManeuverOrdinal() == Maneuver.DEFEND_HOME_BED.ordinal()
                    && s.homeBedAlive() && s.homeBedDefenseRatio() < 0.90 && (blocks >= 1 || hardBlocks >= 1);
            if (atIsland && s.homeBedAlive()) {
                double uncov = invLinearCurve(s.homeBedDefenseRatio(), 0.0, 0.90);
                if (finishingDef) {
                    defUtil = 0.81 + 0.04 * uncov;
                } else if (s.homeBedDefenseRatio() < 0.75 && (blocks >= 24 || hardBlocks >= 4)) {
                    double stock = linearCurve(blocks + hardBlocks * 2.0, 12.0, 48.0);
                    defUtil = 0.56 + 0.07 * iaus(uncov, stock);
                }
            }
            scores[Maneuver.DEFEND_HOME_BED.ordinal()] = clamp01(defUtil);
        }

        // 6. Home Forge Wait (factoring ForgeTier into ETA; never camp with 48 blocks)
        if (isAllowed(mask, Maneuver.HOLD_HOME_GEN)) {
            double holdUtil = 0.08;
            boolean midRushOrGen = s.activeManeuverOrdinal() == Maneuver.RUSH_NEAREST_BED.ordinal()
                    || s.activeManeuverOrdinal() == Maneuver.RUSH_WEAKEST_BED.ordinal()
                    || s.activeManeuverOrdinal() == Maneuver.FARM_DIAMONDS.ordinal()
                    || s.activeManeuverOrdinal() == Maneuver.FARM_EMERALDS.ordinal();
            if (blocks < 4 && (iron < 4 || !canShopEss || !atIsland)) {
                holdUtil = 0.70;
            } else if (atIsland && blocks < 16 && (iron < 8 || !canShopEss)) {
                holdUtil = 0.68;
            } else if (!midRushOrGen && blocks < 12 && (iron < 4 || !canShopEss || !atIsland)) {
                holdUtil = 0.63;
            } else if (atIsland && !baseUnderAttack && etaSec > 0.0 && etaSec <= 2.8 && blocks < 48) {
                double etaUrgency = invLinearCurve(etaSec, 0.0, 3.0);
                holdUtil = 0.44 + 0.12 * etaUrgency;
            }
            scores[Maneuver.HOLD_HOME_GEN.ordinal()] = clamp01(holdUtil);
        }

        // 7. Objective Map Control: Bed Rushes (using weakestEnemyBedDist!), Diamonds, Emeralds, Fireball Jump
        boolean preferEmeralds = matchState != null && matchState.shouldPrioritizeEmeralds(s.livingEnemyBedCount());
        boolean preferDiamonds = matchState != null && matchState.shouldPrioritizeDiamonds(s.homeBedAlive());
        double respawnOpeningBonus = s.respawningEnemies() > 0 ? 0.05 : 0.0;

        if (s.livingEnemyBedCount() > 0 && blocks >= 12) {
            double nearTravelSec = Math.max(2.0, s.nearestEnemyBedDist() / AVG_BRIDGE_RUN_BPS);
            double weakDist = s.weakestEnemyBedDist() > 0.1 ? s.weakestEnemyBedDist() : s.nearestEnemyBedDist();
            double weakTravelSec = Math.max(2.0, weakDist / AVG_BRIDGE_RUN_BPS);
            double nearBreak = Math.max(0.5, s.nearestEnemyBedBreakSec());
            double weakBreak = Math.max(0.5, s.weakestEnemyBedBreakSec());
            double blockReadiness = linearCurve(blocks, 12.0, 48.0);

            if (isAllowed(mask, Maneuver.RUSH_NEAREST_BED)) {
                double speedScore = invLinearCurve(nearTravelSec + nearBreak, 3.0, 34.0);
                double rushUtil = 0.42 + 0.17 * iaus(speedScore, 0.65 + 0.35 * blockReadiness) + respawnOpeningBonus;
                scores[Maneuver.RUSH_NEAREST_BED.ordinal()] = clamp01(rushUtil);
            }
            if (isAllowed(mask, Maneuver.RUSH_WEAKEST_BED)) {
                double weakSpeed = invLinearCurve(weakTravelSec + weakBreak, 3.0, 34.0);
                double breakAdvantage = (weakBreak + 1.8 < nearBreak) ? 0.05 : -0.02;
                double weakUtil = 0.42 + 0.17 * iaus(weakSpeed, 0.65 + 0.35 * blockReadiness) + breakAdvantage + respawnOpeningBonus;
                scores[Maneuver.RUSH_WEAKEST_BED.ordinal()] = clamp01(weakUtil);
            }
            if (isAllowed(mask, Maneuver.FIREBALL_JUMP)
                    && fireballs > 0 && s.onGround() && s.frontVoidEdgeDist() < 2.2
                    && s.nearestEnemyBedDist() > 26.0 && threatDist > 16.0) {
                scores[Maneuver.FIREBALL_JUMP.ordinal()] = 0.62;
            }
        } else if (s.livingEnemyBedCount() > 0 && blocks >= 4 && isAllowed(mask, Maneuver.RUSH_WEAKEST_BED)) {
            scores[Maneuver.RUSH_WEAKEST_BED.ordinal()] = 0.30;
        }

        if (isAllowed(mask, Maneuver.FARM_DIAMONDS) && blocks >= 8 && s.diamondGenDist() < 52.0 && diamonds < 4) {
            double diaProx = invLinearCurve(s.diamondGenDist(), 4.0, 52.0);
            double diaNeed = (sharpTier == 0 ? 0.85 : 0.62) + (preferDiamonds ? 0.15 : 0.0);
            // Factor hologram per-gen spawn timer when known (boost if spawning within 6s!)
            double timerBonus = 0.0;
            if (s.diamondGenSpawnSec() >= 0.0) {
                timerBonus = s.diamondGenSpawnSec() <= 6.0 ? 0.06 : (s.diamondGenSpawnSec() >= 22.0 ? -0.05 : 0.0);
            }
            double diaUtil = 0.38 + 0.17 * iaus(diaProx, Math.min(1.0, diaNeed)) + timerBonus;
            if (s.nearestEnemyBedDist() > 28.0 && threatDist > 12.0 && (armor < 13.0 || preferDiamonds)) {
                diaUtil += 0.04;
            }
            scores[Maneuver.FARM_DIAMONDS.ordinal()] = clamp01(diaUtil);
        }

        if (isAllowed(mask, Maneuver.FARM_EMERALDS) && blocks >= 8 && s.emeraldGenDist() < 68.0 && emeralds < 6) {
            double emProx = invLinearCurve(s.emeraldGenDist(), 6.0, 68.0);
            double emNeed = (armor < 15.0 || swordTier < 4) ? 0.78 : 0.45;
            if (preferEmeralds) {
                emNeed = Math.min(1.0, emNeed + 0.25);
            }
            double timerBonus = 0.0;
            if (s.emeraldGenSpawnSec() >= 0.0) {
                timerBonus = s.emeraldGenSpawnSec() <= 8.0 ? 0.06 : (s.emeraldGenSpawnSec() >= 35.0 ? -0.04 : 0.0);
            }
            double emUtil = 0.36 + 0.20 * iaus(emProx, emNeed) + timerBonus;
            if (preferEmeralds && threatDist > 12.0) {
                emUtil += 0.10;
            }
            scores[Maneuver.FARM_EMERALDS.ordinal()] = clamp01(emUtil);
        }

        // 8. Smooth Anti-Flicker Hysteresis on unified [0, 1] scale (+0.06 meaningfully prevents hesitation!)
        int activeOrd = s.activeManeuverOrdinal();
        if (!immediateThreat && !baseUnderAttack && !highCarryDisengage
                && activeOrd >= 0 && activeOrd < scores.length
                && isAllowed(mask, Maneuver.fromIndex(activeOrd))
                && scores[activeOrd] > 0.20
                && s.maneuverHoldTicks() < 50) {
            scores[activeOrd] = Math.min(1.0, scores[activeOrd] + HYSTERESIS_BONUS);
        }

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
                Locale.ROOT,
                "u=%.3f,wp=%.2f,carry=%.2f,eta=%.1fs,blk=%d,td=%.1f",
                bestScore, effectiveWinProb, carryValue, etaSec, blocks, threatDist
        );
        return new StrategyEvaluation(chosen, scores, carryValue, etaSec, rationale);
    }

    /**
     * Dave Mark's IAUS compensation factor combining {@code n} considerations in {@code [0, 1]}
     * so multiplying multiple factors doesn't crush composite utility toward zero.
     */
    public static double iaus(double... considerations) {
        int n = considerations.length;
        if (n == 0) {
            return 0.0;
        }
        double prod = 1.0;
        for (int i = 0; i < n; i++) {
            double c = clamp01(considerations[i]);
            if (c <= 0.0) {
                return 0.0;
            }
            prod *= c;
        }
        if (n == 1) {
            return prod;
        }
        double mod = 1.0 - (1.0 / n);
        double makeUp = (1.0 - prod) * mod;
        return clamp01(prod + (makeUp * prod));
    }

    private static double linearCurve(double x, double lo, double hi) {
        if (hi <= lo) return x >= hi ? 1.0 : 0.0;
        return clamp01((x - lo) / (hi - lo));
    }

    private static double invLinearCurve(double x, double lo, double hi) {
        return 1.0 - linearCurve(x, lo, hi);
    }

    private static double clamp01(double v) {
        if (!Double.isFinite(v)) return 0.0;
        return Math.max(0.0, Math.min(1.0, v));
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
        double u = eval.utilityScores()[eval.bestManeuver().ordinal()];
        return new BrainDecision(eval.bestManeuver(), sliders, combatMode, (float) (u * 2.0 - 1.0), 1.0f);
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
     * Estimates seconds until the next meaningful gear/block forge breakpoint (default Forge Tier 0).
     */
    public static double estimateNextBreakpointEtaSec(InventorySummary inv) {
        return estimateNextBreakpointEtaSec(inv, 0);
    }

    /**
     * Estimates seconds until the next meaningful gear/block forge breakpoint, scaling home forge
     * generation speed accurately by {@code forgeTier} (0=1.0x, 1=1.5x, 2=2.0x, 3=2.5x, 4=3.0x).
     */
    public static double estimateNextBreakpointEtaSec(InventorySummary inv, int forgeTier) {
        if (inv == null) {
            return 0.0;
        }
        double forgeMult = 1.0 + Math.max(0, Math.min(4, forgeTier)) * 0.50;
        double ironRate = BASE_IRON_GEN_RATE_PER_SEC * forgeMult;
        double goldRate = BASE_GOLD_GEN_RATE_PER_SEC * forgeMult;

        double best = 99.0;
        if (inv.bridgeBlockCount() < 16 && inv.iron() < 4) {
            best = Math.min(best, (4 - inv.iron()) / ironRate);
        }
        if (inv.swordTier() < 2 && inv.iron() < 10 && inv.gold() < 7) {
            best = Math.min(best, (10 - inv.iron()) / ironRate);
        }
        if (inv.armorPoints() < 9.0 && inv.iron() >= 34 && inv.iron() < 40) {
            best = Math.min(best, (40 - inv.iron()) / ironRate);
        }
        if (inv.swordTier() < 3 && inv.gold() >= 5 && inv.gold() < 7) {
            best = Math.min(best, (7 - inv.gold()) / goldRate);
        }
        if (inv.armorPoints() < 12.0 && inv.gold() >= 10 && inv.gold() < 12) {
            best = Math.min(best, (12 - inv.gold()) / goldRate);
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
        for (int i = 0; i < mask.length; i++) {
            if (mask[i]) {
                return Maneuver.fromIndex(i);
            }
        }
        return Maneuver.HOLD_HOME_GEN;
    }
}
