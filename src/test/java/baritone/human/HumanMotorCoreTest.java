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

import baritone.api.pathing.movement.IMovement;
import baritone.api.pathing.movement.MovementStatus;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.Rotation;
import baritone.api.utils.RotationUtils;
import baritone.api.utils.input.Input;
import baritone.behavior.InventoryBehavior;
import baritone.behavior.look.ForkableRandom;
import baritone.process.CombatProcess;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.*;

public class HumanMotorCoreTest {

    @Test
    public void testFittsLawMonotonicityAndMinimumJerk() {
        HumanProfile avg = HumanProfile.preset("average");
        int shortTurn = MouseModel.computeFittsTicks(12.0, 8.0, avg);
        int mediumTurn = MouseModel.computeFittsTicks(45.0, 8.0, avg);
        int largeTurn = MouseModel.computeFittsTicks(120.0, 8.0, avg);

        assertTrue("Medium turn must take >= short turn ticks", mediumTurn >= shortTurn);
        assertTrue("Large turn must take > short turn ticks", largeTurn > shortTurn);
        assertTrue("Large turn must take multiple ticks", largeTurn >= 4);

        assertEquals(0.0, MouseModel.minimumJerk(0.0), 1e-9);
        assertEquals(0.5, MouseModel.minimumJerk(0.5), 1e-9);
        assertEquals(1.0, MouseModel.minimumJerk(1.0), 1e-9);

        // Bell-shaped velocity: midpoint step is larger than initial and final steps
        double firstStep = MouseModel.minimumJerk(0.2) - MouseModel.minimumJerk(0.0);
        double midStep = MouseModel.minimumJerk(0.6) - MouseModel.minimumJerk(0.4);
        double lastStep = MouseModel.minimumJerk(1.0) - MouseModel.minimumJerk(0.8);
        assertTrue("Velocity must peak in the middle of the movement", midStep > firstStep);
        assertTrue("Velocity must decelerate at the end of the movement", midStep > lastStep);
    }

    @Test
    public void testMouseModelNoSingleTickSnapAndGcdQuantization() {
        HumanProfile avg = HumanProfile.preset("average");
        MouseModel mouse = new MouseModel(new ForkableRandom(42L));
        double sensitivity = 0.5;
        float gcdStep = MouseModel.mouseStepAngle(sensitivity);

        Rotation current = new Rotation(0.0f, 0.0f);
        Rotation target = new Rotation(90.0f, -18.0f);

        List<Float> speeds = new ArrayList<>();
        for (int t = 0; t < 16; t++) {
            Rotation next = mouse.step(current, target, true, false, avg, sensitivity);
            float dy = next.getYaw() - current.getYaw();
            float dp = next.getPitch() - current.getPitch();
            float stepMag = (float) Math.hypot(dy, dp);
            speeds.add(stepMag);

            // Must never snap the full 90 degrees in a single tick
            assertTrue("Single tick delta must not snap full 90 deg (was " + stepMag + ")", stepMag < 45.0f);

            // Every yaw and pitch step must be an integer multiple of the hardware mouse GCD step
            float yawPixels = dy / gcdStep;
            assertEquals("Yaw step must be GCD quantized", Math.round(yawPixels), yawPixels, 1e-3f);

            current = next;
        }

        // After settling, rotation must be close to the target
        assertTrue("Camera must settle onto target within 16 ticks",
                MouseModel.angularDistance(current, target) < 2.5f);
    }

