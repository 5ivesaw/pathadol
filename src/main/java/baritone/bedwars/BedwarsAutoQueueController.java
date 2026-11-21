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
import baritone.api.pathing.goals.GoalNear;
import baritone.api.process.PathingCommand;
import baritone.api.process.PathingCommandType;
import baritone.api.utils.IPlayerContext;
import baritone.api.utils.Rotation;
import baritone.api.utils.RotationUtils;
import baritone.api.utils.input.Input;
import baritone.bedwars.BedwarsMapCore.TeamColor;
import baritone.bedwars.BedwarsShopPlanner.ShopSlotInfo;
import baritone.human.CraftingHelper;
import baritone.process.CombatProcess;
import baritone.utils.accessor.IGuiScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.DeathScreen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.BookViewScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundCommandSuggestionsPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.npc.villager.AbstractVillager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.AbstractFurnaceMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.SwingAnimation;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.IntPredicate;
import java.util.regex.Pattern;

/**
 * Autonomous cross-server BedWars auto-requeue and lobby NPC queue controller.
 *
 * <p>Handles two unattended match-loop scenarios when {@code #bws auto} is active:
 * <ol>
 *   <li><b>In-game elimination / match end:</b> selects the hotbar "Play Again" item (e.g. Paper),
 *       right-clicks it, and clicks the Confirm / Play Again / Green slot in the resulting GUI.</li>
 *   <li><b>BedWars lobby return:</b> finds the matching queue NPC (e.g. Solo / Doubles / BedWars),
 *       walks within interact range, right-clicks it, and confirms any mode selector GUI.</li>
 * </ol>
 */
public final class BedwarsAutoQueueController {

    private static final Map<String, List<String>> QUEUE_MODES = Map.of(
            "solo", List.of("solo", "solos", "1v1", "1s"),
            "doubles", List.of("doubles", "double", "duo", "duos", "2v2", "2s"),
            "trios", List.of("trios", "trio", "3v3", "3s", "3v3v3v3"),
            "quads", List.of("quads", "quad", "4v4", "4s", "4v4v4v4")
    );
    private static final Pattern LABEL_WORD = Pattern.compile("[a-z0-9]+");

    public static List<String> queueModeAliases() {
        return QUEUE_MODES.values().stream().flatMap(List::stream).sorted().toList();
    }

    public static String normalizeQueueMode(String mode) {
        String alias = mode == null ? "" : mode.strip().toLowerCase(Locale.ROOT);
        return QUEUE_MODES.entrySet().stream().filter(entry -> entry.getValue().contains(alias))
                .map(Map.Entry::getKey).findFirst().orElse("solo");
    }

    static String explicitQueueMode(String text) {
        var words = LABEL_WORD.matcher(text.toLowerCase(Locale.ROOT));
        String mode = null;
        while (words.find()) {
            String word = words.group();
            for (var entry : QUEUE_MODES.entrySet()) {
                if (entry.getValue().contains(word)) {
                    if (mode != null && !mode.equals(entry.getKey())) {
                        return null;
                    }
                    mode = entry.getKey();
                }
            }
        }
        return mode;
    }

    private enum ModeMatch {
        REQUESTED, OTHER, UNSPECIFIED
    }

    private static ModeMatch matchQueueMode(String label, String normalizedMode) {
        var tokens = LABEL_WORD.matcher(label.toLowerCase(Locale.ROOT));
        boolean hasOtherMode = false;
        while (tokens.find()) {
            String token = tokens.group();
            if (QUEUE_MODES.get(normalizedMode).contains(token)) {
                return ModeMatch.REQUESTED;
            }
            for (List<String> aliases : QUEUE_MODES.values()) {
                hasOtherMode |= aliases.contains(token);
            }
        }
        return hasOtherMode ? ModeMatch.OTHER : ModeMatch.UNSPECIFIED;
    }

    private final BedwarsQueueCommands queueCommands = new BedwarsQueueCommands();
    private volatile boolean enabled = false;
    private boolean hubSelectorRequested;
    private boolean hubSelectorUsed;
    private String lastMenuClick;
    private int lastQueueNpcId = Integer.MIN_VALUE;
    private String preferredMode = "solo";
    private int idleRequeueTicks = 0;
    private int spectatorTicks = 0;
    private int actionCooldown = 0;
    private int scanRetryTicks = 0;
    private int ticksSinceWorldChange = 0;
    private Object lastWorld = null;
    private int npcClickAttempts = 0;
    private int selectorOpenAttempts = 0;
    private int menuNoMatchTicks = 0;
    private boolean lobbySpawnCaptured = false;
    private boolean wasInWaitingLobby = false;
    private double lobbySpawnX = 0.0;
    private double lobbySpawnY = 0.0;
    private double lobbySpawnZ = 0.0;
    private float lobbySpawnYaw = 0.0f;
    private int lobbySearchTicks = 0;
    private String lastAnnounced = "";
    private long lastAnnounceMs = 0L;
    private java.util.function.Consumer<String> chatSink = null;

    /** Registers the concise status sink (BrainProcess chat). Messages are de-duplicated and rate-limited. */
    public void setChatSink(java.util.function.Consumer<String> sink) {
        this.chatSink = sink;
    }

