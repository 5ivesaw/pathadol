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
import baritone.bedwars.BedwarsArenaMemory;
import baritone.bedwars.BedwarsDefensePlanner;
import baritone.bedwars.BedwarsDefensePlanner.DefenseBlockKind;
import baritone.bedwars.BedwarsDefensePlanner.ToolProfile;
import baritone.bedwars.BedwarsMapCore;
import baritone.bedwars.BedwarsMapCore.BedPOI;
import baritone.bedwars.BedwarsMapCore.BwsRuleset;
import baritone.bedwars.BedwarsMapCore.GenPOI;
import baritone.bedwars.BedwarsMapCore.GenType;
import baritone.bedwars.BedwarsMapCore.ShopNPC;
import baritone.bedwars.BedwarsMapCore.SpawnPOI;
import baritone.bedwars.BedwarsTacticalHelper;
import baritone.bedwars.BedwarsTacticalHelper.InventorySummary;
import baritone.brain.BrainActionSpace.Maneuver;
import baritone.brain.BrainSchema.ObsSnapshot;
import baritone.brain.BrainSchema.ThreatFeature;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Extracts a live {@link ObsSnapshot} every tick from {@link Baritone}, {@link IPlayerContext},
 * {@link BedwarsArenaMemory}, and local void terrain geometry.
 */
public final class BrainContextExtractor {

    private BrainContextExtractor() {}

