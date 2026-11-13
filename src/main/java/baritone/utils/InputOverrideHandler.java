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

package baritone.utils;

import baritone.Baritone;
import baritone.api.BaritoneAPI;
import baritone.api.event.events.TickEvent;
import baritone.api.utils.IInputOverrideHandler;
import baritone.api.utils.input.Input;
import baritone.behavior.Behavior;
import baritone.behavior.look.ForkableRandom;
import baritone.human.AutoTestRunner;
import baritone.human.ClickModel;
import baritone.human.DioramaCameraController;
import baritone.human.HumanLabRecorder;
import baritone.human.HumanProfile;
import baritone.human.HumanTelemetryHooks;
import baritone.human.KeyboardModel;
import baritone.human.MotorIntent;
import net.minecraft.client.player.KeyboardInput;

import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;

/**
 * An interface with the game's control system allowing the ability to
 * force down certain controls, having the same effect as if we were actually
 * physically forcing down the assigned key.
 *
 * @author Brady
 * @since 7/31/2018
 */
public final class InputOverrideHandler extends Behavior implements IInputOverrideHandler {

    /**
     * Maps inputs to whether or not we are forcing their state down.
     */
    private final Map<Input, Boolean> inputForceStateMap = new HashMap<>();

    private final BlockBreakHelper blockBreakHelper;
    private final BlockPlaceHelper blockPlaceHelper;
    private final KeyboardModel keyboardModel;
    private final ClickModel clickModel;
    private final HumanLabRecorder labRecorder;
    private final AutoTestRunner autoTestRunner;
    private Map<Input, Boolean> lastResolvedInputs = new EnumMap<>(Input.class);
    private boolean lastRightClickFired = false;
    private boolean lastBrokeBlock = false;
    private boolean wasInControl = false;
    private int ticksSinceMouseAction = 10;

    public InputOverrideHandler(Baritone baritone) {
        super(baritone);
        this.blockBreakHelper = new BlockBreakHelper(baritone.getPlayerContext());
        this.blockPlaceHelper = new BlockPlaceHelper(baritone.getPlayerContext());
        this.keyboardModel = new KeyboardModel();
        long seed = Baritone.settings().humanSeed.value;
        this.clickModel = new ClickModel(seed != 0L ? new ForkableRandom(seed ^ 0x9E3779B97F4A7C15L) : new ForkableRandom());
        this.labRecorder = new HumanLabRecorder(baritone.getDirectory());
        this.autoTestRunner = new AutoTestRunner(baritone, this.labRecorder);
    }

    /**
     * Returns whether or not we are forcing down the specified {@link Input}.
     *
     * @param input The input
     * @return Whether or not it is being forced down
     */
    @Override
    public final boolean isInputForcedDown(Input input) {
        return input == null ? false : this.inputForceStateMap.getOrDefault(input, false);
    }

    /**
     * Sets whether or not the specified {@link Input} is being forced down.
     *
     * @param input  The {@link Input}
     * @param forced Whether or not the state is being forced
     */
    @Override
    public final void setInputForceState(Input input, boolean forced) {
        this.inputForceStateMap.put(input, forced);
    }

    /**
     * Clears the override state for all keys
     */
    @Override
    public final void clearAllKeys() {
        this.inputForceStateMap.clear();
        this.keyboardModel.clearDesiredMoveYaw();
    }

    /**
     * Releases all bot-held override inputs, resolved telemetry snapshots, keyboard hold timers,
     * and raw client movement/action keys when stopping or relinquishing bot control.
     */
    public final void releaseBotInputs() {
        this.inputForceStateMap.clear();
        this.lastResolvedInputs.clear();
        this.keyboardModel.reset();
        this.lastRightClickFired = false;
        this.lastBrokeBlock = false;
        this.wasInControl = false;
        if (ctx.minecraft() != null && ctx.minecraft().options != null) {
            var opts = ctx.minecraft().options;
            opts.keyUp.setDown(false);
            opts.keyDown.setDown(false);
            opts.keyLeft.setDown(false);
            opts.keyRight.setDown(false);
            opts.keyJump.setDown(false);
            opts.keyShift.setDown(false);
            opts.keySprint.setDown(false);
            opts.keyAttack.setDown(false);
            opts.keyUse.setDown(false);
        }
        if (ctx.player() != null && ctx.player().input.getClass() == PlayerMovementInput.class && ctx.minecraft() != null) {
            ctx.player().input = new KeyboardInput(ctx.minecraft().options);
        }
    }

