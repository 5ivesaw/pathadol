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
import baritone.human.AutoTestRunner;
import baritone.human.HumanLabRecorder;
import baritone.human.HumanProfile;
import baritone.human.HumanStyleLearner;

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
        Baritone b = (Baritone) baritone;
        HumanLabRecorder recorder = b.getInputOverrideHandler().getLabRecorder();
        AutoTestRunner runner = b.getInputOverrideHandler().getAutoTestRunner();

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
                if (runner.isRunning()) {
                    runner.abort(ctx, "Stopped by user");
                    return;
                }
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
                HumanProfile p = HumanProfile.applyPreset(preset);
                logDirect(String.format(Locale.ROOT,
                        "Switched HumanProfile preset to: %s (aimSpeed=%.2f, cps=%.1f %s, cone=%.0f)",
                        p.name(), p.aimSpeed(), p.cpsMean(), p.clickStyle(), p.sprintConeDeg()));
            }
            case "mirror" -> {
                args.requireMax(0);
                if (runner.isRunning()) {
                    throw new CommandInvalidStateException("Proving Ground is already running. Use #lab stop to abort.");
                }
                if (!runner.start(ctx, AutoTestRunner.Mode.MIRROR)) {
                    throw new CommandInvalidStateException("Cannot start Mirror Proving Ground right now (player/world not loaded).");
                }
            }
            case "auto", "autotest", "selftest" -> {
                String sub = args.hasAny() ? args.getString().toLowerCase(Locale.ROOT) : "mirror";
                args.requireMax(0);
                if ("stop".equals(sub)) {
                    if (!runner.isRunning()) {
                        logDirect("AutoTest is not currently running.");
                        return;
                    }
                    runner.abort(ctx, "Stopped by user");
                    return;
                }
                if (runner.isRunning()) {
                    throw new CommandInvalidStateException("Proving Ground is already running. Use #lab auto stop to abort.");
                }
                AutoTestRunner.Mode runMode = "fast".equals(sub)
                        ? AutoTestRunner.Mode.FAST
                        : AutoTestRunner.Mode.MIRROR;
                if (!runner.start(ctx, runMode)) {
                    throw new CommandInvalidStateException("Cannot start AutoTest right now (player/world not loaded).");
                }
            }
            case "next", "done" -> {
                args.requireMax(0);
                runner.finishHumanTurn(ctx);
            }
            case "retry" -> {
                args.requireMax(0);
                runner.retryHumanTurn(ctx);
            }
            case "skip" -> {
                args.requireMax(0);
                runner.skipHumanTurn(ctx);
            }
            case "learn" -> {
                String sub = args.hasAny() ? args.getString().toLowerCase(Locale.ROOT) : "stats";
                args.requireMax(0);
                HumanStyleLearner learner = b.getStyleLearner();
                if ("reset".equals(sub)) {
                    learner.reset();
                    logDirect("Cleared learned human style weights (human_learned_profile.json deleted).");
                    return;
                }
                HumanStyleLearner.LearnedState st = HumanStyleLearner.activeLearnedState();
                logDirect(String.format(Locale.ROOT,
                        "HumanStyleLearner (%d trials, %d ticks): aimSpeed=%.2f Fitts(a=%.2f,b=%.2f) cps=%.1f(%s) cone=%.0f lookahead=%.1f obsP90=%.1f",
                        st.trialsLearned, st.totalHumanTicks, st.aimSpeed, st.fittsBaseTicks, st.fittsSlopeTicks,
                        st.cpsMean, st.clickStyle, st.sprintConeDeg, st.steeringLookahead, st.observedP90AimDegPerTick));
            }
            default -> throw new CommandInvalidStateException(
                    "Unknown sub-command. Usage: #lab <mirror|auto [fast|stop]|next|retry|skip|learn|profile|start|stop|stats>");
        }
    }

    @Override
    public Stream<String> tabComplete(String label, IArgConsumer args) throws CommandException {
        if (args.hasExactlyOne()) {
            return new TabCompleteHelper()
                    .append("mirror", "auto", "next", "retry", "skip", "learn", "profile", "start", "stop", "stats")
                    .filterPrefix(args.getString())
                    .stream();
        }
        if (args.has(2)) {
            String first = args.peekString(0).toLowerCase(Locale.ROOT);
            if ("profile".equals(first)) {
                return new TabCompleteHelper()
                        .append("casual", "average", "pro", "sweaty", "learned", "custom")
                        .filterPrefix(args.peekString(1))
                        .stream();
            }
            if ("auto".equals(first) || "autotest".equals(first) || "selftest".equals(first)) {
                return new TabCompleteHelper()
                        .append("mirror", "fast", "stop")
                        .filterPrefix(args.peekString(1))
                        .stream();
            }
            if ("learn".equals(first)) {
                return new TabCompleteHelper()
                        .append("stats", "reset")
                        .filterPrefix(args.peekString(1))
                        .stream();
            }
        }
        return Stream.empty();
    }

    @Override
    public String getShortDesc() {
        return "Mirror Proving Ground, generalized style learner, HumanLab telemetry, and profiles";
    }

    @Override
    public List<String> getLongDesc() {
        return Arrays.asList(
                "Controls Pathadol's 10-stage Extreme Mirror Proving Ground, generalized motor learner, presets, and HumanLab recorder.",
                "",
                "Usage:",
                "> lab mirror - Run 10 extreme Bot-then-You mirror stages & learn your cross-task style",
                "> lab auto [fast|mirror|stop] - Run Mirror Mode (default) or hands-free Fast Bot-only suite",
                "> lab next / retry / skip - Complete, retry, or skip your active YOUR TURN mirror stage",
                "> lab learn [stats|reset] - Inspect or reset learned cross-task human style parameters",
                "> lab profile [casual|average|pro|learned|custom] - View or switch motor preset",
                "> lab start [tag] / stop / stats - Record raw JSONL telemetry"
        );
    }
}
