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

import baritone.api.utils.Rotation;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Generalized cross-task motor style learner.
 * <p>
 * Extracts task-invariant kinematic parameters (Fitts' law flick intercept/slope,
 * peak angular velocity, overshoot tendency, tremor, pursuit gain, sprint camera cone,
 * directional hold duration, click rhythm, and reaction latency) from human mirror trials
 * without ever memorizing world coordinates or specific courses.
 */
public final class HumanStyleLearner {

    public static final String LEARNED_FILE_NAME = "human_learned_profile.json";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static volatile LearnedState globalState = LearnedState.defaultState();

    public record MotionSample(
            float yaw,
            float pitch,
            double vx,
            double vz,
            boolean onGround,
            boolean sprinting,
            boolean sneaking,
            boolean fwd,
            boolean back,
            boolean left,
            boolean right,
            boolean jump,
            boolean leftDown,
            boolean rightDown,
            int slot
    ) {}

    public record TrialSummary(
            double durationSec,
            int ticks,
            double meanAimDegPerTick,
            double p90AimDegPerTick,
            double maxAimDegPerTick,
            double meanMoveLookDiffDeg,
            double sprintFraction,
            double peakClickRate
    ) {}

    public static final class LearnedState {
        public int trialsLearned = 0;
        public long totalHumanTicks = 0;
        public int fittsSamples = 0;
        public double fittsBaseTicks = 0.68;
        public double fittsSlopeTicks = 0.85;
        public double aimSpeed = 1.25;
        public double overshoot = 0.16;
        public double tremor = 0.065;
        public double reactionMedianMs = 148.0;
        public double cpsMean = 12.6;
        public double cpsStd = 1.6;
        public String clickStyle = "butterfly";
        public int minKeyHoldTicks = 2;
        public double sprintConeDeg = 44.0;
        public double pursuitAlpha = 0.76;
        public double steeringLookahead = 2.6;
        public double mistakeRate = 0.008;
        public String bridgeSkill = "sweaty";
        public double observedP90AimDegPerTick = 0.0;
        public double observedMoveLookDiffDeg = 0.0;

        public static LearnedState defaultState() {
            return new LearnedState();
        }

        public HumanProfile toHumanProfile() {
            return new HumanProfile(
                    "learned",
                    fittsBaseTicks,
                    fittsSlopeTicks,
                    aimSpeed,
                    overshoot,
                    tremor,
                    reactionMedianMs,
                    cpsMean,
                    cpsStd,
                    HumanProfile.ClickStyle.fromString(clickStyle),
                    minKeyHoldTicks,
                    sprintConeDeg,
                    pursuitAlpha,
                    mistakeRate
            );
        }
    }

    public record LearnUpdate(
            boolean updated,
            TrialSummary humanSummary,
            String summaryLine
    ) {}

    private final Path saveFile;

    public HumanStyleLearner(Path baritoneDir) {
        this.saveFile = baritoneDir.resolve(LEARNED_FILE_NAME);
        load();
    }

    public static HumanProfile activeLearnedProfile() {
        return globalState.toHumanProfile();
    }

    public static LearnedState activeLearnedState() {
        return globalState;
    }

    public synchronized void load() {
        if (!Files.exists(saveFile)) {
            return;
        }
        try {
            String raw = Files.readString(saveFile, StandardCharsets.UTF_8);
            LearnedState loaded = fromJsonString(raw);
            if (loaded != null && loaded.trialsLearned > 0) {
                sanitize(loaded);
                globalState = loaded;
            }
        } catch (Exception ignored) {
        }
    }

    public synchronized void save() {
        try {
            Files.createDirectories(saveFile.getParent());
            Files.writeString(saveFile, toJsonString(globalState), StandardCharsets.UTF_8);
        } catch (IOException ignored) {
        }
    }

