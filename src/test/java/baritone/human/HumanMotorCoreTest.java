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

import java.util.ArrayList;
import java.util.EnumMap;
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

        // Corner cut off-grid: playerFeet at (123, 100, 165) is NOT in stepValid, so no Loop-1 rollback possible
        BetterBlockPos cornerCell = new BetterBlockPos(123, 100, 165);
        assertTrue("May advance when cutting a corner off-grid near dest",
                PathSmoother.canAdvancePastStep(cornerCell, 123.3, 165.9, b123, b122, stepValid, nextValid));
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
}
