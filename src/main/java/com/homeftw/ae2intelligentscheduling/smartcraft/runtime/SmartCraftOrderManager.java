package com.homeftw.ae2intelligentscheduling.smartcraft.runtime;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.homeftw.ae2intelligentscheduling.smartcraft.model.SmartCraftLayer;
import com.homeftw.ae2intelligentscheduling.smartcraft.model.SmartCraftOrder;
import com.homeftw.ae2intelligentscheduling.smartcraft.model.SmartCraftStatus;
import com.homeftw.ae2intelligentscheduling.smartcraft.model.SmartCraftTask;

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

    public Optional<SmartCraftOrder> cancel(UUID orderId) {
        Optional<SmartCraftOrder> existing = get(orderId);
        if (!existing.isPresent()) {
            return Optional.empty();
        }

        SmartCraftOrder cancelled = existing.get().withLayers(updateAllTasks(existing.get().layers(), true, false))
                .withStatus(SmartCraftStatus.CANCELLED);
        this.orders.put(orderId, cancelled);
        return Optional.of(cancelled);
    }

    public Optional<SmartCraftOrder> retryFailedTasks(UUID orderId) {
        Optional<SmartCraftOrder> existing = get(orderId);
        if (!existing.isPresent()) {
            return Optional.empty();
        }

        SmartCraftOrder retried = existing.get().withLayers(updateAllTasks(existing.get().layers(), false, true))
                .withStatus(SmartCraftStatus.QUEUED);
        this.orders.put(orderId, retried);
        return Optional.of(retried);
    }

    private List<SmartCraftLayer> updateAllTasks(List<SmartCraftLayer> layers, boolean cancelActive, boolean retryFailed) {
        List<SmartCraftLayer> nextLayers = new ArrayList<SmartCraftLayer>(layers.size());

        for (SmartCraftLayer layer : layers) {
            List<SmartCraftTask> nextTasks = new ArrayList<SmartCraftTask>(layer.tasks().size());
            for (SmartCraftTask task : layer.tasks()) {
                SmartCraftTask nextTask = task;
                if (cancelActive && !task.isTerminal()) {
                    nextTask = task.withStatus(SmartCraftStatus.CANCELLED, task.blockingReason());
                } else if (retryFailed && task.status() == SmartCraftStatus.FAILED) {
                    nextTask = task.withStatus(SmartCraftStatus.PENDING, null);
                }
                nextTasks.add(nextTask);
            }
            nextLayers.add(layer.withTasks(nextTasks));
        }

        return nextLayers;
    }
}
