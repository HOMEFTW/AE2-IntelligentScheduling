package com.homeftw.ae2intelligentscheduling.client.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class SmartCraftConfirmButtonLayoutTest {

    // (v0.1.7.3) Vanilla 52x20 GuiButton text buttons hung 4 px outside the AE2 GUI right edge.
    // Buttons stack bottom-up: slot 0 is flush with AE2 Start row (ySize - 25); slot 1 is one
    // pitch (button height 20 + 2 px gap) above so it never collides with AE2's top-row tabs.
    private static final int BUTTON_WIDTH = 52;
    private static final int BUTTON_HEIGHT = 20;
    private static final int RIGHT_GAP = 4;
    private static final int BOTTOM_OFFSET = 25;
    private static final int SLOT_PITCH = BUTTON_HEIGHT + 2;

    @Test
    void smart_craft_button_sits_one_slot_above_the_start_row_outside_the_gui_frame() {
        SmartCraftConfirmButtonLayout.Position position = SmartCraftConfirmButtonLayout.position(10, 20, 238, 206);

        // x is guiLeft + xSize + 4 — hangs outside the AE2 GUI body so it can't overlap content.
        assertEquals(10 + 238 + RIGHT_GAP, position.x(), "smart-craft button hangs 4 px outside the GUI right edge");
        assertEquals(
            20 + 206 - BOTTOM_OFFSET - SLOT_PITCH,
            position.y(),
            "smart-craft button sits one slot above the Start button row");
        assertEquals(BUTTON_WIDTH, position.width(), "button width matches AE2 Start button (52 px)");
        assertEquals(BUTTON_HEIGHT, position.height(), "button height matches AE2 Start button (20 px)");
    }

    @Test
    void view_status_on_confirm_aligns_with_the_start_button_row() {
        SmartCraftConfirmButtonLayout.Position smartCraft = SmartCraftConfirmButtonLayout.position(10, 20, 238, 206);
        SmartCraftConfirmButtonLayout.Position viewStatus = SmartCraftConfirmButtonLayout
            .viewStatusOnConfirmPosition(10, 20, 238, 206);

        assertEquals(smartCraft.x(), viewStatus.x(), "both buttons share the right column outside the GUI");
        assertEquals(
            20 + 206 - BOTTOM_OFFSET,
            viewStatus.y(),
            "view-status button sits flush with the AE2 Start button row");
        assertEquals(
            smartCraft.y() + SLOT_PITCH,
            viewStatus.y(),
            "view-status sits one slot below smart-craft (smart-craft above, view-status at Start row)");
        assertEquals(BUTTON_WIDTH, viewStatus.width());
        assertEquals(BUTTON_HEIGHT, viewStatus.height());
    }

    @Test
    void buttons_never_intrude_into_the_ae2_gui_frame() {
        SmartCraftConfirmButtonLayout.Position smartCraft = SmartCraftConfirmButtonLayout.position(0, 0, 238, 206);
        SmartCraftConfirmButtonLayout.Position viewStatus = SmartCraftConfirmButtonLayout
            .viewStatusOnConfirmPosition(0, 0, 238, 206);
        org.junit.jupiter.api.Assertions
            .assertTrue(smartCraft.x() >= 238, "smart-craft button must not bleed into the AE2 GUI body");
        org.junit.jupiter.api.Assertions
            .assertTrue(viewStatus.x() >= 238, "view-status button must not bleed into the AE2 GUI body");
        // Also verify the smart-craft button's bottom edge does not encroach on the Start row.
        org.junit.jupiter.api.Assertions.assertTrue(
            smartCraft.y() + smartCraft.height() <= 206 - BOTTOM_OFFSET,
            "smart-craft button must not overlap the Start button row");
    }

    @Test
    void view_status_on_terminal_aligns_with_the_start_row_offset_when_alone() {
        SmartCraftConfirmButtonLayout.Position pos = SmartCraftConfirmButtonLayout
            .viewStatusOnTerminalPosition(0, 0, 195, 222);

        // Terminal screens have no Start button, but the view-status button keeps the same
        // bottom-aligned y for a consistent location across screens.
        assertEquals(195 + RIGHT_GAP, pos.x());
        assertEquals(222 - BOTTOM_OFFSET, pos.y());
        assertEquals(BUTTON_WIDTH, pos.width());
        assertEquals(BUTTON_HEIGHT, pos.height());
    }

    @Test
    void view_status_on_crafting_status_aligns_with_the_start_row_offset_when_alone() {
        SmartCraftConfirmButtonLayout.Position pos = SmartCraftConfirmButtonLayout
            .viewStatusOnCraftingStatusPosition(0, 0, 256, 222);

        assertEquals(256 + RIGHT_GAP, pos.x());
        assertEquals(222 - BOTTOM_OFFSET, pos.y());
        assertEquals(BUTTON_WIDTH, pos.width());
        assertEquals(BUTTON_HEIGHT, pos.height());
    }

    @Test
    void retry_button_right_edge_clears_ae2_scrollbar_and_frame() {
        // (v0.1.7.5) Regression for the 2026-04-29_12.34.07 screenshot: previously retryPosition
        // anchored at (xSize - 6 - BUTTON_WIDTH) → right edge = xSize - 6 = 232 on a 238-wide GUI.
        // AE2's craftingcpu.png reserves [218..230] for the scrollbar column and [230..238] for
        // the GUI frame. The button visibly extended into both. AE2's own cancel button right-
        // edges at 213 (CANCEL_LEFT_OFFSET=163 + CANCEL_WIDTH=50). Pin retry to the same line.
        int guiLeft = 100;
        int xSize = 238; // SmartCraftStatusLayout.GUI_WIDTH
        SmartCraftConfirmButtonLayout.Position retry = SmartCraftConfirmButtonLayout
            .retryPosition(guiLeft, 0, xSize, 200);

        int rightEdgeRel = (retry.x() - guiLeft) + retry.width();
        org.junit.jupiter.api.Assertions
            .assertTrue(rightEdgeRel <= 213, "retry right edge (" + rightEdgeRel + ") must not enter the AE2 scrollbar column [218..230]");
        // Sanity: button stays inside the GUI body (left of the scrollbar).
        org.junit.jupiter.api.Assertions
            .assertTrue(retry.x() - guiLeft >= 6, "retry must not run off the left edge");
    }

    @Test
    void cancel_and_retry_share_the_status_row_and_do_not_overlap() {
        int guiLeft = 0;
        int xSize = 238;
        int ySize = 200;
        SmartCraftConfirmButtonLayout.Position cancel = SmartCraftConfirmButtonLayout
            .cancelPosition(guiLeft, 0, xSize, ySize);
        SmartCraftConfirmButtonLayout.Position retry = SmartCraftConfirmButtonLayout
            .retryPosition(guiLeft, 0, xSize, ySize);

        assertEquals(cancel.y(), retry.y(), "cancel and retry sit on the same status row (ySize - 25)");
        org.junit.jupiter.api.Assertions
            .assertTrue(cancel.x() + cancel.width() < retry.x(), "cancel (left) and retry (right) must not overlap");
    }
}
