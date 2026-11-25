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
import baritone.bedwars.BedwarsBridgeConnectivity;
import baritone.bedwars.BedwarsDefensePlanner;
import baritone.bedwars.BedwarsDefensePlanner.ToolProfile;
import baritone.bedwars.BedwarsMapCore;
import baritone.bedwars.BedwarsMapCore.BedPOI;
import baritone.bedwars.BedwarsMapCore.BwsRuleset;
import baritone.bedwars.BedwarsMapCore.CachedBedDefenseProfile;
import baritone.bedwars.BedwarsMapCore.GenPOI;
import baritone.bedwars.BedwarsMapCore.GenType;
import baritone.bedwars.BedwarsMapCore.ShopNPC;
import baritone.bedwars.BedwarsMapCore.SpawnPOI;
import baritone.bedwars.BedwarsMatchState;
import baritone.bedwars.BedwarsTacticalHelper;
import baritone.bedwars.BedwarsTacticalHelper.FightEvaluation;
import baritone.bedwars.BedwarsTacticalHelper.InventorySummary;
import baritone.bedwars.BedwarsTickContext;
import baritone.brain.BrainActionSpace.Maneuver;
import baritone.brain.BrainSchema.ObsSnapshot;
import baritone.brain.BrainSchema.ThreatFeature;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Extracts a live {@link ObsSnapshot} from {@link Baritone}, {@link IPlayerContext},
 * {@link BedwarsArenaMemory} (via single-pass {@link BedwarsTickContext}), and bitset-accelerated
 * local void/ceiling terrain probes.
 */
public final class BrainContextExtractor {

    private static final double[] SURROUND_COS = {
            1.0, 0.70710678, 0.0, -0.70710678, -1.0, -0.70710678, 0.0, 0.70710678
    };
    private static final double[] SURROUND_SIN = {
            0.0, 0.70710678, 1.0, 0.70710678, 0.0, -0.70710678, -1.0, -0.70710678
    };
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
        BedwarsMatchState matchState = memory != null ? memory.getMatchState() : null;
        BedwarsTickContext tickCtx = memory != null ? memory.getTickContext() : null;
        BlockPos.MutableBlockPos mp = tickCtx != null ? tickCtx.scratchPos() : new BlockPos.MutableBlockPos();

        boolean modern = memory != null && memory.getActiveRuleset() == BwsRuleset.MODERN_26_3;
        InventorySummary inv = tickCtx != null
                ? tickCtx.getInventorySummary(p, modern, world.getGameTime())
                : BedwarsTacticalHelper.summarizeInventory(p, modern);

        Vec3 pos = p.position();
        Vec3 vel = p.getDeltaMovement();
        float yaw = p.getYRot();
        int voidFloor = core != null ? core.voidFloorY() : world.getMinY();

        double horizSpeed = Math.sqrt(vel.x * vel.x + vel.z * vel.z);
        double voidClearance = Math.max(-32.0, pos.y - voidFloor);
        double ceilClearance = probeCeilingClearance(world, core, (int) Math.floor(pos.x), (int) Math.floor(pos.y), (int) Math.floor(pos.z), mp);
        double frontVoidDist = probeFrontVoidEdgeDist(world, core, pos, yaw, mp);
        double voidSurroundFrac = probeVoidSurroundFraction(world, core, pos, mp);

        boolean homeBedAlive = false;
        double homeBedDefRatio = 0.0;
        double homeBedOuterDefRatio = 0.0;
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
        double weakestBedDist = 128.0;
        int nearestBedX = 0, nearestBedY = 0, nearestBedZ = 0;
        boolean haveNearestBed = false;
        int weakestBedX = 0, weakestBedY = 0, weakestBedZ = 0;
        boolean haveWeakestBed = false;

        double diamondDist = 128.0;
        double diamondSin = 0.0;
        double diamondCos = 1.0;
        int diamondX = 0, diamondY = 0, diamondZ = 0;
        boolean haveDiamond = false;
        double emeraldDist = 128.0;
        double emeraldSin = 0.0;
        double emeraldCos = 1.0;
        int emeraldX = 0, emeraldY = 0, emeraldZ = 0;
        boolean haveEmerald = false;
        boolean nearShopOrForge = false;

