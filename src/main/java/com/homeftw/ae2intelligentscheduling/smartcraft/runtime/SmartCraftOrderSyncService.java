package com.homeftw.ae2intelligentscheduling.smartcraft.runtime;

import java.util.Optional;
import java.util.UUID;

import net.minecraft.entity.player.EntityPlayerMP;

import com.homeftw.ae2intelligentscheduling.network.NetworkHandler;
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
}
