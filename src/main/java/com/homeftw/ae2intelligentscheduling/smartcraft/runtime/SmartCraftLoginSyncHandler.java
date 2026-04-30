package com.homeftw.ae2intelligentscheduling.smartcraft.runtime;

import net.minecraft.entity.player.EntityPlayerMP;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.PlayerEvent;

/**
 * v0.1.9.4 (G14) Pushes the full smart-craft order list to a player as soon as they log in. Closes
 * the v0.1.9.3 regression where persisted orders were on disk + reloaded into the manager but the
 * client UI never knew about them: prior to this hook, the client only learned about orders by
 * either (a) submitting a new one (gets a {@code SyncSmartCraftOrderPacket} reply) or (b) hitting
 * the "View Status" button which sends a {@code RequestOrderStatusPacket} -- but the View Status
 * button only renders when the client already has data. After a server restart that's a deadlock:
 * no data -> no button -> no request -> no data forever.
 *
 * <p>Fix: subscribe to {@code FMLCommonHandler.bus()} and ship one
 * {@code SyncSmartCraftOrderListPacket} per fresh login. The packet is empty (zero entries) when
 * the manager has nothing to share, and the client's overlay gracefully no-ops on an empty list.
 *
 * <p>Wire-up: {@code AE2IntelligentScheduling.serverStarted} registers a single instance against
 * {@code FMLCommonHandler.instance().bus()} after the persistence handler has loaded its data, so
 * even the first player to join after a fresh restart gets the post-load order list (not the empty
 * pre-load snapshot).
 *
 * <p>Why FML bus and not Forge bus: {@code PlayerEvent.PlayerLoggedInEvent} is a FML-side event
 * (player connection lifecycle), so it goes through {@code FMLCommonHandler.bus()}. The
 * {@code WorldEvent.Save} hook on the persistence handler stays on Forge bus -- different events
 * live on different buses in 1.7.10, this is normal.
 */
public final class SmartCraftLoginSyncHandler {

    private static final Logger LOGGER = LogManager.getLogger("AE2IS-LoginSync");

    /**
     * Function-style abstraction over {@link SmartCraftOrderSyncService#syncListTo} so tests can
     * inject a recording lambda instead of the real (final, network-coupled) service. The
     * production constructor adapts to a method reference; tests pass a Consumer-style lambda.
     */
    @FunctionalInterface
    public interface Pusher {

        void push(EntityPlayerMP player);
    }

    private final Pusher pusher;

    public SmartCraftLoginSyncHandler(SmartCraftOrderSyncService syncService) {
        if (syncService == null) throw new IllegalArgumentException("syncService must not be null");
        // Adapt the service's syncListTo to the Pusher abstraction. Note the explicit lambda is
        // needed because Java can't infer the method reference target when the SAM type is one
        // declared on a different interface.
        this.pusher = syncService::syncListTo;
    }

    /**
     * Test-only constructor for a custom pusher (e.g. a recording lambda). Hidden as
     * package-private to nudge production code towards the SyncService overload above.
     */
    SmartCraftLoginSyncHandler(Pusher pusher) {
        if (pusher == null) throw new IllegalArgumentException("pusher must not be null");
        this.pusher = pusher;
    }

    /**
     * v0.1.9.4 Fires once when a player joins the server (single-player or multiplayer). Pushes
     * the entire current order list to that player. Idempotent across reconnects: each new
     * connection is a fresh login event so a player who quits + rejoins gets a refreshed list
     * automatically, fixing the v0.1.9.3 "after I rejoin my tabs are gone" regression.
     */
    @SubscribeEvent
    public void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event == null || event.player == null) return;
        if (!(event.player instanceof EntityPlayerMP)) return;
        EntityPlayerMP player = (EntityPlayerMP) event.player;
        try {
            this.pusher.push(player);
            LOGGER.info(
                "Pushed smart craft order list to {} on login",
                player.getCommandSenderName());
        } catch (RuntimeException e) {
            // Defensive: a flaky network handler must never derail player login. Log and continue.
            LOGGER.warn(
                "Failed to push initial smart craft order list to {} on login: {}",
                player.getCommandSenderName(),
                e.toString(),
                e);
        }
    }
}
