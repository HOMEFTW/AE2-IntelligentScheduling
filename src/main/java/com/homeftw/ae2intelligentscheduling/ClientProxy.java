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
