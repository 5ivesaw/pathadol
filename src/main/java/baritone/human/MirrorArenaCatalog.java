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

import java.util.List;
import java.util.Locale;
import java.util.function.BiConsumer;

/**
 * Defines the 10-stage Extreme Proving Ground suite used by both Fast AutoTest and Mirror Mode.
 * <p>
 * Every stage keeps individual {@code /fill} commands well under Vanilla's 32,768 block limit
 * and tags test mobs with {@code pathadol_test} so cleanup never emits missing-entity chat errors.
 */
public final class MirrorArenaCatalog {

    private MirrorArenaCatalog() {}

    public record StageDef(
            int index,
            String name,
            String hint,
            boolean combatStage,
            boolean parkourBridgeStage,
            int botMaxTicks,
            int humanMaxTicks,
            BetterBlockPos startRel,
            float startYaw,
            float startPitch,
            BetterBlockPos goalRel,
            BetterBlockPos readyCheckRel,
            BiConsumer<IPlayerContext, BetterBlockPos> builder,
            BiConsumer<Baritone, BetterBlockPos> botStarter
    ) {}

    public static List<StageDef> buildCatalog() {
        return List.of(
                new StageDef(
                        1,
                        "1. Alpine Ridge & S-Curves",
                        "Sprint over ridges and weave S-curves to the Emerald pad",
                        false, false, 520, 1100,
                        new BetterBlockPos(0, 0, 0), -90.0f, 0.0f,
                        new BetterBlockPos(44, 0, 4),
                        new BetterBlockPos(44, -1, 4),
                        MirrorArenaCatalog::buildAlpineStage,
                        (baritone, base) -> {
                            Baritone.settings().allowBreak.value = false;
                            Baritone.settings().allowPlace.value = false;
                            Baritone.settings().allowSprint.value = true;
                            Baritone.settings().allowParkour.value = true;
                            baritone.getCommandManager().execute(gotoCmd(base, 44, 0, 4));
                        }
                ),
                new StageDef(
                        2,
                        "2. Chicane Slalom Cuts",
                        "Cut smooth diagonals through offset slalom gates",
                        false, false, 480, 1000,
                        new BetterBlockPos(0, 0, 0), -90.0f, 0.0f,
                        new BetterBlockPos(40, 0, -2),
                        new BetterBlockPos(40, -1, -2),
                        MirrorArenaCatalog::buildSlalomStage,
                        (baritone, base) -> {
                            Baritone.settings().allowBreak.value = false;
                            Baritone.settings().allowPlace.value = false;
                            Baritone.settings().allowSprint.value = true;
                            Baritone.settings().allowParkour.value = true;
                            baritone.getCommandManager().execute(gotoCmd(base, 40, 0, -2));
                        }
                ),
                new StageDef(
                        3,
                        "3. Multi-Tool Tunnel",
                        "Break cobble, ore, dirt, log & stone barriers cleanly",
                        false, false, 420, 1000,
                        new BetterBlockPos(0, 0, 0), -90.0f, 0.0f,
                        new BetterBlockPos(22, 0, 0),
                        new BetterBlockPos(22, -1, 0),
                        MirrorArenaCatalog::buildMiningStage,
                        (baritone, base) -> {
                            Baritone.settings().allowBreak.value = true;
                            Baritone.settings().allowPlace.value = false;
                            Baritone.settings().allowSprint.value = true;
                            baritone.getCommandManager().execute(gotoCmd(base, 22, 0, 0));
                        }
                ),
                new StageDef(
                        4,
                        "4. 22-Block Void Ninja-Bridge",
                        "Bridge straight across 22 blocks of void to the Emerald pad",
                        false, true, 460, 1200,
                        new BetterBlockPos(0, 0, 0), -90.0f, 15.0f,
                        new BetterBlockPos(27, 0, 0),
                        new BetterBlockPos(27, -1, 0),
                        MirrorArenaCatalog::buildLongBridgeStage,
                        (baritone, base) -> {
                            Baritone.settings().allowBreak.value = false;
                            Baritone.settings().allowPlace.value = true;
                            Baritone.settings().allowSprint.value = true;
                            Baritone.settings().humanBridgeSkill.value = "sweaty";
                            baritone.getPlayerContext().player().getInventory().setSelectedSlot(0);
                            baritone.getCommandManager().execute(gotoCmd(base, 27, 0, 0));
                        }
                ),
                new StageDef(
                        5,
                        "5. L-Turn Corner Void Bridge",
                        "Bridge forward then turn 90 deg right over void to Emerald pad",
                        false, true, 460, 1200,
                        new BetterBlockPos(0, 0, 0), -90.0f, 15.0f,
                        new BetterBlockPos(13, 0, 12),
                        new BetterBlockPos(13, -1, 12),
                        MirrorArenaCatalog::buildCornerBridgeStage,
                        (baritone, base) -> {
                            Baritone.settings().allowBreak.value = false;
                            Baritone.settings().allowPlace.value = true;
                            Baritone.settings().allowSprint.value = true;
                            Baritone.settings().humanBridgeSkill.value = "sweaty";
                            baritone.getPlayerContext().player().getInventory().setSelectedSlot(0);
                            baritone.getCommandManager().execute(gotoCmd(base, 13, 0, 12));
                        }
                ),
                new StageDef(
                        6,
                        "6. Extreme Parkour Gauntlet",
                        "Clear 2-block, 3-block, step-up & diagonal void jumps",
                        false, true, 360, 1100,
                        new BetterBlockPos(0, 0, 0), -90.0f, 0.0f,
                        new BetterBlockPos(24, 1, 2),
                        new BetterBlockPos(24, 0, 2),
                        MirrorArenaCatalog::buildParkourStage,
                        (baritone, base) -> {
                            Baritone.settings().allowBreak.value = false;
                            Baritone.settings().allowPlace.value = false;
                            Baritone.settings().allowSprint.value = true;
                            Baritone.settings().allowParkour.value = true;
                            baritone.getCommandManager().execute(gotoCmd(base, 24, 1, 2));
                        }
                ),
                new StageDef(
                        7,
                        "7. Pillar Tower Climb (+8Y)",
                        "Tower climb +8 blocks onto the elevated Emerald platform",
                        false, false, 440, 1100,
                        new BetterBlockPos(0, 0, 0), -90.0f, -15.0f,
                        new BetterBlockPos(10, 8, 0),
                        new BetterBlockPos(10, 7, 0),
                        MirrorArenaCatalog::buildTowerStage,
                        (baritone, base) -> {
                            Baritone.settings().allowBreak.value = true;
                            Baritone.settings().allowPlace.value = true;
                            Baritone.settings().allowSprint.value = true;
                            baritone.getCommandManager().execute(gotoCmd(base, 10, 8, 0));
                        }
                ),
                new StageDef(
                        8,
                        "8. 1v4 Modern Timed Combat",
                        "Defeat all 4 zombies using timed hits, crits & strafing",
                        true, false, 420, 1200,
                        new BetterBlockPos(0, 0, 0), -90.0f, 0.0f,
                        new BetterBlockPos(0, 0, 0),
                        new BetterBlockPos(14, 0, 0),
                        MirrorArenaCatalog::buildModernArenaStage,
                        (baritone, base) -> {
                            Baritone.settings().humanCombatMode.value = "modern";
                            baritone.getPlayerContext().player().getInventory().setSelectedSlot(0);
                            CombatProcess cp = (CombatProcess) baritone.getCombatProcess();
                            cp.resetTelemetry();
                            baritone.getCommandManager().execute("combat mobs");
                        }
                ),
                new StageDef(
                        9,
                        "9. 1.8 Multi-Target Quick-Flick",
                        "Defeat 4 spread-out zombies with fast flicks & high CPS",
                        true, false, 380, 1100,
                        new BetterBlockPos(0, 0, 0), -90.0f, 0.0f,
                        new BetterBlockPos(0, 0, 0),
                        new BetterBlockPos(14, 0, 0),
                        MirrorArenaCatalog::buildClassicArenaStage,
                        (baritone, base) -> {
                            Baritone.settings().humanCombatMode.value = "1.8";
                            baritone.getPlayerContext().player().getInventory().setSelectedSlot(0);
                            CombatProcess cp = (CombatProcess) baritone.getCombatProcess();
                            cp.resetTelemetry();
                            baritone.getCommandManager().execute("combat mobs");
                        }
                ),
                new StageDef(
                        10,
                        "10. Narrow 2-Wide Bridge Duel",
                        "Slay 2 zombies on a 2-wide void bridge without falling",
                        true, true, 320, 1100,
                        new BetterBlockPos(1, 0, 0), -90.0f, 0.0f,
                        new BetterBlockPos(1, 0, 0),
                        new BetterBlockPos(19, -1, 0),
                        MirrorArenaCatalog::buildBridgeDuelStage,
                        (baritone, base) -> {
                            Baritone.settings().humanCombatMode.value = "modern";
                            baritone.getPlayerContext().player().getInventory().setSelectedSlot(0);
                            CombatProcess cp = (CombatProcess) baritone.getCombatProcess();
                            cp.resetTelemetry();
                            baritone.getCommandManager().execute("combat mobs");
                        }
                )
        );
    }

