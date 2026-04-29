package com.homeftw.ae2intelligentscheduling.client.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.homeftw.ae2intelligentscheduling.network.packet.SyncSmartCraftOrderPacket;

/**
 * Pure-arithmetic tests for the multi-order tab strip. Only verifies the visibility / scroll /
 * hit-test math — rendering can't be exercised here without a live OpenGL context, but that's
 * acceptable because the renderer is a thin wrapper around the same math (see drawTab / drawArrow
 * which take pre-computed coordinates).
 */
class SmartCraftOrderTabsWidgetTest {

    private static final int GUI_LEFT = 0;
    private static final int STRIP_Y = 0;
    private static final int STRIP_WIDTH = 238; // matches SmartCraftStatusLayout.GUI_WIDTH

    @Test
    void visible_tab_count_uses_full_width_when_everything_fits() {
        // (238 - 8 - 8) / 22 = 222 / 22 = 10. With 5 tabs we don't need scroll arrows so all 5
        // fit comfortably and the math gives back 5.
        int visible = SmartCraftOrderTabsWidget.visibleTabCount(STRIP_WIDTH, 5);
        assertEquals(5, visible);
    }

    @Test
    void visible_tab_count_reserves_space_for_arrows_when_overflowing() {
        // 30 tabs definitely overflow; arrows kick in. innerWidth = 238-8-8 = 222.
        // After reserving the two 12-px arrows + 4-px gap: 222 - 24 - 4 = 194. 194 / 22 = 8.
        int visible = SmartCraftOrderTabsWidget.visibleTabCount(STRIP_WIDTH, 30);
        assertEquals(8, visible);
    }

    @Test
    void clamp_scroll_keeps_window_inside_bounds() {
        // 30 tabs, 8 visible → max scroll is 22. Anything above that or below 0 must be clamped.
        assertEquals(0, SmartCraftOrderTabsWidget.clampScroll(-5, 30, 8));
        assertEquals(22, SmartCraftOrderTabsWidget.clampScroll(99, 30, 8));
        assertEquals(7, SmartCraftOrderTabsWidget.clampScroll(7, 30, 8));
    }

    @Test
    void clamp_scroll_returns_zero_when_no_overflow() {
        // 5 tabs all visible → max scroll is 0. Even a positive request is clamped down.
        assertEquals(0, SmartCraftOrderTabsWidget.clampScroll(5, 5, 5));
    }

    @Test
    void hit_test_picks_correct_tab_when_no_arrows() {
        // 3 tabs, all visible. First tab starts at GUI_LEFT + 8. Each tab is 22px wide.
        List<SyncSmartCraftOrderPacket> tabs = makeTabs(3);
        // Click center of tab index 1: x = 8 + 22 + 11 = 41, y = 10.
        String hit = SmartCraftOrderTabsWidget.hitTest(41, 10, GUI_LEFT, STRIP_Y, STRIP_WIDTH, tabs, 0);
        assertEquals(
            tabs.get(1)
                .getOrderId(),
            hit,
            "click in middle tab returns its orderId");
    }

    @Test
    void hit_test_returns_null_when_clicking_outside_strip() {
        List<SyncSmartCraftOrderPacket> tabs = makeTabs(3);
        // y above the strip
        assertNull(SmartCraftOrderTabsWidget.hitTest(20, -5, GUI_LEFT, STRIP_Y, STRIP_WIDTH, tabs, 0));
        // y below the strip (TAB_HEIGHT == 20)
        assertNull(SmartCraftOrderTabsWidget.hitTest(20, 25, GUI_LEFT, STRIP_Y, STRIP_WIDTH, tabs, 0));
    }

    @Test
    void hit_test_picks_arrows_when_overflowing() {
        List<SyncSmartCraftOrderPacket> tabs = makeTabs(30); // forces arrows
        // Left arrow occupies [GUI_LEFT+8, GUI_LEFT+8+12); only enabled when scroll > 0.
        // With scroll = 5 it should respond.
        String left = SmartCraftOrderTabsWidget.hitTest(12, 10, GUI_LEFT, STRIP_Y, STRIP_WIDTH, tabs, 5);
        assertEquals(SmartCraftOrderTabsWidget.SCROLL_LEFT, left);
        // Right arrow at GUI_LEFT + STRIP_WIDTH - 8 - 12 = 218 to 230.
        String right = SmartCraftOrderTabsWidget.hitTest(222, 10, GUI_LEFT, STRIP_Y, STRIP_WIDTH, tabs, 5);
        assertEquals(SmartCraftOrderTabsWidget.SCROLL_RIGHT, right);
    }

    @Test
    void hit_test_disables_left_arrow_at_scroll_zero() {
        List<SyncSmartCraftOrderPacket> tabs = makeTabs(30);
        // Click on the disabled left arrow at scroll=0 returns null — the click was over the
        // arrow's pixels but the arrow refuses to fire because there's nothing to scroll left.
        String hit = SmartCraftOrderTabsWidget.hitTest(12, 10, GUI_LEFT, STRIP_Y, STRIP_WIDTH, tabs, 0);
        assertNull(hit);
    }

    @Test
    void hit_test_disables_right_arrow_at_max_scroll() {
        List<SyncSmartCraftOrderPacket> tabs = makeTabs(30);
        int visible = SmartCraftOrderTabsWidget.visibleTabCount(STRIP_WIDTH, 30);
        int maxScroll = 30 - visible;
        String hit = SmartCraftOrderTabsWidget.hitTest(222, 10, GUI_LEFT, STRIP_Y, STRIP_WIDTH, tabs, maxScroll);
        assertNull(hit, "right arrow disabled at max scroll");
    }

    @Test
    void hit_test_offsets_tabs_by_scroll() {
        List<SyncSmartCraftOrderPacket> tabs = makeTabs(30);
        // With scroll = 3, the leftmost VISIBLE tab is index 3 (the actual indexes 0/1/2 are
        // hidden). First visible tab starts at GUI_LEFT + 8 + 12 + 2 = 22 (after left arrow).
        // So clicking at x=33 (22 + 11, middle of the first visible tab) selects tab index 3.
        String hit = SmartCraftOrderTabsWidget.hitTest(33, 10, GUI_LEFT, STRIP_Y, STRIP_WIDTH, tabs, 3);
        assertEquals(
            tabs.get(3)
                .getOrderId(),
            hit);
    }

    @Test
    void hit_test_returns_null_when_no_tabs() {
        // Empty tab list means there's nothing to hit. Defensive: a click anywhere just falls
        // through so the GUI's mouseClicked can keep dispatching.
        List<SyncSmartCraftOrderPacket> tabs = new ArrayList<SyncSmartCraftOrderPacket>();
        assertNull(SmartCraftOrderTabsWidget.hitTest(50, 10, GUI_LEFT, STRIP_Y, STRIP_WIDTH, tabs, 0));
    }

    private static List<SyncSmartCraftOrderPacket> makeTabs(int n) {
        List<SyncSmartCraftOrderPacket> list = new ArrayList<SyncSmartCraftOrderPacket>(n);
        for (int i = 0; i < n; i++) {
            list.add(StubOrderPacket.withId("order-" + i));
        }
        return list;
    }
}
