package com.homeftw.ae2intelligentscheduling.smartcraft.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import appeng.api.networking.crafting.ICraftingCPU;

import com.homeftw.ae2intelligentscheduling.integration.ae2.Ae2CpuSelector;
import com.homeftw.ae2intelligentscheduling.smartcraft.model.SmartCraftLayer;
import com.homeftw.ae2intelligentscheduling.smartcraft.model.SmartCraftOrder;
import com.homeftw.ae2intelligentscheduling.smartcraft.model.SmartCraftStatus;
import com.homeftw.ae2intelligentscheduling.smartcraft.model.SmartCraftTask;

public final class SmartCraftScheduler {

    public interface SubmissionHandler {

        boolean submit(SmartCraftTask task, ICraftingCPU cpu);
    }

    private static final String NO_IDLE_CPU_REASON = "No idle AE2 crafting CPU available";
    private static final String SUBMIT_FAILED_REASON = "Failed to submit AE2 crafting job";

    private final Ae2CpuSelector cpuSelector;
    private final SubmissionHandler submissionHandler;

    public SmartCraftScheduler(Ae2CpuSelector cpuSelector, SubmissionHandler submissionHandler) {
        this.cpuSelector = cpuSelector;
        this.submissionHandler = submissionHandler;
    }

    public SmartCraftOrder tick(SmartCraftOrder order, Iterable<ICraftingCPU> craftingCpus) {
        if (order.isFinished()) {
            return order;
        }

        SmartCraftLayer currentLayer = order.currentLayer();
        if (currentLayer == null) {
            return order.withStatus(SmartCraftStatus.COMPLETED);
        }

        if (currentLayer.isComplete()) {
            SmartCraftOrder advanced = order.advanceLayer();
            if (advanced.currentLayerIndex() >= advanced.layers().size()) {
                return advanced.withStatus(SmartCraftStatus.COMPLETED);
            }
            return advanced.withStatus(SmartCraftStatus.QUEUED);
        }

        List<ICraftingCPU> availableCpus = this.cpuSelector.idleCpus(craftingCpus);
        List<SmartCraftTask> updatedTasks = new ArrayList<SmartCraftTask>(currentLayer.tasks().size());

        for (SmartCraftTask task : currentLayer.tasks()) {
            if (task.isTerminal() || task.isActive()) {
                updatedTasks.add(task);
                continue;
            }

            if (!task.isReadyForSubmission()) {
                updatedTasks.add(task);
                continue;
            }

            Optional<ICraftingCPU> selectedCpu = takeNextCpu(availableCpus);
            if (!selectedCpu.isPresent()) {
                updatedTasks.add(task.withStatus(SmartCraftStatus.WAITING_CPU, NO_IDLE_CPU_REASON));
                continue;
            }

            boolean submitted = this.submissionHandler.submit(task, selectedCpu.get());
            if (submitted) {
                updatedTasks.add(task.withStatus(SmartCraftStatus.RUNNING, null));
            } else {
                updatedTasks.add(task.withStatus(SmartCraftStatus.FAILED, SUBMIT_FAILED_REASON));
            }
        }

        SmartCraftOrder updated = order.withLayer(order.currentLayerIndex(), currentLayer.withTasks(updatedTasks));
        return updated.withStatus(determineOrderStatus(updated.currentLayer()));
    }

    private Optional<ICraftingCPU> takeNextCpu(List<ICraftingCPU> availableCpus) {
        return availableCpus.isEmpty() ? Optional.<ICraftingCPU>empty() : Optional.of(availableCpus.remove(0));
    }

    private SmartCraftStatus determineOrderStatus(SmartCraftLayer currentLayer) {
        boolean hasRunning = false;
        boolean hasWaiting = false;

        for (SmartCraftTask task : currentLayer.tasks()) {
            if (task.status() == SmartCraftStatus.RUNNING || task.status() == SmartCraftStatus.SUBMITTING
                    || task.status() == SmartCraftStatus.VERIFYING_OUTPUT) {
                hasRunning = true;
            }
            if (task.status() == SmartCraftStatus.WAITING_CPU) {
                hasWaiting = true;
            }
        }

        if (hasRunning) {
            return SmartCraftStatus.RUNNING;
        }
        if (hasWaiting) {
            return SmartCraftStatus.WAITING_CPU;
        }
        return SmartCraftStatus.QUEUED;
    }
}
