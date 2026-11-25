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

package baritone.bedwars;

import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.Rotation;
import baritone.api.utils.input.Input;
import baritone.bedwars.BedwarsDefensePlanner.BedShellScan;
import baritone.bedwars.BedwarsDefensePlanner.BlockKindClassifier;
import baritone.bedwars.BedwarsDefensePlanner.DefenseBlockKind;
import baritone.bedwars.BedwarsDefensePlanner.RayTargetResult;
import baritone.bedwars.BedwarsDefensePlanner.ToolProfile;
import baritone.bedwars.BedwarsMapCore.ArenaBounds;
import baritone.bedwars.BedwarsMapCore.BedPOI;
import baritone.bedwars.BedwarsMapCore.GenPOI;
import baritone.bedwars.BedwarsMapCore.GenType;
import baritone.bedwars.BedwarsMapCore.ShopNPC;
import baritone.bedwars.BedwarsMapCore.SpawnPOI;
import baritone.bedwars.BedwarsMapCore.TeamColor;
import baritone.bedwars.BedwarsMatchState.BedStatus;
import baritone.bedwars.BedwarsMatchState.MatchPhase;
import baritone.bedwars.BedwarsMatchState.ParsedChatOutcome;
import baritone.bedwars.BedwarsLearnedKnowledge.MapKnowledge;
import baritone.bedwars.BedwarsLearnedKnowledge.ServerShopKnowledge;
import baritone.bedwars.BedwarsShopPlanner.BedwarsItemRole;
import baritone.bedwars.BedwarsShopPlanner.CatalogItemEntry;
import baritone.bedwars.BedwarsShopPlanner.CostSpec;
import baritone.bedwars.BedwarsShopPlanner.Currency;
import baritone.bedwars.BedwarsShopPlanner.MenuKind;
import baritone.bedwars.BedwarsShopPlanner.ShopCatalogCache;
import baritone.bedwars.BedwarsShopPlanner.ShopClickDecision;
import baritone.bedwars.BedwarsShopPlanner.ShopPreset;
import baritone.bedwars.BedwarsShopPlanner.ShopSlotInfo;
import baritone.bedwars.BedwarsShopPlanner.SlotAffordability;
import baritone.bedwars.BedwarsTacticalHelper.ClutchCandidate;
import baritone.bedwars.BedwarsTacticalHelper.CombatTargetContext;
import baritone.bedwars.BedwarsTacticalHelper.DefensePlacement;
import baritone.bedwars.BedwarsTacticalHelper.FireballThreat;
import baritone.bedwars.BedwarsTacticalHelper.InventorySummary;
import baritone.behavior.InventoryBehavior;
import baritone.behavior.SurvivalBehavior;
import baritone.behavior.look.ForkableRandom;
import baritone.human.BridgeController;
import baritone.human.BridgeController.BridgeSkill;
import baritone.human.BridgeController.BridgeStyle;
import baritone.human.HumanProfile;
import baritone.human.MouseModel;
import baritone.pathing.movement.FallSafetyPolicy;
import baritone.process.CombatProcess;
import baritone.process.CombatProcess.SpacingAction;
import baritone.process.CombatProcess.ThreatSnapshot;
import baritone.utils.InputOverrideHandler;
import baritone.utils.PlayerMovementInput;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Unit tests for Bedwars tactical planners, NPC/shop classification, and legit PvP math helpers.
 */
public class BedwarsTacticsTest {

    @Test
    public void generatorRecoveryReplaysStuckApproachAndWaitsForKnownSpawn() {
        GeneratorProgress progress = new GeneratorProgress();
        var target = new net.minecraft.core.BlockPos(39, 55, -39);
        assertFalse(progress.isStalled(target, 3905, 3.00, false, 0, 32));
        assertFalse(progress.isStalled(target, 3939, 2.81, false, 0, 32));
        assertTrue(progress.isStalled(target, 3940, 2.82, false, 0, 32));

        progress.reset();
        int wait = GeneratorProgress.waitBudgetTicks(false, 27.0);
        assertFalse(progress.isStalled(target, 100, 0.0, true, 0, wait));
        assertFalse(progress.isStalled(target, 699, 0.0, true, 0, 60));
        assertFalse(progress.isStalled(target, 700, 0.0, true, 1, 60));
        assertTrue(progress.isStalled(target, 760, 0.0, true, 1, 60));
    }

    @Test
    public void testShopNpcRoleClassificationAndHologramUpgrade() {
        assertEquals("UPGRADES", BedwarsMapCore.classifyShopNpcRole("SOLO UPGRADES"));
        assertEquals("UPGRADES", BedwarsMapCore.classifyShopNpcRole("Team Upgrades & Traps"));
        assertEquals("ITEM_SHOP", BedwarsMapCore.classifyShopNpcRole("ITEM SHOP"));
        assertEquals("ITEM_SHOP", BedwarsMapCore.classifyShopNpcRole("Quick Buy Shopkeeper"));
        assertEquals("UNKNOWN", BedwarsMapCore.classifyShopNpcRole("Villager"));

        ArenaBounds bounds = BedwarsMapCore.createCenteredBounds(0, 0, 0, 64, 128);
        BedwarsMapCore core = new BedwarsMapCore(bounds, 0.0, 0.0, 0, 128);
        core.addOrUpdateShopNpc(new ShopNPC("Villager", 10, 65, 10));
        assertEquals("UNKNOWN", BedwarsMapCore.classifyShopNpcRole(core.getShopNpcs().get(0).label()));

        // Overhead hologram upgrade replaces generic label at same stall without duplicating NPC entry
        core.addOrUpdateShopNpc(new ShopNPC("SOLO UPGRADES", 10, 66, 10));
        assertEquals(1, core.getShopNpcs().size());
        assertEquals("UPGRADES", BedwarsMapCore.classifyShopNpcRole(core.getShopNpcs().get(0).label()));

        // Explicit learned label overrides previous guess
        core.addOrUpdateShopNpc(new ShopNPC("ITEM_SHOP:LEARNED", 10, 65, 10));
        assertEquals(1, core.getShopNpcs().size());
        assertEquals("ITEM_SHOP", BedwarsMapCore.classifyShopNpcRole(core.getShopNpcs().get(0).label()));
    }

