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

import baritone.bedwars.BedwarsTacticalHelper.InventorySummary;

import java.util.List;

/**
 * Versioned numeric observation contract ({@value #OBS_DIM} normalized floats) shared identically
 * between live Pathadol runtime inference and the Google Colab PyTorch training pipeline.
 */
public final class BrainSchema {

    public static final int SCHEMA_VERSION = 1;
    public static final int OBS_DIM = 72;
    public static final int MAX_TRACKED_THREATS = 3;

    public record ThreatFeature(
            boolean present,
            double dist,
            double sinBearing,
            double cosBearing,
            double radialClosingSpeed,
            double dangerScore
    ) {
        public static final ThreatFeature EMPTY = new ThreatFeature(false, 64.0, 0.0, 1.0, 0.0, 0.0);
    }

    public record ObsSnapshot(
            // Self kinematics
            float health,
            float absorption,
            int foodLevel,
            boolean onGround,
            boolean sprinting,
            boolean crouching,
            boolean inWater,
            double horizSpeed,
            double vertVel,
            int hurtTime,
            double voidClearanceBlocks,
            double ceilingClearanceBlocks,
            boolean offhandHasBlock,
            boolean modernRuleset,

            // Inventory & currencies
            InventorySummary inventory,

            // Arena & objective spatial context
            boolean homeBedAlive,
            double homeBedDefenseRatio,
            double homeBedDist,
            double homeBedSinBearing,
            double homeBedCosBearing,
            double homeBedDy,
            int livingEnemyBedCount,
            double nearestEnemyBedDist,
            double nearestEnemyBedSinBearing,
            double nearestEnemyBedCosBearing,
            double nearestEnemyBedBreakSec,
            double weakestEnemyBedBreakSec,
            double diamondGenDist,
            double diamondGenSinBearing,
            double diamondGenCosBearing,
            double emeraldGenDist,
            double emeraldGenSinBearing,
            double emeraldGenCosBearing,

            // Enemy radar (nearest 3)
            List<ThreatFeature> threats,

            // Local terrain & process context
            double frontVoidEdgeDist,
            double voidSurroundFraction,
            int activeManeuverOrdinal,
            int maneuverHoldTicks,
            boolean pathActive,
            boolean nearHomeShopOrForge,
            boolean hasEnemyNearHomeBed
    ) {
        public double nearestThreatDist() {
            if (threats == null || threats.isEmpty() || !threats.get(0).present()) {
                return 99.0;
            }
            return threats.get(0).dist();
        }
    }

    private static final List<String> FEATURE_NAMES = List.of(
            "health_norm", "absorption_norm", "food_norm", "on_ground", "is_sprinting", "is_crouching",
            "is_in_water", "horiz_speed", "vert_vel", "hurt_time_norm", "void_clearance_norm",
            "ceiling_clearance_norm", "offhand_is_block", "is_modern_ruleset",
            "iron_norm", "gold_norm", "diamonds_norm", "emeralds_norm", "bridge_blocks_norm",
            "hard_blocks_norm", "sword_tier_norm", "pick_tier_norm", "axe_tier_norm", "has_shears",
            "armor_norm", "gapples_norm", "fireballs_norm", "tnt_norm", "pearls_norm", "has_bow_arrows",
            "home_bed_alive", "home_bed_defense_ratio", "home_bed_dist_norm", "home_bed_sin",
            "home_bed_cos", "home_bed_dy_norm", "living_enemy_beds_norm", "nearest_enemy_bed_dist_norm",
            "nearest_enemy_bed_sin", "nearest_enemy_bed_cos", "nearest_enemy_bed_break_sec",
            "weakest_enemy_bed_break_sec", "diamond_gen_dist_norm", "diamond_gen_sin", "diamond_gen_cos",
            "emerald_gen_dist_norm", "emerald_gen_sin", "emerald_gen_cos",
            "enemy0_present", "enemy0_dist", "enemy0_sin", "enemy0_cos", "enemy0_closing", "enemy0_danger",
            "enemy1_present", "enemy1_dist", "enemy1_sin", "enemy1_cos", "enemy1_closing", "enemy1_danger",
            "enemy2_present", "enemy2_dist", "enemy2_sin", "enemy2_cos", "enemy2_closing", "enemy2_danger",
            "front_void_dist_norm", "void_surround_frac", "active_maneuver_norm", "maneuver_hold_sec_norm",
            "path_active", "near_home_shop_forge"
    );

    private BrainSchema() {}

    public static List<String> featureNames() {
        return FEATURE_NAMES;
    }

