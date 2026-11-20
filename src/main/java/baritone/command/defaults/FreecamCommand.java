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

import baritone.api.IBaritone;
import baritone.api.command.Command;
import baritone.api.command.argument.IArgConsumer;
import baritone.api.command.exception.CommandException;
import baritone.api.command.helpers.TabCompleteHelper;
import baritone.human.FreecamController;
import baritone.human.FreecamController.Mode;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * Pure client-side Freecam command ({@code #freecam} / {@code #fc}) with zero player/bot interference:
 * {@code #freecam fly [speed]} (free-flying observer camera) and {@code #freecam look} (360&deg; client-only freelook).
 */
public final class FreecamCommand extends Command {

    public FreecamCommand(IBaritone baritone) {
        super(baritone, "freecam", "fc");
    }

    @Override
    public void execute(String label, IArgConsumer args) throws CommandException {
        if (!args.hasAny()) {
            if (FreecamController.isActive()) {
                logDirect(FreecamController.setMode(Mode.OFF, ctx));
            } else {
                logDirect("Freecam is OFF. Usage: #freecam <fly [speed]|look|off|speed <val>>");
            }
            return;
        }
        String sub = args.getString().toLowerCase(Locale.ROOT);
        switch (sub) {
            case "fly", "free", "roam" -> {
                if (args.hasAny()) {
                    try {
                        FreecamController.setFlySpeed(Double.parseDouble(args.getString()));
                    } catch (NumberFormatException ignored) {
                    }
                }
                logDirect(FreecamController.setMode(Mode.FLY, ctx));
            }
            case "look", "freelook", "orbit" -> logDirect(FreecamController.setMode(Mode.LOOK, ctx));
            case "off", "stop", "disable" -> logDirect(FreecamController.setMode(Mode.OFF, ctx));
            case "speed" -> {
                if (!args.hasAny()) {
                    logDirect(String.format(Locale.ROOT, "Freecam fly speed: %.2f (usage: #freecam speed <0.1..4.0>)", FreecamController.getFlySpeed()));
                } else {
                    try {
                        double s = FreecamController.setFlySpeed(Double.parseDouble(args.getString()));
                        logDirect(String.format(Locale.ROOT, "Freecam fly speed -> %.2f", s));
                    } catch (NumberFormatException e) {
                        logDirect("Invalid speed value. Example: #freecam speed 1.2");
                    }
                }
            }
            default -> logDirect("Unknown #freecam mode '" + sub + "'. Usage: #freecam <fly [speed]|look|off|speed <val>>");
        }
    }

    @Override
    public Stream<String> tabComplete(String label, IArgConsumer args) throws CommandException {
        if (args.hasExactlyOne()) {
            return new TabCompleteHelper()
                    .append("fly", "look", "off", "speed")
                    .filterPrefix(args.getString())
                    .stream();
        }
        return Stream.empty();
    }

    @Override
    public String getShortDesc() {
        return "Client-side zero-interference freecam (fly / look / off)";
    }

    @Override
    public List<String> getLongDesc() {
        return Arrays.asList(
                "Purely client-side Freecam that never interferes with the real player or active bot processes.",
                "",
                "Usage:",
                "> freecam fly [speed] - Detached free-fly camera (WASD/Space/Shift + Ctrl boost + mouse)",
                "> freecam look        - Client-only 360-deg free-look (1st-person & F5 orbit, zero aim override)",
                "> freecam speed <val> - Set free-fly speed (0.1 .. 4.0, default 0.75)",
                "> freecam off         - Return camera and input to normal"
        );
    }
}
