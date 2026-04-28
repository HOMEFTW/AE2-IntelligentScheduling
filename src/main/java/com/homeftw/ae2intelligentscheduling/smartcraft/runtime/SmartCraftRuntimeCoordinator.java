package com.homeftw.ae2intelligentscheduling.smartcraft.runtime;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

import com.homeftw.ae2intelligentscheduling.integration.ae2.Ae2CpuSelector;
import com.homeftw.ae2intelligentscheduling.smartcraft.model.SmartCraftLayer;
import com.homeftw.ae2intelligentscheduling.smartcraft.model.SmartCraftOrder;
import com.homeftw.ae2intelligentscheduling.smartcraft.model.SmartCraftStatus;
import com.homeftw.ae2intelligentscheduling.smartcraft.model.SmartCraftTask;

import appeng.api.networking.crafting.ICraftingCPU;
import appeng.api.networking.crafting.ICraftingJob;
import appeng.api.networking.crafting.ICraftingLink;

public final class SmartCraftRuntimeCoordinator {

    public interface JobPlanner {

        Future<ICraftingJob> begin(SmartCraftRuntimeSession session, SmartCraftTask task);
    }

    public interface JobSubmitter {

        ICraftingLink submit(SmartCraftRuntimeSession session, SmartCraftTask task, ICraftingCPU cpu, ICraftingJob job);
    }

    public interface OrderSync {

        void sync(SmartCraftRuntimeSession session, UUID orderId);
    }

    private static final String FAILED_TO_BEGIN_REASON = "Failed to begin AE2 crafting job calculation";
    private static final String FAILED_TO_FINISH_REASON = "Failed to finish AE2 crafting job calculation";
    private static final String FAILED_TO_SUBMIT_REASON = "Failed to submit AE2 crafting job";
    private static final String NO_IDLE_CPU_REASON = "No idle AE2 crafting CPU available";

    private final SmartCraftOrderManager orderManager;
    private final Ae2CpuSelector cpuSelector;
    private final JobPlanner jobPlanner;
    private final JobSubmitter jobSubmitter;
    private final OrderSync orderSync;
    private final Map<UUID, SmartCraftRuntimeSession> sessions = new LinkedHashMap<UUID, SmartCraftRuntimeSession>();

    public SmartCraftRuntimeCoordinator(SmartCraftOrderManager orderManager, Ae2CpuSelector cpuSelector,
        JobPlanner jobPlanner, JobSubmitter jobSubmitter, OrderSync orderSync) {
        this.orderManager = orderManager;
        this.cpuSelector = cpuSelector;
        this.jobPlanner = jobPlanner;
        this.jobSubmitter = jobSubmitter;
        this.orderSync = orderSync;
    }

    public void register(UUID orderId, SmartCraftRuntimeSession session) {
        if (orderId == null || session == null) return;
        this.sessions.put(orderId, session);
    }

    public Optional<SmartCraftRuntimeSession> session(UUID orderId) {
        return Optional.ofNullable(this.sessions.get(orderId));
    }

    public void syncLatestOrderForPlayer(net.minecraft.entity.player.EntityPlayerMP player) {
        for (java.util.Map.Entry<UUID, SmartCraftRuntimeSession> entry : this.sessions.entrySet()) {
            if (player.equals(
                entry.getValue()
                    .owner())) {
                this.orderSync.sync(entry.getValue(), entry.getKey());
                return;
            }
        }
    }

    public appeng.api.networking.crafting.ICraftingGrid craftingGridForPlayer(
        net.minecraft.entity.player.EntityPlayerMP player) {
        for (SmartCraftRuntimeSession session : this.sessions.values()) {
            if (player.equals(session.owner())) {
                return session.craftingGrid();
            }
        }
        return null;
    }

    public void tick() {
        List<UUID> orphanedSessions = new ArrayList<UUID>();

        for (Map.Entry<UUID, SmartCraftRuntimeSession> entry : this.sessions.entrySet()) {
            UUID orderId = entry.getKey();
            SmartCraftRuntimeSession session = entry.getValue();
            Optional<SmartCraftOrder> existing = this.orderManager.get(orderId);
            if (!existing.isPresent()) {
                // Order has been removed from the manager (e.g. external admin command). Drop the
                // session along with any in-flight AE2 jobs to avoid leaking links.
                session.cancelAll();
                orphanedSessions.add(orderId);
                continue;
            }

            SmartCraftOrder order = existing.get();
            SmartCraftOrder updated = updateOrder(session, order);
            if (updated != order) {
                this.orderManager.update(orderId, updated);
                this.orderSync.sync(session, orderId);
            }
            // NOTE: We deliberately keep the session attached even when the order reaches a terminal
            // state. The session must outlive CANCELLED / COMPLETED / PAUSED so that the player can
            // still issue retry from the UI and have the next tick resume scheduling without losing
            // the ICraftingGrid / requester bridge handle.
        }

        for (UUID orderId : orphanedSessions) {
            this.sessions.remove(orderId);
        }
    }

