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
import baritone.api.utils.Helper;
import baritone.api.utils.IPlayerContext;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.Locale;

/**
 * Little Nightmares / Reanimal-inspired cinematic diorama follow camera.
 *
 * <p>Detaches rendering viewpoint cleanly into a spring-damped side-angle, 3/4 isometric, or intimate
 * chase perspective while leaving player rotations and combat/mining interactions untouched.
 */
public final class DioramaCameraController {

    public enum Mode {
        OFF("off", "OFF"),
        ISO("iso", "3/4 ISO Diorama"),
        SIDE("side", "2.5D Side Diorama"),
        CLOSE("close", "Over-Shoulder Follow");

        public final String id;
        public final String displayName;

        Mode(String id, String displayName) {
            this.id = id;
            this.displayName = displayName;
        }

        public static Mode parse(String s) {
            if (s == null) {
                return OFF;
            }
            String norm = s.trim().toLowerCase(Locale.ROOT);
            for (Mode m : values()) {
                if (m.id.equals(norm)) {
                    return m;
                }
            }
            return OFF;
        }

        public Mode next() {
            Mode[] v = values();
            return v[(ordinal() + 1) % v.length];
        }
    }

    private static boolean initialized = false;
    private static double prevFocusX, prevFocusY, prevFocusZ;
    private static double focusX, focusY, focusZ;
    private static double smoothVelX, smoothVelZ;
    private static float smoothHeadingDeg = -90.0f;
    private static double prevCamX, prevCamY, prevCamZ;
    private static double camX, camY, camZ;
    private static long tickCounter = 0L;
    private static boolean f7WasDown = false;
    private static volatile boolean botDriving = false;

    private DioramaCameraController() {
    }

    public static void setBotDriving(boolean driving) {
        botDriving = driving;
    }

    public static Mode getEffectiveMode() {
        Mode manual = Mode.parse(Baritone.settings().dioramaCameraMode.value);
        if (manual != Mode.OFF) {
            return manual;
        }
        if (Boolean.TRUE.equals(Baritone.settings().dioramaCameraOnBot.value) && botDriving) {
            return Mode.ISO;
        }
        return Mode.OFF;
    }

    public static boolean isCameraOverrideActive() {
        return getEffectiveMode() != Mode.OFF;
    }

    public static Mode cycleManualMode(IPlayerContext ctx) {
        Mode next = Mode.parse(Baritone.settings().dioramaCameraMode.value).next();
        Baritone.settings().dioramaCameraMode.value = next.id;
        resetSpringState();
        if (ctx != null && ctx.player() != null) {
            ctx.player().sendOverlayMessage(
                    Component.literal("[Pathadol] Diorama Cam [F7]: " + next.displayName)
            );
        } else {
            Helper.HELPER.logDirect("Diorama Camera mode -> " + next.displayName);
        }
        return next;
    }

    public static void setManualMode(Mode mode) {
        Baritone.settings().dioramaCameraMode.value = mode.id;
        resetSpringState();
    }

    public static void resetSpringState() {
        initialized = false;
    }

    public static void onPostTick(IPlayerContext ctx, boolean isBotDriving) {
        botDriving = isBotDriving;
        if (ctx == null || ctx.player() == null) {
            initialized = false;
            f7WasDown = false;
            return;
        }

        // Poll F7 outside any Screen so user can toggle Diorama camera freely mid-run or in freeplay
        Minecraft mc = ctx.minecraft();
        boolean allowHotkey = mc != null && mc.gui != null
                && (mc.gui.screen() == null || mc.gui.screen() instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?>);
        boolean f7Down = allowHotkey && InputConstants.isKeyDown(InputConstants.KEY_F7);
        if (f7Down && !f7WasDown) {
            cycleManualMode(ctx);
        }
        f7WasDown = f7Down;

        Mode mode = getEffectiveMode();
        if (mode == Mode.OFF) {
            initialized = false;
            return;
        }

        tickCounter++;
        Entity entity = ctx.player();
        Vec3 pos = entity.position();
        Vec3 vel = entity.getDeltaMovement();
        stepSpringTick(
                mode,
                pos.x,
                pos.y,
                pos.z,
                vel.x,
                vel.z,
                entity.getYRot(),
                entity.level()
        );
    }

    static void stepSpringTick(
            Mode mode,
            double ex,
            double ey,
            double ez,
            double vx,
            double vz,
            float entityYaw,
            Level world
    ) {
        smoothVelX = smoothVelX * 0.72 + clamp(vx * 3.2, -2.0, 2.0) * 0.28;
        smoothVelZ = smoothVelZ * 0.72 + clamp(vz * 3.2, -2.0, 2.0) * 0.28;
        double speedSq = vx * vx + vz * vz;
        if (speedSq > 0.0025) {
            float velYaw = (float) Math.toDegrees(Math.atan2(-vx, vz));
            float diff = wrapDeg(velYaw - smoothHeadingDeg);
            smoothHeadingDeg = wrapDeg(smoothHeadingDeg + diff * 0.14f);
        } else {
            float diff = wrapDeg(entityYaw - smoothHeadingDeg);
            smoothHeadingDeg = wrapDeg(smoothHeadingDeg + diff * 0.08f);
        }

        double targetFocusX = ex + smoothVelX * 0.55;
        double targetFocusY = ey + 1.05;
        double targetFocusZ = ez + smoothVelZ * 0.55;

        if (!initialized) {
            focusX = prevFocusX = targetFocusX;
            focusY = prevFocusY = targetFocusY;
            focusZ = prevFocusZ = targetFocusZ;
        } else {
            prevFocusX = focusX;
            prevFocusY = focusY;
            prevFocusZ = focusZ;
            // Weighty vertical lag mirrors Little Nightmares / Reanimal diorama feel on jumps & drops
            focusX += (targetFocusX - focusX) * 0.24;
            focusY += (targetFocusY - focusY) * 0.14;
            focusZ += (targetFocusZ - focusZ) * 0.24;
        }

        double[] desiredCam = computeDesiredCameraOffset(mode, smoothHeadingDeg, tickCounter);
        Vec3 desiredWorld = new Vec3(focusX + desiredCam[0], focusY + desiredCam[1], focusZ + desiredCam[2]);
        Vec3 finalCam = world != null
                ? resolveWorldClip(world, new Vec3(ex, ey + 1.35, ez), desiredWorld, smoothHeadingDeg)
                : desiredWorld;

        if (!initialized) {
            camX = prevCamX = finalCam.x;
            camY = prevCamY = finalCam.y;
            camZ = prevCamZ = finalCam.z;
            initialized = true;
        } else {
            prevCamX = camX;
            prevCamY = camY;
            prevCamZ = camZ;
            camX += (finalCam.x - camX) * 0.30;
            camY += (finalCam.y - camY) * 0.24;
            camZ += (finalCam.z - camZ) * 0.30;
        }
    }

