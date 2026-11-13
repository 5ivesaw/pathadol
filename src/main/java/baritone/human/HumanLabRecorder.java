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
import baritone.api.utils.input.Input;
import baritone.pathing.movement.MovementHelper;
import baritone.utils.BlockStateInterface;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.AirBlock;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.WallBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * High-fidelity 1:1 per-tick telemetry recorder for human &amp; bot trajectories.
 *
 * <p>Serializes every tick manually via {@link JsonObject}/{@link JsonArray} (zero Gson POJO
 * reflection), captures sub-tick LMB/RMB click pulses &amp; raw mouse deltas from
 * {@link HumanTelemetryHooks.TickSnapshot}, skips paused/idle wait ticks, and emits local
 * floor/void/ray/voxel/entity context suitable for offline imitation learning and failure audit.
 */
public final class HumanLabRecorder {

    public static final int VOXEL_AIR = 0;
    public static final int VOXEL_SOLID = 1;
    public static final int VOXEL_ICE = 2;
    public static final int VOXEL_HAZARD = 3;
    public static final int VOXEL_WATER = 4;
    public static final int VOXEL_SPECIAL_SOLID = 5;

    private static final DateTimeFormatter TS_FMT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final int NUM_RAYS = 16;
    private static final double MAX_RAY_DIST = 6.0;
    private static final double[] VOID_SCAN_RADII = {0.0, 0.35, 0.70, 1.10, 1.60, 2.20, 3.00, 3.80};

    private final Path labDir;
    private boolean recording = false;
    private String activeTag = "session";
    private Path activeFile;
    private BufferedWriter writer;

    private long sessionStartMs = 0L;
    private long recordedTicks = 0L;
    private long botTicks = 0L;
    private long humanTicks = 0L;

    private float prevYaw;
    private float prevPitch;
    private float prevVelYaw;
    private float prevVelPitch;
    private float prevAccYaw;
    private float prevAccPitch;
    private int prevKeysMask = 0;
    private boolean prevLeftHeld = false;
    private boolean prevRightHeld = false;
    private int prevHurtTime = 0;

    private final List<Double> angularSpeeds = new ArrayList<>();
    private final List<Double> angularJerks = new ArrayList<>();
    private int snapCountOver18Deg = 0;
    private int nonZeroMouseSteps = 0;
    private int gcdQuantizedMatches = 0;
    private int keyTransitions = 0;
    private int leftClickTicks = 0;
    private int leftClickPulses = 0;
    private int rightClickTicks = 0;
    private int rightClickPulses = 0;
    private int attackEntityPulses = 0;
    private int placeSuccessPulses = 0;
    private int breakSuccessPulses = 0;

    public HumanLabRecorder(Path baseDir) {
        this.labDir = baseDir.resolve("lab");
    }

    public synchronized Path start(String tag) throws IOException {
        if (recording) {
            stop();
        }
        Files.createDirectories(labDir);
        this.activeTag = (tag == null || tag.isBlank()) ? "session" : tag.replaceAll("[^a-zA-Z0-9_-]", "_");
        String fileName = TS_FMT.format(LocalDateTime.now()) + "-" + this.activeTag + ".jsonl";
        this.activeFile = labDir.resolve(fileName);
        this.writer = Files.newBufferedWriter(activeFile, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);

        resetStats();
        this.sessionStartMs = System.currentTimeMillis();
        this.recording = true;
        return this.activeFile;
    }

    public synchronized Path stop() {
        this.recording = false;
        if (this.writer != null) {
            try {
                this.writer.flush();
                this.writer.close();
            } catch (IOException ignored) {
            }
            this.writer = null;
        }
        return this.activeFile;
    }

    public synchronized void resetStats() {
        recordedTicks = 0L;
        botTicks = 0L;
        humanTicks = 0L;
        angularSpeeds.clear();
        angularJerks.clear();
        snapCountOver18Deg = 0;
        nonZeroMouseSteps = 0;
        gcdQuantizedMatches = 0;
        keyTransitions = 0;
        leftClickTicks = 0;
        leftClickPulses = 0;
        rightClickTicks = 0;
        rightClickPulses = 0;
        attackEntityPulses = 0;
        placeSuccessPulses = 0;
        breakSuccessPulses = 0;
        prevKeysMask = 0;
        prevLeftHeld = false;
        prevRightHeld = false;
        prevHurtTime = 0;
    }

