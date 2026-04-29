package com.homeftw.ae2intelligentscheduling.network.packet;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import net.minecraft.item.ItemStack;

import com.homeftw.ae2intelligentscheduling.AE2IntelligentScheduling;
import com.homeftw.ae2intelligentscheduling.smartcraft.model.SmartCraftLayer;
import com.homeftw.ae2intelligentscheduling.smartcraft.model.SmartCraftOrder;
import com.homeftw.ae2intelligentscheduling.smartcraft.model.SmartCraftStatus;
import com.homeftw.ae2intelligentscheduling.smartcraft.model.SmartCraftTask;
import com.homeftw.ae2intelligentscheduling.smartcraft.runtime.SmartCraftRuntimeSession;

import cpw.mods.fml.common.network.ByteBufUtils;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

public final class SyncSmartCraftOrderPacket implements IMessage {

    public static final class TaskView {

        private final String requestKeyId;
        private final long amount;
        private final int depth;
        private final int splitIndex;
        private final int splitCount;
        private final SmartCraftStatus status;
        private final String blockingReason;
        private final String executionState;
        private final String assignedCpuName;
        private final ItemStack itemStack;
        /**
         * v0.1.8.4 (G11) Sum of pending plan / submit / link-cancel retry attempts for this task.
         * Surfaces in the detail panel as "failed N times" so the player can spot tasks that are
         * currently struggling. 0 means the task is either healthy or has never attempted yet.
         */
        private final int failureCount;

        public TaskView(String requestKeyId, long amount, int depth, int splitIndex, int splitCount,
            SmartCraftStatus status, String blockingReason, String executionState, String assignedCpuName,
            ItemStack itemStack) {
            this(
                requestKeyId,
                amount,
                depth,
                splitIndex,
                splitCount,
                status,
                blockingReason,
                executionState,
                assignedCpuName,
                itemStack,
                0);
        }

        public TaskView(String requestKeyId, long amount, int depth, int splitIndex, int splitCount,
            SmartCraftStatus status, String blockingReason, String executionState, String assignedCpuName,
            ItemStack itemStack, int failureCount) {
            this.requestKeyId = requestKeyId;
            this.amount = amount;
            this.depth = depth;
            this.splitIndex = splitIndex;
            this.splitCount = splitCount;
            this.status = status;
            this.blockingReason = blockingReason;
            this.executionState = executionState;
            this.assignedCpuName = assignedCpuName;
            this.itemStack = itemStack;
            this.failureCount = failureCount;
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

        public String executionState() {
            return this.executionState;
        }

        public String assignedCpuName() {
            return this.assignedCpuName;
        }

        public ItemStack itemStack() {
            return this.itemStack;
        }

        public int failureCount() {
            return this.failureCount;
        }
    }

    private String orderId;
    private String targetRequestKeyId;
    /**
     * v0.1.9.1: ItemStack of the order's final product (root request). Used as the multi-order
     * tab icon so each tab shows what the player asked for (e.g. "Hull") rather than whatever
     * task happens to be first in the layer list (which is a leaf raw material like Iron Ingot
     * — visually misleading because every tab tends to show the same low-tier resource).
     * Nullable when the request key has no item representation (fluids / virtual outputs).
     */
    private ItemStack targetItemStack;
    private long targetAmount;
    private String orderScale;
    private String status;
    private int currentLayer;
    private int totalLayers;
    /**
     * Owner's display name (player.getCommandSenderName()). Empty string when unknown / test
     * sessions. Used by the multi-order tab UI to label tabs like '[Steve] hull ×100'.
     */
    private String ownerName;
    private List<TaskView> tasks;

    public SyncSmartCraftOrderPacket() {
        this.ownerName = "";
        this.tasks = new ArrayList<TaskView>();
    }

    private SyncSmartCraftOrderPacket(String orderId, String targetRequestKeyId, ItemStack targetItemStack,
        long targetAmount, String orderScale, String status, int currentLayer, int totalLayers, String ownerName,
        List<TaskView> tasks) {
        this.orderId = orderId;
        this.targetRequestKeyId = targetRequestKeyId;
        this.targetItemStack = targetItemStack;
        this.targetAmount = targetAmount;
        this.orderScale = orderScale;
        this.status = status;
        this.currentLayer = currentLayer;
        this.totalLayers = totalLayers;
        this.ownerName = ownerName == null ? "" : ownerName;
        this.tasks = new ArrayList<TaskView>(tasks);
    }

    public static SyncSmartCraftOrderPacket from(UUID orderId, SmartCraftOrder order) {
        return from(orderId, order, null);
    }

    public static SyncSmartCraftOrderPacket from(UUID orderId, SmartCraftOrder order,
        SmartCraftRuntimeSession session) {
        List<TaskView> views = new ArrayList<TaskView>();
        for (SmartCraftLayer layer : order.layers()) {
            for (SmartCraftTask task : layer.tasks()) {
                // (v0.1.8.4 G11) Pull the per-task pending failure count from the live runtime
                // coordinator. Static singleton lookup is acceptable because this packet is built
                // exclusively on the server thread that owns the coordinator's tick state; on the
                // client side this method is never called (TaskView is reconstructed via fromBytes).
                int failureCount = AE2IntelligentScheduling.SMART_CRAFT_RUNTIME == null ? 0
                    : AE2IntelligentScheduling.SMART_CRAFT_RUNTIME.totalFailuresFor(task.taskKey());
                views.add(
                    new TaskView(
                        task.requestKey()
                            .id(),
                        task.amount(),
                        task.depth(),
                        task.splitIndex(),
                        task.splitCount(),
                        task.status(),
                        task.blockingReason() == null ? "" : task.blockingReason(),
                        executionStateOf(session, task),
                        assignedCpuNameOf(session, task),
                        task.requestKey() == null ? null
                            : task.requestKey()
                                .itemStack(),
                        failureCount));
            }
        }

        return new SyncSmartCraftOrderPacket(
            orderId.toString(),
            order.targetRequestKey()
                .id(),
            order.targetRequestKey() == null ? null
                : order.targetRequestKey()
                    .itemStack(),
            order.targetAmount(),
            order.orderScale()
                .name(),
            order.status()
                .name(),
            order.currentLayerIndex(),
            order.layers()
                .size(),
            session == null ? "" : session.ownerName(),
            views);
    }