    /**
     * Encodes an {@link ObsSnapshot} into the fixed {@value #OBS_DIM}-element normalized float tensor.
     */
    public static float[] encodeObservation(ObsSnapshot s) {
        float[] out = new float[OBS_DIM];
        if (s == null) {
            return out;
        }
        int i = 0;
        out[i++] = clamp01(s.health() / 20.0);
        out[i++] = clamp01(s.absorption() / 20.0);
        out[i++] = clamp01(s.foodLevel() / 20.0);
        out[i++] = s.onGround() ? 1.0f : 0.0f;
        out[i++] = s.sprinting() ? 1.0f : 0.0f;
        out[i++] = s.crouching() ? 1.0f : 0.0f;
        out[i++] = s.inWater() ? 1.0f : 0.0f;
        out[i++] = clamp(s.horizSpeed() / 0.45, 0.0, 2.0);
        out[i++] = clamp(s.vertVel() / 0.80, -1.5, 1.5);
        out[i++] = clamp01(s.hurtTime() / 10.0);
        out[i++] = clamp(s.voidClearanceBlocks() / 64.0, -0.5, 2.0);
        out[i++] = clamp(s.ceilingClearanceBlocks() / 64.0, -0.5, 2.0);
        out[i++] = s.offhandHasBlock() ? 1.0f : 0.0f;
        out[i++] = s.modernRuleset() ? 1.0f : 0.0f;

        InventorySummary inv = s.inventory();
        if (inv != null) {
            out[i++] = clamp01(inv.iron() / 64.0);
            out[i++] = clamp01(inv.gold() / 32.0);
            out[i++] = clamp01(inv.diamonds() / 16.0);
            out[i++] = clamp01(inv.emeralds() / 16.0);
            out[i++] = clamp01(inv.bridgeBlockCount() / 64.0);
            out[i++] = clamp01(inv.hardDefenseCount() / 32.0);
            out[i++] = clamp01(inv.swordTier() / 4.0);
            out[i++] = clamp01(inv.pickaxeTier() / 4.0);
            out[i++] = clamp01(inv.axeTier() / 4.0);
            out[i++] = inv.shearsTier() > 0 ? 1.0f : 0.0f;
            out[i++] = clamp(inv.armorPoints() / 20.0, 0.0, 1.2);
            out[i++] = clamp01(inv.gapples() / 8.0);
            out[i++] = clamp01(inv.fireballs() / 4.0);
            out[i++] = clamp01(inv.tntCount() / 4.0);
            out[i++] = clamp01(inv.pearls() / 4.0);
            out[i++] = inv.hasBowAndArrows() ? 1.0f : 0.0f;
        } else {
            i += 16;
        }

        out[i++] = s.homeBedAlive() ? 1.0f : 0.0f;
        out[i++] = clamp01(s.homeBedDefenseRatio());
        out[i++] = clamp01(s.homeBedDist() / 128.0);
        out[i++] = clamp(s.homeBedSinBearing(), -1.0, 1.0);
        out[i++] = clamp(s.homeBedCosBearing(), -1.0, 1.0);
        out[i++] = clamp(s.homeBedDy() / 32.0, -1.0, 1.0);
        out[i++] = clamp01(s.livingEnemyBedCount() / 7.0);
        out[i++] = clamp01(s.nearestEnemyBedDist() / 128.0);
        out[i++] = clamp(s.nearestEnemyBedSinBearing(), -1.0, 1.0);
        out[i++] = clamp(s.nearestEnemyBedCosBearing(), -1.0, 1.0);
        out[i++] = clamp01(s.nearestEnemyBedBreakSec() / 15.0);
        out[i++] = clamp01(s.weakestEnemyBedBreakSec() / 15.0);
        out[i++] = clamp01(s.diamondGenDist() / 128.0);
        out[i++] = clamp(s.diamondGenSinBearing(), -1.0, 1.0);
        out[i++] = clamp(s.diamondGenCosBearing(), -1.0, 1.0);
        out[i++] = clamp01(s.emeraldGenDist() / 128.0);
        out[i++] = clamp(s.emeraldGenSinBearing(), -1.0, 1.0);
        out[i++] = clamp(s.emeraldGenCosBearing(), -1.0, 1.0);

        for (int k = 0; k < MAX_TRACKED_THREATS; k++) {
            ThreatFeature tf = (s.threats() != null && k < s.threats().size())
                    ? s.threats().get(k)
                    : ThreatFeature.EMPTY;
            out[i++] = tf.present() ? 1.0f : 0.0f;
            out[i++] = tf.present() ? clamp01(tf.dist() / 64.0) : 1.0f;
            out[i++] = tf.present() ? clamp(tf.sinBearing(), -1.0, 1.0) : 0.0f;
            out[i++] = tf.present() ? clamp(tf.cosBearing(), -1.0, 1.0) : 1.0f;
            out[i++] = tf.present() ? clamp(tf.radialClosingSpeed(), -1.0, 1.0) : 0.0f;
            out[i++] = tf.present() ? clamp01(tf.dangerScore()) : 0.0f;
        }

        out[i++] = clamp01(s.frontVoidEdgeDist() / 16.0);
        out[i++] = clamp01(s.voidSurroundFraction());
        int maxM = Math.max(1, BrainActionSpace.Maneuver.values().length - 1);
        out[i++] = clamp01(Math.max(0, s.activeManeuverOrdinal()) / (double) maxM);
        out[i++] = clamp01(s.maneuverHoldTicks() / 200.0);
        out[i++] = s.pathActive() ? 1.0f : 0.0f;
        out[i++] = s.nearHomeShopOrForge() ? 1.0f : 0.0f;
        return out;
    }

    /**
     * Computes sine and cosine of horizontal relative bearing {@code targetYaw - playerYaw},
     * returning {@code [sin, cos]}.
     */
    public static double[] relativeBearingSinCos(double dx, double dz, float playerYawDeg) {
        if (Math.hypot(dx, dz) < 1e-4) {
            return new double[]{0.0, 1.0};
        }
        double targetYawRad = Math.atan2(-dx, dz);
        double playerYawRad = Math.toRadians(playerYawDeg);
        double rel = targetYawRad - playerYawRad;
        return new double[]{Math.sin(rel), Math.cos(rel)};
    }

    private static float clamp01(double v) {
        if (!Double.isFinite(v)) {
            return 0.0f;
        }
        return (float) Math.max(0.0, Math.min(1.0, v));
    }

    private static float clamp(double v, double lo, double hi) {
        if (!Double.isFinite(v)) {
            return 0.0f;
        }
        return (float) Math.max(lo, Math.min(hi, v));
    }
}