    @Test
    public void testShopMenuClassifierAndLoreCostPatterns() {
        assertEquals(MenuKind.UPGRADE_SHOP, BedwarsShopPlanner.classifyOpenMenu("Upgrades & Traps", List.of()));
        assertEquals(MenuKind.ITEM_SHOP, BedwarsShopPlanner.classifyOpenMenu("Quick Buy", List.of()));

        // Slot-content fallback when packet/screen title is customized
        List<ShopSlotInfo> upgradeSlots = List.of(
                new ShopSlotInfo(10, "iron_sword", "Sharpened Swords", "Cost: 4 Diamonds", 1),
                new ShopSlotInfo(11, "leather_chestplate", "Reinforced Armor I", "Cost: 2 Diamonds", 1)
        );
        assertEquals(MenuKind.UPGRADE_SHOP, BedwarsShopPlanner.classifyOpenMenu("Menu", upgradeSlots));

        List<ShopSlotInfo> shopSlots = List.of(
                new ShopSlotInfo(19, "white_wool", "Wool", "4x Iron\n16 Wool", 16),
                new ShopSlotInfo(20, "stone_sword", "Stone Sword", "Iron: 10", 1)
        );
        assertEquals(MenuKind.ITEM_SHOP, BedwarsShopPlanner.classifyOpenMenu("Vendor", shopSlots));

        CostSpec altIron = BedwarsShopPlanner.parseCostFromLore("4x Iron");
        assertNotNull(altIron);
        assertEquals(Currency.IRON, altIron.currency());
        assertEquals(4, altIron.amount());

        CostSpec altDiamond = BedwarsShopPlanner.parseCostFromLore("Diamonds: 6");
        assertNotNull(altDiamond);
        assertEquals(Currency.DIAMOND, altDiamond.currency());
        assertEquals(6, altDiamond.amount());
    }

    @Test
    public void testShopCooldownCurrencyUnlock() {
        InventorySummary empty = new InventorySummary(6, 1, 0, 0, 8, 0, 8, 1, 0, 0, 0, 12.0, 0, 0, 0, 0, false, false);
        // Active during cooldown window when currency hasn't meaningfully increased
        assertTrue(BedwarsProcess.isShopCooldownActive(105L, 180L, empty, 6, 1, 0, 0));
        // Unlocks after cooldown tick expires
        assertFalse(BedwarsProcess.isShopCooldownActive(180L, 180L, empty, 6, 1, 0, 0));
        // Unlocks early if +12 iron collected from forge
        InventorySummary moreIron = new InventorySummary(18, 1, 0, 0, 8, 0, 8, 1, 0, 0, 0, 12.0, 0, 0, 0, 0, false, false);
        assertFalse(BedwarsProcess.isShopCooldownActive(110L, 180L, moreIron, 6, 1, 0, 0));
    }

    @Test
    public void testDefenseShellScanAndRaycastTargetAndClosestPoint() {
        // 1-layer wool covering bed (head=0,64,0 foot=0,64,1) with +X side open
        BlockKindClassifier partialCover = (x, y, z) -> {
            boolean isBedCell = (x == 0 && y == 64 && (z == 0 || z == 1));
            if (isBedCell) {
                return DefenseBlockKind.AIR;
            }
            if (y == 65 && x == 0 && (z == 0 || z == 1)) {
                return DefenseBlockKind.WOOD;
            }
            if (x == -1 && y == 64 && (z == 0 || z == 1)) {
                return DefenseBlockKind.WOOL;
            }
            return DefenseBlockKind.AIR;
        };

        BedShellScan scan = BedwarsDefensePlanner.scanBedDefenseShell(
                0, 64, 0,
                0, 64, 1,
                3,
                new ToolProfile(1, 0, 0),
                partialCover
        );
        assertEquals(1, scan.layersDetected());
        assertTrue(scan.innerSolidShellBlocks() >= 3);

        // From +X side, raycast finds direct exposed bed cell without breaking wool on -X side
        RayTargetResult directHit = BedwarsDefensePlanner.findWeakestRaycastTarget(
                2.5, 65.2, 0.5,
                0, 64, 0,
                0, 64, 1,
                4.5,
                ToolProfile.bareHand(),
                partialCover
        );
        assertNotNull(directHit);
        assertTrue("Exposed +X face should hit bed directly", directHit.mineIsBed());

        // From -X side, raycast targets covering wool block first
        RayTargetResult blockerHit = BedwarsDefensePlanner.findWeakestRaycastTarget(
                -2.6, 65.0, 0.5,
                0, 64, 0,
                0, 64, 1,
                4.5,
                new ToolProfile(1, 0, 0),
                partialCover
        );
        assertNotNull(blockerHit);
        assertFalse(blockerHit.mineIsBed());
        assertEquals(-1, blockerHit.mineX());

        double[] pt = BedwarsDefensePlanner.closestPointOnUnitBox(10.0, 70.0, 10.0, 0, 64, 0, 0.15);
        assertEquals(0.85, pt[0], 1e-6);
        assertEquals(64.85, pt[1], 1e-6);
        assertEquals(0.85, pt[2], 1e-6);
    }

    @Test
    public void testSmartBlockInPlanAndVoidClutchAndFireball() {
        // Player at (2, 64, 0) standing on island floor (y=63 solid), bed head at (0,64,0) foot at (0,64,1)
        // Expected: (1, 64, 0) is bedAdjacent so excluded from block-in walls; rear & flank walls + roof planned
        List<DefensePlacement> cocoon = BedwarsTacticalHelper.buildSmartBlockInPlan(
                2, 64, 0,
                0, 0,
                0, 1,
                90.0f,
                (x, y, z) -> y == 63,
                Set.of()
        );
        assertFalse(cocoon.isEmpty());
        for (DefensePlacement dp : cocoon) {
            assertFalse("Block-in must not place in bed-adjacent gap (1,64,0)",
                    dp.x() == 1 && dp.y() == 64 && dp.z() == 0);
        }

        // Void clutch trigger & placement ranking
        assertTrue(BedwarsTacticalHelper.shouldTriggerVoidClutch(false, false, false, 1.5f, -0.35, 18.0, false, false));
        assertFalse("Never clutch during deliberate #bws recall",
                BedwarsTacticalHelper.shouldTriggerVoidClutch(false, false, false, 2.5f, -0.45, 18.0, false, true));

        List<ClutchCandidate> catches = BedwarsTacticalHelper.rankClutchPlacements(
                10.5, 64.1, 10.5,
                0.22, -0.25, 0.0,
                (x, y, z) -> (x == 9 && y == 63 && z == 10),
                4.5
        );
        assertFalse(catches.isEmpty());
        assertEquals(10, catches.get(0).placeX());
        assertEquals(63, catches.get(0).placeY());
        assertEquals(10, catches.get(0).placeZ());

        // Below bridge top, wall-clutch catches under-foot against deeper cliff block without intersecting feet
        List<ClutchCandidate> wallCatches = BedwarsTacticalHelper.rankClutchPlacements(
                10.5, 63.6, 10.5,
                0.22, -0.25, 0.0,
                (x, y, z) -> (x == 9 && (y == 62 || y == 63) && z == 10),
                4.5
        );
        assertFalse(wallCatches.isEmpty());
        assertEquals(10, wallCatches.get(0).placeX());
        assertEquals(62, wallCatches.get(0).placeY());

        // Fireball deflect prediction
        FireballThreat threat = BedwarsTacticalHelper.evaluateIncomingFireball(
                14.0, 65.5, 10.0,
                -0.9, 0.0, 0.0,
                10.0, 65.62, 10.0,
                18.0, 65.62, 10.0,
                true
        );
        assertNotNull(threat);
        assertTrue(threat.dist() <= 5.0);
        assertNull(BedwarsTacticalHelper.evaluateIncomingFireball(
                30.0, 65.5, 10.0,
                0.9, 0.0, 0.0,
                10.0, 65.62, 10.0,
                0.0, 0.0, 0.0,
                false
        ));
    }

