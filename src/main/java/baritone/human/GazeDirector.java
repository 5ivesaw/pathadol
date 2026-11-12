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

        // 1. Look-ahead pre-aiming: check next 1..3 movements for upcoming block breaks or placements
        int maxPreAimIndex = Math.min(movements.size() - 1, pathPosition + 3);
        for (int i = pathPosition + 1; i <= maxPreAimIndex; i++) {
            Movement upcoming = (Movement) movements.get(i);
            List<BlockPos> breaks = upcoming.toBreak(bsi);
            if (!breaks.isEmpty()) {
                BlockPos targetPos = breaks.get(0);
                Vec3 center = new Vec3(targetPos.getX() + 0.5, targetPos.getY() + 0.5, targetPos.getZ() + 0.5);
                if (head.distanceToSqr(center) <= 36.0) {
                    Rotation preAim = RotationUtils.calcRotationFromVec3d(head, center, currentRot);
                    return clampGazeToMoveCone(preAim, moveYaw, maxYawOffset);
                }
            }
            List<BlockPos> places = upcoming.toPlace(bsi);
            if (!places.isEmpty()) {
                BlockPos targetPos = places.get(0);
                Vec3 center = new Vec3(targetPos.getX() + 0.5, targetPos.getY() + 0.85, targetPos.getZ() + 0.5);
                if (head.distanceToSqr(center) <= 30.0) {
                    Rotation preAim = RotationUtils.calcRotationFromVec3d(head, center, currentRot);
                    return clampGazeToMoveCone(preAim, moveYaw, maxYawOffset);
                }
            }
        }

        // 2. Horizon corridor gaze: look 5-7 movements ahead at eye height
        int horizonIndex = Math.min(movements.size() - 1, pathPosition + 6);
        BetterBlockPos horizonBlock = movements.get(horizonIndex).getDest();
        Vec3 horizonEye = new Vec3(
                horizonBlock.x + 0.5,
                horizonBlock.y + 1.52,
                horizonBlock.z + 0.5
        );
        Rotation horizonRot = RotationUtils.calcRotationFromVec3d(head, horizonEye, currentRot);
        float gentlePitch = Rotation.clampPitch(horizonRot.getPitch() * 0.55f);
        return clampGazeToMoveCone(new Rotation(horizonRot.getYaw(), gentlePitch), moveYaw, maxYawOffset);
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
