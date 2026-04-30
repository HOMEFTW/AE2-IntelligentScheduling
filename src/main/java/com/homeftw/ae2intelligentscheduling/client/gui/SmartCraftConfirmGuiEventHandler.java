package com.homeftw.ae2intelligentscheduling.client.gui;

import java.lang.reflect.Field;
import java.util.List;

import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.util.StatCollector;
import net.minecraftforge.client.event.GuiScreenEvent;

import com.homeftw.ae2intelligentscheduling.ClientProxy;
import com.homeftw.ae2intelligentscheduling.network.NetworkHandler;
import com.homeftw.ae2intelligentscheduling.network.packet.OpenSmartCraftPreviewPacket;
import com.homeftw.ae2intelligentscheduling.network.packet.RequestOrderStatusPacket;

import appeng.client.gui.implementations.GuiCraftConfirm;
import appeng.client.gui.implementations.GuiCraftingStatus;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.relauncher.ReflectionHelper;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

@SideOnly(Side.CLIENT)
public final class SmartCraftConfirmGuiEventHandler {

    public static final SmartCraftConfirmGuiEventHandler INSTANCE = new SmartCraftConfirmGuiEventHandler();
    public static final SmartCraftOverlayRenderer OVERLAY = new SmartCraftOverlayRenderer();

    private static final Field GUI_LEFT = ReflectionHelper.findField(GuiContainer.class, "guiLeft", "field_147003_i");
    private static final Field GUI_TOP = ReflectionHelper.findField(GuiContainer.class, "guiTop", "field_147009_r");
    private static final Field X_SIZE = ReflectionHelper.findField(GuiContainer.class, "xSize", "field_146999_f");
    private static final Field Y_SIZE = ReflectionHelper.findField(GuiContainer.class, "ySize", "field_147000_g");
    private static final Field BUTTON_LIST = ReflectionHelper
        .findField(net.minecraft.client.gui.GuiScreen.class, "buttonList", "field_146292_n");

    private SmartCraftConfirmGuiEventHandler() {}

    /**
     * v0.1.9.4 (G14) Client-side throttle for the supplementary order-list pull (see onGuiInit).
     * The primary sync path is the server's PlayerLoggedIn push; this is just a belt-and-suspenders
     * safety net for cases where that push gets dropped (rare, but the cost of a recovery packet
     * is one IMessage per GUI open which is negligible). Throttle = 1 second to prevent spam when
     * the player rapidly toggles between AE2 GUIs.
     */
    private static long lastOrderListPullMs = 0L;
    private static final long ORDER_LIST_PULL_THROTTLE_MS = 1000L;

    @SubscribeEvent
    public void onGuiInit(GuiScreenEvent.InitGuiEvent.Post event) {
        if (event.gui instanceof GuiCraftConfirm) {
            initCraftConfirmButtons(event);
            requestOrderListIfStale();
        } else if (event.gui instanceof GuiCraftingStatus) {
            syncViewStatusButton(event.buttonList, event.gui, SmartCraftScreenFlow.ScreenKind.CRAFTING_STATUS);
            requestOrderListIfStale();
        } else if (event.gui instanceof appeng.client.gui.implementations.GuiMEMonitorable) {
            syncViewStatusButton(event.buttonList, event.gui, SmartCraftScreenFlow.ScreenKind.TERMINAL);
            requestOrderListIfStale();
        }
    }

    /**
     * v0.1.9.4 (G14) Belt-and-suspenders order-list pull for the ME terminal / craft confirm /
     * crafting status GUIs. Without this, the v0.1.9.3 deadlock applies: client OVERLAY is empty
     * after a fresh login -> View Status button doesn't render -> no way to ask the server.
     *
     * <p>The server-side PlayerLoggedIn handler (added in the same patch) is the primary fix --
     * this client-side pull is a safety net for cases where the login push got lost (e.g. the
     * client wasn't fully ready to receive packets at login time on slow setups). Throttled so a
     * player rapid-clicking through GUIs doesn't generate one packet per click.
     */
    private static void requestOrderListIfStale() {
        long now = System.currentTimeMillis();
        if (now - lastOrderListPullMs < ORDER_LIST_PULL_THROTTLE_MS) return;
        lastOrderListPullMs = now;
        com.homeftw.ae2intelligentscheduling.network.NetworkHandler.INSTANCE.sendToServer(
            new com.homeftw.ae2intelligentscheduling.network.packet.RequestOrderStatusPacket());
    }

