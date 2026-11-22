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

import baritone.Baritone;
import baritone.bedwars.BedwarsAutoQueueController;
import baritone.bedwars.BedwarsDefensePlanner;
import baritone.bedwars.BedwarsDefensePlanner.DefenseBlockKind;
import baritone.bedwars.BedwarsDefensePlanner.ToolProfile;
import baritone.bedwars.BedwarsMapCore;
import baritone.bedwars.BedwarsMapCore.ArenaBounds;
import baritone.bedwars.BedwarsMapCore.BedPOI;
import baritone.bedwars.BedwarsMapCore.GenPOI;
import baritone.bedwars.BedwarsMapCore.GenType;
import baritone.bedwars.BedwarsMapCore.ShopNPC;
import baritone.bedwars.BedwarsMapCore.SpawnPOI;
import baritone.bedwars.BedwarsMapCore.TeamColor;
import baritone.bedwars.BedwarsMatchState;
import baritone.bedwars.BedwarsShopPlanner;
import baritone.bedwars.BedwarsShopPlanner.EnemyDefenseIntel;
import baritone.bedwars.BedwarsShopPlanner.MenuKind;
import baritone.bedwars.BedwarsShopPlanner.ShopClickDecision;
import baritone.bedwars.BedwarsShopPlanner.ShopPreset;
import baritone.bedwars.BedwarsShopPlanner.ShopSlotInfo;
import baritone.bedwars.BedwarsTacticalHelper;
import baritone.bedwars.BedwarsTacticalHelper.InventorySummary;
import baritone.brain.BrainActionSpace.Maneuver;
import baritone.brain.BrainSchema.ObsSnapshot;
import baritone.brain.PureNnSchema.PureNnAction;
import baritone.brain.PureNnSchema.PureNnSample;
import baritone.human.BridgeController.BridgeSkill;
import baritone.human.BridgePhysicsModel;
import baritone.pathing.movement.movements.MovementParkour;
import baritone.process.CombatPhysicsModel;
import baritone.process.CombatProcess;
import net.minecraft.world.phys.Vec3;
import org.junit.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

/**
 * Unit tests verifying the deterministic engine overhaul:
 * EV Strategy Engine, Predictive Bridge Kinematics, Ballistic/Void-Vector Combat Physics,
 * Pure-NN Egocentric Binary Schema + Mirror Symmetry, and Daemon Thread Shutdown Safety.
 */
public class DeterministicEngineTest {

    @Test
    public void cacheMergePublishesPoisAndPreservesScannedTerrain() {
        ArenaBounds bounds = new ArenaBounds(-16, 60, -16, 48, 10, 48);
        BedwarsMapCore live = new BedwarsMapCore(bounds, 0, 0, 60, 69);
        BedwarsMapCore cached = new BedwarsMapCore(bounds, 0, 0, 60, 69);
        live.setHomeTeam(TeamColor.RED);
        live.markChunkScanned(0, 0);
        cached.setOriginalBlock(1, 63, 1, true, false);
        cached.setOriginalBlock(18, 63, 1, true, false);
        cached.addBed(new BedPOI(TeamColor.RED, 18, 64, 1, 19, 64, 1, false, false));
        GenPOI generator = new GenPOI(GenType.DIAMOND, 24, 64, 4, "Diamond");
        SpawnPOI spawn = new SpawnPOI(TeamColor.RED, 24, 64, 8);
        ShopNPC shop = new ShopNPC("ITEM SHOP", 24, 64, 12);
        cached.addGen(generator);
        cached.addSpawn(spawn);
        cached.addShopNpc(shop);

        assertEquals(1, live.mergeFromCache(cached));
        assertTrue(live.isKnownAirInScannedChunk(1, 63, 1));
        assertTrue(live.isKnownSolidBitset(18, 63, 1));
        assertTrue(live.getBedsSnapshot()[0].isHome());
        assertArrayEquals(new GenPOI[] {generator}, live.getGensSnapshot());
        assertArrayEquals(new SpawnPOI[] {spawn}, live.getSpawnsSnapshot());
        assertArrayEquals(new ShopNPC[] {shop}, live.getShopNpcsSnapshot());
        assertTrue(live.isInNoPlaceZone(24, 64, 12));

        live.setSpecialNonAir(1, 63, 1, true);
        assertFalse(live.isKnownAirInScannedChunk(1, 63, 1));
        assertFalse(live.isKnownSolidBitset(1, 63, 1));
        assertEquals(0, live.mergeFromCache(cached));
        assertEquals(1, live.getBedsSnapshot().length);
    }

    @Test
    public void influenceGridCostsNearbyThreatsMoreAlongTheVoid() {
        BedwarsMapCore core = new BedwarsMapCore(new ArenaBounds(-16, 60, -16, 32, 10, 32), 0, 0, 60, 69);
        for (int x = -4; x <= 0; x++) {
            for (int z = -4; z <= 4; z++) {
                core.setOriginalBlock(x, 63, z, true, false);
            }
        }
        core.rebuildVoidExposureGrid();
        assertEquals(0.0, core.sampleVoidExposure(-2, 0), 0.0);
        assertEquals(1.0, core.sampleVoidExposure(1, 0), 0.0);
        double[] safeDirection = new double[2];
        assertTrue(core.computeVoidSafeDirection(0, 0, safeDirection));
        assertArrayEquals(new double[] {-1, 0}, safeDirection, 1e-6);

        core.updateThreatHeatGrid(new double[] {-1, 64, 0}, new float[] {1.2f}, 1);
        assertEquals(core.sampleThreatHeat(-2, 0), core.sampleThreatHeat(0, 0), 1e-6);
        assertTrue(core.computeInfluenceDangerCost(0, 64, 0) > core.computeInfluenceDangerCost(-2, 64, 0));
        core.setPlayerPlaced(1, 63, 0, true);
        core.updateVoidExposureNear(1, 0);
        assertTrue(core.sampleVoidExposure(1, 0) < 1.0);
        core.updateThreatHeatGrid(null, null, 0);
        assertEquals(0.0, core.computeInfluenceDangerCost(0, 64, 0), 0.0);
    }

    @Test
    public void bedShellUsesMajorityCostAndBlockInUsesActualBedHeight() {
        assertEquals(1, BedwarsTacticalHelper.swordTierFromPath("golden_sword"));
        assertEquals(3, BedwarsTacticalHelper.toolTierFromPath("golden_pickaxe"));
        ToolProfile tools = new ToolProfile(1, 0, 0);
        var shell = BedwarsDefensePlanner.scanBedDefenseShell(0, 64, 0, 1, 64, 0, 3, tools,
                (x, y, z) -> {
                    int distance = Math.min(Math.abs(x), Math.abs(x - 1)) + Math.abs(y - 64) + Math.abs(z);
                    if (distance != 1) return DefenseBlockKind.AIR;
                    return x == 0 && y == 65 && z == 0 ? DefenseBlockKind.WOOL : DefenseBlockKind.OBSIDIAN;
                });
        assertEquals(1, shell.layersDetected());
        assertEquals(Integer.valueOf(1), shell.counts().get(DefenseBlockKind.WOOL));
        assertEquals(8.0 + BedwarsDefensePlanner.estimateLayerTicks(DefenseBlockKind.OBSIDIAN, tools),
                shell.estimatedClearTicks(), 1e-6);

        var plan = BedwarsTacticalHelper.buildSmartBlockInPlan(
                0, 65, 0, 1, 64, 0, 1, 1, -90.0f, (x, y, z) -> y == 64, Set.of());
        assertTrue(plan.stream().anyMatch(p -> p.x() == 0 && p.y() == 65 && p.z() == 1));
        assertFalse(plan.stream().anyMatch(p -> p.x() == 1 && p.y() <= 65 && (p.z() == 0 || p.z() == 1)));
    }

    @Test
    public void matchStateTracksTrapDeduplicationRespawnsAndGeneratorTimers() {
        BedwarsMatchState match = new BedwarsMatchState();
        match.onChatMessage("BedWars ► You are now in team Aqua.", 1000L);
        match.updatePlayerTeam("RedPlayer", TeamColor.RED);
        match.onChatMessage("Ally purchased Alarm Trap", 2000L);
        match.onChatMessage("Ally purchased Mining Fatigue Trap", 3000L);
        match.onChatMessage("Your trap was triggered!", 4000L);
        match.onChatMessage("Intruder detected! Trap triggered!", 4250L);
        assertEquals(1, match.getActiveTrapCount());
        assertTrue(match.wasTrapTriggeredRecently(4500L, 1000L));
        assertFalse(match.wasTrapTriggeredRecently(5001L, 1000L));

        match.onChatMessage("RedPlayer was killed by Ally", 5000L);
        assertTrue(match.isTeamAnyMemberRespawning(TeamColor.RED, 6000L));
        assertEquals(1, match.countRespawningEnemies(6000L));
        assertEquals(4.0, match.getPlayerRespawnRemainingSec("redplayer", 6000L), 0.0);
        assertFalse(match.isPlayerRespawning("RedPlayer", 10000L));
        match.onChatMessage("RedPlayer was killed by Ally FINAL KILL!", 11000L);
        assertEquals(0, match.countRespawningEnemies(11000L));

        match.onGeneratorHologram(4, 4, GenType.DIAMOND, "Diamond Tier II — Spawns in 12 seconds", 2000L);
        match.onGeneratorHologram(22, 22, GenType.DIAMOND, "Spawns in 5 seconds", 2000L);
        assertEquals(2, match.getDiamondTier());
        assertEquals(11.0, match.getClosestGenSpawnRemainingSec(GenType.DIAMOND, 4, 4, 3000L), 1e-6);
        assertEquals(4.0, match.getMinGenSpawnRemainingSec(GenType.DIAMOND, 3000L), 1e-6);
        assertEquals(-1.0, match.getMinGenSpawnRemainingSec(GenType.EMERALD, 3000L), 0.0);
    }

