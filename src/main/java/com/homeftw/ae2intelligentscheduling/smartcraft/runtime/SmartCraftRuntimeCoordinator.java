package com.homeftw.ae2intelligentscheduling.smartcraft.runtime;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

import appeng.api.networking.crafting.ICraftingCPU;
import appeng.api.networking.crafting.ICraftingJob;
import appeng.api.networking.crafting.ICraftingLink;

import com.homeftw.ae2intelligentscheduling.integration.ae2.Ae2CpuSelector;
import com.homeftw.ae2intelligentscheduling.smartcraft.model.SmartCraftLayer;
import com.homeftw.ae2intelligentscheduling.smartcraft.model.SmartCraftOrder;
import com.homeftw.ae2intelligentscheduling.smartcraft.model.SmartCraftStatus;
import com.homeftw.ae2intelligentscheduling.smartcraft.model.SmartCraftTask;

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
        if (orderId == null || session == null) {
            return;
        }
        this.sessions.put(orderId, session);
    }

    public Optional<SmartCraftRuntimeSession> session(UUID orderId) {
        return Optional.ofNullable(this.sessions.get(orderId));
    }

    public void tick() {
        List<UUID> completed = new ArrayList<UUID>();

        for (Map.Entry<UUID, SmartCraftRuntimeSession> entry : this.sessions.entrySet()) {
            UUID orderId = entry.getKey();
            SmartCraftRuntimeSession session = entry.getValue();
            Optional<SmartCraftOrder> existing = this.orderManager.get(orderId);
            if (!existing.isPresent()) {
                completed.add(orderId);
                continue;
            }

            SmartCraftOrder order = existing.get();
            SmartCraftOrder updated = updateOrder(session, order);
            if (updated != order) {
                this.orderManager.update(orderId, updated);
                this.orderSync.sync(session, orderId);
            }

            if (updated.isFinished()) {
                session.cancelAll();
                completed.add(orderId);
            }
        }

        for (UUID orderId : completed) {
            this.sessions.remove(orderId);
        }
    }

    public Optional<SmartCraftOrder> cancel(UUID orderId) {
        SmartCraftRuntimeSession session = this.sessions.get(orderId);
        if (session != null) {
            session.cancelAll();
        }
        Optional<SmartCraftOrder> cancelled = this.orderManager.cancel(orderId);
        if (cancelled.isPresent() && session != null) {
            this.orderSync.sync(session, orderId);
            this.sessions.remove(orderId);
        }
        return cancelled;
    }

    public Optional<SmartCraftOrder> retryFailed(UUID orderId) {
        SmartCraftRuntimeSession session = this.sessions.get(orderId);
        Optional<SmartCraftOrder> retried = this.orderManager.retryFailedTasks(orderId);
        if (retried.isPresent() && session != null) {
            this.orderSync.sync(session, orderId);
        }
        return retried;
    }

    private SmartCraftOrder updateOrder(SmartCraftRuntimeSession session, SmartCraftOrder order) {
        SmartCraftOrder updated = reconcileTaskExecutions(session, order);
        updated = advanceLayers(updated);

        if (updated.status() == SmartCraftStatus.CANCELLED || updated.status() == SmartCraftStatus.FAILED
                || updated.status() == SmartCraftStatus.COMPLETED) {
            return updated;
        }

        updated = dispatchReadyTasks(session, updated);
        return applyLayerStatus(updated);
    }

    private SmartCraftOrder reconcileTaskExecutions(SmartCraftRuntimeSession session, SmartCraftOrder order) {
        SmartCraftOrder updated = order;
        for (int layerIndex = 0; layerIndex < updated.layers().size(); layerIndex++) {
            SmartCraftLayer layer = updated.layers().get(layerIndex);
            List<SmartCraftTask> nextTasks = new ArrayList<SmartCraftTask>(layer.tasks().size());
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
        SmartCraftRuntimeSession.TaskExecution execution = session.executionFor(task);
        if (execution == null) {
            return task;
        }

        ICraftingLink craftingLink = execution.craftingLink();
        if (craftingLink != null) {
            if (craftingLink.isDone()) {
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
            Thread.currentThread().interrupt();
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
            if (advanced.currentLayerIndex() >= advanced.layers().size()) {
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

        List<ICraftingCPU> availableCpus = this.cpuSelector.idleCpus(session.craftingGrid().getCpus());
        List<SmartCraftTask> nextTasks = new ArrayList<SmartCraftTask>(currentLayer.tasks().size());
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
                    ICraftingLink link = this.jobSubmitter.submit(session, task, selectedCpu.get(), execution.plannedJob());
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

            Optional<ICraftingCPU> selectedCpu = takeNextCpu(availableCpus);
            if (!selectedCpu.isPresent()) {
                nextTask = task.withStatus(SmartCraftStatus.WAITING_CPU, NO_IDLE_CPU_REASON);
                changed = changed || nextTask != task;
                nextTasks.add(nextTask);
                continue;
            }

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

        for (SmartCraftTask task : currentLayer.tasks()) {
            if (task.status() == SmartCraftStatus.FAILED) {
                hasFailed = true;
            }
            if (task.status() == SmartCraftStatus.SUBMITTING || task.status() == SmartCraftStatus.RUNNING
                    || task.status() == SmartCraftStatus.VERIFYING_OUTPUT) {
                hasActive = true;
            }
            if (task.status() == SmartCraftStatus.WAITING_CPU) {
                hasWaiting = true;
            }
        }

        if (hasFailed) {
            return order.withStatus(SmartCraftStatus.FAILED);
        }
        if (hasActive) {
            return order.withStatus(SmartCraftStatus.RUNNING);
        }
        if (hasWaiting) {
            return order.withStatus(SmartCraftStatus.WAITING_CPU);
        }
        return order.withStatus(SmartCraftStatus.QUEUED);
    }

    private Optional<ICraftingCPU> takeNextCpu(List<ICraftingCPU> availableCpus) {
        return availableCpus.isEmpty() ? Optional.<ICraftingCPU>empty() : Optional.of(availableCpus.remove(0));
    }
}
