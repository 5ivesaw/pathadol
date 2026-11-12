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
import baritone.api.utils.Helper;
import baritone.api.utils.IPlayerContext;
import baritone.api.utils.Rotation;
import baritone.api.utils.input.Input;
import baritone.process.CombatProcess;
import baritone.utils.BlockStateInterface;
import baritone.utils.InputOverrideHandler;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Automated single-command self-test suite and auto-judge for Pathadol ({@code #lab auto} / {@code #autotest}).
 * Builds isolated sky arenas via server commands, runs P1-P7 + Trap 1-3 + sprint-ascend regression checks,
 * evaluates deterministic pass/fail criteria, and saves a concise scorecard to {@code .minecraft/pathadol/lab/}.
 */
public final class AutoTestRunner {

    private static final DateTimeFormatter TS_FMT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private enum Stage {
        IDLE,
        S1_PREP, S1_WAIT_ARENA, S1_RUN,
        S2_PREP, S2_WAIT_ARENA, S2_RUN,
        S3A_PREP, S3A_WAIT_ARENA, S3A_RUN,
        S3B_PREP, S3B_WAIT_ARENA, S3B_RUN,
        S4A_PREP, S4A_WAIT_ARENA, S4A_RUN,
        S4B_PREP, S4B_WAIT_ARENA, S4B_RUN,
        S5_PREP, S5_WAIT_ARENA, S5_RUN,
        S6_PREP, S6_WAIT_ARENA, S6_RUN,
        CLEANUP
    }

    public record StageVerdict(String name, boolean passed, String details) {}

    private final Baritone baritone;
    private final HumanLabRecorder labRecorder;
    private final List<StageVerdict> verdicts = new ArrayList<>();

    private Stage stage = Stage.IDLE;
    private int stageTicks = 0;
    private int baseX, arenaY, baseZ;
    private Vec3 originPos;
    private GameType savedGameType;

    // Saved settings snapshot
    private boolean savedHumanMode;
    private String savedProfile;
    private String savedBridgeSkill;
    private String savedCombatMode;
    private boolean savedAllowBreak;
    private boolean savedAllowPlace;
    private boolean savedAllowSprint;
    private boolean savedAllowParkour;
    private boolean savedAllowParkourPlace;

    // Per-stage telemetry accumulators
    private float prevYaw, prevPitch;
    private boolean hasPrevRot;
    private double aimSpeedSum;
    private int aimSamples;
    private int snapsOver18;
    private int nonZeroMouseSteps;
    private int gcdMatches;
    private double minObservedY;
    private int blocksBroken;
    private int lastSlot;
    private int trap3Violations;

    // Cross-substage metrics
    private boolean bridge3aReached;
    private double bridge3aMinY;
    private int bridge3aPlaced;
    private double modernMeanStr;
    private int modernSwings;
    private int modernKills;

    public AutoTestRunner(Baritone baritone, HumanLabRecorder labRecorder) {
        this.baritone = baritone;
        this.labRecorder = labRecorder;
    }

    public boolean isRunning() {
        return stage != Stage.IDLE;
    }

    public boolean start(IPlayerContext ctx) {
        if (stage != Stage.IDLE) {
            return false;
        }
        if (ctx.player() == null || ctx.world() == null) {
            return false;
        }

        verdicts.clear();
        originPos = ctx.player().position();
        BetterBlockPos feet = ctx.playerFeet();
        baseX = feet.x;
        baseZ = feet.z;
        int minAllowed = ctx.world().dimensionType().minY() + 10;
        int maxAllowed = ctx.world().dimensionType().minY() + ctx.world().dimensionType().height() - 20;
        arenaY = Math.max(minAllowed, Math.min(maxAllowed, Math.max(feet.y + 35, 195)));
        savedGameType = ctx.playerController().getGameType();

        saveSettings();
        applyBaselineTestSettings();

        try {
            labRecorder.start("autotest");
        } catch (IOException ignored) {
        }

        Helper.HELPER.logDirect("AutoTest started (6 stages, ~70s hands-free). Run #lab auto stop to abort.");
        transitionTo(Stage.S1_PREP);
        return true;
    }

    public void abort(IPlayerContext ctx, String reason) {
        if (stage == Stage.IDLE) {
            return;
        }
        stopBotActions();
        cleanupArena(ctx);
        restoreSettings();
        labRecorder.stop();
        stage = Stage.IDLE;
        Helper.HELPER.logDirect("AutoTest stopped: " + reason);
    }

    public void onPostTick(IPlayerContext ctx, boolean rightClickFired, boolean brokeBlock) {
        if (stage == Stage.IDLE || ctx.player() == null || ctx.world() == null) {
            return;
        }
        stageTicks++;

        switch (stage) {
            // ---------------- Stage 1: Motor Core & Sprint-Ascend ----------------
            case S1_PREP -> {
                Helper.HELPER.logDirect("[AutoTest 1/6] Motor Core, PathSmoother & Sprint-Ascend...");
                stopBotActions();
                Baritone.settings().humanMode.value = true;
                Baritone.settings().allowPlace.value = false;
                Baritone.settings().allowBreak.value = false;
                Baritone.settings().allowSprint.value = true;
                clearArenaBox(ctx);
                sendServerCmd(ctx, "gamemode survival @s");
                sendServerCmd(ctx, "clear @s");
                sendServerCmd(ctx, "effect give @s minecraft:resistance 120 3 true");
                sendServerCmd(ctx, "effect give @s minecraft:saturation 120 5 true");
                // Flat 5-wide runway + 3-wide center wall + 1-block step up at +19
                sendServerCmd(ctx, fillCmd(baseX - 1, arenaY - 1, baseZ - 2, baseX + 18, arenaY - 1, baseZ + 2, "minecraft:stone"));
                sendServerCmd(ctx, fillCmd(baseX + 19, arenaY, baseZ - 2, baseX + 27, arenaY, baseZ + 2, "minecraft:stone"));
                sendServerCmd(ctx, fillCmd(baseX + 8, arenaY, baseZ - 1, baseX + 8, arenaY + 2, baseZ + 1, "minecraft:cobblestone"));
                sendServerCmd(ctx, String.format(Locale.ROOT, "tp @s %.2f %d %.2f -90 0", baseX + 0.5, arenaY, baseZ + 0.5));
                transitionTo(Stage.S1_WAIT_ARENA);
            }
            case S1_WAIT_ARENA -> {
                if (!checkArenaReady(ctx, baseX, arenaY - 1, baseZ, arenaY)) {
                    return;
                }
                resetStageTelemetry(ctx);
                baritone.getCommandManager().execute("goto " + (baseX + 25) + " " + (arenaY + 1) + " " + baseZ);
                transitionTo(Stage.S1_RUN);
            }
            case S1_RUN -> {
                sampleAimAndY(ctx);
                boolean reached = horizontalDist(ctx, baseX + 25.5, baseZ + 0.5) <= 2.2
                        && Math.abs(ctx.player().getY() - (arenaY + 1)) <= 0.8;
                if (reached || stageTicks >= 320) {
                    stopBotActions();
                    double meanAim = aimSamples > 0 ? aimSpeedSum / aimSamples : 0.0;
                    double gcdRate = nonZeroMouseSteps == 0 ? 100.0 : (100.0 * gcdMatches / nonZeroMouseSteps);
                    boolean pass = reached && minObservedY >= arenaY - 0.5 && gcdRate >= 95.0 && snapsOver18 <= 2 && meanAim > 0.05;
                    String info = String.format(Locale.ROOT, "reached=%s (%.1fs) | GCD=%.1f%% | snaps=%d | meanAim=%.2f deg/t",
                            reached, stageTicks / 20.0, gcdRate, snapsOver18, meanAim);
                    recordVerdict("1. Motor & Sprint-Ascend", pass, info);
                    transitionTo(Stage.S2_PREP);
                }
            }

            // ---------------- Stage 2: Mining & Trap 3 Pre-Swap ----------------
            case S2_PREP -> {
                Helper.HELPER.logDirect("[AutoTest 2/6] Mining & Trap 3 Hotbar Pre-Swap Safety...");
                stopBotActions();
                Baritone.settings().humanMode.value = true;
                Baritone.settings().allowBreak.value = true;
                Baritone.settings().allowPlace.value = false;
                clearArenaBox(ctx);
                sendServerCmd(ctx, "gamemode survival @s");
                sendServerCmd(ctx, "clear @s");
                sendServerCmd(ctx, "give @s minecraft:diamond_pickaxe 1");
                sendServerCmd(ctx, "give @s minecraft:diamond_shovel 1");
                sendServerCmd(ctx, "give @s minecraft:cobblestone 64");
                // Enclosed tunnel forcing pickaxe break (stone + ore) followed by shovel break (dirt)
                sendServerCmd(ctx, fillCmd(baseX - 1, arenaY - 1, baseZ - 2, baseX + 14, arenaY + 2, baseZ + 2, "minecraft:stone"));
                sendServerCmd(ctx, fillCmd(baseX - 1, arenaY, baseZ - 1, baseX + 13, arenaY + 1, baseZ + 1, "minecraft:air"));
                sendServerCmd(ctx, fillCmd(baseX + 5, arenaY, baseZ - 1, baseX + 5, arenaY + 1, baseZ + 1, "minecraft:diamond_ore"));
                sendServerCmd(ctx, fillCmd(baseX + 6, arenaY, baseZ - 1, baseX + 6, arenaY + 1, baseZ + 1, "minecraft:dirt"));
                sendServerCmd(ctx, String.format(Locale.ROOT, "tp @s %.2f %d %.2f -90 0", baseX + 0.5, arenaY, baseZ + 0.5));
                transitionTo(Stage.S2_WAIT_ARENA);
            }
            case S2_WAIT_ARENA -> {
                if (!checkArenaReady(ctx, baseX + 5, arenaY, baseZ, arenaY)) {
                    return;
                }
                resetStageTelemetry(ctx);
                baritone.getCommandManager().execute("goto " + (baseX + 11) + " " + arenaY + " " + baseZ);
                transitionTo(Stage.S2_RUN);
            }
            case S2_RUN -> {
                sampleAimAndY(ctx);
                if (brokeBlock) {
                    blocksBroken++;
                }
                int slot = ctx.player().getInventory().getSelectedSlot();
                InputOverrideHandler io = baritone.getInputOverrideHandler();
                if (slot != lastSlot && (io.getClickModel().isOverHolding() || io.isInputForcedDown(Input.CLICK_LEFT))) {
                    trap3Violations++;
                }
                lastSlot = slot;

                boolean reached = horizontalDist(ctx, baseX + 11.5, baseZ + 0.5) <= 2.2;
                if (reached || stageTicks >= 340) {
                    stopBotActions();
                    boolean pass = reached && blocksBroken >= 2 && trap3Violations == 0;
                    String info = String.format(Locale.ROOT, "reached=%s (%.1fs) | breaks=%d | trap3Violations=%d",
                            reached, stageTicks / 20.0, blocksBroken, trap3Violations);
                    recordVerdict("2. Mining & Trap 3 Pre-Swap", pass, info);
                    transitionTo(Stage.S3A_PREP);
                }
            }

            // ---------------- Stage 3A: Void Bridging (medium) ----------------
            case S3A_PREP -> {
                Helper.HELPER.logDirect("[AutoTest 3/6] Void Bridging (medium + sweaty) & Trap 1 Anchor...");
                stopBotActions();
                Baritone.settings().humanMode.value = true;
                Baritone.settings().allowPlace.value = true;
                Baritone.settings().allowBreak.value = false;
                Baritone.settings().allowParkour.value = false;
                Baritone.settings().humanBridgeSkill.value = "medium";
                clearArenaBox(ctx);
                sendServerCmd(ctx, "gamemode survival @s");
                sendServerCmd(ctx, "clear @s");
                sendServerCmd(ctx, "give @s minecraft:cobblestone 64");
                sendServerCmd(ctx, fillCmd(baseX - 1, arenaY - 1, baseZ - 2, baseX + 3, arenaY - 1, baseZ + 2, "minecraft:stone"));
                sendServerCmd(ctx, fillCmd(baseX + 14, arenaY - 1, baseZ - 2, baseX + 18, arenaY - 1, baseZ + 2, "minecraft:stone"));
                sendServerCmd(ctx, String.format(Locale.ROOT, "tp @s %.2f %d %.2f -90 0", baseX + 1.5, arenaY, baseZ + 0.5));
                transitionTo(Stage.S3A_WAIT_ARENA);
            }
            case S3A_WAIT_ARENA -> {
                if (!checkArenaReady(ctx, baseX + 15, arenaY - 1, baseZ, arenaY)) {
                    return;
                }
                resetStageTelemetry(ctx);
                baritone.getCommandManager().execute("goto " + (baseX + 16) + " " + arenaY + " " + baseZ);
                transitionTo(Stage.S3A_RUN);
            }
            case S3A_RUN -> {
                sampleAimAndY(ctx);
                boolean reached = horizontalDist(ctx, baseX + 16.5, baseZ + 0.5) <= 2.2
                        && Math.abs(ctx.player().getY() - arenaY) <= 1.2;
                boolean fell = ctx.player().getY() < arenaY - 1.5;
                if (reached || fell || stageTicks >= 340) {
                    stopBotActions();
                    bridge3aReached = reached;
                    bridge3aMinY = minObservedY;
                    bridge3aPlaced = countPlacedInGap(ctx, baseX + 4, baseX + 13, arenaY - 1, baseZ - 2, baseZ + 2);
                    transitionTo(Stage.S3B_PREP);
                }
            }

            // ---------------- Stage 3B: Void Bridging (sweaty) ----------------
            case S3B_PREP -> {
                stopBotActions();
                Baritone.settings().humanBridgeSkill.value = "sweaty";
                sendServerCmd(ctx, "clear @s");
                sendServerCmd(ctx, "give @s minecraft:cobblestone 64");
                sendServerCmd(ctx, fillCmd(baseX + 4, arenaY - 2, baseZ - 3, baseX + 13, arenaY + 3, baseZ + 3, "minecraft:air"));
                sendServerCmd(ctx, String.format(Locale.ROOT, "tp @s %.2f %d %.2f -90 0", baseX + 1.5, arenaY, baseZ + 0.5));
                transitionTo(Stage.S3B_WAIT_ARENA);
            }
            case S3B_WAIT_ARENA -> {
                if (stageTicks < 14) {
                    return;
                }
                if (horizontalDist(ctx, baseX + 1.5, baseZ + 0.5) > 2.5 || Math.abs(ctx.player().getY() - arenaY) > 1.5) {
                    if (stageTicks >= 40) {
                        recordVerdict("3. Void Bridging (Trap 1)", false, "Arena teleport timed out before 3b");
                        transitionTo(Stage.S4A_PREP);
                    }
                    return;
                }
                resetStageTelemetry(ctx);
                baritone.getCommandManager().execute("goto " + (baseX + 16) + " " + arenaY + " " + baseZ);
                transitionTo(Stage.S3B_RUN);
            }
            case S3B_RUN -> {
                sampleAimAndY(ctx);
                boolean reached3b = horizontalDist(ctx, baseX + 16.5, baseZ + 0.5) <= 2.2
                        && Math.abs(ctx.player().getY() - arenaY) <= 1.2;
                boolean fell = ctx.player().getY() < arenaY - 1.5;
                if (reached3b || fell || stageTicks >= 340) {
                    stopBotActions();
                    int placed3b = countPlacedInGap(ctx, baseX + 4, baseX + 13, arenaY - 1, baseZ - 2, baseZ + 2);
                    double worstY = Math.min(bridge3aMinY, minObservedY);
                    boolean noFalls = worstY >= arenaY - 1.0;
                    int totalPlaced = bridge3aPlaced + placed3b;
                    boolean pass = noFalls && bridge3aReached && reached3b && totalPlaced >= 8;
                    String info = String.format(Locale.ROOT, "medium=%s sweaty=%s | noFalls=%s (minY=%.1f) | placed=%d",
                            bridge3aReached, reached3b, noFalls, worstY, totalPlaced);
                    recordVerdict("3. Void Bridging (Trap 1)", pass, info);
                    transitionTo(Stage.S4A_PREP);
                }
            }

            // ---------------- Stage 4A: Combat Modern Cooldown ----------------
            case S4A_PREP -> {
                Helper.HELPER.logDirect("[AutoTest 4/6] Combat (modern cooldown + 1.8 CPS)...");
                stopBotActions();
                Baritone.settings().humanMode.value = true;
                Baritone.settings().allowPlace.value = false;
                Baritone.settings().allowBreak.value = false;
                clearArenaBox(ctx);
                sendServerCmd(ctx, "gamemode survival @s");
                sendServerCmd(ctx, "clear @s");
                sendServerCmd(ctx, "give @s minecraft:diamond_sword 1");
                sendServerCmd(ctx, "effect give @s minecraft:resistance 120 3 true");
                // 13x13 walled arena
                sendServerCmd(ctx, fillCmd(baseX - 6, arenaY - 1, baseZ - 6, baseX + 6, arenaY + 2, baseZ + 6, "minecraft:stone"));
                sendServerCmd(ctx, fillCmd(baseX - 5, arenaY, baseZ - 5, baseX + 5, arenaY + 2, baseZ + 5, "minecraft:air"));
                sendServerCmd(ctx, String.format(Locale.ROOT, "tp @s %.2f %d %.2f -90 0", baseX - 2.5, arenaY, baseZ + 0.5));
                sendServerCmd(ctx, String.format(Locale.ROOT, "summon minecraft:zombie %d %d %d", baseX + 3, arenaY, baseZ));
                sendServerCmd(ctx, String.format(Locale.ROOT, "summon minecraft:zombie %d %d %d", baseX + 3, arenaY, baseZ + 1));
                sendServerCmd(ctx, "effect give @e[type=minecraft:zombie,distance=..25] minecraft:fire_resistance 120 0 true");
                transitionTo(Stage.S4A_WAIT_ARENA);
            }
            case S4A_WAIT_ARENA -> {
                if (!checkArenaReady(ctx, baseX, arenaY - 1, baseZ, arenaY)) {
                    return;
                }
                ctx.player().getInventory().setSelectedSlot(0);
                CombatProcess cp = baritone.getCombatProcess();
                cp.resetTelemetry();
                Baritone.settings().humanCombatMode.value = "modern";
                baritone.getCommandManager().execute("combat mobs");
                transitionTo(Stage.S4A_RUN);
            }
            case S4A_RUN -> {
                int aliveZombies = countNearbyZombies(ctx, 14.0);
                if ((stageTicks >= 30 && aliveZombies == 0) || stageTicks >= 220) {
                    CombatProcess cp = baritone.getCombatProcess();
                    modernSwings = cp.getSwingsRecorded();
                    modernMeanStr = cp.getMeanAttackStrengthAtSwing();
                    modernKills = Math.max(0, 2 - aliveZombies);
                    cp.stop();
                    transitionTo(Stage.S4B_PREP);
                }
            }

            // ---------------- Stage 4B: Combat 1.8 CPS ----------------
            case S4B_PREP -> {
                stopBotActions();
                sendServerCmd(ctx, "kill @e[type=minecraft:zombie,distance=..35]");
                sendServerCmd(ctx, String.format(Locale.ROOT, "tp @s %.2f %d %.2f -90 0", baseX - 2.5, arenaY, baseZ + 0.5));
                sendServerCmd(ctx, String.format(Locale.ROOT, "summon minecraft:zombie %d %d %d", baseX + 3, arenaY, baseZ));
                sendServerCmd(ctx, String.format(Locale.ROOT, "summon minecraft:zombie %d %d %d", baseX + 3, arenaY, baseZ + 1));
                sendServerCmd(ctx, "effect give @e[type=minecraft:zombie,distance=..25] minecraft:fire_resistance 120 0 true");
                transitionTo(Stage.S4B_WAIT_ARENA);
            }
            case S4B_WAIT_ARENA -> {
                if (stageTicks < 14) {
                    return;
                }
                ctx.player().getInventory().setSelectedSlot(0);
                CombatProcess cp = baritone.getCombatProcess();
                cp.resetTelemetry();
                Baritone.settings().humanCombatMode.value = "1.8";
                baritone.getCommandManager().execute("combat mobs");
                transitionTo(Stage.S4B_RUN);
            }
            case S4B_RUN -> {
                int aliveZombies = countNearbyZombies(ctx, 14.0);
                if ((stageTicks >= 30 && aliveZombies == 0) || stageTicks >= 180) {
                    CombatProcess cp = baritone.getCombatProcess();
                    int classicSwings = cp.getSwingsRecorded();
                    int totalKills = modernKills + Math.max(0, 2 - aliveZombies);
                    cp.stop();
                    boolean pass = modernSwings >= 2 && modernMeanStr >= 0.85 && classicSwings >= 4 && totalKills >= 1;
                    String info = String.format(Locale.ROOT, "modernStr=%.2f (%d swings) | 1.8_swings=%d | kills=%d/4",
                            modernMeanStr, modernSwings, classicSwings, totalKills);
                    recordVerdict("4. Combat (modern + 1.8)", pass, info);
                    transitionTo(Stage.S5_PREP);
                }
            }

            // ---------------- Stage 5: Trap 2 Narrow Bridge Void-Safe Combat ----------------
            case S5_PREP -> {
                Helper.HELPER.logDirect("[AutoTest 5/6] Trap 2 Narrow 2-Wide Bridge Combat...");
                stopBotActions();
                Baritone.settings().humanCombatMode.value = "modern";
                clearArenaBox(ctx);
                sendServerCmd(ctx, "gamemode survival @s");
                sendServerCmd(ctx, "clear @s");
                sendServerCmd(ctx, "give @s minecraft:diamond_sword 1");
                sendServerCmd(ctx, fillCmd(baseX - 2, arenaY - 1, baseZ, baseX + 14, arenaY - 1, baseZ + 1, "minecraft:stone"));
                sendServerCmd(ctx, String.format(Locale.ROOT, "tp @s %.2f %d %.2f -90 0", baseX + 1.5, arenaY, baseZ + 1.0));
                sendServerCmd(ctx, String.format(Locale.ROOT, "summon minecraft:zombie %d %d %d", baseX + 6, arenaY, baseZ));
                sendServerCmd(ctx, "effect give @e[type=minecraft:zombie,distance=..25] minecraft:fire_resistance 120 0 true");
                transitionTo(Stage.S5_WAIT_ARENA);
            }
            case S5_WAIT_ARENA -> {
                if (!checkArenaReady(ctx, baseX + 2, arenaY - 1, baseZ, arenaY)) {
                    return;
                }
                resetStageTelemetry(ctx);
                ctx.player().getInventory().setSelectedSlot(0);
                CombatProcess cp = baritone.getCombatProcess();
                cp.resetTelemetry();
                baritone.getCommandManager().execute("combat mobs");
                transitionTo(Stage.S5_RUN);
            }
            case S5_RUN -> {
                sampleAimAndY(ctx);
                int aliveZombies = countNearbyZombies(ctx, 16.0);
                boolean fell = ctx.player().getY() < arenaY - 1.2;
                if (fell || (stageTicks >= 30 && aliveZombies == 0) || stageTicks >= 160) {
                    CombatProcess cp = baritone.getCombatProcess();
                    int swings = cp.getSwingsRecorded();
                    cp.stop();
                    boolean noVoidFalls = minObservedY >= arenaY - 1.0;
                    boolean pass = noVoidFalls && swings >= 1;
                    String info = String.format(Locale.ROOT, "voidFalls=%s (minY=%.1f) | swings=%d | zombiesLeft=%d",
                            !noVoidFalls, minObservedY, swings, aliveZombies);
                    recordVerdict("5. Trap 2 Bridge Combat", pass, info);
                    transitionTo(Stage.S6_PREP);
                }
            }

            // ---------------- Stage 6: Parkour Gaps ----------------
            case S6_PREP -> {
                Helper.HELPER.logDirect("[AutoTest 6/6] Parkour Gaps (2-block & 3-block)...");
                stopBotActions();
                Baritone.settings().allowParkour.value = true;
                Baritone.settings().allowPlace.value = false;
                Baritone.settings().allowSprint.value = true;
                clearArenaBox(ctx);
                sendServerCmd(ctx, "gamemode survival @s");
                sendServerCmd(ctx, "clear @s");
                sendServerCmd(ctx, fillCmd(baseX - 1, arenaY - 1, baseZ - 1, baseX + 2, arenaY - 1, baseZ + 1, "minecraft:stone"));
                sendServerCmd(ctx, fillCmd(baseX + 5, arenaY - 1, baseZ - 1, baseX + 7, arenaY - 1, baseZ + 1, "minecraft:stone"));
                sendServerCmd(ctx, fillCmd(baseX + 11, arenaY - 1, baseZ - 1, baseX + 15, arenaY - 1, baseZ + 1, "minecraft:stone"));
                sendServerCmd(ctx, String.format(Locale.ROOT, "tp @s %.2f %d %.2f -90 0", baseX + 0.5, arenaY, baseZ + 0.5));
                transitionTo(Stage.S6_WAIT_ARENA);
            }
            case S6_WAIT_ARENA -> {
                if (!checkArenaReady(ctx, baseX + 12, arenaY - 1, baseZ, arenaY)) {
                    return;
                }
                resetStageTelemetry(ctx);
                baritone.getCommandManager().execute("goto " + (baseX + 13) + " " + arenaY + " " + baseZ);
                transitionTo(Stage.S6_RUN);
            }
            case S6_RUN -> {
                sampleAimAndY(ctx);
                boolean reached = horizontalDist(ctx, baseX + 13.5, baseZ + 0.5) <= 2.2
                        && Math.abs(ctx.player().getY() - arenaY) <= 1.0;
                boolean fell = ctx.player().getY() < arenaY - 1.5;
                if (reached || fell || stageTicks >= 240) {
                    stopBotActions();
                    boolean pass = reached && minObservedY >= arenaY - 1.0;
                    String info = String.format(Locale.ROOT, "reached=%s (%.1fs) | minY=%.1f",
                            reached, stageTicks / 20.0, minObservedY);
                    recordVerdict("6. Parkour Gaps", pass, info);
                    transitionTo(Stage.CLEANUP);
                }
            }

            // ---------------- Final Cleanup & Report ----------------
            case CLEANUP -> {
                stopBotActions();
                cleanupArena(ctx);
                restoreSettings();
                Path jsonl = labRecorder.stop();
                String report = buildScorecardReport(jsonl);
                Path savedReport = writeReportFile(report);
                Helper.HELPER.logDirect(report);
                if (savedReport != null) {
                    Helper.HELPER.logDirect("AutoTest report saved: " + savedReport);
                }
                stage = Stage.IDLE;
            }
            default -> stage = Stage.IDLE;
        }
    }

    private void transitionTo(Stage next) {
        this.stage = next;
        this.stageTicks = 0;
    }

    private boolean checkArenaReady(IPlayerContext ctx, int checkFloorX, int checkFloorY, int checkFloorZ, int expectedPlayerY) {
        if (stageTicks < 12) {
            return false;
        }
        boolean playerMoved = Math.abs(ctx.player().getY() - expectedPlayerY) <= 2.0;
        boolean blockLoaded = !BlockStateInterface.get(ctx, new BetterBlockPos(checkFloorX, checkFloorY, checkFloorZ)).isAir();
        if (playerMoved && blockLoaded) {
            return true;
        }
        if (stageTicks >= 50) {
            abort(ctx, "Failed to build sky test arena (make sure cheats are enabled in Singleplayer or you have OP).");
        }
        return false;
    }

    private void resetStageTelemetry(IPlayerContext ctx) {
        hasPrevRot = false;
        aimSpeedSum = 0.0;
        aimSamples = 0;
        snapsOver18 = 0;
        nonZeroMouseSteps = 0;
        gcdMatches = 0;
        minObservedY = ctx.player().getY();
        blocksBroken = 0;
        lastSlot = ctx.player().getInventory().getSelectedSlot();
        trap3Violations = 0;
    }

    private void sampleAimAndY(IPlayerContext ctx) {
        minObservedY = Math.min(minObservedY, ctx.player().getY());
        float yaw = ctx.player().getYRot();
        float pitch = ctx.player().getXRot();
        if (hasPrevRot) {
            float dYaw = Rotation.normalizeYaw(yaw - prevYaw);
            float dPitch = pitch - prevPitch;
            double speed = Math.hypot(dYaw, dPitch);
            aimSpeedSum += speed;
            aimSamples++;
            if (speed > 18.0) {
                snapsOver18++;
            }
            float step = MouseModel.mouseStepAngle(ctx.minecraft().options.sensitivity().get());
            if (Math.abs(dYaw) > 1e-4f && step > 0f) {
                nonZeroMouseSteps++;
                float rem = Math.abs(dYaw / step - Math.round(dYaw / step));
                if (rem < 0.02f) {
                    gcdMatches++;
                }
            }
        }
        prevYaw = yaw;
        prevPitch = pitch;
        hasPrevRot = true;
    }

    private void recordVerdict(String name, boolean passed, String details) {
        StageVerdict v = new StageVerdict(name, passed, details);
        verdicts.add(v);
        Helper.HELPER.logDirect(String.format(Locale.ROOT, "[%s] %s — %s",
                passed ? "PASS" : "FAIL", name, details));
    }

    private void stopBotActions() {
        baritone.getCombatProcess().stop();
        baritone.getPathingBehavior().cancelEverything();
        baritone.getInputOverrideHandler().clearAllKeys();
    }

    private void clearArenaBox(IPlayerContext ctx) {
        sendServerCmd(ctx, "kill @e[type=minecraft:zombie,distance=..45]");
        sendServerCmd(ctx, fillCmd(baseX - 8, arenaY - 3, baseZ - 8, baseX + 30, arenaY + 6, baseZ + 8, "minecraft:air"));
    }

    private void cleanupArena(IPlayerContext ctx) {
        if (ctx.player() == null) {
            return;
        }
        clearArenaBox(ctx);
        if (originPos != null) {
            sendServerCmd(ctx, String.format(Locale.ROOT, "tp @s %.2f %.2f %.2f", originPos.x, originPos.y, originPos.z));
        }
        if (savedGameType != null) {
            sendServerCmd(ctx, "gamemode " + savedGameType.name().toLowerCase(Locale.ROOT) + " @s");
        }
        sendServerCmd(ctx, "effect clear @s minecraft:resistance");
        sendServerCmd(ctx, "effect clear @s minecraft:saturation");
    }

    private void saveSettings() {
        savedHumanMode = Baritone.settings().humanMode.value;
        savedProfile = Baritone.settings().humanProfile.value;
        savedBridgeSkill = Baritone.settings().humanBridgeSkill.value;
        savedCombatMode = Baritone.settings().humanCombatMode.value;
        savedAllowBreak = Baritone.settings().allowBreak.value;
        savedAllowPlace = Baritone.settings().allowPlace.value;
        savedAllowSprint = Baritone.settings().allowSprint.value;
        savedAllowParkour = Baritone.settings().allowParkour.value;
        savedAllowParkourPlace = Baritone.settings().allowParkourPlace.value;
    }

    private void applyBaselineTestSettings() {
        Baritone.settings().humanMode.value = true;
        Baritone.settings().humanProfile.value = "average";
        Baritone.settings().allowParkourPlace.value = false;
    }

    private void restoreSettings() {
        Baritone.settings().humanMode.value = savedHumanMode;
        Baritone.settings().humanProfile.value = savedProfile;
        Baritone.settings().humanBridgeSkill.value = savedBridgeSkill;
        Baritone.settings().humanCombatMode.value = savedCombatMode;
        Baritone.settings().allowBreak.value = savedAllowBreak;
        Baritone.settings().allowPlace.value = savedAllowPlace;
        Baritone.settings().allowSprint.value = savedAllowSprint;
        Baritone.settings().allowParkour.value = savedAllowParkour;
        Baritone.settings().allowParkourPlace.value = savedAllowParkourPlace;
    }

    private String buildScorecardReport(Path jsonlFile) {
        return formatScorecard(verdicts, jsonlFile);
    }

    public static String formatScorecard(List<StageVerdict> results, Path jsonlFile) {
        long passedCount = results.stream().filter(StageVerdict::passed).count();
        StringBuilder sb = new StringBuilder();
        sb.append(String.format(Locale.ROOT,
                "=== Pathadol AutoTest Scorecard (%d/%d PASSED) ===",
                passedCount, results.size()));
        for (StageVerdict v : results) {
            sb.append('\n')
                    .append(v.passed() ? "[PASS] " : "[FAIL] ")
                    .append(v.name())
                    .append(": ")
                    .append(v.details());
        }
        if (jsonlFile != null) {
            sb.append("\nTelemetry JSONL: ").append(jsonlFile);
        }
        return sb.toString();
    }

    private Path writeReportFile(String report) {
        try {
            Path labDir = baritone.getDirectory().resolve("lab");
            Files.createDirectories(labDir);
            Path out = labDir.resolve("autotest-" + TS_FMT.format(LocalDateTime.now()) + ".txt");
            Files.writeString(out, report + "\n", StandardCharsets.UTF_8);
            return out;
        } catch (IOException e) {
            return null;
        }
    }

    private static int countPlacedInGap(IPlayerContext ctx, int minX, int maxX, int y, int minZ, int maxZ) {
        BlockStateInterface bsi = new BlockStateInterface(ctx);
        int count = 0;
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                if (!bsi.get0(x, y, z).isAir()) {
                    count++;
                }
            }
        }
        return count;
    }

    private static int countNearbyZombies(IPlayerContext ctx, double radius) {
        return (int) ctx.entitiesStream()
                .filter(e -> e instanceof LivingEntity le && le != ctx.player() && le.isAlive() && !le.isRemoved())
                .filter(e -> EntityType.ZOMBIE.equals(e.getType()))
                .filter(e -> e.distanceTo(ctx.player()) <= radius)
                .count();
    }

    private static double horizontalDist(IPlayerContext ctx, double tx, double tz) {
        return Math.hypot(ctx.player().getX() - tx, ctx.player().getZ() - tz);
    }

    private static String fillCmd(int x1, int y1, int z1, int x2, int y2, int z2, String block) {
        return String.format(Locale.ROOT, "fill %d %d %d %d %d %d %s", x1, y1, z1, x2, y2, z2, block);
    }

    private static void sendServerCmd(IPlayerContext ctx, String commandWithoutSlash) {
        if (ctx.player() != null && ctx.player().connection != null) {
            ctx.player().connection.sendCommand(commandWithoutSlash);
        }
    }
}
