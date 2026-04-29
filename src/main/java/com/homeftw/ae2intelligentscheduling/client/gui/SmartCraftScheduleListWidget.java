package com.homeftw.ae2intelligentscheduling.client.gui;

import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.client.renderer.entity.RenderItem;
import net.minecraft.item.ItemStack;
import net.minecraft.util.StatCollector;

import org.lwjgl.opengl.GL11;

import com.homeftw.ae2intelligentscheduling.network.packet.SyncSmartCraftOrderPacket.TaskView;
import com.homeftw.ae2intelligentscheduling.smartcraft.model.SmartCraftStatus;

import appeng.core.localization.GuiColors;

/**
 * Custom-painted task list. Replaces the previous "list of vanilla GuiButton rows" approach (which
 * looked out of place against AE2's panel chrome) with a single self-rendered widget that:
 *
 * <ul>
 * <li>renders a real ItemStack icon for each task (using {@link TaskView#itemStack()})</li>
 * <li>overlays a tiny status dot in the icon's top-right corner (mimicking AE2 patterns terminal)</li>
 * <li>shows {@code itemStack.getDisplayName()} (proper localised name) instead of the raw
 * requestKeyId</li>
 * <li>right-aligns the status text + split progress so all rows align visually</li>
 * <li>draws a 1-pixel divider whenever the task depth changes, replacing the old "Layer N:"
 * repeated text labels</li>
 * <li>highlights the row under the mouse and the currently-selected row, so the player can
 * track which task drives the detail panel without reading text</li>
 * </ul>
 *
 * <p>
 * Hit-testing and click handling are done from the GUI's mouseClicked path; this widget is
 * stateless beyond its constants. Drawing requires the schedule scrollbar's first-visible-task
 * offset and visible-row count, both of which the GUI already computes from
 * {@link SmartCraftStatusLayout}.
 */
public final class SmartCraftScheduleListWidget {

    /** Row height. 18 = 16 (icon) + 1 px top + 1 px bottom padding. */
    public static final int ROW_HEIGHT = 18;
    /** Inner padding from the GUI's left edge to where the row starts drawing. */
    public static final int LIST_X_OFFSET = 8;
    /** Inner padding on the right; leaves room for the schedule scrollbar to overlay this widget. */
    public static final int LIST_RIGHT_INSET = 16;

    /** Width of the icon column (16-px ItemStack). */
    private static final int ICON_SIZE = 16;
    /** Pixel offset within the row for the icon's left edge. */
    private static final int ICON_LEFT_PAD = 1;
    /** Gap between the icon and the start of the name text. */
    private static final int TEXT_LEFT_GAP = 4;
    private static final int TEXT_LEFT_PAD = ICON_LEFT_PAD + ICON_SIZE + TEXT_LEFT_GAP;
    /** 3x3 dot in the icon's top-right corner indicating task status at a glance. */
    private static final int STATUS_DOT_SIZE = 3;

    /**
     * Sentinel returned by {@link #hitTest} for the "Overview" pseudo-row at the top of the list.
     * The GUI uses {@link SmartCraftOverlayRenderer#selectOverview()} when this is hit.
     */
    public static final int OVERVIEW_ROW = -2;
    public static final int NO_HIT = -1;

    // --- Color palette: aligned with AE2 GuiColors so the widget feels native to the panel. ---
    private static final int ROW_BG_HOVER = 0x33FFFFFF;
    private static final int ROW_BG_SELECTED = 0x55FFFFFF;
    /** 1-pixel horizontal line drawn between two rows of differing depth. */
    private static final int LAYER_DIVIDER = 0x40000000;
    private static final int TEXT_PRIMARY = 0x404040;
    private static final int TEXT_SECONDARY = 0x707070;
    /** Fallback background for the status dot when the status has no AE2-mapped color. */
    private static final int STATUS_DOT_DEFAULT = 0xFF606060;

    private static final RenderItem ITEM_RENDERER = new RenderItem();

    private SmartCraftScheduleListWidget() {}

    /**
     * Total number of rows the widget would draw if everything were visible. Equals
     * {@code 1 (overview) + tasks.size()} — used by the layout helper to compute the scrollbar
     * thumb size and drag math.
     */
    public static int totalRows(int taskCount) {
        return 1 + Math.max(0, taskCount);
    }