    public static void clearArenaBox(IPlayerContext ctx, BetterBlockPos b) {
        send(ctx, "execute if entity @e[tag=pathadol_test] run kill @e[tag=pathadol_test]");
        send(ctx, "execute if entity @e[type=minecraft:zombie,distance=..85] run kill @e[type=minecraft:zombie,distance=..85]");
        send(ctx, "execute if entity @e[type=minecraft:item,distance=..85] run kill @e[type=minecraft:item,distance=..85]");
        // Split clear volume into two <= 28,000 block slices (Vanilla /fill cap is 32,768)
        fill(ctx, b, -6, -6, -12, 50, 15, 0, "minecraft:air");
        fill(ctx, b, -6, -6, 1, 50, 15, 12, "minecraft:air");
    }

    private static void prepSurvivalBase(IPlayerContext ctx) {
        send(ctx, "gamemode survival @s");
        send(ctx, "clear @s");
        send(ctx, "effect clear @s");
        send(ctx, "effect give @s minecraft:resistance 180 3 true");
        send(ctx, "effect give @s minecraft:saturation 180 5 true");
    }

    private static void buildAlpineStage(IPlayerContext ctx, BetterBlockPos b) {
        prepSurvivalBase(ctx);
        fill(ctx, b, -2, -1, -8, 46, -1, 8, "minecraft:stone");
        // Subtle lane guide curbs so runs don't drift off the side edge
        fill(ctx, b, -2, 0, -9, 46, 0, -9, "minecraft:stone_brick_slab");
        fill(ctx, b, -2, 0, 9, 46, 0, 9, "minecraft:stone_brick_slab");
        // Undulating ridge 1 + visual S-curve obstacles
        fill(ctx, b, 8, 0, -8, 16, 0, 8, "minecraft:stone");
        fill(ctx, b, 10, 1, 0, 14, 1, 8, "minecraft:andesite");
        fill(ctx, b, 18, 0, -2, 20, 2, 2, "minecraft:cobblestone");
        fill(ctx, b, 19, 3, 0, 19, 3, 0, "minecraft:sea_lantern");
        fill(ctx, b, 25, 0, -8, 26, 2, -1, "minecraft:stone_bricks");
        fill(ctx, b, 25, 3, -2, 25, 3, -2, "minecraft:sea_lantern");
        // Undulating ridge 2
        fill(ctx, b, 30, 0, -8, 38, 0, 8, "minecraft:stone");
        fill(ctx, b, 32, 1, -8, 36, 1, 1, "minecraft:diorite");
        placeStartAndGoalPads(ctx, b, 0, 0, 0, 44, 0, 4);
        tpPlayer(ctx, b, 0, 0, 0, -90.0f, 0.0f);
    }

