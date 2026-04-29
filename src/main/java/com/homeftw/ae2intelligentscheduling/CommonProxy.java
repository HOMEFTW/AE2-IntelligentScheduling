package com.homeftw.ae2intelligentscheduling;

import com.homeftw.ae2intelligentscheduling.network.NetworkHandler;
import com.homeftw.ae2intelligentscheduling.network.packet.SyncCpuDetailPacket;
import com.homeftw.ae2intelligentscheduling.network.packet.SyncSmartCraftOrderListPacket;
import com.homeftw.ae2intelligentscheduling.network.packet.SyncSmartCraftOrderPacket;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLLoadCompleteEvent;
import cpw.mods.fml.common.event.FMLPostInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.common.event.FMLServerStartedEvent;
import cpw.mods.fml.common.event.FMLServerStartingEvent;

public class CommonProxy {

    public void preInit(FMLPreInitializationEvent event) {
        AE2IntelligentScheduling.LOG.info("Initializing {} {}", AE2IntelligentScheduling.MOD_NAME, Tags.VERSION);
    }

    public void init(FMLInitializationEvent event) {
        NetworkHandler.init();
        FMLCommonHandler.instance()
            .bus()
            .register(AE2IntelligentScheduling.SMART_CRAFT_TICK_HANDLER);
    }

    public void postInit(FMLPostInitializationEvent event) {}

    public void complete(FMLLoadCompleteEvent event) {}

    public void serverStarting(FMLServerStartingEvent event) {}

    public void serverStarted(FMLServerStartedEvent event) {}

    public void openSmartCraftStatus(SyncSmartCraftOrderPacket packet) {}

    public void applySmartCraftOrderList(SyncSmartCraftOrderListPacket packet) {}

    public void updateCpuDetail(SyncCpuDetailPacket packet) {}
}
