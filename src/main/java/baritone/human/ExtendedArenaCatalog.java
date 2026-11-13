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

import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.IPlayerContext;
import baritone.human.MirrorArenaCatalog.StageDef;

import java.util.ArrayList;
import java.util.List;

import static baritone.human.MirrorArenaCatalog.*;

/**
 * Stages 11-60 of the Extreme Mirror Proving Ground across 5 specialized domains:
 * <ul>
 *   <li>11-20: Anti-cheese steering, surface traction, & spatial maze puzzles</li>
 *   <li>21-30: Extreme parkour, Neo corner wraps, ladders, ice leaps & water clutches</li>
 *   <li>31-40: Technical void bridging, diagonal ramps, pillars & aperture corridors</li>
 *   <li>41-50: Bedrock-encased excavation, water trenches & hybrid problem solving</li>
 *   <li>51-60: Multi-archetype combat gauntlets (Husks, Spiders, Skeletons, Creepers, 1.8 & Modern)</li>
 * </ul>
 */
public final class ExtendedArenaCatalog {

    private ExtendedArenaCatalog() {
    }

    public static List<StageDef> buildExtendedStages() {
        List<StageDef> list = new ArrayList<>(50);

        // =========================================================================
        // 11-20: STEERING, TRACTION & SPATIAL PUZZLES
        // =========================================================================
        list.add(s(11, "11. Sealed Hairpin Switchbacks", "STEERING",
                "Navigate a tight bedrock-walled 180-deg hairpin S-track",
                false, false, false, true, 540, 1200, 0, 0, -6, -90f, 0f, 32, 0, 6, 32, -1, 6,
                (ctx, b) -> {
                    prepSurvivalBase(ctx);
                    fill(ctx, b, -2, -1, -8, 34, 3, 8, "minecraft:bedrock");
                    fill(ctx, b, -2, 3, -8, 34, 3, 8, "minecraft:tinted_glass");
                    // Carve 3-wide hairpin lanes inside bedrock envelope
                    fill(ctx, b, -1, 0, -7, 11, 2, -5, "minecraft:air");
                    fill(ctx, b, 9, 0, -5, 11, 2, 1, "minecraft:air");
                    fill(ctx, b, 9, 0, -1, 22, 2, 1, "minecraft:air");
                    fill(ctx, b, 20, 0, -7, 22, 2, 1, "minecraft:air");
                    fill(ctx, b, 20, 0, -7, 30, 2, -5, "minecraft:air");
                    fill(ctx, b, 28, 0, -5, 30, 2, 7, "minecraft:air");
                    fill(ctx, b, 30, 0, 5, 33, 2, 7, "minecraft:air");
                    fill(ctx, b, 10, -1, 0, 10, -1, 0, "minecraft:sea_lantern");
                    fill(ctx, b, 21, -1, -6, 21, -1, -6, "minecraft:sea_lantern");
                    placeStartAndGoalPads(ctx, b, 0, 0, -6, 32, 0, 6);
                    tpPlayer(ctx, b, 0, 0, -6, -90f, 0f);
                }, walkStarter(32, 0, 6, false)));

        list.add(s(12, "12. Staggered Pillar Chicane", "STEERING",
                "Thread cleanly around offset pillars inside a sealed quartz gallery",
                false, false, false, true, 480, 1100, 0, 0, 0, -90f, 0f, 34, 0, 0, 34, -1, 0,
                (ctx, b) -> {
                    prepSurvivalBase(ctx);
                    fill(ctx, b, -2, -1, -4, 36, 3, 4, "minecraft:stone_bricks");
                    fill(ctx, b, -2, 3, -4, 36, 3, 4, "minecraft:tinted_glass");
                    fill(ctx, b, -1, 0, -3, 35, 2, 3, "minecraft:air");
                    int[] px = {6, 11, 16, 21, 26, 30};
                    int[] pz = {-1, 1, -1, 1, 0, -1};
                    for (int i = 0; i < px.length; i++) {
                        fill(ctx, b, px[i], 0, pz[i], px[i], 2, pz[i], "minecraft:deepslate_bricks");
                        setBlock(ctx, b, px[i], -1, pz[i], "minecraft:sea_lantern");
                    }
                    fill(ctx, b, 14, 0, -3, 14, 2, -2, "minecraft:deepslate_bricks");
                    fill(ctx, b, 24, 0, 2, 24, 2, 3, "minecraft:deepslate_bricks");
                    placeStartAndGoalPads(ctx, b, 0, 0, 0, 34, 0, 0);
                    tpPlayer(ctx, b, 0, 0, 0, -90f, 0f);
                }, walkStarter(34, 0, 0, true)));

        list.add(s(13, "13. Blue-Ice Chute Drift", "STEERING",
                "Control high-velocity momentum slides through an ice slalom chute",
                false, false, false, true, 460, 1000, 0, 0, -3, -90f, 0f, 36, 0, 3, 36, -1, 3,
                (ctx, b) -> {
                    prepSurvivalBase(ctx);
                    fill(ctx, b, -2, -1, -6, 38, 2, 6, "minecraft:obsidian");
                    fill(ctx, b, -2, 3, -6, 38, 3, 6, "minecraft:glass");
                    fill(ctx, b, -1, 0, -5, 37, 2, 5, "minecraft:air");
                    fill(ctx, b, 2, -1, -5, 34, -1, 5, "minecraft:packed_ice");
                    fill(ctx, b, 8, -1, -2, 26, -1, 2, "minecraft:blue_ice");
                    fill(ctx, b, 10, 0, -5, 11, 2, 0, "minecraft:quartz_block");
                    fill(ctx, b, 19, 0, 0, 20, 2, 5, "minecraft:quartz_block");
                    fill(ctx, b, 28, 0, -5, 29, 2, -1, "minecraft:quartz_block");
                    placeStartAndGoalPads(ctx, b, 0, 0, -3, 36, 0, 3);
                    tpPlayer(ctx, b, 0, 0, -3, -90f, 0f);
                }, walkStarter(36, 0, 3, true)));

        list.add(s(14, "14. Soul-Sand -> Ice Snap-Course", "STEERING",
                "Adapt pacing smoothly across mud bog, ice surge & ascending steps",
                false, false, false, true, 500, 1100, 0, 0, 0, -90f, 0f, 36, 2, 0, 36, 1, 0,
                (ctx, b) -> {
                    prepSurvivalBase(ctx);
                    fill(ctx, b, -2, -1, -5, 38, 5, 5, "minecraft:stone_bricks");
                    fill(ctx, b, -1, 0, -4, 37, 4, 4, "minecraft:air");
                    fill(ctx, b, -2, 5, -5, 38, 5, 5, "minecraft:tinted_glass");
                    fill(ctx, b, 4, -1, -4, 12, -1, 4, "minecraft:soul_sand");
                    fill(ctx, b, 13, -1, -4, 24, -1, 4, "minecraft:blue_ice");
                    fill(ctx, b, 26, 0, -4, 30, 0, 4, "minecraft:stone");
                    fill(ctx, b, 31, 1, -4, 37, 1, 4, "minecraft:stone");
                    fill(ctx, b, 10, 0, -1, 11, 2, 4, "minecraft:obsidian");
                    fill(ctx, b, 20, 0, -4, 21, 2, 0, "minecraft:obsidian");
                    placeStartAndGoalPads(ctx, b, 0, 0, 0, 36, 2, 0);
                    tpPlayer(ctx, b, 0, 0, 0, -90f, 0f);
                }, walkStarter(36, 2, 0, true)));

        list.add(s(15, "15. Cobweb Snares & Needle Eye", "STEERING",
                "Weave between suspended cobweb traps without clipping the sticky silk",
                false, false, false, true, 520, 1100, 0, 0, 0, -90f, 0f, 32, 0, 0, 32, -1, 0,
                (ctx, b) -> {
                    prepSurvivalBase(ctx);
                    fill(ctx, b, -2, -1, -5, 34, 3, 5, "minecraft:deepslate_bricks");
                    fill(ctx, b, -2, 3, -5, 34, 3, 5, "minecraft:glass");
                    fill(ctx, b, -1, 0, -4, 33, 2, 4, "minecraft:air");
                    int[] wx = {5, 6, 10, 11, 15, 16, 21, 22, 26, 27};
                    for (int i = 0; i < wx.length; i++) {
                        int bias = (i % 4 < 2) ? -1 : 1;
                        fill(ctx, b, wx[i], 0, bias, wx[i], 1, bias + 2, "minecraft:cobweb");
                        fill(ctx, b, wx[i], 0, -bias - 2, wx[i], 1, -bias - 2, "minecraft:cobweb");
                    }
                    placeStartAndGoalPads(ctx, b, 0, 0, 0, 32, 0, 0);
                    tpPlayer(ctx, b, 0, 0, 0, -90f, 0f);
                }, walkStarter(32, 0, 0, false)));

        list.add(s(16, "16. 1-Wide Skywalk Ribbon", "STEERING",
                "Balance cleanly across an unwalled 1-wide twisting beam over void",
                false, true, false, false, 460, 1100, 0, 0, 0, -90f, 0f, 24, 0, 0, 24, -1, 0,
                (ctx, b) -> {
                    prepSurvivalBase(ctx);
                    fill(ctx, b, -1, -1, -1, 2, -1, 1, "minecraft:stone");
                    fill(ctx, b, 3, -1, 0, 7, -1, 0, "minecraft:quartz_block");
                    fill(ctx, b, 7, -1, -4, 7, -1, 0, "minecraft:quartz_block");
                    fill(ctx, b, 7, -1, -4, 13, -1, -4, "minecraft:quartz_block");
                    fill(ctx, b, 13, -1, -4, 13, -1, 3, "minecraft:quartz_block");
                    fill(ctx, b, 13, -1, 3, 18, -1, 3, "minecraft:quartz_block");
                    fill(ctx, b, 18, -1, 0, 18, -1, 3, "minecraft:quartz_block");
                    fill(ctx, b, 18, -1, 0, 22, -1, 0, "minecraft:quartz_block");
                    fill(ctx, b, 22, -1, -1, 25, -1, 1, "minecraft:stone");
                    placeStartAndGoalPads(ctx, b, 0, 0, 0, 24, 0, 0);
                    tpPlayer(ctx, b, 0, 0, 0, -90f, 8f);
                }, walkStarter(24, 0, 0, false)));

        list.add(s(17, "17. Corkscrew Stair Tower (+8Y)", "STEERING",
                "Sprint smoothly up an enclosed +8Y spiral stair tower",
                false, false, false, true, 520, 1100, 0, 0, -3, -90f, 0f, 7, 8, -3, 7, 7, -3,
                (ctx, b) -> {
                    prepSurvivalBase(ctx);
                    // 11x11 glass-walled tower shaft around central 3x3 bedrock column
                    fill(ctx, b, -2, -1, -5, 9, 10, 5, "minecraft:bedrock");
                    fill(ctx, b, -1, 0, -4, 8, 9, 4, "minecraft:air");
                    fill(ctx, b, -2, 10, -5, 9, 10, 5, "minecraft:tinted_glass");
                    fill(ctx, b, 2, 0, -1, 5, 9, 1, "minecraft:bedrock");
                    // Ramp steps spiraling counter-clockwise around core
                    for (int i = 0; i < 4; i++) {
                        fill(ctx, b, 2 + i, i, -4, 8, i, -2, "minecraft:stone_bricks");
                    }
                    for (int i = 0; i < 3; i++) {
                        fill(ctx, b, 6, 3 + i, -2 + i, 8, 3 + i, 4, "minecraft:stone_bricks");
                    }
                    fill(ctx, b, -1, 6, 2, 8, 6, 4, "minecraft:stone_bricks");
                    fill(ctx, b, -1, 7, -4, 2, 7, 4, "minecraft:stone_bricks");
                    fill(ctx, b, 5, 7, -4, 8, 7, -2, "minecraft:stone_bricks");
                    setBlock(ctx, b, 4, 7, -3, "minecraft:stone_bricks");
                    fill(ctx, b, 3, 7, -3, 3, 7, -3, "minecraft:stone_bricks");
                    placeStartAndGoalPads(ctx, b, 0, 0, -3, 7, 8, -3);
                    tpPlayer(ctx, b, 0, 0, -3, -90f, 0f);
                }, walkStarter(7, 8, -3, true)));

        list.add(s(18, "18. Step-Down Recovery Canyon", "STEERING",
                "Drop -4Y down canyon ledges, cross a ridge & climb back up",
                false, false, false, true, 520, 1100, 0, 0, 0, -90f, 0f, 34, 0, 0, 34, -1, 0,
                (ctx, b) -> {
                    prepSurvivalBase(ctx);
                    fill(ctx, b, -2, -5, -4, 36, 3, 4, "minecraft:stone_bricks");
                    fill(ctx, b, -2, 3, -4, 36, 3, 4, "minecraft:glass");
                    fill(ctx, b, -1, -4, -3, 35, 2, 3, "minecraft:air");
                    fill(ctx, b, -1, -1, -3, 5, -1, 3, "minecraft:stone");
                    fill(ctx, b, 6, -3, -3, 12, -3, 3, "minecraft:stone");
                    fill(ctx, b, 13, -4, -3, 21, -4, 3, "minecraft:stone");
                    fill(ctx, b, 15, -3, -1, 19, -3, 1, "minecraft:deepslate_bricks");
                    fill(ctx, b, 22, -3, -3, 24, -3, 3, "minecraft:stone");
                    fill(ctx, b, 25, -2, -3, 27, -2, 3, "minecraft:stone");
                    fill(ctx, b, 28, -1, -3, 35, -1, 3, "minecraft:stone");
                    placeStartAndGoalPads(ctx, b, 0, 0, 0, 34, 0, 0);
                    tpPlayer(ctx, b, 0, 0, 0, -90f, 0f);
                }, walkStarter(34, 0, 0, true)));

        list.add(s(19, "19. Dual-Route Choice Hall", "STEERING",
                "Choose between the cobweb wide lane or nimble zig-zag stone bridge",
                false, false, false, true, 480, 1050, 0, 0, 0, -90f, 0f, 32, 0, 0, 32, -1, 0,
                (ctx, b) -> {
                    prepSurvivalBase(ctx);
                    fill(ctx, b, -2, -3, -6, 34, 3, 6, "minecraft:deepslate_bricks");
                    fill(ctx, b, -2, 3, -6, 34, 3, 6, "minecraft:glass");
                    fill(ctx, b, -1, 0, -5, 33, 2, 5, "minecraft:air");
                    fill(ctx, b, -1, -1, -5, 4, -1, 5, "minecraft:stone");
                    fill(ctx, b, 28, -1, -5, 33, -1, 5, "minecraft:stone");
                    // Divider wall splits left mud/web lane from right crisp chicane lane
                    fill(ctx, b, 5, 0, 0, 27, 2, 0, "minecraft:obsidian");
                    fill(ctx, b, 5, -1, 1, 27, -1, 5, "minecraft:soul_sand");
                    for (int x = 8; x <= 24; x += 4) {
                        fill(ctx, b, x, 0, 1, x, 0, 5, "minecraft:cobweb");
                    }
                    fill(ctx, b, 5, -1, -5, 27, -1, -1, "minecraft:polished_deepslate");
                    fill(ctx, b, 9, 0, -5, 9, 2, -2, "minecraft:stone_bricks");
                    fill(ctx, b, 15, 0, -3, 15, 2, -1, "minecraft:stone_bricks");
                    fill(ctx, b, 21, 0, -5, 21, 2, -2, "minecraft:stone_bricks");
                    placeStartAndGoalPads(ctx, b, 0, 0, 0, 32, 0, 0);
                    tpPlayer(ctx, b, 0, 0, 0, -90f, 0f);
                }, walkStarter(32, 0, 0, true)));

        list.add(s(20, "20. Speed-Curve Pendulum Hall", "STEERING",
                "High-speed 40-block sweeping S-corridor testing pure-pursuit arcs",
                false, false, false, true, 500, 1000, 0, 0, -4, -90f, 0f, 40, 0, 4, 40, -1, 4,
                (ctx, b) -> {
                    prepSurvivalBase(ctx);
                    fill(ctx, b, -2, -1, -8, 42, 2, 8, "minecraft:stone_bricks");
                    fill(ctx, b, -2, 3, -8, 42, 3, 8, "minecraft:tinted_glass");
                    fill(ctx, b, -1, 0, -7, 13, 2, -1, "minecraft:air");
                    fill(ctx, b, 8, 0, -4, 24, 2, 4, "minecraft:air");
                    fill(ctx, b, 18, 0, -1, 34, 2, 7, "minecraft:air");
                    fill(ctx, b, 29, 0, 1, 41, 2, 7, "minecraft:air");
                    placeStartAndGoalPads(ctx, b, 0, 0, -4, 40, 0, 4);
                    tpPlayer(ctx, b, 0, 0, -4, -90f, 0f);
                }, walkStarter(40, 0, 4, false)));

        // =========================================================================
        // 21-30: EXTREME PARKOUR & MOVEMENT MECHANICS
        // =========================================================================
        list.add(s(21, "21. Rhythm Hops (+1Y / -1Y)", "PARKOUR",
                "Leap ascending & descending void plinths without losing sprint flow",
                false, true, false, false, 420, 1100, 0, 0, 0, -90f, 0f, 28, 1, 0, 28, 0, 0,
                (ctx, b) -> {
                    prepSurvivalBase(ctx);
                    fill(ctx, b, -1, -1, -1, 2, -1, 1, "minecraft:stone");
                    fill(ctx, b, 5, 0, -1, 7, 0, 1, "minecraft:quartz_block");
                    fill(ctx, b, 10, 1, -1, 12, 1, 1, "minecraft:quartz_block");
                    fill(ctx, b, 15, 0, -1, 17, 0, 1, "minecraft:quartz_block");
                    fill(ctx, b, 21, -1, -1, 23, -1, 1, "minecraft:quartz_block");
                    fill(ctx, b, 26, 0, -1, 29, 0, 1, "minecraft:stone");
                    placeStartAndGoalPads(ctx, b, 0, 0, 0, 28, 1, 0);
                    tpPlayer(ctx, b, 0, 0, 0, -90f, 0f);
                }, walkStarter(28, 1, 0, true)));

        list.add(s(22, "22. 4-Block Momentum Long-Jumps", "PARKOUR",
                "Sprint full-runways cleanly over wide 3-air (4m) void chasms",
                false, true, false, false, 420, 1100, 0, 0, 0, -90f, 0f, 27, 0, 0, 27, -1, 0,
                (ctx, b) -> {
                    prepSurvivalBase(ctx);
                    fill(ctx, b, -1, -1, -1, 5, -1, 1, "minecraft:stone");
                    // Gap x=6,7,8 (3 air = 4-block jump)
                    fill(ctx, b, 9, -1, -1, 15, -1, 1, "minecraft:stone");
                    // Gap x=16,17,18 + 1 diagonal Z shift
                    fill(ctx, b, 19, -1, 0, 22, -1, 2, "minecraft:stone");
                    fill(ctx, b, 25, -1, -1, 28, -1, 1, "minecraft:stone");
                    placeStartAndGoalPads(ctx, b, 0, 0, 0, 27, 0, 0);
                    tpPlayer(ctx, b, 0, 0, 0, -90f, 0f);
                }, walkStarter(27, 0, 0, true)));

        list.add(s(23, "23. Neo Corner-Wrap Jump", "PARKOUR",
                "Wrap around blocking obsidian pillars suspended over empty void",
                false, true, false, false, 440, 1100, 0, 0, 0, -90f, 0f, 22, 0, 0, 22, -1, 0,
                (ctx, b) -> {
                    prepSurvivalBase(ctx);
                    fill(ctx, b, -1, -1, -1, 4, -1, 1, "minecraft:stone");
                    // Center obsidian pillar blocking direct path at x=5, z=-1..1
                    fill(ctx, b, 5, -1, -1, 5, 2, 0, "minecraft:obsidian");
                    fill(ctx, b, 6, -1, -1, 11, -1, 1, "minecraft:stone");
                    // Second Neo wrapping left side (-Z)
                    fill(ctx, b, 12, -1, 0, 12, 2, 1, "minecraft:obsidian");
                    fill(ctx, b, 13, -1, -1, 17, -1, 1, "minecraft:stone");
                    fill(ctx, b, 20, -1, -1, 23, -1, 1, "minecraft:stone");
                    placeStartAndGoalPads(ctx, b, 0, 0, 0, 22, 0, 0);
                    tpPlayer(ctx, b, 0, 0, 0, -90f, 0f);
                }, walkStarter(22, 0, 0, true)));

        list.add(s(24, "24. Low-Ceiling Speed Vaults", "PARKOUR",
                "Execute crisp low-arc headhitter vaults underneath a 3.0-block ceiling canopy",
                false, true, false, false, 420, 1050, 0, 0, 0, -90f, 0f, 25, 0, 0, 25, -1, 0,
                (ctx, b) -> {
                    prepSurvivalBase(ctx);
                    fill(ctx, b, -1, 4, -2, 26, 4, 2, "minecraft:tinted_glass");
                    fill(ctx, b, -1, -1, -1, 4, -1, 1, "minecraft:stone");
                    fill(ctx, b, 7, -1, -1, 11, -1, 1, "minecraft:stone");
                    fill(ctx, b, 14, -1, -1, 18, -1, 1, "minecraft:stone");
                    fill(ctx, b, 21, -1, -1, 26, -1, 1, "minecraft:stone");
                    fill(ctx, b, 4, 3, -1, 5, 3, 1, "minecraft:stone_brick_slab[type=bottom]");
                    fill(ctx, b, 11, 3, -1, 12, 3, 1, "minecraft:stone_brick_slab[type=bottom]");
                    fill(ctx, b, 17, 3, -1, 18, 3, 1, "minecraft:stone_brick_slab[type=bottom]");
                    placeStartAndGoalPads(ctx, b, 0, 0, 0, 25, 0, 0);
                    tpPlayer(ctx, b, 0, 0, 0, -90f, 0f);
                }, walkStarter(25, 0, 0, true)));

        list.add(s(25, "25. Blue-Ice Hyper Leap", "PARKOUR",
                "Build blistering ice speed to launch across dual 4-block void gaps",
                false, true, false, false, 400, 1000, 0, 0, 0, -90f, 0f, 29, 0, 0, 29, -1, 0,
                (ctx, b) -> {
                    prepSurvivalBase(ctx);
                    fill(ctx, b, -1, -1, -1, 7, -1, 1, "minecraft:blue_ice");
                    fill(ctx, b, 11, -1, -1, 17, -1, 1, "minecraft:blue_ice");
                    fill(ctx, b, 21, -1, -1, 24, -1, 1, "minecraft:packed_ice");
                    fill(ctx, b, 27, -1, -1, 30, -1, 1, "minecraft:stone");
                    placeStartAndGoalPads(ctx, b, 0, 0, 0, 29, 0, 0);
                    tpPlayer(ctx, b, 0, 0, 0, -90f, 0f);
                }, walkStarter(29, 0, 0, true)));

        list.add(s(26, "26. Plinth-to-Plinth Stepping Stones", "PARKOUR",
                "Pinpoint 2-wide & 1-wide void landing islands across open sky",
                false, true, false, false, 460, 1200, 0, 0, 0, -90f, 0f, 28, 0, 0, 28, -1, 0,
                (ctx, b) -> {
                    prepSurvivalBase(ctx);
                    fill(ctx, b, -1, -1, -1, 2, -1, 1, "minecraft:stone");
                    fill(ctx, b, 5, -1, -2, 6, -1, -1, "minecraft:quartz_block");
                    fill(ctx, b, 9, 0, 0, 10, 0, 1, "minecraft:quartz_block");
                    fill(ctx, b, 13, 0, -2, 14, 0, -1, "minecraft:quartz_block");
                    fill(ctx, b, 17, 1, 0, 18, 1, 1, "minecraft:quartz_block");
                    fill(ctx, b, 21, 0, -1, 22, 0, 0, "minecraft:quartz_block");
                    fill(ctx, b, 26, -1, -1, 29, -1, 1, "minecraft:stone");
                    placeStartAndGoalPads(ctx, b, 0, 0, 0, 28, 0, 0);
                    tpPlayer(ctx, b, 0, 0, 0, -90f, 0f);
                }, walkStarter(28, 0, 0, true)));

        list.add(s(27, "27. Ladder-Wall Ascent & Leap", "PARKOUR",
                "Scale a vertical ladder spine, leap a void gap & land on the upper deck",
                false, false, false, false, 460, 1100, 0, 0, 0, -90f, 0f, 15, 5, 0, 15, 4, 0,
                (ctx, b) -> {
                    prepSurvivalBase(ctx);
                    fill(ctx, b, -1, -1, -1, 4, -1, 1, "minecraft:stone");
                    fill(ctx, b, 5, -1, -1, 5, 4, 1, "minecraft:stone_bricks");
                    fill(ctx, b, 4, 0, 0, 4, 4, 0, "minecraft:ladder[facing=west]");
                    fill(ctx, b, 8, 4, -1, 16, 4, 1, "minecraft:stone");
                    placeStartAndGoalPads(ctx, b, 0, 0, 0, 15, 5, 0);
                    tpPlayer(ctx, b, 0, 0, 0, -90f, 0f);
                }, walkStarter(15, 5, 0, true)));

        list.add(s(28, "28. Diamond Island Zigzag", "PARKOUR",
                "Chain continuous 45-deg diagonal island leaps left and right over void",
                false, true, false, false, 440, 1100, 0, 0, 0, -90f, 0f, 26, 0, 0, 26, -1, 0,
                (ctx, b) -> {
                    prepSurvivalBase(ctx);
                    fill(ctx, b, -1, -1, -1, 2, -1, 1, "minecraft:stone");
                    fill(ctx, b, 5, -1, 2, 7, -1, 4, "minecraft:quartz_block");
                    fill(ctx, b, 10, -1, -3, 12, -1, -1, "minecraft:quartz_block");
                    fill(ctx, b, 15, -1, 2, 17, -1, 4, "minecraft:quartz_block");
                    fill(ctx, b, 20, -1, -3, 22, -1, -1, "minecraft:quartz_block");
                    fill(ctx, b, 25, -1, -1, 28, -1, 1, "minecraft:stone");
                    placeStartAndGoalPads(ctx, b, 0, 0, 0, 26, 0, 0);
                    tpPlayer(ctx, b, 0, 0, 0, -90f, 0f);
                }, walkStarter(26, 0, 0, true)));

        list.add(s(29, "29. Water-Clutch Drop Basin (-10Y)", "PARKOUR",
                "Plunge -10 blocks cleanly into the glowing water catch pool & exit",
                false, false, false, false, 440, 1000, 0, 10, 0, -90f, 25f, 14, 0, 0, 14, -1, 0,
                (ctx, b) -> {
                    prepSurvivalBase(ctx);
                    fill(ctx, b, -1, 9, -1, 3, 9, 1, "minecraft:stone");
                    fill(ctx, b, -1, 10, -1, 1, 10, 1, "minecraft:gold_block");
                    setBlock(ctx, b, 0, 10, 0, "minecraft:air");
                    // Water pool beneath (-2 floor, -1 water) + rim + exit steps
                    fill(ctx, b, 3, -2, -3, 11, -2, 3, "minecraft:stone_bricks");
                    fill(ctx, b, 3, -1, -3, 11, -1, -3, "minecraft:stone_bricks");
                    fill(ctx, b, 3, -1, 3, 11, -1, 3, "minecraft:stone_bricks");
                    fill(ctx, b, 3, -1, -2, 3, -1, 2, "minecraft:stone_bricks");
                    fill(ctx, b, 4, -1, -2, 10, -1, 2, "minecraft:water");
                    fill(ctx, b, 11, -1, -2, 16, -1, 2, "minecraft:stone");
                    fill(ctx, b, 6, -2, 0, 8, -2, 0, "minecraft:sea_lantern");
                    placeStartAndGoalPads(ctx, b, 0, 10, 0, 14, 0, 0);
                    tpPlayer(ctx, b, 0, 10, 0, -90f, 25f);
                }, walkStarter(14, 0, 0, true)));

        list.add(s(30, "30. Ascending Canyon Stairs-Sprint", "PARKOUR",
                "Continuous uphill sprint-jumps across rising void shelves (+4Y)",
                false, true, false, false, 440, 1100, 0, 0, 0, -90f, 0f, 26, 4, 0, 26, 3, 0,
                (ctx, b) -> {
                    prepSurvivalBase(ctx);
                    fill(ctx, b, -1, -1, -1, 2, -1, 1, "minecraft:stone");
                    fill(ctx, b, 5, 0, -1, 8, 0, 1, "minecraft:stone");
                    fill(ctx, b, 11, 1, -1, 14, 1, 1, "minecraft:stone");
                    fill(ctx, b, 17, 2, -1, 20, 2, 1, "minecraft:stone");
                    fill(ctx, b, 23, 3, -1, 28, 3, 1, "minecraft:stone");
                    placeStartAndGoalPads(ctx, b, 0, 0, 0, 26, 4, 0);
                    tpPlayer(ctx, b, 0, 0, 0, -90f, 0f);
                }, walkStarter(26, 4, 0, true)));

        // =========================================================================
        // 31-40: SMART BRIDGING & VERTICAL ARCHITECTURE
        // =========================================================================
        list.add(s(31, "31. Diagonal Void Speedbridge", "BRIDGING",
                "Bridge diagonally across open air from (0,0) to (16,14)",
                false, true, false, false, 480, 1200, 0, 0, 0, -45f, 15f, 16, 0, 14, 16, -1, 14,
                (ctx, b) -> {
                    prepSurvivalBase(ctx);
                    send(ctx, "give @s minecraft:cobblestone 64");
                    send(ctx, "give @s minecraft:cobblestone 64");
                    fill(ctx, b, -2, -1, -2, 2, -1, 2, "minecraft:stone");
                    fill(ctx, b, 14, -1, 12, 18, -1, 16, "minecraft:stone");
                    placeStartAndGoalPads(ctx, b, 0, 0, 0, 16, 0, 14);
                    tpPlayer(ctx, b, 0, 0, 0, -45f, 15f);
                }, bridgeStarter(16, 0, 14, false)));

        list.add(s(32, "32. Ascend-Ramp Bridge (+6Y)", "BRIDGING",
                "Bridge across the chasm while stair-stepping +6 blocks vertically",
                false, true, false, false, 520, 1250, 0, 0, 0, -90f, 5f, 18, 6, 0, 18, 5, 0,
                (ctx, b) -> {
                    prepSurvivalBase(ctx);
                    send(ctx, "give @s minecraft:cobblestone 64");
                    send(ctx, "give @s minecraft:cobblestone 64");
                    fill(ctx, b, -2, -1, -2, 2, -1, 2, "minecraft:stone");
                    fill(ctx, b, 16, 5, -2, 20, 5, 2, "minecraft:stone");
                    placeStartAndGoalPads(ctx, b, 0, 0, 0, 18, 6, 0);
                    tpPlayer(ctx, b, 0, 0, 0, -90f, 5f);
                }, bridgeStarter(18, 6, 0, false)));

        list.add(s(33, "33. Step-Down Descending Bridge (-5Y)", "BRIDGING",
                "Drop-bridge cleanly down -5 blocks to a far low island",
                false, true, false, false, 500, 1200, 0, 5, 0, -90f, 18f, 18, 0, 0, 18, -1, 0,
                (ctx, b) -> {
                    prepSurvivalBase(ctx);
                    send(ctx, "give @s minecraft:cobblestone 64");
                    send(ctx, "give @s minecraft:cobblestone 64");
                    fill(ctx, b, -2, 4, -2, 2, 4, 2, "minecraft:stone");
                    fill(ctx, b, 16, -1, -2, 20, -1, 2, "minecraft:stone");
                    placeStartAndGoalPads(ctx, b, 0, 5, 0, 18, 0, 0);
                    tpPlayer(ctx, b, 0, 5, 0, -90f, 18f);
                }, bridgeStarter(18, 0, 0, false)));

        list.add(s(34, "34. Three-Island Void Relay", "BRIDGING",
                "Chain two separate bridges via a mid-void lantern island",
                false, true, false, false, 540, 1300, 0, 0, 0, -90f, 12f, 25, 2, -2, 25, 1, -2,
                (ctx, b) -> {
                    prepSurvivalBase(ctx);
                    send(ctx, "give @s minecraft:cobblestone 64");
                    send(ctx, "give @s minecraft:cobblestone 64");
                    fill(ctx, b, -2, -1, -2, 2, -1, 2, "minecraft:stone");
                    fill(ctx, b, 11, 0, 2, 13, 0, 5, "minecraft:sea_lantern");
                    fill(ctx, b, 23, 1, -4, 27, 1, 0, "minecraft:stone");
                    placeStartAndGoalPads(ctx, b, 0, 0, 0, 25, 2, -2);
                    tpPlayer(ctx, b, 0, 0, 0, -90f, 12f);
                }, bridgeStarter(25, 2, -2, false)));

        list.add(s(35, "35. Low-Roof Overhang Bridge", "BRIDGING",
                "Bridge straight across void underneath a low 2.5-block bedrock ceiling",
                false, true, false, true, 480, 1200, 0, 0, 0, -90f, 15f, 20, 0, 0, 20, -1, 0,
                (ctx, b) -> {
                    prepSurvivalBase(ctx);
                    send(ctx, "give @s minecraft:cobblestone 64");
                    send(ctx, "give @s minecraft:cobblestone 64");
                    fill(ctx, b, -2, -1, -2, 2, -1, 2, "minecraft:stone");
                    fill(ctx, b, 18, -1, -2, 22, -1, 2, "minecraft:stone");
                    fill(ctx, b, -2, 3, -3, 22, 3, 3, "minecraft:bedrock");
                    placeStartAndGoalPads(ctx, b, 0, 0, 0, 20, 0, 0);
                    tpPlayer(ctx, b, 0, 0, 0, -90f, 15f);
                }, bridgeStarter(20, 0, 0, false)));

        list.add(s(36, "36. Floating Arch Aperture Bridge", "BRIDGING",
                "Thread a void bridge cleanly through two narrow sky ring arches",
                false, true, false, false, 480, 1200, 0, 0, 0, -90f, 12f, 23, 0, 0, 23, -1, 0,
                (ctx, b) -> {
                    prepSurvivalBase(ctx);
                    send(ctx, "give @s minecraft:cobblestone 64");
                    send(ctx, "give @s minecraft:cobblestone 64");
                    fill(ctx, b, -2, -1, -2, 2, -1, 2, "minecraft:stone");
                    fill(ctx, b, 21, -1, -2, 25, -1, 2, "minecraft:stone");
                    for (int ax : new int[]{9, 16}) {
                        fill(ctx, b, ax, -1, -2, ax, 3, 2, "minecraft:obsidian");
                        fill(ctx, b, ax, 0, -1, ax, 2, 1, "minecraft:air");
                    }
                    placeStartAndGoalPads(ctx, b, 0, 0, 0, 23, 0, 0);
                    tpPlayer(ctx, b, 0, 0, 0, -90f, 12f);
                }, bridgeStarter(23, 0, 0, false)));

        list.add(s(37, "37. +12Y Bedrock Pillar Shaft", "BRIDGING",
                "Pillar straight up +12 blocks inside a sealed glass shaft onto Emerald pad",
                false, false, false, true, 540, 1200, 0, 0, 0, -90f, -20f, 5, 12, 0, 5, 11, 0,
                (ctx, b) -> {
                    prepSurvivalBase(ctx);
                    send(ctx, "give @s minecraft:cobblestone 64");
                    send(ctx, "give @s minecraft:cobblestone 64");
                    send(ctx, "give @s minecraft:diamond_pickaxe 1");
                    fill(ctx, b, -2, -1, -2, 2, -1, 2, "minecraft:stone");
                    fill(ctx, b, -2, 0, -3, 2, 12, -3, "minecraft:tinted_glass");
                    fill(ctx, b, -2, 0, 3, 2, 12, 3, "minecraft:tinted_glass");
                    fill(ctx, b, 3, 0, -2, 3, 10, 2, "minecraft:tinted_glass");
                    fill(ctx, b, -3, 0, -3, -3, 12, 3, "minecraft:bedrock");
                    fill(ctx, b, 3, 11, -2, 7, 11, 2, "minecraft:stone");
                    fill(ctx, b, 8, 12, -2, 8, 13, 2, "minecraft:bedrock");
                    placeStartAndGoalPads(ctx, b, 0, 0, 0, 5, 12, 0);
                    tpPlayer(ctx, b, 0, 0, 0, -90f, -20f);
                }, bridgeStarter(5, 12, 0, true)));

        list.add(s(38, "38. Bridge & Breach Doorway", "BRIDGING",
                "Bridge 12 blocks over void, then mine the stone bulkhead sealing goal",
                false, true, false, false, 480, 1200, 0, 0, 0, -90f, 12f, 21, 0, 0, 21, -1, 0,
                (ctx, b) -> {
                    prepSurvivalBase(ctx);
                    send(ctx, "give @s minecraft:cobblestone 64");
                    send(ctx, "give @s minecraft:diamond_pickaxe 1");
                    fill(ctx, b, -2, -1, -2, 2, -1, 2, "minecraft:stone");
                    fill(ctx, b, 15, -1, -3, 23, 3, 3, "minecraft:bedrock");
                    fill(ctx, b, 16, 0, -2, 22, 2, 2, "minecraft:air");
                    fill(ctx, b, 15, 0, -1, 15, 1, 1, "minecraft:cobblestone");
                    fill(ctx, b, 16, -1, -2, 22, -1, 2, "minecraft:stone");
                    placeStartAndGoalPads(ctx, b, 0, 0, 0, 21, 0, 0);
                    tpPlayer(ctx, b, 0, 0, 0, -90f, 12f);
                }, bridgeStarter(21, 0, 0, true)));

        list.add(s(39, "39. S-Bend Obstacle Void Bridge", "BRIDGING",
                "Route and bridge an S-curve around tall floating bedrock monoliths",
                false, true, false, false, 520, 1250, 0, 0, 0, -90f, 12f, 22, 0, 0, 22, -1, 0,
                (ctx, b) -> {
                    prepSurvivalBase(ctx);
                    send(ctx, "give @s minecraft:cobblestone 64");
                    send(ctx, "give @s minecraft:cobblestone 64");
                    fill(ctx, b, -2, -1, -2, 2, -1, 2, "minecraft:stone");
                    fill(ctx, b, 20, -1, -2, 24, -1, 2, "minecraft:stone");
                    fill(ctx, b, 8, -2, -1, 9, 4, 5, "minecraft:bedrock");
                    fill(ctx, b, 14, -2, -5, 15, 4, 1, "minecraft:bedrock");
                    placeStartAndGoalPads(ctx, b, 0, 0, 0, 22, 0, 0);
                    tpPlayer(ctx, b, 0, 0, 0, -90f, 12f);
                }, bridgeStarter(22, 0, 0, false)));

        list.add(s(40, "40. 32-Block Marathon Speedbridge", "BRIDGING",
                "Sustain clean speedbridge rhythm over a 32-block sky chasm",
                false, true, false, false, 560, 1400, 0, 0, 0, -90f, 15f, 37, 0, 0, 37, -1, 0,
                (ctx, b) -> {
                    prepSurvivalBase(ctx);
                    send(ctx, "give @s minecraft:cobblestone 64");
                    send(ctx, "give @s minecraft:cobblestone 64");
                    fill(ctx, b, -2, -1, -2, 2, -1, 2, "minecraft:stone");
                    fill(ctx, b, 35, -1, -2, 39, -1, 2, "minecraft:stone");
                    placeStartAndGoalPads(ctx, b, 0, 0, 0, 37, 0, 0);
                    tpPlayer(ctx, b, 0, 0, 0, -90f, 15f);
                }, bridgeStarter(37, 0, 0, false)));

        // =========================================================================
        // 41-50: EXCAVATION, SPATIAL PUZZLES & HYBRID PROBLEM SOLVING
        // =========================================================================
        list.add(s(41, "41. Bedrock Lockbox Multi-Door S-Maze", "PUZZLE",
                "Mine alternating wood, gravel, dirt & ore bulkheads around a sealed S-maze",
                false, false, false, true, 540, 1300, 0, 0, -3, -90f, 0f, 24, 0, 3, 24, -1, 3,
                (ctx, b) -> {
                    prepSurvivalBase(ctx);
                    send(ctx, "give @s minecraft:diamond_pickaxe 1");
                    send(ctx, "give @s minecraft:diamond_shovel 1");
                    send(ctx, "give @s minecraft:diamond_axe 1");
                    fill(ctx, b, -2, -2, -5, 26, 3, 5, "minecraft:bedrock");
                    fill(ctx, b, -1, 0, -4, 9, 1, -2, "minecraft:air");
                    fill(ctx, b, 7, 0, -2, 9, 1, 4, "minecraft:air");
                    fill(ctx, b, 9, 0, 2, 17, 1, 4, "minecraft:air");
                    fill(ctx, b, 15, 0, -4, 17, 1, 2, "minecraft:air");
                    fill(ctx, b, 17, 0, -4, 21, 1, -2, "minecraft:air");
                    fill(ctx, b, 19, 0, -2, 21, 1, 4, "minecraft:air");
                    fill(ctx, b, 21, 0, 2, 25, 1, 4, "minecraft:air");
                    fill(ctx, b, 4, 0, -4, 4, 1, -2, "minecraft:oak_planks");
                    fill(ctx, b, 7, 0, 0, 9, 1, 0, "minecraft:gravel");
                    fill(ctx, b, 13, 0, 2, 13, 1, 4, "minecraft:diamond_ore");
                    fill(ctx, b, 15, 0, -1, 17, 1, -1, "minecraft:dirt");
                    fill(ctx, b, 22, 0, 2, 22, 1, 4, "minecraft:cobblestone");
                    fill(ctx, b, 8, -1, 1, 8, -1, 1, "minecraft:sea_lantern");
                    fill(ctx, b, 16, -1, 0, 16, -1, 0, "minecraft:sea_lantern");
                    placeStartAndGoalPads(ctx, b, 0, 0, -3, 24, 0, 3);
                    tpPlayer(ctx, b, 0, 0, -3, -90f, 0f);
                }, mineStarter(24, 0, 3, false)));

        list.add(s(42, "42. Vertical Spiral Descent Shaft (-10Y)", "PUZZLE",
                "Excavate step-down shaft layers inside a 5x5 bedrock vault to the bottom",
                false, false, false, true, 560, 1300, 0, 10, 0, -90f, 25f, 3, 0, 0, 3, -1, 0,
                (ctx, b) -> {
                    prepSurvivalBase(ctx);
                    send(ctx, "give @s minecraft:diamond_pickaxe 1");
                    send(ctx, "give @s minecraft:diamond_shovel 1");
                    send(ctx, "give @s minecraft:diamond_axe 1");
                    fill(ctx, b, -2, -2, -2, 6, 12, 2, "minecraft:bedrock");
                    fill(ctx, b, -1, 0, -1, 5, 11, 1, "minecraft:air");
                    // Alternating solid diggable floors across -10Y
                    fill(ctx, b, -1, 9, -1, 5, 9, 1, "minecraft:oak_planks");
                    fill(ctx, b, -1, 7, -1, 5, 7, 1, "minecraft:dirt");
                    fill(ctx, b, -1, 5, -1, 5, 5, 1, "minecraft:cobblestone");
                    fill(ctx, b, -1, 3, -1, 5, 3, 1, "minecraft:gravel");
                    fill(ctx, b, -1, 1, -1, 5, 1, 1, "minecraft:oak_log");
                    fill(ctx, b, -1, 12, -1, 5, 12, 1, "minecraft:tinted_glass");
                    setBlock(ctx, b, 1, -1, 0, "minecraft:sea_lantern");
                    placeStartAndGoalPads(ctx, b, 0, 10, 0, 3, 0, 0);
                    tpPlayer(ctx, b, 0, 10, 0, -90f, 25f);
                }, mineStarter(3, 0, 0, true)));

        list.add(s(43, "43. Buried Pit Breakout (-6Y -> +6Y)", "PUZZLE",
                "Carve & pillar out of a buried gravel/cobble bedrock pit to upper Emerald",
                false, false, false, true, 520, 1250, 0, -6, 0, -90f, -20f, 7, 0, 0, 7, -1, 0,
                (ctx, b) -> {
                    prepSurvivalBase(ctx);
                    send(ctx, "give @s minecraft:diamond_pickaxe 1");
                    send(ctx, "give @s minecraft:diamond_shovel 1");
                    send(ctx, "give @s minecraft:cobblestone 64");
                    fill(ctx, b, -2, -8, -3, 9, 4, 3, "minecraft:bedrock");
                    fill(ctx, b, -1, -6, -2, 2, 2, 2, "minecraft:air");
                    // Fill pit ceiling obstruction & carve exit terrace
                    fill(ctx, b, -1, -3, -2, 2, -2, 2, "minecraft:dirt");
                    fill(ctx, b, 3, -1, -2, 8, 2, 2, "minecraft:air");
                    fill(ctx, b, 3, -2, -2, 8, -1, 2, "minecraft:stone");
                    fill(ctx, b, -2, 3, -3, 9, 3, 3, "minecraft:tinted_glass");
                    placeStartAndGoalPads(ctx, b, 0, -6, 0, 7, 0, 0);
                    tpPlayer(ctx, b, 0, -6, 0, -90f, -20f);
                }, mineStarter(7, 0, 0, true)));

        list.add(s(44, "44. Flooded Conduit & Sand Dams", "PUZZLE",
                "Excavate underwater sand/clay bulkheads inside a bedrock water canal",
                false, false, false, true, 540, 1250, 0, 0, 0, -90f, 0f, 26, 0, 0, 26, -1, 0,
                (ctx, b) -> {
                    prepSurvivalBase(ctx);
                    send(ctx, "give @s minecraft:diamond_shovel 1");
                    send(ctx, "give @s minecraft:diamond_pickaxe 1");
                    fill(ctx, b, -2, -2, -2, 28, 3, 2, "minecraft:bedrock");
                    fill(ctx, b, -1, 0, -1, 27, 2, 1, "minecraft:air");
                    fill(ctx, b, 3, 0, -1, 22, 0, 1, "minecraft:water");
                    fill(ctx, b, -2, 3, -2, 28, 3, 2, "minecraft:tinted_glass");
                    fill(ctx, b, 7, 0, -1, 7, 1, 1, "minecraft:sand");
                    fill(ctx, b, 12, 0, -1, 12, 1, 1, "minecraft:gravel");
                    fill(ctx, b, 17, 0, -1, 17, 1, 1, "minecraft:dirt");
                    placeStartAndGoalPads(ctx, b, 0, 0, 0, 26, 0, 0);
                    tpPlayer(ctx, b, 0, 0, 0, -90f, 0f);
                }, mineStarter(26, 0, 0, false)));

        list.add(s(45, "45. Keyhole Slit Corridor", "PUZZLE",
                "Punch through staggered offset keyhole slits hidden inside obsidian frames",
                false, false, false, true, 480, 1100, 0, 0, 0, -90f, 0f, 25, 0, 0, 25, -1, 0,
                (ctx, b) -> {
                    prepSurvivalBase(ctx);
                    send(ctx, "give @s minecraft:diamond_pickaxe 1");
                    send(ctx, "give @s minecraft:diamond_axe 1");
                    fill(ctx, b, -2, -2, -3, 27, 3, 3, "minecraft:bedrock");
                    fill(ctx, b, -1, 0, -2, 26, 1, 2, "minecraft:air");
                    int[] kx = {5, 10, 15, 20};
                    int[] kz = {-1, 1, -1, 0};
                    for (int i = 0; i < kx.length; i++) {
                        fill(ctx, b, kx[i], 0, -2, kx[i], 1, 2, "minecraft:obsidian");
                        fill(ctx, b, kx[i], 0, kz[i], kx[i], 1, kz[i], (i % 2 == 0) ? "minecraft:cobblestone" : "minecraft:oak_planks");
                    }
                    placeStartAndGoalPads(ctx, b, 0, 0, 0, 25, 0, 0);
                    tpPlayer(ctx, b, 0, 0, 0, -90f, 0f);
                }, mineStarter(25, 0, 0, false)));

        list.add(s(46, "46. Magma Pit Overhang Crossing", "PUZZLE",
                "Bridge across an enclosed magma-pit chasm beneath a low bedrock roof",
                false, true, false, true, 480, 1200, 0, 0, 0, -90f, 15f, 21, 0, 0, 21, -1, 0,
                (ctx, b) -> {
                    prepSurvivalBase(ctx);
                    send(ctx, "give @s minecraft:cobblestone 64");
                    fill(ctx, b, -2, -4, -3, 23, 3, 3, "minecraft:bedrock");
                    fill(ctx, b, -1, -2, -2, 22, 2, 2, "minecraft:air");
                    fill(ctx, b, -2, 3, -3, 23, 3, 3, "minecraft:tinted_glass");
                    fill(ctx, b, -1, -1, -2, 3, -1, 2, "minecraft:stone");
                    fill(ctx, b, 4, -3, -2, 16, -3, 2, "minecraft:magma_block");
                    fill(ctx, b, 17, -1, -2, 22, -1, 2, "minecraft:stone");
                    placeStartAndGoalPads(ctx, b, 0, 0, 0, 21, 0, 0);
                    tpPlayer(ctx, b, 0, 0, 0, -90f, 15f);
                }, bridgeStarter(21, 0, 0, false)));

        list.add(s(47, "47. Checkerboard Material Matrix", "PUZZLE",
                "Break cheap dirt/wood blockers while weaving past obsidian monoliths",
                false, false, false, true, 480, 1100, 0, 0, 0, -90f, 0f, 25, 0, 0, 25, -1, 0,
                (ctx, b) -> {
                    prepSurvivalBase(ctx);
                    send(ctx, "give @s minecraft:diamond_pickaxe 1");
                    send(ctx, "give @s minecraft:diamond_shovel 1");
                    send(ctx, "give @s minecraft:diamond_axe 1");
                    fill(ctx, b, -2, -2, -4, 27, 3, 4, "minecraft:bedrock");
                    fill(ctx, b, -1, 0, -3, 26, 2, 3, "minecraft:air");
                    fill(ctx, b, -2, 3, -4, 27, 3, 4, "minecraft:tinted_glass");
                    for (int x = 5; x <= 20; x += 3) {
                        for (int z = -2; z <= 2; z += 2) {
                            String blk = ((x + z) % 4 == 0) ? "minecraft:obsidian" : ((x % 2 == 0) ? "minecraft:oak_log" : "minecraft:dirt");
                            fill(ctx, b, x, 0, z, x, 2, z, blk);
                        }
                    }
                    placeStartAndGoalPads(ctx, b, 0, 0, 0, 25, 0, 0);
                    tpPlayer(ctx, b, 0, 0, 0, -90f, 0f);
                }, mineStarter(25, 0, 0, false)));

        list.add(s(48, "48. Sealed Hatches & Ladder Shaft", "PUZZLE",
                "Break ceiling wood hatch mid-climb up an enclosed bedrock ladder tower",
                false, false, false, true, 480, 1100, 0, 0, 0, -90f, -15f, 9, 4, 0, 9, 3, 0,
                (ctx, b) -> {
                    prepSurvivalBase(ctx);
                    send(ctx, "give @s minecraft:diamond_axe 1");
                    send(ctx, "give @s minecraft:diamond_pickaxe 1");
                    fill(ctx, b, -2, -2, -2, 12, 7, 2, "minecraft:bedrock");
                    fill(ctx, b, -1, 0, -1, 11, 6, 1, "minecraft:air");
                    fill(ctx, b, 5, 0, -1, 5, 3, 1, "minecraft:stone_bricks");
                    fill(ctx, b, 4, 0, 0, 4, 3, 0, "minecraft:ladder[facing=west]");
                    // Sealed wood hatch at shaft head (y=4) directly above ladder top (y=3)
                    fill(ctx, b, 3, 4, -1, 4, 4, 1, "minecraft:oak_planks");
                    fill(ctx, b, 5, 3, -1, 11, 3, 1, "minecraft:stone");
                    fill(ctx, b, -2, 7, -2, 12, 7, 2, "minecraft:tinted_glass");
                    placeStartAndGoalPads(ctx, b, 0, 0, 0, 9, 4, 0);
                    tpPlayer(ctx, b, 0, 0, 0, -90f, -15f);
                }, mineStarter(9, 4, 0, false)));

        list.add(s(49, "49. Twin-Choice Vault (Mine vs Hop)", "PUZZLE",
                "Bypass heavy deepslate bulkhead via a 2-gap jump or power-mine straight through",
                false, false, false, true, 460, 1100, 0, 0, 0, -90f, 0f, 21, 0, 0, 21, -1, 0,
                (ctx, b) -> {
                    prepSurvivalBase(ctx);
                    send(ctx, "give @s minecraft:diamond_pickaxe 1");
                    fill(ctx, b, -2, -3, -5, 23, 4, 5, "minecraft:bedrock");
                    fill(ctx, b, -1, 0, -4, 22, 3, 4, "minecraft:air");
                    fill(ctx, b, -2, 4, -5, 23, 4, 5, "minecraft:tinted_glass");
                    fill(ctx, b, -1, -1, -4, 5, -1, 4, "minecraft:stone");
                    fill(ctx, b, 15, -1, -4, 22, -1, 4, "minecraft:stone");
                    // Direct corridor: thick deepslate plug
                    fill(ctx, b, 6, -1, 1, 14, -1, 4, "minecraft:stone");
                    fill(ctx, b, 8, 0, 1, 12, 2, 4, "minecraft:deepslate_bricks");
                    // Side agility path: gap + middle plinth + cobweb pit below
                    fill(ctx, b, 6, -2, -4, 14, -2, 0, "minecraft:cobweb");
                    fill(ctx, b, 9, -1, -3, 11, -1, -1, "minecraft:quartz_block");
                    placeStartAndGoalPads(ctx, b, 0, 0, 0, 21, 0, 0);
                    tpPlayer(ctx, b, 0, 0, 0, -90f, 0f);
                }, mineStarter(21, 0, 0, false)));

        list.add(s(50, "50. Triad Escape (Mine -> Bridge -> Climb)", "PUZZLE",
                "Break locked cellar gate, bridge across void & climb steps to the summit",
                false, true, false, true, 580, 1400, 0, 0, 0, -90f, 0f, 26, 3, 0, 26, 2, 0,
                (ctx, b) -> {
                    prepSurvivalBase(ctx);
                    send(ctx, "give @s minecraft:diamond_pickaxe 1");
                    send(ctx, "give @s minecraft:diamond_axe 1");
                    send(ctx, "give @s minecraft:cobblestone 64");
                    fill(ctx, b, -2, -2, -3, 6, 3, 3, "minecraft:bedrock");
                    fill(ctx, b, -1, 0, -2, 5, 2, 2, "minecraft:air");
                    fill(ctx, b, 4, 0, -2, 4, 1, 2, "minecraft:cobblestone");
                    fill(ctx, b, 6, 0, -1, 6, 1, 1, "minecraft:oak_planks");
                    // Chasm x=7..14 -> land on x=15 staircase up to +3Y
                    fill(ctx, b, 15, -1, -2, 18, -1, 2, "minecraft:stone");
                    fill(ctx, b, 19, 0, -2, 21, 0, 2, "minecraft:stone");
                    fill(ctx, b, 22, 1, -2, 23, 1, 2, "minecraft:stone");
                    fill(ctx, b, 24, 2, -2, 28, 2, 2, "minecraft:stone");
                    fill(ctx, b, 26, 3, -1, 26, 3, 1, "minecraft:air");
                    fill(ctx, b, 25, 3, -1, 25, 4, 1, "minecraft:oak_planks");
                    placeStartAndGoalPads(ctx, b, 0, 0, 0, 26, 3, 0);
                    tpPlayer(ctx, b, 0, 0, 0, -90f, 0f);
                }, bridgeStarter(26, 3, 0, true)));

        // =========================================================================
        // 51-60: TACTICAL COMBAT & SURVIVAL GAUNTLETS
        // =========================================================================
        list.add(s(51, "51. Obsidian Colonnade 5-Husk Brawl", "COMBAT",
                "Outmaneuver & defeat 5 Husks weaving around tall obsidian cover pillars",
                true, false, false, false, 460, 1200, 0, 0, 0, -90f, 0f, 0, 0, 0, 14, 0, 0,
                (ctx, b) -> {
                    prepCombatKitAndWalls(ctx, b, false);
                    for (int x : new int[]{4, 8, 11}) {
                        fill(ctx, b, x, 0, -3, x, 2, -3, "minecraft:obsidian");
                        fill(ctx, b, x, 0, 3, x, 2, 3, "minecraft:obsidian");
                    }
                    summonTestMob(ctx, "minecraft:husk", b, 6, 0, -5);
                    summonTestMob(ctx, "minecraft:husk", b, 6, 0, 5);
                    summonTestMob(ctx, "minecraft:husk", b, 10, 0, -6);
                    summonTestMob(ctx, "minecraft:husk", b, 10, 0, 6);
                    summonTestMob(ctx, "minecraft:husk", b, 12, 0, 0);
                    tpPlayer(ctx, b, 0, 0, 0, -90f, 0f);
                }, modernCombatStarter()));

        list.add(s(52, "52. 1.8 Hyper-Blitz 6-Husk Swarm", "COMBAT",
                "Full 1.8 attack speed: chain high-CPS W-taps vs 6 relentless Husks",
                true, false, true, false, 420, 1200, 0, 0, 0, -90f, 0f, 0, 0, 0, 14, 0, 0,
                (ctx, b) -> {
                    prepCombatKitAndWalls(ctx, b, true);
                    summonTestMob(ctx, "minecraft:husk", b, 5, 0, -6);
                    summonTestMob(ctx, "minecraft:husk", b, 5, 0, 6);
                    summonTestMob(ctx, "minecraft:husk", b, 9, 0, -5);
                    summonTestMob(ctx, "minecraft:husk", b, 9, 0, 5);
                    summonTestMob(ctx, "minecraft:husk", b, 12, 0, -3);
                    summonTestMob(ctx, "minecraft:husk", b, 12, 0, 3);
                    tpPlayer(ctx, b, 0, 0, 0, -90f, 0f);
                }, classic18Starter()));

        list.add(s(53, "53. Spider Ambush Web Arena", "COMBAT",
                "Track fast, low-profile Spiders darting past scattered cobweb traps",
                true, false, false, false, 440, 1200, 0, 0, 0, -90f, 0f, 0, 0, 0, 14, 0, 0,
                (ctx, b) -> {
                    prepCombatKitAndWalls(ctx, b, false);
                    fill(ctx, b, 5, 0, -2, 5, 0, -2, "minecraft:cobweb");
                    fill(ctx, b, 5, 0, 2, 5, 0, 2, "minecraft:cobweb");
                    fill(ctx, b, 9, 0, 0, 9, 0, 0, "minecraft:cobweb");
                    summonTestMob(ctx, "minecraft:spider", b, 7, 0, -5);
                    summonTestMob(ctx, "minecraft:spider", b, 7, 0, 5);
                    summonTestMob(ctx, "minecraft:spider", b, 11, 0, -3);
                    summonTestMob(ctx, "minecraft:spider", b, 11, 0, 3);
                    tpPlayer(ctx, b, 0, 0, 0, -90f, 5f);
                }, modernCombatStarter()));

        list.add(s(54, "54. Skeleton Sniper Cover Arena", "COMBAT",
                "Push stone cover walls under tinted-glass canopy to slay 3 Skeletons",
                true, false, false, true, 460, 1200, 0, 0, 0, -90f, 0f, 0, 0, 0, 14, 0, 0,
                (ctx, b) -> {
                    prepCombatKitAndWalls(ctx, b, false);
                    fill(ctx, b, -2, 4, -8, 14, 4, 8, "minecraft:tinted_glass");
                    fill(ctx, b, 4, 0, -2, 4, 2, 2, "minecraft:stone_bricks");
                    fill(ctx, b, 8, 0, -5, 8, 2, -2, "minecraft:stone_bricks");
                    fill(ctx, b, 8, 0, 2, 8, 2, 5, "minecraft:stone_bricks");
                    summonTestMob(ctx, "minecraft:skeleton", b, 11, 0, -5);
                    summonTestMob(ctx, "minecraft:skeleton", b, 12, 0, 0);
                    summonTestMob(ctx, "minecraft:skeleton", b, 11, 0, 5);
                    tpPlayer(ctx, b, 0, 0, 0, -90f, 0f);
                }, modernCombatStarter()));

        list.add(s(55, "55. Creeper Defusal & Kite Duel", "COMBAT",
                "Slay 3 Creepers using smart hit-and-sprint-retreat before they detonate",
                true, false, false, false, 440, 1200, 0, 0, 0, -90f, 0f, 0, 0, 0, 14, 0, 0,
                (ctx, b) -> {
                    prepCombatKitAndWalls(ctx, b, false);
                    summonTestMob(ctx, "minecraft:creeper", b, 7, 0, -4);
                    summonTestMob(ctx, "minecraft:creeper", b, 9, 0, 4);
                    summonTestMob(ctx, "minecraft:creeper", b, 12, 0, 0);
                    tpPlayer(ctx, b, 0, 0, 0, -90f, 0f);
                }, modernCombatStarter()));

        list.add(s(56, "56. Twin Skywalk 1.8 Void Brawl", "COMBAT",
                "Fight 3 Husks in 1.8 CPS mode across linked 3-wide skywalks over void",
                true, true, true, false, 400, 1150, 1, 0, -3, -90f, 0f, 1, 0, -3, 18, -1, 3,
                (ctx, b) -> {
                    prepSurvivalBase(ctx);
                    applyClassic18AttackSpeed(ctx, true);
                    send(ctx, "give @s minecraft:diamond_sword 1");
                    send(ctx, "item replace entity @s armor.chest with minecraft:iron_chestplate 1");
                    fill(ctx, b, -1, -1, -4, 18, -1, -2, "minecraft:stone");
                    fill(ctx, b, -1, -1, 2, 18, -1, 4, "minecraft:stone");
                    fill(ctx, b, 7, -1, -1, 10, -1, 1, "minecraft:stone");
                    fill(ctx, b, 18, 0, -4, 18, 2, 4, "minecraft:stone_brick_wall");
                    fill(ctx, b, 0, -1, -4, 2, -1, -2, "minecraft:gold_block");
                    summonTestMob(ctx, "minecraft:husk", b, 8, 0, -3);
                    summonTestMob(ctx, "minecraft:husk", b, 9, 0, 3);
                    summonTestMob(ctx, "minecraft:husk", b, 14, 0, 3);
                    tpPlayer(ctx, b, 1, 0, -3, -90f, 0f);
                }, classic18Starter()));

        list.add(s(57, "57. Citadel Approach & Ramp Raid", "COMBAT",
                "Advance along the fortified stone causeway & clear 4 citadel guards",
                true, false, false, true, 460, 1200, 0, 0, 0, -90f, 0f, 0, 0, 0, 20, -1, 0,
                (ctx, b) -> {
                    prepSurvivalBase(ctx);
                    send(ctx, "give @s minecraft:diamond_sword 1");
                    send(ctx, "item replace entity @s armor.chest with minecraft:iron_chestplate 1");
                    fill(ctx, b, -2, -1, -2, 10, -1, 2, "minecraft:stone");
                    fill(ctx, b, 11, -1, -6, 22, -1, 6, "minecraft:stone_bricks");
                    fill(ctx, b, 11, 0, -6, 22, 2, -6, "minecraft:stone_brick_wall");
                    fill(ctx, b, 11, 0, 6, 22, 2, 6, "minecraft:stone_brick_wall");
                    fill(ctx, b, 22, 0, -6, 22, 2, 6, "minecraft:stone_brick_wall");
                    fill(ctx, b, 11, 4, -6, 22, 4, 6, "minecraft:tinted_glass");
                    fill(ctx, b, -1, -1, -1, 1, -1, 1, "minecraft:gold_block");
                    summonTestMob(ctx, "minecraft:husk", b, 8, 0, 0);
                    summonTestMob(ctx, "minecraft:husk", b, 15, 0, -3);
                    summonTestMob(ctx, "minecraft:skeleton", b, 18, 0, 3);
                    summonTestMob(ctx, "minecraft:skeleton", b, 19, 0, -2);
                    tpPlayer(ctx, b, 0, 0, 0, -90f, 0f);
                }, modernCombatStarter()));

        list.add(s(58, "58. Breacher Raid: Bust Gate & Clear", "COMBAT",
                "Break the barred oak doorway & clear 4 Husks inside the courtyard",
                true, false, false, true, 440, 1200, 0, 0, 0, -90f, 0f, 0, 0, 0, 14, -1, 0,
                (ctx, b) -> {
                    prepCombatKitAndWalls(ctx, b, false);
                    send(ctx, "give @s minecraft:diamond_axe 1");
                    send(ctx, "give @s minecraft:diamond_pickaxe 1");
                    fill(ctx, b, 4, 0, -7, 4, 2, 7, "minecraft:obsidian");
                    fill(ctx, b, 4, 1, -4, 4, 1, -3, "minecraft:glass");
                    fill(ctx, b, 4, 1, 3, 4, 1, 4, "minecraft:glass");
                    fill(ctx, b, 4, 0, -1, 4, 1, 1, "minecraft:oak_planks");
                    summonTestMob(ctx, "minecraft:husk", b, 8, 0, -4);
                    summonTestMob(ctx, "minecraft:husk", b, 8, 0, 4);
                    summonTestMob(ctx, "minecraft:husk", b, 12, 0, -3);
                    summonTestMob(ctx, "minecraft:husk", b, 12, 0, 3);
                    tpPlayer(ctx, b, 0, 0, 0, -90f, 0f);
                }, breacherCombatStarter("modern")));

        list.add(s(59, "59. Nightfall Mixed Horde", "COMBAT",
                "Prioritize targets dynamically vs 2 Husks, 2 Spiders & 1 Skeleton",
                true, false, false, true, 480, 1250, 0, 0, 0, -90f, 0f, 0, 0, 0, 14, -1, 0,
                (ctx, b) -> {
                    prepCombatKitAndWalls(ctx, b, false);
                    fill(ctx, b, -2, 4, -8, 14, 4, 8, "minecraft:tinted_glass");
                    fill(ctx, b, 7, 0, -1, 7, 2, 1, "minecraft:stone_bricks");
                    summonTestMob(ctx, "minecraft:husk", b, 6, 0, -5);
                    summonTestMob(ctx, "minecraft:husk", b, 6, 0, 5);
                    summonTestMob(ctx, "minecraft:spider", b, 10, 0, -5);
                    summonTestMob(ctx, "minecraft:spider", b, 10, 0, 5);
                    summonTestMob(ctx, "minecraft:skeleton", b, 12, 0, 0);
                    tpPlayer(ctx, b, 0, 0, 0, -90f, 0f);
                }, modernCombatStarter()));

        list.add(s(60, "60. Grand Championship Finale (1.8)", "COMBAT",
                "Championship 1.8 blitz vs 4 Husks + 1 Spider + 1 Creeper inside grand colosseum",
                true, false, true, true, 520, 1400, 0, 0, 0, -90f, 0f, 0, 0, 0, 17, -1, 0,
                (ctx, b) -> {
                    prepSurvivalBase(ctx);
                    applyClassic18AttackSpeed(ctx, true);
                    send(ctx, "give @s minecraft:diamond_sword 1");
                    send(ctx, "item replace entity @s armor.chest with minecraft:iron_chestplate 1");
                    fill(ctx, b, -3, -1, -9, 17, -1, 9, "minecraft:polished_deepslate");
                    fill(ctx, b, -3, 0, -9, -3, 2, 9, "minecraft:stone_brick_wall");
                    fill(ctx, b, 17, 0, -9, 17, 2, 9, "minecraft:stone_brick_wall");
                    fill(ctx, b, -3, 0, -9, 17, 2, -9, "minecraft:stone_brick_wall");
                    fill(ctx, b, -3, 0, 9, 17, 2, 9, "minecraft:stone_brick_wall");
                    fill(ctx, b, -1, -1, -1, 1, -1, 1, "minecraft:gold_block");
                    fill(ctx, b, 5, 0, -4, 5, 2, -4, "minecraft:deepslate_bricks");
                    fill(ctx, b, 5, 0, 4, 5, 2, 4, "minecraft:deepslate_bricks");
                    fill(ctx, b, 11, 0, -4, 11, 2, -4, "minecraft:deepslate_bricks");
                    fill(ctx, b, 11, 0, 4, 11, 2, 4, "minecraft:deepslate_bricks");
                    summonTestMob(ctx, "minecraft:husk", b, 6, 0, -6);
                    summonTestMob(ctx, "minecraft:husk", b, 6, 0, 6);
                    summonTestMob(ctx, "minecraft:husk", b, 13, 0, -6);
                    summonTestMob(ctx, "minecraft:husk", b, 13, 0, 6);
                    summonTestMob(ctx, "minecraft:spider", b, 9, 0, -2);
                    summonTestMob(ctx, "minecraft:creeper", b, 14, 0, 0);
                    tpPlayer(ctx, b, 0, 0, 0, -90f, 0f);
                }, classic18Starter()));

        return list;
    }

    private static StageDef s(
            int index,
            String name,
            String category,
            String hint,
            boolean combat,
            boolean parkourBridge,
            boolean classic18,
            boolean bedrockEnclosed,
            int botMaxTicks,
            int humanMaxTicks,
            int sx, int sy, int sz,
            float startYaw, float startPitch,
            int gx, int gy, int gz,
            int rx, int ry, int rz,
            java.util.function.BiConsumer<IPlayerContext, BetterBlockPos> builder,
            java.util.function.BiConsumer<baritone.Baritone, BetterBlockPos> botStarter
    ) {
        return new StageDef(
                index, name, category, hint,
                combat, parkourBridge, classic18, bedrockEnclosed,
                botMaxTicks, humanMaxTicks,
                new BetterBlockPos(sx, sy, sz), startYaw, startPitch,
                new BetterBlockPos(gx, gy, gz),
                new BetterBlockPos(rx, ry, rz),
                builder, botStarter
        );
    }
}
