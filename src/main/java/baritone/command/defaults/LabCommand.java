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
import baritone.api.command.exception.CommandInvalidStateException;
import baritone.api.command.helpers.TabCompleteHelper;
import baritone.human.HumanLabRecorder;
import baritone.human.HumanProfile;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

public class LabCommand extends Command {

    public LabCommand(IBaritone baritone) {
        super(baritone, "lab", "humanlab");
    }

    @Override
    public void execute(String label, IArgConsumer args) throws CommandException {
        String action = args.hasAny() ? args.getString().toLowerCase(Locale.ROOT) : "stats";
        HumanLabRecorder recorder = ((Baritone) baritone).getInputOverrideHandler().getLabRecorder();

        switch (action) {
            case "start", "record" -> {
                String tag = args.hasAny() ? args.getString() : "session";
                args.requireMax(0);
                try {
                    Path out = recorder.start(tag);
                    logDirect("HumanLab recording started: " + out.getFileName());
                } catch (IOException e) {
                    throw new CommandInvalidStateException("Failed to start HumanLab recorder: " + e.getMessage());
                }
            }
            case "stop" -> {
                args.requireMax(0);
                Path saved = recorder.stop();
                logDirect(recorder.formatSummary());
                if (saved != null) {
                    logDirect("Saved JSONL telemetry to: " + saved);
                }
            }
            case "stats", "status" -> {
                args.requireMax(0);
                logDirect(recorder.formatSummary());
            }
            case "profile" -> {
                if (!args.hasAny()) {
                    HumanProfile prof = HumanProfile.fromSettings();
                    logDirect(String.format(Locale.ROOT,
                            "Active profile: %s (humanMode=%s, aimSpeed=%.2f, overshoot=%.2f, reaction=%.0fms, cps=%.1f %s)",
                            prof.name(),
                            Baritone.settings().humanMode.value,
                            prof.aimSpeed(),
                            prof.overshoot(),
                            prof.reactionMedianMs(),
                            prof.cpsMean(),
                            prof.clickStyle()
                    ));
                    return;
                }
                String preset = args.getString().toLowerCase(Locale.ROOT);
                args.requireMax(0);
                HumanProfile p = HumanProfile.preset(preset);
                Baritone.settings().humanProfile.value = p.name();
                Baritone.settings().humanAimSpeed.value = 1.0;
                Baritone.settings().humanOvershoot.value = p.overshoot();
                Baritone.settings().humanTremor.value = p.tremor();
                Baritone.settings().humanReactionMedianMs.value = p.reactionMedianMs();
                Baritone.settings().humanCps.value = p.cpsMean();
                Baritone.settings().humanClickStyle.value = p.clickStyle().name().toLowerCase(Locale.ROOT);
                Baritone.settings().humanMinKeyHoldTicks.value = p.minKeyHoldTicks();
                Baritone.settings().humanSprintConeDeg.value = p.sprintConeDeg();
                Baritone.settings().humanMistakeRate.value = p.mistakeRate();
                logDirect("Switched HumanProfile preset to: " + p.name());
            }
            default -> throw new CommandInvalidStateException("Unknown sub-command. Usage: #lab <start|stop|stats|profile>");
        }
    }

    @Override
    public Stream<String> tabComplete(String label, IArgConsumer args) {
        if (args.hasExactlyOne()) {
            return new TabCompleteHelper()
                    .append("start", "stop", "stats", "profile")
                    .filterPrefix(args.getString())
                    .stream();
        }
        if (args.has(2)) {
            String first = args.peekString(0).toLowerCase(Locale.ROOT);
            if ("profile".equals(first)) {
                return new TabCompleteHelper()
                        .append("casual", "average", "sweaty")
                        .filterPrefix(args.peekString(1))
                        .stream();
            }
        }
        return Stream.empty();
    }

    @Override
    public String getShortDesc() {
        return "Record and inspect HumanLab telemetry and motor profiles";
    }

    @Override
    public List<String> getLongDesc() {
        return Arrays.asList(
                "The lab command controls Pathadol's HumanLab telemetry recorder and human profile presets.",
                "",
                "Usage:",
                "> lab start [tag] - Start recording per-tick telemetry to .minecraft/pathadol/lab/",
                "> lab stop - Stop recording and print summary statistics",
                "> lab stats - View live humanization metrics (aim speed, jerk, GCD rate, key switches)",
                "> lab profile [casual|average|sweaty] - View or switch the active human motor preset"
        );
    }
}
