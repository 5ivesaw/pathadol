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

import baritone.bedwars.BedwarsMapCore.GenPOI;
import baritone.bedwars.BedwarsMapCore.GenType;
import baritone.bedwars.BedwarsMapCore.ShopNPC;
import baritone.bedwars.BedwarsShopPlanner.BedwarsItemRole;
import baritone.bedwars.BedwarsShopPlanner.CatalogItemEntry;
import baritone.bedwars.BedwarsShopPlanner.CostSpec;
import baritone.bedwars.BedwarsShopPlanner.Currency;
import baritone.bedwars.BedwarsShopPlanner.MenuKind;
import baritone.bedwars.BedwarsShopPlanner.ShopCatalogCache;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Persistent validated knowledge store for Bedwars:
 * <ul>
 *   <li>Per-server shop catalog prices, slots, and tab names ({@code <server>__shop_knowledge.json})</li>
 *   <li>Per-map NPC roles ({@code ITEM_SHOP} vs {@code UPGRADE_SHOP}), verified home forge coordinates,
 *       and repeatedly unplaceable bed-defense offsets ({@code <server>__<map>__knowledge.json})</li>
 * </ul>
 * Sanity-checks learned entries on both write and read to prevent corrupted or hallucinated
 * interactions from poisoning future matches.
 */
public final class BedwarsLearnedKnowledge {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public record LearnedNpcEntry(int x, int y, int z, String role, int confirmations, long updatedAtMs) {}

    public record LearnedForgeEntry(int x, int y, int z, int confirmations, long updatedAtMs) {}

    public record LearnedBlockedDefenseOffset(int dx, int dy, int dz, int failCount, long updatedAtMs) {}

    public static final class ServerShopKnowledge {
        public final Map<BedwarsItemRole, CatalogItemEntry> items = new EnumMap<>(BedwarsItemRole.class);
        public long updatedAtMs = 0L;
    }

    public static final class MapKnowledge {
        public final List<LearnedNpcEntry> npcs = new ArrayList<>();
        public final List<LearnedForgeEntry> homeForges = new ArrayList<>();
        public final List<LearnedBlockedDefenseOffset> blockedDefenseOffsets = new ArrayList<>();
        public long updatedAtMs = 0L;
    }

    private BedwarsLearnedKnowledge() {}

    public static Path serverShopPath(Path cacheDir, String serverId) {
        return cacheDir.resolve(BedwarsMapCacheIO.sanitizeSlug(serverId) + "__shop_knowledge.json");
    }

    public static Path mapKnowledgePath(Path cacheDir, String serverId, String mapName) {
        String srv = BedwarsMapCacheIO.sanitizeSlug(serverId);
        String map = BedwarsMapCacheIO.sanitizeSlug(mapName);
        return cacheDir.resolve(srv + "__" + map + "__knowledge.json");
    }

    public static boolean isPersistableMapName(String mapName) {
        if (mapName == null || mapName.isBlank()) {
            return false;
        }
        String trimmed = mapName.trim().toLowerCase(Locale.ROOT);
        return !"unknown".equals(trimmed) && !trimmed.startsWith("fp_");
    }

    public static boolean isSaneCost(CostSpec cost) {
        return cost != null && cost.currency() != null && cost.amount() >= 1 && cost.amount() <= 128;
    }

