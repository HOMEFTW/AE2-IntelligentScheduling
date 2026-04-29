package com.homeftw.ae2intelligentscheduling.client.gui;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.client.renderer.entity.RenderItem;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumChatFormatting;

import org.lwjgl.opengl.GL11;

import com.homeftw.ae2intelligentscheduling.network.packet.SyncSmartCraftOrderPacket;
import com.homeftw.ae2intelligentscheduling.smartcraft.model.SmartCraftStatus;

/**
 * v0.1.7 multi-order tab strip rendered horizontally above the status grid. Each tab is a 22x22
 * cell with a 16x16 item icon (the order's root request item) and a 3x3 status dot in the
 * top-right corner — visually matching {@link SmartCraftScheduleListWidget}'s row icons. Hovering
 * a tab shows a tooltip {@code [Player] item ×N · status · scale}, so the player can identify a
 * tab without crowding the strip with text.
 *
 * <p>
 * When the total tab width exceeds the available strip width, two 12x16 arrow buttons appear at
 * each end (◀ ▶) and the visible window scrolls one tab at a time. Scroll state is owned by the
 * widget's caller (typically {@code GuiSmartCraftStatus}) and passed in on every {@link #draw}
 * call; this keeps the widget itself stateless beyond constants.
 *
 * <p>
 * Hit-testing returns either an order ID string (when a tab is clicked), {@link #SCROLL_LEFT} /
 * {@link #SCROLL_RIGHT} sentinels (arrow buttons), or null when the cursor is outside.
 */
public final class SmartCraftOrderTabsWidget {

    /** Total height of the tab strip including the inactive-tab top padding. */
    public static final int STRIP_HEIGHT = 22;

    /** Width of a single tab cell (16-px icon + 3-px padding on each side). */
    public static final int TAB_WIDTH = 22;
    /** Height of a single tab cell. */
    public static final int TAB_HEIGHT = 20;

    /** Width of the scroll-arrow buttons that appear when tabs overflow. */
    public static final int ARROW_WIDTH = 12;

    /** Inset from the GUI's left edge before the first tab (matches the schedule list inset). */
    public static final int LEFT_INSET = 8;
    /** Mirror inset on the right so the strip lines up symmetrically with the schedule list. */
    public static final int RIGHT_INSET = 8;

    /** Sentinel returned by {@link #hitTest} when the click landed on the left scroll arrow. */
    public static final String SCROLL_LEFT = "__scroll_left__";
    /** Sentinel returned by {@link #hitTest} when the click landed on the right scroll arrow. */
    public static final String SCROLL_RIGHT = "__scroll_right__";

    private static final int ICON_SIZE = 16;
    private static final int ICON_INSET = (TAB_WIDTH - ICON_SIZE) / 2;
    private static final int STATUS_DOT_SIZE = 3;

    // --- Color palette: matches SmartCraftScheduleListWidget so the tab strip and the row list
    // read as a single visual language. -----------------------------------------------------
    private static final int TAB_BG_INACTIVE = 0x66303030;
    private static final int TAB_BG_HOVER = 0x99404040;
    private static final int TAB_BG_ACTIVE = 0xFF5A5A5A;
    private static final int TAB_BORDER_ACTIVE = 0xFFE0E0E0;
    private static final int TAB_BORDER_INACTIVE = 0x66808080;
    /** Bottom highlight bar drawn under the active tab. */
    private static final int TAB_ACCENT_ACTIVE = 0xFFFFCC33;
    private static final int ARROW_BG = 0x99303030;
    private static final int ARROW_BG_HOVER = 0xCC505050;
    private static final int ARROW_FG = 0xFFE0E0E0;
    private static final int ARROW_FG_DISABLED = 0x66808080;
    private static final int STATUS_DOT_DEFAULT = 0xFF606060;

    /**
     * Lazy-initialised because {@link RenderItem}'s constructor pokes at OpenGL / texture state
     * during class init; instantiating it eagerly as a {@code static final} field would crash the
     * pure-arithmetic widget tests with NoClassDefFoundError under a headless JVM. Production
     * paths always go through {@link #drawItemStack} which gates on a non-null Minecraft instance
     * before getting here.
     */
    private static RenderItem itemRenderer;

    private static RenderItem itemRenderer() {
        if (itemRenderer == null) {
            itemRenderer = new RenderItem();
        }
        return itemRenderer;
    }

    private SmartCraftOrderTabsWidget() {}