    /** Emits one short {@code [Auto]} status line, never repeating the same line back-to-back. */
    public boolean announce(String msg) {
        if (msg == null || msg.isBlank() || msg.equals(lastAnnounced)) {
            return msg != null && msg.equals(lastAnnounced);
        }
        long now = System.currentTimeMillis();
        if (now - lastAnnounceMs < 1200L) {
            return false;
        }
        lastAnnounced = msg;
        lastAnnounceMs = now;
        if (chatSink != null) {
            chatSink.accept("[Auto] " + msg);
        }
        return true;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public String getPreferredMode() {
        return preferredMode;
    }

    public void setEnabled(boolean enabled, String mode) {
        this.enabled = enabled;
        queueCommands.reset(null);
        rearmLobbyRoutes();
        if (mode != null && !mode.isBlank()) {
            this.preferredMode = normalizeQueueMode(mode);
        }
        this.idleRequeueTicks = 0;
        this.actionCooldown = 10;
        this.scanRetryTicks = 0;
        this.npcClickAttempts = 0;
        this.lastQueueNpcId = Integer.MIN_VALUE;
        this.lobbySpawnCaptured = false;
        this.wasInWaitingLobby = false;
        this.lobbySearchTicks = 0;
        this.lastAnnounced = "";
    }

    public void onQueueCommandSuggestions(Connection connection, ClientboundCommandSuggestionsPacket packet) {
        if (enabled) {
            queueCommands.onSuggestions(connection, packet);
        }
    }

    public void onQueueHelpMessage(Connection connection, String message) {
        if (enabled) {
            queueCommands.onHelp(connection, message);
        }
    }

    private void rearmLobbyRoutes() {
        queueCommands.rearm();
        hubSelectorRequested = false;
        hubSelectorUsed = false;
        selectorOpenAttempts = 0;
        lastMenuClick = null;
        menuNoMatchTicks = 0;
    }

    public void reset() {
        this.idleRequeueTicks = 0;
        this.actionCooldown = 0;
        this.scanRetryTicks = 0;
    }

    /**
     * Called every tick from {@link baritone.brain.BrainProcess} when autonomous mode is active.
     *
     * @return a {@link PathingCommand} if auto-requeue is actively driving right now, or {@code null}
     *         to let normal BedWars gameplay proceed.
     */
    public PathingCommand tick(Baritone baritone, IPlayerContext ctx) {
        if (!enabled || ctx == null || ctx.player() == null || ctx.world() == null) {
            return null;
        }
        if (actionCooldown > 0) {
            actionCooldown--;
        }
        LocalPlayer p = ctx.player();
        if (p.connection != null) {
            queueCommands.prepareConnection(p.connection.getConnection());
        }
        BedwarsArenaMemory memory = baritone.getBedwarsArenaMemory();
        BedwarsMatchState matchState = memory != null ? memory.getMatchState() : null;
        boolean inWaitingLobby = memory != null && memory.isInWaitingLobby();

        // World change bookkeeping (hub -> lobby -> cage -> arena): auto mode itself NEVER resets here.
        Object world = ctx.world();
        if (world != lastWorld) {
            lastWorld = world;
            ticksSinceWorldChange = 0;
            npcClickAttempts = 0;
            lastQueueNpcId = Integer.MIN_VALUE;
            hubSelectorRequested = false;
            lastMenuClick = null;
            scanRetryTicks = 0;
            lobbySpawnCaptured = false;
            wasInWaitingLobby = false;
            lobbySearchTicks = 0;
            actionCooldown = Math.max(actionCooldown, 10);
        } else {
            ticksSinceWorldChange++;
        }
        // Same-world waiting-cage -> arena drop: clear stale sky-cage spawn anchor & trigger fast T0 scan!
        if (wasInWaitingLobby && !inWaitingLobby) {
            lobbySpawnCaptured = false;
            lobbySearchTicks = 0;
            scanRetryTicks = 36;
        }
        wasInWaitingLobby = inWaitingLobby;
        if (!lobbySpawnCaptured && ticksSinceWorldChange >= 5) {
            lobbySpawnCaptured = true;
            lobbySpawnX = p.getX();
            lobbySpawnY = p.getY();
            lobbySpawnZ = p.getZ();
            lobbySpawnYaw = p.getYRot();
        }

        // Auto-dismiss death screens and server news/promo BookViewScreen popups so unattended #bws auto never stalls
        Minecraft mcScreenCheck = ctx.minecraft();
        if (mcScreenCheck != null && mcScreenCheck.gui != null) {
            if (mcScreenCheck.gui.screen() instanceof DeathScreen) {
                if (actionCooldown == 0) {
                    p.respawn();
                    actionCooldown = 20;
                }
                return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
            }
            if (mcScreenCheck.gui.screen() instanceof BookViewScreen) {
                if (actionCooldown == 0) {
                    mcScreenCheck.gui.setScreen(null);
                    actionCooldown = 12;
                }
                return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
            }
        }

        int playAgainSlot = findPlayAgainHotbarSlot(p);
        int returnToLobbySlot = findReturnToLobbyHotbarSlot(p);
        boolean matchEnded = matchState != null && matchState.isMatchEnded();

        // Lobby / hub detection is independent of (possibly stale) arena memory: a Server Selector /
        // Game Menu compass never exists in a live match inventory.
        boolean hasLobbyCompass = findGameMenuHotbarSlot(p) >= 0;
        BedwarsMapCore core = memory != null ? memory.getActiveCore() : null;
        boolean hasLivingArenaBed = false;
        boolean homeBedStillAlive = false;
        if (core != null) {
            for (BedwarsMapCore.BedPOI b : core.getBeds()) {
                if (!b.destroyed()) {
                    hasLivingArenaBed = true;
                    if (b.isHome()) {
                        homeBedStillAlive = true;
                    }
                }
            }
        }
        if (p.isSpectator()) {
            spectatorTicks++;
        } else {
            spectatorTicks = 0;
        }
        int respawnSec = matchState != null ? matchState.getRespawningSecondsRemaining() : 0;
        // Mid-game 5s death-respawn spectator (bed still alive, no Play Again / Lobby item, < 8s) is NOT post-game!
        boolean midGameRespawning = isMidGameRespawnSpectator(
                p.isSpectator(), matchEnded, playAgainSlot >= 0, returnToLobbySlot >= 0,
                homeBedStillAlive, respawnSec, spectatorTicks
        );
        if (midGameRespawning) {
            baritone.getInputOverrideHandler().clearAllKeys();
            idleRequeueTicks = 0;
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }
        boolean spectatorOrPostGame = p.isSpectator() || matchEnded || playAgainSlot >= 0;

        boolean matchStarted = matchState != null && matchState.isMatchStarted();
        boolean homeTeamKnown = matchState != null && matchState.getHomeTeam() != TeamColor.UNKNOWN;
        boolean anyTeamDiscovered = matchState != null && !matchState.getDiscoveredTeams().isEmpty();
        boolean inGameLoadout = hasInGameLoadout(p);
        boolean liveMatch = isLiveArenaMatch(
                inWaitingLobby, hasLobbyCompass, spectatorOrPostGame,
                hasLivingArenaBed, matchStarted, homeTeamKnown, anyTeamDiscovered, inGameLoadout
        );
        String sbTitle = matchState != null ? matchState.getLastScoreboardTitle() : "";
        boolean alreadyInBedwarsLobby = !spectatorOrPostGame && !liveMatch && !inWaitingLobby
                && (isBedwarsLobbyScoreboard(sbTitle) || hasBedwarsLobbyHologram(ctx));
        Entity lobbyNpc = (!spectatorOrPostGame && !liveMatch && !inWaitingLobby)
                ? findLobbyQueueNpc(ctx, p, preferredMode, queueCommands) : null;
        boolean inBedwarsLobby = !spectatorOrPostGame && !liveMatch && (lobbyNpc != null || hasLobbyCompass || alreadyInBedwarsLobby);

        // Only pause for a TRUE pre-game waiting cage — NEVER when eliminated/spectator (Play Again) or in a hub/queue lobby!
        if (inWaitingLobby && !spectatorOrPostGame && !inBedwarsLobby) {
            rearmLobbyRoutes();
            idleRequeueTicks = 0;
            announce("Pre-game lobby - waiting for match start");
            return null;
        }

        if (!spectatorOrPostGame && !inBedwarsLobby) {
            if (liveMatch) {
                rearmLobbyRoutes();
                lobbySearchTicks = 0;
            }
            idleRequeueTicks = 0;
            tickScanWatchdog(baritone, memory, matchState, p, liveMatch);
            return null;
        }

        // Stop active Bedwars/Combat tasks cleanly while requeueing
        if (baritone.getCombatProcess().isActive()) {
            baritone.getCombatProcess().cancel();
        }
        if (baritone.getBedwarsProcess().isActive()) {
            baritone.getBedwarsProcess().cancel();
        }

        // Step 1: If a server container GUI (Play Again confirm / Server Selector / mode selector) is open, click it!
        Minecraft mc = ctx.minecraft();
        AbstractContainerMenu menu = p.containerMenu;
        boolean containerOpen = mc != null && mc.gui != null && mc.gui.screen() instanceof AbstractContainerScreen<?>
                && menu != null && menu != p.inventoryMenu
                && !(menu instanceof CraftingMenu) && !(menu instanceof AbstractFurnaceMenu)
                && menu.slots.size() > 36;
        if (containerOpen) {
            String title = memory != null ? memory.getOpenMenuTitle(menu.containerId) : "";
            if ((title == null || title.isBlank()) && mc.gui.screen() instanceof IGuiScreen gs) {
                Component tc = gs.pathadol$getTitle();
                if (tc != null) {
                    title = BedwarsArenaMemory.stripColors(tc.getString());
                }
            }
            List<ShopSlotInfo> slots = readContainerSlots(menu);
            boolean allowSelector = hubSelectorRequested && !hubSelectorUsed && !alreadyInBedwarsLobby && lobbyNpc == null;
            int targetSlot = findAutoQueueConfirmSlot(title, slots, preferredMode, allowSelector);
            ShopSlotInfo offer = slots.stream().filter(slot -> slot.slotIndex() == targetSlot).findFirst().orElse(null);
            String clickKey = offer == null ? null : menu.containerId + ":" + offer.slotIndex() + ":" + offer.displayName();
            boolean newOffer = clickKey != null && !clickKey.equals(lastMenuClick);
            if (newOffer) {
                announce(title != null && title.toLowerCase(Locale.ROOT).contains("select")
                        ? "Server Selector -> BedWars" : "Confirming queue menu");
            }
            if (newOffer && actionCooldown == 0 && CraftingHelper.advanceAndReadyGui(ctx, targetSlot)) {
                ctx.playerController().windowClick(menu.containerId, targetSlot, 0, ContainerInput.PICKUP, p);
                lastMenuClick = clickKey;
                if (allowSelector && (isServerSelectorTitle(title)
                        || matchQueueMode(offer.displayName(), preferredMode) == ModeMatch.UNSPECIFIED)) {
                    hubSelectorRequested = false;
                    hubSelectorUsed = true;
                }
                actionCooldown = 30;
                idleRequeueTicks = 0;
            } else if (!newOffer && actionCooldown == 0
                    && ++menuNoMatchTicks > (isServerSelectorTitle(title) ? 10 : 60)) {
                // Unknown menu that has nothing we recognise: close it so the compass / NPC flow can retry.
                CraftingHelper.safeCloseContainer(ctx);
                menuNoMatchTicks = 0;
                actionCooldown = 20;
            }
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }
        menuNoMatchTicks = 0;
        lastMenuClick = null;

        // Human delay before clicking Play Again / lobby NPC (~2.5 seconds after elimination/end)
        if (++idleRequeueTicks < 50) {
            baritone.getInputOverrideHandler().clearAllKeys();
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }

        // Step 2: In-game Play Again hotbar item (Paper / "Play Again")
        if (playAgainSlot >= 0) {
            announce("Match over -> Play Again");
            return useHotbarItem(baritone, ctx, p, playAgainSlot, 30);
        }

        // Prefer a command whose destination was advertised by the current server.
        boolean insideBedwars = alreadyInBedwarsLobby || lobbyNpc != null;
        if (!spectatorOrPostGame && mc != null && mc.getCurrentServer() != null
                && mc.gui.screen() == null && actionCooldown == 0
                && queueCommands.tick(p.connection, preferredMode, insideBedwars, this::announce)) {
            baritone.getInputOverrideHandler().clearAllKeys();
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }

        // Step 3: Lobby NPC walk + right-click (e.g. Solo / Doubles / BedWars NPC)
        if (lobbyNpc != null) {
            lobbySearchTicks = 0;
            double dSq = p.distanceToSqr(lobbyNpc);
            // 7.8 sq (~2.8m) guarantees we are inside vanilla 3.0m entity interact raycast reach
            if (dSq <= 7.8) {
                announce("Joining " + preferredMode + " queue NPC");
                baritone.getInputOverrideHandler().clearMovementKeys();
                Vec3 aimVec = lobbyNpc.getBoundingBox().getCenter();
                Rotation aim = RotationUtils.calcRotationFromVec3d(ctx.playerHead(), aimVec, ctx.playerRotations());
                baritone.getLookBehavior().updateTarget(aim, true, true);
                ((baritone.behavior.LookBehavior) baritone.getLookBehavior()).applyClientPreTickRotationAndRepick();
                if (lastQueueNpcId != lobbyNpc.getId()) {
                    lastQueueNpcId = lobbyNpc.getId();
                    npcClickAttempts = 0;
                }
                if (actionCooldown == 0 && mc != null && mc.gameMode != null && mc.gui.screen() == null
                        && !mc.isPaused() && mc.hitResult instanceof EntityHitResult hit && hit.getEntity() == lobbyNpc) {
                    int emptySlot = findEmptyQueueHandSlot(p.getInventory().getSelectedSlot(),
                            slot -> p.getInventory().getItem(slot).isEmpty());
                    if (emptySlot < 0 || npcClickAttempts % 3 == 2) {
                        if (CombatProcess.clickAttackIfCrosshairMatches(ctx, lobbyNpc, 7.8)) {
                            npcClickAttempts++;
                            actionCooldown = 30;
                        }
                    } else if (p.getInventory().getSelectedSlot() != emptySlot) {
                        if (baritone.getInventoryBehavior().safeSelectHotbarSlot(emptySlot)) {
                            actionCooldown = 5;
                        }
                    } else {
                        // Target the NPC directly. Vanilla's use key can fall back to activating the held selector.
                        mc.gameMode.interact(p, lobbyNpc, hit, InteractionHand.MAIN_HAND);
                        p.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, false);
                        npcClickAttempts++;
                        actionCooldown = 30;
                    }
                }
                return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
            }
            announce("Walking to " + preferredMode + " queue NPC");
            BlockPos npcPos = lobbyNpc.blockPosition();
            return new PathingCommand(new GoalNear(npcPos, 1), PathingCommandType.SET_GOAL_AND_PATH);
        }

        // Step 3b: Already inside the BedWars lobby, but queue NPCs are outside entity tracking range (~32m)
        // from spawn — walk forward along spawn facing direction so the NPCs load in instead of re-clicking Server Selector!
        if (alreadyInBedwarsLobby && !spectatorOrPostGame) {
            lobbySearchTicks++;
            if (lobbySearchTicks <= 260) {
                announce("Searching BedWars lobby for " + preferredMode + " NPC");
                int stage = (lobbySearchTicks - 1) / 65;
                BlockPos waypoint = computeLobbySearchWaypoint(
                        lobbySpawnCaptured ? lobbySpawnX : p.getX(),
                        lobbySpawnCaptured ? lobbySpawnY : p.getY(),
                        lobbySpawnCaptured ? lobbySpawnZ : p.getZ(),
                        lobbySpawnCaptured ? lobbySpawnYaw : p.getYRot(),
                        stage
                );
                return new PathingCommand(new GoalNear(waypoint, 3), PathingCommandType.SET_GOAL_AND_PATH);
            }
        }

        // Step 4: Main network hub (not yet in BedWars lobby) — open the Server Selector / Game Menu compass.
        if (hasLobbyCompass && !spectatorOrPostGame && !alreadyInBedwarsLobby
                && !hubSelectorUsed && selectorOpenAttempts < 2) {
            if (ticksSinceWorldChange < 45) {
                announce("Loading lobby...");
                baritone.getInputOverrideHandler().clearAllKeys();
                return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
            }
            int menuSlot = findGameMenuHotbarSlot(p);
            if (menuSlot >= 0) {
                announce("Opening Server Selector");
                if (actionCooldown == 0 && p.getInventory().getSelectedSlot() == menuSlot
                        && mc != null && mc.gui.screen() == null && !mc.isPaused()) {
                    selectorOpenAttempts++;
                    hubSelectorRequested = true;
                }
                return useHotbarItem(baritone, ctx, p, menuSlot, 50);
            }
        }

        // Step 5: Fallback if eliminated spectator has Bed/Clock ("Back to Lobby") when no Paper exists
        if (spectatorOrPostGame && idleRequeueTicks >= 110) {
            int lobbyItemSlot = findReturnToLobbyHotbarSlot(p);
            if (lobbyItemSlot >= 0) {
                announce("Eliminated -> Back to Lobby");
                return useHotbarItem(baritone, ctx, p, lobbyItemSlot, 35);
            }
        }
        return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
    }