    @Test
    public void evenFightWithSixEmeraldsFavorsRetreatAndForgeReducesWait() {
        var evenFight = BedwarsTacticalHelper.evaluateFightWinProbability(
                20, 9, 2, 0, 0, 0.2, 20, 9, 2, 0, 0.2, 0, 6, 0, true);
        assertEquals(0.5, evenFight.winProbability(), 1e-6);
        assertTrue(evenFight.shouldDisengageForLoot());
        InventorySummary loot = new InventorySummary(0, 0, 0, 6, 32, 0, 32, 2, 0, 0, 0, 9, 0, 0, 0, 0, false, false);
        ObsSnapshot snap = new ObsSnapshot(
                20f, 0f, 20, true, false, false, false, 0.1, 0.0, 0, 16, 32, false, false,
                loot, true, 0.85, 40, 0, 1, 0, 3, 44, 0, 1, 4, 2.5,
                26, 1, 0, 50, 0, 1, List.of(new BrainSchema.ThreatFeature(true, 7, 0, 1, 0.5, 0.7)),
                8, 0.2, 0, 10, false, false, false);
        var evaluation = DeterministicStrategyModel.evaluate(snap, BrainActionSpace.computeValidManeuverMask(snap), null);
        assertEquals(Maneuver.RETREAT_HOME, evaluation.bestManeuver());
        assertEquals(0.34375, DeterministicStrategyModel.iaus(0.5, 0.5), 1e-6);
        assertEquals(0.0, DeterministicStrategyModel.iaus(0.5, 0), 0.0);

        InventorySummary needsWool = new InventorySummary(0, 0, 0, 0, 0, 0, 0, 1, 0, 0, 0, 7, 0, 0, 0, 0, false, false);
        double baseEta = DeterministicStrategyModel.estimateNextBreakpointEtaSec(needsWool);
        assertTrue(baseEta > 0.0);
        assertEquals(baseEta / 3.0, DeterministicStrategyModel.estimateNextBreakpointEtaSec(needsWool, 4), 1e-6);
    }

    @Test
    public void scoutedDefenseUnlocksToolUpgradesTntAndCatalogTabs() {
        InventorySummary inv = new InventorySummary(24, 7, 0, 0, 64, 0, 64, 3, 3, 2, 1, 9, 0, 0, 0, 0, false, false);
        EnemyDefenseIntel obsidian = new EnemyDefenseIntel(false, false, true, false);
        EnemyDefenseIntel wood = new EnemyDefenseIntel(true, false, false, true);
        assertEquals(ShopPreset.TOOLS, BedwarsShopPlanner.recommendPreset(inv, true, 0.9, 30, obsidian));
        ShopSlotInfo pick = new ShopSlotInfo(23, "diamond_pickaxe", "Diamond Pickaxe", "Cost: 6 Gold", 1);
        ShopSlotInfo axe = new ShopSlotInfo(24, "iron_axe", "Iron Axe", "Cost: 3 Gold", 1);
        ShopSlotInfo tnt = new ShopSlotInfo(25, "tnt", "TNT", "Cost: 4 Gold", 1);
        assertEquals(23, BedwarsShopPlanner.chooseNextShopClick(
                List.of(pick), ShopPreset.TOOLS, inv, false, true, 0.9, 30, false, null, obsidian, null).slotIndex());
        assertEquals(24, BedwarsShopPlanner.chooseNextShopClick(
                List.of(axe), ShopPreset.TOOLS, inv, false, true, 0.9, 30, false, null, wood, null).slotIndex());
        assertEquals(25, BedwarsShopPlanner.chooseNextShopClick(
                List.of(tnt), ShopPreset.UTILITY, inv, false, true, 0.9, 30, false, null, wood, null).slotIndex());

        var catalog = new BedwarsShopPlanner.ShopCatalogCache();
        ShopSlotInfo tools = new ShopSlotInfo(3, "stone_pickaxe", "Tools", "Click to open", 1);
        catalog.noteClickedTab(3, "Tools");
        catalog.ingestOpenMenu(MenuKind.ITEM_SHOP, List.of(tools, pick));
        catalog.noteClickedTab(0, "Quick Buy");
        ShopSlotInfo wool = new ShopSlotInfo(20, "white_wool", "Wool", "Cost: 4 Iron", 16);
        var choice = BedwarsShopPlanner.chooseNextShopClick(
                List.of(tools, wool), ShopPreset.TOOLS, inv, false, true, 0.9, 30, false, null, obsidian, catalog);
        assertEquals(3, choice.slotIndex());
        assertTrue(choice.categorySwitch());
        assertTrue(choice.reason().contains("Catalog jump"));
    }

    @Test
    public void autoQueueNormalizesAliasesAndRejectsOtherModes() {
        var controller = new BedwarsAutoQueueController();
        for (String alias : BedwarsAutoQueueController.queueModeAliases()) {
            controller.setEnabled(true, alias);
            assertEquals(BedwarsAutoQueueController.normalizeQueueMode(alias), controller.getPreferredMode());
        }
        assertEquals("solo", BedwarsAutoQueueController.normalizeQueueMode(" 1v1 "));
        assertEquals("solo", BedwarsAutoQueueController.normalizeQueueMode("SOLOS"));
        assertEquals("doubles", BedwarsAutoQueueController.normalizeQueueMode("duos"));
        assertEquals("doubles", BedwarsAutoQueueController.normalizeQueueMode("2s"));
        assertEquals("trios", BedwarsAutoQueueController.normalizeQueueMode("3v3"));
        assertEquals("quads", BedwarsAutoQueueController.normalizeQueueMode("4v4v4v4"));
        assertTrue(BedwarsAutoQueueController.isLobbyQueueNpcLabel("3v3v3v3 CLICK TO PLAY", "trio"));
        assertFalse(BedwarsAutoQueueController.isLobbyQueueNpcLabel("BedWars 4v4v4v4 PLAY NOW", "trios"));
        assertTrue(BedwarsAutoQueueController.isLobbyQueueNpcLabel("Bed Wars PLAY NOW", "1s"));
        assertFalse(BedwarsAutoQueueController.isLobbyQueueNpcLabel("Solo Upgrade Shop", "solo"));
        assertEquals(8, BedwarsAutoQueueController.findAutoQueueConfirmSlot("Choose BedWars Mode", List.of(
                new ShopSlotInfo(7, "lime_wool", "BedWars Quads", "Click to play", 1),
                new ShopSlotInfo(8, "bed", "Duo", "Click to play", 1)), "2v2"));
    }

    @Test
    public void testDeterministicStrategySpendBeforeStashAndAntiCamping() {
        // 1) Carrying 8 gold + 1 diamond + 48 wool at base when upgrade shop is on cooldown -> STASH_VALUABLES
        InventorySummary stashReady = new InventorySummary(2, 8, 1, 0, 48, 0, 48, 3, 0, 0, 0, 11.0, 0, 0, 0, 0, false, false);
        ObsSnapshot snapStash = new ObsSnapshot(
                20f, 0f, 20, true, false, false, false, 0.1, 0.0, 0, 16.0, 32.0, false, false,
                stashReady, true, 0.85, 8.0, 0.0, 1.0, 0.0,
                3, 44.0, 0.0, 1.0, 4.0, 2.5,
                26.0, 1.0, 0.0, 50.0, 0.0, 1.0,
                List.of(), 8.0, 0.0, 0, 10, false, true, false
        );
        boolean[] maskNoUpg = BrainActionSpace.computeValidManeuverMask(snapStash);
        maskNoUpg[Maneuver.SHOP_BUY_COMBAT_UPGRADES.ordinal()] = false;
        DeterministicStrategyModel.StrategyEvaluation evalStash = DeterministicStrategyModel.evaluate(snapStash, maskNoUpg, null);
        assertEquals("Unspendable gold/diamond haul with 48 wool should stash before pushing",
                Maneuver.STASH_VALUABLES, evalStash.bestManeuver());

        // 2) Geared with 48 wool and no imminent breakpoint -> must NOT camp HOLD_HOME_GEN
        InventorySummary readyToPlay = new InventorySummary(6, 1, 0, 0, 48, 0, 48, 2, 0, 0, 0, 7.0, 0, 0, 0, 0, false, false);
        ObsSnapshot snapPlay = new ObsSnapshot(
                20f, 0f, 20, true, false, false, false, 0.1, 0.0, 0, 16.0, 32.0, false, false,
                readyToPlay, true, 0.85, 6.0, 0.0, 1.0, 0.0,
                3, 42.0, 0.0, 1.0, 4.0, 1.5,
                24.0, 1.0, 0.0, 48.0, 0.0, 1.0,
                List.of(), 8.0, 0.0, 0, 10, false, true, false
        );
        boolean[] maskPlay = BrainActionSpace.computeValidManeuverMask(snapPlay);
        maskPlay[Maneuver.SHOP_BUY_ESSENTIALS.ordinal()] = false;
        Maneuver active = DeterministicStrategyModel.evaluate(snapPlay, maskPlay, null).bestManeuver();
        assertNotEquals("Must leave base instead of camping HOLD_HOME_GEN with 48 blocks", Maneuver.HOLD_HOME_GEN, active);

        // 3) Carry value increases when home bed is lost and health is low
        double safeVal = DeterministicStrategyModel.computeCarryValue(stashReady, true, 20.0f);
        double riskyVal = DeterministicStrategyModel.computeCarryValue(stashReady, false, 6.0f);
        assertTrue("Carry risk must scale up without bed and at low HP", riskyVal > safeVal * 1.5);
    }