    /**
     * Cancel an order: propagate to AE2 (cancel planning futures + cancel crafting links), flip every
     * non-terminal task to CANCELLED and the order itself to CANCELLED. Keeps the session alive so the
     * player can still trigger a retry from the UI without re-opening the terminal.
     */
    public Optional<SmartCraftOrder> cancel(UUID orderId) {
        SmartCraftRuntimeSession session = this.sessions.get(orderId);
        if (session != null) {
            session.cancelAll();
        }
        Optional<SmartCraftOrder> cancelled = this.orderManager.cancel(orderId);
        if (cancelled.isPresent() && session != null) {
            this.orderSync.sync(session, orderId);
        }
        return cancelled;
    }

    /**
     * Retry failed AND cancelled tasks. Clears any leftover execution entries so the next dispatch
     * pass can re-plan them from scratch instead of skipping due to stale {@link
     * SmartCraftRuntimeSession.TaskExecution} state.
     */
    public Optional<SmartCraftOrder> retryFailed(UUID orderId) {
        SmartCraftRuntimeSession session = this.sessions.get(orderId);
        Optional<SmartCraftOrder> retried = this.orderManager.retryFailedTasks(orderId);
        if (retried.isPresent() && session != null) {
            // Wipe any stale execution / stock baseline left over from the original run so the next
            // dispatchReadyTasks tick gives them a fresh planning future.
            for (SmartCraftLayer layer : retried.get()
                .layers()) {
                for (SmartCraftTask task : layer.tasks()) {
                    if (task.status() == SmartCraftStatus.PENDING) {
                        session.clearExecution(task);
                    }
                }
            }
            this.orderSync.sync(session, orderId);
        }
        return retried;
    }

    private SmartCraftOrder updateOrder(SmartCraftRuntimeSession session, SmartCraftOrder order) {
        SmartCraftOrder updated = reconcileTaskExecutions(session, order);
        updated = advanceLayers(updated);

        if (updated.status() == SmartCraftStatus.CANCELLED || updated.status() == SmartCraftStatus.FAILED
            || updated.status() == SmartCraftStatus.COMPLETED
            || updated.status() == SmartCraftStatus.PAUSED) {
            return updated;
        }

        updated = dispatchReadyTasks(session, updated);
        return applyLayerStatus(updated);
    }

    private SmartCraftOrder reconcileTaskExecutions(SmartCraftRuntimeSession session, SmartCraftOrder order) {
        SmartCraftOrder updated = order;
        for (int layerIndex = 0; layerIndex < updated.layers()
            .size(); layerIndex++) {
            SmartCraftLayer layer = updated.layers()
                .get(layerIndex);
            List<SmartCraftTask> nextTasks = new ArrayList<SmartCraftTask>(
                layer.tasks()
                    .size());
            boolean layerChanged = false;

            for (SmartCraftTask task : layer.tasks()) {
                SmartCraftTask nextTask = reconcileTaskExecution(session, order, task);
                if (nextTask != task) {
                    layerChanged = true;
                }
                nextTasks.add(nextTask);
            }

            if (layerChanged) {
                updated = updated.withLayer(layerIndex, layer.withTasks(nextTasks));
            }
        }
        return updated;
    }

