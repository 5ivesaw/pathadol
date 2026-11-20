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
import baritone.bedwars.BedwarsBlackboxRecorder;
import baritone.bedwars.BedwarsMapCacheIO.CachedMapEntry;
import baritone.bedwars.BedwarsMapCore;
import baritone.bedwars.BedwarsMapCore.BedPOI;
import baritone.bedwars.BedwarsMapCore.BwsRuleset;
import baritone.bedwars.BedwarsProcess;
import baritone.bedwars.BedwarsProcess.TaskMode;
import baritone.brain.BrainProcess.BrainMode;

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
                b.getBrainProcess().cancel();
                if ("stop".equals(sub) && process.isActive()) {
                    process.cancel();
                    logDirect("Stopped active Bedwars task.");
                } else {
                    process.cancel();
                    logDirect(memory.applyRuleset(BwsRuleset.OFF));
                }
            }
            case "auto", "grind" -> {
                String modeArg = args.hasAny() ? args.getString().toLowerCase(Locale.ROOT) : "solo";
                if ("stop".equals(modeArg) || "off".equals(modeArg)) {
                    b.getBrainProcess().setAutoRequeue(false, null);
                    b.getBrainProcess().cancel();
                    process.cancel();
                    logDirect("Stopped autonomous BedWars mode & auto-requeue.");
                    break;
                }
                // One-command full autonomous BedWars setup: ruleset + human/bedwars flags + T=0 scan + auto-requeue + brain + recording
                boolean wantModern = "modern".equals(modeArg) || "1.21".equals(modeArg) || "26.3".equals(modeArg);
                String queueMode = switch (modeArg) {
                    case "doubles", "double", "2v2", "duo" -> "doubles";
                    case "trios", "trio", "3v3", "3s" -> "trios";
                    case "quads", "quad", "4v4", "4s" -> "quads";
                    default -> "solo";
                };
                BrainMode brainMode = ("teacher".equals(modeArg) || "neural".equals(modeArg) || "gym".equals(modeArg))
                        ? BrainMode.fromString(modeArg)
                        : BrainMode.AUTO;
                BwsRuleset targetRuleset = wantModern
                        ? BwsRuleset.MODERN_26_3
                        : (memory.getActiveRuleset() == BwsRuleset.MODERN_26_3 ? BwsRuleset.MODERN_26_3 : BwsRuleset.OLD_1_8);
                Baritone.settings().humanMode.value = true;
                logDirect(memory.applyRuleset(targetRuleset));
                if (!memory.isInWaitingLobby() && ctx.world() != null && ctx.player() != null) {
                    logDirect(memory.captureSnapshotT0());
                }
                b.getBrainProcess().setAutoRequeue(true, queueMode);
                logDirect(b.getBrainProcess().start(brainMode) + " | Auto-Requeue: ON (" + queueMode + ")");
            }
            case "scan" -> logDirect(memory.captureSnapshotT0());
            case "rush" -> {
                String target = args.hasAny() ? args.getString() : "next";
                logDirect(process.startRush(target));
            }
            case "buy", "shop" -> {
                String preset = args.hasAny() ? args.getString() : "auto";
                logDirect(process.startBuy(preset));
            }
            case "defend", "def" -> {
                boolean butterfly = args.hasAny() && "butterfly".equalsIgnoreCase(args.getString());
                logDirect(process.startDefend(butterfly));
            }
            case "blockin", "cocoon" -> logDirect(process.startBlockIn());
            case "fireball", "fb" -> {
                Float yaw = null;
                if (args.hasAny()) {
                    try {
                        yaw = Float.parseFloat(args.getString());
                    } catch (NumberFormatException ignored) {
                    }
                }
                logDirect(process.startFireballJump(yaw));
            }
            case "retreat", "back", "home" -> logDirect(process.startRetreat());
            case "diamonds", "diamond", "dia" -> logDirect(process.startGeneratorRun(TaskMode.DIAMONDS));
            case "emeralds", "emerald", "mid", "em" -> logDirect(process.startGeneratorRun(TaskMode.EMERALDS));
            case "gen", "forge" -> logDirect(process.startGeneratorRun(TaskMode.HOME_GEN));
            case "stash", "deposit" -> logDirect(process.startStash());
            case "recall", "void" -> {
                boolean force = args.hasAny() && "force".equalsIgnoreCase(args.peekString());
                if (force) {
                    args.getString();
                }
                logDirect(process.startRecall(force));
            }
            case "cache" -> executeCacheSubcommand(memory, args);
            case "log", "blackbox", "rec" -> executeLogSubcommand(b.getBedwarsBlackboxRecorder(), memory, args);
            case "status", "info" -> printStatus(memory, process);
            default -> {
                logDirect("Unknown #bws subcommand '" + sub + "'. Usage: #bws <old|modern|auto|off|scan|rush|buy|defend|blockin|fireball|retreat|diamonds|emeralds|gen|stash|recall|cache|log|status>");
            }
        }
    }

    private void executeLogSubcommand(BedwarsBlackboxRecorder rec, BedwarsArenaMemory memory, IArgConsumer args) throws CommandException {
        String action = args.hasAny() ? args.getString().toLowerCase(Locale.ROOT) : "status";
        try {
            switch (action) {
                case "start", "on" -> {
                    String tag = memory.getCurrentMapName() != null ? memory.getCurrentMapName() : memory.getActiveRuleset().toSettingString();
                    logDirect(rec.start(tag, memory.getActiveCore(), memory.getActiveRuleset()));
                    if (rec.getActiveDiagFile() != null) {
                        logDirect("Path: " + rec.getActiveDiagFile().toAbsolutePath());
                    }
                }
                case "human", "manual" -> {
                    String tag = args.hasAny()
                            ? args.getString()
                            : (memory.getCurrentMapName() != null && !"unknown".equalsIgnoreCase(memory.getCurrentMapName())
                                    ? memory.getCurrentMapName()
                                    : "human");
                    logDirect(rec.startHuman(tag, memory.getActiveCore(), memory.getActiveRuleset()));
                    if (memory.isBedwarsActive()) {
                        logDirect("Human observe active (run '#bws off' if you want 100% manual inputs).");
                    }
                    if (rec.getActiveDiagFile() != null) {
                        logDirect("Path: " + rec.getActiveDiagFile().toAbsolutePath());
                    }
                }
                case "stop", "off" -> logDirect(rec.stop("manual_stop", true));
                case "status", "info" -> {
                    if (rec.isRecording()) {
                        logDirect(String.format(
                                Locale.ROOT,
                                "Blackbox RECORDING (%s): ticks=%d, datasetSteps=%d, dropped=%d -> %s",
                                rec.isHumanObserveMode() ? "HUMAN" : "BOT",
                                rec.getTickCounter(), rec.getDatasetStepsWritten(), rec.getDroppedLines(),
                                rec.getActiveDiagFile() != null ? rec.getActiveDiagFile().toAbsolutePath() : rec.getRecordsDir().toAbsolutePath()
                        ));
                    } else {
                        logDirect("Blackbox IDLE (auto-starts with #bws old|modern|auto, or use #bws rec human). Dir: " + rec.getRecordsDir().toAbsolutePath());
                    }
                }
                default -> logDirect("Usage: #bws rec <human|start|stop|status>");
            }
        } catch (IOException ex) {
            logDirect("Blackbox error: " + ex.getMessage());
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
                    .append("old", "modern", "auto", "off", "scan", "rush", "buy", "defend", "blockin", "fireball", "retreat", "diamonds", "emeralds", "gen", "stash", "recall", "cache", "log", "status", "stop")
                    .filterPrefix(args.getString())
                    .stream();
        }
        String first = args.getString().toLowerCase(Locale.ROOT);
        if ("log".equals(first) && args.hasExactlyOne()) {
            return new TabCompleteHelper()
                    .append("status", "start", "stop")
                    .filterPrefix(args.getString())
                    .stream();
        }
        if ("rush".equals(first) && args.hasExactlyOne()) {
            return new TabCompleteHelper()
                    .append("next", "weakest", "side", "red", "blue", "green", "yellow", "aqua", "white", "pink", "gray")
                    .filterPrefix(args.getString())
                    .stream();
        }
        if ("buy".equals(first) && args.hasExactlyOne()) {
            return new TabCompleteHelper()
                    .append("auto", "essentials", "defense", "combat", "utility", "upgrades")
                    .filterPrefix(args.getString())
                    .stream();
        }
        if ("defend".equals(first) && args.hasExactlyOne()) {
            return new TabCompleteHelper()
                    .append("standard", "butterfly")
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
                "> bws auto       - Start full autonomous Neuro-Symbolic Brain + Bedwars strategy loop",
                "> bws off        - Restore standard Survival/PvP settings & stop Brain",
                "> bws scan       - Capture/refresh T=0 arena map, beds, gens, and shops",
                "> bws rush [col|weakest] - Rush & break enemy bed (or weakest defended bed)",
                "> bws buy [preset]       - Open Villager Shop / Team Upgrades and buy preset",
                "> bws defend [butterfly] - Build layered defense around Home Bed",
                "> bws blockin            - Rapidly place protective walls+roof while bed-breaking",
                "> bws fireball [yaw]     - Execute fireball jump impulse toward yaw",
                "> bws retreat            - Retreat cleanly to Home Base",
                "> bws diamonds   - Farm nearest Diamond generator",
                "> bws emeralds   - Farm nearest Emerald generator",
                "> bws gen        - Stand at home forge generator sweet-spot",
                "> bws stash      - Deposit excess valuables into Ender/Team chest",
                "> bws recall     - Tactically void only when carrying no valuable diamonds/emeralds/gold",
                "> bws cache <list|save|load|clear> - Manage .bwsmap disk cache"
        );
    }
}
