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
import baritone.api.Settings;
import baritone.api.process.ICombatProcess;
import baritone.api.process.PathingCommand;
import baritone.api.process.PathingCommandType;
import baritone.bedwars.BedwarsArenaMemory;
import baritone.bedwars.BedwarsMapCore.BwsRuleset;
import baritone.bedwars.BedwarsProcess;
import baritone.brain.BrainActionSpace.AntiChatterCommitter;
import baritone.brain.BrainActionSpace.BrainDecision;
import baritone.brain.BrainActionSpace.CombatMode;
import baritone.brain.BrainActionSpace.Maneuver;
import baritone.brain.BrainActionSpace.StyleSliders;
import baritone.brain.BrainSchema.ObsSnapshot;
import baritone.process.CombatProcess;
import baritone.utils.BaritoneProcessHelper;
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
 * <p>Every tick when active, it extracts a 72-float {@link ObsSnapshot}, queries either a loaded
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

    private boolean wasPlayerDead = false;
    private long tickCounter = 0L;
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

    public synchronized void cancel() {
        boolean wasActiveControl = (mode != BrainMode.OFF);
        mode = BrainMode.OFF;
        Baritone.settings().brainMode.value = "off";
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

    @Override
    public void onLostControl() {
        if (mode != BrainMode.OFF) {
            cancel();
        }
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
        LocalPlayer p = ctx.player();
        if (p == null || ctx.world() == null) {
            return new PathingCommand(null, PathingCommandType.DEFER);
        }
        tickCounter++;

        boolean deadNow = !p.isAlive() || p.getHealth() <= 0.0f || p.isSpectator();
        boolean deathEdge = deadNow && !wasPlayerDead;
        if (deadNow != wasPlayerDead && !deadNow && neuralEngine != null) {
            neuralEngine.resetHiddenState();
            committer.reset();
        }
        wasPlayerDead = deadNow;

        ObsSnapshot snap = BrainContextExtractor.capture(baritone, committer.getCommittedManeuver(), committer.getTicksHeld());
        if (snap == null) {
            return new PathingCommand(null, PathingCommandType.DEFER);
        }

        float[] obsVec = BrainSchema.encodeObservation(snap);
        boolean[] mask = BrainActionSpace.computeValidManeuverMask(snap);
        BrainDecision teacherDecision = RuleTeacherPolicy.decide(snap, mask);

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
            gymBridge.exchangeStep(obsVec, mask, reward, deathEdge, tickCounter);
            return new PathingCommand(null, PathingCommandType.DEFER);
        }

        BrainDecision raw = selectRawDecision(obsVec, mask, teacherDecision);
        boolean subTaskCompleted = !baritone.getBedwarsProcess().isActive() && !baritone.getCombatProcess().isActive();
        int minHold = Math.max(4, Baritone.settings().brainMinHoldTicks.value);
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
            applySlidersToSettings(committed.sliders());
            if (baritone.getCombatProcess() instanceof CombatProcess cp) {
                cp.setBrainCombatMode(committed.combatMode());
            }
            dispatchManeuver(committed.maneuver());

            float reward = recorder.recordStep(snap, obsVec, mask, committed, teacherDecision.maneuver(), false, resolveSourceTag());
            gymBridge.exchangeStep(obsVec, mask, reward, false, tickCounter);
        }

        // Always DEFER execution downward so CombatProcess (4.0) or BedwarsProcess (3.5) acts cleanly as the muscle.
        return new PathingCommand(null, PathingCommandType.DEFER);
    }

    private BrainDecision selectRawDecision(float[] obsVec, boolean[] mask, BrainDecision teacherDecision) {
        if (mode == BrainMode.GYM) {
            BrainDecision gymDecision = gymBridge.exchangeStep(null, mask, 0.0f, false, tickCounter);
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

        if (maneuver == Maneuver.ENGAGE_THREAT_COMBAT) {
            if (bws.isActive()) {
                bws.cancel();
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
        BedwarsProcess.TaskMode bm = baritone.getBedwarsProcess().getMode();
        return switch (bm) {
            case RUSH -> Maneuver.RUSH_NEAREST_BED;
            case DIAMONDS -> Maneuver.FARM_DIAMONDS;
            case EMERALDS -> Maneuver.FARM_EMERALDS;
            case HOME_GEN -> Maneuver.HOLD_HOME_GEN;
            case STASH -> Maneuver.STASH_VALUABLES;
            case RECALL -> Maneuver.TACTICAL_VOID_RECALL;
            case BUY -> Maneuver.SHOP_BUY_ESSENTIALS;
            case DEFEND -> Maneuver.DEFEND_HOME_BED;
            case BLOCK_IN -> Maneuver.RUSH_NEAREST_BED;
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
        s.humanBridgeSkill.value = sl.bridgeSpeed() >= 0.72f ? "sweaty" : (sl.bridgeSpeed() >= 0.42f ? "fast" : "safe");
        s.allowParkour.value = sl.riskAppetite() >= 0.62f;
        s.allowParkourPlace.value = sl.riskAppetite() >= 0.78f;
        s.maxFallHeightNoWater.value = sl.voidCaution() >= 0.65f ? 3 : (sl.voidCaution() >= 0.35f ? 4 : 6);
        s.humanAimSpeed.value = Math.max(0.75, Math.min(1.5, savedAimSpeed * (0.85 + 0.35 * sl.combatAggression())));
        s.humanCps.value = Math.max(9.0, Math.min(17.0, savedCps * (0.85 + 0.30 * sl.combatAggression())));
    }
}