    @Test
    public void testKeyboardOmnidirectionalStrafeAndHysteresis() {
        HumanProfile avg = HumanProfile.preset("average");
        KeyboardModel kb = new KeyboardModel();

        Map<Input, Boolean> raw = new EnumMap<>(Input.class);
        raw.put(Input.MOVE_FORWARD, true);
        raw.put(Input.SPRINT, true);

        // Travel direction is 0 deg (South), but camera is currently pointing at -90 deg (East).
        // Relative yaw = 0 - (-90) = +90 deg -> player must strafe right (D) and sprint must be suppressed!
        kb.setDesiredMoveYaw(0.0f);
        Map<Input, Boolean> step1 = kb.step(raw, -90.0f, avg);
        assertTrue("Must press MOVE_RIGHT when travel direction is +90 deg right of camera",
                step1.get(Input.MOVE_RIGHT));
        assertFalse("Must suppress SPRINT when camera is 90 deg off travel direction",
                step1.get(Input.SPRINT));

        // Even if camera shifts on the very next tick (tick 2), minKeyHoldTicks=2 prevents 1-tick key flicker
        Map<Input, Boolean> step2 = kb.step(raw, 0.0f, avg);
        assertTrue("Hysteresis minKeyHoldTicks=2 must keep MOVE_RIGHT down on tick 2",
                step2.get(Input.MOVE_RIGHT));

        // On tick 3, minKeyHoldTicks=2 is satisfied, so keys transition cleanly to MOVE_FORWARD + SPRINT
        Map<Input, Boolean> step3 = kb.step(raw, 0.0f, avg);
        assertTrue("Must transition to MOVE_FORWARD once camera aligns with travel direction",
                step3.get(Input.MOVE_FORWARD));
        assertFalse("Must release MOVE_RIGHT once aligned",
                step3.get(Input.MOVE_RIGHT));
        assertTrue("Must allow SPRINT when camera is within sprint cone",
                step3.get(Input.SPRINT));
    }

    @Test
    public void testClickModelGatingAndPostBreakOverHold() {
        ClickModel clicks = new ClickModel(new ForkableRandom(12345L));

        // While crosshair is not yet on target, left-click is held back
        assertFalse("Must gate left-click while camera is mid-turn",
                clicks.stepLeftClick(true, false));

        // Once crosshair arrives, left-click engages
        assertTrue("Must allow left-click when crosshair is on target",
                clicks.stepLeftClick(true, true));

        // When block breaks, over-hold reaction tail keeps LMB pressed for 1..3 ticks even if request stops
        clicks.onBlockBroken();
        assertTrue("Post-break over-hold must be 1..3 ticks",
                clicks.postBreakOverHoldTicks() >= 1 && clicks.postBreakOverHoldTicks() <= 3);
        assertTrue("First tick after break must still hold LMB",
                clicks.stepLeftClick(false, false));
    }

    @Test
    public void testPathSmootherStringPullingAndSweptClearance() {
        // Build a 6-step staircase grid path from (0,64,0) to (4,64,2) in an open flat corridor
        List<BetterBlockPos> staircase = List.of(
                new BetterBlockPos(0, 64, 0),
                new BetterBlockPos(1, 64, 0),
                new BetterBlockPos(1, 64, 1),
                new BetterBlockPos(2, 64, 1),
                new BetterBlockPos(3, 64, 1),
                new BetterBlockPos(3, 64, 2),
                new BetterBlockPos(4, 64, 2)
        );

        // In an unobstructed flat plane, string-pulling collapses the 7 waypoints into a single direct chord
        List<BetterBlockPos> smoothedOpen = PathSmoother.smoothGridPolyline(
                staircase,
                (x, z) -> true,
                PathSmoother.DEFAULT_CLEARANCE_RADIUS
        );
        assertEquals("Unobstructed staircase must collapse to 2 endpoints", 2, smoothedOpen.size());
        assertEquals(new BetterBlockPos(0, 64, 0), smoothedOpen.get(0));
        assertEquals(new BetterBlockPos(4, 64, 2), smoothedOpen.get(1));

        // Now place a wall pillar at (2, 1) blocking the direct chord: string-puller must preserve intermediate corners
        List<BetterBlockPos> smoothedBlocked = PathSmoother.smoothGridPolyline(
                staircase,
                (x, z) -> !(x == 2 && z == 0),
                PathSmoother.DEFAULT_CLEARANCE_RADIUS
        );
        assertTrue("Obstacle near chord must prevent unsafe direct shortcut", smoothedBlocked.size() > 2);
    }

