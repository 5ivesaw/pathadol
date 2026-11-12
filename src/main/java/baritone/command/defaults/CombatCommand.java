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
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

public class CombatCommand extends Command {

    public CombatCommand(IBaritone baritone) {
        super(baritone, "combat", "fight");
    }

    @Override
    public void execute(String label, IArgConsumer args) throws CommandException {
        if (!args.hasAny()) {
            baritone.getCombatProcess().attack(e -> e instanceof Mob);
            logDirect("Engaging hostile/nearby mobs (combat mode: " + Baritone.settings().humanCombatMode.value + ")");
            return;
        }
        String sub = args.getString().toLowerCase(Locale.ROOT);
        switch (sub) {
            case "players", "player" -> {
                args.requireMax(0);
                baritone.getCombatProcess().attack(e -> e instanceof Player);
                logDirect("Combat targeting players (" + Baritone.settings().humanCombatMode.value + ")");
            }
            case "mobs", "mob" -> {
                args.requireMax(0);
                baritone.getCombatProcess().attack(e -> e instanceof Mob);
                logDirect("Combat targeting mobs (" + Baritone.settings().humanCombatMode.value + ")");
            }
            case "all", "entities" -> {
                args.requireMax(0);
                baritone.getCombatProcess().attack(e -> e instanceof LivingEntity);
                logDirect("Combat targeting all living entities (" + Baritone.settings().humanCombatMode.value + ")");
            }
            case "stop", "off", "cancel" -> {
                args.requireMax(0);
                baritone.getCombatProcess().cancel();
                logDirect("Combat stopped");
            }
            case "mode" -> {
                args.requireMin(1);
                String mode = args.getString().toLowerCase(Locale.ROOT);
                args.requireMax(0);
                if (!"1.8".equals(mode) && !"modern".equals(mode) && !"auto".equals(mode)) {
                    throw new CommandInvalidStateException("Unknown combat mode '" + mode + "'. Expected: 1.8, modern, or auto");
                }
                Baritone.settings().humanCombatMode.value = mode;
                logDirect("Set humanCombatMode to: " + mode);
            }
            default -> throw new CommandInvalidStateException("Usage: #combat <players|mobs|all|stop|mode <1.8|modern|auto>>");
        }
    }

    @Override
    public Stream<String> tabComplete(String label, IArgConsumer args) throws CommandException {
        if (args.hasExactlyOne()) {
            return new TabCompleteHelper()
                    .append("players", "mobs", "all", "stop", "mode")
                    .filterPrefix(args.getString())
                    .stream();
        }
        if (args.has(2) && "mode".equalsIgnoreCase(args.peekString(0))) {
            return new TabCompleteHelper()
                    .append("1.8", "modern", "auto")
                    .filterPrefix(args.peekString(1))
                    .stream();
        }
        return Stream.empty();
    }

    @Override
    public String getShortDesc() {
        return "Engage nearby targets with humanized combat";
    }

    @Override
    public List<String> getLongDesc() {
        return Arrays.asList(
                "Controls Pathadol's humanized CombatProcess.",
                "",
                "Usage:",
                "> combat players - Engage nearby players",
                "> combat mobs - Engage nearby mobs",
                "> combat all - Engage all nearby living entities",
                "> combat mode <1.8|modern|auto> - Switch combat ruleset (CPS/W-tap vs cooldown crits)",
                "> combat stop - Disengage combat"
        );
    }
}
