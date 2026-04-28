package com.homeftw.ae2intelligentscheduling.client.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class SmartCraftConfirmButtonLayoutTest {

    @Test
    void places_smart_button_above_ae2_start_button_area() {
        SmartCraftConfirmButtonLayout.Position position = SmartCraftConfirmButtonLayout.position(10, 20, 238, 206);

        // 智能合成 button must sit on the empty middle band (ySize - 47), right side, 52 wide so it
        // matches the AE2 Cancel / Start button width.
        assertEquals(190, position.x());
        assertEquals(179, position.y());
        assertEquals(52, position.width());
        assertEquals(20, position.height());
    }

    @Test
    void view_status_button_on_confirm_sits_left_of_smart_button_on_same_row() {
        SmartCraftConfirmButtonLayout.Position smartCraft = SmartCraftConfirmButtonLayout.position(10, 20, 238, 206);
        SmartCraftConfirmButtonLayout.Position viewStatus = SmartCraftConfirmButtonLayout
            .viewStatusOnConfirmPosition(10, 20, 238, 206);

        assertEquals(smartCraft.y(), viewStatus.y(), "查看调度 button must share the middle band with 智能合成 button");
        assertEquals(
            smartCraft.x() - 4 - viewStatus.width(),
            viewStatus.x(),
            "查看调度 button must sit immediately to the left of the 智能合成 button");
        assertEquals(52, viewStatus.width());
        assertEquals(20, viewStatus.height());
    }

    @Test
    void view_status_button_on_confirm_does_not_overlap_ae2_start_row() {
        // AE2 Start button occupies the row at ySize - 25 with bottom edge at ySize - 5.
        SmartCraftConfirmButtonLayout.Position viewStatus = SmartCraftConfirmButtonLayout
            .viewStatusOnConfirmPosition(0, 0, 238, 206);

        int viewStatusBottom = viewStatus.y() + viewStatus.height();
        int ae2StartTop = 206 - 25;
        org.junit.jupiter.api.Assertions
            .assertTrue(viewStatusBottom <= ae2StartTop, "查看调度 button must not overlap AE2 Start row");
    }

    @Test
    void view_status_button_on_terminal_uses_bottom_right_with_ae2_button_width() {
        SmartCraftConfirmButtonLayout.Position pos = SmartCraftConfirmButtonLayout
            .viewStatusOnTerminalPosition(0, 0, 195, 222);

        assertEquals(195 - 6 - 52, pos.x());
        assertEquals(222 - 25, pos.y());
        assertEquals(52, pos.width());
        assertEquals(20, pos.height());
    }

    @Test
    void view_status_button_on_crafting_status_fits_within_top_header_row() {
        SmartCraftConfirmButtonLayout.Position pos = SmartCraftConfirmButtonLayout
            .viewStatusOnCraftingStatusPosition(0, 0, 256, 222);

        // Must clear the AE2 search field area which starts at xSize - 101
        org.junit.jupiter.api.Assertions
            .assertTrue(pos.x() + pos.width() <= 256 - 101, "查看调度 button must not overlap AE2 search field");
        // Must fit inside the top header strip (search field occupies 12px starting at top + 5)
        org.junit.jupiter.api.Assertions
            .assertTrue(pos.height() <= 16, "查看调度 button must not exceed AE2 top header strip");
    }
}