    @Test
    public void testAutoSwapValidBlocksAndPvpHelpers() {
        assertTrue(InventoryBehavior.isValidAutoSwapBlockPath("white_wool", false));
        assertTrue(InventoryBehavior.isValidAutoSwapBlockPath("oak_planks", false));
        assertTrue(InventoryBehavior.isValidAutoSwapBlockPath("end_stone", false));
        assertFalse(InventoryBehavior.isValidAutoSwapBlockPath("tnt", false));
        assertFalse(InventoryBehavior.isValidAutoSwapBlockPath("obsidian", false));
        assertFalse(InventoryBehavior.isValidAutoSwapBlockPath("red_bed", false));
        assertFalse(InventoryBehavior.isValidAutoSwapBlockPath("glass", false));

        // Eye-level aim stays flat horizontally when eyeY sits inside [minY+0.15, maxY-0.15]
        Vec3 eyePos = new Vec3(0.0, 65.62, 0.0);
        AABB enemyBox = new AABB(2.4, 64.0, -0.3, 3.0, 65.8, 0.3);
        Vec3 aim = CombatProcess.computeReachOptimalAimPoint(eyePos, enemyBox, Vec3.ZERO, 1.0, 0.16);
        assertEquals("Eye-level Y avoids diagonal hypotenuse reach shrinkage", 65.62, aim.y, 1e-6);

        // HurtTime-synced W-tap / S-tap resets only at hurtTime >= 9
        assertEquals(SpacingAction.NONE, CombatProcess.chooseHurtTimeSpacingReset(6, 2.5, true, false));
        assertEquals(SpacingAction.S_TAP, CombatProcess.chooseHurtTimeSpacingReset(9, 2.15, true, false));
        assertEquals(SpacingAction.W_TAP, CombatProcess.chooseHurtTimeSpacingReset(10, 2.65, true, false));
        assertEquals(SpacingAction.NONE, CombatProcess.chooseHurtTimeSpacingReset(10, 2.98, false, false));

        // JumpReset safety & trigger
        assertTrue(CombatProcess.shouldTriggerJumpReset(9, true, true, false, 3.2, false));
        assertFalse("Block JumpReset when void edge is directly behind in Bedwars",
                CombatProcess.shouldTriggerJumpReset(9, true, true, false, 1.8, false));

        // HitSelect approach gate & legacy 1.8 contract preservation
        assertFalse(CombatProcess.shouldHitSelectEngage(0, 0, true, 3.1, 1, 5));
        assertTrue(CombatProcess.shouldHitSelectEngage(0, 0, true, 3.1, 5, 5));
        assertTrue(CombatProcess.shouldHitSelectEngage(1, 0, true, 3.1, 1, 5));
        assertTrue(CombatProcess.shouldHitSelect18(0, 2.6, false));
        assertFalse(CombatProcess.shouldHitSelect18(6, 2.6, false));
        assertTrue(CombatProcess.shouldHitSelect18(6, 2.6, true));

        // Shield facing angle check (defender facing attacker -> blocking us)
        assertTrue(CombatProcess.isShieldBlockingUs(
                new Vec3(0.0, 64.0, 0.0),
                new Vec3(3.0, 64.0, 0.0),
                new Vec3(-1.0, 0.0, 0.0)
        ));
        assertFalse(CombatProcess.isShieldBlockingUs(
                new Vec3(0.0, 64.0, 0.0),
                new Vec3(3.0, 64.0, 0.0),
                new Vec3(1.0, 0.0, 0.0)
        ));
    }

    @Test
    public void testPikaShopClassificationAndLoreFiltering() {
        assertEquals(MenuKind.ITEM_SHOP, BedwarsShopPlanner.classifyOpenMenu("Shop - Quickbuy", List.of()));
        assertEquals(MenuKind.UNKNOWN, BedwarsShopPlanner.classifyOpenMenu("Go to new game", List.of()));
        assertTrue(BedwarsShopPlanner.isNonShopUiTitle("Go to new game"));

        List<ShopSlotInfo> pikaSlots = List.of(
                new ShopSlotInfo(2, "terracotta", "Blocks", "► Click to open", 1),
                new ShopSlotInfo(19, "white_wool", "Wool", "Costs: 4 Iron Ingots\n► Click to buy", 16),
                new ShopSlotInfo(20, "stone_sword", "Stone Sword", "Costs: 10 Iron Ingots\n► You can not afford this", 1),
                new ShopSlotInfo(21, "shears", "Permanent Shears", "Costs: 20 Iron Ingots\n► You already own this", 1),
                new ShopSlotInfo(44, "blaze_powder", "Manage Hotbar", "Click to open", 1)
        );
        InventorySummary richInv = new InventorySummary(24, 0, 0, 0, 0, 0, 0, 1, 0, 0, 0, 0.0, 0, 0, 0, 0, false, false);
        ShopClickDecision buyWool = BedwarsShopPlanner.chooseNextShopClick(
                pikaSlots, ShopPreset.ESSENTIALS, richInv, false, true, 0.0, 40.0, false
        );
        assertNotNull(buyWool);
        assertEquals(19, buyWool.slotIndex());

        // When wool is already stocked and remaining items are "You can not afford this" / "already own" / "Click to open",
        // planner returns null instead of repeatedly clicking unaffordable slots or tab headers
        InventorySummary fullWoolInv = new InventorySummary(24, 0, 0, 0, 48, 0, 48, 1, 0, 0, 0, 0.0, 0, 0, 0, 0, false, false);
        assertNull(BedwarsShopPlanner.chooseNextShopClick(
                pikaSlots, ShopPreset.COMBAT, fullWoolInv, false, true, 0.0, 40.0, true
        ));
    }

