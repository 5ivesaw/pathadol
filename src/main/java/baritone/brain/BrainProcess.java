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

package baritone.brain;

import baritone.Baritone;
import baritone.utils.EdgeSafety;
import baritone.api.Settings;
import baritone.api.process.ICombatProcess;
import baritone.api.process.PathingCommand;
import baritone.api.process.PathingCommandType;
import baritone.bedwars.BedwarsArenaMemory;
import baritone.bedwars.BedwarsMapCore.BwsRuleset;
import baritone.bedwars.BedwarsMatchState;
import baritone.bedwars.BedwarsProcess;
import baritone.bedwars.BedwarsProcess.TaskMode;
import baritone.bedwars.BedwarsShopPlanner.ShopPreset;
import baritone.bedwars.BedwarsTacticalHelper.InventorySummary;
import baritone.bedwars.BedwarsTickContext;
import baritone.brain.BrainActionSpace.AntiChatterCommitter;
import baritone.brain.BrainActionSpace.BrainDecision;
import baritone.brain.BrainActionSpace.CombatMode;
import baritone.brain.BrainActionSpace.Maneuver;
import baritone.brain.BrainActionSpace.StyleSliders;
import baritone.brain.BrainSchema.ObsSnapshot;
import baritone.human.CraftingHelper;
import baritone.process.CombatProcess;
import baritone.utils.BaritoneProcessHelper;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Neuro-Symbolic "Brain + Muscle" supervisory coordinator ({@code priority = 8.5}, temporary)
 * sitting above {@link CombatProcess} ({@code 4.0}) and {@link BedwarsProcess} ({@code 3.5}).
 *
 * <p>Every tick when active, it extracts a versioned {@link ObsSnapshot}, queries either a loaded
 * zero-dependency {@code .pbrain} neural policy, the localhost {@link BrainGymBridge}, or the
 * Day-1 {@link RuleTeacherPolicy}, smooths decisions via {@link AntiChatterCommitter}, shapes
 * humanization sliders cleanly on {@link Settings}, and delegates execution to Pathadol's combat
 * and Bedwars muscle processes without ever running raw pathfinding math itself.
 */
public final class BrainProcess extends BaritoneProcessHelper {

    public enum BrainMode {
        OFF,
        AUTO,
        NEURAL,
        TEACHER,
        GYM;

        public static BrainMode fromString(String raw) {
            if (raw == null || raw.isBlank()) {
                return AUTO;
            }
            return switch (raw.trim().toLowerCase(Locale.ROOT)) {
                case "off", "stop", "disable" -> OFF;
                case "neural", "nn", "model", "pbrain" -> NEURAL;
                case "teacher", "script", "rule", "rules" -> TEACHER;
                case "gym", "bridge", "python" -> GYM;
                default -> AUTO;
            };
        }
    }

    private final AntiChatterCommitter committer = new AntiChatterCommitter();
    private final BrainTrajectoryRecorder recorder;
    private final BrainGymBridge gymBridge = new BrainGymBridge();
    private final baritone.bedwars.BedwarsAutoQueueController autoQueue = new baritone.bedwars.BedwarsAutoQueueController();

    private volatile BrainMode mode = BrainMode.OFF;
    private volatile NeuralPolicyEngine neuralEngine = null;
    private volatile BrainDecision lastDecision = null;
    private volatile boolean passiveOnlyWarningSent = false;

    private boolean settingsSaved = false;
    private String savedBridgeSkill;
    private boolean savedAllowParkour;
    private boolean savedAllowParkourPlace;
    private int savedMaxFallNoWater;
    private double savedAimSpeed;
    private double savedCps;
    private String savedCombatMode;

    private Maneuver lastAnnouncedManeuver = null;
    private Maneuver lastDispatchedManeuver = null;
    private Maneuver lastLoggedCommittedManeuver = null;
    private int perchHoldTicks = 0;
    private boolean wasPlayerDead = false;
    private long tickCounter = 0L;
    private long lastMacroEvalTick = Long.MIN_VALUE;
    private float lastGymReward = 0.0f;
    private StyleSliders manualSliderOverride = null;

