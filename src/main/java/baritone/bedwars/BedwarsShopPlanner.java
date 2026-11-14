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

import baritone.bedwars.BedwarsTacticalHelper.InventorySummary;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure-Java Bedwars Villager Item Shop &amp; Team Upgrades planner and cross-server GUI slot matcher.
 * Matches displayed item registry paths, display titles, and parsed currency costs rather than hardcoded
 * QuickBuy positions so purchases work across Hypixel-style and custom Bedwars servers.
 */
public final class BedwarsShopPlanner {

    private static final Pattern COST_PATTERN = Pattern.compile("(?i)(\\d{1,3})\\s*x?\\s*(iron|gold|diamond|diamonds|emerald|emeralds)");
    private static final Pattern COST_PATTERN_ALT = Pattern.compile("(?i)\\b(iron|gold|diamond|diamonds|emerald|emeralds)\\s*(?:ingots?|blocks?)?\\s*[:x-]\\s*(\\d{1,3})\\b");

    public enum ShopPreset {
        ESSENTIALS,
        DEFENSE,
        COMBAT,
        UTILITY,
        UPGRADES,
        AUTO;

        public static ShopPreset fromString(String raw) {
            if (raw == null || raw.isBlank()) {
                return AUTO;
            }
            return switch (raw.trim().toLowerCase(Locale.ROOT)) {
                case "essentials", "wool", "rush", "basic", "tools" -> ESSENTIALS;
                case "defense", "defend", "endstone", "blocks" -> DEFENSE;
                case "combat", "gear", "sword", "armor", "gap" -> COMBAT;
                case "utility", "tnt", "fb", "fireball", "pearl" -> UTILITY;
                case "upgrades", "upgrade", "team", "sharp", "prot" -> UPGRADES;
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
        ITEM_SHOP,
        UPGRADE_SHOP,
        UNKNOWN
    }

    public record CostSpec(Currency currency, int amount) {}

    public record ShopSlotInfo(
            int slotIndex,
            String itemPath,
            String displayName,
            String loreText,
            int count
    ) {}

    public record ShopClickDecision(
            int slotIndex,
            String itemLabel,
            boolean categorySwitch,
            String reason
    ) {}

    private BedwarsShopPlanner() {}

    /**
     * Chooses the highest-leverage shop preset given current inventory and base threat context.
     */
    public static ShopPreset recommendPreset(
            InventorySummary inv,
            boolean homeBedAlive,
            double homeDefenseRatio,
            double nearestEnemyDist
    ) {
        if (inv == null) {
            return ShopPreset.ESSENTIALS;
        }
        if (inv.diamonds() >= 2 && homeBedAlive) {
            return ShopPreset.UPGRADES;
        }
        if (inv.bridgeBlockCount() < 16 && inv.iron() >= 4) {
            return ShopPreset.ESSENTIALS;
        }
        if (homeBedAlive && homeDefenseRatio < 0.65 && (inv.iron() >= 16 || inv.gold() >= 4)) {
            return ShopPreset.DEFENSE;
        }
        if (inv.emeralds() >= 4 || inv.fireballs() == 0 && inv.iron() >= 40) {
            return ShopPreset.UTILITY;
        }
        if ((inv.swordTier() < 2 && inv.iron() >= 10) || (inv.shearsTier() == 0 && inv.iron() >= 20)) {
            return ShopPreset.ESSENTIALS;
        }
        if (inv.gold() >= 3 || (nearestEnemyDist <= 28.0 && inv.iron() >= 24)) {
            return ShopPreset.COMBAT;
        }
        return ShopPreset.ESSENTIALS;
    }

    /**
     * Parses a lore cost string such as {@code "Cost: 16 Iron"} or {@code "4 Emeralds"}.
     */
    public static CostSpec parseCostFromLore(String loreText) {
        if (loreText == null || loreText.isBlank()) {
            return null;
        }
        Matcher m = COST_PATTERN.matcher(loreText);
        if (m.find()) {
            int amount = parsePositiveInt(m.group(1));
            return amount > 0 ? new CostSpec(parseCurrencyToken(m.group(2)), amount) : null;
        }
        Matcher alt = COST_PATTERN_ALT.matcher(loreText);
        if (alt.find()) {
            int amount = parsePositiveInt(alt.group(2));
            return amount > 0 ? new CostSpec(parseCurrencyToken(alt.group(1)), amount) : null;
        }
        return null;
    }

    private static int parsePositiveInt(String raw) {
        try {
            return Math.max(0, Integer.parseInt(raw.trim()));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private static Currency parseCurrencyToken(String unit) {
        String lower = unit.toLowerCase(Locale.ROOT);
        if (lower.startsWith("gold")) return Currency.GOLD;
        if (lower.startsWith("diamond")) return Currency.DIAMOND;
        if (lower.startsWith("emerald")) return Currency.EMERALD;
        return Currency.IRON;
    }

    /**
     * Classifies an open Bedwars container GUI by window title AND slot offer contents so
     * clicking the Upgrades NPC while wanting wool/gear is immediately detected and self-corrected.
     */
    public static MenuKind classifyOpenMenu(String menuTitle, List<ShopSlotInfo> containerSlots) {
        String tLower = menuTitle != null ? menuTitle.toLowerCase(Locale.ROOT) : "";
        if (tLower.contains("upgrade") || tLower.contains("traps") || tLower.contains("solo upgrade") || tLower.contains("team upgrade")) {
            return MenuKind.UPGRADE_SHOP;
        }
        if (tLower.contains("quick buy") || tLower.contains("item shop")
                || tLower.equals("blocks") || tLower.equals("melee")
                || tLower.equals("armor") || tLower.equals("armour")
                || tLower.equals("tools") || tLower.equals("ranged")
                || tLower.equals("potions") || tLower.equals("utility")) {
            return MenuKind.ITEM_SHOP;
        }

        int upgradeScore = 0;
        int itemShopScore = 0;
        if (containerSlots != null) {
            for (ShopSlotInfo s : containerSlots) {
                if (s == null) {
                    continue;
                }
                String combined = ((s.displayName() != null ? s.displayName() : "")
                        + "\n" + (s.loreText() != null ? s.loreText() : "")).toLowerCase(Locale.ROOT);
                String path = s.itemPath() != null ? s.itemPath().toLowerCase(Locale.ROOT) : "";
                if (combined.contains("sharpened sword") || combined.contains("reinforced armor")
                        || combined.contains("maniac miner") || combined.contains("iron forge")
                        || combined.contains("molten forge") || combined.contains("emerald forge")
                        || combined.contains("heal pool") || combined.contains("dragon buff")
                        || combined.contains("it's a trap") || combined.contains("counter-offensive")
                        || combined.contains("mining fatigue") || combined.contains("alarm trap")
                        || combined.contains("buy a trap")) {
                    upgradeScore += 3;
                }
                CostSpec cost = parseCostFromLore(combined);
                if (cost != null && (cost.currency() == Currency.IRON || cost.currency() == Currency.GOLD)) {
                    if (path.endsWith("_wool") || path.contains("sword") || path.equals("shears")
                            || path.contains("pickaxe") || path.contains("golden_apple")
                            || path.contains("fire_charge") || path.contains("end_stone")) {
                        itemShopScore += 3;
                    } else {
                        itemShopScore += 1;
                    }
                } else if (cost != null && cost.currency() == Currency.DIAMOND && (combined.contains("tier") || combined.contains("trap") || combined.contains("forge"))) {
                    upgradeScore += 2;
                }
            }
        }
        if (upgradeScore > itemShopScore) {
            return MenuKind.UPGRADE_SHOP;
        }
        if (itemShopScore > upgradeScore) {
            return MenuKind.ITEM_SHOP;
        }
        return MenuKind.UNKNOWN;
    }

    public static boolean canAfford(InventorySummary inv, CostSpec cost) {
        if (inv == null) {
            return false;
        }
        if (cost == null) {
            return true;
        }
        return switch (cost.currency()) {
            case IRON -> inv.iron() >= cost.amount();
            case GOLD -> inv.gold() >= cost.amount();
            case DIAMOND -> inv.diamonds() >= cost.amount();
            case EMERALD -> inv.emeralds() >= cost.amount();
        };
    }

    /**
     * Picks the best next slot click inside the top container area (typically slots {@code 0..53})
     * for {@code requestedPreset}. Resolves {@link ShopPreset#AUTO} automatically.
     */
    public static ShopClickDecision chooseNextShopClick(
            List<ShopSlotInfo> containerSlots,
            ShopPreset requestedPreset,
            InventorySummary inv,
            boolean inUpgradeShop,
            boolean homeBedAlive,
            double homeDefenseRatio,
            double nearestEnemyDist,
            boolean categoryAlreadySwitched
    ) {
        if (containerSlots == null || containerSlots.isEmpty() || inv == null) {
            return null;
        }
        ShopPreset preset = (requestedPreset == null || requestedPreset == ShopPreset.AUTO)
                ? recommendPreset(inv, homeBedAlive, homeDefenseRatio, nearestEnemyDist)
                : requestedPreset;

        if (inUpgradeShop || preset == ShopPreset.UPGRADES) {
            return chooseUpgradeClick(containerSlots, inv);
        }

        ShopClickDecision bestItem = null;
        double bestScore = 0.0;
        for (ShopSlotInfo slot : containerSlots) {
            if (slot == null || slot.itemPath() == null || slot.itemPath().isBlank()) {
                continue;
            }
            if (isAlreadyMaxedOrRedundant(slot, inv)) {
                continue;
            }
            double score = scoreShopOffer(slot, preset, inv, homeBedAlive, homeDefenseRatio);
            if (score <= bestScore) {
                continue;
            }
            CostSpec parsed = parseCostFromLore((slot.displayName() != null ? slot.displayName() : "") + "\n" + (slot.loreText() != null ? slot.loreText() : ""));
            CostSpec fallback = defaultItemCost(slot.itemPath(), slot.displayName());
            CostSpec effective = parsed != null ? parsed : fallback;
            if (!canAfford(inv, effective)) {
                continue;
            }
            bestScore = score;
            bestItem = new ShopClickDecision(slot.slotIndex(), slot.displayName(), false, "buy:" + preset.name());
        }

        if (bestItem != null) {
            return bestItem;
        }
        if (categoryAlreadySwitched) {
            return null;
        }
        return findCategoryTabForPreset(containerSlots, preset, inv);
    }

    private static ShopClickDecision chooseUpgradeClick(List<ShopSlotInfo> slots, InventorySummary inv) {
        if (inv.diamonds() <= 0) {
            return null;
        }
        ShopClickDecision best = null;
        double bestScore = 0.0;
        for (ShopSlotInfo slot : slots) {
            if (slot == null || slot.itemPath() == null) {
                continue;
            }
            String text = ((slot.displayName() == null ? "" : slot.displayName()) + " "
                    + (slot.loreText() == null ? "" : slot.loreText())).toLowerCase(Locale.ROOT);
            if (text.contains("maxed") || text.contains("unlocked!") || text.contains("you have already")) {
                continue;
            }
            CostSpec parsed = parseCostFromLore((slot.displayName() != null ? slot.displayName() : "") + "\n" + (slot.loreText() != null ? slot.loreText() : ""));
            if (parsed != null && !canAfford(inv, parsed)) {
                continue;
            }
            double score = 0.0;
            if (text.contains("sharp") || slot.itemPath().contains("iron_sword")) {
                score = inv.diamonds() >= 4 ? 120.0 : 0.0;
            } else if (text.contains("protection") || text.contains("reinforced armor") || slot.itemPath().contains("iron_chestplate")) {
                score = inv.diamonds() >= 2 ? 105.0 : 0.0;
            } else if (text.contains("maniac miner") || text.contains("haste")) {
                score = inv.diamonds() >= 2 ? 78.0 : 0.0;
            } else if (text.contains("forge")) {
                score = inv.diamonds() >= 2 ? 70.0 : 0.0;
            } else if (text.contains("trap")) {
                score = inv.diamonds() >= 1 ? 62.0 : 0.0;
            } else if (text.contains("heal pool")) {
                score = inv.diamonds() >= 1 ? 52.0 : 0.0;
            }
            if (score > bestScore) {
                bestScore = score;
                best = new ShopClickDecision(slot.slotIndex(), slot.displayName(), false, "upgrade");
            }
        }
        return best;
    }

    private static double scoreShopOffer(
            ShopSlotInfo slot,
            ShopPreset preset,
            InventorySummary inv,
            boolean homeBedAlive,
            double homeDefenseRatio
    ) {
        String p = slot.itemPath().toLowerCase(Locale.ROOT);
        String name = slot.displayName() == null ? "" : slot.displayName().toLowerCase(Locale.ROOT);
        if (slot.slotIndex() < 9 && isCategoryHeaderItem(p, name)) {
            return -1.0;
        }
        if (p.endsWith("_wool")) {
            if (inv.woolCount() >= 48) {
                return -1.0;
            }
            return inv.woolCount() < 16 ? 130.0 : (inv.woolCount() < 32 ? 90.0 : 42.0);
        }
        if (p.equals("end_stone")) {
            if (preset == ShopPreset.DEFENSE && homeBedAlive && homeDefenseRatio < 0.85 && inv.hardDefenseCount() < 16) {
                return 125.0;
            }
            return (homeBedAlive && homeDefenseRatio < 0.65 && inv.hardDefenseCount() < 12) ? 88.0 : 0.0;
        }
        if (p.endsWith("_planks")) {
            if (preset == ShopPreset.DEFENSE && homeBedAlive && inv.hardDefenseCount() < 16) {
                return 96.0;
            }
            return 0.0;
        }
        if (p.equals("shears")) {
            return inv.shearsTier() == 0 ? 105.0 : -1.0;
        }
        if (p.equals("stone_sword")) {
            return inv.swordTier() < 2 ? 115.0 : -1.0;
        }
        if (p.equals("iron_sword")) {
            return inv.swordTier() < 3 ? (preset == ShopPreset.COMBAT ? 122.0 : 95.0) : -1.0;
        }
        if (p.equals("diamond_sword")) {
            return inv.swordTier() < 4 ? 128.0 : -1.0;
        }
        if (p.contains("pickaxe")) {
            int tier = BedwarsTacticalHelper.toolTierFromPath(p);
            return inv.pickaxeTier() == 0 ? 92.0 : (tier > inv.pickaxeTier() && inv.iron() >= 20 ? 58.0 : -1.0);
        }
        if (p.contains("_axe")) {
            int tier = BedwarsTacticalHelper.toolTierFromPath(p);
            return inv.axeTier() == 0 ? 84.0 : (tier > inv.axeTier() && inv.iron() >= 20 ? 50.0 : -1.0);
        }
        if (p.contains("diamond_boots") || name.contains("diamond armor")) {
            return inv.armorPoints() < 16.0 ? 126.0 : -1.0;
        }
        if (p.contains("iron_boots") || name.contains("iron armor")) {
            return inv.armorPoints() < 13.0 ? 118.0 : -1.0;
        }
        if (p.contains("chainmail_boots") || name.contains("chainmail armor")) {
            return inv.armorPoints() < 9.0 ? 92.0 : -1.0;
        }
        if (p.equals("golden_apple")) {
            if (inv.gapples() >= 3) {
                return -1.0;
            }
            return preset == ShopPreset.COMBAT ? 98.0 : 72.0;
        }
        if (p.equals("fire_charge")) {
            if (inv.fireballs() >= 2) {
                return -1.0;
            }
            return preset == ShopPreset.UTILITY ? 108.0 : 76.0;
        }
        if (p.equals("tnt")) {
            if (inv.tntCount() >= 2) {
                return -1.0;
            }
            return preset == ShopPreset.UTILITY ? 96.0 : 60.0;
        }
        if (p.equals("ender_pearl")) {
            if (inv.pearls() >= 2) {
                return -1.0;
            }
            return preset == ShopPreset.UTILITY ? 124.0 : 88.0;
        }
        return 0.0;
    }

    private static boolean isAlreadyMaxedOrRedundant(ShopSlotInfo slot, InventorySummary inv) {
        String p = slot.itemPath().toLowerCase(Locale.ROOT);
        if (p.equals("shears") && inv.shearsTier() > 0) return true;
        if (p.equals("stone_sword") && inv.swordTier() >= 2) return true;
        if (p.equals("iron_sword") && inv.swordTier() >= 3) return true;
        if (p.equals("diamond_sword") && inv.swordTier() >= 4) return true;
        String lore = slot.loreText() == null ? "" : slot.loreText().toLowerCase(Locale.ROOT);
        return lore.contains("already purchased") || lore.contains("you don't have enough");
    }

    private static boolean isCategoryHeaderItem(String path, String name) {
        return name.contains("quick buy")
                || name.contains("blocks")
                || name.contains("melee")
                || name.contains("armor")
                || name.contains("tools")
                || name.contains("ranged")
                || name.contains("potions")
                || name.contains("utility")
                || path.contains("nether_star");
    }

    private static ShopClickDecision findCategoryTabForPreset(
            List<ShopSlotInfo> slots,
            ShopPreset preset,
            InventorySummary inv
    ) {
        String wantedTab = switch (preset) {
            case DEFENSE -> "blocks";
            case COMBAT -> inv.gold() >= 12 && inv.armorPoints() < 13 ? "armor" : "melee";
            case UTILITY -> "utility";
            case ESSENTIALS -> inv.bridgeBlockCount() < 16 ? "blocks" : (inv.swordTier() < 2 ? "melee" : "tools");
            default -> "blocks";
        };
        for (ShopSlotInfo slot : slots) {
            if (slot.slotIndex() > 8 || slot.displayName() == null) {
                continue;
            }
            String lower = slot.displayName().toLowerCase(Locale.ROOT);
            if (lower.contains(wantedTab)) {
                return new ShopClickDecision(slot.slotIndex(), slot.displayName(), true, "tab:" + wantedTab);
            }
        }
        return null;
    }

    private static CostSpec defaultItemCost(String itemPath, String displayName) {
        String p = itemPath.toLowerCase(Locale.ROOT);
        if (p.endsWith("_wool")) return new CostSpec(Currency.IRON, 4);
        if (p.equals("end_stone")) return new CostSpec(Currency.IRON, 24);
        if (p.endsWith("_planks")) return new CostSpec(Currency.GOLD, 4);
        if (p.equals("stone_sword")) return new CostSpec(Currency.IRON, 10);
        if (p.equals("iron_sword")) return new CostSpec(Currency.GOLD, 7);
        if (p.equals("diamond_sword")) return new CostSpec(Currency.EMERALD, 4);
        if (p.equals("shears")) return new CostSpec(Currency.IRON, 20);
        if (p.contains("pickaxe") || p.contains("_axe")) return new CostSpec(Currency.IRON, 10);
        if (p.contains("chainmail_boots")) return new CostSpec(Currency.IRON, 40);
        if (p.contains("iron_boots")) return new CostSpec(Currency.GOLD, 12);
        if (p.contains("diamond_boots")) return new CostSpec(Currency.EMERALD, 6);
        if (p.equals("golden_apple")) return new CostSpec(Currency.GOLD, 3);
        if (p.equals("fire_charge")) return new CostSpec(Currency.IRON, 40);
        if (p.equals("tnt")) return new CostSpec(Currency.GOLD, 4);
        if (p.equals("ender_pearl")) return new CostSpec(Currency.EMERALD, 4);
        return new CostSpec(Currency.IRON, 999);
    }
}
