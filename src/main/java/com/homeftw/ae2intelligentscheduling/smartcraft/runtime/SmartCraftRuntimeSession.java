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
        /**
         * Server-tick timestamp captured when {@link #planning} was called. Used by the runtime
         * coordinator to detect AE2 planner hangs: if the planning future stays unresolved beyond
         * the configured timeout we forcibly cancel it and surface FAILED instead of leaving the
         * task wedged in SUBMITTING forever (e.g. when AE2 hits an internal deadlock or when the
         * grid topology changed under planning's feet).
         */
        private final long submittedAtTick;
        /**
         * Tick at which this task first entered WAITING_CPU after its planning completed. Drives the
         * anti-starvation tiebreaker in the dispatch sort: among tasks of equal critical-path
         * priority, the one that has been waiting longest gets a CPU first. {@code -1} means the
         * task has not yet been observed in WAITING_CPU on this execution; once stamped it is never
         * re-stamped (so a task that gets a CPU, finishes, and re-enters via retry would create a
         * fresh execution with its own counter).
         */
        private final long waitingCpuSinceTick;

        private TaskExecution(String taskId, java.util.concurrent.Future<ICraftingJob> planningFuture,
            ICraftingJob plannedJob, ICraftingLink craftingLink, String assignedCpuName, long submittedAtTick,
            long waitingCpuSinceTick) {
            this.taskId = taskId;
            this.planningFuture = planningFuture;
            this.plannedJob = plannedJob;
            this.craftingLink = craftingLink;
            this.assignedCpuName = assignedCpuName;
            this.submittedAtTick = submittedAtTick;
            this.waitingCpuSinceTick = waitingCpuSinceTick;
        }

        public static TaskExecution planning(String taskId, java.util.concurrent.Future<ICraftingJob> planningFuture,
            long submittedAtTick) {
            return new TaskExecution(taskId, planningFuture, null, null, null, submittedAtTick, -1L);
        }

        public TaskExecution withPlannedJob(ICraftingJob nextJob) {
            return new TaskExecution(
                this.taskId,
                this.planningFuture,
                nextJob,
                this.craftingLink,
                this.assignedCpuName,
                this.submittedAtTick,
                this.waitingCpuSinceTick);
        }

        public TaskExecution withAssignedCpuName(String nextAssignedCpuName) {
            return new TaskExecution(
                this.taskId,
                this.planningFuture,
                this.plannedJob,
                this.craftingLink,
                nextAssignedCpuName,
                this.submittedAtTick,
                this.waitingCpuSinceTick);
        }

        public TaskExecution withCraftingLink(ICraftingLink nextLink) {
            return new TaskExecution(
                this.taskId,
                this.planningFuture,
                this.plannedJob,
                nextLink,
                this.assignedCpuName,
                this.submittedAtTick,
                this.waitingCpuSinceTick);
        }

        /**
         * Returns a copy with the WAITING_CPU entry-tick stamped. Idempotent — once set we keep the
         * original tick because the whole point of the field is "how long has this been waiting".
         */
        public TaskExecution withWaitingCpuSinceTick(long tick) {
            if (this.waitingCpuSinceTick >= 0L) {
                return this;
            }
            return new TaskExecution(
                this.taskId,
                this.planningFuture,
                this.plannedJob,
                this.craftingLink,
                this.assignedCpuName,
                this.submittedAtTick,
                tick);
        }

        public String taskId() {
            return this.taskId;
        }

        public java.util.concurrent.Future<ICraftingJob> planningFuture() {
            return this.planningFuture;
        }

        public long submittedAtTick() {
            return this.submittedAtTick;
        }

        public ICraftingJob plannedJob() {
            return this.plannedJob;
        }

        public long waitingCpuSinceTick() {
            return this.waitingCpuSinceTick;
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

    /**
     * Display name suitable for embedding in client-side tooltips. Returns an empty string when
     * the owner is null (defensive — production paths always set it but tests construct sessions
     * with mocked owners).
     */
    public String ownerName() {
        return this.owner == null ? "" : this.owner.getCommandSenderName();
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

    /**
     * v0.1.9.2 (G13) Returns how many tasks in this session currently hold an AE2 crafting link
     * (i.e. occupy a CraftingCPU cluster). Used by
     * {@code SmartCraftRuntimeCoordinator} to enforce the global submission cap from
     * {@code Config.MAX_CONCURRENT_SMART_CRAFT_SUBMISSIONS}.
     *
     * <p>Counts every execution whose {@code craftingLink} is non-null \u2014 including links that
     * AE2 has already finished or canceled but that the next reconcile pass has not yet swept
     * away. This slight over-count is fine: it errs on the side of throttling more, never less,
     * so the cap is never violated even in the worst-case interleaving where reconcile races
     * against dispatch.
     */
    public int countActiveSubmissions() {
        int active = 0;
        for (TaskExecution exe : this.executionsByTaskId.values()) {
            if (exe.craftingLink() != null) active++;
        }
        return active;
    }

    public void trackPlanning(SmartCraftTask task, java.util.concurrent.Future<ICraftingJob> planningFuture,
        long submittedAtTick) {
        if (task == null || planningFuture == null) {
            return;
        }
        this.executionsByTaskId
            .put(task.taskKey(), TaskExecution.planning(task.taskKey(), planningFuture, submittedAtTick));
    }

    public void attachPlannedJob(SmartCraftTask task, ICraftingJob plannedJob) {
        TaskExecution existing = executionFor(task);
        if (existing == null) {
            return;
        }
        this.executionsByTaskId.put(task.taskKey(), existing.withPlannedJob(plannedJob));
    }

    /**
     * Stamp the tick at which this task entered WAITING_CPU for the first time on this execution.
     * Drives anti-starvation in dispatch: tasks that have been waiting longer get scheduled first
     * when CPUs free up. Idempotent: re-calling on an already-stamped execution is a no-op.
     */
    public void markWaitingCpu(SmartCraftTask task, long tick) {
        TaskExecution existing = executionFor(task);
        if (existing == null) {
            return;
        }
        TaskExecution updated = existing.withWaitingCpuSinceTick(tick);
        if (updated != existing) {
            this.executionsByTaskId.put(task.taskKey(), updated);
        }
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

    /**
     * v0.1.8 (G5): drop the assigned-CPU pin without touching the cached plan or planning future.
     * Used by submit-failure backoff: AE2 rejected our submission so the chosen CPU is no longer
     * reserved, but the plannedJob is still valid (plan succeeded earlier; the failure happened
     * at submitJob time, not during planning). Next dispatch can re-pick a fresh idle CPU.
     */
    public void detachAssignedCpu(SmartCraftTask task) {
        TaskExecution existing = executionFor(task);
        if (existing == null) {
            return;
        }
        this.executionsByTaskId.put(task.taskKey(), existing.withAssignedCpuName(null));
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
        // FIX (P0-#1): cancel any still-running planning future before dropping the execution.
        // Without this, an order cancelled while a task is mid-planning leaks an AE2 planner thread
        // that may later try to write back into a session whose execution map has been cleared,
        // producing ghost entries or NullPointerException. cancelAll() handles this for full session
        // teardown but per-task transitions (terminal task in reconcileTaskExecution, submit-failed
        // path in dispatchReadyTasks, etc.) used to leak the future. Future.cancel is idempotent so
        // the always-already-done callers (link.isDone() / link.isCanceled()) cost nothing.
        TaskExecution removed = this.executionsByTaskId.remove(task.taskKey());
        if (removed != null && removed.planningFuture() != null
            && !removed.planningFuture()
                .isDone()) {
            removed.planningFuture()
                .cancel(true);
        }
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
