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
import baritone.behavior.look.ForkableRandom;

/**
 * Physics- and psychophysics-grounded human mouse model:
 * <ul>
 *   <li>Movement duration scaled by Fitts' law: {@code MT = a + b * log2(D / W + 1)}</li>
 *   <li>Minimum-jerk bell-shaped velocity trajectory: {@code 10t^3 - 15t^4 + 6t^5}</li>
 *   <li>Primary movement overshoot/undershoot on large flicks followed by corrective sub-movement</li>
 *   <li>Smooth pursuit with lag on slowly moving targets</li>
 *   <li>Crosshair coasting inside the inner 85% target box</li>
 *   <li>3-stage pink-noise (1/f) drift + velocity-scaled motor tremor</li>
 *   <li>Exact Minecraft mouse sensitivity GCD quantization</li>
 * </ul>
 */
public final class MouseModel {

    private final ForkableRandom rand;

    // Active trajectory state
    private boolean initialized;
    private float currentYaw;
    private float currentPitch;
    private float velYaw;
    private float velPitch;

    private float startYaw;
    private float startPitch;
    private float primaryGoalYaw;
    private float primaryGoalPitch;
    private float finalGoalYaw;
    private float finalGoalPitch;
    private int elapsedTicks;
    private int durationTicks;
    private boolean hasCorrectionSubmovement;
    private boolean inCorrectionSubmovement;

    private static final double SQRT3 = Math.sqrt(3.0);
    private static final double SQRT5 = Math.sqrt(5.0);

    // 3-stage pink noise state (Paul Kellet filter approximation)
    private double pinkYaw0, pinkYaw1, pinkYaw2;
    private double pinkPitch0, pinkPitch1, pinkPitch2;
    // WindMouse correlated lateral wind state for natural 2D curved flicks
    private double windYaw, windPitch;
    private long tickCounter;

    public MouseModel(ForkableRandom rand) {
        this.rand = rand;
    }

    public MouseModel fork() {
        MouseModel copy = new MouseModel(this.rand.fork());
        copy.initialized = this.initialized;
        copy.currentYaw = this.currentYaw;
        copy.currentPitch = this.currentPitch;
        copy.velYaw = this.velYaw;
        copy.velPitch = this.velPitch;
        copy.startYaw = this.startYaw;
        copy.startPitch = this.startPitch;
        copy.primaryGoalYaw = this.primaryGoalYaw;
        copy.primaryGoalPitch = this.primaryGoalPitch;
        copy.finalGoalYaw = this.finalGoalYaw;
        copy.finalGoalPitch = this.finalGoalPitch;
        copy.elapsedTicks = this.elapsedTicks;
        copy.durationTicks = this.durationTicks;
        copy.hasCorrectionSubmovement = this.hasCorrectionSubmovement;
        copy.inCorrectionSubmovement = this.inCorrectionSubmovement;
        copy.pinkYaw0 = this.pinkYaw0;
        copy.pinkYaw1 = this.pinkYaw1;
        copy.pinkYaw2 = this.pinkYaw2;
        copy.pinkPitch0 = this.pinkPitch0;
        copy.pinkPitch1 = this.pinkPitch1;
        copy.pinkPitch2 = this.pinkPitch2;
        copy.windYaw = this.windYaw;
        copy.windPitch = this.windPitch;
        copy.tickCounter = this.tickCounter;
        return copy;
    }

    public void reset(Rotation current) {
        if (current == null) {
            this.initialized = false;
            return;
        }
        this.initialized = true;
        this.currentYaw = current.getYaw();
        this.currentPitch = current.getPitch();
        this.velYaw = 0f;
        this.velPitch = 0f;
        this.startYaw = this.currentYaw;
        this.startPitch = this.currentPitch;
        this.primaryGoalYaw = this.currentYaw;
        this.primaryGoalPitch = this.currentPitch;
        this.finalGoalYaw = this.currentYaw;
        this.finalGoalPitch = this.currentPitch;
        this.elapsedTicks = 0;
        this.durationTicks = 0;
        this.hasCorrectionSubmovement = false;
        this.inCorrectionSubmovement = false;
        this.windYaw = 0.0;
        this.windPitch = 0.0;
    }

