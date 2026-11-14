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
import baritone.utils.accessor.IAbstractContainerScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.AbstractFurnaceScreen;
import net.minecraft.client.gui.screens.inventory.CraftingScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.inventory.Slot;

/**
 * Coordinates realistic visual container screens, smooth Fitts'-law cursor movement across
 * inventory/crafting slots, and a compact Top-Right Picture-in-Picture overlay whenever
 * {@link DioramaCameraController#isCameraOverrideActive()} is active.
 *
 * <p>Pure layout and cursor-curve math helpers avoid loading Minecraft singletons so unit tests
 * run headlessly without game bootstrap.
 */
public final class BotInventoryUi {

    public record MiniCornerLayout(
            int targetX,
            int targetY,
            int drawnWidth,
            int drawnHeight,
            float scale
    ) {}

    public record CursorPoint(double x, double y) {}

    private static final double DEFAULT_ENTRY_LOCAL_X = 88.0;
    private static final double DEFAULT_ENTRY_LOCAL_Y = 122.0;

    // Render pipeline flags (client render thread)
    private static boolean miniTransformActive = false;
    private static boolean suppressBackgroundDim = false;
    private static boolean suppressTooltip = false;
    private static float activeScale = 1.0f;
    private static float activeOffsetX = 0.0f;
    private static float activeOffsetY = 0.0f;
    private static boolean openingBotScreen = false;

    // Multi-tick animated bot cursor state (panel-local GUI coordinates relative to leftPos/topPos)
    private static int trackedScreenIdentity = 0;
    private static boolean botCursorVisible = false;
    private static int activeTargetSlotId = -1;
    private static double startLocalX = DEFAULT_ENTRY_LOCAL_X;
    private static double startLocalY = DEFAULT_ENTRY_LOCAL_Y;
    private static double prevLocalX = DEFAULT_ENTRY_LOCAL_X;
    private static double prevLocalY = DEFAULT_ENTRY_LOCAL_Y;
    private static double curLocalX = DEFAULT_ENTRY_LOCAL_X;
    private static double curLocalY = DEFAULT_ENTRY_LOCAL_Y;
    private static double targetLocalX = DEFAULT_ENTRY_LOCAL_X;
    private static double targetLocalY = DEFAULT_ENTRY_LOCAL_Y;
    private static int glideTick = 0;
    private static int glideTotalTicks = 1;
    private static long curveSeed = 1L;
    private static int clickFlashTicks = 0;
    private static int postOpenSettleTicks = 0;
    private static int cursorFreshTicks = 0;
    private static long lastAdvancedWorldTime = Long.MIN_VALUE;

    private BotInventoryUi() {}

    /**
     * Computes the scaled Top-Right corner layout for an open container screen.
     */
    public static MiniCornerLayout computeMiniCornerLayout(int screenW, int screenH, int panelW, int panelH) {
        int safeW = Math.max(120, screenW);
        int safeH = Math.max(100, screenH);
        int safePanelW = Math.max(64, panelW);
        int safePanelH = Math.max(64, panelH);

        float rawScale = Math.min((safeW * 0.27f) / safePanelW, (safeH * 0.34f) / safePanelH);
        float scale = Math.max(0.36f, Math.min(0.56f, rawScale));
        int drawnW = Math.max(1, Math.round(safePanelW * scale));
        int drawnH = Math.max(1, Math.round(safePanelH * scale));

        int marginX = 8;
        int headerTop = 18;
        int targetX = Math.max(marginX, safeW - drawnW - marginX);
        int targetY = Math.max(headerTop, Math.min(headerTop, safeH - drawnH - 8));
        return new MiniCornerLayout(targetX, targetY, drawnW, drawnH, scale);
    }

