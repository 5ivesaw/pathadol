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
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.IPlayerContext;
import baritone.process.CombatProcess;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.function.BiConsumer;

/**
 * Generates the 60-stage Mirror Proving Ground for bot benchmarks and human demonstration learning.
 * Stages 1-10 cover the Core Proving Ground; stages 11-60 in {@link ExtendedArenaCatalog} expand into
 * anti-cheese steering puzzles, extreme parkour, technical bridging, bedrock-encased excavation,
 * and multi-archetype tactical combat.
 */
public final class MirrorArenaCatalog {

    public record StageDef(
            int index,
            String name,
            String category,
            String hint,
            boolean combatStage,
            boolean parkourBridgeStage,
            boolean classic18Combat,
            boolean bedrockEnclosed,
            int botMaxTicks,
            int humanMaxTicks,
            BetterBlockPos startRel,
            float startYaw,
            float startPitch,
            BetterBlockPos goalRel,
            BetterBlockPos readyCheckRel,
            BiConsumer<IPlayerContext, BetterBlockPos> builder,
            BiConsumer<Baritone, BetterBlockPos> botStarter
    ) {
    }

    private MirrorArenaCatalog() {
    }

    public static List<StageDef> buildCatalog() {
        List<StageDef> stages = new ArrayList<>(60);
        stages.add(new StageDef(
                1,
                "1. Alpine Ridge & S-Curves",
                "STEERING",
                "Sprint over ridges and weave enclosed S-curves to the Emerald pad",
                false, false, false, false, 520, 1100,
                new BetterBlockPos(0, 0, 0), -90.0f, 0.0f,
                new BetterBlockPos(44, 0, 4),
                new BetterBlockPos(44, -1, 4),
                MirrorArenaCatalog::buildAlpineStage,
                walkStarter(44, 0, 4, true)
        ));
        stages.add(new StageDef(
                2,
                "2. Chicane Slalom Cuts",
                "STEERING",
                "Cut smooth diagonals cleanly through staggered deep-slate slalom gates",
                false, false, false, false, 480, 1000,
                new BetterBlockPos(0, 0, 0), -90.0f, 0.0f,
                new BetterBlockPos(40, 0, -2),
                new BetterBlockPos(40, -1, -2),
                MirrorArenaCatalog::buildSlalomStage,
                walkStarter(40, 0, -2, true)
        ));
        stages.add(new StageDef(
                3,
                "3. Sealed Multi-Tool Tunnel",
                "PUZZLE",
                "Break cobble, ore, dirt, log & stone barriers inside bedrock walls (no roof bypass)",
                false, false, false, true, 420, 1000,
                new BetterBlockPos(0, 0, 0), -90.0f, 0.0f,
                new BetterBlockPos(22, 0, 0),
                new BetterBlockPos(22, -1, 0),
                MirrorArenaCatalog::buildMiningStage,
                mineStarter(22, 0, 0, false)
        ));
        stages.add(new StageDef(
                4,
                "4. 22-Block Void Ninja-Bridge",
                "BRIDGING",
                "Bridge straight across 22 blocks of void to the Emerald pad",
                false, true, false, false, 460, 1200,
                new BetterBlockPos(0, 0, 0), -90.0f, 15.0f,
                new BetterBlockPos(27, 0, 0),
                new BetterBlockPos(27, -1, 0),
                MirrorArenaCatalog::buildLongBridgeStage,
                bridgeStarter(27, 0, 0, false)
        ));
        stages.add(new StageDef(
                5,
                "5. L-Turn Corner Void Bridge",
                "BRIDGING",
                "Bridge forward then turn 90 deg right over void to Emerald pad",
                false, true, false, false, 460, 1200,
                new BetterBlockPos(0, 0, 0), -90.0f, 15.0f,
                new BetterBlockPos(13, 0, 12),
                new BetterBlockPos(13, -1, 12),
                MirrorArenaCatalog::buildCornerBridgeStage,
                bridgeStarter(13, 0, 12, false)
        ));
        stages.add(new StageDef(
                6,
                "6. Extreme Parkour Gauntlet",
                "PARKOUR",
                "Clear 2-block, 3-block, step-up & diagonal void jumps",
                false, true, false, false, 360, 1100,
                new BetterBlockPos(0, 0, 0), -90.0f, 0.0f,
                new BetterBlockPos(24, 1, 2),
                new BetterBlockPos(24, 0, 2),
                MirrorArenaCatalog::buildParkourStage,
                walkStarter(24, 1, 2, true)
        ));
        stages.add(new StageDef(
                7,
                "7. Pillar Tower Climb (+8Y)",
                "BRIDGING",
                "Tower climb +8 blocks onto the elevated Emerald platform",
                false, false, false, false, 440, 1100,
                new BetterBlockPos(0, 0, 0), -90.0f, -15.0f,
                new BetterBlockPos(10, 8, 0),
                new BetterBlockPos(10, 7, 0),
                MirrorArenaCatalog::buildTowerStage,
                bridgeStarter(10, 8, 0, true)
        ));
        stages.add(new StageDef(
                8,
                "8. 1v4 Modern Timed Combat",
                "COMBAT",
                "Defeat all 4 Husks using timed hits, crits & strafing",
                true, false, false, false, 420, 1200,
                new BetterBlockPos(0, 0, 0), -90.0f, 0.0f,
                new BetterBlockPos(0, 0, 0),
                new BetterBlockPos(14, 0, 0),
                MirrorArenaCatalog::buildModernArenaStage,
                modernCombatStarter()
        ));
        stages.add(new StageDef(
                9,
                "9. 1.8 Multi-Target Quick-Flick",
                "COMBAT",
                "Zero-cooldown 1.8 blitz: defeat 4 spread-out Husks with fast flicks & high CPS",
                true, false, true, false, 360, 1100,
                new BetterBlockPos(0, 0, 0), -90.0f, 0.0f,
                new BetterBlockPos(0, 0, 0),
                new BetterBlockPos(14, 0, 0),
                MirrorArenaCatalog::buildClassicArenaStage,
                classic18Starter()
        ));
        stages.add(new StageDef(
                10,
                "10. Narrow 2-Wide Bridge Duel",
                "COMBAT",
                "Slay 2 Husks on a 2-wide void bridge without falling",
                true, true, false, false, 320, 1100,
                new BetterBlockPos(1, 0, 0), -90.0f, 0.0f,
                new BetterBlockPos(1, 0, 0),
                new BetterBlockPos(19, -1, 0),
                MirrorArenaCatalog::buildBridgeDuelStage,
                modernCombatStarter()
        ));

        stages.addAll(ExtendedArenaCatalog.buildExtendedStages());
        return Collections.unmodifiableList(stages);
    }