    public BrainProcess(Baritone baritone) {
        super(baritone);
        this.recorder = new BrainTrajectoryRecorder(baritone.getDirectory());
        tryLoadDefaultModelQuietly();
    }

    public Path getModelsDir() {
        return baritone.getDirectory().resolve("brain");
    }

    public Path getDefaultModelPath() {
        return getModelsDir().resolve("active.pbrain");
    }

    public BrainTrajectoryRecorder getRecorder() {
        return recorder;
    }

    public BrainGymBridge getGymBridge() {
        return gymBridge;
    }

    public baritone.bedwars.BedwarsAutoQueueController getAutoQueueController() {
        return autoQueue;
    }

    public synchronized void setAutoRequeue(boolean enabled, String queueMode) {
        autoQueue.setEnabled(enabled, queueMode);
    }

    public BrainMode getMode() {
        return mode;
    }

    public BrainDecision getLastDecision() {
        return lastDecision;
    }

    public synchronized String start(BrainMode targetMode) {
        BrainMode chosen = (targetMode == null || targetMode == BrainMode.OFF) ? BrainMode.AUTO : targetMode;
        if (chosen == BrainMode.AUTO || chosen == BrainMode.NEURAL) {
            tryLoadDefaultModelQuietly();
        }
        BedwarsArenaMemory memory = baritone.getBedwarsArenaMemory();
        if (memory != null && !memory.isBedwarsActive()) {
            memory.applyRuleset(BwsRuleset.OLD_1_8);
        }
        snapshotSettingsIfNeeded();
        committer.reset();
        passiveOnlyWarningSent = false;
        wasPlayerDead = false;
        this.mode = chosen;
        autoQueue.setChatSink(this::logDirect);
        Baritone.settings().brainMode.value = chosen.name().toLowerCase(Locale.ROOT);

        if (chosen == BrainMode.GYM && !gymBridge.isRunning()) {
            try {
                gymBridge.start(Baritone.settings().brainGymPort.value);
            } catch (IOException e) {
                logDirect("Brain Gym bridge failed on port " + Baritone.settings().brainGymPort.value + ": " + e.getMessage());
            }
        }
        if (Baritone.settings().brainAutoRecord.value && !recorder.isRecording()) {
            try {
                recorder.start("brain-" + chosen.name().toLowerCase(Locale.ROOT));
            } catch (IOException ignored) {
            }
        }
        String policyLabel = resolveActivePolicyLabel();
        return "Brain active [" + chosen.name() + "] using " + policyLabel + ".";
    }

    public synchronized String reloadModel(String optionalNameOrPath) {
        Path candidate;
        if (optionalNameOrPath == null || optionalNameOrPath.isBlank()) {
            candidate = getDefaultModelPath();
        } else {
            Path p = Path.of(optionalNameOrPath.trim());
            candidate = p.isAbsolute() ? p : getModelsDir().resolve(optionalNameOrPath.trim());
        }
        if (!Files.isRegularFile(candidate)) {
            return "No .pbrain file found at " + candidate;
        }
        try {
            NeuralPolicyEngine engine = NeuralPolicyEngine.loadFromFile(candidate);
            this.neuralEngine = engine;
            this.passiveOnlyWarningSent = false;
            return "Loaded neural brain '" + engine.getModelName() + "' from " + candidate.getFileName();
        } catch (Exception e) {
            return "Failed to load .pbrain (" + candidate.getFileName() + "): " + e.getMessage();
        }
    }

    public synchronized String setManualSliders(StyleSliders override) {
        this.manualSliderOverride = override;
        return override == null
                ? "Brain sliders returned to policy control."
                : String.format(Locale.ROOT, "Brain slider override -> risk=%.2f speed=%.2f caution=%.2f aggr=%.2f",
                        override.riskAppetite(), override.bridgeSpeed(), override.voidCaution(), override.combatAggression());
    }

    private void tryLoadDefaultModelQuietly() {
        Path p = getDefaultModelPath();
        if (Files.isRegularFile(p)) {
            try {
                this.neuralEngine = NeuralPolicyEngine.loadFromFile(p);
            } catch (Exception ignored) {
            }
        }
    }