    /**
     * Returns whether mouse actions have been completely idle long enough to switch hotbar slots safely.
     */
    public final boolean isMouseActionIdleForSlotSwitch() {
        return this.ticksSinceMouseAction >= 1
                && !isInputForcedDown(Input.CLICK_LEFT)
                && !isInputForcedDown(Input.CLICK_RIGHT)
                && !this.lastBrokeBlock
                && !this.lastRightClickFired;
    }

    public void applyMotorIntent(MotorIntent intent) {
        if (intent != null) {
            this.keyboardModel.setDesiredMoveYaw(intent.desiredMoveYaw().orElse(null));
        }
    }

    public void setDesiredMovementYaw(Float yaw) {
        this.keyboardModel.setDesiredMoveYaw(yaw);
    }

    public Float getDesiredMovementYaw() {
        return this.keyboardModel.getDesiredMoveYaw();
    }

    /**
     * Resolves the effective W/A/S/D, jump, sneak, and sprint states for the current tick,
     * applying omnidirectional strafe decoupling and key hold hysteresis when {@code humanMode} is enabled.
     */
    public Map<Input, Boolean> resolveMovementInputs(float currentCameraYaw) {
        if (!Baritone.settings().humanMode.value) {
            this.lastResolvedInputs = new HashMap<>(this.inputForceStateMap);
            return this.lastResolvedInputs;
        }
        if (this.keyboardModel.getDesiredMoveYaw() == null) {
            Float guideYaw = baritone.getLookBehavior().getGuideTargetYaw();
            if (guideYaw != null && isInputForcedDown(Input.MOVE_FORWARD) && !isInputForcedDown(Input.MOVE_BACK)) {
                this.keyboardModel.setDesiredMoveYaw(guideYaw);
            }
        }
        this.lastResolvedInputs = this.keyboardModel.step(
                this.inputForceStateMap,
                currentCameraYaw,
                HumanProfile.fromSettings()
        );
        return this.lastResolvedInputs;
    }

    @Override
    public final void onTick(TickEvent event) {
        if (event.getType() == TickEvent.Type.OUT) {
            return;
        }
        this.lastRightClickFired = false;
        this.lastBrokeBlock = false;

        if (Baritone.settings().humanMode.value) {
            boolean hotbarReady = baritone.getInventoryBehavior().isHotbarReadyForAction();
            boolean crosshairReady = hotbarReady && baritone.getLookBehavior().isCrosshairReadyForInteract();
            boolean rawLeft = isInputForcedDown(Input.CLICK_LEFT);
            if (rawLeft) {
                setInputForceState(Input.CLICK_RIGHT, false);
            }
            boolean effectiveLeft = clickModel.stepLeftClick(rawLeft, crosshairReady);
            boolean brokeBlock = blockBreakHelper.tick(effectiveLeft, true);
            this.lastBrokeBlock = brokeBlock;
            if (brokeBlock) {
                clickModel.onBlockBroken();
            }

            boolean rawRight = !effectiveLeft && isInputForcedDown(Input.CLICK_RIGHT);
            boolean effectiveRight = clickModel.stepRightClick(rawRight, crosshairReady, HumanProfile.fromSettings());
            this.lastRightClickFired = blockPlaceHelper.tick(effectiveRight, true);
        } else {
            if (isInputForcedDown(Input.CLICK_LEFT)) {
                setInputForceState(Input.CLICK_RIGHT, false);
            }
            this.lastBrokeBlock = blockBreakHelper.tick(isInputForcedDown(Input.CLICK_LEFT), false);
            this.lastRightClickFired = blockPlaceHelper.tick(isInputForcedDown(Input.CLICK_RIGHT), false);
        }

        if (inControl()) {
            this.wasInControl = true;
            if (this.lastBrokeBlock || this.lastRightClickFired
                    || isInputForcedDown(Input.CLICK_LEFT) || isInputForcedDown(Input.CLICK_RIGHT)
                    || (Baritone.settings().humanMode.value && clickModel.isOverHolding())) {
                this.ticksSinceMouseAction = 0;
            } else if (this.ticksSinceMouseAction < 100) {
                this.ticksSinceMouseAction++;
            }
            if (ctx.player().input.getClass() != PlayerMovementInput.class) {
                ctx.player().input = new PlayerMovementInput(this);
            }
        } else {
            if (this.wasInControl) {
                releaseBotInputs();
            }
            this.ticksSinceMouseAction = 10;
            if (ctx.player().input.getClass() == PlayerMovementInput.class) { // allow other movement inputs that aren't this one, e.g. for a freecam
                ctx.player().input = new KeyboardInput(ctx.minecraft().options);
            }
        }
        // only set it if it was previously incorrect
        // gotta do it this way, or else it constantly thinks you're beginning a double tap W sprint lol
    }