    static double[] snapshotStateForTest() {
        float[] rot = computeLookAngles(new Vec3(camX, camY, camZ), new Vec3(focusX, focusY, focusZ));
        return new double[]{camX, camY, camZ, focusX, focusY, focusZ, rot[0], rot[1]};
    }

    /**
     * Computes render-frame diorama camera state {@code [x, y, z, yaw, pitch]}, or {@code null} when disabled.
     */
    public static double[] computeCameraState(Entity entity, float partialTicks) {
        if (!isCameraOverrideActive() || entity == null || !initialized) {
            return null;
        }
        double x = Mth.lerp(partialTicks, prevCamX, camX);
        double y = Mth.lerp(partialTicks, prevCamY, camY);
        double z = Mth.lerp(partialTicks, prevCamZ, camZ);
        double fx = Mth.lerp(partialTicks, prevFocusX, focusX);
        double fy = Mth.lerp(partialTicks, prevFocusY, focusY);
        double fz = Mth.lerp(partialTicks, prevFocusZ, focusZ);
        float[] rot = computeLookAngles(new Vec3(x, y, z), new Vec3(fx, fy, fz));
        return new double[]{x, y, z, rot[0], rot[1]};
    }

    public static double[] computeDesiredCameraOffset(Mode mode, float headingDeg, long tick) {
        double breatheY = Math.sin(tick * 0.065) * 0.05;
        double breatheX = Math.cos(tick * 0.045) * 0.04;
        return switch (mode) {
            case SIDE -> new double[]{-1.75 + breatheX, 3.75 + breatheY, 8.6};
            case ISO -> new double[]{-5.3 + breatheX, 6.1 + breatheY, 7.6};
            case CLOSE -> {
                double rad = Math.toRadians(headingDeg);
                double backX = Math.sin(rad);
                double backZ = -Math.cos(rad);
                yield new double[]{backX * 4.5, 2.35 + breatheY, backZ * 4.5};
            }
            case OFF -> new double[]{0.0, 0.0, 0.0};
        };
    }

    public static float[] computeLookAngles(Vec3 camPos, Vec3 focus) {
        double dx = focus.x - camPos.x;
        double dy = focus.y - camPos.y;
        double dz = focus.z - camPos.z;
        double horiz = Math.hypot(dx, dz);
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float pitch = (float) clamp(-Math.toDegrees(Math.atan2(dy, Math.max(1e-4, horiz))), -89.0, 89.0);
        return new float[]{yaw, pitch};
    }

    private static Vec3 resolveWorldClip(Level world, Vec3 pivot, Vec3 desired, float headingDeg) {
        Vec3 delta = desired.subtract(pivot);
        double len = delta.length();
        if (len < 1e-4) {
            return desired;
        }
        BlockHitResult hit = world.clip(new ClipContext(
                pivot,
                desired,
                ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE,
                net.minecraft.world.phys.shapes.CollisionContext.empty()
        ));
        if (hit.getType() != HitResult.Type.MISS) {
            Vec3 hitLoc = hit.getLocation();
            double clippedDist = hitLoc.distanceTo(pivot);
            if (clippedDist >= 2.2) {
                return pivot.add(delta.normalize().scale(clippedDist - 0.42));
            }
            // Enclosed roof/corridor fallback: pull smoothly behind entity inside tunnel headroom
            double rad = Math.toRadians(headingDeg);
            Vec3 fallbackDesired = pivot.add(Math.sin(rad) * 2.4, 0.55, -Math.cos(rad) * 2.4);
            BlockHitResult fbHit = world.clip(new ClipContext(
                    pivot,
                    fallbackDesired,
                    ClipContext.Block.COLLIDER,
                    ClipContext.Fluid.NONE,
                    net.minecraft.world.phys.shapes.CollisionContext.empty()
            ));
            if (fbHit.getType() != HitResult.Type.MISS) {
                double d = Math.max(0.75, fbHit.getLocation().distanceTo(pivot) - 0.35);
                return pivot.add(fallbackDesired.subtract(pivot).normalize().scale(d));
            }
            return fallbackDesired;
        }
        // Guard against camera sitting directly inside a roof slab corner
        if (world.getBlockState(BlockPos.containing(desired)).isSuffocating(world, BlockPos.containing(desired))) {
            return pivot.add(delta.scale(0.65));
        }
        return desired;
    }

    private static float wrapDeg(float deg) {
        float d = deg % 360.0f;
        if (d >= 180.0f) {
            d -= 360.0f;
        } else if (d < -180.0f) {
            d += 360.0f;
        }
        return d;
    }

    private static double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }
}