    public String getStatusSummary() {
        String policy = resolveActivePolicyLabel();
        BrainDecision d = this.lastDecision;
        String decStr = d == null
                ? "none"
                : String.format(Locale.ROOT, "%s | %s | conf=%.2f val=%.2f",
                        d.maneuver().name(), d.combatMode().name(), d.confidence(), d.valueEstimate());
        String modelLat = neuralEngine != null ? (neuralEngine.getLastInferenceMicros() + "us") : "n/a";
        return String.format(Locale.ROOT,
                "mode=%s policy=%s (%s) decision=[%s] rec=%s (%d steps, R=%.2f) gym=%s",
                mode.name(), policy, modelLat, decStr,
                recorder.isRecording() ? "ON" : "OFF", recorder.getStepCount(), recorder.getCumulativeReward(),
                gymBridge.isRunning() ? gymBridge.getStatus() : "OFF");
    }

    private String resolveActivePolicyLabel() {
        return switch (mode) {
            case OFF -> "IDLE";
            case TEACHER -> "RuleTeacherPolicy";
            case NEURAL -> neuralEngine != null ? ("NN:" + neuralEngine.getModelName()) : "RuleTeacherFallback";
            case GYM -> gymBridge.isClientConnected() ? "PythonGymBridge" : "GymWaiting(TeacherFallback)";
            case AUTO -> neuralEngine != null ? ("NN:" + neuralEngine.getModelName()) : "RuleTeacherPolicy";
        };
    }

    @Override
    public boolean isActive() {
        return mode != BrainMode.OFF || recorder.isRecording() || gymBridge.isRunning();
    }

    /**
     * Returns {@code true} ONLY when the Brain is actively issuing autonomous macro control
     * (never during passive {@code #brain record} or passive telemetry observation).
     */
    public boolean isControlling() {
        return mode != BrainMode.OFF;
    }

    public Maneuver getCommittedManeuver() {
        return committer.getCommittedManeuver();
    }

    public synchronized void cancel() {
        boolean wasActiveControl = (mode != BrainMode.OFF);
        mode = BrainMode.OFF;
        Baritone.settings().brainMode.value = "off";
        autoQueue.setEnabled(false, null);
        committer.reset();
        lastDecision = null;
        if (neuralEngine != null) {
            neuralEngine.resetHiddenState();
        }
        restoreSettingsIfNeeded();
        if (wasActiveControl) {
            if (baritone.getCombatProcess() instanceof CombatProcess cp) {
                cp.setBrainCombatMode(CombatMode.BALANCED);
            }
            if (baritone.getCombatProcess().isActive()) {
                baritone.getCombatProcess().cancel();
            }
            if (baritone.getBedwarsProcess().isActive()) {
                baritone.getBedwarsProcess().cancel();
            }
        }
        if (recorder.isRecording() && Baritone.settings().brainAutoRecord.value) {
            recorder.stop();
        }
    }

    /**
     * Autonomous mode is persistent: world loads, server transfers, deaths, cancelEverything() and higher-priority
     * processes must NEVER switch it off. Only {@code #bws off|stop} / {@code #bws auto stop} call {@link #cancel()}.
     */
    @Override
    public void onLostControl() {
        if (mode != BrainMode.OFF) {
            committer.reset();
        }
    }

    @Override
    public boolean isTemporary() {
        // While persistent auto mode is on we must survive PathingControlManager.cancelEverything()
        // (fired on every world change) without tripping its "stayed active after being cancelled" guard.
        return mode != BrainMode.OFF;
    }

    @Override
    public double priority() {
        // Below SpeedrunProcess (10.0) so Speedrun overrides when explicitly started;
        // above InventoryPauser (5.1), CombatProcess (4.0), and BedwarsProcess (3.5).
        return 8.5;
    }

    @Override
    public String displayName0() {
        if (mode == BrainMode.OFF) {
            return recorder.isRecording() ? "Brain[REC]" : "Brain[GYM]";
        }
        BrainDecision d = this.lastDecision;
        return d == null ? ("Brain[" + mode.name() + "]") : ("Brain:" + d.maneuver().name());
    }