    @Test
    public void testGazeDirectorSprintConeClamp() {
        Rotation widePreAim = new Rotation(85.0f, 12.0f);
        Rotation clamped = GazeDirector.clampGazeToMoveCone(widePreAim, 0.0f, 35.0f);
        assertEquals(35.0f, clamped.getYaw(), 1e-4f);
        assertEquals(12.0f, clamped.getPitch(), 1e-4f);
    }

    @Test
    public void testBridgeControllerEdgeSneakSkillTiers() {
        // SAFE always holds sneak even away from edge
        assertTrue(BridgeController.shouldSneakAtEdge(BridgeController.BridgeSkill.SAFE, 0.45, false, 123L));
        // MEDIUM ninja bridges release sneak in safe center (>0.32) and sneak near edge (<=0.18)
        assertFalse(BridgeController.shouldSneakAtEdge(BridgeController.BridgeSkill.MEDIUM, 0.40, false, 123L));
        assertTrue(BridgeController.shouldSneakAtEdge(BridgeController.BridgeSkill.MEDIUM, 0.16, false, 123L));
        // SWEATY waits closer to edge than MEDIUM unless already looking at face
        assertFalse(BridgeController.shouldSneakAtEdge(BridgeController.BridgeSkill.SWEATY, 0.28, false, 123L));
        assertTrue(BridgeController.shouldSneakAtEdge(BridgeController.BridgeSkill.SWEATY, 0.28, true, 123L));
    }

    @Test
    public void testCombatShrunkTargetPointAndInventoryFitts() {
        AABB box = new AABB(-0.3, 0.0, -0.3, 0.3, 1.8, 0.3);
        Vec3 farCornerEye = new Vec3(5.0, 3.0, 5.0);
        Vec3 aim = CombatProcess.computeShrunkTargetPoint(farCornerEye, box, 0.15);

        // Must stay inside 15%-shrunk bounds rather than snapping to outer corner (0.3, 1.8, 0.3)
        assertTrue("Shrunk X must be inside outer bound 0.3", aim.x < 0.29 && aim.x > 0.20);
        assertTrue("Shrunk Z must be inside outer bound 0.3", aim.z < 0.29 && aim.z > 0.20);
        assertTrue("Shrunk Y must stay below top 1.8 edge", aim.y < 1.75);

        int adjacentTicks = InventoryBehavior.computeFittsSlotDelayTicks(0, 1);
        int crossInvTicks = InventoryBehavior.computeFittsSlotDelayTicks(9, 35);
        assertTrue("Cross-inventory slot travel must take >= adjacent slot travel", crossInvTicks > adjacentTicks);
    }

    @Test
    public void testDeterministicOffCenterBlockAimPoint() {
        BlockPos pos = new BlockPos(12, 64, -7);
        Vec3 center = new Vec3(12.5, 64.5, -6.5);
        Vec3 aim1 = RotationUtils.calculateHumanBlockAimPoint(center, pos);
        Vec3 aim2 = RotationUtils.calculateHumanBlockAimPoint(center, pos);

        assertEquals("Human block aim point must be deterministic across ticks for the same BlockPos", aim1, aim2);
        assertTrue("Aim point must stay off exact center", aim1.distanceTo(center) > 0.01);
        assertTrue("Aim point must remain well within block bounds", Math.abs(aim1.x - center.x) <= 0.20);
    }

