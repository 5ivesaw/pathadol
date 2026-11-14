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

import baritone.api.utils.Rotation;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.entity.ai.attributes.Attributes;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Pure-Java + lightweight MC tactical planning helpers shared across {@link BedwarsProcess}
 * and the Neuro-Symbolic Brain state/action layers:
 * <ul>
 *   <li>Unified inventory resource &amp; combat/tool/block counting (including modern off-hand wool)</li>
 *   <li>Bottom-up Home Bed defense shell &amp; Bed-Rush Block-In placement plans</li>
 *   <li>Fireball jump trajectory &amp; two-tick launch choreography solver</li>
 * </ul>
 */
public final class BedwarsTacticalHelper {

    public record InventorySummary(
            int iron,
            int gold,
            int diamonds,
            int emeralds,
            int woolCount,
            int hardDefenseCount,
            int bridgeBlockCount,
            int swordTier,
            int pickaxeTier,
            int axeTier,
            int shearsTier,
            double armorPoints,
            int gapples,
            int fireballs,
            int tntCount,
            int pearls,
            boolean hasBowAndArrows,
            boolean offhandHasBridgeBlock
    ) {}

    public record DefensePlacement(
            int x,
            int y,
            int z,
            int layer,
            boolean hardPreferred
    ) {}

    public record FireballJumpPlan(
            float launchYaw,
            float launchPitch,
            boolean readyToJump,
            boolean readyToFire
    ) {}

    @FunctionalInterface
    public interface BlockSolidPredicate {
        boolean isBlockedOrProtected(int x, int y, int z);
    }

    private BedwarsTacticalHelper() {}

    /**
     * Summarizes all Bedwars-relevant currencies, combat gear, tools, utility items, and bridge blocks.
     * In Modern 26.3 mode, off-hand wool/terracotta/planks count toward usable bridge blocks.
     */
    public static InventorySummary summarizeInventory(LocalPlayer player, boolean modernRuleset) {
        if (player == null) {
            return new InventorySummary(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0.0, 0, 0, 0, 0, false, false);
        }
        int iron = 0;
        int gold = 0;
        int diamonds = 0;
        int emeralds = 0;
        int wool = 0;
        int hardDefense = 0;
        int planksOrLadder = 0;
        int sword = 0;
        int pick = 0;
        int axe = 0;
        int shears = 0;
        int gapples = 0;
        int fireballs = 0;
        int tnt = 0;
        int pearls = 0;
        boolean hasBow = false;
        boolean hasArrows = false;

        NonNullList<ItemStack> main = player.getInventory().getNonEquipmentItems();
        for (ItemStack st : main) {
            if (st.isEmpty()) {
                continue;
            }
            Item item = st.getItem();
            int count = st.getCount();
            if (item == Items.IRON_INGOT) {
                iron += count;
            } else if (item == Items.GOLD_INGOT) {
                gold += count;
            } else if (item == Items.DIAMOND) {
                diamonds += count;
            } else if (item == Items.EMERALD) {
                emeralds += count;
            } else if (item == Items.SHEARS) {
                shears = Math.max(shears, 1);
            } else if (item == Items.GOLDEN_APPLE || item == Items.ENCHANTED_GOLDEN_APPLE) {
                gapples += count;
            } else if (item == Items.FIRE_CHARGE) {
                fireballs += count;
            } else if (item == Items.TNT) {
                tnt += count;
            } else if (item == Items.ENDER_PEARL) {
                pearls += count;
            } else if (item == Items.BOW) {
                hasBow = true;
            } else if (item == Items.ARROW) {
                hasArrows = true;
            } else if (item == Items.END_STONE || item == Items.OBSIDIAN) {
                hardDefense += count;
            } else if (item == Items.LADDER) {
                planksOrLadder += count;
            } else {
                String path = BuiltInRegistries.ITEM.getKey(item).getPath();
                if (path.endsWith("_wool")) {
                    wool += count;
                } else if (path.endsWith("terracotta") || path.endsWith("_planks")) {
                    planksOrLadder += count;
                    hardDefense += count;
                } else {
                    int tier = toolTierFromPath(path);
                    if (path.endsWith("_sword")) {
                        sword = Math.max(sword, tier);
                    } else if (path.endsWith("_pickaxe")) {
                        pick = Math.max(pick, tier);
                    } else if (path.endsWith("_axe")) {
                        axe = Math.max(axe, tier);
                    }
                }
            }
        }

        boolean offhandBridge = false;
        ItemStack offhand = player.getItemBySlot(EquipmentSlot.OFFHAND);
        if (!offhand.isEmpty()) {
            String offPath = BuiltInRegistries.ITEM.getKey(offhand.getItem()).getPath();
            if (offPath.endsWith("_wool") || offPath.endsWith("terracotta") || offPath.endsWith("_planks")) {
                offhandBridge = true;
                if (modernRuleset) {
                    if (offPath.endsWith("_wool")) {
                        wool += offhand.getCount();
                    } else {
                        planksOrLadder += offhand.getCount();
                    }
                }
            }
        }

        double armorScore = 0.0;
        EquipmentSlot[] slots = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};
        for (EquipmentSlot eq : slots) {
            ItemStack st = player.getItemBySlot(eq);
            if (!st.isEmpty()) {
                ItemAttributeModifiers mods = st.getItem().components().getOrDefault(DataComponents.ATTRIBUTE_MODIFIERS, ItemAttributeModifiers.EMPTY);
                armorScore += mods.compute(Attributes.ARMOR, 0.0, eq);
            }
        }