    public static void clearArenaBox(IPlayerContext ctx, BetterBlockPos b) {
        send(ctx, "execute if entity @e[tag=pathadol_test] run kill @e[tag=pathadol_test]");
        send(ctx, "execute if entity @e[type=minecraft:husk,distance=..90] run kill @e[type=minecraft:husk,distance=..90]");
        send(ctx, "execute if entity @e[type=minecraft:zombie,distance=..90] run kill @e[type=minecraft:zombie,distance=..90]");
        send(ctx, "execute if entity @e[type=minecraft:skeleton,distance=..90] run kill @e[type=minecraft:skeleton,distance=..90]");
        send(ctx, "execute if entity @e[type=minecraft:spider,distance=..90] run kill @e[type=minecraft:spider,distance=..90]");
        send(ctx, "execute if entity @e[type=minecraft:creeper,distance=..90] run kill @e[type=minecraft:creeper,distance=..90]");
        send(ctx, "execute if entity @e[type=minecraft:item,distance=..90] run kill @e[type=minecraft:item,distance=..90]");
        // Split clear box [-10..50] x [-14..18] x [-14..14] into three <= 20,000 block slices (< 32,768 Vanilla /fill cap)
        fill(ctx, b, -10, -14, -14, 50, 18, -5, "minecraft:air");
        fill(ctx, b, -10, -14, -4, 50, 18, 4, "minecraft:air");
        fill(ctx, b, -10, -14, 5, 50, 18, 14, "minecraft:air");
    }

    static BiConsumer<Baritone, BetterBlockPos> walkStarter(int gx, int gy, int gz, boolean allowParkour) {
        return (baritone, base) -> {
            Baritone.settings().allowBreak.value = false;
            Baritone.settings().allowPlace.value = false;
            Baritone.settings().allowSprint.value = true;
            Baritone.settings().allowParkour.value = allowParkour;
            baritone.getCommandManager().execute(gotoCmd(base, gx, gy, gz));
        };
    }

