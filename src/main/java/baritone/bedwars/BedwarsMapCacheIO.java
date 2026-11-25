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

import baritone.bedwars.BedwarsMapCore.ArenaBounds;
import baritone.bedwars.BedwarsMapCore.BedPOI;
import baritone.bedwars.BedwarsMapCore.GenPOI;
import baritone.bedwars.BedwarsMapCore.GenType;
import baritone.bedwars.BedwarsMapCore.ShopNPC;
import baritone.bedwars.BedwarsMapCore.SpawnPOI;
import baritone.bedwars.BedwarsMapCore.SymmetryMode;
import baritone.bedwars.BedwarsMapCore.TeamColor;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Ultra-compact GZIP + sparse RLE persistence layer for {@link BedwarsMapCore} keyed by
 * server identifier, normalized map name, and 64-bit structural fingerprint under
 * {@code <pathadolDir>/bedwars_cache/*.bwsmap}.
 */
public final class BedwarsMapCacheIO {

    private static final int MAGIC = 0x42575331; // "BWS1"
    private static final int VERSION = 1;

    private BedwarsMapCacheIO() {}

    public record CachedMapEntry(
            Path file,
            String serverId,
            String mapName,
            long structuralFingerprint,
            long savedAtMs,
            long fileBytes
    ) {}

    public static String sanitizeSlug(String raw) {
        if (raw == null || raw.isBlank()) {
            return "unknown";
        }
        StringBuilder sb = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = Character.toLowerCase(raw.charAt(i));
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) {
                sb.append(c);
            } else if (c == '.' || c == '-' || c == '_' || c == ' ') {
                if (sb.length() > 0 && sb.charAt(sb.length() - 1) != '_') {
                    sb.append('_');
                }
            }
        }
        while (sb.length() > 0 && sb.charAt(sb.length() - 1) == '_') {
            sb.setLength(sb.length() - 1);
        }
        return sb.length() == 0 ? "unknown" : sb.toString();
    }

    public static Path buildCacheFilePath(Path cacheDir, String serverId, String mapName, long structuralFingerprint) {
        String srvSlug = sanitizeSlug(serverId);
        String mapSlug = (mapName == null || mapName.isBlank() || "unknown".equalsIgnoreCase(mapName))
                ? ("fp_" + Long.toUnsignedString(structuralFingerprint, 16))
                : sanitizeSlug(mapName);
        return cacheDir.resolve(srvSlug + "__" + mapSlug + ".bwsmap");
    }

    record SaveData(
            long fingerprint, ArenaBounds bounds, int center2X, int center2Z, int voidFloorY, int maxBuildY,
            SymmetryMode symmetry, List<BedPOI> beds, List<GenPOI> gens, List<SpawnPOI> spawns, List<ShopNPC> npcs,
            long[] scanned, long[] original
    ) {}

    static SaveData snapshotForSave(BedwarsMapCore core) {
        if (core == null || core.getBeds().isEmpty()) {
            return null;
        }
        long fingerprint = core.structuralFingerprint();
        if (fingerprint == 0L) {
            fingerprint = core.computeAndStoreStructuralFingerprint();
        }
        return new SaveData(fingerprint, core.bounds(), core.center2X(), core.center2Z(), core.voidFloorY(),
                core.mapMaxBuildY(), core.symmetryMode(), List.copyOf(core.getBeds()), List.copyOf(core.getGens()),
                List.copyOf(core.getSpawns()), List.copyOf(core.getShopNpcs()),
                core.scannedChunkWordsUnsafe().clone(), core.originalWordsUnsafe().clone());
    }

    public static Path save(Path cacheDir, String serverId, String mapName, BedwarsMapCore core) throws IOException {
        return saveSnapshot(cacheDir, serverId, mapName, snapshotForSave(core));
    }

    static Path saveSnapshot(Path cacheDir, String serverId, String mapName, SaveData snapshot) throws IOException {
        if (snapshot == null) {
            return null;
        }
        Files.createDirectories(cacheDir);
        long fp = snapshot.fingerprint();
        Path outFile = buildCacheFilePath(cacheDir, serverId, mapName, fp);
        Path staging = Files.createTempFile(cacheDir, ".pathadol-map-", ".tmp");
        try {
            writeSnapshot(staging, serverId, mapName, snapshot);
            try {
                Files.move(staging, outFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(staging, outFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(staging);
        }
        return outFile;
    }

    private static void writeSnapshot(Path staging, String serverId, String mapName, SaveData snapshot) throws IOException {
        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(new GZIPOutputStream(Files.newOutputStream(staging))))) {
            out.writeInt(MAGIC);
            out.writeInt(VERSION);
            out.writeUTF(serverId != null ? serverId : "unknown");
            out.writeUTF(mapName != null ? mapName : "unknown");
            out.writeLong(snapshot.fingerprint());
            out.writeLong(System.currentTimeMillis());

            ArenaBounds b = snapshot.bounds();
            out.writeInt(b.minX());
            out.writeInt(b.minY());
            out.writeInt(b.minZ());
            out.writeInt(b.sizeX());
            out.writeInt(b.sizeY());
            out.writeInt(b.sizeZ());
            out.writeInt(snapshot.center2X());
            out.writeInt(snapshot.center2Z());
            out.writeInt(snapshot.voidFloorY());
            out.writeInt(snapshot.maxBuildY());
            out.writeByte(snapshot.symmetry().ordinal());

            List<BedPOI> beds = snapshot.beds();
            out.writeInt(beds.size());
            for (BedPOI bed : beds) {
                out.writeByte(bed.team().ordinal());
                out.writeInt(bed.x());
                out.writeInt(bed.y());
                out.writeInt(bed.z());
                out.writeInt(bed.footX());
                out.writeInt(bed.footY());
                out.writeInt(bed.footZ());
            }

            List<GenPOI> gens = snapshot.gens();
            out.writeInt(gens.size());
            for (GenPOI gen : gens) {
                out.writeByte(gen.type().ordinal());
                out.writeInt(gen.x());
                out.writeInt(gen.y());
                out.writeInt(gen.z());
                out.writeUTF(gen.label() != null ? gen.label() : "");
            }

            List<SpawnPOI> spawns = snapshot.spawns();
            out.writeInt(spawns.size());
            for (SpawnPOI sp : spawns) {
                out.writeByte(sp.team().ordinal());
                out.writeInt(sp.x());
                out.writeInt(sp.y());
                out.writeInt(sp.z());
            }

            List<ShopNPC> npcs = snapshot.npcs();
            out.writeInt(npcs.size());
            for (ShopNPC npc : npcs) {
                out.writeUTF(npc.label() != null ? npc.label() : "shop");
                out.writeInt(npc.x());
                out.writeInt(npc.y());
                out.writeInt(npc.z());
            }

            long[] scanned = snapshot.scanned();
            out.writeInt(scanned.length);
            for (long w : scanned) {
                out.writeLong(w);
            }

            // Sparse zero-run RLE encoding of originalMapBits prior to GZIP compression
            long[] words = snapshot.original();
            out.writeInt(words.length);
            int i = 0;
            while (i < words.length) {
                int zeroRun = 0;
                while (i < words.length && words[i] == 0L) {
                    zeroRun++;
                    i++;
                }
                int nonZeroStart = i;
                while (i < words.length && words[i] != 0L) {
                    i++;
                }
                int nonZeroLen = i - nonZeroStart;
                out.writeInt(zeroRun);
                out.writeInt(nonZeroLen);
                for (int k = 0; k < nonZeroLen; k++) {
                    out.writeLong(words[nonZeroStart + k]);
                }
            }
        }
    }

    public static LoadedCache load(Path file) throws IOException {
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new GZIPInputStream(Files.newInputStream(file))))) {
            int magic = in.readInt();
            int version = in.readInt();
            if (magic != MAGIC || version != VERSION) {
                throw new IOException("Unsupported bwsmap header");
            }
            String serverId = in.readUTF();
            String mapName = in.readUTF();
            long fp = in.readLong();
            long savedAt = in.readLong();

            ArenaBounds bounds = new ArenaBounds(
                    in.readInt(), in.readInt(), in.readInt(),
                    in.readInt(), in.readInt(), in.readInt()
            );
            int c2x = in.readInt();
            int c2z = in.readInt();
            int voidFloorY = in.readInt();
            int maxBuildY = in.readInt();
            int symIdx = Byte.toUnsignedInt(in.readByte());
            SymmetryMode sym = symIdx < SymmetryMode.values().length ? SymmetryMode.values()[symIdx] : SymmetryMode.FOUR_WAY_ROTATIONAL;

            BedwarsMapCore core = new BedwarsMapCore(bounds, c2x * 0.5, c2z * 0.5, voidFloorY, maxBuildY);
            core.setSymmetryMode(sym);
            core.setStructuralFingerprint(fp);

            int bedCount = in.readInt();
            for (int i = 0; i < bedCount; i++) {
                int tIdx = Byte.toUnsignedInt(in.readByte());
                TeamColor tc = tIdx < TeamColor.values().length ? TeamColor.values()[tIdx] : TeamColor.UNKNOWN;
                core.addBed(new BedPOI(tc, in.readInt(), in.readInt(), in.readInt(), in.readInt(), in.readInt(), in.readInt(), false, false));
            }

            int genCount = in.readInt();
            for (int i = 0; i < genCount; i++) {
                int gIdx = Byte.toUnsignedInt(in.readByte());
                GenType gt = gIdx < GenType.values().length ? GenType.values()[gIdx] : GenType.DIAMOND;
                core.addGen(new GenPOI(gt, in.readInt(), in.readInt(), in.readInt(), in.readUTF()));
            }

            int spCount = in.readInt();
            for (int i = 0; i < spCount; i++) {
                int tIdx = Byte.toUnsignedInt(in.readByte());
                TeamColor tc = tIdx < TeamColor.values().length ? TeamColor.values()[tIdx] : TeamColor.UNKNOWN;
                core.addSpawn(new SpawnPOI(tc, in.readInt(), in.readInt(), in.readInt()));
            }

            int npcCount = in.readInt();
            for (int i = 0; i < npcCount; i++) {
                core.addShopNpc(new ShopNPC(in.readUTF(), in.readInt(), in.readInt(), in.readInt()));
            }

            int scannedLen = in.readInt();
            long[] scannedDst = core.scannedChunkWordsUnsafe();
            for (int i = 0; i < scannedLen; i++) {
                long w = in.readLong();
                if (i < scannedDst.length) {
                    scannedDst[i] = w;
                }
            }

            int wordLen = in.readInt();
            long[] dstWords = core.originalWordsUnsafe();
            int cursor = 0;
            while (cursor < wordLen) {
                int zeroRun = in.readInt();
                int nonZeroLen = in.readInt();
                cursor += zeroRun;
                for (int k = 0; k < nonZeroLen; k++) {
                    long w = in.readLong();
                    if (cursor < dstWords.length) {
                        dstWords[cursor] = w;
                    }
                    cursor++;
                }
            }
            return new LoadedCache(serverId, mapName, fp, savedAt, core);
        }
    }

    public record LoadedCache(
            String serverId,
            String mapName,
            long structuralFingerprint,
            long savedAtMs,
            BedwarsMapCore core
    ) {}

    public static Optional<LoadedCache> findMatching(
            Path cacheDir,
            String serverId,
            String mapName,
            long structuralFingerprint
    ) {
        if (!Files.isDirectory(cacheDir)) {
            return Optional.empty();
        }
        String srvSlug = sanitizeSlug(serverId);
        // 1. Exact server + map name match
        if (mapName != null && !mapName.isBlank() && !"unknown".equalsIgnoreCase(mapName)) {
            Path direct = cacheDir.resolve(srvSlug + "__" + sanitizeSlug(mapName) + ".bwsmap");
            if (Files.isRegularFile(direct)) {
                try {
                    LoadedCache loaded = load(direct);
                    if (!loaded.core().getBeds().isEmpty()) {
                        return Optional.of(loaded);
                    }
                } catch (IOException ignored) {}
            }
        }
        // 2. Fallback match on server + structural fingerprint if scoreboard/chat map name was absent
        if (structuralFingerprint != 0L) {
            for (CachedMapEntry entry : listEntries(cacheDir)) {
                if (sanitizeSlug(entry.serverId()).equals(srvSlug) && entry.structuralFingerprint() == structuralFingerprint) {
                    try {
                        LoadedCache loaded = load(entry.file());
                        if (!loaded.core().getBeds().isEmpty()) {
                            return Optional.of(loaded);
                        }
                    } catch (IOException ignored) {}
                }
            }
        }
        return Optional.empty();
    }

    public static List<CachedMapEntry> listEntries(Path cacheDir) {
        if (!Files.isDirectory(cacheDir)) {
            return List.of();
        }
        List<CachedMapEntry> list = new ArrayList<>();
        try (var stream = Files.list(cacheDir)) {
            for (Path f : stream.filter(p -> p.getFileName().toString().endsWith(".bwsmap")).toList()) {
                try (DataInputStream in = new DataInputStream(new BufferedInputStream(new GZIPInputStream(Files.newInputStream(f))))) {
                    if (in.readInt() != MAGIC || in.readInt() != VERSION) {
                        continue;
                    }
                    String srv = in.readUTF();
                    String map = in.readUTF();
                    long fp = in.readLong();
                    long saved = in.readLong();
                    long bytes = Files.size(f);
                    list.add(new CachedMapEntry(f, srv, map, fp, saved, bytes));
                } catch (IOException ignored) {}
            }
        } catch (IOException ignored) {}
        list.sort(Comparator.comparingLong(CachedMapEntry::savedAtMs).reversed());
        return list;
    }

    public static int clearAll(Path cacheDir) throws IOException {
        if (!Files.isDirectory(cacheDir)) {
            return 0;
        }
        int deleted = 0;
        try (var stream = Files.list(cacheDir)) {
            for (Path f : stream.filter(p -> p.getFileName().toString().endsWith(".bwsmap")).toList()) {
                if (Files.deleteIfExists(f)) {
                    deleted++;
                }
            }
        }
        return deleted;
    }
}
