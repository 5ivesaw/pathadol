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
 * First-principles continuous {@code EV / ETA} Utility AI macro decision engine.
 *
 * <p>Replaces brittle binary switches ({@code savingIronForChain}, {@code blocks >= 32},
 * {@code activelyOutOnBridgeOrObjective}, {@code maneuverHoldTicks < 50}) with physically grounded
 * expected-value-per-second rates mapped smoothly onto {@code [0.0, 1.0]} via {@code R / (R + 1)}:</p>
 * <ul>
 *   <li>Path-aware reachability ({@code blocksNeeded*}, {@code walkDist*}) recognizing existing
 *       straight, diagonal, curved, and L-shaped bridges + jumpable &le;3-block gaps first.</li>
 *   <li>Wool crossing priority tied directly to unlocking {@code G_nextObjective} so saving iron
 *       for Chainmail never leaves the bot short of crossing blocks.</li>
 *   <li>Exact walk-home vs. void-recall ETA + gear-loss comparison.</li>
 *   <li>Continuous progress-weighted commitment hysteresis without arbitrary tick cliffs.</li>
 * </ul>
 */
public final class DeterministicStrategyModel {

    private static final double NEG_INF = -1.0;
    private static final double SPRINT_BPS = 5.2;
    private static final double BRIDGE_BPS = 2.4;
    private static final double SHOP_INTERACT_SEC = 2.5;
    private static final double RESPAWN_TIMER_SEC = 5.0;
    private static final double BASE_IRON_GEN_RATE_PER_SEC = 1.45;
    private static final double BASE_GOLD_GEN_RATE_PER_SEC = 0.28;

    private DeterministicStrategyModel() {}

    public record StrategyEvaluation(
            Maneuver bestManeuver,
            double[] utilityScores,
            double carryRiskValue,
            double nextBreakpointEtaSec,
            String rationale
    ) {}