    private void initCraftConfirmButtons(GuiScreenEvent.InitGuiEvent.Post event) {
        int guiLeft = readInt(event.gui, GUI_LEFT), guiTop = readInt(event.gui, GUI_TOP);
        int xSize = readInt(event.gui, X_SIZE), ySize = readInt(event.gui, Y_SIZE);

        SmartCraftConfirmButtonLayout.Position pos = SmartCraftConfirmButtonLayout
            .position(guiLeft, guiTop, xSize, ySize);
        event.buttonList.add(
            new GuiButton(
                SmartCraftConfirmButtonLayout.BUTTON_ID,
                pos.x(),
                pos.y(),
                pos.width(),
                pos.height(),
                StatCollector.translateToLocal("gui.ae2intelligentscheduling.smartCraft")));
        syncViewStatusButton(event.buttonList, event.gui, SmartCraftScreenFlow.ScreenKind.CRAFT_CONFIRM);
    }

    @SubscribeEvent
    @SuppressWarnings("unchecked")
    public void onDrawScreen(GuiScreenEvent.DrawScreenEvent.Post event) {
        SmartCraftScreenFlow.ScreenKind screenKind = SmartCraftScreenFlow.kindOf(event.gui);
        if (screenKind == SmartCraftScreenFlow.ScreenKind.CRAFT_CONFIRM
            || screenKind == SmartCraftScreenFlow.ScreenKind.CRAFTING_STATUS
            || screenKind == SmartCraftScreenFlow.ScreenKind.TERMINAL) {
            syncViewStatusButton((List<GuiButton>) readList(event.gui, BUTTON_LIST), event.gui, screenKind);
        }
        if (screenKind == SmartCraftScreenFlow.ScreenKind.CRAFTING_STATUS) {
            // Inject our supplementary tooltip on top of AE2's GuiCraftingStatus when the player hovers
            // an item that also belongs to one of our smart craft tasks. AE2 already drew its native
            // tooltip via super.drawScreen — by deferring to DrawScreenEvent.Post we render OUR tooltip
            // afterwards, so it sits on top without conflicting with AE2's draw order.
            drawSmartCraftSupplementOnAe2Status(event.gui, event.mouseX, event.mouseY);
        }
        if (!SmartCraftScreenFlow.shouldDrawStatusOverlayOnConfirm()) return;
    }

    /**
     * Inspect AE2 {@code GuiCraftingCPU#getHoveredStack()} (inherited by GuiCraftingStatus) to find the
     * currently hovered cell. If the corresponding task exists in our smart craft order, render an
     * additional small tooltip with our scheduling info (status / layer / split / CPU / blocking
     * reason) at an offset position so it does not overlap AE2's own tooltip.
     */
    private static void drawSmartCraftSupplementOnAe2Status(Object gui, int mouseX, int mouseY) {
        if (!(gui instanceof appeng.client.gui.implementations.GuiCraftingCPU)) return;
        if (!OVERLAY.hasData()) return;

        appeng.client.gui.implementations.GuiCraftingCPU craftingGui = (appeng.client.gui.implementations.GuiCraftingCPU) gui;
        net.minecraft.item.ItemStack hovered = craftingGui.getHoveredStack();
        if (hovered == null) return;

        com.homeftw.ae2intelligentscheduling.network.packet.SyncSmartCraftOrderPacket.TaskView matched = OVERLAY
            .findMatchingTask(hovered);
        if (matched == null) return;

        // Offset the supplementary tooltip below AE2's native tooltip so both are readable. AE2 renders
        // its tooltip starting roughly at (mouseX + 12, mouseY - 12); we shift down by ~50px to clear
        // AE2's typical 4-line height when the player holds shift.
        OVERLAY.drawSupplementaryTooltip(OVERLAY.buildAe2SupplementHintLines(matched), mouseX, mouseY + 60);
    }

