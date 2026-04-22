package com.homeftw.ae2intelligentscheduling.smartcraft.runtime;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.homeftw.ae2intelligentscheduling.smartcraft.model.SmartCraftOrder;

public final class SmartCraftOrderManager {

    private final Map<UUID, SmartCraftOrder> orders = new LinkedHashMap<UUID, SmartCraftOrder>();

    public UUID track(SmartCraftOrder order) {
        UUID orderId = UUID.randomUUID();
        this.orders.put(orderId, order);
        return orderId;
    }

    public Optional<SmartCraftOrder> get(UUID orderId) {
        return Optional.ofNullable(this.orders.get(orderId));
    }

    public void update(UUID orderId, SmartCraftOrder order) {
        this.orders.put(orderId, order);
    }

    public void remove(UUID orderId) {
        this.orders.remove(orderId);
    }

    public Collection<SmartCraftOrder> all() {
        return this.orders.values();
    }
}
