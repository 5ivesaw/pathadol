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

package baritone.human;

import baritone.process.SpeedrunProcess;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.regex.Pattern;

/**
 * Discovers or creates a low-lag Flat Cheats-On tester world and launches
 * {@link AutoTestRunner} automatically as soon as the player enters the world.
 */
public final class TesterWorldLauncher {

    public static final String TESTER_PREFIX = "pathadol_tester";
    private static final String TESTER_MARKER = ".pathadol-proving-ground";
    private static final Pattern TESTER_FOLDER = Pattern.compile(TESTER_PREFIX + "(?:_\\d{8}_\\d{6}(?:_\\d+)?)?");
    private static final DateTimeFormatter FOLDER_TS = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");
    private static Path pendingNewTester;

    private TesterWorldLauncher() {}

    /**
     * Finds the most recently modified saved tester world matching {@value #TESTER_PREFIX}, or {@code null}.
     */
    public static String findLatestTesterWorld(Minecraft mc) {
        if (mc == null) {
            return null;
        }
        try {
            LevelStorageSource source = mc.getLevelSource();
            LevelStorageSource.LevelCandidates candidates = source.findLevelCandidates();
            return candidates.levels().stream()
                    .filter(dir -> TESTER_FOLDER.matcher(dir.directoryName()).matches() && isMarkedTester(dir.path()))
                    .max(Comparator.comparingLong(TesterWorldLauncher::lastModifiedMillis))
                    .map(LevelStorageSource.LevelDirectory::directoryName)
                    .orElse(null);
        } catch (Exception ignored) {
            return null;
        }
    }

    static boolean isTesterWorld(Minecraft mc, Level level) {
        var server = mc == null ? null : mc.getSingleplayerServer();
        if (server == null || level == null || !Level.OVERWORLD.equals(level.dimension())) {
            return false;
        }
        Path root = server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
        if (!TESTER_FOLDER.matcher(root.getFileName().toString()).matches()) {
            return false;
        }
        if (root.equals(pendingNewTester)) {
            pendingNewTester = null;
            try {
                Files.createFile(root.resolve(TESTER_MARKER));
            } catch (IOException ignored) {
                // Fail closed unless this exact world already has a marker.
            }
        }
        return isMarkedTester(root);
    }

    private static boolean isMarkedTester(Path root) {
        return Files.isRegularFile(root.resolve(TESTER_MARKER), java.nio.file.LinkOption.NOFOLLOW_LINKS);
    }

    /**
     * Launches the latest tester world (or creates a fresh Superflat cheats-on Normal world) and
     * arms {@link AutoTestRunner} to start in {@code mode} once chunks settle.
     */
    public static void launch(
            Minecraft mc,
            AutoTestRunner.Mode mode,
            boolean forceNewWorld,
            Screen fallbackScreen
    ) {
        launch(mc, mode, 1, 60, forceNewWorld, fallbackScreen);
    }

    public static void launch(
            Minecraft mc,
            AutoTestRunner.Mode mode,
            int startStage,
            int endStage,
            boolean forceNewWorld,
            Screen fallbackScreen
    ) {
        if (mc == null) {
            return;
        }
        pendingNewTester = null;
        SpeedrunProcess.scheduleAutoStart(false);
        AutoTestRunner.scheduleAutoStart(mode, startStage, endStage);
        Runnable onCancel = () -> {
            AutoTestRunner.scheduleAutoStart(null);
            mc.gui.setScreen(fallbackScreen);
        };

        if (!forceNewWorld) {
            String existing = findLatestTesterWorld(mc);
            if (existing != null) {
                mc.createWorldOpenFlows().openWorld(existing, onCancel);
                return;
            }
        }

        LevelStorageSource source = mc.getLevelSource();
        String folder = TESTER_PREFIX;
        if (source.levelExists(folder)) {
            folder = TESTER_PREFIX + "_" + LocalDateTime.now().format(FOLDER_TS);
            String candidate = folder;
            for (int suffix = 1; source.levelExists(folder); suffix++) {
                folder = candidate + "_" + suffix;
            }
        }
        pendingNewTester = source.getLevelPath(folder).toAbsolutePath().normalize();
        String displayName = folder.equals(TESTER_PREFIX)
                ? "Pathadol Tester"
                : "Pathadol Tester " + folder.substring(TESTER_PREFIX.length() + 1);

        LevelSettings settings = new LevelSettings(
                displayName,
                GameType.CREATIVE,
                new LevelSettings.DifficultySettings(Difficulty.NORMAL, false, false),
                true,
                WorldDataConfiguration.DEFAULT
        );

        mc.createWorldOpenFlows().createFreshLevel(
                folder,
                settings,
                WorldOptions.testWorldWithRandomSeed(),
                WorldPresets::createTestWorldDimensions,
                fallbackScreen
        );
    }

    /**
     * Generates a fresh Normal Survival world with a brand-new random seed and automatically starts
     * {@link SpeedrunProcess} upon entry to benchmark an autonomous Any% Ender Dragon run.
     */
    public static void launchRandomSpeedrun(Minecraft mc, Screen fallbackScreen) {
        if (mc == null) {
            return;
        }
        pendingNewTester = null;
        AutoTestRunner.scheduleAutoStart(null);
        SpeedrunProcess.scheduleAutoStart(true);

        String folder = "pathadol_speedrun_" + LocalDateTime.now().format(FOLDER_TS);
        String displayName = "Pathadol Speedrun " + folder.substring("pathadol_speedrun_".length());

        LevelSettings settings = new LevelSettings(
                displayName,
                GameType.SURVIVAL,
                new LevelSettings.DifficultySettings(Difficulty.NORMAL, false, false),
                true,
                WorldDataConfiguration.DEFAULT
        );

        mc.createWorldOpenFlows().createFreshLevel(
                folder,
                settings,
                WorldOptions.defaultWithRandomSeed(),
                WorldPresets::createNormalWorldDimensions,
                fallbackScreen
        );
    }

    private static long lastModifiedMillis(LevelStorageSource.LevelDirectory dir) {
        try {
            Path target = Files.exists(dir.dataFile()) ? dir.dataFile() : dir.path();
            return Files.getLastModifiedTime(target).toMillis();
        } catch (IOException e) {
            return 0L;
        }
    }
}