    @SubscribeEvent
    public void onButtonClicked(GuiScreenEvent.ActionPerformedEvent.Post event) {
        SmartCraftScreenFlow.ScreenKind screenKind = SmartCraftScreenFlow.kindOf(event.gui);
        int id = event.button.id;

        if (SmartCraftScreenFlow.shouldHandleCraftConfirmButton(screenKind, id)) {
            NetworkHandler.INSTANCE.sendToServer(OpenSmartCraftPreviewPacket.preview());
        } else if (SmartCraftScreenFlow.shouldRequestStatus(screenKind, id)) {
            ClientProxy.requestOpenSmartCraftStatusOnNextSync();
            NetworkHandler.INSTANCE.sendToServer(new RequestOrderStatusPacket());
        }
    }

    @SubscribeEvent
    public void onGuiClosed(GuiScreenEvent.InitGuiEvent.Pre event) {
        // Only clear when entering a screen unrelated to AE2 crafting
        // Keeps overlay data alive when switching between terminal GUIs
        if (event.gui instanceof GuiCraftConfirm) return;
        if (event.gui instanceof GuiCraftingStatus) return;
        if (event.gui instanceof GuiSmartCraftStatus) return;
        if (event.gui instanceof appeng.client.gui.implementations.GuiMEMonitorable) return;
        OVERLAY.clear();
    }

    // --- Helpers ---

    private static void syncViewStatusButton(List<GuiButton> buttons, Object gui,
        SmartCraftScreenFlow.ScreenKind screenKind) {
        GuiButton button = findButton(buttons, SmartCraftConfirmButtonLayout.VIEW_STATUS_BUTTON_ID);
        boolean visible = SmartCraftScreenFlow.shouldShowViewStatusButton(screenKind, OVERLAY.hasData());
        if (!visible) {
            if (button != null) {
                button.visible = false;
            }
            return;
        }

        int guiLeft = readInt(gui, GUI_LEFT), guiTop = readInt(gui, GUI_TOP);
        int xSize = readInt(gui, X_SIZE), ySize = readInt(gui, Y_SIZE);
        SmartCraftConfirmButtonLayout.Position pos = viewStatusPositionFor(screenKind, guiLeft, guiTop, xSize, ySize);

        if (button == null) {
            buttons.add(
                new GuiButton(
                    SmartCraftConfirmButtonLayout.VIEW_STATUS_BUTTON_ID,
                    pos.x(),
                    pos.y(),
                    pos.width(),
                    pos.height(),
                    StatCollector.translateToLocal("gui.ae2intelligentscheduling.viewStatus")));
            return;
        }

        button.xPosition = pos.x();
        button.yPosition = pos.y();
        button.width = pos.width();
        button.height = pos.height();
        button.visible = true;
    }

    private static SmartCraftConfirmButtonLayout.Position viewStatusPositionFor(
        SmartCraftScreenFlow.ScreenKind screenKind, int guiLeft, int guiTop, int xSize, int ySize) {
        switch (screenKind) {
            case CRAFT_CONFIRM:
                return SmartCraftConfirmButtonLayout.viewStatusOnConfirmPosition(guiLeft, guiTop, xSize, ySize);
            case CRAFTING_STATUS:
                return SmartCraftConfirmButtonLayout.viewStatusOnCraftingStatusPosition(guiLeft, guiTop, xSize, ySize);
            case TERMINAL:
            default:
                return SmartCraftConfirmButtonLayout.viewStatusOnTerminalPosition(guiLeft, guiTop, xSize, ySize);
        }
    }

    private static GuiButton findButton(List<GuiButton> buttons, int id) {
        for (GuiButton button : buttons) {
            if (button.id == id) {
                return button;
            }
        }
        return null;
    }

    private static int readInt(Object target, Field field) {
        try {
            return field.getInt(target);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(field.getName(), e);
        }
    }

    private static List<?> readList(Object target, Field field) {
        try {
            return (List<?>) field.get(target);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(field.getName(), e);
        }
    }
}