    /**
     * Paint the visible portion of the schedule list at {@code (guiLeft, listY)}, drawing one
     * Overview pseudo-row followed by up to {@code maxTaskRows} task rows starting from
     * {@code firstTask}.
     */
    public static void draw(FontRenderer fr, int guiLeft, int listY, int width, List<TaskView> tasks, int firstTask,
        int maxTaskRows, int selectedTaskIndex, boolean overviewSelected, int mouseX, int mouseY) {
        int innerWidth = Math.max(0, width - LIST_X_OFFSET - LIST_RIGHT_INSET);
        int x1 = guiLeft + LIST_X_OFFSET;

        drawOverviewRow(fr, x1, listY, innerWidth, mouseX, mouseY, overviewSelected);

        int rowY = listY + ROW_HEIGHT;
        int firstIdx = Math.max(0, firstTask);
        int lastIdx = Math.min(tasks.size(), firstIdx + Math.max(0, maxTaskRows));
        int prevDepth = -1;
        for (int i = firstIdx; i < lastIdx; i++) {
            TaskView task = tasks.get(i);
            // Layer divider on depth change. We never draw above the first visible task row \u2014
            // that line would visually merge with the overview row's bottom and look like noise.
            if (i > firstIdx && task.depth() != prevDepth) {
                Gui.drawRect(x1 + 2, rowY, x1 + innerWidth - 2, rowY + 1, LAYER_DIVIDER);
            }
            prevDepth = task.depth();
            drawTaskRow(fr, x1, rowY, innerWidth, task, mouseX, mouseY, selectedTaskIndex == i);
            rowY += ROW_HEIGHT;
        }
    }

    /**
     * Map a screen-space mouse position to a row index. Returns {@link #OVERVIEW_ROW} for the
     * Overview pseudo-row, a non-negative task index for a task row, or {@link #NO_HIT} when the
     * cursor is outside the list bounds.
     */
    public static int hitTest(int mouseX, int mouseY, int guiLeft, int listY, int width, int taskCount, int firstTask,
        int maxTaskRows) {
        int innerWidth = Math.max(0, width - LIST_X_OFFSET - LIST_RIGHT_INSET);
        int x1 = guiLeft + LIST_X_OFFSET;
        int x2 = x1 + innerWidth;
        if (mouseX < x1 || mouseX >= x2) return NO_HIT;

        int rowY = listY;
        if (mouseY >= rowY && mouseY < rowY + ROW_HEIGHT) return OVERVIEW_ROW;
        rowY += ROW_HEIGHT;

        int firstIdx = Math.max(0, firstTask);
        int lastIdx = Math.min(taskCount, firstIdx + Math.max(0, maxTaskRows));
        for (int i = firstIdx; i < lastIdx; i++) {
            if (mouseY >= rowY && mouseY < rowY + ROW_HEIGHT) return i;
            rowY += ROW_HEIGHT;
        }
        return NO_HIT;
    }

    // --- private rendering helpers -----------------------------------------------------------

    private static void drawOverviewRow(FontRenderer fr, int x1, int rowY, int innerWidth, int mouseX, int mouseY,
        boolean selected) {
        boolean hovered = isHovered(mouseX, mouseY, x1, rowY, innerWidth);
        drawRowBackground(x1, rowY, innerWidth, hovered, selected);
        // Draw a small inset square to stand in for the "all tasks" notion. Using a flat rect (no
        // texture lookup) keeps the renderer cheap and avoids requiring a custom UI texture.
        int boxX = x1 + ICON_LEFT_PAD + 2;
        int boxY = rowY + (ROW_HEIGHT - 12) / 2;
        Gui.drawRect(boxX, boxY, boxX + 12, boxY + 12, 0xFF7A7A7A);
        Gui.drawRect(boxX + 1, boxY + 1, boxX + 11, boxY + 11, 0xFFE0E0E0);
        Gui.drawRect(boxX + 3, boxY + 3, boxX + 9, boxY + 4, 0xFF7A7A7A);
        Gui.drawRect(boxX + 3, boxY + 6, boxX + 9, boxY + 7, 0xFF7A7A7A);
        Gui.drawRect(boxX + 3, boxY + 9, boxX + 9, boxY + 10, 0xFF7A7A7A);

        String label = StatCollector.translateToLocal("gui.ae2intelligentscheduling.overview");
        int textY = rowY + (ROW_HEIGHT - 8) / 2;
        fr.drawString(label, x1 + TEXT_LEFT_PAD, textY, TEXT_PRIMARY);
    }

