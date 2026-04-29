package com.homeftw.ae2intelligentscheduling.smartcraft.runtime;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import net.minecraft.entity.player.EntityPlayerMP;

import com.homeftw.ae2intelligentscheduling.AE2IntelligentScheduling;
import com.homeftw.ae2intelligentscheduling.network.NetworkHandler;
import com.homeftw.ae2intelligentscheduling.network.packet.SyncSmartCraftOrderListPacket;
import com.homeftw.ae2intelligentscheduling.network.packet.SyncSmartCraftOrderPacket;
import com.homeftw.ae2intelligentscheduling.smartcraft.model.SmartCraftOrder;

public final class SmartCraftOrderSyncService {

    private final SmartCraftOrderManager orderManager;

    public SmartCraftOrderSyncService(SmartCraftOrderManager orderManager) {
        this.orderManager = orderManager;
    }

    public void sync(EntityPlayerMP player, UUID orderId) {
        sync(player, orderId, null);
    }

    public void sync(EntityPlayerMP player, UUID orderId, SmartCraftRuntimeSession session) {
        Optional<SmartCraftOrder> order = this.orderManager.get(orderId);
        if (player == null || !order.isPresent()) {
            return;
        }

        NetworkHandler.INSTANCE.sendTo(SyncSmartCraftOrderPacket.from(orderId, order.get(), session), player);
    }

    /**
     * v0.1.7: push the full list of every active order to a single client. Resolves the order
     * snapshot and the session map from the global runtime singletons, then ships a
     * {@link SyncSmartCraftOrderListPacket}. Cheap when the manager is empty (still sends an empty
     * list packet so the client clears its tab bar — important for "terminal-orders-vanish"
     * semantics).
     */
    public void syncListTo(EntityPlayerMP player) {
        syncListTo(player, this.orderManager.snapshot(), AE2IntelligentScheduling.SMART_CRAFT_RUNTIME.sessionsView());
    }

    /**
     * Test-friendly overload: callers supply pre-resolved snapshots so the test doesn't need a
     * live RuntimeCoordinator. Production paths should use the no-snapshot overload above which
     * goes through the live manager + runtime.
     */
    public void syncListTo(EntityPlayerMP player, LinkedHashMap<UUID, SmartCraftOrder> orderSnapshot,
        Map<UUID, SmartCraftRuntimeSession> sessions) {
        if (player == null) {
            return;
        }
        NetworkHandler.INSTANCE.sendTo(SyncSmartCraftOrderListPacket.from(orderSnapshot, sessions), player);
    }
}
