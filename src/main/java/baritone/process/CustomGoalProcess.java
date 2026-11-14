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

package baritone.process;

import baritone.Baritone;
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.pathing.goals.GoalXZ;
import baritone.api.process.ICustomGoalProcess;
import baritone.api.process.PathingCommand;
import baritone.api.process.PathingCommandType;
import baritone.human.ElytraOverworldHelper;
import baritone.process.elytra.ElytraBehavior;
import baritone.utils.BaritoneProcessHelper;
import net.minecraft.ChatFormatting;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.Level;

/**
 * As set by ExampleBaritoneControl or something idk
 *
 * @author leijurv
 */
public final class CustomGoalProcess extends BaritoneProcessHelper implements ICustomGoalProcess {

    /**
     * The current goal
     */
    private Goal goal;

    /**
     * The most recent goal. Not invalidated upon {@link #onLostControl()}
     */
    private Goal mostRecentGoal;

    /**
     * The current process state.
     *
     * @see State
     */
    private State state;
    private int consecutiveCalcFailures = 0;
    private int retryCooldownTicks = 0;
    private boolean autoEngagedElytraForCurrentGoal = false;

    public CustomGoalProcess(Baritone baritone) {
        super(baritone);
    }

    @Override
    public void setGoal(Goal goal) {
        this.goal = goal;
        this.mostRecentGoal = goal;
        this.consecutiveCalcFailures = 0;
        this.retryCooldownTicks = 0;
        this.autoEngagedElytraForCurrentGoal = false;
        if (baritone.getElytraProcess().isActive()) {
            try {
                baritone.getElytraProcess().pathTo(goal);
            } catch (IllegalArgumentException e) {
                logDirect("Failed to update elytra goal because: " + e.getMessage(), ChatFormatting.RED);
            }
        }
        if (this.state == State.NONE) {
            this.state = State.GOAL_SET;
        }
        if (this.state == State.EXECUTING) {
            this.state = State.PATH_REQUESTED;
        }
    }

    @Override
    public void path() {
        this.consecutiveCalcFailures = 0;
        this.retryCooldownTicks = 0;
        this.autoEngagedElytraForCurrentGoal = false;
        this.state = State.PATH_REQUESTED;
    }

    @Override
    public Goal getGoal() {
        return this.goal;
    }

    @Override
    public Goal mostRecentGoal() {
        return this.mostRecentGoal;
    }

    @Override
    public boolean isActive() {
        return this.state != State.NONE;
    }

    @Override
    public PathingCommand onTick(boolean calcFailed, boolean isSafeToCancel) {
        switch (this.state) {
            case GOAL_SET:
                return new PathingCommand(this.goal, PathingCommandType.CANCEL_AND_SET_GOAL);
            case PATH_REQUESTED:
                if (tryAutoEngageOverworldElytra()) {
                    return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
                }
                if (retryCooldownTicks > 0) {
                    retryCooldownTicks--;
                    return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
                }
                // return FORCE_REVALIDATE_GOAL_AND_PATH just once
                PathingCommand ret = new PathingCommand(this.goal, PathingCommandType.FORCE_REVALIDATE_GOAL_AND_PATH);
                this.state = State.EXECUTING;
                return ret;
            case EXECUTING:
                if (tryAutoEngageOverworldElytra()) {
                    return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
                }
                if (calcFailed) {
                    consecutiveCalcFailures++;
                    if (consecutiveCalcFailures >= 6) {
                        onLostControl();
                        return new PathingCommand(this.goal, PathingCommandType.CANCEL_AND_SET_GOAL);
                    }
                    retryCooldownTicks = Math.min(18, 3 * consecutiveCalcFailures);
                    this.state = State.PATH_REQUESTED;
                    return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
                }
                if (baritone.getPathingBehavior().getCurrent() != null) {
                    consecutiveCalcFailures = 0;
                }
                if (this.goal == null || (this.goal.isInGoal(ctx.playerFeet()) && this.goal.isInGoal(baritone.getPathingBehavior().pathStart()))) {
                    onLostControl(); // we're there xd
                    if (Baritone.settings().disconnectOnArrival.value) {
                        if (ctx.world() instanceof ClientLevel clientLevel) {
                            clientLevel.disconnect(Component.literal("[Pathadol] Arrived at goal!"));
                        }
                    }
                    if (Baritone.settings().notificationOnPathComplete.value) {
                        logNotification("Pathing complete", false);
                    }
                    return new PathingCommand(this.goal, PathingCommandType.CANCEL_AND_SET_GOAL);
                }
                return new PathingCommand(this.goal, PathingCommandType.SET_GOAL_AND_PATH);
            default:
                throw new IllegalStateException("Unexpected state " + this.state);
        }
    }

    @Override
    public void onLostControl() {
        this.state = State.NONE;
        this.goal = null;
        this.consecutiveCalcFailures = 0;
        this.retryCooldownTicks = 0;
        this.autoEngagedElytraForCurrentGoal = false;
    }

    private boolean tryAutoEngageOverworldElytra() {
        if (this.autoEngagedElytraForCurrentGoal || this.goal == null || ctx.player() == null || ctx.world() == null) {
            return false;
        }
        if (ctx.player().isPassenger()) {
            return false;
        }
        if (baritone.getSpeedrunProcess() != null && baritone.getSpeedrunProcess().isActive()) {
            return false;
        }
        if (!baritone.getElytraProcess().isLoaded() || baritone.getElytraProcess().isActive()) {
            return false;
        }
        final int targetX;
        final int targetZ;
        if (this.goal instanceof GoalXZ xz) {
            targetX = xz.getX();
            targetZ = xz.getZ();
        } else if (this.goal instanceof GoalBlock blk) {
            targetX = blk.x;
            targetZ = blk.z;
        } else {
            return false;
        }
        double distXZ = Math.hypot((targetX + 0.5) - ctx.player().getX(), (targetZ + 0.5) - ctx.player().getZ());
        boolean engage = ElytraOverworldHelper.shouldAutoEngageOverworldElytra(
                ctx.world().dimension() == Level.OVERWORLD,
                Baritone.settings().autoOverworldElytra.value,
                ctx.player().isFallFlying(),
                ElytraBehavior.hasUsableElytra(ctx.player()),
                ElytraBehavior.countUsableFireworks(ctx.player()),
                distXZ,
                Baritone.settings().elytraOverworldMinDistance.value
        );
        if (!engage) {
            return false;
        }
        this.autoEngagedElytraForCurrentGoal = true;
        try {
            baritone.getElytraProcess().pathTo(this.goal);
            return baritone.getElytraProcess().isActive();
        } catch (IllegalArgumentException e) {
            logDebug("Overworld auto-elytra skipped: " + e.getMessage());
            return false;
        }
    }

    @Override
    public String displayName0() {
        return "Custom Goal " + this.goal;
    }

    protected enum State {
        NONE,
        GOAL_SET,
        PATH_REQUESTED,
        EXECUTING
    }
}