    @Override
    public PathingCommand onTick(boolean calcFailed, boolean isSafeToCancel) {
        long tickStartNs = System.nanoTime();
        try {
            return onTickInternal(calcFailed, isSafeToCancel);
        } finally {
            long elapsedNs = System.nanoTime() - tickStartNs;
            if (elapsedNs > 1_000_000L && baritone.getBedwarsBlackboxRecorder() != null
                    && baritone.getBedwarsBlackboxRecorder().isRecording()) {
                baritone.getBedwarsBlackboxRecorder().recordEvent(
                        "PERF_WARN",
                        String.format(Locale.ROOT, "BrainProcess.onTick=%.2fms mode=%s maneuver=%s",
                                elapsedNs / 1_000_000.0, mode.name(),
                                lastDecision != null ? lastDecision.maneuver().name() : "NONE")
                );
            }
        }
    }

    private PathingCommand onTickInternal(boolean calcFailed, boolean isSafeToCancel) {
        LocalPlayer p = ctx.player();
        if (p == null || ctx.world() == null) {
            return new PathingCommand(null, PathingCommandType.DEFER);
        }
        tickCounter++;

        // Autonomous requeue / lobby NPC queue / post-lobby auto-scan when #bws auto is active
        if (mode != BrainMode.OFF && autoQueue.isEnabled()) {
            PathingCommand queueCmd = autoQueue.tick(baritone, ctx);
            if (queueCmd != null) {
                committer.reset();
                return queueCmd;
            }
        }

        boolean inWaitingLobby = baritone.getBedwarsArenaMemory() != null && baritone.getBedwarsArenaMemory().isInWaitingLobby();
        if (inWaitingLobby) {
            if (mode == BrainMode.OFF) {
                return new PathingCommand(null, PathingCommandType.DEFER);
            }
            baritone.getInputOverrideHandler().clearAllKeys();
            if (baritone.getCombatProcess().isActive()) {
                baritone.getCombatProcess().cancel();
            }
            if (baritone.getBedwarsProcess().isActive()) {
                baritone.getBedwarsProcess().cancel("waiting_lobby");
            }
            baritone.getPathingBehavior().cancelEverything();
            committer.reset();
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }
        // Global airborne void safety (20 Hz reflex): runs regardless of which sub-process is active
        if (mode != BrainMode.OFF && Baritone.settings().bedwarsMode.value
                && !p.onGround() && p.isAlive() && !p.isSpectator() && !p.isInWater()
                && baritone.getBedwarsProcess().stepReactiveVoidClutch(false)) {
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }
        if (mode != BrainMode.OFF && baritone.getBedwarsArenaMemory() != null
                && baritone.getBedwarsArenaMemory().isSetbackCircuitBreakerActive()) {
            baritone.getPathingBehavior().cancelEverything();
            baritone.getInputOverrideHandler().clearAllKeys();
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }
        if (mode != BrainMode.OFF) {
            Minecraft mc = ctx.minecraft();
            TaskMode bwsMode = baritone.getBedwarsProcess().getMode();
            boolean containerOpen = mc != null && mc.gui != null && mc.gui.screen() instanceof AbstractContainerScreen<?>;
            boolean botOwnedContainer = containerOpen && (bwsMode == TaskMode.BUY || bwsMode == TaskMode.STASH);
            boolean serverContainerMenu = containerOpen && p.containerMenu != null && p.containerMenu != p.inventoryMenu;
            if (serverContainerMenu && !botOwnedContainer) {
                // Close leftover shop/chest UI cleanly instead of soft-locking on REQUEST_PAUSE
                CraftingHelper.safeCloseContainer(ctx);
                baritone.getInputOverrideHandler().clearAllKeys();
            } else if (mc != null && (((mc.gui != null && mc.gui.screen() != null) && !botOwnedContainer) || mc.isPaused())) {
                baritone.getInputOverrideHandler().clearAllKeys();
                return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
            }
        }

        boolean deadNow = !p.isAlive() || p.getHealth() <= 0.0f || p.isSpectator();
        boolean deathEdge = deadNow && !wasPlayerDead;
        if (deadNow != wasPlayerDead && !deadNow && neuralEngine != null) {
            neuralEngine.resetHiddenState();
            committer.reset();
        }
        wasPlayerDead = deadNow;

        boolean subTaskCompleted = !baritone.getBedwarsProcess().isActive() && !baritone.getCombatProcess().isActive();
        // Run steady-state macro scoring at 5 Hz (every 4 ticks), promoting immediately to 20 Hz on urgent events
        if (mode != BrainMode.OFF && mode != BrainMode.GYM && !recorder.isRecording()
                && !deadNow && !deathEdge && lastDecision != null && !subTaskCompleted
                && p.hurtTime == 0 && lastMacroEvalTick != Long.MIN_VALUE && tickCounter - lastMacroEvalTick < 4L) {
            boolean closeEnemyNow = false;
            BedwarsArenaMemory mem = baritone.getBedwarsArenaMemory();
            if (mem != null) {
                BedwarsTickContext tc = mem.getTickContext();
                java.util.List<Player> enemies = tc.enemies();
                for (int i = 0, n = enemies.size(); i < n; i++) {
                    if (p.distanceToSqr(enemies.get(i)) <= 256.0) {
                        closeEnemyNow = true;
                        break;
                    }
                }
            }
            boolean trapTriggered = mem != null
                    && mem.getMatchState().wasTrapTriggeredRecently(System.currentTimeMillis(), 3000L);
            if (!closeEnemyNow && !trapTriggered) {
                dispatchManeuver(lastDecision.maneuver());
                return new PathingCommand(null, PathingCommandType.DEFER);
            }
        }
        lastMacroEvalTick = tickCounter;

        ObsSnapshot snap = BrainContextExtractor.capture(baritone, committer.getCommittedManeuver(), committer.getTicksHeld());
        if (snap == null) {
            return new PathingCommand(null, PathingCommandType.DEFER);
        }

        BedwarsMatchState matchState = baritone.getBedwarsArenaMemory() != null
                ? baritone.getBedwarsArenaMemory().getMatchState()
                : null;
        float[] obsVec = BrainSchema.encodeObservation(snap);
        boolean[] mask = BrainActionSpace.computeValidManeuverMask(snap);
        if (baritone.getBedwarsProcess().isShopCooldownBlocking(false)) {
            mask[Maneuver.SHOP_BUY_ESSENTIALS.ordinal()] = false;
        }
        // Match exact item-shop vs upgrade-shop routing used in BedwarsProcess.executeBrainManeuver!
        if (baritone.getBedwarsProcess().isCombatUpgradesShopCooldownBlocking(snap.inventory(), matchState)) {
            mask[Maneuver.SHOP_BUY_COMBAT_UPGRADES.ordinal()] = false;
        }
        if (baritone.getBedwarsProcess().isDefendCooldownBlocking()) {
            mask[Maneuver.DEFEND_HOME_BED.ordinal()] = false;
        }
        if (baritone.getBedwarsProcess().isStashCooldownBlocking()) {
            mask[Maneuver.STASH_VALUABLES.ordinal()] = false;
        }
        BrainDecision teacherDecision = RuleTeacherPolicy.decide(snap, mask, matchState);

        // Passive observation recording / Gym telemetry mode (e.g. while human plays or #bws runs manually)
        if (mode == BrainMode.OFF) {
            Maneuver observedManeuver = inferPassiveObservedManeuver(teacherDecision.maneuver());
            BrainDecision passiveObserved = new BrainDecision(
                    observedManeuver, StyleSliders.BALANCED, CombatMode.BALANCED, 0.0f, 1.0f
            );
            float reward = recorder.recordStep(snap, obsVec, mask, passiveObserved, teacherDecision.maneuver(), deathEdge, "passive");
            gymBridge.exchangeStep(obsVec, mask, reward, deathEdge, tickCounter);
            return new PathingCommand(null, PathingCommandType.DEFER);
        }

        if (deadNow) {
            float reward = recorder.recordStep(snap, obsVec, mask,
                    lastDecision != null ? lastDecision : teacherDecision,
                    teacherDecision.maneuver(), deathEdge, resolveSourceTag());
            lastGymReward = reward;
            gymBridge.exchangeStep(obsVec, mask, reward, deathEdge, tickCounter);
            return new PathingCommand(null, PathingCommandType.DEFER);
        }

        // Single exchangeStep per tick in GYM mode (prevents double gym step)
        BrainDecision raw = selectRawDecision(obsVec, mask, teacherDecision, lastGymReward, false);
        int minHold = Math.max(4, Baritone.settings().brainMinHoldTicks.value);
        Maneuver prevCommitted = committer.getCommittedManeuver();
        BrainDecision committed = committer.filter(raw, snap, mask, minHold, subTaskCompleted);
        if (manualSliderOverride != null && committed != null) {
            committed = new BrainDecision(
                    committed.maneuver(),
                    manualSliderOverride,
                    committed.combatMode(),
                    committed.valueEstimate(),
                    committed.confidence()
            );
        }
        this.lastDecision = committed;

        if (committed != null) {
            if (committed.maneuver() != lastLoggedCommittedManeuver) {
                if (baritone.getBedwarsBlackboxRecorder() != null && baritone.getBedwarsBlackboxRecorder().isRecording()) {
                    baritone.getBedwarsBlackboxRecorder().recordEvent(
                            "MANEUVER_SWITCH",
                            (lastLoggedCommittedManeuver != null ? lastLoggedCommittedManeuver.name() : prevCommitted.name())
                                    + " -> " + committed.maneuver().name()
                                    + String.format(Locale.ROOT, " (val=%.2f td=%.1f wp=%.2f baseThreat=%s)",
                                    committed.valueEstimate(), snap.nearestThreatDist(), snap.fightWinProb(), snap.hasEnemyNearHomeBed())
                    );
                }
                lastLoggedCommittedManeuver = committed.maneuver();
            }

            applySlidersToSettings(committed.sliders());
            if (baritone.getCombatProcess() instanceof CombatProcess cp) {
                cp.setBrainCombatMode(committed.combatMode());
            }
            dispatchManeuver(committed.maneuver());
            if (autoQueue.isEnabled() && committed.maneuver() != lastAnnouncedManeuver) {
                if (autoQueue.announce(describeManeuver(committed.maneuver()))) {
                    lastAnnouncedManeuver = committed.maneuver();
                }
            }
            if (baritone.getBedwarsBlackboxRecorder() != null) {
                JsonObject dm = new JsonObject();
                InventorySummary inv = snap.inventory();
                dm.addProperty("blocks", inv != null ? inv.bridgeBlockCount() : 0);
                dm.addProperty("iron", inv != null ? inv.iron() : 0);
                dm.addProperty("gold", inv != null ? inv.gold() : 0);
                dm.addProperty("dia", inv != null ? inv.diamonds() : 0);
                dm.addProperty("em", inv != null ? inv.emeralds() : 0);
                dm.addProperty("defRatio", Math.round(snap.homeBedDefenseRatio() * 100.0) / 100.0);
                dm.addProperty("livingBeds", snap.livingEnemyBedCount());
                dm.addProperty("threatDist", Math.round(snap.nearestThreatDist() * 10.0) / 10.0);
                dm.addProperty("fightWinProb", Math.round(snap.fightWinProb() * 100.0) / 100.0);
                if (matchState != null) {
                    dm.addProperty("phase", matchState.getNextPhase().name());
                }
                baritone.getBedwarsBlackboxRecorder().recordDecisionReason(
                        "Brain/" + resolveSourceTag(),
                        committed.maneuver().name(),
                        committed.combatMode().name(),
                        dm
                );
            }

            float reward = recorder.recordStep(snap, obsVec, mask, committed, teacherDecision.maneuver(), false, resolveSourceTag());
            lastGymReward = reward;
            if (mode != BrainMode.GYM) {
                gymBridge.exchangeStep(obsVec, mask, reward, false, tickCounter);
            }
        }

        // Always DEFER execution downward so CombatProcess (4.0) or BedwarsProcess (3.5) acts cleanly as the muscle.
        return new PathingCommand(null, PathingCommandType.DEFER);
    }

