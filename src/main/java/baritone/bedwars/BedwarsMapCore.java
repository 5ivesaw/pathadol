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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Pure-Java T=0 Bedwars spatial topology memory storing:
 * <ul>
 *   <li>Packed {@code OriginalMapBitSet} over match volume (unbreakable permanent arena terrain)</li>
 *   <li>Packed {@code PlayerPlacedBitSet} updated live on {@code Air -> Solid} and {@code Solid -> Air}</li>
 *   <li>Rotational 4-way / octahedral 8-way symmetry reconstruction for unloaded quadrants</li>
 *   <li>Indexed Beds, Generators, Spawns, Shop NPCs, custom void floor &amp; build ceiling, and No-Place zones</li>
 * </ul>
 * Intentionally avoids Minecraft block/item static initializers so headless JUnit tests run cleanly.
 */
public final class BedwarsMapCore {

    public enum BwsRuleset {
        OFF,
        OLD_1_8,
        MODERN_26_3;

        public static BwsRuleset fromString(String value) {
            if (value == null) {
                return OFF;
            }
            return switch (value.trim().toLowerCase(Locale.ROOT)) {
                case "old", "1.8", "old_1_8", "hypixel" -> OLD_1_8;
                case "modern", "1.21", "26.3", "modern_26_3" -> MODERN_26_3;
                default -> OFF;
            };
        }

        public String toSettingString() {
            return switch (this) {
                case OFF -> "off";
                case OLD_1_8 -> "old";
                case MODERN_26_3 -> "modern";
            };
        }
    }

    public enum TeamColor {
        RED, BLUE, GREEN, YELLOW, AQUA, WHITE, PINK, GRAY, UNKNOWN;

        public static TeamColor fromName(String raw) {
            if (raw == null || raw.isEmpty()) {
                return UNKNOWN;
            }
            String lower = raw.toLowerCase(Locale.ROOT);
            if (lower.contains("red")) return RED;
            if (lower.contains("light_blue") || lower.contains("cyan") || lower.contains("aqua")) return AQUA;
            if (lower.contains("blue")) return BLUE;
            if (lower.contains("lime") || lower.contains("green")) return GREEN;
            if (lower.contains("yellow")) return YELLOW;
            if (lower.contains("white")) return WHITE;
            if (lower.contains("pink") || lower.contains("magenta")) return PINK;
            if (lower.contains("gray") || lower.contains("grey")) return GRAY;
            return UNKNOWN;
        }
    }

    public enum SymmetryMode {
        NONE,
        FOUR_WAY_ROTATIONAL,
        EIGHT_WAY_OCTAHEDRAL
    }

    public enum GenType {
        HOME_FORGE,
        DIAMOND,
        EMERALD
    }

    public record BedPOI(
            TeamColor team,
            int x,
            int y,
            int z,
            int footX,
            int footY,
            int footZ,
            boolean isHome,
            boolean destroyed
    ) {
        public boolean matchesCell(int bx, int by, int bz) {
            return (bx == x && by == y && bz == z) || (bx == footX && by == footY && bz == footZ);
        }

        public BedPOI withHome(boolean home) {
            return new BedPOI(team, x, y, z, footX, footY, footZ, home, destroyed);
        }

        public BedPOI withDestroyed(boolean dead) {
            return new BedPOI(team, x, y, z, footX, footY, footZ, isHome, dead);
        }
    }

    public record GenPOI(GenType type, int x, int y, int z, String label) {}

    public record SpawnPOI(TeamColor team, int x, int y, int z) {}

    public record ShopNPC(String label, int x, int y, int z) {}

    public record ArenaBounds(int minX, int minY, int minZ, int sizeX, int sizeY, int sizeZ) {
        public boolean contains(int x, int y, int z) {
            return x >= minX && x < minX + sizeX
                    && y >= minY && y < minY + sizeY
                    && z >= minZ && z < minZ + sizeZ;
        }

        public long voxelCount() {
            return (long) sizeX * (long) sizeY * (long) sizeZ;
        }

        public int index(int x, int y, int z) {
            return ((y - minY) * sizeZ + (z - minZ)) * sizeX + (x - minX);
        }

        public int chunkColsX() {
            return ((minX + sizeX - 1) >> 4) - (minX >> 4) + 1;
        }