    private SmartCraftTask reconcileTaskExecution(SmartCraftRuntimeSession session, SmartCraftOrder order,
        SmartCraftTask task) {
        // Tasks already in a terminal state must not be re-touched by AE2 link state.
        // Without this guard, a task that was just CANCELLED by `orderManager.cancel(...)` could be
        // flipped to FAILED on the next tick when the cancelled link reports `isCanceled()`.
        if (task.isTerminal()) {
            session.clearExecution(task);
            return task;
        }

        // VERIFYING_OUTPUT is kept around for backward compatibility (orders that were already in
        // this state when the fix below shipped). Treat it as a one-tick passthrough to DONE so we
        // never strand a task here. The previous "wait for stock to grow by amount" logic was broken:
        // by the time we observed link.isDone() the items were ALREADY in ME storage (AE2's
        // completeJob() runs in the same call stack as Platform.poweredInsert for the final output),
        // so the captured baseline already included the crafted output and the delta never reached
        // task.amount(). Trust AE2's terminal link state instead.
        if (task.status() == SmartCraftStatus.VERIFYING_OUTPUT) {
            session.clearExecution(task);
            return task.withStatus(SmartCraftStatus.DONE, null);
        }

        SmartCraftRuntimeSession.TaskExecution execution = session.executionFor(task);
        if (execution == null) {
            return task;
        }

        ICraftingLink craftingLink = execution.craftingLink();
        if (craftingLink != null) {
            if (craftingLink.isDone()) {
                // AE2's CraftingCPUCluster.injectItems decrements finalOutput, calls our bridge, then
                // invokes completeJob() (which markDone()s the link) — all in the same call stack
                // that subsequently routes the leftover stack into ME storage via the storage handler
                // chain. By the time we observe link.isDone() on the next server tick the items are
                // already queryable in the network. There is no need (and previously no correct way)
                // to verify the output via a stock baseline here; just transition to DONE so the next
                // layer can be dispatched.
                session.clearExecution(task);
                return task.withStatus(SmartCraftStatus.DONE, null);
            }
            if (craftingLink.isCanceled()) {
                session.clearExecution(task);
                return task.withStatus(
                    order.status() == SmartCraftStatus.CANCELLED ? SmartCraftStatus.CANCELLED : SmartCraftStatus.FAILED,
                    FAILED_TO_SUBMIT_REASON);
            }
            return task.withStatus(SmartCraftStatus.RUNNING, null);
        }

        ICraftingJob plannedJob = execution.plannedJob();
        if (plannedJob != null) {
            return task;
        }

        Future<ICraftingJob> planningFuture = execution.planningFuture();
        if (planningFuture == null || !planningFuture.isDone()) {
            return task.withStatus(SmartCraftStatus.SUBMITTING, null);
        }

        try {
            ICraftingJob readyJob = planningFuture.get();
            if (readyJob == null || readyJob.isSimulation()) {
                session.clearExecution(task);
                return task.withStatus(SmartCraftStatus.FAILED, FAILED_TO_FINISH_REASON);
            }

            session.attachPlannedJob(task, readyJob);
            return task.withStatus(SmartCraftStatus.WAITING_CPU, NO_IDLE_CPU_REASON);
        } catch (InterruptedException e) {
            Thread.currentThread()
                .interrupt();
            session.clearExecution(task);
            return task.withStatus(SmartCraftStatus.FAILED, FAILED_TO_FINISH_REASON);
        } catch (ExecutionException e) {
            session.clearExecution(task);
            return task.withStatus(SmartCraftStatus.FAILED, FAILED_TO_FINISH_REASON);
        }
    }

    private SmartCraftOrder advanceLayers(SmartCraftOrder order) {
        SmartCraftOrder updated = order;
        while (!updated.isFinished()) {
            SmartCraftLayer currentLayer = updated.currentLayer();
            if (currentLayer == null) {
                return updated.withStatus(SmartCraftStatus.COMPLETED);
            }
            if (!currentLayer.isComplete()) {
                return updated;
            }

            SmartCraftOrder advanced = updated.advanceLayer();
            if (advanced.currentLayerIndex() >= advanced.layers()
                .size()) {
                return advanced.withStatus(SmartCraftStatus.COMPLETED);
            }
            updated = advanced.withStatus(SmartCraftStatus.QUEUED);
        }
        return updated;
    }