    private static void drawTaskRow(FontRenderer fr, int x1, int rowY, int innerWidth, TaskView task, int mouseX,
        int mouseY, boolean selected) {
        boolean hovered = isHovered(mouseX, mouseY, x1, rowY, innerWidth);
        drawRowBackground(x1, rowY, innerWidth, hovered, selected);

        // 1) item icon
        int iconX = x1 + ICON_LEFT_PAD;
        int iconY = rowY + (ROW_HEIGHT - ICON_SIZE) / 2;
        ItemStack stack = task.itemStack();
        if (stack != null && stack.getItem() != null) {
            drawItemStack(fr, stack, iconX, iconY);
        } else {
            // Fallback: solid color tile so the row never looks empty even if the server failed
            // to attach an itemStack to the TaskView for this task (shouldn't normally happen,
            // but we don't want the layout to collapse if it does).
            Gui.drawRect(iconX, iconY, iconX + ICON_SIZE, iconY + ICON_SIZE, 0xFF606060);
        }

        // 2) status dot in the icon's top-right corner
        int dotColor = statusDotColor(task.status());
        int dotX = iconX + ICON_SIZE - STATUS_DOT_SIZE - 1;
        int dotY = iconY + 1;
        // 1-px black outline so the dot stands out on bright item icons (iron, gold, glass).
        Gui.drawRect(dotX - 1, dotY - 1, dotX + STATUS_DOT_SIZE + 1, dotY + STATUS_DOT_SIZE + 1, 0xFF000000);
        Gui.drawRect(dotX, dotY, dotX + STATUS_DOT_SIZE, dotY + STATUS_DOT_SIZE, dotColor);

        // 3) right-aligned status text + split progress
        String statusText = statusLabel(task);
        int statusW = fr.getStringWidth(statusText);
        int textY = rowY + (ROW_HEIGHT - 8) / 2;
        fr.drawString(statusText, x1 + innerWidth - statusW - 2, textY, statusTextColor(task.status()));

        // 4) middle: display name x amount, truncated to fit the gap between icon and status text
        int nameAreaWidth = innerWidth - TEXT_LEFT_PAD - statusW - 6;
        if (nameAreaWidth > 0) {
            String label = taskLabel(task);
            label = truncateToWidth(fr, label, nameAreaWidth);
            fr.drawString(label, x1 + TEXT_LEFT_PAD, textY, TEXT_PRIMARY);
        }
    }

    private static void drawItemStack(FontRenderer fr, ItemStack stack, int x, int y) {
        // RenderItem requires lighting + alpha state — push/pop so we don't taint the GUI's later
        // text draws (font renderer dislikes a leftover GL_LIGHTING enabled).
        GL11.glPushAttrib(GL11.GL_ENABLE_BIT);
        RenderHelper.enableGUIStandardItemLighting();
        GL11.glEnable(GL11.GL_DEPTH_TEST);
        GL11.glColor4f(1.0f, 1.0f, 1.0f, 1.0f);
        ITEM_RENDERER.zLevel = 100.0F;
        ITEM_RENDERER.renderItemAndEffectIntoGUI(
            fr,
            Minecraft.getMinecraft()
                .getTextureManager(),
            stack,
            x,
            y);
        // We deliberately skip renderItemOverlayIntoGUI (which prints stack count over the icon)
        // \u2014 our amount column already prints "x N" in the name text, and the AE2 plan amounts are
        // often huge (10000+) which would overflow the 16x16 cell.
        ITEM_RENDERER.zLevel = 0.0F;
        RenderHelper.disableStandardItemLighting();
        GL11.glPopAttrib();
        GL11.glColor4f(1.0f, 1.0f, 1.0f, 1.0f);
    }

    private static void drawRowBackground(int x1, int rowY, int innerWidth, boolean hovered, boolean selected) {
        if (selected) {
            Gui.drawRect(x1, rowY, x1 + innerWidth, rowY + ROW_HEIGHT, ROW_BG_SELECTED);
        } else if (hovered) {
            Gui.drawRect(x1, rowY, x1 + innerWidth, rowY + ROW_HEIGHT, ROW_BG_HOVER);
        }
    }

    private static boolean isHovered(int mouseX, int mouseY, int x1, int rowY, int innerWidth) {
        return mouseX >= x1 && mouseX < x1 + innerWidth && mouseY >= rowY && mouseY < rowY + ROW_HEIGHT;
    }

    private static String taskLabel(TaskView task) {
        // Prefer the localised display name when we have an itemStack \u2014 it reads correctly across
        // languages and respects mod-supplied formatting (color codes, NBT-suffixed names, etc.).
        ItemStack stack = task.itemStack();
        String name;
        if (stack != null && stack.getItem() != null) {
            try {
                name = stack.getDisplayName();
            } catch (RuntimeException e) {
                // Some mods throw from getDisplayName when the stack is missing NBT they expect.
                // Fall back to the requestKeyId rather than blowing up the whole row.
                name = task.requestKeyId();
            }
        } else {
            name = task.requestKeyId();
            int colon = name.lastIndexOf(':');
            if (colon >= 0 && colon < name.length() - 1) {
                name = name.substring(colon + 1);
            }
            name = name.replace('_', ' ');
        }
        return name + " \u00d7" + compact(task.amount());
    }

