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

import baritone.Baritone;
import baritone.api.Settings;
import baritone.behavior.look.ForkableRandom;

import java.util.Locale;

/**
 * Parameterized human motor persona used by the Human Motor Layer.
 * Supports presets ("casual", "average", "sweaty", "custom") as well as
 * live overrides from {@link Settings}.
 */
public final class HumanProfile {

    public enum ClickStyle {
        HOLD,
        JITTER,
        BUTTERFLY;

        public static ClickStyle fromString(String name) {
            if (name == null) {
                return JITTER;
            }
            return switch (name.trim().toLowerCase(Locale.ROOT)) {
                case "hold", "normal", "regular" -> HOLD;
                case "butterfly" -> BUTTERFLY;
                default -> JITTER;
            };
        }
    }

    private final String name;
    private final double fittsBaseTicks;
    private final double fittsSlopeTicks;
    private final double aimSpeed;
    private final double overshoot;
    private final double tremor;
    private final double reactionMedianMs;
    private final double cpsMean;
    private final double cpsStd;
    private final ClickStyle clickStyle;
    private final int minKeyHoldTicks;
    private final double sprintConeDeg;
    private final double pursuitAlpha;
    private final double mistakeRate;

    public HumanProfile(
            String name,
            double fittsBaseTicks,
            double fittsSlopeTicks,
            double aimSpeed,
            double overshoot,
            double tremor,
            double reactionMedianMs,
            double cpsMean,
            double cpsStd,
            ClickStyle clickStyle,
            int minKeyHoldTicks,
            double sprintConeDeg,
            double pursuitAlpha,
            double mistakeRate
    ) {
        this.name = name;
        this.fittsBaseTicks = fittsBaseTicks;
        this.fittsSlopeTicks = fittsSlopeTicks;
        this.aimSpeed = Math.max(0.1, aimSpeed);
        this.overshoot = Math.max(0.0, overshoot);
        this.tremor = Math.max(0.0, tremor);
        this.reactionMedianMs = Math.max(50.0, reactionMedianMs);
        this.cpsMean = Math.max(1.0, cpsMean);
        this.cpsStd = Math.max(0.1, cpsStd);
        this.clickStyle = clickStyle;
        this.minKeyHoldTicks = Math.max(1, minKeyHoldTicks);
        this.sprintConeDeg = Math.max(15.0, Math.min(65.0, sprintConeDeg));
        this.pursuitAlpha = Math.max(0.1, Math.min(1.0, pursuitAlpha));
        this.mistakeRate = Math.max(0.0, Math.min(0.25, mistakeRate));
    }

    public static HumanProfile preset(String presetName) {
        String key = presetName == null ? "average" : presetName.trim().toLowerCase(Locale.ROOT);
        return switch (key) {
            case "casual" -> new HumanProfile(
                    "casual",
                    2.2,
                    2.1,
                    0.82,
                    0.38,
                    0.16,
                    265.0,
                    7.5,
                    1.4,
                    ClickStyle.HOLD,
                    3,
                    40.0,
                    0.35,
                    0.035
            );
            case "sweaty", "pro" -> new HumanProfile(
                    "sweaty",
                    1.2,
                    1.25,
                    1.30,
                    0.22,
                    0.09,
                    175.0,
                    12.8,
                    1.8,
                    ClickStyle.BUTTERFLY,
                    2,
                    44.0,
                    0.55,
                    0.008
            );
            default -> new HumanProfile(
                    "average",
                    1.6,
                    1.65,
                    1.0,
                    0.30,
                    0.12,
                    220.0,
                    10.5,
                    1.6,
                    ClickStyle.JITTER,
                    2,
                    42.0,
                    0.44,
                    0.02
            );
        };
    }

    public static HumanProfile fromSettings() {
        Settings s = Baritone.settings();
        HumanProfile base = preset(s.humanProfile.value);
        return new HumanProfile(
                base.name,
                base.fittsBaseTicks,
                base.fittsSlopeTicks,
                s.humanAimSpeed.value * base.aimSpeed,
                s.humanOvershoot.value,
                s.humanTremor.value,
                s.humanReactionMedianMs.value,
                s.humanCps.value,
                base.cpsStd,
                ClickStyle.fromString(s.humanClickStyle.value),
                s.humanMinKeyHoldTicks.value,
                s.humanSprintConeDeg.value,
                base.pursuitAlpha,
                s.humanMistakeRate.value
        );
    }

    /**
     * Samples a log-normal human reaction delay in ticks (50ms per tick).
     */
    public int sampleReactionTicks(ForkableRandom rand, long sessionTicks) {
        double fatigue = fatigueMultiplier(sessionTicks);
        double gaussian = nextGaussian(rand);
        double ms = this.reactionMedianMs * fatigue * Math.exp(0.22 * gaussian);
        return Math.max(2, (int) Math.round(ms / 50.0));
    }

    /**
     * Slow session fatigue multiplier (up to +8% after ~30 minutes of play).
     */
    public double fatigueMultiplier(long sessionTicks) {
        double minutes = Math.max(0L, sessionTicks) / (20.0 * 60.0);
        return 1.0 + 0.08 * (1.0 - Math.exp(-minutes / 25.0));
    }

    public static double nextGaussian(ForkableRandom rand) {
        double u1 = Math.max(1e-12, rand.nextDouble());
        double u2 = rand.nextDouble();
        return Math.sqrt(-2.0 * Math.log(u1)) * Math.cos(2.0 * Math.PI * u2);
    }

    public String name() {
        return name;
    }

    public double fittsBaseTicks() {
        return fittsBaseTicks;
    }

    public double fittsSlopeTicks() {
        return fittsSlopeTicks;
    }

    public double aimSpeed() {
        return aimSpeed;
    }

    public double overshoot() {
        return overshoot;
    }

    public double tremor() {
        return tremor;
    }

    public double reactionMedianMs() {
        return reactionMedianMs;
    }

    public double cpsMean() {
        return cpsMean;
    }

    public double cpsStd() {
        return cpsStd;
    }

    public ClickStyle clickStyle() {
        return clickStyle;
    }

    public int minKeyHoldTicks() {
        return minKeyHoldTicks;
    }

    public double sprintConeDeg() {
        return sprintConeDeg;
    }

    public double pursuitAlpha() {
        return pursuitAlpha;
    }

    public double mistakeRate() {
        return mistakeRate;
    }
}
