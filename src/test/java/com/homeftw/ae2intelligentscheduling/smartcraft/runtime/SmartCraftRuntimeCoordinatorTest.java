package com.homeftw.ae2intelligentscheduling.smartcraft.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import com.homeftw.ae2intelligentscheduling.integration.ae2.Ae2CpuSelector;
import com.homeftw.ae2intelligentscheduling.smartcraft.model.SmartCraftLayer;
import com.homeftw.ae2intelligentscheduling.smartcraft.model.SmartCraftOrder;
import com.homeftw.ae2intelligentscheduling.smartcraft.model.SmartCraftOrderScale;
import com.homeftw.ae2intelligentscheduling.smartcraft.model.SmartCraftRequestKey;
import com.homeftw.ae2intelligentscheduling.smartcraft.model.SmartCraftStatus;
import com.homeftw.ae2intelligentscheduling.smartcraft.model.SmartCraftTask;

import appeng.api.networking.crafting.ICraftingCPU;
import appeng.api.networking.crafting.ICraftingGrid;
import appeng.api.networking.crafting.ICraftingJob;
import appeng.api.networking.crafting.ICraftingLink;

class SmartCraftRuntimeCoordinatorTest {

    @Test
    void progresses_task_from_submitting_to_running_to_done() {
        SmartCraftOrderManager orderManager = new SmartCraftOrderManager();
        RecordingSync sync = new RecordingSync();
        MutableLinkState linkState = new MutableLinkState();
        SmartCraftRuntimeCoordinator coordinator = new SmartCraftRuntimeCoordinator(
            orderManager,
            new Ae2CpuSelector(),
            (session, task) -> new CompletedFuture(job(false)),
            (session, task, cpu, job) -> link(linkState),
            sync);
        SmartCraftOrder order = order(task("task-1", SmartCraftStatus.PENDING));
        UUID orderId = orderManager.track(order);
        coordinator.register(orderId, session(cpu("cpu-1", false)));

        coordinator.tick();

        SmartCraftOrder afterFirstTick = orderManager.get(orderId)
            .get();
        assertEquals(
            SmartCraftStatus.SUBMITTING,
            afterFirstTick.currentLayer()
                .tasks()
                .get(0)
                .status());

        coordinator.tick();

        SmartCraftOrder afterSecondTick = orderManager.get(orderId)
            .get();
        assertEquals(
            SmartCraftStatus.RUNNING,
            afterSecondTick.currentLayer()
                .tasks()
                .get(0)
                .status());

        linkState.done = true;
        coordinator.tick();

        SmartCraftOrder afterThirdTick = orderManager.get(orderId)
            .get();
        assertEquals(SmartCraftStatus.COMPLETED, afterThirdTick.status());
        assertEquals(
            SmartCraftStatus.DONE,
            afterThirdTick.layers()
                .get(0)
                .tasks()
                .get(0)
                .status());
        assertEquals(3, sync.syncCount);
    }

    @Test
    void cancel_then_retry_revives_cancelled_tasks_and_resumes_dispatch() {
        // Regression: cancel used to drop the session from the coordinator, leaving retry()
        // unable to advance the order on the next tick. Now sessions are kept for the lifetime
        // of the order in the manager, and retry revives both FAILED and CANCELLED tasks.
        SmartCraftOrderManager orderManager = new SmartCraftOrderManager();
        RecordingSync sync = new RecordingSync();
        MutableLinkState linkState = new MutableLinkState();
        SmartCraftRuntimeCoordinator coordinator = new SmartCraftRuntimeCoordinator(
            orderManager,
            new Ae2CpuSelector(),
            (session, task) -> new CompletedFuture(job(false)),
            (session, task, cpu, job) -> link(linkState),
            sync);
        SmartCraftOrder order = order(task("task-1", SmartCraftStatus.PENDING));
        UUID orderId = orderManager.track(order);
        coordinator.register(orderId, session(cpu("cpu-1", false)));

        // First tick begins planning, second tick submits and reaches RUNNING
        coordinator.tick();
        coordinator.tick();
        assertEquals(
            SmartCraftStatus.RUNNING,
            orderManager.get(orderId)
                .get()
                .currentLayer()
                .tasks()
                .get(0)
                .status());

        // User cancels the order
        coordinator.cancel(orderId);
        SmartCraftOrder cancelled = orderManager.get(orderId)
            .get();
        assertEquals(SmartCraftStatus.CANCELLED, cancelled.status());
        assertEquals(
            SmartCraftStatus.CANCELLED,
            cancelled.currentLayer()
                .tasks()
                .get(0)
                .status());

        // Ticking on a CANCELLED order should NOT remove the session — retry must still work
        coordinator.tick();
        assertEquals(
            SmartCraftStatus.CANCELLED,
            orderManager.get(orderId)
                .get()
                .status());

        // Retry: previously a no-op for CANCELLED tasks, now reset to PENDING and order to QUEUED
        coordinator.retryFailed(orderId);
        SmartCraftOrder retried = orderManager.get(orderId)
            .get();
        assertEquals(SmartCraftStatus.QUEUED, retried.status());
        assertEquals(
            SmartCraftStatus.PENDING,
            retried.currentLayer()
                .tasks()
                .get(0)
                .status());

        // Next tick should pick up the revived task and begin planning
        coordinator.tick();
        assertEquals(
            SmartCraftStatus.SUBMITTING,
            orderManager.get(orderId)
                .get()
                .currentLayer()
                .tasks()
                .get(0)
                .status());
    }