    private static String statusLabel(TaskView task) {
        SmartCraftStatus s = task.status();
        String base = s == null ? "" : statusShortName(s);
        if (task.splitCount() > 1) {
            return base + " " + task.splitIndex() + "/" + task.splitCount();
        }
        return base;
    }

    /**
     * Compress the verbose status enum into a 1-2 word label that fits the right column.
     * SUBMITTING/VERIFYING_OUTPUT/RUNNING all read as "Crafting" because from a player's POV that
     * is what's happening; the detail panel still shows the precise state for those who care.
     */
    private static String statusShortName(SmartCraftStatus s) {
        switch (s) {
            case PENDING:
            case QUEUED:
                return "Pending";
            case WAITING_CPU:
                return "Wait CPU";
            case SUBMITTING:
                return "Plan...";
            case RUNNING:
                return "Crafting";
            case VERIFYING_OUTPUT:
                return "Verify";
            case PAUSED:
                return "Paused";
            case DONE:
            case COMPLETED:
                return "Done";
            case FAILED:
                return "Failed";
            case CANCELLED:
                return "Cancel";
            default:
                return s.name();
        }
    }

    /**
     * Color of the small status indicator dot in the icon's corner. Bright, fully-opaque palette
     * \u2014 the dot is only 3x3 px so it must read at a glance against busy item icons.
     */
    private static int statusDotColor(SmartCraftStatus s) {
        if (s == null) return STATUS_DOT_DEFAULT;
        switch (s) {
            case RUNNING:
            case SUBMITTING:
            case VERIFYING_OUTPUT:
                return 0xFF3B82F6; // blue
            case DONE:
            case COMPLETED:
                return 0xFF22C55E; // green
            case PENDING:
            case QUEUED:
                return 0xFF9CA3AF; // gray
            case WAITING_CPU:
            case PAUSED:
                return 0xFFF59E0B; // amber
            case FAILED:
                return 0xFFEF4444; // red
            case CANCELLED:
                return 0xFF6B7280; // dim gray
            default:
                return STATUS_DOT_DEFAULT;
        }
    }

    private static int statusTextColor(SmartCraftStatus s) {
        if (s == null) return TEXT_SECONDARY;
        switch (s) {
            case FAILED:
                return GuiColors.CellStatusRed.getColor();
            case WAITING_CPU:
            case PAUSED:
                return GuiColors.CellStatusOrange.getColor();
            case RUNNING:
            case SUBMITTING:
            case VERIFYING_OUTPUT:
                return GuiColors.CellStatusBlue.getColor();
            case DONE:
            case COMPLETED:
                return GuiColors.CellStatusGreen.getColor();
            default:
                return TEXT_SECONDARY;
        }
    }

    private static String truncateToWidth(FontRenderer fr, String text, int maxWidth) {
        if (fr.getStringWidth(text) <= maxWidth) return text;
        String ellipsis = "...";
        int ellW = fr.getStringWidth(ellipsis);
        if (ellW >= maxWidth) return ""; // pathological narrow column
        // Linear scan from the end is fine for our short labels (<= 64 chars typically).
        for (int len = text.length() - 1; len > 0; len--) {
            String candidate = text.substring(0, len);
            if (fr.getStringWidth(candidate) + ellW <= maxWidth) {
                return candidate + ellipsis;
            }
        }
        return ellipsis;
    }

    /**
     * Compact integer formatter (1.2k, 3M, 5G). Mirrors the helper in
     * {@link SmartCraftOverlayRenderer} so the row labels match the rest of the GUI's number
     * style. Duplicated here intentionally to keep this widget free of cross-class coupling.
     */
    private static String compact(long n) {
        long abs = Math.abs(n);
        if (abs < 1_000L) return Long.toString(n);
        if (abs < 1_000_000L) return formatScaled(n, 1_000L) + "k";
        if (abs < 1_000_000_000L) return formatScaled(n, 1_000_000L) + "M";
        return formatScaled(n, 1_000_000_000L) + "G";
    }

    private static String formatScaled(long n, long divisor) {
        double v = (double) n / (double) divisor;
        // 1 decimal digit if the integer part is < 10, else round to integer to keep labels short.
        if (Math.abs(v) < 10.0d) {
            return String.format(java.util.Locale.ROOT, "%.1f", Double.valueOf(v));
        }
        return Long.toString(Math.round(v));
    }
}
