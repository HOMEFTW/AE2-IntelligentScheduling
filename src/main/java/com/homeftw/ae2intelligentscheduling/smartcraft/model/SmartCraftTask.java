package com.homeftw.ae2intelligentscheduling.smartcraft.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

public final class SmartCraftTask {

    private final String taskId;
    private final SmartCraftRequestKey requestKey;
    private final long amount;
    private final int depth;
    private final int splitIndex;
    private final int splitCount;
    private final SmartCraftStatus status;
    private final String blockingReason;
    /**
     * Task-level dependencies: every taskId in this list must reach DONE before this task is
     * considered ready for submission. Empty list = ready as soon as the order is dispatched.
     * Server-side runtime field only — not serialized over the wire (clients only need the layered
     * task list for display, the dependency graph drives scheduling on the server).
     */
    private final List<String> dependsOnTaskIds;

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
            blockingReason,
            Collections.<String>emptyList());
    }

    public SmartCraftTask(String taskId, SmartCraftRequestKey requestKey, long amount, int depth, int splitIndex,
        int splitCount, SmartCraftStatus status, String blockingReason) {
        this(
            taskId,
            requestKey,
            amount,
            depth,
            splitIndex,
            splitCount,
            status,
            blockingReason,
            Collections.<String>emptyList());
    }

    public SmartCraftTask(String taskId, SmartCraftRequestKey requestKey, long amount, int depth, int splitIndex,
        int splitCount, SmartCraftStatus status, String blockingReason, List<String> dependsOnTaskIds) {
        this.taskId = taskId;
        this.requestKey = requestKey;
        this.amount = amount;
        this.depth = depth;
        this.splitIndex = splitIndex;
        this.splitCount = splitCount;
        this.status = status;
        this.blockingReason = blockingReason;
        this.dependsOnTaskIds = dependsOnTaskIds == null || dependsOnTaskIds.isEmpty() ? Collections.<String>emptyList()
            : Collections.unmodifiableList(new ArrayList<String>(dependsOnTaskIds));
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

    public List<String> dependsOnTaskIds() {
        return this.dependsOnTaskIds;
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
            nextBlockingReason,
            this.dependsOnTaskIds);
    }

    public SmartCraftTask withDependsOnTaskIds(List<String> nextDependsOnTaskIds) {
        return new SmartCraftTask(
            this.taskId,
            this.requestKey,
            this.amount,
            this.depth,
            this.splitIndex,
            this.splitCount,
            this.status,
            this.blockingReason,
            nextDependsOnTaskIds);
    }

    /**
     * v0.1.9 (G12) Persist this task to NBT. Format mirrors the constructor parameters; status is
     * stored as the enum {@code name()} so renaming a status would force-migrate (intentional \u2014
     * status names are part of the persistence contract).
     */
    public void writeToNBT(NBTTagCompound tag) {
        tag.setString("taskId", this.taskId == null ? "" : this.taskId);
        if (this.requestKey != null) {
            NBTTagCompound keyTag = new NBTTagCompound();
            this.requestKey.writeToNBT(keyTag);
            tag.setTag("requestKey", keyTag);
        }
        tag.setLong("amount", this.amount);
        tag.setInteger("depth", this.depth);
        tag.setInteger("splitIndex", this.splitIndex);
        tag.setInteger("splitCount", this.splitCount);
        tag.setString("status", this.status == null ? SmartCraftStatus.PENDING.name() : this.status.name());
        if (this.blockingReason != null && !this.blockingReason.isEmpty()) {
            tag.setString("blockingReason", this.blockingReason);
        }
        if (!this.dependsOnTaskIds.isEmpty()) {
            NBTTagList depList = new NBTTagList();
            for (String id : this.dependsOnTaskIds) {
                depList.appendTag(new net.minecraft.nbt.NBTTagString(id));
            }
            tag.setTag("dependsOn", depList);
        }
    }

    /**
     * Inverse of {@link #writeToNBT}. Returns {@code null} if the requestKey can't be deserialized
     * (its type is unregistered or the backing item disappeared) \u2014 callers (typically
     * {@link SmartCraftLayer#readFromNBT}) drop the task in that case.
     */
    public static SmartCraftTask readFromNBT(NBTTagCompound tag) {
        if (tag == null) return null;
        SmartCraftRequestKey key = tag.hasKey("requestKey")
            ? SmartCraftRequestKeyRegistry.readFromNBT(tag.getCompoundTag("requestKey"))
            : null;
        if (key == null) return null;
        String taskId = tag.hasKey("taskId") ? tag.getString("taskId") : "";
        long amount = tag.getLong("amount");
        int depth = tag.getInteger("depth");
        int splitIndex = tag.getInteger("splitIndex");
        int splitCount = tag.hasKey("splitCount") ? tag.getInteger("splitCount") : 1;
        SmartCraftStatus status;
        try {
            status = SmartCraftStatus.valueOf(tag.getString("status"));
        } catch (IllegalArgumentException e) {
            status = SmartCraftStatus.PENDING;
        }
        String blockingReason = tag.hasKey("blockingReason") ? tag.getString("blockingReason") : null;
        List<String> dependsOn;
        if (tag.hasKey("dependsOn")) {
            NBTTagList depList = tag.getTagList("dependsOn", 8); // 8 = TAG_String
            dependsOn = new ArrayList<String>(depList.tagCount());
            for (int i = 0; i < depList.tagCount(); i++) {
                dependsOn.add(depList.getStringTagAt(i));
            }
        } else {
            dependsOn = Collections.emptyList();
        }
        return new SmartCraftTask(
            taskId == null || taskId.isEmpty() ? key.id() + "#" + depth + "#" + splitIndex + "/" + splitCount : taskId,
            key,
            amount,
            depth,
            splitIndex,
            splitCount,
            status,
            blockingReason,
            dependsOn);
    }
}
