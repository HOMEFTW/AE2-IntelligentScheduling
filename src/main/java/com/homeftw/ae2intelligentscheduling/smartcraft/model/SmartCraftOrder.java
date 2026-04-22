package com.homeftw.ae2intelligentscheduling.smartcraft.model;

import java.util.ArrayList;
import java.util.List;

public final class SmartCraftOrder {

    private final SmartCraftRequestKey targetRequestKey;
    private final long targetAmount;
    private final SmartCraftOrderScale orderScale;
    private final SmartCraftStatus status;
    private final List<SmartCraftLayer> layers;
    private final int currentLayerIndex;

    public SmartCraftOrder(SmartCraftRequestKey targetRequestKey, long targetAmount, SmartCraftOrderScale orderScale,
            SmartCraftStatus status, List<SmartCraftLayer> layers, int currentLayerIndex) {
        this.targetRequestKey = targetRequestKey;
        this.targetAmount = targetAmount;
        this.orderScale = orderScale;
        this.status = status;
        this.layers = layers;
        this.currentLayerIndex = currentLayerIndex;
    }

    public static SmartCraftOrder queued(SmartCraftRequestKey targetRequestKey, long targetAmount,
            SmartCraftOrderScale orderScale, List<SmartCraftLayer> layers) {
        return new SmartCraftOrder(
            targetRequestKey,
            targetAmount,
            orderScale,
            SmartCraftStatus.QUEUED,
            new ArrayList<>(layers),
            0);
    }

    public SmartCraftRequestKey targetRequestKey() {
        return this.targetRequestKey;
    }

    public long targetAmount() {
        return this.targetAmount;
    }

    public SmartCraftOrderScale orderScale() {
        return this.orderScale;
    }

    public SmartCraftStatus status() {
        return this.status;
    }

    public List<SmartCraftLayer> layers() {
        return this.layers;
    }

    public int currentLayerIndex() {
        return this.currentLayerIndex;
    }

    public SmartCraftLayer currentLayer() {
        if (this.currentLayerIndex < 0 || this.currentLayerIndex >= this.layers.size()) {
            return null;
        }
        return this.layers.get(this.currentLayerIndex);
    }

    public boolean isFinished() {
        return this.currentLayerIndex >= this.layers.size() || this.status == SmartCraftStatus.COMPLETED
                || this.status == SmartCraftStatus.CANCELLED || this.status == SmartCraftStatus.FAILED;
    }

    public SmartCraftOrder withStatus(SmartCraftStatus nextStatus) {
        return new SmartCraftOrder(
            this.targetRequestKey,
            this.targetAmount,
            this.orderScale,
            nextStatus,
            new ArrayList<SmartCraftLayer>(this.layers),
            this.currentLayerIndex);
    }

    public SmartCraftOrder withLayer(int layerIndex, SmartCraftLayer nextLayer) {
        List<SmartCraftLayer> nextLayers = new ArrayList<SmartCraftLayer>(this.layers);
        nextLayers.set(layerIndex, nextLayer);
        return new SmartCraftOrder(
            this.targetRequestKey,
            this.targetAmount,
            this.orderScale,
            this.status,
            nextLayers,
            this.currentLayerIndex);
    }

    public SmartCraftOrder withCurrentLayerIndex(int nextLayerIndex) {
        return new SmartCraftOrder(
            this.targetRequestKey,
            this.targetAmount,
            this.orderScale,
            this.status,
            new ArrayList<SmartCraftLayer>(this.layers),
            nextLayerIndex);
    }

    public SmartCraftOrder advanceLayer() {
        return withCurrentLayerIndex(this.currentLayerIndex + 1);
    }
}