    @Test
    public void testBedColorVotingHomeForgeUpgradeAndUrgentClutchTurn() {
        // Decorative gray terracotta around GREEN_BED must not mislabel home bed GRAY
        Map<TeamColor, Integer> votes = new EnumMap<>(TeamColor.class);
        votes.put(TeamColor.GRAY, 5);
        assertEquals(TeamColor.GREEN, BedwarsMapCore.selectBedTeamFromVotes(votes, TeamColor.GREEN, false, false));

        // Actual BLUE wool outweighs decorative terracotta on RED_BED fallback
        Map<TeamColor, Integer> blueVotes = new EnumMap<>(TeamColor.class);
        blueVotes.put(TeamColor.GRAY, 5);
        blueVotes.put(TeamColor.BLUE, 5);
        assertEquals(TeamColor.BLUE, BedwarsMapCore.selectBedTeamFromVotes(blueVotes, TeamColor.RED, true, false));

        // Spawn-derived HOME_FORGE fallback gets upgraded in-place when ingot drop is indexed
        BedwarsMapCore core = new BedwarsMapCore(BedwarsMapCore.createCenteredBounds(0, 0, 0, 64, 128), 0.0, 0.0, 0, 128);
        core.addOrUpgradeHomeForge(new GenPOI(GenType.HOME_FORGE, 2, 65, 2, "Team Forge (Home Spawn)"));
        assertEquals(1, core.getGens().size());
        core.addOrUpgradeHomeForge(new GenPOI(GenType.HOME_FORGE, -2, 65, 5, "Team Forge (Ingot)"));
        assertEquals(1, core.getGens().size());
        assertEquals("Team Forge (Ingot)", core.getGens().get(0).label());

        // Urgent clutch turns 120 deg smoothly within 4 ticks with GCD quantization
        MouseModel mouse = new MouseModel(new ForkableRandom(123L));
        HumanProfile pro = HumanProfile.preset("pro");
        Rotation cur = new Rotation(0.0f, 10.0f);
        Rotation clutchGoal = new Rotation(120.0f, 75.0f);
        for (int t = 0; t < 4; t++) {
            cur = mouse.step(cur, clutchGoal, true, false, pro, 0.5, false, true);
        }
        assertTrue("Urgent clutch turn must converge within 4 ticks",
                MouseModel.angularDistance(cur, clutchGoal) < 4.0f);
    }

    @Test
    public void testWaitingLobbyDetectionAndLegitCombatGates() {
        // Skycrypt waiting-lobby glass cage at y=113 with beds far below, barrier lobby item, and countdown chat
        assertTrue(BedwarsArenaMemory.isLikelyWaitingLobby(113, 65, true, false, false, true, false));
        assertTrue(BedwarsArenaMemory.isLikelyWaitingLobby(113, null, true, false, false, false, false));
        assertTrue(BedwarsArenaMemory.isLikelyWaitingLobby(66, 65, false, false, true, false, false));
        assertTrue(BedwarsArenaMemory.isLikelyWaitingLobby(113, 65, false, false, false, false, false));

        // Active match after teleport to island (or mid-match skybridge with real wool/sword gear)
        assertFalse(BedwarsArenaMemory.isLikelyWaitingLobby(66, 65, false, true, false, false, true));
        assertFalse(BedwarsArenaMemory.isLikelyWaitingLobby(113, 65, false, true, false, false, false));
        // Even if scoreboard/chat waiting heuristics lingered or false-triggered, active Bedwars gear unlocks gameplay
        assertFalse(BedwarsArenaMemory.isLikelyWaitingLobby(66, 65, false, true, true, false, false));
        assertFalse(BedwarsArenaMemory.isLikelyWaitingLobby(66, 65, false, true, false, true, false));

        // Pure ingot forge selector: stray single ingot >12 blocks from home anchor is rejected,
        // while a multi-ingot forge cluster beats single stragglers
        BedwarsArenaMemory.ForgeIngotDrop strayFar = new BedwarsArenaMemory.ForgeIngotDrop(14.5, 65.0, 0.5, 14, 65, 0, 1, 196);
        assertTrue(BedwarsArenaMemory.selectBestForgeIngotCandidate(java.util.List.of(strayFar)) == null);

        BedwarsArenaMemory.ForgeIngotDrop strayNear = new BedwarsArenaMemory.ForgeIngotDrop(10.0, 65.0, 0.0, 10, 65, 0, 1, 100);
        BedwarsArenaMemory.ForgeIngotDrop forgeDropA = new BedwarsArenaMemory.ForgeIngotDrop(-4.0, 65.0, -4.0, -4, 65, -4, 1, 36);
        BedwarsArenaMemory.ForgeIngotDrop forgeDropB = new BedwarsArenaMemory.ForgeIngotDrop(-4.6, 65.2, -3.5, -5, 65, -4, 2, 41);
        BedwarsArenaMemory.ForgeIngotDrop bestCluster = BedwarsArenaMemory.selectBestForgeIngotCandidate(
                java.util.List.of(strayNear, strayFar, forgeDropA, forgeDropB)
        );
        assertTrue(bestCluster != null && bestCluster.blockX() == -5 && bestCluster.blockZ() == -4);

        BedwarsArenaMemory.ForgeIngotDrop stackedAt13 = new BedwarsArenaMemory.ForgeIngotDrop(13.0, 65.0, 0.0, 13, 65, 0, 4, 169);
        BedwarsArenaMemory.ForgeIngotDrop selectedStacked = BedwarsArenaMemory.selectBestForgeIngotCandidate(
                java.util.List.of(stackedAt13)
        );
        assertTrue(selectedStacked != null && selectedStacked.count() == 4);

        // Legit attack gate must reject BlockHitResult (e.g. waiting cage barrier) and MISS hit results
        BlockHitResult solidBlockHit = new BlockHitResult(Vec3.ZERO, Direction.UP, BlockPos.ZERO, false);
        BlockHitResult missBlockHit = BlockHitResult.miss(Vec3.ZERO, Direction.UP, BlockPos.ZERO);
        HitResult genericMiss = new HitResult(Vec3.ZERO) {
            @Override
            public Type getType() {
                return Type.MISS;
            }
        };
        assertFalse(CombatProcess.canDispatchLegitAttack(solidBlockHit, null, null, 2.4, 2.98, true, false));
        assertFalse(CombatProcess.canDispatchLegitAttack(missBlockHit, null, null, 2.4, 2.98, true, false));
        assertFalse(CombatProcess.canDispatchLegitAttack(genericMiss, null, null, 2.4, 2.98, true, false));
        assertFalse(CombatProcess.canDispatchLegitAttack(null, null, null, 3.05, 2.98, true, false));
        assertFalse(CombatProcess.canDispatchLegitAttack(null, null, null, 2.5, 2.98, true, true));
        assertTrue(CombatProcess.canDispatchLegitAttack(null, null, null, 2.5, 2.98, true, false));

        // Exact hitbox alignment must not inflate by 0.05 blocks outside the target AABB
        AABB targetBox = new AABB(-0.3, 0.0, 2.2, 0.3, 1.8, 2.8);
        assertFalse(CombatProcess.isCrosshairOnTarget(
                new Vec3(-0.33, 1.62, 0.0), targetBox, new Rotation(0.0f, 0.0f), 3.0
        ));
        assertTrue(CombatProcess.isCrosshairOnTarget(
                new Vec3(0.0, 1.62, 0.0), targetBox, new Rotation(0.0f, 0.0f), 3.0
        ));

        // 1.8 CPS burst limiter allows natural pairs but blocks 3+ consecutive 1-tick (20 TPS) swings
        assertTrue(CombatProcess.isClassicBurstAllowed(11L, 10L, 2L));
        assertFalse(CombatProcess.isClassicBurstAllowed(12L, 11L, 1L));
        assertTrue(CombatProcess.isClassicBurstAllowed(13L, 11L, 1L));
    }