    @Test
    public void testBridgePhysicsModelNinjaCadenceAndBlockBudget() {
        // Simulate a 30-block flat Ninja bridge using exact ground drag integration
        double pos = 0.0;
        double vel = 0.04;
        int totalTicks = 0;
        int sneakTicks = 0;
        int ultraSlowTicks = 0;
        int blocksPlaced = 0;
        int postPlaceHold = 0;

        while (blocksPlaced < 30 && totalTicks < 600) {
            totalTicks++;
            double localProgress = pos - Math.floor(pos) - 0.5; // [-0.5 .. +0.5]
            double signedProgress = localProgress + 0.5; // 0.0 at back edge, 0.5 at center, 1.0 at far edge -> map to controller [0..0.75]
            double ctrlProgress = pos - blocksPlaced; // 0.0 at src center, 0.5 at cantilever edge
            boolean sneak;
            if (postPlaceHold > 0) {
                sneak = true;
                postPlaceHold--;
            } else {
                sneak = BridgePhysicsModel.shouldEngagePredictiveSneak(
                        BridgeSkill.MEDIUM, ctrlProgress, vel, ctrlProgress >= 0.45, true, totalTicks
                );
            }
            vel = BridgePhysicsModel.stepGroundSpeed(vel, true, sneak);
            pos += vel;
            if (sneak) {
                sneakTicks++;
                if (vel < 0.03) {
                    ultraSlowTicks++;
                }
            }
            assertTrue("Must never overshoot past cantilever edge without placing (ctrlProgress=" + (pos - blocksPlaced) + ")",
                    (pos - blocksPlaced) < 0.80);
            if (sneak && (pos - blocksPlaced) >= 0.48) {
                blocksPlaced++;
                postPlaceHold = 1;
            }
        }
        assertEquals(30, blocksPlaced);
        double sneakRatio = (double) sneakTicks / totalTicks;
        double ultraSlowRatio = sneakTicks > 0 ? (double) ultraSlowTicks / sneakTicks : 0.0;
        assertTrue("Ninja sneak duty cycle should stay brisk and human-like (got " + sneakRatio + ")",
                sneakRatio >= 0.30 && sneakRatio <= 0.72);
        assertTrue("Ultra-slow sneak stalls (<0.03 b/t) must stay minimal (got " + ultraSlowRatio + ")",
                ultraSlowRatio < 0.15);

        // Block budget check prevents mid-air towering out of blocks
        assertFalse(BridgePhysicsModel.hasSufficientBlocksForSpan(12, 16, 4));
        assertTrue(BridgePhysicsModel.hasSufficientBlocksForSpan(28, 16, 4));
    }

    @Test
    public void testCombatPhysicsModelInterceptVoidStrafeAndHumanClickDistribution() {
        double nextDist = CombatPhysicsModel.predictNextTickBoxDist(
                new Vec3(0.0, 65.62, 0.0), new Vec3(0.0, 0.0, 0.25),
                new Vec3(0.0, 64.0, 3.40), new Vec3(0.0, 0.0, -0.15),
                0.3, 1.8
        );
        assertEquals(2.70, nextDist, 0.05);

        // Cliff on forward-left (rays 14..15 = 1.5m), safe ground on right (ray 4 = 8m) -> strafe right (+1)
        double[] vd = new double[16];
        boolean[] safe = new boolean[16];
        Arrays.fill(vd, 8.0);
        Arrays.fill(safe, true);
        vd[14] = 1.5;
        vd[15] = 1.6;
        assertEquals(1, CombatPhysicsModel.computeVoidPressureStrafeSign(vd, safe, -1));

        // Cliff on right (ray 4 = 1.2m), safe on left -> must flip to left (-1)
        vd[4] = 1.2;
        assertEquals(-1, CombatPhysicsModel.computeVoidPressureStrafeSign(vd, safe, 1));

        // Human click spacing distribution: strictly >= 2t, p50 around 3-4t, non-metronomic variance
        Random rng = new Random(777L);
        int[] samples = new int[500];
        for (int i = 0; i < samples.length; i++) {
            samples[i] = CombatPhysicsModel.nextHumanAttackDelayTicks(6.0, true, rng.nextDouble(), rng.nextDouble());
            assertTrue("Strict legit attack delay must stay within [2, 7]t, got " + samples[i],
                    samples[i] >= 2 && samples[i] <= 7);
        }
        Arrays.sort(samples);
        int p50 = samples[samples.length / 2];
        assertTrue("Median delay should be 3-4t, got " + p50, p50 >= 3 && p50 <= 4);
        assertTrue("Distribution must have natural spread, not metronomic lock", samples[450] > samples[50]);
    }

    @Test
    public void testPureNnSchemaEgocentricMuLawBinaryRoundTripAndMirrorSymmetry() {
        // 1) Egocentric transform: facing South (yaw=0 -> +Z) vs facing North (yaw=180 -> -Z)
        double[] egoSouth = new double[2];
        double[] egoNorth = new double[2];
        PureNnSchema.worldToEgocentric(0.0, 5.0, 0.0f, egoSouth);   // 5 blocks ahead facing South
        PureNnSchema.worldToEgocentric(0.0, -5.0, 180.0f, egoNorth); // 5 blocks ahead facing North
        assertEquals(5.0, egoSouth[0], 1e-6);
        assertEquals(0.0, egoSouth[1], 1e-6);
        assertEquals(egoSouth[0], egoNorth[0], 1e-6);
        assertEquals(egoSouth[1], egoNorth[1], 1e-6);

        // 2) Mu-Law aim encoding: exact center zero, high precision near 0, antisymmetric mirror
        assertEquals(12, PureNnSchema.muLawEncode(0.0f));
        assertEquals(0.0f, PureNnSchema.muLawDecode(12), 1e-5f);
        assertTrue("Near-zero bin step must be < 0.7 deg for crisp tracking",
                Math.abs(PureNnSchema.muLawDecode(13) - PureNnSchema.muLawDecode(12)) < 0.7f);
        for (float d : new float[]{0.5f, 2.0f, 8.5f, 22.0f, 35.0f}) {
            assertEquals(PureNnSchema.AIM_BINS - 1 - PureNnSchema.muLawEncode(d), PureNnSchema.muLawEncode(-d));
        }

        // 3) Binary encode/decode round-trip + exact Left-Right mirror involution
        byte[] vox = new byte[PureNnSchema.VOXEL_DIM];
        vox[PureNnSchema.voxelIndex(0, 2, 0)] = PureNnSchema.VOX_PLAYER_PLACED; // far left floor
        vox[PureNnSchema.voxelIndex(1, 3, 4)] = PureNnSchema.VOX_ENEMY_BED;     // far right ahead
        float[] fl = new float[PureNnSchema.FLOAT_OBS_DIM];
        fl[0] = 0.25f;
        fl[2] = 0.18f;  // moving right
        fl[23] = 0.5f;  // fwd-right void ray
        fl[29] = 1.0f;  // fwd-left void ray
        fl[96 + 3] = 0.4f; // enemy right coord
        PureNnAction act = new PureNnAction(
                PureNnSchema.KEY_W | PureNnSchema.KEY_D | PureNnSchema.KEY_SNEAK,
                PureNnSchema.muLawEncode(6.5f),
                PureNnSchema.muLawEncode(-2.0f),
                1, 0, 2, 0
        );
        PureNnSample orig = new PureNnSample(42, 1, 0, 2, 0.15f, vox, fl, act);

        byte[] raw = PureNnSchema.encodeRecord(orig);
        assertEquals(PureNnSchema.RECORD_BYTES, raw.length);
        PureNnSample decoded = PureNnSchema.decodeRecord(raw);
        assertEquals(42, decoded.tick());
        assertEquals(orig.action(), decoded.action());
        assertArrayEquals(orig.voxels(), decoded.voxels());
        assertArrayEquals(orig.floats(), decoded.floats(), 1e-6f);

        PureNnSample mirrored = PureNnSchema.mirrorLeftRight(decoded);
        assertEquals(PureNnSchema.VOX_PLAYER_PLACED, mirrored.voxels()[PureNnSchema.voxelIndex(0, 2, 4)]);
        assertEquals(PureNnSchema.VOX_ENEMY_BED, mirrored.voxels()[PureNnSchema.voxelIndex(1, 3, 0)]);
        assertEquals(-0.18f, mirrored.floats()[2], 1e-6f);
        assertEquals(1.0f, mirrored.floats()[23], 1e-6f);
        assertEquals(0.5f, mirrored.floats()[29], 1e-6f);
        assertTrue((mirrored.action().keysMask() & PureNnSchema.KEY_A) != 0);
        assertEquals(0, mirrored.action().keysMask() & PureNnSchema.KEY_D);

        PureNnSample doubleMirrored = PureNnSchema.mirrorLeftRight(mirrored);
        assertEquals(decoded.action(), doubleMirrored.action());
        assertArrayEquals(decoded.voxels(), doubleMirrored.voxels());
        assertArrayEquals(decoded.floats(), doubleMirrored.floats(), 1e-6f);
    }

    @Test
    public void testBaritoneExecutorThreadsAreDaemon() throws Exception {
        CompletableFuture<Boolean> isDaemonFuture = new CompletableFuture<>();
        Baritone.getExecutor().execute(() -> isDaemonFuture.complete(Thread.currentThread().isDaemon()));
        assertTrue("Baritone worker pool threads must be daemon to prevent ClientShutdownWatchdog hangs",
                isDaemonFuture.get(5, TimeUnit.SECONDS));
    }

