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
import baritone.utils.BlockStateInterface;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Converts per-tick motion/input/rotation captures from AutoTest and Mirror Mode runs into
 * vivid, scene-by-scene literary diagnostics (wall scrapes with coordinates & duration,
 * backtracks, jump arcs, bridge cadence, camera snap spikes, frozen-gaze plateaus, and
 * side-by-side Bot-vs-Player choreography comparisons).
 */
public final class StageNarrativeAnalyzer {

    private StageNarrativeAnalyzer() {}

    public record TickFrame(
            int tick,
            double x,
            double y,
            double z,
            double vx,
            double vy,
            double vz,
            float yaw,
            float pitch,
            boolean onGround,
            boolean horizontalCollision,
            boolean sprinting,
            boolean sneaking,
            boolean fwd,
            boolean back,
            boolean left,
            boolean right,
            boolean jump,
            boolean leftDown,
            boolean rightDown,
            boolean rightClicked,
            boolean brokeBlock,
            int slot,
            String wallBlockHint
    ) {}

    public record NarrativeStats(
            double durationSec,
            int ticks,
            double p50Aim,
            double p90Aim,
            double p99Aim,
            double maxAim,
            int snapsOver30,
            int snapsOver38,
            int maxFrozenRun,
            int scrapeCount,
            int scrapeTicksTotal,
            int backtrackCount,
            int airArcCount,
            double widestArcDist,
            int midAirWhips,
            double minY,
            double peakHorizSpeed,
            double sprintFraction,
            double strafeFraction,
            double meanMoveLookDiffDeg,
            int placements,
            int breaks
    ) {}

    public record RunNarrative(
            String actorLabel,
            String headline,
            List<String> sceneLines,
            NarrativeStats stats
    ) {}

    private record Episode(int startTick, int endTick, int startIdx, int endIdx, double value, String label) {}

    public static String probeTouchingWallBlock(IPlayerContext ctx) {
        if (ctx == null || ctx.player() == null || !ctx.player().horizontalCollision) {
            return null;
        }
        AABB box = ctx.player().getBoundingBox();
        int minX = (int) Math.floor(box.minX - 0.16);
        int maxX = (int) Math.floor(box.maxX + 0.16);
        int minZ = (int) Math.floor(box.minZ - 0.16);
        int maxZ = (int) Math.floor(box.maxZ + 0.16);
        int footY = (int) Math.floor(box.minY + 0.25);
        int headY = (int) Math.floor(box.minY + 1.25);

        Map<String, Integer> counts = new HashMap<>();
        for (int y : new int[]{footY, headY}) {
            for (int x = minX; x <= maxX; x++) {
                for (int z = minZ; z <= maxZ; z++) {
                    if (x >= (int) Math.floor(box.minX + 0.02) && x <= (int) Math.floor(box.maxX - 0.02)
                            && z >= (int) Math.floor(box.minZ + 0.02) && z <= (int) Math.floor(box.maxZ - 0.02)) {
                        continue;
                    }
                    BlockState state = BlockStateInterface.get(ctx, new BlockPos(x, y, z));
                    if (!state.isAir() && state.isSolid()) {
                        String name = BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
                        counts.merge(name, 1, Integer::sum);
                    }
                }
            }
        }
        if (counts.isEmpty()) {
            return "solid wall";
        }
        return counts.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(e -> e.getKey().replace('_', ' '))
                .orElse("solid wall");
    }