    private static String describeManeuver(Maneuver m) {
        return switch (m) {
            case HOLD_HOME_GEN -> "Next: guard home generator";
            case SHOP_BUY_ESSENTIALS -> "Next: buying blocks and sword";
            case SHOP_BUY_COMBAT_UPGRADES -> "Next: buying armor/upgrades";
            case DEFEND_HOME_BED -> "Next: defending own bed";
            case RUSH_NEAREST_BED -> "Next: rushing nearest enemy bed";
            case RUSH_WEAKEST_BED -> "Next: rushing weakest enemy bed";
            case FARM_DIAMONDS -> "Next: collecting diamonds";
            case FARM_EMERALDS -> "Next: collecting emeralds";
            case ENGAGE_THREAT_COMBAT -> "Fighting nearby enemy";
            default -> "Next: " + m.name().toLowerCase(Locale.ROOT).replace('_', ' ');
        };
    }

    private BrainDecision selectRawDecision(
            float[] obsVec, boolean[] mask, BrainDecision teacherDecision, float reward, boolean done
    ) {
        if (mode == BrainMode.GYM) {
            BrainDecision gymDecision = gymBridge.exchangeStep(obsVec, mask, reward, done, tickCounter);
            if (gymDecision != null) {
                return enforceMask(gymDecision, mask, teacherDecision);
            }
            return teacherDecision;
        }
        if (mode == BrainMode.NEURAL || (mode == BrainMode.AUTO && neuralEngine != null)) {
            if (neuralEngine != null) {
                return neuralEngine.infer(obsVec, mask);
            }
            if (mode == BrainMode.NEURAL && !passiveOnlyWarningSent) {
                passiveOnlyWarningSent = true;
                logDirect("No .pbrain checkpoint loaded in " + getDefaultModelPath().getFileName() + " — using RuleTeacher fallback.");
            }
        }
        return teacherDecision;
    }