    @Test
    void terminal_tasks_are_not_overwritten_by_canceled_link_state() {
        // Regression: a task that was just CANCELLED by orderManager.cancel(...) used to be flipped
        // back to FAILED on the next tick because the cancelled link reports isCanceled() == true.
        // The reconcile path now early-returns for terminal tasks and clears their execution entry.
        SmartCraftOrderManager orderManager = new SmartCraftOrderManager();
        MutableLinkState linkState = new MutableLinkState();
        SmartCraftRuntimeCoordinator coordinator = new SmartCraftRuntimeCoordinator(
            orderManager,
            new Ae2CpuSelector(),
            (session, task) -> new CompletedFuture(job(false)),
            (session, task, cpu, job) -> link(linkState),
            new RecordingSync());
        SmartCraftOrder order = order(task("task-1", SmartCraftStatus.PENDING));
        UUID orderId = orderManager.track(order);
        coordinator.register(orderId, session(cpu("cpu-1", false)));

        coordinator.tick();
        coordinator.tick();
        coordinator.cancel(orderId);
        // AE2 link reports the cancellation only after the next tick; reconcile must NOT overwrite.
        linkState.canceled = true;
        coordinator.tick();

        SmartCraftOrder afterTick = orderManager.get(orderId)
            .get();
        assertEquals(SmartCraftStatus.CANCELLED, afterTick.status());
        assertEquals(
            SmartCraftStatus.CANCELLED,
            afterTick.currentLayer()
                .tasks()
                .get(0)
                .status());
    }

    @Test
    void planning_starts_for_all_tasks_even_when_only_one_cpu_is_idle() {
        // Regression: dispatchReadyTasks used to consume one CPU per planning task, which serialized
        // planning behind CPU count and produced bogus WAITING_CPU labels for tasks that hadn't even
        // started planning. Planning now runs in parallel; CPU is consumed only at submission.
        SmartCraftOrderManager orderManager = new SmartCraftOrderManager();
        SmartCraftRuntimeCoordinator coordinator = new SmartCraftRuntimeCoordinator(
            orderManager,
            new Ae2CpuSelector(),
            (session, task) -> new CompletedFuture(job(false)),
            (session, task, cpu, job) -> link(new MutableLinkState()),
            new RecordingSync());
        SmartCraftOrder order = order(
            task("task-1", SmartCraftStatus.PENDING),
            task("task-2", SmartCraftStatus.PENDING),
            task("task-3", SmartCraftStatus.PENDING));
        UUID orderId = orderManager.track(order);
        // Only one idle CPU available — old behavior would leave task-2 / task-3 stuck on WAITING_CPU
        coordinator.register(orderId, session(cpu("cpu-1", false)));

        coordinator.tick();

        SmartCraftOrder afterTick = orderManager.get(orderId)
            .get();
        for (int i = 0; i < 3; i++) {
            assertEquals(
                SmartCraftStatus.SUBMITTING,
                afterTick.currentLayer()
                    .tasks()
                    .get(i)
                    .status(),
                "task-" + (i + 1) + " must enter SUBMITTING regardless of idle CPU count");
        }
    }

    @Test
    void waits_for_cpu_after_planning_finishes_without_idle_cpu() {
        SmartCraftOrderManager orderManager = new SmartCraftOrderManager();
        MutableLinkState linkState = new MutableLinkState();
        SmartCraftRuntimeCoordinator coordinator = new SmartCraftRuntimeCoordinator(
            orderManager,
            new Ae2CpuSelector(),
            (session, task) -> new CompletedFuture(job(false)),
            (session, task, cpu, job) -> link(linkState),
            new RecordingSync());
        SmartCraftOrder order = order(task("task-1", SmartCraftStatus.PENDING));
        UUID orderId = orderManager.track(order);
        coordinator.register(orderId, session(cpu("cpu-busy", true)));

        coordinator.tick();
        coordinator.tick();

        SmartCraftOrder updated = orderManager.get(orderId)
            .get();
        assertEquals(SmartCraftStatus.WAITING_CPU, updated.status());
        assertEquals(
            SmartCraftStatus.WAITING_CPU,
            updated.currentLayer()
                .tasks()
                .get(0)
                .status());
    }