    public static ObsSnapshot capture(
            Baritone baritone,
            Maneuver activeManeuver,
            int maneuverHoldTicks
    ) {
        IPlayerContext ctx = baritone.getPlayerContext();
        LocalPlayer p = ctx.player();
        Level world = ctx.world();
        if (p == null || world == null) {
            return null;
        }

        BedwarsArenaMemory memory = baritone.getBedwarsArenaMemory();
        BedwarsMapCore core = memory != null ? memory.getActiveCore() : null;
        boolean modern = memory != null && memory.getActiveRuleset() == BwsRuleset.MODERN_26_3;
        InventorySummary inv = BedwarsTacticalHelper.summarizeInventory(p, modern);

        Vec3 pos = p.position();
        Vec3 vel = p.getDeltaMovement();
        float yaw = p.getYRot();
        int voidFloor = core != null ? core.voidFloorY() : world.getMinY();

        double horizSpeed = Math.hypot(vel.x, vel.z);
        double voidClearance = Math.max(-32.0, pos.y - voidFloor);
        double ceilClearance = probeCeilingClearance(world, p.blockPosition());
        double frontVoidDist = probeFrontVoidEdgeDist(world, pos, yaw);
        double voidSurroundFrac = probeVoidSurroundFraction(world, pos);

        boolean homeBedAlive = false;
        double homeBedDefRatio = 0.0;
        double homeBedDist = 128.0;
        double homeBedSin = 0.0;
        double homeBedCos = 1.0;
        double homeBedDy = 0.0;
        double homePosX = pos.x;
        double homePosY = pos.y;
        double homePosZ = pos.z;
        boolean haveHomeAnchor = false;

        int livingEnemyBeds = 0;
        double nearestBedDist = 128.0;
        double nearestBedSin = 0.0;
        double nearestBedCos = 1.0;
        double nearestBedBreakSec = 0.0;
        double weakestBedBreakSec = 15.0;

        double diamondDist = 128.0;
        double diamondSin = 0.0;
        double diamondCos = 1.0;
        double emeraldDist = 128.0;
        double emeraldSin = 0.0;
        double emeraldCos = 1.0;
        boolean nearShopOrForge = false;

        ToolProfile toolProfile = new ToolProfile(inv.shearsTier(), inv.pickaxeTier(), inv.axeTier());

        if (core != null) {
            for (BedPOI bed : core.getBeds()) {
                if (bed.isHome()) {
                    homeBedAlive = !bed.destroyed();
                    homePosX = bed.x() + 0.5;
                    homePosY = bed.y() + 0.5;
                    homePosZ = bed.z() + 0.5;
                    haveHomeAnchor = true;
                    double dx = homePosX - pos.x;
                    double dy = homePosY - pos.y;
                    double dz = homePosZ - pos.z;
                    homeBedDist = Math.sqrt(dx * dx + dy * dy + dz * dz);
                    double[] sc = BrainSchema.relativeBearingSinCos(dx, dz, yaw);
                    homeBedSin = sc[0];
                    homeBedCos = sc[1];
                    homeBedDy = dy;
                    if (homeBedAlive && world.isLoaded(new BlockPos(bed.x(), bed.y(), bed.z()))) {
                        homeBedDefRatio = BedwarsTacticalHelper.computeBedShellCoverageRatio(
                                bed.x(), bed.y(), bed.z(),
                                bed.footX(), bed.footY(), bed.footZ(),
                                (bx, by, bz) -> !world.getBlockState(new BlockPos(bx, by, bz)).canBeReplaced()
                        );
                    }
                } else if (!bed.destroyed()) {
                    livingEnemyBeds++;
                    double dx = (bed.x() + 0.5) - pos.x;
                    double dy = (bed.y() + 0.5) - pos.y;
                    double dz = (bed.z() + 0.5) - pos.z;
                    double d = Math.sqrt(dx * dx + dy * dy + dz * dz);
                    double breakSec = estimateQuickBedBreakSec(world, bed, toolProfile);
                    if (breakSec < weakestBedBreakSec) {
                        weakestBedBreakSec = breakSec;
                    }
                    if (d < nearestBedDist) {
                        nearestBedDist = d;
                        double[] sc = BrainSchema.relativeBearingSinCos(dx, dz, yaw);
                        nearestBedSin = sc[0];
                        nearestBedCos = sc[1];
                        nearestBedBreakSec = breakSec;
                    }
                }
            }

            if (!haveHomeAnchor) {
                for (SpawnPOI sp : core.getSpawns()) {
                    if (sp.team() == core.homeTeam()) {
                        homePosX = sp.x() + 0.5;
                        homePosY = sp.y() + 0.5;
                        homePosZ = sp.z() + 0.5;
                        haveHomeAnchor = true;
                        double dx = homePosX - pos.x;
                        double dy = homePosY - pos.y;
                        double dz = homePosZ - pos.z;
                        homeBedDist = Math.sqrt(dx * dx + dy * dy + dz * dz);
                        double[] sc = BrainSchema.relativeBearingSinCos(dx, dz, yaw);
                        homeBedSin = sc[0];
                        homeBedCos = sc[1];
                        homeBedDy = dy;
                        break;
                    }
                }
            }

            for (GenPOI gen : core.getGens()) {
                double dx = (gen.x() + 0.5) - pos.x;
                double dy = (gen.y() + 0.5) - pos.y;
                double dz = (gen.z() + 0.5) - pos.z;
                double d = Math.sqrt(dx * dx + dy * dy + dz * dz);
                if (gen.type() == GenType.DIAMOND && d < diamondDist) {
                    diamondDist = d;
                    double[] sc = BrainSchema.relativeBearingSinCos(dx, dz, yaw);
                    diamondSin = sc[0];
                    diamondCos = sc[1];
                } else if (gen.type() == GenType.EMERALD && d < emeraldDist) {
                    emeraldDist = d;
                    double[] sc = BrainSchema.relativeBearingSinCos(dx, dz, yaw);
                    emeraldSin = sc[0];
                    emeraldCos = sc[1];
                } else if (gen.type() == GenType.HOME_FORGE && d <= 9.0) {
                    nearShopOrForge = true;
                }
            }

            for (ShopNPC npc : core.getShopNpcs()) {
                double dx = (npc.x() + 0.5) - pos.x;
                double dy = (npc.y() + 0.5) - pos.y;
                double dz = (npc.z() + 0.5) - pos.z;
                if (dx * dx + dy * dy + dz * dz <= 100.0) {
                    nearShopOrForge = true;
                    break;
                }
            }
        }

        if (livingEnemyBeds == 0) {
            weakestBedBreakSec = 0.0;
        }
        if (homeBedAlive && homeBedDist <= 14.0) {
            nearShopOrForge = true;
        }

        List<ThreatFeature> threats = new ArrayList<>(BrainSchema.MAX_TRACKED_THREATS);
        boolean enemyNearHome = false;
        List<EnemyCandidate> candidates = new ArrayList<>();
        for (Entity entity : ctx.entities()) {
            if (!(entity instanceof Player other) || other == p || !other.isAlive() || other.isSpectator()) {
                continue;
            }
            if (p.isAlliedTo(other) || other.getUUID().version() == 2) {
                continue;
            }
            String cleanName = BedwarsArenaMemory.stripColors(other.getName().getString()).toUpperCase(Locale.ROOT);
            if (cleanName.contains("[NPC]") || cleanName.contains("SHOP") || cleanName.contains("UPGRADE")) {
                continue;
            }
            double dx = other.getX() - pos.x;
            double dy = other.getY() - pos.y;
            double dz = other.getZ() - pos.z;
            double d = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (haveHomeAnchor && homeBedAlive) {
                double hx = other.getX() - homePosX;
                double hz = other.getZ() - homePosZ;
                if (hx * hx + Math.pow(other.getY() - homePosY, 2) + hz * hz <= 400.0) {
                    enemyNearHome = true;
                }
            }
            if (d > 64.0) {
                continue;
            }
            double[] sc = BrainSchema.relativeBearingSinCos(dx, dz, yaw);
            double horizD = Math.max(1e-4, Math.hypot(dx, dz));
            Vec3 ov = other.getDeltaMovement();
            // Positive closing speed when enemy approaches us faster than we pull away
            double closing = -(((ov.x - vel.x) * (dx / horizD)) + ((ov.z - vel.z) * (dz / horizD))) / 0.45;
            double danger = Math.max(0.0, Math.min(1.0, (1.0 - d / 24.0) * 0.72 + Math.max(0.0, closing) * 0.28));
            candidates.add(new EnemyCandidate(d, new ThreatFeature(true, d, sc[0], sc[1], closing, danger)));
        }
        candidates.sort(Comparator.comparingDouble(c -> c.dist));
        for (int i = 0; i < Math.min(BrainSchema.MAX_TRACKED_THREATS, candidates.size()); i++) {
            threats.add(candidates.get(i).feature);
        }

        return new ObsSnapshot(
                p.getHealth(),
                p.getAbsorptionAmount(),
                p.getFoodData().getFoodLevel(),
                p.onGround(),
                p.isSprinting(),
                p.isCrouching(),
                p.isInWater(),
                horizSpeed,
                vel.y,
                p.hurtTime,
                voidClearance,
                ceilClearance,
                inv.offhandHasBridgeBlock(),
                modern,
                inv,
                homeBedAlive,
                homeBedDefRatio,
                homeBedDist,
                homeBedSin,
                homeBedCos,
                homeBedDy,
                livingEnemyBeds,
                nearestBedDist,
                nearestBedSin,
                nearestBedCos,
                nearestBedBreakSec,
                weakestBedBreakSec,
                diamondDist,
                diamondSin,
                diamondCos,
                emeraldDist,
                emeraldSin,
                emeraldCos,
                threats,
                frontVoidDist,
                voidSurroundFrac,
                activeManeuver != null ? activeManeuver.ordinal() : 0,
                maneuverHoldTicks,
                baritone.getPathingBehavior().isPathing(),
                nearShopOrForge,
                enemyNearHome
        );
    }