    /**
     * Evaluates a smooth nonlinear cursor trajectory along minimum-jerk progress with subtle
     * deterministic perpendicular curvature so inventory pointer motion reads human.
     */
    public static CursorPoint evaluateCursorPosition(
            double fromX,
            double fromY,
            double toX,
            double toY,
            double progress01,
            long seed
    ) {
        double t = Math.max(0.0, Math.min(1.0, progress01));
        double ease = MouseModel.minimumJerk(t);

        double dx = toX - fromX;
        double dy = toY - fromY;
        double dist = Math.hypot(dx, dy);
        double bx = fromX + dx * ease;
        double by = fromY + dy * ease;

        if (dist > 4.0) {
            double nx = -dy / dist;
            double ny = dx / dist;
            long h = mix64(seed);
            double sign = ((h & 1L) == 0L) ? 1.0 : -1.0;
            double arcRatio = 0.08 + 0.08 * (((h >>> 8) & 0xFFL) / 255.0);
            double maxBow = Math.min(13.0, dist * arcRatio) * sign;
            double bell = 4.0 * ease * (1.0 - ease);
            bx += nx * maxBow * bell;
            by += ny * maxBow * bell;
        }
        return new CursorPoint(bx, by);
    }

    /**
     * Maps a transformed screen coordinate back into unscaled container coordinates.
     */
    public static int unscaleMouseCoord(double screenCoord, double offset, float scale) {
        float safeScale = Math.max(0.1f, scale);
        return (int) Math.round((screenCoord - offset) / safeScale);
    }

    private static long mix64(long z) {
        z ^= (z >>> 30);
        z *= 0xBF58476D1CE4E5B9L;
        z ^= (z >>> 27);
        z *= 0x94D049BB133111EBL;
        z ^= (z >>> 31);
        return z;
    }

    // -------------------------------------------------------------------------
    // Client GUI open / close & multi-tick virtual pointer state
    // -------------------------------------------------------------------------

    public static void onTickClient(IPlayerContext ctx) {
        if (ctx == null || ctx.world() == null || ctx.minecraft() == null) {
            return;
        }
        long worldTime = ctx.world().getGameTime();
        if (worldTime == lastAdvancedWorldTime) {
            return;
        }
        lastAdvancedWorldTime = worldTime;

        Screen screen = ctx.minecraft().gui != null ? ctx.minecraft().gui.screen() : null;
        if (!(screen instanceof AbstractContainerScreen<?> cs)) {
            if (trackedScreenIdentity != 0) {
                resetCursorState();
            }
            return;
        }

        int screenId = System.identityHashCode(cs);
        if (screenId != trackedScreenIdentity) {
            trackedScreenIdentity = screenId;
            activeTargetSlotId = -1;
            startLocalX = DEFAULT_ENTRY_LOCAL_X;
            startLocalY = DEFAULT_ENTRY_LOCAL_Y;
            prevLocalX = DEFAULT_ENTRY_LOCAL_X;
            prevLocalY = DEFAULT_ENTRY_LOCAL_Y;
            curLocalX = DEFAULT_ENTRY_LOCAL_X;
            curLocalY = DEFAULT_ENTRY_LOCAL_Y;
            targetLocalX = DEFAULT_ENTRY_LOCAL_X;
            targetLocalY = DEFAULT_ENTRY_LOCAL_Y;
            glideTick = 0;
            glideTotalTicks = 1;
            if (postOpenSettleTicks <= 0) {
                postOpenSettleTicks = 3;
            }
        }

        prevLocalX = curLocalX;
        prevLocalY = curLocalY;

        if (postOpenSettleTicks > 0) {
            postOpenSettleTicks--;
        }
        if (clickFlashTicks > 0) {
            clickFlashTicks--;
        }
        if (cursorFreshTicks > 0) {
            cursorFreshTicks--;
            if (cursorFreshTicks == 0) {
                botCursorVisible = false;
            }
        }

        if (glideTick < glideTotalTicks) {
            glideTick++;
            double p = (double) glideTick / (double) Math.max(1, glideTotalTicks);
            CursorPoint pt = evaluateCursorPosition(startLocalX, startLocalY, targetLocalX, targetLocalY, p, curveSeed);
            curLocalX = pt.x();
            curLocalY = pt.y();
        } else {
            curLocalX = targetLocalX;
            curLocalY = targetLocalY;
        }
    }

