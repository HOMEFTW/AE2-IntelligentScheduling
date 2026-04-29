package com.homeftw.ae2intelligentscheduling;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.homeftw.ae2intelligentscheduling.config.Config;
import com.homeftw.ae2intelligentscheduling.integration.ae2.Ae2CpuSelector;
import com.homeftw.ae2intelligentscheduling.integration.ae2.Ae2CraftSubmitter;
import com.homeftw.ae2intelligentscheduling.integration.ae2.Ae2SmartCraftJobPlanner;
import com.homeftw.ae2intelligentscheduling.smartcraft.runtime.SmartCraftAe2RuntimeSessionFactory;
import com.homeftw.ae2intelligentscheduling.smartcraft.runtime.SmartCraftOrderManager;
import com.homeftw.ae2intelligentscheduling.smartcraft.runtime.SmartCraftOrderSyncService;
import com.homeftw.ae2intelligentscheduling.smartcraft.runtime.SmartCraftRuntimeCoordinator;
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
        (session, task, cpu, job) -> new Ae2CraftSubmitter()
            .submit(session.craftingGrid(), job, task, cpu, session.requesterBridge(), session.actionSource()),
        (session, orderId) -> {
            if (session != null && session.owner() != null) {
                SMART_CRAFT_ORDER_SYNC.sync(session.owner(), orderId, session);
            }
        },
        // (v0.1.8.1 G8) Order-level completion notifier. Fires exactly once per order on the
        // tick its status transitions into COMPLETED. We wire it to a chat message + a single
        // levelup sound for the order's owner — the same UX as a vanilla single-craft finish,
        // but at the order granularity (not per sub-task). Sub-task completion is silenced
        // upstream by Ae2CraftSubmitter using MachineSource which prevents AE2 + AE2Things
        // from firing their per-craft notifications on internal tasks.
        (session, orderId, order) -> {
            if (session == null || session.owner() == null || order == null) {
                return;
            }
            net.minecraft.entity.player.EntityPlayerMP owner = session.owner();
            net.minecraft.item.ItemStack target = order.targetRequestKey() == null ? null
                : order.targetRequestKey()
                    .itemStack();
            String displayName;
            if (target != null && target.getItem() != null) {
                displayName = target.getDisplayName();
            } else if (order.targetRequestKey() != null) {
                displayName = order.targetRequestKey()
                    .id();
            } else {
                displayName = "<unknown>";
            }
            String msg = net.minecraft.util.EnumChatFormatting.GREEN + "[\u667a\u80fd\u5408\u6210] "
                + net.minecraft.util.EnumChatFormatting.RESET
                + displayName
                + net.minecraft.util.EnumChatFormatting.GRAY
                + " x"
                + order.targetAmount()
                + " "
                + net.minecraft.util.EnumChatFormatting.GREEN
                + "\u5df2\u5b8c\u6210";
            owner.addChatMessage(new net.minecraft.util.ChatComponentText(msg));
            if (owner.worldObj != null) {
                owner.worldObj.playSoundAtEntity(owner, "random.levelup", 1.0F, 1.0F);
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
        // (v0.1.9 G12) Register concrete SmartCraftRequestKey type readers BEFORE any world load
        // can happen. Doing it here keeps the model package free of AE2 imports while still
        // letting SmartCraftRequestKeyRegistry route persisted "ae2.requestKey" entries back to
        // Ae2RequestKey at load time.
        com.homeftw.ae2intelligentscheduling.integration.ae2.Ae2RequestKey.registerNbtReader();
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
        // (v0.1.9 G12) Attach the WorldSavedData to the overworld AFTER the server has finished
        // bringing dimensions up. The order manager is global per server, so we deliberately bind
        // to DIM 0 (overworld) regardless of which dimension the player is currently in. The
        // attach call:
        //   1. Loads <world>/data/AE2IS_SmartCraftOrders.dat into SMART_CRAFT_ORDER_MANAGER (or
        //      leaves it empty if the file doesn't exist yet).
        //   2. Wires manager.setDirtyListener so subsequent track / update / cancel / retry
        //      mutations call WorldSavedData.markDirty(), letting vanilla pick up the save.
        //   3. Folds in-flight task statuses (RUNNING / SUBMITTING / WAITING_CPU / VERIFYING_OUTPUT)
        //      back to PENDING via SmartCraftOrderManager.resetForRestart \u2014 AE2 link / planning
        //      objects don't survive restart, so we re-run plan + submit on the next tick.
        net.minecraft.world.WorldServer overworld = net.minecraft.server.MinecraftServer.getServer()
            .worldServerForDimension(0);
        if (overworld != null) {
            com.homeftw.ae2intelligentscheduling.smartcraft.runtime.SmartCraftOrderWorldData
                .attach(overworld, SMART_CRAFT_ORDER_MANAGER);
        } else {
            LOG.warn("Could not attach SmartCraftOrderWorldData: overworld is null at FMLServerStartedEvent");
        }
        proxy.serverStarted(event);
    }
}
