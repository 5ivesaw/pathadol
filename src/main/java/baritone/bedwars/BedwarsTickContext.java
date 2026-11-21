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

import baritone.api.utils.IPlayerContext;
import baritone.bedwars.BedwarsMapCore.GenPOI;
import baritone.bedwars.BedwarsMapCore.GenType;
import baritone.bedwars.BedwarsMapCore.TeamColor;
import baritone.bedwars.BedwarsTacticalHelper.InventorySummary;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Blaze;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.wanderingtrader.WanderingTrader;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.hurtingprojectile.Fireball;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Single-pass reusable per-tick perception cache shared across {@link BedwarsArenaMemory},
 * {@link BedwarsProcess}, {@link baritone.brain.BrainContextExtractor}, and {@link baritone.process.CombatProcess}.
 *
 * <p>Replaces 12+ redundant {@code ctx.entities()} loops and per-tick inventory summaries with ONE sweep
 * per game tick, caches {@link InventorySummary} by {@code Inventory.getTimesChanged()}, extracts both
 * {@link ArmorStand} and {@link Display.TextDisplay} holograms, tracks last-seen enemy gear/positions,
 * and pushes 5 Hz threat heat updates into {@link BedwarsMapCore}.</p>
 */
public final class BedwarsTickContext {

    public record HologramInfo(double x, double y, double z, String cleanText, String lowerText, boolean isTextDisplay) {}

    public record LastSeenEnemy(
            String name,
            double x,
            double y,
            double z,
            double armorPoints,
            int swordTier,
            double hp,
            TeamColor team,
            long seenWallMs
    ) {}

    private long lastSweepGameTime = Long.MIN_VALUE;
    private LocalPlayer lastSweepPlayer = null;
    private long lastThreatGridTick = Long.MIN_VALUE;
    private long lastHologramFeedTick = Long.MIN_VALUE;

    private final List<Player> enemies = new ArrayList<>(16);
    private final List<Player> allies = new ArrayList<>(8);
    private final List<HologramInfo> holograms = new ArrayList<>(32);
    private final List<ItemEntity> currencyDrops = new ArrayList<>(24);
    private final List<Entity> fireballs = new ArrayList<>(8);
    private final List<Entity> shopEntities = new ArrayList<>(16);
    private final Map<String, LastSeenEnemy> lastSeenEnemies = new HashMap<>(16);

    // Reusable buffers for 5 Hz threat influence grid updates (zero allocations)
    private final double[] threatCoordsBuf = new double[24 * 3];
    private final float[] threatWeightsBuf = new float[24];

    // Cached inventory summary keyed by Inventory.getTimesChanged()
    private InventorySummary cachedInv = null;
    private int lastInvTimesChanged = Integer.MIN_VALUE;
    private boolean lastInvModern = false;
    private LocalPlayer lastInvPlayer = null;
    private long lastInvRefreshTick = Long.MIN_VALUE;

    // Reusable mutable BlockPos for zero-alloc probes
    private final BlockPos.MutableBlockPos scratchPos = new BlockPos.MutableBlockPos();

    // Cached 5 Hz connected-bridge surface solver workspace (zero per-tick allocations)
    private final BedwarsBridgeConnectivity.Workspace bridgeWorkspace = new BedwarsBridgeConnectivity.Workspace();
    private long lastBridgeSolveTick = Long.MIN_VALUE;
    private int lastBridgeSolveX = Integer.MIN_VALUE;
    private int lastBridgeSolveY = Integer.MIN_VALUE;
    private int lastBridgeSolveZ = Integer.MIN_VALUE;

    public void reset() {
        lastSweepGameTime = Long.MIN_VALUE;
        lastSweepPlayer = null;
        lastThreatGridTick = Long.MIN_VALUE;
        lastHologramFeedTick = Long.MIN_VALUE;
        enemies.clear();
        allies.clear();
        holograms.clear();
        currencyDrops.clear();
        fireballs.clear();
        shopEntities.clear();
        lastSeenEnemies.clear();
        cachedInv = null;
        lastInvTimesChanged = Integer.MIN_VALUE;
        lastInvPlayer = null;
        lastInvRefreshTick = Long.MIN_VALUE;
        lastBridgeSolveTick = Long.MIN_VALUE;
        lastBridgeSolveX = Integer.MIN_VALUE;
        lastBridgeSolveY = Integer.MIN_VALUE;
        lastBridgeSolveZ = Integer.MIN_VALUE;
    }

