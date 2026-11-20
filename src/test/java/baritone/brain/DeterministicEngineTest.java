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
import baritone.bedwars.BedwarsMapCore;
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
}