        ToolProfile toolProfile = new ToolProfile(inv.shearsTier(), inv.pickaxeTier(), inv.axeTier());

        if (core != null) {
            BedPOI[] beds = core.getBedsSnapshot();
            for (int bIdx = 0; bIdx < beds.length; bIdx++) {
                BedPOI bed = beds[bIdx];
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
                    if (homeBedAlive && world.isLoaded(mp.set(bed.x(), bed.y(), bed.z()))) {
                        BedwarsTacticalHelper.BlockSolidPredicate coveredPred = (bx, by, bz) ->
                                !world.getBlockState(mp.set(bx, by, bz)).canBeReplaced()
                                        || core.isBlockedForBedDefense(bx, by, bz);
                        homeBedDefRatio = BedwarsTacticalHelper.computeBedShellCoverageRatio(
                                bed.x(), bed.y(), bed.z(),
                                bed.footX(), bed.footY(), bed.footZ(),
                                coveredPred
                        );
                        if (homeBedDefRatio >= 0.85) {
                            homeBedOuterDefRatio = BedwarsTacticalHelper.computeBedOuterLayerCoverageRatio(
                                    bed.x(), bed.y(), bed.z(),
                                    bed.footX(), bed.footY(), bed.footZ(),
                                    coveredPred
                            );
                        }
                    }
                } else if (!bed.destroyed()) {
                    livingEnemyBeds++;
                    double dx = (bed.x() + 0.5) - pos.x;
                    double dy = (bed.y() + 0.5) - pos.y;
                    double dz = (bed.z() + 0.5) - pos.z;
                    double d = Math.sqrt(dx * dx + dy * dy + dz * dz);
                    double breakSec = estimateQuickBedBreakSec(memory, core, bed, toolProfile);
                    if (breakSec < weakestBedBreakSec || (Math.abs(breakSec - weakestBedBreakSec) < 0.15 && d < weakestBedDist)) {
                        weakestBedBreakSec = breakSec;
                        weakestBedDist = d;
                        weakestBedX = bed.x();
                        weakestBedY = bed.y();
                        weakestBedZ = bed.z();
                        haveWeakestBed = true;
                    }
                    if (d < nearestBedDist) {
                        nearestBedDist = d;
                        double[] sc = BrainSchema.relativeBearingSinCos(dx, dz, yaw);
                        nearestBedSin = sc[0];
                        nearestBedCos = sc[1];
                        nearestBedBreakSec = breakSec;
                        nearestBedX = bed.x();
                        nearestBedY = bed.y();
                        nearestBedZ = bed.z();
                        haveNearestBed = true;
                    }
                }
            }

