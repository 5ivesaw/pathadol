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

package baritone.brain;

import baritone.bedwars.BedwarsTacticalHelper.InventorySummary;
import baritone.brain.BrainActionSpace.BrainDecision;
import baritone.brain.BrainActionSpace.Maneuver;
import baritone.brain.BrainSchema.ObsSnapshot;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Records structured Neuro-Symbolic training trajectories ({@code obs[72], mask[13], action, reward, done})
 * into {@code .minecraft/pathadol/brain_data/*.jsonl} for Behavioral Cloning &amp; Offline-to-Online RL
 * on Google Colab L4 GPUs. Uses manual Gson DOM construction (no POJO reflection).
 */
public final class BrainTrajectoryRecorder {

    private static final DateTimeFormatter TS_FMT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.ROOT);

    /**
     * Pure-Java Bedwars tactical event reward calculator comparing consecutive {@link ObsSnapshot}s.
     */
    public static final class StepRewardCalculator {

        private ObsSnapshot lastSnap = null;
        private boolean wasDead = false;

        public void reset() {
            this.lastSnap = null;
            this.wasDead = false;
        }

        public float computeStepReward(ObsSnapshot cur, boolean playerDeadNow) {
            if (cur == null) {
                return 0.0f;
            }
            if (lastSnap == null) {
                lastSnap = cur;
                wasDead = playerDeadNow;
                return 0.0f;
            }
            double reward = -0.002; // gentle time pressure against passive AFK

            // Enemy bed kills (+5.0) and home bed loss (-4.0)
            int enemyBedDrop = lastSnap.livingEnemyBedCount() - cur.livingEnemyBedCount();
            if (enemyBedDrop > 0 && enemyBedDrop <= 7) {
                reward += 5.0 * enemyBedDrop;
            }
            if (lastSnap.homeBedAlive() && !cur.homeBedAlive()) {
                reward -= 4.0;
            }

            // Death edge
            boolean diedEvent = !wasDead && (playerDeadNow || cur.health() <= 0.0f);
            if (diedEvent) {
                reward -= 3.5;
            } else if (!wasDead && !playerDeadNow) {
                double dHp = cur.health() - lastSnap.health();
                if (Math.abs(dHp) < 15.0) {
                    reward += 0.08 * dHp;
                }
            }

            // Defense completeness shaping
            if (cur.homeBedAlive() && lastSnap.homeBedAlive()) {
                double dDef = cur.homeBedDefenseRatio() - lastSnap.homeBedDefenseRatio();
                if (dDef > 0.0 && dDef <= 1.0) {
                    reward += 0.45 * dDef;
                }
            }

            // Resource pickups & gear upgrades (ignore respawn wipes when died)
            if (!diedEvent && !wasDead && cur.inventory() != null && lastSnap.inventory() != null) {
                InventorySummary p = lastSnap.inventory();
                InventorySummary c = cur.inventory();
                int dDia = Math.max(0, c.diamonds() - p.diamonds());
                int dEm = Math.max(0, c.emeralds() - p.emeralds());
                int dGold = Math.max(0, c.gold() - p.gold());
                int dIron = Math.max(0, c.iron() - p.iron());
                reward += 0.16 * Math.min(8, dDia)
                        + 0.28 * Math.min(8, dEm)
                        + 0.03 * Math.min(16, dGold)
                        + 0.008 * Math.min(32, dIron);

                if (c.swordTier() > p.swordTier()) {
                    reward += 0.40 * (c.swordTier() - p.swordTier());
                }
                if (c.armorPoints() > p.armorPoints() + 0.5) {
                    reward += 0.08 * (c.armorPoints() - p.armorPoints());
                }
                if (c.shearsTier() > p.shearsTier()) {
                    reward += 0.25;
                }
            }

            lastSnap = cur;
            wasDead = playerDeadNow;
            return (float) reward;
        }
    }

    private final Path dataDir;
    private final StepRewardCalculator rewardCalc = new StepRewardCalculator();

    private BufferedWriter writer;
    private Path activeFile;
    private volatile boolean recording = false;
    private long stepCount = 0L;
    private long startMs = 0L;
    private double cumulativeReward = 0.0;

    public BrainTrajectoryRecorder(Path baseDir) {
        this.dataDir = baseDir.resolve("brain_data");
    }

    public boolean isRecording() {
        return recording;
    }

    public long getStepCount() {
        return stepCount;
    }

    public double getCumulativeReward() {
        return cumulativeReward;
    }

    public Path getActiveFile() {
        return activeFile;
    }

    public Path getDataDir() {
        return dataDir;
    }

    public synchronized Path start(String tag) throws IOException {
        if (recording) {
            stop();
        }
        Files.createDirectories(dataDir);
        String cleanTag = (tag == null || tag.isBlank()) ? "bw" : tag.replaceAll("[^a-zA-Z0-9_-]", "_");
        this.activeFile = dataDir.resolve(TS_FMT.format(LocalDateTime.now()) + "-" + cleanTag + ".jsonl");
        this.writer = Files.newBufferedWriter(activeFile, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        this.rewardCalc.reset();
        this.stepCount = 0L;
        this.cumulativeReward = 0.0;
        this.startMs = System.currentTimeMillis();
        this.recording = true;
        return this.activeFile;
    }

    public synchronized Path stop() {
        this.recording = false;
        if (this.writer != null) {
            try {
                this.writer.flush();
                this.writer.close();
            } catch (IOException ignored) {
            }
            this.writer = null;
        }
        return this.activeFile;
    }

    public synchronized float recordStep(
            ObsSnapshot snap,
            float[] obsVec,
            boolean[] mask,
            BrainDecision executedDecision,
            Maneuver teacherHint,
            boolean done,
            String sourceTag
    ) {
        float reward = rewardCalc.computeStepReward(snap, done);
        cumulativeReward += reward;
        if (!recording || writer == null || obsVec == null || executedDecision == null) {
            return reward;
        }
        stepCount++;

        JsonObject row = new JsonObject();
        row.addProperty("schema", BrainSchema.SCHEMA_VERSION);
        row.addProperty("t", stepCount);
        row.addProperty("ms", Math.max(0L, System.currentTimeMillis() - startMs));
        row.addProperty("src", sourceTag != null ? sourceTag : "bot");
        row.addProperty("maneuver", executedDecision.maneuver().ordinal());
        row.addProperty("maneuverName", executedDecision.maneuver().name());
        row.addProperty("combatMode", executedDecision.combatMode().ordinal());
        row.addProperty("reward", Math.round(reward * 10000.0f) / 10000.0f);
        row.addProperty("done", done);
        if (teacherHint != null) {
            row.addProperty("teacherManeuver", teacherHint.ordinal());
        }

        JsonArray obsArr = new JsonArray(obsVec.length);
        for (float v : obsVec) {
            obsArr.add(Math.round(v * 10000.0f) / 10000.0f);
        }
        row.add("obs", obsArr);

        JsonArray sliderArr = new JsonArray(BrainActionSpace.NUM_SLIDERS);
        for (float s : executedDecision.sliders().toArray()) {
            sliderArr.add(Math.round(s * 10000.0f) / 10000.0f);
        }
        row.add("sliders", sliderArr);

        if (mask != null) {
            JsonArray maskArr = new JsonArray(mask.length);
            for (boolean b : mask) {
                maskArr.add(b ? 1 : 0);
            }
            row.add("mask", maskArr);
        }

        try {
            writer.write(row.toString());
            writer.newLine();
            if ((stepCount % 25L) == 0L) {
                writer.flush();
            }
        } catch (IOException ignored) {
        }
        return reward;
    }
}
