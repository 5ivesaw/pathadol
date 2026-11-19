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

package baritone.brain;

import baritone.Baritone;
import baritone.api.utils.IPlayerContext;
import baritone.api.utils.Rotation;
import baritone.api.utils.input.Input;
import baritone.bedwars.BedwarsArenaMemory;
import baritone.bedwars.BedwarsMapCore;
import baritone.bedwars.BedwarsMapCore.BedPOI;
import baritone.bedwars.BedwarsMapCore.BwsRuleset;
import baritone.bedwars.BedwarsMapCore.GenPOI;
import baritone.bedwars.BedwarsMapCore.GenType;
import baritone.bedwars.BedwarsMapCore.ShopNPC;
import baritone.bedwars.BedwarsMapCore.TeamColor;
import baritone.bedwars.BedwarsMatchState;
import baritone.bedwars.BedwarsTacticalHelper;
import baritone.bedwars.BedwarsTacticalHelper.InventorySummary;
import baritone.brain.PureNnSchema.PureNnAction;
import baritone.brain.PureNnSchema.PureNnSample;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
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
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * High-throughput, zero-GC-stall 20 Hz binary {@code .pnn} shard recorder aligned with
 * {@code bedwars_pure_nn_plan.md}. Writes compact fixed-stride egocentric observations and factored
 * actions on a daemon background thread while strictly filtering out lobby, pause, chat, spectator,
 * and post-death frames.
 */
public final class PureNnRecorder {

    private static final DateTimeFormatter TS_FMT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final int QUEUE_CAPACITY = 4096;