    public synchronized void recordTick(
            IPlayerContext ctx,
            Map<Input, Boolean> resolvedInputs,
            boolean rightClickFired,
            boolean botInControl
    ) {
        recordTick(
                ctx,
                resolvedInputs,
                HumanTelemetryHooks.TickSnapshot.EMPTY,
                rightClickFired,
                false,
                botInControl,
                botInControl ? "BOT_RUN" : "FREEPLAY",
                -1,
                null,
                null
        );
    }

    public synchronized void recordTick(
            IPlayerContext ctx,
            Map<Input, Boolean> resolvedInputs,
            HumanTelemetryHooks.TickSnapshot hookSnap,
            boolean botRightClickFired,
            boolean botBrokeBlock,
            boolean botInControl,
            String phaseTag,
            int stageId,
            String stageName,
            String category
    ) {
        if (!recording || ctx == null || ctx.player() == null || ctx.world() == null) {
            return;
        }
        if (ctx.minecraft() != null && ctx.minecraft().isPaused()) {
            return;
        }

        HumanTelemetryHooks.TickSnapshot snap = hookSnap == null ? HumanTelemetryHooks.TickSnapshot.EMPTY : hookSnap;
        var player = ctx.player();
        float yaw = player.getYRot();
        float pitch = player.getXRot();
        Vec3 pos = player.position();
        Vec3 vel = player.getDeltaMovement();
        int hotbar = player.getInventory().getSelectedSlot();
        double sens = ctx.minecraft() != null ? ctx.minecraft().options.sensitivity().get() : 0.5;

        int keysMask = encodeKeysMask(resolvedInputs);
        boolean fwd = Boolean.TRUE.equals(resolvedInputs.get(Input.MOVE_FORWARD));
        boolean back = Boolean.TRUE.equals(resolvedInputs.get(Input.MOVE_BACK));
        boolean left = Boolean.TRUE.equals(resolvedInputs.get(Input.MOVE_LEFT));
        boolean right = Boolean.TRUE.equals(resolvedInputs.get(Input.MOVE_RIGHT));
        boolean jump = Boolean.TRUE.equals(resolvedInputs.get(Input.JUMP));
        boolean sneak = Boolean.TRUE.equals(resolvedInputs.get(Input.SNEAK));
        boolean sprint = Boolean.TRUE.equals(resolvedInputs.get(Input.SPRINT)) || player.isSprinting();
        boolean leftDown = Boolean.TRUE.equals(resolvedInputs.get(Input.CLICK_LEFT));
        boolean rightDown = Boolean.TRUE.equals(resolvedInputs.get(Input.CLICK_RIGHT));

        float dYaw = 0f;
        float dPitch = 0f;
        float jerkMag = 0f;

        if (recordedTicks > 0) {
            dYaw = Rotation.normalizeYaw(yaw - prevYaw);
            dPitch = pitch - prevPitch;
            double speed = Math.hypot(dYaw, dPitch);
            angularSpeeds.add(speed);
            if (speed > 18.0) {
                snapCountOver18Deg++;
            }

            float accYaw = dYaw - prevVelYaw;
            float accPitch = dPitch - prevVelPitch;
            if (recordedTicks > 1) {
                float jYaw = accYaw - prevAccYaw;
                float jPitch = accPitch - prevAccPitch;
                jerkMag = (float) Math.hypot(jYaw, jPitch);
                angularJerks.add((double) jerkMag);
            }
            prevAccYaw = accYaw;
            prevAccPitch = accPitch;
            prevVelYaw = dYaw;
            prevVelPitch = dPitch;

            float step = MouseModel.mouseStepAngle(sens);
            if (Math.abs(dYaw) > 1e-4f && step > 0f) {
                nonZeroMouseSteps++;
                float rem = Math.abs(dYaw / step - Math.round(dYaw / step));
                if (rem < 0.02f) {
                    gcdQuantizedMatches++;
                }
            }
            keyTransitions += Integer.bitCount(keysMask ^ prevKeysMask);
        }

        prevYaw = yaw;
        prevPitch = pitch;
        prevKeysMask = keysMask;
        recordedTicks++;
        if (botInControl) {
            botTicks++;
        } else {
            humanTicks++;
        }

        int lmbTickPulses = Math.max(snap.lmbPresses(), Math.max(snap.startAttackCalls(), snap.attackEntityCalls()));
        if (lmbTickPulses == 0 && (leftDown && !prevLeftHeld)) {
            lmbTickPulses = 1;
        }
        int rmbTickPulses = Math.max(snap.rmbPresses(),
                Math.max(snap.startUseItemCalls(), Math.max(snap.useItemOnCalls(), snap.useItemCalls())));
        if (rmbTickPulses == 0 && (botRightClickFired || (rightDown && !prevRightHeld))) {
            rmbTickPulses = 1;
        }

        if (leftDown || lmbTickPulses > 0) {
            leftClickTicks++;
        }
        leftClickPulses += lmbTickPulses;
        if (rightDown || rmbTickPulses > 0 || botRightClickFired) {
            rightClickTicks++;
        }
        rightClickPulses += rmbTickPulses;
        attackEntityPulses += snap.attackEntityCalls();
        placeSuccessPulses += snap.useItemOnConsumed() > 0 ? snap.useItemOnConsumed() : (botRightClickFired ? 1 : 0);
        breakSuccessPulses += snap.blocksDestroyed() > 0 ? snap.blocksDestroyed() : (botBrokeBlock ? 1 : 0);

        prevLeftHeld = leftDown;
        prevRightHeld = rightDown;

        int hurtTimeNow = player.hurtTime;
        boolean damageTaken = hurtTimeNow > prevHurtTime;
        prevHurtTime = hurtTimeNow;

        if (writer == null) {
            return;
        }

        BlockStateInterface bsi = new BlockStateInterface(ctx);
        double hSpd = Math.hypot(vel.x, vel.z);
        int floorX = (int) Math.floor(pos.x);
        int floorY = (int) Math.floor(pos.y);
        int floorZ = (int) Math.floor(pos.z);

        BlockPos belowPos = BlockPos.containing(pos.x, pos.y - 0.15, pos.z);
        BlockState belowState = bsi.get0(belowPos.getX(), belowPos.getY(), belowPos.getZ());
        boolean onIce = isIceBlock(belowState.getBlock());
        double voidEdgeDist = computeVoidEdgeDist(bsi, pos.x, floorY, pos.z);

        JsonObject root = new JsonObject();
        root.addProperty("t", recordedTicks);
        root.addProperty("ms", Math.max(0L, System.currentTimeMillis() - sessionStartMs));
        root.addProperty("tag", activeTag);
        root.addProperty("phase", phaseTag == null ? (botInControl ? "BOT_RUN" : "FREEPLAY") : phaseTag);
        root.addProperty("stageId", stageId);
        if (stageName != null) {
            root.addProperty("stageName", stageName);
        }
        if (category != null) {
            root.addProperty("category", category);
        }
        root.addProperty("bot", botInControl);
        root.addProperty("slot", hotbar);

        root.add("pos", vec3Array(pos.x, pos.y, pos.z));
        root.add("vel", vec3Array(vel.x, vel.y, vel.z));
        root.addProperty("hSpd", round4(hSpd));

        JsonArray rotArr = new JsonArray();
        rotArr.add(round4(yaw));
        rotArr.add(round4(pitch));
        root.add("rot", rotArr);
        root.addProperty("headYaw", round4(player.getYHeadRot()));
        root.addProperty("dYaw", round4(dYaw));
        root.addProperty("dPitch", round4(dPitch));
        root.addProperty("jerk", round4(jerkMag));

        JsonArray rawArr = new JsonArray();
        rawArr.add(round4(snap.rawMouseDx()));
        rawArr.add(round4(snap.rawMouseDy()));
        rawArr.add(snap.rawMouseSamples());
        root.add("rawMouse", rawArr);

        JsonObject keysObj = new JsonObject();
        keysObj.addProperty("w", fwd);
        keysObj.addProperty("a", left);
        keysObj.addProperty("s", back);
        keysObj.addProperty("d", right);
        keysObj.addProperty("j", jump);
        keysObj.addProperty("sn", sneak);
        keysObj.addProperty("sp", sprint);
        keysObj.addProperty("lc", leftDown);
        keysObj.addProperty("rc", rightDown);
        keysObj.addProperty("mask", keysMask);
        root.add("keys", keysObj);

        JsonObject actionsObj = new JsonObject();
        actionsObj.addProperty("lmbPulses", lmbTickPulses);
        actionsObj.addProperty("rmbPulses", rmbTickPulses);
        actionsObj.addProperty("startAttack", snap.startAttackCalls());
        actionsObj.addProperty("startAttackHits", snap.startAttackHits());
        actionsObj.addProperty("startUse", snap.startUseItemCalls());
        actionsObj.addProperty("continueAtk", snap.continueAttackActiveTicks());
        actionsObj.addProperty("atkEntity", snap.attackEntityCalls());
        actionsObj.addProperty("useOn", snap.useItemOnCalls());
        actionsObj.addProperty("useOnOk", snap.useItemOnConsumed());
        actionsObj.addProperty("useItem", snap.useItemCalls());
        actionsObj.addProperty("useItemOk", snap.useItemConsumed());
        actionsObj.addProperty("breakStart", snap.startDestroyCalls());
        actionsObj.addProperty("breakCont", snap.continueDestroyCalls());
        actionsObj.addProperty("broke", snap.blocksDestroyed());
        actionsObj.addProperty("botRc", botRightClickFired);
        actionsObj.addProperty("botBroke", botBrokeBlock);
        root.add("actions", actionsObj);

        JsonObject physObj = new JsonObject();
        physObj.addProperty("ground", player.onGround());
        physObj.addProperty("hColl", player.horizontalCollision);
        physObj.addProperty("crouch", player.isShiftKeyDown());
        physObj.addProperty("sprint", player.isSprinting());
        physObj.addProperty("water", player.isInWater());
        physObj.addProperty("climb", player.onClimbable());
        physObj.addProperty("hurtTime", hurtTimeNow);
        physObj.addProperty("fallDist", round4(player.fallDistance));
        root.add("phys", physObj);

        root.add("crosshair", buildCrosshairJson(ctx, pos));
        root.add("underfoot", buildUnderfootJson(belowState, voidEdgeDist));
        root.add("rays", buildRaySensorsJson(bsi, pos, floorY, yaw));
        root.add("voxels7x4x7", buildLocalVoxelRle(bsi, floorX, floorY, floorZ));
        root.add("entities", buildNearbyEntitiesJson(ctx, pos, yaw));

        JsonArray eventsArr = new JsonArray();
        boolean anyMoveKey = fwd || back || left || right;
        if (player.horizontalCollision && hSpd < 0.06 && anyMoveKey) {
            eventsArr.add("BONK_WALL");
        }
        if (vel.y < -0.35 && voidEdgeDist < 0.8 && !player.onGround() && !player.isInWater()) {
            eventsArr.add("VOID_FALL");
        }
        if (damageTaken) {
            eventsArr.add("DAMAGE_TAKEN");
        }
        if (snap.attackEntityCalls() > 0) {
            eventsArr.add("ATTACK");
        }
        if (snap.useItemOnConsumed() > 0 || botRightClickFired) {
            eventsArr.add("PLACE");
        }
        if (snap.blocksDestroyed() > 0 || botBrokeBlock) {
            eventsArr.add("BREAK");
        }
        if (onIce && !player.onGround() && sprint && hSpd > 0.28) {
            eventsArr.add("ICE_SPRINT_JUMP");
        }
        if (voidEdgeDist <= 0.65 && (back || sneak || Math.abs(pitch) > 55.0f)) {
            eventsArr.add("BRIDGE_EDGE");
        }
        root.add("events", eventsArr);

        try {
            writer.write(root.toString());
            writer.write('\n');
            if ((recordedTicks & 19) == 0) {
                writer.flush();
            }
        } catch (IOException ignored) {
        }
    }