    @Test
    public void testBridgeAnchorModeSuppressesOvershoot() {
        HumanProfile sweaty = HumanProfile.preset("sweaty");
        MouseModel mouse = new MouseModel(new ForkableRandom(99L));
        double sens = 0.5;
        Rotation current = new Rotation(0.0f, 76.0f);
        Rotation anchorTarget = new Rotation(12.0f, 78.0f);

        for (int t = 0; t < 6; t++) {
            Rotation next = mouse.step(current, anchorTarget, true, false, sweaty, sens, true);
            // In bridgeAnchor mode yaw must never overshoot past target + tiny tremor margin
            assertTrue("Bridge anchor yaw must not overshoot target", next.getYaw() <= 13.0f && next.getYaw() >= -1.0f);
            current = next;
        }
        assertTrue("Bridge anchor must converge tightly onto target face", MouseModel.angularDistance(current, anchorTarget) < 1.2f);
    }

    @Test
    public void testPathSmootherPreventsPrematureAdvanceThatCausesBacktrackLoop() {
        // Reproduces crash pos=(124.19, 100.0, 166.52) moving from 124->123->122:
        // dist to stepDest (123.5) is 0.69 < old 0.78 threshold, while playerFeet is still 124 (in stepValid, not in nextValid).
        BetterBlockPos b124 = new BetterBlockPos(124, 100, 166);
        BetterBlockPos b123 = new BetterBlockPos(123, 100, 166);
        BetterBlockPos b122 = new BetterBlockPos(122, 100, 166);
        Set<BetterBlockPos> stepValid = Set.of(b124, b123);
        Set<BetterBlockPos> nextValid = Set.of(b123, b122);

        assertFalse("Must not advance past step while playerFeet is still exclusively on step src",
                PathSmoother.canAdvancePastStep(b124, 124.19, 166.52, b123, b122, stepValid, nextValid));

        // Once player steps onto b123 (which IS in nextValid), advancing is safe and allowed
        assertTrue("May advance once playerFeet reaches shared stepDest",
                PathSmoother.canAdvancePastStep(b123, 123.85, 166.50, b123, b122, stepValid, nextValid));

        // Corner cut off-grid: (124,166)->(123,166)->(123,165) while player cuts diagonally through (124,165)
        BetterBlockPos cornerTurnDest = new BetterBlockPos(123, 100, 165);
        BetterBlockPos cornerCell = new BetterBlockPos(124, 100, 165);
        assertTrue("May advance when cutting a corner off-grid closer to nextDest",
                PathSmoother.canAdvancePastStep(cornerCell, 124.2, 165.6, b123, cornerTurnDest, stepValid, Set.of(b123, cornerTurnDest)));
    }

    @Test
    public void testAutoTestScorecardFormatting() {
        List<AutoTestRunner.StageVerdict> verdicts = List.of(
                new AutoTestRunner.StageVerdict("1. Motor & Sprint-Ascend", true, "reached=true | GCD=100.0%"),
                new AutoTestRunner.StageVerdict("2. Mining & Trap 3 Pre-Swap", false, "trap3Violations=1")
        );
        String formatted = AutoTestRunner.formatScorecard(verdicts, null);
        assertTrue(formatted.contains("1/2 PASSED"));
        assertTrue(formatted.contains("[PASS] 1. Motor & Sprint-Ascend"));
        assertTrue(formatted.contains("[FAIL] 2. Mining & Trap 3 Pre-Swap"));
    }

    @Test
    public void testHermiteMinimumJerkVelocityContinuity() {
        double u0 = 0.8;
        assertEquals(0.0, MouseModel.hermiteMinimumJerk(0.0, u0), 1e-9);
        assertEquals(1.0, MouseModel.hermiteMinimumJerk(1.0, u0), 1e-9);

        double eps = 1e-4;
        double startSlope = (MouseModel.hermiteMinimumJerk(eps, u0) - MouseModel.hermiteMinimumJerk(0.0, u0)) / eps;
        double endSlope = (MouseModel.hermiteMinimumJerk(1.0, u0) - MouseModel.hermiteMinimumJerk(1.0 - eps, u0)) / eps;
        assertEquals("Initial derivative must match preserved normalized velocity u0", u0, startSlope, 1e-3);
        assertEquals("End derivative must settle cleanly to zero velocity", 0.0, endSlope, 1e-3);
    }