    private static BrainDecision enforceMask(BrainDecision raw, boolean[] mask, BrainDecision fallback) {
        int idx = raw.maneuver().ordinal();
        if (mask != null && idx < mask.length && mask[idx]) {
            return raw;
        }
        return new BrainDecision(fallback.maneuver(), raw.sliders(), raw.combatMode(), raw.valueEstimate(), raw.confidence());
    }

    private void dispatchManeuver(Maneuver maneuver) {
        ICombatProcess combat = baritone.getCombatProcess();
        BedwarsProcess bws = baritone.getBedwarsProcess();
        LocalPlayer me = ctx.player();

        if (combat instanceof CombatProcess cp) {
            cp.setContextualPriorityResolver(bws::resolveCombatPriorityAdjustment);
        }
        // Never abandon an in-progress bridge/rush macro while the hitbox is hanging over void: finish the step first.
        if (maneuver != lastDispatchedManeuver && bws.isActive()
                && EdgeSafety.isPerchedOverVoid(ctx) && perchHoldTicks++ < 12) {
            return;
        }
        perchHoldTicks = 0;
        lastDispatchedManeuver = maneuver;
        if (maneuver == Maneuver.ENGAGE_THREAT_COMBAT) {
            if (bws.isActive()) {
                bws.cancel("brain_switch_combat");
            }
            if (!combat.isActive()) {
                combat.attack(e -> isEnemyBedwarsPlayer(me, e));
            }
            return;
        }

        if (combat.isActive()) {
            combat.cancel();
        }
        bws.executeBrainManeuver(maneuver);
    }