        public int chunkColsZ() {
            return ((minZ + sizeZ - 1) >> 4) - (minZ >> 4) + 1;
        }

        public int chunkIndex(int cx, int cz) {
            int localX = cx - (minX >> 4);
            int localZ = cz - (minZ >> 4);
            if (localX < 0 || localZ < 0 || localX >= chunkColsX() || localZ >= chunkColsZ()) {
                return -1;
            }
            return localZ * chunkColsX() + localX;
        }
    }

    private final ArenaBounds bounds;
    private final long[] originalMapBits;
    private final long[] playerPlacedBits;
    private final long[] scannedChunksBits;

    private int center2X;
    private int center2Z;
    private SymmetryMode symmetryMode = SymmetryMode.FOUR_WAY_ROTATIONAL;
    private int voidFloorY;
    private int mapMaxBuildY;
    private TeamColor homeTeam = TeamColor.UNKNOWN;
    private long structuralFingerprint = 0L;

    private final List<BedPOI> beds = new ArrayList<>();
    private final List<GenPOI> gens = new ArrayList<>();
    private final List<SpawnPOI> spawns = new ArrayList<>();
    private final List<ShopNPC> shopNpcs = new ArrayList<>();
    private final Set<Long> shopExcludedCells = new HashSet<>();

    private volatile BedPOI[] bedsSnapshot = new BedPOI[0];
    private volatile GenPOI[] gensSnapshot = new GenPOI[0];
    private volatile SpawnPOI[] spawnsSnapshot = new SpawnPOI[0];
    private volatile ShopNPC[] shopNpcsSnapshot = new ShopNPC[0];

    public BedwarsMapCore(ArenaBounds bounds, double centerX, double centerZ, int voidFloorY, int mapMaxBuildY) {
        this.bounds = bounds;
        long totalVoxels = bounds.voxelCount();
        int wordCount = (int) ((totalVoxels + 63L) >>> 6);
        this.originalMapBits = new long[wordCount];
        this.playerPlacedBits = new long[wordCount];
        int totalChunks = Math.max(1, bounds.chunkColsX() * bounds.chunkColsZ());
        this.scannedChunksBits = new long[(totalChunks + 63) >>> 6];
        this.center2X = (int) Math.round(centerX * 2.0);
        this.center2Z = (int) Math.round(centerZ * 2.0);
        this.voidFloorY = voidFloorY;
        this.mapMaxBuildY = mapMaxBuildY;
    }

    public static ArenaBounds createCenteredBounds(int centerX, int minY, int centerZ, int halfExtentXZ, int heightY) {
        int alignedMinX = ((centerX - halfExtentXZ) >> 4) << 4;
        int alignedMinZ = ((centerZ - halfExtentXZ) >> 4) << 4;
        int sizeXZ = ((halfExtentXZ * 2 + 31) >> 4) << 4;
        return new ArenaBounds(alignedMinX, minY, alignedMinZ, sizeXZ, heightY, sizeXZ);
    }

    public ArenaBounds bounds() {
        return bounds;
    }

    public int center2X() {
        return center2X;
    }

    public int center2Z() {
        return center2Z;
    }

    public void setCenter(double centerX, double centerZ) {
        this.center2X = (int) Math.round(centerX * 2.0);
        this.center2Z = (int) Math.round(centerZ * 2.0);
    }

    public SymmetryMode symmetryMode() {
        return symmetryMode;
    }

    public void setSymmetryMode(SymmetryMode symmetryMode) {
        this.symmetryMode = symmetryMode != null ? symmetryMode : SymmetryMode.NONE;
    }

    public int voidFloorY() {
        return voidFloorY;
    }

    public void setVoidFloorY(int voidFloorY) {
        this.voidFloorY = voidFloorY;
    }

    public int mapMaxBuildY() {
        return mapMaxBuildY;
    }

    public void setMapMaxBuildY(int mapMaxBuildY) {
        this.mapMaxBuildY = mapMaxBuildY;
    }

    public TeamColor homeTeam() {
        return homeTeam;
    }

    public void setHomeTeam(TeamColor homeTeam) {
        this.homeTeam = homeTeam != null ? homeTeam : TeamColor.UNKNOWN;
    }

