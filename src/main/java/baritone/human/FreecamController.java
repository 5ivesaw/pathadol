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
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.CameraType;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.KeyEvent;
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
 * Purely client-side, zero-interference Freecam supporting two modes:
 * <ul>
 *   <li><b>FLY ({@code #freecam fly}):</b> detached free-flying camera driven by WASD + Space/Shift + Ctrl boost + mouse,
 *       while the real {@code LocalPlayer} and any active bot processes continue completely undisturbed.</li>
 *   <li><b>LOOK ({@code #freecam look}):</b> pure client-side 360&deg; free-look camera attached to the player
 *       (1st-person eye view or F5 3rd-person orbit) without touching {@code player.yRot}/{@code xRot} or bot aiming.</li>
 * </ul>
 */
public final class FreecamController {

    public enum Mode {
        OFF("off", "OFF"),
        FLY("fly", "Free-Fly"),
        LOOK("look", "Free-Look");

        public final String id;
        public final String displayName;

        Mode(String id, String displayName) {
            this.id = id;
            this.displayName = displayName;
        }

        public static Mode parse(String s) {
            if (s == null || s.isBlank()) {
                return OFF;
            }
            return switch (s.trim().toLowerCase(Locale.ROOT)) {
                case "fly", "free", "roam", "cam", "on" -> FLY;
                case "look", "freelook", "orbit", "head" -> LOOK;
                default -> OFF;
            };
        }
    }

    private static volatile Mode mode = Mode.OFF;
    private static volatile double flySpeed = 0.75;

    private static int pressedMouseButtons;
    private static boolean initialized = false;
    private static Object lastWorld = null;
    private static double prevCamX, prevCamY, prevCamZ;
    private static double camX, camY, camZ;
    private static double velX, velY, velZ;
    private static float camYaw = 0.0f;
    private static float camPitch = 0.0f;

    private FreecamController() {
    }

    public static Mode getMode() {
        return mode;
    }

    public static boolean isActive() {
        return mode != Mode.OFF;
    }

    public static float getBaseFov() {
        return Minecraft.getInstance().options.fov().get();
    }

    public static boolean acceptsCameraInput(Minecraft mc) {
        return isActive() && mc != null && mc.player != null && mc.gui != null
                && !mc.isPaused() && mc.isWindowActive()
                && (mc.gui.screen() == null || mc.gui.screen() instanceof AbstractContainerScreen<?>);
    }

    public static boolean blocksGameplayKeys(Minecraft mc, KeyEvent event) {
        if (!isActive() || mc.player == null
                || (mc.gui.screen() != null && !(mc.gui.screen() instanceof AbstractContainerScreen<?>))) {
            return false;
        }
        Options options = mc.options;
        if (event.isEscape() || options.keyScreenshot.matches(event) || options.keyFullscreen.matches(event)) {
            return false;
        }
        if (mc.gui.screen() != null) {
            return true;
        }
        // Leave chat and client display controls available. A conflicting gameplay binding must stay blocked.
        for (KeyMapping mapping : options.keyMappings) {
            if (mapping.matches(event) && mapping != options.keyChat && mapping != options.keyCommand
                    && mapping != options.keyTogglePerspective && mapping != options.keyToggleGui
                    && mapping != options.keyPlayerList) {
                return true;
            }
        }
        return !(options.keyChat.matches(event) || options.keyCommand.matches(event)
                || options.keyTogglePerspective.matches(event) || options.keyToggleGui.matches(event)
                || options.keyPlayerList.matches(event));
    }

    public static boolean isFlyMode() {
        return mode == Mode.FLY;
    }

    public static boolean isLookMode() {
        return mode == Mode.LOOK;
    }

    public static boolean isDetachedCameraActive() {
        if (mode == Mode.FLY) {
            return true;
        }
        if (mode == Mode.LOOK) {
            Minecraft mc = Minecraft.getInstance();
            return mc != null && mc.options != null && mc.options.getCameraType() != CameraType.FIRST_PERSON;
        }
        return false;
    }

    public static double getFlySpeed() {
        return flySpeed;
    }

    public static double setFlySpeed(double speed) {
        flySpeed = Math.max(0.1, Math.min(4.0, speed));
        return flySpeed;
    }

    public static String setMode(Mode next, IPlayerContext ctx) {
        Mode target = next != null ? next : Mode.OFF;
        boolean enabling = !isActive() && target != Mode.OFF;
        mode = target;
        if (enabling) {
            KeyMapping.releaseAll();
            pressedMouseButtons = 0;
        }
        velX = 0.0;
        velY = 0.0;
        velZ = 0.0;
        if (target == Mode.OFF) {
            initialized = false;
            lastWorld = null;
            showOverlay(ctx, "[Freecam] OFF");
            return "Freecam disabled.";
        }
        if (ctx != null && ctx.player() != null) {
            anchorToEntity(ctx.player());
            lastWorld = ctx.world();
            grabCameraMouse(ctx.minecraft());
        } else {
            initialized = false;
        }
        String desc = target == Mode.FLY
                ? String.format(Locale.ROOT, "Free-Fly ON (speed=%.2f, WASD/Space/Shift + Ctrl boost, client-only)", flySpeed)
                : "Free-Look ON (360° client-only look, works in 1st-person & F5, zero bot interference)";
        showOverlay(ctx, "[Freecam] " + target.displayName);
        return desc;
    }

    private static void anchorToEntity(Entity e) {
        Vec3 eye = e.getEyePosition();
        camX = prevCamX = eye.x;
        camY = prevCamY = eye.y;
        camZ = prevCamZ = eye.z;
        camYaw = e.getYRot();
        camPitch = e.getXRot();
        velX = velY = velZ = 0.0;
        initialized = true;
    }

    private static void showOverlay(IPlayerContext ctx, String text) {
        if (ctx != null && ctx.player() != null) {
            ctx.player().sendOverlayMessage(Component.literal(text));
        }
    }

    /**
     * Called directly from {@code MixinMouseHandler.turnPlayer} when freecam is active.
     * Consumes raw cursor movement into client-only camera angles without ever turning {@code LocalPlayer}.
     */
    public static void onMouseDelta(double rawDx, double rawDy, Minecraft mc) {
        if (mode == Mode.OFF) {
            return;
        }
        if (!initialized && mc != null && mc.player != null) {
            anchorToEntity(mc.player);
            lastWorld = mc.level;
        }
        double sens = (mc != null && mc.options != null) ? mc.options.sensitivity().get() : 0.5;
        double f = sens * 0.6 + 0.2;
        double mult = f * f * f * 8.0 * 0.15;
        camYaw += (float) (rawDx * mult);
        camPitch = Mth.clamp(camPitch + (float) (rawDy * mult), -90.0f, 90.0f);
    }

    /**
     * Called once per client tick to advance Free-Fly position smoothly from raw keyboard state
     * without affecting {@code LocalPlayer} movement inputs.
     */
    public static void onPostTick(IPlayerContext ctx) {
        if (mode == Mode.OFF || ctx == null || ctx.player() == null) {
            return;
        }
        Object w = ctx.world();
        if (!initialized || w != lastWorld) {
            anchorToEntity(ctx.player());
            lastWorld = w;
            return;
        }
        Minecraft mc = ctx.minecraft();
        grabCameraMouse(mc);
        if (mode != Mode.FLY) {
            return;
        }
        prevCamX = camX;
        prevCamY = camY;
        prevCamZ = camZ;

        double fwd = 0.0;
        double strafe = 0.0;
        double vert = 0.0;
        boolean boost = false;
        if (acceptsCameraInput(mc)) {
            if (isKeyMappingDown(mc.options.keyUp)) fwd += 1.0;
            if (isKeyMappingDown(mc.options.keyDown)) fwd -= 1.0;
            if (isKeyMappingDown(mc.options.keyLeft)) strafe += 1.0;
            if (isKeyMappingDown(mc.options.keyRight)) strafe -= 1.0;
            if (isKeyMappingDown(mc.options.keyJump)) vert += 1.0;
            if (isKeyMappingDown(mc.options.keyShift)) vert -= 1.0;
            boost = isKeyMappingDown(mc.options.keySprint);
        }
        double[] next = stepFlyPhysics(camX, camY, camZ, velX, velY, velZ, camYaw, camPitch, fwd, strafe, vert, flySpeed * (boost ? 1.85 : 1.0));
        camX = next[0];
        camY = next[1];
        camZ = next[2];
        velX = next[3];
        velY = next[4];
        velZ = next[5];
    }

    /**
     * Pure 3D free-fly kinematic step for deterministic unit testing and smooth inertia-damped flight.
     */
    public static double[] stepFlyPhysics(
            double x, double y, double z,
            double vx, double vy, double vz,
            float yawDeg, float pitchDeg,
            double forwardInput, double strafeInput, double verticalInput,
            double speed
    ) {
        double yawRad = Math.toRadians(yawDeg);
        double pitchRad = Math.toRadians(pitchDeg);
        double fx = -Math.sin(yawRad) * Math.cos(pitchRad);
        double fy = -Math.sin(pitchRad);
        double fz = Math.cos(yawRad) * Math.cos(pitchRad);

        // Left strafe vector perpendicular to horizontal look direction
        double lx = Math.cos(yawRad);
        double lz = Math.sin(yawRad);

        double ax = fx * forwardInput + lx * strafeInput;
        double ay = fy * forwardInput + verticalInput;
        double az = fz * forwardInput + lz * strafeInput;
        double len = Math.sqrt(ax * ax + ay * ay + az * az);
        double targetVx = len > 1e-4 ? (ax / len) * speed : 0.0;
        double targetVy = len > 1e-4 ? (ay / len) * speed : 0.0;
        double targetVz = len > 1e-4 ? (az / len) * speed : 0.0;

        double blend = 0.42;
        double nvx = vx + (targetVx - vx) * blend;
        double nvy = vy + (targetVy - vy) * blend;
        double nvz = vz + (targetVz - vz) * blend;
        if (len <= 1e-4 && Math.abs(nvx) < 1e-4 && Math.abs(nvy) < 1e-4 && Math.abs(nvz) < 1e-4) {
            nvx = nvy = nvz = 0.0;
        }
        return new double[]{x + nvx, y + nvy, z + nvz, nvx, nvy, nvz};
    }

    /**
     * Computes render camera state {@code [x, y, z, yaw, pitch, detachedFlag]} for {@code MixinCamera},
     * or {@code null} when freecam is {@link Mode#OFF}.
     */
    public static double[] computeCameraState(Entity entity, float partialTick) {
        Mode cur = mode;
        if (cur == Mode.OFF || entity == null) {
            return null;
        }
        if (!initialized) {
            anchorToEntity(entity);
        }
        if (cur == Mode.FLY) {
            double rx = Mth.lerp(partialTick, prevCamX, camX);
            double ry = Mth.lerp(partialTick, prevCamY, camY);
            double rz = Mth.lerp(partialTick, prevCamZ, camZ);
            return new double[]{rx, ry, rz, camYaw, camPitch, 1.0};
        }
        // Mode.LOOK: client-only 360-deg look attached to entity eye (1st-person) or smooth 3rd-person orbit (F5)
        Vec3 eye = entity.getEyePosition(partialTick);
        Minecraft mc = Minecraft.getInstance();
        boolean thirdPerson = mc != null && mc.options != null && mc.options.getCameraType() != CameraType.FIRST_PERSON;
        if (!thirdPerson) {
            return new double[]{eye.x, eye.y, eye.z, camYaw, camPitch, 0.0};
        }
        double yawRad = Math.toRadians(camYaw);
        double pitchRad = Math.toRadians(camPitch);
        double lookX = -Math.sin(yawRad) * Math.cos(pitchRad);
        double lookY = -Math.sin(pitchRad);
        double lookZ = Math.cos(yawRad) * Math.cos(pitchRad);
        double orbitDist = 4.0;
        Vec3 desired = new Vec3(eye.x - lookX * orbitDist, eye.y - lookY * orbitDist, eye.z - lookZ * orbitDist);
        Level level = entity.level();
        if (level != null) {
            BlockHitResult hit = level.clip(new ClipContext(eye, desired, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, entity));
            if (hit != null && hit.getType() == HitResult.Type.BLOCK) {
                double d = Math.max(0.5, eye.distanceTo(hit.getLocation()) - 0.25);
                desired = new Vec3(eye.x - lookX * d, eye.y - lookY * d, eye.z - lookZ * d);
            }
        }
        return new double[]{desired.x, desired.y, desired.z, camYaw, camPitch, 1.0};
    }

    private static void grabCameraMouse(Minecraft mc) {
        if (acceptsCameraInput(mc) && !mc.mouseHandler.isMouseGrabbed()) {
            mc.mouseHandler.grabMouse();
        }
    }

    public static void onMouseButton(int button, boolean pressed) {
        if (button >= 0 && button < Integer.SIZE) {
            int mask = 1 << button;
            pressedMouseButtons = pressed ? pressedMouseButtons | mask : pressedMouseButtons & ~mask;
        }
    }

    private static boolean isKeyMappingDown(KeyMapping mapping) {
        InputConstants.Key key = InputConstants.getKey(mapping.saveString());
        if (key.getName().startsWith("key.mouse.")) {
            return (pressedMouseButtons & (1 << key.getValue())) != 0;
        }
        return key.getName().startsWith("key.keyboard.") && key.getValue() != InputConstants.UNKNOWN.getValue()
                && InputConstants.isKeyDown(key.getValue());
    }
}