    // Explicit JsonObject serialization avoids reflection failures under ProGuard field name overloading
    static String toJsonString(LearnedState s) {
        JsonObject obj = new JsonObject();
        obj.addProperty("trialsLearned", s.trialsLearned);
        obj.addProperty("totalHumanTicks", s.totalHumanTicks);
        obj.addProperty("fittsSamples", s.fittsSamples);
        obj.addProperty("fittsBaseTicks", s.fittsBaseTicks);
        obj.addProperty("fittsSlopeTicks", s.fittsSlopeTicks);
        obj.addProperty("aimSpeed", s.aimSpeed);
        obj.addProperty("overshoot", s.overshoot);
        obj.addProperty("tremor", s.tremor);
        obj.addProperty("reactionMedianMs", s.reactionMedianMs);
        obj.addProperty("cpsMean", s.cpsMean);
        obj.addProperty("cpsStd", s.cpsStd);
        obj.addProperty("clickStyle", s.clickStyle);
        obj.addProperty("minKeyHoldTicks", s.minKeyHoldTicks);
        obj.addProperty("sprintConeDeg", s.sprintConeDeg);
        obj.addProperty("pursuitAlpha", s.pursuitAlpha);
        obj.addProperty("steeringLookahead", s.steeringLookahead);
        obj.addProperty("mistakeRate", s.mistakeRate);
        obj.addProperty("bridgeSkill", s.bridgeSkill);
        obj.addProperty("observedP90AimDegPerTick", s.observedP90AimDegPerTick);
        obj.addProperty("observedMoveLookDiffDeg", s.observedMoveLookDiffDeg);
        return GSON.toJson(obj);
    }

    static LearnedState fromJsonString(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        JsonElement parsed = JsonParser.parseString(raw);
        if (!parsed.isJsonObject()) {
            return null;
        }
        JsonObject obj = parsed.getAsJsonObject();
        LearnedState s = LearnedState.defaultState();
        s.trialsLearned = readInt(obj, "trialsLearned", s.trialsLearned);
        s.totalHumanTicks = readLong(obj, "totalHumanTicks", s.totalHumanTicks);
        s.fittsSamples = readInt(obj, "fittsSamples", s.fittsSamples);
        s.fittsBaseTicks = readDouble(obj, "fittsBaseTicks", s.fittsBaseTicks);
        s.fittsSlopeTicks = readDouble(obj, "fittsSlopeTicks", s.fittsSlopeTicks);
        s.aimSpeed = readDouble(obj, "aimSpeed", s.aimSpeed);
        s.overshoot = readDouble(obj, "overshoot", s.overshoot);
        s.tremor = readDouble(obj, "tremor", s.tremor);
        s.reactionMedianMs = readDouble(obj, "reactionMedianMs", s.reactionMedianMs);
        s.cpsMean = readDouble(obj, "cpsMean", s.cpsMean);
        s.cpsStd = readDouble(obj, "cpsStd", s.cpsStd);
        s.clickStyle = readString(obj, "clickStyle", s.clickStyle);
        s.minKeyHoldTicks = readInt(obj, "minKeyHoldTicks", s.minKeyHoldTicks);
        s.sprintConeDeg = readDouble(obj, "sprintConeDeg", s.sprintConeDeg);
        s.pursuitAlpha = readDouble(obj, "pursuitAlpha", s.pursuitAlpha);
        s.steeringLookahead = readDouble(obj, "steeringLookahead", s.steeringLookahead);
        s.mistakeRate = readDouble(obj, "mistakeRate", s.mistakeRate);
        s.bridgeSkill = readString(obj, "bridgeSkill", s.bridgeSkill);
        s.observedP90AimDegPerTick = readDouble(obj, "observedP90AimDegPerTick", s.observedP90AimDegPerTick);
        s.observedMoveLookDiffDeg = readDouble(obj, "observedMoveLookDiffDeg", s.observedMoveLookDiffDeg);
        return s;
    }

    private static int readInt(JsonObject obj, String key, int def) {
        JsonElement el = obj.get(key);
        if (el == null || !el.isJsonPrimitive()) {
            return def;
        }
        try {
            return el.getAsInt();
        } catch (Exception ignored) {
            return def;
        }
    }