    @Test
    public void testMatchStateChatScoreboardAndTimers() {
        BedwarsMatchState ms = new BedwarsMatchState();
        ParsedChatOutcome start = ms.onChatMessage("BedWars ► You are now in team Aqua.", 1000L);
        assertEquals(TeamColor.AQUA, start.assignedHomeTeam());
        assertEquals(TeamColor.AQUA, ms.getHomeTeam());
        assertTrue(ms.isMatchStarted());

        ms.updateScoreboard("BEDWARS", List.of(
                "Diamond II in 04:12",
                "R Red: ✔",
                "G Green: 2",
                "Y Yellow: ✗",
                "A Aqua: ✔ YOU"
        ));
        assertEquals(MatchPhase.DIAMOND_II, ms.getNextPhase());
        assertEquals(252, ms.getNextPhaseSeconds());
        assertEquals(BedStatus.ALIVE, ms.getTeamStatus(TeamColor.RED).bedStatus());
        assertEquals(BedStatus.DESTROYED, ms.getTeamStatus(TeamColor.GREEN).bedStatus());
        assertEquals(2, ms.getTeamStatus(TeamColor.GREEN).alivePlayers());
        assertTrue(ms.isTeamEliminated(TeamColor.YELLOW));
        assertEquals(BedStatus.ALIVE, ms.getTeamStatus(TeamColor.AQUA).bedStatus());

        ParsedChatOutcome noMats = ms.onChatMessage("You do not have enough materials to buy this!", 2000L);
        assertTrue(noMats.insufficientMaterials());
        assertTrue(ms.isInsufficientMaterialsCooldown(2500L));
        ms.clearInsufficientMaterialsCooldown();
        assertFalse(ms.isInsufficientMaterialsCooldown(2500L));

        ms.updatePlayerTeam("EnemyGreen", TeamColor.GREEN);
        assertEquals(TeamColor.GREEN, ms.getPlayerTeam("EnemyGreen"));
    }

    @Test
    public void testSingleHomeBedAssignmentAndNoPlaceProtection() {
        BedwarsMapCore core = new BedwarsMapCore(new ArenaBounds(-64, 0, -64, 128, 128, 128), 0.0, 0.0, 20, 120);
        // Simulate corrupted/mislabeled home beds + waiting SpawnPOI near home bed
        core.addBed(new BedPOI(TeamColor.AQUA, 32, 65, 32, 33, 65, 32, true, false));
        core.addBed(new BedPOI(TeamColor.AQUA, -32, 65, -32, -31, 65, -32, true, false));
        core.addBed(new BedPOI(TeamColor.RED, -32, 65, 32, -31, 65, 32, true, false));
        core.addSpawn(new SpawnPOI(TeamColor.AQUA, 34, 65, 32));

        BedPOI chosen = core.assignSingleHomeBed(TeamColor.AQUA, 33, 65, 32);
        assertNotNull(chosen);
        assertEquals(32, chosen.x());
        long homeCount = core.getBeds().stream().filter(BedPOI::isHome).count();
        assertEquals("Strictly one bed may be marked home=true", 1L, homeCount);

        // Bed defense shell adjacent to bed MUST remain placeable even within SpawnPOI proximity
        assertFalse(core.isBlockedForBedDefense(33, 66, 32));
        assertFalse(core.isBlockedForBedDefense(32, 66, 32));
    }

    @Test
    public void testSafeShopCategoryScanAndAffordabilityGuard() {
        InventorySummary poor = new InventorySummary(2, 0, 0, 0, 0, 0, 0, 1, 0, 0, 0, 0.0, 0, 0, 0, 0, false, false);
        ShopSlotInfo tabQuick = new ShopSlotInfo(0, "nether_star", "Quick Buy", "► Click to open", 1);
        ShopSlotInfo tabBlocks = new ShopSlotInfo(1, "terracotta", "Blocks", "► Click to open", 1);
        ShopSlotInfo tabMelee = new ShopSlotInfo(2, "golden_sword", "Melee", "► Click to open", 1);
        ShopSlotInfo manageHotbar = new ShopSlotInfo(44, "blaze_powder", "Manage Hotbar", "► Click to open\nEdit hotbar preferences", 1);
        ShopSlotInfo woolCost = new ShopSlotInfo(19, "white_wool", "Wool", "Costs: 4 Iron\n► You can not afford this", 16);

        assertEquals(BedwarsItemRole.MANAGE_HOTBAR, BedwarsShopPlanner.classifyItemRole(manageHotbar));
        assertFalse(BedwarsShopPlanner.isSafeScannableCategoryTab(manageHotbar));
        assertFalse(BedwarsShopPlanner.isSafeScannableCategoryTab(woolCost));
        assertTrue(BedwarsShopPlanner.isSafeScannableCategoryTab(tabBlocks));
        assertEquals(SlotAffordability.CANNOT_AFFORD,
                BedwarsShopPlanner.evaluateAffordability(woolCost, poor, BedwarsShopPlanner.parseCostFromLore(woolCost.loreText())));

        ShopCatalogCache cache = new ShopCatalogCache();
        List<ShopSlotInfo> menu = List.of(tabQuick, tabBlocks, tabMelee, manageHotbar, woolCost);
        ShopClickDecision d1 = cache.nextScanTabDecision(MenuKind.ITEM_SHOP, menu);
        assertNotNull(d1);
        assertTrue(d1.categorySwitch());
        assertTrue("Scan must NEVER click Manage Hotbar", d1.slotIndex() != 44);
        assertTrue("Scan must NEVER click buyable item slots", d1.slotIndex() != 19);

        ShopClickDecision d2 = cache.nextScanTabDecision(MenuKind.ITEM_SHOP, menu);
        assertNotNull(d2);
        assertTrue(d2.slotIndex() != 44 && d2.slotIndex() != 19 && d2.slotIndex() != d1.slotIndex());
    }

