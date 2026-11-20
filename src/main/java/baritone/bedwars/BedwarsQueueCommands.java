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

package baritone.bedwars;

import com.mojang.brigadier.tree.CommandNode;
import com.mojang.brigadier.tree.LiteralCommandNode;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.game.ClientboundCommandSuggestionsPacket;
import net.minecraft.network.protocol.game.ServerboundCommandSuggestionPacket;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/** Learns and tries server-advertised BedWars navigation commands during a lobby visit. */
final class BedwarsQueueCommands {

    private static final Pattern MODE_TARGET = Pattern.compile("^(?:bed-?wars|bw)(?:[-_]?([a-z0-9]+))?$");
    private static final Pattern INVOCATION = Pattern.compile("/([a-z0-9_?:-]+(?: [a-z0-9_-]+){0,3})", Pattern.CASE_INSENSITIVE);
    private static final Pattern HELP_SEPARATOR = Pattern.compile("\\s+[-–—|]\\s+|:\\s+|\\s+(?:(?:right|left) )?click\\b", Pattern.CASE_INSENSITIVE);
    private static final Set<String> NAVIGATION = Set.of("play", "join", "queue", "lobby", "server", "hub");
    private static final Set<String> JOIN_ACTIONS = Set.of("play", "join", "queue");
    private static final int DISCOVERY_STEPS = 5;
    private static final AtomicInteger SUGGESTION_IDS = new AtomicInteger(1_000_000);
    private static final int MAX_ROUTE_TRIES = 3;

    private record Route(String command, String mode, boolean numbered, int confidence) {}

    private static final class Discovery {
        private int step;
        private long resumeAt;
    }

    private final Map<String, Route> routes = new HashMap<>();
    private final Set<String> roots = new HashSet<>();
    private final Set<String> tried = new HashSet<>();
    private final Discovery hub = new Discovery();
    private final Discovery bedwars = new Discovery();
    private Connection connection;
    private Object commandTree;
    private int suggestionId = -1;
    private String suggestionInput;
    private long helpUntil;
    private int helpLinesLeft;
    private long tick;
    private long commandWaitUntil;

    synchronized void reset(Connection nextConnection) {
        connection = nextConnection;
        commandTree = null;
        roots.clear();
        routes.clear();
        rearm();
        hub.step = 0;
        hub.resumeAt = 0;
        bedwars.step = 0;
        bedwars.resumeAt = 0;
        tick = 0;
    }

    synchronized void rearm() {
        tried.clear();
        commandWaitUntil = 0;
        suggestionId = -1;
        suggestionInput = null;
        helpUntil = 0;
        helpLinesLeft = 0;
    }

    synchronized void prepareConnection(Connection current) {
        if (connection != current) {
            reset(current);
        }
    }

    /**
     * Returns true while issuing a route or giving a discovery request time to resolve.
     * Each lobby type has a bounded set of read-only discovery requests per connection.
     */
    synchronized boolean tick(ClientPacketListener listener, String mode, boolean inBedwarsLobby,
                              Consumer<String> announce) {
        if (listener == null || connection == null) {
            return false;
        }
        tick++;
        if (tick < commandWaitUntil) {
            return true;
        }

        Discovery discovery = inBedwarsLobby ? bedwars : hub;
        if (commandTree != listener.getCommands()) {
            commandTree = listener.getCommands();
            readCommandTree(listener);
        }
        String route = findRoute(mode, inBedwarsLobby, discovery.step >= DISCOVERY_STEPS || inBedwarsLobby);
        if (route != null && tried.size() < MAX_ROUTE_TRIES) {
            tried.add(route);
            commandWaitUntil = tick + 70;
            announce.accept("Joining with /" + route);
            listener.sendCommand(route);
            return true;
        }
        if (discovery.step >= DISCOVERY_STEPS && tick >= discovery.resumeAt) {
            return false;
        }
        if (tick < discovery.resumeAt) {
            return true;
        }

        switch (discovery.step++) {
            case 0 -> requestSuggestions(listener, "/");
            case 1 -> requestSuggestions(listener, "/bed");
            case 2 -> requestSuggestions(listener, suggestedArgumentInput());
            case 3 -> requestHelp(listener, "help");
            case 4 -> requestHelp(listener, roots.contains("commands") ? "commands" : roots.contains("?help") ? "?help" : "?");
            default -> throw new IllegalStateException("Invalid queue command discovery step");
        }
        discovery.resumeAt = tick + 22;
        return true;
    }

    synchronized void onSuggestions(Connection source, ClientboundCommandSuggestionsPacket packet) {
        if (source != connection || packet.id() != suggestionId || suggestionInput == null) {
            return;
        }
        for (var suggestion : packet.toSuggestions().getList()) {
            try {
                String candidate = suggestion.apply(suggestionInput);
                if (candidate.indexOf(' ') < 0) {
                    roots.add(candidate.replaceFirst("^/", "").toLowerCase(Locale.ROOT));
                }
                learn(candidate, null, 2);
            } catch (IndexOutOfBoundsException ignored) {
                // Ignore ranges that do not refer to the requested command input.
            }
        }
        suggestionId = -1;
        suggestionInput = null;
    }

