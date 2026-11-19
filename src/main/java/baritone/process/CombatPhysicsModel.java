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

package baritone.process;

import net.minecraft.world.phys.Vec3;

/**
 * Predictive kinematic and bio-motor combat physics model:
 * <ul>
 *   <li>1-tick kinematic intercept box distance ({@code pos + vel})</li>
 *   <li>Void-vector strafe alignment so W-tap knockback pushes enemies toward the nearest cliff</li>
 *   <li>Human log-normal inter-click interval sampler (p50 ~3-4t, spread 2-6t, capped &lt;= 10 CPS)</li>
 * </ul>
 */
public final class CombatPhysicsModel {

    private CombatPhysicsModel() {}

    /**
     * Predicts the closest horizontal+vertical distance from our next-tick eye position to the
     * target's next-tick AABB using linear velocity extrapolation.
     */
    public static double predictNextTickBoxDist(
            Vec3 selfEyePos,
            Vec3 selfVel,
            Vec3 targetPos,
            Vec3 targetVel,
            double halfWidth,
            double height
    ) {
        if (selfEyePos == null || targetPos == null) {
            return 99.0;
        }
        double ex = selfEyePos.x + (selfVel != null ? selfVel.x : 0.0);
        double ey = selfEyePos.y + (selfVel != null ? selfVel.y : 0.0);
        double ez = selfEyePos.z + (selfVel != null ? selfVel.z : 0.0);

        double tx = targetPos.x + (targetVel != null ? targetVel.x : 0.0);
        double ty = targetPos.y + (targetVel != null ? targetVel.y : 0.0);
        double tz = targetPos.z + (targetVel != null ? targetVel.z : 0.0);

        double dx = Math.max(0.0, Math.abs(ex - tx) - halfWidth);
        double dz = Math.max(0.0, Math.abs(ez - tz) - halfWidth);
        double dy = 0.0;
        if (ey < ty) {
            dy = ty - ey;
        } else if (ey > ty + height) {
            dy = ey - (ty + height);
        }
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /**
     * Chooses the lateral strafe direction ({@code +1} right or {@code -1} left) that keeps our
     * footing safe while rotating our knockback vector toward the nearest void cliff behind the target.
     *
     * @param voidDist16 16-ray void distances (0=forward, 4=right, 8=back, 12=left)
     * @param safe16     16-ray footing safety flags
     * @param fallback   current strafe direction (+1 or -1)
     */
    public static int computeVoidPressureStrafeSign(double[] voidDist16, boolean[] safe16, int fallback) {
        if (voidDist16 == null || voidDist16.length < 16 || safe16 == null || safe16.length < 16) {
            return fallback >= 0 ? 1 : -1;
        }
        boolean rightSafe = safe16[4] && voidDist16[4] >= 2.50;
        boolean leftSafe = safe16[12] && voidDist16[12] >= 2.50;
        if (rightSafe && !leftSafe) {
            return 1;
        }
        if (leftSafe && !rightSafe) {
            return -1;
        }
        if (!rightSafe && !leftSafe) {
            return 0;
        }
        // If there is a cliff diagonally forward-left (rays 14..15) and more ground on the right,
        // strafe right (+1) so our forward knockback vector points toward the forward-left cliff.
        double frontLeftVoid = Math.min(voidDist16[14], voidDist16[15]);
        double frontRightVoid = Math.min(voidDist16[1], voidDist16[2]);
        if (frontLeftVoid + 1.4 < frontRightVoid && voidDist16[4] >= 3.2) {
            return 1;
        }
        if (frontRightVoid + 1.4 < frontLeftVoid && voidDist16[12] >= 3.2) {
            return -1;
        }
        return fallback >= 0 ? 1 : -1;
    }

    /**
     * Samples a human-like inter-click interval in ticks using a clamped log-normal distribution
     * calibrated to winning human PvP telemetry (p50 ~3-4t, natural 2-6t spread, strictly >= 2t in
     * strict legit mode so CPS never exceeds 10.0 or locks into metronomic 3t bursts).
     */
    public static int nextHumanAttackDelayTicks(double targetCps, boolean strictLegit, double u1, double u2) {
        double safeCps = strictLegit
                ? Math.max(4.5, Math.min(7.5, targetCps))
                : Math.max(6.0, Math.min(11.0, targetCps));
        double meanTicks = 20.0 / safeCps;
        double a = Math.max(1e-6, Math.min(1.0 - 1e-6, u1));
        double b = Math.max(0.0, Math.min(1.0, u2));
        double z = Math.sqrt(-2.0 * Math.log(a)) * Math.cos(2.0 * Math.PI * b);
        double sigma = strictLegit ? 0.34 : 0.24;
        double raw = meanTicks * Math.exp(sigma * z - 0.5 * sigma * sigma);
        int minTicks = strictLegit ? 2 : 1;
        int maxTicks = strictLegit ? 7 : 5;
        return Math.max(minTicks, Math.min(maxTicks, (int) Math.round(raw)));
    }
}