    /**
     * Opens the real vanilla {@link InventoryScreen} cleanly if no container screen is currently open.
     *
     * @return {@code true} once an {@link InventoryScreen} is open and ready; {@code false} if a non-container
     * modal screen blocked opening or opening just triggered this tick.
     */
    public static boolean ensureInventoryScreenOpen(IPlayerContext ctx) {
        if (ctx == null || ctx.minecraft() == null || ctx.player() == null) {
            return false;
        }
        onTickClient(ctx);
        Minecraft mc = ctx.minecraft();
        if (mc.gui == null) {
            return false;
        }
        Screen current = mc.gui.screen();
        if (current instanceof InventoryScreen) {
            botCursorVisible = true;
            cursorFreshTicks = Math.max(cursorFreshTicks, 20);
            return postOpenSettleTicks <= 0;
        }
        if (current != null) {
            if (current instanceof AbstractContainerScreen<?>) {
                closeBotScreen(ctx);
                return false;
            }
            // Respect pause menu or external screens if open
            return false;
        }

        LocalPlayer player = ctx.player();
        openingBotScreen = true;
        try {
            mc.gui.setScreen(new InventoryScreen(player));
        } finally {
            openingBotScreen = false;
        }

        if (mc.gui.screen() instanceof AbstractContainerScreen<?> opened) {
            trackedScreenIdentity = System.identityHashCode(opened);
            botCursorVisible = true;
            cursorFreshTicks = 25;
            postOpenSettleTicks = 3;
            startLocalX = DEFAULT_ENTRY_LOCAL_X;
            startLocalY = DEFAULT_ENTRY_LOCAL_Y;
            prevLocalX = DEFAULT_ENTRY_LOCAL_X;
            prevLocalY = DEFAULT_ENTRY_LOCAL_Y;
            curLocalX = DEFAULT_ENTRY_LOCAL_X;
            curLocalY = DEFAULT_ENTRY_LOCAL_Y;
            targetLocalX = DEFAULT_ENTRY_LOCAL_X;
            targetLocalY = DEFAULT_ENTRY_LOCAL_Y;
            glideTick = 0;
            glideTotalTicks = 1;
            activeTargetSlotId = -1;
        }
        return false;
    }

    /**
     * Closes the active inventory or container screen cleanly if one is open.
     */
    public static void closeBotScreen(IPlayerContext ctx) {
        resetCursorState();
        if (ctx == null || ctx.player() == null || ctx.minecraft() == null) {
            return;
        }
        LocalPlayer player = ctx.player();
        Minecraft mc = ctx.minecraft();
        if (player.containerMenu != player.inventoryMenu
                || (mc.gui != null && mc.gui.screen() instanceof AbstractContainerScreen<?>)) {
            player.closeContainer();
        }
    }

    /**
     * Steers the visible cursor smoothly onto {@code slot} over {@code travelTicks} ticks.
     *
     * @return {@code true} once the screen has settled and the cursor rests cleanly on {@code slot}.
     */
    public static boolean moveCursorToSlot(AbstractContainerScreen<?> screen, Slot slot, int travelTicks, long seed) {
        if (screen == null || slot == null) {
            return false;
        }
        botCursorVisible = true;
        cursorFreshTicks = 25;

        int screenId = System.identityHashCode(screen);
        if (screenId != trackedScreenIdentity) {
            trackedScreenIdentity = screenId;
            activeTargetSlotId = -1;
        }

        if (activeTargetSlotId != slot.index) {
            long h = mix64(seed ^ (slot.index * 0x9E3779B97F4A7C15L));
            double jx = (((h & 0xFFL) / 255.0) - 0.5) * 2.4;
            double jy = ((((h >>> 8) & 0xFFL) / 255.0) - 0.5) * 2.4;

            startLocalX = curLocalX;
            startLocalY = curLocalY;
            targetLocalX = slot.x + 8.0 + jx;
            targetLocalY = slot.y + 8.0 + jy;
            activeTargetSlotId = slot.index;
            curveSeed = h;

            double pxDist = Math.hypot(targetLocalX - startLocalX, targetLocalY - startLocalY);
            if (pxDist < 2.0) {
                glideTotalTicks = 1;
                glideTick = 1;
                curLocalX = targetLocalX;
                curLocalY = targetLocalY;
            } else {
                glideTotalTicks = Math.max(1, Math.min(8, travelTicks));
                glideTick = 0;
            }
        }

        double distToGoal = Math.hypot(targetLocalX - curLocalX, targetLocalY - curLocalY);
        return postOpenSettleTicks <= 0 && glideTick >= glideTotalTicks && distToGoal <= 1.8;
    }

    public static void notifySlotClicked() {
        clickFlashTicks = 3;
        botCursorVisible = true;
        cursorFreshTicks = 25;
    }