    private final Path dataDir;
    private final BlockingQueue<byte[]> queue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);

    private Thread writerThread;
    private OutputStream outStream;
    private Path activeFile;
    private volatile boolean recording = false;

    private int episodeId = 0;
    private int tickIdx = 0;
    private long framesWritten = 0L;
    private long droppedFrames = 0L;

    private float prevYaw = 0.0f;
    private float prevPitch = 0.0f;
    private boolean hasPrevRot = false;
    private boolean prevLmb = false;
    private boolean prevRmb = false;
    private int prevHotbarSlot = -1;
    private boolean prevDeadFrameWritten = false;
    private int pendingGuiAction = 0;

    public PureNnRecorder(Path baseDir) {
        this.dataDir = baseDir.resolve("pure_nn_data");
    }

    public boolean isRecording() {
        return recording;
    }

    public long getFramesWritten() {
        return framesWritten;
    }

    public Path getActiveFile() {
        return activeFile;
    }

    public synchronized void recordGuiSlotClick(int slotIndex) {
        if (slotIndex >= 0 && slotIndex < 54) {
            this.pendingGuiAction = slotIndex + 1;
        }
    }

    public synchronized void recordGuiClose() {
        this.pendingGuiAction = 55;
    }

    public synchronized void start(String tag) throws IOException {
        if (recording) {
            stop();
        }
        Files.createDirectories(dataDir);
        String clean = (tag == null || tag.isBlank()) ? "bw" : tag.replaceAll("[^a-zA-Z0-9_-]", "_");
        this.activeFile = dataDir.resolve("pnn-" + TS_FMT.format(LocalDateTime.now()) + "-" + clean + ".pnn");
        this.outStream = new BufferedOutputStream(
                Files.newOutputStream(activeFile, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE),
                65536
        );
        this.outStream.write(PureNnSchema.encodeFileHeader());
        this.outStream.flush();

        this.episodeId++;
        this.tickIdx = 0;
        this.framesWritten = 0L;
        this.droppedFrames = 0L;
        this.hasPrevRot = false;
        this.prevLmb = false;
        this.prevRmb = false;
        this.prevHotbarSlot = -1;
        this.prevDeadFrameWritten = false;
        this.pendingGuiAction = 0;
        this.recording = true;

        this.writerThread = new Thread(this::writerLoop, "Pathadol-PureNnWriter");
        this.writerThread.setDaemon(true);
        this.writerThread.start();
    }

    public synchronized void stop() {
        if (!recording) {
            return;
        }
        recording = false;
        Thread wt = writerThread;
        writerThread = null;
        if (wt != null) {
            wt.interrupt();
            try {
                wt.join(1500L);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
        closeQuietly();
    }

    /**
     * Captures one 20 Hz egocentric Pure-NN training frame if the player is actively in a match.
     */
    public void onTick(Baritone baritone, IPlayerContext ctx, LocalPlayer p, Level world,
                       BedwarsArenaMemory memory, boolean humanSource, float stepReward) {
        if (!recording || p == null || world == null) {
            return;
        }
        Minecraft mc = ctx.minecraft();
        if (mc != null && (mc.isPaused() || (mc.gui != null && mc.gui.screen() instanceof ChatScreen))) {
            hasPrevRot = false;
            return;
        }
        BedwarsMapCore core = memory != null ? memory.getActiveCore() : null;
        ItemStack held = p.getMainHandItem();
        String heldPath = held.isEmpty() ? "" : BuiltInRegistries.ITEM.getKey(held.getItem()).getPath();
        boolean lobbyItem = heldPath.equals("barrier") || heldPath.equals("paper")
                || heldPath.equals("compass") || heldPath.equals("clock") || heldPath.equals("nether_star");
        boolean inLobby = p.isSpectator()
                || (memory != null && memory.isInWaitingLobby())
                || lobbyItem
                || (core != null && core.bounds() != null && p.getY() > (core.bounds().minY() + core.bounds().sizeY() + 12.0));
        if (inLobby) {
            hasPrevRot = false;
            prevDeadFrameWritten = false;
            return;
        }

        boolean deadNow = !p.isAlive() || p.isDeadOrDying();
        if (deadNow && prevDeadFrameWritten) {
            hasPrevRot = false;
            return;
        }
        if (!deadNow) {
            prevDeadFrameWritten = false;
        } else {
            prevDeadFrameWritten = true;
        }

        tickIdx++;
        float yaw = p.getYRot();
        float pitch = p.getXRot();
        float dYaw = hasPrevRot ? Rotation.normalizeYaw(yaw - prevYaw) : 0.0f;
        float dPitch = hasPrevRot ? (pitch - prevPitch) : 0.0f;
        prevYaw = yaw;
        prevPitch = pitch;
        hasPrevRot = true;

        byte[] voxels = extractEgocentricVoxels(p, world, core, yaw);
        float[] floats = extractDenseFeatures(ctx, p, world, memory, core, yaw, pitch);
        PureNnAction action = extractFactoredAction(baritone, ctx, p, dYaw, dPitch, floats[80] < 0.5f);

        int flags = deadNow ? 1 : 0;
        int sourceId = humanSource ? 1 : 2; // 1=human, 2=pathadol_deterministic
        PureNnSample sample = new PureNnSample(tickIdx, episodeId, flags, sourceId, stepReward, voxels, floats, action);
        byte[] encoded = PureNnSchema.encodeRecord(sample);
        if (!queue.offer(encoded)) {
            droppedFrames++;
        }
    }

    private byte[] extractEgocentricVoxels(LocalPlayer p, Level world, BedwarsMapCore core, float yawDeg) {
        byte[] vox = new byte[PureNnSchema.VOXEL_DIM];
        Vec3 pos = p.position();
        int feetY = p.blockPosition().getY();
        int voidY = core != null ? core.voidFloorY() : world.getMinY();
        double[] dxdz = new double[2];
        BlockPos.MutableBlockPos mpos = new BlockPos.MutableBlockPos();

        for (int iy = 0; iy < PureNnSchema.VOXEL_Y; iy++) {
            int wy = feetY + (iy - 1);
            for (int ifwd = 0; ifwd < PureNnSchema.VOXEL_FWD; ifwd++) {
                double fwdOff = ifwd - 1.0;
                for (int ir = 0; ir < PureNnSchema.VOXEL_RIGHT; ir++) {
                    double rightOff = ir - 2.0;
                    PureNnSchema.egocentricToWorld(fwdOff, rightOff, yawDeg, dxdz);
                    int wx = (int) Math.floor(pos.x + dxdz[0]);
                    int wz = (int) Math.floor(pos.z + dxdz[1]);
                    mpos.set(wx, wy, wz);
                    vox[PureNnSchema.voxelIndex(iy, ifwd, ir)] = classifyVoxel(world, mpos, core, voidY);
                }
            }
        }
        return vox;
    }

    private static byte classifyVoxel(Level world, BlockPos bp, BedwarsMapCore core, int voidY) {
        if (bp.getY() <= voidY) {
            return PureNnSchema.VOX_DANGER_VOID_FIRE;
        }
        BlockState bs = world.getBlockState(bp);
        if (bs.isAir()) {
            return PureNnSchema.VOX_AIR;
        }
        if (bs.getBlock() == Blocks.FIRE || bs.getBlock() == Blocks.LAVA || bs.getBlock() == Blocks.SOUL_FIRE) {
            return PureNnSchema.VOX_DANGER_VOID_FIRE;
        }
        if (bs.getBlock() instanceof BedBlock) {
            boolean own = false;
            if (core != null && core.homeTeam() != TeamColor.UNKNOWN) {
                for (BedPOI bed : core.getBeds()) {
                    if (bed.team() == core.homeTeam() && bed.matchesCell(bp.getX(), bp.getY(), bp.getZ())) {
                        own = true;
                        break;
                    }
                }
            }
            return own ? PureNnSchema.VOX_OWN_BED : PureNnSchema.VOX_ENEMY_BED;
        }
        if (core != null) {
            for (GenPOI g : core.getGens()) {
                if (Math.abs(g.x() - bp.getX()) <= 1 && Math.abs(g.y() - bp.getY()) <= 1 && Math.abs(g.z() - bp.getZ()) <= 1) {
                    return PureNnSchema.VOX_GENERATOR;
                }
            }
            for (ShopNPC npc : core.getShopNpcs()) {
                if (Math.abs(npc.x() - bp.getX()) <= 1 && Math.abs(npc.y() - bp.getY()) <= 1 && Math.abs(npc.z() - bp.getZ()) <= 1) {
                    return PureNnSchema.VOX_SHOP_NPC;
                }
            }
            if (core.isPlayerPlacedBlock(bp.getX(), bp.getY(), bp.getZ())) {
                return PureNnSchema.VOX_PLAYER_PLACED;
            }
        }
        return PureNnSchema.VOX_UNBREAKABLE_MAP;
    }

    private float[] extractDenseFeatures(
            IPlayerContext ctx,
            LocalPlayer p,
            Level world,
            BedwarsArenaMemory memory,
            BedwarsMapCore core,
            float yaw,
            float pitch
    ) {
        float[] f = new float[PureNnSchema.FLOAT_OBS_DIM];
        Vec3 pos = p.position();
        Vec3 vel = p.getDeltaMovement();
        double[] ego = new double[2];

        // --- A. Self Kinematics & Vitals [0..21] ---
        PureNnSchema.worldToEgocentric(vel.x, vel.z, yaw, ego);
        f[0] = clamp((float) ego[0], -2.0f, 2.0f);
        f[1] = clamp((float) vel.y, -2.0f, 2.0f);
        f[2] = clamp((float) ego[1], -2.0f, 2.0f);
        f[3] = clamp(pitch / 90.0f, -1.0f, 1.0f);

        BedPOI homeBed = null;
        List<BedPOI> enemyBeds = new ArrayList<>();
        if (core != null) {
            for (BedPOI b : core.getBeds()) {
                if (b.isHome() || (core.homeTeam() != TeamColor.UNKNOWN && b.team() == core.homeTeam())) {
                    homeBed = b;
                } else if (!b.destroyed()) {
                    enemyBeds.add(b);
                }
            }
        }
        enemyBeds.sort(Comparator.comparingDouble(b -> pos.distanceToSqr(b.x() + 0.5, b.y() + 0.5, b.z() + 0.5)));

        if (homeBed != null) {
            encodeRelBearing(pos, homeBed.x() + 0.5, homeBed.z() + 0.5, yaw, f, 4);
        }
        encodeRelBearing(pos, 0.0, 0.0, yaw, f, 6);
        if (!enemyBeds.isEmpty()) {
            BedPOI nb = enemyBeds.get(0);
            encodeRelBearing(pos, nb.x() + 0.5, nb.z() + 0.5, yaw, f, 8);
        }

        int voidY = core != null ? core.voidFloorY() : world.getMinY();
        f[10] = p.onGround() ? 1.0f : 0.0f;
        f[11] = p.isCrouching() ? 1.0f : 0.0f;
        f[12] = p.isSprinting() ? 1.0f : 0.0f;
        f[13] = clamp(p.hurtTime / 10.0f, 0.0f, 1.0f);
        f[14] = clamp(p.getAttackStrengthScale(0.5f), 0.0f, 1.0f);
        f[15] = clamp((float) (p.fallDistance / 10.0), 0.0f, 2.0f);
        f[16] = clamp((float) ((pos.y - voidY) / 32.0), -1.0f, 2.0f);
        f[17] = clamp(p.getHealth() / 20.0f, 0.0f, 1.5f);
        f[18] = clamp(p.getAbsorptionAmount() / 20.0f, 0.0f, 1.5f);
        f[19] = p.hasEffect(MobEffects.SPEED) ? 1.0f : 0.0f;
        f[20] = p.hasEffect(MobEffects.JUMP_BOOST) ? 1.0f : 0.0f;
        f[21] = p.hasEffect(MobEffects.INVISIBILITY) ? 1.0f : 0.0f;

        // --- B. Bridge & Edge Raycasts + LookHit [22..45] ---
        int feetY = p.blockPosition().getY();
        for (int dirIdx = 0; dirIdx < 8; dirIdx++) {
            float rayYaw = yaw + dirIdx * 45.0f;
            f[22 + dirIdx] = measureVoidRayDist(world, pos, feetY, rayYaw, voidY) / 16.0f;
        }

        Minecraft mc = ctx.minecraft();
        HitResult hr = (mc != null && mc.hitResult != null) ? mc.hitResult : ctx.objectMouseOver();
        if (hr == null || hr.getType() == HitResult.Type.MISS) {
            f[30] = 1.0f;
        } else if (hr instanceof BlockHitResult bhr) {
            f[31] = 1.0f;
            BlockPos bp = bhr.getBlockPos();
            f[33] = clamp((float) (bhr.getLocation().distanceTo(pos) / 8.0), 0.0f, 1.5f);
            byte cat = classifyVoxel(world, bp, core, voidY);
            f[34] = cat / 7.0f;
            boolean placed = cat == PureNnSchema.VOX_PLAYER_PLACED;
            boolean enemyBed = cat == PureNnSchema.VOX_ENEMY_BED;
            boolean ownBed = cat == PureNnSchema.VOX_OWN_BED;
            f[35] = (placed || enemyBed) ? 1.0f : 0.0f;
            f[36] = placed ? 1.0f : 0.0f;
            f[37] = enemyBed ? 1.0f : 0.0f;
            f[38] = ownBed ? 1.0f : 0.0f;
            Direction face = bhr.getDirection();
            PureNnSchema.worldToEgocentric(face.getStepX(), face.getStepZ(), yaw, ego);
            f[39] = (float) ego[0];
            f[40] = face.getStepY();
            f[41] = (float) ego[1];
        } else if (hr instanceof EntityHitResult ehr) {
            f[32] = 1.0f;
            f[33] = clamp((float) (ehr.getLocation().distanceTo(pos) / 8.0), 0.0f, 1.5f);
        }

        f[42] = !world.getBlockState(p.blockPosition().below()).isAir() ? 1.0f : 0.0f;
        PureNnSchema.egocentricToWorld(1.0, 0.0, yaw, ego);
        f[43] = !world.getBlockState(BlockPos.containing(pos.x + ego[0], feetY - 1, pos.z + ego[1])).isAir() ? 1.0f : 0.0f;
        PureNnSchema.egocentricToWorld(2.0, 0.0, yaw, ego);
        f[44] = !world.getBlockState(BlockPos.containing(pos.x + ego[0], feetY - 1, pos.z + ego[1])).isAir() ? 1.0f : 0.0f;
        f[45] = clamp((float) Math.hypot(pos.x - Math.floor(pos.x) - 0.5, pos.z - Math.floor(pos.z) - 0.5), 0.0f, 1.0f);

        // --- C. Inventory, Economy, Hotbar & GUI State [46..95] ---
        boolean modern = memory != null && memory.getActiveRuleset() == BwsRuleset.MODERN_26_3;
        InventorySummary inv = BedwarsTacticalHelper.summarizeInventory(p, modern);
        f[46] = (float) (Math.log1p(inv.iron()) / 4.0);
        f[47] = (float) (Math.log1p(inv.gold()) / 4.0);
        f[48] = (float) (Math.log1p(inv.diamonds()) / 4.0);
        f[49] = (float) (Math.log1p(inv.emeralds()) / 4.0);
        f[50] = clamp(inv.bridgeBlockCount() / 64.0f, 0.0f, 4.0f);
        f[51] = clamp(inv.gapples() / 8.0f, 0.0f, 2.0f);
        f[52] = clamp(inv.fireballs() / 4.0f, 0.0f, 2.0f);
        f[53] = clamp(inv.tntCount() / 4.0f, 0.0f, 2.0f);
        f[54] = clamp(inv.pearls() / 4.0f, 0.0f, 2.0f);
        f[55] = 0.0f;
        f[56] = inv.swordTier() / 4.0f;
        f[57] = clamp((float) (inv.armorPoints() / 16.0), 0.0f, 1.25f);
        f[58] = inv.pickaxeTier() / 4.0f;
        f[59] = inv.axeTier() / 4.0f;
        f[60] = inv.shearsTier() > 0 ? 1.0f : 0.0f;
        f[61] = inv.hasBowAndArrows() ? 1.0f : 0.0f;

        for (int s = 0; s < 9; s++) {
            ItemStack st = p.getInventory().getNonEquipmentItems().get(s);
            f[62 + s] = categorizeItem(st) / 12.0f;
        }
        int selSlot = Math.max(0, Math.min(8, p.getInventory().getSelectedSlot()));
        f[71 + selSlot] = 1.0f;

        AbstractContainerMenu menu = p.containerMenu;
        int guiType = 0;
        if (menu != null && menu != p.inventoryMenu) {
            String title = memory != null ? memory.getOpenMenuTitle(menu.containerId) : "";
            String lower = title != null ? title.toLowerCase(Locale.ROOT) : "";
            if (lower.contains("upgrade") || lower.contains("trap")) {
                guiType = 2;
            } else if (lower.contains("shop") || lower.contains("quick")) {
                guiType = 1;
            } else {
                guiType = 3;
            }
        }
        f[80 + guiType] = 1.0f;
        f[84] = 0.0f;
        // 11-category affordability bitmask
        f[85] = inv.iron() >= 4 ? 1.0f : 0.0f;
        f[86] = inv.gold() >= 4 ? 1.0f : 0.0f;
        f[87] = inv.iron() >= 24 ? 1.0f : 0.0f;
        f[88] = inv.iron() >= 10 ? 1.0f : 0.0f;
        f[89] = inv.gold() >= 7 ? 1.0f : 0.0f;
        f[90] = inv.iron() >= 40 ? 1.0f : 0.0f;
        f[91] = inv.gold() >= 12 ? 1.0f : 0.0f;
        f[92] = inv.emeralds() >= 6 ? 1.0f : 0.0f;
        f[93] = inv.iron() >= 20 ? 1.0f : 0.0f;
        f[94] = inv.gold() >= 3 ? 1.0f : 0.0f;
        f[95] = (inv.diamonds() >= 2 || inv.emeralds() >= 1) ? 1.0f : 0.0f;

        // --- D. Threat & Entity Radar [96..151] ---
        List<Player> nearbyPlayers = new ArrayList<>();
        Entity nearestProj = null;
        double nearestProjDistSq = Double.MAX_VALUE;
        for (Entity e : (Iterable<Entity>) ctx.entitiesStream()::iterator) {
            if (e == null || e == p || !e.isAlive()) continue;
            double dSq = p.distanceToSqr(e);
            if (dSq > 48.0 * 48.0) continue;
            if (e instanceof Player other && !other.isSpectator()) {
                nearbyPlayers.add(other);
            } else {
                String tid = BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).getPath();
                if (("fireball".equals(tid) || "small_fireball".equals(tid) || "arrow".equals(tid) || "tnt".equals(tid))
                        && dSq < nearestProjDistSq) {
                    nearestProjDistSq = dSq;
                    nearestProj = e;
                }
            }
        }
        nearbyPlayers.sort(Comparator.comparingDouble(p::distanceToSqr));
        for (int i = 0; i < Math.min(4, nearbyPlayers.size()); i++) {
            Player other = nearbyPlayers.get(i);
            int base = 96 + i * 12;
            Vec3 op = other.position();
            Vec3 ov = other.getDeltaMovement();
            PureNnSchema.worldToEgocentric(op.x - pos.x, op.z - pos.z, yaw, ego);
            f[base] = 1.0f;
            f[base + 1] = clamp((float) (ego[0] / 32.0), -2.0f, 2.0f);
            f[base + 2] = clamp((float) ((op.y - pos.y) / 16.0), -2.0f, 2.0f);
            f[base + 3] = clamp((float) (ego[1] / 32.0), -2.0f, 2.0f);
            PureNnSchema.worldToEgocentric(ov.x - vel.x, ov.z - vel.z, yaw, ego);
            f[base + 4] = clamp((float) ego[0], -2.0f, 2.0f);
            f[base + 5] = clamp((float) (ov.y - vel.y), -2.0f, 2.0f);
            f[base + 6] = clamp((float) ego[1], -2.0f, 2.0f);
            double dist = Math.sqrt(p.distanceToSqr(other));
            f[base + 7] = clamp((float) (dist / 32.0), 0.0f, 2.0f);
            Vec3 toMe = pos.subtract(op).normalize();
            Vec3 otherLook = Vec3.directionFromRotation(other.getXRot(), other.getYRot());
            f[base + 8] = clamp((float) otherLook.dot(toMe), -1.0f, 1.0f);
            f[base + 9] = other.isCrouching() ? 1.0f : 0.0f;
            f[base + 10] = categorizeItem(other.getMainHandItem()) / 12.0f;
            f[base + 11] = p.isAlliedTo(other) ? 1.0f : 0.0f;
        }
        if (nearestProj != null) {
            int base = 144;
            Vec3 pp = nearestProj.position();
            Vec3 pv = nearestProj.getDeltaMovement();
            PureNnSchema.worldToEgocentric(pp.x - pos.x, pp.z - pos.z, yaw, ego);
            f[base] = 1.0f;
            f[base + 1] = clamp((float) (ego[0] / 32.0), -2.0f, 2.0f);
            f[base + 2] = clamp((float) ((pp.y - pos.y) / 16.0), -2.0f, 2.0f);
            f[base + 3] = clamp((float) (ego[1] / 32.0), -2.0f, 2.0f);
            PureNnSchema.worldToEgocentric(pv.x, pv.z, yaw, ego);
            f[base + 4] = clamp((float) ego[0], -2.0f, 2.0f);
            f[base + 5] = clamp((float) pv.y, -2.0f, 2.0f);
            f[base + 6] = clamp((float) ego[1], -2.0f, 2.0f);
            f[base + 7] = 1.0f;
        }

        // --- E. Macro Match & Multi-Server Context [152..191] ---
        f[152] = (homeBed != null && !homeBed.destroyed()) ? 1.0f : 0.0f;
        f[153] = clamp(enemyBeds.size() / 7.0f, 0.0f, 1.0f);
        f[154] = clamp(nearbyPlayers.size() / 8.0f, 0.0f, 1.0f);
        for (int i = 0; i < Math.min(3, enemyBeds.size()); i++) {
            BedPOI eb = enemyBeds.get(i);
            int base = 155 + i * 5;
            double d = Math.hypot(eb.x() + 0.5 - pos.x, eb.z() + 0.5 - pos.z);
            f[base] = clamp((float) (d / 64.0), 0.0f, 2.0f);
            encodeRelBearing(pos, eb.x() + 0.5, eb.z() + 0.5, yaw, f, base + 1);
            f[base + 3] = clamp((float) ((eb.y() - pos.y) / 32.0), -1.0f, 1.0f);
            f[base + 4] = 0.5f;
        }

        if (core != null) {
            GenPOI bestDia = null;
            double bestDiaD = Double.MAX_VALUE;
            GenPOI bestEm = null;
            double bestEmD = Double.MAX_VALUE;
            for (GenPOI g : core.getGens()) {
                double d = Math.hypot(g.x() + 0.5 - pos.x, g.z() + 0.5 - pos.z);
                if (g.type() == GenType.DIAMOND && d < bestDiaD) {
                    bestDiaD = d;
                    bestDia = g;
                } else if (g.type() == GenType.EMERALD && d < bestEmD) {
                    bestEmD = d;
                    bestEm = g;
                }
            }
            if (bestDia != null) {
                f[170] = clamp((float) (bestDiaD / 64.0), 0.0f, 2.0f);
                encodeRelBearing(pos, bestDia.x() + 0.5, bestDia.z() + 0.5, yaw, f, 171);
            }
            if (bestEm != null) {
                f[173] = clamp((float) (bestEmD / 64.0), 0.0f, 2.0f);
                encodeRelBearing(pos, bestEm.x() + 0.5, bestEm.z() + 0.5, yaw, f, 174);
            }
        }

        BedwarsMatchState ms = memory != null ? memory.getMatchState() : null;
        if (ms != null) {
            f[176] = clamp(ms.getSharpnessTier(), 0.0f, 1.0f);
            f[177] = clamp(ms.getProtectionTier() / 4.0f, 0.0f, 1.0f);
            f[178] = clamp(ms.getHasteTier() / 2.0f, 0.0f, 1.0f);
            f[179] = clamp(ms.getForgeTier() / 4.0f, 0.0f, 1.0f);
            f[180] = ms.getActiveTrapCount() > 0 ? 1.0f : 0.0f;
            f[181] = clamp((float) (tickIdx / (20.0 * 900.0)), 0.0f, 1.5f);
            f[182] = clamp(ms.getDiamondTier() / 3.0f, 0.0f, 1.0f);
            f[183] = clamp(ms.getEmeraldTier() / 3.0f, 0.0f, 1.0f);
        }

        String srv = memory != null && memory.getCurrentServerId() != null ? memory.getCurrentServerId() : "";
        String map = memory != null && memory.getCurrentMapName() != null ? memory.getCurrentMapName() : "";
        f[184] = (Math.abs(srv.hashCode()) % 1000) / 1000.0f;
        f[185] = (Math.abs(map.hashCode()) % 1000) / 1000.0f;
        f[186] = modern ? 1.0f : 0.0f;
        // Standard normalized BedWars price vector (wool=4, iron_sword=7, iron_armor=12, gapple=3, sharp=4)
        f[187] = 4.0f / 16.0f;
        f[188] = 7.0f / 16.0f;
        f[189] = 12.0f / 24.0f;
        f[190] = 3.0f / 8.0f;
        f[191] = 4.0f / 8.0f;
        return f;
    }

    private PureNnAction extractFactoredAction(
            Baritone baritone,
            IPlayerContext ctx,
            LocalPlayer p,
            float dYaw,
            float dPitch,
            boolean guiOpen
    ) {
        Map<Input, Boolean> resolved = baritone.getInputOverrideHandler().getLastResolvedInputs();
        var opts = ctx.minecraft() != null ? ctx.minecraft().options : null;
        int keys = 0;
        if (Boolean.TRUE.equals(resolved.get(Input.MOVE_FORWARD)) || (opts != null && opts.keyUp.isDown())) keys |= PureNnSchema.KEY_W;
        if (Boolean.TRUE.equals(resolved.get(Input.MOVE_LEFT)) || (opts != null && opts.keyLeft.isDown())) keys |= PureNnSchema.KEY_A;
        if (Boolean.TRUE.equals(resolved.get(Input.MOVE_BACK)) || (opts != null && opts.keyDown.isDown())) keys |= PureNnSchema.KEY_S;
        if (Boolean.TRUE.equals(resolved.get(Input.MOVE_RIGHT)) || (opts != null && opts.keyRight.isDown())) keys |= PureNnSchema.KEY_D;
        if (Boolean.TRUE.equals(resolved.get(Input.JUMP)) || (opts != null && opts.keyJump.isDown())) keys |= PureNnSchema.KEY_JUMP;
        if (Boolean.TRUE.equals(resolved.get(Input.SNEAK)) || (opts != null && opts.keyShift.isDown())) keys |= PureNnSchema.KEY_SNEAK;
        if (Boolean.TRUE.equals(resolved.get(Input.SPRINT)) || p.isSprinting()) keys |= PureNnSchema.KEY_SPRINT;

        boolean lmbNow = Boolean.TRUE.equals(resolved.get(Input.CLICK_LEFT)) || (opts != null && opts.keyAttack.isDown());
        boolean rmbNow = Boolean.TRUE.equals(resolved.get(Input.CLICK_RIGHT)) || (opts != null && opts.keyUse.isDown());
        int lmbMode = !lmbNow ? 0 : (prevLmb ? 2 : 1);
        int rmbMode = !rmbNow ? 0 : (prevRmb ? 2 : 1);
        prevLmb = lmbNow;
        prevRmb = rmbNow;

        int selSlot = p.getInventory().getSelectedSlot();
        int hotbarAction = (prevHotbarSlot >= 0 && selSlot == prevHotbarSlot) ? 0 : (selSlot + 1);
        prevHotbarSlot = selSlot;

        int guiAct = 0;
        synchronized (this) {
            if (guiOpen && pendingGuiAction > 0) {
                guiAct = pendingGuiAction;
            }
            pendingGuiAction = 0;
        }

        return new PureNnAction(
                keys,
                PureNnSchema.muLawEncode(dYaw),
                PureNnSchema.muLawEncode(dPitch),
                lmbMode,
                rmbMode,
                hotbarAction,
                guiAct
        );
    }

    private static void encodeRelBearing(Vec3 pos, double tx, double tz, float yawDeg, float[] out, int idx) {
        double[] ego = new double[2];
        PureNnSchema.worldToEgocentric(tx - pos.x, tz - pos.z, yawDeg, ego);
        double len = Math.hypot(ego[0], ego[1]);
        if (len > 1e-4) {
            out[idx] = (float) (ego[1] / len);     // sin(relBearing) -> +right
            out[idx + 1] = (float) (ego[0] / len); // cos(relBearing) -> +forward
        } else {
            out[idx] = 0.0f;
            out[idx + 1] = 1.0f;
        }
    }

    private static float measureVoidRayDist(Level world, Vec3 pos, int feetY, float rayYawDeg, int voidY) {
        double rad = Math.toRadians(rayYawDeg);
        double dx = -Math.sin(rad);
        double dz = Math.cos(rad);
        BlockPos.MutableBlockPos mpos = new BlockPos.MutableBlockPos();
        for (int step = 1; step <= 16; step++) {
            int bx = (int) Math.floor(pos.x + dx * step);
            int bz = (int) Math.floor(pos.z + dz * step);
            boolean hasFloor = false;
            for (int dy = 0; dy >= -4; dy--) {
                int y = feetY - 1 + dy;
                if (y <= voidY) break;
                mpos.set(bx, y, bz);
                if (!world.getBlockState(mpos).isAir()) {
                    hasFloor = true;
                    break;
                }
            }
            if (!hasFloor) {
                return (float) step;
            }
        }
        return 16.0f;
    }

    private static int categorizeItem(ItemStack st) {
        if (st == null || st.isEmpty()) return 0;
        String path = BuiltInRegistries.ITEM.getKey(st.getItem()).getPath();
        if (path.endsWith("_sword")) return 1;
        if (path.endsWith("_wool")) return 2;
        if (path.contains("planks") || path.contains("end_stone") || path.contains("terracotta") || path.contains("obsidian")) return 3;
        if (path.contains("pickaxe")) return 4;
        if (path.contains("_axe")) return 5;
        if (path.contains("shears")) return 6;
        if (path.contains("golden_apple")) return 7;
        if (path.contains("fire_charge")) return 8;
        if (path.contains("tnt")) return 9;
        if (path.contains("ender_pearl")) return 10;
        if (path.contains("potion")) return 11;
        return 12;
    }

    private static float clamp(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, Float.isFinite(v) ? v : 0.0f));
    }

    private void writerLoop() {
        try {
            while (recording || !queue.isEmpty()) {
                byte[] rec = queue.poll(250L, TimeUnit.MILLISECONDS);
                if (rec == null) continue;
                OutputStream os = outStream;
                if (os != null) {
                    os.write(rec);
                    framesWritten++;
                    if ((framesWritten & 127L) == 0L) {
                        os.flush();
                    }
                }
            }
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        } catch (IOException ignored) {
        } finally {
            closeQuietly();
        }
    }

    private synchronized void closeQuietly() {
        if (outStream != null) {
            try {
                outStream.flush();
                outStream.close();
            } catch (IOException ignored) {
            }
            outStream = null;
        }
    }
}