    @Test
    public void testMultiTeamThirdPartyCombatPriorityAndForgeStandY() {
        // Dye inference across classic Bukkit & modern leather RGBs
        assertEquals(TeamColor.GREEN, BedwarsTacticalHelper.inferTeamColorFromDyeRgb(0x00FF00));
        assertEquals(TeamColor.YELLOW, BedwarsTacticalHelper.inferTeamColorFromDyeRgb(0xFFFF55));
        assertEquals(TeamColor.AQUA, BedwarsTacticalHelper.inferTeamColorFromDyeRgb(0x3AB3DA));

        // Attacking Green's living bed: Green defender beats closer Yellow third-party unless Yellow in immediate danger while Green far/invulnerable
        double greenDefAdj = BedwarsTacticalHelper.computeContextualTargetScoreAdjustment(new CombatTargetContext(
                TeamColor.GREEN, TeamColor.AQUA, true, 48.0,
                TeamColor.GREEN, true, 4.0,
                3.4, 0.1, 0,
                true, 3.4, 0
        ));
        double yellowThirdPartyAdj = BedwarsTacticalHelper.computeContextualTargetScoreAdjustment(new CombatTargetContext(
                TeamColor.YELLOW, TeamColor.AQUA, true, 48.0,
                TeamColor.GREEN, true, 3.0,
                2.8, 0.05, 0,
                true, 3.4, 0
        ));
        ThreatSnapshot yellow = new ThreatSnapshot(10, 0, 2.8, 2.8, 0.05, 10.0, 5.0f, 0, 20.0f, false, false, yellowThirdPartyAdj);
        ThreatSnapshot green = new ThreatSnapshot(11, 0, 3.4, 3.4, 0.10, 8.0, 10.0f, 0, 20.0f, false, false, greenDefAdj);
        assertEquals("Green living-bed defender should outrank Yellow third-party",
                1, CombatProcess.selectPrimaryThreatIndex(List.of(yellow, green), -1));

        // Home bed rush priority
        double homeRusherAdj = BedwarsTacticalHelper.computeContextualTargetScoreAdjustment(new CombatTargetContext(
                TeamColor.RED, TeamColor.AQUA, true, 3.0,
                TeamColor.GREEN, true, 35.0,
                6.0, 0.2, 0,
                false, 99.0, 0
        ));
        assertTrue(homeRusherAdj < -2.0);

        // Forge stand Y helper covers solid forge block, floor ingot drop, and floating marker
        assertEquals(66, BedwarsProcess.resolveStandBlockY(0, 65, 0, false, false));
        assertEquals(65, BedwarsProcess.resolveStandBlockY(0, 65, 0, true, false));
        assertEquals(64, BedwarsProcess.resolveStandBlockY(0, 65, 0, true, true));

        // Currency drop after death with empty block reserve must unlock stale shop cooldown immediately
        InventorySummary respawned = new InventorySummary(12, 0, 0, 0, 0, 0, 0, 1, 0, 0, 0, 7.0, 0, 0, 0, 0, false, false);
        assertFalse("Respawn currency drop with 0 blocks must unlock cooldown",
                BedwarsProcess.isShopCooldownActive(110L, 180L, respawned, 21, 2, 0, 0));
    }

    @Test
    public void testBridgeControllerSafetyGappleBlockPvpAndDrops() {
        assertEquals(BridgeStyle.NINJA,
                BridgeController.selectAutoBridgeStyle(BridgeSkill.MEDIUM, false, 12, 4, false, 1.0));
        assertEquals(BridgeStyle.NINJA,
                BridgeController.selectAutoBridgeStyle(BridgeSkill.SWEATY, true, 12, 4, false, 1.0));
        assertEquals(BridgeStyle.SAFE,
                BridgeController.selectAutoBridgeStyle(BridgeSkill.MEDIUM, false, 12, 4, false, 0.20));

        assertTrue(BridgeController.shouldSneakAtProgress(BridgeSkill.MEDIUM, 0.35, false, 42L));
        assertFalse(BridgeController.shouldSneakAtProgress(BridgeSkill.MEDIUM, 0.18, false, 42L));
        assertTrue(BridgeController.shouldPulseRightClick(true, true, true, 0.49, 7L, 1L));

        float pitch = BridgeController.resolveBridgeFacePitch(
                new Vec3(0.5, 65.62, 0.05), 180.0f, new BetterBlockPos(0, 64, 0),
                0.5, -0.005, 0.0, -1.0, 0.0, 78.0f
        );
        assertTrue("Bridge face pitch should stay in clamp [74.5, 85.2], got " + pitch,
                pitch >= 74.5f && pitch <= 85.2f);

        assertTrue(InventoryBehavior.isValidAutoSwapBlockPath("white_wool", false));
        assertFalse(InventoryBehavior.isValidAutoSwapBlockPath("tnt", true));
        assertFalse(InventoryBehavior.isValidAutoSwapBlockPath("obsidian", true));
        assertFalse(InventoryBehavior.isValidAutoSwapBlockPath("ender_chest", false));

        assertEquals(7, FallSafetyPolicy.computeEffectiveMaxFallNoWater(3, true, false, true, false, 20.0f));
        assertEquals(4, FallSafetyPolicy.computeEffectiveMaxFallNoWater(3, true, false, true, false, 8.0f));
        assertEquals(44.0, FallSafetyPolicy.survivalFallDamagePenalty(6, true, false), 1e-6);

        assertTrue("Full-hunger Bedwars gapple healing at 12 HP",
                SurvivalBehavior.shouldHealWithGoldenApple(true, 12.0f, 0.0f, 20, 8.0, false, 0));
        assertTrue("Pre-gap at 20/20 hunger approaching fight",
                SurvivalBehavior.shouldHealWithGoldenApple(true, 17.0f, 0.0f, 20, 8.0, false, 0));
        assertFalse("Must not initiate gapple at 16 HP inside 3.35m melee exchange",
                SurvivalBehavior.shouldHealWithGoldenApple(true, 16.0f, 0.0f, 20, 2.5, false, 0));

        assertFalse("Never stop to dig single placed wool in open block-PvP <= 5.2m",
                CombatProcess.shouldAttemptBarrierBreach(2.5, 4.0, false, false));
        assertFalse(CombatProcess.shouldAttemptBarrierBreach(2.5, 4.0, true, true));
        assertTrue("Allow breaking out when sealed on >= 3 sides without direct threat",
                CombatProcess.shouldAttemptBarrierBreach(2.5, 4.0, false, true));
    }

