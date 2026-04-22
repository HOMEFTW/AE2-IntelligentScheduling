package com.homeftw.ae2intelligentscheduling;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.homeftw.ae2intelligentscheduling.config.Config;
import com.homeftw.ae2intelligentscheduling.integration.ae2.Ae2CraftSubmitter;
import com.homeftw.ae2intelligentscheduling.integration.ae2.Ae2CpuSelector;
import com.homeftw.ae2intelligentscheduling.integration.ae2.Ae2SmartCraftJobPlanner;
import com.homeftw.ae2intelligentscheduling.smartcraft.runtime.SmartCraftOrderManager;
import com.homeftw.ae2intelligentscheduling.smartcraft.runtime.SmartCraftAe2RuntimeSessionFactory;
import com.homeftw.ae2intelligentscheduling.smartcraft.runtime.SmartCraftRuntimeCoordinator;
import com.homeftw.ae2intelligentscheduling.smartcraft.runtime.SmartCraftOrderSyncService;
import com.homeftw.ae2intelligentscheduling.smartcraft.runtime.SmartCraftRuntimeSession;
import com.homeftw.ae2intelligentscheduling.smartcraft.runtime.SmartCraftServerTickHandler;

import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.SidedProxy;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLLoadCompleteEvent;
import cpw.mods.fml.common.event.FMLPostInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.common.event.FMLServerStartedEvent;
import cpw.mods.fml.common.event.FMLServerStartingEvent;

@Mod(
    modid = AE2IntelligentScheduling.MODID,
    version = AE2IntelligentScheduling.VERSION,
    name = AE2IntelligentScheduling.MOD_NAME,
    dependencies = "required-after:appliedenergistics2",
    acceptedMinecraftVersions = "[1.7.10]")
public class AE2IntelligentScheduling {

    public static final String MODID = "ae2intelligentscheduling";
    public static final String MOD_NAME = "AE2-IntelligentScheduling";
    public static final String VERSION = Tags.VERSION;

    public static final Logger LOG = LogManager.getLogger(MODID);
    public static final SmartCraftOrderManager SMART_CRAFT_ORDER_MANAGER = new SmartCraftOrderManager();
    public static final SmartCraftOrderSyncService SMART_CRAFT_ORDER_SYNC = new SmartCraftOrderSyncService(
        SMART_CRAFT_ORDER_MANAGER);
    public static final SmartCraftAe2RuntimeSessionFactory SMART_CRAFT_SESSION_FACTORY = new SmartCraftAe2RuntimeSessionFactory();
    public static final SmartCraftRuntimeCoordinator SMART_CRAFT_RUNTIME = new SmartCraftRuntimeCoordinator(
        SMART_CRAFT_ORDER_MANAGER,
        new Ae2CpuSelector(),
        new Ae2SmartCraftJobPlanner(),
        (session, task, cpu, job) -> new Ae2CraftSubmitter().submit(
            session.craftingGrid(),
            job,
            task,
            cpu,
            session.requesterBridge(),
            session.actionSource()),
        (session, orderId) -> {
            if (session != null && session.owner() != null) {
                SMART_CRAFT_ORDER_SYNC.sync(session.owner(), orderId);
            }
        });
    public static final SmartCraftServerTickHandler SMART_CRAFT_TICK_HANDLER = new SmartCraftServerTickHandler(
        SMART_CRAFT_RUNTIME);

    @Mod.Instance
    public static AE2IntelligentScheduling instance;

    @SidedProxy(
        clientSide = "com.homeftw.ae2intelligentscheduling.ClientProxy",
        serverSide = "com.homeftw.ae2intelligentscheduling.CommonProxy")
    public static CommonProxy proxy;

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        Config.synchronizeConfiguration(event.getSuggestedConfigurationFile());
        proxy.preInit(event);
    }

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        proxy.init(event);
    }

    @Mod.EventHandler
    public void postInit(FMLPostInitializationEvent event) {
        proxy.postInit(event);
    }

    @Mod.EventHandler
    public void completeInit(FMLLoadCompleteEvent event) {
        proxy.complete(event);
    }

    @Mod.EventHandler
    public void serverStarting(FMLServerStartingEvent event) {
        proxy.serverStarting(event);
    }

    @Mod.EventHandler
    public void serverStarted(FMLServerStartedEvent event) {
        proxy.serverStarted(event);
    }
}