    public static boolean isBotCursorActive() {
        return botCursorVisible && cursorFreshTicks > 0;
    }

    public static boolean isOpeningBotScreen() {
        return openingBotScreen;
    }

    public static boolean shouldKeepMouseGrab() {
        return DioramaCameraController.isCameraOverrideActive() || openingBotScreen;
    }

    public static boolean shouldSuppressBackgroundDim() {
        return suppressBackgroundDim;
    }

    public static boolean shouldSuppressTooltip() {
        return suppressTooltip;
    }

    public static boolean isMiniTransformActive() {
        return miniTransformActive;
    }

    public static void resetCursorState() {
        trackedScreenIdentity = 0;
        botCursorVisible = false;
        activeTargetSlotId = -1;
        postOpenSettleTicks = 0;
        cursorFreshTicks = 0;
        clickFlashTicks = 0;
    }

    // -------------------------------------------------------------------------
    // Mixin render hooks (Gui, Screen, AbstractContainerScreen, GuiGraphicsExtractor)
    // -------------------------------------------------------------------------

    /**
     * Renders open container screens either in a scaled Top-Right corner PiP when Spectator/Diorama
     * camera mode is active, or full-screen with the animated bot cursor overlay.
     */
    public static void extractScreenRenderState(
            Screen screen,
            GuiGraphicsExtractor gfx,
            int mouseX,
            int mouseY,
            float partialTicks
    ) {
        if (screen == null || gfx == null) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        boolean dioramaActive = DioramaCameraController.isCameraOverrideActive();
        boolean containerScreen = screen instanceof AbstractContainerScreen<?>;
        if (!dioramaActive || !containerScreen) {
            int effMouseX = mouseX;
            int effMouseY = mouseY;
            boolean showCursor = containerScreen && isBotCursorActive();
            if (showCursor) {
                IAbstractContainerScreen acc = (IAbstractContainerScreen) screen;
                double interpX = lerp(prevLocalX, curLocalX, partialTicks);
                double interpY = lerp(prevLocalY, curLocalY, partialTicks);
                effMouseX = Math.round((float) (acc.getLeftPos() + interpX));
                effMouseY = Math.round((float) (acc.getTopPos() + interpY));
            }
            screen.extractRenderStateWithTooltipAndSubtitles(gfx, effMouseX, effMouseY, partialTicks);
            if (showCursor) {
                gfx.nextStratum();
                renderAnimatedCursor(gfx, effMouseX, effMouseY);
            }
            return;
        }

        AbstractContainerScreen<?> cs = (AbstractContainerScreen<?>) screen;
        IAbstractContainerScreen acc = (IAbstractContainerScreen) cs;
        int leftPos = acc.getLeftPos();
        int topPos = acc.getTopPos();
        int panelW = Math.max(64, acc.getImageWidth());
        int panelH = Math.max(64, acc.getImageHeight());
        int screenW = mc != null && mc.getWindow() != null ? mc.getWindow().getGuiScaledWidth() : 480;
        int screenH = mc != null && mc.getWindow() != null ? mc.getWindow().getGuiScaledHeight() : 270;

        MiniCornerLayout layout = computeMiniCornerLayout(screenW, screenH, panelW, panelH);

        // Draw crisp dark corner card bezel behind mini container
        int cardX0 = layout.targetX() - 4;
        int cardY0 = layout.targetY() - 14;
        int cardX1 = layout.targetX() + layout.drawnWidth() + 4;
        int cardY1 = layout.targetY() + layout.drawnHeight() + 4;
        int accent = isBotCursorActive() ? 0xFF38BDF8 : 0xFF64748B;

        gfx.fill(cardX0, cardY0, cardX1, cardY1, 0xCC0D1117);
        gfx.fill(cardX0, cardY0, cardX1, cardY0 + 1, accent);
        gfx.fill(cardX0, cardY1 - 1, cardX1, cardY1, 0xFF1E293B);
        gfx.fill(cardX0, cardY0, cardX0 + 1, cardY1, 0xFF1E293B);
        gfx.fill(cardX1 - 1, cardY0, cardX1, cardY1, 0xFF1E293B);

        if (mc != null && mc.font != null) {
            String title = resolveMiniTitle(cs);
            gfx.pose().pushMatrix();
            gfx.pose().translate(cardX0 + 4.0f, cardY0 + 3.0f);
            gfx.pose().scale(0.68f, 0.68f);
            gfx.text(mc.font, title, 0, 0, 0xFFE2E8F0, true);
            gfx.pose().popMatrix();
        }
        gfx.nextStratum();

        double interpLocalX = lerp(prevLocalX, curLocalX, partialTicks);
        double interpLocalY = lerp(prevLocalY, curLocalY, partialTicks);
        int effUnscaledX = isBotCursorActive()
                ? (int) Math.round(leftPos + interpLocalX)
                : unscaleMouseCoord(mouseX, layout.targetX() - leftPos * layout.scale(), layout.scale());
        int effUnscaledY = isBotCursorActive()
                ? (int) Math.round(topPos + interpLocalY)
                : unscaleMouseCoord(mouseY, layout.targetY() - topPos * layout.scale(), layout.scale());

        float scale = layout.scale();
        float offX = layout.targetX() - leftPos * scale;
        float offY = layout.targetY() - topPos * scale;

        miniTransformActive = true;
        suppressBackgroundDim = true;
        suppressTooltip = true;
        activeScale = scale;
        activeOffsetX = offX;
        activeOffsetY = offY;

        gfx.pose().pushMatrix();
        gfx.pose().translate(offX, offY);
        gfx.pose().scale(scale, scale);
        try {
            screen.extractRenderStateWithTooltipAndSubtitles(gfx, effUnscaledX, effUnscaledY, partialTicks);
            if (isBotCursorActive()) {
                gfx.nextStratum();
                renderAnimatedCursor(gfx, effUnscaledX, effUnscaledY);
            }
        } finally {
            gfx.pose().popMatrix();
            miniTransformActive = false;
            suppressBackgroundDim = false;
            suppressTooltip = false;
        }
    }

