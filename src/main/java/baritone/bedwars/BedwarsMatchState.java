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

import baritone.bedwars.BedwarsMapCore.GenType;
import baritone.bedwars.BedwarsMapCore.TeamColor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Cross-server pure-Java Bedwars match intelligence tracker parsing scoreboard sidebars,
 * chat messages, actionbars, titles/subtitles, bossbars, and player-team lists into
 * structured phase timers, per-team bed/alive status, upgrades, and player teams.
 */
public final class BedwarsMatchState {

    public enum MatchPhase {
        UNKNOWN,
        PRE_GAME,
        DIAMOND_II,
        EMERALD_II,
        DIAMOND_III,
        EMERALD_III,
        BED_GONE,
        SUDDEN_DEATH,
        GAME_END,
        ENDED
    }

    public enum BedStatus {
        UNKNOWN,
        ALIVE,
        DESTROYED
    }

    public record TeamStatusSnapshot(
            TeamColor team,
            BedStatus bedStatus,
            int alivePlayers,
            boolean eliminated
    ) {}

    public record ParsedChatOutcome(
            TeamColor assignedHomeTeam,
            TeamColor destroyedBedTeam,
            boolean homeBedDestroyed,
            boolean insufficientMaterials,
            boolean countdownActive,
            boolean matchStarted,
            boolean finalKillEvent,
            int countdownSeconds,
            String eventTag
    ) {
        public static final ParsedChatOutcome NONE = new ParsedChatOutcome(
                TeamColor.UNKNOWN, TeamColor.UNKNOWN, false, false, false, false, false, -1, ""
        );
    }

