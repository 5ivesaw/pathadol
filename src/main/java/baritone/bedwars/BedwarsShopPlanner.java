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

import baritone.bedwars.BedwarsDefensePlanner.DefenseBlockKind;
import baritone.bedwars.BedwarsTacticalHelper.InventorySummary;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure-Java cross-server Bedwars item/upgrade catalog, affordability filter, purchase priority
 * scorer, and zero-spend category-tab mapper.
 */
public final class BedwarsShopPlanner {

    private static final Pattern COST_PATTERN = Pattern.compile(
            "(?i)(\\d{1,3})\\s*x?\\s*(iron|gold(?:en)?|diamond|diamonds|emerald|emeralds)(?:\\s*ingots?|\\s*blocks?)?"
    );
    private static final Pattern COST_PATTERN_ALT = Pattern.compile(
            "(?i)\\b(iron|gold(?:en)?|diamond|diamonds|emerald|emeralds)\\s*(?:ingots?|blocks?)?\\s*[:x-]\\s*(\\d{1,3})\\b"
    );

    public enum ShopPreset {
        AUTO,
        ESSENTIALS,
        COMBAT,
        TOOLS,
        UTILITY,
        UPGRADES;

        public static ShopPreset fromString(String raw) {
            if (raw == null || raw.isBlank()) {
                return AUTO;
            }
            return switch (raw.trim().toLowerCase(Locale.ROOT)) {
                case "essentials", "wool", "blocks", "rush" -> ESSENTIALS;
                case "combat", "gear", "armor", "sword", "pvp" -> COMBAT;
                case "tools", "pick", "axe", "shears" -> TOOLS;
                case "utility", "tnt", "fb", "fireball", "gapple", "pearl" -> UTILITY;
                case "upgrades", "upgrade", "diamond", "diamonds", "trap" -> UPGRADES;
                default -> AUTO;
            };
        }
    }

    public enum Currency {
        IRON,
        GOLD,
        DIAMOND,
        EMERALD
    }

    public enum MenuKind {
        UNKNOWN,
        ITEM_SHOP,
        UPGRADE_SHOP
    }

    public enum BedwarsItemRole {
        SEPARATOR,
        MANAGE_HOTBAR,
        CATEGORY_TAB,
        BLOCK_WOOL,
        BLOCK_END_STONE,
        BLOCK_WOOD,
        BLOCK_SANDSTONE_OR_TERRACOTTA,
        BLOCK_BLAST_GLASS,
        BLOCK_OBSIDIAN,
        BLOCK_LADDER,
        SWORD_STONE,
        SWORD_IRON,
        SWORD_DIAMOND,
        KNOCKBACK_STICK,
        BOW,
        ARROW,
        SHEARS,
        PICKAXE,
        AXE,
        ARMOR_CHAIN,
        ARMOR_IRON,
        ARMOR_DIAMOND,
        GAPPLE,
        FIREBALL,
        TNT,
        PEARL,
        WATER_BUCKET,
        SILVERFISH,
        IRON_GOLEM,
        BRIDGE_EGG,
        MAGIC_MILK,
        SPONGE,
        PLAYER_TRACKER,
        POTION_SPEED,
        POTION_JUMP,
        POTION_INVIS,
        UPGRADE_SHARPNESS,
        UPGRADE_PROTECTION,
        UPGRADE_HASTE,
        UPGRADE_FORGE,
        UPGRADE_HEAL_POOL,
        UPGRADE_DRAGON_BUFF,
        UPGRADE_TRAP,
        ROTATIONAL_OTHER,
        UNKNOWN
    }

    public enum SlotAffordability {
        NON_BUYABLE,
        CATEGORY_TAB,
        OWNED_OR_MAXED,
        CANNOT_AFFORD,
        AFFORDABLE
    }

    public record CostSpec(Currency currency, int amount) {}

    public record ShopSlotInfo(
            int slotIndex,
            String itemPath,
            String displayName,
            String loreText,
            int stackCount
    ) {}

    public record ShopClickDecision(
            int slotIndex,
            String buyKey,
            String itemName,
            CostSpec cost,
            boolean categorySwitch,
            String reason
    ) {}

    public record CatalogItemEntry(
            BedwarsItemRole role,
            String tabName,
            int slotIndex,
            String itemPath,
            String displayName,
            CostSpec cost,
            int stackCount
    ) {}

    /**
     * Scouted enemy bed defense profile cached on {@link BedwarsMapCore} so the shop reactively
     * purchases Wood Axes, Endstone/Obsidian Pickaxes, and TNT without manual overrides.
     */
    public record EnemyDefenseIntel(
            boolean anyWood,
            boolean anyEndstone,
            boolean anyObsidian,
            boolean anyMultiLayer
    ) {
        public static final EnemyDefenseIntel NONE = new EnemyDefenseIntel(false, false, false, false);

        public static EnemyDefenseIntel fromCore(BedwarsMapCore core) {
            if (core == null) {
                return NONE;
            }
            return new EnemyDefenseIntel(
                    core.anyEnemyBedHasDefense(DefenseBlockKind.WOOD),
                    core.anyEnemyBedHasDefense(DefenseBlockKind.ENDSTONE),
                    core.anyEnemyBedHasDefense(DefenseBlockKind.OBSIDIAN),
                    core.anyEnemyBedHasMultiLayerDefense()
            );
        }
    }

    /**
     * Per-match catalog populated by safe category-tab inspection so Pathadol knows every item,
     * tool, defense material, utility item, and upgrade offered by the current server shop.
     */
    public static final class ShopCatalogCache {
        private final EnumMap<BedwarsItemRole, CatalogItemEntry> roleCatalog = new EnumMap<>(BedwarsItemRole.class);
        private final Set<String> scannedTabs = new HashSet<>();
        private final Map<Integer, String> knownTabNamesBySlot = new LinkedHashMap<>();
        private boolean itemShopScanComplete = false;
        private boolean upgradeShopScanComplete = false;
        private String currentTabName = "Quick Buy";

        public synchronized void reset() {
            roleCatalog.clear();
            scannedTabs.clear();
            knownTabNamesBySlot.clear();
            itemShopScanComplete = false;
            upgradeShopScanComplete = false;
            currentTabName = "Quick Buy";
        }

        public synchronized boolean needsScan(MenuKind kind) {
            if (kind == MenuKind.ITEM_SHOP) {
                return !itemShopScanComplete;
            }
            if (kind == MenuKind.UPGRADE_SHOP) {
                return !upgradeShopScanComplete;
            }
            return false;
        }

        public synchronized void markScanComplete(MenuKind kind) {
            if (kind == MenuKind.ITEM_SHOP) {
                itemShopScanComplete = true;
            } else if (kind == MenuKind.UPGRADE_SHOP) {
                upgradeShopScanComplete = true;
            }
        }

        public synchronized void noteClickedTab(int slotIndex, String tabName) {
            String clean = normalizeTabKey(tabName);
            if (!clean.isEmpty()) {
                scannedTabs.add(clean);
                currentTabName = tabName.trim();
                knownTabNamesBySlot.put(slotIndex, tabName.trim());
            } else {
                scannedTabs.add("slot:" + slotIndex);
            }
        }

        public synchronized int scannedTabCount() {
            return scannedTabs.size();
        }

        public synchronized int knownItemRoleCount() {
            return roleCatalog.size();
        }