    /**
     * Evaluates every legal {@link Maneuver} via continuous first-principles {@code EV / ETA} math
     * mapped smoothly to {@code [0, 1]} and returns the highest-utility legal maneuver.
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
        int protTier = matchState != null ? matchState.getProtectionTier() : s.protectionTier();
        boolean trapPopped = s.trapTriggeredRecently()
                || (matchState != null && matchState.wasTrapTriggeredRecently(System.currentTimeMillis(), 12_000L));
        boolean baseUnderAttack = (s.hasEnemyNearHomeBed() || trapPopped) && s.homeBedAlive();

        boolean hasChain = armor >= BedwarsTacticalHelper.BEDWARS_ARMOR_CHAIN_MIN;
        boolean hasIronArmor = armor >= BedwarsTacticalHelper.BEDWARS_ARMOR_IRON_MIN;
        boolean hasDiamondArmor = armor >= BedwarsTacticalHelper.BEDWARS_ARMOR_DIAMOND_MIN;

        // 0. Path-aware objective routes (uses live connected-bridge BFS when available; smooth fallback otherwise)
        int blocksHome = s.blocksNeededHome() >= 0 ? s.blocksNeededHome() : 0;
        double walkHome = s.walkDistHome() >= 0.0 ? s.walkDistHome() : s.homeBedDist();
        double etaHome = walkHome / SPRINT_BPS + blocksHome / BRIDGE_BPS;
        double feasHome = computeBlockFeasibility(blocks, blocksHome);

        int blocksNearBed = s.blocksNeededNearestBed() >= 0
                ? s.blocksNeededNearestBed()
                : fallbackBlocksNeeded(s.nearestEnemyBedDist());
        double walkNearBed = s.walkDistNearestBed() >= 0.0
                ? s.walkDistNearestBed()
                : Math.max(0.0, s.nearestEnemyBedDist() - blocksNearBed);
        double nearBreakSec = Math.max(0.5, s.nearestEnemyBedBreakSec());
        double etaNearBed = walkNearBed / SPRINT_BPS + blocksNearBed / BRIDGE_BPS + nearBreakSec;
        double feasNearBed = computeBlockFeasibility(blocks, blocksNearBed);

        double weakEuclid = s.weakestEnemyBedDist() > 0.1 ? s.weakestEnemyBedDist() : s.nearestEnemyBedDist();
        int blocksWeakBed = s.blocksNeededWeakestBed() >= 0
                ? s.blocksNeededWeakestBed()
                : fallbackBlocksNeeded(weakEuclid);
        double walkWeakBed = s.walkDistWeakestBed() >= 0.0
                ? s.walkDistWeakestBed()
                : Math.max(0.0, weakEuclid - blocksWeakBed);
        double weakBreakSec = Math.max(0.5, s.weakestEnemyBedBreakSec());
        double etaWeakBed = walkWeakBed / SPRINT_BPS + blocksWeakBed / BRIDGE_BPS + weakBreakSec;
        double feasWeakBed = computeBlockFeasibility(blocks, blocksWeakBed);

        int blocksDia = s.blocksNeededDiamond() >= 0
                ? s.blocksNeededDiamond()
                : fallbackBlocksNeeded(s.diamondGenDist());
        double walkDia = s.walkDistDiamond() >= 0.0
                ? s.walkDistDiamond()
                : Math.max(0.0, s.diamondGenDist() - blocksDia);
        double travelDiaSec = walkDia / SPRINT_BPS + blocksDia / BRIDGE_BPS;
        double diaWaitSec = (s.diamondGenSpawnSec() >= 0.0)
                ? Math.max(0.0, Math.min(18.0, s.diamondGenSpawnSec() - travelDiaSec))
                : 0.0;
        double etaDia = Math.max(1.5, travelDiaSec + diaWaitSec);
        double feasDia = computeBlockFeasibility(blocks, blocksDia);

        int blocksEm = s.blocksNeededEmerald() >= 0
                ? s.blocksNeededEmerald()
                : fallbackBlocksNeeded(s.emeraldGenDist());
        double walkEm = s.walkDistEmerald() >= 0.0
                ? s.walkDistEmerald()
                : Math.max(0.0, s.emeraldGenDist() - blocksEm);
        double travelEmSec = walkEm / SPRINT_BPS + blocksEm / BRIDGE_BPS;
        double emWaitSec = (s.emeraldGenSpawnSec() >= 0.0)
                ? Math.max(0.0, Math.min(22.0, s.emeraldGenSpawnSec() - travelEmSec))
                : 0.0;
        double etaEm = Math.max(2.0, travelEmSec + emWaitSec);
        double feasEm = computeBlockFeasibility(blocks, blocksEm);

        // Next-objective crossing gap G_nextObjective: directly drives wool buy feasibility & priority
        int gapNextObjective = computeNextObjectiveCrossingGap(
                s.livingEnemyBedCount(), blocksNearBed, blocksWeakBed,
                s.diamondGenDist(), blocksDia, s.emeraldGenDist(), blocksEm
        );
        int defWoolNeed = (s.homeBedAlive() && s.homeBedDefenseRatio() < 0.85)
                ? (int) Math.ceil((1.0 - s.homeBedDefenseRatio()) * 8.0)
                : 0;
        int hardCrossingNeed = gapNextObjective;
        int safeWoolTarget = gapNextObjective > 0
                ? Math.min(64, Math.max(16, gapNextObjective + 8) + defWoolNeed)
                : Math.min(32, 16 + defWoolNeed);

        double carryValue = computeCarryValue(inv, s.homeBedAlive(), s.health());
        double etaSec = estimateNextBreakpointEtaSec(inv, forgeTier);

        boolean bridgeThreat = RuleTeacherPolicy.isBridgeConfrontation(s)
                || (threatDist <= 14.5 && (s.frontVoidEdgeDist() < 5.0 || s.voidSurroundFraction() >= 0.20));
        boolean homeIntruder = baseUnderAttack && s.homeBedDist() <= 24.0 && threatDist <= 22.0;
        boolean immediateThreat = threatDist <= 9.5 || bridgeThreat || homeIntruder
                || (s.hurtTime() > 0 && threatDist <= 16.0);

        double fightWinProb = s.fightWinProb();
        FightEvaluation carryFightEval = BedwarsTacticalHelper.evaluateFightWinProbability(
                s.health() + s.absorption(),
                armor + protTier * 1.6,
                Math.max(1, swordTier),
                sharpTier,
                gapples,
                s.voidSurroundFraction(),
                20.0,
                armor,
                Math.max(1, swordTier),
                0,
                0.25,
                diamonds,
                emeralds,
                gold,
                s.homeBedAlive()
        );
        double effectiveWinProb = (s.threats() != null && !s.threats().isEmpty() && s.threats().get(0).present())
                ? fightWinProb
                : carryFightEval.winProbability();
        boolean canRetreatHome = s.homeBedDist() > 14.0 && isAllowed(mask, Maneuver.RETREAT_HOME);
        boolean highCarryDisengage = !homeIntruder
                && canRetreatHome
                && threatDist > 3.8 && threatDist <= 20.0
                && ((emeralds >= 4 && effectiveWinProb < 0.78)
                    || (carryFightEval.shouldDisengageForLoot() && effectiveWinProb < 0.65));

        // 1. Tactical Void Recall vs. Walk-Home ETA + Gear-Loss Physics
        double voidRecallEtaSec = estimateVoidRecallEtaSec(s.frontVoidEdgeDist(), s.voidClearanceBlocks());
        double walkHomeEffectiveEta = (blocks >= blocksHome) ? Math.max(1.5, etaHome) : 60.0;
        double recallGearLossSec = estimateVoidGearLossSec(inv);

        if (isAllowed(mask, Maneuver.TACTICAL_VOID_RECALL)) {
            double rRecall = 0.01;
            boolean strandedDisconnected = s.homeBedAlive()
                    && blocksHome > 0 && blocks < blocksHome
                    && (s.livingEnemyBedCount() == 0 || (blocksNearBed > 0 && blocks < blocksNearBed));
            if (baseUnderAttack && s.homeBedAlive()) {
                double netTimeSaved = walkHomeEffectiveEta - voidRecallEtaSec - recallGearLossSec;
                if (netTimeSaved > 0.6) {
                    // Bed-save emergency value divided by actual void-recall arrival ETA
                    double bedSaveEv = 112.0 + 18.0 * clamp01(netTimeSaved / 4.0);
                    rRecall = bedSaveEv / Math.max(3.5, voidRecallEtaSec);
                }
            } else if (strandedDisconnected) {
                // Zero-block stranded-island recovery: broken bridge home & cannot reach any bed -> clean recall to forge!
                rRecall = 9.5 / Math.max(4.0, voidRecallEtaSec);
            }
            scores[Maneuver.TACTICAL_VOID_RECALL.ordinal()] = rateToUtility(rRecall);
        }

        // 2. Combat & Defensive Retreat EV / ETA
        boolean bedlessEnemyNearby = s.nearestBedlessEnemyDist() <= 32.0 && threatDist <= 32.0;
        if (isAllowed(mask, Maneuver.ENGAGE_THREAT_COMBAT)) {
            double rCombat;
            if (highCarryDisengage && !bridgeThreat) {
                // Risking rich emerald/diamond haul on an unfavorable/coinflip duel has low EV/s vs banking home
                rCombat = 0.35 * effectiveWinProb;
            } else if (immediateThreat) {
                double proxFactor = 1.0 / (0.65 + threatDist / 10.0);
                double baseThreatEv = homeIntruder ? 72.0 : (bridgeThreat ? 48.0 : 34.0);
                rCombat = baseThreatEv * proxFactor * (0.72 + 0.28 * effectiveWinProb);
            } else if (bedlessEnemyNearby && s.health() >= 10.0f && effectiveWinProb >= 0.42) {
                // Eliminating a bedless player permanently removes a team from the match
                double fkEta = Math.max(2.0, s.nearestBedlessEnemyDist() / SPRINT_BPS + 2.5);
                rCombat = (26.0 * (0.35 + 0.65 * effectiveWinProb)) / fkEta;
            } else if (s.livingEnemyBedCount() == 0 && threatDist <= 36.0) {
                double huntEta = Math.max(2.0, threatDist / SPRINT_BPS + 2.5);
                rCombat = (24.0 * (0.40 + 0.60 * effectiveWinProb)) / huntEta;
            } else if (threatDist <= 16.0 && s.health() >= 14.0f && swordTier >= 2 && effectiveWinProb >= 0.55) {
                double oppEta = Math.max(2.0, threatDist / SPRINT_BPS + 2.5);
                rCombat = (9.5 * effectiveWinProb) / oppEta;
            } else {
                rCombat = 0.05;
            }
            scores[Maneuver.ENGAGE_THREAT_COMBAT.ordinal()] = rateToUtility(rCombat);
        }

        if (isAllowed(mask, Maneuver.RETREAT_HOME)) {
            double rRetreat = 0.03;
            double retreatEta = Math.max(2.0, walkHomeEffectiveEta);
            if (highCarryDisengage && s.homeBedDist() > 14.0) {
                double saveEv = 52.0 + 7.5 * carryValue;
                rRetreat = Math.max(rRetreat, (saveEv * Math.max(0.25, feasHome)) / Math.max(2.5, retreatEta * 0.55));
            }
            if (immediateThreat && s.health() < 7.5f && threatDist > 5.0
                    && s.homeBedDist() > 14.0 && s.homeBedDist() < 38.0
                    && !homeIntruder && gapples == 0) {
                double lowHpMult = 1.0 + invLinearCurve(s.health(), 2.0, 8.0);
                rRetreat = Math.max(rRetreat, (65.0 * lowHpMult * Math.max(0.25, feasHome)) / retreatEta);
            } else if (baseUnderAttack && s.homeBedDist() > 22.0) {
                // Competes directly on arrival ETA against TACTICAL_VOID_RECALL!
                rRetreat = Math.max(rRetreat, (102.0 * Math.max(0.20, feasHome)) / retreatEta);
            } else if (carryValue >= 4.2 && s.homeBedDist() > 18.0 && s.homeBedDist() < 72.0 && threatDist > 8.0) {
                double bankEv = (carryValue - 2.4) * 2.6 * feasHome;
                rRetreat = Math.max(rRetreat, bankEv / retreatEta);
            }
            scores[Maneuver.RETREAT_HOME.ordinal()] = rateToUtility(rRetreat);
        }

        // 3. Base Shopping: Marginal Value Unlocked / Shop Round-Trip ETA
        boolean canShopEss = isAllowed(mask, Maneuver.SHOP_BUY_ESSENTIALS);
        boolean canShopUpg = isAllowed(mask, Maneuver.SHOP_BUY_COMBAT_UPGRADES);
        boolean alreadyAtShop = s.nearHomeShopOrForge() || walkHome <= 10.5;
        boolean alreadyShoppingEss = s.activeManeuverOrdinal() == Maneuver.SHOP_BUY_ESSENTIALS.ordinal() && alreadyAtShop;
        boolean alreadyShoppingUpg = s.activeManeuverOrdinal() == Maneuver.SHOP_BUY_COMBAT_UPGRADES.ordinal() && alreadyAtShop;

        // Going back to base mid-bridge incurs full round-trip travel + lost bridge progress!
        double shopEtaBase = alreadyAtShop
                ? SHOP_INTERACT_SEC
                : SHOP_INTERACT_SEC + etaHome * 1.90 + (s.voidSurroundFraction() >= 0.25 ? 2.5 : 0.0);
        double shopFeas = (blocks >= blocksHome) ? 1.0 : 0.05;

        int missingCrossingBlocks = Math.max(0, hardCrossingNeed - blocks);
        int woolIronToCross = missingCrossingBlocks > 0
                ? 4 * ((missingCrossingBlocks + 15) / 16)
                : 0;
        boolean ironCloseToChain = !hasChain && iron >= 30 && iron < 40 && blocks >= Math.max(16, hardCrossingNeed);

        boolean needTrapOrUpgradeDia = diamonds >= 2
                || (diamonds == 1 && (activeTraps == 0 || !hasHealPool));
        boolean affordChainArmor = iron >= 40 && !hasChain;
        boolean affordIronArmor = gold >= 12 && !hasIronArmor;
        boolean savingGoldForIronArmor = !hasIronArmor && gold >= 8 && gold < 12 && swordTier >= 2
                && s.homeBedAlive() && threatDist > 22.0;
        boolean affordIronSword = gold >= 7 && swordTier < 3 && !savingGoldForIronArmor;
        boolean affordEmGear = emeralds >= 4;
        boolean affordGapple = gold >= 3 && gapples == 0
                && (hasIronArmor || !s.homeBedAlive() || threatDist < 20.0);

        if (canShopEss) {
            double essEta = alreadyShoppingEss ? 1.6 : shopEtaBase;
            double woolEv = 0.0;
            if (iron >= 4) {
                int criticalFloor = Math.max(hardCrossingNeed, (alreadyAtShop && (gapNextObjective > 0 || defWoolNeed > 0)) ? 16 : 0);
                if (blocks < criticalFloor) {
                    // Cannot cross G_nextObjective (or lack base starter stack): buying wool unlocks entire objective EV!
                    double deficitFrac = 1.0 - (blocks / (double) Math.max(16, criticalFloor));
                    woolEv = 9.0 + 4.0 * clamp01(deficitFrac);
                } else if (blocks < safeWoolTarget && (alreadyAtShop || blocks < 16)) {
                    // Comfort/clutch/defense buffer above hard crossing need (never pulls bot back from mid-bridge!)
                    double bufFrac = (safeWoolTarget - blocks) / (double) Math.max(8, safeWoolTarget - criticalFloor);
                    woolEv = (alreadyShoppingEss ? 5.8 : 3.2) * clamp01(bufFrac);
                }
            }

            double stoneSwordEv = 0.0;
            if (swordTier < 2 && iron >= 10 && gold < 7
                    && !ironCloseToChain && (iron - 10 >= woolIronToCross)) {
                stoneSwordEv = 5.0;
            }

            double shearsEv = 0.0;
            if (shears == 0 && iron >= 20
                    && !ironCloseToChain && (iron - 20 >= woolIronToCross)) {
                shearsEv = 5.8;
            }

            double toolEv = 0.0;
            if (inv != null && iron >= 10 && !ironCloseToChain && (iron - 10 >= woolIronToCross)
                    && blocks >= Math.max(16, hardCrossingNeed)) {
                boolean needHardTools = s.livingEnemyBedCount() > 0 && s.weakestEnemyBedBreakSec() >= 3.2
                        && (inv.pickaxeTier() < 2 || inv.axeTier() < 2);
                boolean needBasicTools = shears > 0 && (inv.pickaxeTier() == 0 || inv.axeTier() == 0);
                if (needHardTools) {
                    toolEv = 4.2 + 2.2 * linearCurve(s.weakestEnemyBedBreakSec(), 3.0, 8.0);
                } else if (needBasicTools) {
                    toolEv = 3.6;
                }
            }

            double bestEssEv = Math.max(Math.max(woolEv, stoneSwordEv), Math.max(shearsEv, toolEv));
            // Small batching bonus when multiple purchases happen in one visit
            double sumOthers = (woolEv + stoneSwordEv + shearsEv + toolEv) - bestEssEv;
            double totalEssEv = bestEssEv + 0.15 * Math.min(bestEssEv, sumOthers);
            double rEss = (totalEssEv > 0.0) ? (totalEssEv * shopFeas) / essEta : 0.02;
            scores[Maneuver.SHOP_BUY_ESSENTIALS.ordinal()] = rateToUtility(rEss);
        }

        boolean wantUpgradeVisit = false;
        if (canShopUpg) {
            double upgEta = alreadyShoppingUpg ? 1.8 : shopEtaBase;
            double upgEv = 0.0;
            if (needTrapOrUpgradeDia && (s.homeBedAlive() || diamonds >= 4)
                    && (blocks >= Math.max(16, hardCrossingNeed) || diamonds >= 2 || !canShopEss)) {
                upgEv = Math.max(upgEv, 8.4 + 1.8 * Math.min(4, diamonds));
            }
            if (affordEmGear) {
                upgEv = Math.max(upgEv, 10.8);
            }
            if (affordIronArmor) {
                upgEv = Math.max(upgEv, 9.2);
            }
            if (affordChainArmor) {
                // Never let Chainmail spend our last iron and strand us unable to cross G_nextObjective!
                double chainVal = (iron - 40 >= woolIronToCross) ? 7.8 : 1.8;
                upgEv = Math.max(upgEv, chainVal);
            }
            if (affordIronSword) {
                upgEv = Math.max(upgEv, 6.8);
            }
            if (affordGapple) {
                upgEv = Math.max(upgEv, 4.8);
            }
            wantUpgradeVisit = upgEv >= 4.0 && alreadyAtShop;
            double rUpg = (upgEv > 0.0) ? (upgEv * shopFeas) / upgEta : 0.02;
            scores[Maneuver.SHOP_BUY_COMBAT_UPGRADES.ordinal()] = rateToUtility(rUpg);
        }

        // 4. Proactive Chest Stashing (strictly for unspendable valuables; scaled by distance to home chest)
        if (isAllowed(mask, Maneuver.STASH_VALUABLES)) {
            double rStash = 0.02;
            boolean unspendableGold = gold >= 6 && !affordIronArmor && !affordIronSword
                    && (hasIronArmor || !canShopUpg);
            boolean hasUnspentDia = diamonds >= 1 && !wantUpgradeVisit;
            boolean hasUnspentEm = emeralds >= 1 && (!affordEmGear || !canShopUpg);
            if ((unspendableGold || hasUnspentDia || hasUnspentEm) && blocks >= Math.min(16, Math.max(1, hardCrossingNeed))) {
                double unspendableVal = (unspendableGold ? gold * 0.25 : 0.0)
                        + (hasUnspentDia ? diamonds * 1.15 : 0.0)
                        + (hasUnspentEm ? emeralds * 1.85 : 0.0);
                double stashEta = alreadyAtShop ? 2.5 : (2.5 + etaHome * 1.6);
                double stashEv = 4.2 + 1.55 * unspendableVal;
                rStash = (stashEv * shopFeas) / stashEta;
            }
            scores[Maneuver.STASH_VALUABLES.ordinal()] = rateToUtility(rStash);
        }

        // 5. Home Bed Defense (Layer 1 shell + Layer 2 reinforcement EV / remaining placement ETA)
        if (isAllowed(mask, Maneuver.DEFEND_HOME_BED)) {
            double rDef = 0.02;
            if (s.homeBedAlive() && (blocks > 0 || hardBlocks > 0)) {
                boolean activeDef = s.activeManeuverOrdinal() == Maneuver.DEFEND_HOME_BED.ordinal();
                double walkToBedEta = (walkHome <= 4.5) ? 0.0 : etaHome;
                if (s.homeBedDefenseRatio() < 0.90) {
                    double uncov = 1.0 - s.homeBedDefenseRatio();
                    int remBlocks = Math.max(1, (int) Math.ceil(uncov * 8.0));
                    double placeEta = Math.max(1.2, remBlocks * 0.42);
                    double defEta = walkToBedEta + placeEta;
                    double stockFactor = activeDef ? 1.0 : linearCurve(blocks + hardBlocks * 2.0, 8.0, 24.0);
                    double defEv = (9.0 + 6.0 * uncov) * stockFactor;
                    rDef = (defEv * shopFeas) / defEta;
                } else if (s.homeBedOuterDefenseRatio() < 0.80
                        && (hardBlocks >= 4 || (blocks >= 32 && hasChain) || (activeDef && s.homeBedOuterDefenseRatio() > 0.05))
                        && threatDist > 20.0) {
                    double outerUncov = 1.0 - s.homeBedOuterDefenseRatio();
                    double defEta = walkToBedEta + Math.max(2.0, outerUncov * 5.0);
                    double defEv = (hardBlocks >= 4 ? 7.2 : 4.8) * outerUncov;
                    rDef = (defEv * shopFeas) / defEta;
                }
            }
            scores[Maneuver.DEFEND_HOME_BED.ordinal()] = rateToUtility(rDef);
        }

        // 6. Home Forge Wait (only when stranded without crossing blocks or right on a brief high-EV breakpoint)
        if (isAllowed(mask, Maneuver.HOLD_HOME_GEN)) {
            double rHold = 0.08;
            double forgeMult = 1.0 + Math.max(0, Math.min(4, forgeTier)) * 0.50;
            double ironRate = BASE_IRON_GEN_RATE_PER_SEC * forgeMult;
            int minViableBlocks = Math.max(4, Math.min(16, hardCrossingNeed));

            if (blocks < minViableBlocks && (iron < 4 || !canShopEss || !alreadyAtShop)) {
                // Must gather at least 4 iron to unlock wool crossing (uses walkHomeEffectiveEta so broken bridges penalize!)
                double waitSec = Math.max(0.8, Math.max(0, 4 - iron) / ironRate);
                double holdEta = waitSec + (alreadyAtShop ? 0.0 : walkHomeEffectiveEta);
                rHold = 6.2 / Math.max(1.2, holdEta);
            } else if (alreadyAtShop && !baseUnderAttack && blocks < hardCrossingNeed && iron < 4) {
                double waitSec = Math.max(0.8, (4 - iron) / ironRate);
                rHold = 6.8 / (waitSec + 1.5);
            } else if (alreadyAtShop && !baseUnderAttack && etaSec > 0.0 && etaSec <= 3.5 && blocks < 48) {
                // Brief forge wait only when the imminent breakpoint's EV/s beats leaving right now
                double bpEv = (blocks < hardCrossingNeed) ? 6.5 : 3.2;
                rHold = bpEv / (etaSec + SHOP_INTERACT_SEC);
            }
            scores[Maneuver.HOLD_HOME_GEN.ordinal()] = rateToUtility(rHold);
        }

        // 7. Objective Map Control: Bed Rushes, Diamond Snowball, Mid Emeralds (continuous EV / ETA * F_blocks)
        boolean preferEmeralds = matchState != null && matchState.shouldPrioritizeEmeralds(s.livingEnemyBedCount());
        boolean preferDiamonds = matchState != null && matchState.shouldPrioritizeDiamonds(s.homeBedAlive());
        double baseBedEv = 14.5
                + (s.respawningEnemies() > 0 ? 3.5 : 0.0)
                + (hasChain ? 1.2 : 0.0)
                + (shears > 0 ? 1.4 : 0.0)
                + (sharpTier > 0 ? 1.4 : 0.0)
                + 1.4 * invLinearCurve(s.livingEnemyBedCount(), 1.0, 6.0);

        if (s.livingEnemyBedCount() > 0) {
            if (isAllowed(mask, Maneuver.RUSH_NEAREST_BED)) {
                double rNear = (baseBedEv * feasNearBed) / Math.max(1.2, etaNearBed);
                scores[Maneuver.RUSH_NEAREST_BED.ordinal()] = rateToUtility(rNear);
            }
            if (isAllowed(mask, Maneuver.RUSH_WEAKEST_BED)) {
                double weakBonus = (weakBreakSec + 1.5 < nearBreakSec) ? 2.0 : 0.0;
                double rWeak = ((baseBedEv + weakBonus) * feasWeakBed) / Math.max(1.2, etaWeakBed);
                scores[Maneuver.RUSH_WEAKEST_BED.ordinal()] = rateToUtility(rWeak);
            }
            if (isAllowed(mask, Maneuver.FIREBALL_JUMP)
                    && fireballs > 0 && s.onGround() && s.frontVoidEdgeDist() < 2.2
                    && blocksNearBed >= 12 && threatDist > 16.0) {
                scores[Maneuver.FIREBALL_JUMP.ordinal()] = rateToUtility(1.8);
            }
        }

        boolean missingEmGear = !hasDiamondArmor || swordTier < 4;
        if (isAllowed(mask, Maneuver.FARM_DIAMONDS) && s.diamondGenDist() < 56.0 && diamonds < 4) {
            boolean missingCoreUpgrades = sharpTier == 0 || (s.homeBedAlive() && activeTraps == 0) || protTier < 2;
            boolean diaPriorityBonus = preferDiamonds && !(s.livingEnemyBedCount() == 0 && missingEmGear);
            double diaEv = (sharpTier == 0 ? 11.5 : (missingCoreUpgrades ? 8.5 : 5.5))
                    + (diaPriorityBonus ? 2.6 : 0.0);
            double rDia = (diaEv * feasDia) / etaDia;
            scores[Maneuver.FARM_DIAMONDS.ordinal()] = rateToUtility(rDia);
        }

        if (isAllowed(mask, Maneuver.FARM_EMERALDS) && s.emeraldGenDist() < 72.0 && emeralds < 6) {
            double lateGameEmBonus = (preferEmeralds || s.emeraldTier() >= 2 || s.livingEnemyBedCount() <= 2)
                    ? ((missingEmGear && (preferEmeralds || s.livingEnemyBedCount() == 0)) ? 9.5 : 4.4)
                    : 0.0;
            double emEv = (missingEmGear ? 12.2 : 6.8)
                    + lateGameEmBonus
                    + ((hasChain && sharpTier >= 1 && threatDist > 12.0) ? 2.2 : 0.0);
            double rEm = (emEv * feasEm) / etaEm;
            scores[Maneuver.FARM_EMERALDS.ordinal()] = rateToUtility(rEm);
        }

        // 8. Progress-Weighted Commitment Hysteresis (NO arbitrary 50-tick cliff!)
        int activeOrd = s.activeManeuverOrdinal();
        if (!immediateThreat && !baseUnderAttack && !highCarryDisengage
                && activeOrd >= 0 && activeOrd < scores.length
                && isAllowed(mask, Maneuver.fromIndex(activeOrd))
                && scores[activeOrd] > 0.15) {
            double commitBonus = computeCommitmentBonus(
                    Maneuver.fromIndex(activeOrd), s,
                    feasNearBed, feasWeakBed, feasDia, feasEm, alreadyAtShop
            );
            scores[activeOrd] = Math.min(1.0, scores[activeOrd] + commitBonus);
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
                "u=%.3f,wp=%.2f,carry=%.2f,eta=%.1fs,blk=%d,gap=%d,td=%.1f",
                bestScore, effectiveWinProb, carryValue, etaSec, blocks, gapNextObjective, threatDist
        );
        return new StrategyEvaluation(chosen, scores, carryValue, etaSec, rationale);
    }

    /**
     * Computes the live crossing wool requirement for {@link baritone.bedwars.BedwarsShopPlanner}
     * so shop item scoring shares the exact same {@code G_nextObjective} crossing target as the macro brain.
     */
    public static int computeRequiredCrossingWoolTarget(ObsSnapshot s) {
        if (s == null) {
            return -1;
        }
        int blocksNear = s.blocksNeededNearestBed() >= 0
                ? s.blocksNeededNearestBed()
                : fallbackBlocksNeeded(s.nearestEnemyBedDist());
        double weakDist = s.weakestEnemyBedDist() > 0.1 ? s.weakestEnemyBedDist() : s.nearestEnemyBedDist();
        int blocksWeak = s.blocksNeededWeakestBed() >= 0
                ? s.blocksNeededWeakestBed()
                : fallbackBlocksNeeded(weakDist);
        int blocksDia = s.blocksNeededDiamond() >= 0
                ? s.blocksNeededDiamond()
                : fallbackBlocksNeeded(s.diamondGenDist());
        int blocksEm = s.blocksNeededEmerald() >= 0
                ? s.blocksNeededEmerald()
                : fallbackBlocksNeeded(s.emeraldGenDist());
        return computeNextObjectiveCrossingGap(
                s.livingEnemyBedCount(), blocksNear, blocksWeak,
                s.diamondGenDist(), blocksDia, s.emeraldGenDist(), blocksEm
        );
    }