    private Maneuver inferPassiveObservedManeuver(Maneuver fallback) {
        if (baritone.getCombatProcess().isActive()) {
            return Maneuver.ENGAGE_THREAT_COMBAT;
        }
        BedwarsProcess bws = baritone.getBedwarsProcess();
        BedwarsProcess.TaskMode bm = bws.getMode();
        return switch (bm) {
            case RUSH -> bws.isPreferringWeakestBed() ? Maneuver.RUSH_WEAKEST_BED : Maneuver.RUSH_NEAREST_BED;
            case DIAMONDS -> Maneuver.FARM_DIAMONDS;
            case EMERALDS -> Maneuver.FARM_EMERALDS;
            case HOME_GEN -> Maneuver.HOLD_HOME_GEN;
            case STASH -> Maneuver.STASH_VALUABLES;
            case RECALL -> Maneuver.TACTICAL_VOID_RECALL;
            case BUY -> {
                ShopPreset preset = bws.getActiveShopPreset();
                yield (preset == ShopPreset.UPGRADES || preset == ShopPreset.COMBAT)
                        ? Maneuver.SHOP_BUY_COMBAT_UPGRADES
                        : Maneuver.SHOP_BUY_ESSENTIALS;
            }
            case DEFEND -> Maneuver.DEFEND_HOME_BED;
            case BLOCK_IN -> bws.isPreferringWeakestBed() ? Maneuver.RUSH_WEAKEST_BED : Maneuver.RUSH_NEAREST_BED;
            case FIREBALL_JUMP -> Maneuver.FIREBALL_JUMP;
            case RETREAT -> Maneuver.RETREAT_HOME;
            default -> fallback;
        };
    }

