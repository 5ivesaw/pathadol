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

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.level.storage.LevelStorageSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.Locale;

/**
 * Discovers or creates a low-lag Flat Cheats-On tester world and launches
 * {@link AutoTestRunner} automatically as soon as the player enters the world.
 */
public final class TesterWorldLauncher {

    public static final String TESTER_PREFIX = "pathadol_tester";
    private static final DateTimeFormatter FOLDER_TS = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

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
                    .filter(dir -> dir.directoryName().toLowerCase(Locale.ROOT).startsWith(TESTER_PREFIX))
                    .max(Comparator.comparingLong(TesterWorldLauncher::lastModifiedMillis))
                    .map(LevelStorageSource.LevelDirectory::directoryName)
                    .orElse(null);
        } catch (Exception ignored) {
            return null;
        }
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
        }
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

    private static long lastModifiedMillis(LevelStorageSource.LevelDirectory dir) {
        try {
            Path target = Files.exists(dir.dataFile()) ? dir.dataFile() : dir.path();
            return Files.getLastModifiedTime(target).toMillis();
        } catch (IOException e) {
            return 0L;
        }
    }
}