    private static void buildSlalomStage(IPlayerContext ctx, BetterBlockPos b) {
        prepSurvivalBase(ctx);
        fill(ctx, b, -2, -1, -9, 42, -1, 9, "minecraft:polished_deepslate");
        // Alternating wide offset gates with glowing sea-lantern caps for crisp visual rhythm
        fill(ctx, b, 7, 0, -9, 8, 2, 1, "minecraft:deepslate_bricks");
        fill(ctx, b, 7, 3, 1, 8, 3, 1, "minecraft:sea_lantern");
        fill(ctx, b, 14, 0, -1, 15, 2, 9, "minecraft:deepslate_bricks");
        fill(ctx, b, 14, 3, -1, 15, 3, -1, "minecraft:sea_lantern");
        fill(ctx, b, 21, 0, -9, 22, 2, 2, "minecraft:deepslate_bricks");
        fill(ctx, b, 21, 3, 2, 22, 3, 2, "minecraft:sea_lantern");
        fill(ctx, b, 28, 0, -2, 29, 2, 9, "minecraft:deepslate_bricks");
        fill(ctx, b, 28, 3, -2, 29, 3, -2, "minecraft:sea_lantern");
        fill(ctx, b, 34, 0, -3, 35, 2, 3, "minecraft:deepslate_bricks");
        fill(ctx, b, 34, 3, 0, 35, 3, 0, "minecraft:sea_lantern");
        placeStartAndGoalPads(ctx, b, 0, 0, 0, 40, 0, -2);
        tpPlayer(ctx, b, 0, 0, 0, -90.0f, 0.0f);
    }

