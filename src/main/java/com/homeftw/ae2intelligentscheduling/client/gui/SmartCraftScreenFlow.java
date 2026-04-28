package com.homeftw.ae2intelligentscheduling.client.gui;

import net.minecraft.client.gui.GuiScreen;

import appeng.client.gui.implementations.GuiCraftConfirm;
import appeng.client.gui.implementations.GuiCraftingStatus;
import appeng.client.gui.implementations.GuiMEMonitorable;

public final class SmartCraftScreenFlow {

    public enum ScreenKind {
        CRAFT_CONFIRM,
        TERMINAL,
        CRAFTING_STATUS,
        SMART_STATUS,
        OTHER
    }

    private SmartCraftScreenFlow() {}

    public static ScreenKind kindOf(GuiScreen screen) {
        if (screen instanceof GuiCraftConfirm) {
            return ScreenKind.CRAFT_CONFIRM;
        }
        if (screen instanceof GuiSmartCraftStatus) {
            return ScreenKind.SMART_STATUS;
        }
        if (screen instanceof GuiCraftingStatus) {
            return ScreenKind.CRAFTING_STATUS;
        }
        if (screen instanceof GuiMEMonitorable) {
            return ScreenKind.TERMINAL;
        }
        return ScreenKind.OTHER;
    }

    public static boolean shouldOpenDedicatedStatusScreen(ScreenKind currentScreen, boolean playerRequestedStatus) {
        return playerRequestedStatus && currentScreen != ScreenKind.SMART_STATUS;
    }

    public static boolean shouldShowViewStatusButton(ScreenKind currentScreen, boolean hasOrderData) {
        return hasOrderData && (currentScreen == ScreenKind.TERMINAL || currentScreen == ScreenKind.CRAFT_CONFIRM
            || currentScreen == ScreenKind.CRAFTING_STATUS);
    }

    public static boolean shouldRequestStatus(ScreenKind currentScreen, int buttonId) {
        return buttonId == SmartCraftConfirmButtonLayout.VIEW_STATUS_BUTTON_ID
            && (currentScreen == ScreenKind.TERMINAL || currentScreen == ScreenKind.CRAFT_CONFIRM
                || currentScreen == ScreenKind.CRAFTING_STATUS);
    }

    public static boolean shouldHandleCraftConfirmButton(ScreenKind currentScreen, int buttonId) {
        return currentScreen == ScreenKind.CRAFT_CONFIRM && (buttonId == SmartCraftConfirmButtonLayout.BUTTON_ID);
    }

    public static boolean shouldDrawStatusOverlayOnConfirm() {
        return false;
    }
}
