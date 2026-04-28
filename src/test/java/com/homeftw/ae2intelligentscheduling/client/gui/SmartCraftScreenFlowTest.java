package com.homeftw.ae2intelligentscheduling.client.gui;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SmartCraftScreenFlowTest {

    @Test
    void status_sync_does_not_auto_open_screen_from_craft_confirm() {
        assertFalse(
            SmartCraftScreenFlow.shouldOpenDedicatedStatusScreen(SmartCraftScreenFlow.ScreenKind.CRAFT_CONFIRM, false));
    }

    @Test
    void requested_status_sync_opens_dedicated_screen_from_terminal() {
        assertTrue(
            SmartCraftScreenFlow.shouldOpenDedicatedStatusScreen(SmartCraftScreenFlow.ScreenKind.TERMINAL, true));
    }

    @Test
    void requested_status_sync_does_not_reopen_existing_status_screen() {
        assertFalse(
            SmartCraftScreenFlow.shouldOpenDedicatedStatusScreen(SmartCraftScreenFlow.ScreenKind.SMART_STATUS, true));
    }

    @Test
    void terminal_view_status_button_is_handled_from_terminal_screen() {
        assertTrue(
            SmartCraftScreenFlow.shouldRequestStatus(
                SmartCraftScreenFlow.ScreenKind.TERMINAL,
                SmartCraftConfirmButtonLayout.VIEW_STATUS_BUTTON_ID));
    }

    @Test
    void craft_confirm_can_show_view_status_button_after_order_sync() {
        assertTrue(
            SmartCraftScreenFlow.shouldShowViewStatusButton(SmartCraftScreenFlow.ScreenKind.CRAFT_CONFIRM, true));
    }

    @Test
    void craft_confirm_does_not_show_view_status_button_without_order_data() {
        assertFalse(
            SmartCraftScreenFlow.shouldShowViewStatusButton(SmartCraftScreenFlow.ScreenKind.CRAFT_CONFIRM, false));
    }

    @Test
    void craft_confirm_overlay_is_disabled_to_keep_ae2_confirm_ui_clean() {
        assertFalse(SmartCraftScreenFlow.shouldDrawStatusOverlayOnConfirm());
    }

    @Test
    void crafting_status_screen_can_show_view_status_button_after_order_sync() {
        assertTrue(
            SmartCraftScreenFlow.shouldShowViewStatusButton(SmartCraftScreenFlow.ScreenKind.CRAFTING_STATUS, true));
    }

    @Test
    void crafting_status_screen_does_not_show_view_status_button_without_order_data() {
        assertFalse(
            SmartCraftScreenFlow.shouldShowViewStatusButton(SmartCraftScreenFlow.ScreenKind.CRAFTING_STATUS, false));
    }

    @Test
    void crafting_status_view_status_button_routes_to_smart_craft_status_request() {
        assertTrue(
            SmartCraftScreenFlow.shouldRequestStatus(
                SmartCraftScreenFlow.ScreenKind.CRAFTING_STATUS,
                SmartCraftConfirmButtonLayout.VIEW_STATUS_BUTTON_ID));
    }
}