    public static float transformEntityScale(float scale) {
        return miniTransformActive ? scale * activeScale : scale;
    }

    public static int transformEntityX(int x) {
        return miniTransformActive ? Math.round(activeOffsetX + x * activeScale) : x;
    }

    public static int transformEntityY(int y) {
        return miniTransformActive ? Math.round(activeOffsetY + y * activeScale) : y;
    }

    private static void renderAnimatedCursor(GuiGraphicsExtractor gfx, int x, int y) {
        boolean clicking = clickFlashTicks > 0;
        int ox = x + (clicking ? 1 : 0);
        int oy = y + (clicking ? 1 : 0);

        if (clicking) {
            gfx.fill(x - 5, y - 5, x + 6, y - 4, 0xCC38BDF8);
            gfx.fill(x - 5, y + 5, x + 6, y + 6, 0xCC38BDF8);
            gfx.fill(x - 5, y - 4, x - 4, y + 5, 0xCC38BDF8);
            gfx.fill(x + 5, y - 4, x + 6, y + 5, 0xCC38BDF8);
        }

        // Crisp 10x14 OS-style pixel pointer with dark outline + bright interior
        int outline = 0xFF090D16;
        int fill = clicking ? 0xFF7DD3FC : 0xFFF8FAFC;
        int[] widths = {1, 2, 3, 4, 5, 6, 7, 5, 4, 3, 2};
        for (int row = 0; row < widths.length; row++) {
            int w = widths[row];
            gfx.fill(ox - 1, oy + row, ox + w + 1, oy + row + 1, outline);
        }
        gfx.fill(ox - 1, oy - 1, ox + 2, oy, outline);
        for (int row = 1; row < widths.length - 1; row++) {
            int w = widths[row] - 1;
            if (w >= 1) {
                gfx.fill(ox, oy + row, ox + w, oy + row + 1, fill);
            }
        }
    }

    private static String resolveMiniTitle(AbstractContainerScreen<?> screen) {
        if (screen instanceof InventoryScreen) {
            return "BOT INVENTORY";
        }
        if (screen instanceof CraftingScreen) {
            return "BOT CRAFTING";
        }
        if (screen instanceof AbstractFurnaceScreen<?>) {
            return "BOT FURNACE";
        }
        return "BOT CONTAINER";
    }

    private static double lerp(double a, double b, float partial) {
        double p = Math.max(0.0, Math.min(1.0, partial));
        return a + (b - a) * p;
    }
}