    private static long readLong(JsonObject obj, String key, long def) {
        JsonElement el = obj.get(key);
        if (el == null || !el.isJsonPrimitive()) {
            return def;
        }
        try {
            return el.getAsLong();
        } catch (Exception ignored) {
            return def;
        }
    }

    private static double readDouble(JsonObject obj, String key, double def) {
        JsonElement el = obj.get(key);
        if (el == null || !el.isJsonPrimitive()) {
            return def;
        }
        try {
            double v = el.getAsDouble();
            return Double.isFinite(v) ? v : def;
        } catch (Exception ignored) {
            return def;
        }
    }

    private static String readString(JsonObject obj, String key, String def) {
        JsonElement el = obj.get(key);
        if (el == null || !el.isJsonPrimitive()) {
            return def;
        }
        try {
            return el.getAsString();
        } catch (Exception ignored) {
            return def;
        }
    }

    public synchronized void reset() {
        globalState = LearnedState.defaultState();
        try {
            Files.deleteIfExists(saveFile);
        } catch (IOException ignored) {
        }
    }

    public boolean hasLearnedData() {
        return globalState.trialsLearned > 0;
    }

    public static TrialSummary summarize(List<MotionSample> samples) {
        if (samples == null || samples.size() < 2) {
            return new TrialSummary(0, 0, 0, 0, 0, 0, 0, 0);
        }
        List<Double> aimSpeeds = new ArrayList<>();
        List<Double> moveLookDiffs = new ArrayList<>();
        int sprintTicks = 0;
        int clickEdges = 0;
        int maxClicksInSec = 0;
        List<Integer> clickTickIndices = new ArrayList<>();

        for (int i = 1; i < samples.size(); i++) {
            MotionSample prev = samples.get(i - 1);
            MotionSample cur = samples.get(i);
            float dy = Rotation.normalizeYaw(cur.yaw - prev.yaw);
            float dp = cur.pitch - prev.pitch;
            aimSpeeds.add(Math.hypot(dy, dp));

            double speed = Math.hypot(cur.vx, cur.vz);
            if (speed > 0.06) {
                float moveYaw = (float) Math.toDegrees(Math.atan2(-cur.vx, cur.vz));
                double diff = Math.abs(Rotation.normalizeYaw(moveYaw - cur.yaw));
                moveLookDiffs.add(diff);
            }
            if (cur.sprinting) {
                sprintTicks++;
            }
            if ((cur.leftDown && !prev.leftDown) || (cur.rightDown && !prev.rightDown)) {
                clickEdges++;
                clickTickIndices.add(i);
            }
        }
        for (int i = 0; i < clickTickIndices.size(); i++) {
            int t0 = clickTickIndices.get(i);
            int windowCount = 0;
            for (int j = i; j < clickTickIndices.size() && clickTickIndices.get(j) - t0 < 20; j++) {
                windowCount++;
            }
            maxClicksInSec = Math.max(maxClicksInSec, windowCount);
        }

        double meanAim = aimSpeeds.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
        double p90Aim = percentile(aimSpeeds, 0.90);
        double maxAim = aimSpeeds.stream().mapToDouble(Double::doubleValue).max().orElse(0.0);
        double meanDiff = moveLookDiffs.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
        double sprintFrac = (double) sprintTicks / samples.size();
        double peakCps = Math.max(maxClicksInSec, clickEdges / Math.max(0.5, samples.size() / 20.0));
        return new TrialSummary(samples.size() / 20.0, samples.size(), meanAim, p90Aim, maxAim, meanDiff, sprintFrac, peakCps);
    }

