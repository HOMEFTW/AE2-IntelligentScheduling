package com.homeftw.ae2intelligentscheduling;

import net.minecraft.client.Minecraft;
import net.minecraftforge.common.MinecraftForge;

import com.homeftw.ae2intelligentscheduling.client.gui.GuiSmartCraftStatus;
import com.homeftw.ae2intelligentscheduling.client.gui.SmartCraftConfirmGuiEventHandler;
import com.homeftw.ae2intelligentscheduling.client.gui.SmartCraftScreenFlow;
import com.homeftw.ae2intelligentscheduling.network.packet.SyncCpuDetailPacket;
import com.homeftw.ae2intelligentscheduling.network.packet.SyncSmartCraftOrderListPacket;
import com.homeftw.ae2intelligentscheduling.network.packet.SyncSmartCraftOrderPacket;

import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLPostInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;

public class ClientProxy extends CommonProxy {

    private static boolean openSmartCraftStatusOnNextSync = false;

    @Override
    public void preInit(FMLPreInitializationEvent event) {
        super.preInit(event);
    }

    @Override
    public void init(FMLInitializationEvent event) {
        super.init(event);
        MinecraftForge.EVENT_BUS.register(SmartCraftConfirmGuiEventHandler.INSTANCE);
    }

    @Override
    public void postInit(FMLPostInitializationEvent event) {
        super.postInit(event);
    }

    @Override
    public void openSmartCraftStatus(SyncSmartCraftOrderPacket packet) {
        SmartCraftConfirmGuiEventHandler.OVERLAY.update(packet);
        Minecraft minecraft = Minecraft.getMinecraft();
        if (minecraft == null) {
            return;
        }

        net.minecraft.client.gui.GuiScreen current = minecraft.currentScreen;
        if (SmartCraftScreenFlow.shouldOpenDedicatedStatusScreen(
            SmartCraftScreenFlow.kindOf(current),
            consumeOpenSmartCraftStatusOnNextSync())) {
            minecraft.displayGuiScreen(new GuiSmartCraftStatus(packet));
        }
    }

    @Override
    public void applySmartCraftOrderList(SyncSmartCraftOrderListPacket packet) {
        SmartCraftConfirmGuiEventHandler.OVERLAY.applyOrderList(packet);
        // v0.1.9.5 (G15) Mirror the GUI-open logic from {@link #openSmartCraftStatus} so that
        // View-Status button clicks resolved through the LIST sync path (server replies with
        // SyncSmartCraftOrderListPacket to RequestOrderStatusPacket) actually open the dedicated
        // status GUI. Pre-v0.1.9.5 only the single-order sync path consumed the flag, leaving the
        // post-restart "click View Status -> nothing happens" deadlock: the player has orders but
        // no in-flight order-submission to trigger a single-order sync, so the LIST packet was
        // the only sync that arrived, and it didn't honor the flag.
        Minecraft minecraft = Minecraft.getMinecraft();
        if (minecraft == null) return;
        net.minecraft.client.gui.GuiScreen current = minecraft.currentScreen;
        if (SmartCraftScreenFlow.shouldOpenDedicatedStatusScreen(
            SmartCraftScreenFlow.kindOf(current),
            consumeOpenSmartCraftStatusOnNextSync())) {
            // Use the overlay's currently-focused order as the initial packet for the new GUI.
            // applyOrderList above has already populated/refreshed the overlay, so this is the
            // freshest available view of the order the player wants to inspect. Suppress the open
            // when the overlay is empty (manager genuinely has no orders) -- opening an empty
            // status GUI is worse UX than no-op.
            SyncSmartCraftOrderPacket initial = SmartCraftConfirmGuiEventHandler.OVERLAY.currentOrderPacket();
            if (initial != null) {
                minecraft.displayGuiScreen(new GuiSmartCraftStatus(initial));
            }
        }
    }

    @Override
    public void updateCpuDetail(SyncCpuDetailPacket packet) {
        SmartCraftConfirmGuiEventHandler.OVERLAY.updateCpuDetail(packet);
    }

    public static void requestOpenSmartCraftStatusOnNextSync() {
        openSmartCraftStatusOnNextSync = true;
    }

    private static boolean consumeOpenSmartCraftStatusOnNextSync() {
        boolean requested = openSmartCraftStatusOnNextSync;
        openSmartCraftStatusOnNextSync = false;
        return requested;
    }
}