    static BiConsumer<Baritone, BetterBlockPos> mineStarter(int gx, int gy, int gz, boolean allowPlace) {
        return (baritone, base) -> {
            Baritone.settings().allowBreak.value = true;
            Baritone.settings().allowPlace.value = allowPlace;
            Baritone.settings().allowSprint.value = true;
            Baritone.settings().allowParkour.value = true;
            baritone.getCommandManager().execute(gotoCmd(base, gx, gy, gz));
        };
    }

    static BiConsumer<Baritone, BetterBlockPos> bridgeStarter(int gx, int gy, int gz, boolean allowBreak) {
        return (baritone, base) -> {
            Baritone.settings().allowBreak.value = allowBreak;
            Baritone.settings().allowPlace.value = true;
            Baritone.settings().allowSprint.value = true;
            Baritone.settings().allowParkour.value = true;
            Baritone.settings().humanBridgeSkill.value = "sweaty";
            if (baritone.getPlayerContext().player() != null) {
                baritone.getPlayerContext().player().getInventory().setSelectedSlot(0);
            }
            baritone.getCommandManager().execute(gotoCmd(base, gx, gy, gz));
        };
    }

    static BiConsumer<Baritone, BetterBlockPos> modernCombatStarter() {
        return (baritone, base) -> startCombat(baritone, "modern", false);
    }

    static BiConsumer<Baritone, BetterBlockPos> classic18Starter() {
        return (baritone, base) -> startCombat(baritone, "1.8", false);
    }

    static BiConsumer<Baritone, BetterBlockPos> breacherCombatStarter(String mode) {
        return (baritone, base) -> startCombat(baritone, mode, true);
    }

    private static void startCombat(Baritone baritone, String mode, boolean allowBreak) {
        Baritone.settings().humanCombatMode.value = mode;
        Baritone.settings().allowBreak.value = allowBreak;
        Baritone.settings().allowPlace.value = false;
        if (baritone.getPlayerContext().player() != null) {
            baritone.getPlayerContext().player().getInventory().setSelectedSlot(0);
        }
        CombatProcess cp = (CombatProcess) baritone.getCombatProcess();
        cp.resetTelemetry();
        baritone.getCommandManager().execute("combat mobs");
    }

    static void prepSurvivalBase(IPlayerContext ctx) {
        send(ctx, "gamemode survival @s");
        send(ctx, "clear @s");
        send(ctx, "effect clear @s");
        send(ctx, "attribute @s minecraft:attack_speed base reset");
        send(ctx, "effect give @s minecraft:resistance 180 3 true");
        send(ctx, "effect give @s minecraft:saturation 180 5 true");
    }

    static void applyClassic18AttackSpeed(IPlayerContext ctx, boolean enable) {
        send(ctx, enable
                ? "attribute @s minecraft:attack_speed base set 32.0"
                : "attribute @s minecraft:attack_speed base reset");
    }

    private static void buildAlpineStage(IPlayerContext ctx, BetterBlockPos b) {
        prepSurvivalBase(ctx);
        fill(ctx, b, -2, -1, -8, 46, -1, 8, "minecraft:stone");
        // 3-high perimeter guardrail walls prevent sidestepping or jumping outside the S-course
        fill(ctx, b, -2, 0, -9, 46, 2, -9, "minecraft:stone_brick_wall");
        fill(ctx, b, -2, 0, 9, 46, 2, 9, "minecraft:stone_brick_wall");
        // Undulating ridges & taller 4-high baffle obstacles (cannot jump over obstacles)
        fill(ctx, b, 8, 0, -8, 16, 0, 8, "minecraft:stone");
        fill(ctx, b, 10, 1, 0, 14, 1, 8, "minecraft:andesite");
        fill(ctx, b, 18, 0, -2, 20, 3, 2, "minecraft:cobblestone");
        fill(ctx, b, 19, 4, 0, 19, 4, 0, "minecraft:sea_lantern");
        fill(ctx, b, 25, 0, -8, 26, 3, -1, "minecraft:stone_bricks");
        fill(ctx, b, 25, 4, -2, 25, 4, -2, "minecraft:sea_lantern");
        fill(ctx, b, 30, 0, -8, 38, 0, 8, "minecraft:stone");
        fill(ctx, b, 32, 1, -8, 36, 1, 1, "minecraft:diorite");
        placeStartAndGoalPads(ctx, b, 0, 0, 0, 44, 0, 4);
        tpPlayer(ctx, b, 0, 0, 0, -90.0f, 0.0f);
    }