    private SmartCraftOrder dispatchReadyTasks(SmartCraftRuntimeSession session, SmartCraftOrder order) {
        SmartCraftLayer currentLayer = order.currentLayer();
        if (currentLayer == null) {
            return order;
        }

        // CPU pool is consulted ONLY when we are about to submit a fully-planned job. Planning itself
        // does not consume a CPU; we want as many tasks planning in parallel as the AE2 grid allows.
        // The previous implementation took a CPU at planning time, which starved planning in busy
        // grids and produced bogus WAITING_CPU labels even for tasks that had not begun planning yet.
        List<ICraftingCPU> availableCpus = this.cpuSelector.idleCpus(
            session.craftingGrid()
                .getCpus());
        List<SmartCraftTask> nextTasks = new ArrayList<SmartCraftTask>(
            currentLayer.tasks()
                .size());
        boolean changed = false;

        for (SmartCraftTask task : currentLayer.tasks()) {
            SmartCraftTask nextTask = task;
            SmartCraftRuntimeSession.TaskExecution execution = session.executionFor(task);

            if (task.isTerminal()) {
                nextTasks.add(task);
                continue;
            }

            if (execution != null && execution.plannedJob() != null && execution.craftingLink() == null) {
                Optional<ICraftingCPU> selectedCpu = takeNextCpu(availableCpus);
                if (!selectedCpu.isPresent()) {
                    nextTask = task.withStatus(SmartCraftStatus.WAITING_CPU, NO_IDLE_CPU_REASON);
                } else {
                    session.attachAssignedCpu(task, selectedCpu.get());
                    ICraftingLink link = this.jobSubmitter
                        .submit(session, task, selectedCpu.get(), execution.plannedJob());
                    if (link != null) {
                        session.attachCraftingLink(task, link);
                        nextTask = task.withStatus(SmartCraftStatus.RUNNING, null);
                    } else {
                        session.clearExecution(task);
                        nextTask = task.withStatus(SmartCraftStatus.FAILED, FAILED_TO_SUBMIT_REASON);
                    }
                }
                changed = changed || nextTask != task;
                nextTasks.add(nextTask);
                continue;
            }

            if (execution != null || !task.isReadyForSubmission()) {
                nextTasks.add(task);
                continue;
            }

            // Begin planning unconditionally — CPU availability only gates submission.
            try {
                Future<ICraftingJob> planningFuture = this.jobPlanner.begin(session, task);
                if (planningFuture == null) {
                    nextTask = task.withStatus(SmartCraftStatus.FAILED, FAILED_TO_BEGIN_REASON);
                } else {
                    session.trackPlanning(task, planningFuture);
                    nextTask = task.withStatus(SmartCraftStatus.SUBMITTING, null);
                }
            } catch (RuntimeException e) {
                nextTask = task.withStatus(SmartCraftStatus.FAILED, FAILED_TO_BEGIN_REASON);
            }

            changed = changed || nextTask != task;
            nextTasks.add(nextTask);
        }

        if (!changed) {
            return order;
        }

        return order.withLayer(order.currentLayerIndex(), currentLayer.withTasks(nextTasks));
    }

    private SmartCraftOrder applyLayerStatus(SmartCraftOrder order) {
        SmartCraftLayer currentLayer = order.currentLayer();
        if (currentLayer == null) {
            return order.withStatus(SmartCraftStatus.COMPLETED);
        }

        boolean hasFailed = false;
        boolean hasActive = false;
        boolean hasWaiting = false;
        boolean hasPlannable = false;

        for (SmartCraftTask task : currentLayer.tasks()) {
            SmartCraftStatus s = task.status();
            if (s == SmartCraftStatus.FAILED) {
                hasFailed = true;
            }
            if (s == SmartCraftStatus.SUBMITTING || s == SmartCraftStatus.RUNNING
                || s == SmartCraftStatus.VERIFYING_OUTPUT) {
                hasActive = true;
            }
            if (s == SmartCraftStatus.WAITING_CPU) {
                hasWaiting = true;
            }
            // PENDING / QUEUED tasks have not started yet but are eligible for the next dispatch pass.
            // Historically this branch was missing, which let an order with [FAILED, WAITING_CPU] fall
            // into PAUSED below — and PAUSED short-circuits updateOrder so the WAITING_CPU sibling
            // never got another chance, even when a CPU freed up. Treat plannable tasks as live work.
            if (s == SmartCraftStatus.PENDING || s == SmartCraftStatus.QUEUED) {
                hasPlannable = true;
            }
        }

        // Anything currently being executed wins: keep the order RUNNING (and let the next tick decide
        // again) regardless of whether some siblings have failed.
        if (hasActive) {
            return order.withStatus(SmartCraftStatus.RUNNING);
        }
        // Nothing actively executing — but some task could still progress on its own next tick.
        if (hasPlannable) {
            return order.withStatus(SmartCraftStatus.QUEUED);
        }
        if (hasWaiting) {
            return order.withStatus(SmartCraftStatus.WAITING_CPU);
        }
        // Nothing left that could move forward; if any task failed, surface PAUSED so the player can
        // press "Retry Failed". If we got here without a single failure, the layer must be entirely
        // terminal-success and advanceLayers will pick the next layer up next tick.
        if (hasFailed) {
            return order.withStatus(SmartCraftStatus.PAUSED);
        }
        return order.withStatus(SmartCraftStatus.QUEUED);
    }

    private Optional<ICraftingCPU> takeNextCpu(List<ICraftingCPU> availableCpus) {
        return availableCpus.isEmpty() ? Optional.<ICraftingCPU>empty() : Optional.of(availableCpus.remove(0));
    }
}