    private String resolveSourceTag() {
        if (mode == BrainMode.GYM) return "gym";
        if ((mode == BrainMode.NEURAL || mode == BrainMode.AUTO) && neuralEngine != null) return "neural";
        return "teacher";
    }

    private static boolean isEnemyBedwarsPlayer(LocalPlayer me, Entity e) {
        if (me == null || !(e instanceof Player other) || other == me || !other.isAlive() || other.isSpectator()) {
            return false;
        }
        if (me.isAlliedTo(other) || other.getUUID().version() == 2) {
            return false;
        }
        String clean = BedwarsArenaMemory.stripColors(other.getName().getString()).toUpperCase(Locale.ROOT);
        return !clean.contains("[NPC]") && !clean.contains("SHOP") && !clean.contains("UPGRADE");
    }

    private void snapshotSettingsIfNeeded() {
        if (settingsSaved) {
            return;
        }
        Settings s = Baritone.settings();
        this.savedBridgeSkill = s.humanBridgeSkill.value;
        this.savedAllowParkour = s.allowParkour.value;
        this.savedAllowParkourPlace = s.allowParkourPlace.value;
        this.savedMaxFallNoWater = s.maxFallHeightNoWater.value;
        this.savedAimSpeed = s.humanAimSpeed.value;
        this.savedCps = s.humanCps.value;
        this.savedCombatMode = s.humanCombatMode.value;
        this.settingsSaved = true;
    }

    private void restoreSettingsIfNeeded() {
        if (!settingsSaved) {
            return;
        }
        Settings s = Baritone.settings();
        s.humanBridgeSkill.value = savedBridgeSkill;
        s.allowParkour.value = savedAllowParkour;
        s.allowParkourPlace.value = savedAllowParkourPlace;
        s.maxFallHeightNoWater.value = savedMaxFallNoWater;
        s.humanAimSpeed.value = savedAimSpeed;
        s.humanCps.value = savedCps;
        s.humanCombatMode.value = savedCombatMode;
        this.settingsSaved = false;
    }

    private void applySlidersToSettings(StyleSliders sl) {
        snapshotSettingsIfNeeded();
        Settings s = Baritone.settings();
        s.humanBridgeSkill.value = sl.bridgeSpeed() >= 0.72f ? "sweaty" : (sl.bridgeSpeed() >= 0.28f ? "medium" : "safe");
        s.allowParkour.value = sl.riskAppetite() >= 0.62f;
        s.allowParkourPlace.value = !s.bedwarsMode.value && sl.riskAppetite() >= 0.78f;
        int baseFall = sl.voidCaution() >= 0.65f ? 3 : (sl.voidCaution() >= 0.35f ? 4 : 6);
        s.maxFallHeightNoWater.value = s.bedwarsMode.value ? Math.max(4, baseFall) : baseFall;
        s.humanAimSpeed.value = Math.max(0.75, Math.min(1.5, savedAimSpeed * (0.85 + 0.35 * sl.combatAggression())));
        s.humanCps.value = Math.max(8.0, Math.min(13.5, savedCps * (0.85 + 0.25 * sl.combatAggression())));
    }
}
