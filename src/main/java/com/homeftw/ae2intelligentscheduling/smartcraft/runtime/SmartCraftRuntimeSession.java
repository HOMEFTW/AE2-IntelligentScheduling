package com.homeftw.ae2intelligentscheduling.smartcraft.runtime;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.world.World;

import com.homeftw.ae2intelligentscheduling.smartcraft.model.SmartCraftTask;

import appeng.api.networking.IGrid;
import appeng.api.networking.crafting.ICraftingCPU;
import appeng.api.networking.crafting.ICraftingGrid;
import appeng.api.networking.crafting.ICraftingJob;
import appeng.api.networking.crafting.ICraftingLink;
import appeng.api.networking.security.BaseActionSource;
import appeng.api.networking.security.IActionHost;

public final class SmartCraftRuntimeSession {

    public static final class TaskExecution {

        private final String taskId;
        private final java.util.concurrent.Future<ICraftingJob> planningFuture;
        private final ICraftingJob plannedJob;
        private final ICraftingLink craftingLink;
        private final String assignedCpuName;

        private TaskExecution(String taskId, java.util.concurrent.Future<ICraftingJob> planningFuture,
            ICraftingJob plannedJob, ICraftingLink craftingLink, String assignedCpuName) {
            this.taskId = taskId;
            this.planningFuture = planningFuture;
            this.plannedJob = plannedJob;
            this.craftingLink = craftingLink;
            this.assignedCpuName = assignedCpuName;
        }

        public static TaskExecution planning(String taskId, java.util.concurrent.Future<ICraftingJob> planningFuture) {
            return new TaskExecution(taskId, planningFuture, null, null, null);
        }

        public TaskExecution withPlannedJob(ICraftingJob nextJob) {
            return new TaskExecution(
                this.taskId,
                this.planningFuture,
                nextJob,
                this.craftingLink,
                this.assignedCpuName);
        }

        public TaskExecution withAssignedCpuName(String nextAssignedCpuName) {
            return new TaskExecution(
                this.taskId,
                this.planningFuture,
                this.plannedJob,
                this.craftingLink,
                nextAssignedCpuName);
        }

        public TaskExecution withCraftingLink(ICraftingLink nextLink) {
            return new TaskExecution(this.taskId, this.planningFuture, this.plannedJob, nextLink, this.assignedCpuName);
        }

        public String taskId() {
            return this.taskId;
        }

        public java.util.concurrent.Future<ICraftingJob> planningFuture() {
            return this.planningFuture;
        }

        public ICraftingJob plannedJob() {
            return this.plannedJob;
        }

        public ICraftingLink craftingLink() {
            return this.craftingLink;
        }

        public String assignedCpuName() {
            return this.assignedCpuName;
        }
    }

    private final EntityPlayerMP owner;
    private final World world;
    private final IActionHost actionHost;
    private final BaseActionSource actionSource;
    private final IGrid grid;
    private final ICraftingGrid craftingGrid;
    private final SmartCraftRequesterBridge requesterBridge;
    private final Map<String, TaskExecution> executionsByTaskId = new LinkedHashMap<String, TaskExecution>();
    private final Map<String, Long> stockBaselineByTaskId = new LinkedHashMap<String, Long>();

    public SmartCraftRuntimeSession(EntityPlayerMP owner, World world, IActionHost actionHost,
        BaseActionSource actionSource, IGrid grid, ICraftingGrid craftingGrid,
        SmartCraftRequesterBridge requesterBridge) {
        this.owner = owner;
        this.world = world;
        this.actionHost = actionHost;
        this.actionSource = actionSource;
        this.grid = grid;
        this.craftingGrid = craftingGrid;
        this.requesterBridge = requesterBridge;
    }

    public EntityPlayerMP owner() {
        return this.owner;
    }

    public World world() {
        return this.world;
    }

    public IActionHost actionHost() {
        return this.actionHost;
    }

    public BaseActionSource actionSource() {
        return this.actionSource;
    }

    public IGrid grid() {
        return this.grid;
    }

    public ICraftingGrid craftingGrid() {
        return this.craftingGrid;
    }

    public SmartCraftRequesterBridge requesterBridge() {
        return this.requesterBridge;
    }

    public TaskExecution executionFor(SmartCraftTask task) {
        return task == null ? null : this.executionsByTaskId.get(task.taskKey());
    }

    public void trackPlanning(SmartCraftTask task, java.util.concurrent.Future<ICraftingJob> planningFuture) {
        if (task == null || planningFuture == null) {
            return;
        }
        this.executionsByTaskId.put(task.taskKey(), TaskExecution.planning(task.taskKey(), planningFuture));
    }

    public void attachPlannedJob(SmartCraftTask task, ICraftingJob plannedJob) {
        TaskExecution existing = executionFor(task);
        if (existing == null) {
            return;
        }
        this.executionsByTaskId.put(task.taskKey(), existing.withPlannedJob(plannedJob));
    }

    public void attachCraftingLink(SmartCraftTask task, ICraftingLink craftingLink) {
        TaskExecution existing = executionFor(task);
        if (existing == null) {
            return;
        }
        this.executionsByTaskId.put(task.taskKey(), existing.withCraftingLink(craftingLink));
    }

    public void attachAssignedCpu(SmartCraftTask task, ICraftingCPU cpu) {
        TaskExecution existing = executionFor(task);
        if (existing == null || cpu == null) {
            return;
        }
        this.executionsByTaskId.put(task.taskKey(), existing.withAssignedCpuName(cpu.getName()));
    }

    public void recordStockBaseline(SmartCraftTask task, long currentStock) {
        if (task == null) return;
        this.stockBaselineByTaskId.put(task.taskKey(), Long.valueOf(currentStock));
    }

    public long stockBaseline(SmartCraftTask task) {
        if (task == null) return 0L;
        Long v = this.stockBaselineByTaskId.get(task.taskKey());
        return v == null ? 0L : v.longValue();
    }

    public void clearExecution(SmartCraftTask task) {
        if (task == null) {
            return;
        }
        this.executionsByTaskId.remove(task.taskKey());
        this.stockBaselineByTaskId.remove(task.taskKey());
    }

    public void cancelAll() {
        Iterator<TaskExecution> iterator = this.executionsByTaskId.values()
            .iterator();
        while (iterator.hasNext()) {
            TaskExecution execution = iterator.next();
            if (execution.craftingLink() != null) {
                execution.craftingLink()
                    .cancel();
            }
            if (execution.planningFuture() != null) {
                execution.planningFuture()
                    .cancel(true);
            }
            iterator.remove();
        }
    }
}