    /**
     * Learns generalized motor parameters from a completed human mirror trial and updates
     * the persisted {@code learned} profile using confidence-weighted exponential moving average blending.
     */
    public synchronized LearnUpdate learnFromTrial(List<MotionSample> samples) {
        TrialSummary summary = summarize(samples);
        if (samples == null || samples.size() < 15) {
            return new LearnUpdate(false, summary, "Too few ticks (" + (samples == null ? 0 : samples.size()) + ") to update learned profile.");
        }

        // 1. Segment ballistic flicks & measure Fitts (ID, T) + overshoot
        List<double[]> fittsPairs = new ArrayList<>(); // [id, ticks]
        List<Double> tremorResiduals = new ArrayList<>();
        int flickCount = 0;
        int overshootCount = 0;

        int segStart = -1;
        for (int i = 1; i < samples.size(); i++) {
            float dy = Rotation.normalizeYaw(samples.get(i).yaw - samples.get(i - 1).yaw);
            float dp = samples.get(i).pitch - samples.get(i - 1).pitch;
            double stepSpeed = Math.hypot(dy, dp);

            if (stepSpeed > 0.01 && stepSpeed < 1.35) {
                tremorResiduals.add(stepSpeed);
            }

            if (stepSpeed >= 2.2) {
                if (segStart < 0) {
                    segStart = i - 1;
                }
            } else if (segStart >= 0) {
                int segEnd = i - 1;
                int dur = segEnd - segStart;
                float netYaw = Rotation.normalizeYaw(samples.get(segEnd).yaw - samples.get(segStart).yaw);
                float netPitch = samples.get(segEnd).pitch - samples.get(segStart).pitch;
                double dist = Math.hypot(netYaw, netPitch);
                if (dist >= 8.0 && dur >= 1 && dur <= 12) {
                    flickCount++;
                    double id = Math.log(dist / 8.0 + 1.0) / Math.log(2.0);
                    fittsPairs.add(new double[]{id, dur});
                    // Check 1-2 ticks after end for corrective reversal (overshoot)
                    int settleEnd = Math.min(samples.size() - 1, segEnd + 2);
                    float revYaw = Rotation.normalizeYaw(samples.get(settleEnd).yaw - samples.get(segEnd).yaw);
                    float revPitch = samples.get(settleEnd).pitch - samples.get(segEnd).pitch;
                    double dot = revYaw * netYaw + revPitch * netPitch;
                    double revMag = Math.hypot(revYaw, revPitch);
                    if (dot < -0.5 * dist && revMag >= 0.75) {
                        overshootCount++;
                    }
                }
                segStart = -1;
            }
        }

        // Confidence blending weight based on trial duration and flick density
        double w = Math.max(0.14, Math.min(0.45, samples.size() / 220.0));
        LearnedState next = globalState;

        if (fittsPairs.size() >= 3) {
            double[] ab = fitLinearRegression(fittsPairs);
            if (ab != null) {
                double obsA = clamp(ab[0], 0.35, 1.65);
                double obsB = clamp(ab[1], 0.45, 1.65);
                next.fittsBaseTicks = lerp(next.fittsBaseTicks, obsA, w * 0.75);
                next.fittsSlopeTicks = lerp(next.fittsSlopeTicks, obsB, w * 0.75);
                next.fittsSamples += fittsPairs.size();
            }
        }

        // Adapt aimSpeed from observed p90 speed (pro target reference ~10 deg/t active turning)
        if (summary.p90AimDegPerTick() > 1.2) {
            double impliedSpeed = clamp(0.85 + summary.p90AimDegPerTick() / 11.5, 0.85, 2.50);
            next.aimSpeed = lerp(next.aimSpeed, impliedSpeed, w);
            double impliedPursuit = clamp(0.55 + summary.p90AimDegPerTick() / 32.0, 0.50, 0.90);
            next.pursuitAlpha = lerp(next.pursuitAlpha, impliedPursuit, w);
        }

        if (flickCount >= 3) {
            double obsOvershoot = clamp((double) overshootCount / flickCount, 0.06, 0.42);
            next.overshoot = lerp(next.overshoot, obsOvershoot, w);
        }

        if (tremorResiduals.size() >= 10) {
            double rmsTremor = tremorResiduals.stream().mapToDouble(v -> v * v).average().orElse(0.0);
            double obsTremor = clamp(Math.sqrt(rmsTremor) * 0.18, 0.035, 0.19);
            next.tremor = lerp(next.tremor, obsTremor, w);
        }

        // 2. Locomotion strafe cone & directional key hold runs
        List<Double> sprintAngles = new ArrayList<>();
        List<Integer> holdRuns = new ArrayList<>();
        int curMask = -1;
        int runLen = 0;
        int sneakMoveTicks = 0;
        int totalMoveTicks = 0;
        for (MotionSample s : samples) {
            double speed = Math.hypot(s.vx, s.vz);
            if (speed > 0.06) {
                totalMoveTicks++;
                if (s.sneaking) {
                    sneakMoveTicks++;
                }
                if (s.sprinting) {
                    float moveYaw = (float) Math.toDegrees(Math.atan2(-s.vx, s.vz));
                    sprintAngles.add(Math.abs((double) Rotation.normalizeYaw(moveYaw - s.yaw)));
                }
            }
            int dirMask = (s.fwd ? 1 : 0) | (s.back ? 2 : 0) | (s.left ? 4 : 0) | (s.right ? 8 : 0);
            if (dirMask != 0) {
                if (dirMask == curMask) {
                    runLen++;
                } else {
                    if (curMask != -1 && runLen >= 1 && runLen <= 12) {
                        holdRuns.add(runLen);
                    }
                    curMask = dirMask;
                    runLen = 1;
                }
            } else {
                curMask = -1;
                runLen = 0;
            }
        }
        if (sprintAngles.size() >= 10) {
            double p85Cone = clamp(percentile(sprintAngles, 0.85) + 4.0, 26.0, 50.0);
            next.sprintConeDeg = lerp(next.sprintConeDeg, p85Cone, w);
        }
        if (holdRuns.size() >= 4) {
            int lowHold = (int) Math.round(clamp(percentile(holdRuns.stream().map(Double::valueOf).toList(), 0.25), 1.0, 3.0));
            next.minKeyHoldTicks = Math.max(1, Math.min(3, (int) Math.round(lerp(next.minKeyHoldTicks, lowHold, w))));
        }

        // 3. Click intervals & reaction timing (hotbar slot change -> first click)
        List<Double> iciTicks = new ArrayList<>();
        int lastClickIdx = -100;
        int lastSlotChangeIdx = -100;
        List<Double> reactionMsSamples = new ArrayList<>();
        for (int i = 1; i < samples.size(); i++) {
            MotionSample prev = samples.get(i - 1);
            MotionSample cur = samples.get(i);
            if (cur.slot != prev.slot) {
                lastSlotChangeIdx = i;
            }
            boolean leftEdge = cur.leftDown && !prev.leftDown;
            boolean rightEdge = cur.rightDown && !prev.rightDown;
            if (leftEdge || rightEdge) {
                if (lastSlotChangeIdx > 0 && i - lastSlotChangeIdx >= 1 && i - lastSlotChangeIdx <= 12) {
                    reactionMsSamples.add((i - lastSlotChangeIdx) * 50.0);
                }
                int dt = i - lastClickIdx;
                if (dt >= 1 && dt <= 8) {
                    iciTicks.add((double) dt);
                }
                lastClickIdx = i;
            }
        }
        if (iciTicks.size() >= 5) {
            double meanDt = iciTicks.stream().mapToDouble(Double::doubleValue).average().orElse(2.0);
            double obsCps = clamp(20.0 / Math.max(1.2, meanDt), 6.5, 16.5);
            next.cpsMean = lerp(next.cpsMean, obsCps, w);
            if (next.cpsMean >= 11.8) {
                next.clickStyle = "butterfly";
            } else if (next.cpsMean >= 8.8) {
                next.clickStyle = "jitter";
            } else {
                next.clickStyle = "hold";
            }
        }
        if (reactionMsSamples.size() >= 2) {
            double obsReact = clamp(percentile(reactionMsSamples, 0.5), 115.0, 270.0);
            next.reactionMedianMs = lerp(next.reactionMedianMs, obsReact, w);
        }
        if (totalMoveTicks >= 30 && sneakMoveTicks > 5) {
            double sneakFrac = (double) sneakMoveTicks / totalMoveTicks;
            next.bridgeSkill = sneakFrac > 0.72 ? "safe" : (sneakFrac > 0.42 ? "medium" : "sweaty");
        }

        // Steering lookahead reflects how smoothly the human maintains alignment while cutting corners
        double obsLookahead = clamp(2.0 + summary.sprintFraction() * 1.15 - Math.min(0.6, summary.meanMoveLookDiffDeg() / 45.0), 1.8, 3.4);
        next.steeringLookahead = lerp(next.steeringLookahead, obsLookahead, w * 0.75);

        next.trialsLearned++;
        next.totalHumanTicks += samples.size();
        next.observedP90AimDegPerTick = next.trialsLearned == 1
                ? summary.p90AimDegPerTick()
                : lerp(next.observedP90AimDegPerTick, summary.p90AimDegPerTick(), w);
        next.observedMoveLookDiffDeg = next.trialsLearned == 1
                ? summary.meanMoveLookDiffDeg()
                : lerp(next.observedMoveLookDiffDeg, summary.meanMoveLookDiffDeg(), w);

        sanitize(next);
        save();

        String line = String.format(Locale.ROOT,
                "Learned trial #%d (+%dt): aimSpeed=%.2f Fitts(a=%.2f,b=%.2f) cps=%.1f(%s) cone=%.0f° lookahead=%.1f",
                next.trialsLearned, samples.size(), next.aimSpeed, next.fittsBaseTicks, next.fittsSlopeTicks,
                next.cpsMean, next.clickStyle, next.sprintConeDeg, next.steeringLookahead);
        return new LearnUpdate(true, summary, line);
    }

