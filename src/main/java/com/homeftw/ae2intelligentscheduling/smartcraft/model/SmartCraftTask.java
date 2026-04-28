package com.homeftw.ae2intelligentscheduling.smartcraft.model;

public final class SmartCraftTask {

    private final String taskId;
    private final SmartCraftRequestKey requestKey;
    private final long amount;
    private final int depth;
    private final int splitIndex;
    private final int splitCount;
    private final SmartCraftStatus status;
    private final String blockingReason;

    public SmartCraftTask(SmartCraftRequestKey requestKey, long amount, int depth, int splitIndex, int splitCount,
        SmartCraftStatus status, String blockingReason) {
        this(
            requestKey == null ? "task:null" : requestKey.id() + "#" + depth + "#" + splitIndex + "/" + splitCount,
            requestKey,
            amount,
            depth,
            splitIndex,
            splitCount,
            status,
            blockingReason);
    }

    public SmartCraftTask(String taskId, SmartCraftRequestKey requestKey, long amount, int depth, int splitIndex,
        int splitCount, SmartCraftStatus status, String blockingReason) {
        this.taskId = taskId;
        this.requestKey = requestKey;
        this.amount = amount;
        this.depth = depth;
        this.splitIndex = splitIndex;
        this.splitCount = splitCount;
        this.status = status;
        this.blockingReason = blockingReason;
    }

    public String taskId() {
        return this.taskId;
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

    public String taskKey() {
        return this.taskId;
    }

    public boolean isReadyForSubmission() {
        return this.status == SmartCraftStatus.PENDING || this.status == SmartCraftStatus.QUEUED
            || this.status == SmartCraftStatus.WAITING_CPU;
    }

    public boolean isTerminal() {
        return this.status.isTerminalTaskState();
    }

    public boolean isActive() {
        return this.status.isActiveTaskState();
    }

    public SmartCraftTask withStatus(SmartCraftStatus nextStatus, String nextBlockingReason) {
        return new SmartCraftTask(
            this.taskId,
            this.requestKey,
            this.amount,
            this.depth,
            this.splitIndex,
            this.splitCount,
            nextStatus,
            nextBlockingReason);
    }
}
