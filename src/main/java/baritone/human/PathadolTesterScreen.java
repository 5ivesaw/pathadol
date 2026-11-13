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
import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.process.SpeedrunProcess;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.Locale;

/**
 * Main-menu & in-game Proving Ground launcher that discovers or creates a low-lag Flat Cheats-On
 * tester world and immediately runs the 60-stage Mirror Proving Ground upon entry.
 */
public class PathadolTesterScreen extends Screen {

    private static final List<String> PROFILE_CYCLE = List.of("pro", "learned", "average", "casual");
    private static final List<String> BRIDGE_STYLE_CYCLE = List.of(
            "auto", "ninja", "moonwalk", "breezily", "godbridge", "telly", "jump", "safe"
    );

    private final Screen parent;
    private String latestWorld;

    public PathadolTesterScreen(Screen parent) {
        super(Component.literal("Pathadol Mirror Proving Ground (60 Stages)"));
        this.parent = parent;
    }

    /**
     * Builds the compact button injected into {@link net.minecraft.client.gui.screens.TitleScreen}
     * without lambdas inside Mixin classes.
     */
    public static Button createTitleScreenButton(Minecraft mc, Screen parent, int x, int y) {
        AutoTestRunner.scheduleAutoStart(null);
        return Button.builder(Component.literal("Auto Tester"), button -> {
                    if (mc == null) {
                        return;
                    }
                    if (TesterWorldLauncher.findLatestTesterWorld(mc) == null) {
                        TesterWorldLauncher.launch(mc, AutoTestRunner.Mode.MIRROR, 1, 60, true, parent);
                    } else {
                        mc.gui.setScreen(new PathadolTesterScreen(parent));
                    }
                })
                .bounds(x, y, 88, 20)
                .tooltip(Tooltip.create(Component.literal(
                        "Open Pathadol 60-Stage Mirror Proving Ground (auto-creates a Flat tester world if none exists)")))
                .build();
    }