    private static void buildSlalomStage(IPlayerContext ctx, BetterBlockPos b) {
        prepSurvivalBase(ctx);
        fill(ctx, b, -2, -1, -9, 42, -1, 9, "minecraft:polished_deepslate");
        // 3-high perimeter border so slalom gates cannot be bypassed around the outer edge
        fill(ctx, b, -2, 0, -10, 42, 2, -10, "minecraft:deepslate_bricks");
        fill(ctx, b, -2, 0, 10, 42, 2, 10, "minecraft:deepslate_bricks");
        // 4-high staggered slalom gates with lantern tips
        fill(ctx, b, 7, 0, -9, 8, 3, 1, "minecraft:deepslate_bricks");
        fill(ctx, b, 7, 4, 1, 8, 4, 1, "minecraft:sea_lantern");
        fill(ctx, b, 14, 0, -1, 15, 3, 9, "minecraft:deepslate_bricks");
        fill(ctx, b, 14, 4, -1, 15, 4, -1, "minecraft:sea_lantern");
        fill(ctx, b, 21, 0, -9, 22, 3, 2, "minecraft:deepslate_bricks");
        fill(ctx, b, 21, 4, 2, 22, 4, 2, "minecraft:sea_lantern");
        fill(ctx, b, 28, 0, -2, 29, 3, 9, "minecraft:deepslate_bricks");
        fill(ctx, b, 28, 4, -2, 29, 4, -2, "minecraft:sea_lantern");
        fill(ctx, b, 34, 0, -3, 35, 3, 3, "minecraft:deepslate_bricks");
        fill(ctx, b, 34, 4, 0, 35, 4, 0, "minecraft:sea_lantern");
        placeStartAndGoalPads(ctx, b, 0, 0, 0, 40, 0, -2);
        tpPlayer(ctx, b, 0, 0, 0, -90.0f, 0.0f);
    }

    private static void buildMiningStage(IPlayerContext ctx, BetterBlockPos b) {
        prepSurvivalBase(ctx);
        send(ctx, "give @s minecraft:diamond_pickaxe 1");
        send(ctx, "give @s minecraft:diamond_shovel 1");
        send(ctx, "give @s minecraft:diamond_axe 1");
        // Bedrock-encased outer shell prevents mining out of the ceiling to skip barriers
        fill(ctx, b, -3, -2, -3, 20, 3, 3, "minecraft:bedrock");
        fill(ctx, b, -1, 0, -1, 19, 1, 1, "minecraft:air");
        // Under-floor glow strips sealed beneath floor bedrock (y=-2 is bedrock)
        fill(ctx, b, 4, -1, 0, 4, -1, 0, "minecraft:sea_lantern");
        fill(ctx, b, 10, -1, 0, 10, -1, 0, "minecraft:sea_lantern");
        fill(ctx, b, 16, -1, 0, 16, -1, 0, "minecraft:sea_lantern");
        // 5 full 3x2 bulkheads inside the bedrock bore
        fill(ctx, b, 5, 0, -1, 5, 1, 1, "minecraft:cobblestone");
        fill(ctx, b, 8, 0, -1, 8, 1, 1, "minecraft:diamond_ore");
        fill(ctx, b, 11, 0, -1, 11, 1, 1, "minecraft:dirt");
        fill(ctx, b, 14, 0, -1, 14, 1, 1, "minecraft:oak_log");
        fill(ctx, b, 17, 0, -1, 17, 1, 1, "minecraft:stone");
        // Open sky courtyard after barrier #5
        fill(ctx, b, 20, -1, -2, 25, -1, 2, "minecraft:stone");
        fill(ctx, b, 20, 0, -2, 25, 3, 2, "minecraft:air");
        fill(ctx, b, 20, 0, -3, 25, 2, -3, "minecraft:stone_brick_wall");
        fill(ctx, b, 20, 0, 3, 25, 2, 3, "minecraft:stone_brick_wall");
        fill(ctx, b, 26, 0, -3, 26, 2, 3, "minecraft:stone_brick_wall");
        placeStartAndGoalPads(ctx, b, 0, 0, 0, 22, 0, 0);
        tpPlayer(ctx, b, 0, 0, 0, -90.0f, 0.0f);
    }

    private static void buildLongBridgeStage(IPlayerContext ctx, BetterBlockPos b) {
        prepSurvivalBase(ctx);
        send(ctx, "give @s minecraft:cobblestone 64");
        send(ctx, "give @s minecraft:cobblestone 64");
        fill(ctx, b, -2, -1, -2, 2, -1, 2, "minecraft:stone");
        fill(ctx, b, 25, -1, -2, 29, -1, 2, "minecraft:stone");
        placeStartAndGoalPads(ctx, b, 0, 0, 0, 27, 0, 0);
        tpPlayer(ctx, b, 0, 0, 0, -90.0f, 15.0f);
    }

