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
    private double startVelYawNorm;
    private double startVelPitchNorm;

    // Smoothed target angular velocity for predictive pursuit of moving mobs/corridors
    private boolean hasPrevDesired;
    private float prevDesiredYaw;
    private float prevDesiredPitch;
    private float targetVelYaw;
    private float targetVelPitch;

    private static final double SQRT3 = Math.sqrt(3.0);
    private static final double SQRT5 = Math.sqrt(5.0);

    // 3-stage pink noise state (Paul Kellet filter approximation)
    private double pinkYaw0, pinkYaw1, pinkYaw2;
    private double pinkPitch0, pinkPitch1, pinkPitch2;
    // WindMouse correlated lateral wind state for natural 2D curved flicks
    private double windYaw, windPitch;
    // Sub-pixel physiological wrist drift accumulators (quantized smoothly via hardware GCD)
    private double subpixelDriftYaw, subpixelDriftPitch;
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
        copy.startVelYawNorm = this.startVelYawNorm;
        copy.startVelPitchNorm = this.startVelPitchNorm;
        copy.hasPrevDesired = this.hasPrevDesired;
        copy.prevDesiredYaw = this.prevDesiredYaw;
        copy.prevDesiredPitch = this.prevDesiredPitch;
        copy.targetVelYaw = this.targetVelYaw;
        copy.targetVelPitch = this.targetVelPitch;
        copy.pinkYaw0 = this.pinkYaw0;
        copy.pinkYaw1 = this.pinkYaw1;
        copy.pinkYaw2 = this.pinkYaw2;
        copy.pinkPitch0 = this.pinkPitch0;
        copy.pinkPitch1 = this.pinkPitch1;
        copy.pinkPitch2 = this.pinkPitch2;
        copy.windYaw = this.windYaw;
        copy.windPitch = this.windPitch;
        copy.subpixelDriftYaw = this.subpixelDriftYaw;
        copy.subpixelDriftPitch = this.subpixelDriftPitch;
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
        this.startVelYawNorm = 0.0;
        this.startVelPitchNorm = 0.0;
        this.hasPrevDesired = false;
        this.targetVelYaw = 0f;
        this.targetVelPitch = 0f;
        this.windYaw = 0.0;
        this.windPitch = 0.0;
        this.subpixelDriftYaw = 0.0;
        this.subpixelDriftPitch = 0.0;
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
        return step(playerCurrent, desired, blockInteract, alreadyOnTarget, profile, sensitivity, false);
    }

    /**
     * Advances the mouse model by one game tick toward {@code desired}.
     * When {@code bridgeAnchor} is true, wide WindMouse arcs and overshoot are suppressed
     * so the crosshair never slips off a bridge placement face.
     */
    public Rotation step(
            Rotation playerCurrent,
            Rotation desired,
            boolean blockInteract,
            boolean alreadyOnTarget,
            HumanProfile profile,
            double sensitivity,
            boolean bridgeAnchor
    ) {
        tickCounter++;
        if (!initialized) {
            reset(playerCurrent);
        } else if (angularDistance(new Rotation(currentYaw, currentPitch), playerCurrent) > 35.0f) {
            // External teleport or manual camera movement
            reset(playerCurrent);
        }

        float targetPitch = desired.getPitch();
        if (!blockInteract && !bridgeAnchor && Math.abs(targetPitch - playerCurrent.getPitch()) < 0.001f) {
            targetPitch = nudgeHorizonPitch(playerCurrent.getPitch(), tickCounter);
        }

        // Track target angular velocity for predictive pursuit of moving targets/corridors
        if (hasPrevDesired) {
            float dDesYaw = shortestYawDelta(prevDesiredYaw, desired.getYaw());
            float dDesPitch = targetPitch - prevDesiredPitch;
            if (Math.hypot(dDesYaw, dDesPitch) < 28.0f) {
                targetVelYaw = targetVelYaw * 0.45f + clampFloat(dDesYaw, -10.0f, 10.0f) * 0.55f;
                targetVelPitch = targetVelPitch * 0.45f + clampFloat(dDesPitch, -8.0f, 8.0f) * 0.55f;
            } else {
                targetVelYaw = 0f;
                targetVelPitch = 0f;
            }
        } else {
            hasPrevDesired = true;
        }
        prevDesiredYaw = desired.getYaw();
        prevDesiredPitch = targetPitch;

        float leadYaw = desired.getYaw() + (bridgeAnchor ? 0f : targetVelYaw * 0.55f);
        float leadPitch = Rotation.clampPitch(targetPitch + (bridgeAnchor ? 0f : targetVelPitch * 0.45f));

        float deltaToNewGoalYaw = shortestYawDelta(finalGoalYaw, leadYaw);
        float deltaToNewGoalPitch = leadPitch - finalGoalPitch;
        float goalShift = (float) Math.hypot(deltaToNewGoalYaw, deltaToNewGoalPitch);

        float totalDistYaw = shortestYawDelta(currentYaw, desired.getYaw());
        float totalDistPitch = targetPitch - currentPitch;
        float totalDist = (float) Math.hypot(totalDistYaw, totalDistPitch);

        if (bridgeAnchor) {
            if (totalDist > 26.0f) {
                // Fast multi-tick ballistic flip instead of a single-tick 95-deg snap when turning onto a bridge face
                if (goalShift > 5.0f || durationTicks == 0) {
                    this.startYaw = this.currentYaw;
                    this.startPitch = this.currentPitch;
                    this.finalGoalYaw = this.currentYaw + totalDistYaw;
                    this.finalGoalPitch = Rotation.clampPitch(this.currentPitch + totalDistPitch);
                    this.primaryGoalYaw = this.finalGoalYaw;
                    this.primaryGoalPitch = this.finalGoalPitch;
                    this.elapsedTicks = 0;
                    this.durationTicks = Math.max(3, Math.min(8, computeFittsTicks(totalDist, 10.0, profile)));
                    this.startVelYawNorm = Math.abs(totalDistYaw) >= 3.0f ? clampDouble(velYaw * durationTicks / totalDistYaw, -0.35, 1.4) : 0.0;
                    this.startVelPitchNorm = Math.abs(totalDistPitch) >= 2.0f ? clampDouble(velPitch * durationTicks / totalDistPitch, -0.35, 1.4) : 0.0;
                    this.hasCorrectionSubmovement = false;
                    this.inCorrectionSubmovement = false;
                }
                elapsedTicks++;
                double tau = Math.min(1.0, (double) elapsedTicks / (double) durationTicks);
                double sy = hermiteMinimumJerk(tau, startVelYawNorm);
                double sp = hermiteMinimumJerk(tau, startVelPitchNorm);
                float nextYaw = startYaw + (float) (sy * (primaryGoalYaw - startYaw));
                float nextPitch = startPitch + (float) (sp * (primaryGoalPitch - startPitch));
                if (elapsedTicks >= durationTicks) {
                    durationTicks = 0;
                    elapsedTicks = 0;
                }
                return applyQuantizedStep(nextYaw, Rotation.clampPitch(nextPitch), sensitivity);
            }
            // Close-range Bridge Anchor Mode: tight damped tracking + tiny pink-noise drift, zero overshoot, zero arc
            this.durationTicks = 0;
            this.hasCorrectionSubmovement = false;
            this.inCorrectionSubmovement = false;
            double alpha = Math.min(0.88, Math.max(0.68, profile.pursuitAlpha() * 1.15));
            float tinyDriftYaw = (float) nextPinkYaw(profile.tremor() * 0.22);
            float tinyDriftPitch = (float) nextPinkPitch(profile.tremor() * 0.20);
            float nextYaw = currentYaw + (float) (totalDistYaw * alpha) + tinyDriftYaw;
            float nextPitch = Rotation.clampPitch(currentPitch + (float) (totalDistPitch * alpha) + tinyDriftPitch);
            return applyQuantizedStep(nextYaw, nextPitch, sensitivity);
        }

        float targetWidthDeg = blockInteract ? 5.5f : 11.5f;
        float coastRadiusDeg = blockInteract ? 1.45f : 2.10f;

        // Crosshair coasting only when already inside tight precision deadzone and target isn't moving quickly
        double targetSpeed = Math.hypot(targetVelYaw, targetVelPitch);
        if ((alreadyOnTarget && totalDist < targetWidthDeg * 0.90f)
                || (totalDist <= coastRadiusDeg && durationTicks == 0 && targetSpeed < 0.55)) {
            if (alreadyOnTarget) {
                float driftYaw = (float) nextPinkYaw(profile.tremor() * 0.28);
                float driftPitch = (float) nextPinkPitch(profile.tremor() * 0.24);
                return applyQuantizedStep(currentYaw + driftYaw, currentPitch + driftPitch, sensitivity);
            }
            // Accumulate continuous sub-pixel wrist micro-drift so GCD quantization produces natural small ticks instead of flat 0.000s plateaus
            double wanderYaw = 0.052 * Math.sin(tickCounter * 0.095 + 0.7) + 0.041 * Math.cos(tickCounter * 0.19);
            double wanderPitch = 0.032 * Math.cos(tickCounter * 0.085 + 0.4) + 0.022 * Math.sin(tickCounter * 0.23);
            double swayYaw = Math.sin(tickCounter * 0.21) * 0.036 + wanderYaw + nextPinkYaw(Math.max(0.09, profile.tremor()) * 0.85);
            double swayPitch = Math.cos(tickCounter * 0.17) * 0.026 + wanderPitch + nextPinkPitch(Math.max(0.08, profile.tremor()) * 0.74);
            subpixelDriftYaw = clampDouble(subpixelDriftYaw + swayYaw + totalDistYaw * 0.24, -0.42, 0.42);
            subpixelDriftPitch = clampDouble(subpixelDriftPitch + swayPitch + totalDistPitch * 0.22, -0.35, 0.35);
            float prevY = this.currentYaw;
            float prevP = this.currentPitch;
            Rotation stepped = applyQuantizedStep(
                    prevY + (float) subpixelDriftYaw,
                    Rotation.clampPitch(prevP + (float) subpixelDriftPitch),
                    sensitivity
            );
            subpixelDriftYaw = clampDouble(subpixelDriftYaw - shortestYawDelta(prevY, stepped.getYaw()), -0.35, 0.35);
            subpixelDriftPitch = clampDouble(subpixelDriftPitch - (stepped.getPitch() - prevP), -0.35, 0.35);
            return stepped;
        }

        float leadDistYaw = shortestYawDelta(currentYaw, leadYaw);
        float leadDistPitch = leadPitch - currentPitch;
        float leadDist = (float) Math.hypot(leadDistYaw, leadDistPitch);

        // Start or seamlessly redirect a velocity-continuous Hermite saccade if target jumped substantially
        if (goalShift > 5.0f || (durationTicks == 0 && totalDist > coastRadiusDeg)) {
            planSaccade(leadDistYaw, leadDistPitch, Math.max(totalDist, leadDist), targetWidthDeg, profile);
        } else if (goalShift > 0.05f && durationTicks > 0) {
            // Smooth pursuit update for small target shifts during an active movement
            float blend = (float) Math.max(0.65, profile.pursuitAlpha());
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
            double sy = hermiteMinimumJerk(tau, startVelYawNorm);
            double sp = hermiteMinimumJerk(tau, startVelPitchNorm);

            nextUnquantizedYaw = startYaw + (float) (sy * (primaryGoalYaw - startYaw));
            nextUnquantizedPitch = startPitch + (float) (sp * (primaryGoalPitch - startPitch));

            // WindMouse correlated lateral curvature modulated by sin(pi * tau)
            double windMag = Math.min(2.4, totalDist * 0.045) * Math.max(0.20, profile.tremor());
            windYaw = windYaw / SQRT3 + (2.0 * rand.nextDouble() - 1.0) * windMag / SQRT5;
            windPitch = windPitch / SQRT3 + (2.0 * rand.nextDouble() - 1.0) * (windMag * 0.72) / SQRT5;
            double arcEnvelope = Math.sin(Math.PI * tau);
            nextUnquantizedYaw += (float) (windYaw * arcEnvelope);
            nextUnquantizedPitch += (float) (windPitch * arcEnvelope);

            // Velocity-scaled motor noise + pink drift
            double speedNorm = Math.min(1.5, totalDist / 45.0);
            double noiseScale = profile.tremor() * (0.35 + 0.55 * speedNorm);
            nextUnquantizedYaw += (float) nextPinkYaw(noiseScale);
            nextUnquantizedPitch += (float) nextPinkPitch(noiseScale * 0.85);

            if (elapsedTicks >= durationTicks) {
                if (hasCorrectionSubmovement && !inCorrectionSubmovement) {
                    // Transition into corrective sub-movement preserving exit velocity
                    inCorrectionSubmovement = true;
                    startYaw = nextUnquantizedYaw;
                    startPitch = nextUnquantizedPitch;
                    primaryGoalYaw = finalGoalYaw;
                    primaryGoalPitch = finalGoalPitch;
                    elapsedTicks = 0;
                    float remYaw = shortestYawDelta(startYaw, finalGoalYaw);
                    float remPitch = finalGoalPitch - startPitch;
                    float remDist = (float) Math.hypot(remYaw, remPitch);
                    durationTicks = Math.max(1, Math.min(2, Math.round(remDist / 6.0f) + 1));
                    startVelYawNorm = Math.abs(remYaw) >= 1.5f ? clampDouble(velYaw * durationTicks / remYaw, -0.35, 1.2) : 0.0;
                    startVelPitchNorm = Math.abs(remPitch) >= 1.5f ? clampDouble(velPitch * durationTicks / remPitch, -0.35, 1.2) : 0.0;
                } else {
                    durationTicks = 0;
                    elapsedTicks = 0;
                    hasCorrectionSubmovement = false;
                    inCorrectionSubmovement = false;
                    startVelYawNorm = 0.0;
                    startVelPitchNorm = 0.0;
                }
            }
        } else {
            // Crisp smooth pursuit + target velocity feed-forward for tracking without stalling
            float alpha = (float) Math.max(0.60, profile.pursuitAlpha());
            nextUnquantizedYaw = currentYaw + totalDistYaw * alpha + targetVelYaw * 0.35f + (float) nextPinkYaw(profile.tremor() * 0.32);
            nextUnquantizedPitch = currentPitch + totalDistPitch * alpha + targetVelPitch * 0.30f + (float) nextPinkPitch(profile.tremor() * 0.28);
        }

        return applyQuantizedStep(nextUnquantizedYaw, Rotation.clampPitch(nextUnquantizedPitch), sensitivity);
    }

    /**
     * Computes the Fitts' law movement duration in ticks for an angular turn of {@code distanceDeg}
     * into a target of width {@code widthDeg}, bounded so peak minimum-jerk angular velocity never
     * exceeds realistic human wrist speed.
     */
    public static int computeFittsTicks(double distanceDeg, double widthDeg, HumanProfile profile) {
        if (distanceDeg <= 2.2) {
            return 1;
        }
        double id = Math.log(distanceDeg / Math.max(1.0, widthDeg) + 1.0) / Math.log(2.0);
        double rawTicks = (profile.fittsBaseTicks() + profile.fittsSlopeTicks() * id) / profile.aimSpeed();
        double maxPeakDegPerTick = Math.max(20.0, Math.min(34.0, 24.0 * profile.aimSpeed()));
        int minTicksForSpeed = distanceDeg <= 12.0
                ? 1
                : (int) Math.ceil((1.875 * distanceDeg) / maxPeakDegPerTick);
        return Math.max(1, Math.min(12, Math.max((int) Math.round(rawTicks), minTicksForSpeed)));
    }

    /**
     * Minimum-jerk position profile s(tau) = 10*tau^3 - 15*tau^4 + 6*tau^5 for tau in [0, 1].
     */
    public static double minimumJerk(double tau) {
        return hermiteMinimumJerk(tau, 0.0);
    }

    /**
     * Velocity-continuous quintic minimum-jerk Hermite spline matching normalized initial slope {@code u0}
     * and settling smoothly to zero acceleration/velocity at {@code tau = 1}.
     */
    public static double hermiteMinimumJerk(double tau, double u0) {
        if (tau <= 0.0) {
            return 0.0;
        }
        if (tau >= 1.0) {
            return 1.0;
        }
        double c3 = 10.0 - 6.0 * u0;
        double c4 = -15.0 + 8.0 * u0;
        double c5 = 6.0 - 3.0 * u0;
        double t2 = tau * tau;
        double t3 = t2 * tau;
        return u0 * tau + t3 * (c3 + tau * (c4 + tau * c5));
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

        // Preserve angular momentum across mid-flight target updates
        this.startVelYawNorm = Math.abs(deltaYaw) >= 3.0f
                ? clampDouble((velYaw * durationTicks) / deltaYaw, -0.35, 1.50)
                : 0.0;
        this.startVelPitchNorm = Math.abs(deltaPitch) >= 2.0f
                ? clampDouble((velPitch * durationTicks) / deltaPitch, -0.35, 1.50)
                : 0.0;

        // On larger flicks (> 42 deg) with overshoot enabled, create a modest primary overshoot (~2-6%) + 1-tick settle
        if (distanceDeg > 42.0f && profile.overshoot() > 0.01 && durationTicks >= 3) {
            double gain = 1.0 + profile.overshoot() * 0.10 * (rand.nextDouble() * 1.25 - 0.25);
            double ortho = profile.overshoot() * 0.025 * HumanProfile.nextGaussian(rand);
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

    private static float clampFloat(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static double clampDouble(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
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