    /**
     * Advances the mouse model by one game tick toward {@code desired}.
     *
     * @param playerCurrent   current player rotation
     * @param desired         target rotation
     * @param blockInteract   whether precision block/entity interaction is required
     * @param alreadyOnTarget true if raycast already hits the intended block/entity inside safe bounds
     * @param profile         active human profile
     * @param sensitivity     Minecraft mouse sensitivity option (0.0 .. 1.0, default 0.5)
     * @return next quantized rotation for this tick
     */
    public Rotation step(
            Rotation playerCurrent,
            Rotation desired,
            boolean blockInteract,
            boolean alreadyOnTarget,
            HumanProfile profile,
            double sensitivity
    ) {
        tickCounter++;
        if (!initialized) {
            reset(playerCurrent);
        } else if (angularDistance(new Rotation(currentYaw, currentPitch), playerCurrent) > 35.0f) {
            // External teleport or manual camera movement
            reset(playerCurrent);
        }

        float targetPitch = desired.getPitch();
        if (!blockInteract && Math.abs(targetPitch - playerCurrent.getPitch()) < 0.001f) {
            targetPitch = nudgeHorizonPitch(playerCurrent.getPitch(), tickCounter);
        }

        float deltaToNewGoalYaw = shortestYawDelta(finalGoalYaw, desired.getYaw());
        float deltaToNewGoalPitch = targetPitch - finalGoalPitch;
        float goalShift = (float) Math.hypot(deltaToNewGoalYaw, deltaToNewGoalPitch);

        float totalDistYaw = shortestYawDelta(currentYaw, desired.getYaw());
        float totalDistPitch = targetPitch - currentPitch;
        float totalDist = (float) Math.hypot(totalDistYaw, totalDistPitch);

        float targetWidthDeg = blockInteract ? 6.5f : 15.0f;
        float coastRadiusDeg = targetWidthDeg * 0.42f; // inner ~85% safe zone

        // Crosshair coasting: if already on target block or well inside the safe zone, just drift naturally
        if ((alreadyOnTarget && totalDist < targetWidthDeg * 1.15f) || (totalDist <= coastRadiusDeg && durationTicks == 0)) {
            float driftYaw = (float) nextPinkYaw(profile.tremor() * 0.35);
            float driftPitch = (float) nextPinkPitch(profile.tremor() * 0.30);
            return applyQuantizedStep(currentYaw + driftYaw, currentPitch + driftPitch, sensitivity);
        }

        // Start a new Fitts minimum-jerk saccade if goal shifted substantially
        if (goalShift > 4.0f || (durationTicks == 0 && totalDist > coastRadiusDeg)) {
            planSaccade(totalDistYaw, totalDistPitch, totalDist, targetWidthDeg, profile);
        } else if (goalShift > 0.05f && durationTicks > 0) {
            // Smooth pursuit update for small target shifts during an active movement
            float blend = (float) profile.pursuitAlpha();
            this.finalGoalYaw += deltaToNewGoalYaw * blend;
            this.finalGoalPitch = Rotation.clampPitch(this.finalGoalPitch + deltaToNewGoalPitch * blend);
            if (!hasCorrectionSubmovement || inCorrectionSubmovement) {
                this.primaryGoalYaw = this.finalGoalYaw;
                this.primaryGoalPitch = this.finalGoalPitch;
            }
        }

        float nextUnquantizedYaw;
        float nextUnquantizedPitch;

        if (durationTicks > 0) {
            elapsedTicks++;
            double tau = Math.min(1.0, (double) elapsedTicks / (double) durationTicks);
            double s = minimumJerk(tau);

            nextUnquantizedYaw = startYaw + (float) (s * (primaryGoalYaw - startYaw));
            nextUnquantizedPitch = startPitch + (float) (s * (primaryGoalPitch - startPitch));

            // WindMouse correlated lateral curvature (BenLand100 / UnionClef formulation)
            // Modulated by sin(pi * tau) so curvature peaks mid-flight and vanishes at tau = 1.0
            double windMag = Math.min(3.2, totalDist * 0.065) * Math.max(0.25, profile.tremor());
            windYaw = windYaw / SQRT3 + (2.0 * rand.nextDouble() - 1.0) * windMag / SQRT5;
            windPitch = windPitch / SQRT3 + (2.0 * rand.nextDouble() - 1.0) * (windMag * 0.75) / SQRT5;
            double arcEnvelope = Math.sin(Math.PI * tau);
            nextUnquantizedYaw += (float) (windYaw * arcEnvelope);
            nextUnquantizedPitch += (float) (windPitch * arcEnvelope);

            // Add velocity-scaled motor noise + pink drift
            double speedNorm = Math.min(1.5, totalDist / 45.0);
            double noiseScale = profile.tremor() * (0.4 + 0.6 * speedNorm);
            nextUnquantizedYaw += (float) nextPinkYaw(noiseScale);
            nextUnquantizedPitch += (float) nextPinkPitch(noiseScale * 0.85);

            if (elapsedTicks >= durationTicks) {
                if (hasCorrectionSubmovement && !inCorrectionSubmovement) {
                    // Transition into corrective sub-movement
                    inCorrectionSubmovement = true;
                    startYaw = nextUnquantizedYaw;
                    startPitch = nextUnquantizedPitch;
                    primaryGoalYaw = finalGoalYaw;
                    primaryGoalPitch = finalGoalPitch;
                    elapsedTicks = 0;
                    float remDist = (float) Math.hypot(
                            shortestYawDelta(startYaw, finalGoalYaw),
                            finalGoalPitch - startPitch
                    );
                    durationTicks = Math.max(1, Math.min(3, Math.round(remDist / 4.5f) + 1));
                } else {
                    durationTicks = 0;
                    elapsedTicks = 0;
                    hasCorrectionSubmovement = false;
                    inCorrectionSubmovement = false;
                }
            }
        } else {
            // Smooth pursuit for small residual error
            float alpha = (float) profile.pursuitAlpha();
            nextUnquantizedYaw = currentYaw + totalDistYaw * alpha + (float) nextPinkYaw(profile.tremor() * 0.4);
            nextUnquantizedPitch = currentPitch + totalDistPitch * alpha + (float) nextPinkPitch(profile.tremor() * 0.35);
        }

        return applyQuantizedStep(nextUnquantizedYaw, nextUnquantizedPitch, sensitivity);
    }