    public long structuralFingerprint() {
        return structuralFingerprint;
    }

    public void setStructuralFingerprint(long structuralFingerprint) {
        this.structuralFingerprint = structuralFingerprint;
    }

    public synchronized void setOriginalBlock(int x, int y, int z, boolean solid, boolean excludeFromSymmetry) {
        if (!bounds.contains(x, y, z)) {
            return;
        }
        int idx = bounds.index(x, y, z);
        setBit(originalMapBits, idx, solid);
        if (excludeFromSymmetry) {
            shopExcludedCells.add(packPos(x, y, z));
        }
    }

    public boolean isOriginalMapBlock(int x, int y, int z) {
        if (!bounds.contains(x, y, z)) {
            return false;
        }
        return getBit(originalMapBits, bounds.index(x, y, z));
    }

    public synchronized void setPlayerPlaced(int x, int y, int z, boolean placed) {
        if (!bounds.contains(x, y, z)) {
            return;
        }
        setBit(playerPlacedBits, bounds.index(x, y, z), placed);
    }

    public boolean isPlayerPlacedBlock(int x, int y, int z) {
        if (!bounds.contains(x, y, z)) {
            return false;
        }
        return getBit(playerPlacedBits, bounds.index(x, y, z));
    }

    public synchronized void markChunkScanned(int chunkX, int chunkZ) {
        int ci = bounds.chunkIndex(chunkX, chunkZ);
        if (ci >= 0) {
            setBit(scannedChunksBits, ci, true);
        }
    }

    public boolean isChunkScanned(int chunkX, int chunkZ) {
        int ci = bounds.chunkIndex(chunkX, chunkZ);
        return ci >= 0 && getBit(scannedChunksBits, ci);
    }

    /**
     * Reconstructs unloaded distant island geometry using exact half-coordinate rotational/mirror symmetry
     * around {@code (center2X, center2Z)}, never overwriting genuinely scanned chunks.
     */
    public synchronized int applySymmetryReconstruction(SymmetryMode mode) {
        this.symmetryMode = mode;
        if (mode == null || mode == SymmetryMode.NONE) {
            return 0;
        }
        int added = 0;
        int maxY = bounds.minY + bounds.sizeY;
        int maxZ = bounds.minZ + bounds.sizeZ;
        int maxX = bounds.minX + bounds.sizeX;
        boolean octahedral = mode == SymmetryMode.EIGHT_WAY_OCTAHEDRAL;

        for (int y = bounds.minY; y < maxY; y++) {
            for (int z = bounds.minZ; z < maxZ; z++) {
                int rowBase = ((y - bounds.minY) * bounds.sizeZ + (z - bounds.minZ)) * bounds.sizeX;
                for (int x = bounds.minX; x < maxX; x++) {
                    int idx = rowBase + (x - bounds.minX);
                    if (!getBit(originalMapBits, idx)) {
                        continue;
                    }
                    if (!isChunkScanned(x >> 4, z >> 4) || isNearShopNpc(x, y, z)) {
                        continue;
                    }
                    int hx = 2 * x + 1 - center2X;
                    int hz = 2 * z + 1 - center2Z;

                    added += projectSymmetryCell(-hz, hx, y);
                    added += projectSymmetryCell(-hx, -hz, y);
                    added += projectSymmetryCell(hz, -hx, y);
                    if (octahedral) {
                        added += projectSymmetryCell(-hx, hz, y);
                        added += projectSymmetryCell(hx, -hz, y);
                        added += projectSymmetryCell(hz, hx, y);
                        added += projectSymmetryCell(-hz, -hx, y);
                    }
                }
            }
        }
        return added;
    }

    private int projectSymmetryCell(int thX, int thZ, int y) {
        int tx = (thX + center2X - 1) >> 1;
        int tz = (thZ + center2Z - 1) >> 1;
        if (!bounds.contains(tx, y, tz) || isChunkScanned(tx >> 4, tz >> 4)) {
            return 0;
        }
        int tIdx = bounds.index(tx, y, tz);
        if (!getBit(originalMapBits, tIdx)) {
            setBit(originalMapBits, tIdx, true);
            return 1;
        }
        return 0;
    }