    /**
     * Compute the maximum number of tabs the available width can show, given that scroll arrows
     * occupy the leftmost / rightmost slots when they are needed. When all tabs fit naturally,
     * the arrows are hidden so the entire strip width is available for tabs.
     */
    public static int visibleTabCount(int stripWidth, int totalTabs) {
        int innerWidth = Math.max(0, stripWidth - LEFT_INSET - RIGHT_INSET);
        int withoutArrows = innerWidth / TAB_WIDTH;
        if (totalTabs <= withoutArrows) {
            return totalTabs;
        }
        // Reserve space for both arrows when scrolling kicks in.
        int withArrows = Math.max(0, (innerWidth - 2 * ARROW_WIDTH - 4) / TAB_WIDTH);
        return Math.min(totalTabs, withArrows);
    }

    /**
     * Clamp the scroll offset into the valid range [0, totalTabs - visible]. Used by the GUI
     * after the orders list shrinks to keep the visible window inside bounds.
     */
    public static int clampScroll(int scroll, int totalTabs, int visible) {
        int max = Math.max(0, totalTabs - visible);
        if (scroll < 0) return 0;
        if (scroll > max) return max;
        return scroll;
    }

    /**
     * Paint the strip at {@code (guiLeft, stripY)} with the orders list and current scroll /
     * focus state. Stateless: caller supplies all variable state.
     */
    public static void draw(FontRenderer fr, int guiLeft, int stripY, int stripWidth,
        List<SyncSmartCraftOrderPacket> tabs, String currentOrderId, int scroll, int mouseX, int mouseY) {
        int total = tabs.size();
        int visible = visibleTabCount(stripWidth, total);
        boolean needsArrows = total > visible;
        int tabsStartX = guiLeft + LEFT_INSET + (needsArrows ? ARROW_WIDTH + 2 : 0);

        // Left arrow
        if (needsArrows) {
            int leftArrowX = guiLeft + LEFT_INSET;
            boolean leftEnabled = scroll > 0;
            boolean leftHover = leftEnabled && pointIn(mouseX, mouseY, leftArrowX, stripY, ARROW_WIDTH, TAB_HEIGHT);
            drawArrow(fr, leftArrowX, stripY, true, leftEnabled, leftHover);
        }

        // Tabs
        int firstIdx = Math.max(0, scroll);
        int lastIdx = Math.min(total, firstIdx + visible);
        int hoverTabIdx = -1;
        for (int i = firstIdx; i < lastIdx; i++) {
            int tabX = tabsStartX + (i - firstIdx) * TAB_WIDTH;
            SyncSmartCraftOrderPacket tab = tabs.get(i);
            boolean active = currentOrderId != null && currentOrderId.equals(tab.getOrderId());
            boolean hover = pointIn(mouseX, mouseY, tabX, stripY, TAB_WIDTH, TAB_HEIGHT);
            if (hover) hoverTabIdx = i;
            drawTab(fr, tabX, stripY, tab, active, hover);
        }

        // Right arrow
        if (needsArrows) {
            int rightArrowX = guiLeft + stripWidth - RIGHT_INSET - ARROW_WIDTH;
            boolean rightEnabled = scroll < total - visible;
            boolean rightHover = rightEnabled && pointIn(mouseX, mouseY, rightArrowX, stripY, ARROW_WIDTH, TAB_HEIGHT);
            drawArrow(fr, rightArrowX, stripY, false, rightEnabled, rightHover);
        }

        // Tooltip drawn last so it overlays everything else. Defer to the GUI's tooltip pipeline
        // by returning the relevant tab — but we don't have that wiring, so render inline.
        if (hoverTabIdx >= 0) {
            drawHoverTooltip(fr, tabs.get(hoverTabIdx), mouseX, mouseY);
        }
    }

    /**
     * Map a mouse click to either an order ID (string), a scroll sentinel, or null when missed.
     * Caller passes the same scroll value used during the last {@link #draw}.
     */
    public static String hitTest(int mouseX, int mouseY, int guiLeft, int stripY, int stripWidth,
        List<SyncSmartCraftOrderPacket> tabs, int scroll) {
        int total = tabs.size();
        int visible = visibleTabCount(stripWidth, total);
        boolean needsArrows = total > visible;

        if (needsArrows) {
            int leftArrowX = guiLeft + LEFT_INSET;
            if (pointIn(mouseX, mouseY, leftArrowX, stripY, ARROW_WIDTH, TAB_HEIGHT) && scroll > 0) {
                return SCROLL_LEFT;
            }
            int rightArrowX = guiLeft + stripWidth - RIGHT_INSET - ARROW_WIDTH;
            if (pointIn(mouseX, mouseY, rightArrowX, stripY, ARROW_WIDTH, TAB_HEIGHT) && scroll < total - visible) {
                return SCROLL_RIGHT;
            }
        }

        int tabsStartX = guiLeft + LEFT_INSET + (needsArrows ? ARROW_WIDTH + 2 : 0);
        int firstIdx = Math.max(0, scroll);
        int lastIdx = Math.min(total, firstIdx + visible);
        for (int i = firstIdx; i < lastIdx; i++) {
            int tabX = tabsStartX + (i - firstIdx) * TAB_WIDTH;
            if (pointIn(mouseX, mouseY, tabX, stripY, TAB_WIDTH, TAB_HEIGHT)) {
                return tabs.get(i)
                    .getOrderId();
            }
        }
        return null;
    }

