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

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Compact egocentric 20 Hz observation and factored action contract matching
 * {@code bedwars_pure_nn_plan.md} for Two-Timescale Pure-NN training (BC, HG-DAgger, ReST, KL-PPO).
 *
 * <ul>
 *   <li>Egocentric rotated local voxel patch: {@code 5 x 4 x 5 = 100} categorical bytes</li>
 *   <li>Dense egocentric float features (kinematics, raycasts, economy, radar, macro): {@value #FLOAT_OBS_DIM} floats</li>
 *   <li>Factored 20 Hz action: 7 movement bits, 2x25 Mu-Law aim bins, LMB/RMB 3-way, hotbar 0..9, masked GUI slot 0..55</li>
 * </ul>
 */
public final class PureNnSchema {

    public static final int MAGIC = 0x504E4E31; // "PNN1"
    public static final int SCHEMA_VERSION = 1;

    public static final int VOXEL_Y = 4;
    public static final int VOXEL_FWD = 5;
    public static final int VOXEL_RIGHT = 5;
    public static final int VOXEL_DIM = VOXEL_Y * VOXEL_FWD * VOXEL_RIGHT; // 100
    public static final int FLOAT_OBS_DIM = 192;

    // Voxel categorical IDs
    public static final byte VOX_AIR = 0;
    public static final byte VOX_UNBREAKABLE_MAP = 1;
    public static final byte VOX_PLAYER_PLACED = 2;
    public static final byte VOX_ENEMY_BED = 3;
    public static final byte VOX_OWN_BED = 4;
    public static final byte VOX_SHOP_NPC = 5;
    public static final byte VOX_GENERATOR = 6;
    public static final byte VOX_DANGER_VOID_FIRE = 7;

    // Key bitmask constants
    public static final int KEY_W = 1 << 0;
    public static final int KEY_A = 1 << 1;
    public static final int KEY_S = 1 << 2;
    public static final int KEY_D = 1 << 3;
    public static final int KEY_JUMP = 1 << 4;
    public static final int KEY_SNEAK = 1 << 5;
    public static final int KEY_SPRINT = 1 << 6;

    // Mu-Law discrete aim quantization
    public static final int AIM_BINS = 25;
    public static final float AIM_MAX_DEG = 35.0f;
    public static final double MU_LAW = 15.0;

    // Fixed binary record size in bytes:
    // tick(4) + episode(4) + flags(1) + source(1) + keysMask(1) + yawBin(1) + pitchBin(1)
    // + lmb(1) + rmb(1) + hotbar(1) + guiAction(1) + reserved(3) + reward(4)
    // + voxels(100) + floats(192*4=768) = 892 bytes/tick
    public static final int RECORD_HEADER_BYTES = 24;
    public static final int RECORD_BYTES = RECORD_HEADER_BYTES + VOXEL_DIM + (FLOAT_OBS_DIM * 4);

    private PureNnSchema() {}

    public record PureNnAction(
            int keysMask,
            int yawBin,
            int pitchBin,
            int lmbMode,
            int rmbMode,
            int hotbarAction,
            int guiAction
    ) {}

    public record PureNnSample(
            int tick,
            int episodeId,
            int flags,
            int sourceId,
            float reward,
            byte[] voxels,
            float[] floats,
            PureNnAction action
    ) {}

    /**
     * Converts world horizontal delta {@code (dx, dz)} into player egocentric {@code (fwd, right)}
     * under Minecraft's yaw convention (yaw=0 -> +Z forward, -X right).
     */
    public static void worldToEgocentric(double dx, double dz, float yawDeg, double[] outFwdRight) {
        double rad = Math.toRadians(yawDeg);
        double sin = Math.sin(rad);
        double cos = Math.cos(rad);
        outFwdRight[0] = dx * (-sin) + dz * cos;    // forward
        outFwdRight[1] = dx * (-cos) + dz * (-sin); // right
    }

    /**
     * Converts egocentric {@code (fwdOffset, rightOffset)} back to world horizontal offsets {@code (dx, dz)}.
     */
    public static void egocentricToWorld(double fwdOffset, double rightOffset, float yawDeg, double[] outDxDz) {
        double rad = Math.toRadians(yawDeg);
        double sin = Math.sin(rad);
        double cos = Math.cos(rad);
        outDxDz[0] = fwdOffset * (-sin) + rightOffset * (-cos);
        outDxDz[1] = fwdOffset * cos + rightOffset * (-sin);
    }

    /**
     * Flat index into the {@code 5x4x5} rotated voxel grid ({@code iy in [0..3], ifwd in [0..4], iright in [0..4]}).
     */
    public static int voxelIndex(int iy, int ifwd, int iright) {
        return ((iy * VOXEL_FWD) + ifwd) * VOXEL_RIGHT + iright;
    }

    /**
     * Encodes angular delta (degrees/tick) into {@code [0 .. AIM_BINS-1]} using symmetric Mu-Law
     * companding (high precision near 0 deg, capped at +/-35 deg/tick).
     */
    public static int muLawEncode(float deltaDeg) {
        double clamped = Math.max(-AIM_MAX_DEG, Math.min(AIM_MAX_DEG, deltaDeg));
        double norm = clamped / AIM_MAX_DEG; // [-1, 1]
        double companded = Math.signum(norm) * Math.log1p(MU_LAW * Math.abs(norm)) / Math.log1p(MU_LAW);
        double scaled = (companded + 1.0) * 0.5 * (AIM_BINS - 1);
        return Math.max(0, Math.min(AIM_BINS - 1, (int) Math.round(scaled)));
    }

    /**
     * Decodes a Mu-Law discrete bin {@code [0 .. AIM_BINS-1]} back to degrees/tick.
     */
    public static float muLawDecode(int bin) {
        int c = Math.max(0, Math.min(AIM_BINS - 1, bin));
        double companded = (2.0 * c / (AIM_BINS - 1)) - 1.0; // [-1, 1]
        double norm = Math.signum(companded) * (Math.expm1(Math.abs(companded) * Math.log1p(MU_LAW)) / MU_LAW);
        return (float) (norm * AIM_MAX_DEG);
    }

    /**
     * Encodes a single 20 Hz {@link PureNnSample} into a fixed {@value #RECORD_BYTES}-byte little-endian buffer.
     */
    public static byte[] encodeRecord(PureNnSample sample) {
        ByteBuffer buf = ByteBuffer.allocate(RECORD_BYTES).order(ByteOrder.LITTLE_ENDIAN);
        buf.putInt(sample.tick());
        buf.putInt(sample.episodeId());
        buf.put((byte) (sample.flags() & 0xFF));
        buf.put((byte) (sample.sourceId() & 0xFF));
        PureNnAction a = sample.action();
        buf.put((byte) (a.keysMask() & 0xFF));
        buf.put((byte) (a.yawBin() & 0xFF));
        buf.put((byte) (a.pitchBin() & 0xFF));
        buf.put((byte) (a.lmbMode() & 0xFF));
        buf.put((byte) (a.rmbMode() & 0xFF));
        buf.put((byte) (a.hotbarAction() & 0xFF));
        buf.put((byte) (a.guiAction() & 0xFF));
        buf.put((byte) 0);
        buf.put((byte) 0);
        buf.put((byte) 0);
        buf.putFloat(sample.reward());

        byte[] vox = sample.voxels();
        for (int i = 0; i < VOXEL_DIM; i++) {
            buf.put(vox != null && i < vox.length ? vox[i] : VOX_AIR);
        }
        float[] fl = sample.floats();
        for (int i = 0; i < FLOAT_OBS_DIM; i++) {
            float val = (fl != null && i < fl.length && Float.isFinite(fl[i])) ? fl[i] : 0.0f;
            buf.putFloat(val);
        }
        return buf.array();
    }

    /**
     * Decodes a {@value #RECORD_BYTES}-byte little-endian record back into a {@link PureNnSample}.
     */
    public static PureNnSample decodeRecord(byte[] raw) {
        if (raw == null || raw.length < RECORD_BYTES) {
            throw new IllegalArgumentException("Invalid PNN record size: " + (raw == null ? 0 : raw.length));
        }
        ByteBuffer buf = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
        int tick = buf.getInt();
        int episodeId = buf.getInt();
        int flags = buf.get() & 0xFF;
        int sourceId = buf.get() & 0xFF;
        int keysMask = buf.get() & 0xFF;
        int yawBin = buf.get() & 0xFF;
        int pitchBin = buf.get() & 0xFF;
        int lmbMode = buf.get() & 0xFF;
        int rmbMode = buf.get() & 0xFF;
        int hotbarAction = buf.get() & 0xFF;
        int guiAction = buf.get() & 0xFF;
        buf.get();
        buf.get();
        buf.get();
        float reward = buf.getFloat();

        byte[] vox = new byte[VOXEL_DIM];
        buf.get(vox);
        float[] fl = new float[FLOAT_OBS_DIM];
        for (int i = 0; i < FLOAT_OBS_DIM; i++) {
            fl[i] = buf.getFloat();
        }
        return new PureNnSample(
                tick, episodeId, flags, sourceId, reward, vox, fl,
                new PureNnAction(keysMask, yawBin, pitchBin, lmbMode, rmbMode, hotbarAction, guiAction)
        );
    }

    /**
     * Applies exact Left-Right mirror symmetry to a sample (doubles effective training data for free).
     */
    public static PureNnSample mirrorLeftRight(PureNnSample s) {
        byte[] mv = new byte[VOXEL_DIM];
        byte[] v = s.voxels();
        for (int iy = 0; iy < VOXEL_Y; iy++) {
            for (int ifwd = 0; ifwd < VOXEL_FWD; ifwd++) {
                for (int ir = 0; ir < VOXEL_RIGHT; ir++) {
                    mv[voxelIndex(iy, ifwd, (VOXEL_RIGHT - 1) - ir)] = v[voxelIndex(iy, ifwd, ir)];
                }
            }
        }
        float[] mf = s.floats().clone();
        // Flip egocentric right velocity & relative sin bearings & hit normal right
        mf[2] = -mf[2];
        mf[4] = -mf[4];
        mf[6] = -mf[6];
        mf[8] = -mf[8];
        mf[41] = -mf[41];
        // Swap 8-dir void raycasts: (fwd-R 23 <-> fwd-L 29), (R 24 <-> L 28), (back-R 25 <-> back-L 27)
        swap(mf, 23, 29);
        swap(mf, 24, 28);
        swap(mf, 25, 27);
        // Flip top-4 player radar right coords & right vel
        for (int p = 0; p < 4; p++) {
            int base = 96 + p * 12;
            mf[base + 3] = -mf[base + 3];
            mf[base + 6] = -mf[base + 6];
        }
        // Flip nearest projectile right coord & vel
        mf[144 + 3] = -mf[144 + 3];
        mf[144 + 6] = -mf[144 + 6];
        // Flip top-3 enemy beds sin_rel
        for (int b = 0; b < 3; b++) {
            mf[155 + b * 5 + 1] = -mf[155 + b * 5 + 1];
        }
        // Flip dia/em gen sin_rel
        mf[171] = -mf[171];
        mf[174] = -mf[174];

        PureNnAction a = s.action();
        int km = a.keysMask();
        boolean hadA = (km & KEY_A) != 0;
        boolean hadD = (km & KEY_D) != 0;
        km &= ~(KEY_A | KEY_D);
        if (hadA) km |= KEY_D;
        if (hadD) km |= KEY_A;
        int mirroredYawBin = (AIM_BINS - 1) - a.yawBin();

        return new PureNnSample(
                s.tick(), s.episodeId(), s.flags(), s.sourceId(), s.reward(), mv, mf,
                new PureNnAction(km, mirroredYawBin, a.pitchBin(), a.lmbMode(), a.rmbMode(), a.hotbarAction(), a.guiAction())
        );
    }

    private static void swap(float[] arr, int i, int j) {
        float tmp = arr[i];
        arr[i] = arr[j];
        arr[j] = tmp;
    }

    /**
     * Compact 16-byte file header for binary {@code .pnn} shards.
     */
    public static byte[] encodeFileHeader() {
        ByteBuffer hdr = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN);
        hdr.putInt(MAGIC);
        hdr.putInt(SCHEMA_VERSION);
        hdr.putInt(VOXEL_DIM);
        hdr.putInt(FLOAT_OBS_DIM);
        return hdr.array();
    }
}