    private record EnemyCandidate(double dist, ThreatFeature feature) {}

    private static double estimateQuickBedBreakSec(Level world, BedPOI bed, ToolProfile tools) {
        BlockPos head = new BlockPos(bed.x(), bed.y(), bed.z());
        if (!world.isLoaded(head)) {
            return 2.0;
        }
        int[][] offsets = {{1, 0, 0}, {-1, 0, 0}, {0, 0, 1}, {0, 0, -1}, {0, 1, 0}};
        double minTicks = Double.POSITIVE_INFINITY;
        for (int[] o : offsets) {
            BlockPos p = head.offset(o[0], o[1], o[2]);
            var state = world.getBlockState(p);
            if (state.getBlock() instanceof BedBlock) {
                continue;
            }
            if (state.canBeReplaced()) {
                return 0.15;
            }
            DefenseBlockKind kind = BedwarsArenaMemory.classifyDefenseBlock(state.getBlock());
            double ticks = BedwarsDefensePlanner.estimateLayerTicks(kind, tools);
            if (ticks < minTicks) {
                minTicks = ticks;
            }
        }
        if (!Double.isFinite(minTicks)) {
            return 1.5;
        }
        return Math.max(0.2, Math.min(15.0, minTicks / 20.0));
    }

    private static double probeCeilingClearance(Level world, BlockPos feet) {
        for (int dy = 2; dy <= 48; dy++) {
            BlockPos p = feet.above(dy);
            if (!world.getBlockState(p).canBeReplaced()) {
                return dy - 1;
            }
        }
        return 48.0;
    }

    private static double probeFrontVoidEdgeDist(Level world, Vec3 pos, float yawDeg) {
        double rad = Math.toRadians(yawDeg);
        double dirX = -Math.sin(rad);
        double dirZ = Math.cos(rad);
        int feetY = (int) Math.floor(pos.y);
        for (double dist = 0.75; dist <= 16.0; dist += 0.75) {
            int bx = (int) Math.floor(pos.x + dirX * dist);
            int bz = (int) Math.floor(pos.z + dirZ * dist);
            if (!hasGroundSupport(world, bx, feetY, bz)) {
                return dist;
            }
        }
        return 16.0;
    }

    private static double probeVoidSurroundFraction(Level world, Vec3 pos) {
        int feetY = (int) Math.floor(pos.y);
        int voidDirs = 0;
        for (int k = 0; k < 8; k++) {
            double ang = (Math.PI * 2.0 * k) / 8.0;
            int bx = (int) Math.floor(pos.x + Math.cos(ang) * 2.0);
            int bz = (int) Math.floor(pos.z + Math.sin(ang) * 2.0);
            if (!hasGroundSupport(world, bx, feetY, bz)) {
                voidDirs++;
            }
        }
        return voidDirs / 8.0;
    }

    private static boolean hasGroundSupport(Level world, int x, int feetY, int z) {
        for (int dy = 0; dy >= -3; dy--) {
            BlockPos p = new BlockPos(x, feetY + dy, z);
            if (!world.getBlockState(p).canBeReplaced()) {
                return true;
            }
        }
        return false;
    }
}