    private JsonObject buildCrosshairJson(IPlayerContext ctx, Vec3 playerPos) {
        JsonObject obj = new JsonObject();
        HitResult hit = ctx.minecraft() != null ? ctx.minecraft().hitResult : ctx.objectMouseOver();
        if (hit instanceof BlockHitResult bhr && hit.getType() == HitResult.Type.BLOCK) {
            BlockPos bp = bhr.getBlockPos();
            BlockState bs = ctx.world().getBlockState(bp);
            obj.addProperty("type", "BLOCK");
            JsonArray pArr = new JsonArray();
            pArr.add(bp.getX());
            pArr.add(bp.getY());
            pArr.add(bp.getZ());
            obj.add("pos", pArr);
            obj.addProperty("face", bhr.getDirection().getName());
            obj.addProperty("block", BuiltInRegistries.BLOCK.getKey(bs.getBlock()).getPath());
            return obj;
        }
        Entity ent = null;
        if (hit instanceof EntityHitResult ehr && hit.getType() == HitResult.Type.ENTITY) {
            ent = ehr.getEntity();
        } else if (ctx.minecraft() != null && ctx.minecraft().crosshairPickEntity != null) {
            ent = ctx.minecraft().crosshairPickEntity;
        }
        if (ent != null) {
            obj.addProperty("type", "ENTITY");
            obj.addProperty("id", ent.getId());
            obj.addProperty("entityType", BuiltInRegistries.ENTITY_TYPE.getKey(ent.getType()).getPath());
            obj.addProperty("dist", round4(ent.position().distanceTo(playerPos)));
            return obj;
        }
        obj.addProperty("type", "MISS");
        return obj;
    }

