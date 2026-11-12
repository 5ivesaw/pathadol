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
import baritone.api.pathing.calc.IPath;
import baritone.api.pathing.movement.IMovement;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.IPlayerContext;
import baritone.api.utils.Rotation;
import baritone.api.utils.RotationUtils;
import baritone.pathing.movement.Movement;
import baritone.utils.BlockStateInterface;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * Decouples human camera gaze from foot movement direction:
 * <ul>
 *   <li>Pre-aims 3-5 ticks ahead at upcoming break/place targets while feet keep following the current corridor</li>
 *   <li>Aims at eye-level horizon 5-8 blocks down the path during locomotion instead of staring at the floor</li>
 *   <li>Clamps gaze yaw within {@code humanSprintConeDeg} of movement yaw while sprinting</li>
 * </ul>
 */
public final class GazeDirector {

    /**
     * Computes the desired camera rotation for the current tick along {@code path} at {@code pathPosition},
     * or returns {@code null} if the current movement requires strict block/entity interaction and already
     * owns the camera target.
     */
    public Rotation computeGazeRotation(
            IPath path,
            int pathPosition,
            IPlayerContext ctx,
            BlockStateInterface bsi,
            Float desiredMoveYaw,
            boolean sprinting
    ) {
        return computeGazeRotation(path, pathPosition, ctx, bsi, desiredMoveYaw, sprinting, null);
    }

    /**
     * Computes a smooth, continuous camera rotation along {@code path}. When {@code smoothedGazeTarget}
     * from {@link PathSmoother} is available, tracks the spline lookahead directly; otherwise samples
     * a continuous 6-block arc-length point along upcoming movement segments to eliminate grid stepping.
     */
    public Rotation computeGazeRotation(
            IPath path,
            int pathPosition,
            IPlayerContext ctx,
            BlockStateInterface bsi,
            Float desiredMoveYaw,
            boolean sprinting,
            Vec3 smoothedGazeTarget
    ) {
        if (!Baritone.settings().humanMode.value || path == null || pathPosition < 0 || pathPosition >= path.movements().size()) {
            return null;
        }

        List<IMovement> movements = path.movements();
        Movement current = (Movement) movements.get(pathPosition);
        if (!current.toBreak(bsi).isEmpty() || !current.toPlace(bsi).isEmpty()) {
            // Active movement is interacting with a block; let its MUST_HIT target control the camera
            return null;
        }

        Vec3 head = ctx.playerHead();
        Rotation currentRot = ctx.playerRotations();
        float moveYaw = desiredMoveYaw != null ? desiredMoveYaw : currentRot.getYaw();
        float maxYawOffset = sprinting
                ? (float) Math.max(15.0, Baritone.settings().humanSprintConeDeg.value * 0.82)
                : 75.0f;

        // 1. Look-ahead pre-aiming: glance at upcoming break/place targets within natural interaction range
        int maxPreAimIndex = Math.min(movements.size() - 1, pathPosition + 2);
        for (int i = pathPosition + 1; i <= maxPreAimIndex; i++) {
            Movement upcoming = (Movement) movements.get(i);
            List<BlockPos> breaks = upcoming.toBreak(bsi);
            if (!breaks.isEmpty()) {
                BlockPos targetPos = breaks.get(0);
                Vec3 center = new Vec3(targetPos.getX() + 0.5, targetPos.getY() + 0.5, targetPos.getZ() + 0.5);
                if (head.distanceToSqr(center) <= 22.0) {
                    Rotation preAim = RotationUtils.calcRotationFromVec3d(head, center, currentRot);
                    return clampGazeToMoveCone(preAim, moveYaw, maxYawOffset);
                }
            }
            List<BlockPos> places = upcoming.toPlace(bsi);
            if (!places.isEmpty()) {
                BlockPos targetPos = places.get(0);
                Vec3 center = new Vec3(targetPos.getX() + 0.5, targetPos.getY() + 0.85, targetPos.getZ() + 0.5);
                if (head.distanceToSqr(center) <= 20.25) {
                    Rotation preAim = RotationUtils.calcRotationFromVec3d(head, center, currentRot);
                    return clampGazeToMoveCone(preAim, moveYaw, maxYawOffset);
                }
            }
        }

        // 2. Continuous spline / arc-length horizon gaze (~6 blocks ahead at natural eye level)
        Vec3 horizonPoint = smoothedGazeTarget != null
                ? new Vec3(smoothedGazeTarget.x, smoothedGazeTarget.y + 1.52, smoothedGazeTarget.z)
                : sampleContinuousArcPoint(ctx.playerFeetAsVec(), movements, pathPosition, 6.0);

        Rotation horizonRot = RotationUtils.calcRotationFromVec3d(head, horizonPoint, currentRot);
        float targetYaw = horizonRot.getYaw();
        if (desiredMoveYaw != null) {
            // Keep camera flowing gently with curved steering so turns feel fluid rather than crab-walked
            float yawLeadDiff = MouseModel.shortestYawDelta(desiredMoveYaw, targetYaw);
            if (Math.abs(yawLeadDiff) > 12.0f) {
                targetYaw = Rotation.normalizeYaw(desiredMoveYaw + Math.copySign(12.0f + (Math.abs(yawLeadDiff) - 12.0f) * 0.45f, yawLeadDiff));
            }
        }
        float gentlePitch = Rotation.clampPitch(horizonRot.getPitch() * 0.50f);
        return clampGazeToMoveCone(new Rotation(targetYaw, gentlePitch), moveYaw, maxYawOffset);
    }