    @Test
    public void testParkourTakeoffAlignmentAndOverheadBedDefenseInvariants() {
        // 1) Off-axis parkour takeoff (e.g. 18.6 deg Stonekeep void drift) must align before sprint-jumping
        assertTrue(MovementParkour.shouldAlignBeforeParkourTakeoff(18.6f, 0.05, 4));
        assertTrue(MovementParkour.shouldAlignBeforeParkourTakeoff(4.0f, 0.34, 3));
        assertFalse(MovementParkour.shouldAlignBeforeParkourTakeoff(6.0f, 0.12, 3));

        // 2) Home bed defense shell protection prevents pathfinder from mining our own End Stone
        BedwarsMapCore core = new BedwarsMapCore(
                BedwarsMapCore.createCenteredBounds(-75, 20, -26, 64, 96),
                -75.0, -26.0, 24, 108
        );
        core.addBed(new BedwarsMapCore.BedPOI(BedwarsMapCore.TeamColor.GRAY, -75, 66, -26, -75, 66, -25, true, false));
        assertTrue("Adjacent end_stone defense shell must be protected from Baritone mining",
                core.isHomeBedDefenseShell(-76, 66, -26));
        assertTrue("Top defense block over home bed must be protected",
                core.isHomeBedDefenseShell(-75, 67, -26));
        assertFalse("Distant blocks on the island must not be marked as home bed shell",
                core.isHomeBedDefenseShell(-88, 65, -20));

        // 3) Overhead home-bed defense: hold solid ground instead of pillaring onto enemy void sky-bridge
        assertTrue(CombatProcess.shouldHoldHomeGroundAgainstOverheadEnemy(true, true, 6.0, 7.5, 6.5));
        assertFalse("Engage normally once enemy drops onto same level",
                CombatProcess.shouldHoldHomeGroundAgainstOverheadEnemy(true, true, 6.0, 7.5, 1.5));
        assertFalse("Do not camp home bed if home bed is already gone",
                CombatProcess.shouldHoldHomeGroundAgainstOverheadEnemy(true, false, 6.0, 7.5, 6.5));

        // 4) Generator scoring: hologram-verified diamond gen beats closer decorative high-Y diamond block
        double decoHighY = baritone.bedwars.BedwarsProcess.scoreGeneratorCandidate(25.0, false, 55, 45);
        double realGen = baritone.bedwars.BedwarsProcess.scoreGeneratorCandidate(32.0, true, 45, 45);
        assertTrue("Hologram-verified generator on island Y must beat decorative high-Y block", realGen < decoHighY);

        // 5) Floor-level forge filter rejects decorative roof iron/gold blocks (e.g. +6Y roof beams)
        assertTrue(baritone.bedwars.BedwarsArenaMemory.isFloorLevelForgeBlock(51, 51));
        assertTrue(baritone.bedwars.BedwarsArenaMemory.isFloorLevelForgeBlock(49, 51));
        assertFalse("Roof iron block +6Y above bed must be rejected as HOME_FORGE",
                baritone.bedwars.BedwarsArenaMemory.isFloorLevelForgeBlock(57, 51));

        // 6) Server-agnostic scoreboard team parsing (prefix checkmarks + crown symbol, e.g. Jartex)
        baritone.bedwars.BedwarsMatchState ms = new baritone.bedwars.BedwarsMatchState();
        ms.updateScoreboard("BedWars", List.of(
                "Diamond Upgrade:", "04:00",
                "✔ Red", "✔ Blue", "✗ Yellow", "✔ Green (♚)"
        ));
        assertEquals(BedwarsMapCore.TeamColor.GREEN, ms.getHomeTeam());
        assertTrue(ms.isMatchStarted());
        assertFalse(ms.isTeamBedDestroyed(BedwarsMapCore.TeamColor.RED));
        assertTrue(ms.isTeamBedDestroyed(BedwarsMapCore.TeamColor.YELLOW));

        // 7) Never start eating golden apple in close melee even at critical 5.4 HP, and pre-gap safely at 8m
        assertFalse("Must NOT start eating gapple at 2.5m melee even at 5.4 HP",
                baritone.behavior.SurvivalBehavior.shouldHealWithGoldenApple(true, 5.4f, 0.0f, 20, 2.5, false, 32));
        assertFalse("Must abort active gapple chew if enemy closes to 3.0m with >5 ticks left",
                baritone.behavior.SurvivalBehavior.shouldHealWithGoldenApple(true, 6.0f, 0.0f, 20, 3.0, true, 18));
        assertTrue("Should pre-gap before fight at 9m when missing absorption",
                baritone.behavior.SurvivalBehavior.shouldHealWithGoldenApple(true, 19.0f, 0.0f, 20, 9.0, false, 32));

        // 8) Auto-requeue hotbar item, confirm GUI slot, and lobby NPC detection
        assertTrue(baritone.bedwars.BedwarsAutoQueueController.isPlayAgainHotbarItem("paper", "Play Again (Right Click)"));
        assertEquals(11, baritone.bedwars.BedwarsAutoQueueController.findAutoQueueConfirmSlot(
                "Go to new game",
                List.of(
                        new baritone.bedwars.BedwarsShopPlanner.ShopSlotInfo(11, "lime_wool", "Confirm", "Click to join", 1),
                        new baritone.bedwars.BedwarsShopPlanner.ShopSlotInfo(15, "red_wool", "Cancel", "Click to close", 1)
                ),
                "solo"
        ));
        assertTrue(baritone.bedwars.BedwarsAutoQueueController.isLobbyQueueNpcLabel("BedWars Solo CLICK TO PLAY", "solo"));
        assertFalse(baritone.bedwars.BedwarsAutoQueueController.isLobbyQueueNpcLabel("Item Shop", "solo"));

        // 9) PikaNetwork-style hologram stacks, hub Server Selector and non-queue NPCs
        assertTrue(baritone.bedwars.BedwarsAutoQueueController.isLobbyQueueNpcLabel(
                "SOLOS 8 teams of 1 player 92 players playing /bedwars-1 Right Click to join", "solo"));
        assertTrue(baritone.bedwars.BedwarsAutoQueueController.isLobbyQueueNpcLabel(
                "DOUBLES 8 teams of 2 players 207 players playing /bedwars-2 Right Click to join", "doubles"));
        assertFalse(baritone.bedwars.BedwarsAutoQueueController.isLobbyQueueNpcLabel(
                "Going back to the main lobby? Return Hub Right Click to teleport", "solo"));
        assertFalse(baritone.bedwars.BedwarsAutoQueueController.isLobbyQueueNpcLabel(
                "Customize your QuickBuy layout to shop faster during your games! BedWars ShopKeeper Right Click to edit", "solo"));
        assertTrue(baritone.bedwars.BedwarsAutoQueueController.isGameMenuHotbarItem("compass", "Server Selector \u25BA Right Click"));
        assertTrue(baritone.bedwars.BedwarsAutoQueueController.isGameMenuHotbarItem("nether_star", "Game Menu"));
        assertFalse(baritone.bedwars.BedwarsAutoQueueController.isGameMenuHotbarItem("clock", "Spectate \u25BA Right Click"));
        assertFalse(baritone.bedwars.BedwarsAutoQueueController.isGameMenuHotbarItem("player_head", "Profile"));
        assertEquals(30, baritone.bedwars.BedwarsAutoQueueController.findAutoQueueConfirmSlot(
                "Server Selector",
                List.of(
                        new baritone.bedwars.BedwarsShopPlanner.ShopSlotInfo(10, "tnt", "OpFactions", "Season #49", 1),
                        new baritone.bedwars.BedwarsShopPlanner.ShopSlotInfo(30, "red_bed", "BedWars", "Click to play", 1),
                        new baritone.bedwars.BedwarsShopPlanner.ShopSlotInfo(32, "golden_apple", "OpLifesteal", "", 1)
                ),
                "solo"
        ));
        // 10) BedWars lobby vs multi-game hub detection & forward NPC discovery waypoint
        assertTrue(baritone.bedwars.BedwarsAutoQueueController.isBedwarsLobbyScoreboard("BedWars"));
        assertTrue(baritone.bedwars.BedwarsAutoQueueController.isBedwarsLobbyScoreboard("§e§lBED WARS"));
        assertFalse(baritone.bedwars.BedwarsAutoQueueController.isBedwarsLobbyScoreboard("PikaNetwork"));
        net.minecraft.core.BlockPos wp0 = baritone.bedwars.BedwarsAutoQueueController.computeLobbySearchWaypoint(0.5, 75.0, 14.5, 0.0f, 0);
        assertEquals(0, wp0.getX());
        assertEquals(34, wp0.getZ());
    }

    @Test
    public void predictedVoidFallDistinguishesVoidFromLandings() {
        // Island floor at y=62 for x in [0,4); nothing beyond
        baritone.bedwars.BedwarsTacticalHelper.BlockSolidPredicate island = (x, y, z) -> y == 62 && x >= 0 && x < 4 && z >= 0 && z < 4;
        // Falling straight off the +x edge into void
        assertTrue(baritone.bedwars.BedwarsTacticalHelper.predictsVoidFall(4.6, 63.0, 2.5, 0.1, -0.08, 0.0, island, 14.0));
        // Short hop landing back on the island
        assertFalse(baritone.bedwars.BedwarsTacticalHelper.predictsVoidFall(3.0, 63.5, 2.5, 0.05, 0.1, 0.0, island, 14.0));
        // Directly above floor
        assertFalse(baritone.bedwars.BedwarsTacticalHelper.predictsVoidFall(2.0, 64.0, 2.0, 0.0, -0.2, 0.0, island, 14.0));
    }

