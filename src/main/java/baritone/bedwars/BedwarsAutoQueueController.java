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
import baritone.bedwars.BedwarsShopPlanner.ShopSlotInfo;
import baritone.human.CraftingHelper;
import baritone.process.CombatProcess;
import baritone.utils.accessor.IGuiScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
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
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

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

    private boolean enabled = false;
    private String preferredMode = "solo";
    private int idleRequeueTicks = 0;
    private int actionCooldown = 0;
    private boolean wasWaitingLobby = false;
    private int postLobbyScanTicks = 0;

    public boolean isEnabled() {
        return enabled;
    }

    public String getPreferredMode() {
        return preferredMode;
    }

    public void setEnabled(boolean enabled, String mode) {
        this.enabled = enabled;
        if (mode != null && !mode.isBlank()) {
            this.preferredMode = mode.trim().toLowerCase(Locale.ROOT);
        }
        this.idleRequeueTicks = 0;
        this.actionCooldown = 10;
        this.postLobbyScanTicks = 0;
    }

    public void reset() {
        this.idleRequeueTicks = 0;
        this.actionCooldown = 0;
        this.postLobbyScanTicks = 0;
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
        BedwarsArenaMemory memory = baritone.getBedwarsArenaMemory();
        BedwarsMatchState matchState = memory != null ? memory.getMatchState() : null;
        boolean inWaitingLobby = memory != null && memory.isInWaitingLobby();

        // Automatically trigger a fresh T=0 arena scan right after dropping from waiting cage into arena!
        if (wasWaitingLobby && !inWaitingLobby && !p.isSpectator()) {
            postLobbyScanTicks = 25;
        }
        wasWaitingLobby = inWaitingLobby;
        if (postLobbyScanTicks > 0) {
            postLobbyScanTicks--;
            if (postLobbyScanTicks == 15 || postLobbyScanTicks == 1) {
                if (memory != null) {
                    memory.captureSnapshotT0();
                }
            }
        }

        // Never requeue while waiting in pre-game countdown cage
        if (inWaitingLobby) {
            idleRequeueTicks = 0;
            return null;
        }

        int playAgainSlot = findPlayAgainHotbarSlot(p);
        boolean matchEnded = matchState != null && matchState.isMatchEnded();
        boolean spectatorOrPostGame = p.isSpectator() || matchEnded || playAgainSlot >= 0;

        // Also detect BedWars hub / lobby: no living beds in core + lobby selector items or queue NPCs nearby
        BedwarsMapCore core = memory != null ? memory.getActiveCore() : null;
        boolean hasLivingArenaBed = false;
        if (core != null) {
            for (BedwarsMapCore.BedPOI b : core.getBeds()) {
                if (!b.destroyed()) {
                    hasLivingArenaBed = true;
                    break;
                }
            }
        }
        boolean hasLobbyCompass = hasLobbyHotbarItems(p);
        Entity lobbyNpc = (!hasLivingArenaBed && !spectatorOrPostGame) ? findLobbyQueueNpc(ctx, p, preferredMode) : null;
        boolean inBedwarsLobby = !hasLivingArenaBed && (lobbyNpc != null || hasLobbyCompass);

        if (!spectatorOrPostGame && !inBedwarsLobby) {
            idleRequeueTicks = 0;
            return null;
        }

        // Stop active Bedwars/Combat tasks cleanly while requeueing
        if (baritone.getCombatProcess().isActive()) {
            baritone.getCombatProcess().cancel();
        }
        if (baritone.getBedwarsProcess().isActive()) {
            baritone.getBedwarsProcess().cancel();
        }

        // Step 1: If a server container GUI (Play Again confirm / Mode selector) is open, click confirm/mode!
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
            int targetSlot = findAutoQueueConfirmSlot(title, slots, preferredMode);
            if (targetSlot >= 0 && actionCooldown == 0 && CraftingHelper.advanceAndReadyGui(ctx, targetSlot)) {
                ctx.playerController().windowClick(menu.containerId, targetSlot, 0, ContainerInput.PICKUP, p);
                actionCooldown = 18;
                idleRequeueTicks = 0;
            }
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }

        // Human delay before clicking Play Again / lobby NPC (~2.5 seconds after elimination/end)
        if (++idleRequeueTicks < 50) {
            baritone.getInputOverrideHandler().clearAllKeys();
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }

        // Step 2: In-game Play Again hotbar item (Paper / "Play Again")
        if (playAgainSlot >= 0) {
            baritone.getInputOverrideHandler().clearAllKeys();
            if (p.getInventory().getSelectedSlot() != playAgainSlot) {
                baritone.getInventoryBehavior().safeSelectHotbarSlot(playAgainSlot);
                actionCooldown = Math.max(actionCooldown, 4);
                return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
            }
            if (actionCooldown == 0) {
                baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_RIGHT, true);
                actionCooldown = 25;
            }
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }

        // Step 3: Lobby NPC walk + right-click (e.g. Solo / Doubles / BedWars NPC)
        if (lobbyNpc != null) {
            double dSq = p.distanceToSqr(lobbyNpc);
            if (dSq <= 12.25) {
                baritone.getInputOverrideHandler().clearMovementKeys();
                Vec3 aimVec = lobbyNpc.getBoundingBox().getCenter();
                Rotation aim = RotationUtils.calcRotationFromVec3d(ctx.playerHead(), aimVec, ctx.playerRotations());
                baritone.getLookBehavior().updateTarget(aim, true, true);
                ((baritone.behavior.LookBehavior) baritone.getLookBehavior()).applyClientPreTickRotationAndRepick();
                if (actionCooldown == 0 && CombatProcess.clickUseIfCrosshairMatches(ctx, lobbyNpc, 12.25, true)) {
                    actionCooldown = 25;
                }
                return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
            }
            BlockPos npcPos = lobbyNpc.blockPosition();
            return new PathingCommand(new GoalNear(npcPos, 3), PathingCommandType.SET_GOAL_AND_PATH);
        }

        // Step 4: Fallback if eliminated spectator has Bed/Clock ("Back to Lobby") when no Paper exists
        if (spectatorOrPostGame && idleRequeueTicks >= 110) {
            int lobbyItemSlot = findReturnToLobbyHotbarSlot(p);
            if (lobbyItemSlot >= 0) {
                baritone.getInputOverrideHandler().clearAllKeys();
                if (p.getInventory().getSelectedSlot() != lobbyItemSlot) {
                    baritone.getInventoryBehavior().safeSelectHotbarSlot(lobbyItemSlot);
                    actionCooldown = 4;
                    return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
                }
                if (actionCooldown == 0) {
                    baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_RIGHT, true);
                    actionCooldown = 30;
                }
            }
        }
        return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
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
        if (slots == null || slots.isEmpty()) {
            return -1;
        }
        String mode = preferredMode != null ? preferredMode.toLowerCase(Locale.ROOT) : "solo";
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
            int score = 0;
            if (name.contains("confirm") || name.contains("play again") || name.contains("join game")
                    || name.contains("new game") || name.equals("yes") || name.contains("accept")) {
                score = Math.max(score, 120);
            }
            if (name.contains(mode) && (name.contains("bedwars") || lore.contains("click") || lore.contains("play") || path.contains("bed") || path.contains("wool"))) {
                score = Math.max(score, 110);
            } else if (name.contains(mode)) {
                score = Math.max(score, 95);
            }
            if (name.contains("bedwars") || name.contains("bed wars")) {
                score = Math.max(score, 85);
            }
            // Standard green/lime confirm button (slot 11 / 12 / 13 in 3-row confirm menus)
            boolean greenConfirmBlock = path.contains("lime_wool") || path.contains("green_wool")
                    || path.contains("lime_terracotta") || path.contains("green_terracotta")
                    || path.contains("lime_stained_glass") || path.contains("green_stained_glass")
                    || path.contains("lime_concrete") || path.contains("green_concrete")
                    || path.equals("emerald_block") || path.equals("emerald") || path.equals("lime_dye");
            if (greenConfirmBlock && !path.contains("red_")) {
                score = Math.max(score, 80);
            }
            if (score > bestScore) {
                bestScore = score;
                bestSlot = s.slotIndex();
            }
        }
        return bestSlot;
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
                || u.contains("STATS") || u.contains("COSMETIC") || u.contains("PARKOUR") || u.contains("DELIVERY")) {
            return false;
        }
        String mode = preferredMode != null ? preferredMode.toUpperCase(Locale.ROOT) : "SOLO";
        if (u.contains(mode)) {
            return true;
        }
        return u.contains("BEDWARS") || u.contains("BED WARS") || u.contains("PLAY NOW");
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

    private static boolean hasLobbyHotbarItems(LocalPlayer p) {
        for (int i = 0; i < 9; i++) {
            ItemStack st = p.getInventory().getItem(i);
            if (st.isEmpty()) continue;
            if (st.is(Items.COMPASS) || st.is(Items.NETHER_STAR)) {
                String name = BedwarsArenaMemory.stripColors(st.getHoverName().getString()).toLowerCase(Locale.ROOT);
                if (name.contains("game") || name.contains("menu") || name.contains("lobby") || name.contains("selector")) {
                    return true;
                }
            }
        }
        return false;
    }

    private static Entity findLobbyQueueNpc(IPlayerContext ctx, LocalPlayer me, String preferredMode) {
        Entity best = null;
        double bestScore = Double.POSITIVE_INFINITY;
        String modeUp = preferredMode != null ? preferredMode.toUpperCase(Locale.ROOT) : "SOLO";
        for (Entity e : ctx.entities()) {
            if (e == me || !e.isAlive()) continue;
            boolean isNpcBody = e instanceof AbstractVillager
                    || (e instanceof Player pl && (pl.getUUID().version() == 2 || pl.getName().getString().contains("NPC")));
            if (!isNpcBody) continue;
            double dSq = me.distanceToSqr(e);
            if (dSq > 2500.0) continue; // within 50 blocks
            StringBuilder sb = new StringBuilder(BedwarsArenaMemory.stripColors(e.getName().getString()));
            for (Entity h : ctx.entities()) {
                if (h instanceof ArmorStand) {
                    double dx = h.getX() - e.getX();
                    double dz = h.getZ() - e.getZ();
                    double dy = h.getY() - e.getY();
                    if (dx * dx + dz * dz <= 2.25 && dy >= -0.3 && dy <= 3.5) {
                        sb.append(' ').append(BedwarsArenaMemory.stripColors(h.getName().getString()));
                    }
                }
            }
            String label = sb.toString();
            if (!isLobbyQueueNpcLabel(label, preferredMode)) {
                continue;
            }
            double score = dSq + (label.toUpperCase(Locale.ROOT).contains(modeUp) ? -1000.0 : 0.0);
            if (score < bestScore) {
                bestScore = score;
                best = e;
            }
        }
        return best;
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
