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

import baritone.Baritone;
import baritone.api.event.events.BlockChangeEvent;
import baritone.api.event.events.PacketEvent;
import baritone.api.event.events.TickEvent;
import baritone.api.event.events.WorldEvent;
import baritone.api.event.events.type.EventState;
import baritone.api.event.listener.AbstractGameEventListener;
import baritone.api.utils.Helper;
import baritone.api.utils.IPlayerContext;
import baritone.api.utils.Pair;
import baritone.bedwars.BedwarsDefensePlanner.DefenseBlockKind;
import baritone.bedwars.BedwarsMapCacheIO.CachedMapEntry;
import baritone.bedwars.BedwarsMapCacheIO.LoadedCache;
import baritone.bedwars.BedwarsMapCore.ArenaBounds;
import baritone.bedwars.BedwarsMapCore.BedPOI;
import baritone.bedwars.BedwarsMapCore.BwsRuleset;
import baritone.bedwars.BedwarsMapCore.GenPOI;
import baritone.bedwars.BedwarsMapCore.GenType;
import baritone.bedwars.BedwarsMapCore.ShopNPC;
import baritone.bedwars.BedwarsMapCore.SpawnPOI;
import baritone.bedwars.BedwarsMapCore.SymmetryMode;
import baritone.bedwars.BedwarsMapCore.TeamColor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundOpenScreenPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.AirBlock;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.PlayerScoreEntry;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Live Bedwars arena spatial controller maintaining the T=0 {@link BedwarsMapCore},
 * {@code OriginalMapBitSet} / {@code PlayerPlacedBitSet} delta tracking, {@code .bwsmap} cache IO,
 * ruleset toggling ({@code #bws old|modern|off}), and edge-case listeners (late scan, height clamp, lagback).
 */
public final class BedwarsArenaMemory implements AbstractGameEventListener, Helper {

    private static final Pattern COLOR_CODE_PATTERN = Pattern.compile("\u00A7.");
    private static final Pattern MAP_LINE_PATTERN = Pattern.compile("(?i)\\bMap\\s*:\\s*([A-Za-z0-9 _'-]{2,36})");
    private static final Pattern PLAYING_ON_PATTERN = Pattern.compile("(?i)playing on\\s+(?:map\\s+)?([A-Za-z0-9 _'-]{2,36})");
    private static final Pattern MAX_HEIGHT_PATTERN = Pattern.compile("(?i)(?:height limit|above\\s*y\\s*=?)\\s*[:=]?\\s*(\\d{2,3})");

    private static List<Item> buildBedwarsBridgeItems() {
        List<Item> items = new ArrayList<>(44);
        items.addAll(Items.WOOL.asList());
        items.add(Items.TERRACOTTA);
        items.addAll(Items.DYED_TERRACOTTA.asList());
        items.add(Items.OAK_PLANKS);
        items.add(Items.SPRUCE_PLANKS);
        items.add(Items.BIRCH_PLANKS);
        items.add(Items.JUNGLE_PLANKS);
        items.add(Items.ACACIA_PLANKS);
        items.add(Items.DARK_OAK_PLANKS);
        items.add(Items.CHERRY_PLANKS);
        items.add(Items.MANGROVE_PLANKS);
        return items;
    }

    private final Baritone baritone;
    private final IPlayerContext ctx;

    private volatile BedwarsMapCore activeCore;
    private volatile BwsRuleset activeRuleset = BwsRuleset.fromString(Baritone.settings().bedwarsRuleset.value);
    private volatile String currentMapName = "unknown";
    private volatile String currentServerId = "singleplayer";
    private volatile boolean snapshotCapturedForMatch = false;
    private volatile int chatBuildHeightLimit = Integer.MIN_VALUE;
    private volatile long lagbackCooldownUntilTick = -1L;
    private volatile BlockPos lagbackSuspectedBlock = null;
    private volatile boolean pendingDelayedScan = false;
    private volatile int delayedScanCountdown = 0;
    private volatile boolean seenBedwarsScoreboardSidebar = false;
    private volatile boolean scoreboardSuggestsWaiting = false;
    private volatile boolean recentStartCountdownChat = false;
    private volatile boolean gameStartedChatSeen = false;
    private volatile boolean waitingLobbyDetected = false;
    private volatile BedwarsMapCore preservedRespawnCore = null;
    private volatile String preservedMapName = "unknown";
    private volatile long preservedAtWallMs = 0L;

    private final ConcurrentLinkedQueue<BlockUpdateItem> pendingBlockUpdates = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<String> pendingChatLines = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<Vec3> pendingServerTeleports = new ConcurrentLinkedQueue<>();

    private boolean hasSavedSettings = false;
    private String prevCombatMode = "auto";
    private double prevBlockPlacementPenalty = 20.0;
    private List<Item> prevThrowawayItems = List.of();
    private boolean prevBedwarsMode = false;
    private boolean prevAllowParkourPlace = false;
    private boolean prevAssumeSafeWalk = false;

    private int incrementalScanTicker = 0;
    private Level lastObservedWorld = null;

    private volatile int lastOpenContainerId = Integer.MIN_VALUE;
    private volatile String lastOpenContainerTitle = "";

    private record BlockUpdateItem(BlockPos pos, BlockState state) {}

    public BedwarsArenaMemory(Baritone baritone) {
        this.baritone = baritone;
        this.ctx = baritone.getPlayerContext();
    }

    public String getOpenMenuTitle(int containerId) {
        return (containerId >= 0 && containerId == lastOpenContainerId) ? lastOpenContainerTitle : "";
    }

    public Path getCacheDir() {
        return baritone.getDirectory().resolve("bedwars_cache");
    }

    public BedwarsMapCore getActiveCore() {
        return activeCore;
    }

    public boolean isSnapshotCapturedForMatch() {
        return snapshotCapturedForMatch;
    }

    public BwsRuleset getActiveRuleset() {
        return BwsRuleset.fromString(Baritone.settings().bedwarsRuleset.value);
    }

    public boolean isBedwarsActive() {
        return getActiveRuleset() != BwsRuleset.OFF;
    }

    public String getCurrentMapName() {
        return currentMapName;
    }

    public String getCurrentServerId() {
        return currentServerId;
    }

    public boolean isInWaitingLobby() {
        return waitingLobbyDetected;
    }

    /**
     * Evaluates whether the local player is inside a pre-game Bedwars waiting lobby cage rather than
     * an active match island, using hotbar lobby items, scoreboard status, chat countdowns, and
     * vertical separation above scanned beds when no Bedwars gear is held.
     */
    public static boolean isLikelyWaitingLobby(
            int playerY,
            Integer maxBedY,
            boolean hasLobbyItemInInventory,
            boolean hasActiveBedwarsGear,
            boolean scoreboardSuggestsWaiting,
            boolean recentStartCountdownChat,
            boolean gameStartedChatSeen
    ) {
        if (hasLobbyItemInInventory) {
            return true;
        }
        if ((scoreboardSuggestsWaiting || recentStartCountdownChat) && !gameStartedChatSeen && !hasActiveBedwarsGear) {
            return true;
        }
        return maxBedY != null && (playerY - maxBedY) >= 14 && !gameStartedChatSeen && !hasActiveBedwarsGear;
    }

    private boolean refreshWaitingLobbyState(LocalPlayer player, Integer overrideMaxBedY) {
        if (player == null) {
            waitingLobbyDetected = false;
            return false;
        }
        refreshMetadataFromScoreboard();
        boolean hasLobbyItem = false;
        boolean hasCombatOrBuildGear = false;
        var inv = player.getInventory().getNonEquipmentItems();
        for (int i = 0; i < Math.min(inv.size(), 36); i++) {
            ItemStack st = inv.get(i);
            if (st.isEmpty()) {
                continue;
            }
            if (st.is(Items.BARRIER)) {
                hasLobbyItem = true;
            }
            String hover = stripColors(st.getHoverName().getString()).toLowerCase(Locale.ROOT);
            if (hover.contains("back to lobby") || hover.contains("return to lobby")
                    || hover.contains("leave game") || hover.contains("leave bedwars")) {
                hasLobbyItem = true;
            }
            String path = BuiltInRegistries.ITEM.getKey(st.getItem()).getPath();
            if (path.endsWith("_sword") || path.endsWith("_wool") || path.endsWith("_terracotta")
                    || st.is(Items.SHEARS) || st.is(Items.WOODEN_PICKAXE) || st.is(Items.STONE_PICKAXE)
                    || st.is(Items.IRON_PICKAXE) || st.is(Items.DIAMOND_PICKAXE)
                    || st.is(Items.GOLDEN_APPLE) || st.is(Items.FIRE_CHARGE) || st.is(Items.ENDER_PEARL)) {
                hasCombatOrBuildGear = true;
            }
        }
        Integer maxBedY = overrideMaxBedY;
        if (maxBedY == null && activeCore != null && !activeCore.getBeds().isEmpty()) {
            int max = Integer.MIN_VALUE;
            for (BedPOI b : activeCore.getBeds()) {
                max = Math.max(max, b.y());
            }
            maxBedY = max;
        }
        boolean waiting = isLikelyWaitingLobby(
                player.blockPosition().getY(),
                maxBedY,
                hasLobbyItem,
                hasCombatOrBuildGear,
                scoreboardSuggestsWaiting,
                recentStartCountdownChat,
                gameStartedChatSeen
        );
        boolean wasWaiting = this.waitingLobbyDetected;
        this.waitingLobbyDetected = waiting;
        if (wasWaiting && !waiting && activeCore != null && isBedwarsActive()) {
            snapshotCapturedForMatch = false;
            pendingDelayedScan = true;
            delayedScanCountdown = 2;
        }
        return waiting;
    }

    public boolean isInLagbackCooldown() {
        Level world = ctx.world();
        return world != null && world.getGameTime() <= lagbackCooldownUntilTick;
    }

    public BlockPos getLagbackSuspectedBlock() {
        return lagbackSuspectedBlock;
    }

    /**
     * Switches Bedwars combat, placement cost, bridge item ordering, and crouch-eye calibration rulesets,
     * saving/restoring standard Survival defaults cleanly on {@code off}.
     */
    public synchronized String applyRuleset(BwsRuleset target) {
        BwsRuleset next = target != null ? target : BwsRuleset.OFF;
        Baritone.settings().bedwarsRuleset.value = next.toSettingString();
        this.activeRuleset = next;

        if (next == BwsRuleset.OFF) {
            if (hasSavedSettings) {
                Baritone.settings().humanCombatMode.value = prevCombatMode;
                Baritone.settings().blockPlacementPenalty.value = prevBlockPlacementPenalty;
                Baritone.settings().acceptableThrowawayItems.value.clear();
                Baritone.settings().acceptableThrowawayItems.value.addAll(prevThrowawayItems);
                Baritone.settings().bedwarsMode.value = prevBedwarsMode;
                Baritone.settings().allowParkourPlace.value = prevAllowParkourPlace;
                Baritone.settings().assumeSafeWalk.value = prevAssumeSafeWalk;
                hasSavedSettings = false;
            } else {
                Baritone.settings().bedwarsMode.value = false;
            }
            if (baritone.getBedwarsProcess() != null && baritone.getBedwarsProcess().isActive()) {
                baritone.getBedwarsProcess().cancel();
            }
            return "Bedwars ruleset disabled (normal settings restored).";
        }

        if (!hasSavedSettings) {
            prevCombatMode = Baritone.settings().humanCombatMode.value;
            prevBlockPlacementPenalty = Baritone.settings().blockPlacementPenalty.value;
            prevThrowawayItems = new ArrayList<>(Baritone.settings().acceptableThrowawayItems.value);
            prevBedwarsMode = Baritone.settings().bedwarsMode.value;
            prevAllowParkourPlace = Baritone.settings().allowParkourPlace.value;
            prevAssumeSafeWalk = Baritone.settings().assumeSafeWalk.value;
            hasSavedSettings = true;
        }

        Baritone.settings().bedwarsMode.value = true;
        Baritone.settings().blockPlacementPenalty.value = 1.8;
        Baritone.settings().allowParkourPlace.value = false;
        Baritone.settings().assumeSafeWalk.value = false;
        Baritone.settings().humanCombatMode.value = (next == BwsRuleset.OLD_1_8) ? "1.8" : "modern";
        Baritone.settings().acceptableThrowawayItems.value.clear();
        Baritone.settings().acceptableThrowawayItems.value.addAll(buildBedwarsBridgeItems());

        if (Baritone.settings().bedwarsAutoScan.value && ctx.world() != null && ctx.player() != null && !snapshotCapturedForMatch) {
            if (!refreshWaitingLobbyState(ctx.player(), null)) {
                captureSnapshotT0();
            }
        } else if (ctx.player() != null) {
            refreshWaitingLobbyState(ctx.player(), null);
        }
        String modeLabel = next == BwsRuleset.OLD_1_8
                ? "OLD 1.8 (1.8 blockhit PvP, 1.54f crouch eye, 0.30 hang)"
                : "MODERN 26.3 (cooldown/shield PvP, 1.27f crouch eye)";
        int bedCount = activeCore != null ? activeCore.getBeds().size() : 0;
        int genCount = activeCore != null ? activeCore.getGens().size() : 0;
        return "Bedwars " + modeLabel + " active [beds=" + bedCount + ", gens=" + genCount + ", map=" + currentMapName + "]";
    }

    /**
     * Captures the fast T=0 arena structural snapshot over loaded non-air chunk sections,
     * infers half-coord symmetry center, restores missing unloaded quadrants from cache/4-way mirror,
     * and persists a compact {@code .bwsmap}.
     */
    public synchronized String captureSnapshotT0() {
        if (ctx.world() == null || ctx.player() == null) {
            return "Cannot scan arena: world not loaded.";
        }
        LocalPlayer checkPlayer = ctx.player();
        if (!checkPlayer.isAlive() || checkPlayer.isDeadOrDying() || checkPlayer.isSpectator()
                || ctx.world().dimension() == Level.NETHER) {
            return "Waiting for respawn before scanning Bedwars arena.";
        }
        long startNs = System.nanoTime();
        this.currentServerId = resolveServerId();
        refreshMetadataFromScoreboard();

        BedwarsMapCore restoredRespawn = tryRestorePreservedRespawnCore(checkPlayer);
        if (restoredRespawn != null) {
            return "Restored Bedwars arena '" + this.currentMapName + "' across respawn (beds="
                    + restoredRespawn.getBeds().size() + ", gens=" + restoredRespawn.getGens().size() + ").";
        }

        LocalPlayer player = ctx.player();
        int pxChunk = player.chunkPosition().x();
        int pzChunk = player.chunkPosition().z();
        ClientChunkCache chunkSource = (ClientChunkCache) ctx.world().getChunkSource();
        int dimMinY = ctx.world().dimensionType().minY();
        int maxWorldY = dimMinY + ctx.world().dimensionType().height();

        List<LevelChunk> loadedChunks = new ArrayList<>(256);
        List<BedCandidate> discoveredBeds = new ArrayList<>();
        List<BlockPos> emeraldBlocks = new ArrayList<>();
        List<BlockPos> diamondBlocks = new ArrayList<>();
        List<BlockPos> forgeBlocks = new ArrayList<>();

        int minSolidX = Integer.MAX_VALUE;
        int maxSolidX = Integer.MIN_VALUE;
        int minSolidY = Integer.MAX_VALUE;
        int maxSolidY = Integer.MIN_VALUE;
        int minSolidZ = Integer.MAX_VALUE;
        int maxSolidZ = Integer.MIN_VALUE;

        for (int dx = -10; dx <= 10; dx++) {
            for (int dz = -10; dz <= 10; dz++) {
                int cx = pxChunk + dx;
                int cz = pzChunk + dz;
                LevelChunk chunk = chunkSource.getChunk(cx, cz, ChunkStatus.FULL, false);
                if (chunk == null || chunk.isEmpty()) {
                    continue;
                }
                loadedChunks.add(chunk);
                LevelChunkSection[] sections = chunk.getSections();
                int baseX = cx << 4;
                int baseZ = cz << 4;
                for (int secIdx = 0; secIdx < sections.length; secIdx++) {
                    LevelChunkSection sec = sections[secIdx];
                    if (sec == null || sec.hasOnlyAir()) {
                        continue;
                    }
                    int secBaseY = dimMinY + (secIdx << 4);
                    for (int ly = 0; ly < 16; ly++) {
                        int wy = secBaseY + ly;
                        if (wy < dimMinY || wy >= maxWorldY) {
                            continue;
                        }
                        for (int lz = 0; lz < 16; lz++) {
                            int wz = baseZ + lz;
                            for (int lx = 0; lx < 16; lx++) {
                                BlockState st = sec.getBlockState(lx, ly, lz);
                                Block block = st.getBlock();
                                if (block instanceof AirBlock || block == Blocks.BARRIER || !st.getFluidState().isEmpty()) {
                                    continue;
                                }
                                int wx = baseX + lx;
                                if (wx < minSolidX) minSolidX = wx;
                                if (wx > maxSolidX) maxSolidX = wx;
                                if (wy < minSolidY) minSolidY = wy;
                                if (wy > maxSolidY) maxSolidY = wy;
                                if (wz < minSolidZ) minSolidZ = wz;
                                if (wz > maxSolidZ) maxSolidZ = wz;

                                if (block instanceof BedBlock && st.getValue(BedBlock.PART) == BedPart.HEAD) {
                                    BlockPos head = new BlockPos(wx, wy, wz);
                                    BlockPos foot = head.relative(st.getValue(BedBlock.FACING).getOpposite());
                                    TeamColor color = inferBedTeamColor(chunk, dimMinY, wx, wy, wz, block);
                                    discoveredBeds.add(new BedCandidate(color, head, foot));
                                } else if (block == Blocks.EMERALD_BLOCK) {
                                    emeraldBlocks.add(new BlockPos(wx, wy, wz));
                                } else if (block == Blocks.DIAMOND_BLOCK) {
                                    diamondBlocks.add(new BlockPos(wx, wy, wz));
                                } else if (block == Blocks.IRON_BLOCK || block == Blocks.GOLD_BLOCK) {
                                    forgeBlocks.add(new BlockPos(wx, wy, wz));
                                }
                            }
                        }
                    }
                }
            }
        }

        // Exclude waiting-lobby cage blocks > 45 blocks above discovered beds from build-ceiling inference
        int arenaMaxRefY = maxSolidY;
        int arenaMinRefY = minSolidY;
        if (!discoveredBeds.isEmpty()) {
            int minBedY = Integer.MAX_VALUE;
            int maxBedY = Integer.MIN_VALUE;
            for (BedCandidate bc : discoveredBeds) {
                minBedY = Math.min(minBedY, bc.head.getY());
                maxBedY = Math.max(maxBedY, bc.head.getY());
            }
            arenaMinRefY = Math.max(minSolidY, minBedY - 18);
            arenaMaxRefY = Math.min(maxSolidY, maxBedY + 40);
        }

        double centerX;
        double centerZ;
        if (emeraldBlocks.size() >= 2) {
            double sx = 0.0;
            double sz = 0.0;
            for (BlockPos p : emeraldBlocks) {
                sx += p.getX() + 0.5;
                sz += p.getZ() + 0.5;
            }
            centerX = snapHalfCoord(sx / emeraldBlocks.size());
            centerZ = snapHalfCoord(sz / emeraldBlocks.size());
        } else if (discoveredBeds.size() >= 2) {
            double sx = 0.0;
            double sz = 0.0;
            for (BedCandidate bc : discoveredBeds) {
                sx += bc.head.getX() + 0.5;
                sz += bc.head.getZ() + 0.5;
            }
            centerX = snapHalfCoord(sx / discoveredBeds.size());
            centerZ = snapHalfCoord(sz / discoveredBeds.size());
        } else if (minSolidX != Integer.MAX_VALUE) {
            centerX = snapHalfCoord((minSolidX + maxSolidX + 1) * 0.5);
            centerZ = snapHalfCoord((minSolidZ + maxSolidZ + 1) * 0.5);
        } else {
            centerX = player.blockPosition().getX() + 0.5;
            centerZ = player.blockPosition().getZ() + 0.5;
        }

        int fallbackY = player.blockPosition().getY();
        int voidFloorY = Math.max(dimMinY, (arenaMinRefY != Integer.MAX_VALUE ? arenaMinRefY - 12 : fallbackY - 24));
        int mapMaxBuildY = chatBuildHeightLimit > voidFloorY
                ? chatBuildHeightLimit
                : Math.min(maxWorldY - 1, Math.max(voidFloorY + 64, (arenaMaxRefY != Integer.MIN_VALUE ? arenaMaxRefY + 32 : fallbackY + 48)));

        int boundsMinY = Math.max(dimMinY, Math.min(voidFloorY - 4, fallbackY - 36));
        ArenaBounds bounds = BedwarsMapCore.createCenteredBounds((int) Math.round(centerX), boundsMinY, (int) Math.round(centerZ), 128, 144);
        BedwarsMapCore core = new BedwarsMapCore(bounds, centerX, centerZ, voidFloorY, mapMaxBuildY);

        TeamColor playerTeam = resolvePlayerTeamColor();
        BedCandidate closestHome = null;
        double closestHomeDistSq = Double.POSITIVE_INFINITY;
        for (BedCandidate bc : discoveredBeds) {
            double dSq = bc.head.distSqr(player.blockPosition());
            if (dSq < closestHomeDistSq) {
                closestHomeDistSq = dSq;
                closestHome = bc;
            }
        }
        if (playerTeam == TeamColor.UNKNOWN && closestHome != null && closestHomeDistSq <= 900.0) {
            playerTeam = closestHome.team;
        }
        core.setHomeTeam(playerTeam);

        for (BedCandidate bc : discoveredBeds) {
            boolean isHome = (playerTeam != TeamColor.UNKNOWN && bc.team == playerTeam)
                    || (closestHome == bc && closestHomeDistSq <= 900.0);
            TeamColor bedTeam = (isHome && playerTeam != TeamColor.UNKNOWN) ? playerTeam : bc.team;
            core.addBed(new BedPOI(
                    bedTeam,
                    bc.head.getX(), bc.head.getY(), bc.head.getZ(),
                    bc.foot.getX(), bc.foot.getY(), bc.foot.getZ(),
                    isHome,
                    false
            ));
        }

        // Index Shop NPCs and Hologram Generators from loaded entities (pairing overhead holograms onto NPC bodies)
        List<ShopNPC> shops = indexArenaEntities(core);

        for (BlockPos p : emeraldBlocks) {
            addUniqueGen(core, new GenPOI(GenType.EMERALD, p.getX(), p.getY(), p.getZ(), "Emerald Block"));
        }
        for (BlockPos p : diamondBlocks) {
            addUniqueGen(core, new GenPOI(GenType.DIAMOND, p.getX(), p.getY(), p.getZ(), "Diamond Block"));
        }
        for (BlockPos p : forgeBlocks) {
            boolean nearBed = false;
            for (BedCandidate bc : discoveredBeds) {
                if (p.distSqr(bc.head) <= 196.0) {
                    nearBed = true;
                    break;
                }
            }
            if (nearBed || p.distSqr(player.blockPosition()) <= 256.0) {
                addUniqueGen(core, new GenPOI(GenType.HOME_FORGE, p.getX(), p.getY(), p.getZ(), "Team Forge"));
            }
        }
        boolean hasHomeForge = false;
        for (GenPOI g : core.getGens()) {
            if (g.type() == GenType.HOME_FORGE) {
                hasHomeForge = true;
                break;
            }
        }
        Integer maxDiscoveredBedY = null;
        if (!discoveredBeds.isEmpty()) {
            int max = Integer.MIN_VALUE;
            for (BedCandidate bc : discoveredBeds) {
                max = Math.max(max, bc.head.getY());
            }
            maxDiscoveredBedY = max;
        }
        boolean inWaitingLobbyNow = refreshWaitingLobbyState(player, maxDiscoveredBedY);

        if (!hasHomeForge) {
            BlockPos playerPos = player.blockPosition();
            for (Entity e : (Iterable<Entity>) ctx.entitiesStream()::iterator) {
                if (e instanceof ItemEntity ie && ie.isAlive()
                        && (ie.getItem().is(Items.IRON_INGOT) || ie.getItem().is(Items.GOLD_INGOT))) {
                    BlockPos ip = ie.blockPosition();
                    boolean nearSpawn = ip.distSqr(playerPos) <= 784.0;
                    boolean nearHome = closestHome != null && ip.distSqr(closestHome.head) <= 484.0;
                    if (nearSpawn || nearHome) {
                        addUniqueGen(core, new GenPOI(GenType.HOME_FORGE, ip.getX(), ip.getY(), ip.getZ(), "Team Forge (Ingot)"));
                        hasHomeForge = true;
                        break;
                    }
                }
            }
            if (!hasHomeForge && !inWaitingLobbyNow
                    && ((closestHome != null && closestHomeDistSq <= 900.0) || !shops.isEmpty() || seenBedwarsScoreboardSidebar)) {
                BlockPos fallbackForge = resolveBehindSpawnForgeFallback(playerPos);
                addUniqueGen(core, new GenPOI(GenType.HOME_FORGE, fallbackForge.getX(), fallbackForge.getY(), fallbackForge.getZ(), "Team Forge (Home Spawn)"));
            }
        }

        // Record player spawn as active home spawn POI + inferred mirrored spawns (never inside a waiting cage)
        BlockPos feet = player.blockPosition();
        if (!inWaitingLobbyNow) {
            core.addSpawn(new SpawnPOI(playerTeam, feet.getX(), feet.getY(), feet.getZ()));
        }
        for (BedPOI b : core.getBeds()) {
            if (!b.isHome()) {
                core.addSpawn(new SpawnPOI(b.team(), b.x(), b.y() + 1, b.z()));
            }
        }

        // Populate OriginalMapBitSet from already-fetched chunk sections
        for (LevelChunk chunk : loadedChunks) {
            int cx = chunk.getPos().x();
            int cz = chunk.getPos().z();
            core.markChunkScanned(cx, cz);
            LevelChunkSection[] sections = chunk.getSections();
            int baseX = cx << 4;
            int baseZ = cz << 4;
            for (int secIdx = 0; secIdx < sections.length; secIdx++) {
                LevelChunkSection sec = sections[secIdx];
                if (sec == null || sec.hasOnlyAir()) {
                    continue;
                }
                int secBaseY = dimMinY + (secIdx << 4);
                for (int ly = 0; ly < 16; ly++) {
                    int wy = secBaseY + ly;
                    if (wy < bounds.minY() || wy >= bounds.minY() + bounds.sizeY()) {
                        continue;
                    }
                    for (int lz = 0; lz < 16; lz++) {
                        int wz = baseZ + lz;
                        for (int lx = 0; lx < 16; lx++) {
                            int wx = baseX + lx;
                            if (!bounds.contains(wx, wy, wz)) {
                                continue;
                            }
                            BlockState st = sec.getBlockState(lx, ly, lz);
                            Block block = st.getBlock();
                            if (block instanceof AirBlock || block instanceof BedBlock || block == Blocks.BARRIER || !st.getFluidState().isEmpty()) {
                                continue;
                            }
                            // Treat existing wool/glass/slime closely wrapping an already-defended bed (Edge Case B) as breakable player blocks
                            if (isLateScanBedDefenseShell(block, wx, wy, wz, core)) {
                                core.setPlayerPlaced(wx, wy, wz, true);
                                continue;
                            }
                            boolean nearShop = false;
                            for (ShopNPC s : shops) {
                                int ddx = wx - s.x();
                                int ddz = wz - s.z();
                                if (ddx * ddx + ddz * ddz <= 9 && Math.abs(wy - s.y()) <= 3) {
                                    nearShop = true;
                                    break;
                                }
                            }
                            core.setOriginalBlock(wx, wy, wz, true, nearShop);
                        }
                    }
                }
            }
        }

        long fp = core.computeAndStoreStructuralFingerprint();
        int mergedFromCache = 0;
        if (Baritone.settings().bedwarsMapCache.value) {
            Optional<LoadedCache> cached = BedwarsMapCacheIO.findMatching(getCacheDir(), currentServerId, currentMapName, fp);
            if (cached.isPresent()) {
                mergedFromCache = core.mergeFromCache(cached.get().core());
                if ("unknown".equals(currentMapName) && cached.get().mapName() != null && !cached.get().mapName().isBlank()) {
                    currentMapName = cached.get().mapName();
                }
            }
        }

        int mirrored = core.applySymmetryReconstruction(SymmetryMode.FOUR_WAY_ROTATIONAL);
        core.computeAndStoreStructuralFingerprint();

        this.activeCore = core;
        this.snapshotCapturedForMatch = !inWaitingLobbyNow;

        if (!inWaitingLobbyNow && Baritone.settings().bedwarsMapCache.value) {
            try {
                BedwarsMapCacheIO.save(getCacheDir(), currentServerId, currentMapName, core);
            } catch (IOException ignored) {
            }
        }

        double elapsedMs = (System.nanoTime() - startNs) / 1_000_000.0;
        return String.format(
                Locale.ROOT,
                "Scanned Bedwars arena '%s' in %.2f ms (beds=%d, gens=%d, cachedBlocks=%d, mirrored=%d, voidY=%d, maxY=%d%s)",
                currentMapName, elapsedMs, core.getBeds().size(), core.getGens().size(), mergedFromCache, mirrored, voidFloorY, mapMaxBuildY,
                inWaitingLobbyNow ? ", waitingLobby=true" : ""
        );
    }

    /**
     * Collects living enemy player coordinates within {@code maxRange} blocks into flat {@code [x,y,z,...]}
     * array for Edge Case D exposed-bridge threat avoidance.
     */
    public int collectNearbyEnemyCoords(double maxRange, double[] outXYZ) {
        LocalPlayer self = ctx.player();
        if (self == null || ctx.world() == null || outXYZ == null || outXYZ.length < 3) {
            return 0;
        }
        int maxCount = outXYZ.length / 3;
        int count = 0;
        double maxDistSq = maxRange * maxRange;
        for (Entity e : (Iterable<Entity>) ctx.entitiesStream()::iterator) {
            if (!(e instanceof Player other) || other == self || !other.isAlive() || other.isSpectator()) {
                continue;
            }
            if (self.isAlliedTo(other)) {
                continue;
            }
            if (self.distanceToSqr(other) > maxDistSq) {
                continue;
            }
            int idx = count * 3;
            outXYZ[idx] = other.getX();
            outXYZ[idx + 1] = other.getY();
            outXYZ[idx + 2] = other.getZ();
            count++;
            if (count >= maxCount) {
                break;
            }
        }
        return count;
    }

    @Override
    public void onTick(TickEvent event) {
        if (event.getType() != TickEvent.Type.IN) {
            return;
        }
        BwsRuleset settingRuleset = getActiveRuleset();
        if (settingRuleset != this.activeRuleset) {
            applyRuleset(settingRuleset);
        }

        if (ctx.world() != lastObservedWorld) {
            lastObservedWorld = ctx.world();
            if (activeCore != null && !activeCore.getBeds().isEmpty()) {
                preservedRespawnCore = activeCore;
                preservedMapName = currentMapName;
                preservedAtWallMs = System.currentTimeMillis();
            }
            activeCore = null;
            snapshotCapturedForMatch = false;
            chatBuildHeightLimit = Integer.MIN_VALUE;
            currentMapName = "unknown";
            seenBedwarsScoreboardSidebar = false;
            scoreboardSuggestsWaiting = false;
            recentStartCountdownChat = false;
            gameStartedChatSeen = false;
            waitingLobbyDetected = false;
            pendingDelayedScan = false;
            delayedScanCountdown = 0;
            pendingBlockUpdates.clear();
            pendingServerTeleports.clear();
        }

        drainPendingChatMessages();
        drainPendingBlockUpdates();
        drainPendingLagbacks();

        if (!isBedwarsActive() || ctx.world() == null || ctx.player() == null) {
            if (ctx.player() != null && ctx.world() != null) {
                refreshWaitingLobbyState(ctx.player(), null);
            }
            return;
        }
        LocalPlayer me = ctx.player();
        if (!me.isAlive() || me.isDeadOrDying() || me.isSpectator() || ctx.world().dimension() == Level.NETHER) {
            return;
        }
        boolean waitingNow = refreshWaitingLobbyState(me, null);
        if (waitingNow) {
            return;
        }

        if (pendingDelayedScan) {
            if (delayedScanCountdown-- <= 0) {
                pendingDelayedScan = false;
                captureSnapshotT0();
            }
        } else if (Baritone.settings().bedwarsAutoScan.value && !snapshotCapturedForMatch) {
            // Edge Case B: auto-recover snapshot if user enables #bws mid-match or enters loaded arena late
            if (++incrementalScanTicker >= 25) {
                incrementalScanTicker = 0;
                if (hasNearbyBedsOrBedwarsScoreboard()) {
                    captureSnapshotT0();
                }
            }
        } else if (activeCore != null && (++incrementalScanTicker >= 20)) {
            incrementalScanTicker = 0;
            scanNewlyLoadedChunksIntoCore(activeCore);
            indexArenaEntities(activeCore);
        }
    }

    /**
     * Indexes diamond/emerald hologram stands and pairs overhead ArmorStand holograms
     * ({@code ITEM SHOP} vs {@code TEAM/SOLO UPGRADES}) onto the underlying NPC entities.
     */
    private List<ShopNPC> indexArenaEntities(BedwarsMapCore core) {
        if (core == null || ctx.world() == null) {
            return List.of();
        }
        record StandTag(double x, double y, double z, String text, String lower, boolean paired) {}
        List<StandTag> stands = new ArrayList<>();
        List<Entity> npcBodies = new ArrayList<>();

        for (Entity e : (Iterable<Entity>) ctx.entitiesStream()::iterator) {
            if (e == null || e instanceof Player || !e.isAlive()) {
                continue;
            }
            String typePath = BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).getPath();
            String label = stripColors(e.getName() != null ? e.getName().getString() : "");
            if (label.isEmpty() && e.getCustomName() != null) {
                label = stripColors(e.getCustomName().getString());
            }
            String trimmed = label.trim();
            String lower = trimmed.toLowerCase(Locale.ROOT);
            int ex = e.blockPosition().getX();
            int ey = e.blockPosition().getY();
            int ez = e.blockPosition().getZ();

            if ("armor_stand".equals(typePath)) {
                if (lower.contains("diamond") && (lower.contains("tier") || lower.contains("spawn") || lower.contains("second") || lower.contains("gen") || lower.contains("drop"))) {
                    addUniqueGen(core, new GenPOI(GenType.DIAMOND, ex, ey - 1, ez, trimmed));
                } else if (lower.contains("emerald") && (lower.contains("tier") || lower.contains("spawn") || lower.contains("second") || lower.contains("gen") || lower.contains("drop"))) {
                    addUniqueGen(core, new GenPOI(GenType.EMERALD, ex, ey - 1, ez, trimmed));
                } else if (!trimmed.isEmpty() && !lower.equals("armor stand")) {
                    stands.add(new StandTag(e.getX(), e.getY(), e.getZ(), trimmed, lower, false));
                }
            } else if ("villager".equals(typePath) || "wandering_trader".equals(typePath)
                    || "blaze".equals(typePath) || "witch".equals(typePath) || "pillager".equals(typePath)) {
                npcBodies.add(e);
            }
        }
        upgradeHomeForgeFromIngotDrops(core);

        List<ShopNPC> pairedShops = new ArrayList<>();
        boolean[] usedStand = new boolean[stands.size()];
        for (Entity npc : npcBodies) {
            double bx = npc.getX();
            double by = npc.getY();
            double bz = npc.getZ();
            String typePath = BuiltInRegistries.ENTITY_TYPE.getKey(npc.getType()).getPath();
            String selfLabel = stripColors(npc.getCustomName() != null
                    ? npc.getCustomName().getString()
                    : (npc.getName() != null ? npc.getName().getString() : ""));

            StringBuilder combined = new StringBuilder();
            if (!selfLabel.isBlank() && !selfLabel.equalsIgnoreCase(typePath)) {
                combined.append(selfLabel).append(' ');
            }
            String bestLine = selfLabel;
            for (int i = 0; i < stands.size(); i++) {
                StandTag st = stands.get(i);
                double dx = st.x - bx;
                double dz = st.z - bz;
                double dy = st.y - by;
                if (dx * dx + dz * dz <= 5.0625 && dy >= -0.6 && dy <= 3.8) {
                    usedStand[i] = true;
                    // Skip uninformative prompt lines unless nothing else is available
                    boolean clickPrompt = st.lower.equals("right click") || st.lower.equals("left click") || st.lower.contains("click to");
                    combined.append(st.text).append(' ');
                    if (!clickPrompt || bestLine.isBlank()) {
                        if (st.lower.contains("upgrade") || st.lower.contains("shop") || bestLine.isBlank()) {
                            bestLine = st.text;
                        }
                    }
                }
            }
            String role = BedwarsMapCore.classifyShopNpcRole(combined.toString());
            String finalLabel = !"UNKNOWN".equals(role)
                    ? role + ":" + (bestLine.isBlank() ? role : bestLine)
                    : (bestLine.isBlank() ? typePath : bestLine);
            ShopNPC poi = new ShopNPC(finalLabel, npc.blockPosition().getX(), npc.blockPosition().getY(), npc.blockPosition().getZ());
            pairedShops.add(poi);
            core.addOrUpdateShopNpc(poi);
        }

        // Handle hologram-only or Player-NPC shopkeepers without a Villager entity body
        for (int i = 0; i < stands.size(); i++) {
            if (usedStand[i]) {
                continue;
            }
            StandTag st = stands.get(i);
            String role = BedwarsMapCore.classifyShopNpcRole(st.text);
            if (!"UNKNOWN".equals(role)) {
                String finalLabel = role + ":" + st.text;
                int sx = (int) Math.floor(st.x);
                int sy = (int) Math.floor(st.y) - 1;
                int sz = (int) Math.floor(st.z);
                ShopNPC poi = new ShopNPC(finalLabel, sx, sy, sz);
                pairedShops.add(poi);
                core.addOrUpdateShopNpc(poi);
            }
        }
        return pairedShops;
    }

    @Override
    public void onBlockChange(BlockChangeEvent event) {
        if (!isBedwarsActive() || activeCore == null) {
            return;
        }
        for (Pair<BlockPos, BlockState> pair : event.getBlocks()) {
            applyLiveBlockUpdate(activeCore, pair.first(), pair.second());
        }
    }

    @Override
    public void onWorldEvent(WorldEvent event) {
        if (event.getWorld() == null) {
            if (activeCore != null && !activeCore.getBeds().isEmpty()) {
                preservedRespawnCore = activeCore;
                preservedMapName = currentMapName;
                preservedAtWallMs = System.currentTimeMillis();
            }
            activeCore = null;
            snapshotCapturedForMatch = false;
            seenBedwarsScoreboardSidebar = false;
            scoreboardSuggestsWaiting = false;
            recentStartCountdownChat = false;
            gameStartedChatSeen = false;
            waitingLobbyDetected = false;
            pendingBlockUpdates.clear();
            pendingServerTeleports.clear();
            lagbackCooldownUntilTick = -1L;
            lagbackSuspectedBlock = null;
        }
    }

    @Override
    public void onReceivePacket(PacketEvent event) {
        if (event.getState() != EventState.POST) {
            return;
        }
        Packet<?> pkt = event.getPacket();
        if (pkt instanceof ClientboundSystemChatPacket chat) {
            Component comp = chat.content();
            if (comp != null) {
                pendingChatLines.add(stripColors(comp.getString()));
            }
        } else if (pkt instanceof ClientboundOpenScreenPacket openPkt) {
            this.lastOpenContainerId = openPkt.getContainerId();
            this.lastOpenContainerTitle = openPkt.getTitle() != null ? stripColors(openPkt.getTitle().getString()) : "";
        } else if (pkt instanceof ClientboundBlockUpdatePacket blk && isBedwarsActive()) {
            pendingBlockUpdates.add(new BlockUpdateItem(blk.getPos(), blk.getBlockState()));
        } else if (pkt instanceof ClientboundPlayerPositionPacket posPkt && isBedwarsActive()) {
            pendingServerTeleports.add(posPkt.change().position());
            if (baritone.getBedwarsBlackboxRecorder() != null) {
                Vec3 tp = posPkt.change().position();
                baritone.getBedwarsBlackboxRecorder().recordEvent("SERVER_TELEPORT",
                        String.format(Locale.ROOT, "pos=(%.2f,%.2f,%.2f)", tp.x, tp.y, tp.z));
            }
        }
    }

    private void drainPendingChatMessages() {
        String msg;
        while ((msg = pendingChatLines.poll()) != null) {
            if (msg.isBlank()) {
                continue;
            }
            if (baritone.getBedwarsBlackboxRecorder() != null && isBedwarsActive()) {
                baritone.getBedwarsBlackboxRecorder().recordEvent("CHAT", msg);
            }
            Matcher mapMatcher = MAP_LINE_PATTERN.matcher(msg);
            if (mapMatcher.find()) {
                currentMapName = mapMatcher.group(1).trim();
            } else {
                Matcher playMatcher = PLAYING_ON_PATTERN.matcher(msg);
                if (playMatcher.find()) {
                    currentMapName = playMatcher.group(1).trim();
                }
            }
            Matcher heightMatcher = MAX_HEIGHT_PATTERN.matcher(msg);
            if (heightMatcher.find()) {
                try {
                    int limitY = Integer.parseInt(heightMatcher.group(1));
                    chatBuildHeightLimit = limitY - 1;
                    if (activeCore != null) {
                        activeCore.setMapMaxBuildY(chatBuildHeightLimit);
                    }
                } catch (NumberFormatException ignored) {
                }
            }
            String lower = msg.toLowerCase(Locale.ROOT);
            boolean isCountdownMsg = lower.contains("the game will start in")
                    || lower.contains("game starts in")
                    || lower.contains("not enough players")
                    || (lower.contains("has joined (") && lower.contains("/"));
            boolean isStartMsg = lower.contains("protect your bed")
                    || lower.contains("destroy the enemy beds")
                    || lower.contains("the game has started")
                    || lower.contains("bedwars has started");
            if (isCountdownMsg) {
                recentStartCountdownChat = true;
                gameStartedChatSeen = false;
            }
            if (isStartMsg) {
                gameStartedChatSeen = true;
                recentStartCountdownChat = false;
                waitingLobbyDetected = false;
                if (isBedwarsActive()) {
                    preservedRespawnCore = null;
                    snapshotCapturedForMatch = false;
                    pendingDelayedScan = true;
                    delayedScanCountdown = 4;
                }
            }
            if (activeCore != null && (lower.contains("bed destruction") || lower.contains("bed was destroyed") || lower.contains("your bed was destroyed"))) {
                TeamColor deadTeam = TeamColor.fromName(lower);
                if (lower.contains("your bed")) {
                    activeCore.markTeamBedDestroyed(activeCore.homeTeam());
                } else if (deadTeam != TeamColor.UNKNOWN) {
                    activeCore.markTeamBedDestroyed(deadTeam);
                }
            }
        }
    }

    private void drainPendingBlockUpdates() {
        BedwarsMapCore core = activeCore;
        if (core == null) {
            pendingBlockUpdates.clear();
            return;
        }
        BlockUpdateItem item;
        while ((item = pendingBlockUpdates.poll()) != null) {
            applyLiveBlockUpdate(core, item.pos(), item.state());
        }
    }

    private void drainPendingLagbacks() {
        Vec3 serverPos;
        LocalPlayer p = ctx.player();
        Level world = ctx.world();
        if (p == null || world == null) {
            pendingServerTeleports.clear();
            return;
        }
        while ((serverPos = pendingServerTeleports.poll()) != null) {
            double dist = p.position().distanceTo(serverPos);
            // Edge Case E: close-range rubberband reset during active bridging/moving flags ghost block & applies brief S-tap + clutch window
            if (dist >= 0.45 && dist <= 14.0 && !pendingDelayedScan) {
                lagbackCooldownUntilTick = world.getGameTime() + 10L;
                BlockPos below = BlockPos.containing(serverPos.x, serverPos.y - 0.5, serverPos.z);
                lagbackSuspectedBlock = below;
                if (activeCore != null && activeCore.isPlayerPlacedBlock(below.getX(), below.getY(), below.getZ())) {
                    activeCore.setPlayerPlaced(below.getX(), below.getY(), below.getZ(), false);
                }
            }
        }
    }

    private static void applyLiveBlockUpdate(BedwarsMapCore core, BlockPos pos, BlockState state) {
        int x = pos.getX();
        int y = pos.getY();
        int z = pos.getZ();
        Block b = state.getBlock();
        boolean isAir = b instanceof AirBlock || !state.getFluidState().isEmpty();
        if (isAir) {
            core.setPlayerPlaced(x, y, z, false);
            core.markBedDestroyedAt(x, y, z);
            return;
        }
        if (!(b instanceof BedBlock) && !core.isOriginalMapBlock(x, y, z)) {
            core.setPlayerPlaced(x, y, z, true);
        }
    }

    private void scanNewlyLoadedChunksIntoCore(BedwarsMapCore core) {
        if (ctx.world() == null || ctx.player() == null) {
            return;
        }
        ClientChunkCache chunkSource = (ClientChunkCache) ctx.world().getChunkSource();
        ArenaBounds bounds = core.bounds();
        int minChunkX = bounds.minX() >> 4;
        int maxChunkX = (bounds.minX() + bounds.sizeX() - 1) >> 4;
        int minChunkZ = bounds.minZ() >> 4;
        int maxChunkZ = (bounds.minZ() + bounds.sizeZ() - 1) >> 4;
        int dimMinY = ctx.world().dimensionType().minY();
        int scannedNew = 0;

        for (int cx = minChunkX; cx <= maxChunkX && scannedNew < 8; cx++) {
            for (int cz = minChunkZ; cz <= maxChunkZ && scannedNew < 8; cz++) {
                if (core.isChunkScanned(cx, cz)) {
                    continue;
                }
                LevelChunk chunk = chunkSource.getChunk(cx, cz, ChunkStatus.FULL, false);
                if (chunk == null || chunk.isEmpty()) {
                    continue;
                }
                scannedNew++;
                core.markChunkScanned(cx, cz);
                LevelChunkSection[] sections = chunk.getSections();
                int baseX = cx << 4;
                int baseZ = cz << 4;
                for (int secIdx = 0; secIdx < sections.length; secIdx++) {
                    LevelChunkSection sec = sections[secIdx];
                    if (sec == null || sec.hasOnlyAir()) {
                        continue;
                    }
                    int secBaseY = dimMinY + (secIdx << 4);
                    for (int ly = 0; ly < 16; ly++) {
                        int wy = secBaseY + ly;
                        if (wy < bounds.minY() || wy >= bounds.minY() + bounds.sizeY()) {
                            continue;
                        }
                        for (int lz = 0; lz < 16; lz++) {
                            int wz = baseZ + lz;
                            for (int lx = 0; lx < 16; lx++) {
                                int wx = baseX + lx;
                                if (!bounds.contains(wx, wy, wz)) {
                                    continue;
                                }
                                BlockState st = sec.getBlockState(lx, ly, lz);
                                Block block = st.getBlock();
                                if (block instanceof BedBlock && st.getValue(BedBlock.PART) == BedPart.HEAD) {
                                    BlockPos head = new BlockPos(wx, wy, wz);
                                    BlockPos foot = head.relative(st.getValue(BedBlock.FACING).getOpposite());
                                    if (!hasBedRecordedAt(core, head)) {
                                        TeamColor tc = inferBedTeamColor(chunk, dimMinY, wx, wy, wz, block);
                                        boolean isHomeBed = core.homeTeam() != TeamColor.UNKNOWN && tc == core.homeTeam();
                                        core.addBed(new BedPOI(tc, head.getX(), head.getY(), head.getZ(), foot.getX(), foot.getY(), foot.getZ(), isHomeBed, false));
                                    }
                                    continue;
                                }
                                if (block instanceof AirBlock || block == Blocks.BARRIER || !st.getFluidState().isEmpty()) {
                                    continue;
                                }
                                core.setOriginalBlock(wx, wy, wz, true, false);
                            }
                        }
                    }
                }
            }
        }
    }

    private boolean hasNearbyBedsOrBedwarsScoreboard() {
        refreshMetadataFromScoreboard();
        if (seenBedwarsScoreboardSidebar || !"unknown".equals(currentMapName)) {
            return true;
        }
        LocalPlayer p = ctx.player();
        BlockPos feet = p.blockPosition();
        for (int dx = -24; dx <= 24; dx += 2) {
            for (int dy = -8; dy <= 8; dy++) {
                for (int dz = -24; dz <= 24; dz += 2) {
                    if (ctx.world().getBlockState(feet.offset(dx, dy, dz)).getBlock() instanceof BedBlock) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private void refreshMetadataFromScoreboard() {
        if (ctx.world() == null) {
            return;
        }
        Scoreboard sb = ctx.world().getScoreboard();
        Objective sidebar = sb.getDisplayObjective(DisplaySlot.SIDEBAR);
        if (sidebar == null) {
            return;
        }
        String title = stripColors(sidebar.getDisplayName() != null ? sidebar.getDisplayName().getString() : "")
                .toLowerCase(Locale.ROOT);
        if (title.contains("bed")) {
            seenBedwarsScoreboardSidebar = true;
        }
        boolean waitingLine = false;
        for (PlayerScoreEntry entry : sb.listPlayerScores(sidebar)) {
            PlayerTeam team = sb.getPlayersTeam(entry.owner());
            String line = stripColors(PlayerTeam.formatNameForTeam(team, entry.ownerName()).getString());
            String lowerLine = line.toLowerCase(Locale.ROOT);
            if (lowerLine.contains("waiting")
                    || lowerLine.contains("starting in")
                    || lowerLine.contains("starts in")
                    || lowerLine.contains("will start")
                    || (lowerLine.contains("players:") && lowerLine.contains("/"))) {
                waitingLine = true;
            }
            Matcher m = MAP_LINE_PATTERN.matcher(line);
            if (m.find()) {
                currentMapName = m.group(1).trim();
            }
        }
        scoreboardSuggestsWaiting = waitingLine;
    }

    private TeamColor resolvePlayerTeamColor() {
        LocalPlayer p = ctx.player();
        if (p == null || ctx.world() == null) {
            return TeamColor.UNKNOWN;
        }
        PlayerTeam team = ctx.world().getScoreboard().getPlayersTeam(p.getScoreboardName());
        if (team != null) {
            TeamColor tc = TeamColor.fromName(stripColors(team.getPlayerPrefix().getString()) + " " + team.getName());
            if (tc != TeamColor.UNKNOWN) {
                return tc;
            }
        }
        return TeamColor.UNKNOWN;
    }

    private String resolveServerId() {
        Minecraft mc = ctx.minecraft();
        if (mc == null || mc.hasSingleplayerServer()) {
            return "singleplayer";
        }
        ServerData data = mc.getCurrentServer();
        return (data != null && data.ip != null && !data.ip.isBlank()) ? data.ip : "multiplayer";
    }

    private static TeamColor inferBedTeamColor(LevelChunk chunk, int dimMinY, int bx, int by, int bz, Block bedBlock) {
        Map<TeamColor, Integer> votes = new EnumMap<>(TeamColor.class);
        boolean hasWoolVote = false;
        boolean hasGrayWoolVote = false;
        LevelChunkSection[] sections = chunk.getSections();
        int chunkMinX = chunk.getPos().x() << 4;
        int chunkMinZ = chunk.getPos().z() << 4;
        for (int dx = -4; dx <= 4; dx++) {
            int wx = bx + dx;
            if ((wx >> 4) != chunk.getPos().x()) continue;
            for (int dy = -2; dy <= 2; dy++) {
                int wy = by + dy;
                int secIdx = (wy - dimMinY) >> 4;
                if (secIdx < 0 || secIdx >= sections.length) continue;
                LevelChunkSection sec = sections[secIdx];
                if (sec == null || sec.hasOnlyAir()) continue;
                for (int dz = -4; dz <= 4; dz++) {
                    int wz = bz + dz;
                    if ((wz >> 4) != chunk.getPos().z()) continue;
                    Block b = sec.getBlockState(wx - chunkMinX, (wy - dimMinY) & 15, wz - chunkMinZ).getBlock();
                    String path = BuiltInRegistries.BLOCK.getKey(b).getPath();
                    int weight = 0;
                    boolean wool = false;
                    if (path.endsWith("_wool")) {
                        weight = 5;
                        wool = true;
                    } else if (path.endsWith("_concrete") || path.endsWith("_banner")) {
                        weight = 3;
                    } else if (path.endsWith("_terracotta") || path.endsWith("_stained_glass")) {
                        weight = 1;
                    }
                    if (weight > 0) {
                        TeamColor c = TeamColor.fromName(path);
                        if (c != TeamColor.UNKNOWN) {
                            votes.merge(c, weight, Integer::sum);
                            if (wool) {
                                hasWoolVote = true;
                                if (c == TeamColor.GRAY) {
                                    hasGrayWoolVote = true;
                                }
                            }
                        }
                    }
                }
            }
        }
        TeamColor blockColor = TeamColor.fromName(BuiltInRegistries.BLOCK.getKey(bedBlock).getPath());
        return BedwarsMapCore.selectBedTeamFromVotes(votes, blockColor, hasWoolVote, hasGrayWoolVote);
    }

    private BedwarsMapCore tryRestorePreservedRespawnCore(LocalPlayer player) {
        BedwarsMapCore prev = preservedRespawnCore;
        if (prev == null || ctx.world() == null || player == null) {
            return null;
        }
        if (System.currentTimeMillis() - preservedAtWallMs > 25_000L) {
            preservedRespawnCore = null;
            return null;
        }
        BlockPos feet = player.blockPosition();
        if (!prev.bounds().contains(feet.getX(), feet.getY(), feet.getZ())) {
            return null;
        }
        boolean matchedEvidence = (!"unknown".equals(preservedMapName) && preservedMapName.equals(currentMapName))
                || seenBedwarsScoreboardSidebar;
        if (!matchedEvidence) {
            for (BedPOI b : prev.getBeds()) {
                BlockPos head = new BlockPos(b.x(), b.y(), b.z());
                if (ctx.world().isLoaded(head) && ctx.world().getBlockState(head).getBlock() instanceof BedBlock) {
                    matchedEvidence = true;
                    break;
                }
            }
        }
        if (!matchedEvidence) {
            return null;
        }
        preservedRespawnCore = null;
        this.activeCore = prev;
        this.snapshotCapturedForMatch = true;
        if ("unknown".equals(this.currentMapName) && !"unknown".equals(preservedMapName)) {
            this.currentMapName = preservedMapName;
        }
        scanNewlyLoadedChunksIntoCore(prev);
        indexArenaEntities(prev);
        return prev;
    }

    /**
     * Pure snapshot of an iron/gold ingot entity used to locate a team's home forge from clustered drops.
     */
    public record ForgeIngotDrop(double x, double y, double z, int blockX, int blockY, int blockZ, int count, int anchorDistSq) {}

    /**
     * Selects the strongest home-forge ingot cluster candidate from nearby drops so a single stray
     * ingot on a bridge never overrides {@code "Team Forge (Home Spawn)"}.
     */
    public static ForgeIngotDrop selectBestForgeIngotCandidate(List<ForgeIngotDrop> drops) {
        if (drops == null || drops.isEmpty()) {
            return null;
        }
        ForgeIngotDrop best = null;
        int bestScore = Integer.MIN_VALUE;
        for (ForgeIngotDrop d : drops) {
            int clusterItems = 0;
            for (ForgeIngotDrop other : drops) {
                double dx = other.x() - d.x();
                double dy = other.y() - d.y();
                double dz = other.z() - d.z();
                if (Math.abs(dy) <= 2.5 && (dx * dx + dy * dy + dz * dz) <= 4.84) {
                    clusterItems += Math.max(1, other.count());
                }
            }
            if (clusterItems < 2 && d.anchorDistSq() > 144) {
                continue;
            }
            int score = clusterItems * 1000 + Math.max(1, d.count()) * 100 - d.anchorDistSq();
            if (score > bestScore) {
                bestScore = score;
                best = d;
            }
        }
        return best;
    }

    private BlockPos resolveBehindSpawnForgeFallback(BlockPos playerPos) {
        LocalPlayer fp = ctx.player();
        if (fp == null || ctx.world() == null) {
            return playerPos;
        }
        double yawRad = Math.toRadians(fp.getYRot());
        double bx = Math.sin(yawRad);
        double bz = -Math.cos(yawRad);
        int dx = Math.abs(bx) >= Math.abs(bz) ? (bx >= 0.0 ? 1 : -1) : 0;
        int dz = dx == 0 ? (bz >= 0.0 ? 1 : -1) : 0;
        for (int dist : new int[]{3, 2, 4}) {
            BlockPos behind = playerPos.offset(dx * dist, 0, dz * dist);
            if (baritone.pathing.movement.MovementHelper.canWalkOn(ctx, behind.below())
                    && baritone.pathing.movement.MovementHelper.fullyPassable(ctx, behind)) {
                return behind;
            }
        }
        return playerPos;
    }

    private void upgradeHomeForgeFromIngotDrops(BedwarsMapCore core) {
        if (core == null || ctx.world() == null) {
            return;
        }
        List<ForgeIngotDrop> drops = new java.util.ArrayList<>();
        for (var e : ctx.entities()) {
            if (e instanceof ItemEntity ie
                    && (ie.getItem().is(Items.IRON_INGOT) || ie.getItem().is(Items.GOLD_INGOT))) {
                double ix = ie.getX();
                double iy = ie.getY();
                double iz = ie.getZ();
                int bx = (int) Math.floor(ix);
                int by = (int) Math.floor(iy);
                int bz = (int) Math.floor(iz);
                int anchorDistSq = minHomeAnchorDistSq(core, bx, by, bz);
                if (anchorDistSq <= 225) {
                    drops.add(new ForgeIngotDrop(ix, iy, iz, bx, by, bz, ie.getItem().getCount(), anchorDistSq));
                }
            }
        }
        ForgeIngotDrop best = selectBestForgeIngotCandidate(drops);
        if (best != null) {
            core.addOrUpgradeHomeForge(new GenPOI(GenType.HOME_FORGE, best.blockX(), best.blockY(), best.blockZ(), "Team Forge (Ingot)"));
        }
    }

    private int minHomeAnchorDistSq(BedwarsMapCore core, int x, int y, int z) {
        int bestSq = Integer.MAX_VALUE;
        for (BedPOI b : core.getBeds()) {
            if (b.isHome()) {
                int dx = x - b.x();
                int dy = y - b.y();
                int dz = z - b.z();
                bestSq = Math.min(bestSq, dx * dx + dy * dy + dz * dz);
            }
        }
        if (bestSq < Integer.MAX_VALUE) {
            return bestSq;
        }
        if (ctx.player() != null) {
            BlockPos p = ctx.player().blockPosition();
            if (Math.abs(y - p.getY()) <= 8) {
                int dx = x - p.getX();
                int dz = z - p.getZ();
                bestSq = dx * dx + dz * dz;
            }
        }
        return bestSq;
    }

    private boolean isNearHomeAnchor(BedwarsMapCore core, int x, int y, int z, int radius) {
        return minHomeAnchorDistSq(core, x, y, z) <= radius * radius;
    }

    private static boolean isLateScanBedDefenseShell(Block block, int x, int y, int z, BedwarsMapCore core) {
        DefenseBlockKind kind = classifyDefenseBlock(block);
        if (kind == DefenseBlockKind.AIR || kind == DefenseBlockKind.ORIGINAL_MAP) {
            return false;
        }
        for (BedPOI b : core.getBeds()) {
            int dx = x - b.x();
            int dy = y - b.y();
            int dz = z - b.z();
            if (dx * dx + dz * dz <= 16 && dy >= 0 && dy <= 4) {
                return true;
            }
        }
        return false;
    }

    public static DefenseBlockKind classifyDefenseBlock(Block block) {
        if (block instanceof AirBlock) {
            return DefenseBlockKind.AIR;
        }
        if (block == Blocks.WATER) {
            return DefenseBlockKind.WATER_COVERED;
        }
        if (block == Blocks.OBSIDIAN) {
            return DefenseBlockKind.OBSIDIAN;
        }
        if (block == Blocks.END_STONE) {
            return DefenseBlockKind.ENDSTONE;
        }
        String path = BuiltInRegistries.BLOCK.getKey(block).getPath();
        if (path.endsWith("_wool")) {
            return DefenseBlockKind.WOOL;
        }
        if (path.endsWith("_planks") || path.endsWith("_log") || path.endsWith("_wood")) {
            return DefenseBlockKind.WOOD;
        }
        if (path.endsWith("terracotta")) {
            return DefenseBlockKind.TERRACOTTA;
        }
        if (path.contains("glass")) {
            return DefenseBlockKind.GLASS;
        }
        if (block == Blocks.LADDER || path.endsWith("_slab")) {
            return DefenseBlockKind.LADDER_SLAB;
        }
        return DefenseBlockKind.ORIGINAL_MAP;
    }

    private static boolean hasBedRecordedAt(BedwarsMapCore core, BlockPos head) {
        for (BedPOI b : core.getBeds()) {
            if (b.matchesCell(head.getX(), head.getY(), head.getZ())) {
                return true;
            }
        }
        return false;
    }

    private static void addUniqueGen(BedwarsMapCore core, GenPOI candidate) {
        for (GenPOI existing : core.getGens()) {
            if (existing.type() == candidate.type()
                    && Math.abs(existing.x() - candidate.x()) <= 2
                    && Math.abs(existing.y() - candidate.y()) <= 4
                    && Math.abs(existing.z() - candidate.z()) <= 2) {
                return;
            }
        }
        core.addGen(candidate);
    }

    private static double snapHalfCoord(double v) {
        return Math.round(v * 2.0) / 2.0;
    }

    public static String stripColors(String raw) {
        return raw == null ? "" : COLOR_CODE_PATTERN.matcher(raw).replaceAll("").trim();
    }

    public List<CachedMapEntry> listCacheEntries() {
        return BedwarsMapCacheIO.listEntries(getCacheDir());
    }

    public int clearCache() throws IOException {
        return BedwarsMapCacheIO.clearAll(getCacheDir());
    }

    public String saveCurrentToCache(String optionalMapName) throws IOException {
        if (activeCore == null) {
            captureSnapshotT0();
        }
        if (activeCore == null) {
            return "No active arena to save.";
        }
        if (optionalMapName != null && !optionalMapName.isBlank()) {
            this.currentMapName = optionalMapName.trim();
        }
        Path out = BedwarsMapCacheIO.save(getCacheDir(), resolveServerId(), currentMapName, activeCore);
        return "Saved .bwsmap cache: " + out.getFileName() + " (" + out.toFile().length() + " bytes)";
    }

    public String loadCacheByName(String mapName) throws IOException {
        if (mapName == null || mapName.isBlank()) {
            return "Specify map name to load.";
        }
        Optional<LoadedCache> loaded = BedwarsMapCacheIO.findMatching(getCacheDir(), resolveServerId(), mapName.trim(), 0L);
        if (loaded.isEmpty()) {
            return "No cached .bwsmap matching '" + mapName + "'.";
        }
        LoadedCache lc = loaded.get();
        this.currentMapName = lc.mapName();
        if (this.activeCore == null) {
            this.activeCore = lc.core();
            this.snapshotCapturedForMatch = true;
            return "Loaded .bwsmap '" + lc.mapName() + "' (beds=" + lc.core().getBeds().size() + ")";
        }
        int merged = this.activeCore.mergeFromCache(lc.core());
        return "Merged " + merged + " cached blocks from '" + lc.mapName() + "'.";
    }

    private record BedCandidate(TeamColor team, BlockPos head, BlockPos foot) {}
}