    @Override
    public final void onPostTick(TickEvent event) {
        HumanTelemetryHooks.TickSnapshot tickSnap = HumanTelemetryHooks.consumeTickSnapshot();
        if (event.getType() == TickEvent.Type.OUT || ctx.player() == null) {
            HumanTelemetryHooks.resetAll();
            return;
        }
        if (ctx.minecraft() != null && ctx.minecraft().isPaused()) {
            return;
        }
        boolean effectiveRight = this.lastRightClickFired || tickSnap.anyRightImpulse();
        boolean effectiveBroke = this.lastBrokeBlock || tickSnap.blocksDestroyed() > 0;
        autoTestRunner.onPostTick(ctx, effectiveRight, effectiveBroke, tickSnap);
        DioramaCameraController.onPostTick(
                ctx,
                inControl() || baritone.getPathingBehavior().isPathing() || baritone.getCombatProcess().isActive()
        );
        if (!labRecorder.isRecording() || !autoTestRunner.shouldRecordTelemetryTick()) {
            return;
        }
        boolean botActive = inControl();
        Map<Input, Boolean> snapshot = new EnumMap<>(Input.class);
        if (botActive) {
            snapshot.putAll(this.lastResolvedInputs);
        } else {
            if (!this.lastResolvedInputs.isEmpty()) {
                this.lastResolvedInputs.clear();
            }
            var kp = ctx.player().input.keyPresses;
            var opts = ctx.minecraft().options;
            var mh = ctx.minecraft().mouseHandler;
            snapshot.put(Input.MOVE_FORWARD, kp.forward());
            snapshot.put(Input.MOVE_BACK, kp.backward());
            snapshot.put(Input.MOVE_LEFT, kp.left());
            snapshot.put(Input.MOVE_RIGHT, kp.right());
            snapshot.put(Input.JUMP, kp.jump());
            snapshot.put(Input.SNEAK, kp.shift());
            snapshot.put(Input.SPRINT, kp.sprint());
            boolean leftDown = tickSnap.lmbPhysicallyDown()
                    || opts.keyAttack.isDown()
                    || mh.isLeftPressed()
                    || tickSnap.anyLeftImpulse();
            boolean rightDown = tickSnap.rmbPhysicallyDown()
                    || opts.keyUse.isDown()
                    || mh.isRightPressed()
                    || tickSnap.anyRightImpulse();
            snapshot.put(Input.CLICK_LEFT, leftDown);
            snapshot.put(Input.CLICK_RIGHT, rightDown);
        }
        labRecorder.recordTick(
                ctx,
                snapshot,
                tickSnap,
                this.lastRightClickFired,
                this.lastBrokeBlock,
                botActive,
                autoTestRunner.getTelemetryPhaseTag(),
                autoTestRunner.getActiveStageId(),
                autoTestRunner.getActiveStageName(),
                autoTestRunner.getActiveStageCategory()
        );
    }

    public Map<Input, Boolean> getLastResolvedInputs() {
        return Collections.unmodifiableMap(this.lastResolvedInputs);
    }

    private boolean inControl() {
        for (Input input : new Input[]{Input.MOVE_FORWARD, Input.MOVE_BACK, Input.MOVE_LEFT, Input.MOVE_RIGHT, Input.SNEAK, Input.JUMP}) {
            if (isInputForcedDown(input)) {
                return true;
            }
        }
        // if we are not primary (a bot) we should set the movementinput even when idle (not pathing)
        return baritone.getPathingBehavior().isPathing() || baritone != BaritoneAPI.getProvider().getPrimaryBaritone();
    }

    public BlockBreakHelper getBlockBreakHelper() {
        return blockBreakHelper;
    }

    public KeyboardModel getKeyboardModel() {
        return keyboardModel;
    }

    public ClickModel getClickModel() {
        return clickModel;
    }

    public HumanLabRecorder getLabRecorder() {
        return labRecorder;
    }

    public AutoTestRunner getAutoTestRunner() {
        return autoTestRunner;
    }
}