    private static void buildMiningStage(IPlayerContext ctx, BetterBlockPos b) {
        prepSurvivalBase(ctx);
        send(ctx, "give @s minecraft:diamond_pickaxe 1");
        send(ctx, "give @s minecraft:diamond_shovel 1");
        send(ctx, "give @s minecraft:diamond_axe 1");
        send(ctx, "give @s minecraft:cobblestone 64");
        fill(ctx, b, -2, -1, -2, 25, 2, 2, "minecraft:stone");
        fill(ctx, b, -1, 0, -1, 24, 1, 1, "minecraft:air");
        // Open sky courtyard above finish pad + sea lantern sconces inside tunnel
        fill(ctx, b, 20, 0, -2, 25, 2, 2, "minecraft:air");
        fill(ctx, b, 20, -1, -2, 25, -1, 2, "minecraft:stone");
        fill(ctx, b, 4, 2, 0, 4, 2, 0, "minecraft:sea_lantern");
        fill(ctx, b, 10, 2, 0, 10, 2, 0, "minecraft:sea_lantern");
        fill(ctx, b, 16, 2, 0, 16, 2, 0, "minecraft:sea_lantern");
        fill(ctx, b, 5, 0, -1, 5, 1, 1, "minecraft:cobblestone");
        fill(ctx, b, 8, 0, -1, 8, 1, 1, "minecraft:diamond_ore");
        fill(ctx, b, 11, 0, -1, 11, 1, 1, "minecraft:dirt");
        fill(ctx, b, 14, 0, -1, 14, 1, 1, "minecraft:oak_log");
        fill(ctx, b, 17, 0, -1, 17, 1, 1, "minecraft:stone");
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
        // High overhead beacon at (13, 5, 0) clearly marks the 90-degree turn pivot above the void
        fill(ctx, b, 13, 5, 0, 13, 5, 0, "minecraft:sea_lantern");
        placeStartAndGoalPads(ctx, b, 0, 0, 0, 13, 0, 12);
        tpPlayer(ctx, b, 0, 0, 0, -90.0f, 15.0f);
    }

    private static void buildParkourStage(IPlayerContext ctx, BetterBlockPos b) {
        prepSurvivalBase(ctx);
        // Physically crisp parkour: 2-air flat -> 2-air +1Y step-up -> 3-air diagonal -> 3-air sprint finish
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
        // Safety backstop curb behind the elevated roof pad
        fill(ctx, b, 12, 8, -2, 12, 9, 2, "minecraft:cobblestone_wall");
        placeStartAndGoalPads(ctx, b, 0, 0, 0, 10, 8, 0);
        tpPlayer(ctx, b, 0, 0, 0, -90.0f, -15.0f);
    }

    private static void buildModernArenaStage(IPlayerContext ctx, BetterBlockPos b) {
        prepCombatKitAndWalls(ctx, b);
        // Tactical cover pillars
        fill(ctx, b, 4, 0, -3, 4, 2, -3, "minecraft:stone_bricks");
        fill(ctx, b, 4, 0, 3, 4, 2, 3, "minecraft:stone_bricks");
        fill(ctx, b, 9, 0, -3, 9, 2, -3, "minecraft:stone_bricks");
        fill(ctx, b, 9, 0, 3, 9, 2, 3, "minecraft:stone_bricks");
        summonTestZombie(ctx, b, 7, 0, -5);
        summonTestZombie(ctx, b, 7, 0, 5);
        summonTestZombie(ctx, b, 11, 0, -4);
        summonTestZombie(ctx, b, 11, 0, 4);
        tpPlayer(ctx, b, 0, 0, 0, -90.0f, 0.0f);
    }

    private static void buildClassicArenaStage(IPlayerContext ctx, BetterBlockPos b) {
        prepCombatKitAndWalls(ctx, b);
        summonTestZombie(ctx, b, 6, 0, -6);
        summonTestZombie(ctx, b, 6, 0, 6);
        summonTestZombie(ctx, b, 12, 0, -5);
        summonTestZombie(ctx, b, 12, 0, 5);
        tpPlayer(ctx, b, 0, 0, 0, -90.0f, 0.0f);
    }