    /**
     * Merges unloaded terrain and POIs from a previously cached {@link BedwarsMapCore} matching the
     * current arena without overwriting freshly scanned live chunks or live home-team flags.
     */
    public synchronized int mergeFromCache(BedwarsMapCore cached) {
        if (cached == null || !cached.bounds.equals(this.bounds)) {
            return 0;
        }
        int mergedBlocks = 0;
        int maxY = bounds.minY + bounds.sizeY;
        int maxZ = bounds.minZ + bounds.sizeZ;
        int maxX = bounds.minX + bounds.sizeX;
        for (int y = bounds.minY; y < maxY; y++) {
            for (int z = bounds.minZ; z < maxZ; z++) {
                int rowBase = ((y - bounds.minY) * bounds.sizeZ + (z - bounds.minZ)) * bounds.sizeX;
                for (int x = bounds.minX; x < maxX; x++) {
                    if (isChunkScanned(x >> 4, z >> 4)) {
                        continue;
                    }
                    int idx = rowBase + (x - bounds.minX);
                    if (getBit(cached.originalMapBits, idx) && !getBit(this.originalMapBits, idx)) {
                        setBit(this.originalMapBits, idx, true);
                        mergedBlocks++;
                    }
                }
            }
        }
        for (BedPOI cb : cached.getBeds()) {
            boolean exists = false;
            for (BedPOI lb : beds) {
                if (lb.matchesCell(cb.x(), cb.y(), cb.z())) {
                    exists = true;
                    break;
                }
            }
            if (!exists) {
                beds.add(cb.withHome(cb.team() != TeamColor.UNKNOWN && cb.team() == homeTeam));
            }
        }
        for (GenPOI cg : cached.getGens()) {
            boolean exists = false;
            for (GenPOI lg : gens) {
                if (Math.abs(lg.x() - cg.x()) <= 2 && Math.abs(lg.z() - cg.z()) <= 2 && lg.type() == cg.type()) {
                    exists = true;
                    break;
                }
            }
            if (!exists) {
                gens.add(cg);
            }
        }
        for (SpawnPOI cs : cached.getSpawns()) {
            boolean exists = false;
            for (SpawnPOI ls : spawns) {
                if (Math.abs(ls.x() - cs.x()) <= 3 && Math.abs(ls.z() - cs.z()) <= 3) {
                    exists = true;
                    break;
                }
            }
            if (!exists) {
                spawns.add(cs);
            }
        }
        for (ShopNPC cn : cached.getShopNpcs()) {
            boolean exists = false;
            for (ShopNPC ln : shopNpcs) {
                if (Math.abs(ln.x() - cn.x()) <= 2 && Math.abs(ln.z() - cn.z()) <= 2) {
                    exists = true;
                    break;
                }
            }
            if (!exists) {
                shopNpcs.add(cn);
            }
        }
        if (this.mapMaxBuildY <= this.voidFloorY && cached.mapMaxBuildY > this.voidFloorY) {
            this.mapMaxBuildY = cached.mapMaxBuildY;
        }
        return mergedBlocks;
    }