    /**
     * Refreshes the entity cache at most once per Minecraft world tick.
     */
    public void refreshIfNeeded(IPlayerContext ctx, BedwarsArenaMemory arenaMemory) {
        if (ctx == null || ctx.player() == null || ctx.world() == null) {
            return;
        }
        LocalPlayer me = ctx.player();
        long gameTime = ctx.world().getGameTime();
        if (gameTime == lastSweepGameTime && me == lastSweepPlayer) {
            return;
        }
        lastSweepGameTime = gameTime;
        lastSweepPlayer = me;

        enemies.clear();
        allies.clear();
        holograms.clear();
        currencyDrops.clear();
        fireballs.clear();
        shopEntities.clear();

        long nowMs = System.currentTimeMillis();
        BedwarsMatchState matchState = arenaMemory != null ? arenaMemory.getMatchState() : null;
        BedwarsMapCore core = arenaMemory != null ? arenaMemory.getActiveCore() : null;
        boolean feedHolograms = lastHologramFeedTick == Long.MIN_VALUE || gameTime - lastHologramFeedTick >= 4L;
        if (feedHolograms) {
            lastHologramFeedTick = gameTime;
        }

        for (Entity e : ctx.entities()) {
            if (e == null || !e.isAlive() || e.isRemoved()) {
                continue;
            }
            if (e instanceof Player pl) {
                if (pl == me || pl.isSpectator()) {
                    continue;
                }
                if (pl.getUUID().version() == 2) {
                    shopEntities.add(pl);
                    continue;
                }
                if (me.isAlliedTo(pl)) {
                    allies.add(pl);
                    continue;
                }
                enemies.add(pl);
                String name = BedwarsArenaMemory.stripColors(pl.getName().getString());
                if (!name.isEmpty()) {
                    String key = name.toLowerCase(Locale.ROOT);
                    double armor = BedwarsTacticalHelper.computePlayerArmorPoints(pl);
                    ItemStack mainHand = pl.getMainHandItem();
                    int swordTier = mainHand.isEmpty() ? 0 : BedwarsTacticalHelper.swordTierFromItem(mainHand.getItem());
                    TeamColor tc = matchState != null ? matchState.getPlayerTeam(name) : TeamColor.UNKNOWN;
                    lastSeenEnemies.put(key, new LastSeenEnemy(
                            name, pl.getX(), pl.getY(), pl.getZ(),
                            armor, swordTier, pl.getHealth(), tc, nowMs
                    ));
                }
            } else if (e instanceof ArmorStand as) {
                Component cn = as.getCustomName();
                String raw = cn != null ? cn.getString() : "";
                if (!raw.isEmpty()) {
                    String clean = BedwarsArenaMemory.stripColors(raw);
                    if (!clean.isEmpty()) {
                        String lower = clean.toLowerCase(Locale.ROOT);
                        holograms.add(new HologramInfo(as.getX(), as.getY(), as.getZ(), clean, lower, false));
                    }
                }
            } else if (e instanceof Display.TextDisplay td) {
                String raw = extractTextDisplayString(td);
                if (!raw.isEmpty()) {
                    String clean = BedwarsArenaMemory.stripColors(raw);
                    if (!clean.isEmpty()) {
                        String lower = clean.toLowerCase(Locale.ROOT);
                        holograms.add(new HologramInfo(td.getX(), td.getY(), td.getZ(), clean, lower, true));
                    }
                }
            } else if (e instanceof ItemEntity ie) {
                ItemStack stack = ie.getItem();
                if (!stack.isEmpty()) {
                    Item it = stack.getItem();
                    if (it == Items.DIAMOND || it == Items.EMERALD || it == Items.GOLD_INGOT || it == Items.IRON_INGOT) {
                        currencyDrops.add(ie);
                    }
                }
            } else if (e instanceof Fireball) {
                fireballs.add(e);
            } else if (e instanceof Villager || e instanceof WanderingTrader || e instanceof Blaze) {
                shopEntities.add(e);
            }
        }

        // Feed generator countdown / tier holograms into BedwarsMatchState at 5 Hz
        if (feedHolograms && matchState != null && !holograms.isEmpty()) {
            GenPOI[] gens = core != null ? core.getGensSnapshot() : null;
            for (int i = 0, n = holograms.size(); i < n; i++) {
                HologramInfo h = holograms.get(i);
                String l = h.lowerText();
                if (l.contains("diamond") || l.contains("emerald") || l.contains("spawn") || l.contains("tier")) {
                    GenType inferred = GenType.HOME_FORGE;
                    if (l.contains("diamond")) {
                        inferred = GenType.DIAMOND;
                    } else if (l.contains("emerald")) {
                        inferred = GenType.EMERALD;
                    } else if (gens != null) {
                        // Associate bare countdown lines ("Spawns in 12 seconds") with nearest Diamond/Emerald POI within 4m
                        double bestDSq = 16.0;
                        for (int g = 0; g < gens.length; g++) {
                            GenPOI gp = gens[g];
                            if (gp.type() != GenType.DIAMOND && gp.type() != GenType.EMERALD) {
                                continue;
                            }
                            double dx = h.x() - (gp.x() + 0.5);
                            double dz = h.z() - (gp.z() + 0.5);
                            double dSq = dx * dx + dz * dz;
                            if (dSq <= bestDSq) {
                                bestDSq = dSq;
                                inferred = gp.type();
                            }
                        }
                    }
                    if (inferred == GenType.DIAMOND || inferred == GenType.EMERALD) {
                        matchState.onGeneratorHologram((int) Math.floor(h.x()), (int) Math.floor(h.z()), inferred, h.cleanText(), nowMs);
                    }
                }
            }
        }

        // Prune stale last-seen enemies (> 18s) and update 2D Influence Map threat heat at 5 Hz
        if (core != null && (lastThreatGridTick == Long.MIN_VALUE || gameTime - lastThreatGridTick >= 4L)) {
            lastThreatGridTick = gameTime;
            int count = 0;
            for (int i = 0, n = enemies.size(); i < n && count < 24; i++) {
                Player ep = enemies.get(i);
                int base = count * 3;
                threatCoordsBuf[base] = ep.getX();
                threatCoordsBuf[base + 1] = ep.getY();
                threatCoordsBuf[base + 2] = ep.getZ();
                ItemStack mh = ep.getMainHandItem();
                int sw = mh.isEmpty() ? 0 : BedwarsTacticalHelper.swordTierFromItem(mh.getItem());
                float weight = 1.0f + sw * 0.12f;
                threatWeightsBuf[count] = weight;
                count++;
            }
            // Add recently seen enemies not currently in render range (decayed heat)
            Iterator<Map.Entry<String, LastSeenEnemy>> it = lastSeenEnemies.entrySet().iterator();
            while (it.hasNext()) {
                LastSeenEnemy lse = it.next().getValue();
                long ageMs = nowMs - lse.seenWallMs();
                if (ageMs > 18_000L) {
                    it.remove();
                    continue;
                }
                if (ageMs > 300L && count < 24) {
                    int base = count * 3;
                    threatCoordsBuf[base] = lse.x();
                    threatCoordsBuf[base + 1] = lse.y();
                    threatCoordsBuf[base + 2] = lse.z();
                    float decay = (float) Math.max(0.2, 1.0 - (ageMs / 18_000.0));
                    threatWeightsBuf[count] = 0.75f * decay;
                    count++;
                }
            }
            core.updateThreatHeatGrid(threatCoordsBuf, threatWeightsBuf, count);
        }
    }

