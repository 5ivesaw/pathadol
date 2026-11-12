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
import baritone.api.utils.RotationUtils;
import baritone.behavior.look.ForkableRandom;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Map;

/**
 * Frustum and log-normal reaction-time gate for newly discovered blocks or entities.
 * Prevents instant 360-degree 1-tick snapping to newly exposed ores or targets.
 */
public final class PerceptionGate {

    private static final float DEFAULT_FOV_HALF_ANGLE_DEG = 68.0f;

    private final ForkableRandom rand;
    private final Map<BlockPos, Long> readyAtTick = new HashMap<>();
    private long tickCounter = 0L;

    public PerceptionGate(ForkableRandom rand) {
        this.rand = rand;
    }

    public void tick() {
        tickCounter++;
        if (readyAtTick.size() > 512) {
            long cutoff = tickCounter - 600L;
            readyAtTick.entrySet().removeIf(e -> e.getValue() < cutoff);
        }
    }

    public void reset() {
        readyAtTick.clear();
    }

    /**
     * Checks whether {@code pos} is within the player's field of view (or adjacent peripheral awareness),
     * has line-of-sight reachability, and has been perceived for at least one human reaction delay.
     */
    public boolean canPerceiveBlock(
            IPlayerContext ctx,
            BlockPos pos,
            double maxReachDist,
            HumanProfile profile
    ) {
        Vec3 head = ctx.playerHead();
        Vec3 center = new Vec3(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
        double distSq = head.distanceToSqr(center);

        Rotation currentRot = ctx.playerRotations();
        Rotation toBlock = RotationUtils.calcRotationFromVec3d(head, center, currentRot);
        float angleFromGaze = MouseModel.angularDistance(currentRot, toBlock);

        // Allow wider peripheral awareness for blocks right next to the player (<= 2.6 blocks),
        // otherwise require the block to be inside the camera's visual cone.
        float maxAngle = distSq <= 6.76 ? 115.0f : DEFAULT_FOV_HALF_ANGLE_DEG;
        if (angleFromGaze > maxAngle) {
            return false;
        }

        if (!RotationUtils.reachable(ctx, pos, maxReachDist).isPresent()) {
            return false;
        }

        Long readyTick = readyAtTick.get(pos);
        if (readyTick == null) {
            int delayTicks = profile.sampleReactionTicks(rand);
            readyTick = tickCounter + delayTicks;
            readyAtTick.put(pos.immutable(), readyTick);
        }
        return tickCounter >= readyTick;
    }
}
