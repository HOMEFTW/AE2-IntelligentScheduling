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
}