    synchronized void onHelp(Connection source, String text) {
        if (source != connection || helpLinesLeft <= 0 || tick > helpUntil) {
            return;
        }
        for (String line : text.split("\\R", 17)) {
            if (helpLinesLeft-- <= 0) break;
            learnAdvertisement(line);
        }
    }

    synchronized void learnAdvertisement(String label) {
        if (label == null || label.length() > 1024) {
            return;
        }
        String mode = BedwarsAutoQueueController.explicitQueueMode(label);
        int slash = label.indexOf('/');
        if (slash < 0) {
            return;
        }
        var separator = HELP_SEPARATOR.matcher(label);
        String commandPart = separator.find(slash) ? label.substring(slash, separator.start()) : label.substring(slash);
        var match = INVOCATION.matcher(commandPart);
        while (match.find()) {
            learn(match.group(), mode, 3);
        }
    }

    synchronized String findRoute(String mode, boolean inBedwarsLobby, boolean allowGeneric) {
        String best = null;
        int bestScore = 0;
        for (Route route : routes.values()) {
            boolean exactMode = mode.equals(route.mode());
            if (tried.contains(route.command()) || (!exactMode
                    && (route.mode() != null || route.numbered() || inBedwarsLobby || !allowGeneric))) {
                continue;
            }
            int score = (exactMode ? 10 : 0) + route.confidence();
            if (score > bestScore || (score == bestScore && best != null && route.command().compareTo(best) < 0)) {
                best = route.command();
                bestScore = score;
            }
        }
        return best;
    }

    private String suggestedArgumentInput() {
        for (String prefix : new String[] {"play", "join", "queue", "server", "lobby"}) {
            if (roots.contains(prefix)) {
                return "/" + prefix + " bed";
            }
        }
        return roots.contains("bedwars") ? "/bedwars " : "/bw";
    }

    private void requestSuggestions(ClientPacketListener listener, String input) {
        suggestionId = SUGGESTION_IDS.getAndIncrement();
        suggestionInput = input;
        listener.send(new ServerboundCommandSuggestionPacket(suggestionId, input));
    }

    private void requestHelp(ClientPacketListener listener, String command) {
        helpUntil = tick + 22;
        helpLinesLeft = 64;
        listener.sendCommand(command);
    }

    private void readCommandTree(ClientPacketListener listener) {
        for (var child : listener.getCommands().getRoot().getChildren()) {
            if (child instanceof LiteralCommandNode<?>) {
                String root = child.getName().toLowerCase(Locale.ROOT);
                roots.add(root);
                if (NAVIGATION.contains(root) || MODE_TARGET.matcher(root).matches()) {
                    readLiteralPaths(child, child.getName(), 1);
                }
            }
        }
    }

    private void readLiteralPaths(CommandNode<?> node, String path, int depth) {
        if (node.getCommand() != null) {
            learn(path, null, 1);
        }
        if (depth < 4) {
            for (var child : node.getChildren()) {
                if (child instanceof LiteralCommandNode<?>) {
                    readLiteralPaths(child, path + " " + child.getName(), depth + 1);
                }
            }
        }
    }

    private void learn(String advertised, String modeHint, int confidence) {
        String command = advertised.strip().replaceFirst("^/", "");
        if (command.isEmpty() || command.length() > 80) {
            return;
        }
        String[] words = command.toLowerCase(Locale.ROOT).split(" +");
        int index = NAVIGATION.contains(words[0]) ? 1 : 0;
        if (index >= words.length) {
            return;
        }
        var target = MODE_TARGET.matcher(words[index++]);
        if (!target.matches()) {
            return;
        }
        String suffix = target.group(1);
        boolean numbered = suffix != null && suffix.matches("[0-9]+");
        String mode = suffix == null || numbered ? null : BedwarsAutoQueueController.explicitQueueMode(suffix);
        if (suffix != null && !numbered && mode == null) {
            return;
        }
        if (index < words.length && JOIN_ACTIONS.contains(words[index]) && ++index == words.length) {
            return;
        }
        if (index < words.length) {
            String word = words[index++];
            String explicit = word.matches("[a-z0-9]+") ? BedwarsAutoQueueController.explicitQueueMode(word) : null;
            if (explicit == null || (mode != null && !mode.equals(explicit))) {
                return;
            }
            mode = explicit;
        }
        if (index != words.length || (mode != null && modeHint != null && !mode.equals(modeHint))) {
            return;
        }
        String resolvedMode = mode == null ? modeHint : mode;
        Route learned = new Route(command, resolvedMode, numbered, confidence);
        Route current = routes.get(command);
        if (current == null || confidence > current.confidence()
                || (confidence == current.confidence() && current.mode() == null && resolvedMode != null)) {
            routes.put(command, learned);
        }
    }
}