    /**
     * Edge Case A: Invisible No-Place exclusion zones protecting Spawn (r=6), Shop NPCs (r=3),
     * and Diamond/Emerald Generators (r=3) so the bot never wastes blocks or traps itself.
     */
    public boolean isInNoPlaceZone(int x, int y, int z) {
        for (SpawnPOI sp : spawnsSnapshot) {
            if (y >= sp.y - 1 && y <= sp.y + 3) {
                int dx = x - sp.x;
                int dz = z - sp.z;
                if (dx * dx + dz * dz <= 36) {
                    return true;
                }
            }
        }
        for (ShopNPC npc : shopNpcsSnapshot) {
            if (y >= npc.y - 1 && y <= npc.y + 3) {
                int dx = x - npc.x;
                int dz = z - npc.z;
                if (dx * dx + dz * dz <= 9) {
                    return true;
                }
            }
        }
        for (GenPOI gen : gensSnapshot) {
            if (gen.type != GenType.HOME_FORGE && y >= gen.y - 1 && y <= gen.y + 3) {
                int dx = x - gen.x;
                int dz = z - gen.z;
                if (dx * dx + dz * dz <= 9) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean isNearShopNpc(int x, int y, int z) {
        if (!shopExcludedCells.isEmpty() && shopExcludedCells.contains(packPos(x, y, z))) {
            return true;
        }
        for (ShopNPC npc : shopNpcsSnapshot) {
            int dx = x - npc.x;
            int dy = y - npc.y;
            int dz = z - npc.z;
            if (dx * dx + dz * dz <= 9 && Math.abs(dy) <= 4) {
                return true;
            }
        }
        return false;
    }

    public boolean isWithinBuildVerticalBounds(int y) {
        return y >= voidFloorY && y <= mapMaxBuildY;
    }

    public boolean isProtectedMapOrHomeBed(int x, int y, int z) {
        for (BedPOI bed : bedsSnapshot) {
            if (bed.isHome && bed.matchesCell(x, y, z)) {
                return true;
            }
        }
        return isOriginalMapBlock(x, y, z) && !isPlayerPlacedBlock(x, y, z);
    }

    public double distanceSqToNearestLivingEnemyBed(int x, int y, int z) {
        double best = Double.POSITIVE_INFINITY;
        for (BedPOI bed : bedsSnapshot) {
            if (bed.isHome || bed.destroyed) {
                continue;
            }
            double dx = (x + 0.5) - (bed.x + 0.5);
            double dy = (y + 0.5) - (bed.y + 0.5);
            double dz = (z + 0.5) - (bed.z + 0.5);
            double dSq = dx * dx + dy * dy + dz * dz;
            if (dSq < best) {
                best = dSq;
            }
        }
        return best;
    }

    public synchronized void clearPois() {
        beds.clear();
        gens.clear();
        spawns.clear();
        shopNpcs.clear();
        shopExcludedCells.clear();
        bedsSnapshot = new BedPOI[0];
        gensSnapshot = new GenPOI[0];
        spawnsSnapshot = new SpawnPOI[0];
        shopNpcsSnapshot = new ShopNPC[0];
    }

    public synchronized void clearPlayerPlaced() {
        Arrays.fill(playerPlacedBits, 0L);
    }

    public synchronized void addBed(BedPOI bed) {
        beds.add(bed);
        bedsSnapshot = beds.toArray(new BedPOI[0]);
    }

    public synchronized void markBedDestroyedAt(int x, int y, int z) {
        boolean changed = false;
        for (int i = 0; i < beds.size(); i++) {
            BedPOI b = beds.get(i);
            if (!b.destroyed && b.matchesCell(x, y, z)) {
                beds.set(i, b.withDestroyed(true));
                changed = true;
            }
        }
        if (changed) {
            bedsSnapshot = beds.toArray(new BedPOI[0]);
        }
    }

    public synchronized void markTeamBedDestroyed(TeamColor color) {
        if (color == null || color == TeamColor.UNKNOWN) {
            return;
        }
        boolean changed = false;
        for (int i = 0; i < beds.size(); i++) {
            BedPOI b = beds.get(i);
            if (!b.destroyed && b.team() == color) {
                beds.set(i, b.withDestroyed(true));
                changed = true;
            }
        }
        if (changed) {
            bedsSnapshot = beds.toArray(new BedPOI[0]);
        }
    }

    public synchronized void addGen(GenPOI gen) {
        gens.add(gen);
        gensSnapshot = gens.toArray(new GenPOI[0]);
    }

    public synchronized void addSpawn(SpawnPOI spawn) {
        spawns.add(spawn);
        spawnsSnapshot = spawns.toArray(new SpawnPOI[0]);
    }

    public synchronized void addShopNpc(ShopNPC npc) {
        addOrUpdateShopNpc(npc);
    }

    /**
     * Adds a {@link ShopNPC} or upgrades an existing nearby generic/roleless NPC label
     * once overhead hologram text or opened-GUI inspection reveals {@code ITEM_SHOP} vs {@code UPGRADES}.
     */
    public synchronized void addOrUpdateShopNpc(ShopNPC npc) {
        if (npc == null) {
            return;
        }
        for (int i = 0; i < shopNpcs.size(); i++) {
            ShopNPC existing = shopNpcs.get(i);
            if (Math.abs(existing.x - npc.x) <= 2
                    && Math.abs(existing.z - npc.z) <= 2
                    && Math.abs(existing.y - npc.y) <= 3) {
                if (shouldReplaceShopLabel(existing.label, npc.label)) {
                    shopNpcs.set(i, npc);
                    shopNpcsSnapshot = shopNpcs.toArray(new ShopNPC[0]);
                }
                return;
            }
        }
        shopNpcs.add(npc);
        shopNpcsSnapshot = shopNpcs.toArray(new ShopNPC[0]);
    }

    public static String classifyShopNpcRole(String label) {
        if (label == null || label.isBlank()) {
            return "UNKNOWN";
        }
        String trimmed = label.trim();
        if (trimmed.startsWith("UPGRADES:")) {
            return "UPGRADES";
        }
        if (trimmed.startsWith("ITEM_SHOP:")) {
            return "ITEM_SHOP";
        }
        String lower = trimmed.toLowerCase(Locale.ROOT);
        if (lower.contains("upgrade") || lower.contains("trap") || lower.contains("forge") || lower.contains("perk")) {
            return "UPGRADES";
        }
        if (lower.contains("item shop") || lower.contains("quick buy") || lower.contains("shopkeeper")
                || lower.contains("shop keeper") || lower.contains("shop")) {
            return "ITEM_SHOP";
        }
        return "UNKNOWN";
    }

    private static boolean shouldReplaceShopLabel(String oldLabel, String newLabel) {
        String newRole = classifyShopNpcRole(newLabel);
        String oldRole = classifyShopNpcRole(oldLabel);
        if (!"UNKNOWN".equals(newRole) && "UNKNOWN".equals(oldRole)) {
            return true;
        }
        // Explicit learned tags override guessed hologram prefixes
        boolean newExplicit = newLabel != null && (newLabel.startsWith("ITEM_SHOP:LEARNED") || newLabel.startsWith("UPGRADES:LEARNED"));
        if (newExplicit) {
            return true;
        }
        return false;
    }

    public synchronized List<BedPOI> getBeds() {
        return Collections.unmodifiableList(new ArrayList<>(beds));
    }

    public synchronized List<GenPOI> getGens() {
        return Collections.unmodifiableList(new ArrayList<>(gens));
    }

    public synchronized List<SpawnPOI> getSpawns() {
        return Collections.unmodifiableList(new ArrayList<>(spawns));
    }

    public synchronized List<ShopNPC> getShopNpcs() {
        return Collections.unmodifiableList(new ArrayList<>(shopNpcs));
    }

    /**
     * Computes a deterministic 64-bit structural fingerprint over solid island footprint &amp; POIs
     * so revisiting the same Bedwars arena recognizes and validates cached maps reliably.
     */
    public synchronized long computeAndStoreStructuralFingerprint() {
        long h = 0xCBF29CE484222325L;
        h = mix64(h, center2X);
        h = mix64(h, center2Z);
        for (int i = 0; i < originalMapBits.length; i++) {
            long w = originalMapBits[i];
            if (w != 0L) {
                h = mix64(h, ((long) i << 16) ^ Long.bitCount(w));
                h = mix64(h, w);
            }
        }
        this.structuralFingerprint = h;
        return h;
    }

    long[] originalWordsUnsafe() {
        return originalMapBits;
    }

    long[] scannedChunkWordsUnsafe() {
        return scannedChunksBits;
    }

    private static long mix64(long h, long v) {
        h ^= v;
        h *= 0x100000001B3L;
        return h ^ (h >>> 31);
    }

    private static long packPos(int x, int y, int z) {
        return (((long) x & 0x3FFFFFFL) << 38) | (((long) z & 0x3FFFFFFL) << 12) | ((long) y & 0xFFFL);
    }

    private static boolean getBit(long[] words, int idx) {
        int word = idx >>> 6;
        return word >= 0 && word < words.length && ((words[word] & (1L << (idx & 63))) != 0L);
    }

    private static void setBit(long[] words, int idx, boolean val) {
        int word = idx >>> 6;
        if (word < 0 || word >= words.length) {
            return;
        }
        long mask = 1L << (idx & 63);
        if (val) {
            words[word] |= mask;
        } else {
            words[word] &= ~mask;
        }
    }
}