    private JsonObject buildUnderfootJson(BlockState belowState, double voidEdgeDist) {
        JsonObject obj = new JsonObject();
        Block b = belowState.getBlock();
        obj.addProperty("block", BuiltInRegistries.BLOCK.getKey(b).getPath());
        obj.addProperty("friction", round4(b.getFriction()));
        obj.addProperty("ice", isIceBlock(b));
        obj.addProperty("packedIce", b == Blocks.PACKED_ICE);
        obj.addProperty("blueIce", b == Blocks.BLUE_ICE);
        obj.addProperty("solid", !(b instanceof AirBlock) && belowState.getFluidState().isEmpty());
        obj.addProperty("liquid", !belowState.getFluidState().isEmpty());
        obj.addProperty("voidEdgeDist", round4(voidEdgeDist));
        return obj;
    }

    private JsonObject buildRaySensorsJson(BlockStateInterface bsi, Vec3 pos, int floorY, float yawDeg) {
        JsonObject obj = new JsonObject();
        JsonArray eyeArr = new JsonArray();
        JsonArray floorArr = new JsonArray();
        int eyeBlockY = (int) Math.floor(pos.y + 1.55);
        int chestBlockY = (int) Math.floor(pos.y + 0.65);

        for (int i = 0; i < NUM_RAYS; i++) {
            double angRad = Math.toRadians(yawDeg + i * (360.0 / NUM_RAYS));
            double rx = -Math.sin(angRad);
            double rz = Math.cos(angRad);
            double eyeClear = MAX_RAY_DIST;
            double floorSafe = MAX_RAY_DIST;

            for (double d = 0.5; d <= MAX_RAY_DIST + 1e-6; d += 0.5) {
                int bx = (int) Math.floor(pos.x + rx * d);
                int bz = (int) Math.floor(pos.z + rz * d);
                if (eyeClear >= MAX_RAY_DIST) {
                    boolean eyeOk = MovementHelper.canWalkThrough(bsi, bx, eyeBlockY, bz)
                            && MovementHelper.canWalkThrough(bsi, bx, chestBlockY, bz);
                    if (!eyeOk) {
                        eyeClear = d - 0.5;
                    }
                }
                if (floorSafe >= MAX_RAY_DIST) {
                    if (!hasFloorWithin3Blocks(bsi, bx, floorY, bz)) {
                        floorSafe = d - 0.5;
                    }
                }
                if (eyeClear < MAX_RAY_DIST && floorSafe < MAX_RAY_DIST) {
                    break;
                }
            }
            eyeArr.add(round2(eyeClear));
            floorArr.add(round2(floorSafe));
        }
        obj.add("eye", eyeArr);
        obj.add("floor", floorArr);
        return obj;
    }