            if (!haveHomeAnchor) {
                SpawnPOI[] spawns = core.getSpawnsSnapshot();
                for (int sIdx = 0; sIdx < spawns.length; sIdx++) {
                    SpawnPOI sp = spawns[sIdx];
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

            GenPOI[] gens = core.getGensSnapshot();
            for (int gIdx = 0; gIdx < gens.length; gIdx++) {
                GenPOI gen = gens[gIdx];
                double dx = (gen.x() + 0.5) - pos.x;
                double dy = (gen.y() + 0.5) - pos.y;
                double dz = (gen.z() + 0.5) - pos.z;
                double dSq = dx * dx + dy * dy + dz * dz;
                if (gen.type() == GenType.DIAMOND && dSq < diamondDist * diamondDist) {
                    diamondDist = Math.sqrt(dSq);
                    double[] sc = BrainSchema.relativeBearingSinCos(dx, dz, yaw);
                    diamondSin = sc[0];
                    diamondCos = sc[1];
                    diamondX = gen.x();
                    diamondY = gen.y();
                    diamondZ = gen.z();
                    haveDiamond = true;
                } else if (gen.type() == GenType.EMERALD && dSq < emeraldDist * emeraldDist) {
                    emeraldDist = Math.sqrt(dSq);
                    double[] sc = BrainSchema.relativeBearingSinCos(dx, dz, yaw);
                    emeraldSin = sc[0];
                    emeraldCos = sc[1];
                    emeraldX = gen.x();
                    emeraldY = gen.y();
                    emeraldZ = gen.z();
                    haveEmerald = true;
                } else if (gen.type() == GenType.HOME_FORGE && dSq <= 81.0) {
                    nearShopOrForge = true;
                }
            }

            ShopNPC[] npcs = core.getShopNpcsSnapshot();
            for (int nIdx = 0; nIdx < npcs.length; nIdx++) {
                ShopNPC npc = npcs[nIdx];
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
            weakestBedDist = 128.0;
        }
        if (homeBedAlive && homeBedDist <= 14.0) {
            nearShopOrForge = true;
        }

        long nowMs = System.currentTimeMillis();
        boolean trapTriggeredRecently = matchState != null && matchState.wasTrapTriggeredRecently(nowMs, 12_000L);
        boolean enemyNearHome = trapTriggeredRecently && homeBedAlive;

        // Zero-allocation 3-slot primitive top-K threat scan (replaces ArrayList + sort!)
        double topD0 = Double.POSITIVE_INFINITY, topD1 = Double.POSITIVE_INFINITY, topD2 = Double.POSITIVE_INFINITY;
        ThreatFeature topF0 = null, topF1 = null, topF2 = null;
        Player nearestThreatPlayer = null;
        double nearestBedlessEnemyDist = 99.0;

        List<Player> enemyList = tickCtx != null ? tickCtx.enemies() : List.of();
        for (int eIdx = 0, en = enemyList.size(); eIdx < en; eIdx++) {
            Player other = enemyList.get(eIdx);
            if (tickCtx != null && tickCtx.isEnemyDoomedToVoid(other)) {
                continue;
            }
            String rawName = other.getName().getString();
            String cleanName = BedwarsArenaMemory.stripColors(rawName).toUpperCase(Locale.ROOT);
            if (cleanName.contains("[NPC]") || cleanName.contains("SHOP") || cleanName.contains("UPGRADE")) {
                continue;
            }
            double dx = other.getX() - pos.x;
            double dy = other.getY() - pos.y;
            double dz = other.getZ() - pos.z;
            double dSq = dx * dx + dy * dy + dz * dz;
            Vec3 ov = other.getDeltaMovement();

            // Detect nearby bedless (Final Kill!) enemies using MatchState + ArenaCore team bed status
            if (dSq < nearestBedlessEnemyDist * nearestBedlessEnemyDist && matchState != null) {
                var et = matchState.getPlayerTeam(rawName);
                if (et != null && et != BedwarsMapCore.TeamColor.UNKNOWN) {
                    boolean bedGone = matchState.isTeamBedDestroyed(et);
                    if (!bedGone && core != null) {
                        BedPOI[] beds = core.getBedsSnapshot();
                        for (int bi = 0; bi < beds.length; bi++) {
                            if (beds[bi].team() == et && beds[bi].destroyed()) {
                                bedGone = true;
                                break;
                            }
                        }
                    }
                    if (bedGone) {
                        nearestBedlessEnemyDist = Math.sqrt(dSq);
                    }
                }
            }

            if (haveHomeAnchor && homeBedAlive && !enemyNearHome) {
                double hx = other.getX() - homePosX;
                double hy = other.getY() - homePosY;
                double hz = other.getZ() - homePosZ;
                double homeDistSq = hx * hx + hy * hy + hz * hz;
                double hHoriz = Math.sqrt(hx * hx + hz * hz);
                double closingHome = 0.0;
                double facingHomeDot = 0.0;
                if (hHoriz > 1e-4) {
                    double uxHome = -(hx / hHoriz);
                    double uzHome = -(hz / hHoriz);
                    closingHome = (ov.x * uxHome) + (ov.z * uzHome);
                    Vec3 lookVec = other.getLookAngle();
                    double lookHoriz = Math.hypot(lookVec.x, lookVec.z);
                    if (lookHoriz > 1e-4) {
                        facingHomeDot = (lookVec.x / lookHoriz) * uxHome + (lookVec.z / lookHoriz) * uzHome;
                    }
                }
                boolean holdingBlocks = other.getMainHandItem().getItem() instanceof net.minecraft.world.item.BlockItem;
                if (isIncomingHomeRusher(homeDistSq, hy, closingHome, facingHomeDot, holdingBlocks)) {
                    enemyNearHome = true;
                }
            }

            if (dSq > 64.0 * 64.0) {
                continue;
            }
            double d = Math.sqrt(dSq);
            if (d >= topD2) {
                continue;
            }
            double[] sc = BrainSchema.relativeBearingSinCos(dx, dz, yaw);
            double horizD = Math.max(1e-4, Math.sqrt(dx * dx + dz * dz));
            double closing = -(((ov.x - vel.x) * (dx / horizD)) + ((ov.z - vel.z) * (dz / horizD))) / 0.45;
            double danger = Math.max(0.0, Math.min(1.0, (1.0 - d / 24.0) * 0.72 + Math.max(0.0, closing) * 0.28));
            ThreatFeature tf = new ThreatFeature(true, d, sc[0], sc[1], closing, danger);

            if (d < topD0) {
                topD2 = topD1; topF2 = topF1;
                topD1 = topD0; topF1 = topF0;
                topD0 = d; topF0 = tf;
                nearestThreatPlayer = other;
            } else if (d < topD1) {
                topD2 = topD1; topF2 = topF1;
                topD1 = d; topF1 = tf;
            } else {
                topD2 = d; topF2 = tf;
            }
        }

        List<ThreatFeature> threats = new ArrayList<>(BrainSchema.MAX_TRACKED_THREATS);
        if (topF0 != null) threats.add(topF0);
        if (topF1 != null) threats.add(topF1);
        if (topF2 != null) threats.add(topF2);

        // Compute deterministic Fight Win Probability against nearest enemy (using live or last-seen gear + void exposure)
        int sharpTier = matchState != null ? matchState.getSharpnessTier() : 0;
        int protTier = matchState != null ? matchState.getProtectionTier() : 0;
        double fightWinProb = 0.55;
        if (nearestThreatPlayer != null) {
            double enemyArmor = BedwarsTacticalHelper.computePlayerArmorPoints(nearestThreatPlayer);
            ItemStack emh = nearestThreatPlayer.getMainHandItem();
            int enemySword = emh.isEmpty() ? 1 : Math.max(1, BedwarsTacticalHelper.swordTierFromItem(emh.getItem()));
            double ourVoidExp = core != null ? core.sampleVoidExposure((int) Math.floor(pos.x), (int) Math.floor(pos.z)) : voidSurroundFrac;
            double enemyVoidExp = core != null ? core.sampleVoidExposure(nearestThreatPlayer.getBlockX(), nearestThreatPlayer.getBlockZ()) : 0.25;
            FightEvaluation fe = BedwarsTacticalHelper.evaluateFightWinProbability(
                    p.getHealth() + p.getAbsorptionAmount(),
                    inv.armorPoints() + protTier * 1.6,
                    Math.max(1, inv.swordTier()),
                    sharpTier,
                    inv.gapples(),
                    ourVoidExp,
                    nearestThreatPlayer.getHealth() + nearestThreatPlayer.getAbsorptionAmount(),
                    enemyArmor,
                    enemySword,
                    0,
                    enemyVoidExp,
                    inv.diamonds(),
                    inv.emeralds(),
                    inv.gold(),
                    homeBedAlive
            );
            fightWinProb = fe.winProbability();
        }

        int diamondTier = matchState != null ? matchState.getDiamondTier() : 1;
        int emeraldTier = matchState != null ? matchState.getEmeraldTier() : 1;
        int forgeTier = matchState != null ? matchState.getForgeTier() : 0;
        int activeTrapCount = matchState != null ? matchState.getActiveTrapCount() : 0;
        int respawningEnemies = matchState != null ? matchState.countRespawningEnemies(nowMs) : 0;
        double diamondGenSpawnSec = matchState != null ? matchState.getMinGenSpawnRemainingSec(GenType.DIAMOND, nowMs) : -1.0;
        double emeraldGenSpawnSec = matchState != null ? matchState.getMinGenSpawnRemainingSec(GenType.EMERALD, nowMs) : -1.0;

        // Connected-bridge path-aware reachability (handles straight, curved, diagonal, and L-shaped bridges + <=3-block jumps)
        int blocksNeededHome = -1;
        double walkDistHome = -1.0;
        int blocksNeededNearestBed = -1;
        double walkDistNearestBed = -1.0;
        int blocksNeededWeakestBed = -1;
        double walkDistWeakestBed = -1.0;
        int blocksNeededDiamond = -1;
        double walkDistDiamond = -1.0;
        int blocksNeededEmerald = -1;
        double walkDistEmerald = -1.0;

        if (core != null && (haveHomeAnchor || haveNearestBed || haveWeakestBed || haveDiamond || haveEmerald)) {
            BedwarsBridgeConnectivity.ColumnGroundProbe probe = BedwarsBridgeConnectivity.fromSolidProbe(
                    (x, y, z) -> !world.getBlockState(mp.set(x, y, z)).canBeReplaced(),
                    (x, y, z) -> world.isLoaded(mp.set(x, y, z))
            );
            int px = (int) Math.floor(pos.x);
            int py = (int) Math.floor(pos.y);
            int pz = (int) Math.floor(pos.z);
            BedwarsBridgeConnectivity.Workspace ws = tickCtx != null
                    ? tickCtx.getOrSolveBridgeConnectivity(world.getGameTime(), px, py, pz, probe)
                    : solveOneOffBridgeConnectivity(px, py, pz, probe);
            if (haveHomeAnchor) {
                var r = BedwarsBridgeConnectivity.estimateRouteTo(ws,
                        (int) Math.floor(homePosX), (int) Math.floor(homePosY), (int) Math.floor(homePosZ));
                blocksNeededHome = r.blocksNeeded();
                walkDistHome = r.walkDistBlocks();
            }
            if (haveNearestBed) {
                var r = BedwarsBridgeConnectivity.estimateRouteTo(ws, nearestBedX, nearestBedY, nearestBedZ);
                blocksNeededNearestBed = r.blocksNeeded();
                walkDistNearestBed = r.walkDistBlocks();
            }
            if (haveWeakestBed) {
                if (haveNearestBed && weakestBedX == nearestBedX && weakestBedY == nearestBedY && weakestBedZ == nearestBedZ) {
                    blocksNeededWeakestBed = blocksNeededNearestBed;
                    walkDistWeakestBed = walkDistNearestBed;
                } else {
                    var r = BedwarsBridgeConnectivity.estimateRouteTo(ws, weakestBedX, weakestBedY, weakestBedZ);
                    blocksNeededWeakestBed = r.blocksNeeded();
                    walkDistWeakestBed = r.walkDistBlocks();
                }
            }
            if (haveDiamond) {
                var r = BedwarsBridgeConnectivity.estimateRouteTo(ws, diamondX, diamondY, diamondZ);
                blocksNeededDiamond = r.blocksNeeded();
                walkDistDiamond = r.walkDistBlocks();
            }
            if (haveEmerald) {
                var r = BedwarsBridgeConnectivity.estimateRouteTo(ws, emeraldX, emeraldY, emeraldZ);
                blocksNeededEmerald = r.blocksNeeded();
                walkDistEmerald = r.walkDistBlocks();
            }
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
                enemyNearHome,
                weakestBedDist,
                fightWinProb,
                diamondTier,
                emeraldTier,
                sharpTier,
                protTier,
                forgeTier,
                activeTrapCount,
                trapTriggeredRecently,
                respawningEnemies,
                diamondGenSpawnSec,
                emeraldGenSpawnSec,
                homeBedOuterDefRatio,
                nearestBedlessEnemyDist,
                blocksNeededHome,
                walkDistHome,
                blocksNeededNearestBed,
                walkDistNearestBed,
                blocksNeededWeakestBed,
                walkDistWeakestBed,
                blocksNeededDiamond,
                walkDistDiamond,
                blocksNeededEmerald,
                walkDistEmerald
        );
    }

    private static BedwarsBridgeConnectivity.Workspace solveOneOffBridgeConnectivity(
            int px, int py, int pz, BedwarsBridgeConnectivity.ColumnGroundProbe probe
    ) {
        BedwarsBridgeConnectivity.Workspace ws = new BedwarsBridgeConnectivity.Workspace();
        BedwarsBridgeConnectivity.solve(ws, px, py, pz, probe);
        return ws;
    }

    private static double estimateQuickBedBreakSec(
            BedwarsArenaMemory memory, BedwarsMapCore core, BedPOI bed, ToolProfile tools
    ) {
        CachedBedDefenseProfile profile = memory.getOrScanBedDefense(core, bed, tools);
        return profile == null ? 2.0 : Math.max(0.2, Math.min(15.0, profile.shellScan().estimatedClearTicks() / 20.0));
    }

    private static double probeCeilingClearance(
            Level world, BedwarsMapCore core, int fx, int fy, int fz, BlockPos.MutableBlockPos mp
    ) {
        for (int dy = 2; dy <= 48; dy++) {
            int y = fy + dy;
            if (core != null) {
                if (core.isKnownSolidBitset(fx, y, fz)) {
                    return dy - 1;
                }
                if (core.isKnownAirInScannedChunk(fx, y, fz)) {
                    continue;
                }
            }
            if (!world.getBlockState(mp.set(fx, y, fz)).canBeReplaced()) {
                return dy - 1;
            }
        }
        return 48.0;
    }

    public static double probeFrontVoidEdgeDist(Level world, Vec3 pos, float yawDeg) {
        return probeFrontVoidEdgeDist(world, null, pos, yawDeg, new BlockPos.MutableBlockPos());
    }

    public static double probeFrontVoidEdgeDist(
            Level world, BedwarsMapCore core, Vec3 pos, float yawDeg, BlockPos.MutableBlockPos mp
    ) {
        double rad = Math.toRadians(yawDeg);
        double dirX = -Math.sin(rad);
        double dirZ = Math.cos(rad);
        int feetY = (int) Math.floor(pos.y);
        for (double dist = 0.75; dist <= 16.0; dist += 0.75) {
            int bx = (int) Math.floor(pos.x + dirX * dist);
            int bz = (int) Math.floor(pos.z + dirZ * dist);
            if (!hasGroundSupport(world, core, bx, feetY, bz, mp)) {
                return dist;
            }
        }
        return 16.0;
    }

    public static double probeVoidSurroundFraction(Level world, Vec3 pos) {
        return probeVoidSurroundFraction(world, null, pos, new BlockPos.MutableBlockPos());
    }

    public static double probeVoidSurroundFraction(
            Level world, BedwarsMapCore core, Vec3 pos, BlockPos.MutableBlockPos mp
    ) {
        int feetY = (int) Math.floor(pos.y);
        int voidDirs = 0;
        for (int k = 0; k < 8; k++) {
            int bx = (int) Math.floor(pos.x + SURROUND_COS[k] * 2.0);
            int bz = (int) Math.floor(pos.z + SURROUND_SIN[k] * 2.0);
            if (!hasGroundSupport(world, core, bx, feetY, bz, mp)) {
                voidDirs++;
            }
        }
        return voidDirs / 8.0;
    }

    private static boolean hasGroundSupport(
            Level world, BedwarsMapCore core, int x, int feetY, int z, BlockPos.MutableBlockPos mp
    ) {
        for (int dy = 0; dy >= -3; dy--) {
            int y = feetY + dy;
            if (core != null) {
                if (core.isKnownSolidBitset(x, y, z)) {
                    return true;
                }
                if (core.isKnownAirInScannedChunk(x, y, z)) {
                    continue;
                }
            }
            if (!world.getBlockState(mp.set(x, y, z)).canBeReplaced()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Pure discriminator for incoming base rushers, catching crouched/ninja bridgers within 36m
     * moving or facing toward home bed even when closing speed is below sprint speed (~0.04 b/t).
     */
    public static boolean isIncomingHomeRusher(
            double homeDistSq,
            double enemyDyAboveHome,
            double closingHomeSpeed,
            double facingHomeDot,
            boolean holdingBlocks
    ) {
        if (homeDistSq <= 484.0) { // <= 22m
            return true;
        }
        if (homeDistSq > 1296.0) { // > 36m
            return false;
        }
        return closingHomeSpeed >= 0.035
                || (enemyDyAboveHome >= -2.0 && holdingBlocks && facingHomeDot >= 0.55 && closingHomeSpeed >= 0.015);
    }
}
