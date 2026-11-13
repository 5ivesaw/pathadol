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

package baritone.human;

import baritone.Baritone;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.Helper;
import baritone.api.utils.IPlayerContext;
import baritone.api.utils.Rotation;
import baritone.api.utils.input.Input;
import baritone.human.MirrorArenaCatalog.StageDef;
import baritone.pathing.movement.MovementHelper;
import baritone.process.CombatProcess;
import baritone.utils.BlockStateInterface;
import baritone.utils.InputOverrideHandler;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Automated Proving Ground & Bot-vs-Player Mirror Learner.
 * <p>
 * Supports:
 * <ul>
 *   <li>{@link Mode#MIRROR}: Bot performs each of 10 extreme stages first, then resets the exact arena,
 *       hands control to the player ("YOUR TURN"), records the player's natural kinematics, updates
 *       {@link HumanStyleLearner} dynamically across tasks, and prints Bot vs You comparisons.</li>
 *   <li>{@link Mode#FAST}: Runs all 10 extreme stages hands-free back-to-back for rapid regression testing.</li>
 * </ul>
 */
public final class AutoTestRunner {

    private static final DateTimeFormatter TS_FMT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    public enum Mode {
        MIRROR,
        FAST
    }

    private enum Phase {
        IDLE,
        BOT_PREP,
        BOT_WAIT,
        BOT_RUN,
        HUMAN_PREP,
        HUMAN_WAIT,
        HUMAN_RUN,
        CLEANUP
    }

    public record StageVerdict(String name, boolean passed, String details) {}

    private static volatile Mode pendingAutoStartMode = null;
    private static volatile int pendingStartStage = 1;
    private static volatile int pendingEndStage = 60;

    private final Baritone baritone;
    private final HumanLabRecorder labRecorder;
    private final List<StageDef> allStages = MirrorArenaCatalog.buildCatalog();
    private List<StageDef> catalog = allStages;
    private final List<StageVerdict> verdicts = new ArrayList<>();
    private final List<String> mirrorComparisons = new ArrayList<>();
    private final List<String> detailedNarratives = new ArrayList<>();
    private final List<HumanStyleLearner.MotionSample> botSamples = new ArrayList<>();
    private final List<HumanStyleLearner.MotionSample> humanSamples = new ArrayList<>();
    private final List<StageNarrativeAnalyzer.TickFrame> botFrames = new ArrayList<>();
    private final List<StageNarrativeAnalyzer.TickFrame> humanFrames = new ArrayList<>();
    private StageNarrativeAnalyzer.RunNarrative lastBotNarrative;

    private Mode mode = Mode.MIRROR;
    private Phase phase = Phase.IDLE;
    private int stageIdx = 0;
    private int phaseTicks = 0;
    private int worldSettleTicks = 0;
    private BetterBlockPos basePos;
    private Vec3 originPos;
    private GameType savedGameType;
    private Difficulty savedDifficulty;

    // Saved settings snapshot
    private boolean savedHumanMode;
    private String savedProfile;
    private String savedBridgeSkill;
    private String savedCombatMode;
    private boolean savedAllowBreak;
    private boolean savedAllowPlace;
    private boolean savedAllowSprint;
    private boolean savedAllowParkour;
    private boolean savedAllowParkourPlace;

    // Bot per-stage telemetry
    private float prevYaw, prevPitch;
    private boolean hasPrevRot;
    private double prevPosX, prevPosY, prevPosZ;
    private boolean hasPrevPos;
    private double aimSpeedSum;
    private int aimSamples;
    private int snapsOver38;
    private int nonZeroMouseSteps;
    private int gcdMatches;
    private double minObservedY;
    private int blocksBroken;
    private int lastSlot;
    private int trap3Violations;

    // Human mirror turn state
    private boolean humanStartedMoving;
    private int humanActiveTicks;
    private Vec3 humanStartVec;
    private int trialsLearnedInRun;

    public AutoTestRunner(Baritone baritone, HumanLabRecorder labRecorder) {
        this.baritone = baritone;
        this.labRecorder = labRecorder;
    }

    public static void scheduleAutoStart(Mode mode) {
        scheduleAutoStart(mode, 1, 60);
    }

    public static void scheduleAutoStart(Mode mode, int startStage, int endStage) {
        pendingStartStage = Math.max(1, Math.min(60, startStage));
        pendingEndStage = Math.max(pendingStartStage, Math.min(60, endStage));
        pendingAutoStartMode = mode;
    }

    public static Mode getPendingAutoStart() {
        return pendingAutoStartMode;
    }

    public boolean isRunning() {
        return phase != Phase.IDLE;
    }

    public boolean isBotPhase() {
        return phase == Phase.BOT_PREP || phase == Phase.BOT_WAIT || phase == Phase.BOT_RUN;
    }

    public boolean isAwaitingHumanInput() {
        return phase == Phase.HUMAN_RUN || phase == Phase.HUMAN_WAIT || phase == Phase.HUMAN_PREP;
    }

    public boolean start(IPlayerContext ctx) {
        return start(ctx, Mode.MIRROR, 1, allStages.size());
    }

    public boolean start(IPlayerContext ctx, Mode runMode) {
        return start(ctx, runMode, 1, allStages.size());
    }

    public boolean start(IPlayerContext ctx, Mode runMode, int startStage, int endStage) {
        if (phase != Phase.IDLE || ctx.player() == null || ctx.world() == null) {
            return false;
        }

        int from = Math.max(1, Math.min(allStages.size(), startStage));
        int to = Math.max(from, Math.min(allStages.size(), endStage));
        List<StageDef> selected = new ArrayList<>();
        for (StageDef s : allStages) {
            if (s.index() >= from && s.index() <= to) {
                selected.add(s);
            }
        }
        if (selected.isEmpty()) {
            return false;
        }
        this.catalog = selected;

        this.mode = runMode;
        this.verdicts.clear();
        this.mirrorComparisons.clear();
        this.detailedNarratives.clear();
        this.lastBotNarrative = null;
        this.trialsLearnedInRun = 0;
        this.stageIdx = 0;

        this.originPos = ctx.player().position();
        BetterBlockPos feet = ctx.playerFeet();
        int minAllowed = ctx.world().dimensionType().minY() + 12;
        int maxAllowed = ctx.world().dimensionType().minY() + ctx.world().dimensionType().height() - 28;
        int arenaY = Math.max(minAllowed, Math.min(maxAllowed, Math.max(feet.y + 38, 195)));
        this.basePos = new BetterBlockPos(feet.x, arenaY, feet.z);
        this.savedGameType = ctx.playerController().getGameType();
        this.savedDifficulty = ctx.world().getDifficulty();

        saveSettings();
        applyTestSettings();

        if (savedDifficulty == Difficulty.PEACEFUL) {
            sendServerCmd(ctx, "difficulty normal");
        }

        try {
            labRecorder.start(runMode == Mode.MIRROR ? "mirror_test" : "autotest_fast");
        } catch (IOException ignored) {
        }

        Helper.HELPER.logDirect(runMode == Mode.MIRROR
                ? String.format(Locale.ROOT, "Mirror Proving Ground started (%d extreme stages, profile=%s). Bot runs each stage, then YOUR TURN! Run #lab stop to abort.",
                catalog.size(), Baritone.settings().humanProfile.value)
                : String.format(Locale.ROOT, "Fast AutoTest started (%d extreme stages, hands-free, profile=%s). Run #lab auto stop to abort.",
                catalog.size(), Baritone.settings().humanProfile.value));

        transitionTo(Phase.BOT_PREP);
        return true;
    }

    public void finishHumanTurn(IPlayerContext ctx) {
        if (phase != Phase.HUMAN_RUN) {
            Helper.HELPER.logDirect("No active YOUR TURN trial right now.");
            return;
        }
        completeHumanTrial(ctx, "manual-done");
    }

    public void retryHumanTurn(IPlayerContext ctx) {
        if (phase != Phase.HUMAN_RUN && phase != Phase.HUMAN_WAIT) {
            Helper.HELPER.logDirect("No active YOUR TURN trial to retry.");
            return;
        }
        Helper.HELPER.logDirect("Resetting stage for retry...");
        transitionTo(Phase.HUMAN_PREP);
    }

    public void skipHumanTurn(IPlayerContext ctx) {
        if (phase != Phase.HUMAN_RUN && phase != Phase.HUMAN_WAIT && phase != Phase.HUMAN_PREP) {
            Helper.HELPER.logDirect("No active YOUR TURN trial to skip.");
            return;
        }
        StageDef cur = catalog.get(stageIdx);
        mirrorComparisons.add(cur.name() + ": human skipped");
        Helper.HELPER.logDirect("Skipped YOUR TURN for " + cur.name());
        advanceToNextStage();
    }

    public void abort(IPlayerContext ctx, String reason) {
        if (phase == Phase.IDLE) {
            return;
        }
        stopBotActions();
        if (ctx.player() != null && ctx.world() != null) {
            cleanupArena(ctx);
        }
        restoreSettings(false);
        labRecorder.stop();
        phase = Phase.IDLE;
        Helper.HELPER.logDirect("Proving Ground stopped: " + reason);
    }

    public void onPostTick(IPlayerContext ctx, boolean rightClickFired, boolean brokeBlock) {
        onPostTick(ctx, rightClickFired, brokeBlock, HumanTelemetryHooks.TickSnapshot.EMPTY);
    }

    public void onPostTick(
            IPlayerContext ctx,
            boolean rightClickFired,
            boolean brokeBlock,
            HumanTelemetryHooks.TickSnapshot tickSnap
    ) {
        if (ctx.player() == null || ctx.world() == null) {
            return;
        }
        if (ctx.minecraft() != null && ctx.minecraft().isPaused()) {
            return;
        }
        HumanTelemetryHooks.TickSnapshot snap = tickSnap == null ? HumanTelemetryHooks.TickSnapshot.EMPTY : tickSnap;

        // Auto-start pending Menu launch once singleplayer connection & chunks settle
        if (phase == Phase.IDLE) {
            Mode pending = pendingAutoStartMode;
            if (pending != null && ctx.player().connection != null) {
                worldSettleTicks++;
                if (worldSettleTicks >= 30) {
                    int sStart = pendingStartStage;
                    int sEnd = pendingEndStage;
                    pendingAutoStartMode = null;
                    worldSettleTicks = 0;
                    start(ctx, pending, sStart, sEnd);
                }
            } else {
                worldSettleTicks = 0;
            }
            return;
        }

        if (phase == Phase.CLEANUP) {
            finishSuite(ctx);
            return;
        }
        if (stageIdx < 0 || stageIdx >= catalog.size()) {
            transitionTo(Phase.CLEANUP);
            finishSuite(ctx);
            return;
        }

        phaseTicks++;
        StageDef stage = catalog.get(stageIdx);

        switch (phase) {
            case BOT_PREP -> {
                stopBotActions();
                // Use learned profile dynamically on later Mirror stages once we've learned user kinematics
                if (mode == Mode.MIRROR && baritone.getStyleLearner().hasLearnedData()
                        && ("pro".equalsIgnoreCase(Baritone.settings().humanProfile.value)
                        || "sweaty".equalsIgnoreCase(Baritone.settings().humanProfile.value)
                        || "learned".equalsIgnoreCase(Baritone.settings().humanProfile.value))) {
                    Baritone.settings().humanProfile.value = "learned";
                }
                Helper.HELPER.logDirect(String.format(Locale.ROOT,
                        "[Proving Ground %d/%d | %s] BOT: %s (%s)%s",
                        stageIdx + 1, catalog.size(), stage.category(), stage.name(),
                        Baritone.settings().humanProfile.value,
                        stageIdx == 0 ? " — press [F7] for Diorama Cam" : ""));
                MirrorArenaCatalog.clearArenaBox(ctx, basePos);
                stage.builder().accept(ctx, basePos);
                sendOverlay(ctx, String.format(Locale.ROOT, "[%d/%d BOT] Building %s...", stageIdx + 1, catalog.size(), stage.name()));
                transitionTo(Phase.BOT_WAIT);
            }
            case BOT_WAIT -> {
                BetterBlockPos checkPos = add(basePos, stage.readyCheckRel());
                BetterBlockPos startWorld = add(basePos, stage.startRel());
                if (!checkArenaReady(ctx, stage, checkPos, startWorld)) {
                    return;
                }
                MirrorArenaCatalog.applyClassic18AttackSpeed(ctx, stage.classic18Combat());
                resetBotTelemetry(ctx);
                botSamples.clear();
                botFrames.clear();
                lastBotNarrative = null;
                stage.botStarter().accept(baritone, basePos);
                transitionTo(Phase.BOT_RUN);
            }
            case BOT_RUN -> {
                sampleBotAimAndY(ctx);
                StageNarrativeAnalyzer.TickFrame frame = captureCurrentFrame(ctx, stage, true, phaseTicks, rightClickFired, brokeBlock, snap);
                botFrames.add(frame);
                botSamples.add(toMotionSample(frame));
                if (brokeBlock) {
                    blocksBroken++;
                }
                int slot = ctx.player().getInventory().getSelectedSlot();
                InputOverrideHandler io = baritone.getInputOverrideHandler();
                if (slot != lastSlot && (io.getClickModel().isOverHolding() || io.isInputForcedDown(Input.CLICK_LEFT))) {
                    trap3Violations++;
                }
                lastSlot = slot;

                boolean reachedOrCleared = checkStageComplete(ctx, stage, phaseTicks);
                int minSafeFloorY = Math.min(stage.startRel().y, stage.goalRel().y);
                boolean fell = stage.parkourBridgeStage() && ctx.player().getY() < basePos.y + minSafeFloorY - 2.0;
                boolean timedOut = phaseTicks >= stage.botMaxTicks();

                if ((phaseTicks & 3) == 0) {
                    sendOverlay(ctx, String.format(Locale.ROOT, "[%d/%d BOT] %s | %.1fs",
                            stageIdx + 1, catalog.size(), stage.name(), phaseTicks / 20.0));
                }

                if (reachedOrCleared || fell || timedOut) {
                    evaluateAndRecordBotStage(ctx, stage, reachedOrCleared, fell);
                    stopBotActions();
                    if (mode == Mode.MIRROR) {
                        transitionTo(Phase.HUMAN_PREP);
                    } else {
                        advanceToNextStage();
                    }
                }
            }
            case HUMAN_PREP -> {
                stopBotActions();
                MirrorArenaCatalog.clearArenaBox(ctx, basePos);
                stage.builder().accept(ctx, basePos);
                transitionTo(Phase.HUMAN_WAIT);
            }
            case HUMAN_WAIT -> {
                BetterBlockPos checkPos = add(basePos, stage.readyCheckRel());
                BetterBlockPos startWorld = add(basePos, stage.startRel());
                if (!checkArenaReady(ctx, stage, checkPos, startWorld)) {
                    return;
                }
                MirrorArenaCatalog.applyClassic18AttackSpeed(ctx, stage.classic18Combat());
                humanSamples.clear();
                humanFrames.clear();
                humanStartedMoving = false;
                humanActiveTicks = 0;
                humanStartVec = ctx.player().position();
                announceYourTurn(ctx, stage);
                transitionTo(Phase.HUMAN_RUN);
            }
            case HUMAN_RUN -> {
                var kp = ctx.player().input.keyPresses;
                boolean anyInput = kp.forward() || kp.backward() || kp.left() || kp.right()
                        || kp.jump() || kp.shift()
                        || ctx.minecraft().options.keyAttack.isDown()
                        || ctx.minecraft().options.keyUse.isDown()
                        || snap.anyLeftImpulse()
                        || snap.anyRightImpulse();
                double movedFromStart = humanStartVec == null ? 0.0 : ctx.player().position().distanceTo(humanStartVec);

                if (!humanStartedMoving && (anyInput || movedFromStart > 0.35)) {
                    humanStartedMoving = true;
                }

                if (humanStartedMoving) {
                    humanActiveTicks++;
                    StageNarrativeAnalyzer.TickFrame frame = captureCurrentFrame(ctx, stage, false, humanActiveTicks, rightClickFired, brokeBlock, snap);
                    humanFrames.add(frame);
                    humanSamples.add(toMotionSample(frame));
                }

                // Forgiving void rescue during player turn so player can complete/retry cleanly
                if (humanActiveTicks > 10 && ctx.player().getY() < basePos.y - 5.0) {
                    BetterBlockPos startWorld = add(basePos, stage.startRel());
                    sendServerCmd(ctx, String.format(Locale.ROOT, "tp @s %.2f %.2f %.2f %.1f %.1f",
                            startWorld.x + 0.5, (double) startWorld.y, startWorld.z + 0.5, stage.startYaw(), stage.startPitch()));
                    Helper.HELPER.logDirect("Void rescue -> returned to start pad. Keep going or click [Retry] / [Skip]!");
                }

                if ((phaseTicks & 1) == 0) {
                    updateHumanActionBar(ctx, stage);
                }

                boolean finished = humanActiveTicks >= 10 && checkStageComplete(ctx, stage, humanActiveTicks);
                boolean timedOut = humanStartedMoving
                        ? humanActiveTicks >= stage.humanMaxTicks()
                        : phaseTicks >= stage.humanMaxTicks() + 400;

                if (finished) {
                    completeHumanTrial(ctx, "completed");
                } else if (timedOut) {
                    completeHumanTrial(ctx, "timeout");
                }
            }
            case CLEANUP -> finishSuite(ctx);
            case IDLE -> {}
        }
    }

    private void evaluateAndRecordBotStage(IPlayerContext ctx, StageDef stage, boolean reachedOrCleared, boolean fell) {
        HumanStyleLearner.TrialSummary botSum = HumanStyleLearner.summarize(botSamples);
        StageNarrativeAnalyzer.RunNarrative botNar = StageNarrativeAnalyzer.analyzeRun(
                "BOT",
                stage.combatStage(),
                stage.parkourBridgeStage(),
                centerVec(basePos, stage.startRel()),
                stage.combatStage() ? null : centerVec(basePos, stage.goalRel()),
                botFrames
        );
        this.lastBotNarrative = botNar;

        double meanAim = aimSamples > 0 ? aimSpeedSum / aimSamples : 0.0;
        double gcdRate = nonZeroMouseSteps == 0 ? 100.0 : (100.0 * gcdMatches / nonZeroMouseSteps);

        boolean pass;
        String details;
        if (stage.combatStage()) {
            CombatProcess cp = (CombatProcess) baritone.getCombatProcess();
            int swings = cp.getSwingsRecorded();
            double meanStr = cp.getMeanAttackStrengthAtSwing();
            int zombiesLeft = countNearbyZombies(ctx, 34.0);
            int minSafeY = Math.min(stage.startRel().y, stage.goalRel().y);
            boolean safeY = !stage.parkourBridgeStage() || minObservedY >= basePos.y + minSafeY - 2.0;
            boolean okMode = stage.classic18Combat() ? swings >= 4 : (swings >= 2 && meanStr >= 0.80);
            pass = safeY && okMode && zombiesLeft <= 1 && (reachedOrCleared || swings >= 3);
            details = String.format(Locale.ROOT,
                    "%.1fs | left=%d | swings=%d (str=%.2f) | aimP90=%.1f deg/t | GCD=%.1f%% | safeY=%s",
                    phaseTicks / 20.0, zombiesLeft, swings, meanStr, botSum.p90AimDegPerTick(), gcdRate, safeY);
        } else if (stage.index() == 3) {
            pass = reachedOrCleared && blocksBroken >= 4 && trap3Violations == 0 && gcdRate >= 95.0;
            details = String.format(Locale.ROOT,
                    "reached=%s (%.1fs) | breaks=%d | trap3=%d | GCD=%.1f%% | aimP90=%.1f deg/t",
                    reachedOrCleared, phaseTicks / 20.0, blocksBroken, trap3Violations, gcdRate, botSum.p90AimDegPerTick());
        } else if (stage.bedrockEnclosed() && !stage.parkourBridgeStage() && "PUZZLE".equals(stage.category())) {
            pass = reachedOrCleared && trap3Violations == 0 && gcdRate >= 94.0;
            details = String.format(Locale.ROOT,
                    "reached=%s (%.1fs) | breaks=%d | trap3=%d | GCD=%.1f%% | aimP90=%.1f deg/t",
                    reachedOrCleared, phaseTicks / 20.0, blocksBroken, trap3Violations, gcdRate, botSum.p90AimDegPerTick());
        } else if (stage.parkourBridgeStage()) {
            int minSafeY = Math.min(stage.startRel().y, stage.goalRel().y);
            boolean noFalls = !fell && minObservedY >= basePos.y + minSafeY - 2.0;
            pass = reachedOrCleared && noFalls && gcdRate >= 94.0;
            details = String.format(Locale.ROOT,
                    "reached=%s (%.1fs) | minY=%.1f | GCD=%.1f%% | snaps>38=%d | aimP90=%.1f deg/t",
                    reachedOrCleared, phaseTicks / 20.0, minObservedY, gcdRate, snapsOver38, botSum.p90AimDegPerTick());
        } else {
            pass = reachedOrCleared && gcdRate >= 95.0 && snapsOver38 == 0 && meanAim > 0.05;
            details = String.format(Locale.ROOT,
                    "reached=%s (%.1fs) | GCD=%.1f%% | snaps>38=%d | meanAim=%.2f p90=%.1f deg/t | scrapes=%d",
                    reachedOrCleared, phaseTicks / 20.0, gcdRate, snapsOver38, meanAim,
                    botSum.p90AimDegPerTick(), botNar.stats().scrapeCount());
        }
        recordVerdict(stage.name(), pass, details);

        detailedNarratives.add("\n[Stage " + stage.index() + "] " + stage.name() + " — " + botNar.headline());
        for (String scene : botNar.sceneLines()) {
            detailedNarratives.add("  " + scene);
            if (scene.startsWith("Wall ") || scene.startsWith("Path Hesitation") || scene.startsWith("Camera Texture")
                    || scene.startsWith("Airborne") || scene.startsWith("Bridge ")) {
                Helper.HELPER.logDirect("  " + scene);
            }
        }
    }

    private void completeHumanTrial(IPlayerContext ctx, String reason) {
        StageDef stage = catalog.get(stageIdx);
        HumanStyleLearner.TrialSummary botSum = HumanStyleLearner.summarize(botSamples);
        StageNarrativeAnalyzer.RunNarrative youNar = StageNarrativeAnalyzer.analyzeRun(
                "YOU",
                stage.combatStage(),
                stage.parkourBridgeStage(),
                centerVec(basePos, stage.startRel()),
                stage.combatStage() ? null : centerVec(basePos, stage.goalRel()),
                humanFrames
        );
        HumanStyleLearner.TrialContext trialCtx = new HumanStyleLearner.TrialContext(
                stage.name(),
                stage.category(),
                stage.combatStage(),
                stage.parkourBridgeStage(),
                !"timeout".equals(reason)
        );
        HumanStyleLearner.LearnUpdate learnUpdate = baritone.getStyleLearner().learnFromTrial(humanSamples, trialCtx);

        detailedNarratives.add("  [" + youNar.headline() + "]");
        for (String scene : youNar.sceneLines()) {
            detailedNarratives.add("    " + scene);
        }

        if (learnUpdate.updated()) {
            trialsLearnedInRun++;
            HumanStyleLearner.TrialSummary youSum = learnUpdate.humanSummary();
            String cmp = String.format(Locale.ROOT,
                    "%s (%s): Bot %.1fs vs You %.1fs | Aim P90: %.1f vs %.1f deg/t | MoveLook: %.1f vs %.1f deg | CPS: %.1f vs %.1f",
                    stage.name(), reason,
                    botSum.durationSec(), youSum.durationSec(),
                    botSum.p90AimDegPerTick(), youSum.p90AimDegPerTick(),
                    botSum.meanMoveLookDiffDeg(), youSum.meanMoveLookDiffDeg(),
                    botSum.peakClickRate(), youSum.peakClickRate());
            mirrorComparisons.add(cmp);
            Helper.HELPER.logDirect(cmp);
            if (lastBotNarrative != null) {
                List<String> prose = StageNarrativeAnalyzer.buildBotVsYouCommentary(stage.name(), lastBotNarrative, youNar);
                detailedNarratives.addAll(prose);
                for (String p : prose) {
                    Helper.HELPER.logDirect("  " + p);
                }
            }
            Helper.HELPER.logDirect(learnUpdate.summaryLine());
        } else {
            mirrorComparisons.add(stage.name() + ": " + learnUpdate.summaryLine());
            Helper.HELPER.logDirect(learnUpdate.summaryLine());
        }
        advanceToNextStage();
    }

    private void advanceToNextStage() {
        stageIdx++;
        if (stageIdx >= catalog.size()) {
            transitionTo(Phase.CLEANUP);
        } else {
            transitionTo(Phase.BOT_PREP);
        }
    }

    private void finishSuite(IPlayerContext ctx) {
        stopBotActions();
        cleanupArena(ctx);
        boolean activateLearned = mode == Mode.MIRROR && trialsLearnedInRun > 0;
        restoreSettings(activateLearned);

        Path jsonl = labRecorder.stop();
        String chatReport = buildScorecardReport(jsonl, false);
        String fullReport = buildScorecardReport(jsonl, true);
        Path savedReport = writeReportFile(fullReport);
        Helper.HELPER.logDirect(chatReport);
        if (activateLearned) {
            Helper.HELPER.logDirect("Active profile switched to 'learned' (trained from "
                    + HumanStyleLearner.activeLearnedState().trialsLearned + " total trials).");
        }
        if (savedReport != null) {
            Helper.HELPER.logDirect("Proving Ground report saved: " + savedReport);
        }
        sendOverlay(ctx, activateLearned
                ? "Proving Ground complete! Learned profile active."
                : "Proving Ground complete!");
        phase = Phase.IDLE;
    }

    private void announceYourTurn(IPlayerContext ctx, StageDef stage) {
        sendServerCmd(ctx, "title @s times 5 50 10");
        sendServerCmd(ctx, String.format(Locale.ROOT,
                "title @s title {\"text\":\"YOUR TURN [%d/%d]\",\"color\":\"gold\",\"bold\":true}",
                stageIdx + 1, catalog.size()));
        sendServerCmd(ctx, String.format(Locale.ROOT,
                "title @s subtitle {\"text\":\"%s\",\"color\":\"yellow\"}",
                escapeJson(stage.hint())));

        MutableComponent controls = Component.literal(String.format(Locale.ROOT,
                "[YOUR TURN %d/%d] %s — %s\n",
                stageIdx + 1, catalog.size(), stage.name(), stage.hint()))
                .withStyle(ChatFormatting.GOLD)
                .append(clickPill("[Done / Next]", "#lab next", ChatFormatting.GREEN))
                .append(Component.literal(" "))
                .append(clickPill("[Retry]", "#lab retry", ChatFormatting.YELLOW))
                .append(Component.literal(" "))
                .append(clickPill("[Skip]", "#lab skip", ChatFormatting.GRAY))
                .append(Component.literal(" "))
                .append(clickPill("[Stop]", "#lab stop", ChatFormatting.RED));
        Helper.HELPER.logDirect(controls);
    }

    private void updateHumanActionBar(IPlayerContext ctx, StageDef stage) {
        String waitTag = humanStartedMoving ? "" : " (move/click to start timer)";
        double remainSec = Math.max(0.0, (stage.humanMaxTicks() - humanActiveTicks) / 20.0);
        String targetStat;
        if (stage.combatStage()) {
            targetStat = "Mobs left: " + countNearbyZombies(ctx, 34.0);
        } else {
            BetterBlockPos goal = add(basePos, stage.goalRel());
            double dist = horizontalDist(ctx, goal.x + 0.5, goal.z + 0.5);
            targetStat = String.format(Locale.ROOT, "Emerald Pad: %.1fm", dist);
        }
        sendOverlay(ctx, String.format(Locale.ROOT,
                "YOUR TURN %d/%d | %s | %.0fs left%s",
                stageIdx + 1, catalog.size(), targetStat, remainSec, waitTag));
    }

    private boolean checkStageComplete(IPlayerContext ctx, StageDef stage, int elapsedTicks) {
        if (stage.combatStage()) {
            return elapsedTicks >= 20 && countNearbyZombies(ctx, 34.0) == 0;
        }
        BetterBlockPos goal = add(basePos, stage.goalRel());
        return horizontalDist(ctx, goal.x + 0.5, goal.z + 0.5) <= 2.35
                && Math.abs(ctx.player().getY() - goal.y) <= 1.35;
    }

    private StageNarrativeAnalyzer.TickFrame captureCurrentFrame(
            IPlayerContext ctx,
            StageDef stage,
            boolean botActive,
            int tick,
            boolean rightClickFired,
            boolean brokeBlock,
            HumanTelemetryHooks.TickSnapshot snap
    ) {
        var p = ctx.player();
        Vec3 v = p.getDeltaMovement();
        boolean fwd, back, left, right, jump, sneak, sprint, atk, use;
        if (botActive) {
            var m = baritone.getInputOverrideHandler().getLastResolvedInputs();
            fwd = Boolean.TRUE.equals(m.get(Input.MOVE_FORWARD));
            back = Boolean.TRUE.equals(m.get(Input.MOVE_BACK));
            left = Boolean.TRUE.equals(m.get(Input.MOVE_LEFT));
            right = Boolean.TRUE.equals(m.get(Input.MOVE_RIGHT));
            jump = Boolean.TRUE.equals(m.get(Input.JUMP));
            sneak = Boolean.TRUE.equals(m.get(Input.SNEAK));
            sprint = Boolean.TRUE.equals(m.get(Input.SPRINT)) || p.isSprinting();
            atk = baritone.getInputOverrideHandler().isInputForcedDown(Input.CLICK_LEFT);
            use = baritone.getInputOverrideHandler().isInputForcedDown(Input.CLICK_RIGHT);
        } else {
            var kp = p.input.keyPresses;
            fwd = kp.forward();
            back = kp.backward();
            left = kp.left();
            right = kp.right();
            jump = kp.jump();
            sneak = kp.shift();
            sprint = kp.sprint() || p.isSprinting();
            atk = snap.lmbPhysicallyDown() || ctx.minecraft().options.keyAttack.isDown()
                    || ctx.minecraft().mouseHandler.isLeftPressed() || snap.anyLeftImpulse();
            use = snap.rmbPhysicallyDown() || ctx.minecraft().options.keyUse.isDown()
                    || ctx.minecraft().mouseHandler.isRightPressed() || snap.anyRightImpulse();
        }
        BlockStateInterface bsi = new BlockStateInterface(ctx);
        BlockPos below = BlockPos.containing(p.getX(), p.getY() - 0.15, p.getZ());
        boolean onIce = HumanLabRecorder.isIceBlock(bsi.get0(below.getX(), below.getY(), below.getZ()).getBlock());
        int floorY = (int) Math.floor(p.getY());
        double voidEdgeDist = computeLocalVoidEdgeDist(bsi, (int) Math.floor(p.getX()), floorY, (int) Math.floor(p.getZ()));
        double distToGoal = stage == null || stage.combatStage()
                ? -1.0
                : Math.hypot(p.getX() - (basePos.x + stage.goalRel().x + 0.5), p.getZ() - (basePos.z + stage.goalRel().z + 0.5));
        int lmbPulses = Math.max(snap.lmbPresses(), Math.max(snap.startAttackCalls(), snap.attackEntityCalls()));
        int rmbPulses = Math.max(snap.rmbPresses(), Math.max(snap.startUseItemCalls(), Math.max(snap.useItemOnCalls(), snap.useItemCalls())));
        if (rmbPulses == 0 && rightClickFired) {
            rmbPulses = 1;
        }
        String wallHint = StageNarrativeAnalyzer.probeTouchingWallBlock(ctx);
        return new StageNarrativeAnalyzer.TickFrame(
                tick,
                p.getX(), p.getY(), p.getZ(),
                v.x, v.y, v.z,
                p.getYRot(), p.getXRot(),
                p.onGround(), p.horizontalCollision, sprint, sneak,
                fwd, back, left, right, jump,
                atk, use,
                rightClickFired || snap.useItemOnConsumed() > 0,
                brokeBlock || snap.blocksDestroyed() > 0,
                p.getInventory().getSelectedSlot(),
                wallHint,
                lmbPulses,
                rmbPulses,
                p.hurtTime,
                onIce,
                voidEdgeDist,
                distToGoal
        );
    }

    private static double computeLocalVoidEdgeDist(BlockStateInterface bsi, int cx, int floorY, int cz) {
        boolean centerHasFloor = false;
        for (int dy = 1; dy <= 3; dy++) {
            if (MovementHelper.canWalkOn(bsi, cx, floorY - dy, cz)) {
                centerHasFloor = true;
                break;
            }
        }
        if (!centerHasFloor) {
            return 0.0;
        }
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) {
                    continue;
                }
                boolean ok = false;
                for (int dy = 1; dy <= 3; dy++) {
                    if (MovementHelper.canWalkOn(bsi, cx + dx, floorY - dy, cz + dz)) {
                        ok = true;
                        break;
                    }
                }
                if (!ok) {
                    return Math.hypot(dx, dz);
                }
            }
        }
        return 4.0;
    }

    private static HumanStyleLearner.MotionSample toMotionSample(StageNarrativeAnalyzer.TickFrame f) {
        return new HumanStyleLearner.MotionSample(
                f.yaw(), f.pitch(),
                f.vx(), f.vz(),
                f.onGround(), f.sprinting(), f.sneaking(),
                f.fwd(), f.back(), f.left(), f.right(), f.jump(),
                f.leftDown(), f.rightDown(),
                f.slot(),
                f.x(), f.y(), f.z(),
                f.vy(),
                f.horizontalCollision(),
                f.hurtTime(),
                f.lmbPulses(),
                f.rmbPulses(),
                f.onIce(),
                f.voidEdgeDist()
        );
    }

    private static Vec3 centerVec(BetterBlockPos base, BetterBlockPos rel) {
        return new Vec3(base.x + rel.x + 0.5, base.y + rel.y, base.z + rel.z + 0.5);
    }

    private void transitionTo(Phase next) {
        this.phase = next;
        this.phaseTicks = 0;
    }

    public boolean shouldRecordTelemetryTick() {
        return phase == Phase.IDLE || phase == Phase.BOT_RUN || phase == Phase.HUMAN_RUN;
    }

    public boolean isBotActivePhase() {
        return phase == Phase.BOT_RUN;
    }

    public String getTelemetryPhaseTag() {
        return phase == Phase.IDLE ? "FREEPLAY" : phase.name();
    }

    public int getActiveStageId() {
        if (phase == Phase.IDLE || stageIdx < 0 || stageIdx >= catalog.size()) {
            return -1;
        }
        return catalog.get(stageIdx).index();
    }

    public String getActiveStageName() {
        if (phase == Phase.IDLE || stageIdx < 0 || stageIdx >= catalog.size()) {
            return null;
        }
        return catalog.get(stageIdx).name();
    }

    public String getActiveStageCategory() {
        if (phase == Phase.IDLE || stageIdx < 0 || stageIdx >= catalog.size()) {
            return null;
        }
        return catalog.get(stageIdx).category();
    }

    private boolean checkArenaReady(IPlayerContext ctx, StageDef stage, BetterBlockPos readyBlock, BetterBlockPos expectedStart) {
        if (phaseTicks < 10) {
            return false;
        }
        double dx = ctx.player().getX() - (expectedStart.x + 0.5);
        double dz = ctx.player().getZ() - (expectedStart.z + 0.5);
        boolean playerMoved = Math.abs(ctx.player().getY() - expectedStart.y) <= 2.2
                && Math.hypot(dx, dz) <= 1.25;
        boolean blockLoaded = !BlockStateInterface.get(ctx, readyBlock).isAir();
        if (playerMoved && blockLoaded) {
            return true;
        }
        if (phaseTicks == 45 && stage != null) {
            MirrorArenaCatalog.clearArenaBox(ctx, basePos);
            stage.builder().accept(ctx, basePos);
        }
        if (phaseTicks >= 95) {
            abort(ctx, "Failed to build sky test arena (ensure Singleplayer Cheats are enabled or you have OP).");
        }
        return false;
    }

    private void resetBotTelemetry(IPlayerContext ctx) {
        hasPrevRot = false;
        hasPrevPos = false;
        aimSpeedSum = 0.0;
        aimSamples = 0;
        snapsOver38 = 0;
        nonZeroMouseSteps = 0;
        gcdMatches = 0;
        minObservedY = ctx.player().getY();
        blocksBroken = 0;
        lastSlot = ctx.player().getInventory().getSelectedSlot();
        trap3Violations = 0;
    }

    private void sampleBotAimAndY(IPlayerContext ctx) {
        double px = ctx.player().getX();
        double py = ctx.player().getY();
        double pz = ctx.player().getZ();
        if (hasPrevPos && Math.hypot(Math.hypot(px - prevPosX, pz - prevPosZ), py - prevPosY) > 2.5) {
            // External server teleport occurred on this tick; do not attribute warp angle to bot aim
            hasPrevRot = false;
        }
        prevPosX = px;
        prevPosY = py;
        prevPosZ = pz;
        hasPrevPos = true;

        minObservedY = Math.min(minObservedY, py);
        float yaw = ctx.player().getYRot();
        float pitch = ctx.player().getXRot();
        if (hasPrevRot) {
            float dYaw = Rotation.normalizeYaw(yaw - prevYaw);
            float dPitch = pitch - prevPitch;
            double speed = Math.hypot(dYaw, dPitch);
            aimSpeedSum += speed;
            aimSamples++;
            if (speed > 38.0) {
                snapsOver38++;
            }
            float step = MouseModel.mouseStepAngle(ctx.minecraft().options.sensitivity().get());
            if (Math.abs(dYaw) > 1e-4f && step > 0f) {
                nonZeroMouseSteps++;
                float rem = Math.abs(dYaw / step - Math.round(dYaw / step));
                if (rem < 0.02f) {
                    gcdMatches++;
                }
            }
        }
        prevYaw = yaw;
        prevPitch = pitch;
        hasPrevRot = true;
    }

    private void recordVerdict(String name, boolean passed, String details) {
        StageVerdict v = new StageVerdict(name, passed, details);
        verdicts.add(v);
        Helper.HELPER.logDirect(String.format(Locale.ROOT, "[%s] %s — %s",
                passed ? "PASS" : "FAIL", name, details));
    }

    private void stopBotActions() {
        baritone.getCustomGoalProcess().onLostControl();
        baritone.getCombatProcess().cancel();
        baritone.getPathingBehavior().cancelEverything();
        baritone.getInputOverrideHandler().releaseBotInputs();
    }

    private void cleanupArena(IPlayerContext ctx) {
        if (basePos != null) {
            MirrorArenaCatalog.clearArenaBox(ctx, basePos);
        }
        if (originPos != null) {
            sendServerCmd(ctx, String.format(Locale.ROOT, "tp @s %.2f %.2f %.2f", originPos.x, originPos.y, originPos.z));
        }
        if (savedGameType != null) {
            sendServerCmd(ctx, "gamemode " + savedGameType.name().toLowerCase(Locale.ROOT) + " @s");
        }
        if (savedDifficulty != null && savedDifficulty == Difficulty.PEACEFUL) {
            sendServerCmd(ctx, "difficulty peaceful");
        }
        MirrorArenaCatalog.applyClassic18AttackSpeed(ctx, false);
        sendServerCmd(ctx, "effect clear @s");
    }

    private void saveSettings() {
        savedHumanMode = Baritone.settings().humanMode.value;
        savedProfile = Baritone.settings().humanProfile.value;
        savedBridgeSkill = Baritone.settings().humanBridgeSkill.value;
        savedCombatMode = Baritone.settings().humanCombatMode.value;
        savedAllowBreak = Baritone.settings().allowBreak.value;
        savedAllowPlace = Baritone.settings().allowPlace.value;
        savedAllowSprint = Baritone.settings().allowSprint.value;
        savedAllowParkour = Baritone.settings().allowParkour.value;
        savedAllowParkourPlace = Baritone.settings().allowParkourPlace.value;
    }

    private void applyTestSettings() {
        Baritone.settings().humanMode.value = true;
        String cur = Baritone.settings().humanProfile.value;
        if ("learned".equalsIgnoreCase(cur) && !baritone.getStyleLearner().hasLearnedData()) {
            Baritone.settings().humanProfile.value = "pro";
        } else if (cur == null || cur.isBlank()) {
            Baritone.settings().humanProfile.value = "pro";
        }
        Baritone.settings().allowParkourPlace.value = false;
    }

    private void restoreSettings(boolean keepLearnedProfile) {
        Baritone.settings().humanMode.value = savedHumanMode;
        Baritone.settings().humanProfile.value = keepLearnedProfile ? "learned" : savedProfile;
        Baritone.settings().humanBridgeSkill.value = savedBridgeSkill;
        Baritone.settings().humanCombatMode.value = savedCombatMode;
        Baritone.settings().allowBreak.value = savedAllowBreak;
        Baritone.settings().allowPlace.value = savedAllowPlace;
        Baritone.settings().allowSprint.value = savedAllowSprint;
        Baritone.settings().allowParkour.value = savedAllowParkour;
        Baritone.settings().allowParkourPlace.value = savedAllowParkourPlace;
    }

    private String buildScorecardReport(Path jsonlFile, boolean includeSceneNarratives) {
        StringBuilder sb = new StringBuilder(formatScorecard(verdicts, jsonlFile));
        if (!mirrorComparisons.isEmpty()) {
            sb.append("\n--- Mirror Bot-vs-Player Telemetry ---");
            for (String line : mirrorComparisons) {
                sb.append('\n').append(line);
            }
        }
        if (baritone.getStyleLearner().hasLearnedData()) {
            HumanStyleLearner.LearnedState st = HumanStyleLearner.activeLearnedState();
            sb.append(String.format(Locale.ROOT,
                    "\nLearned Style (%d trials): aimSpeed=%.2f Fitts(a=%.2f,b=%.2f) cps=%.1f(%s) cone=%.0f lookahead=%.1f",
                    st.trialsLearned, st.aimSpeed, st.fittsBaseTicks, st.fittsSlopeTicks,
                    st.cpsMean, st.clickStyle, st.sprintConeDeg, st.steeringLookahead));
        }
        if (includeSceneNarratives && !detailedNarratives.isEmpty()) {
            sb.append("\n\n=== Scene-by-Scene Replay Narrative ===");
            for (String line : detailedNarratives) {
                sb.append('\n').append(line);
            }
        }
        return sb.toString();
    }

    public static String formatScorecard(List<StageVerdict> results, Path jsonlFile) {
        long passedCount = results.stream().filter(StageVerdict::passed).count();
        StringBuilder sb = new StringBuilder();
        sb.append(String.format(Locale.ROOT,
                "=== Pathadol AutoTest Scorecard (%d/%d PASSED) ===",
                passedCount, results.size()));
        for (StageVerdict v : results) {
            sb.append('\n')
                    .append(v.passed() ? "[PASS] " : "[FAIL] ")
                    .append(v.name())
                    .append(": ")
                    .append(v.details());
        }
        if (jsonlFile != null) {
            sb.append("\nTelemetry JSONL: ").append(jsonlFile);
        }
        return sb.toString();
    }

    private Path writeReportFile(String report) {
        try {
            Path labDir = baritone.getDirectory().resolve("lab");
            Files.createDirectories(labDir);
            Path out = labDir.resolve("autotest-" + TS_FMT.format(LocalDateTime.now()) + ".txt");
            Files.writeString(out, report + "\n", StandardCharsets.UTF_8);
            return out;
        } catch (IOException e) {
            return null;
        }
    }

    private static Component clickPill(String label, String command, ChatFormatting color) {
        return Component.literal(label).withStyle(style -> style
                .withColor(color)
                .withBold(true)
                .withClickEvent(new ClickEvent.RunCommand(command)));
    }

    private static void sendOverlay(IPlayerContext ctx, String text) {
        if (ctx.player() != null) {
            ctx.player().sendOverlayMessage(Component.literal(text).withStyle(ChatFormatting.GOLD));
        }
    }

    private static BetterBlockPos add(BetterBlockPos a, BetterBlockPos b) {
        return new BetterBlockPos(a.x + b.x, a.y + b.y, a.z + b.z);
    }

    private static int countNearbyZombies(IPlayerContext ctx, double radius) {
        return (int) ctx.entitiesStream()
                .filter(e -> e instanceof Mob m && m.isAlive() && !m.isRemoved())
                .filter(e -> e.distanceTo(ctx.player()) <= radius)
                .count();
    }

    private static double horizontalDist(IPlayerContext ctx, double tx, double tz) {
        return Math.hypot(ctx.player().getX() - tx, ctx.player().getZ() - tz);
    }

    private static String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static void sendServerCmd(IPlayerContext ctx, String commandWithoutSlash) {
        if (ctx.player() != null && ctx.player().connection != null) {
            ctx.player().connection.sendCommand(commandWithoutSlash);
        }
    }
}
