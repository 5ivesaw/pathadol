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
import baritone.api.command.exception.CommandException;
import baritone.process.SpeedrunProcess;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * Starts, inspects, or cancels autonomous Any% Ender Dragon speedruns (#speedrun / #dragon).
 */
public final class SpeedrunCommand extends Command {

    public SpeedrunCommand(IBaritone baritone) {
        super(baritone, "speedrun", "dragon");
    }

    @Override
    public void execute(String label, IArgConsumer args) throws CommandException {
        if (!(baritone instanceof Baritone b)) {
            return;
        }
        SpeedrunProcess proc = b.getSpeedrunProcess();
        if (args.hasAny()) {
            String sub = args.getString().toLowerCase(Locale.ROOT);
            if ("stop".equals(sub) || "cancel".equals(sub) || "off".equals(sub)) {
                proc.cancel();
                logDirect("Speedrun cancelled.");
                return;
            }
            if ("status".equals(sub)) {
                logDirect("Speedrun status: " + (proc.isActive() ? proc.getStatusSummary() : "IDLE"));
                return;
            }
        }
        proc.start();
    }

    @Override
    public Stream<String> tabComplete(String label, IArgConsumer args) {
        return Stream.of("start", "status", "stop");
    }

    @Override
    public String getShortDesc() {
        return "Autonomous Any% Ender Dragon speedrun workflow";
    }

    @Override
    public List<String> getLongDesc() {
        return Arrays.asList(
                "Runs an autonomous Survival Any% Ender Dragon state machine reusing Baritone exploration, mining, crafting, Nether portal building, Eye triangulation, and End combat.",
                "Usage:",
                "> speedrun — Start speedrun from current world state",
                "> speedrun status — Print current stage & timer",
                "> speedrun stop — Cancel active speedrun"
        );
    }
}
