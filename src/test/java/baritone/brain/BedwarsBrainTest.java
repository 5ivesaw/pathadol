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

import baritone.bedwars.BedwarsProcess;
import baritone.bedwars.BedwarsShopPlanner;
import baritone.bedwars.BedwarsShopPlanner.CostSpec;
import baritone.bedwars.BedwarsShopPlanner.Currency;
import baritone.bedwars.BedwarsShopPlanner.ShopClickDecision;
import baritone.bedwars.BedwarsShopPlanner.ShopPreset;
import baritone.bedwars.BedwarsShopPlanner.ShopSlotInfo;
import baritone.bedwars.BedwarsTacticalHelper;
import baritone.bedwars.BedwarsTacticalHelper.DefensePlacement;
import baritone.bedwars.BedwarsTacticalHelper.FireballJumpPlan;
import baritone.bedwars.BedwarsTacticalHelper.InventorySummary;
import baritone.brain.BrainActionSpace.AntiChatterCommitter;
import baritone.brain.BrainActionSpace.BrainDecision;
import baritone.brain.BrainActionSpace.CombatMode;
import baritone.brain.BrainActionSpace.Maneuver;
import baritone.brain.BrainActionSpace.StyleSliders;
import baritone.brain.BrainSchema.ObsSnapshot;
import baritone.brain.BrainSchema.ThreatFeature;
import baritone.brain.BrainTrajectoryRecorder.StepRewardCalculator;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class BedwarsBrainTest {

    private static InventorySummary emptyInv() {
        return new InventorySummary(0, 0, 0, 0, 0, 0, 0, 1, 0, 0, 0, 7.0, 0, 0, 0, 0, false, false);
    }

    private static ObsSnapshot sampleSnapshot(InventorySummary inv, boolean homeAlive, double defRatio, double homeDist, boolean threatClose, boolean enemyNearHome) {
        List<ThreatFeature> threats = threatClose
                ? List.of(new ThreatFeature(true, 4.2, 0.0, 1.0, 0.4, 0.85))
                : List.of();
        return new ObsSnapshot(
                20.0f, 0.0f, 20, true, false, false, false,
                0.2, 0.0, 0, 16.0, 32.0, inv.offhandHasBridgeBlock(), false,
                inv,
                homeAlive, defRatio, homeDist, 0.0, 1.0, 0.0,
                3, 38.0, 0.0, 1.0, 4.0, 1.5,
                26.0, 1.0, 0.0, 48.0, 0.0, 1.0,
                threats, 6.0, 0.125, 0, 10, true, homeDist <= 14.0, enemyNearHome
        );
    }

    @Test
    public void testBedDefenseAndBlockInOrdering() {
        List<DefensePlacement> butterfly = BedwarsTacticalHelper.buildHomeBedDefensePlan(
                10, 64, 10, 10, 64, 11, true, (x, y, z) -> false
        );
        assertTrue(butterfly.size() > 10);
        Set<String> coords = new HashSet<>();
        int maxLayerSeen = 0;
        for (DefensePlacement dp : butterfly) {
            assertFalse("Must never overwrite head cell", dp.x() == 10 && dp.y() == 64 && dp.z() == 10);
            assertFalse("Must never overwrite foot cell", dp.x() == 10 && dp.y() == 64 && dp.z() == 11);
            assertTrue("Unique placement coordinate", coords.add(dp.x() + "," + dp.y() + "," + dp.z()));
            assertTrue("Bottom-up layer order", dp.layer() >= maxLayerSeen);
            maxLayerSeen = dp.layer();
        }

        // Block-in facing North bed (z=90 vs player at z=94 -> dzBed = -4, must leave z=93 clear!)
        List<DefensePlacement> blockIn = BedwarsTacticalHelper.buildBlockInPlan(10, 64, 94, 10, 90, (x, y, z) -> false);
        assertEquals(7, blockIn.size()); // 3 sides * 2 height + 1 roof
        for (DefensePlacement dp : blockIn) {
            assertFalse("Direction facing bed must stay open", dp.x() == 10 && dp.z() == 93);
        }

        FireballJumpPlan fbGround = BedwarsTacticalHelper.computeFireballJumpPlan(45.0f, true, 0);
        assertEquals(-135.0f, fbGround.launchYaw(), 0.5f);
        assertTrue(fbGround.readyToJump());
        assertFalse(fbGround.readyToFire());

        FireballJumpPlan fbAir = BedwarsTacticalHelper.computeFireballJumpPlan(45.0f, false, 2);
        assertTrue(fbAir.readyToFire());
    }

    @Test
    public void testShopPlannerLoreParsingAndPurchasePriority() {
        CostSpec iron = BedwarsShopPlanner.parseCostFromLore("Cost: 4 Iron\nRight click to buy!");
        assertNotNull(iron);
        assertEquals(Currency.IRON, iron.currency());
        assertEquals(4, iron.amount());

        CostSpec dia = BedwarsShopPlanner.parseCostFromLore("Tier I\nCost: 4 Diamonds");
        assertNotNull(dia);
        assertEquals(Currency.DIAMOND, dia.currency());
        assertEquals(4, dia.amount());

        InventorySummary lowGear = new InventorySummary(20, 8, 0, 0, 4, 0, 4, 1, 0, 0, 0, 7.0, 0, 0, 0, 0, false, false);
        List<ShopSlotInfo> menuSlots = List.of(
                new ShopSlotInfo(10, "white_wool", "Wool", "Cost: 4 Iron", 16),
                new ShopSlotInfo(11, "stone_sword", "Stone Sword", "Cost: 10 Iron", 1),
                new ShopSlotInfo(12, "chainmail_boots", "Permanent Chainmail Armor", "Cost: 40 Iron", 1)
        );
        ShopClickDecision buy = BedwarsShopPlanner.chooseNextShopClick(
                menuSlots, ShopPreset.ESSENTIALS, lowGear, false, true, 0.4, 30.0, false
        );
        assertNotNull(buy);
        assertFalse(buy.categorySwitch());
        assertEquals(10, buy.slotIndex()); // Need wool urgently when < 16!

        InventorySummary richDiamonds = new InventorySummary(16, 4, 4, 0, 32, 0, 32, 2, 1, 1, 0, 12.0, 0, 0, 0, 0, false, false);
        List<ShopSlotInfo> upgSlots = List.of(
                new ShopSlotInfo(11, "iron_sword", "Sharpened Swords", "Cost: 4 Diamonds", 1),
                new ShopSlotInfo(12, "iron_chestplate", "Reinforced Armor", "Cost: 2 Diamonds", 1)
        );
        ShopClickDecision upgBuy = BedwarsShopPlanner.chooseNextShopClick(
                upgSlots, ShopPreset.UPGRADES, richDiamonds, true, true, 0.8, 30.0, false
        );
        assertNotNull(upgBuy);
        assertEquals(11, upgBuy.slotIndex());
    }

    @Test
    public void testBrainSchemaDimensionsAndBearings() {
        assertEquals(BrainSchema.OBS_DIM, BrainSchema.featureNames().size());
        ObsSnapshot snap = sampleSnapshot(emptyInv(), true, 0.5, 20.0, true, false);
        float[] vec = BrainSchema.encodeObservation(snap);
        assertEquals(72, vec.length);
        for (float v : vec) {
            assertTrue("All encoded features must be finite", Float.isFinite(v));
        }
        assertEquals(1.0f, vec[0], 1e-4f);  // health 20 / 20
        assertEquals(1.0f, vec[30], 1e-4f); // home_bed_alive
        assertEquals(1.0f, vec[48], 1e-4f); // enemy0_present

        // Player yaw = 0 faces +Z. Target straight +Z -> sin=0, cos=1. Target east (-X) -> sin>0, cos~0.
        double[] ahead = BrainSchema.relativeBearingSinCos(0.0, 10.0, 0.0f);
        assertEquals(0.0, ahead[0], 1e-5);
        assertEquals(1.0, ahead[1], 1e-5);
        double[] left = BrainSchema.relativeBearingSinCos(10.0, 0.0, 0.0f);
        assertTrue(left[0] < -0.99);
    }

    @Test
    public void testActionMaskingAndAntiChatterCommitter() {
        InventorySummary broke = new InventorySummary(2, 0, 2, 0, 0, 0, 0, 1, 0, 0, 0, 7.0, 0, 0, 0, 0, false, false);
        ObsSnapshot s = sampleSnapshot(broke, true, 0.2, 40.0, false, true);
        boolean[] mask = BrainActionSpace.computeValidManeuverMask(s);
        assertTrue(mask[Maneuver.HOLD_HOME_GEN.ordinal()]);
        assertFalse("Need blocks >= 4 to rush", mask[Maneuver.RUSH_NEAREST_BED.ordinal()]);
        assertFalse("Must not tactical-void while carrying diamonds", mask[Maneuver.TACTICAL_VOID_RECALL.ordinal()]);
        assertFalse("Cannot fireball jump with 0 fireballs", mask[Maneuver.FIREBALL_JUMP.ordinal()]);

        // Anti-chatter holds decision cleanly across minHoldTicks
        ObsSnapshot calm = sampleSnapshot(new InventorySummary(16, 4, 0, 0, 32, 0, 32, 2, 0, 0, 0, 12.0, 0, 0, 0, 0, false, false),
                true, 0.8, 30.0, false, false);
        boolean[] calmMask = BrainActionSpace.computeValidManeuverMask(calm);
        AntiChatterCommitter committer = new AntiChatterCommitter();
        BrainDecision d1 = new BrainDecision(Maneuver.RUSH_WEAKEST_BED, StyleSliders.BALANCED, CombatMode.BALANCED, 0.5f, 0.9f);
        assertEquals(Maneuver.RUSH_WEAKEST_BED, committer.filter(d1, calm, calmMask, 16, false).maneuver());

        BrainDecision flicker = new BrainDecision(Maneuver.FARM_DIAMONDS, StyleSliders.BALANCED, CombatMode.BALANCED, 0.4f, 0.6f);
        assertEquals(Maneuver.RUSH_WEAKEST_BED, committer.filter(flicker, calm, calmMask, 16, false).maneuver());

        // Close melee interrupt immediately overrides hold timer!
        ObsSnapshot ambush = sampleSnapshot(new InventorySummary(16, 4, 0, 0, 32, 0, 32, 2, 0, 0, 0, 12.0, 0, 0, 0, 0, false, false),
                true, 0.8, 30.0, true, false);
        boolean[] ambushMask = BrainActionSpace.computeValidManeuverMask(ambush);
        BrainDecision engage = new BrainDecision(Maneuver.ENGAGE_THREAT_COMBAT, StyleSliders.BALANCED, CombatMode.VOID_KNOCKBACK, 0.8f, 0.95f);
        assertEquals(Maneuver.ENGAGE_THREAT_COMBAT, committer.filter(engage, ambush, ambushMask, 16, false).maneuver());
    }

    @Test
    public void testNeuralPolicyEngineExactForwardAndMasking() {
        int obsDim = BrainSchema.OBS_DIM;
        int hDim = 2;

        float[] obsMean = new float[obsDim];
        float[] obsStd = new float[obsDim];
        for (int i = 0; i < obsDim; i++) {
            obsStd[i] = 1.0f;
        }
        obsMean[0] = 0.5f;
        obsStd[0] = 0.5f;

        // Encoder: 72 -> 2 with identity-like weights on dim 0 & 1 + LayerNorm + SiLU
        float[] encW = new float[hDim * obsDim];
        encW[0] = 1.5f;           // row 0 col 0
        encW[obsDim + 1] = -1.0f; // row 1 col 1
        float[] encB = new float[]{0.2f, -0.3f};
        float[] lnW = new float[]{1.0f, 1.0f};
        float[] lnB = new float[]{0.0f, 0.1f};

        // GRU (input=2, hidden=2 -> 3*2=6 rows)
        float[] wIh = new float[]{0.2f, -0.1f, 0.3f, 0.4f, 0.5f, -0.2f, -0.3f, 0.25f, 0.6f, 0.1f, -0.4f, 0.3f};
        float[] bIh = new float[]{0.1f, -0.1f, 0.05f, 0.02f, -0.05f, 0.15f};
        float[] wHh = new float[]{0.1f, 0.2f, -0.15f, 0.3f, 0.25f, -0.1f, 0.05f, 0.2f, -0.3f, 0.15f, 0.4f, -0.2f};
        float[] bHh = new float[]{0.0f, 0.05f, -0.02f, 0.03f, 0.01f, -0.04f};

        // Heads
        float[] mW = new float[BrainActionSpace.NUM_MANEUVERS * hDim];
        mW[1 * hDim + 0] = 28.0f; // top maneuver 1 (SHOP_BUY_ESSENTIALS)
        mW[4 * hDim + 0] = 10.0f; // second highest: maneuver 4 (RUSH_NEAREST_BED)
        float[] mB = new float[BrainActionSpace.NUM_MANEUVERS];

        float[] sW = new float[]{1.0f, 0.0f, 0.0f, 1.0f, -1.0f, 1.0f, 0.5f, 0.5f};
        float[] sB = new float[]{0.0f, 0.0f, 0.0f, 0.0f};
        float[] cW = new float[BrainActionSpace.NUM_COMBAT_MODES * hDim];
        cW[2 * hDim + 0] = 2.0f;
        float[] cB = new float[BrainActionSpace.NUM_COMBAT_MODES];
        float[] vW = new float[]{1.2f, -0.7f};
        float[] vB = new float[]{0.5f};

        JsonObject bundle = new JsonObject();
        bundle.addProperty("schema_version", BrainSchema.SCHEMA_VERSION);
        bundle.addProperty("obs_dim", obsDim);
        bundle.addProperty("name", "unit-test-net");
        bundle.addProperty("activation", "silu");
        bundle.add("obs_mean", encodeB64Tensor(obsMean));
        bundle.add("obs_std", encodeB64Tensor(obsStd));

        JsonArray encArr = new JsonArray();
        JsonObject l0 = new JsonObject();
        l0.addProperty("out_dim", hDim);
        l0.addProperty("in_dim", obsDim);
        l0.add("weight", encodeB64Tensor(encW));
        l0.add("bias", encodeB64Tensor(encB));
        l0.add("ln_weight", encodeB64Tensor(lnW));
        l0.add("ln_bias", encodeB64Tensor(lnB));
        encArr.add(l0);
        bundle.add("encoder_layers", encArr);

        JsonObject gru = new JsonObject();
        gru.addProperty("input_size", hDim);
        gru.addProperty("hidden_size", hDim);
        gru.add("weight_ih", encodeB64Tensor(wIh));
        gru.add("bias_ih", encodeB64Tensor(bIh));
        gru.add("weight_hh", encodeB64Tensor(wHh));
        gru.add("bias_hh", encodeB64Tensor(bHh));
        bundle.add("gru", gru);

        JsonObject heads = new JsonObject();
        heads.add("maneuver", makeHeadJson(BrainActionSpace.NUM_MANEUVERS, hDim, mW, mB));
        heads.add("sliders", makeHeadJson(BrainActionSpace.NUM_SLIDERS, hDim, sW, sB));
        heads.add("combat", makeHeadJson(BrainActionSpace.NUM_COMBAT_MODES, hDim, cW, cB));
        heads.add("value", makeHeadJson(1, hDim, vW, vB));
        bundle.add("heads", heads);

        NeuralPolicyEngine engine = NeuralPolicyEngine.fromJson(bundle);

        float[] obs = new float[obsDim];
        obs[0] = 1.0f;
        obs[1] = 0.25f;

        boolean[] allAllowed = new boolean[BrainActionSpace.NUM_MANEUVERS];
        java.util.Arrays.fill(allAllowed, true);
        BrainDecision d1 = engine.infer(obs, allAllowed);
        assertEquals(Maneuver.SHOP_BUY_ESSENTIALS, d1.maneuver());
        assertEquals(CombatMode.CRIT_DUEL, d1.combatMode());
        assertTrue(Float.isFinite(d1.valueEstimate()));
        assertTrue("Confidence=" + d1.confidence(), d1.confidence() > 0.5f && d1.confidence() <= 1.0f);

        // Mask out top maneuver 1 -> must select second best (maneuver 4)
        engine.resetHiddenState();
        allAllowed[1] = false;
        BrainDecision dMasked = engine.infer(obs, allAllowed);
        assertEquals(Maneuver.RUSH_NEAREST_BED, dMasked.maneuver());
    }

    @Test
    public void testRuleTeacherAndTrajectoryRecorderJsonl() throws Exception {
        InventorySummary baseRich = new InventorySummary(20, 4, 2, 0, 32, 0, 32, 2, 0, 0, 0, 10.0, 0, 0, 0, 0, false, false);
        ObsSnapshot snapShop = sampleSnapshot(baseRich, true, 0.9, 6.0, false, false);
        boolean[] maskShop = BrainActionSpace.computeValidManeuverMask(snapShop);
        BrainDecision decShop = RuleTeacherPolicy.decide(snapShop, maskShop);
        assertEquals(Maneuver.SHOP_BUY_COMBAT_UPGRADES, decShop.maneuver());

        InventorySummary poor = new InventorySummary(8, 0, 0, 0, 8, 0, 8, 1, 0, 0, 0, 7.0, 0, 0, 0, 0, false, false);
        ObsSnapshot baseThreat = sampleSnapshot(poor, true, 0.9, 45.0, false, true);
        boolean[] maskThreat = BrainActionSpace.computeValidManeuverMask(baseThreat);
        assertEquals(Maneuver.TACTICAL_VOID_RECALL, RuleTeacherPolicy.decide(baseThreat, maskThreat).maneuver());

        StepRewardCalculator rewardCalc = new StepRewardCalculator();
        ObsSnapshot s1 = sampleSnapshot(poor, true, 0.2, 12.0, false, false);
        assertEquals(0.0f, rewardCalc.computeStepReward(s1, false), 1e-5f);
        ObsSnapshot s2 = new ObsSnapshot(
                20f, 0f, 20, true, false, false, false, 0.2, 0.0, 0, 16.0, 32.0, false, false,
                baseRich, true, 0.8, 12.0, 0.0, 1.0, 0.0,
                2, 38.0, 0.0, 1.0, 4.0, 1.5,
                26.0, 1.0, 0.0, 48.0, 0.0, 1.0,
                List.of(), 6.0, 0.125, 0, 10, true, true, false
        );
        float positiveReward = rewardCalc.computeStepReward(s2, false);
        assertTrue("Breaking an enemy bed + defense + upgrades should yield strong positive reward", positiveReward > 5.0f);

        Path tmp = Files.createTempDirectory("pathadol_brain_test");
        BrainTrajectoryRecorder rec = new BrainTrajectoryRecorder(tmp);
        Path outFile = rec.start("unit");
        rec.recordStep(s1, BrainSchema.encodeObservation(s1), maskThreat, decShop, decShop.maneuver(), false, "teacher");
        rec.recordStep(s2, BrainSchema.encodeObservation(s2), maskShop, decShop, decShop.maneuver(), false, "teacher");
        rec.stop();

        List<String> lines = Files.readAllLines(outFile);
        assertEquals(2, lines.size());
        JsonObject parsed = JsonParser.parseString(lines.get(1)).getAsJsonObject();
        assertEquals(BrainSchema.SCHEMA_VERSION, parsed.get("schema").getAsInt());
        assertEquals(BrainSchema.OBS_DIM, parsed.getAsJsonArray("obs").size());
        assertEquals(BrainActionSpace.NUM_MANEUVERS, parsed.getAsJsonArray("mask").size());
        assertTrue(parsed.get("reward").getAsFloat() > 5.0f);
    }

    @Test
    public void testBridgeConfrontationInterruptsRushingInTeacherAndManualModes() {
        InventorySummary rushInv = new InventorySummary(20, 4, 0, 0, 32, 0, 32, 1, 0, 0, 0, 7.0, 0, 0, 0, 0, false, false);
        ObsSnapshot farOnBridge = new ObsSnapshot(
                20.0f, 0.0f, 20, true, true, false, false,
                0.24, 0.0, 0, 16.0, 32.0, false, false,
                rushInv,
                true, 0.6, 28.0, 0.0, -1.0, 0.0,
                3, 24.0, 0.0, 1.0, 4.0, 1.5,
                26.0, 1.0, 0.0, 44.0, 0.0, 1.0,
                List.of(new ThreatFeature(true, 28.0, 0.1, 0.99, 0.0, 0.15)),
                2.0, 0.50, Maneuver.RUSH_NEAREST_BED.ordinal(), 2, true, false, false
        );
        boolean[] farMask = BrainActionSpace.computeValidManeuverMask(farOnBridge);
        assertFalse(RuleTeacherPolicy.isBridgeConfrontation(farOnBridge));
        BrainDecision rushDecision = RuleTeacherPolicy.decide(farOnBridge, farMask);
        assertEquals(Maneuver.RUSH_NEAREST_BED, rushDecision.maneuver());

        ObsSnapshot bridgeThreat = new ObsSnapshot(
                20.0f, 0.0f, 20, true, false, false, false,
                0.15, 0.0, 0, 16.0, 32.0, false, false,
                rushInv,
                true, 0.6, 28.0, 0.0, -1.0, 0.0,
                3, 24.0, 0.0, 1.0, 4.0, 1.5,
                26.0, 1.0, 0.0, 44.0, 0.0, 1.0,
                List.of(new ThreatFeature(true, 11.5, 0.25, 0.96, 0.18, 0.55)),
                2.0, 0.50, Maneuver.RUSH_NEAREST_BED.ordinal(), 2, true, false, false
        );
        boolean[] threatMask = BrainActionSpace.computeValidManeuverMask(bridgeThreat);
        assertTrue(RuleTeacherPolicy.isBridgeConfrontation(bridgeThreat));
        BrainDecision fightDecision = RuleTeacherPolicy.decide(bridgeThreat, threatMask);
        assertEquals(Maneuver.ENGAGE_THREAT_COMBAT, fightDecision.maneuver());
        assertEquals(CombatMode.VOID_KNOCKBACK, fightDecision.combatMode());

        // AntiChatterCommitter must break RUSH hold immediately for bridge confrontation (< 15.5m)
        AntiChatterCommitter committer = new AntiChatterCommitter();
        committer.filter(rushDecision, farOnBridge, farMask, 40, false);
        BrainDecision committed = committer.filter(fightDecision, bridgeThreat, threatMask, 40, false);
        assertEquals(Maneuver.ENGAGE_THREAT_COMBAT, committed.maneuver());

        // Manual #bws field threat interception mirrors the same bridge awareness
        assertTrue(BedwarsProcess.shouldInterceptFieldThreat(12.0, 0.0, 0.50, 2.0, 1.0));
        assertTrue(BedwarsProcess.shouldInterceptFieldThreat(16.0, 0.12, 0.10, 2.5, 1.0));
        assertFalse(BedwarsProcess.shouldInterceptFieldThreat(16.0, 0.01, 0.10, 8.0, 1.0));
        assertFalse(BedwarsProcess.shouldInterceptFieldThreat(11.0, 0.20, 0.60, 1.5, 9.0));
    }

    private static JsonObject encodeB64Tensor(float[] data) {
        ByteBuffer buf = ByteBuffer.allocate(data.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (float v : data) {
            buf.putFloat(v);
        }
        JsonObject obj = new JsonObject();
        obj.addProperty("f32_b64", Base64.getEncoder().encodeToString(buf.array()));
        return obj;
    }

    private static JsonObject makeHeadJson(int outDim, int inDim, float[] w, float[] b) {
        JsonObject o = new JsonObject();
        o.addProperty("out_dim", outDim);
        o.addProperty("in_dim", inDim);
        o.add("weight", encodeB64Tensor(w));
        o.add("bias", encodeB64Tensor(b));
        return o;
    }
}