    /**
     * Computes the Fitts' law movement duration in ticks for an angular turn of {@code distanceDeg}
     * into a target of width {@code widthDeg}.
     */
    public static int computeFittsTicks(double distanceDeg, double widthDeg, HumanProfile profile) {
        if (distanceDeg <= 1.5) {
            return 1;
        }
        double id = Math.log(distanceDeg / Math.max(1.0, widthDeg) + 1.0) / Math.log(2.0);
        double rawTicks = (profile.fittsBaseTicks() + profile.fittsSlopeTicks() * id) / profile.aimSpeed();
        return Math.max(1, Math.min(14, (int) Math.round(rawTicks)));
    }

    /**
     * Minimum-jerk position profile s(tau) = 10*tau^3 - 15*tau^4 + 6*tau^5 for tau in [0, 1].
     */
    public static double minimumJerk(double tau) {
        if (tau <= 0.0) {
            return 0.0;
        }
        if (tau >= 1.0) {
            return 1.0;
        }
        double t2 = tau * tau;
        double t3 = t2 * tau;
        return t3 * (10.0 - 15.0 * tau + 6.0 * t2);
    }

    private void planSaccade(
            float deltaYaw,
            float deltaPitch,
            float distanceDeg,
            float widthDeg,
            HumanProfile profile
    ) {
        this.startYaw = this.currentYaw;
        this.startPitch = this.currentPitch;
        this.finalGoalYaw = this.currentYaw + deltaYaw;
        this.finalGoalPitch = Rotation.clampPitch(this.currentPitch + deltaPitch);
        this.elapsedTicks = 0;
        this.durationTicks = computeFittsTicks(distanceDeg, widthDeg, profile);

        // On larger flicks (> 38 deg) with overshoot enabled, create a primary sub-movement
        // that lands at ~94%..108% of the distance, followed by a 1-2 tick corrective settle
        if (distanceDeg > 38.0f && profile.overshoot() > 0.01 && durationTicks >= 3) {
            double gain = 1.0 + profile.overshoot() * 0.14 * (rand.nextDouble() * 1.35 - 0.35);
            double ortho = profile.overshoot() * 0.035 * HumanProfile.nextGaussian(rand);
            this.primaryGoalYaw = this.startYaw + (float) (deltaYaw * gain - deltaPitch * ortho);
            this.primaryGoalPitch = Rotation.clampPitch(this.startPitch + (float) (deltaPitch * gain + deltaYaw * ortho));
            this.hasCorrectionSubmovement = Math.hypot(
                    shortestYawDelta(primaryGoalYaw, finalGoalYaw),
                    finalGoalPitch - primaryGoalPitch
            ) > 1.2;
            this.inCorrectionSubmovement = false;
        } else {
            this.primaryGoalYaw = this.finalGoalYaw;
            this.primaryGoalPitch = this.finalGoalPitch;
            this.hasCorrectionSubmovement = false;
            this.inCorrectionSubmovement = false;
        }
    }