    /**
     * Samples a continuous point at {@code arcDist} blocks forward along {@code movements} from {@code startPos}.
     */
    public static Vec3 sampleContinuousArcPoint(Vec3 startPos, List<IMovement> movements, int pathPosition, double arcDist) {
        if (movements == null || movements.isEmpty() || pathPosition < 0 || pathPosition >= movements.size()) {
            return startPos.add(0.0, 1.52, 0.0);
        }
        int startIdx = pathPosition;
        int maxIdx = Math.min(movements.size() - 1, pathPosition + 12);
        // Skip initial segments already passed horizontally so mid-air horizon gaze never flips backward
        while (startIdx < maxIdx) {
            BetterBlockPos src = movements.get(startIdx).getSrc();
            BetterBlockPos dst = movements.get(startIdx).getDest();
            double vx = dst.x - src.x;
            double vz = dst.z - src.z;
            double remDot = ((dst.x + 0.5) - startPos.x) * vx + ((dst.z + 0.5) - startPos.z) * vz;
            if (remDot <= 0.15 && (vx != 0.0 || vz != 0.0)) {
                startIdx++;
            } else {
                break;
            }
        }
        Vec3 cursor = startPos;
        double remaining = Math.max(0.5, arcDist);
        for (int i = startIdx; i <= maxIdx; i++) {
            BetterBlockPos dest = movements.get(i).getDest();
            Vec3 end = new Vec3(dest.x + 0.5, dest.y, dest.z + 0.5);
            double segLen = cursor.distanceTo(end);
            if (segLen < 1e-4) {
                cursor = end;
                continue;
            }
            if (segLen >= remaining) {
                double t = remaining / segLen;
                Vec3 pt = cursor.lerp(end, t);
                return new Vec3(pt.x, pt.y + 1.52, pt.z);
            }
            remaining -= segLen;
            cursor = end;
        }
        BetterBlockPos last = movements.get(maxIdx).getDest();
        return new Vec3(last.x + 0.5, last.y + 1.52, last.z + 0.5);
    }

    public static Rotation clampGazeToMoveCone(Rotation gaze, float moveYaw, float maxConeDeg) {
        float delta = MouseModel.shortestYawDelta(moveYaw, gaze.getYaw());
        if (Math.abs(delta) <= maxConeDeg) {
            return gaze;
        }
        float clampedYaw = Rotation.normalizeYaw(moveYaw + Math.copySign(maxConeDeg, delta));
        return new Rotation(clampedYaw, gaze.getPitch());
    }
}
