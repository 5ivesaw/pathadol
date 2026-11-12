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

import baritone.api.utils.IPlayerContext;
import baritone.api.utils.Rotation;
import baritone.api.utils.input.Input;
import net.minecraft.world.phys.Vec3;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Phase 8 (HumanLab) telemetry recorder and live statistical analyzer.
 * Logs per-tick rotation, velocity, jerk, GCD quantization residuals, key states,
 * clicks, and travel vectors to JSONL in {@code .minecraft/pathadol/lab/}, and
 * computes live summary metrics for {@code #lab stats}.
 */
public final class HumanLabRecorder {

    private static final DateTimeFormatter TS_FMT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final Path labDir;
    private boolean recording = false;
    private String activeTag = "session";
    private Path activeFile;
    private BufferedWriter writer;

    // Live statistics
    private long recordedTicks = 0;
    private float prevYaw, prevPitch;
    private float prevVelYaw, prevVelPitch;
    private float prevAccYaw, prevAccPitch;
    private int prevKeysMask = 0;

    private final List<Double> angularSpeeds = new ArrayList<>();
    private final List<Double> angularJerks = new ArrayList<>();
    private int snapCountOver18Deg = 0;
    private int nonZeroMouseSteps = 0;
    private int gcdQuantizedMatches = 0;
    private int keyTransitions = 0;
    private int leftClickTicks = 0;
    private int rightClickPulses = 0;

    public HumanLabRecorder(Path baseDir) {
        this.labDir = baseDir.resolve("lab");
    }

    public synchronized Path start(String tag) throws IOException {
        if (recording) {
            stop();
        }
        Files.createDirectories(labDir);
        this.activeTag = (tag == null || tag.isBlank()) ? "session" : tag.replaceAll("[^a-zA-Z0-9_-]", "_");
        String fileName = TS_FMT.format(LocalDateTime.now()) + "-" + this.activeTag + ".jsonl";
        this.activeFile = labDir.resolve(fileName);
        this.writer = Files.newBufferedWriter(activeFile, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);

        resetStats();
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

    public synchronized void resetStats() {
        recordedTicks = 0;
        angularSpeeds.clear();
        angularJerks.clear();
        snapCountOver18Deg = 0;
        nonZeroMouseSteps = 0;
        gcdQuantizedMatches = 0;
        keyTransitions = 0;
        leftClickTicks = 0;
        rightClickPulses = 0;
    }

    public synchronized void recordTick(
            IPlayerContext ctx,
            Map<Input, Boolean> resolvedInputs,
            boolean rightClickFired,
            boolean botInControl
    ) {
        if (!recording || ctx.player() == null) {
            return;
        }
        float yaw = ctx.player().getYRot();
        float pitch = ctx.player().getXRot();
        Vec3 pos = ctx.player().position();
        Vec3 vel = ctx.player().getDeltaMovement();
        int hotbar = ctx.player().getInventory().getSelectedSlot();
        double sens = ctx.minecraft().options.sensitivity().get();

        int keysMask = encodeKeysMask(resolvedInputs);

        float dYaw = 0f;
        float dPitch = 0f;
        float jerkMag = 0f;

        if (recordedTicks > 0) {
            dYaw = Rotation.normalizeYaw(yaw - prevYaw);
            dPitch = pitch - prevPitch;
            double speed = Math.hypot(dYaw, dPitch);
            angularSpeeds.add(speed);
            if (speed > 18.0) {
                snapCountOver18Deg++;
            }

            float accYaw = dYaw - prevVelYaw;
            float accPitch = dPitch - prevVelPitch;
            if (recordedTicks > 1) {
                float jYaw = accYaw - prevAccYaw;
                float jPitch = accPitch - prevAccPitch;
                jerkMag = (float) Math.hypot(jYaw, jPitch);
                angularJerks.add((double) jerkMag);
            }
            prevAccYaw = accYaw;
            prevAccPitch = accPitch;
            prevVelYaw = dYaw;
            prevVelPitch = dPitch;

            float step = MouseModel.mouseStepAngle(sens);
            if (Math.abs(dYaw) > 1e-4f && step > 0f) {
                nonZeroMouseSteps++;
                float rem = Math.abs(dYaw / step - Math.round(dYaw / step));
                if (rem < 0.02f) {
                    gcdQuantizedMatches++;
                }
            }
            keyTransitions += Integer.bitCount(keysMask ^ prevKeysMask);
        }

        prevYaw = yaw;
        prevPitch = pitch;
        prevKeysMask = keysMask;
        recordedTicks++;

        if (Boolean.TRUE.equals(resolvedInputs.get(Input.CLICK_LEFT))) {
            leftClickTicks++;
        }
        if (rightClickFired) {
            rightClickPulses++;
        }

        if (writer != null) {
            try {
                String line = String.format(Locale.ROOT,
                        "{\"tick\":%d,\"tag\":\"%s\",\"bot\":%s,\"yaw\":%.4f,\"pitch\":%.4f,\"dyaw\":%.4f,\"dpitch\":%.4f,\"jerk\":%.4f,\"x\":%.3f,\"y\":%.3f,\"z\":%.3f,\"vx\":%.4f,\"vz\":%.4f,\"keys\":%d,\"slot\":%d,\"rc\":%s}\n",
                        recordedTicks, activeTag, botInControl, yaw, pitch, dYaw, dPitch, jerkMag,
                        pos.x, pos.y, pos.z, vel.x, vel.z, keysMask, hotbar, rightClickFired);
                writer.write(line);
                if ((recordedTicks & 19) == 0) {
                    writer.flush();
                }
            } catch (IOException ignored) {
            }
        }
    }

    public synchronized String formatSummary() {
        if (recordedTicks == 0) {
            return "No HumanLab ticks recorded yet. Run #lab start [tag] first.";
        }
        double seconds = recordedTicks / 20.0;
        double meanSpeed = angularSpeeds.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
        double maxSpeed = angularSpeeds.stream().mapToDouble(Double::doubleValue).max().orElse(0.0);
        double p95Speed = percentile(angularSpeeds, 0.95);
        double meanJerk = angularJerks.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
        double gcdRate = nonZeroMouseSteps == 0 ? 100.0 : (100.0 * gcdQuantizedMatches / nonZeroMouseSteps);
        double switchesPerSec = seconds > 0 ? keyTransitions / seconds : 0.0;
        double rcPerSec = seconds > 0 ? rightClickPulses / seconds : 0.0;

        return String.format(Locale.ROOT,
                "HumanLab [%s] (%s) — %d ticks (%.1fs)\n"
                        + "  Aim speed: mean=%.2f°/t, p95=%.2f°/t, max=%.2f°/t | snaps(>18°)=%d\n"
                        + "  Aim jerk:  mean=%.2f°/t³ | Mouse GCD match: %.1f%% (%d/%d)\n"
                        + "  Keys:      %.2f transitions/s | LMB held: %d ticks | RMB pulses: %d (%.2f/s)",
                activeTag,
                recording ? "RECORDING" : "STOPPED",
                recordedTicks,
                seconds,
                meanSpeed,
                p95Speed,
                maxSpeed,
                snapCountOver18Deg,
                meanJerk,
                gcdRate,
                gcdQuantizedMatches,
                nonZeroMouseSteps,
                switchesPerSec,
                leftClickTicks,
                rightClickPulses,
                rcPerSec
        );
    }

    private static double percentile(List<Double> values, double q) {
        if (values.isEmpty()) {
            return 0.0;
        }
        List<Double> sorted = new ArrayList<>(values);
        sorted.sort(Double::compareTo);
        int idx = Math.min(sorted.size() - 1, Math.max(0, (int) Math.round(q * (sorted.size() - 1))));
        return sorted.get(idx);
    }

    private static int encodeKeysMask(Map<Input, Boolean> map) {
        int mask = 0;
        for (Input input : Input.values()) {
            if (Boolean.TRUE.equals(map.get(input))) {
                mask |= (1 << input.ordinal());
            }
        }
        return mask;
    }

    public synchronized boolean isRecording() {
        return recording;
    }

    public synchronized Path getActiveFile() {
        return activeFile;
    }
}
