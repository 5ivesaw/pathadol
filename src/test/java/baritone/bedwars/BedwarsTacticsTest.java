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

import baritone.bedwars.BedwarsDefensePlanner.BedShellScan;
import baritone.bedwars.BedwarsDefensePlanner.BlockKindClassifier;
import baritone.bedwars.BedwarsDefensePlanner.DefenseBlockKind;
import baritone.bedwars.BedwarsDefensePlanner.RayTargetResult;
import baritone.bedwars.BedwarsDefensePlanner.ToolProfile;
import baritone.bedwars.BedwarsMapCore.ArenaBounds;
import baritone.bedwars.BedwarsMapCore.ShopNPC;
import baritone.bedwars.BedwarsShopPlanner.CostSpec;
import baritone.bedwars.BedwarsShopPlanner.Currency;
import baritone.bedwars.BedwarsShopPlanner.MenuKind;
import baritone.bedwars.BedwarsShopPlanner.ShopSlotInfo;
import baritone.bedwars.BedwarsTacticalHelper.ClutchCandidate;
import baritone.bedwars.BedwarsTacticalHelper.DefensePlacement;
import baritone.bedwars.BedwarsTacticalHelper.FireballThreat;
import baritone.bedwars.BedwarsTacticalHelper.InventorySummary;
import baritone.behavior.InventoryBehavior;
import baritone.process.CombatProcess;
import baritone.process.CombatProcess.SpacingAction;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.junit.Test;

import java.util.List;
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
        InventorySummary empty = new InventorySummary(6, 1, 0, 0, 8, 1, 12.0, 0, 0, 0, 0, 0, 0, 0, 0);
        // Active during cooldown window when currency hasn't meaningfully increased
        assertTrue(BedwarsProcess.isShopCooldownActive(105L, 180L, empty, 6, 1, 0, 0));
        // Unlocks after cooldown tick expires
        assertFalse(BedwarsProcess.isShopCooldownActive(180L, 180L, empty, 6, 1, 0, 0));
        // Unlocks early if +12 iron collected from forge
        InventorySummary moreIron = new InventorySummary(18, 1, 0, 0, 8, 1, 12.0, 0, 0, 0, 0, 0, 0, 0, 0);
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
                10.5, 63.6, 10.5,
                0.22, -0.25, 0.0,
                (x, y, z) -> (x == 9 && y == 63 && z == 10),
                4.5
        );
        assertFalse(catches.isEmpty());
        assertEquals(10, catches.get(0).placeX());

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
}