    static int computeNextObjectiveCrossingGap(
            int livingEnemyBeds,
            int blocksNearBed,
            int blocksWeakBed,
            double diamondDist,
            int blocksDia,
            double emeraldDist,
            int blocksEm
    ) {
        if (livingEnemyBeds > 0) {
            int bedGap = Math.min(Math.max(0, blocksNearBed), Math.max(0, blocksWeakBed));
            if (bedGap > 0) {
                return bedGap;
            }
            if (diamondDist < 56.0 && blocksDia > 0) {
                return blocksDia;
            }
            return 0;
        }
        if (emeraldDist < 72.0 && blocksEm > 0) {
            return blocksEm;
        }
        if (diamondDist < 56.0 && blocksDia > 0) {
            return blocksDia;
        }
        return 0;
    }

    /**
     * Continuous block-feasibility factor {@code F_blocks(have, need)} in {@code [0, 1]}:
     * {@code 1.0} when the path is already connected/jumpable ({@code need == 0}) or stocked with
     * clutch margin; drops sharply toward zero when {@code have < need} so bridging cannot begin short.
     */
    public static double computeBlockFeasibility(int haveBlocks, int blocksNeeded) {
        if (blocksNeeded <= 0) {
            return 1.0;
        }
        if (haveBlocks <= 0) {
            return 0.0;
        }
        if (haveBlocks >= blocksNeeded + 4) {
            return 1.0;
        }
        if (haveBlocks >= blocksNeeded) {
            return 0.86 + 0.14 * ((haveBlocks - blocksNeeded) / 4.0);
        }
        double ratio = haveBlocks / (double) blocksNeeded;
        if (ratio < 0.70) {
            return 0.03 * ratio;
        }
        double t = (ratio - 0.70) / 0.30;
        return 0.021 + 0.28 * (t * t);
    }