    @Test
    public void testLearnedKnowledgePersistenceAndOverwriteValidation() throws Exception {
        assertFalse(BedwarsLearnedKnowledge.isPersistableMapName("unknown"));
        assertFalse(BedwarsLearnedKnowledge.isPersistableMapName("fp_53fd786182a2474f"));
        assertTrue(BedwarsLearnedKnowledge.isPersistableMapName("Skycrypt"));
        assertFalse(BedwarsLearnedKnowledge.isSaneCost(new CostSpec(Currency.IRON, 999)));
        assertTrue(BedwarsLearnedKnowledge.isSaneCost(new CostSpec(Currency.GOLD, 12)));

        Path tmp = Files.createTempDirectory("pathadol_learned_test");
        try {
            ShopCatalogCache catalog = new ShopCatalogCache();
            catalog.importLearnedEntries(List.of(
                    new CatalogItemEntry(BedwarsItemRole.BLOCK_WOOL, "Quick Buy", 19, "white_wool", "Wool",
                            new CostSpec(Currency.IRON, 4), 16),
                    new CatalogItemEntry(BedwarsItemRole.SWORD_IRON, "Melee", 21, "iron_sword", "Iron Sword",
                            new CostSpec(Currency.GOLD, 999), 1),
                    new CatalogItemEntry(BedwarsItemRole.ARMOR_IRON, "Armor", 22, "iron_boots", "Iron Armor",
                            new CostSpec(Currency.GOLD, 12), 1)
            ));
            assertEquals(2, catalog.knownItemRoleCount());
            BedwarsLearnedKnowledge.saveServerShop(tmp, "play.pika-network.net", catalog);

            ServerShopKnowledge loadedShop = BedwarsLearnedKnowledge.loadServerShop(tmp, "play.pika-network.net");
            assertEquals(2, loadedShop.items.size());
            assertEquals(12, loadedShop.items.get(BedwarsItemRole.ARMOR_IRON).cost().amount());

            BedwarsLearnedKnowledge.recordNpcObservation(tmp, "play.pika-network.net", "Skycrypt", 10, 65, 10, MenuKind.ITEM_SHOP);
            BedwarsLearnedKnowledge.recordNpcObservation(tmp, "play.pika-network.net", "Skycrypt", 10, 65, 10, MenuKind.UPGRADE_SHOP);
            BedwarsLearnedKnowledge.recordNpcObservation(tmp, "play.pika-network.net", "Skycrypt", 10, 65, 10, MenuKind.UPGRADE_SHOP);
            BedwarsLearnedKnowledge.recordVerifiedForgeObservation(tmp, "play.pika-network.net", "Skycrypt", 14, 65, 14);
            for (int i = 0; i < 3; i++) {
                BedwarsLearnedKnowledge.recordBlockedDefenseOffset(tmp, "play.pika-network.net", "Skycrypt", 1, 0, -1);
            }

            MapKnowledge mk = BedwarsLearnedKnowledge.loadMapKnowledge(tmp, "play.pika-network.net", "Skycrypt");
            assertEquals(1, mk.npcs.size());
            assertEquals("UPGRADE_SHOP", mk.npcs.get(0).role());
            assertEquals(2, mk.npcs.get(0).confirmations());
            assertEquals(1, mk.homeForges.size());
            assertTrue(BedwarsLearnedKnowledge.isBlockedDefenseOffset(mk, 1, 0, -1));
            assertFalse(BedwarsLearnedKnowledge.isBlockedDefenseOffset(mk, 0, 1, 0));
        } finally {
            try (var walk = Files.walk(tmp)) {
                walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                    try { Files.deleteIfExists(p); } catch (Exception ignored) {}
                });
            }
        }
    }

    @Test
    public void testVanillaSneakNormalizationAndInputInvariants() {
        // PlayerMovementInput must match vanilla KeyboardInput.tick(): normalized unit vector, NO extra 0.3x scaling
        Vec2 straight = PlayerMovementInput.computeNormalizedMoveVector(true, false, false, false);
        assertEquals(0.0f, straight.x, 1e-4f);
        assertEquals(1.0f, straight.y, 1e-4f);

        Vec2 diag = PlayerMovementInput.computeNormalizedMoveVector(true, false, true, false);
        double diagLen = Math.hypot(diag.x, diag.y);
        assertEquals(1.0, diagLen, 1e-4);
        assertEquals(Math.sqrt(0.5), diag.x, 1e-4);
        assertEquals(Math.sqrt(0.5), diag.y, 1e-4);

        Vec2 idle = PlayerMovementInput.computeNormalizedMoveVector(false, false, false, false);
        assertEquals(0.0f, idle.x, 1e-4f);
        assertEquals(0.0f, idle.y, 1e-4f);

        // Vanilla invariants: never SPRINT while SNEAKing or moving backward
        Map<Input, Boolean> sneakSprint = new EnumMap<>(Input.class);
        sneakSprint.put(Input.MOVE_FORWARD, true);
        sneakSprint.put(Input.SNEAK, true);
        sneakSprint.put(Input.SPRINT, true);
        InputOverrideHandler.enforceVanillaMovementInvariants(sneakSprint);
        assertFalse("SPR+SNK must never both be active", Boolean.TRUE.equals(sneakSprint.get(Input.SPRINT)));

        Map<Input, Boolean> backSprint = new EnumMap<>(Input.class);
        backSprint.put(Input.MOVE_BACK, true);
        backSprint.put(Input.SPRINT, true);
        InputOverrideHandler.enforceVanillaMovementInvariants(backSprint);
        assertFalse("Must not sprint backward", Boolean.TRUE.equals(backSprint.get(Input.SPRINT)));
    }

    @Test
    public void testStrictClassicSpacingAndSharedVoidClutchBounds() {
        assertTrue(CombatProcess.isClassicSpacingAllowed(11L, 10L, 2L, false));
        assertFalse("Strict legit spacing blocks dt==1 (20 TPS bursts)",
                CombatProcess.isClassicSpacingAllowed(11L, 10L, 2L, true));
        assertTrue(CombatProcess.isClassicSpacingAllowed(12L, 10L, 1L, true));

        assertFalse(BedwarsTacticalHelper.isClutchCapExceeded(60, 52, 0, 8, 5));
        assertTrue("Must stop void clutch after falling > 8 blocks without floor",
                BedwarsTacticalHelper.isClutchCapExceeded(60, 51, 0, 8, 5));
        assertTrue("Must stop void clutch after >= 5 airborne placement tries",
                BedwarsTacticalHelper.isClutchCapExceeded(60, 58, 5, 8, 5));

        ClutchCandidate cand = new ClutchCandidate(11, 63, 10, 10, 63, 10, 1, 0, 0, 1.0);
        assertTrue(BedwarsTacticalHelper.isClutchHitResultValid(10, 63, 10, 1, 0, 0, cand));
        assertTrue(BedwarsTacticalHelper.isClutchHitResultValid(10, 63, 10, 0, 1, 0, cand));
        assertFalse("Reject bottom-face clicks when falling past anchor block",
                BedwarsTacticalHelper.isClutchHitResultValid(10, 63, 10, 0, -1, 0, cand));
        assertFalse("Reject unrelated wall clicks",
                BedwarsTacticalHelper.isClutchHitResultValid(20, 63, 20, 1, 0, 0, cand));

        // Facing +X (11.5 - 10.5 = +1, Z = 0) yields yaw ~ -90 degrees
        assertEquals(-90.0f, BedwarsTacticalHelper.computeClutchSteerYaw(10.5, 10.5, cand), 0.1f);
    }

    @Test
    public void testNoPlainSwordInteractSpamAndSmoothBridgeEdgeSneak() {
        assertFalse(CombatProcess.canBlockHitWithMainHand(null));
        assertFalse(CombatProcess.canBlockHitWithMainHand(net.minecraft.world.item.ItemStack.EMPTY));

        assertFalse("Do not stall MOVE_BACK while crouched on solid footing with blocks in hand",
                baritone.human.BridgeController.shouldPauseBackForEdge(true, false, true, true, true, 0.48, 0.48));
        assertTrue("Still pause MOVE_BACK if not holding blocks near edge",
                baritone.human.BridgeController.shouldPauseBackForEdge(true, true, false, true, true, 0.35, 0.48));
        assertTrue("Still pause MOVE_BACK if un-crouched and not yet looking at goal near edge",
                baritone.human.BridgeController.shouldPauseBackForEdge(true, false, true, true, false, 0.46, 0.48));
    }

    @Test
    public void bridgeMaterialsExcludeLadders() {
        assertFalse(BedwarsTacticalHelper.isBridgeBlockPath("ladder"));
        assertTrue(BedwarsTacticalHelper.isBridgeBlockPath("white_wool"));
        assertTrue(BedwarsTacticalHelper.isBridgeBlockPath("oak_planks"));
        assertTrue(BedwarsTacticalHelper.isBridgeBlockPath("red_terracotta"));
    }

    @Test
    public void queueNpcUsesEmptyHotbarSlotAndIgnoresUnsolicitedGameSelector() {
        assertEquals(4, BedwarsAutoQueueController.findEmptyQueueHandSlot(0, slot -> slot == 4 || slot == 8));
        assertEquals(8, BedwarsAutoQueueController.findEmptyQueueHandSlot(8, slot -> slot == 4 || slot == 8));
        assertEquals(-1, BedwarsAutoQueueController.findEmptyQueueHandSlot(0, slot -> false));

        var globalGameMenu = List.of(new ShopSlotInfo(30, "red_bed", "BedWars", "Click to play", 1));
        assertEquals(-1, BedwarsAutoQueueController.findAutoQueueConfirmSlot("Game Selector", globalGameMenu, "doubles", false));
        assertEquals(30, BedwarsAutoQueueController.findAutoQueueConfirmSlot("Game Selector", globalGameMenu, "doubles", true));
        assertEquals(-1, BedwarsAutoQueueController.findAutoQueueConfirmSlot("BedWars", globalGameMenu, "doubles", false));
        var modeMenu = List.of(new ShopSlotInfo(12, "white_wool", "Solo", "Click to queue", 1),
                new ShopSlotInfo(13, "blue_wool", "Doubles", "Click to queue", 1));
        assertEquals(13, BedwarsAutoQueueController.findAutoQueueConfirmSlot("BedWars Game Menu", modeMenu, "doubles", false));
    }

    @Test
    public void discoversBedWarsCommandsFromHelpAndHologramsWithoutGuessingModeNumbers() {
        BedwarsQueueCommands commands = new BedwarsQueueCommands();
        commands.learnAdvertisement("/bedwars-2");
        assertNull(commands.findRoute("doubles", false, true));
        commands.learnAdvertisement("DOUBLES 8 teams of 2 players /bedwars-2 Right Click to join");
        commands.learnAdvertisement("SOLOS — /bedwars-1 Left Click to join");
        commands.learnAdvertisement("/play bedwars 3v3v3v3 - Joins Trios");
        commands.learnAdvertisement("/queue bedwars join quads: Join Quads");
        commands.learnAdvertisement("/bedwars reset - Reset BedWars Quads");
        commands.learnAdvertisement("/server bedwars-4 - Join BedWars Quads");
        commands.learnAdvertisement("/bedwars - Opens the BedWars lobby");
        assertEquals("bedwars-2", commands.findRoute("doubles", true, true));
        assertEquals("bedwars-1", commands.findRoute("solo", true, true));
        assertEquals("play bedwars 3v3v3v3", commands.findRoute("trios", true, true));
        assertEquals("queue bedwars join quads", commands.findRoute("quads", true, true));

        commands.reset(null);
        commands.learnAdvertisement("/warp bedwars doubles - Warp to a BedWars match");
        commands.learnAdvertisement("/bedwars reset doubles - Reset the game");
        commands.learnAdvertisement("/bedwars join - Join Doubles");
        commands.learnAdvertisement("/bedwars hub - Exit the Doubles lobby");
        commands.learnAdvertisement("/play bedwars solo - Join Doubles");
        commands.learnAdvertisement("/bedwars - Open the BedWars lobby");
        assertNull(commands.findRoute("doubles", true, true));
        assertNull(commands.findRoute("doubles", false, false));
        assertEquals("bedwars", commands.findRoute("doubles", false, true));
    }
}
