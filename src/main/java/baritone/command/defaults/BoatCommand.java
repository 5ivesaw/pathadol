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

package baritone.command.defaults;

import baritone.Baritone;
import baritone.api.IBaritone;
import baritone.api.command.Command;
import baritone.api.command.argument.IArgConsumer;
import baritone.api.command.datatypes.RelativeCoordinate;
import baritone.api.command.datatypes.RelativeGoal;
import baritone.api.command.exception.CommandException;
import baritone.api.command.exception.CommandInvalidStateException;
import baritone.api.command.helpers.TabCompleteHelper;
import baritone.api.pathing.goals.Goal;
import baritone.api.utils.BetterBlockPos;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

public class BoatCommand extends Command {

    public BoatCommand(IBaritone baritone) {
        super(baritone, "boat");
    }

    @Override
    public void execute(String label, IArgConsumer args) throws CommandException {
        if (!(baritone instanceof Baritone b) || b.getBoatProcess() == null) {
            throw new CommandInvalidStateException("Boat process unavailable");
        }
        if (!args.hasAny()) {
            Goal goal = baritone.getCustomGoalProcess().mostRecentGoal();
            if (goal == null) {
                throw new CommandInvalidStateException("No goal set. Use #boat <x> <z> or #goal <x> <z> first");
            }
            b.getBoatProcess().startTo(goal);
            logDirect("Engaging autonomous boat navigation toward " + goal);
            return;
        }
        if (args.peekDatatypeOrNull(RelativeCoordinate.INSTANCE) != null) {
            args.requireMax(3);
            BetterBlockPos origin = ctx.playerFeet();
            Goal goal = args.getDatatypePost(RelativeGoal.INSTANCE, origin);
            b.getBoatProcess().startTo(goal);
            logDirect("Boating toward " + goal);
            return;
        }
        String sub = args.getString().toLowerCase(Locale.ROOT);
        args.requireMax(0);
        switch (sub) {
            case "stop", "off", "cancel" -> {
                b.getBoatProcess().onLostControl();
                logDirect("Boat navigation stopped");
            }
            case "status" -> logDirect("Boat state: " + b.getBoatProcess().getState());
            default -> throw new CommandInvalidStateException("Usage: #boat [x [y] z | stop | status]");
        }
    }

    @Override
    public Stream<String> tabComplete(String label, IArgConsumer args) throws CommandException {
        if (args.hasExactlyOne()) {
            return new TabCompleteHelper()
                    .append("stop", "status")
                    .filterPrefix(args.getString())
                    .stream();
        }
        return Stream.empty();
    }

    @Override
    public String getShortDesc() {
        return "Navigate across water or ice using a boat";
    }

    @Override
    public List<String> getLongDesc() {
        return Arrays.asList(
                "Autonomous boat crafting, water/ice placement, obstacle-avoiding steering, and recovery.",
                "",
                "Usage:",
                "> boat - Sail toward the current #goal",
                "> boat <x> <z> - Craft/launch a boat and sail toward (x, z)",
                "> boat status - Show current BoatProcess state",
                "> boat stop - Stop autonomous boat control"
        );
    }
}