    @Test
    public void testHumanStyleLearnerGeneralizationAndPersistence() throws Exception {
        Path tmpDir = Files.createTempDirectory("pathadol_learn_test");
        HumanStyleLearner learner = new HumanStyleLearner(tmpDir);
        learner.reset();

        // Synthesize 60 ticks of brisk pro human movement: fast curved flicks + high CPS + forward sprint
        List<HumanStyleLearner.MotionSample> samples = new ArrayList<>();
        float yaw = 0.0f;
        for (int t = 0; t < 60; t++) {
            if (t >= 10 && t < 13) {
                yaw += 18.0f;
            } else if (t >= 30 && t < 34) {
                yaw -= 14.0f;
            } else {
                yaw += 0.25f;
            }
            boolean leftClick = (t >= 14 && (t % 2 == 0));
            samples.add(new HumanStyleLearner.MotionSample(
                    yaw, 2.0f,
                    0.0, 0.26,
                    true, true, false,
                    true, false, false, false, false,
                    leftClick, false, 0
            ));
        }

        HumanStyleLearner.LearnUpdate update = learner.learnFromTrial(samples);
        assertTrue("Learning from valid trial must update weights", update.updated());
        assertEquals(1, HumanStyleLearner.activeLearnedState().trialsLearned);
        assertTrue(Files.exists(tmpDir.resolve(HumanStyleLearner.LEARNED_FILE_NAME)));

        // Reset in-memory globalState via a throwaway dir, then reload from tmpDir JSON to verify disk deserialization
        double savedAimSpeed = HumanStyleLearner.activeLearnedState().aimSpeed;
        Path scratchDir = Files.createTempDirectory("pathadol_learn_scratch");
        new HumanStyleLearner(scratchDir).reset();
        assertEquals(0, HumanStyleLearner.activeLearnedState().trialsLearned);

        HumanStyleLearner reloaded = new HumanStyleLearner(tmpDir);
        assertEquals(1, HumanStyleLearner.activeLearnedState().trialsLearned);
        assertEquals(savedAimSpeed, HumanStyleLearner.activeLearnedProfile().aimSpeed(), 1e-6);

        // Clean up global singleton state
        reloaded.reset();
    }

    @Test
    public void testGazeDirectorContinuousArcSampling() {
        List<IMovement> chain = List.of(
                stubMovement(0, 64, 0, 4, 64, 0),
                stubMovement(4, 64, 0, 8, 64, 4),
                stubMovement(8, 64, 4, 12, 64, 4)
        );
        Vec3 p1 = GazeDirector.sampleContinuousArcPoint(new Vec3(0.5, 64.0, 0.5), chain, 0, 5.0);
        Vec3 p2 = GazeDirector.sampleContinuousArcPoint(new Vec3(0.5, 64.0, 0.5), chain, 0, 6.0);
        assertTrue("6m point must be strictly further along X than 5m point", p2.x > p1.x);
        assertTrue("Continuous sample around diagonal turn must transition smoothly along Z", p2.z > p1.z);
    }

    @Test
    public void testMirrorArenaCatalogValidity() {
        List<MirrorArenaCatalog.StageDef> stages = MirrorArenaCatalog.buildCatalog();
        assertEquals(60, stages.size());
        Set<Integer> indices = new HashSet<>();
        for (MirrorArenaCatalog.StageDef st : stages) {
            assertTrue("Duplicate stage index", indices.add(st.index()));
            assertTrue(st.botMaxTicks() >= 200);
            assertTrue(st.humanMaxTicks() >= 800);
            assertNotNull(st.builder());
            assertNotNull(st.botStarter());
        }
    }