        public synchronized CatalogItemEntry getCatalogEntry(BedwarsItemRole role) {
            return role != null ? roleCatalog.get(role) : null;
        }

        public synchronized Map<BedwarsItemRole, CatalogItemEntry> snapshotEntries() {
            return Collections.unmodifiableMap(new EnumMap<>(roleCatalog));
        }

        public synchronized void importLearnedEntries(Collection<CatalogItemEntry> entries) {
            if (entries == null || entries.isEmpty()) {
                return;
            }
            for (CatalogItemEntry e : entries) {
                if (e == null || e.role() == null || e.cost() == null
                        || e.cost().amount() < 1 || e.cost().amount() > 128
                        || e.slotIndex() < 0 || e.slotIndex() >= 54) {
                    continue;
                }
                roleCatalog.putIfAbsent(e.role(), e);
            }
        }

        public synchronized CostSpec getLearnedCost(BedwarsItemRole role) {
            CatalogItemEntry entry = role != null ? roleCatalog.get(role) : null;
            return entry != null ? entry.cost() : null;
        }

        public synchronized int getLearnedCostAmount(BedwarsItemRole role, int fallbackAmount) {
            CostSpec learned = getLearnedCost(role);
            return learned != null && learned.amount() > 0 ? learned.amount() : fallbackAmount;
        }

        public synchronized String compactSummary() {
            return "tabs=" + scannedTabs.size() + ",roles=" + roleCatalog.size()
                    + ",itemDone=" + itemShopScanComplete + ",upgDone=" + upgradeShopScanComplete;
        }

        public synchronized void ingestOpenMenu(MenuKind kind, List<ShopSlotInfo> slots) {
            if (kind == MenuKind.UNKNOWN || slots == null || slots.isEmpty()) {
                return;
            }
            // Detect active tab from green glass pane in separator row (slots 9..17) or fallback name
            for (ShopSlotInfo s : slots) {
                if (s == null) continue;
                int idx = s.slotIndex();
                if (idx >= 0 && idx <= 8) {
                    String tabLabel = s.displayName() != null ? s.displayName().trim() : "";
                    if (!tabLabel.isEmpty()) {
                        knownTabNamesBySlot.putIfAbsent(idx, tabLabel);
                    }
                }
                String path = lower(s.itemPath());
                if (idx >= 9 && idx <= 17 && (path.contains("lime_stained_glass") || path.contains("green_stained_glass"))) {
                    String aboveName = knownTabNamesBySlot.get(idx - 9);
                    if (aboveName != null && !aboveName.isBlank()) {
                        currentTabName = aboveName;
                        scannedTabs.add(normalizeTabKey(aboveName));
                    }
                }
            }
            if (kind == MenuKind.ITEM_SHOP && currentTabName != null && !currentTabName.isBlank()) {
                scannedTabs.add(normalizeTabKey(currentTabName));
            } else if (kind == MenuKind.UPGRADE_SHOP) {
                scannedTabs.add("upgrades_main");
            }

            for (ShopSlotInfo s : slots) {
                if (s == null) continue;
                BedwarsItemRole role = classifyItemRole(s);
                if (role == BedwarsItemRole.SEPARATOR || role == BedwarsItemRole.MANAGE_HOTBAR
                        || role == BedwarsItemRole.CATEGORY_TAB || role == BedwarsItemRole.UNKNOWN) {
                    continue;
                }
                CostSpec cost = parseCostFromLore(s.loreText());
                if (cost == null) {
                    cost = defaultItemCost(lower(s.itemPath()), lower(s.displayName()));
                }
                roleCatalog.put(role, new CatalogItemEntry(
                        role, currentTabName, s.slotIndex(), s.itemPath(), s.displayName(), cost, Math.max(1, s.stackCount())
                ));
            }
        }

        public synchronized ShopClickDecision nextScanTabDecision(MenuKind kind, List<ShopSlotInfo> slots) {
            ingestOpenMenu(kind, slots);
            if (!needsScan(kind) || slots == null || slots.isEmpty()) {
                return null;
            }
            if (kind == MenuKind.UPGRADE_SHOP) {
                // Most upgrades fit in one menu; only click safe sub-tabs if clearly marked "Click to open" & non-buyable
                for (ShopSlotInfo s : slots) {
                    if (s == null) continue;
                    if (isSafeScannableCategoryTab(s)) {
                        String key = normalizeTabKey(s.displayName());
                        if (!key.isEmpty() && !scannedTabs.contains(key)) {
                            noteClickedTab(s.slotIndex(), s.displayName());
                            return new ShopClickDecision(
                                    s.slotIndex(), "scan_tab:" + key, s.displayName(),
                                    null, true, "Safe category scan (upgrades)"
                            );
                        }
                    }
                }
                markScanComplete(MenuKind.UPGRADE_SHOP);
                return null;
            }

            for (ShopSlotInfo s : slots) {
                if (s == null) continue;
                if (!isSafeScannableCategoryTab(s)) {
                    continue;
                }
                String label = s.displayName() != null ? s.displayName().trim() : "";
                String key = normalizeTabKey(label);
                if (key.isEmpty() || "quick buy".equals(key) || "quickbuy".equals(key)) {
                    scannedTabs.add("quick buy");
                    continue;
                }
                if (!scannedTabs.contains(key)) {
                    noteClickedTab(s.slotIndex(), label);
                    return new ShopClickDecision(
                            s.slotIndex(), "scan_tab:" + key, label,
                            null, true, "Safe category tab scan"
                    );
                }
            }
            markScanComplete(MenuKind.ITEM_SHOP);
            return null;
        }

