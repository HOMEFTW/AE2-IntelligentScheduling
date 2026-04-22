package com.homeftw.ae2intelligentscheduling.network.packet;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import cpw.mods.fml.common.network.ByteBufUtils;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

import com.homeftw.ae2intelligentscheduling.AE2IntelligentScheduling;
import com.homeftw.ae2intelligentscheduling.smartcraft.model.SmartCraftLayer;
import com.homeftw.ae2intelligentscheduling.smartcraft.model.SmartCraftOrder;
import com.homeftw.ae2intelligentscheduling.smartcraft.model.SmartCraftStatus;
import com.homeftw.ae2intelligentscheduling.smartcraft.model.SmartCraftTask;

public final class SyncSmartCraftOrderPacket implements IMessage {

    public static final class TaskView {

        private final String requestKeyId;
        private final long amount;
        private final int depth;
        private final int splitIndex;
        private final int splitCount;
        private final SmartCraftStatus status;
        private final String blockingReason;

        public TaskView(String requestKeyId, long amount, int depth, int splitIndex, int splitCount,
                SmartCraftStatus status, String blockingReason) {
            this.requestKeyId = requestKeyId;
            this.amount = amount;
            this.depth = depth;
            this.splitIndex = splitIndex;
            this.splitCount = splitCount;
            this.status = status;
            this.blockingReason = blockingReason;
        }

        public String requestKeyId() {
            return this.requestKeyId;
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

    private String orderId;
    private String targetRequestKeyId;
    private long targetAmount;
    private String orderScale;
    private String status;
    private int currentLayer;
    private int totalLayers;
    private List<TaskView> tasks;

    public SyncSmartCraftOrderPacket() {
        this.tasks = new ArrayList<TaskView>();
    }

    private SyncSmartCraftOrderPacket(String orderId, String targetRequestKeyId, long targetAmount, String orderScale,
            String status, int currentLayer, int totalLayers, List<TaskView> tasks) {
        this.orderId = orderId;
        this.targetRequestKeyId = targetRequestKeyId;
        this.targetAmount = targetAmount;
        this.orderScale = orderScale;
        this.status = status;
        this.currentLayer = currentLayer;
        this.totalLayers = totalLayers;
        this.tasks = new ArrayList<TaskView>(tasks);
    }

    public static SyncSmartCraftOrderPacket from(UUID orderId, SmartCraftOrder order) {
        List<TaskView> views = new ArrayList<TaskView>();
        for (SmartCraftLayer layer : order.layers()) {
            for (SmartCraftTask task : layer.tasks()) {
                views.add(new TaskView(
                    task.requestKey().id(),
                    task.amount(),
                    task.depth(),
                    task.splitIndex(),
                    task.splitCount(),
                    task.status(),
                    task.blockingReason() == null ? "" : task.blockingReason()));
            }
        }

        return new SyncSmartCraftOrderPacket(
            orderId.toString(),
            order.targetRequestKey().id(),
            order.targetAmount(),
            order.orderScale().name(),
            order.status().name(),
            order.currentLayerIndex(),
            order.layers().size(),
            views);
    }

    public String getOrderId() {
        return this.orderId;
    }

    public String getTargetRequestKeyId() {
        return this.targetRequestKeyId;
    }

    public long getTargetAmount() {
        return this.targetAmount;
    }

    public String getOrderScale() {
        return this.orderScale;
    }

    public String getStatus() {
        return this.status;
    }

    public int getCurrentLayer() {
        return this.currentLayer;
    }

    public int getTotalLayers() {
        return this.totalLayers;
    }

    public List<TaskView> getTasks() {
        return Collections.unmodifiableList(this.tasks);
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        this.orderId = ByteBufUtils.readUTF8String(buf);
        this.targetRequestKeyId = ByteBufUtils.readUTF8String(buf);
        this.targetAmount = buf.readLong();
        this.orderScale = ByteBufUtils.readUTF8String(buf);
        this.status = ByteBufUtils.readUTF8String(buf);
        this.currentLayer = buf.readInt();
        this.totalLayers = buf.readInt();

        int taskCount = buf.readInt();
        this.tasks = new ArrayList<TaskView>(taskCount);
        for (int i = 0; i < taskCount; i++) {
            this.tasks.add(new TaskView(
                ByteBufUtils.readUTF8String(buf),
                buf.readLong(),
                buf.readInt(),
                buf.readInt(),
                buf.readInt(),
                SmartCraftStatus.valueOf(ByteBufUtils.readUTF8String(buf)),
                ByteBufUtils.readUTF8String(buf)));
        }
    }

    @Override
    public void toBytes(ByteBuf buf) {
        ByteBufUtils.writeUTF8String(buf, this.orderId);
        ByteBufUtils.writeUTF8String(buf, this.targetRequestKeyId);
        buf.writeLong(this.targetAmount);
        ByteBufUtils.writeUTF8String(buf, this.orderScale);
        ByteBufUtils.writeUTF8String(buf, this.status);
        buf.writeInt(this.currentLayer);
        buf.writeInt(this.totalLayers);
        buf.writeInt(this.tasks.size());

        for (TaskView task : this.tasks) {
            ByteBufUtils.writeUTF8String(buf, task.requestKeyId());
            buf.writeLong(task.amount());
            buf.writeInt(task.depth());
            buf.writeInt(task.splitIndex());
            buf.writeInt(task.splitCount());
            ByteBufUtils.writeUTF8String(buf, task.status().name());
            ByteBufUtils.writeUTF8String(buf, task.blockingReason());
        }
    }

    public static final class Handler implements IMessageHandler<SyncSmartCraftOrderPacket, IMessage> {

        @Override
        public IMessage onMessage(SyncSmartCraftOrderPacket message, MessageContext ctx) {
            AE2IntelligentScheduling.proxy.openSmartCraftStatus(message);
            return null;
        }
    }
}