    @Test
    public void testMouseModelPeakSpeedCapAndSubPixelWristDrift() {
        HumanProfile pro = HumanProfile.preset("pro");
        MouseModel mouse = new MouseModel(new ForkableRandom(77L));
        double sens = 0.5;
        Rotation cur = new Rotation(0.0f, 0.0f);
        Rotation goal = new Rotation(90.0f, 0.0f);

        float maxStep = 0.0f;
        for (int t = 0; t < 12; t++) {
            Rotation next = mouse.step(cur, goal, false, false, pro, sens);
            float stepMag = (float) Math.hypot(MouseModel.shortestYawDelta(cur.getYaw(), next.getYaw()), next.getPitch() - cur.getPitch());
            maxStep = Math.max(maxStep, stepMag);
            cur = next;
        }
        assertTrue("Pro 90-deg turn peak velocity must stay capped under 35 deg/t (was " + maxStep + ")", maxStep < 35.0f);
        assertTrue("Pro 90-deg turn must converge within 12 ticks", MouseModel.angularDistance(cur, goal) < 2.5f);

        // Coasting inside deadzone must emit subtle wrist micro-steps instead of stalling at 0.000 for 14+ ticks
        int nonZeroTicks = 0;
        for (int t = 0; t < 18; t++) {
            Rotation next = mouse.step(cur, new Rotation(90.4f, 0.2f), false, false, pro, sens);
            if (Math.hypot(MouseModel.shortestYawDelta(cur.getYaw(), next.getYaw()), next.getPitch() - cur.getPitch()) > 1e-4f) {
                nonZeroTicks++;
            }
            cur = next;
        }
        assertTrue("Sub-pixel wrist accumulator must prevent deadzone freezes", nonZeroTicks >= 3);
    }

    @Test
    public void testHumanStyleLearnerJsonObjectRoundTrip() {
        HumanStyleLearner.LearnedState state = HumanStyleLearner.LearnedState.defaultState();
        state.trialsLearned = 4;
        state.totalHumanTicks = 520L;
        state.aimSpeed = 1.42;
        state.clickStyle = "jitter";
        state.bridgeSkill = "medium";
        state.steeringLookahead = 2.95;

        String json = HumanStyleLearner.toJsonString(state);
        HumanStyleLearner.LearnedState decoded = HumanStyleLearner.fromJsonString(json);
        assertNotNull(decoded);
        assertEquals(4, decoded.trialsLearned);
        assertEquals(520L, decoded.totalHumanTicks);
        assertEquals(1.42, decoded.aimSpeed, 1e-6);
        assertEquals("jitter", decoded.clickStyle);
        assertEquals("medium", decoded.bridgeSkill);
        assertEquals(2.95, decoded.steeringLookahead, 1e-6);
        assertNull(HumanStyleLearner.fromJsonString("[]"));
    }

    @Test
    public void testGazeDirectorSkipsPassedStepsMidAir() {
        List<IMovement> chain = List.of(
                stubMovement(0, 64, 0, 4, 64, 0),
                stubMovement(4, 64, 0, 10, 64, 0)
        );
        Vec3 forwardLook = GazeDirector.sampleContinuousArcPoint(new Vec3(5.2, 65.2, 0.0), chain, 0, 5.0);
        assertTrue("Horizon gaze past passed dest must look forward along X (>6.5), was " + forwardLook.x, forwardLook.x > 6.5);
    }