    @Test
    public void waitingCageVsHubLobbySpectatorAndBridgeWallCountersAndSmoothAim() {
        // 1) Spectator (Play Again paper + Back to Lobby barrier) & BedWars Queue Lobby must NEVER be treated as pre-game cage
        assertTrue(baritone.bedwars.BedwarsArenaMemory.isHubLobbyOrSpectatorNotWaitingCage(
                true, false, true, false, false, false, false, -1));
        assertTrue(baritone.bedwars.BedwarsArenaMemory.isHubLobbyOrSpectatorNotWaitingCage(
                false, false, false, true, true, false, false, -1));
        assertTrue(baritone.bedwars.BedwarsArenaMemory.isHubLobbyOrSpectatorNotWaitingCage(
                false, false, false, true, false, false, false, -1));
        // True pre-game waiting cage (countdown active, no hub compass, no Play Again/Spectate)
        assertFalse(baritone.bedwars.BedwarsArenaMemory.isHubLobbyOrSpectatorNotWaitingCage(
                false, false, false, false, true, true, true, 15));

        // 2) Butterfly / 2-layer bed defense must still plan Layer 2 even after Layer 1 is 100% complete!
        baritone.bedwars.BedwarsTacticalHelper.BlockSolidPredicate layer1Done = (x, y, z) -> {
            if (y == 64 && (x == 0 || x == 1) && z == 0) return true; // bed
            // All 8 layer-1 cells covered
            boolean cap = (y == 65 && (x == 0 || x == 1) && z == 0);
            boolean side = (y == 64 && ((x == -1 && z == 0) || (x == 2 && z == 0)
                    || ((x == 0 || x == 1) && (z == -1 || z == 1))));
            return cap || side;
        };
        List<baritone.bedwars.BedwarsTacticalHelper.DefensePlacement> layer2 =
                baritone.bedwars.BedwarsTacticalHelper.buildHomeBedDefensePlan(0, 64, 0, 1, 64, 0, true, layer1Done);
        assertFalse("Layer 2 must still be planned once Layer 1 is covered", layer2.isEmpty());
        assertTrue(layer2.stream().allMatch(p -> p.layer() == 2));

        // 3) Pro bridge block-wall counters (shears bottom-break vs +2Y pillar-over vs 1-block jump)
        assertEquals(CombatProcess.BridgeWallCounter.FAST_TOOL_BOTTOM_BREAK,
                CombatProcess.chooseBridgeWallCounter(true, true, true, 32, true));
        assertEquals(CombatProcess.BridgeWallCounter.HIGH_GROUND_PILLAR,
                CombatProcess.chooseBridgeWallCounter(false, true, true, 16, true));
        assertEquals(CombatProcess.BridgeWallCounter.STEP_JUMP,
                CombatProcess.chooseBridgeWallCounter(false, false, true, 16, true));

        // 4) Smooth Taunahi-grade saccade sweep: 90-degree turn must converge with ZERO tick-to-tick yaw reversals
        baritone.human.MouseModel mouse = new baritone.human.MouseModel(new baritone.behavior.look.ForkableRandom(2026L));
        baritone.human.HumanProfile pro = baritone.human.HumanProfile.preset("pro");
        baritone.api.utils.Rotation cur = new baritone.api.utils.Rotation(0.0f, 5.0f);
        baritone.api.utils.Rotation goal = new baritone.api.utils.Rotation(90.0f, 5.0f);
        float prevDy = 0.0f;
        int reversals = 0;
        for (int t = 0; t < 12; t++) {
            baritone.api.utils.Rotation next = mouse.step(cur, goal, false, false, pro, 0.5);
            float dy = baritone.human.MouseModel.shortestYawDelta(cur.getYaw(), next.getYaw());
            if (t > 0 && prevDy * dy < -0.5f && baritone.human.MouseModel.angularDistance(cur, goal) > 4.0f) {
                reversals++;
            }
            prevDy = dy;
            cur = next;
        }
        assertEquals("Primary saccade sweep should not vibrate/reverse direction", 0, reversals);

        // 5) Client-only FreecamController mode parsing & smooth 3D free-fly kinematics
        assertEquals(baritone.human.FreecamController.Mode.FLY, baritone.human.FreecamController.Mode.parse("fly"));
        assertEquals(baritone.human.FreecamController.Mode.LOOK, baritone.human.FreecamController.Mode.parse("look"));
        assertEquals(baritone.human.FreecamController.Mode.OFF, baritone.human.FreecamController.Mode.parse("off"));
        double[] fly = baritone.human.FreecamController.stepFlyPhysics(0, 70, 0, 0, 0, 0, 0.0f, 0.0f, 1.0, 0.0, 1.0, 1.0);
        assertTrue("Forward (+Z at yaw=0) must increase z", fly[2] > 0.2);
        assertTrue("Upward input must increase y", fly[1] > 70.2);
    }

    @Test
    public void testVoxelShapeBedAimArmorThresholdsZeroDeadlockAndWinProgression() {
        // 1) Half-height BedBlock (maxY = 0.5625) VoxelShape aim must NEVER shoot at y+0.85 above the bed!
        double[] bedAim = BedwarsTacticalHelper.closestPointOnShapeBox(
                76.38, 58.62, -19.91,
                76, 57, -23,
                0.0, 0.0, 0.0, 1.0, 0.5625, 1.0,
                0.18
        );
        assertTrue("Bed aim Y must stay strictly inside [57.0, 57.5625], got " + bedAim[1],
                bedAim[1] >= 57.08 && bedAim[1] <= 57.48);

        // 2) Exact BedWars Iron Armor (11.0 points) must be recognized as owned & unlock Gapples/TNT,
        //    and 32 wool + 20 iron must buy Shears instead of hoarding endless wool!
        InventorySummary ironArmorWearing = new InventorySummary(20, 6, 0, 0, 32, 0, 32, 3, 1, 1, 0, 11.0, 0, 0, 0, 0, false, false);
        List<ShopSlotInfo> shopSlots = List.of(
                new ShopSlotInfo(19, "white_wool", "Wool", "Cost: 4 Iron", 16),
                new ShopSlotInfo(20, "iron_boots", "Permanent Iron Armor", "Cost: 12 Gold", 1),
                new ShopSlotInfo(21, "shears", "Permanent Shears", "Cost: 20 Iron", 1),
                new ShopSlotInfo(22, "golden_apple", "Golden Apple", "Cost: 3 Gold", 1)
        );
        var shearsClick = BedwarsShopPlanner.chooseNextShopClick(
                shopSlots, ShopPreset.ESSENTIALS, ironArmorWearing, false, true, 0.9, 30.0, false
        );
        assertNotNull(shearsClick);
        assertEquals("With 32 wool and 20 iron, Shears must outscore buying a 3rd stack of wool", 21, shearsClick.slotIndex());

        // Trap sub-menu opener button inside Team Upgrades shop
        InventorySummary oneDia = new InventorySummary(8, 0, 1, 0, 32, 0, 32, 2, 0, 0, 0, 9.0, 0, 0, 0, 0, false, false);
        List<ShopSlotInfo> upgMenu = List.of(
                new ShopSlotInfo(10, "iron_sword", "Sharpened Swords", "Cost: 4 Diamonds", 1),
                new ShopSlotInfo(14, "tripwire_hook", "Buy a trap", "Click to open", 1)
        );
        var trapTab = BedwarsShopPlanner.chooseNextShopClick(
                upgMenu, ShopPreset.UPGRADES, oneDia, true, true, 0.9, 40.0, false
        );
        assertNotNull("Must click 'Buy a trap' sub-menu button when holding 1 diamond with 0 active traps", trapTab);
        assertTrue(trapTab.categorySwitch());
        assertEquals(14, trapTab.slotIndex());

        // 3) Zero 4.5m..9.0m Combat-vs-Shop/Stash paralysis deadlock when trapped at home with broken bed!
        InventorySummary lowHpAtBase = new InventorySummary(24, 8, 1, 0, 32, 0, 32, 2, 0, 0, 0, 9.0, 0, 0, 0, 0, false, false);
        ObsSnapshot trappedAtHome = new ObsSnapshot(
                5.8f, 0.0f, 20, true, false, false, false,
                0.1, 0.0, 0, 16.0, 32.0, false, false,
                lowHpAtBase,
                false, 0.0, 6.0, 0.0, 1.0, 0.0,
                2, 42.0, 0.0, 1.0, 4.0, 2.0,
                28.0, 1.0, 0.0, 48.0, 0.0, 1.0,
                List.of(new BrainSchema.ThreatFeature(true, 5.6, 0.1, 0.99, 0.2, 0.85)),
                8.0, 0.1, Maneuver.ENGAGE_THREAT_COMBAT.ordinal(), 8, false, true, false,
                42.0, 0.08, 1, 1, 0, 0, 0, 0, false, 0, -1.0, -1.0, 0.0, 99.0
        );
        boolean[] trappedMask = BrainActionSpace.computeValidManeuverMask(trappedAtHome);
        assertFalse("Must mask SHOP_BUY_ESSENTIALS at 5.6m threat", trappedMask[Maneuver.SHOP_BUY_ESSENTIALS.ordinal()]);
        assertFalse("Must mask SHOP_BUY_COMBAT_UPGRADES at 5.6m threat", trappedMask[Maneuver.SHOP_BUY_COMBAT_UPGRADES.ordinal()]);
        assertFalse("Must mask STASH_VALUABLES at 5.6m threat", trappedMask[Maneuver.STASH_VALUABLES.ordinal()]);
        assertEquals("Must fight at 5.6m instead of deadlocking on SHOP/STASH when trapped at base",
                Maneuver.ENGAGE_THREAT_COMBAT,
                DeterministicStrategyModel.evaluate(trappedAtHome, trappedMask, null).bestManeuver());

        // 4) Bed-Break -> Final-Kill follow-through on nearby bedless enemy (22m) even while other beds exist!
        ObsSnapshot finalKillFollowThrough = new ObsSnapshot(
                18.0f, 0.0f, 20, true, false, false, false,
                0.2, 0.0, 0, 16.0, 32.0, false, false,
                ironArmorWearing,
                true, 0.9, 42.0, 0.0, 1.0, 0.0,
                3, 48.0, 0.0, 1.0, 5.0, 3.0,
                30.0, 1.0, 0.0, 50.0, 0.0, 1.0,
                List.of(new BrainSchema.ThreatFeature(true, 22.0, 0.1, 0.99, 0.1, 0.50)),
                6.0, 0.15, Maneuver.RUSH_NEAREST_BED.ordinal(), 12, true, false, false,
                48.0, 0.68, 1, 1, 1, 1, 0, 1, false, 0, -1.0, -1.0, 0.0, 22.0
        );
        boolean[] fkMask = BrainActionSpace.computeValidManeuverMask(finalKillFollowThrough);
        assertEquals("Must hunt nearby bedless enemy for Final Kill elimination",
                Maneuver.ENGAGE_THREAT_COMBAT,
                DeterministicStrategyModel.evaluate(finalKillFollowThrough, fkMask, null).bestManeuver());

        // 5) Zero mid-bridge RUSH -> SHOP yank-back when placing 1 wool drops blocks to 31 while still within 22m of home!
        InventorySummary midBridgeInv = new InventorySummary(8, 0, 0, 0, 31, 0, 31, 2, 0, 0, 0, 7.0, 0, 0, 0, 0, false, false);
        ObsSnapshot midBridgeRush = new ObsSnapshot(
                20.0f, 0.0f, 20, true, false, true, false,
                0.2, 0.0, 0, 16.0, 32.0, false, false,
                midBridgeInv,
                true, 1.0, 16.0, 0.0, 1.0, 0.0,
                3, 24.0, 0.0, 1.0, 2.0, 2.0,
                30.0, 1.0, 0.0, 50.0, 0.0, 1.0,
                List.of(),
                1.2, 0.45, Maneuver.RUSH_NEAREST_BED.ordinal(), 18, true, false, false
        );
        boolean[] midBridgeMask = BrainActionSpace.computeValidManeuverMask(midBridgeRush);
        assertEquals("Must stay committed to RUSH_NEAREST_BED mid-bridge instead of yanking back to shop for bulk wool",
                Maneuver.RUSH_NEAREST_BED,
                DeterministicStrategyModel.evaluate(midBridgeRush, midBridgeMask, null).bestManeuver());

        // 6) Mid-game 5s respawn spectator immunity, decorative gen filter, competitive 1.8 CPS & KB stick selection
        assertTrue(BedwarsAutoQueueController.isMidGameRespawnSpectator(true, false, false, false, true, 4, 40));
        assertFalse(BedwarsAutoQueueController.isMidGameRespawnSpectator(true, false, true, false, false, 0, 120));
        assertTrue(baritone.bedwars.BedwarsArenaMemory.isPlausibleStandaloneGenBlock(true, true, 0, 64, 50, 90, 900.0));
        assertFalse("Covered decorative wall block must be rejected",
                baritone.bedwars.BedwarsArenaMemory.isPlausibleStandaloneGenBlock(false, true, 0, 64, 50, 90, 900.0));
        assertFalse("Multi-block decorative statue cluster must be rejected",
                baritone.bedwars.BedwarsArenaMemory.isPlausibleStandaloneGenBlock(true, true, 3, 64, 50, 90, 900.0));
        assertTrue(CombatPhysicsModel.shouldUseKnockbackStick(true, true, 0.15, 16.0f));
        assertFalse(CombatPhysicsModel.shouldUseKnockbackStick(true, false, 0.05, 16.0f));
    }

