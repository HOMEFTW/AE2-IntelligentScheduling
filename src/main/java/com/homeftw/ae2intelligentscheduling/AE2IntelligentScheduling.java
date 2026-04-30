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
    /**
     * v0.1.9.3 (G12-fix) Replaces the v0.1.9 {@code SmartCraftOrderWorldData} (vanilla
     * {@code WorldSavedData} subclass) which never reliably wrote to disk on the 1.7.10 + GTNH
     * single-player save path. The new handler subscribes to {@code WorldEvent.Save} on the
     * Forge bus, owns the dirty flag, and serializes the manager into
     * {@code <world>/data/AE2IS_SmartCraftOrders.dat} via direct I/O. See
     * {@link com.homeftw.ae2intelligentscheduling.smartcraft.runtime.SmartCraftPersistenceHandler}
     * for the full lifecycle rationale.
     */
    public static final com.homeftw.ae2intelligentscheduling.smartcraft.runtime.SmartCraftPersistenceHandler SMART_CRAFT_PERSISTENCE = new com.homeftw.ae2intelligentscheduling.smartcraft.runtime.SmartCraftPersistenceHandler(
        SMART_CRAFT_ORDER_MANAGER);
    /**
     * v0.1.9.4 (G14) Pushes the full order list to a player when they log in, fixing the v0.1.9.3
     * regression where persisted orders never reached the client because the client-side overlay
     * had no path to discover them after a fresh server start. See
     * {@link com.homeftw.ae2intelligentscheduling.smartcraft.runtime.SmartCraftLoginSyncHandler}
     * for the deadlock chain this resolves.
     */
    public static final com.homeftw.ae2intelligentscheduling.smartcraft.runtime.SmartCraftLoginSyncHandler SMART_CRAFT_LOGIN_SYNC = new com.homeftw.ae2intelligentscheduling.smartcraft.runtime.SmartCraftLoginSyncHandler(
        SMART_CRAFT_ORDER_SYNC);

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
        // v0.1.9.3 (G12-fix) Drive persistence via the new direct-I/O handler. Why not the v0.1.9
        // WorldSavedData approach: vanilla 1.7.10 MapStorage.saveAllData was unreliably hit on
        // the GTNH integrated-server save path, so smart craft orders silently failed to round-
        // trip across restarts. SmartCraftPersistenceHandler owns its own File I/O on
        // <world>/data/AE2IS_SmartCraftOrders.dat and persists on every WorldEvent.Save (DIM 0)
        // plus a forced flush on serverStopping.
        net.minecraft.world.WorldServer overworld = net.minecraft.server.MinecraftServer.getServer()
            .worldServerForDimension(0);
        if (overworld != null) {
            // Resolve the world directory once and hand it to the persistence handler. The
            // handler caches it for subsequent save events so we don't have to re-resolve the
            // MinecraftServer singleton from inside event handlers.
            java.io.File worldDir = overworld.getSaveHandler()
                .getWorldDirectory();
            SMART_CRAFT_PERSISTENCE.loadOnServerStart(worldDir);
            // Register the handler with the Forge event bus AFTER the initial load. Order matters:
            // load runs on the server thread synchronously inside this handler, and the in-load
            // markDirty calls are masked by SmartCraftPersistenceHandler.loadOnServerStart which
            // resets the dirty flag at the end. If we registered before loading, a concurrent
            // WorldEvent.Save during dimension init could fire flush() with a half-loaded manager.
            cpw.mods.fml.common.FMLCommonHandler.instance()
                .bus()
                .register(SMART_CRAFT_PERSISTENCE);
            net.minecraftforge.common.MinecraftForge.EVENT_BUS.register(SMART_CRAFT_PERSISTENCE);
            // v0.1.9.4 (G14) Register the login-sync handler on the FML bus. It listens for
            // PlayerLoggedInEvent and pushes the full order list to each freshly-connected player
            // so the client-side overlay knows about persisted orders without waiting for the
            // player to manually click View Status (which doesn't render until OVERLAY has data --
            // chicken-and-egg without this hook).
            cpw.mods.fml.common.FMLCommonHandler.instance()
                .bus()
                .register(SMART_CRAFT_LOGIN_SYNC);
            // (v0.1.9 G12) Folds in-flight task statuses (RUNNING / SUBMITTING / WAITING_CPU /
            // VERIFYING_OUTPUT) back to PENDING via SmartCraftOrderManager.resetForRestart. This
            // is now done explicitly here because SmartCraftPersistence.readFromFile delegates
            // to SmartCraftOrderManager.loadFromNBT which already runs resetForRestart on each
            // loaded order, so this is a no-op in practice -- left here as a safety net in case
            // an order was track()'d before this hook (e.g. from a future preInit code path).
        } else {
            LOG.warn("Smart craft persistence: overworld is null at FMLServerStartedEvent; orders will not persist this session");
        }
        proxy.serverStarted(event);
    }

    /**
     * v0.1.9.3 (G12-fix) Final flush before JVM exit. Belt-and-suspenders: WorldEvent.Save fires
     * during the stop sequence and will already have flushed our data, but some optimised
     * GTNH-style modpacks short-circuit that path. The forced flush here is cheap (no-op when
     * not dirty -- usually true at this point because the WorldEvent.Save just ran) and
     * eliminates the entire class of "I quit too fast and lost the order I just queued" bugs.
     */
    @Mod.EventHandler
    public void serverStopping(cpw.mods.fml.common.event.FMLServerStoppingEvent event) {
        SMART_CRAFT_PERSISTENCE.flushOnServerStop();
    }
}