    private static double[] fitLinearRegression(List<double[]> pairs) {
        int n = pairs.size();
        double sx = 0, sy = 0, sxx = 0, sxy = 0;
        for (double[] p : pairs) {
            sx += p[0];
            sy += p[1];
            sxx += p[0] * p[0];
            sxy += p[0] * p[1];
        }
        double denom = n * sxx - sx * sx;
        if (Math.abs(denom) < 1e-6) {
            return null;
        }
        double b = (n * sxy - sx * sy) / denom;
        double a = (sy - b * sx) / n;
        return new double[]{a, b};
    }

    private static void sanitize(LearnedState s) {
        s.fittsBaseTicks = clamp(s.fittsBaseTicks, 0.35, 1.8);
        s.fittsSlopeTicks = clamp(s.fittsSlopeTicks, 0.45, 1.8);
        s.aimSpeed = clamp(s.aimSpeed, 0.75, 2.6);
        s.overshoot = clamp(s.overshoot, 0.04, 0.45);
        s.tremor = clamp(s.tremor, 0.03, 0.22);
        s.reactionMedianMs = clamp(s.reactionMedianMs, 100.0, 290.0);
        s.cpsMean = clamp(s.cpsMean, 5.5, 16.5);
        s.cpsStd = clamp(s.cpsStd, 0.5, 2.8);
        s.minKeyHoldTicks = Math.max(1, Math.min(4, s.minKeyHoldTicks));
        s.sprintConeDeg = clamp(s.sprintConeDeg, 24.0, 52.0);
        s.pursuitAlpha = clamp(s.pursuitAlpha, 0.40, 0.90);
        s.steeringLookahead = clamp(s.steeringLookahead, 1.6, 3.6);
        s.mistakeRate = clamp(s.mistakeRate, 0.0, 0.15);
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

    private static double lerp(double a, double b, double t) {
        return a + (b - a) * t;
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