    public String getOrderId() {
        return this.orderId;
    }

    public String getTargetRequestKeyId() {
        return this.targetRequestKeyId;
    }

    /**
     * v0.1.9.1: ItemStack of the final product. May be {@code null} for fluid/virtual targets.
     * Used by {@code SmartCraftOrderTabsWidget} to render the tab icon.
     */
    public ItemStack getTargetItemStack() {
        return this.targetItemStack;
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

    public String getOwnerName() {
        return this.ownerName;
    }

    public List<TaskView> getTasks() {
        return Collections.unmodifiableList(this.tasks);
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        this.orderId = ByteBufUtils.readUTF8String(buf);
        this.targetRequestKeyId = ByteBufUtils.readUTF8String(buf);
        // v0.1.9.1: read targetItemStack just after the id so the wire layout mirrors how
        // TaskView packs its own ItemStack (UTF8 id + ItemStack on the same record). Nullable
        // ItemStacks survive the round-trip via ByteBufUtils' built-in null-marker handling.
        this.targetItemStack = ByteBufUtils.readItemStack(buf);
        this.targetAmount = buf.readLong();
        this.orderScale = ByteBufUtils.readUTF8String(buf);
        this.status = ByteBufUtils.readUTF8String(buf);
        this.currentLayer = buf.readInt();
        this.totalLayers = buf.readInt();
        this.ownerName = ByteBufUtils.readUTF8String(buf);

        int taskCount = buf.readInt();
        this.tasks = new ArrayList<TaskView>(taskCount);
        for (int i = 0; i < taskCount; i++) {
            this.tasks.add(
                new TaskView(
                    ByteBufUtils.readUTF8String(buf),
                    buf.readLong(),
                    buf.readInt(),
                    buf.readInt(),
                    buf.readInt(),
                    SmartCraftStatus.valueOf(ByteBufUtils.readUTF8String(buf)),
                    ByteBufUtils.readUTF8String(buf),
                    ByteBufUtils.readUTF8String(buf),
                    ByteBufUtils.readUTF8String(buf),
                    ByteBufUtils.readItemStack(buf),
                    buf.readInt()));
        }
    }

    @Override
    public void toBytes(ByteBuf buf) {
        ByteBufUtils.writeUTF8String(buf, this.orderId);
        ByteBufUtils.writeUTF8String(buf, this.targetRequestKeyId);
        ByteBufUtils.writeItemStack(buf, this.targetItemStack);
        buf.writeLong(this.targetAmount);
        ByteBufUtils.writeUTF8String(buf, this.orderScale);
        ByteBufUtils.writeUTF8String(buf, this.status);
        buf.writeInt(this.currentLayer);
        buf.writeInt(this.totalLayers);
        ByteBufUtils.writeUTF8String(buf, this.ownerName == null ? "" : this.ownerName);
        buf.writeInt(this.tasks.size());

        for (TaskView task : this.tasks) {
            ByteBufUtils.writeUTF8String(buf, task.requestKeyId());
            buf.writeLong(task.amount());
            buf.writeInt(task.depth());
            buf.writeInt(task.splitIndex());
            buf.writeInt(task.splitCount());
            ByteBufUtils.writeUTF8String(
                buf,
                task.status()
                    .name());
            ByteBufUtils.writeUTF8String(buf, task.blockingReason());
            ByteBufUtils.writeUTF8String(buf, task.executionState());
            ByteBufUtils.writeUTF8String(buf, task.assignedCpuName());
            ByteBufUtils.writeItemStack(buf, task.itemStack());
            buf.writeInt(task.failureCount());
        }
    }

    private static String executionStateOf(SmartCraftRuntimeSession session, SmartCraftTask task) {
        SmartCraftRuntimeSession.TaskExecution execution = executionOf(session, task);
        if (execution == null) {
            return "";
        }
        if (execution.craftingLink() != null) {
            return "SUBMITTED";
        }
        if (execution.plannedJob() != null) {
            return "PLANNED";
        }
        if (execution.planningFuture() != null) {
            return "PLANNING";
        }
        return "";
    }

    private static String assignedCpuNameOf(SmartCraftRuntimeSession session, SmartCraftTask task) {
        SmartCraftRuntimeSession.TaskExecution execution = executionOf(session, task);
        if (execution == null || execution.assignedCpuName() == null) {
            return "";
        }
        return execution.assignedCpuName();
    }

    private static SmartCraftRuntimeSession.TaskExecution executionOf(SmartCraftRuntimeSession session,
        SmartCraftTask task) {
        return session == null || task == null ? null : session.executionFor(task);
    }

    public static final class Handler implements IMessageHandler<SyncSmartCraftOrderPacket, IMessage> {

        @Override
        public IMessage onMessage(SyncSmartCraftOrderPacket message, MessageContext ctx) {
            AE2IntelligentScheduling.proxy.openSmartCraftStatus(message);
            return null;
        }
    }
}
