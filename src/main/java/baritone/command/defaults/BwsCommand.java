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
import baritone.api.command.helpers.TabCompleteHelper;
import baritone.bedwars.BedwarsArenaMemory;
import baritone.bedwars.BedwarsMapCacheIO.CachedMapEntry;
import baritone.bedwars.BedwarsMapCore;
import baritone.bedwars.BedwarsMapCore.BedPOI;
import baritone.bedwars.BedwarsMapCore.BwsRuleset;
import baritone.bedwars.BedwarsProcess;
import baritone.bedwars.BedwarsProcess.TaskMode;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * Bedwars Switch command ({@code #bws}) controlling 1.8 vs Modern rulesets, T=0 arena scan,
 * {@code .bwsmap} cache management, and autonomous tactical Bedwars tasks.
 */
public final class BwsCommand extends Command {

    public BwsCommand(IBaritone baritone) {
        super(baritone, "bws", "bedwars");
    }

    @Override
    public void execute(String label, IArgConsumer args) throws CommandException {
        if (!(baritone instanceof Baritone b)) {
            logDirect("Bedwars controller unavailable on this Baritone instance.");
            return;
        }
        BedwarsArenaMemory memory = b.getBedwarsArenaMemory();
        BedwarsProcess process = b.getBedwarsProcess();

        if (!args.hasAny()) {
            printStatus(memory, process);
            return;
        }

        String sub = args.getString().toLowerCase(Locale.ROOT);
        switch (sub) {
            case "old", "1.8" -> logDirect(memory.applyRuleset(BwsRuleset.OLD_1_8));
            case "modern", "26.3", "1.21" -> logDirect(memory.applyRuleset(BwsRuleset.MODERN_26_3));
            case "off", "disable", "stop" -> {
                if ("stop".equals(sub) && process.isActive()) {
                    process.cancel();
                    logDirect("Stopped active Bedwars task.");
                } else {
                    logDirect(memory.applyRuleset(BwsRuleset.OFF));
                }
            }
            case "scan" -> logDirect(memory.captureSnapshotT0());
            case "rush" -> {
                String target = args.hasAny() ? args.getString() : "next";
                logDirect(process.startRush(target));
            }
            case "diamonds", "diamond", "dia" -> logDirect(process.startGeneratorRun(TaskMode.DIAMONDS));
            case "emeralds", "emerald", "mid", "em" -> logDirect(process.startGeneratorRun(TaskMode.EMERALDS));
            case "gen", "forge", "home" -> logDirect(process.startGeneratorRun(TaskMode.HOME_GEN));
            case "stash", "deposit" -> logDirect(process.startStash());
            case "recall", "void" -> {
                boolean force = args.hasAny() && "force".equalsIgnoreCase(args.peekString());
                if (force) {
                    args.getString();
                }
                logDirect(process.startRecall(force));
            }
            case "cache" -> executeCacheSubcommand(memory, args);
            case "status", "info" -> printStatus(memory, process);
            default -> {
                logDirect("Unknown #bws subcommand '" + sub + "'. Usage: #bws <old|modern|off|scan|rush|diamonds|emeralds|gen|stash|recall|cache|status>");
            }
        }
    }

    private void executeCacheSubcommand(BedwarsArenaMemory memory, IArgConsumer args) throws CommandException {
        String action = args.hasAny() ? args.getString().toLowerCase(Locale.ROOT) : "list";
        try {
            switch (action) {
                case "list" -> {
                    List<CachedMapEntry> entries = memory.listCacheEntries();
                    if (entries.isEmpty()) {
                        logDirect("No cached .bwsmap files in " + memory.getCacheDir());
                        return;
                    }
                    logDirect("Cached Bedwars maps (" + entries.size() + "):");
                    for (CachedMapEntry e : entries) {
                        logDirect(String.format(
                                Locale.ROOT,
                                " - [%s] %s (%d bytes, fp=%s)",
                                e.serverId(), e.mapName(), e.fileBytes(), Long.toUnsignedString(e.structuralFingerprint(), 16)
                        ));
                    }
                }
                case "save" -> {
                    String mapName = args.hasAny() ? args.getString() : null;
                    logDirect(memory.saveCurrentToCache(mapName));
                }
                case "load" -> {
                    String mapName = args.hasAny() ? args.getString() : memory.getCurrentMapName();
                    logDirect(memory.loadCacheByName(mapName));
                }
                case "clear" -> {
                    int n = memory.clearCache();
                    logDirect("Deleted " + n + " cached .bwsmap file(s).");
                }
                default -> logDirect("Usage: #bws cache <list|save [name]|load <name>|clear>");
            }
        } catch (IOException ex) {
            logDirect("Cache operation failed: " + ex.getMessage());
        }
    }

    private void printStatus(BedwarsArenaMemory memory, BedwarsProcess process) {
        BedwarsMapCore core = memory.getActiveCore();
        logDirect("Bedwars Ruleset: " + memory.getActiveRuleset().toSettingString()
                + " | Active Task: " + (process.isActive() ? process.displayName0() : "idle")
                + " | Map: " + memory.getCurrentMapName() + " @ " + memory.getCurrentServerId());
        if (core != null) {
            int livingEnemyBeds = 0;
            for (BedPOI b : core.getBeds()) {
                if (!b.isHome() && !b.destroyed()) {
                    livingEnemyBeds++;
                }
            }
            logDirect(String.format(
                    Locale.ROOT,
                    "Arena Center=(%.1f, %.1f), VoidY=%d, MaxBuildY=%d, Home=%s, LivingEnemyBeds=%d, Gens=%d, Shops=%d",
                    core.center2X() * 0.5, core.center2Z() * 0.5, core.voidFloorY(), core.mapMaxBuildY(),
                    core.homeTeam(), livingEnemyBeds, core.getGens().size(), core.getShopNpcs().size()
            ));
        } else {
            logDirect("Arena map not scanned yet (run '#bws scan' or '#bws old|modern' inside a Bedwars arena).");
        }
    }

    @Override
    public Stream<String> tabComplete(String label, IArgConsumer args) throws CommandException {
        if (args.hasExactlyOne()) {
            return new TabCompleteHelper()
                    .append("old", "modern", "off", "scan", "rush", "diamonds", "emeralds", "gen", "stash", "recall", "cache", "status", "stop")
                    .filterPrefix(args.getString())
                    .stream();
        }
        String first = args.getString().toLowerCase(Locale.ROOT);
        if ("rush".equals(first) && args.hasExactlyOne()) {
            return new TabCompleteHelper()
                    .append("next", "side", "red", "blue", "green", "yellow", "aqua", "white", "pink", "gray")
                    .filterPrefix(args.getString())
                    .stream();
        }
        if ("cache".equals(first) && args.hasExactlyOne()) {
            return new TabCompleteHelper()
                    .append("list", "save", "load", "clear")
                    .filterPrefix(args.getString())
                    .stream();
        }
        if ("recall".equals(first) && args.hasExactlyOne()) {
            return new TabCompleteHelper()
                    .append("force")
                    .filterPrefix(args.getString())
                    .stream();
        }
        return Stream.empty();
    }

    @Override
    public String getShortDesc() {
        return "Toggle Bedwars mode (1.8 / modern / off) and run tactical Bedwars automation";
    }

    @Override
    public List<String> getLongDesc() {
        return Arrays.asList(
                "Switches Pathadol into specialized Bedwars mode with T=0 symmetry map memory & compact .bwsmap cache.",
                "",
                "Usage:",
                "> bws old        - Enable Bedwars 1.8 ruleset (1.8 PvP, 1.54f crouch eye, cheap wool bridging)",
                "> bws modern     - Enable Bedwars modern 26.3 ruleset (cooldown/shield PvP, 1.27f crouch eye)",
                "> bws off        - Restore standard Survival/PvP settings",
                "> bws scan       - Capture/refresh T=0 arena map, beds, gens, and shops",
                "> bws rush [col] - Rush & break enemy bed using X-Ray weakest-layer defense solver",
                "> bws diamonds   - Farm nearest Diamond generator",
                "> bws emeralds   - Farm nearest Emerald generator",
                "> bws gen        - Stand at home forge generator sweet-spot",
                "> bws stash      - Deposit excess valuables into Ender/Team chest",
                "> bws recall     - Tactically void only when carrying no valuable diamonds/emeralds/gold",
                "> bws cache <list|save|load|clear> - Manage .bwsmap disk cache"
        );
    }
}