    private static final Pattern COLOR_CODES = Pattern.compile("\u00A7.");
    private static final Pattern NON_ASCII_DECOR = Pattern.compile("[^\\x20-\\x7E✔✓✗✘×✦►•|:/\\[\\]()\\-+_.]");
    private static final Pattern MMSS_PATTERN = Pattern.compile("\\b(\\d{1,2}):(\\d{2})\\b");
    private static final Pattern SECS_PATTERN = Pattern.compile("\\b(\\d{1,3})\\s*(?:seconds?|secs?|s)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern MINS_SECS_PATTERN = Pattern.compile("\\b(\\d{1,2})m(?:\\s*(\\d{1,2})s)?\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern TEAM_ASSIGN_PATTERN = Pattern.compile(
            "(?i)(?:you are (?:now )?(?:in|on) team|joined team|team assigned)\\s*[:>-]?\\s*(light[_ ]?blue|aqua|cyan|pink|magenta|light[_ ]?purple|red|blue|green|lime|yellow|white|gray|grey)\\b"
    );
    private static final Pattern BED_DESTROYED_PATTERN = Pattern.compile(
            "(?i)\\b(aqua|cyan|pink|magenta|red|blue|green|lime|yellow|white|gray|grey)\\s+bed\\b.*?(?:destroyed|broken|eliminated|gone|lost)"
    );
    private static final Pattern BED_DESTRUCTION_HEADER_PATTERN = Pattern.compile(
            "(?i)bed destruction.*?(?:>|»|:)\\s*(aqua|cyan|pink|magenta|red|blue|green|lime|yellow|white|gray|grey)\\b"
    );
    private static final Pattern TEAM_LINE_FULL_PATTERN = Pattern.compile(
            "(?i)^([^A-Za-z]{0,8}|[\\p{L}0-9►•\\s\\[\\]]{0,6})\\b(red|blue|green|lime|yellow|aqua|cyan|white|pink|magenta|gray|grey)\\b\\s*[:\\-\\]>]*\\s*(.*)$"
    );
    private static final Pattern TEAM_LINE_INITIAL_PATTERN = Pattern.compile(
            "^([RBGAYWP])\\s+([A-Za-z]+)\\s*[:\\-\\]>]*\\s*(.*)$"
    );
    private static final Pattern TRAILING_COUNT_PATTERN = Pattern.compile("\\b(\\d{1,2})\\b");
    private static final Pattern DEATH_VICTIM_PATTERN = Pattern.compile(
            "^(?:\\[[^\\]]+\\]\\s*)?(?:(?:RED|BLUE|GREEN|YELLOW|AQUA|WHITE|PINK|GRAY|GREY)\\s+)?([A-Za-z0-9_]{2,16})\\s+(?:was\\s+(?:killed|slain|knocked|thrown|shot|blown|pushed|eliminated|voided|hit\\s+into)|has\\s+been\\s+killed|fell\\s+(?:into|to|from)|died|burnt|drowned)",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern GEN_SPAWN_SEC_PATTERN = Pattern.compile(
            "(?i)(?:spawns?\\s+in|next\\s+in|in)\\s*(\\d{1,3})\\s*(?:seconds?|secs?|s)?\\b"
    );

    public record GenSpawnTimer(GenType type, int x, int z, int reportedSeconds, int tier, long updatedMs) {
        public double remainingSeconds(long nowMs) {
            double elapsed = Math.max(0.0, (nowMs - updatedMs) / 1000.0);
            return Math.max(0.0, reportedSeconds - elapsed);
        }
    }

    public record ParsedItemMeta(
            int sharpnessLevel,
            int protectionLevel,
            int efficiencyLevel,
            int knockbackLevel,
            boolean isLobbyItem,
            boolean isPlayAgainItem,
            boolean isAlreadyOwned,
            boolean isTrapItem
    ) {}

    private final EnumMap<TeamColor, TeamStatusMutable> teams = new EnumMap<>(TeamColor.class);
    private final Map<String, TeamColor> playerTeams = new HashMap<>();
    private final List<TeamColor> discoveredTeamOrder = new ArrayList<>();
    private final Map<String, Long> respawningPlayersUntilMs = new HashMap<>();
    private final EnumMap<TeamColor, Long> teamRespawnUntilMs = new EnumMap<>(TeamColor.class);
    private final Map<Long, GenSpawnTimer> genSpawnTimers = new HashMap<>();

    private volatile TeamColor homeTeam = TeamColor.UNKNOWN;
    private volatile String mapName = "unknown";
    private volatile String modeLabel = "";
    private volatile MatchPhase nextPhase = MatchPhase.UNKNOWN;
    private volatile int nextPhaseSeconds = -1;
    private volatile int diamondUpgradeCountdownSec = -1;
    private volatile int emeraldUpgradeCountdownSec = -1;
    private volatile int lobbyCountdownSeconds = -1;
    private volatile int diamondTier = 1;
    private volatile int emeraldTier = 1;
    private volatile boolean allBedsDestroyed = false;
    private volatile boolean suddenDeathActive = false;
    private volatile boolean matchStarted = false;
    private volatile boolean matchEnded = false;
    private volatile int sharpnessTier = 0;
    private volatile int protectionTier = 0;
    private volatile int hasteTier = 0;
    private volatile int forgeTier = 0;
    private volatile boolean healPool = false;
    private volatile int activeTrapCount = 0;
    private volatile long lastTrapTriggeredMs = 0L;
    private volatile long insufficientMaterialsUntilMs = 0L;
    private volatile String lastActionBar = "";
    private volatile String lastTitle = "";
    private volatile String lastSubtitle = "";
    private volatile String lastBossBar = "";
    private volatile String lastScoreboardTitle = "";
    private volatile List<String> lastScoreboardLines = List.of();
    private volatile String tabHeader = "";
    private volatile String tabFooter = "";
    private volatile int respawningSecondsRemaining = -1;
    private volatile long respawnCountdownUntilMs = 0L;
    private volatile int killsThisMatch = 0;
    private volatile int finalKillsThisMatch = 0;

    private static final class TeamStatusMutable {
        final TeamColor team;
        BedStatus bedStatus = BedStatus.UNKNOWN;
        int alivePlayers = -1;
        boolean eliminated = false;
        final Set<String> members = new HashSet<>();

        TeamStatusMutable(TeamColor team) {
            this.team = team;
        }

        TeamStatusSnapshot toSnapshot() {
            return new TeamStatusSnapshot(team, bedStatus, alivePlayers, eliminated);
        }
    }

    public BedwarsMatchState() {
        reset();
    }

    public synchronized void reset() {
        teams.clear();
        for (TeamColor tc : TeamColor.values()) {
            if (tc != TeamColor.UNKNOWN) {
                teams.put(tc, new TeamStatusMutable(tc));
            }
        }
        playerTeams.clear();
        discoveredTeamOrder.clear();
        respawningPlayersUntilMs.clear();
        teamRespawnUntilMs.clear();
        genSpawnTimers.clear();
        homeTeam = TeamColor.UNKNOWN;
        mapName = "unknown";
        modeLabel = "";
        nextPhase = MatchPhase.UNKNOWN;
        nextPhaseSeconds = -1;
        diamondUpgradeCountdownSec = -1;
        emeraldUpgradeCountdownSec = -1;
        lobbyCountdownSeconds = -1;
        diamondTier = 1;
        emeraldTier = 1;
        allBedsDestroyed = false;
        suddenDeathActive = false;
        matchStarted = false;
        matchEnded = false;
        sharpnessTier = 0;
        protectionTier = 0;
        hasteTier = 0;
        forgeTier = 0;
        healPool = false;
        activeTrapCount = 0;
        lastTrapTriggeredMs = 0L;
        insufficientMaterialsUntilMs = 0L;
        lastActionBar = "";
        lastTitle = "";
        lastSubtitle = "";
        lastBossBar = "";
        lastScoreboardTitle = "";
        lastScoreboardLines = List.of();
        tabHeader = "";
        tabFooter = "";
        respawningSecondsRemaining = -1;
        respawnCountdownUntilMs = 0L;
        killsThisMatch = 0;
        finalKillsThisMatch = 0;
    }

    public synchronized ParsedChatOutcome onChatMessage(String rawChat, long nowMs) {
        String clean = stripFormatting(rawChat);
        if (clean.isEmpty()) {
            return ParsedChatOutcome.NONE;
        }
        String lower = clean.toLowerCase(Locale.ROOT);

        TeamColor assignedTeam = TeamColor.UNKNOWN;
        Matcher tm = TEAM_ASSIGN_PATTERN.matcher(clean);
        if (tm.find()) {
            assignedTeam = TeamColor.fromName(tm.group(1));
            if (assignedTeam != TeamColor.UNKNOWN) {
                this.homeTeam = assignedTeam;
                this.matchStarted = true;
                this.lobbyCountdownSeconds = -1;
                TeamStatusMutable t = teams.get(assignedTeam);
                if (t != null && t.bedStatus == BedStatus.UNKNOWN) {
                    t.bedStatus = BedStatus.ALIVE;
                }
            }
        }

        boolean insufficient = lower.contains("do not have enough materials")
                || lower.contains("don't have enough")
                || lower.contains("not enough resources")
                || lower.contains("cannot afford")
                || lower.contains("can not afford");
        if (insufficient) {
            insufficientMaterialsUntilMs = nowMs + 1500L;
        }

        boolean countdown = lower.contains("will start in")
                || lower.contains("game starts in")
                || lower.contains("starting in");
        int cSecs = -1;
        if (countdown) {
            cSecs = extractSeconds(clean);
            if (cSecs >= 0) {
                this.lobbyCountdownSeconds = cSecs;
            }
            this.nextPhase = MatchPhase.PRE_GAME;
            this.nextPhaseSeconds = cSecs;
        }
        if (lower.contains("not enough players to start")) {
            this.lobbyCountdownSeconds = -1;
        }

        boolean startedNow = lower.contains("goodluck with your bedwars game")
                || lower.contains("protect your bed and destroy the enemy beds")
                || lower.contains("protect your own")
                || lower.contains("destroy the other beds")
                || lower.contains("you are now in team")
                || lower.contains("the game has started");
        if (startedNow) {
            this.matchStarted = true;
            this.lobbyCountdownSeconds = -1;
            if (this.nextPhase == MatchPhase.PRE_GAME) {
                this.nextPhase = MatchPhase.DIAMOND_II;
                this.nextPhaseSeconds = -1;
            }
        }

        // Generator tier & phase events
        if (lower.contains("diamond generator") && (lower.contains("tier ii") || lower.contains("level 2") || lower.contains("tier 2"))) {
            diamondTier = Math.max(diamondTier, 2);
            if (nextPhase == MatchPhase.DIAMOND_II) nextPhaseSeconds = -1;
        } else if (lower.contains("diamond generator") && (lower.contains("tier iii") || lower.contains("level 3") || lower.contains("tier 3"))) {
            diamondTier = 3;
            if (nextPhase == MatchPhase.DIAMOND_III) nextPhaseSeconds = -1;
        }
        if (lower.contains("emerald generator") && (lower.contains("tier ii") || lower.contains("level 2") || lower.contains("tier 2"))) {
            emeraldTier = Math.max(emeraldTier, 2);
            if (nextPhase == MatchPhase.EMERALD_II) nextPhaseSeconds = -1;
        } else if (lower.contains("emerald generator") && (lower.contains("tier iii") || lower.contains("level 3") || lower.contains("tier 3"))) {
            emeraldTier = 3;
            if (nextPhase == MatchPhase.EMERALD_III) nextPhaseSeconds = -1;
        }
        if (lower.contains("all beds have been destroyed") || lower.contains("all beds are now destroyed")) {
            allBedsDestroyed = true;
            for (TeamStatusMutable t : teams.values()) {
                if (t.bedStatus != BedStatus.UNKNOWN || !t.eliminated) {
                    t.bedStatus = BedStatus.DESTROYED;
                }
            }
        }
        if (lower.contains("sudden death") || lower.contains("dragons have spawned")) {
            suddenDeathActive = true;
        }
        if (lower.contains("victory!") || lower.contains("has won the game") || lower.contains("game over")) {
            matchEnded = true;
        }

        // Bed destruction detection
        TeamColor destroyedBed = TeamColor.UNKNOWN;
        boolean homeBedHit = false;
        if (lower.contains("your bed was destroyed") || lower.contains("your bed has been destroyed")) {
            homeBedHit = true;
            destroyedBed = this.homeTeam;
        } else {
            Matcher bm = BED_DESTROYED_PATTERN.matcher(clean);
            if (bm.find()) {
                destroyedBed = TeamColor.fromName(bm.group(1));
            } else {
                Matcher bhm = BED_DESTRUCTION_HEADER_PATTERN.matcher(clean);
                if (bhm.find()) {
                    destroyedBed = TeamColor.fromName(bhm.group(1));
                }
            }
        }
        if (destroyedBed != TeamColor.UNKNOWN) {
            TeamStatusMutable t = teams.get(destroyedBed);
            if (t != null) {
                t.bedStatus = BedStatus.DESTROYED;
            }
            if (destroyedBed == this.homeTeam) {
                homeBedHit = true;
            }
        }

        // Team elimination messages ("TEAM ELIMINATED > Red Team has been eliminated!")
        if (lower.contains("has been eliminated") || lower.contains("team eliminated")) {
            TeamColor elim = TeamColor.fromName(clean);
            if (elim != TeamColor.UNKNOWN) {
                TeamStatusMutable t = teams.get(elim);
                if (t != null) {
                    t.bedStatus = BedStatus.DESTROYED;
                    t.alivePlayers = 0;
                    t.eliminated = true;
                }
            }
        }

        // Team upgrades feedback (purchases & trap pops!)
        boolean isPurchaseMsg = lower.contains("purchased") || lower.contains("unlocked") || lower.contains("bought");
        if (isPurchaseMsg) {
            if (lower.contains("sharp")) sharpnessTier = Math.max(sharpnessTier, 1);
            if (lower.contains("protection") || lower.contains("reinforced armor")) {
                protectionTier = Math.min(4, Math.max(protectionTier + 1, extractRomanTier(lower)));
            }
            if (lower.contains("haste") || lower.contains("maniac miner")) {
                hasteTier = Math.min(2, Math.max(hasteTier + 1, extractRomanTier(lower)));
            }
            if (lower.contains("forge")) {
                forgeTier = Math.min(4, Math.max(forgeTier + 1, extractRomanTier(lower)));
            }
            if (lower.contains("heal pool")) healPool = true;
            if (lower.contains("trap") || lower.contains("alarm") || lower.contains("fatigue")) {
                activeTrapCount = Math.min(3, activeTrapCount + 1);
            }
        } else if (isTrapTriggeredText(lower)) {
            onTrapPopped(nowMs);
        }

        boolean finalKill = lower.contains("final kill");
        if (lower.contains("has been killed by")
                || lower.contains("was killed by")
                || lower.contains("was slain by")
                || lower.contains("was knocked into")
                || lower.contains("fell into the void")
                || lower.contains("was thrown into")
                || lower.contains("was pushed into")
                || lower.contains("was shot by")
                || lower.contains("was blown up")
                || lower.contains("died")) {
            killsThisMatch++;
            if (finalKill) finalKillsThisMatch++;
            Matcher dm = DEATH_VICTIM_PATTERN.matcher(clean);
            if (dm.find()) {
                String victim = dm.group(1);
                if (victim != null && !victim.isBlank()) {
                    String victimKey = victim.toLowerCase(Locale.ROOT);
                    TeamColor victimTeam = playerTeams.getOrDefault(victimKey, TeamColor.UNKNOWN);
                    if (finalKill || (victimTeam != TeamColor.UNKNOWN && isTeamBedDestroyed(victimTeam))) {
                        respawningPlayersUntilMs.remove(victimKey);
                    } else {
                        // Standard BedWars 5-second respawn window
                        long untilMs = nowMs + 5000L;
                        respawningPlayersUntilMs.put(victimKey, untilMs);
                        if (victimTeam != TeamColor.UNKNOWN) {
                            teamRespawnUntilMs.put(victimTeam, untilMs);
                        }
                    }
                }
            }
        }

        String tag = "";
        if (assignedTeam != TeamColor.UNKNOWN) tag = "TEAM_ASSIGN";
        else if (destroyedBed != TeamColor.UNKNOWN || homeBedHit) tag = "BED_DESTROY";
        else if (insufficient) tag = "INSUFFICIENT_MATS";
        else if (startedNow) tag = "MATCH_START";
        else if (countdown) tag = "COUNTDOWN";

        return new ParsedChatOutcome(assignedTeam, destroyedBed, homeBedHit, insufficient, countdown, startedNow, finalKill, cSecs, tag);
    }

    public synchronized void onActionBar(String rawText) {
        String clean = stripFormatting(rawText);
        this.lastActionBar = clean;
        if (clean.isEmpty()) {
            return;
        }
        String lower = clean.toLowerCase(Locale.ROOT);
        if (lower.contains("respawn in")) {
            respawningSecondsRemaining = extractSeconds(clean);
            respawnCountdownUntilMs = respawningSecondsRemaining > 0
                    ? System.currentTimeMillis() + Math.min(15, respawningSecondsRemaining + 1) * 1000L
                    : 0L;
        } else if (lower.contains("respawned")) {
            respawningSecondsRemaining = -1;
            respawnCountdownUntilMs = 0L;
        }
        if (lower.contains("starting in") || lower.contains("starts in")) {
            int s = extractSeconds(clean);
            if (s >= 0) lobbyCountdownSeconds = s;
        }
        if (isTrapTriggeredText(lower)) {
            onTrapPopped(System.currentTimeMillis());
        }
        parsePhaseLine(clean, null);
    }

    public synchronized void onTitle(String rawTitle) {
        String clean = stripFormatting(rawTitle);
        this.lastTitle = clean;
        String lower = clean.toLowerCase(Locale.ROOT);
        if (lower.contains("bed destroyed") || lower.contains("bed lost")) {
            if (homeTeam != TeamColor.UNKNOWN) {
                TeamStatusMutable t = teams.get(homeTeam);
                if (t != null) t.bedStatus = BedStatus.DESTROYED;
            }
        } else if (lower.contains("victory") || lower.contains("game over") || lower.contains("defeat")) {
            matchEnded = true;
        } else if (isTrapTriggeredText(lower)) {
            onTrapPopped(System.currentTimeMillis());
        }
    }

    public synchronized void onSubtitle(String rawSubtitle) {
        String clean = stripFormatting(rawSubtitle);
        this.lastSubtitle = clean;
        String lower = clean.toLowerCase(Locale.ROOT);
        if (lower.contains("you will respawn in")) {
            respawningSecondsRemaining = extractSeconds(clean);
            respawnCountdownUntilMs = respawningSecondsRemaining > 0
                    ? System.currentTimeMillis() + Math.min(15, respawningSecondsRemaining + 1) * 1000L
                    : 0L;
        } else if (lower.contains("will no longer respawn")) {
            if (homeTeam != TeamColor.UNKNOWN) {
                TeamStatusMutable t = teams.get(homeTeam);
                if (t != null) t.bedStatus = BedStatus.DESTROYED;
            }
        } else if (isTrapTriggeredText(lower)) {
            onTrapPopped(System.currentTimeMillis());
        }
    }

    private void onTrapPopped(long nowMs) {
        // Deduplicate simultaneous title + subtitle + chat notification for the same trap trigger within 1500ms
        if (lastTrapTriggeredMs == 0L || nowMs - lastTrapTriggeredMs > 1500L) {
            activeTrapCount = Math.max(0, activeTrapCount - 1);
            lastTrapTriggeredMs = nowMs;
        }
    }

    public static boolean isTrapTriggeredText(String lower) {
        if (lower == null || lower.isEmpty()) {
            return false;
        }
        if (lower.contains("purchased") || lower.contains("unlocked") || lower.contains("bought")) {
            return false;
        }
        return lower.contains("trap triggered")
                || lower.contains("trap was set off")
                || lower.contains("trap has been triggered")
                || lower.contains("trap has been set off")
                || lower.contains("your trap was triggered")
                || lower.contains("alarm trap set off")
                || lower.contains("revealed an invisible")
                || lower.contains("has entered your base")
                || lower.contains("intruder detected")
                || (lower.contains("trap") && (lower.contains("set off") || lower.contains("triggered") || lower.contains("activated")));
    }

    public synchronized void onBossBar(String rawTitle) {
        this.lastBossBar = stripFormatting(rawTitle);
        if (!lastBossBar.isEmpty()) {
            parsePhaseLine(lastBossBar, null);
        }
    }

    public synchronized void onTabListHeaderFooter(String header, String footer) {
        this.tabHeader = stripFormatting(header);
        this.tabFooter = stripFormatting(footer);
        parseMapOrMode(this.tabHeader);
        parseMapOrMode(this.tabFooter);
    }

    public synchronized void updatePlayerTeam(String playerName, TeamColor team) {
        if (playerName == null || playerName.isBlank() || team == null || team == TeamColor.UNKNOWN) {
            return;
        }
        playerTeams.put(playerName.trim().toLowerCase(Locale.ROOT), team);
        TeamStatusMutable ts = teams.get(team);
        if (ts != null) {
            ts.members.add(playerName.trim().toLowerCase(Locale.ROOT));
        }
    }

    public synchronized void updateScoreboard(String title, List<String> orderedLines) {
        this.lastScoreboardTitle = stripFormatting(title);
        if (orderedLines == null) {
            this.lastScoreboardLines = List.of();
            return;
        }
        List<String> cleanedLines = new ArrayList<>(orderedLines.size());
        String pendingNextEventHeader = null;
        for (String raw : orderedLines) {
            String line = stripFormatting(raw);
            if (line.isEmpty()) {
                continue;
            }
            cleanedLines.add(line);
            parseMapOrMode(line);
            String lower = line.toLowerCase(Locale.ROOT);
            if (lower.startsWith("next event")) {
                pendingNextEventHeader = line;
                continue;
            }
            if (parsePhaseLine(line, pendingNextEventHeader)) {
                pendingNextEventHeader = null;
            } else {
                pendingNextEventHeader = null;
            }
            parseScoreboardTeamLine(line);
        }
        this.lastScoreboardLines = Collections.unmodifiableList(cleanedLines);
    }

    private void parseMapOrMode(String line) {
        if (line == null || line.isEmpty()) {
            return;
        }
        String lower = line.toLowerCase(Locale.ROOT);
        int idx = lower.indexOf("map:");
        if (idx >= 0) {
            String m = line.substring(idx + 4).trim();
            if (!m.isEmpty() && m.length() <= 36) {
                this.mapName = m;
            }
        }
        int midx = lower.indexOf("mode:");
        if (midx >= 0) {
            String md = line.substring(midx + 5).trim();
            if (!md.isEmpty() && md.length() <= 24) {
                this.modeLabel = md;
            }
        }
    }

    private boolean parsePhaseLine(String line, String previousHeader) {
        String combined = previousHeader != null ? (previousHeader + " " + line) : line;
        String lower = combined.toLowerCase(Locale.ROOT);
        int secs = extractSeconds(combined);
        MatchPhase matched = MatchPhase.UNKNOWN;
        if (lower.contains("diamond iii") || lower.contains("diamond 3")) {
            matched = MatchPhase.DIAMOND_III;
            diamondTier = Math.max(diamondTier, 2);
        } else if (lower.contains("diamond ii") || lower.contains("diamond 2")) {
            matched = MatchPhase.DIAMOND_II;
        } else if (lower.contains("emerald iii") || lower.contains("emerald 3")) {
            matched = MatchPhase.EMERALD_III;
            emeraldTier = Math.max(emeraldTier, 2);
        } else if (lower.contains("emerald ii") || lower.contains("emerald 2")) {
            matched = MatchPhase.EMERALD_II;
        } else if (lower.contains("bed gone") || lower.contains("beds gone") || lower.contains("bed destruction") || lower.contains("beds destroy")) {
            matched = MatchPhase.BED_GONE;
            diamondTier = Math.max(diamondTier, 2);
        } else if (lower.contains("sudden death")) {
            matched = MatchPhase.SUDDEN_DEATH;
        } else if (lower.contains("game end") || lower.contains("game ends")) {
            matched = MatchPhase.GAME_END;
        } else if (lower.contains("starting in") || lower.contains("starts in") || lower.contains("waiting")) {
            matched = MatchPhase.PRE_GAME;
            if (secs >= 0) lobbyCountdownSeconds = secs;
        }
        if (matched != MatchPhase.UNKNOWN) {
            this.nextPhase = matched;
            if (secs >= 0) {
                this.nextPhaseSeconds = secs;
                if (matched == MatchPhase.DIAMOND_II || matched == MatchPhase.DIAMOND_III) {
                    this.diamondUpgradeCountdownSec = secs;
                } else if (matched == MatchPhase.EMERALD_II || matched == MatchPhase.EMERALD_III) {
                    this.emeraldUpgradeCountdownSec = secs;
                }
            }
            if (matched != MatchPhase.PRE_GAME) {
                this.matchStarted = true;
            }
            return true;
        }
        return false;
    }

    /**
     * Parses a generator hologram line (from either ArmorStand or Display.TextDisplay) into per-generator
     * spawn countdown timers and updates diamond/emerald generator tier if reported on the hologram.
     */
    public synchronized void onGeneratorHologram(int x, int z, GenType inferredType, String rawText, long nowMs) {
        String clean = stripFormatting(rawText);
        if (clean.isEmpty()) {
            return;
        }
        String lower = clean.toLowerCase(Locale.ROOT);
        GenType type = inferredType;
        if (type == null || type == GenType.HOME_FORGE) {
            if (lower.contains("diamond")) {
                type = GenType.DIAMOND;
            } else if (lower.contains("emerald")) {
                type = GenType.EMERALD;
            }
        }
        if (type != GenType.DIAMOND && type != GenType.EMERALD) {
            return;
        }
        int tier = 0;
        if (lower.contains("tier iii") || lower.contains("tier 3") || lower.contains("level 3")) {
            tier = 3;
        } else if (lower.contains("tier ii") || lower.contains("tier 2") || lower.contains("level 2")) {
            tier = 2;
        } else if (lower.contains("tier i") || lower.contains("tier 1") || lower.contains("level 1")) {
            tier = 1;
        }
        if (tier > 0) {
            if (type == GenType.DIAMOND) {
                diamondTier = Math.max(diamondTier, tier);
            } else {
                emeraldTier = Math.max(emeraldTier, tier);
            }
        }
        int secs = -1;
        Matcher m = GEN_SPAWN_SEC_PATTERN.matcher(clean);
        if (m.find()) {
            secs = safeParseInt(m.group(1), -1);
        } else if (lower.contains("spawn") || lower.contains("sec")) {
            secs = extractSeconds(clean);
        }
        if (secs >= 0 && secs <= 120) {
            long cellKey = (((long) (x >> 2)) << 32) ^ ((z >> 2) & 0xFFFFFFFFL);
            int effectiveTier = tier > 0 ? tier : (type == GenType.DIAMOND ? diamondTier : emeraldTier);
            genSpawnTimers.put(cellKey, new GenSpawnTimer(type, x, z, secs, effectiveTier, nowMs));
        }
    }

    /**
     * Deterministically parses item display names + lore lines for BedWars enchantments,
     * lobby/play-again selectors, and shop upgrade status.
     */
    public static ParsedItemMeta parseItemLoreMetadata(String itemName, String loreText) {
        String combined = ((itemName != null ? itemName : "") + " " + (loreText != null ? loreText : ""));
        String clean = stripFormatting(combined).toLowerCase(Locale.ROOT);
        int sharp = 0;
        if (clean.contains("sharpness iv") || clean.contains("sharpness 4")) sharp = 4;
        else if (clean.contains("sharpness iii") || clean.contains("sharpness 3")) sharp = 3;
        else if (clean.contains("sharpness ii") || clean.contains("sharpness 2")) sharp = 2;
        else if (clean.contains("sharpness i") || clean.contains("sharpness 1") || clean.contains("sharpened")) sharp = 1;

        int prot = 0;
        if (clean.contains("protection iv") || clean.contains("protection 4")) prot = 4;
        else if (clean.contains("protection iii") || clean.contains("protection 3")) prot = 3;
        else if (clean.contains("protection ii") || clean.contains("protection 2")) prot = 2;
        else if (clean.contains("protection i") || clean.contains("protection 1")) prot = 1;

        int eff = 0;
        if (clean.contains("efficiency v") || clean.contains("efficiency 5")) eff = 5;
        else if (clean.contains("efficiency iv") || clean.contains("efficiency 4")) eff = 4;
        else if (clean.contains("efficiency iii") || clean.contains("efficiency 3")) eff = 3;
        else if (clean.contains("efficiency ii") || clean.contains("efficiency 2")) eff = 2;
        else if (clean.contains("efficiency i") || clean.contains("efficiency 1")) eff = 1;

        int kb = 0;
        if (clean.contains("knockback ii") || clean.contains("knockback 2")) kb = 2;
        else if (clean.contains("knockback i") || clean.contains("knockback 1")) kb = 1;

        boolean isPlayAgain = clean.contains("play again") || clean.contains("requeue") || clean.contains("new game");
        boolean isLobby = clean.contains("return to lobby") || clean.contains("leave") || clean.contains("game menu")
                || clean.contains("server selector") || clean.contains("cosmetics") || clean.contains("quick play");
        boolean isAlreadyOwned = clean.contains("already purchased") || clean.contains("unlocked")
                || clean.contains("max tier") || clean.contains("maxed");
        boolean isTrap = clean.contains("trap") && (clean.contains("alarm") || clean.contains("fatigue")
                || clean.contains("blindness") || clean.contains("counter"));

        return new ParsedItemMeta(sharp, prot, eff, kb, isLobby, isPlayAgain, isAlreadyOwned, isTrap);
    }

    private void parseScoreboardTeamLine(String line) {
        TeamColor tc = TeamColor.UNKNOWN;
        String prefix = "";
        String tail = "";
        Matcher fm = TEAM_LINE_FULL_PATTERN.matcher(line);
        if (fm.find()) {
            prefix = fm.group(1) != null ? fm.group(1) : "";
            tc = TeamColor.fromName(fm.group(2));
            tail = fm.group(3) != null ? fm.group(3) : "";
        } else {
            Matcher im = TEAM_LINE_INITIAL_PATTERN.matcher(line);
            if (im.find()) {
                TeamColor candidate = TeamColor.fromName(im.group(2));
                if (candidate != TeamColor.UNKNOWN) {
                    tc = candidate;
                    tail = im.group(3) != null ? im.group(3) : "";
                }
            }
        }
        if (tc == TeamColor.UNKNOWN) {
            return;
        }
        if (!discoveredTeamOrder.contains(tc)) {
            discoveredTeamOrder.add(tc);
        }
        TeamStatusMutable st = teams.get(tc);
        if (st == null) {
            return;
        }
        String cleanPrefix = prefix.trim();
        String cleanTail = tail.trim();
        String combined = cleanPrefix + " " + cleanTail;
        String lowerTail = cleanTail.toLowerCase(Locale.ROOT);
        if (lowerTail.contains("(you)")
                || lowerTail.endsWith("you")
                || lowerTail.contains("[you]")
                || lowerTail.contains("(me)")
                || cleanTail.contains("♚")
                || cleanTail.contains("♔")
                || cleanTail.contains("★")
                || cleanTail.contains("←")) {
            if (this.homeTeam == TeamColor.UNKNOWN) {
                this.homeTeam = tc;
            }
        }
        boolean checkMark = combined.contains("✔") || combined.contains("✓") || lowerTail.contains("alive");
        boolean crossMark = combined.contains("✗") || combined.contains("✘") || combined.contains("×")
                || lowerTail.contains("dead") || lowerTail.contains("eliminated");

        Matcher numM = TRAILING_COUNT_PATTERN.matcher(cleanTail.replace("you", ""));
        int count = numM.find() ? safeParseInt(numM.group(1), -1) : -1;

        if (checkMark) {
            st.bedStatus = BedStatus.ALIVE;
            st.eliminated = false;
            if (count > 0) st.alivePlayers = count;
            else if (st.alivePlayers <= 0) st.alivePlayers = 1;
            this.matchStarted = true;
        } else if (crossMark) {
            st.bedStatus = BedStatus.DESTROYED;
            if (count > 0) {
                st.alivePlayers = count;
                st.eliminated = false;
            } else {
                st.alivePlayers = 0;
                st.eliminated = true;
            }
            this.matchStarted = true;
        } else if (count >= 0) {
            // Hypixel uses numeric player count when bed is destroyed (or team has no bed)
            st.bedStatus = count > 0 ? BedStatus.DESTROYED : BedStatus.DESTROYED;
            st.alivePlayers = count;
            st.eliminated = (count == 0);
            this.matchStarted = true;
        }
    }

    public static int extractSeconds(String text) {
        if (text == null || text.isEmpty()) {
            return -1;
        }
        Matcher mm = MMSS_PATTERN.matcher(text);
        if (mm.find()) {
            int mins = safeParseInt(mm.group(1), 0);
            int secs = safeParseInt(mm.group(2), 0);
            return mins * 60 + secs;
        }
        Matcher ms = MINS_SECS_PATTERN.matcher(text);
        if (ms.find()) {
            int mins = safeParseInt(ms.group(1), 0);
            int secs = ms.group(2) != null ? safeParseInt(ms.group(2), 0) : 0;
            return mins * 60 + secs;
        }
        Matcher sm = SECS_PATTERN.matcher(text);
        if (sm.find()) {
            return safeParseInt(sm.group(1), -1);
        }
        return -1;
    }

    private static int extractRomanTier(String lower) {
        if (lower.contains(" iv") || lower.endsWith(" 4")) return 4;
        if (lower.contains(" iii") || lower.endsWith(" 3")) return 3;
        if (lower.contains(" ii") || lower.endsWith(" 2")) return 2;
        if (lower.contains(" i") || lower.endsWith(" 1")) return 1;
        return 1;
    }

    private static int safeParseInt(String s, int fallback) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    public static String stripFormatting(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        String noColors = COLOR_CODES.matcher(raw).replaceAll("");
        return noColors.replaceAll("\\s+", " ").trim();
    }

    public synchronized TeamColor getHomeTeam() {
        return homeTeam;
    }

    public synchronized void setHomeTeam(TeamColor team) {
        if (team != null && team != TeamColor.UNKNOWN) {
            this.homeTeam = team;
        }
    }

    public synchronized TeamColor getPlayerTeam(String playerName) {
        if (playerName == null || playerName.isBlank()) {
            return TeamColor.UNKNOWN;
        }
        return playerTeams.getOrDefault(playerName.trim().toLowerCase(Locale.ROOT), TeamColor.UNKNOWN);
    }

    public synchronized boolean isTeamBedDestroyed(TeamColor team) {
        if (allBedsDestroyed) {
            return true;
        }
        TeamStatusMutable st = team != null ? teams.get(team) : null;
        return st != null && st.bedStatus == BedStatus.DESTROYED;
    }

    public synchronized boolean isTeamEliminated(TeamColor team) {
        TeamStatusMutable st = team != null ? teams.get(team) : null;
        return st != null && st.eliminated;
    }

    public synchronized TeamStatusSnapshot getTeamStatus(TeamColor team) {
        TeamStatusMutable st = team != null ? teams.get(team) : null;
        return st != null ? st.toSnapshot() : new TeamStatusSnapshot(TeamColor.UNKNOWN, BedStatus.UNKNOWN, -1, false);
    }

    public synchronized List<TeamStatusSnapshot> getDiscoveredTeams() {
        List<TeamStatusSnapshot> out = new ArrayList<>(discoveredTeamOrder.size());
        for (TeamColor tc : discoveredTeamOrder) {
            TeamStatusMutable st = teams.get(tc);
            if (st != null) {
                out.add(st.toSnapshot());
            }
        }
        return out;
    }

    public synchronized int countLivingEnemyTeams() {
        int living = 0;
        for (TeamColor tc : discoveredTeamOrder) {
            if (tc == homeTeam) continue;
            TeamStatusMutable st = teams.get(tc);
            if (st != null && !st.eliminated) {
                living++;
            }
        }
        return living;
    }

    public synchronized boolean isInsufficientMaterialsCooldown(long nowMs) {
        return nowMs < insufficientMaterialsUntilMs;
    }

    public synchronized void clearInsufficientMaterialsCooldown() {
        insufficientMaterialsUntilMs = 0L;
    }

    public synchronized MatchPhase getNextPhase() {
        return nextPhase;
    }

    public synchronized int getNextPhaseSeconds() {
        return nextPhaseSeconds;
    }

    public synchronized int getLobbyCountdownSeconds() {
        return lobbyCountdownSeconds;
    }

    public synchronized int getDiamondTier() {
        return diamondTier;
    }

    public synchronized int getEmeraldTier() {
        return emeraldTier;
    }

    public synchronized boolean isAllBedsDestroyed() {
        return allBedsDestroyed;
    }

    public synchronized boolean isSuddenDeathActive() {
        return suddenDeathActive;
    }

    public synchronized boolean isMatchStarted() {
        return matchStarted;
    }

    public synchronized boolean isMatchEnded() {
        return matchEnded;
    }

    public synchronized int getSharpnessTier() {
        return sharpnessTier;
    }

    public synchronized int getProtectionTier() {
        return protectionTier;
    }

    public synchronized int getHasteTier() {
        return hasteTier;
    }

    public synchronized int getForgeTier() {
        return forgeTier;
    }

    public synchronized boolean hasHealPool() {
        return healPool;
    }

    public synchronized int getActiveTrapCount() {
        return activeTrapCount;
    }

    public synchronized long getLastTrapTriggeredMs() {
        return lastTrapTriggeredMs;
    }

    public synchronized boolean wasTrapTriggeredRecently(long nowMs, long windowMs) {
        return lastTrapTriggeredMs > 0L && nowMs >= lastTrapTriggeredMs && nowMs - lastTrapTriggeredMs <= windowMs;
    }

    public synchronized int getDiamondUpgradeCountdownSec() {
        return diamondUpgradeCountdownSec;
    }

    public synchronized int getEmeraldUpgradeCountdownSec() {
        return emeraldUpgradeCountdownSec;
    }

    public synchronized boolean isPlayerRespawning(String playerName, long nowMs) {
        if (playerName == null || playerName.isBlank()) {
            return false;
        }
        String key = playerName.trim().toLowerCase(Locale.ROOT);
        Long until = respawningPlayersUntilMs.get(key);
        if (until == null) {
            return false;
        }
        if (nowMs >= until) {
            respawningPlayersUntilMs.remove(key);
            return false;
        }
        return true;
    }

    public synchronized double getPlayerRespawnRemainingSec(String playerName, long nowMs) {
        if (playerName == null || playerName.isBlank()) {
            return 0.0;
        }
        String key = playerName.trim().toLowerCase(Locale.ROOT);
        Long until = respawningPlayersUntilMs.get(key);
        if (until == null || nowMs >= until) {
            return 0.0;
        }
        return (until - nowMs) / 1000.0;
    }

    public synchronized int countRespawningEnemies(long nowMs) {
        int count = 0;
        Iterator<Map.Entry<String, Long>> it = respawningPlayersUntilMs.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Long> e = it.next();
            if (nowMs >= e.getValue()) {
                it.remove();
                continue;
            }
            TeamColor tc = playerTeams.getOrDefault(e.getKey(), TeamColor.UNKNOWN);
            if (tc == TeamColor.UNKNOWN || tc != homeTeam) {
                count++;
            }
        }
        return count;
    }

    public synchronized boolean isTeamAnyMemberRespawning(TeamColor team, long nowMs) {
        if (team == null || team == TeamColor.UNKNOWN) {
            return false;
        }
        Long until = teamRespawnUntilMs.get(team);
        if (until == null) {
            return false;
        }
        if (nowMs >= until) {
            teamRespawnUntilMs.remove(team);
            return false;
        }
        return true;
    }

    /**
     * Returns remaining seconds until the next item spawns at a generator near {@code (x, z)},
     * or {@code -1.0} if no recent hologram timer has been observed within 12 blocks.
     */
    public synchronized double getClosestGenSpawnRemainingSec(GenType type, int x, int z, long nowMs) {
        if (type == null || genSpawnTimers.isEmpty()) {
            return -1.0;
        }
        double bestDistSq = 12.0 * 12.0;
        double bestRem = -1.0;
        Iterator<Map.Entry<Long, GenSpawnTimer>> it = genSpawnTimers.entrySet().iterator();
        while (it.hasNext()) {
            GenSpawnTimer t = it.next().getValue();
            if (nowMs - t.updatedMs() > 90_000L) {
                it.remove();
                continue;
            }
            if (t.type() != type) {
                continue;
            }
            double dx = t.x() - x;
            double dz = t.z() - z;
            double dSq = dx * dx + dz * dz;
            if (dSq <= bestDistSq) {
                bestDistSq = dSq;
                bestRem = t.remainingSeconds(nowMs);
            }
        }
        return bestRem;
    }

    /**
     * Returns the shortest observed remaining spawn time across all tracked generators of {@code type},
     * or {@code -1.0} if none have been observed recently.
     */
    public synchronized double getMinGenSpawnRemainingSec(GenType type, long nowMs) {
        if (type == null || genSpawnTimers.isEmpty()) {
            return -1.0;
        }
        double best = -1.0;
        for (GenSpawnTimer t : genSpawnTimers.values()) {
            if (t.type() == type && nowMs - t.updatedMs() <= 90_000L) {
                double rem = t.remainingSeconds(nowMs);
                if (best < 0.0 || rem < best) {
                    best = rem;
                }
            }
        }
        return best;
    }

    public synchronized String getMapName() {
        return mapName;
    }

    public synchronized String getModeLabel() {
        return modeLabel;
    }

    public synchronized String getLastActionBar() {
        return lastActionBar;
    }

    public synchronized String getLastTitle() {
        return lastTitle;
    }

    public synchronized String getLastSubtitle() {
        return lastSubtitle;
    }

    public synchronized String getLastBossBar() {
        return lastBossBar;
    }

    public synchronized String getLastScoreboardTitle() {
        return lastScoreboardTitle;
    }

    public synchronized List<String> getLastScoreboardLines() {
        return lastScoreboardLines;
    }

    public synchronized String getTabHeader() {
        return tabHeader;
    }

    public synchronized String getTabFooter() {
        return tabFooter;
    }

    public synchronized int getRespawningSecondsRemaining() {
        if (respawningSecondsRemaining > 0 && respawnCountdownUntilMs > 0L
                && System.currentTimeMillis() > respawnCountdownUntilMs) {
            respawningSecondsRemaining = -1;
            respawnCountdownUntilMs = 0L;
        }
        return respawningSecondsRemaining;
    }

    public synchronized boolean shouldPrioritizeEmeralds(int livingEnemyBeds) {
        if (allBedsDestroyed || suddenDeathActive || livingEnemyBeds <= 1) {
            return true;
        }
        if (emeraldTier >= 2) {
            return true;
        }
        return nextPhase == MatchPhase.BED_GONE && nextPhaseSeconds >= 0 && nextPhaseSeconds <= 90;
    }

    public synchronized boolean shouldPrioritizeDiamonds(boolean homeBedAlive) {
        if (!homeBedAlive || allBedsDestroyed) {
            return protectionTier < 2;
        }
        if (sharpnessTier == 0 || protectionTier == 0) {
            return true;
        }
        return diamondTier >= 2 && protectionTier < 3;
    }

    public synchronized String compactSummary() {
        StringBuilder sb = new StringBuilder();
        sb.append("home=").append(homeTeam.name())
                .append(" phase=").append(nextPhase.name()).append(':').append(nextPhaseSeconds).append('s')
                .append(" diaT=").append(diamondTier)
                .append(" emT=").append(emeraldTier)
                .append(" upg[S").append(sharpnessTier).append(" P").append(protectionTier)
                .append(" H").append(hasteTier).append(" F").append(forgeTier)
                .append(healPool ? " +HP" : "").append(" T").append(activeTrapCount).append("] ");
        for (TeamColor tc : discoveredTeamOrder) {
            TeamStatusMutable ts = teams.get(tc);
            if (ts == null) continue;
            sb.append(tc.name().charAt(0)).append(':')
                    .append(ts.bedStatus == BedStatus.ALIVE ? 'B' : (ts.eliminated ? 'X' : String.valueOf(Math.max(0, ts.alivePlayers))))
                    .append(' ');
        }
        return sb.toString().trim();
    }
}