    private JsonArray buildLocalVoxelRle(BlockStateInterface bsi, int cx, int cy, int cz) {
        int[] codes = new int[7 * 4 * 7];
        int idx = 0;
        for (int dy = -1; dy <= 2; dy++) {
            int y = cy + dy;
            for (int dz = -3; dz <= 3; dz++) {
                int z = cz + dz;
                for (int dx = -3; dx <= 3; dx++) {
                    codes[idx++] = classifyVoxelCode(bsi.get0(cx + dx, y, z));
                }
            }
        }
        return encodeVoxelGridRle(codes);
    }

    /**
     * Run-length encodes a flat array of voxel codes into {@code [[count, code], ...]}.
     */
    public static JsonArray encodeVoxelGridRle(int[] codes) {
        JsonArray runs = new JsonArray();
        if (codes == null || codes.length == 0) {
            return runs;
        }
        int cur = codes[0];
        int runLen = 1;
        for (int i = 1; i < codes.length; i++) {
            if (codes[i] == cur) {
                runLen++;
            } else {
                JsonArray pair = new JsonArray();
                pair.add(runLen);
                pair.add(cur);
                runs.add(pair);
                cur = codes[i];
                runLen = 1;
            }
        }
        JsonArray last = new JsonArray();
        last.add(runLen);
        last.add(cur);
        runs.add(last);
        return runs;
    }