    @Test
    public void testStageNarrativeAnalyzerDetectsWallScrapeBacktrackAndAnomalies() {
        List<StageNarrativeAnalyzer.TickFrame> frames = new ArrayList<>();
        double x = 0.5;
        double z = 0.5;
        float yaw = -90.0f;
        for (int t = 0; t < 40; t++) {
            double vx = 0.25;
            double vz = 0.0;
            boolean onGround = true;
            boolean coll = false;
            String wall = null;
            if (t >= 10 && t <= 16) {
                // Frozen 0.00 deg/t plateau while moving
                x += 0.24;
            } else if (t >= 18 && t <= 25) {
                // Wall scrape against cobblestone
                vx = 0.01;
                coll = true;
                wall = "cobblestone";
            } else if (t >= 26 && t <= 32) {
                // Backtrack + airborne arc + snap
                vx = -0.25;
                x -= 0.25;
                onGround = false;
                if (t == 28) {
                    yaw = -48.0f;
                }
            } else {
                x += 0.25;
                yaw += 0.3f;
            }
            frames.add(new StageNarrativeAnalyzer.TickFrame(
                    t, x, onGround ? 195.0 : 196.2, z,
                    vx, 0.0, vz,
                    yaw, 0.0f,
                    onGround, coll, true, false,
                    true, false, false, false, false,
                    false, false, false, false, 0, wall
            ));
        }

        StageNarrativeAnalyzer.RunNarrative nar = StageNarrativeAnalyzer.analyzeRun(
                "BOT", false, true, new Vec3(0.5, 195.0, 0.5), new Vec3(20.5, 195.0, 0.5), frames);
        assertTrue("Must detect cobblestone wall scrape", nar.stats().scrapeCount() >= 1);
        assertTrue("Must detect backtrack", nar.stats().backtrackCount() >= 1);
        assertTrue("Must detect airborne arc", nar.stats().airArcCount() >= 1);
        assertTrue("Must detect frozen aim plateau", nar.stats().maxFrozenRun() >= 6);
        assertTrue("Must detect >30 deg/t snap", nar.stats().snapsOver30() >= 1);
        String joined = String.join("\n", nar.sceneLines());
        assertTrue("Scene text must name colliding block", joined.contains("cobblestone"));

        List<String> cmp = StageNarrativeAnalyzer.buildBotVsYouCommentary("1. Test Stage", nar, nar);
        assertEquals(3, cmp.size());
    }

    @Test
    public void testSixtyStageCatalogIntegrity() {
        List<MirrorArenaCatalog.StageDef> catalog = MirrorArenaCatalog.buildCatalog();
        assertEquals("Proving Ground should contain 60 stages", 60, catalog.size());

        int combatStages = 0;
        int classic18Stages = 0;
        for (int i = 0; i < catalog.size(); i++) {
            MirrorArenaCatalog.StageDef s = catalog.get(i);
            assertEquals("Sequential index expected", i + 1, s.index());
            assertNotNull(s.name());
            assertFalse(s.name().isBlank());
            assertNotNull(s.category());
            assertNotNull(s.hint());
            assertNotNull(s.builder());
            assertNotNull(s.botStarter());
            assertTrue("botMaxTicks too small on " + s.name(), s.botMaxTicks() >= 300);
            assertTrue("humanMaxTicks too small on " + s.name(), s.humanMaxTicks() >= 900);
            if (s.combatStage()) {
                combatStages++;
                if (s.classic18Combat()) {
                    classic18Stages++;
                }
            } else {
                assertEquals("Non-combat stage readyCheck must sit below goal pad on " + s.name(),
                        s.goalRel().below(), s.readyCheckRel());
            }
        }
        assertTrue("Expected at least 10 combat stages", combatStages >= 10);
        assertTrue("Expected multiple 1.8 classic combat stages", classic18Stages >= 3);
    }

    @Test
    public void testPathSmootherRejectsActualPlayerWallCornerClip() {
        boolean losCleared = PathSmoother.hasSweptLineOfSight(
                28.70, 2.74,
                30.50, 2.50,
                195,
                PathSmoother.DEFAULT_CLEARANCE_RADIUS,
                (x, z) -> !(x == 29 && z == 3),
                null
        );
        assertFalse("Player grazing wall corner (29, 3) from z=2.74 must reject shortcut LOS", losCleared);
    }

