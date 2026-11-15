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
import baritone.api.utils.IPlayerContext;
import baritone.api.utils.Rotation;
import baritone.api.utils.input.Input;
import baritone.bedwars.BedwarsMapCore.BedPOI;
import baritone.bedwars.BedwarsMapCore.BwsRuleset;
import baritone.bedwars.BedwarsMapCore.GenPOI;
import baritone.bedwars.BedwarsMapCore.ShopNPC;
import baritone.bedwars.BedwarsMapCore.TeamColor;
import baritone.bedwars.BedwarsMatchState.TeamStatusSnapshot;
import baritone.bedwars.BedwarsShopPlanner.BedwarsItemRole;
import baritone.bedwars.BedwarsShopPlanner.CostSpec;
import baritone.bedwars.BedwarsShopPlanner.ShopSlotInfo;
import baritone.bedwars.BedwarsShopPlanner.SlotAffordability;
import baritone.bedwars.BedwarsTacticalHelper.InventorySummary;
import baritone.brain.BrainActionSpace;
import baritone.brain.BrainActionSpace.BrainDecision;
import baritone.brain.BrainActionSpace.CombatMode;
import baritone.brain.BrainActionSpace.Maneuver;
import baritone.brain.BrainActionSpace.StyleSliders;
import baritone.brain.BrainContextExtractor;
import baritone.brain.BrainProcess;
import baritone.brain.BrainSchema;
import baritone.brain.BrainSchema.ObsSnapshot;
import baritone.brain.BrainTrajectoryRecorder.StepRewardCalculator;
import baritone.brain.RuleTeacherPolicy;
import baritone.human.HumanTelemetryHooks;
import baritone.process.CombatProcess;
import baritone.utils.accessor.IGuiScreen;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.DyedItemColor;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Asynchronous Bedwars blackbox flight recorder + dataset generator writing two JSONL streams
 * under {@code pathadol/bedwars_records/}:
 * <ul>
 *   <li>{@code diag-*.jsonl}: full per-tick kinematics, inputs, gaze raycast, GUI container titles &amp; slot contents,
 *       nearby holograms/NPCs/players/fireballs, and structured tactical events for autonomous troubleshooting.</li>
 *   <li>{@code dataset-*.jsonl}: {@link BrainSchema#OBS_DIM}-dim observations, action masks, active maneuvers,
 *       shaped step rewards, and teacher hints ready for future offline imitation/RL training.</li>
 * </ul>
 */
public final class BedwarsBlackboxRecorder {

    private static final DateTimeFormatter FILE_TS = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
            .withZone(ZoneOffset.UTC);

    private static final int QUEUE_CAPACITY = 8192;

    private enum StreamKind {
        DIAG,
        DATASET,
        FLUSH
    }

    private record WriteEntry(StreamKind kind, String line) {}

    private final Path recordsDir;
    private final BlockingQueue<WriteEntry> queue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
    private final StepRewardCalculator rewardCalc = new StepRewardCalculator();

    private Thread writerThread;
    private BufferedWriter diagWriter;
    private BufferedWriter datasetWriter;
    private volatile boolean recording = false;
    private volatile boolean manualPaused = false;
    private volatile boolean humanObserveMode = false;

    private long lastHumanAttackTick = -100L;
    private long lastHumanUseConsumeTick = -100L;
    private long lastHumanShopClickTick = -100L;
    private boolean lastHumanShopWasUpgrades = false;

    private Path activeDiagFile;
    private Path activeDatasetFile;
    private long tickCounter = 0L;
    private long diagLinesWritten = 0L;
    private long datasetStepsWritten = 0L;
    private long droppedLines = 0L;
    private long startMs = 0L;

    private int lastContainerId = Integer.MIN_VALUE;
    private int lastContainerHash = 0;
    private int lastGuiOpenTicks = 0;
    private String lastScreenClass = "";
    private int lastHotbarHash = 0;
    private Maneuver lastManeuver = null;
    private int maneuverHoldTicks = 0;
    private boolean lastCombatActive = false;
    private float prevHealth = -1.0f;
    private String lastActionBarText = "";
    private String lastTitleText = "";
    private String lastSubtitleText = "";
    private String lastBossBarText = "";
    private String lastTabHeader = "";
    private String lastTabFooter = "";
    private String lastScoreboardSig = "";
    private String lastTabPlayersSig = "";
    private String lastMatchStateSig = "";
    private String lastDecisionSig = "";

    public BedwarsBlackboxRecorder(Path baseDir) {
        this.recordsDir = baseDir.resolve("bedwars_records");
    }

    public boolean isRecording() {
        return recording;
    }

    public boolean isHumanObserveMode() {
        return humanObserveMode;
    }

    public boolean isManualPaused() {
        return manualPaused;
    }

    public Path getRecordsDir() {
        return recordsDir;
    }

    public Path getActiveDiagFile() {
        return activeDiagFile;
    }

    public Path getActiveDatasetFile() {
        return activeDatasetFile;
    }

    public long getTickCounter() {
        return tickCounter;
    }

    public long getDatasetStepsWritten() {
        return datasetStepsWritten;
    }

    public long getDroppedLines() {
        return droppedLines;
    }

    public synchronized String start(String tag, BedwarsMapCore core, BwsRuleset ruleset) throws IOException {
        return start(tag, core, ruleset, false);
    }

    /**
     * Starts passive full-resolution human match recording that keeps logging across {@code #bws off}
     * and arena transitions without overriding any player inputs or camera rotations.
     */
    public synchronized String startHuman(String tag, BedwarsMapCore core, BwsRuleset ruleset) throws IOException {
        if (recording && !humanObserveMode) {
            stop("switch_to_human", false);
        }
        return start(tag, core, ruleset, true);
    }

    private synchronized String start(String tag, BedwarsMapCore core, BwsRuleset ruleset, boolean humanObserve) throws IOException {
        if (recording) {
            return "Blackbox already recording -> " + (activeDiagFile != null ? activeDiagFile.getFileName() : "active");
        }
        Files.createDirectories(recordsDir);
        String safeTag = sanitizeTag(tag);
        String ts = FILE_TS.format(Instant.now());
        this.activeDiagFile = recordsDir.resolve("diag-" + ts + "-" + safeTag + ".jsonl");
        this.activeDatasetFile = recordsDir.resolve("dataset-" + ts + "-" + safeTag + ".jsonl");

        BufferedWriter dw = Files.newBufferedWriter(
                activeDiagFile, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING
        );
        BufferedWriter dsw = Files.newBufferedWriter(
                activeDatasetFile, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING
        );
        synchronized (this) {
            this.diagWriter = dw;
            this.datasetWriter = dsw;
            this.tickCounter = 0L;
            this.diagLinesWritten = 0L;
            this.datasetStepsWritten = 0L;
            this.droppedLines = 0L;
            this.startMs = System.currentTimeMillis();
            this.lastContainerId = Integer.MIN_VALUE;
            this.lastContainerHash = 0;
            this.lastGuiOpenTicks = 0;
            this.lastScreenClass = "";
            this.lastHotbarHash = 0;
            this.lastManeuver = null;
            this.maneuverHoldTicks = 0;
            this.lastCombatActive = false;
            this.prevHealth = -1.0f;
            this.lastActionBarText = "";
            this.lastTitleText = "";
            this.lastSubtitleText = "";
            this.lastBossBarText = "";
            this.lastTabHeader = "";
            this.lastTabFooter = "";
            this.lastScoreboardSig = "";
            this.lastTabPlayersSig = "";
            this.lastMatchStateSig = "";
            this.lastDecisionSig = "";
            this.lastHumanAttackTick = -100L;
            this.lastHumanUseConsumeTick = -100L;
            this.lastHumanShopClickTick = -100L;
            this.lastHumanShopWasUpgrades = false;
            this.humanObserveMode = humanObserve;
            this.rewardCalc.reset();
            this.manualPaused = false;
            this.queue.clear();
            this.recording = true;
            ensureWriterThreadLocked();
        }

        JsonObject startObj = new JsonObject();
        startObj.addProperty("type", "SESSION_START");
        startObj.addProperty("ms", startMs);
        startObj.addProperty("source", humanObserve ? "human" : "bot");
        startObj.addProperty("ruleset", ruleset != null ? ruleset.name() : "UNKNOWN");
        if (core != null) {
            startObj.addProperty("homeTeam", core.homeTeam().name());
            startObj.addProperty("voidY", core.voidFloorY());
            startObj.addProperty("maxY", core.mapMaxBuildY());
            JsonArray bedsArr = new JsonArray();
            for (BedPOI b : core.getBeds()) {
                JsonObject bo = new JsonObject();
                bo.addProperty("team", b.team().name());
                bo.addProperty("home", b.isHome());
                bo.addProperty("dead", b.destroyed());
                bo.addProperty("x", b.x());
                bo.addProperty("y", b.y());
                bo.addProperty("z", b.z());
                bedsArr.add(bo);
            }
            startObj.add("beds", bedsArr);
            JsonArray gensArr = new JsonArray();
            for (GenPOI g : core.getGens()) {
                JsonObject go = new JsonObject();
                go.addProperty("kind", g.type().name());
                go.addProperty("label", g.label());
                go.addProperty("x", g.x());
                go.addProperty("y", g.y());
                go.addProperty("z", g.z());
                gensArr.add(go);
            }
            startObj.add("gens", gensArr);
            JsonArray shopsArr = new JsonArray();
            for (ShopNPC npc : core.getShopNpcs()) {
                JsonObject so = new JsonObject();
                so.addProperty("label", npc.label());
                so.addProperty("x", npc.x());
                so.addProperty("y", npc.y());
                so.addProperty("z", npc.z());
                shopsArr.add(so);
            }
            startObj.add("shops", shopsArr);
        }
        enqueueDiag(startObj);
        return (humanObserve ? "Human blackbox started: " : "Blackbox started: ")
                + activeDiagFile.getFileName() + " & " + activeDatasetFile.getFileName();
    }

    public synchronized String stop(String reason, boolean userPaused) {
        this.manualPaused = userPaused;
        if (userPaused) {
            this.humanObserveMode = false;
        }
        if (!recording) {
            return "Blackbox already stopped.";
        }
        JsonObject endObj = new JsonObject();
        endObj.addProperty("type", "SESSION_END");
        endObj.addProperty("t", tickCounter);
        endObj.addProperty("ms", System.currentTimeMillis());
        endObj.addProperty("reason", reason != null ? reason : "stop");
        endObj.addProperty("diagLines", diagLinesWritten);
        endObj.addProperty("datasetSteps", datasetStepsWritten);
        endObj.addProperty("dropped", droppedLines);
        enqueueDiag(endObj);
        this.recording = false;
        this.queue.offer(new WriteEntry(StreamKind.FLUSH, ""));
        if (writerThread != null) {
            writerThread.interrupt();
        }
        closeWritersQuietly();
        return String.format(Locale.ROOT, "Blackbox stopped (%s): %d ticks, %d dataset steps -> %s",
                reason != null ? reason : "stop", tickCounter, datasetStepsWritten, recordsDir);
    }

    public void onWorldTick(Baritone baritone) {
        onWorldTick(baritone, HumanTelemetryHooks.TickSnapshot.EMPTY);
    }

    public void onWorldTick(Baritone baritone, HumanTelemetryHooks.TickSnapshot tickSnap) {
        List<HumanTelemetryHooks.ContainerClickEvent> containerClicks = HumanTelemetryHooks.consumeContainerClicks();
        if (baritone == null) {
            return;
        }
        BedwarsArenaMemory memory = baritone.getBedwarsArenaMemory();
        IPlayerContext ctx = baritone.getPlayerContext();
        LocalPlayer p = ctx != null ? ctx.player() : null;
        Level world = ctx != null ? ctx.world() : null;

        if (p == null || world == null) {
            if (recording) {
                stop("world_left", false);
            }
            return;
        }

        boolean bwsActive = memory != null && memory.isBedwarsActive();
        if (!recording) {
            if ((bwsActive || humanObserveMode) && !manualPaused) {
                try {
                    String mapTag = memory != null ? memory.getCurrentMapName() : null;
                    if (mapTag == null || mapTag.isBlank()) {
                        mapTag = humanObserveMode
                                ? "human"
                                : (memory != null ? memory.getActiveRuleset().toSettingString() : "bws");
                    }
                    start(mapTag, memory != null ? memory.getActiveCore() : null,
                            memory != null ? memory.getActiveRuleset() : BwsRuleset.OFF, humanObserveMode);
                } catch (IOException ignored) {
                    return;
                }
            } else {
                return;
            }
        } else if (!humanObserveMode && !bwsActive && !baritone.getBedwarsProcess().isActive() && !baritone.getBrainProcess().isActive()) {
            stop("bws_off", false);
            return;
        }

        tickCounter++;
        long nowMs = System.currentTimeMillis();
        BedwarsMapCore core = memory != null ? memory.getActiveCore() : null;

        recordTickSnapshot(baritone, ctx, p, world, memory, core, nowMs);
        recordHotbarIfChanged(p, nowMs);
        recordGuiContainerState(ctx, p, memory, nowMs);
        recordInteractionTelemetryEvents(baritone, ctx, p, memory, tickSnap, containerClicks);
        if (memory != null) {
            recordMatchStateIfChanged(memory.getMatchState());
        }
        if (tickCounter % 20L == 1L) {
            recordNearbyEntitiesSnapshot(ctx, p, nowMs);
        }
        recordDatasetStep(baritone, p, nowMs);
    }

    private void recordInteractionTelemetryEvents(
            Baritone baritone,
            IPlayerContext ctx,
            LocalPlayer p,
            BedwarsArenaMemory memory,
            HumanTelemetryHooks.TickSnapshot tickSnap,
            List<HumanTelemetryHooks.ContainerClickEvent> containerClicks
    ) {
        BedwarsProcess bws = baritone.getBedwarsProcess();
        boolean botShopping = bws != null && bws.isActive() && bws.getMode() == BedwarsProcess.TaskMode.BUY;
        if (!containerClicks.isEmpty() && !botShopping) {
            boolean modern = memory != null && memory.getActiveRuleset() == BwsRuleset.MODERN_26_3;
            InventorySummary curInv = BedwarsTacticalHelper.summarizeInventory(p, modern);
            String title = memory != null ? memory.getLastOpenContainerTitle() : "";
            String lowerTitle = title != null ? title.toLowerCase(Locale.ROOT) : "";
            lastHumanShopWasUpgrades = lowerTitle.contains("upgrade") || lowerTitle.contains("trap");
            AbstractContainerMenu menu = p.containerMenu;
            for (HumanTelemetryHooks.ContainerClickEvent click : containerClicks) {
                String itemDesc = "slot_" + click.slotNum();
                if (menu != null && menu.containerId == click.containerId()
                        && click.slotNum() >= 0 && click.slotNum() < menu.slots.size()) {
                    ItemStack st = menu.getSlot(click.slotNum()).getItem();
                    itemDesc = st.isEmpty() ? "empty" : BuiltInRegistries.ITEM.getKey(st.getItem()).getPath() + "x" + st.getCount();
                }
                recordShopClick(
                        click.containerId(),
                        click.slotNum(),
                        "human_click",
                        itemDesc,
                        "btn=" + click.buttonNum(),
                        curInv.iron(),
                        curInv.gold(),
                        curInv.diamonds(),
                        curInv.emeralds()
                );
                lastHumanShopClickTick = tickCounter;
            }
        }
        if (tickSnap == null) {
            return;
        }
        if ((tickSnap.startAttackCalls() > 0 || tickSnap.attackEntityCalls() > 0)
                && !baritone.getCombatProcess().isActive()) {
            Minecraft mc = ctx.minecraft();
            HitResult hr = mc != null ? mc.hitResult : null;
            String hitType = hr != null ? hr.getType().name() : "NULL";
            String distStr = "";
            String tgtHurtStr = "";
            if (hr instanceof EntityHitResult ehr && ehr.getEntity() != null) {
                Entity ent = ehr.getEntity();
                distStr = String.format(Locale.ROOT, ",dist=%.2f", p.distanceTo(ent));
                if (ent instanceof LivingEntity le) {
                    tgtHurtStr = ",targetHurt=" + le.hurtTime;
                }
            }
            BwsRuleset rs = memory != null ? memory.getActiveRuleset() : BwsRuleset.OFF;
            recordEvent(
                    "ATTACK_FIRE",
                    "source=human,ruleset=" + rs.name()
                            + distStr
                            + ",hitResult=" + hitType
                            + String.format(Locale.ROOT, ",cd=%.2f", p.getAttackStrengthScale(0.5f))
                            + tgtHurtStr
                            + ",hits=" + tickSnap.attackEntityCalls()
            );
            lastHumanAttackTick = tickCounter;
        }
        if (tickSnap.useItemOnConsumed() > 0) {
            ItemStack held = p.getMainHandItem();
            if (!held.isEmpty() && held.getItem() instanceof BlockItem) {
                String heldName = BuiltInRegistries.ITEM.getKey(held.getItem()).getPath();
                boolean botDriving = (bws != null && bws.isActive()) || baritone.getCombatProcess().isActive();
                recordEvent(
                        "BLOCK_PLACE",
                        "source=" + (botDriving ? "bot" : "human")
                                + ",held=" + heldName
                                + ",consumed=" + tickSnap.useItemOnConsumed()
                );
                if (!botDriving) {
                    lastHumanUseConsumeTick = tickCounter;
                }
            }
        }
    }

    public void recordEvent(String eventType, String detail) {
        if (!recording || eventType == null) {
            return;
        }
        JsonObject o = new JsonObject();
        o.addProperty("type", "EVENT");
        o.addProperty("event", eventType);
        o.addProperty("t", tickCounter);
        o.addProperty("ms", System.currentTimeMillis());
        if (detail != null) {
            o.addProperty("detail", detail);
        }
        enqueueDiag(o);
    }

    public void recordEvent(String eventType, JsonObject fields) {
        if (!recording || eventType == null) {
            return;
        }
        JsonObject o = fields != null ? fields.deepCopy() : new JsonObject();
        o.addProperty("type", "EVENT");
        o.addProperty("event", eventType);
        o.addProperty("t", tickCounter);
        o.addProperty("ms", System.currentTimeMillis());
        enqueueDiag(o);
    }

    public void recordShopClick(int containerId, int slot, String buyKey, String itemName, String costStr, int iron, int gold, int dia, int em) {
        if (!recording) {
            return;
        }
        JsonObject o = new JsonObject();
        o.addProperty("type", "SHOP_CLICK");
        o.addProperty("t", tickCounter);
        o.addProperty("ms", System.currentTimeMillis());
        o.addProperty("containerId", containerId);
        o.addProperty("slot", slot);
        if (buyKey != null) o.addProperty("buyKey", buyKey);
        if (itemName != null) o.addProperty("item", itemName);
        if (costStr != null) o.addProperty("cost", costStr);
        o.addProperty("iron", iron);
        o.addProperty("gold", gold);
        o.addProperty("dia", dia);
        o.addProperty("em", em);
        enqueueDiag(o);
    }

    public void recordHudText(String channel, String text) {
        if (!recording || channel == null) {
            return;
        }
        String clean = text != null ? text.trim() : "";
        String normChannel = channel.trim().toUpperCase(Locale.ROOT);
        synchronized (this) {
            switch (normChannel) {
                case "ACTIONBAR" -> {
                    if (clean.equals(lastActionBarText)) return;
                    lastActionBarText = clean;
                }
                case "TITLE" -> {
                    if (clean.equals(lastTitleText)) return;
                    lastTitleText = clean;
                }
                case "SUBTITLE" -> {
                    if (clean.equals(lastSubtitleText)) return;
                    lastSubtitleText = clean;
                }
                case "BOSSBAR" -> {
                    if (clean.equals(lastBossBarText)) return;
                    lastBossBarText = clean;
                }
                default -> {
                    // Record other HUD channels cleanly
                }
            }
        }
        JsonObject o = new JsonObject();
        o.addProperty("type", "HUD_TEXT");
        o.addProperty("t", tickCounter);
        o.addProperty("ms", System.currentTimeMillis());
        o.addProperty("channel", normChannel);
        o.addProperty("text", clean);
        enqueueDiag(o);
    }

    public void recordTabHeaderFooter(String header, String footer) {
        if (!recording) {
            return;
        }
        String h = header != null ? header.trim() : "";
        String f = footer != null ? footer.trim() : "";
        synchronized (this) {
            if (h.equals(lastTabHeader) && f.equals(lastTabFooter)) {
                return;
            }
            lastTabHeader = h;
            lastTabFooter = f;
        }
        JsonObject o = new JsonObject();
        o.addProperty("type", "TAB_HEADER_FOOTER");
        o.addProperty("t", tickCounter);
        o.addProperty("ms", System.currentTimeMillis());
        o.addProperty("header", h);
        o.addProperty("footer", f);
        enqueueDiag(o);
    }

    public void recordChatMessage(String cleanMsg, String rawComponent) {
        if (!recording) {
            return;
        }
        String clean = cleanMsg != null ? cleanMsg.trim() : "";
        if (clean.isEmpty()) {
            return;
        }
        JsonObject o = new JsonObject();
        o.addProperty("type", "CHAT");
        o.addProperty("t", tickCounter);
        o.addProperty("ms", System.currentTimeMillis());
        o.addProperty("clean", clean);
        if (rawComponent != null && !rawComponent.isBlank()) {
            o.addProperty("raw", rawComponent.length() > 800 ? rawComponent.substring(0, 800) : rawComponent);
        }
        enqueueDiag(o);
    }

    public void recordScoreboardIfChanged(String title, List<String> lines) {
        if (!recording) {
            return;
        }
        String cleanTitle = title != null ? title.trim() : "";
        StringBuilder sig = new StringBuilder(cleanTitle);
        JsonArray arr = new JsonArray();
        if (lines != null) {
            for (String l : lines) {
                String line = l != null ? l.trim() : "";
                sig.append('|').append(line);
                arr.add(line);
            }
        }
        String finalSig = sig.toString();
        synchronized (this) {
            if (finalSig.equals(lastScoreboardSig)) {
                return;
            }
            lastScoreboardSig = finalSig;
        }
        JsonObject o = new JsonObject();
        o.addProperty("type", "SCOREBOARD");
        o.addProperty("t", tickCounter);
        o.addProperty("ms", System.currentTimeMillis());
        o.addProperty("title", cleanTitle);
        o.add("lines", arr);
        enqueueDiag(o);
    }

    public void recordTabPlayersIfChanged(List<String> roster) {
        if (!recording || roster == null) {
            return;
        }
        StringBuilder sig = new StringBuilder();
        JsonArray arr = new JsonArray();
        for (String entry : roster) {
            String s = entry != null ? entry.trim() : "";
            if (!s.isEmpty()) {
                sig.append(s).append(';');
                arr.add(s);
            }
        }
        String finalSig = sig.toString();
        synchronized (this) {
            if (finalSig.equals(lastTabPlayersSig)) {
                return;
            }
            lastTabPlayersSig = finalSig;
        }
        JsonObject o = new JsonObject();
        o.addProperty("type", "TAB_PLAYERS");
        o.addProperty("t", tickCounter);
        o.addProperty("ms", System.currentTimeMillis());
        o.add("players", arr);
        enqueueDiag(o);
    }

    public void recordMatchStateIfChanged(BedwarsMatchState state) {
        if (!recording || state == null) {
            return;
        }
        String sig = state.compactSummary()
                + "|allGone=" + state.isAllBedsDestroyed()
                + "|sd=" + state.isSuddenDeathActive()
                + "|ended=" + state.isMatchEnded()
                + "|respawn=" + state.getRespawningSecondsRemaining();
        synchronized (this) {
            if (sig.equals(lastMatchStateSig)) {
                return;
            }
            lastMatchStateSig = sig;
        }
        JsonObject o = new JsonObject();
        o.addProperty("type", "MATCH_STATE");
        o.addProperty("t", tickCounter);
        o.addProperty("ms", System.currentTimeMillis());
        o.addProperty("homeTeam", state.getHomeTeam().name());
        o.addProperty("phase", state.getNextPhase().name());
        o.addProperty("phaseSec", state.getNextPhaseSeconds());
        o.addProperty("lobbySec", state.getLobbyCountdownSeconds());
        o.addProperty("diaTier", state.getDiamondTier());
        o.addProperty("emTier", state.getEmeraldTier());
        o.addProperty("sharp", state.getSharpnessTier());
        o.addProperty("prot", state.getProtectionTier());
        o.addProperty("haste", state.getHasteTier());
        o.addProperty("forge", state.getForgeTier());
        o.addProperty("healPool", state.hasHealPool());
        o.addProperty("traps", state.getActiveTrapCount());
        o.addProperty("allBedsDestroyed", state.isAllBedsDestroyed());
        o.addProperty("suddenDeath", state.isSuddenDeathActive());
        o.addProperty("ended", state.isMatchEnded());
        o.addProperty("respawnSec", state.getRespawningSecondsRemaining());
        JsonArray teams = new JsonArray();
        for (TeamStatusSnapshot ts : state.getDiscoveredTeams()) {
            JsonObject to = new JsonObject();
            to.addProperty("team", ts.team().name());
            to.addProperty("bed", ts.bedStatus().name());
            to.addProperty("alive", ts.alivePlayers());
            to.addProperty("eliminated", ts.eliminated());
            teams.add(to);
        }
        o.add("teams", teams);
        enqueueDiag(o);
    }

    public void recordDecisionReason(String policyTag, String action, String reason, JsonObject metrics) {
        if (!recording || policyTag == null || action == null) {
            return;
        }
        String r = reason != null ? reason : "";
        String sig = policyTag + "|" + action + "|" + r;
        synchronized (this) {
            if (sig.equals(lastDecisionSig)) {
                return;
            }
            lastDecisionSig = sig;
        }
        JsonObject o = new JsonObject();
        o.addProperty("type", "DECISION");
        o.addProperty("t", tickCounter);
        o.addProperty("ms", System.currentTimeMillis());
        o.addProperty("policy", policyTag);
        o.addProperty("action", action);
        if (!r.isEmpty()) {
            o.addProperty("reason", r);
        }
        if (metrics != null) {
            o.add("metrics", metrics);
        }
        enqueueDiag(o);
    }

    private void recordTickSnapshot(
            Baritone baritone,
            IPlayerContext ctx,
            LocalPlayer p,
            Level world,
            BedwarsArenaMemory memory,
            BedwarsMapCore core,
            long nowMs
    ) {
        JsonObject o = new JsonObject();
        o.addProperty("type", "TICK");
        o.addProperty("t", tickCounter);
        o.addProperty("ms", nowMs);
        o.addProperty("gt", world.getGameTime());

        Vec3 pos = p.position();
        Vec3 vel = p.getDeltaMovement();
        o.add("pos", roundVec3(pos));
        o.add("vel", roundVec34(vel));

        JsonArray rotArr = new JsonArray();
        rotArr.add(round2(p.getYRot()));
        rotArr.add(round2(p.getXRot()));
        o.add("rot", rotArr);

        Rotation targetRot = baritone.getLookBehavior().getDesiredTargetRotation();
        if (targetRot != null) {
            JsonArray trArr = new JsonArray();
            trArr.add(round2(targetRot.getYaw()));
            trArr.add(round2(targetRot.getPitch()));
            o.add("targetRot", trArr);
        }

        int voidY = core != null ? core.voidFloorY() : world.getMinY();
        o.addProperty("onGround", p.onGround());
        o.addProperty("sprinting", p.isSprinting());
        o.addProperty("crouching", p.isCrouching());
        o.addProperty("using", p.isUsingItem());
        o.addProperty("blocking", p.isBlocking());
        o.addProperty("hp", round2(p.getHealth()));
        o.addProperty("hurt", p.hurtTime);
        o.addProperty("fall", round2(p.fallDistance));
        o.addProperty("voidDist", round2(pos.y - voidY));
        o.addProperty("atkScale", round2(p.getAttackStrengthScale(0.5f)));

        int sel = p.getInventory().getSelectedSlot();
        o.addProperty("slot", sel);
        ItemStack held = p.getMainHandItem();
        ItemStack offhand = p.getItemBySlot(EquipmentSlot.OFFHAND);
        o.addProperty("held", held.isEmpty() ? "air" : BuiltInRegistries.ITEM.getKey(held.getItem()).getPath() + "x" + held.getCount());
        if (!offhand.isEmpty()) {
            o.addProperty("offhand", BuiltInRegistries.ITEM.getKey(offhand.getItem()).getPath() + "x" + offhand.getCount());
        }

        o.addProperty("keys", buildInputString(baritone, ctx, p));
        o.add("lookHit", buildLookHitJson(ctx, pos));

        BedwarsProcess bws = baritone.getBedwarsProcess();
        o.addProperty("bwsMode", bws.isActive() ? bws.getMode().name() : "IDLE");

        CombatProcess cp = (CombatProcess) baritone.getCombatProcess();
        if (cp != null && cp.isActive()) {
            JsonObject co = new JsonObject();
            LivingEntity tgt = cp.getActiveTargetEntity();
            if (tgt != null) {
                co.addProperty("id", tgt.getId());
                co.addProperty("name", tgt.getName().getString());
                co.addProperty("dist", round2(p.distanceTo(tgt)));
                co.addProperty("hurt", tgt.hurtTime);
                co.addProperty("blocking", tgt.isBlocking());
                co.addProperty("ground", tgt.onGround());
            }
            co.addProperty("mode", cp.getBrainCombatMode().name());
            o.add("combat", co);
        }

        boolean modern = memory != null && memory.getActiveRuleset() == BwsRuleset.MODERN_26_3;
        InventorySummary inv = BedwarsTacticalHelper.summarizeInventory(p, modern);
        JsonObject io = new JsonObject();
        io.addProperty("iron", inv.iron());
        io.addProperty("gold", inv.gold());
        io.addProperty("dia", inv.diamonds());
        io.addProperty("em", inv.emeralds());
        io.addProperty("blocks", inv.bridgeBlockCount());
        io.addProperty("sword", inv.swordTier());
        io.addProperty("pick", inv.pickaxeTier());
        io.addProperty("axe", inv.axeTier());
        io.addProperty("shears", inv.shearsTier());
        io.addProperty("armor", round2(inv.armorPoints()));
        o.add("inv", io);

        enqueueDiag(o);
    }

    private void recordHotbarIfChanged(LocalPlayer p, long nowMs) {
        int h = p.getInventory().getSelectedSlot() * 31;
        for (int i = 0; i < 9; i++) {
            ItemStack st = p.getInventory().getNonEquipmentItems().get(i);
            h = 31 * h + (st.isEmpty() ? 0 : (System.identityHashCode(st.getItem()) * 67 + st.getCount()));
        }
        ItemStack off = p.getItemBySlot(EquipmentSlot.OFFHAND);
        h = 31 * h + (off.isEmpty() ? 0 : (System.identityHashCode(off.getItem()) * 67 + off.getCount()));
        if (h == lastHotbarHash) {
            return;
        }
        lastHotbarHash = h;
        JsonObject o = new JsonObject();
        o.addProperty("type", "HOTBAR");
        o.addProperty("t", tickCounter);
        o.addProperty("ms", nowMs);
        o.addProperty("selected", p.getInventory().getSelectedSlot());
        JsonArray slots = new JsonArray();
        for (int i = 0; i < 9; i++) {
            ItemStack st = p.getInventory().getNonEquipmentItems().get(i);
            if (!st.isEmpty()) {
                JsonObject so = new JsonObject();
                so.addProperty("s", i);
                so.addProperty("id", BuiltInRegistries.ITEM.getKey(st.getItem()).getPath());
                so.addProperty("c", st.getCount());
                slots.add(so);
            }
        }
        o.add("slots", slots);
        if (!off.isEmpty()) {
            o.addProperty("offhand", BuiltInRegistries.ITEM.getKey(off.getItem()).getPath() + "x" + off.getCount());
        }
        enqueueDiag(o);
    }

    private void recordGuiContainerState(IPlayerContext ctx, LocalPlayer p, BedwarsArenaMemory memory, long nowMs) {
        Minecraft mc = ctx.minecraft();
        Screen screen = (mc != null && mc.gui != null) ? mc.gui.screen() : null;
        AbstractContainerMenu menu = p.containerMenu;
        boolean containerOpen = menu != null && menu != p.inventoryMenu;
        String screenClass = screen != null ? screen.getClass().getSimpleName() : "";

        if (!containerOpen && screen == null) {
            if (lastContainerId != Integer.MIN_VALUE || !lastScreenClass.isEmpty()) {
                JsonObject close = new JsonObject();
                close.addProperty("type", "GUI_CLOSE");
                close.addProperty("t", tickCounter);
                close.addProperty("ms", nowMs);
                close.addProperty("containerId", lastContainerId);
                close.addProperty("screenClass", lastScreenClass);
                close.addProperty("ticksOpen", lastGuiOpenTicks);
                enqueueDiag(close);
                lastContainerId = Integer.MIN_VALUE;
                lastContainerHash = 0;
                lastGuiOpenTicks = 0;
                lastScreenClass = "";
            }
            return;
        }

        lastGuiOpenTicks++;
        int cid = menu != null ? menu.containerId : -1;
        int contentHash = screenClass.hashCode();
        if (menu != null) {
            for (int i = 0; i < menu.slots.size(); i++) {
                Slot slot = menu.slots.get(i);
                ItemStack st = slot.getItem();
                if (!st.isEmpty()) {
                    contentHash = 31 * contentHash + (i * 131) + System.identityHashCode(st.getItem()) * 17 + st.getCount();
                    contentHash = 31 * contentHash + st.getHoverName().getString().hashCode();
                    var loreHash = st.get(DataComponents.LORE);
                    if (loreHash != null) {
                        contentHash = 31 * contentHash + loreHash.lines().size();
                    }
                }
            }
        }

        if (cid == lastContainerId && contentHash == lastContainerHash && screenClass.equals(lastScreenClass)) {
            return;
        }

        boolean newWindow = (cid != lastContainerId) || (!screenClass.equals(lastScreenClass));
        lastContainerId = cid;
        lastContainerHash = contentHash;
        lastScreenClass = screenClass;

        JsonObject g = new JsonObject();
        g.addProperty("type", newWindow ? "GUI_OPEN" : "GUI_UPDATE");
        g.addProperty("t", tickCounter);
        g.addProperty("ms", nowMs);
        g.addProperty("containerId", cid);
        g.addProperty("screenClass", screenClass.isEmpty() ? "none" : screenClass);
        String pktTitle = memory != null ? memory.getOpenMenuTitle(cid) : "";
        String scrTitle = "";
        if (screen instanceof IGuiScreen igs) {
            Component comp = igs.pathadol$getTitle();
            if (comp != null) {
                scrTitle = BedwarsArenaMemory.stripColors(comp.getString());
            }
        }
        g.addProperty("packetTitle", pktTitle);
        g.addProperty("screenTitle", scrTitle);

        if (menu != null) {
            boolean modernRules = memory != null && memory.getActiveRuleset() == BwsRuleset.MODERN_26_3;
            InventorySummary invSummary = BedwarsTacticalHelper.summarizeInventory(p, modernRules);
            g.addProperty("totalSlots", menu.slots.size());
            JsonArray items = new JsonArray();
            int limit = Math.min(menu.slots.size(), 90);
            for (int i = 0; i < limit; i++) {
                ItemStack st = menu.slots.get(i).getItem();
                if (st.isEmpty()) {
                    continue;
                }
                String itemId = BuiltInRegistries.ITEM.getKey(st.getItem()).getPath();
                String itemName = BedwarsArenaMemory.stripColors(st.getHoverName().getString());
                JsonObject so = new JsonObject();
                so.addProperty("slot", i);
                so.addProperty("id", itemId);
                so.addProperty("count", st.getCount());
                so.addProperty("name", itemName);
                StringBuilder loreJoined = new StringBuilder();
                var lore = st.get(DataComponents.LORE);
                if (lore != null && !lore.lines().isEmpty()) {
                    JsonArray loreArr = new JsonArray();
                    for (Component line : lore.lines()) {
                        String s = BedwarsArenaMemory.stripColors(line.getString()).trim();
                        if (!s.isEmpty()) {
                            loreArr.add(s);
                            if (loreJoined.length() > 0) loreJoined.append('\n');
                            loreJoined.append(s);
                        }
                    }
                    if (loreArr.size() > 0) {
                        so.add("lore", loreArr);
                    }
                }
                ShopSlotInfo slotInfo = new ShopSlotInfo(i, itemId, itemName, loreJoined.toString(), st.getCount());
                BedwarsItemRole role = BedwarsShopPlanner.classifyItemRole(slotInfo);
                CostSpec cost = BedwarsShopPlanner.parseCostFromLore(slotInfo.loreText());
                SlotAffordability afford = BedwarsShopPlanner.evaluateAffordability(slotInfo, invSummary, cost);
                so.addProperty("role", role.name());
                so.addProperty("afford", afford.name());
                if (cost != null) {
                    so.addProperty("cost", cost.amount() + " " + cost.currency().name());
                }
                appendItemComponentMetadata(so, st);
                items.add(so);
            }
            g.add("items", items);
        }
        enqueueDiag(g);
    }

    private void recordNearbyEntitiesSnapshot(IPlayerContext ctx, LocalPlayer p, long nowMs) {
        JsonObject o = new JsonObject();
        o.addProperty("type", "ENTITIES");
        o.addProperty("t", tickCounter);
        o.addProperty("ms", nowMs);

        JsonArray stands = new JsonArray();
        JsonArray npcs = new JsonArray();
        JsonArray players = new JsonArray();
        JsonArray projectiles = new JsonArray();
        JsonArray drops = new JsonArray();

        for (Entity e : (Iterable<Entity>) ctx.entitiesStream()::iterator) {
            if (e == null || e == p || !e.isAlive()) {
                continue;
            }
            double dist = p.distanceTo(e);
            if (dist > 48.0) {
                continue;
            }
            String typeId = BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).getPath();
            String customName = e.hasCustomName() && e.getCustomName() != null
                    ? BedwarsArenaMemory.stripColors(e.getCustomName().getString()).trim()
                    : BedwarsArenaMemory.stripColors(e.getName().getString()).trim();

            if ("armor_stand".equals(typeId)) {
                if (dist <= 28.0 && !customName.isBlank() && !customName.equalsIgnoreCase("Armor Stand")) {
                    JsonObject ao = new JsonObject();
                    ao.addProperty("id", e.getId());
                    ao.addProperty("text", customName);
                    ao.add("pos", roundVec3(e.position()));
                    ao.addProperty("dist", round2(dist));
                    stands.add(ao);
                }
            } else if ("villager".equals(typeId) || "wandering_trader".equals(typeId) || "blaze".equals(typeId) || "witch".equals(typeId) || "pillager".equals(typeId)) {
                JsonObject vo = new JsonObject();
                vo.addProperty("id", e.getId());
                vo.addProperty("kind", typeId);
                vo.addProperty("text", customName);
                vo.add("pos", roundVec3(e.position()));
                vo.addProperty("dist", round2(dist));
                npcs.add(vo);
            } else if (e instanceof Player other && !other.isSpectator()) {
                JsonObject po = new JsonObject();
                po.addProperty("id", other.getId());
                po.addProperty("name", other.getGameProfile() != null ? other.getGameProfile().name() : customName);
                po.addProperty("allied", p.isAlliedTo(other));
                TeamColor dyeTeam = TeamColor.UNKNOWN;
                ItemStack chest = other.getItemBySlot(EquipmentSlot.CHEST);
                if (!chest.isEmpty()) {
                    int rgb = DyedItemColor.getOrDefault(chest, -1);
                    if (rgb != -1) {
                        dyeTeam = BedwarsTacticalHelper.inferTeamColorFromDyeRgb(rgb);
                    }
                }
                if (dyeTeam == TeamColor.UNKNOWN) {
                    ItemStack helm = other.getItemBySlot(EquipmentSlot.HEAD);
                    if (!helm.isEmpty()) {
                        int rgb = DyedItemColor.getOrDefault(helm, -1);
                        if (rgb != -1) {
                            dyeTeam = BedwarsTacticalHelper.inferTeamColorFromDyeRgb(rgb);
                        }
                    }
                }
                if (dyeTeam != TeamColor.UNKNOWN) {
                    po.addProperty("dyeTeam", dyeTeam.name());
                }
                po.add("pos", roundVec3(other.position()));
                po.add("vel", roundVec34(other.getDeltaMovement()));
                po.addProperty("dist", round2(dist));
                po.addProperty("hp", round2(other.getHealth()));
                po.addProperty("hurt", other.hurtTime);
                po.addProperty("blocking", other.isBlocking());
                po.addProperty("ground", other.onGround());
                ItemStack held = other.getMainHandItem();
                if (!held.isEmpty()) {
                    po.addProperty("held", BuiltInRegistries.ITEM.getKey(held.getItem()).getPath());
                }
                players.add(po);
            } else if ("fireball".equals(typeId) || "small_fireball".equals(typeId) || "arrow".equals(typeId) || "tnt".equals(typeId)) {
                JsonObject fo = new JsonObject();
                fo.addProperty("id", e.getId());
                fo.addProperty("kind", typeId);
                fo.add("pos", roundVec3(e.position()));
                fo.add("vel", roundVec34(e.getDeltaMovement()));
                fo.addProperty("dist", round2(dist));
                projectiles.add(fo);
            } else if (e instanceof ItemEntity itemEntity && dist <= 28.0 && drops.size() < 24) {
                ItemStack dropped = itemEntity.getItem();
                if (!dropped.isEmpty()) {
                    JsonObject dro = new JsonObject();
                    dro.addProperty("id", BuiltInRegistries.ITEM.getKey(dropped.getItem()).getPath());
                    dro.addProperty("count", dropped.getCount());
                    dro.add("pos", roundVec3(itemEntity.position()));
                    dro.addProperty("dist", round2(dist));
                    dro.addProperty("age", itemEntity.getAge());
                    drops.add(dro);
                }
            }
        }

        o.add("stands", stands);
        o.add("npcs", npcs);
        o.add("players", players);
        o.add("projectiles", projectiles);
        o.add("drops", drops);
        enqueueDiag(o);
    }

    private static void appendItemComponentMetadata(JsonObject so, ItemStack st) {
        ItemEnchantments ench = st.get(DataComponents.ENCHANTMENTS);
        if (ench == null || ench.isEmpty()) {
            ench = st.get(DataComponents.STORED_ENCHANTMENTS);
        }
        if (ench != null && !ench.isEmpty()) {
            JsonObject eo = new JsonObject();
            for (var entry : ench.entrySet()) {
                eo.addProperty(entry.getKey().getRegisteredName(), entry.getIntValue());
            }
            so.add("enchants", eo);
        }
        PotionContents pot = st.get(DataComponents.POTION_CONTENTS);
        if (pot != null && !pot.equals(PotionContents.EMPTY)) {
            JsonObject po = new JsonObject();
            pot.potion().ifPresent(holder -> po.addProperty("id", holder.getRegisteredName()));
            JsonArray effArr = new JsonArray();
            for (MobEffectInstance eff : pot.getAllEffects()) {
                JsonObject eObj = new JsonObject();
                eObj.addProperty("effect", eff.getEffect().getRegisteredName());
                eObj.addProperty("amp", eff.getAmplifier());
                eObj.addProperty("dur", eff.getDuration());
                effArr.add(eObj);
            }
            if (effArr.size() > 0) {
                po.add("effects", effArr);
            }
            so.add("potion", po);
        }
        int dyeRgb = DyedItemColor.getOrDefault(st, -1);
        if (dyeRgb != -1) {
            so.addProperty("dyeRgb", String.format(Locale.ROOT, "#%06X", dyeRgb & 0x00FFFFFF));
            TeamColor dyeTeam = BedwarsTacticalHelper.inferTeamColorFromDyeRgb(dyeRgb);
            if (dyeTeam != TeamColor.UNKNOWN) {
                so.addProperty("dyeTeam", dyeTeam.name());
            }
        }
        CustomData cd = st.get(DataComponents.CUSTOM_DATA);
        if (cd != null && !cd.isEmpty()) {
            CompoundTag tag = cd.copyTag();
            if (!tag.isEmpty()) {
                String raw = tag.toString();
                so.addProperty("customData", raw.length() > 300 ? raw.substring(0, 300) : raw);
            }
        }
    }

    private void recordDatasetStep(Baritone baritone, LocalPlayer p, long nowMs) {
        if (datasetWriter == null) {
            return;
        }
        BrainProcess brain = baritone.getBrainProcess();
        CombatProcess cp = (CombatProcess) baritone.getCombatProcess();
        BedwarsProcess bws = baritone.getBedwarsProcess();
        boolean manualHumanControl = humanObserveMode
                && (brain == null || !brain.isActive())
                && (bws == null || !bws.isActive())
                && (cp == null || !cp.isActive());

        Maneuver currentManeuver;
        if (manualHumanControl) {
            ObsSnapshot prelim = BrainContextExtractor.capture(
                    baritone,
                    lastManeuver != null ? lastManeuver : Maneuver.HOLD_HOME_GEN,
                    maneuverHoldTicks
            );
            boolean guiOpen = p.containerMenu != null && p.containerMenu != p.inventoryMenu;
            currentManeuver = inferHumanManeuver(
                    prelim,
                    guiOpen,
                    lastHumanShopWasUpgrades,
                    tickCounter - lastHumanAttackTick,
                    tickCounter - lastHumanUseConsumeTick,
                    tickCounter - lastHumanShopClickTick,
                    p.isSprinting()
            );
        } else {
            currentManeuver = inferExecutedManeuver(baritone);
        }
        boolean combatActive = (cp != null && cp.isActive()) || (manualHumanControl && currentManeuver == Maneuver.ENGAGE_THREAT_COMBAT);
        boolean maneuverChanged = currentManeuver != lastManeuver || combatActive != lastCombatActive;
        boolean damageOrDeath = (prevHealth > 0.0f && Math.abs(p.getHealth() - prevHealth) >= 1.5f) || !p.isAlive() || p.isDeadOrDying();
        prevHealth = p.getHealth();

        if (currentManeuver == lastManeuver) {
            maneuverHoldTicks++;
        } else {
            lastManeuver = currentManeuver;
            maneuverHoldTicks = 0;
        }
        lastCombatActive = combatActive;

        // Sample periodically every 4 ticks (200ms) or immediately on decision transitions / damage
        if (!maneuverChanged && !damageOrDeath && (tickCounter % 4L != 0L)) {
            return;
        }

        ObsSnapshot snap = BrainContextExtractor.capture(baritone, currentManeuver, maneuverHoldTicks);
        if (snap == null) {
            return;
        }
        float[] obsVec = BrainSchema.encodeObservation(snap);
        boolean[] mask = BrainActionSpace.computeValidManeuverMask(snap);
        BrainDecision teacher = RuleTeacherPolicy.decide(snap, mask);
        boolean done = !p.isAlive() || p.isDeadOrDying();
        float reward = rewardCalc.computeStepReward(snap, done);

        CombatMode cMode = brain != null && brain.isActive()
                ? teacher.combatMode()
                : (cp != null ? cp.getBrainCombatMode() : teacher.combatMode());
        StyleSliders sliders = brain != null && brain.isActive()
                ? teacher.sliders()
                : StyleSliders.BALANCED;

        JsonObject row = new JsonObject();
        row.addProperty("schemaVersion", BrainSchema.SCHEMA_VERSION);
        row.addProperty("step", datasetStepsWritten++);
        row.addProperty("t", tickCounter);
        row.addProperty("tsMs", nowMs);
        String source = manualHumanControl
                ? "human"
                : ((brain != null && brain.isActive()) ? "brain_auto" : "manual_bws");
        row.addProperty("source", source);

        JsonArray obsArr = new JsonArray();
        for (float v : obsVec) {
            obsArr.add(v);
        }
        row.add("obs", obsArr);

        JsonArray maskArr = new JsonArray();
        for (boolean b : mask) {
            maskArr.add(b ? 1 : 0);
        }
        row.add("mask", maskArr);

        row.addProperty("action", currentManeuver.ordinal());
        row.addProperty("actionName", currentManeuver.name());
        row.addProperty("combatMode", cMode.ordinal());

        JsonArray slidersArr = new JsonArray();
        slidersArr.add(sliders.riskAppetite());
        slidersArr.add(sliders.bridgeSpeed());
        slidersArr.add(sliders.voidCaution());
        slidersArr.add(sliders.combatAggression());
        row.add("sliders", slidersArr);

        row.addProperty("reward", reward);
        row.addProperty("teacherHint", teacher.maneuver().ordinal());
        row.addProperty("done", done);

        if (!queue.offer(new WriteEntry(StreamKind.DATASET, row.toString()))) {
            droppedLines++;
        }
    }

    private static Maneuver inferExecutedManeuver(Baritone baritone) {
        BrainProcess brain = baritone.getBrainProcess();
        if (brain != null && brain.isActive()) {
            return brain.getCommittedManeuver();
        }
        if (baritone.getCombatProcess().isActive()) {
            return Maneuver.ENGAGE_THREAT_COMBAT;
        }
        BedwarsProcess bws = baritone.getBedwarsProcess();
        if (bws != null && bws.isActive()) {
            return switch (bws.getMode()) {
                case RUSH -> bws.isRushWeakestMode() ? Maneuver.RUSH_WEAKEST_BED : Maneuver.RUSH_NEAREST_BED;
                case BUY -> bws.isUpgradeBuyPreset() ? Maneuver.SHOP_BUY_COMBAT_UPGRADES : Maneuver.SHOP_BUY_ESSENTIALS;
                case DEFEND, BLOCK_IN -> Maneuver.DEFEND_HOME_BED;
                case FIREBALL_JUMP -> Maneuver.FIREBALL_JUMP;
                case RETREAT -> Maneuver.RETREAT_HOME;
                case DIAMONDS -> Maneuver.FARM_DIAMONDS;
                case EMERALDS -> Maneuver.FARM_EMERALDS;
                case HOME_GEN, NONE -> Maneuver.HOLD_HOME_GEN;
                case STASH -> Maneuver.STASH_VALUABLES;
                case RECALL -> Maneuver.TACTICAL_VOID_RECALL;
            };
        }
        return Maneuver.HOLD_HOME_GEN;
    }

    /**
     * Infers the semantic Bedwars maneuver a human player is currently performing from GUI state,
     * recent attack/placement events, threat proximity, and arena distances.
     */
    public static Maneuver inferHumanManeuver(
            ObsSnapshot snap,
            boolean guiOpen,
            boolean lastShopWasUpgrades,
            long ticksSinceAttack,
            long ticksSincePlace,
            long ticksSinceShopClick,
            boolean sprinting
    ) {
        if (guiOpen || (ticksSinceShopClick >= 0L && ticksSinceShopClick <= 15L)) {
            return lastShopWasUpgrades ? Maneuver.SHOP_BUY_COMBAT_UPGRADES : Maneuver.SHOP_BUY_ESSENTIALS;
        }
        if (snap == null) {
            return Maneuver.HOLD_HOME_GEN;
        }
        if ((ticksSinceAttack >= 0L && ticksSinceAttack <= 20L)
                || snap.hurtTime() > 0
                || snap.nearestThreatDist() <= 6.5
                || (snap.hasEnemyNearHomeBed() && snap.homeBedDist() <= 22.0)) {
            return Maneuver.ENGAGE_THREAT_COMBAT;
        }
        if (snap.homeBedAlive()
                && snap.homeBedDist() <= 8.5
                && snap.homeBedDefenseRatio() < 0.85
                && ticksSincePlace >= 0L && ticksSincePlace <= 20L) {
            return Maneuver.DEFEND_HOME_BED;
        }
        if (snap.health() < 8.5f && snap.homeBedDist() > 16.0 && snap.nearestThreatDist() <= 16.0) {
            return Maneuver.RETREAT_HOME;
        }
        if (snap.emeraldGenDist() <= 14.0
                || (snap.emeraldGenDist() < snap.homeBedDist()
                        && snap.emeraldGenDist() < snap.nearestEnemyBedDist()
                        && snap.emeraldGenDist() < 26.0)) {
            return Maneuver.FARM_EMERALDS;
        }
        if (snap.diamondGenDist() <= 14.0
                || (snap.diamondGenDist() < snap.homeBedDist()
                        && snap.diamondGenDist() < snap.nearestEnemyBedDist()
                        && snap.diamondGenDist() < 24.0)) {
            return Maneuver.FARM_DIAMONDS;
        }
        if (snap.homeBedDist() > 20.0
                && (snap.nearestEnemyBedDist() <= 32.0 || (sprinting && ticksSincePlace >= 0L && ticksSincePlace <= 30L))) {
            return Maneuver.RUSH_NEAREST_BED;
        }
        return Maneuver.HOLD_HOME_GEN;
    }

    private static JsonObject buildLookHitJson(IPlayerContext ctx, Vec3 playerPos) {
        JsonObject h = new JsonObject();
        Minecraft mc = ctx.minecraft();
        HitResult hr = (mc != null && mc.hitResult != null) ? mc.hitResult : ctx.objectMouseOver();
        if (hr == null || hr.getType() == HitResult.Type.MISS) {
            h.addProperty("type", "MISS");
            return h;
        }
        if (hr instanceof EntityHitResult ehr) {
            Entity ent = ehr.getEntity();
            h.addProperty("type", "ENTITY");
            h.addProperty("kind", BuiltInRegistries.ENTITY_TYPE.getKey(ent.getType()).getPath());
            h.addProperty("id", ent.getId());
            h.addProperty("dist", round2(ehr.getLocation().distanceTo(playerPos)));
            return h;
        }
        if (hr instanceof BlockHitResult bhr) {
            BlockPos bp = bhr.getBlockPos();
            Level world = ctx.world();
            BlockState bs = world != null ? world.getBlockState(bp) : null;
            h.addProperty("type", "BLOCK");
            h.addProperty("x", bp.getX());
            h.addProperty("y", bp.getY());
            h.addProperty("z", bp.getZ());
            h.addProperty("face", bhr.getDirection().name());
            if (bs != null) {
                h.addProperty("block", BuiltInRegistries.BLOCK.getKey(bs.getBlock()).getPath());
            }
            return h;
        }
        h.addProperty("type", hr.getType().name());
        return h;
    }

    private static String buildInputString(Baritone baritone, IPlayerContext ctx, LocalPlayer p) {
        Map<Input, Boolean> resolved = baritone.getInputOverrideHandler().getLastResolvedInputs();
        var opts = ctx.minecraft() != null ? ctx.minecraft().options : null;
        boolean w = Boolean.TRUE.equals(resolved.get(Input.MOVE_FORWARD)) || (opts != null && opts.keyUp.isDown());
        boolean a = Boolean.TRUE.equals(resolved.get(Input.MOVE_LEFT)) || (opts != null && opts.keyLeft.isDown());
        boolean s = Boolean.TRUE.equals(resolved.get(Input.MOVE_BACK)) || (opts != null && opts.keyDown.isDown());
        boolean d = Boolean.TRUE.equals(resolved.get(Input.MOVE_RIGHT)) || (opts != null && opts.keyRight.isDown());
        boolean j = Boolean.TRUE.equals(resolved.get(Input.JUMP)) || (opts != null && opts.keyJump.isDown());
        boolean k = Boolean.TRUE.equals(resolved.get(Input.SNEAK)) || (opts != null && opts.keyShift.isDown());
        boolean sp = Boolean.TRUE.equals(resolved.get(Input.SPRINT)) || p.isSprinting();
        boolean lmb = Boolean.TRUE.equals(resolved.get(Input.CLICK_LEFT)) || (opts != null && opts.keyAttack.isDown());
        boolean rmb = Boolean.TRUE.equals(resolved.get(Input.CLICK_RIGHT)) || (opts != null && opts.keyUse.isDown());
        StringBuilder sb = new StringBuilder(16);
        if (w) sb.append('W');
        if (a) sb.append('A');
        if (s) sb.append('S');
        if (d) sb.append('D');
        if (j) sb.append(",JMP");
        if (k) sb.append(",SNK");
        if (sp) sb.append(",SPR");
        if (lmb) sb.append(",LMB");
        if (rmb) sb.append(",RMB");
        return sb.toString();
    }

    private void enqueueDiag(JsonObject obj) {
        if (!recording || diagWriter == null) {
            return;
        }
        if (queue.offer(new WriteEntry(StreamKind.DIAG, obj.toString()))) {
            diagLinesWritten++;
        } else {
            droppedLines++;
        }
    }

    private void ensureWriterThreadLocked() {
        if (writerThread != null && writerThread.isAlive()) {
            return;
        }
        writerThread = new Thread(this::writerLoop, "Pathadol-BedwarsBlackboxWriter");
        writerThread.setDaemon(true);
        writerThread.start();
    }

    private void writerLoop() {
        long lastFlush = System.currentTimeMillis();
        while (recording || !queue.isEmpty()) {
            try {
                WriteEntry entry = queue.poll(250L, TimeUnit.MILLISECONDS);
                if (entry != null) {
                    writeEntry(entry);
                    int drained = 0;
                    while (drained < 128 && (entry = queue.poll()) != null) {
                        writeEntry(entry);
                        drained++;
                    }
                }
                long now = System.currentTimeMillis();
                if (now - lastFlush >= 500L) {
                    flushWriters();
                    lastFlush = now;
                }
            } catch (InterruptedException ignored) {
                if (!recording) {
                    break;
                }
            }
        }
        WriteEntry rem;
        while ((rem = queue.poll()) != null) {
            writeEntry(rem);
        }
        flushWriters();
    }

    private void writeEntry(WriteEntry entry) {
        try {
            if (entry.kind == StreamKind.DIAG && diagWriter != null) {
                diagWriter.write(entry.line);
                diagWriter.newLine();
            } else if (entry.kind == StreamKind.DATASET && datasetWriter != null) {
                datasetWriter.write(entry.line);
                datasetWriter.newLine();
            } else if (entry.kind == StreamKind.FLUSH) {
                flushWriters();
            }
        } catch (IOException ignored) {
        }
    }

    private void flushWriters() {
        try {
            if (diagWriter != null) diagWriter.flush();
        } catch (IOException ignored) {
        }
        try {
            if (datasetWriter != null) datasetWriter.flush();
        } catch (IOException ignored) {
        }
    }

    private void closeWritersQuietly() {
        Thread wt = this.writerThread;
        if (wt != null && wt.isAlive()) {
            try {
                wt.join(400L);
            } catch (InterruptedException ignored) {
            }
        }
        try {
            if (diagWriter != null) diagWriter.close();
        } catch (IOException ignored) {
        }
        try {
            if (datasetWriter != null) datasetWriter.close();
        } catch (IOException ignored) {
        }
        diagWriter = null;
        datasetWriter = null;
    }

    private static JsonArray roundVec3(Vec3 v) {
        JsonArray a = new JsonArray();
        a.add(round2(v.x));
        a.add(round2(v.y));
        a.add(round2(v.z));
        return a;
    }

    private static JsonArray roundVec34(Vec3 v) {
        JsonArray a = new JsonArray();
        a.add(round4(v.x));
        a.add(round4(v.y));
        a.add(round4(v.z));
        return a;
    }

    private static double round2(double d) {
        return Math.round(d * 100.0) / 100.0;
    }

    private static double round4(double d) {
        return Math.round(d * 10000.0) / 10000.0;
    }

    private static String sanitizeTag(String tag) {
        if (tag == null || tag.isBlank()) {
            return "arena";
        }
        String cleaned = tag.replaceAll("[^A-Za-z0-9_-]", "_");
        return cleaned.isEmpty() ? "arena" : cleaned;
    }
}