    private static int fallbackBlocksNeeded(double euclideanDist) {
        if (!Double.isFinite(euclideanDist) || euclideanDist <= 14.0) {
            return 0;
        }
        return Math.max(0, (int) Math.round(euclideanDist - 14.0));
    }

    private static double estimateVoidRecallEtaSec(double frontVoidEdgeDist, double voidClearanceBlocks) {
        double edgeRunSec = Math.min(Math.max(0.0, frontVoidEdgeDist), 4.5) / SPRINT_BPS;
        double h = Math.max(6.0, Math.min(48.0, voidClearanceBlocks));
        // Minecraft gravity (~0.08 b/t^2 with 0.98 air drag) -> effective acceleration ~26 b/s^2
        double fallSec = Math.max(0.6, Math.min(2.8, Math.sqrt(2.0 * h / 26.0)));
        return edgeRunSec + fallSec + RESPAWN_TIMER_SEC;
    }

    private static double estimateVoidGearLossSec(InventorySummary inv) {
        if (inv == null) {
            return 0.0;
        }
        double swordLoss = inv.swordTier() >= 2 ? 0.65 * (inv.swordTier() - 1) : 0.0;
        double toolLoss = (inv.pickaxeTier() >= 2 ? 0.45 : 0.0) + (inv.axeTier() >= 2 ? 0.45 : 0.0);
        double carryLoss = inv.iron() * 0.04 + inv.gold() * 0.35 + Math.min(inv.bridgeBlockCount(), 64) * 0.025;
        return swordLoss + toolLoss + carryLoss;
    }