    @Test
    public void testPhase2ConnectedBridgeEvEtaUtilityAndLiveMatchDetection() {
        // 1) BedwarsBridgeConnectivity: L-shaped Home (0,64,0) -> Diamond (30,64,0) -> Enemy (30,64,30)
        baritone.bedwars.BedwarsBridgeConnectivity.Workspace ws = new baritone.bedwars.BedwarsBridgeConnectivity.Workspace();
        baritone.bedwars.BedwarsBridgeConnectivity.ColumnGroundProbe lBridge =
                baritone.bedwars.BedwarsBridgeConnectivity.fromSolidProbe((x, y, z) -> {
                    if (y != 63) return false;
                    boolean homeIsland = Math.abs(x) <= 3 && Math.abs(z) <= 3;
                    boolean diamondIsland = Math.abs(x - 30) <= 3 && Math.abs(z) <= 3;
                    boolean enemyIsland = Math.abs(x - 30) <= 3 && Math.abs(z - 30) <= 3;
                    boolean leg1 = z == 0 && x >= 0 && x <= 30;
                    boolean leg2 = x == 30 && z >= 0 && z <= 30;
                    return homeIsland || diamondIsland || enemyIsland || leg1 || leg2;
                });
        baritone.bedwars.BedwarsBridgeConnectivity.solve(ws, 0, 64, 0, lBridge);
        var lRoute = baritone.bedwars.BedwarsBridgeConnectivity.estimateRouteTo(ws, 30, 64, 30);
        assertTrue("L-shaped Home->Diamond->Enemy bridge must be detected as connected", lRoute.connected());
        assertEquals("Connected L-shaped bridge needs 0 blocks", 0, lRoute.blocksNeeded());
        assertTrue("Walk distance along L-shaped bridge should reflect ~55-65 blocks (not straight-line 42), got " + lRoute.walkDistBlocks(),
                lRoute.walkDistBlocks() >= 52.0 && lRoute.walkDistBlocks() <= 68.0);

        // 2) Jumpable 3-block air gap (0 blocks needed) vs unjumpable 4-block gap (4 blocks needed)
        baritone.bedwars.BedwarsBridgeConnectivity.ColumnGroundProbe threeGap =
                baritone.bedwars.BedwarsBridgeConnectivity.fromSolidProbe((x, y, z) ->
                        y == 63 && z == 0 && (x == 0 || x == 4 || x == 5));
        baritone.bedwars.BedwarsBridgeConnectivity.solve(ws, 0, 64, 0, threeGap);
        var jumpRoute = baritone.bedwars.BedwarsBridgeConnectivity.estimateRouteTo(ws, 5, 64, 0);
        assertTrue("3-block flat air gap must be jump-connected", jumpRoute.connected());
        assertEquals(0, jumpRoute.blocksNeeded());

        baritone.bedwars.BedwarsBridgeConnectivity.ColumnGroundProbe fourGap =
                baritone.bedwars.BedwarsBridgeConnectivity.fromSolidProbe((x, y, z) ->
                        y == 63 && z == 0 && (x == 0 || x == 5 || x == 6));
        baritone.bedwars.BedwarsBridgeConnectivity.solve(ws, 0, 64, 0, fourGap);
        var noJumpRoute = baritone.bedwars.BedwarsBridgeConnectivity.estimateRouteTo(ws, 6, 64, 0);
        assertFalse("4-block air gap cannot be jumped", noJumpRoute.connected());
        assertEquals(4, noJumpRoute.blocksNeeded());

        // 3) 4 blocks from an open bed with 0 blocks (connected/jumpable) -> finish bed instead of going to shop!
        InventorySummary zeroBlkRichIron = new InventorySummary(20, 0, 0, 0, 0, 0, 0, 2, 0, 0, 0, 7.0, 0, 0, 0, 0, false, false);
        ObsSnapshot nearOpenBed = new ObsSnapshot(
                20f, 0f, 20, true, false, false, false, 0.1, 0.0, 0, 16, 32, false, false,
                zeroBlkRichIron, true, 0.9, 48.0, 0, 1, 0, 2, 4.0, 0, 1, 0.6, 0.6,
                32.0, 1, 0, 50.0, 0, 1, List.of(), 6.0, 0.1,
                Maneuver.RUSH_NEAREST_BED.ordinal(), 65, true, false, false,
                4.0, 0.55, 1, 1, 0, 0, 0, 0, false, 0, -1.0, -1.0, 0.0, 99.0,
                0, 48.0, 0, 4.0, 0, 4.0, 14, 18.0, 26, 24.0
        );
        boolean[] openBedMask = BrainActionSpace.computeValidManeuverMask(nearOpenBed);
        assertTrue("Connected/jumpable bed must stay unmasked even at 0 blocks", openBedMask[Maneuver.RUSH_NEAREST_BED.ordinal()]);
        assertEquals("Must finish breaking open bed 4m away with 0 blocks instead of walking home to shop",
                Maneuver.RUSH_NEAREST_BED, DeterministicStrategyModel.evaluate(nearOpenBed, openBedMask, null).bestManeuver());

        // 4) Wool priority tied directly to G_nextObjective: 16 wool + 34 iron with 24-block gap MUST buy wool, not save for Chainmail!
        InventorySummary shortWool34Iron = new InventorySummary(34, 0, 0, 0, 16, 0, 16, 1, 0, 0, 0, 7.0, 0, 0, 0, 0, false, false);
        ObsSnapshot needCrossSnap = new ObsSnapshot(
                20f, 0f, 20, true, false, false, false, 0.0, 0.0, 0, 16, 32, false, false,
                shortWool34Iron, true, 0.9, 6.0, 0, 1, 0, 3, 44.0, 0, 1, 3.0, 2.5,
                28.0, 1, 0, 50.0, 0, 1, List.of(), 8.0, 0.0,
                Maneuver.SHOP_BUY_ESSENTIALS.ordinal(), 4, false, true, false,
                44.0, 0.55, 1, 1, 0, 0, 0, 0, false, 0, -1.0, -1.0, 0.0, 99.0,
                0, 6.0, 24, 18.0, 24, 18.0, 16, 12.0, 28, 22.0
        );
        assertEquals(Maneuver.SHOP_BUY_ESSENTIALS,
                DeterministicStrategyModel.evaluate(needCrossSnap, BrainActionSpace.computeValidManeuverMask(needCrossSnap), null).bestManeuver());

        List<ShopSlotInfo> woolAndChain = List.of(
                new ShopSlotInfo(19, "white_wool", "Wool", "Cost: 4 Iron", 16),
                new ShopSlotInfo(20, "chainmail_boots", "Permanent Chainmail Armor", "Cost: 40 Iron", 1)
        );
        ShopClickDecision buyCrossingWool = BedwarsShopPlanner.chooseNextShopClick(
                woolAndChain, ShopPreset.ESSENTIALS, shortWool34Iron, false, true, 0.9, 45.0, false, null, EnemyDefenseIntel.NONE, null, 24
        );
        assertNotNull("Must buy wool when blocks (16) < G_nextObjective (24) instead of saving 34 iron for Chainmail", buyCrossingWool);
        assertEquals(19, buyCrossingWool.slotIndex());

        // Even with 40 iron and 16 wool when gap needs 24 blocks, buying wool beats spending last 40 iron on Chainmail!
        InventorySummary exact40IronShortWool = new InventorySummary(40, 0, 0, 0, 16, 0, 16, 1, 0, 0, 0, 7.0, 0, 0, 0, 0, false, false);
        ShopClickDecision woolBeforeChain = BedwarsShopPlanner.chooseNextShopClick(
                woolAndChain, ShopPreset.COMBAT, exact40IronShortWool, false, true, 0.9, 45.0, false, null, EnemyDefenseIntel.NONE, null, 24
        );
        assertNotNull(woolBeforeChain);
        assertEquals("Must buy wool before spending final 40 iron on Chainmail when unable to cross gap=24", 19, woolBeforeChain.slotIndex());

        // 5) Walk-home vs Void-recall ETA + gear-loss: connected 30m bridge walks home; connected 47m w/ Stone Sword walks home; broken bridge recalls!
        InventorySummary gearedPush = new InventorySummary(16, 0, 0, 0, 24, 0, 24, 2, 0, 0, 0, 7.0, 0, 0, 0, 0, false, false);
        ObsSnapshot connected47m = new ObsSnapshot(
                20f, 0f, 20, true, false, false, false, 0.2, 0.0, 0, 16, 32, false, false,
                gearedPush, true, 0.9, 47.0, 0, 1, 0, 3, 20.0, 0, 1, 3.0, 2.5,
                25.0, 1, 0, 45.0, 0, 1, List.of(), 4.0, 0.2,
                Maneuver.RUSH_NEAREST_BED.ordinal(), 30, true, false, true,
                20.0, 0.55, 1, 1, 0, 0, 0, 0, false, 0, -1.0, -1.0, 0.0, 99.0,
                0, 47.0, 4, 16.0, 4, 16.0, 10, 15.0, 20, 25.0
        );
        assertEquals("With Stone Sword + 24 wool on a connected 47m bridge, walking home beats void suicide",
                Maneuver.RETREAT_HOME,
                DeterministicStrategyModel.evaluate(connected47m, BrainActionSpace.computeValidManeuverMask(connected47m), null).bestManeuver());

        InventorySummary lowGear = new InventorySummary(8, 0, 0, 0, 4, 0, 4, 1, 0, 0, 0, 7.0, 0, 0, 0, 0, false, false);
        ObsSnapshot brokenBridgeHome = new ObsSnapshot(
                20f, 0f, 20, true, false, false, false, 0.1, 0.0, 0, 16, 32, false, false,
                lowGear, true, 0.9, 34.0, 0, 1, 0, 3, 20.0, 0, 1, 3.0, 2.5,
                25.0, 1, 0, 45.0, 0, 1, List.of(), 2.0, 0.4,
                Maneuver.RUSH_NEAREST_BED.ordinal(), 30, true, false, true,
                20.0, 0.55, 1, 1, 0, 0, 0, 0, false, 0, -1.0, -1.0, 0.0, 99.0,
                15, 19.0, 4, 16.0, 4, 16.0, 10, 15.0, 20, 25.0
        );
        assertEquals("When bridge home is broken (need 15 > have 4) and bed is under attack, void recall must fire",
                Maneuver.TACTICAL_VOID_RECALL,
                DeterministicStrategyModel.evaluate(brokenBridgeHome, BrainActionSpace.computeValidManeuverMask(brokenBridgeHome), null).bestManeuver());

        // 6) Progress commitment past tick 50 (no 50-tick mid-bridge cliff!)
        InventorySummary midBridgePast50 = new InventorySummary(12, 0, 0, 0, 22, 0, 22, 2, 0, 0, 0, 7.0, 0, 0, 0, 0, false, false);
        ObsSnapshot past50Snap = new ObsSnapshot(
                20f, 0f, 20, true, false, true, false, 0.2, 0.0, 0, 16, 32, false, false,
                midBridgePast50, true, 0.9, 18.0, 0, 1, 0, 3, 22.0, 0, 1, 2.0, 2.0,
                30.0, 1, 0, 50.0, 0, 1, List.of(), 1.5, 0.50,
                Maneuver.RUSH_NEAREST_BED.ordinal(), 85, true, false, false,
                22.0, 0.55, 1, 1, 0, 0, 0, 0, false, 0, -1.0, -1.0, 0.0, 99.0,
                0, 18.0, 10, 12.0, 10, 12.0, 16, 14.0, 28, 22.0
        );
        assertEquals("Mid-bridge rush past tick 50 must remain committed without flickering to shop",
                Maneuver.RUSH_NEAREST_BED,
                DeterministicStrategyModel.evaluate(past50Snap, BrainActionSpace.computeValidManeuverMask(past50Snap), null).bestManeuver());

        // 7) BedwarsAutoQueueController.isLiveArenaMatch: cage drop w/ core==null & wooden sword != BedWars lobby!
        assertTrue("Same-world cage drop with wooden sword/discovered teams must be classified as live match immediately",
                BedwarsAutoQueueController.isLiveArenaMatch(false, false, false, false, false, false, true, true));
        assertTrue(BedwarsAutoQueueController.isInGameLoadoutItem("wooden_sword"));
        assertTrue(BedwarsAutoQueueController.isInGameLoadoutItem("iron_ingot"));
        assertFalse(BedwarsAutoQueueController.isInGameLoadoutItem("compass"));
        assertFalse("Real BedWars queue lobby (no teams, no loadout, no beds) must NOT be treated as live match",
                BedwarsAutoQueueController.isLiveArenaMatch(false, false, false, false, false, false, false, false));
        assertFalse("Pre-game waiting cage must NOT be treated as live match",
                BedwarsAutoQueueController.isLiveArenaMatch(true, false, false, false, true, true, true, true));
        assertFalse("Main hub with Server Selector compass must NOT be treated as live match",
                BedwarsAutoQueueController.isLiveArenaMatch(false, true, false, true, true, true, true, true));
    }