        int bridgeBlocks = wool + planksOrLadder;
        return new InventorySummary(
                iron, gold, diamonds, emeralds,
                wool, hardDefense, bridgeBlocks,
                sword, pick, axe, shears,
                armorScore, gapples, fireballs, tnt, pearls,
                hasBow && hasArrows, offhandBridge
        );
    }

    public static int toolTierFromPath(String path) {
        if (path == null) {
            return 0;
        }
        if (path.startsWith("netherite_")) return 5;
        if (path.startsWith("diamond_")) return 4;
        if (path.startsWith("iron_") || path.startsWith("golden_")) return 3;
        if (path.startsWith("stone_")) return 2;
        if (path.startsWith("wooden_")) return 1;
        return 0;
    }

    /**
     * Generates a bottom-up placement order covering all exposed sides and caps of a 2-block Bedwars bed.
     * For {@code butterflyStyle = true}, also adds an outer wool shell over the inner layer.
     */
    public static List<DefensePlacement> buildHomeBedDefensePlan(
            int headX,
            int headY,
            int headZ,
            int footX,
            int footY,
            int footZ,
            boolean butterflyStyle,
            BlockSolidPredicate solidOrProtected
    ) {
        List<DefensePlacement> out = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        long headKey = packPos(headX, headY, headZ);
        long footKey = packPos(footX, footY, footZ);
        seen.add(headKey);
        seen.add(footKey);

        // Layer 1 sides at y = bedY (must come before top caps at bedY + 1 so adjacent supports exist)
        int[][] beds = {{headX, headY, headZ}, {footX, footY, footZ}};
        int[][] horiz = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int[] b : beds) {
            for (int[] d : horiz) {
                int cx = b[0] + d[0];
                int cy = b[1];
                int cz = b[2] + d[1];
                long key = packPos(cx, cy, cz);
                if (seen.add(key) && (solidOrProtected == null || !solidOrProtected.isBlockedOrProtected(cx, cy, cz))) {
                    out.add(new DefensePlacement(cx, cy, cz, 1, true));
                }
            }
        }
        // Layer 1 top caps at y = bedY + 1
        for (int[] b : beds) {
            int cx = b[0];
            int cy = b[1] + 1;
            int cz = b[2];
            long key = packPos(cx, cy, cz);
            if (seen.add(key) && (solidOrProtected == null || !solidOrProtected.isBlockedOrProtected(cx, cy, cz))) {
                out.add(new DefensePlacement(cx, cy, cz, 1, true));
            }
        }

        if (butterflyStyle) {
            List<DefensePlacement> layer1Copy = new ArrayList<>(out);
            int[][] dirs = {{1, 0, 0}, {-1, 0, 0}, {0, 0, 1}, {0, 0, -1}, {0, 1, 0}};
            for (DefensePlacement inner : layer1Copy) {
                for (int[] d : dirs) {
                    int ox = inner.x() + d[0];
                    int oy = inner.y() + d[1];
                    int oz = inner.z() + d[2];
                    if (oy < Math.min(headY, footY)) {
                        continue;
                    }
                    long key = packPos(ox, oy, oz);
                    if (seen.add(key) && (solidOrProtected == null || !solidOrProtected.isBlockedOrProtected(ox, oy, oz))) {
                        out.add(new DefensePlacement(ox, oy, oz, 2, false));
                    }
                }
            }
        }
        return out;
    }

    /**
     * Generates a compact 1-2 high defensive cocoon around the player while breaking an enemy bed defense,
     * leaving the cardinal direction facing {@code bedTarget} clear so mining isn't blocked.
     */
    public static List<DefensePlacement> buildBlockInPlan(
            int px,
            int py,
            int pz,
            int bedX,
            int bedZ,
            BlockSolidPredicate solidOrProtected
    ) {
        List<DefensePlacement> out = new ArrayList<>();
        int dxBed = bedX - px;
        int dzBed = bedZ - pz;
        int skipDirX = Math.abs(dxBed) >= Math.abs(dzBed) ? Integer.signum(dxBed) : 0;
        int skipDirZ = Math.abs(dzBed) > Math.abs(dxBed) ? Integer.signum(dzBed) : 0;

        int[][] sideDirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        // Feet level walls first, then eye-level walls, then roof overhead cap
        for (int dy = 0; dy <= 1; dy++) {
            for (int[] d : sideDirs) {
                if (d[0] == skipDirX && d[1] == skipDirZ) {
                    continue;
                }
                int cx = px + d[0];
                int cy = py + dy;
                int cz = pz + d[1];
                if (solidOrProtected == null || !solidOrProtected.isBlockedOrProtected(cx, cy, cz)) {
                    out.add(new DefensePlacement(cx, cy, cz, dy + 1, false));
                }
            }
        }
        int roofY = py + 2;
        if (solidOrProtected == null || !solidOrProtected.isBlockedOrProtected(px, roofY, pz)) {
            out.add(new DefensePlacement(px, roofY, pz, 2, false));
        }
        return out;
    }

    /**
     * Computes backward-downward yaw/pitch for firing a fireball behind/under the player after takeoff
     * to propel horizontally toward {@code desiredTravelYaw}.
     */
    public static FireballJumpPlan computeFireballJumpPlan(float desiredTravelYaw, boolean onGround, int ticksAirborne) {
        float backYaw = Rotation.normalizeYaw(desiredTravelYaw + 180.0f);
        // Pitch ~74-78 deg fires just behind feet to give forward+upward boost rather than pure vertical bounce
        float launchPitch = 76.5f;
        boolean readyToJump = onGround && ticksAirborne == 0;
        boolean readyToFire = ticksAirborne >= 1 && ticksAirborne <= 3;
        return new FireballJumpPlan(backYaw, launchPitch, readyToJump, readyToFire);
    }

    /**
     * Computes how many of the 8 immediate surrounding bed shell cells are already protected.
     */
    public static double computeBedShellCoverageRatio(
            int headX,
            int headY,
            int headZ,
            int footX,
            int footY,
            int footZ,
            BlockSolidPredicate isCovered
    ) {
        if (isCovered == null) {
            return 0.0;
        }
        Set<Long> seen = new HashSet<>();
        seen.add(packPos(headX, headY, headZ));
        seen.add(packPos(footX, footY, footZ));
        int[][] beds = {{headX, headY, headZ}, {footX, footY, footZ}};
        int[][] offsets = {{1, 0, 0}, {-1, 0, 0}, {0, 0, 1}, {0, 0, -1}, {0, 1, 0}};
        int total = 0;
        int covered = 0;
        for (int[] b : beds) {
            for (int[] o : offsets) {
                int cx = b[0] + o[0];
                int cy = b[1] + o[1];
                int cz = b[2] + o[2];
                if (seen.add(packPos(cx, cy, cz))) {
                    total++;
                    if (isCovered.isBlockedOrProtected(cx, cy, cz)) {
                        covered++;
                    }
                }
            }
        }
        return total == 0 ? 0.0 : (double) covered / (double) total;
    }

    private static long packPos(int x, int y, int z) {
        return BlockPos.asLong(x, y, z);
    }
}