    public static RunNarrative analyzeRun(
            String actorLabel,
            boolean combatStage,
            boolean parkourBridgeStage,
            Vec3 startPos,
            Vec3 goalPos,
            List<TickFrame> frames
    ) {
        if (frames == null || frames.isEmpty()) {
            NarrativeStats emptyStats = new NarrativeStats(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
            return new RunNarrative(actorLabel, actorLabel + ": no movement telemetry captured.", List.of(), emptyStats);
        }

        int n = frames.size();
        double durationSec = n / 20.0;
        List<Double> aimSteps = new ArrayList<>(Math.max(1, n - 1));
        List<Episode> snapEvents = new ArrayList<>();
        List<Episode> midAirWhips = new ArrayList<>();

        int snaps30 = 0;
        int snaps38 = 0;
        int curFrozenRun = 0;
        int frozenStartIdx = -1;
        List<Episode> frozenRuns = new ArrayList<>();
        int maxFrozenRun = 0;

        int launchIdx = -1;
        int sprintStartIdx = -1;
        int sprintTicks = 0;
        int strafeTicks = 0;
        int sneakTicks = 0;
        int jumpPresses = 0;
        int placements = 0;
        int breaks = 0;
        double peakSpeed = 0.0;
        double minY = frames.get(0).y();
        double moveLookSum = 0.0;
        int moveLookCount = 0;

        List<Episode> scrapeEpisodes = new ArrayList<>();
        int curScrapeStart = -1;
        int scrapeGap = 0;

        List<Episode> airArcs = new ArrayList<>();
        int curAirStart = -1;

        List<String> slotSwaps = new ArrayList<>();
        int prevSlot = frames.get(0).slot();

        double minGoalDist = goalPos != null ? horizDist(frames.get(0), goalPos) : 0.0;
        int backtrackStart = -1;
        double worstBacktrackGain = 0.0;
        List<Episode> backtracks = new ArrayList<>();

        for (int i = 0; i < n; i++) {
            TickFrame f = frames.get(i);
            double hSpeed = Math.hypot(f.vx(), f.vz());
            peakSpeed = Math.max(peakSpeed, hSpeed);
            minY = Math.min(minY, f.y());

            boolean anyMove = f.fwd() || f.back() || f.left() || f.right();
            if (launchIdx < 0 && (anyMove || hSpeed > 0.07)) {
                launchIdx = i;
            }
            if (f.sprinting() || hSpeed > 0.215) {
                sprintTicks++;
                if (sprintStartIdx < 0) {
                    sprintStartIdx = i;
                }
            }
            if (f.left() || f.right()) {
                strafeTicks++;
            }
            if (f.sneaking()) {
                sneakTicks++;
            }
            if (f.jump() && (i == 0 || !frames.get(i - 1).jump())) {
                jumpPresses++;
            }
            if (f.rightClicked()) {
                placements++;
            }
            if (f.brokeBlock()) {
                breaks++;
            }
            if (f.slot() != prevSlot) {
                if (slotSwaps.size() < 6) {
                    slotSwaps.add(String.format(Locale.ROOT, "t=%d (%d->%d)", f.tick(), prevSlot, f.slot()));
                }
                prevSlot = f.slot();
            }

            if (hSpeed > 0.08) {
                float velYaw = PathSmoother.computeYawTo(0.0, 0.0, f.vx(), f.vz());
                double off = Math.abs(Rotation.normalizeYaw(f.yaw() - velYaw));
                moveLookSum += off;
                moveLookCount++;

                if (!f.onGround() && !combatStage && !f.leftDown() && !f.rightDown() && hSpeed > 0.14 && off > 80.0) {
                    midAirWhips.add(new Episode(f.tick(), f.tick(), i, i, off,
                            String.format(Locale.ROOT, "yaw=%.1f pitch=%.1f (%.0f deg off flight)", f.yaw(), f.pitch(), off)));
                }
            }

            // Aim step analysis & frozen plateau tracking
            if (i > 0) {
                TickFrame prev = frames.get(i - 1);
                float dYaw = Rotation.normalizeYaw(f.yaw() - prev.yaw());
                float dPitch = f.pitch() - prev.pitch();
                double step = Math.hypot(dYaw, dPitch);
                aimSteps.add(step);

                if (step > 30.0) {
                    snaps30++;
                    if (step > 38.0) {
                        snaps38++;
                    }
                    snapEvents.add(new Episode(f.tick(), f.tick(), i - 1, i, step,
                            String.format(Locale.ROOT, "yaw %.1f->%.1f pitch %.1f->%.1f",
                                    prev.yaw(), f.yaw(), prev.pitch(), f.pitch())));
                }

                if (step < 0.001 && hSpeed > 0.10) {
                    if (curFrozenRun == 0) {
                        frozenStartIdx = i;
                    }
                    curFrozenRun++;
                    maxFrozenRun = Math.max(maxFrozenRun, curFrozenRun);
                } else {
                    if (curFrozenRun >= 6) {
                        frozenRuns.add(new Episode(frames.get(frozenStartIdx).tick(), frames.get(i - 1).tick(),
                                frozenStartIdx, i - 1, hSpeed, "frozen gaze"));
                    }
                    curFrozenRun = 0;
                    frozenStartIdx = -1;
                }
            }

            // Wall scrape / obstruction detection (ignoring intentional mining/combat standoffs)
            boolean intentMoving = (f.fwd() || f.left() || f.right()) && !f.sneaking() && !f.leftDown() && !f.rightDown();
            boolean stalledIntoBlock = (f.horizontalCollision() && hSpeed < 0.14)
                    || (intentMoving && !combatStage && i > 4 && hSpeed < 0.055);
            if (stalledIntoBlock) {
                if (curScrapeStart < 0) {
                    curScrapeStart = i;
                }
                scrapeGap = 0;
            } else if (curScrapeStart >= 0) {
                scrapeGap++;
                if (scrapeGap > 2) {
                    int endIdx = i - scrapeGap;
                    if (endIdx - curScrapeStart + 1 >= 3) {
                        scrapeEpisodes.add(buildScrapeEpisode(frames, curScrapeStart, endIdx));
                    }
                    curScrapeStart = -1;
                    scrapeGap = 0;
                }
            }

            // Airborne jump/drop arcs
            if (!f.onGround()) {
                if (curAirStart < 0) {
                    curAirStart = i;
                }
            } else if (curAirStart >= 0) {
                if (i - curAirStart >= 4) {
                    double span = Math.hypot(f.x() - frames.get(curAirStart).x(), f.z() - frames.get(curAirStart).z());
                    airArcs.add(new Episode(frames.get(curAirStart).tick(), f.tick(), curAirStart, i, span, "air arc"));
                }
                curAirStart = -1;
            }

            // Goal backtrack check for navigation stages
            if (!combatStage && goalPos != null) {
                double d = horizDist(f, goalPos);
                if (d < minGoalDist) {
                    if (backtrackStart >= 0 && worstBacktrackGain >= 1.35 && (i - backtrackStart) >= 6) {
                        backtracks.add(new Episode(frames.get(backtrackStart).tick(), f.tick(),
                                backtrackStart, i, worstBacktrackGain, "backtrack"));
                    }
                    minGoalDist = d;
                    backtrackStart = -1;
                    worstBacktrackGain = 0.0;
                } else if (d - minGoalDist >= 0.75) {
                    if (backtrackStart < 0) {
                        backtrackStart = i;
                    }
                    worstBacktrackGain = Math.max(worstBacktrackGain, d - minGoalDist);
                }
            }
        }

        // Flush trailing open episodes
        if (curFrozenRun >= 6) {
            frozenRuns.add(new Episode(frames.get(frozenStartIdx).tick(), frames.get(n - 1).tick(),
                    frozenStartIdx, n - 1, peakSpeed, "frozen gaze"));
        }
        if (curScrapeStart >= 0 && (n - curScrapeStart) >= 3) {
            scrapeEpisodes.add(buildScrapeEpisode(frames, curScrapeStart, n - 1));
        }
        if (backtrackStart >= 0 && worstBacktrackGain >= 1.35 && (n - backtrackStart) >= 6) {
            backtracks.add(new Episode(frames.get(backtrackStart).tick(), frames.get(n - 1).tick(),
                    backtrackStart, n - 1, worstBacktrackGain, "backtrack"));
        }

        double p50 = percentile(aimSteps, 0.50);
        double p90 = percentile(aimSteps, 0.90);
        double p99 = percentile(aimSteps, 0.99);
        double maxAim = aimSteps.isEmpty() ? 0.0 : Collections.max(aimSteps);
        double sprintFrac = (double) sprintTicks / n;
        double strafeFrac = (double) strafeTicks / n;
        double meanMoveLook = moveLookCount > 0 ? moveLookSum / moveLookCount : 0.0;

        int scrapeTicksTotal = 0;
        for (Episode ep : scrapeEpisodes) {
            scrapeTicksTotal += (ep.endIdx() - ep.startIdx() + 1);
        }
        double widestArc = airArcs.stream().mapToDouble(Episode::value).max().orElse(0.0);

        List<String> scenes = new ArrayList<>();

        // Scene 1: Launch & pacing
        if (launchIdx >= 0) {
            TickFrame lf = frames.get(launchIdx);
            scenes.add(String.format(Locale.ROOT,
                    "Launch & Pace: Broke off the start pad after %.2fs (t=%d) heading yaw %.1f deg; ran %.2fs total (%dt) with %.0f%% sprinting, %.0f%% lateral strafing, peak ground speed %.2f b/t.",
                    lf.tick() / 20.0, lf.tick(), lf.yaw(), durationSec, n, sprintFrac * 100.0, strafeFrac * 100.0, peakSpeed));
        }

        // Scene 2: Wall scrapes & obstacles (vivid imagery)
        if (!scrapeEpisodes.isEmpty()) {
            for (int k = 0; k < Math.min(3, scrapeEpisodes.size()); k++) {
                Episode ep = scrapeEpisodes.get(k);
                TickFrame sf = frames.get(ep.startIdx());
                TickFrame ef = frames.get(ep.endIdx());
                int len = ep.endIdx() - ep.startIdx() + 1;
                scenes.add(String.format(Locale.ROOT,
                        "Wall Scrape #%d: Caught against [%s] near (%.1f, %.1f, %.1f) for %.2fs (%dt, t=%d..%d), slowing to %.3f b/t before pivoting yaw %.1f -> %.1f deg.",
                        k + 1, ep.label(), sf.x(), sf.y(), sf.z(), len / 20.0, len,
                        ep.startTick(), ep.endTick(), ep.value(), sf.yaw(), ef.yaw()));
            }
        } else if (!combatStage) {
            scenes.add("Wall Clearance: Crisp obstacle weaving with zero wall scrapes or corridor snags across the entire run.");
        }

        // Scene 3: Backtracks / U-turns
        if (!backtracks.isEmpty()) {
            Episode bt = backtracks.get(0);
            TickFrame bf = frames.get(bt.startIdx());
            scenes.add(String.format(Locale.ROOT,
                    "Path Hesitation: Doubled back +%.1fm away from the emerald pad near (%.1f, %.1f, %.1f) over t=%d..%d (%dt) before re-locking onto the lane.",
                    bt.value(), bf.x(), bf.y(), bf.z(), bt.startTick(), bt.endTick(), bt.endIdx() - bt.startIdx() + 1));
        }

        // Scene 4: Jumps, Bridge / Parkour & Tools
        if (parkourBridgeStage || !airArcs.isEmpty() || placements > 0 || breaks > 0) {
            double startY = startPos != null ? startPos.y : frames.get(0).y();
            double yDrop = Math.max(0.0, startY - minY);
            if (placements > 0) {
                scenes.add(String.format(Locale.ROOT,
                        "Bridge & Placement Rhythm: Triggered %d block placements with %.0f%% crouch duty over open sky (min Y=%.2f, %.2fm drop below deck; %d airborne arcs).",
                        placements, (100.0 * sneakTicks) / n, minY, yDrop, airArcs.size()));
            } else if (!airArcs.isEmpty()) {
                Episode bestArc = airArcs.stream().max((a, b) -> Double.compare(a.value(), b.value())).orElse(airArcs.get(0));
                TickFrame a0 = frames.get(bestArc.startIdx());
                TickFrame a1 = frames.get(bestArc.endIdx());
                double arcPeakY = a0.y();
                for (int j = bestArc.startIdx(); j <= bestArc.endIdx(); j++) {
                    arcPeakY = Math.max(arcPeakY, frames.get(j).y());
                }
                scenes.add(String.format(Locale.ROOT,
                        "Airborne Rhythm: Cleared %d air arcs (%d jumps); widest bound spanned %.1fm from (%.1f, %.1f, %.1f) -> (%.1f, %.1f, %.1f) cresting Y=%.1f over %.2fs.",
                        airArcs.size(), jumpPresses, bestArc.value(),
                        a0.x(), a0.y(), a0.z(), a1.x(), a1.y(), a1.z(), arcPeakY,
                        (bestArc.endIdx() - bestArc.startIdx()) / 20.0));
            }
            if (breaks > 0) {
                scenes.add(String.format(Locale.ROOT,
                        "Mining & Hotbar Flow: Shattered %d blocks%s.",
                        breaks, slotSwaps.isEmpty() ? "" : " with tool swaps at " + String.join(", ", slotSwaps)));
            }
        }

        // Scene 5: Camera Texture & Anomalies
        StringBuilder cam = new StringBuilder(String.format(Locale.ROOT,
                "Camera Texture: p50=%.2f | p90=%.1f | p99=%.1f | max=%.1f deg/t (avg move-look drift %.1f deg).",
                p50, p90, p99, maxAim, meanMoveLook));
        if (!snapEvents.isEmpty()) {
            Episode worstSnap = snapEvents.stream().max((a, b) -> Double.compare(a.value(), b.value())).orElse(snapEvents.get(0));
            TickFrame sf = frames.get(worstSnap.endIdx());
            cam.append(String.format(Locale.ROOT,
                    " [%d spikes >30 deg/t; worst %.1f deg/t at t=%d near (%.1f, %.1f, %.1f): %s].",
                    snaps30, worstSnap.value(), worstSnap.startTick(), sf.x(), sf.y(), sf.z(), worstSnap.label()));
        } else {
            cam.append(" [Zero jarring snaps >30 deg/t].");
        }
        if (!frozenRuns.isEmpty()) {
            Episode worstFreeze = frozenRuns.stream().max((a, b) -> Integer.compare(a.endIdx() - a.startIdx(), b.endIdx() - b.startIdx())).orElse(frozenRuns.get(0));
            TickFrame ff = frames.get(worstFreeze.startIdx());
            int len = worstFreeze.endIdx() - worstFreeze.startIdx() + 1;
            cam.append(String.format(Locale.ROOT,
                    " [Locked 0.00 deg/t plateau for %.2fs (%dt, t=%d..%d) near (%.1f, %.1f, %.1f) while running].",
                    len / 20.0, len, worstFreeze.startTick(), worstFreeze.endTick(), ff.x(), ff.y(), ff.z()));
        } else {
            cam.append(String.format(Locale.ROOT, " [Live wrist micro-sway maintained: longest 0.00 streak %dt].", maxFrozenRun));
        }
        if (!midAirWhips.isEmpty()) {
            Episode w = midAirWhips.get(0);
            TickFrame wf = frames.get(w.startIdx());
            cam.append(String.format(Locale.ROOT,
                    " [Mid-air head twist at t=%d over (%.1f, %.1f, %.1f): %s].",
                    w.startTick(), wf.x(), wf.y(), wf.z(), w.label()));
        }
        scenes.add(cam.toString());

        String headline = String.format(Locale.ROOT,
                "%s: %.2fs | scrapes=%d (%dt) | snaps>30=%d (max %.1f deg/t) | p50=%.2f p90=%.1f | freezeMax=%dt | arcMax=%.1fm",
                actorLabel, durationSec, scrapeEpisodes.size(), scrapeTicksTotal,
                snaps30, maxAim, p50, p90, maxFrozenRun, widestArc);

        NarrativeStats stats = new NarrativeStats(
                durationSec, n, p50, p90, p99, maxAim,
                snaps30, snaps38, maxFrozenRun,
                scrapeEpisodes.size(), scrapeTicksTotal, backtracks.size(),
                airArcs.size(), widestArc, midAirWhips.size(),
                minY, peakSpeed, sprintFrac, strafeFrac, meanMoveLook,
                placements, breaks
        );
        return new RunNarrative(actorLabel, headline, scenes, stats);
    }

    public static List<String> buildBotVsYouCommentary(String stageName, RunNarrative bot, RunNarrative you) {
        List<String> lines = new ArrayList<>();
        NarrativeStats b = bot.stats();
        NarrativeStats y = you.stats();

        String paceVerdict;
        if (b.ticks() <= 0 || y.ticks() <= 0) {
            paceVerdict = "Insufficient samples for direct pace comparison.";
        } else if (y.durationSec() + 0.35 < b.durationSec()) {
            paceVerdict = String.format(Locale.ROOT,
                    "You outran the bot by %.2fs (Bot %.2fs vs You %.2fs) with %.0f%% vs %.0f%% sprinting.",
                    b.durationSec() - y.durationSec(), b.durationSec(), y.durationSec(),
                    b.sprintFraction() * 100.0, y.sprintFraction() * 100.0);
        } else if (b.durationSec() + 0.35 < y.durationSec()) {
            paceVerdict = String.format(Locale.ROOT,
                    "Bot finished %.2fs faster (Bot %.2fs vs You %.2fs); you took tighter visual checks.",
                    y.durationSec() - b.durationSec(), b.durationSec(), y.durationSec());
        } else {
            paceVerdict = String.format(Locale.ROOT,
                    "Dead-even tempo (Bot %.2fs vs You %.2fs).",
                    b.durationSec(), y.durationSec());
        }

        String aimVerdict = String.format(Locale.ROOT,
                "Camera Texture: Bot p50=%.2f / p90=%.1f / max=%.1f deg/t (snaps>30=%d, freezeMax=%dt) vs You p50=%.2f / p90=%.1f / max=%.1f deg/t.",
                b.p50Aim(), b.p90Aim(), b.maxAim(), b.snapsOver30(), b.maxFrozenRun(),
                y.p50Aim(), y.p90Aim(), y.maxAim());

        String obstacleVerdict = String.format(Locale.ROOT,
                "Corridor Flow: Bot scraped %dx (%dt), reversed %dx, mid-air whips=%d, look-move=%.1f deg vs You scrapes=%dx, reversed=%dx, look-move=%.1f deg.",
                b.scrapeCount(), b.scrapeTicksTotal(), b.backtrackCount(), b.midAirWhips(), b.meanMoveLookDiffDeg(),
                y.scrapeCount(), y.backtrackCount(), y.meanMoveLookDiffDeg());

        lines.add(stageName + " — " + paceVerdict);
        lines.add("  " + aimVerdict);
        lines.add("  " + obstacleVerdict);
        return lines;
    }

    private static Episode buildScrapeEpisode(List<TickFrame> frames, int startIdx, int endIdx) {
        double minSpd = Double.MAX_VALUE;
        String wallName = null;
        for (int i = startIdx; i <= endIdx; i++) {
            TickFrame f = frames.get(i);
            minSpd = Math.min(minSpd, Math.hypot(f.vx(), f.vz()));
            if (wallName == null && f.wallBlockHint() != null) {
                wallName = f.wallBlockHint();
            }
        }
        return new Episode(
                frames.get(startIdx).tick(),
                frames.get(endIdx).tick(),
                startIdx,
                endIdx,
                minSpd,
                wallName != null ? wallName : "block edge"
        );
    }

    private static double horizDist(TickFrame f, Vec3 p) {
        return Math.hypot(f.x() - p.x, f.z() - p.z);
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
}