    @Test
    public void testPhase3And4VoidDeadEnemyFilterStrandedRecoveryGroundedPvpAndStalemate() {
        // 1) isEnemyDoomedToVoid: jumping from skybridge (y=95) down onto island (y=64) is NOT a void death!
        BedwarsTacticalHelper.BlockSolidPredicate islandAt64 = (x, y, z) ->
                y == 64 && x >= -4 && x <= 4 && z >= -4 && z <= 4;
        assertFalse("Enemy jumping from high skybridge (y=95) onto solid island (y=64) must NOT be ignored",
                BedwarsTacticalHelper.isEnemyDoomedToVoid(0.5, 92.0, 0.5, 0.05, -0.45, 0.0, false, false, 30, islandAt64));

        // Falling over empty void far below/clear of bridge with zero blocks down to voidFloorY=30 -> VOID_DEAD!
        BedwarsTacticalHelper.BlockSolidPredicate bridgeAt63 = (x, y, z) ->
                y == 63 && x == 0 && z >= -10 && z <= 10;
        assertTrue("Enemy falling over empty void 5 blocks clear of bridge with no pearl must be classified VOID_DEAD",
                BedwarsTacticalHelper.isEnemyDoomedToVoid(5.2, 58.0, 0.5, 0.15, -0.55, 0.0, false, false, 30, bridgeAt63));

        // Still within 3.8m eye wall-clutch reach of bridge -> NOT doomed yet!
        assertFalse("Enemy 1.5 blocks off bridge at y=62.5 can still wall-clutch",
                BedwarsTacticalHelper.isEnemyDoomedToVoid(1.8, 62.5, 0.5, 0.05, -0.30, 0.0, false, false, 30, bridgeAt63));

        // Holding an Ender Pearl while falling over void -> NOT doomed!
        assertFalse("Falling enemy holding Ender Pearl must NOT be ignored",
                BedwarsTacticalHelper.isEnemyDoomedToVoid(5.2, 58.0, 0.5, 0.15, -0.55, 0.0, false, true, 30, bridgeAt63));

        // Grounded or rising -> NOT doomed!
        assertFalse(BedwarsTacticalHelper.isEnemyDoomedToVoid(5.2, 58.0, 0.5, 0.0, -0.55, 0.0, true, false, 30, bridgeAt63));
        assertFalse(BedwarsTacticalHelper.isEnemyDoomedToVoid(5.2, 62.0, 0.5, 0.0, -0.08, 0.0, false, false, 30, bridgeAt63));

        // 2) Stranded-island recovery (0 blocks + broken bridge home with living home bed -> TACTICAL_VOID_RECALL!)
        InventorySummary strandedInv = new InventorySummary(12, 0, 0, 0, 0, 0, 0, 1, 0, 0, 0, 7.0, 0, 0, 0, 0, false, false);
        ObsSnapshot strandedSnap = new ObsSnapshot(
                20f, 0f, 20, true, false, false, false, 0.0, 0.0, 0, 16, 32, false, false,
                strandedInv, true, 0.9, 36.0, 0, 1, 0, 3, 28.0, 0, 1, 3.0, 2.5,
                6.0, 1, 0, 48.0, 0, 1, List.of(), 2.5, 0.35,
                Maneuver.HOLD_HOME_GEN.ordinal(), 20, false, false, false,
                28.0, 0.55, 1, 1, 0, 0, 0, 0, false, 0, -1.0, -1.0, 0.0, 99.0,
                18, 18.0, 20, 12.0, 20, 12.0, 10, 6.0, 24, 24.0
        );
        boolean[] strandedMask = BrainActionSpace.computeValidManeuverMask(strandedSnap);
        assertTrue("Stranded off-home with broken bridge must unmask TACTICAL_VOID_RECALL",
                strandedMask[Maneuver.TACTICAL_VOID_RECALL.ordinal()]);
        assertEquals("Stranded with 0 blocks and broken bridge home must recall to home forge",
                Maneuver.TACTICAL_VOID_RECALL,
                DeterministicStrategyModel.evaluate(strandedSnap, strandedMask, null).bestManeuver());

        // Never void-recall if home bed is destroyed (would be a Final Death!)
        ObsSnapshot strandedNoBed = new ObsSnapshot(
                20f, 0f, 20, true, false, false, false, 0.0, 0.0, 0, 16, 32, false, false,
                strandedInv, false, 0.0, 36.0, 0, 1, 0, 3, 28.0, 0, 1, 3.0, 2.5,
                6.0, 1, 0, 48.0, 0, 1, List.of(), 2.5, 0.35,
                Maneuver.HOLD_HOME_GEN.ordinal(), 20, false, false, false,
                28.0, 0.55, 1, 1, 0, 0, 0, 0, false, 0, -1.0, -1.0, 0.0, 99.0,
                18, 18.0, 20, 12.0, 20, 12.0, 10, 6.0, 24, 24.0
        );
        assertFalse("Must NEVER allow void recall when home bed is dead",
                BrainActionSpace.computeValidManeuverMask(strandedNoBed)[Maneuver.TACTICAL_VOID_RECALL.ordinal()]);

        // 3) Grounded melee chase jump policy: grounded inside ~5.2m & on narrow bridges; sprint-jump to catch runners!
        assertFalse("Must stay grounded inside 5.2m right before melee range",
                CombatProcess.shouldSprintJumpDuringChase(4.8, -0.10, true, true, 4.5, 4.5, 4.5, 4.5, true));
        assertTrue("Must allow sprint-jumping at 5.8m when chasing a fleeing opponent on open ground",
                CombatProcess.shouldSprintJumpDuringChase(5.8, -0.12, true, true, 4.5, 4.5, 4.5, 4.5, true));
        assertFalse("Must plant feet before contact against a head-on charger at 6.0m",
                CombatProcess.shouldSprintJumpDuringChase(6.0, 0.25, true, true, 4.5, 4.5, 4.5, 4.5, true));
        assertFalse("Must NEVER sprint-jump on a narrow void bridge",
                CombatProcess.shouldSprintJumpDuringChase(7.5, -0.10, true, true, 4.5, 1.2, 1.2, 4.5, true));

        // 4) Barrier breach only when actually boxed in (never mine random walls for a distant 11m enemy!)
        assertFalse("Must NOT stop in open terrain to mine random blocks for an 11m enemy",
                CombatProcess.shouldAttemptBarrierBreach(10.5, 11.0, false, false));
        assertTrue("Must still breach when genuinely boxed in on >=3 sides",
                CombatProcess.shouldAttemptBarrierBreach(3.5, 4.2, false, true));

        // 5) Bridge-camper stalemate escalation (+2Y high-ground bypass)
        assertTrue("Must escalate bridge stalemate after 36+ ticks + knockback with blocks",
                CombatProcess.shouldEscalateBridgeStalemate(true, 5.2, 40, 1, 16, true, 0.0));
        assertFalse("Must NOT escalate on open island",
                CombatProcess.shouldEscalateBridgeStalemate(false, 5.2, 60, 2, 16, true, 0.0));
        assertFalse("Must NOT escalate without enough blocks",
                CombatProcess.shouldEscalateBridgeStalemate(true, 5.2, 60, 2, 4, true, 0.0));
        assertFalse("Must NOT escalate immediately before stalemate develops",
                CombatProcess.shouldEscalateBridgeStalemate(true, 5.2, 12, 0, 16, true, 0.0));

        // 6) Void-behind-back positioning steers away from cliff toward solid interior
        boolean[] safe16 = new boolean[16];
        double[] wall16 = new double[16];
        double[] void16 = new double[16];
        Arrays.fill(safe16, true);
        Arrays.fill(wall16, 2.65);
        Arrays.fill(void16, 4.65);
        // Back (8) and right (4..7) near void cliff; left (12) deep solid interior
        void16[8] = 1.0;
        void16[7] = 1.1;
        void16[6] = 1.2;
        void16[5] = 1.3;
        void16[4] = 1.4;
        var edgeThreat = List.of(new CombatProcess.ThreatSnapshot(1, 0.0, 2.8, 2.8, 0.05, 0.0, 0.0f, 0, 20.0f, false, false));
        float escapeYaw = CombatProcess.chooseContextKiteYaw16(
                edgeThreat, 0, 0.0, 0.0, 0.0f, null, false, false, 0,
                new CombatProcess.RayEnv16(safe16, wall16, void16)
        );
        assertFalse(Float.isNaN(escapeYaw));
        // Yaw=0 faces +Z; left (+X) is negative yaw (-90 deg), right (-X) is positive yaw (+90 deg)
        assertTrue("With void behind & right, kite yaw should favor left interior (< 0 deg), got " + escapeYaw,
                escapeYaw < 0.0f);
    }