    private static double computeCommitmentBonus(
            Maneuver active,
            ObsSnapshot s,
            double feasNearBed,
            double feasWeakBed,
            double feasDia,
            double feasEm,
            boolean alreadyAtShop
    ) {
        return switch (active) {
            case RUSH_NEAREST_BED -> (0.04 + 0.05 * linearCurve(s.homeBedDist(), 6.0, 24.0)
                    + 0.04 * linearCurve(s.voidSurroundFraction(), 0.05, 0.35)) * feasNearBed;
            case RUSH_WEAKEST_BED -> (0.04 + 0.05 * linearCurve(s.homeBedDist(), 6.0, 24.0)
                    + 0.04 * linearCurve(s.voidSurroundFraction(), 0.05, 0.35)) * feasWeakBed;
            case FARM_DIAMONDS -> (0.04 + 0.05 * linearCurve(s.homeBedDist(), 6.0, 22.0)
                    + 0.04 * linearCurve(s.voidSurroundFraction(), 0.05, 0.35)) * feasDia;
            case FARM_EMERALDS -> (0.04 + 0.05 * linearCurve(s.homeBedDist(), 6.0, 26.0)
                    + 0.04 * linearCurve(s.voidSurroundFraction(), 0.05, 0.35)) * feasEm;
            case DEFEND_HOME_BED -> s.homeBedAlive()
                    ? (0.06 + 0.08 * clamp01(s.homeBedDefenseRatio() / 0.85))
                    : 0.0;
            case SHOP_BUY_ESSENTIALS, SHOP_BUY_COMBAT_UPGRADES -> alreadyAtShop ? 0.04 : 0.0;
            default -> 0.03;
        };
    }

    private static double rateToUtility(double evPerSec) {
        if (!Double.isFinite(evPerSec) || evPerSec <= 0.0) {
            return 0.0;
        }
        return clamp01(evPerSec / (evPerSec + 1.0));
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
        if (inv.shearsTier() == 0 && inv.iron() >= 15 && inv.iron() < 20) {
            best = Math.min(best, (20 - inv.iron()) / ironRate);
        }
        if (inv.armorPoints() < BedwarsTacticalHelper.BEDWARS_ARMOR_CHAIN_MIN && inv.iron() >= 31 && inv.iron() < 40) {
            best = Math.min(best, (40 - inv.iron()) / ironRate);
        }
        if (inv.swordTier() < 3 && inv.gold() >= 5 && inv.gold() < 7) {
            best = Math.min(best, (7 - inv.gold()) / goldRate);
        }
        if (inv.armorPoints() < BedwarsTacticalHelper.BEDWARS_ARMOR_IRON_MIN && inv.gold() >= 9 && inv.gold() < 12) {
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
