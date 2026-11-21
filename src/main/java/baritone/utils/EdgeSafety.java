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

package baritone.utils;

import baritone.api.utils.IPlayerContext;
import baritone.pathing.movement.MovementHelper;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;

/**
 * Pure world-geometry check shared by input clearing and macro switching: is the player standing on only a
 * sliver of a block (hitbox overhanging air with no floor close underneath)? Releasing SNEAK or abandoning
 * a bridge at that instant walks the player off the edge.
 */
public final class EdgeSafety {

    private EdgeSafety() {
    }

    /**
     * True when the hitbox corners (x/z +-0.29) are mostly over air with no floor within 4 blocks beneath them.
     */
    public static boolean isPerchedOverVoid(IPlayerContext ctx) {
        return isPerchedOverVoid(ctx, 3);
    }

    /**
     * @param maxSupportedCorners hitbox corners allowed to still touch floor (1 = deep overhang, 3 = any edge)
     */
    public static boolean isPerchedOverVoid(IPlayerContext ctx, int maxSupportedCorners) {
        LocalPlayer p = ctx != null ? ctx.player() : null;
        if (p == null || ctx.world() == null || !p.onGround() || p.isInWater() || p.isSpectator()) {
            return false;
        }
        double px = p.getX();
        double pz = p.getZ();
        int footY = (int) Math.floor(p.getY() - 0.01);
        BlockPos centerBelow = new BlockPos((int) Math.floor(px), footY, (int) Math.floor(pz));
        boolean centerSolid = MovementHelper.canWalkOn(ctx, centerBelow)
                || !ctx.world().getBlockState(centerBelow).getCollisionShape(ctx.world(), centerBelow).isEmpty();
        boolean[] outVoidAround = new boolean[]{true};
        int supported = countSupportedCorners(ctx, px, pz, footY, 0.29, outVoidAround);
        if (supported == 0 && !centerSolid) {
            // A sneaking 0.6-wide hitbox (+-0.30) can cling at 0.291..0.300 past a block edge where +-0.29 misses all 4 corners
            supported = countSupportedCorners(ctx, px, pz, footY, 0.305, outVoidAround);
        }
        boolean voidAroundUnsupported = outVoidAround[0];
        // Must have >= 1 supported corner; supported == 0 means standing on a non-standard collision surface (slab/bed/carpet), NOT overhanging a ledge!
        // When the player center is already past the block edge over void (e.g. straight 1-wide bridge overhang with 2 corners clinging), releasing SNEAK drops immediately!
        boolean centerHangingOverVoid = !centerSolid && supported > 0 && supported <= 2 && voidAroundUnsupported;
        return centerHangingOverVoid || (supported > 0 && supported <= maxSupportedCorners && supported < 4 && voidAroundUnsupported);
    }

    private static int countSupportedCorners(
            IPlayerContext ctx,
            double px,
            double pz,
            int footY,
            double halfWidth,
            boolean[] outVoidAround
    ) {
        int supported = 0;
        boolean voidAroundUnsupported = true;
        for (int sx = -1; sx <= 1; sx += 2) {
            for (int sz = -1; sz <= 1; sz += 2) {
                int bx = (int) Math.floor(px + sx * halfWidth);
                int bz = (int) Math.floor(pz + sz * halfWidth);
                BlockPos below = new BlockPos(bx, footY, bz);
                boolean solidHere = MovementHelper.canWalkOn(ctx, below)
                        || !ctx.world().getBlockState(below).getCollisionShape(ctx.world(), below).isEmpty();
                if (solidHere) {
                    supported++;
                } else {
                    for (int dy = 1; dy <= 8; dy++) {
                        BlockPos under = new BlockPos(bx, footY - dy, bz);
                        if (MovementHelper.canWalkOn(ctx, under)
                                || !ctx.world().getBlockState(under).getCollisionShape(ctx.world(), under).isEmpty()) {
                            voidAroundUnsupported = false;
                            break;
                        }
                    }
                }
            }
        }
        outVoidAround[0] = voidAroundUnsupported;
        return supported;
    }
}