    @Test
    public void testMouseModelContinuousDriftAvoidsZeroPlateaus() {
        MouseModel model = new MouseModel(new ForkableRandom(98765L));
        HumanProfile profile = HumanProfile.preset("pro");
        Rotation current = new Rotation(-90.0f, 0.0f);
        Rotation cruiseTarget = new Rotation(-90.0f, 0.0f);

        int maxZeroStreak = 0;
        int currentStreak = 0;
        double maxYawDrift = 0.0;
        for (int t = 0; t < 45; t++) {
            Rotation next = model.step(current, cruiseTarget, false, false, profile, 0.5);
            double dy = Math.abs(MouseModel.shortestYawDelta(current.getYaw(), next.getYaw()));
            double dp = Math.abs(next.getPitch() - current.getPitch());
            if (dy < 1e-4 && dp < 1e-4) {
                currentStreak++;
                maxZeroStreak = Math.max(maxZeroStreak, currentStreak);
            } else {
                currentStreak = 0;
            }
            maxYawDrift = Math.max(maxYawDrift, Math.abs(MouseModel.shortestYawDelta(-90.0f, next.getYaw())));
            current = next;
        }
        assertTrue("Continuous cruising drift must not flatline for >= 8 consecutive ticks (maxStreak=" + maxZeroStreak + ")",
                maxZeroStreak < 8);
        assertTrue("Drift must remain bounded near intended gaze (maxYawDrift=" + maxYawDrift + ")",
                maxYawDrift < 2.0);
    }

    @Test
    public void testDioramaCameraSpringFollowAndLookAngles() {
        DioramaCameraController.resetSpringState();
        for (int t = 0; t < 10; t++) {
            DioramaCameraController.stepSpringTick(
                    DioramaCameraController.Mode.ISO,
                    10.0 + t * 0.28, 195.0, 0.0,
                    0.28, 0.0, -90.0f, null
            );
        }
        double[] beforeJump = DioramaCameraController.snapshotStateForTest();
        // Sudden +1.25 block jump: camera Y focus should lag smoothly rather than jumping rigid 1:1
        DioramaCameraController.stepSpringTick(
                DioramaCameraController.Mode.ISO,
                12.8, 196.25, 0.0,
                0.28, 0.0, -90.0f, null
        );
        double[] onJump = DioramaCameraController.snapshotStateForTest();
        double focusDeltaY = onJump[4] - beforeJump[4];
        assertTrue("Vertical diorama focus should damp jumps smoothly (dy=" + focusDeltaY + ")",
                focusDeltaY > 0.01 && focusDeltaY < 0.65);
        assertTrue("ISO camera should pitch smoothly downward (pitch=" + onJump[7] + ")",
                onJump[7] > 15.0 && onJump[7] < 55.0);

        DioramaCameraController.resetSpringState();
        DioramaCameraController.stepSpringTick(
                DioramaCameraController.Mode.SIDE,
                5.0, 195.0, 0.0,
                0.2, 0.0, -90.0f, null
        );
        double[] sideState = DioramaCameraController.snapshotStateForTest();
        assertTrue("SIDE camera should offset along +Z relative to actor", sideState[2] > sideState[5] + 5.0);
        DioramaCameraController.resetSpringState();
    }

    private static IMovement stubMovement(int sx, int sy, int sz, int dx, int dy, int dz) {
        BetterBlockPos src = new BetterBlockPos(sx, sy, sz);
        BetterBlockPos dest = new BetterBlockPos(dx, dy, dz);
        return new IMovement() {
            @Override public double getCost() { return 1; }
            @Override public boolean calculatedWhileLoaded() { return true; }
            @Override public BetterBlockPos getSrc() { return src; }
            @Override public BetterBlockPos getDest() { return dest; }
            @Override public BlockPos getDirection() { return dest.subtract(src); }
            @Override public MovementStatus update() { return MovementStatus.RUNNING; }
            @Override public boolean safeToCancel() { return true; }
            @Override public void reset() {}
            @Override public void resetBlockCache() {}
        };
    }
}