    private static void buildCornerBridgeStage(IPlayerContext ctx, BetterBlockPos b) {
        prepSurvivalBase(ctx);
        send(ctx, "give @s minecraft:cobblestone 64");
        send(ctx, "give @s minecraft:cobblestone 64");
        fill(ctx, b, -2, -1, -2, 2, -1, 2, "minecraft:stone");
        fill(ctx, b, 11, -1, 10, 15, -1, 14, "minecraft:stone");
        fill(ctx, b, 13, 5, 0, 13, 5, 0, "minecraft:sea_lantern");
        placeStartAndGoalPads(ctx, b, 0, 0, 0, 13, 0, 12);
        tpPlayer(ctx, b, 0, 0, 0, -90.0f, 15.0f);
    }

    private static void buildParkourStage(IPlayerContext ctx, BetterBlockPos b) {
        prepSurvivalBase(ctx);
        fill(ctx, b, -1, -1, -1, 2, -1, 1, "minecraft:stone");
        fill(ctx, b, 5, -1, -1, 7, -1, 1, "minecraft:stone");
        fill(ctx, b, 10, 0, -1, 12, 0, 1, "minecraft:stone");
        fill(ctx, b, 16, 0, 1, 18, 0, 3, "minecraft:stone");
        fill(ctx, b, 22, 0, 1, 25, 0, 3, "minecraft:stone");
        placeStartAndGoalPads(ctx, b, 0, 0, 0, 24, 1, 2);
        tpPlayer(ctx, b, 0, 0, 0, -90.0f, 0.0f);
    }

    private static void buildTowerStage(IPlayerContext ctx, BetterBlockPos b) {
        prepSurvivalBase(ctx);
        send(ctx, "give @s minecraft:cobblestone 64");
        send(ctx, "give @s minecraft:cobblestone 64");
        send(ctx, "give @s minecraft:diamond_pickaxe 1");
        fill(ctx, b, -2, -1, -2, 6, -1, 2, "minecraft:stone");
        fill(ctx, b, 7, 7, -2, 12, 7, 2, "minecraft:stone");
        fill(ctx, b, 12, 8, -2, 12, 9, 2, "minecraft:cobblestone_wall");
        placeStartAndGoalPads(ctx, b, 0, 0, 0, 10, 8, 0);
        tpPlayer(ctx, b, 0, 0, 0, -90.0f, -15.0f);
    }

    private static void buildModernArenaStage(IPlayerContext ctx, BetterBlockPos b) {
        prepCombatKitAndWalls(ctx, b, false);
        fill(ctx, b, 4, 0, -3, 4, 2, -3, "minecraft:stone_bricks");
        fill(ctx, b, 4, 0, 3, 4, 2, 3, "minecraft:stone_bricks");
        fill(ctx, b, 9, 0, -3, 9, 2, -3, "minecraft:stone_bricks");
        fill(ctx, b, 9, 0, 3, 9, 2, 3, "minecraft:stone_bricks");
        summonTestMob(ctx, "minecraft:husk", b, 7, 0, -5);
        summonTestMob(ctx, "minecraft:husk", b, 7, 0, 5);
        summonTestMob(ctx, "minecraft:husk", b, 11, 0, -4);
        summonTestMob(ctx, "minecraft:husk", b, 11, 0, 4);
        tpPlayer(ctx, b, 0, 0, 0, -90.0f, 0.0f);
    }

    private static void buildClassicArenaStage(IPlayerContext ctx, BetterBlockPos b) {
        prepCombatKitAndWalls(ctx, b, true);
        summonTestMob(ctx, "minecraft:husk", b, 6, 0, -6);
        summonTestMob(ctx, "minecraft:husk", b, 6, 0, 6);
        summonTestMob(ctx, "minecraft:husk", b, 12, 0, -5);
        summonTestMob(ctx, "minecraft:husk", b, 12, 0, 5);
        tpPlayer(ctx, b, 0, 0, 0, -90.0f, 0.0f);
    }

