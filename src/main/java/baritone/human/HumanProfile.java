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
        String key = presetName == null ? "pro" : presetName.trim().toLowerCase(Locale.ROOT);
        return switch (key) {
            case "casual" -> new HumanProfile(
                    "casual",
                    1.45,
                    1.55,
                    0.95,
                    0.28,
                    0.14,
                    245.0,
                    7.5,
                    1.4,
                    ClickStyle.HOLD,
                    3,
                    40.0,
                    0.46,
                    0.03
            );
            case "average" -> new HumanProfile(
                    "average",
                    0.95,
                    1.05,
                    1.22,
                    0.22,
                    0.09,
                    185.0,
                    10.5,
                    1.6,
                    ClickStyle.JITTER,
                    2,
                    43.0,
                    0.62,
                    0.015
            );
            case "learned" -> HumanStyleLearner.activeLearnedProfile();
            case "sweaty", "pro" -> new HumanProfile(
                    "pro",
                    0.70,
                    0.90,
                    1.24,
                    0.15,
                    0.06,
                    145.0,
                    13.0,
                    1.7,
                    ClickStyle.BUTTERFLY,
                    2,
                    45.0,
                    0.80,
                    0.008
            );
            default -> preset("pro");
        };
    }

    /**
     * Switches {@code humanProfile} to {@code presetName} and resets individual override settings back to
     * their defaults so preset parameters (including dynamically updated {@code learned} style) apply cleanly.
     */
    public static HumanProfile applyPreset(String presetName) {
        Settings s = Baritone.settings();
        HumanProfile p = preset(presetName);
        s.humanProfile.value = p.name;
        s.humanAimSpeed.value = s.humanAimSpeed.defaultValue;
        s.humanOvershoot.value = s.humanOvershoot.defaultValue;
        s.humanTremor.value = s.humanTremor.defaultValue;
        s.humanReactionMedianMs.value = s.humanReactionMedianMs.defaultValue;
        s.humanCps.value = s.humanCps.defaultValue;
        s.humanClickStyle.value = s.humanClickStyle.defaultValue;
        s.humanMinKeyHoldTicks.value = s.humanMinKeyHoldTicks.defaultValue;
        s.humanSprintConeDeg.value = s.humanSprintConeDeg.defaultValue;
        s.humanMistakeRate.value = s.humanMistakeRate.defaultValue;
        return p;
    }

    public static HumanProfile fromSettings() {
        Settings s = Baritone.settings();
        String profileKey = s.humanProfile.value == null ? "pro" : s.humanProfile.value.trim().toLowerCase(Locale.ROOT);
        if ("custom".equals(profileKey)) {
            HumanProfile base = preset("pro");
            return new HumanProfile(
                    "custom",
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
        HumanProfile base = preset(profileKey);
        double overshoot = !s.humanOvershoot.value.equals(s.humanOvershoot.defaultValue) ? s.humanOvershoot.value : base.overshoot;
        double tremor = !s.humanTremor.value.equals(s.humanTremor.defaultValue) ? s.humanTremor.value : base.tremor;
        double reaction = !s.humanReactionMedianMs.value.equals(s.humanReactionMedianMs.defaultValue) ? s.humanReactionMedianMs.value : base.reactionMedianMs;
        double cps = !s.humanCps.value.equals(s.humanCps.defaultValue) ? s.humanCps.value : base.cpsMean;
        ClickStyle style = !s.humanClickStyle.value.equals(s.humanClickStyle.defaultValue)
                ? ClickStyle.fromString(s.humanClickStyle.value)
                : base.clickStyle;
        int holdTicks = !s.humanMinKeyHoldTicks.value.equals(s.humanMinKeyHoldTicks.defaultValue) ? s.humanMinKeyHoldTicks.value : base.minKeyHoldTicks;
        double cone = !s.humanSprintConeDeg.value.equals(s.humanSprintConeDeg.defaultValue) ? s.humanSprintConeDeg.value : base.sprintConeDeg;
        double mistake = !s.humanMistakeRate.value.equals(s.humanMistakeRate.defaultValue) ? s.humanMistakeRate.value : base.mistakeRate;

        return new HumanProfile(
                base.name,
                base.fittsBaseTicks,
                base.fittsSlopeTicks,
                s.humanAimSpeed.value * base.aimSpeed,
                overshoot,
                tremor,
                reaction,
                cps,
                base.cpsStd,
                style,
                holdTicks,
                cone,
                base.pursuitAlpha,
                mistake
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