    /**
     * Extracts text from a {@link Display.TextDisplay} safely across MC 26.3 render-state / custom-name paths.
     */
    public static String extractTextDisplayString(Display.TextDisplay td) {
        if (td == null) {
            return "";
        }
        Display.TextDisplay.TextRenderState rs = td.textRenderState();
        if (rs != null && rs.text() != null) {
            String s = rs.text().getString();
            if (s != null && !s.isBlank()) {
                return s;
            }
        }
        Component cn = td.getCustomName();
        return cn != null ? cn.getString() : "";
    }

    /**
     * Returns a cached {@link InventorySummary}, recomputing only when {@code Inventory.getTimesChanged()}
     * changes (or every 10 ticks as an armor-sync safety fallback).
     */
    public InventorySummary getInventorySummary(LocalPlayer player, boolean modernRuleset, long gameTime) {
        if (player == null) {
            return BedwarsTacticalHelper.summarizeInventory(null, modernRuleset);
        }
        int timesChanged = player.getInventory().getTimesChanged();
        if (cachedInv == null
                || player != lastInvPlayer
                || timesChanged != lastInvTimesChanged
                || modernRuleset != lastInvModern
                || (gameTime - lastInvRefreshTick) >= 10L) {
            cachedInv = BedwarsTacticalHelper.summarizeInventory(player, modernRuleset);
            lastInvPlayer = player;
            lastInvTimesChanged = timesChanged;
            lastInvModern = modernRuleset;
            lastInvRefreshTick = gameTime;
        }
        return cachedInv;
    }

    public List<Player> enemies() {
        return enemies;
    }

    public List<Player> allies() {
        return allies;
    }

    public List<HologramInfo> holograms() {
        return holograms;
    }

    public List<ItemEntity> currencyDrops() {
        return currencyDrops;
    }

    public List<Entity> fireballs() {
        return fireballs;
    }

    public List<Entity> shopEntities() {
        return shopEntities;
    }

    public Map<String, LastSeenEnemy> lastSeenEnemies() {
        return lastSeenEnemies;
    }

    public LastSeenEnemy getLastSeenEnemy(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        return lastSeenEnemies.get(name.trim().toLowerCase(Locale.ROOT));
    }

    public BlockPos.MutableBlockPos scratchPos() {
        return scratchPos;
    }

    /**
     * Returns the connected-surface {@link BedwarsBridgeConnectivity.Workspace}, solving at most
     * every 4 ticks (or immediately when the player moves &ge; 2 blocks).
     */
    public BedwarsBridgeConnectivity.Workspace getOrSolveBridgeConnectivity(
            long gameTime,
            int px,
            int py,
            int pz,
            BedwarsBridgeConnectivity.ColumnGroundProbe probe
    ) {
        boolean moved = Math.abs(px - lastBridgeSolveX) >= 2
                || Math.abs(py - lastBridgeSolveY) >= 2
                || Math.abs(pz - lastBridgeSolveZ) >= 2;
        if (lastBridgeSolveTick == Long.MIN_VALUE || moved || (gameTime - lastBridgeSolveTick) >= 4L) {
            BedwarsBridgeConnectivity.solve(bridgeWorkspace, px, py, pz, probe);
            lastBridgeSolveTick = gameTime;
            lastBridgeSolveX = px;
            lastBridgeSolveY = py;
            lastBridgeSolveZ = pz;
        }
        return bridgeWorkspace;
    }
}
