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
import baritone.brain.BrainActionSpace.StyleSliders;
import baritone.brain.BrainProcess;
import baritone.brain.BrainProcess.BrainMode;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * Command interface for the Neuro-Symbolic "Brain + Muscle" Bedwars/PvP strategy layer ({@code #brain}).
 */
public final class BrainCommand extends Command {

    public BrainCommand(IBaritone baritone) {
        super(baritone, "brain", "nn");
    }

    @Override
    public void execute(String label, IArgConsumer args) throws CommandException {
        if (!(baritone instanceof Baritone b)) {
            return;
        }
        BrainProcess brain = b.getBrainProcess();
        if (!args.hasAny()) {
            logDirect(brain.getStatusSummary());
            return;
        }

        String sub = args.getString().toLowerCase(Locale.ROOT);
        switch (sub) {
            case "start", "on", "auto" -> logDirect(brain.start(BrainMode.AUTO));
            case "stop", "off", "cancel" -> {
                brain.cancel();
                logDirect("Brain stopped.");
            }
            case "mode" -> {
                if (!args.hasAny()) {
                    logDirect("Brain mode: " + brain.getMode().name() + " | use: #brain mode <auto|neural|teacher|gym|off>");
                    return;
                }
                BrainMode next = BrainMode.fromString(args.getString());
                if (next == BrainMode.OFF) {
                    brain.cancel();
                    logDirect("Brain stopped.");
                } else {
                    logDirect(brain.start(next));
                }
            }
            case "reload", "load" -> {
                String target = args.hasAny() ? args.getString() : "";
                logDirect(brain.reloadModel(target));
            }
            case "record", "rec" -> executeRecordSubcommand(brain, args);
            case "bridge", "gym" -> executeBridgeSubcommand(brain, args);
            case "sliders", "slider" -> executeSlidersSubcommand(brain, args);
            case "status", "info" -> logDirect(brain.getStatusSummary());
            default -> {
                logDirect("Usage: #brain <start|stop|mode|reload|record|bridge|sliders|status>");
            }
        }
    }

    private void executeRecordSubcommand(BrainProcess brain, IArgConsumer args) throws CommandException {
        String action = args.hasAny() ? args.getString().toLowerCase(Locale.ROOT) : "status";
        switch (action) {
            case "start", "on" -> {
                String tag = args.hasAny() ? args.getString() : "bw";
                try {
                    Path file = brain.getRecorder().start(tag);
                    logDirect("Brain trajectory recording -> " + file.getFileName());
                } catch (IOException e) {
                    logDirect("Failed to start brain recording: " + e.getMessage());
                }
            }
            case "stop", "off" -> {
                Path closed = brain.getRecorder().stop();
                logDirect("Brain trajectory recording stopped ("
                        + brain.getRecorder().getStepCount() + " steps, R="
                        + String.format(Locale.ROOT, "%.2f", brain.getRecorder().getCumulativeReward())
                        + (closed != null ? ", file=" + closed.getFileName() : "") + ").");
            }
            default -> {
                logDirect("Brain recording: "
                        + (brain.getRecorder().isRecording() ? "ACTIVE" : "OFF")
                        + " | dir=" + brain.getRecorder().getDataDir()
                        + " | steps=" + brain.getRecorder().getStepCount());
            }
        }
    }

    private void executeBridgeSubcommand(BrainProcess brain, IArgConsumer args) throws CommandException {
        String action = args.hasAny() ? args.getString().toLowerCase(Locale.ROOT) : "status";
        switch (action) {
            case "start", "on" -> {
                int port = Baritone.settings().brainGymPort.value;
                if (args.hasAny()) {
                    try {
                        port = Math.max(1024, Math.min(65535, Integer.parseInt(args.getString())));
                    } catch (NumberFormatException ignored) {
                    }
                }
                try {
                    brain.getGymBridge().start(port);
                    logDirect("Brain Gym loopback bridge listening on 127.0.0.1:" + port);
                } catch (IOException e) {
                    logDirect("Failed to start Brain Gym bridge: " + e.getMessage());
                }
            }
            case "stop", "off" -> {
                brain.getGymBridge().stop();
                logDirect("Brain Gym bridge stopped.");
            }
            default -> {
                logDirect("Brain Gym bridge: " + brain.getGymBridge().getStatus());
            }
        }
    }

    private void executeSlidersSubcommand(BrainProcess brain, IArgConsumer args) throws CommandException {
        if (!args.hasAny()) {
            logDirect("Usage: #brain sliders <auto | risk speed caution aggr>");
            return;
        }
        String first = args.getString();
        if ("auto".equalsIgnoreCase(first) || "reset".equalsIgnoreCase(first)) {
            logDirect(brain.setManualSliders(null));
            return;
        }
        try {
            float risk = Float.parseFloat(first);
            float speed = args.hasAny() ? Float.parseFloat(args.getString()) : 0.72f;
            float caution = args.hasAny() ? Float.parseFloat(args.getString()) : 0.45f;
            float aggr = args.hasAny() ? Float.parseFloat(args.getString()) : 0.55f;
            logDirect(brain.setManualSliders(StyleSliders.fromArray(new float[]{risk, speed, caution, aggr})));
        } catch (NumberFormatException e) {
            logDirect("Invalid slider floats. Usage: #brain sliders 0.7 0.85 0.4 0.75");
        }
    }

    @Override
    public Stream<String> tabComplete(String label, IArgConsumer args) {
        return Stream.of("start", "stop", "mode", "reload", "record", "bridge", "sliders", "status");
    }

    @Override
    public String getShortDesc() {
        return "Neuro-Symbolic Bedwars/PvP Brain + Muscle coordinator";
    }

    @Override
    public List<String> getLongDesc() {
        return Arrays.asList(
                "Coordinates high-level tactical Bedwars & PvP maneuvers via .pbrain neural policy, RuleTeacher, or Python Gym.",
                "Usage:",
                "> #brain start - Start Brain in AUTO mode (uses pathadol/brain/active.pbrain or RuleTeacher).",
                "> #brain mode <auto|neural|teacher|gym|off> - Switch policy source.",
                "> #brain reload [file.pbrain] - Hot-reload .pbrain checkpoint.",
                "> #brain record <start [tag]|stop|status> - Record 72-float trajectory JSONL for Colab training.",
                "> #brain bridge <start [port]|stop|status> - Start localhost 127.0.0.1 Gym JSONL bridge."
        );
    }
}
