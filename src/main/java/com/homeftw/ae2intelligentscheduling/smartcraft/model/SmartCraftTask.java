package com.homeftw.ae2intelligentscheduling.smartcraft.model;

public final class SmartCraftTask {

    private final SmartCraftRequestKey requestKey;
    private final long amount;
    private final int depth;
    private final int splitIndex;
    private final int splitCount;
    private final SmartCraftStatus status;
    private final String blockingReason;

    public SmartCraftTask(SmartCraftRequestKey requestKey, long amount, int depth, int splitIndex, int splitCount,
            SmartCraftStatus status, String blockingReason) {
        this.requestKey = requestKey;
        this.amount = amount;
        this.depth = depth;
        this.splitIndex = splitIndex;
        this.splitCount = splitCount;
        this.status = status;
        this.blockingReason = blockingReason;
    }

    public SmartCraftRequestKey requestKey() {
        return this.requestKey;
    }

    public long amount() {
        return this.amount;
    }

    public int depth() {
        return this.depth;
    }

    public int splitIndex() {
        return this.splitIndex;
    }

    public int splitCount() {
        return this.splitCount;
    }

    public SmartCraftStatus status() {
        return this.status;
    }

    public String blockingReason() {
        return this.blockingReason;
    }
}