    @Test
    void mixed_failed_and_waiting_cpu_must_not_pause_the_order() {
        // Regression: an order with [FAILED, WAITING_CPU] used to flip to PAUSED, which short-
        // circuits updateOrder so the WAITING_CPU sibling could never be dispatched even when a CPU
        // frees up later. Tasks in WAITING_CPU / PENDING / QUEUED must keep the order out of PAUSED
        // because they still represent live work that needs to make progress.
        SmartCraftOrderManager orderManager = new SmartCraftOrderManager();
        MutableLinkState linkState = new MutableLinkState();
        SmartCraftRuntimeCoordinator coordinator = new SmartCraftRuntimeCoordinator(
            orderManager,
            new Ae2CpuSelector(),
            // 'failed' task gets a simulation result → reconcile flips it to FAILED. 'waiting' task
            // gets a real (non-simulation) job → reconcile flips it to WAITING_CPU.
            (session, task) -> new CompletedFuture(job("failed".equals(task.taskKey()))),
            (session, task, cpu, job) -> link(linkState),
            new RecordingSync());
        SmartCraftOrder order = order(
            task("failed", SmartCraftStatus.PENDING),
            task("waiting", SmartCraftStatus.PENDING));
        UUID orderId = orderManager.track(order);
        // CPU is busy so 'waiting' task cannot submit and stays in WAITING_CPU after planning.
        coordinator.register(orderId, session(cpu("cpu-busy", true)));

        // Tick 1: planning begins for both → SUBMITTING.
        coordinator.tick();
        // Tick 2: 'failed' future returns simulation → FAILED; 'waiting' future returns real job →
        // WAITING_CPU (no idle CPU). Order should remain in WAITING_CPU, NOT PAUSED.
        coordinator.tick();

        SmartCraftOrder updated = orderManager.get(orderId)
            .get();
        assertEquals(SmartCraftStatus.WAITING_CPU, updated.status());
        assertEquals(
            SmartCraftStatus.FAILED,
            updated.currentLayer()
                .tasks()
                .get(0)
                .status());
        assertEquals(
            SmartCraftStatus.WAITING_CPU,
            updated.currentLayer()
                .tasks()
                .get(1)
                .status());
    }

    @Test
    void completed_link_advances_to_done_in_one_tick_without_verifying_output_trap() {
        // Regression: previously when craftingLink.isDone() became true the coordinator captured the
        // post-craft stock as a baseline and switched the task to VERIFYING_OUTPUT. Because AE2 routes
        // the crafted output to ME storage in the SAME call stack as completeJob() (which markDone()s
        // the link), the captured baseline already included the just-crafted items. The subsequent
        // isOutputAvailable() check waited for ANOTHER `task.amount()` items to appear above that
        // baseline — which never happens — so the task got stuck in VERIFYING_OUTPUT forever, the
        // current layer never completed, and follow-up layers never got dispatched. This was the
        // user-visible "智能合成卡在第一步不推进" symptom. The fix trusts AE2's terminal link state
        // and transitions directly RUNNING -> DONE.
        SmartCraftOrderManager orderManager = new SmartCraftOrderManager();
        MutableLinkState linkState = new MutableLinkState();
        SmartCraftRuntimeCoordinator coordinator = new SmartCraftRuntimeCoordinator(
            orderManager,
            new Ae2CpuSelector(),
            (session, task) -> new CompletedFuture(job(false)),
            (session, task, cpu, job) -> link(linkState),
            new RecordingSync());
        SmartCraftOrder order = order(task("layer-0-task", SmartCraftStatus.PENDING));
        UUID orderId = orderManager.track(order);
        coordinator.register(orderId, session(cpu("cpu-1", false)));

        // Tick 1 plans, tick 2 submits → RUNNING.
        coordinator.tick();
        coordinator.tick();
        assertEquals(
            SmartCraftStatus.RUNNING,
            orderManager.get(orderId)
                .get()
                .currentLayer()
                .tasks()
                .get(0)
                .status());

        // AE2 finishes crafting and marks the link done.
        linkState.done = true;

        // Tick 3 must transition straight to DONE (and the order to COMPLETED). The previous bug
        // would leave the task in VERIFYING_OUTPUT here.
        coordinator.tick();

        SmartCraftOrder finished = orderManager.get(orderId)
            .get();
        assertEquals(
            SmartCraftStatus.DONE,
            finished.layers()
                .get(0)
                .tasks()
                .get(0)
                .status(),
            "task must reach DONE on the same tick the link reports isDone()");
        assertEquals(
            SmartCraftStatus.COMPLETED,
            finished.status(),
            "single-task single-layer order must be COMPLETED once its only task is DONE");
    }

