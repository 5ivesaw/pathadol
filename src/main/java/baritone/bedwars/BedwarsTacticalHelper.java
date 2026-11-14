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

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Queue;
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

    public record ClutchCandidate(
            int placeX,
            int placeY,
            int placeZ,
            int againstX,
            int againstY,
            int againstZ,
            int faceDx,
            int faceDy,
            int faceDz,
            double score
    ) {}

    public record FireballThreat(
            double fx,
            double fy,
            double fz,
            double dist,
            double timeToImpactTicks,
            boolean directCollision,
            float punchYaw,
            float punchPitch,
            String aimMode
    ) {}

    /**
     * Builds a smart cocoon block-in plan around {@code (px, py, pz)} adapted from SUIM's {@code AutoBlockIn}:
     * <ul>
     *   <li>Excludes the opening facing the target bed plus ALL cells orthogonal to bed head/foot ({@code bedAdjacent})</li>
     *   <li>Orders walls facing-relative (back wall first -> sides -> roof) for immediate cover</li>
     *   <li>Skips cells on {@code skipCooldownPositions} after recent break/fail retries</li>
     *   <li>Prepends a bounded BFS support chain (depth &lt;= 4) if a roof/wall cell floats without solid neighbors</li>
     * </ul>
     */
    public static List<DefensePlacement> buildSmartBlockInPlan(
            int px,
            int py,
            int pz,
            int bedHeadX,
            int bedHeadZ,
            int bedFootX,
            int bedFootZ,
            float playerYawDeg,
            BlockSolidPredicate solidOrProtected,
            Set<Long> skipCooldownPositions
    ) {
        List<DefensePlacement> out = new ArrayList<>();
        double midBedX = (bedHeadX + bedFootX) * 0.5;
        double midBedZ = (bedHeadZ + bedFootZ) * 0.5;
        double dxBed = midBedX - px;
        double dzBed = midBedZ - pz;
        int skipDirX = Math.abs(dxBed) >= Math.abs(dzBed) ? (dxBed >= 0 ? 1 : -1) : 0;
        int skipDirZ = Math.abs(dzBed) > Math.abs(dxBed) ? (dzBed >= 0 ? 1 : -1) : 0;

        int[][] cardinals = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        double lookX = -Math.sin(Math.toRadians(playerYawDeg));
        double lookZ = Math.cos(Math.toRadians(playerYawDeg));

        List<int[]> wallDirs = new ArrayList<>(3);
        for (int[] c : cardinals) {
            if (c[0] == skipDirX && c[1] == skipDirZ) {
                continue;
            }
            wallDirs.add(c);
        }
        // Place rear/opposite wall first for immediate cover from behind while facing the bed, then flanks
        wallDirs.sort(Comparator.comparingDouble(d -> d[0] * lookX + d[1] * lookZ));

        Set<Long> plannedSolid = new HashSet<>();
        BlockSolidPredicate virtualSolid = (x, y, z) ->
                (solidOrProtected != null && solidOrProtected.isBlockedOrProtected(x, y, z))
                        || plannedSolid.contains(packPos(x, y, z));

        List<int[]> desiredCells = new ArrayList<>(7);
        for (int[] d : wallDirs) {
            desiredCells.add(new int[]{px + d[0], py, pz + d[1]});
        }
        for (int[] d : wallDirs) {
            desiredCells.add(new int[]{px + d[0], py + 1, pz + d[1]});
        }
        desiredCells.add(new int[]{px, py + 2, pz});

        for (int[] cell : desiredCells) {
            int tx = cell[0];
            int ty = cell[1];
            int tz = cell[2];
            long key = packPos(tx, ty, tz);
            if (isBedAdjacent(tx, ty, tz, py, bedHeadX, bedHeadZ, bedFootX, bedFootZ)) {
                continue;
            }
            if (skipCooldownPositions != null && skipCooldownPositions.contains(key)) {
                continue;
            }
            if (virtualSolid.isBlockedOrProtected(tx, ty, tz)) {
                continue;
            }
            if (!hasAnySolidNeighbor(tx, ty, tz, virtualSolid)) {
                List<DefensePlacement> supportChain = findSupportChain(
                        tx, ty, tz, px, py, pz, bedHeadX, bedHeadZ, bedFootX, bedFootZ, virtualSolid, skipCooldownPositions, 4
                );
                for (DefensePlacement sp : supportChain) {
                    long sk = packPos(sp.x(), sp.y(), sp.z());
                    if (plannedSolid.add(sk)) {
                        out.add(sp);
                    }
                }
                if (!hasAnySolidNeighbor(tx, ty, tz, virtualSolid)) {
                    continue;
                }
            }
            if (plannedSolid.add(key)) {
                out.add(new DefensePlacement(tx, ty, tz, "wool_block"));
            }
        }
        return out;
    }

    private static boolean isBedAdjacent(int x, int y, int z, int bedY, int hx, int hz, int fx, int fz) {
        if (Math.abs(y - bedY) > 1) {
            return false;
        }
        if ((x == hx && z == hz) || (x == fx && z == fz)) {
            return true;
        }
        int distH = Math.abs(x - hx) + Math.abs(z - hz);
        int distF = Math.abs(x - fx) + Math.abs(z - fz);
        return (y == bedY) && (distH <= 1 || distF <= 1);
    }

    private static boolean hasAnySolidNeighbor(int x, int y, int z, BlockSolidPredicate solid) {
        int[][] adj = {{0, -1, 0}, {1, 0, 0}, {-1, 0, 0}, {0, 0, 1}, {0, 0, -1}, {0, 1, 0}};
        for (int[] o : adj) {
            if (solid.isBlockedOrProtected(x + o[0], y + o[1], z + o[2])) {
                return true;
            }
        }
        return false;
    }

    private static List<DefensePlacement> findSupportChain(
            int targetX, int targetY, int targetZ,
            int px, int py, int pz,
            int hx, int hz, int fx, int fz,
            BlockSolidPredicate solid,
            Set<Long> skipCooldown,
            int maxDepth
    ) {
        record Node(int x, int y, int z, int depth, Long parent) {}
        int[][] dirs = {{0, -1, 0}, {1, 0, 0}, {-1, 0, 0}, {0, 0, 1}, {0, 0, -1}, {0, 1, 0}};
        Queue<Node> queue = new ArrayDeque<>();
        Set<Long> visited = new HashSet<>();
        java.util.Map<Long, Node> nodesByKey = new java.util.HashMap<>();

        long startKey = packPos(targetX, targetY, targetZ);
        Node root = new Node(targetX, targetY, targetZ, 0, null);
        queue.add(root);
        visited.add(startKey);
        nodesByKey.put(startKey, root);

        Node anchorParent = null;
        while (!queue.isEmpty()) {
            Node cur = queue.poll();
            if (cur.depth >= maxDepth) {
                continue;
            }
            for (int[] d : dirs) {
                int nx = cur.x + d[0];
                int ny = cur.y + d[1];
                int nz = cur.z + d[2];
                if (hasAnySolidNeighbor(nx, ny, nz, solid) && !(nx == targetX && ny == targetY && nz == targetZ)) {
                    // Found a step adjacent to solid terrain; ensure intermediate cells are valid
                    long nk = packPos(nx, ny, nz);
                    if (!solid.isBlockedOrProtected(nx, ny, nz)
                            && !(nx == px && (ny == py || ny == py + 1) && nz == pz)
                            && !isBedAdjacent(nx, ny, nz, py, hx, hz, fx, fz)
                            && (skipCooldown == null || !skipCooldown.contains(nk))) {
                        anchorParent = new Node(nx, ny, nz, cur.depth + 1, packPos(cur.x, cur.y, cur.z));
                        break;
                    }
                }
                long nk = packPos(nx, ny, nz);
                if (!visited.add(nk) || solid.isBlockedOrProtected(nx, ny, nz)) {
                    continue;
                }
                if (nx == px && (ny == py || ny == py + 1) && nz == pz) {
                    continue;
                }
                if (isBedAdjacent(nx, ny, nz, py, hx, hz, fx, fz)) {
                    continue;
                }
                if (skipCooldown != null && skipCooldown.contains(nk)) {
                    continue;
                }
                Node child = new Node(nx, ny, nz, cur.depth + 1, packPos(cur.x, cur.y, cur.z));
                nodesByKey.put(nk, child);
                queue.add(child);
            }
            if (anchorParent != null) {
                break;
            }
        }
        if (anchorParent == null) {
            return List.of();
        }
        List<DefensePlacement> chain = new ArrayList<>();
        // Walk from support anchor back toward target (skipping target itself)
        Node cur = anchorParent;
        while (cur != null && !(cur.x == targetX && cur.y == targetY && cur.z == targetZ)) {
            chain.add(new DefensePlacement(cur.x, cur.y, cur.z, "wool_block"));
            cur = cur.parent != null ? nodesByKey.get(cur.parent) : null;
        }
        return chain;
    }

    /**
     * Returns whether reactive AutoClutch should activate while falling in Bedwars.
     */
    public static boolean shouldTriggerVoidClutch(
            boolean onGround,
            boolean isFallFlying,
            boolean isInWater,
            float fallDistance,
            double vy,
            double voidClearanceBlocks,
            boolean hasFloorWithinTwoBlocksBelow,
            boolean recallMode
    ) {
        if (recallMode || onGround || isFallFlying || isInWater) {
            return false;
        }
        if (hasFloorWithinTwoBlocksBelow && fallDistance < 3.0f) {
            return false;
        }
        if (vy > -0.10) {
            return false;
        }
        if (fallDistance >= 1.35f) {
            return true;
        }
        return voidClearanceBlocks <= 18.0 && fallDistance >= 0.85f && vy <= -0.18;
    }

    /**
     * Predicts 1..4 tick falling player positions and ranks valid solid-neighbor catch block placements
     * adapted from LiquidBounce {@code ModuleClutch} and SUIM {@code AutoClutch}.
     */
    public static List<ClutchCandidate> rankClutchPlacements(
            double px,
            double py,
            double pz,
            double vx,
            double vy,
            double vz,
            BlockSolidPredicate isSolid,
            double maxReach
    ) {
        if (isSolid == null) {
            return List.of();
        }
        double eyeY = py + 1.62;
        double[] predX = new double[5];
        double[] predY = new double[5];
        double[] predZ = new double[5];
        double cx = px, cy = py, cz = pz;
        double cvx = vx, cvy = vy, cvz = vz;
        for (int t = 0; t < 5; t++) {
            predX[t] = cx;
            predY[t] = cy;
            predZ[t] = cz;
            cvy = (cvy - 0.08) * 0.98;
            cx += cvx;
            cy += cvy;
            cz += cvz;
            cvx *= 0.91;
            cvz *= 0.91;
        }

        int minX = (int) Math.floor(Math.min(px, predX[4])) - 2;
        int maxX = (int) Math.floor(Math.max(px, predX[4])) + 2;
        int minZ = (int) Math.floor(Math.min(pz, predZ[4])) - 2;
        int maxZ = (int) Math.floor(Math.max(pz, predZ[4])) + 2;
        int minY = (int) Math.floor(py) - 3;
        int maxY = (int) Math.floor(py);

        int[][] adj = {{0, -1, 0}, {1, 0, 0}, {-1, 0, 0}, {0, 0, 1}, {0, 0, -1}, {0, 1, 0}};
        List<ClutchCandidate> out = new ArrayList<>();
        for (int y = maxY; y >= minY; y--) {
            for (int x = minX; x <= maxX; x++) {
                for (int z = minZ; z <= maxZ; z++) {
                    if (isSolid.isBlockedOrProtected(x, y, z)) {
                        continue;
                    }
                    // Reject placing strictly overlapping upper player hitbox
                    boolean overlapsBody = (x + 1.0 > px - 0.29 && x < px + 0.29)
                            && (z + 1.0 > pz - 0.29 && z < pz + 0.29)
                            && (y + 1.0 > py + 0.02 && y < py + 1.79);
                    if (overlapsBody) {
                        continue;
                    }
                    double bestScore = Double.NEGATIVE_INFINITY;
                    ClutchCandidate bestForCell = null;
                    for (int[] o : adj) {
                        int ax = x + o[0];
                        int ay = y + o[1];
                        int az = z + o[2];
                        if (!isSolid.isBlockedOrProtected(ax, ay, az)) {
                            continue;
                        }
                        int fdx = x - ax;
                        int fdy = y - ay;
                        int fdz = z - az;
                        double hitX = ax + 0.5 + fdx * 0.5;
                        double hitY = ay + 0.5 + fdy * 0.5;
                        double hitZ = az + 0.5 + fdz * 0.5;
                        double reach = Math.sqrt((hitX - px) * (hitX - px) + (hitY - eyeY) * (hitY - eyeY) + (hitZ - pz) * (hitZ - pz));
                        if (reach > maxReach) {
                            continue;
                        }
                        double minTrajDistSq = Double.POSITIVE_INFINITY;
                        for (int t = 0; t < 5; t++) {
                            double ddx = (x + 0.5) - predX[t];
                            double ddz = (z + 0.5) - predZ[t];
                            double ddy = (y + 1.0) - predY[t];
                            double distSq = ddx * ddx + ddz * ddz + ddy * ddy * 1.35;
                            if (distSq < minTrajDistSq) {
                                minTrajDistSq = distSq;
                            }
                        }
                        double faceBonus = (fdy == 1) ? 1.5 : (fdy == 0 ? 0.6 : -0.6);
                        double underFootBonus = (y <= Math.floor(py) - 1 && Math.abs(x + 0.5 - px) <= 0.85 && Math.abs(z + 0.5 - pz) <= 0.85) ? 2.5 : 0.0;
                        double score = 12.0 - Math.sqrt(minTrajDistSq) * 2.2 - reach * 0.55 + faceBonus + underFootBonus;
                        if (score > bestScore) {
                            bestScore = score;
                            bestForCell = new ClutchCandidate(x, y, z, ax, ay, az, fdx, fdy, fdz, score);
                        }
                    }
                    if (bestForCell != null) {
                        out.add(bestForCell);
                    }
                }
            }
        }
        out.sort((a, b) -> Double.compare(b.score(), a.score()));
        return out;
    }

    /**
     * Evaluates an incoming fireball for legit melee punch deflection ({@code AntiFireball}),
     * verifying whether the fireball trajectory approaches or intersects the player and computing
     * a punch aim angle (biased slightly inside the fireball box toward an enemy when aligned).
     */
    public static FireballThreat evaluateIncomingFireball(
            double fx,
            double fy,
            double fz,
            double fvx,
            double fvy,
            double fvz,
            double playerX,
            double playerEyeY,
            double playerZ,
            double nearestEnemyX,
            double nearestEnemyY,
            double nearestEnemyZ,
            boolean hasNearestEnemy
    ) {
        double rx = fx - playerX;
        double ry = (fy + 0.35) - playerEyeY;
        double rz = fz - playerZ;
        double dist = Math.sqrt(rx * rx + ry * ry + rz * rz);
        if (dist > 6.5) {
            return null;
        }
        double speedSq = fvx * fvx + fvy * fvy + fvz * fvz;
        // Require either closing velocity toward player OR close stationary/slow proximity hazard
        double closingDot = -(rx * fvx + ry * fvy + rz * fvz);
        if (speedSq > 1e-4 && closingDot <= 0.0 && dist > 3.2) {
            return null;
        }

        double minTrajDistSq = Double.POSITIVE_INFINITY;
        double impactTick = 0.0;
        double bodyMidY = playerEyeY - 0.72;
        for (int t = 0; t <= 10; t++) {
            double px = fx + fvx * t;
            double py = fy + 0.35 + fvy * t;
            double pz = fz + fvz * t;
            double dx = px - playerX;
            double dy = py - bodyMidY;
            double dz = pz - playerZ;
            double dSq = dx * dx + dy * dy + dz * dz;
            if (dSq < minTrajDistSq) {
                minTrajDistSq = dSq;
                impactTick = t;
            }
        }
        boolean directCollision = minTrajDistSq <= (1.65 * 1.65) || dist <= 4.2;
        if (!directCollision && dist > 5.2) {
            return null;
        }

        // Aim at fireball 0.45 ticks ahead so ping + swing connects inside its 1.0x1.0x1.0 hitbox
        double leadX = fx + fvx * 0.45;
        double leadY = fy + 0.35 + fvy * 0.45;
        double leadZ = fz + fvz * 0.45;

        double aimX = leadX;
        double aimY = leadY;
        double aimZ = leadZ;
        String aimMode = "CENTER";

        if (hasNearestEnemy) {
            double toEnemyX = nearestEnemyX - playerX;
            double toEnemyY = nearestEnemyY - playerEyeY;
            double toEnemyZ = nearestEnemyZ - playerZ;
            double toFbX = leadX - playerX;
            double toFbY = leadY - playerEyeY;
            double toFbZ = leadZ - playerZ;
            double eLen = Math.sqrt(toEnemyX * toEnemyX + toEnemyY * toEnemyY + toEnemyZ * toEnemyZ);
            double fLen = Math.sqrt(toFbX * toFbX + toFbY * toFbY + toFbZ * toFbZ);
            if (eLen > 1e-4 && fLen > 1e-4) {
                double dot = (toEnemyX * toFbX + toEnemyY * toFbY + toEnemyZ * toFbZ) / (eLen * fLen);
                if (dot >= 0.82) {
                    // Nudge aim point inside fireball hitbox edge toward enemy
                    aimX = playerX + (toEnemyX / eLen) * fLen;
                    aimY = playerEyeY + (toEnemyY / eLen) * fLen;
                    aimZ = playerZ + (toEnemyZ / eLen) * fLen;
                    aimX = Math.max(leadX - 0.32, Math.min(leadX + 0.32, aimX));
                    aimY = Math.max(leadY - 0.32, Math.min(leadY + 0.32, aimY));
                    aimZ = Math.max(leadZ - 0.32, Math.min(leadZ + 0.32, aimZ));
                    aimMode = "REFLECT_ENEMY";
                }
            }
        }

        double adx = aimX - playerX;
        double ady = aimY - playerEyeY;
        double adz = aimZ - playerZ;
        double horiz = Math.hypot(adx, adz);
        float yaw = (float) Math.toDegrees(Math.atan2(-adx, adz));
        float pitch = Rotation.clampPitch((float) -Math.toDegrees(Math.atan2(ady, Math.max(1e-4, horiz))));
        return new FireballThreat(fx, fy, fz, dist, impactTick, directCollision, yaw, pitch, aimMode);
    }

    private static long packPos(int x, int y, int z) {
        return BlockPos.asLong(x, y, z);
    }
}