    @Test
    public void testGroundedCombatVsApproachingAndStableGroundRetreat() {
        // Never sprint-jump at stationary or approaching enemies inside 9.5m (eliminates airborne KB juggles!)
        assertFalse("Must stay grounded at 6.5m when enemy is stationary/trading (closing=0.0)",
                CombatProcess.shouldSprintJumpDuringChase(6.5, 0.0, true, true, 4.5, 4.5, 4.5, 4.5, true));
        assertFalse("Must stay grounded at 8.0m when enemy is slightly closing (closing=+0.05)",
                CombatProcess.shouldSprintJumpDuringChase(8.0, 0.05, true, true, 4.5, 4.5, 4.5, 4.5, true));
        assertTrue("Still sprint-jump when enemy is actively running away at 6.2m (closing=-0.15)",
                CombatProcess.shouldSprintJumpDuringChase(6.2, -0.15, true, true, 4.5, 4.5, 4.5, 4.5, true));

        // Stable-ground reposition: perched on cliff facing +Z (void at front/left/right, wide island behind at r=7..9)
        boolean[] safe = new boolean[16];
        double[] wall = new double[16];
        double[] voidD = new double[16];
        Arrays.fill(safe, true);
        Arrays.fill(wall, 2.65);
        Arrays.fill(voidD, 1.0);
        safe[0] = false;
        voidD[6] = 3.2;
        voidD[7] = 4.4;
        voidD[8] = 4.65;
        voidD[9] = 4.4;
        voidD[10] = 3.2;
        float retreatYaw = CombatProcess.chooseStableGroundRetreatYaw16(0.0f, new CombatProcess.RayEnv16(safe, wall, voidD));
        assertFalse("Must find a valid stable-ground retreat heading", Float.isNaN(retreatYaw));
        assertTrue("Must retreat toward wide solid ground behind (|yaw| >= 135 deg), got " + retreatYaw,
                Math.abs(retreatYaw) >= 135.0f);

        // Continuous kinematic time-to-contact (replaces hardcoded 8.5m/9.5m bailout thresholds)
        assertFalse("Must keep bridging when 8m enemy is running away (closing=-0.15)",
                CombatProcess.shouldAbortBlockPlacementForThreat(8.0, -0.15, 6.0));
        assertFalse("Must keep bridging when 8m enemy is stationary/stuck across void gap (closing=0.0)",
                CombatProcess.shouldAbortBlockPlacementForThreat(8.0, 0.0, 6.0));
        assertTrue("Must abort bridging early when 10m enemy sprints straight at us (closing=0.35 -> ttc=19.4t <= 22t)",
                CombatProcess.shouldAbortBlockPlacementForThreat(10.0, 0.35, 10.0));
        assertTrue("Must always abort when enemy is already inside immediate melee reach (3.4m)",
                CombatProcess.shouldAbortBlockPlacementForThreat(3.4, 0.0, 6.0));
    }

    @Test
    public void testAsyncForwardGoalUpdateAndWrappedYawDistance() {
        // 2-layer async splice replanning: forward/flank updates keep walking, 180-deg reversals cancel immediately
        assertTrue("Same-direction goal shift must keep current path running",
                baritone.behavior.PathingBehavior.isForwardGoalUpdate(1.0, 0.0, 12.0, 3.0));
        assertTrue("Orthogonal flank goal shift must keep current path running",
                baritone.behavior.PathingBehavior.isForwardGoalUpdate(0.0, 1.0, 6.0, 1.0));
        assertFalse("180-degree reversal must cancel current path immediately",
                baritone.behavior.PathingBehavior.isForwardGoalUpdate(1.0, 0.0, -10.0, 0.0));

        // Unwrapped MC yaw wraparound fix (-224.8 vs +135.2 is 0 deg, not -360 deg!)
        assertEquals(0.0f, baritone.api.utils.Rotation.yawDistanceFromOffset(-224.8f, 135.2f), 1e-3f);
        assertEquals(0.0f, baritone.api.utils.Rotation.yawDistanceFromOffset(540.0f, -180.0f), 1e-3f);
        assertEquals(10.0f, baritone.api.utils.Rotation.yawDistanceFromOffset(-175.0f, 175.0f), 1e-3f);
    }
}