    @Override
    protected void init() {
        AutoTestRunner.scheduleAutoStart(null);
        SpeedrunProcess.scheduleAutoStart(false);
        this.latestWorld = TesterWorldLauncher.findLatestTesterWorld(this.minecraft);

        int halfW = 122;
        int gap = 6;
        int fullW = halfW * 2 + gap;
        int btnH = 20;
        int leftX = this.width / 2 - fullW / 2;
        int rightX = leftX + halfW + gap;
        int y = Math.max(54, this.height / 4 + 2);
        int row = 22;

        // Row 1: All 60 Mirror vs Smart 50 New Stages Mirror
        this.addRenderableWidget(Button.builder(Component.literal("All 60 Mirror (1-60)"), b ->
                        TesterWorldLauncher.launch(this.minecraft, AutoTestRunner.Mode.MIRROR, 1, 60, false, this))
                .bounds(leftX, y, halfW, btnH)
                .tooltip(Tooltip.create(Component.literal(
                        "Bot runs each of 60 stages, then hands over YOUR TURN and learns your style")))
                .build());

        this.addRenderableWidget(Button.builder(Component.literal("Smart 50 New (11-60)"), b ->
                        TesterWorldLauncher.launch(this.minecraft, AutoTestRunner.Mode.MIRROR, 11, 60, false, this))
                .bounds(rightX, y, halfW, btnH)
                .tooltip(Tooltip.create(Component.literal(
                        "Run stages 11-60 in Mirror Mode: puzzles, parkour, bridging, mines & horde gauntlets")))
                .build());

        // Row 2: Core 10 Quick Mirror vs Fast Bot-Only All 60
        this.addRenderableWidget(Button.builder(Component.literal("Core 10 Quick (1-10)"), b ->
                        TesterWorldLauncher.launch(this.minecraft, AutoTestRunner.Mode.MIRROR, 1, 10, false, this))
                .bounds(leftX, y + row, halfW, btnH)
                .tooltip(Tooltip.create(Component.literal(
                        "Run the classic 10-stage benchmark in Mirror Mode (Bot + YOUR TURN)")))
                .build());

        this.addRenderableWidget(Button.builder(Component.literal("Fast Bot All 60"), b ->
                        TesterWorldLauncher.launch(this.minecraft, AutoTestRunner.Mode.FAST, 1, 60, false, this))
                .bounds(rightX, y + row, halfW, btnH)
                .tooltip(Tooltip.create(Component.literal(
                        "Hands-free benchmark across all 60 stages without waiting for player turns")))
                .build());

        // Row 3: Fast Bot Smart 50 vs Fresh Flat World
        this.addRenderableWidget(Button.builder(Component.literal("Fast Bot Smart 50"), b ->
                        TesterWorldLauncher.launch(this.minecraft, AutoTestRunner.Mode.FAST, 11, 60, false, this))
                .bounds(leftX, y + row * 2, halfW, btnH)
                .tooltip(Tooltip.create(Component.literal(
                        "Hands-free Bot-only run across new stages 11-60")))
                .build());

        this.addRenderableWidget(Button.builder(Component.literal("New Flat World (1-60)"), b ->
                        TesterWorldLauncher.launch(this.minecraft, AutoTestRunner.Mode.MIRROR, 1, 60, true, this))
                .bounds(rightX, y + row * 2, halfW, btnH)
                .tooltip(Tooltip.create(Component.literal(
                        "Create a brand-new low-lag Flat Cheats-On tester world & run Mirror Mode")))
                .build());

        // Row 4: Profile & Bridging Technique
        HumanProfile active = HumanProfile.fromSettings();
        this.addRenderableWidget(Button.builder(
                        Component.literal("Profile: " + active.name().toUpperCase(Locale.ROOT)),
                        b -> {
                            cycleProfile();
                            this.rebuildWidgets();
                        })
                .bounds(leftX, y + row * 3, halfW, btnH)
                .tooltip(Tooltip.create(Component.literal(
                        "Cycle motor style: PRO -> LEARNED -> AVERAGE -> CASUAL")))
                .build());

        String curBridge = BridgeController.BridgeStyle.fromSetting(Baritone.settings().humanBridgeStyle.value).name();
        this.addRenderableWidget(Button.builder(
                        Component.literal("Bridge: " + curBridge),
                        b -> {
                            cycleBridgeStyle();
                            this.rebuildWidgets();
                        })
                .bounds(rightX, y + row * 3, halfW, btnH)
                .tooltip(Tooltip.create(Component.literal(
                        "Bridging style: AUTO (situational) -> NINJA -> MOONWALK -> BREEZILY -> GODBRIDGE -> TELLY -> JUMP -> SAFE")))
                .build());

        // Row 5: Diorama Cam & Bot Auto-Cam
        DioramaCameraController.Mode camMode = DioramaCameraController.Mode.parse(Baritone.settings().dioramaCameraMode.value);
        this.addRenderableWidget(Button.builder(
                        Component.literal("Cam [F7]: " + camMode.id.toUpperCase(Locale.ROOT)),
                        b -> {
                            DioramaCameraController.cycleManualMode(null);
                            this.rebuildWidgets();
                        })
                .bounds(leftX, y + row * 4, halfW, btnH)
                .tooltip(Tooltip.create(Component.literal(
                        "Little Nightmares / Reanimal diorama follow camera (F7 in-game): OFF -> ISO -> SIDE -> CLOSE")))
                .build());

        boolean botAutoCam = Boolean.TRUE.equals(Baritone.settings().dioramaCameraOnBot.value);
        this.addRenderableWidget(Button.builder(
                        Component.literal("Bot Auto-Cam: " + (botAutoCam ? "ON" : "OFF")),
                        b -> {
                            Baritone.settings().dioramaCameraOnBot.value = !botAutoCam;
                            this.rebuildWidgets();
                        })
                .bounds(rightX, y + row * 4, halfW, btnH)
                .tooltip(Tooltip.create(Component.literal(
                        "When ON, switches to 3/4 ISO Diorama camera automatically while Bot plays & restores 1st-person on YOUR TURN")))
                .build());

        // Row 6: Random-Seed Any% Speedrun & Reset Learned Style
        this.addRenderableWidget(Button.builder(
                        Component.literal("Random Seed Dragon"),
                        b -> TesterWorldLauncher.launchRandomSpeedrun(this.minecraft, this))
                .bounds(leftX, y + row * 5, halfW, btnH)
                .tooltip(Tooltip.create(Component.literal(
                        "Generate a brand-new Survival Normal world (random seed) & auto-start an Any% Ender Dragon Speedrun")))
                .build());

        int trials = HumanStyleLearner.activeLearnedState().trialsLearned;
        this.addRenderableWidget(Button.builder(
                        Component.literal("Reset Style (" + trials + "t)"),
                        b -> {
                            IBaritone primary = BaritoneAPI.getProvider().getPrimaryBaritone();
                            if (primary instanceof Baritone baritone) {
                                baritone.getStyleLearner().reset();
                            }
                            this.rebuildWidgets();
                        })
                .bounds(rightX, y + row * 5, halfW, btnH)
                .tooltip(Tooltip.create(Component.literal(
                        "Clear saved human_learned_profile.json cross-task style weights")))
                .build());

        this.addRenderableWidget(Button.builder(Component.literal("Back"), b -> this.onClose())
                .bounds(leftX, y + row * 6 + 4, fullW, btnH)
                .build());
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor gfx, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(gfx, mouseX, mouseY, partialTick);

        int cx = this.width / 2;
        int topY = Math.max(12, this.height / 4 - 46);
        gfx.centeredText(this.font, this.title, cx, topY, 0xFFFFD700);

        String worldLine = latestWorld != null
                ? "Reusing tester world: " + latestWorld + " (Flat, Cheats ON)"
                : "No tester world saved yet - will create Flat Cheats-On world";
        gfx.centeredText(this.font, worldLine, cx, topY + 14, 0xFFB8B8B8);

        HumanStyleLearner.LearnedState st = HumanStyleLearner.activeLearnedState();
        String learnedLine = st.trialsLearned > 0
                ? String.format(Locale.ROOT,
                "Learned (%d trials, %dt): aim=%.2f Fitts(%.2f,%.2f) cps=%.1f %s cone=%.0f look=%.1f",
                st.trialsLearned, st.totalHumanTicks, st.aimSpeed, st.fittsBaseTicks, st.fittsSlopeTicks,
                st.cpsMean, st.clickStyle, st.sprintConeDeg, st.steeringLookahead)
                : "Learned Style: 0 trials yet (Play Mirror Mode to teach Pathadol your style!)";
        gfx.centeredText(this.font, learnedLine, cx, topY + 28, st.trialsLearned > 0 ? 0xFF55FF55 : 0xFFFFAA00);
    }

    @Override
    public void onClose() {
        if (this.minecraft != null) {
            this.minecraft.gui.setScreen(this.parent);
        }
    }

    private static void cycleProfile() {
        String current = Baritone.settings().humanProfile.value;
        String key = current == null ? "pro" : current.trim().toLowerCase(Locale.ROOT);
        if ("sweaty".equals(key)) {
            key = "pro";
        }
        int idx = PROFILE_CYCLE.indexOf(key);
        String next = PROFILE_CYCLE.get((idx + 1) % PROFILE_CYCLE.size());
        HumanProfile.applyPreset(next);
    }

    private static void cycleBridgeStyle() {
        String current = Baritone.settings().humanBridgeStyle.value;
        String key = current == null ? "auto" : current.trim().toLowerCase(Locale.ROOT);
        int idx = BRIDGE_STYLE_CYCLE.indexOf(key);
        String next = BRIDGE_STYLE_CYCLE.get((idx + 1) % BRIDGE_STYLE_CYCLE.size());
        Baritone.settings().humanBridgeStyle.value = next;
    }
}