    private static void buildBridgeDuelStage(IPlayerContext ctx, BetterBlockPos b) {
        prepSurvivalBase(ctx);
        send(ctx, "give @s minecraft:diamond_sword 1");
        send(ctx, "item replace entity @s armor.chest with minecraft:iron_chestplate 1");
        fill(ctx, b, -2, -1, 0, 17, -1, 1, "minecraft:stone");
        fill(ctx, b, 18, -1, -1, 20, -1, 2, "minecraft:stone");
        fill(ctx, b, 20, 0, -1, 20, 2, 2, "minecraft:cobblestone_wall");
        fill(ctx, b, 0, -1, 0, 2, -1, 1, "minecraft:gold_block");
        summonTestMob(ctx, "minecraft:husk", b, 8, 0, 0);
        summonTestMob(ctx, "minecraft:husk", b, 14, 0, 1);
        tpPlayer(ctx, b, 1, 0, 0, -90.0f, 0.0f);
    }

    static void prepCombatKitAndWalls(IPlayerContext ctx, BetterBlockPos b, boolean classic18) {
        prepSurvivalBase(ctx);
        applyClassic18AttackSpeed(ctx, classic18);
        send(ctx, "give @s minecraft:diamond_sword 1");
        send(ctx, "item replace entity @s armor.chest with minecraft:iron_chestplate 1");
        fill(ctx, b, -2, -1, -8, 14, -1, 8, "minecraft:stone_bricks");
        fill(ctx, b, -2, 0, -8, -2, 2, 8, "minecraft:stone_brick_wall");
        fill(ctx, b, 14, 0, -8, 14, 2, 8, "minecraft:stone_brick_wall");
        fill(ctx, b, -2, 0, -8, 14, 2, -8, "minecraft:stone_brick_wall");
        fill(ctx, b, -2, 0, 8, 14, 2, 8, "minecraft:stone_brick_wall");
        fill(ctx, b, -1, -1, -1, 1, -1, 1, "minecraft:gold_block");
    }

    static void placeStartAndGoalPads(
            IPlayerContext ctx, BetterBlockPos b,
            int sx, int sy, int sz,
            int gx, int gy, int gz
    ) {
        fill(ctx, b, sx - 1, sy - 1, sz - 1, sx + 1, sy - 1, sz + 1, "minecraft:gold_block");
        fill(ctx, b, gx - 1, gy - 1, gz - 1, gx + 1, gy - 1, gz + 1, "minecraft:emerald_block");
    }

    static void summonTestMob(IPlayerContext ctx, String entityId, BetterBlockPos b, int dx, int dy, int dz) {
        send(ctx, String.format(Locale.ROOT,
                "summon %s %d %d %d {Tags:[\"pathadol_test\"],PersistenceRequired:1b}",
                entityId, b.x + dx, b.y + dy, b.z + dz));
        send(ctx, "execute if entity @e[tag=pathadol_test] run effect give @e[tag=pathadol_test] minecraft:fire_resistance 180 0 true");
        send(ctx, "execute if entity @e[tag=pathadol_test] run effect give @e[tag=pathadol_test] minecraft:resistance 180 0 true");
    }

    static void tpPlayer(IPlayerContext ctx, BetterBlockPos b, int dx, int dy, int dz, float yaw, float pitch) {
        send(ctx, String.format(Locale.ROOT, "tp @s %.2f %.2f %.2f %.1f %.1f",
                b.x + dx + 0.5, (double) (b.y + dy), b.z + dz + 0.5, yaw, pitch));
    }

    static void fill(IPlayerContext ctx, BetterBlockPos b, int x1, int y1, int z1, int x2, int y2, int z2, String block) {
        send(ctx, String.format(Locale.ROOT, "fill %d %d %d %d %d %d %s",
                b.x + Math.min(x1, x2), b.y + Math.min(y1, y2), b.z + Math.min(z1, z2),
                b.x + Math.max(x1, x2), b.y + Math.max(y1, y2), b.z + Math.max(z1, z2),
                block));
    }

    static void setBlock(IPlayerContext ctx, BetterBlockPos b, int x, int y, int z, String block) {
        send(ctx, String.format(Locale.ROOT, "setblock %d %d %d %s", b.x + x, b.y + y, b.z + z, block));
    }

    static String gotoCmd(BetterBlockPos base, int dx, int dy, int dz) {
        return String.format(Locale.ROOT, "goto %d %d %d", base.x + dx, base.y + dy, base.z + dz);
    }

    static void send(IPlayerContext ctx, String commandWithoutSlash) {
        if (ctx.player() != null && ctx.player().connection != null) {
            ctx.player().connection.sendCommand(commandWithoutSlash);
        }
    }
}