    // --- private helpers ---------------------------------------------------------------------

    private static void drawTab(FontRenderer fr, int x, int y, SyncSmartCraftOrderPacket tab, boolean active,
        boolean hover) {
        int bg = active ? TAB_BG_ACTIVE : (hover ? TAB_BG_HOVER : TAB_BG_INACTIVE);
        int border = active ? TAB_BORDER_ACTIVE : TAB_BORDER_INACTIVE;
        // Border + fill (1-px outline so adjacent tabs are visually separable).
        Gui.drawRect(x, y, x + TAB_WIDTH, y + TAB_HEIGHT, border);
        Gui.drawRect(x + 1, y + 1, x + TAB_WIDTH - 1, y + TAB_HEIGHT - 1, bg);

        // Item icon
        int iconX = x + ICON_INSET;
        int iconY = y + (TAB_HEIGHT - ICON_SIZE) / 2;
        ItemStack stack = rootItemStack(tab);
        if (stack != null && stack.getItem() != null) {
            drawItemStack(fr, stack, iconX, iconY);
        } else {
            // Solid fallback so an icon-less tab is still visually a tab. Same color as the
            // schedule list's missing-icon fallback for consistency.
            Gui.drawRect(iconX, iconY, iconX + ICON_SIZE, iconY + ICON_SIZE, 0xFF606060);
        }

        // Status dot in the top-right corner of the icon (mirrors schedule row visuals).
        int dotColor = statusDotColor(parseOrderStatus(tab.getStatus()));
        int dotX = iconX + ICON_SIZE - STATUS_DOT_SIZE - 1;
        int dotY = iconY + 1;
        Gui.drawRect(dotX - 1, dotY - 1, dotX + STATUS_DOT_SIZE + 1, dotY + STATUS_DOT_SIZE + 1, 0xFF000000);
        Gui.drawRect(dotX, dotY, dotX + STATUS_DOT_SIZE, dotY + STATUS_DOT_SIZE, dotColor);

        // Active-tab accent: a 2-px bar along the bottom edge.
        if (active) {
            Gui.drawRect(x + 1, y + TAB_HEIGHT - 2, x + TAB_WIDTH - 1, y + TAB_HEIGHT, TAB_ACCENT_ACTIVE);
        }
    }

    private static void drawArrow(FontRenderer fr, int x, int y, boolean leftFacing, boolean enabled, boolean hover) {
        int bg = hover ? ARROW_BG_HOVER : ARROW_BG;
        Gui.drawRect(x, y, x + ARROW_WIDTH, y + TAB_HEIGHT, bg);
        String glyph = leftFacing ? "<" : ">";
        int color = enabled ? ARROW_FG : ARROW_FG_DISABLED;
        int textW = fr.getStringWidth(glyph);
        fr.drawString(glyph, x + (ARROW_WIDTH - textW) / 2, y + (TAB_HEIGHT - 8) / 2, color);
    }

    private static void drawItemStack(FontRenderer fr, ItemStack stack, int x, int y) {
        // Same lighting / depth dance as SmartCraftScheduleListWidget so tabs and rows render
        // identically in their item icon column.
        GL11.glPushAttrib(GL11.GL_ENABLE_BIT);
        RenderHelper.enableGUIStandardItemLighting();
        GL11.glEnable(GL11.GL_DEPTH_TEST);
        GL11.glColor4f(1.0f, 1.0f, 1.0f, 1.0f);
        RenderItem renderer = itemRenderer();
        renderer.zLevel = 100.0F;
        renderer.renderItemAndEffectIntoGUI(
            fr,
            Minecraft.getMinecraft()
                .getTextureManager(),
            stack,
            x,
            y);
        renderer.zLevel = 0.0F;
        RenderHelper.disableStandardItemLighting();
        GL11.glPopAttrib();
        GL11.glColor4f(1.0f, 1.0f, 1.0f, 1.0f);
    }