        private static String normalizeTabKey(String raw) {
            return raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9 ]", "").trim();
        }
    }

    private BedwarsShopPlanner() {}

    public static BedwarsItemRole classifyItemRole(ShopSlotInfo slot) {
        if (slot == null) {
            return BedwarsItemRole.UNKNOWN;
        }
        String path = lower(slot.itemPath());
        String name = lower(slot.displayName());
        String lore = lower(slot.loreText());

        if (isSeparatorOrEmpty(path, name, lore)) {
            return BedwarsItemRole.SEPARATOR;
        }
        if (isHotbarPreferenceItem(name, lore, path)) {
            return BedwarsItemRole.MANAGE_HOTBAR;
        }
        if (isSafeScannableCategoryTab(slot) || (slot.slotIndex() == 0 && (path.contains("nether_star") || name.contains("quick buy")))) {
            return BedwarsItemRole.CATEGORY_TAB;
        }

        // Team Upgrades first so iron_sword / boots / chestplate icons inside Upgrade Shop classify cleanly
        if (name.contains("sharp") || name.contains("sharpness")) return BedwarsItemRole.UPGRADE_SHARPNESS;
        if (name.contains("reinforced armor") || name.contains("protection")) return BedwarsItemRole.UPGRADE_PROTECTION;
        if (name.contains("maniac miner") || name.contains("haste")) return BedwarsItemRole.UPGRADE_HASTE;
        if (name.contains("forge") || name.contains("molten")) return BedwarsItemRole.UPGRADE_FORGE;
        if (name.contains("heal pool")) return BedwarsItemRole.UPGRADE_HEAL_POOL;
        if (name.contains("dragon buff")) return BedwarsItemRole.UPGRADE_DRAGON_BUFF;
        if (name.contains("trap") || name.contains("alarm") || name.contains("miner fatigue") || name.contains("counter-offensive")) {
            return BedwarsItemRole.UPGRADE_TRAP;
        }

        // Blocks
        if (path.endsWith("_wool") || name.contains("wool")) return BedwarsItemRole.BLOCK_WOOL;
        if (path.contains("end_stone") || name.contains("end stone") || name.contains("endstone")) return BedwarsItemRole.BLOCK_END_STONE;
        if (path.contains("planks") || name.contains("wood planks") || name.equals("wood")) return BedwarsItemRole.BLOCK_WOOD;
        if (path.contains("sandstone") || path.contains("terracotta") || name.contains("hardened clay") || name.contains("sandstone")) {
            return BedwarsItemRole.BLOCK_SANDSTONE_OR_TERRACOTTA;
        }
        if ((path.contains("glass") && !path.contains("pane")) || name.contains("blast proof") || name.contains("blast-proof")) {
            return BedwarsItemRole.BLOCK_BLAST_GLASS;
        }
        if (path.contains("obsidian") || name.contains("obsidian")) return BedwarsItemRole.BLOCK_OBSIDIAN;
        if (path.contains("ladder") || name.contains("ladder")) return BedwarsItemRole.BLOCK_LADDER;

        // Melee & Archery
        if (path.equals("stone_sword") || name.contains("stone sword")) return BedwarsItemRole.SWORD_STONE;
        if (path.equals("iron_sword") || name.contains("iron sword")) return BedwarsItemRole.SWORD_IRON;
        if (path.equals("diamond_sword") || name.contains("diamond sword")) return BedwarsItemRole.SWORD_DIAMOND;
        if (path.equals("stick") || name.contains("knockback") || name.contains("slapper")) return BedwarsItemRole.KNOCKBACK_STICK;
        if (path.equals("bow") || name.contains("bow")) return BedwarsItemRole.BOW;
        if (path.equals("arrow") || name.contains("arrow")) return BedwarsItemRole.ARROW;

        // Tools
        if (path.equals("shears") || name.contains("shears")) return BedwarsItemRole.SHEARS;
        if (path.endsWith("_pickaxe") || name.contains("pickaxe")) return BedwarsItemRole.PICKAXE;
        if (path.endsWith("_axe") || name.contains("axe")) return BedwarsItemRole.AXE;

        // Armor (also recognize chestplate icons & British "armour" on non-tab item slots)
        if (path.contains("chainmail") || name.contains("chainmail")) return BedwarsItemRole.ARMOR_CHAIN;
        if (path.contains("iron_boots") || path.contains("iron_leggings")
                || (slot.slotIndex() >= 9 && path.contains("iron_chestplate"))
                || name.contains("iron armor") || name.contains("iron armour")) return BedwarsItemRole.ARMOR_IRON;
        if (path.contains("diamond_boots") || path.contains("diamond_leggings")
                || (slot.slotIndex() >= 9 && path.contains("diamond_chestplate"))
                || name.contains("diamond armor") || name.contains("diamond armour")) return BedwarsItemRole.ARMOR_DIAMOND;

        // Utility & Potions
        if (path.contains("golden_apple") || name.contains("golden apple")) return BedwarsItemRole.GAPPLE;
        if (path.contains("fire_charge") || name.contains("fireball")) return BedwarsItemRole.FIREBALL;
        if (path.equals("tnt") || name.equals("tnt") || name.contains("tnt x")) return BedwarsItemRole.TNT;
        if (path.contains("ender_pearl") || name.contains("pearl")) return BedwarsItemRole.PEARL;
        if (path.contains("water_bucket") || name.contains("water bucket")) return BedwarsItemRole.WATER_BUCKET;
        if (name.contains("silverfish") || name.contains("bedbug")) return BedwarsItemRole.SILVERFISH;
        if (name.contains("golem") || name.contains("dream defender")) return BedwarsItemRole.IRON_GOLEM;
        if (path.equals("egg") || name.contains("bridge egg")) return BedwarsItemRole.BRIDGE_EGG;
        if (path.contains("milk_bucket") || name.contains("magic milk")) return BedwarsItemRole.MAGIC_MILK;
        if (path.contains("sponge") || name.contains("sponge")) return BedwarsItemRole.SPONGE;
        if (path.equals("compass") || name.contains("tracker")) return BedwarsItemRole.PLAYER_TRACKER;
        if (path.contains("potion") || name.contains("potion")) {
            if (name.contains("invis") || lore.contains("invis")) return BedwarsItemRole.POTION_INVIS;
            if (name.contains("jump") || lore.contains("jump")) return BedwarsItemRole.POTION_JUMP;
            if (name.contains("speed") || lore.contains("swiftness") || lore.contains("speed")) return BedwarsItemRole.POTION_SPEED;
        }
        return BedwarsItemRole.UNKNOWN;
    }

    public static SlotAffordability evaluateAffordability(
            ShopSlotInfo slot,
            InventorySummary inv,
            CostSpec parsedCost
    ) {
        if (slot == null) {
            return SlotAffordability.NON_BUYABLE;
        }
        String path = lower(slot.itemPath());
        String name = lower(slot.displayName());
        String lore = lower(slot.loreText());

        if (isSeparatorOrEmpty(path, name, lore) || isHotbarPreferenceItem(name, lore, path)) {
            return SlotAffordability.NON_BUYABLE;
        }
        if (isSafeScannableCategoryTab(slot) || (slot.slotIndex() == 0 && (path.contains("nether_star") || name.contains("quick buy")))) {
            return SlotAffordability.CATEGORY_TAB;
        }
        if (lore.contains("you already own") || lore.contains("already own")
                || lore.contains("already unlocked") || lore.contains("already purchased")
                || lore.contains("max tier") || lore.contains("maximum tier")
                || lore.contains("maxed") || lore.contains("highest tier")) {
            return SlotAffordability.OWNED_OR_MAXED;
        }
        if (lore.contains("can not afford") || lore.contains("cannot afford")
                || lore.contains("not enough") || lore.contains("don't have enough")
                || lore.contains("do not have enough")) {
            return SlotAffordability.CANNOT_AFFORD;
        }
        // Any remaining non-cost "click to open" slot is a navigation button, never buyable
        if ((lore.contains("click to open") || lore.contains("click to edit") || lore.contains("edit hotbar"))
                && !lore.contains("cost") && !lore.contains("click to buy")) {
            return SlotAffordability.NON_BUYABLE;
        }
        if (parsedCost == null) {
            return SlotAffordability.NON_BUYABLE;
        }
        return canAfford(inv, parsedCost) ? SlotAffordability.AFFORDABLE : SlotAffordability.CANNOT_AFFORD;
    }

    public static boolean isSafeScannableCategoryTab(ShopSlotInfo slot) {
        if (slot == null) {
            return false;
        }
        String path = lower(slot.itemPath());
        String name = lower(slot.displayName());
        String lore = lower(slot.loreText());
        if (isSeparatorOrEmpty(path, name, lore) || isHotbarPreferenceItem(name, lore, path)) {
            return false;
        }
        if (lore.contains("cost:") || lore.contains("costs:") || lore.contains("click to buy")) {
            return false;
        }
        boolean clickToOpen = lore.contains("click to open") || lore.contains("click to view");
        boolean topTabIndex = slot.slotIndex() >= 0 && slot.slotIndex() <= 8;
        if (topTabIndex && (clickToOpen || isCategoryHeaderItem(path, name))) {
            return true;
        }
        return clickToOpen && isCategoryHeaderItem(path, name);
    }

    public static ShopPreset recommendPreset(
            InventorySummary inv,
            boolean homeBedAlive,
            double homeBedDefenseRatio,
            double nearestEnemyDist
    ) {
        return recommendPreset(inv, homeBedAlive, homeBedDefenseRatio, nearestEnemyDist, EnemyDefenseIntel.NONE);
    }

    public static ShopPreset recommendPreset(
            InventorySummary inv,
            boolean homeBedAlive,
            double homeBedDefenseRatio,
            double nearestEnemyDist,
            EnemyDefenseIntel defenseIntel
    ) {
        if (inv == null) {
            return ShopPreset.ESSENTIALS;
        }
        EnemyDefenseIntel intel = defenseIntel != null ? defenseIntel : EnemyDefenseIntel.NONE;
        if (inv.diamonds() >= 2 || (inv.diamonds() >= 1 && homeBedAlive && inv.bridgeBlockCount() >= 16)) {
            return ShopPreset.UPGRADES;
        }
        if ((inv.iron() >= 40 && inv.armorPoints() < BedwarsTacticalHelper.BEDWARS_ARMOR_CHAIN_MIN && inv.bridgeBlockCount() >= 16)
                || (inv.gold() >= 12 && inv.armorPoints() < BedwarsTacticalHelper.BEDWARS_ARMOR_IRON_MIN && inv.bridgeBlockCount() >= 16)
                || (inv.emeralds() >= 6 && inv.armorPoints() < BedwarsTacticalHelper.BEDWARS_ARMOR_DIAMOND_MIN && inv.bridgeBlockCount() >= 16)
                || (inv.gold() >= 7 && inv.swordTier() < 3 && inv.bridgeBlockCount() >= 16
                    && (inv.armorPoints() >= BedwarsTacticalHelper.BEDWARS_ARMOR_IRON_MIN || !homeBedAlive || nearestEnemyDist <= 22.0))) {
            return ShopPreset.COMBAT;
        }
        // Make ShopPreset.TOOLS directly reachable when scouted enemy beds require pickaxe/axe upgrades or shears!
        boolean needObsidianPick = intel.anyObsidian() && inv.pickaxeTier() < 4 && (inv.gold() >= 6 || inv.iron() >= 10);
        boolean needEndstonePick = intel.anyEndstone() && inv.pickaxeTier() < 3 && (inv.iron() >= 10 || inv.gold() >= 3);
        boolean needWoodAxe = intel.anyWood() && inv.axeTier() < 3 && (inv.iron() >= 10 || inv.gold() >= 3);
        if (inv.bridgeBlockCount() >= 16 && (needObsidianPick || needEndstonePick || needWoodAxe)) {
            return ShopPreset.TOOLS;
        }
        // Reactive TNT preset against endstone/wood/multi-layer enemy defenses (no Iron Armor gate!)
        if (inv.bridgeBlockCount() >= 16 && inv.tntCount() < 2 && inv.gold() >= 4
                && (intel.anyEndstone() || intel.anyWood() || intel.anyMultiLayer())) {
            return ShopPreset.UTILITY;
        }
        if (inv.bridgeBlockCount() < 32
                || (inv.shearsTier() == 0 && inv.iron() >= 20 && !(inv.armorPoints() < BedwarsTacticalHelper.BEDWARS_ARMOR_CHAIN_MIN && inv.iron() >= 28))
                || (inv.swordTier() < 2 && inv.iron() >= 10 && inv.gold() < 7)) {
            return ShopPreset.ESSENTIALS;
        }
        if (homeBedAlive && homeBedDefenseRatio < 0.85 && inv.hardDefenseCount() < 12 && inv.iron() >= 24) {
            return ShopPreset.ESSENTIALS;
        }
        if (inv.emeralds() >= 2 || inv.gold() >= 7 || nearestEnemyDist <= 28.0) {
            return ShopPreset.COMBAT;
        }
        if (inv.iron() >= 40 || inv.gold() >= 4) {
            return ShopPreset.UTILITY;
        }
        return ShopPreset.COMBAT;
    }

    public static CostSpec parseCostFromLore(String loreText) {
        if (loreText == null || loreText.isBlank()) {
            return null;
        }
        Matcher m1 = COST_PATTERN.matcher(loreText);
        if (m1.find()) {
            int amount = parsePositiveInt(m1.group(1));
            Currency c = parseCurrencyToken(m1.group(2));
            if (amount > 0 && c != null) {
                return new CostSpec(c, amount);
            }
        }
        Matcher m2 = COST_PATTERN_ALT.matcher(loreText);
        if (m2.find()) {
            Currency c = parseCurrencyToken(m2.group(1));
            int amount = parsePositiveInt(m2.group(2));
            if (amount > 0 && c != null) {
                return new CostSpec(c, amount);
            }
        }
        return null;
    }

    private static int parsePositiveInt(String raw) {
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    private static Currency parseCurrencyToken(String unit) {
        String u = unit.trim().toLowerCase(Locale.ROOT);
        if (u.startsWith("iron")) return Currency.IRON;
        if (u.startsWith("gold")) return Currency.GOLD;
        if (u.startsWith("diamond")) return Currency.DIAMOND;
        if (u.startsWith("emerald")) return Currency.EMERALD;
        return null;
    }

    public static boolean isNonShopUiTitle(String menuTitle) {
        String title = lower(menuTitle);
        if (title.isBlank()) {
            return false;
        }
        return title.contains("new game")
                || title.contains("play again")
                || title.contains("map selector")
                || title.contains("game selector")
                || title.contains("teleporter")
                || title.contains("spectate")
                || title.contains("profile")
                || title.contains("cosmetics")
                || title.contains("settings")
                || title.contains("manage hotbar")
                || title.contains("hotbar manager")
                || title.contains("statistics")
                || title.contains("stats")
                || title.contains("quests")
                || title.contains("lobby")
                || title.contains("bedwars menu");
    }

    public static MenuKind classifyOpenMenu(String menuTitle, List<ShopSlotInfo> containerSlots) {
        String title = lower(menuTitle);
        if (isNonShopUiTitle(title)) {
            return MenuKind.UNKNOWN;
        }
        if (title.contains("upgrade") || title.contains("trap") || title.contains("perk") || title.contains("diamond shop")) {
            return MenuKind.UPGRADE_SHOP;
        }
        if (title.contains("quick buy") || title.contains("quickbuy") || title.contains("item shop")
                || title.contains("shopkeeper") || title.contains("armor") || title.contains("melee")
                || title.contains("tools") || title.contains("utility") || title.contains("potions")
                || title.contains("archery") || title.contains("blocks") || title.contains("rotational")
                || title.contains("shop -")) {
            return MenuKind.ITEM_SHOP;
        }
        if (containerSlots == null || containerSlots.isEmpty()) {
            return MenuKind.UNKNOWN;
        }
        int upgradeSignals = 0;
        int itemShopSignals = 0;
        for (ShopSlotInfo s : containerSlots) {
            if (s == null) continue;
            BedwarsItemRole role = classifyItemRole(s);
            if (role.name().startsWith("UPGRADE_")) {
                upgradeSignals++;
            } else if (role != BedwarsItemRole.SEPARATOR && role != BedwarsItemRole.MANAGE_HOTBAR && role != BedwarsItemRole.UNKNOWN) {
                itemShopSignals++;
            }
            CostSpec c = parseCostFromLore(s.loreText());
            if (c != null) {
                if (c.currency() == Currency.DIAMOND) upgradeSignals++;
                else itemShopSignals++;
            }
        }
        if (upgradeSignals >= 2 && upgradeSignals > itemShopSignals) {
            return MenuKind.UPGRADE_SHOP;
        }
        if (itemShopSignals >= 2) {
            return MenuKind.ITEM_SHOP;
        }
        return MenuKind.UNKNOWN;
    }

    public static boolean canAfford(InventorySummary inv, CostSpec cost) {
        if (inv == null || cost == null) {
            return false;
        }
        return switch (cost.currency()) {
            case IRON -> inv.iron() >= cost.amount();
            case GOLD -> inv.gold() >= cost.amount();
            case DIAMOND -> inv.diamonds() >= cost.amount();
            case EMERALD -> inv.emeralds() >= cost.amount();
        };
    }

    public static ShopClickDecision chooseNextShopClick(
            List<ShopSlotInfo> containerSlots,
            ShopPreset preset,
            InventorySummary inv,
            boolean upgradeShopOpen,
            boolean homeBedAlive,
            double homeBedDefenseRatio,
            double nearestEnemyDist,
            boolean categorySwitched
    ) {
        return chooseNextShopClick(
                containerSlots, preset, inv, upgradeShopOpen, homeBedAlive,
                homeBedDefenseRatio, nearestEnemyDist, categorySwitched, null
        );
    }

    public static ShopClickDecision chooseNextShopClick(
            List<ShopSlotInfo> containerSlots,
            ShopPreset preset,
            InventorySummary inv,
            boolean upgradeShopOpen,
            boolean homeBedAlive,
            double homeBedDefenseRatio,
            double nearestEnemyDist,
            boolean categorySwitched,
            BedwarsMatchState matchState
    ) {
        return chooseNextShopClick(
                containerSlots, preset, inv, upgradeShopOpen, homeBedAlive,
                homeBedDefenseRatio, nearestEnemyDist, categorySwitched, matchState,
                EnemyDefenseIntel.NONE, null
        );
    }

    public static ShopClickDecision chooseNextShopClick(
            List<ShopSlotInfo> containerSlots,
            ShopPreset preset,
            InventorySummary inv,
            boolean upgradeShopOpen,
            boolean homeBedAlive,
            double homeBedDefenseRatio,
            double nearestEnemyDist,
            boolean categorySwitched,
            BedwarsMatchState matchState,
            EnemyDefenseIntel defenseIntel,
            ShopCatalogCache catalog
    ) {
        return chooseNextShopClick(
                containerSlots, preset, inv, upgradeShopOpen, homeBedAlive,
                homeBedDefenseRatio, nearestEnemyDist, categorySwitched, matchState,
                defenseIntel, catalog, -1
        );
    }

    public static ShopClickDecision chooseNextShopClick(
            List<ShopSlotInfo> containerSlots,
            ShopPreset preset,
            InventorySummary inv,
            boolean upgradeShopOpen,
            boolean homeBedAlive,
            double homeBedDefenseRatio,
            double nearestEnemyDist,
            boolean categorySwitched,
            BedwarsMatchState matchState,
            EnemyDefenseIntel defenseIntel,
            ShopCatalogCache catalog,
            int minCrossingBlocks
    ) {
        if (containerSlots == null || containerSlots.isEmpty() || inv == null) {
            return null;
        }
        EnemyDefenseIntel intel = defenseIntel != null ? defenseIntel : EnemyDefenseIntel.NONE;
        ShopPreset resolved = (preset == null || preset == ShopPreset.AUTO)
                ? recommendPreset(inv, homeBedAlive, homeBedDefenseRatio, nearestEnemyDist, intel)
                : preset;

        if (upgradeShopOpen || resolved == ShopPreset.UPGRADES) {
            return chooseUpgradeClick(containerSlots, inv, matchState);
        }

        ShopClickDecision bestBuy = null;
        double bestScore = 0.0;
        for (ShopSlotInfo slot : containerSlots) {
            if (slot == null) continue;
            BedwarsItemRole role = classifyItemRole(slot);
            if (role == BedwarsItemRole.SEPARATOR || role == BedwarsItemRole.MANAGE_HOTBAR || role == BedwarsItemRole.CATEGORY_TAB) {
                continue;
            }
            String path = lower(slot.itemPath());
            String name = lower(slot.displayName());
            CostSpec cost = parseCostFromLore(slot.loreText());
            if (cost == null && catalog != null) {
                cost = catalog.getLearnedCost(role);
            }
            if (cost == null) {
                cost = defaultItemCost(path, name);
            }
            SlotAffordability aff = evaluateAffordability(slot, inv, cost);
            if (aff != SlotAffordability.AFFORDABLE) {
                continue;
            }
            if (isAlreadyMaxedOrRedundant(slot, role, inv)) {
                continue;
            }
            double score = scoreShopOffer(role, cost, resolved, inv, homeBedAlive, homeBedDefenseRatio, nearestEnemyDist, matchState, intel, minCrossingBlocks);
            if (score > bestScore) {
                bestScore = score;
                bestBuy = new ShopClickDecision(
                        slot.slotIndex(),
                        role.name().toLowerCase(Locale.ROOT),
                        slot.displayName(),
                        cost,
                        false,
                        resolved.name() + " item score=" + Math.round(score)
                );
            }
        }

        // Query ShopCatalogCache to check if a higher-priority affordable item sits on another known tab!
        if (!categorySwitched && catalog != null) {
            ShopClickDecision tabJump = findBestCatalogTabSwitch(
                    containerSlots, catalog, resolved, inv, homeBedAlive, homeBedDefenseRatio,
                    nearestEnemyDist, matchState, intel, minCrossingBlocks, bestScore
            );
            if (tabJump != null) {
                return tabJump;
            }
        }

        if (bestBuy != null) {
            return bestBuy;
        }
        if (!categorySwitched) {
            return findCategoryTabForPreset(containerSlots, resolved, inv);
        }
        return null;
    }

    private static ShopClickDecision findBestCatalogTabSwitch(
            List<ShopSlotInfo> containerSlots,
            ShopCatalogCache catalog,
            ShopPreset resolved,
            InventorySummary inv,
            boolean homeBedAlive,
            double homeBedDefenseRatio,
            double nearestEnemyDist,
            BedwarsMatchState matchState,
            EnemyDefenseIntel intel,
            int minCrossingBlocks,
            double currentPageBestScore
    ) {
        double bestOffPageScore = currentPageBestScore + (currentPageBestScore > 0.0 ? 22.0 : 0.0);
        CatalogItemEntry bestOffPage = null;
        for (CatalogItemEntry entry : catalog.snapshotEntries().values()) {
            if (entry == null || entry.cost() == null || entry.tabName() == null || entry.tabName().isBlank()) {
                continue;
            }
            BedwarsItemRole role = entry.role();
            if (role == null || role.name().startsWith("UPGRADE_")
                    || role == BedwarsItemRole.SEPARATOR || role == BedwarsItemRole.MANAGE_HOTBAR || role == BedwarsItemRole.CATEGORY_TAB) {
                continue;
            }
            if (!canAfford(inv, entry.cost())) {
                continue;
            }
            ShopSlotInfo synthetic = new ShopSlotInfo(entry.slotIndex(), entry.itemPath(), entry.displayName(), "", entry.stackCount());
            if (isAlreadyMaxedOrRedundant(synthetic, role, inv)) {
                continue;
            }
            double s = scoreShopOffer(role, entry.cost(), resolved, inv, homeBedAlive, homeBedDefenseRatio, nearestEnemyDist, matchState, intel, minCrossingBlocks);
            if (s > bestOffPageScore) {
                bestOffPageScore = s;
                bestOffPage = entry;
            }
        }
        if (bestOffPage == null) {
            return null;
        }
        String wantedTab = lower(bestOffPage.tabName());
        for (ShopSlotInfo slot : containerSlots) {
            if (slot == null || !isSafeScannableCategoryTab(slot)) {
                continue;
            }
            String tabLabel = lower(slot.displayName());
            if (!tabLabel.isEmpty() && (tabLabel.contains(wantedTab) || wantedTab.contains(tabLabel))) {
                return new ShopClickDecision(
                        slot.slotIndex(),
                        "tab:" + wantedTab,
                        slot.displayName(),
                        null,
                        true,
                        "Catalog jump to " + bestOffPage.tabName() + " for " + bestOffPage.role().name()
                );
            }
        }
        return null;
    }

    private static ShopClickDecision chooseUpgradeClick(
            List<ShopSlotInfo> slots,
            InventorySummary inv,
            BedwarsMatchState matchState
    ) {
        ShopClickDecision best = null;
        double bestScore = 0.0;
        ShopClickDecision trapSubmenuButton = null;
        int activeTraps = matchState != null ? matchState.getActiveTrapCount() : 0;
        for (ShopSlotInfo s : slots) {
            if (s == null) continue;
            String name = lower(s.displayName());
            String lore = lower(s.loreText());
            // Support Hypixel / Pika "Buy a trap" sub-menu opener button inside the Team Upgrades GUI!
            if (trapSubmenuButton == null && activeTraps < 2 && inv.diamonds() >= 1
                    && (name.contains("buy a trap") || name.equals("traps") || name.contains("bed traps"))
                    && (lore.contains("click to open") || lore.contains("click to view") || lore.contains("click to browse"))) {
                trapSubmenuButton = new ShopClickDecision(
                        s.slotIndex(), "tab:traps", s.displayName(), null, true, "Open Trap sub-menu"
                );
                continue;
            }
            BedwarsItemRole role = classifyItemRole(s);
            if (role == BedwarsItemRole.SEPARATOR || role == BedwarsItemRole.MANAGE_HOTBAR || role == BedwarsItemRole.CATEGORY_TAB) {
                continue;
            }
            CostSpec cost = parseCostFromLore(s.loreText());
            if (cost == null) {
                int defDia = name.contains("sharp") ? 4 : ((name.contains("trap") || name.contains("heal") || name.contains("alarm")) ? 1 : 2);
                cost = new CostSpec(Currency.DIAMOND, defDia);
            }
            SlotAffordability aff = evaluateAffordability(s, inv, cost);
            if (aff != SlotAffordability.AFFORDABLE) {
                continue;
            }
            double score = switch (role) {
                case UPGRADE_SHARPNESS -> (matchState != null && matchState.getSharpnessTier() > 0) ? 0.0 : 135.0;
                case UPGRADE_PROTECTION -> 122.0 - (matchState != null ? matchState.getProtectionTier() * 12.0 : 0.0);
                case UPGRADE_TRAP -> activeTraps >= 2 ? 0.0 : (activeTraps == 0 ? 115.0 : 65.0);
                case UPGRADE_FORGE -> 88.0;
                case UPGRADE_HASTE -> 82.0 - (matchState != null ? matchState.getHasteTier() * 20.0 : 0.0);
                case UPGRADE_HEAL_POOL -> (matchState != null && matchState.hasHealPool()) ? 0.0 : 78.0;
                case UPGRADE_DRAGON_BUFF -> (matchState != null && matchState.isAllBedsDestroyed()) ? 90.0 : 25.0;
                default -> 0.0;
            };
            if (score > bestScore) {
                bestScore = score;
                best = new ShopClickDecision(s.slotIndex(), "upgrade:" + role.name().toLowerCase(Locale.ROOT),
                        s.displayName(), cost, false, "Team upgrade");
            }
        }
        if (best == null && trapSubmenuButton != null) {
            return trapSubmenuButton;
        }
        return best;
    }

    private static double scoreShopOffer(
            BedwarsItemRole role,
            CostSpec cost,
            ShopPreset preset,
            InventorySummary inv,
            boolean homeBedAlive,
            double homeBedDefenseRatio,
            double nearestEnemyDist,
            BedwarsMatchState matchState,
            EnemyDefenseIntel defenseIntel,
            int minCrossingBlocks
    ) {
        EnemyDefenseIntel intel = defenseIntel != null ? defenseIntel : EnemyDefenseIntel.NONE;
        double w = preset == ShopPreset.ESSENTIALS ? 1.15 : (preset == ShopPreset.TOOLS ? 0.85 : 1.0);
        boolean bedGoneOrLate = matchState != null && matchState.isAllBedsDestroyed();
        boolean hasChain = inv.armorPoints() >= BedwarsTacticalHelper.BEDWARS_ARMOR_CHAIN_MIN;
        boolean hasIronArmor = inv.armorPoints() >= BedwarsTacticalHelper.BEDWARS_ARMOR_IRON_MIN;
        boolean hasDiamondArmor = inv.armorPoints() >= BedwarsTacticalHelper.BEDWARS_ARMOR_DIAMOND_MIN;
        int crossingFloor = minCrossingBlocks >= 0 ? Math.max(16, minCrossingBlocks) : 16;
        int comfortableWool = minCrossingBlocks >= 0 ? Math.min(64, Math.max(32, minCrossingBlocks + 8)) : 32;
        int missingCrossingBlocks = Math.max(0, crossingFloor - inv.bridgeBlockCount());
        int woolIronToCross = missingCrossingBlocks > 0 ? 4 * ((missingCrossingBlocks + 15) / 16) : 0;
        boolean savingGoldForIronSword = inv.swordTier() < 3 && inv.gold() >= 3 && inv.gold() < 7;
        boolean savingGoldForIronArmor = !hasIronArmor && inv.gold() >= 4 && inv.gold() < 12;
        boolean savingIronForChainArmor = !hasChain && inv.iron() >= 28 && inv.iron() < 40 && inv.bridgeBlockCount() >= crossingFloor;

        return switch (role) {
            case BLOCK_WOOL -> {
                // Crossing G_nextObjective ALWAYS beats saving iron for Chainmail or buying armor first!
                if (inv.bridgeBlockCount() < crossingFloor) yield 186.0 * w;
                if (inv.bridgeBlockCount() < comfortableWool && (minCrossingBlocks > 0 || !savingIronForChainArmor)) yield 144.0 * w;
                boolean allowBulkStacks = preset != ShopPreset.COMBAT && preset != ShopPreset.UPGRADES;
                // Above comfortable crossing stock, NEVER outscore permanent Chainmail, Iron Armor, Shears, Tools, Gapples, TNT, or Fireballs!
                if (allowBulkStacks && inv.bridgeBlockCount() < 48 && !savingIronForChainArmor) yield 94.0;
                if (allowBulkStacks && inv.bridgeBlockCount() < 64 && inv.iron() >= 8 && !savingIronForChainArmor) yield 62.0;
                yield 0.0;
            }
            case BLOCK_END_STONE -> (homeBedAlive && (homeBedDefenseRatio < 0.85 || inv.hardDefenseCount() < 8)
                    && inv.hardDefenseCount() < 16 && inv.bridgeBlockCount() >= 16
                    && !savingIronForChainArmor) ? 124.0 : 0.0;
            case BLOCK_WOOD -> (homeBedAlive && (homeBedDefenseRatio < 0.85 || inv.hardDefenseCount() < 8)
                    && inv.hardDefenseCount() < 16 && inv.bridgeBlockCount() >= 16
                    && !savingGoldForIronSword && !savingGoldForIronArmor
                    && !( !hasIronArmor && inv.gold() < 16)) ? 108.0 : 0.0;
            case BLOCK_OBSIDIAN -> (homeBedAlive && inv.emeralds() >= 4 && (homeBedDefenseRatio < 0.90 || inv.hardDefenseCount() < 8)) ? 136.0 : 0.0;
            case BLOCK_SANDSTONE_OR_TERRACOTTA -> (inv.bridgeBlockCount() < 24 && !savingIronForChainArmor) ? 72.0 : 0.0;
            case BLOCK_BLAST_GLASS -> (homeBedAlive && (homeBedDefenseRatio < 0.85 || inv.hardDefenseCount() < 8)
                    && inv.hardDefenseCount() < 12 && inv.bridgeBlockCount() >= 16
                    && !savingIronForChainArmor) ? 106.0 : 0.0;
            case BLOCK_LADDER -> (preset == ShopPreset.UTILITY && inv.bridgeBlockCount() >= 32 && inv.iron() >= 28
                    && !savingIronForChainArmor) ? 28.0 : 0.0;

            case SWORD_STONE -> (inv.swordTier() < 2 && inv.gold() < 7 && !savingIronForChainArmor) ? 136.0 : 0.0;
            case SWORD_IRON -> {
                if (inv.swordTier() >= 3) yield 0.0;
                // Prefer saving for permanent 12-gold Iron Armor when we already have Stone Sword & aren't in imminent danger
                if (!hasIronArmor && inv.swordTier() >= 2 && inv.gold() >= 8 && inv.gold() < 12 && homeBedAlive && nearestEnemyDist > 22.0) {
                    yield 0.0;
                }
                yield (inv.bridgeBlockCount() < 16 && inv.iron() < 4) ? 115.0 : 158.0;
            }
            case SWORD_DIAMOND -> (inv.swordTier() < 4) ? 176.0 : 0.0;
            case KNOCKBACK_STICK -> (inv.swordTier() >= 2 && inv.gold() >= 10 && !bedGoneOrLate && hasIronArmor) ? 78.0 : 0.0;

            case ARMOR_DIAMOND -> (!hasDiamondArmor) ? 190.0 : 0.0;
            case ARMOR_IRON -> (!hasIronArmor) ? 182.0 : 0.0;
            case ARMOR_CHAIN -> (!hasChain && (inv.bridgeBlockCount() >= crossingFloor || inv.iron() >= 40 + woolIronToCross)) ? 160.0 : 0.0;

            case SHEARS -> (inv.shearsTier() == 0 && !savingIronForChainArmor)
                    ? (inv.bridgeBlockCount() >= 16 ? 148.0 : 62.0) : 0.0;
            case PICKAXE -> {
                if (savingIronForChainArmor || inv.bridgeBlockCount() < 16) yield 0.0;
                // Reactive pickaxe scaling all the way up to Diamond Pickaxe (tier 4) when enemy has Obsidian/Endstone!
                if (intel.anyObsidian() && inv.pickaxeTier() < 4) {
                    yield 168.0 - inv.pickaxeTier() * 8.0;
                }
                if ((intel.anyEndstone() || intel.anyMultiLayer()) && inv.pickaxeTier() < 3) {
                    yield 144.0 - inv.pickaxeTier() * 10.0;
                }
                if (inv.pickaxeTier() == 0) yield 126.0;
                if (inv.pickaxeTier() == 1 && (inv.iron() >= 20 || preset == ShopPreset.TOOLS)) yield 96.0;
                if (inv.pickaxeTier() == 2 && (inv.gold() >= 6 || inv.iron() >= 30 || preset == ShopPreset.TOOLS)) yield 82.0;
                if (inv.pickaxeTier() == 3 && (inv.gold() >= 8 || preset == ShopPreset.TOOLS)) yield 68.0;
                yield 0.0;
            }
            case AXE -> {
                if (savingIronForChainArmor || inv.bridgeBlockCount() < 16) yield 0.0;
                // Reactive axe scaling past tier 1 up to Iron/Diamond Axe when enemy has Wood/multi-layer defense!
                if ((intel.anyWood() || intel.anyMultiLayer()) && inv.axeTier() < 3) {
                    yield 138.0 - inv.axeTier() * 10.0;
                }
                if (inv.axeTier() == 0) yield 112.0;
                if (inv.axeTier() == 1 && (inv.iron() >= 20 || preset == ShopPreset.TOOLS)) yield 84.0;
                if (inv.axeTier() == 2 && (inv.gold() >= 6 || preset == ShopPreset.TOOLS)) yield 72.0;
                if (inv.axeTier() == 3 && (inv.gold() >= 8 || preset == ShopPreset.TOOLS)) yield 58.0;
                yield 0.0;
            }

            case GAPPLE -> {
                if (inv.gapples() == 0 && (nearestEnemyDist < 20.0 || !homeBedAlive)) yield 126.0;
                if (inv.gapples() < 3 && hasIronArmor && inv.swordTier() >= 3) yield 122.0;
                if (inv.gapples() < 2 && hasIronArmor) yield 116.0;
                if (inv.gapples() < 2 && hasChain && !savingGoldForIronSword && !savingGoldForIronArmor && inv.gold() >= 10) yield 108.0;
                yield 0.0;
            }
            case PEARL -> (inv.pearls() < 2) ? 162.0 : 0.0;
            case FIREBALL -> (inv.fireballs() < 2 && inv.bridgeBlockCount() >= 24 && !savingIronForChainArmor) ? 116.0 : 0.0;
            case TNT -> {
                if (inv.tntCount() >= 2 || bedGoneOrLate || inv.bridgeBlockCount() < 16) yield 0.0;
                // Reactive TNT against scouted endstone/wood/multi-layer beds WITHOUT hard-gating behind Iron Armor!
                if ((intel.anyEndstone() || intel.anyWood() || intel.anyMultiLayer()) && !savingGoldForIronSword) {
                    yield 134.0;
                }
                if (!savingGoldForIronSword && !savingGoldForIronArmor && hasIronArmor) {
                    yield 104.0;
                }
                yield 0.0;
            }
            case POTION_SPEED, POTION_JUMP -> (inv.emeralds() >= 2 && inv.bridgeBlockCount() >= 24) ? 92.0 : 0.0;
            case POTION_INVIS -> (!bedGoneOrLate && inv.emeralds() >= 2 && inv.bridgeBlockCount() >= 24) ? 108.0 : 0.0;
            case BOW -> (nearestEnemyDist > 20.0 && !inv.hasBowAndArrows() && hasIronArmor && inv.gold() >= 14) ? 60.0 : 0.0;
            case ARROW -> (inv.hasBowAndArrows() || (inv.gold() >= 10 && hasIronArmor)) ? 34.0 : 0.0;
            case PLAYER_TRACKER -> (bedGoneOrLate && matchState.countLivingEnemyTeams() <= 2) ? 86.0 : 0.0;
            default -> 0.0;
        };
    }

    private static boolean isAlreadyMaxedOrRedundant(ShopSlotInfo slot, BedwarsItemRole role, InventorySummary inv) {
        return switch (role) {
            case SWORD_STONE -> inv.swordTier() >= 2;
            case SWORD_IRON -> inv.swordTier() >= 3;
            case SWORD_DIAMOND -> inv.swordTier() >= 4;
            case SHEARS -> inv.shearsTier() >= 1;
            case ARMOR_CHAIN -> inv.armorPoints() >= BedwarsTacticalHelper.BEDWARS_ARMOR_CHAIN_MIN;
            case ARMOR_IRON -> inv.armorPoints() >= BedwarsTacticalHelper.BEDWARS_ARMOR_IRON_MIN;
            case ARMOR_DIAMOND -> inv.armorPoints() >= BedwarsTacticalHelper.BEDWARS_ARMOR_DIAMOND_MIN;
            case BLOCK_WOOL -> inv.bridgeBlockCount() >= 80;
            case BLOCK_SANDSTONE_OR_TERRACOTTA -> inv.bridgeBlockCount() >= 64;
            case PICKAXE -> inv.pickaxeTier() >= 4;
            case AXE -> inv.axeTier() >= 4;
            case GAPPLE -> inv.gapples() >= 4;
            case FIREBALL -> inv.fireballs() >= 2;
            case TNT -> inv.tntCount() >= 2;
            case PEARL -> inv.pearls() >= 2;
            default -> false;
        };
    }

    private static boolean isSeparatorOrEmpty(String path, String name, String lore) {
        if ((path == null || path.isEmpty() || "air".equals(path)) && (name == null || name.isEmpty())) {
            return true;
        }
        return path.contains("stained_glass_pane") || path.contains("glass_pane");
    }

    private static boolean isHotbarPreferenceItem(String name, String lore, String path) {
        return name.contains("manage hotbar") || name.contains("hotbar manager")
                || lore.contains("hotbar preferences") || lore.contains("edit hotbar")
                || ("blaze_powder".equals(path) && name.contains("hotbar"));
    }

    private static boolean isCategoryHeaderItem(String path, String name) {
        if (name.contains("armor") || name.contains("armour") || name.equals("blocks")
                || name.contains("melee") || name.contains("sword") || name.contains("tool")
                || name.contains("utility") || name.contains("ranged") || name.contains("archery")
                || name.contains("potion") || name.contains("rotational") || name.contains("quick buy")) {
            return !name.contains("permanent") && !name.contains("x1") && !name.contains("x16");
        }
        return path.contains("nether_star") || path.equals("golden_sword") || path.equals("diamond_pickaxe")
                || path.equals("tnt") || path.equals("iron_chestplate");
    }

    private static ShopClickDecision findCategoryTabForPreset(
            List<ShopSlotInfo> slots,
            ShopPreset preset,
            InventorySummary inv
    ) {
        String[] keywords = switch (preset) {
            case ESSENTIALS -> {
                if (inv.bridgeBlockCount() < 24 && inv.iron() >= 4) {
                    yield new String[]{"block", "wool", "building"};
                }
                if ((inv.gold() >= 12 && inv.armorPoints() < BedwarsTacticalHelper.BEDWARS_ARMOR_IRON_MIN)
                        || (inv.iron() >= 40 && inv.armorPoints() < BedwarsTacticalHelper.BEDWARS_ARMOR_CHAIN_MIN)) {
                    yield new String[]{"armor", "armour", "melee", "weapon", "block"};
                }
                if (inv.shearsTier() == 0 && inv.iron() >= 20) {
                    yield new String[]{"tool", "melee", "weapon", "armor", "armour", "block"};
                }
                yield new String[]{"melee", "weapon", "combat", "armor", "armour", "tool", "block"};
            }
            case TOOLS -> new String[]{"tool", "utility", "pickaxe"};
            case UTILITY -> new String[]{"utility", "potion", "special", "misc"};
            case COMBAT, AUTO, UPGRADES -> (inv.gold() >= 12 || inv.emeralds() >= 6
                    || (inv.iron() >= 40 && inv.armorPoints() < BedwarsTacticalHelper.BEDWARS_ARMOR_CHAIN_MIN))
                    ? new String[]{"armor", "armour", "melee", "weapon", "combat", "utility"}
                    : new String[]{"melee", "weapon", "armor", "armour", "utility"};
        };
        for (String kw : keywords) {
            for (ShopSlotInfo s : slots) {
                if (s == null || !isSafeScannableCategoryTab(s)) continue;
                String n = lower(s.displayName());
                if (n.contains(kw)) {
                    return new ShopClickDecision(s.slotIndex(), "tab:" + kw, s.displayName(), null, true, "Switch shop category");
                }
            }
        }
        return null;
    }

    private static CostSpec defaultItemCost(String itemPath, String displayName) {
        if (itemPath.endsWith("_wool")) return new CostSpec(Currency.IRON, 4);
        if (itemPath.contains("end_stone")) return new CostSpec(Currency.IRON, 24);
        if (itemPath.contains("planks") || displayName.contains("wood")) return new CostSpec(Currency.GOLD, 4);
        if (itemPath.contains("glass") && !itemPath.contains("pane")) return new CostSpec(Currency.IRON, 12);
        if (itemPath.contains("obsidian")) return new CostSpec(Currency.EMERALD, 4);
        if (itemPath.equals("stone_sword")) return new CostSpec(Currency.IRON, 10);
        if (itemPath.equals("iron_sword")) return new CostSpec(Currency.GOLD, 7);
        if (itemPath.equals("diamond_sword")) return new CostSpec(Currency.EMERALD, 4);
        if (itemPath.contains("chainmail") || displayName.contains("chainmail")) return new CostSpec(Currency.IRON, 40);
        if (itemPath.contains("iron_boots") || itemPath.contains("iron_leggings") || displayName.contains("iron armor") || displayName.contains("iron armour")) {
            return new CostSpec(Currency.GOLD, 12);
        }
        if (itemPath.contains("diamond_boots") || itemPath.contains("diamond_leggings") || displayName.contains("diamond armor") || displayName.contains("diamond armour")) {
            return new CostSpec(Currency.EMERALD, 6);
        }
        if (itemPath.equals("shears")) return new CostSpec(Currency.IRON, 20);
        if (itemPath.endsWith("_pickaxe") || itemPath.endsWith("_axe")) {
            if (itemPath.startsWith("diamond_")) return new CostSpec(Currency.GOLD, 6);
            if (itemPath.startsWith("gold_") || itemPath.startsWith("golden_")) return new CostSpec(Currency.GOLD, 3);
            if (itemPath.startsWith("iron_")) return new CostSpec(Currency.GOLD, 3);
            return new CostSpec(Currency.IRON, 10);
        }
        if (itemPath.equals("fire_charge")) return new CostSpec(Currency.IRON, 40);
        if (itemPath.equals("golden_apple")) return new CostSpec(Currency.GOLD, 3);
        if (itemPath.equals("tnt")) return new CostSpec(Currency.GOLD, 4);
        if (itemPath.equals("ender_pearl")) return new CostSpec(Currency.EMERALD, 4);
        return null;
    }

    private static String lower(String s) {
        return s == null ? "" : s.trim().toLowerCase(Locale.ROOT);
    }
}