    private static SmartCraftOrder order(SmartCraftTask... tasks) {
        return new SmartCraftOrder(
            new FakeRequestKey("target"),
            1L,
            SmartCraftOrderScale.SMALL,
            SmartCraftStatus.QUEUED,
            Arrays.asList(new SmartCraftLayer(0, Arrays.asList(tasks))),
            0);
    }

    private static SmartCraftTask task(String taskId, SmartCraftStatus status) {
        return new SmartCraftTask(taskId, new FakeRequestKey("processor"), 64L, 0, 1, 1, status, null);
    }

    private static SmartCraftRuntimeSession session(ICraftingCPU cpu) {
        return new SmartCraftRuntimeSession(
            null,
            null,
            null,
            null,
            null,
            craftingGrid(Collections.singletonList(cpu)),
            new SmartCraftRequesterBridge(null));
    }

    private static ICraftingGrid craftingGrid(final Iterable<ICraftingCPU> cpus) {
        InvocationHandler handler = new InvocationHandler() {

            @Override
            public Object invoke(Object proxy, Method method, Object[] args) {
                if ("getCpus".equals(method.getName())) {
                    return com.google.common.collect.ImmutableSet.copyOf(cpus);
                }
                return defaultValue(method.getReturnType());
            }
        };

        return (ICraftingGrid) Proxy.newProxyInstance(
            SmartCraftRuntimeCoordinatorTest.class.getClassLoader(),
            new Class<?>[] { ICraftingGrid.class },
            handler);
    }

    private static ICraftingCPU cpu(final String name, final boolean busy) {
        InvocationHandler handler = new InvocationHandler() {

            @Override
            public Object invoke(Object proxy, Method method, Object[] args) {
                if ("isBusy".equals(method.getName())) {
                    return busy;
                }
                if ("getName".equals(method.getName())) {
                    return name;
                }
                return defaultValue(method.getReturnType());
            }
        };

        return (ICraftingCPU) Proxy.newProxyInstance(
            SmartCraftRuntimeCoordinatorTest.class.getClassLoader(),
            new Class<?>[] { ICraftingCPU.class },
            handler);
    }

    private static ICraftingJob job(final boolean simulation) {
        InvocationHandler handler = new InvocationHandler() {

            @Override
            public Object invoke(Object proxy, Method method, Object[] args) {
                if ("isSimulation".equals(method.getName())) {
                    return simulation;
                }
                return defaultValue(method.getReturnType());
            }
        };

        return (ICraftingJob) Proxy.newProxyInstance(
            SmartCraftRuntimeCoordinatorTest.class.getClassLoader(),
            new Class<?>[] { ICraftingJob.class },
            handler);
    }

    private static ICraftingLink link(final MutableLinkState state) {
        InvocationHandler handler = new InvocationHandler() {

            @Override
            public Object invoke(Object proxy, Method method, Object[] args) {
                if ("isDone".equals(method.getName())) {
                    return state.done;
                }
                if ("isCanceled".equals(method.getName())) {
                    return state.canceled;
                }
                return defaultValue(method.getReturnType());
            }
        };

        return (ICraftingLink) Proxy.newProxyInstance(
            SmartCraftRuntimeCoordinatorTest.class.getClassLoader(),
            new Class<?>[] { ICraftingLink.class },
            handler);
    }

    private static Object defaultValue(Class<?> returnType) {
        if (returnType == boolean.class) {
            return false;
        }
        if (returnType == int.class) {
            return 0;
        }
        if (returnType == long.class) {
            return 0L;
        }
        if (returnType == float.class) {
            return 0.0F;
        }
        if (returnType == double.class) {
            return 0.0D;
        }
        return null;
    }

    private static final class CompletedFuture implements Future<ICraftingJob> {

        private final ICraftingJob job;

        private CompletedFuture(ICraftingJob job) {
            this.job = job;
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            return false;
        }

        @Override
        public boolean isCancelled() {
            return false;
        }

        @Override
        public boolean isDone() {
            return true;
        }

        @Override
        public ICraftingJob get() throws InterruptedException, ExecutionException {
            return this.job;
        }

        @Override
        public ICraftingJob get(long timeout, TimeUnit unit) throws InterruptedException, ExecutionException {
            return this.job;
        }
    }

    private static final class MutableLinkState {

        private boolean done;
        private boolean canceled;
    }

    private static final class RecordingSync implements SmartCraftRuntimeCoordinator.OrderSync {

        private int syncCount;

        @Override
        public void sync(SmartCraftRuntimeSession session, UUID orderId) {
            this.syncCount++;
        }
    }

    private static final class FakeRequestKey implements SmartCraftRequestKey {

        private final String id;

        private FakeRequestKey(String id) {
            this.id = id;
        }

        @Override
        public String id() {
            return this.id;
        }

        @Override
        public net.minecraft.item.ItemStack itemStack() {
            return null;
        }
    }
}