    private static void drawHoverTooltip(FontRenderer fr, SyncSmartCraftOrderPacket tab, int mouseX, int mouseY) {
        List<String> lines = new ArrayList<String>();
        ItemStack stack = rootItemStack(tab);
        String itemName;
        if (stack != null && stack.getItem() != null) {
            try {
                itemName = stack.getDisplayName();
            } catch (RuntimeException e) {
                itemName = tab.getTargetRequestKeyId();
            }
        } else {
            itemName = tab.getTargetRequestKeyId();
        }
        String ownerPrefix = tab.getOwnerName() == null || tab.getOwnerName()
            .isEmpty() ? "" : "[" + tab.getOwnerName() + "] ";
        lines.add(EnumChatFormatting.WHITE + ownerPrefix + itemName);
        lines.add(
            EnumChatFormatting.GRAY + "x"
                + tab.getTargetAmount()
                + EnumChatFormatting.DARK_GRAY
                + "  ("
                + tab.getOrderScale()
                + ")");
        SmartCraftStatus status = parseOrderStatus(tab.getStatus());
        lines.add(chatColorForStatus(status) + (status == null ? "-" : status.name()));

        int maxW = 0;
        for (String l : lines) maxW = Math.max(maxW, fr.getStringWidth(l));
        int bx = mouseX + 12, by = mouseY - 12;
        Gui.drawRect(bx - 3, by - 3, bx + maxW + 3, by + lines.size() * 10 + 3, 0xC0000000);
        for (int i = 0; i < lines.size(); i++) {
            fr.drawString(lines.get(i), bx, by + i * 10, 0xFFFFFFFF);
        }
    }

    private static ItemStack rootItemStack(SyncSmartCraftOrderPacket tab) {
        // (v0.1.9.1) Always show the order's FINAL PRODUCT as the tab icon, never a leaf task.
        // Earlier versions iterated tasks and grabbed the first non-null itemStack which, given
        // the builder's height-from-leaves ordering, was always a low-tier raw material (Iron
        // Ingot etc.) — so every tab tended to show the same icon and players could not tell
        // their orders apart. The packet now ships the root itemStack explicitly via
        // {@link SyncSmartCraftOrderPacket#getTargetItemStack}.
        ItemStack target = tab.getTargetItemStack();
        if (target != null) return target;
        // Fallback for legacy packets / fluid targets where the root has no item form: probe the
        // task list for the first non-null stack so the tab still renders SOMETHING. Imperfect
        // but keeps the GUI usable when the upgrade path crosses a server boundary.
        for (SyncSmartCraftOrderPacket.TaskView t : tab.getTasks()) {
            if (t.itemStack() != null) return t.itemStack();
        }
        return null;
    }

    private static SmartCraftStatus parseOrderStatus(String s) {
        if (s == null) return null;
        try {
            return SmartCraftStatus.valueOf(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static int statusDotColor(SmartCraftStatus s) {
        if (s == null) return STATUS_DOT_DEFAULT;
        switch (s) {
            case RUNNING:
            case SUBMITTING:
            case VERIFYING_OUTPUT:
                return 0xFF3B82F6;
            case DONE:
            case COMPLETED:
                return 0xFF22C55E;
            case PENDING:
            case QUEUED:
                return 0xFF9CA3AF;
            case WAITING_CPU:
            case PAUSED:
                return 0xFFF59E0B;
            case FAILED:
                return 0xFFEF4444;
            case CANCELLED:
                return 0xFF6B7280;
            default:
                return STATUS_DOT_DEFAULT;
        }
    }

    private static EnumChatFormatting chatColorForStatus(SmartCraftStatus s) {
        if (s == null) return EnumChatFormatting.WHITE;
        switch (s) {
            case RUNNING:
            case SUBMITTING:
            case VERIFYING_OUTPUT:
                return EnumChatFormatting.AQUA;
            case PENDING:
            case QUEUED:
            case WAITING_CPU:
                return EnumChatFormatting.YELLOW;
            case PAUSED:
                return EnumChatFormatting.GOLD;
            case DONE:
            case COMPLETED:
                return EnumChatFormatting.GREEN;
            case FAILED:
                return EnumChatFormatting.RED;
            case CANCELLED:
                return EnumChatFormatting.DARK_GRAY;
            default:
                return EnumChatFormatting.WHITE;
        }
    }

    private static boolean pointIn(int px, int py, int x, int y, int w, int h) {
        return px >= x && px < x + w && py >= y && py < y + h;
    }
}
