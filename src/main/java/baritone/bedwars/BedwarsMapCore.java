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

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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

    public static final int INFLUENCE_GRID_SIZE = 272;

    /**
     * Cached scouted defense summary per enemy BedPOI, invalidated only when a block changes within radius 5 of the bed.
     */
    public record CachedBedDefenseProfile(
            BedwarsDefensePlanner.BedShellScan shellScan,
            BedwarsDefensePlanner.ToolProfile tools
    ) {
        public boolean hasWood() {
            return shellScan.counts().containsKey(BedwarsDefensePlanner.DefenseBlockKind.WOOD);
        }

        public boolean hasEndstone() {
            return shellScan.counts().containsKey(BedwarsDefensePlanner.DefenseBlockKind.ENDSTONE);
        }

        public boolean hasObsidian() {
            return shellScan.counts().containsKey(BedwarsDefensePlanner.DefenseBlockKind.OBSIDIAN);
        }
    }

    private static final VarHandle BIT_WORD = MethodHandles.arrayElementVarHandle(long[].class);

    private final ArenaBounds bounds;
    private final long[] originalMapBits;
    private final long[] playerPlacedBits;
    private final long[] specialNonAirBits;
    private final long[] scannedChunksBits;

    // 272x272 2D Influence Map (centered on arena center): voidExposure (updated on block change) + threatHeat (4-5 Hz)
    private int influenceOriginX;
    private int influenceOriginZ;
    private final float[] voidExposureGrid = new float[INFLUENCE_GRID_SIZE * INFLUENCE_GRID_SIZE];
    private volatile float[] threatHeatGrid = new float[INFLUENCE_GRID_SIZE * INFLUENCE_GRID_SIZE];
    private float[] threatHeatScratch = new float[INFLUENCE_GRID_SIZE * INFLUENCE_GRID_SIZE];
    private volatile boolean voidExposureBuilt = false;

    // Per-bed scouted defense profile cache (key = BlockPos.asLong(headX, headY, headZ)), invalidated within r=5 on block change
    private final java.util.concurrent.ConcurrentHashMap<Long, CachedBedDefenseProfile> bedDefenseCache =
            new java.util.concurrent.ConcurrentHashMap<>();

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
    private final Set<Long> shopExcludedCells = java.util.concurrent.ConcurrentHashMap.newKeySet();

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
        this.specialNonAirBits = new long[wordCount];
        int totalChunks = Math.max(1, bounds.chunkColsX() * bounds.chunkColsZ());
        this.scannedChunksBits = new long[(totalChunks + 63) >>> 6];
        this.center2X = (int) Math.round(centerX * 2.0);
        this.center2Z = (int) Math.round(centerZ * 2.0);
        this.influenceOriginX = (this.center2X >> 1) - (INFLUENCE_GRID_SIZE >> 1);
        this.influenceOriginZ = (this.center2Z >> 1) - (INFLUENCE_GRID_SIZE >> 1);
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
        this.influenceOriginX = (this.center2X >> 1) - (INFLUENCE_GRID_SIZE >> 1);
        this.influenceOriginZ = (this.center2Z >> 1) - (INFLUENCE_GRID_SIZE >> 1);
        this.voidExposureBuilt = false;
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

    /**
     * Lock-free voxel write for single-writer client tick/chunk scans so background A* threads never stall.
     */
    public void setOriginalBlock(int x, int y, int z, boolean solid, boolean excludeFromSymmetry) {
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

    public void setPlayerPlaced(int x, int y, int z, boolean placed) {
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

    /**
     * Fast ~2ns bitset lookup: returns {@code true} when {@code (x, y, z)} is recorded as solid
     * (either original permanent arena terrain or a live player-placed block).
     */
    public boolean isKnownSolidBitset(int x, int y, int z) {
        if (!bounds.contains(x, y, z)) {
            return false;
        }
        int idx = bounds.index(x, y, z);
        return getBit(originalMapBits, idx) || getBit(playerPlacedBits, idx);
    }

    public void setSpecialNonAir(int x, int y, int z, boolean special) {
        if (!bounds.contains(x, y, z)) {
            return;
        }
        setBit(specialNonAirBits, bounds.index(x, y, z), special);
    }

    /**
     * Fast ~2ns bitset short-circuit for pure air checks inside already-scanned chunks (avoids chunk/BlockState lookups).
     * Excludes beds, barriers, and fluids tracked in {@code specialNonAirBits} so water/beds are never misclassified as air.
     */
    public boolean isKnownAirInScannedChunk(int x, int y, int z) {
        if (!bounds.contains(x, y, z) || !isChunkScanned(x >> 4, z >> 4)) {
            return false;
        }
        int idx = bounds.index(x, y, z);
        return !getBit(originalMapBits, idx) && !getBit(playerPlacedBits, idx) && !getBit(specialNonAirBits, idx);
    }

    /** Removes inferred terrain before replacing a chunk with a live snapshot. */
    void clearInferredChunk(int chunkX, int chunkZ) {
        int minX = Math.max(bounds.minX, chunkX << 4);
        int minZ = Math.max(bounds.minZ, chunkZ << 4);
        int maxX = Math.min(bounds.minX + bounds.sizeX, (chunkX + 1) << 4);
        int maxZ = Math.min(bounds.minZ + bounds.sizeZ, (chunkZ + 1) << 4);
        for (int y = bounds.minY; y < bounds.minY + bounds.sizeY; y++) {
            for (int z = minZ; z < maxZ && minX < maxX; z++) {
                int index = bounds.index(minX, y, z);
                int shift = index & 63;
                long mask = (1L << (maxX - minX)) - 1L;
                BIT_WORD.getAndBitwiseAnd(originalMapBits, index >>> 6, ~(mask << shift));
                if (shift + maxX - minX > Long.SIZE) {
                    BIT_WORD.getAndBitwiseAnd(originalMapBits, (index >>> 6) + 1, ~(mask >>> (Long.SIZE - shift)));
                }
            }
        }
        for (BedPOI bed : bedsSnapshot) {
            if ((bed.x() >= minX - 3 && bed.x() < maxX + 3 && bed.z() >= minZ - 3 && bed.z() < maxZ + 3)
                    || (bed.footX() >= minX - 3 && bed.footX() < maxX + 3 && bed.footZ() >= minZ - 3 && bed.footZ() < maxZ + 3)) {
                bedDefenseCache.remove(packPos(bed.x(), bed.y(), bed.z()));
            }
        }
    }

    public void markChunkScanned(int chunkX, int chunkZ) {
        int ci = bounds.chunkIndex(chunkX, chunkZ);
        if (ci >= 0) {
            setBit(scannedChunksBits, ci, true);
        }
    }

    public boolean isChunkScanned(int chunkX, int chunkZ) {
        int ci = bounds.chunkIndex(chunkX, chunkZ);
        return ci >= 0 && (((long) BIT_WORD.getAcquire(scannedChunksBits, ci >>> 6)) & (1L << (ci & 63))) != 0L;
    }

    /**
     * Reconstructs unloaded distant island geometry using exact half-coordinate rotational/mirror symmetry
     * around {@code (center2X, center2Z)}, skipping zero long words via {@link Long#numberOfTrailingZeros}
     * (~64x faster on sparse sky-island maps) and never overwriting genuinely scanned chunks.
     */
    public synchronized int applySymmetryReconstruction(SymmetryMode mode) {
        this.symmetryMode = mode;
        if (mode == null || mode == SymmetryMode.NONE) {
            return 0;
        }
        int added = 0;
        int sizeX = bounds.sizeX;
        int sizeZ = bounds.sizeZ;
        int planeArea = sizeX * sizeZ;
        int totalVoxels = (int) bounds.voxelCount();
        boolean octahedral = mode == SymmetryMode.EIGHT_WAY_OCTAHEDRAL;

        for (int w = 0; w < originalMapBits.length; w++) {
            long word = originalMapBits[w];
            if (word == 0L) {
                continue;
            }
            int baseIdx = w << 6;
            while (word != 0L) {
                int bit = Long.numberOfTrailingZeros(word);
                word &= (word - 1L);
                int idx = baseIdx + bit;
                if (idx >= totalVoxels) {
                    break;
                }
                int ly = idx / planeArea;
                int rem = idx - ly * planeArea;
                int lz = rem / sizeX;
                int lx = rem - lz * sizeX;
                int x = bounds.minX + lx;
                int y = bounds.minY + ly;
                int z = bounds.minZ + lz;
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
        this.voidExposureBuilt = false;
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
        int cachedEmCount = 0;
        int cachedDiaCount = 0;
        for (GenPOI cg : cached.getGens()) {
            if (cg.type() == GenType.EMERALD) cachedEmCount++;
            else if (cg.type() == GenType.DIAMOND) cachedDiaCount++;
        }
        boolean poisonedCachedEm = cachedEmCount > 8;
        boolean poisonedCachedDia = cachedDiaCount > 10;
        for (GenPOI cg : cached.getGens()) {
            if ((cg.type() == GenType.EMERALD && poisonedCachedEm)
                    || (cg.type() == GenType.DIAMOND && poisonedCachedDia)) {
                continue;
            }
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
        // Refresh volatile snapshot arrays so hot-path checks immediately see cache-loaded beds/gens/spawns/shops!
        this.bedsSnapshot = beds.toArray(new BedPOI[0]);
        this.gensSnapshot = gens.toArray(new GenPOI[0]);
        this.spawnsSnapshot = spawns.toArray(new SpawnPOI[0]);
        this.shopNpcsSnapshot = shopNpcs.toArray(new ShopNPC[0]);
        this.voidExposureBuilt = false;
        return mergedBlocks;
    }

    // -------------------------------------------------------------------------
    // 272x272 2D Influence Map (voidExposure + threatHeat) & Bed Defense Cache
    // -------------------------------------------------------------------------

    private int influenceIndex(int x, int z) {
        int lx = x - influenceOriginX;
        int lz = z - influenceOriginZ;
        if (lx < 0 || lx >= INFLUENCE_GRID_SIZE || lz < 0 || lz >= INFLUENCE_GRID_SIZE) {
            return -1;
        }
        return lz * INFLUENCE_GRID_SIZE + lx;
    }

    private boolean hasAnySolidColumnInArena(int x, int z) {
        if (x < bounds.minX || x >= bounds.minX + bounds.sizeX
                || z < bounds.minZ || z >= bounds.minZ + bounds.sizeZ) {
            return false;
        }
        int minY = Math.max(bounds.minY, voidFloorY);
        int maxY = Math.min(bounds.minY + bounds.sizeY, Math.max(minY + 1, mapMaxBuildY + 1));
        for (int y = minY; y < maxY; y++) {
            int idx = bounds.index(x, y, z);
            if (getBit(originalMapBits, idx) || getBit(playerPlacedBits, idx)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Recomputes the 272x272 {@code voidExposureGrid} from current solid columns (O(272^2) once per scan/merge).
     * {@code 0.0} = safely surrounded by solid ground; {@code 1.0} = directly over void or narrow 1-wide void bridge.
     */
    public void rebuildVoidExposureGrid() {
        boolean[] hasCol = new boolean[INFLUENCE_GRID_SIZE * INFLUENCE_GRID_SIZE];
        for (int lz = 0; lz < INFLUENCE_GRID_SIZE; lz++) {
            int wz = influenceOriginZ + lz;
            int row = lz * INFLUENCE_GRID_SIZE;
            for (int lx = 0; lx < INFLUENCE_GRID_SIZE; lx++) {
                hasCol[row + lx] = hasAnySolidColumnInArena(influenceOriginX + lx, wz);
            }
        }
        for (int lz = 0; lz < INFLUENCE_GRID_SIZE; lz++) {
            int row = lz * INFLUENCE_GRID_SIZE;
            for (int lx = 0; lx < INFLUENCE_GRID_SIZE; lx++) {
                if (!hasCol[row + lx]) {
                    voidExposureGrid[row + lx] = 1.0f;
                    continue;
                }
                int voidNeighbors = 0;
                int totalNeighbors = 0;
                for (int dz = -2; dz <= 2; dz++) {
                    int nz = lz + dz;
                    if (nz < 0 || nz >= INFLUENCE_GRID_SIZE) continue;
                    int nRow = nz * INFLUENCE_GRID_SIZE;
                    for (int dx = -2; dx <= 2; dx++) {
                        if (dx == 0 && dz == 0) continue;
                        int nx = lx + dx;
                        if (nx < 0 || nx >= INFLUENCE_GRID_SIZE) continue;
                        totalNeighbors++;
                        if (!hasCol[nRow + nx]) {
                            voidNeighbors++;
                        }
                    }
                }
                voidExposureGrid[row + lx] = totalNeighbors > 0 ? ((float) voidNeighbors / (float) totalNeighbors) : 0.0f;
            }
        }
        this.voidExposureBuilt = true;
    }

    /**
     * Incrementally updates {@code voidExposureGrid} in a 5x5 neighborhood around a live block change.
     */
    public void updateVoidExposureNear(int wx, int wz) {
        if (!voidExposureBuilt) {
            return;
        }
        for (int dz = -2; dz <= 2; dz++) {
            for (int dx = -2; dx <= 2; dx++) {
                int cx = wx + dx;
                int cz = wz + dz;
                int gi = influenceIndex(cx, cz);
                if (gi < 0) continue;
                if (!hasAnySolidColumnInArena(cx, cz)) {
                    voidExposureGrid[gi] = 1.0f;
                    continue;
                }
                int voidN = 0;
                int totN = 0;
                for (int oz = -2; oz <= 2; oz++) {
                    for (int ox = -2; ox <= 2; ox++) {
                        if (ox == 0 && oz == 0) continue;
                        totN++;
                        if (!hasAnySolidColumnInArena(cx + ox, cz + oz)) {
                            voidN++;
                        }
                    }
                }
                voidExposureGrid[gi] = totN > 0 ? ((float) voidN / (float) totN) : 0.0f;
            }
        }
    }

    /**
     * Updates the 272x272 {@code threatHeatGrid} (called at 4-5 Hz) from live + last-seen enemy positions & gear weights.
     * {@code enemyCoords} packs {@code [x0, y0, z0, x1, y1, z1, ...]}, {@code weights} has per-enemy gear danger in [0.5..2.0].
     */
    public void updateThreatHeatGrid(double[] enemyCoords, float[] weights, int count) {
        float[] heatGrid = threatHeatScratch;
        Arrays.fill(heatGrid, 0.0f);
        int available = enemyCoords == null ? 0 : Math.min(count, enemyCoords.length / 3);
        int radius = 12;
        float invR = 1.0f / radius;
        for (int i = 0; i < available; i++) {
            int ex = (int) Math.floor(enemyCoords[i * 3]);
            int ez = (int) Math.floor(enemyCoords[i * 3 + 2]);
            float w = (weights != null && i < weights.length) ? Math.max(0.4f, Math.min(2.5f, weights[i])) : 1.0f;
            for (int dz = -radius; dz <= radius; dz++) {
                int lz = (ez + dz) - influenceOriginZ;
                if (lz < 0 || lz >= INFLUENCE_GRID_SIZE) continue;
                int row = lz * INFLUENCE_GRID_SIZE;
                for (int dx = -radius; dx <= radius; dx++) {
                    int lx = (ex + dx) - influenceOriginX;
                    if (lx < 0 || lx >= INFLUENCE_GRID_SIZE) continue;
                    int d2 = dx * dx + dz * dz;
                    if (d2 > radius * radius) continue;
                    float falloff = 1.0f - ((float) Math.sqrt(d2) * invR);
                    float heat = w * falloff * falloff;
                    int idx = row + lx;
                    heatGrid[idx] = Math.min(3.0f, heatGrid[idx] + heat);
                }
            }
        }
        threatHeatScratch = threatHeatGrid;
        threatHeatGrid = heatGrid;
    }

    public float sampleVoidExposure(int x, int z) {
        if (!voidExposureBuilt) {
            return 0.0f;
        }
        int gi = influenceIndex(x, z);
        return gi >= 0 ? voidExposureGrid[gi] : 0.5f;
    }

    public float sampleThreatHeat(int x, int z) {
        int gi = influenceIndex(x, z);
        return gi >= 0 ? threatHeatGrid[gi] : 0.0f;
    }

    /**
     * Unified O(1) BedWars danger cost queried by ALL A* Movement* classes (Traverse, Diagonal, Ascend, Descend).
     * Penalizes walking onto void-exposed bridges/edges near enemies without breaking normal island movement.
     */
    public double computeInfluenceDangerCost(int x, int y, int z) {
        int gi = influenceIndex(x, z);
        if (gi < 0) {
            return 0.0;
        }
        float heat = threatHeatGrid[gi];
        float voidExp = voidExposureBuilt ? voidExposureGrid[gi] : 0.0f;
        if (heat <= 0.02f) {
            return 0.0;
        }
        // On solid wide ground (voidExp ~ 0) enemy heat is a mild tactical detour;
        // on narrow bridges/void ledges (voidExp >= 0.45), enemy knockback danger scales up sharply!
        return heat * (3.5 + voidExp * 18.0);
    }

    /**
     * Computes a normalized horizontal gradient pointing AWAY from void exposure toward solid ground
     * around {@code (x, z)} so PvP strafing keeps solid blocks behind our back and knocks enemies off edges.
     */
    public boolean computeVoidSafeDirection(int x, int z, double[] outDxDz) {
        if (!voidExposureBuilt || outDxDz == null || outDxDz.length < 2) {
            return false;
        }
        float gx = sampleVoidExposure(x - 1, z) - sampleVoidExposure(x + 1, z);
        float gz = sampleVoidExposure(x, z - 1) - sampleVoidExposure(x, z + 1);
        double mag = Math.hypot(gx, gz);
        if (mag < 0.06) {
            outDxDz[0] = 0.0;
            outDxDz[1] = 0.0;
            return false;
        }
        outDxDz[0] = gx / mag;
        outDxDz[1] = gz / mag;
        return true;
    }

    public CachedBedDefenseProfile getCachedBedDefense(BedPOI bed) {
        return bed != null ? bedDefenseCache.get(packPos(bed.x(), bed.y(), bed.z())) : null;
    }

    public void putCachedBedDefense(BedPOI bed, CachedBedDefenseProfile profile) {
        if (bed != null && profile != null) {
            bedDefenseCache.put(packPos(bed.x(), bed.y(), bed.z()), profile);
        }
    }

    /**
     * Invalidates cached bed-shell scans & raycasts ONLY for beds within {@code radius} blocks of {@code (x, y, z)}.
     */
    public void invalidateBedScanCacheNear(int x, int y, int z, int radius) {
        if (bedDefenseCache.isEmpty()) {
            return;
        }
        int rSq = radius * radius;
        for (BedPOI bed : bedsSnapshot) {
            int dx = x - bed.x();
            int dy = y - bed.y();
            int fdy = y - bed.footY();
            int dz = z - bed.z();
            int fdx = x - bed.footX();
            int fdz = z - bed.footZ();
            if ((dx * dx + dy * dy + dz * dz <= rSq) || (fdx * fdx + fdy * fdy + fdz * fdz <= rSq)) {
                bedDefenseCache.remove(packPos(bed.x(), bed.y(), bed.z()));
            }
        }
    }

    /**
     * Returns whether any living enemy bed has a scouted hard defense (wood, endstone, or obsidian) cached.
     */
    public boolean anyEnemyBedHasDefense(BedwarsDefensePlanner.DefenseBlockKind kind) {
        for (BedPOI bed : bedsSnapshot) {
            if (bed.isHome() || bed.destroyed()) continue;
            CachedBedDefenseProfile prof = getCachedBedDefense(bed);
            if (prof == null) continue;
            if (kind == BedwarsDefensePlanner.DefenseBlockKind.OBSIDIAN && prof.hasObsidian()) return true;
            if (kind == BedwarsDefensePlanner.DefenseBlockKind.ENDSTONE && prof.hasEndstone()) return true;
            if (kind == BedwarsDefensePlanner.DefenseBlockKind.WOOD && prof.hasWood()) return true;
        }
        return false;
    }

    public boolean anyEnemyBedHasMultiLayerDefense() {
        for (BedPOI bed : bedsSnapshot) {
            if (bed.isHome() || bed.destroyed()) continue;
            CachedBedDefenseProfile prof = getCachedBedDefense(bed);
            if (prof != null && prof.shellScan().layersDetected() >= 2) return true;
        }
        return false;
    }

    /**
     * Edge Case A: Invisible No-Place exclusion zones protecting Spawn points (r=6), Shop NPCs (r=3),
     * and Diamond/Emerald Generators (r=3) for general pathing &amp; bridging.
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

    /**
     * Bed-defense placement check: allows placing full bed defense shells even when a team spawn
     * cage is nearby, while still avoiding direct overlap with Shop NPCs or Diamond/Emerald gens.
     */
    public boolean isBlockedForBedDefense(int x, int y, int z) {
        if (isNearAnyBedDefenseEnvelope(x, y, z)) {
            return false;
        }
        for (ShopNPC npc : shopNpcsSnapshot) {
            if (y >= npc.y - 1 && y <= npc.y + 3) {
                int dx = x - npc.x;
                int dz = z - npc.z;
                if (dx * dx + dz * dz <= 2) {
                    return true;
                }
            }
        }
        for (GenPOI gen : gensSnapshot) {
            if (gen.type != GenType.HOME_FORGE && y >= gen.y - 1 && y <= gen.y + 3) {
                int dx = x - gen.x;
                int dz = z - gen.z;
                if (dx * dx + dz * dz <= 4) {
                    return true;
                }
            }
        }
        return false;
    }

    public boolean isNearAnyBedDefenseEnvelope(int x, int y, int z) {
        for (BedPOI bed : bedsSnapshot) {
            if (y >= bed.y - 1 && y <= bed.y + 3) {
                if ((Math.abs(x - bed.x) <= 2 && Math.abs(z - bed.z) <= 2)
                        || (Math.abs(x - bed.footX) <= 2 && Math.abs(z - bed.footZ) <= 2)) {
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

    /**
     * Returns {@code true} when {@code (x, y, z)} lies inside the defensive shell surrounding our
     * living home bed so pathfinding never mines through our own End Stone / Wool bed defense.
     */
    public boolean isHomeBedDefenseShell(int x, int y, int z) {
        for (BedPOI bed : bedsSnapshot) {
            if (!bed.isHome || bed.destroyed) {
                continue;
            }
            if (y < bed.y || y > bed.y + 3) {
                continue;
            }
            int hdx = x - bed.x;
            int hdz = z - bed.z;
            int fdx = x - bed.footX;
            int fdz = z - bed.footZ;
            if ((hdx * hdx + hdz * hdz <= 9) || (fdx * fdx + fdz * fdz <= 9)) {
                return true;
            }
        }
        return false;
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

    /**
     * Guarantees at most ONE {@link BedPOI} is marked {@code isHome = true} and corrects
     * duplicated or missing team tags when live chat/scoreboard reveals our actual team.
     */
    public synchronized BedPOI assignSingleHomeBed(TeamColor targetTeam, int anchorX, int anchorY, int anchorZ) {
        if (targetTeam != null && targetTeam != TeamColor.UNKNOWN) {
            this.homeTeam = targetTeam;
        }
        if (beds.isEmpty()) {
            return null;
        }
        boolean hasAnchor = anchorX != Integer.MIN_VALUE;
        int bestIdx = -1;
        double bestDistSq = Double.POSITIVE_INFINITY;

        if (hasAnchor) {
            for (int i = 0; i < beds.size(); i++) {
                BedPOI b = beds.get(i);
                double dx = b.x() - anchorX;
                double dy = b.y() - anchorY;
                double dz = b.z() - anchorZ;
                double dSq = dx * dx + dy * dy * 2.0 + dz * dz;
                if (dSq <= 900.0 && dSq < bestDistSq) {
                    bestDistSq = dSq;
                    bestIdx = i;
                }
            }
        }
        if (bestIdx < 0 && this.homeTeam != TeamColor.UNKNOWN) {
            for (int i = 0; i < beds.size(); i++) {
                BedPOI b = beds.get(i);
                if (b.team() == this.homeTeam) {
                    bestIdx = i;
                    break;
                }
            }
        }
        if (bestIdx < 0) {
            return null;
        }

        BedPOI chosen = beds.get(bestIdx);
        TeamColor chosenTeam = (this.homeTeam != TeamColor.UNKNOWN) ? this.homeTeam : chosen.team();
        for (int i = 0; i < beds.size(); i++) {
            BedPOI b = beds.get(i);
            if (i == bestIdx) {
                beds.set(i, new BedPOI(chosenTeam, b.x(), b.y(), b.z(), b.footX(), b.footY(), b.footZ(), true, b.destroyed()));
            } else {
                // Demote duplicated homeTeam tags caused by map terracotta so all other beds remain rushable targets
                TeamColor fixedTeam = (this.homeTeam != TeamColor.UNKNOWN && b.team() == this.homeTeam)
                        ? TeamColor.UNKNOWN
                        : b.team();
                beds.set(i, new BedPOI(fixedTeam, b.x(), b.y(), b.z(), b.footX(), b.footY(), b.footZ(), false, b.destroyed()));
            }
        }
        bedsSnapshot = beds.toArray(new BedPOI[0]);
        return beds.get(bestIdx);
    }

    public synchronized void addGen(GenPOI gen) {
        gens.add(gen);
        gensSnapshot = gens.toArray(new GenPOI[0]);
    }

    /**
     * Replaces a temporary spawn-derived {@code HOME_FORGE} fallback once real generator
     * iron/gold ingots are observed on the home island.
     */
    public synchronized void addOrUpgradeHomeForge(GenPOI realForge) {
        if (realForge == null || realForge.type() != GenType.HOME_FORGE) {
            return;
        }
        for (int i = 0; i < gens.size(); i++) {
            GenPOI g = gens.get(i);
            if (g.type() == GenType.HOME_FORGE) {
                if ("Team Forge (Home Spawn)".equals(g.label())) {
                    gens.set(i, realForge);
                    gensSnapshot = gens.toArray(new GenPOI[0]);
                    return;
                }
                if (Math.abs(g.x() - realForge.x()) <= 3
                        && Math.abs(g.y() - realForge.y()) <= 4
                        && Math.abs(g.z() - realForge.z()) <= 3) {
                    return;
                }
            }
        }
        gens.add(realForge);
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

    public static TeamColor selectBedTeamFromVotes(
            Map<TeamColor, Integer> votes,
            TeamColor bedBlockColor,
            boolean hasWoolVote,
            boolean hasGrayWoolVote
    ) {
        if (bedBlockColor != null && bedBlockColor != TeamColor.UNKNOWN && bedBlockColor != TeamColor.RED) {
            votes.merge(bedBlockColor, 12, Integer::sum);
        }
        TeamColor best = TeamColor.UNKNOWN;
        int bestVotes = 0;
        for (Map.Entry<TeamColor, Integer> e : votes.entrySet()) {
            if (e.getKey() == TeamColor.GRAY
                    && !hasGrayWoolVote
                    && e.getValue() < 8
                    && (hasWoolVote || (bedBlockColor != null && bedBlockColor != TeamColor.UNKNOWN))) {
                continue;
            }
            if (e.getValue() > bestVotes) {
                bestVotes = e.getValue();
                best = e.getKey();
            }
        }
        if (best != TeamColor.UNKNOWN) {
            return best;
        }
        return bedBlockColor != null ? bedBlockColor : TeamColor.UNKNOWN;
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

    /** Published snapshot for read-only iteration. */
    public BedPOI[] getBedsSnapshot() {
        return bedsSnapshot;
    }

    /** Published snapshot for read-only iteration. */
    public GenPOI[] getGensSnapshot() {
        return gensSnapshot;
    }

    /** Published snapshot for read-only iteration. */
    public SpawnPOI[] getSpawnsSnapshot() {
        return spawnsSnapshot;
    }

    /** Published snapshot for read-only iteration. */
    public ShopNPC[] getShopNpcsSnapshot() {
        return shopNpcsSnapshot;
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
        return word >= 0 && word < words.length && ((((long) BIT_WORD.getOpaque(words, word)) & (1L << (idx & 63))) != 0L);
    }

    private static void setBit(long[] words, int idx, boolean val) {
        int word = idx >>> 6;
        if (word < 0 || word >= words.length) {
            return;
        }
        long mask = 1L << (idx & 63);
        if (val) {
            BIT_WORD.getAndBitwiseOr(words, word, mask);
        } else {
            BIT_WORD.getAndBitwiseAnd(words, word, ~mask);
        }
    }
}