    private static int classifyVoxelCode(BlockState state) {
        if (state == null || state.getBlock() instanceof AirBlock) {
            return VOXEL_AIR;
        }
        Block b = state.getBlock();
        if (isIceBlock(b)) {
            return VOXEL_ICE;
        }
        if (b == Blocks.LAVA || b instanceof BaseFireBlock || b == Blocks.MAGMA_BLOCK
                || b == Blocks.CACTUS || b == Blocks.SWEET_BERRY_BUSH
                || b == Blocks.COBWEB || b == Blocks.POWDER_SNOW) {
            return VOXEL_HAZARD;
        }
        if (!state.getFluidState().isEmpty()) {
            return VOXEL_WATER;
        }
        if (b instanceof SlabBlock || b instanceof StairBlock || b instanceof FenceBlock
                || b instanceof WallBlock || b instanceof DoorBlock) {
            return VOXEL_SPECIAL_SOLID;
        }
        return state.canBeReplaced() ? VOXEL_AIR : VOXEL_SOLID;
    }

    private JsonArray buildNearbyEntitiesJson(IPlayerContext ctx, Vec3 playerPos, float playerYaw) {
        JsonArray arr = new JsonArray();
        var self = ctx.player();
        record EntEntry(Entity entity, double dist) {}
        List<EntEntry> near = new ArrayList<>();
        try {
            for (Entity e : ctx.entities()) {
                if (e == null || e == self || !e.isAlive() || !(e instanceof LivingEntity)) {
                    continue;
                }
                double d = e.position().distanceTo(playerPos);
                if (d <= 20.0) {
                    near.add(new EntEntry(e, d));
                }
            }
        } catch (Exception ignored) {
        }
        near.sort(Comparator.comparingDouble(EntEntry::dist));
        int limit = Math.min(8, near.size());
        for (int i = 0; i < limit; i++) {
            Entity e = near.get(i).entity();
            double d = near.get(i).dist();
            Vec3 ep = e.position();
            Vec3 ev = e.getDeltaMovement();
            double dx = ep.x - playerPos.x;
            double dz = ep.z - playerPos.z;
            double hDist = Math.hypot(dx, dz);
            float targetYaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
            float relYaw = Rotation.normalizeYaw(targetYaw - playerYaw);
            double radialClosing = hDist > 1e-4 ? -((ev.x * dx + ev.z * dz) / hDist) : 0.0;

            JsonObject eo = new JsonObject();
            eo.addProperty("id", e.getId());
            eo.addProperty("type", BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).getPath());
            eo.addProperty("dist", round4(d));
            eo.addProperty("relYaw", round2(relYaw));
            eo.addProperty("dy", round2(ep.y - playerPos.y));
            eo.add("vel", vec3Array(ev.x, ev.y, ev.z));
            eo.addProperty("closingSpd", round4(radialClosing));
            if (e instanceof LivingEntity le) {
                eo.addProperty("hurtTime", le.hurtTime);
            }
            arr.add(eo);
        }
        return arr;
    }

    private static double computeVoidEdgeDist(BlockStateInterface bsi, double px, int floorY, double pz) {
        int cx = (int) Math.floor(px);
        int cz = (int) Math.floor(pz);
        if (!hasFloorWithin3Blocks(bsi, cx, floorY, cz)) {
            return 0.0;
        }
        for (int rIdx = 1; rIdx < VOID_SCAN_RADII.length; rIdx++) {
            double r = VOID_SCAN_RADII[rIdx];
            for (int dir = 0; dir < 8; dir++) {
                double a = dir * (Math.PI / 4.0);
                int bx = (int) Math.floor(px + Math.cos(a) * r);
                int bz = (int) Math.floor(pz + Math.sin(a) * r);
                if (!hasFloorWithin3Blocks(bsi, bx, floorY, bz)) {
                    return r;
                }
            }
        }
        return 4.0;
    }

    private static boolean hasFloorWithin3Blocks(BlockStateInterface bsi, int bx, int floorY, int bz) {
        for (int dy = 1; dy <= 3; dy++) {
            if (MovementHelper.canWalkOn(bsi, bx, floorY - dy, bz)) {
                return true;
            }
        }
        return false;
    }

    public static boolean isIceBlock(Block block) {
        return block == Blocks.ICE
                || block == Blocks.PACKED_ICE
                || block == Blocks.BLUE_ICE
                || block == Blocks.FROSTED_ICE;
    }

    private static JsonArray vec3Array(double x, double y, double z) {
        JsonArray arr = new JsonArray();
        arr.add(round4(x));
        arr.add(round4(y));
        arr.add(round4(z));
        return arr;
    }

    private static double round4(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    public synchronized String formatSummary() {
        if (recordedTicks == 0) {
            return "No HumanLab ticks recorded yet. Run #lab start [tag] first.";
        }
        double seconds = recordedTicks / 20.0;
        double meanSpeed = angularSpeeds.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
        double maxSpeed = angularSpeeds.stream().mapToDouble(Double::doubleValue).max().orElse(0.0);
        double p95Speed = percentile(angularSpeeds, 0.95);
        double meanJerk = angularJerks.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
        double gcdRate = nonZeroMouseSteps == 0 ? 100.0 : (100.0 * gcdQuantizedMatches / nonZeroMouseSteps);
        double switchesPerSec = seconds > 0 ? keyTransitions / seconds : 0.0;
        double rcPerSec = seconds > 0 ? rightClickPulses / seconds : 0.0;
        double lcPerSec = seconds > 0 ? leftClickPulses / seconds : 0.0;

        return String.format(Locale.ROOT,
                "HumanLab [%s] (%s) — %d ticks (%.1fs | bot=%dt you=%dt)\n"
                        + "  Aim speed: mean=%.2f°/t, p95=%.2f°/t, max=%.2f°/t | snaps(>18°)=%d\n"
                        + "  Aim jerk:  mean=%.2f°/t³ | Mouse GCD match: %.1f%% (%d/%d)\n"
                        + "  Keys/Clk:  %.2f transitions/s | LMB: %d pulses (%dt, %.2f/s, atk=%d) | RMB: %d pulses (%dt, %.2f/s, plc=%d) | brk=%d",
                activeTag,
                recording ? "RECORDING" : "STOPPED",
                recordedTicks,
                seconds,
                botTicks,
                humanTicks,
                meanSpeed,
                p95Speed,
                maxSpeed,
                snapCountOver18Deg,
                meanJerk,
                gcdRate,
                gcdQuantizedMatches,
                nonZeroMouseSteps,
                switchesPerSec,
                leftClickPulses,
                leftClickTicks,
                lcPerSec,
                attackEntityPulses,
                rightClickPulses,
                rightClickTicks,
                rcPerSec,
                placeSuccessPulses,
                breakSuccessPulses
        );
    }

    private static double percentile(List<Double> values, double q) {
        if (values.isEmpty()) {
            return 0.0;
        }
        List<Double> sorted = new ArrayList<>(values);
        sorted.sort(Double::compareTo);
        int idx = Math.min(sorted.size() - 1, Math.max(0, (int) Math.round(q * (sorted.size() - 1))));
        return sorted.get(idx);
    }

    private static int encodeKeysMask(Map<Input, Boolean> map) {
        int mask = 0;
        for (Input input : Input.values()) {
            if (Boolean.TRUE.equals(map.get(input))) {
                mask |= (1 << input.ordinal());
            }
        }
        return mask;
    }

    public synchronized boolean isRecording() {
        return recording;
    }

    public synchronized Path getActiveFile() {
        return activeFile;
    }
}
