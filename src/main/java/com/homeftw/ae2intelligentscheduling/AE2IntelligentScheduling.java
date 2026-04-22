package com.homeftw.ae2intelligentscheduling;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.homeftw.ae2intelligentscheduling.config.Config;

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