    private Rotation applyQuantizedStep(float targetYaw, float targetPitch, double sensitivity) {
        float prevYaw = this.currentYaw;
        float prevPitch = this.currentPitch;

        float qYaw = quantizeAngle(prevYaw, targetYaw, sensitivity);
        float qPitch = Rotation.clampPitch(quantizeAngle(prevPitch, targetPitch, sensitivity));

        this.velYaw = qYaw - prevYaw;
        this.velPitch = qPitch - prevPitch;
        this.currentYaw = qYaw;
        this.currentPitch = qPitch;

        return new Rotation(qYaw, qPitch);
    }

    /**
     * Applies Minecraft's exact mouse sensitivity quantization step (GCD).
     */
    public static float quantizeAngle(float current, float target, double sensitivity) {
        float step = mouseStepAngle(sensitivity);
        if (step <= 0f) {
            return target;
        }
        long pixels = Math.round((target - current) / step);
        return current + pixels * step;
    }

    public static float mouseStepAngle(double sensitivity) {
        double f = sensitivity * (double) 0.6f + (double) 0.2f;
        return (float) (1.0d * f * f * f * 8.0d) * 0.15f;
    }

    public boolean isAlignedWith(Rotation target, float maxErrorDeg) {
        if (!initialized || target == null) {
            return false;
        }
        return angularDistance(new Rotation(currentYaw, currentPitch), target) <= maxErrorDeg;
    }

    public static float angularDistance(Rotation a, Rotation b) {
        float dy = shortestYawDelta(a.getYaw(), b.getYaw());
        float dp = b.getPitch() - a.getPitch();
        return (float) Math.hypot(dy, dp);
    }

    public static float shortestYawDelta(float fromYaw, float toYaw) {
        return Rotation.normalizeYaw(toYaw - fromYaw);
    }

    private static float nudgeHorizonPitch(float pitch, long tick) {
        // Gentle horizon centering with subtle step-cycle oscillation (never 0 pitch delta while walking)
        float bob = (float) (Math.sin(tick * 0.55) * 0.35 + Math.sin(tick * 0.17) * 0.22);
        if (pitch < -18f) {
            return pitch + 0.85f + bob;
        } else if (pitch > 12f) {
            return pitch - 0.85f + bob;
        }
        return pitch + bob * 0.4f;
    }

    private double nextPinkYaw(double scale) {
        double white = (rand.nextDouble() - 0.5) * 2.0;
        pinkYaw0 = 0.99765 * pinkYaw0 + white * 0.0990460;
        pinkYaw1 = 0.96300 * pinkYaw1 + white * 0.2965164;
        pinkYaw2 = 0.57000 * pinkYaw2 + white * 1.0526913;
        return (pinkYaw0 + pinkYaw1 + pinkYaw2 + white * 0.1848) * 0.12 * scale;
    }

    private double nextPinkPitch(double scale) {
        double white = (rand.nextDouble() - 0.5) * 2.0;
        pinkPitch0 = 0.99765 * pinkPitch0 + white * 0.0990460;
        pinkPitch1 = 0.96300 * pinkPitch1 + white * 0.2965164;
        pinkPitch2 = 0.57000 * pinkPitch2 + white * 1.0526913;
        return (pinkPitch0 + pinkPitch1 + pinkPitch2 + white * 0.1848) * 0.12 * scale;
    }

    public Rotation currentRotation() {
        return new Rotation(currentYaw, currentPitch);
    }

    public float velocityDegPerTick() {
        return (float) Math.hypot(velYaw, velPitch);
    }
}