    public static ServerShopKnowledge loadServerShop(Path cacheDir, String serverId) {
        ServerShopKnowledge out = new ServerShopKnowledge();
        if (cacheDir == null) {
            return out;
        }
        Path file = serverShopPath(cacheDir, serverId);
        if (!Files.isRegularFile(file)) {
            return out;
        }
        try {
            String raw = Files.readString(file, StandardCharsets.UTF_8);
            JsonObject root = JsonParser.parseString(raw).getAsJsonObject();
            if (root.has("updatedAtMs")) {
                out.updatedAtMs = root.get("updatedAtMs").getAsLong();
            }
            if (root.has("items") && root.get("items").isJsonArray()) {
                for (JsonElement el : root.getAsJsonArray("items")) {
                    if (!el.isJsonObject()) continue;
                    JsonObject obj = el.getAsJsonObject();
                    try {
                        BedwarsItemRole role = BedwarsItemRole.valueOf(obj.get("role").getAsString());
                        int slot = obj.get("slot").getAsInt();
                        int count = obj.has("count") ? Math.max(1, Math.min(64, obj.get("count").getAsInt())) : 1;
                        Currency cur = Currency.valueOf(obj.get("currency").getAsString());
                        int amount = obj.get("amount").getAsInt();
                        CostSpec cost = new CostSpec(cur, amount);
                        if (!isSaneCost(cost) || slot < 0 || slot >= 54) {
                            continue;
                        }
                        String tab = obj.has("tab") ? obj.get("tab").getAsString() : "Quick Buy";
                        String itemPath = obj.has("itemPath") ? obj.get("itemPath").getAsString() : "";
                        String displayName = obj.has("displayName") ? obj.get("displayName").getAsString() : role.name();
                        out.items.put(role, new CatalogItemEntry(role, tab, slot, itemPath, displayName, cost, count));
                    } catch (Exception ignored) {
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    public static void saveServerShop(Path cacheDir, String serverId, ShopCatalogCache catalog) {
        if (cacheDir == null || catalog == null) {
            return;
        }
        Map<BedwarsItemRole, CatalogItemEntry> snapshot = catalog.snapshotEntries();
        if (snapshot.isEmpty()) {
            return;
        }
        ServerShopKnowledge existing = loadServerShop(cacheDir, serverId);
        boolean changed = false;
        for (Map.Entry<BedwarsItemRole, CatalogItemEntry> e : snapshot.entrySet()) {
            CatalogItemEntry entry = e.getValue();
            if (entry == null || !isSaneCost(entry.cost()) || entry.slotIndex() < 0 || entry.slotIndex() >= 54) {
                continue;
            }
            CatalogItemEntry prev = existing.items.get(e.getKey());
            if (!entry.equals(prev)) {
                existing.items.put(e.getKey(), entry);
                changed = true;
            }
        }
        if (!changed) {
            return;
        }
        JsonObject root = new JsonObject();
        root.addProperty("serverId", serverId != null ? serverId : "local");
        root.addProperty("updatedAtMs", System.currentTimeMillis());
        JsonArray arr = new JsonArray();
        for (CatalogItemEntry e : existing.items.values()) {
            JsonObject obj = new JsonObject();
            obj.addProperty("role", e.role().name());
            obj.addProperty("tab", e.tabName() != null ? e.tabName() : "Quick Buy");
            obj.addProperty("slot", e.slotIndex());
            obj.addProperty("itemPath", e.itemPath() != null ? e.itemPath() : "");
            obj.addProperty("displayName", e.displayName() != null ? e.displayName() : "");
            obj.addProperty("currency", e.cost().currency().name());
            obj.addProperty("amount", e.cost().amount());
            obj.addProperty("count", e.stackCount());
            arr.add(obj);
        }
        root.add("items", arr);
        writeAtomic(serverShopPath(cacheDir, serverId), root);
    }

    public static MapKnowledge loadMapKnowledge(Path cacheDir, String serverId, String mapName) {
        MapKnowledge out = new MapKnowledge();
        if (cacheDir == null || !isPersistableMapName(mapName)) {
            return out;
        }
        Path file = mapKnowledgePath(cacheDir, serverId, mapName);
        if (!Files.isRegularFile(file)) {
            return out;
        }
        try {
            String raw = Files.readString(file, StandardCharsets.UTF_8);
            JsonObject root = JsonParser.parseString(raw).getAsJsonObject();
            if (root.has("updatedAtMs")) {
                out.updatedAtMs = root.get("updatedAtMs").getAsLong();
            }
            if (root.has("npcs") && root.get("npcs").isJsonArray()) {
                for (JsonElement el : root.getAsJsonArray("npcs")) {
                    if (!el.isJsonObject()) continue;
                    JsonObject obj = el.getAsJsonObject();
                    String role = obj.has("role") ? obj.get("role").getAsString() : "";
                    if (!"ITEM_SHOP".equals(role) && !"UPGRADE_SHOP".equals(role)) {
                        continue;
                    }
                    int conf = obj.has("confirmations") ? obj.get("confirmations").getAsInt() : 1;
                    if (conf <= 0) continue;
                    out.npcs.add(new LearnedNpcEntry(
                            obj.get("x").getAsInt(), obj.get("y").getAsInt(), obj.get("z").getAsInt(),
                            role, conf, obj.has("updatedAtMs") ? obj.get("updatedAtMs").getAsLong() : 0L
                    ));
                }
            }
            if (root.has("homeForges") && root.get("homeForges").isJsonArray()) {
                for (JsonElement el : root.getAsJsonArray("homeForges")) {
                    if (!el.isJsonObject()) continue;
                    JsonObject obj = el.getAsJsonObject();
                    int conf = obj.has("confirmations") ? obj.get("confirmations").getAsInt() : 1;
                    if (conf <= 0) continue;
                    out.homeForges.add(new LearnedForgeEntry(
                            obj.get("x").getAsInt(), obj.get("y").getAsInt(), obj.get("z").getAsInt(),
                            conf, obj.has("updatedAtMs") ? obj.get("updatedAtMs").getAsLong() : 0L
                    ));
                }
            }
            if (root.has("blockedDefenseOffsets") && root.get("blockedDefenseOffsets").isJsonArray()) {
                for (JsonElement el : root.getAsJsonArray("blockedDefenseOffsets")) {
                    if (!el.isJsonObject()) continue;
                    JsonObject obj = el.getAsJsonObject();
                    int dx = obj.get("dx").getAsInt();
                    int dy = obj.get("dy").getAsInt();
                    int dz = obj.get("dz").getAsInt();
                    int fails = obj.has("failCount") ? obj.get("failCount").getAsInt() : 1;
                    if (Math.abs(dx) <= 4 && dy >= -1 && dy <= 4 && Math.abs(dz) <= 4 && fails >= 1) {
                        out.blockedDefenseOffsets.add(new LearnedBlockedDefenseOffset(
                                dx, dy, dz, fails, obj.has("updatedAtMs") ? obj.get("updatedAtMs").getAsLong() : 0L
                        ));
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    public static void saveMapKnowledge(Path cacheDir, String serverId, String mapName, MapKnowledge knowledge) {
        if (cacheDir == null || knowledge == null || !isPersistableMapName(mapName)) {
            return;
        }
        JsonObject root = new JsonObject();
        root.addProperty("serverId", serverId != null ? serverId : "local");
        root.addProperty("mapName", mapName);
        root.addProperty("updatedAtMs", System.currentTimeMillis());

        JsonArray npcArr = new JsonArray();
        for (LearnedNpcEntry n : knowledge.npcs) {
            JsonObject obj = new JsonObject();
            obj.addProperty("x", n.x());
            obj.addProperty("y", n.y());
            obj.addProperty("z", n.z());
            obj.addProperty("role", n.role());
            obj.addProperty("confirmations", n.confirmations());
            obj.addProperty("updatedAtMs", n.updatedAtMs());
            npcArr.add(obj);
        }
        root.add("npcs", npcArr);

        JsonArray forgeArr = new JsonArray();
        for (LearnedForgeEntry f : knowledge.homeForges) {
            JsonObject obj = new JsonObject();
            obj.addProperty("x", f.x());
            obj.addProperty("y", f.y());
            obj.addProperty("z", f.z());
            obj.addProperty("confirmations", f.confirmations());
            obj.addProperty("updatedAtMs", f.updatedAtMs());
            forgeArr.add(obj);
        }
        root.add("homeForges", forgeArr);

        JsonArray blockArr = new JsonArray();
        for (LearnedBlockedDefenseOffset b : knowledge.blockedDefenseOffsets) {
            JsonObject obj = new JsonObject();
            obj.addProperty("dx", b.dx());
            obj.addProperty("dy", b.dy());
            obj.addProperty("dz", b.dz());
            obj.addProperty("failCount", b.failCount());
            obj.addProperty("updatedAtMs", b.updatedAtMs());
            blockArr.add(obj);
        }
        root.add("blockedDefenseOffsets", blockArr);
        writeAtomic(mapKnowledgePath(cacheDir, serverId, mapName), root);
    }

    public static void recordNpcObservation(
            Path cacheDir,
            String serverId,
            String mapName,
            int x,
            int y,
            int z,
            MenuKind openedKind
    ) {
        if (cacheDir == null || openedKind == null || openedKind == MenuKind.UNKNOWN || !isPersistableMapName(mapName)) {
            return;
        }
        String roleStr = openedKind == MenuKind.UPGRADE_SHOP ? "UPGRADE_SHOP" : "ITEM_SHOP";
        MapKnowledge mk = loadMapKnowledge(cacheDir, serverId, mapName);
        long now = System.currentTimeMillis();
        int matchIdx = -1;
        for (int i = 0; i < mk.npcs.size(); i++) {
            LearnedNpcEntry e = mk.npcs.get(i);
            int dx = e.x() - x;
            int dy = e.y() - y;
            int dz = e.z() - z;
            if (dx * dx + dy * dy + dz * dz <= 4) {
                matchIdx = i;
                break;
            }
        }
        if (matchIdx >= 0) {
            LearnedNpcEntry prev = mk.npcs.get(matchIdx);
            // Overwrite if cached NPC role was wrong, or increment confidence if confirmed
            int conf = roleStr.equals(prev.role()) ? Math.min(20, prev.confirmations() + 1) : 1;
            mk.npcs.set(matchIdx, new LearnedNpcEntry(x, y, z, roleStr, conf, now));
        } else {
            mk.npcs.add(new LearnedNpcEntry(x, y, z, roleStr, 1, now));
        }
        saveMapKnowledge(cacheDir, serverId, mapName, mk);
    }

    public static void recordVerifiedForgeObservation(
            Path cacheDir,
            String serverId,
            String mapName,
            int x,
            int y,
            int z
    ) {
        if (cacheDir == null || !isPersistableMapName(mapName)) {
            return;
        }
        MapKnowledge mk = loadMapKnowledge(cacheDir, serverId, mapName);
        long now = System.currentTimeMillis();
        for (int i = 0; i < mk.homeForges.size(); i++) {
            LearnedForgeEntry e = mk.homeForges.get(i);
            int dx = e.x() - x;
            int dy = e.y() - y;
            int dz = e.z() - z;
            if (dx * dx + dy * dy + dz * dz <= 6) {
                mk.homeForges.set(i, new LearnedForgeEntry(x, y, z, Math.min(20, e.confirmations() + 1), now));
                saveMapKnowledge(cacheDir, serverId, mapName, mk);
                return;
            }
        }
        mk.homeForges.add(new LearnedForgeEntry(x, y, z, 1, now));
        saveMapKnowledge(cacheDir, serverId, mapName, mk);
    }

    public static void recordBlockedDefenseOffset(
            Path cacheDir,
            String serverId,
            String mapName,
            int dx,
            int dy,
            int dz
    ) {
        if (cacheDir == null || !isPersistableMapName(mapName)) {
            return;
        }
        if (Math.abs(dx) > 4 || dy < -1 || dy > 4 || Math.abs(dz) > 4) {
            return;
        }
        MapKnowledge mk = loadMapKnowledge(cacheDir, serverId, mapName);
        long now = System.currentTimeMillis();
        for (int i = 0; i < mk.blockedDefenseOffsets.size(); i++) {
            LearnedBlockedDefenseOffset b = mk.blockedDefenseOffsets.get(i);
            if (b.dx() == dx && b.dy() == dy && b.dz() == dz) {
                mk.blockedDefenseOffsets.set(i, new LearnedBlockedDefenseOffset(
                        dx, dy, dz, Math.min(20, b.failCount() + 1), now
                ));
                saveMapKnowledge(cacheDir, serverId, mapName, mk);
                return;
            }
        }
        mk.blockedDefenseOffsets.add(new LearnedBlockedDefenseOffset(dx, dy, dz, 1, now));
        saveMapKnowledge(cacheDir, serverId, mapName, mk);
    }

    public static boolean isBlockedDefenseOffset(MapKnowledge mk, int dx, int dy, int dz) {
        if (mk == null) {
            return false;
        }
        for (LearnedBlockedDefenseOffset b : mk.blockedDefenseOffsets) {
            if (b.dx() == dx && b.dy() == dy && b.dz() == dz && b.failCount() >= 3) {
                return true;
            }
        }
        return false;
    }

    public static void applyMapKnowledgeToCore(MapKnowledge mk, BedwarsMapCore core) {
        if (mk == null || core == null) {
            return;
        }
        for (LearnedNpcEntry n : mk.npcs) {
            String prefix = "UPGRADE_SHOP".equals(n.role()) ? "UPGRADES:LEARNED" : "ITEM_SHOP:LEARNED";
            core.addOrUpdateShopNpc(new ShopNPC(prefix, n.x(), n.y(), n.z()));
        }
        for (LearnedForgeEntry f : mk.homeForges) {
            // Only restore forge POIs near one of this map's indexed team islands/beds/spawns
            boolean nearAnyIsland = core.getBeds().isEmpty() && core.getSpawns().isEmpty();
            for (BedwarsMapCore.BedPOI b : core.getBeds()) {
                int dx = f.x() - b.x();
                int dz = f.z() - b.z();
                if (dx * dx + dz * dz <= 784) {
                    nearAnyIsland = true;
                    break;
                }
            }
            if (nearAnyIsland) {
                boolean exists = false;
                for (GenPOI g : core.getGens()) {
                    if (g.type() == GenType.HOME_FORGE
                            && Math.abs(g.x() - f.x()) <= 2 && Math.abs(g.z() - f.z()) <= 2 && Math.abs(g.y() - f.y()) <= 2) {
                        exists = true;
                        break;
                    }
                }
                if (!exists) {
                    core.addGen(new GenPOI(GenType.HOME_FORGE, f.x(), f.y(), f.z(), "Team Forge (Learned)"));
                }
            }
        }
    }

    private static void writeAtomic(Path file, JsonObject root) {
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName().toString() + ".tmp");
            Files.writeString(tmp, GSON.toJson(root), StandardCharsets.UTF_8);
            Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ignored) {
        }
    }
}