    private static void buildBridgeDuelStage(IPlayerContext ctx, BetterBlockPos b) {
        prepSurvivalBase(ctx);
        send(ctx, "give @s minecraft:diamond_sword 1");
        send(ctx, "item replace entity @s armor.chest with minecraft:iron_chestplate 1");
        fill(ctx, b, -2, -1, 0, 17, -1, 1, "minecraft:stone");
        // Small far-end catch terrace & backstop so mobs don't wander into the void before engagement
        fill(ctx, b, 18, -1, -1, 20, -1, 2, "minecraft:stone");
        fill(ctx, b, 20, 0, -1, 20, 1, 2, "minecraft:cobblestone_wall");
        fill(ctx, b, 0, -1, 0, 2, -1, 1, "minecraft:gold_block");
        summonTestZombie(ctx, b, 8, 0, 0);
        summonTestZombie(ctx, b, 14, 0, 1);
        tpPlayer(ctx, b, 1, 0, 0, -90.0f, 0.0f);
    }

    private static void prepCombatKitAndWalls(IPlayerContext ctx, BetterBlockPos b) {
        prepSurvivalBase(ctx);
        send(ctx, "give @s minecraft:diamond_sword 1");
        send(ctx, "item replace entity @s armor.chest with minecraft:iron_chestplate 1");
        fill(ctx, b, -2, -1, -8, 14, -1, 8, "minecraft:stone_bricks");
        fill(ctx, b, -2, 0, -8, -2, 1, 8, "minecraft:cobblestone_wall");
        fill(ctx, b, 14, 0, -8, 14, 1, 8, "minecraft:cobblestone_wall");
        fill(ctx, b, -2, 0, -8, 14, 1, -8, "minecraft:cobblestone_wall");
        fill(ctx, b, -2, 0, 8, 14, 1, 8, "minecraft:cobblestone_wall");
        fill(ctx, b, -1, -1, -1, 1, -1, 1, "minecraft:gold_block");
    }

    private static void placeStartAndGoalPads(
            IPlayerContext ctx, BetterBlockPos b,
            int sx, int sy, int sz,
            int gx, int gy, int gz
    ) {
        fill(ctx, b, sx - 1, sy - 1, sz - 1, sx + 1, sy - 1, sz + 1, "minecraft:gold_block");
        fill(ctx, b, gx - 1, gy - 1, gz - 1, gx + 1, gy - 1, gz + 1, "minecraft:emerald_block");
    }

    private static void summonTestZombie(IPlayerContext ctx, BetterBlockPos b, int dx, int dy, int dz) {
        send(ctx, String.format(Locale.ROOT,
                "summon minecraft:zombie %d %d %d {Tags:[\"pathadol_test\"],PersistenceRequired:1b}",
                b.x + dx, b.y + dy, b.z + dz));
        send(ctx, "execute if entity @e[tag=pathadol_test] run effect give @e[tag=pathadol_test] minecraft:fire_resistance 180 0 true");
        send(ctx, "execute if entity @e[tag=pathadol_test] run effect give @e[tag=pathadol_test] minecraft:resistance 180 0 true");
    }

    private static void tpPlayer(IPlayerContext ctx, BetterBlockPos b, int dx, int dy, int dz, float yaw, float pitch) {
        send(ctx, String.format(Locale.ROOT, "tp @s %.2f %.2f %.2f %.1f %.1f",
                b.x + dx + 0.5, (double) (b.y + dy), b.z + dz + 0.5, yaw, pitch));
    }

    private static void fill(IPlayerContext ctx, BetterBlockPos b, int x1, int y1, int z1, int x2, int y2, int z2, String block) {
        send(ctx, String.format(Locale.ROOT, "fill %d %d %d %d %d %d %s",
                b.x + x1, b.y + y1, b.z + z1, b.x + x2, b.y + y2, b.z + z2, block));
    }

    private static String gotoCmd(BetterBlockPos b, int dx, int dy, int dz) {
        return "goto " + (b.x + dx) + " " + (b.y + dy) + " " + (b.z + dz);
    }

    private static void send(IPlayerContext ctx, String cmdNoSlash) {
        if (ctx.player() != null && ctx.player().connection != null) {
            ctx.player().connection.sendCommand(cmdNoSlash);
        }
    }
}