    /**
     * Pure deterministic guard separating a 5-second mid-game death respawn spectator state
     * (home bed alive, no Play Again / Back-to-Lobby hotbar item, match not ended) from genuine
     * post-elimination spectating so auto-queue never cancels BedWars or spams queue commands mid-game.
     */
    public static boolean isMidGameRespawnSpectator(
            boolean isSpectator,
            boolean matchEnded,
            boolean hasPlayAgainItem,
            boolean hasReturnToLobbyItem,
            boolean homeBedAlive,
            int respawningSecondsRemaining,
            int spectatorTicks
    ) {
        if (!isSpectator || matchEnded || hasPlayAgainItem || hasReturnToLobbyItem) {
            return false;
        }
        return respawningSecondsRemaining > 0 || (homeBedAlive && spectatorTicks <= 160);
    }

    static int findEmptyQueueHandSlot(int selected, IntPredicate isEmpty) {
        if (selected >= 0 && selected < 9 && isEmpty.test(selected)) {
            return selected;
        }
        for (int slot = 0; slot < 9; slot++) {
            if (isEmpty.test(slot)) {
                return slot;
            }
        }
        return -1;
    }

    private PathingCommand useHotbarItem(Baritone baritone, IPlayerContext ctx, LocalPlayer p, int slot, int cooldownAfter) {
        baritone.getInputOverrideHandler().clearAllKeys();
        Minecraft mc = ctx.minecraft();
        if (mc == null || mc.gui.screen() != null || mc.isPaused()) {
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }
        if (p.getInventory().getSelectedSlot() != slot) {
            baritone.getInventoryBehavior().safeSelectHotbarSlot(slot);
            actionCooldown = Math.max(actionCooldown, 5);
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }
        if (actionCooldown == 0 && ctx.world() != null) {
            // Directly send vanilla right-click item use (ServerboundUseItemPacket) so non-block UI items
            // (Server Selector compass, Play Again paper, Back to Lobby item) open reliably even when looking
            // at air or crowded hub players — BlockPlaceHelper only fires on HitResult.Type.BLOCK!
            ctx.playerController().processRightClick(p, ctx.world(), InteractionHand.MAIN_HAND);
            p.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, false);
            actionCooldown = cooldownAfter;
        }
        return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
    }

    /**
     * Pure classifier distinguishing an active in-arena BedWars match (even on tick 0 after a
     * same-world cage-to-island drop when {@code core} has not scanned beds yet and the scoreboard
     * title is {@code "BEDWARS"}) from a pre-game waiting cage or BedWars queue lobby.
     */
    public static boolean isLiveArenaMatch(
            boolean inWaitingLobby,
            boolean hasLobbyCompass,
            boolean spectatorOrPostGame,
            boolean hasLivingArenaBed,
            boolean matchStarted,
            boolean homeTeamKnown,
            boolean anyTeamDiscovered,
            boolean hasInGameLoadout
    ) {
        if (inWaitingLobby || hasLobbyCompass || spectatorOrPostGame) {
            return false;
        }
        return hasLivingArenaBed || matchStarted || homeTeamKnown || anyTeamDiscovered || hasInGameLoadout;
    }

    /**
     * Pure classifier for items that belong exclusively to a live BedWars match loadout (spawn sword,
     * forge currencies, shears, mining tools) and never appear in a BedWars queue lobby hotbar.
     */
    public static boolean isInGameLoadoutItem(String itemPath) {
        if (itemPath == null || itemPath.isBlank()) {
            return false;
        }
        String p = itemPath.toLowerCase(Locale.ROOT);
        return p.endsWith("_sword")
                || p.equals("iron_ingot")
                || p.equals("gold_ingot")
                || p.equals("shears")
                || p.endsWith("_pickaxe")
                || p.endsWith("_axe");
    }

    private static boolean hasInGameLoadout(LocalPlayer p) {
        if (p == null) {
            return false;
        }
        for (int i = 0; i < 36; i++) {
            ItemStack st = p.getInventory().getItem(i);
            if (st.isEmpty()) {
                continue;
            }
            String path = BuiltInRegistries.ITEM.getKey(st.getItem()).getPath();
            if (isInGameLoadoutItem(path)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Non-fatal in-match arena scan watchdog: whenever the match has started (home team known or
     * live match loadout detected) but the arena core has no beds yet, immediately trigger T=0 scan
     * and retry every 40 ticks until it succeeds. Never disables auto mode.
     */
    private void tickScanWatchdog(
            Baritone baritone,
            BedwarsArenaMemory memory,
            BedwarsMatchState matchState,
            LocalPlayer p,
            boolean liveMatch
    ) {
        if (memory == null || matchState == null || p.isSpectator() || !p.isAlive()) {
            return;
        }
        boolean homeKnown = matchState.getHomeTeam() != TeamColor.UNKNOWN;
        if ((!homeKnown && !liveMatch) || ticksSinceWorldChange < 15) {
            return;
        }
        BedwarsMapCore core = memory.getActiveCore();
        if (core != null && !core.getBeds().isEmpty()) {
            if (scanRetryTicks != -1) {
                scanRetryTicks = -1;
                announce("Match started on " + memory.getCurrentMapName() + " (" + matchState.getHomeTeam().name()
                        + ", " + core.getBeds().size() + " beds scanned)");
            }
            return;
        }
        if (scanRetryTicks < 0) {
            scanRetryTicks = 0;
        }
        if (scanRetryTicks == 0 || ++scanRetryTicks >= 40) {
            scanRetryTicks = 1;
            memory.requestRescan();
            memory.captureSnapshotT0();
            announce("Scanning arena...");
        }
    }

    /**
     * Pure classifier for hotbar "Play Again" / "New Game" items across BedWars servers.
     */
    public static boolean isPlayAgainHotbarItem(String itemPath, String displayName) {
        String p = itemPath != null ? itemPath.toLowerCase(Locale.ROOT) : "";
        String n = displayName != null ? BedwarsArenaMemory.stripColors(displayName).toLowerCase(Locale.ROOT) : "";
        if (n.contains("play again") || n.contains("new game") || n.contains("requeue")
                || n.contains("next game") || n.contains("join again")) {
            return true;
        }
        return p.equals("paper") && !n.contains("map") && !n.contains("stat") && !n.contains("quest");
    }

    /**
     * Pure GUI slot selector for Play Again confirmation dialogs (e.g. Pika/Jartex "Go to new game"
     * confirm button) and BedWars mode selector GUIs (Solo / Doubles / Trios / Quads).
     */
    public static int findAutoQueueConfirmSlot(
            String menuTitle,
            List<ShopSlotInfo> slots,
            String preferredMode
    ) {
        return findAutoQueueConfirmSlot(menuTitle, slots, preferredMode, isServerSelectorTitle(menuTitle));
    }

    static int findAutoQueueConfirmSlot(String menuTitle, List<ShopSlotInfo> slots,
                                       String preferredMode, boolean allowHubSelector) {
        if (slots == null || slots.isEmpty() || (isServerSelectorTitle(menuTitle) && !allowHubSelector)) {
            return -1;
        }
        String title = menuTitle == null ? "" : menuTitle.toLowerCase(Locale.ROOT);
        boolean confirmsQueue = title.contains("confirm") || title.contains("play again")
                || title.contains("new game") || title.contains("join game");
        String mode = normalizeQueueMode(preferredMode);
        int bestSlot = -1;
        int bestScore = 0;
        for (ShopSlotInfo s : slots) {
            if (s == null) continue;
            String path = s.itemPath() != null ? s.itemPath().toLowerCase(Locale.ROOT) : "";
            String name = s.displayName() != null ? BedwarsArenaMemory.stripColors(s.displayName()).toLowerCase(Locale.ROOT) : "";
            String lore = s.loreText() != null ? BedwarsArenaMemory.stripColors(s.loreText()).toLowerCase(Locale.ROOT) : "";
            if (path.isEmpty() || path.contains("black_stained_glass") || path.contains("gray_stained_glass")
                    || path.contains("barrier") || name.contains("cancel") || name.contains("close")
                    || name.contains("decline") || name.contains("deny") || name.equals("no")) {
                continue;
            }
            ModeMatch match = matchQueueMode(name, mode);
            if (match == ModeMatch.OTHER) {
                continue;
            }
            int score = 0;
            if (confirmsQueue && (name.contains("confirm") || name.contains("play again") || name.contains("join game")
                    || name.contains("new game") || name.equals("yes") || name.contains("accept"))) {
                score = Math.max(score, 120);
            }
            if (match == ModeMatch.REQUESTED && (name.contains("bedwars") || lore.contains("click") || lore.contains("play") || path.contains("bed") || path.contains("wool"))) {
                score = Math.max(score, 110);
            } else if (match == ModeMatch.REQUESTED) {
                score = Math.max(score, 95);
            }
            if (allowHubSelector && (name.contains("bedwars") || name.contains("bed wars"))) {
                score = Math.max(score, 85);
            }
            // Standard green/lime confirm button (slot 11 / 12 / 13 in 3-row confirm menus)
            boolean greenConfirmBlock = path.contains("lime_wool") || path.contains("green_wool")
                    || path.contains("lime_terracotta") || path.contains("green_terracotta")
                    || path.contains("lime_stained_glass") || path.contains("green_stained_glass")
                    || path.contains("lime_concrete") || path.contains("green_concrete")
                    || path.equals("emerald_block") || path.equals("emerald") || path.equals("lime_dye");
            if (confirmsQueue && greenConfirmBlock && !path.contains("red_")) {
                score = Math.max(score, 80);
            }
            if (score > bestScore) {
                bestScore = score;
                bestSlot = s.slotIndex();
            }
        }
        return bestSlot;
    }

    private static boolean isServerSelectorTitle(String title) {
        String normalized = title == null ? "" : title.toLowerCase(Locale.ROOT);
        return normalized.contains("server selector") || normalized.contains("minigame selector")
                || (!isBedwarsLobbyScoreboard(normalized)
                    && (normalized.contains("game menu") || normalized.contains("game selector")));
    }

    /**
     * Pure matcher for BedWars lobby queue NPCs (combining entity name + overhead armor-stand tags).
     */
    public static boolean isLobbyQueueNpcLabel(String cleanLabel, String preferredMode) {
        if (cleanLabel == null || cleanLabel.isBlank()) {
            return false;
        }
        String u = cleanLabel.toUpperCase(Locale.ROOT);
        if (u.contains("SHOP") || u.contains("UPGRADE") || u.contains("QUEST") || u.contains("LEADERBOARD")
                || u.contains("STATS") || u.contains("COSMETIC") || u.contains("PARKOUR") || u.contains("DELIVERY")
                || u.contains("RETURN HUB") || u.contains("MAIN LOBBY") || u.contains("GUILD") || u.contains("VOTE")
                || u.contains("CRATE") || u.contains("GOLD SHOP") || u.contains("QUICKBUY") || u.contains("SPECTATE")) {
            return false;
        }
        return switch (matchQueueMode(cleanLabel, normalizeQueueMode(preferredMode))) {
            case REQUESTED -> true;
            case OTHER -> false;
            case UNSPECIFIED -> u.contains("BEDWARS") || u.contains("BED WARS") || u.contains("PLAY NOW");
        };
    }

    private static int findPlayAgainHotbarSlot(LocalPlayer p) {
        for (int i = 0; i < 9; i++) {
            ItemStack st = p.getInventory().getItem(i);
            if (st.isEmpty()) continue;
            String path = BuiltInRegistries.ITEM.getKey(st.getItem()).getPath();
            String name = st.getHoverName().getString();
            if (isPlayAgainHotbarItem(path, name)) {
                return i;
            }
        }
        return -1;
    }

    private static int findReturnToLobbyHotbarSlot(LocalPlayer p) {
        for (int i = 0; i < 9; i++) {
            ItemStack st = p.getInventory().getItem(i);
            if (st.isEmpty()) continue;
            String path = BuiltInRegistries.ITEM.getKey(st.getItem()).getPath();
            String name = BedwarsArenaMemory.stripColors(st.getHoverName().getString()).toLowerCase(Locale.ROOT);
            if (name.contains("lobby") || name.contains("leave") || name.contains("hub")
                    || path.endsWith("_bed")) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Pure classifier for hub "Server Selector" / "Game Menu" hotbar items across servers
     * (compass / nether star / clock / book named selector, menu, games, play, lobby, minigames...).
     */
    public static boolean isGameMenuHotbarItem(String itemPath, String displayName) {
        String path = itemPath != null ? itemPath.toLowerCase(Locale.ROOT) : "";
        String n = displayName != null ? BedwarsArenaMemory.stripColors(displayName).toLowerCase(Locale.ROOT) : "";
        boolean carrier = path.equals("compass") || path.equals("nether_star") || path.equals("clock")
                || path.equals("recovery_compass") || path.equals("book") || path.equals("enchanted_book")
                || path.equals("ender_pearl") || path.equals("ender_eye") || path.equals("heart_of_the_sea");
        if (!carrier) {
            return false;
        }
        if (n.contains("spectat") || n.contains("play again") || n.contains("back to lobby") || n.contains("reset")
                || n.contains("tracker") || n.contains("friend") || n.contains("language") || n.contains("cosmetic")) {
            return false;
        }
        if (n.contains("selector") || n.contains("game menu") || n.contains("games") || n.contains("minigame")
                || n.contains("play") || n.contains("teleporter") || n.contains("server") || n.contains("navigator")
                || n.contains("hub") || n.contains("bedwars")) {
            return true;
        }
        return (path.equals("compass") || path.equals("nether_star")) && (n.contains("game") || n.contains("menu"));
    }

    /** Safe public helper used by {@code #bws auto} to skip an immediate arena scan when started in a hub/lobby. */
    public static int findGameMenuHotbarSlotSafe(LocalPlayer p) {
        return p != null ? findGameMenuHotbarSlot(p) : -1;
    }

    private static int findGameMenuHotbarSlot(LocalPlayer p) {
        int best = -1;
        int bestScore = -1;
        for (int i = 0; i < 9; i++) {
            ItemStack st = p.getInventory().getItem(i);
            if (st.isEmpty()) continue;
            String path = BuiltInRegistries.ITEM.getKey(st.getItem()).getPath();
            String name = st.getHoverName().getString();
            if (isGameMenuHotbarItem(path, name)) {
                int score = path.equals("compass") ? 3 : path.equals("nether_star") ? 2 : 1;
                if (score > bestScore) {
                    bestScore = score;
                    best = i;
                }
            }
        }
        return best;
    }

    /** One stacked hologram: a column of ArmorStand text lines at the same x/z. */
    public record HologramCluster(double x, double z, double minY, double maxY, String text, Entity lowest) {}

    /** Pure label text helper for tests: joins hologram lines top-to-bottom. */
    public static boolean isLobbyQueueHologramText(String joinedText, String preferredMode) {
        return isLobbyQueueNpcLabel(joinedText, preferredMode);
    }

    private static String hologramText(Entity h) {
        if (h instanceof Display.TextDisplay display) {
            return BedwarsArenaMemory.stripColors(BedwarsTickContext.extractTextDisplayString(display)).trim();
        }
        Component cn = h.getCustomName();
        String raw = cn != null ? cn.getString() : h.getName().getString();
        return BedwarsArenaMemory.stripColors(raw).trim();
    }

    private static Entity findLobbyQueueNpc(IPlayerContext ctx, LocalPlayer me, String preferredMode,
                                            BedwarsQueueCommands queueCommands) {
        String mode = normalizeQueueMode(preferredMode);
        List<Entity> stands = new ArrayList<>();
        List<Entity> bodies = new ArrayList<>();
        for (Entity e : ctx.entities()) {
            if (e == me || !e.isAlive()) continue;
            if (e instanceof ArmorStand || e instanceof Display.TextDisplay) {
                stands.add(e);
            } else if (e instanceof net.minecraft.world.entity.LivingEntity) {
                bodies.add(e);
            }
        }
        // 1) Cluster stacked hologram lines by horizontal position (hologram-first: works for any NPC entity type/UUID)
        List<List<Entity>> clusters = new ArrayList<>();
        for (Entity st : stands) {
            if (hologramText(st).isEmpty()) continue;
            List<Entity> home = null;
            for (List<Entity> c : clusters) {
                Entity ref = c.get(0);
                double dx = ref.getX() - st.getX();
                double dz = ref.getZ() - st.getZ();
                if (dx * dx + dz * dz <= 0.64) {
                    home = c;
                    break;
                }
            }
            if (home == null) {
                home = new ArrayList<>();
                clusters.add(home);
            }
            home.add(st);
        }
        Entity best = null;
        double bestScore = Double.POSITIVE_INFINITY;
        for (List<Entity> c : clusters) {
            c.sort((a, b) -> Double.compare(b.getY(), a.getY()));
            StringBuilder sb = new StringBuilder();
            double minY = Double.POSITIVE_INFINITY;
            Entity lowestStand = null;
            for (Entity st : c) {
                sb.append(hologramText(st)).append(' ');
                if (st instanceof ArmorStand && st.getY() < minY) {
                    minY = st.getY();
                    lowestStand = st;
                }
            }
            String label = sb.toString().trim();
            if (!isLobbyQueueNpcLabel(label, preferredMode)) {
                continue;
            }
            queueCommands.learnAdvertisement(label);
            Entity ref = c.get(0);
            Entity body = null;
            double bodyD = Double.POSITIVE_INFINITY;
            for (Entity b : bodies) {
                double dx = b.getX() - ref.getX();
                double dz = b.getZ() - ref.getZ();
                double dy = ref.getY() - b.getY();
                if (dx * dx + dz * dz <= 2.25 && dy >= -1.0 && dy <= 5.0) {
                    double d = dx * dx + dz * dz + Math.abs(dy) * 0.05;
                    if (d < bodyD) {
                        bodyD = d;
                        body = b;
                    }
                }
            }
            if (body == null) {
                String up = label.toUpperCase(Locale.ROOT);
                // Reject static tutorial/info sign holograms that have no NPC body underneath
                if (lowestStand == null || (!up.contains("CLICK") && !up.contains("JOIN") && !up.contains("PLAY NOW"))) {
                    continue;
                }
            }
            Entity target = body != null ? body : lowestStand;
            double dSq = me.distanceToSqr(target);
            double score = dSq + (matchQueueMode(label, mode) == ModeMatch.REQUESTED ? -100000.0 : 0.0)
                    + (body == null ? 200000.0 : 0.0);
            if (score < bestScore) {
                bestScore = score;
                best = target;
            }
        }
        // 2) Legacy fallback: named villager / NPC player whose own name carries the label
        if (best == null) {
            for (Entity e : bodies) {
                if (!(e instanceof AbstractVillager) && !(e instanceof Player)) continue;
                String nm = BedwarsArenaMemory.stripColors(e.getName().getString());
                if (isLobbyQueueNpcLabel(nm, preferredMode)) {
                    queueCommands.learnAdvertisement(nm);
                    double d = me.distanceToSqr(e) + (matchQueueMode(nm, mode) == ModeMatch.REQUESTED ? -100000.0 : 0.0);
                    if (d < bestScore) {
                        bestScore = d;
                        best = e;
                    }
                }
            }
        }
        return best;
    }

    /**
     * Pure classifier: returns true when the scoreboard sidebar title indicates we are already inside a
     * BedWars lobby (as opposed to a multi-game network hub like "PikaNetwork" or "JartexNetwork").
     */
    public static boolean isBedwarsLobbyScoreboard(String scoreboardTitle) {
        if (scoreboardTitle == null || scoreboardTitle.isBlank()) {
            return false;
        }
        String clean = BedwarsArenaMemory.stripColors(scoreboardTitle).toLowerCase(Locale.ROOT);
        return clean.contains("bedwars") || clean.contains("bed wars");
    }

    /**
     * Computes progressive search waypoints forward along the lobby spawn facing direction so NPCs placed
     * 35-55 blocks ahead of spawn enter client entity-tracking range.
     */
    public static BlockPos computeLobbySearchWaypoint(double spawnX, double spawnY, double spawnZ, float spawnYawDeg, int stage) {
        double rad = Math.toRadians(spawnYawDeg);
        double fx = -Math.sin(rad);
        double fz = Math.cos(rad);
        double rx = -fz;
        double rz = fx;
        double fwd;
        double side;
        switch (stage % 4) {
            case 0 -> { fwd = 20.0; side = 0.0; }
            case 1 -> { fwd = 36.0; side = 0.0; }
            case 2 -> { fwd = 26.0; side = 14.0; }
            default -> { fwd = 26.0; side = -14.0; }
        }
        return BlockPos.containing(spawnX + fx * fwd + rx * side, spawnY, spawnZ + fz * fwd + rz * side);
    }

    private static boolean hasBedwarsLobbyHologram(IPlayerContext ctx) {
        for (Entity e : ctx.entities()) {
            if (!(e instanceof ArmorStand) && !(e instanceof Display.TextDisplay)) continue;
            String u = hologramText(e).toUpperCase(Locale.ROOT);
            if (u.contains("BEDWARS") || u.contains("BED WARS") || u.contains("QUICKBUY")) {
                return true;
            }
        }
        return false;
    }

    private static List<ShopSlotInfo> readContainerSlots(AbstractContainerMenu menu) {
        int count = Math.max(0, menu.slots.size() - 36);
        List<ShopSlotInfo> out = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            Slot sl = menu.getSlot(i);
            ItemStack st = sl.getItem();
            if (st.isEmpty()) continue;
            String path = BuiltInRegistries.ITEM.getKey(st.getItem()).getPath();
            String name = BedwarsArenaMemory.stripColors(st.getHoverName().getString());
            StringBuilder loreSb = new StringBuilder();
            var lore = st.get(DataComponents.LORE);
            if (lore != null) {
                for (Component line : lore.lines()) {
                    if (!loreSb.isEmpty()) loreSb.append('\n');
                    loreSb.append(BedwarsArenaMemory.stripColors(line.getString()));
                }
            }
            out.add(new ShopSlotInfo(i, path, name, loreSb.toString(), st.getCount()));
        }
        return out;
    }
}
