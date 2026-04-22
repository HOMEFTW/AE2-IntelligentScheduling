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

import appeng.api.networking.crafting.ICraftingCPU;
import appeng.api.networking.crafting.ICraftingGrid;
import appeng.api.networking.crafting.ICraftingJob;
import appeng.api.networking.crafting.ICraftingLink;

import com.homeftw.ae2intelligentscheduling.integration.ae2.Ae2CpuSelector;
import com.homeftw.ae2intelligentscheduling.smartcraft.model.SmartCraftLayer;
import com.homeftw.ae2intelligentscheduling.smartcraft.model.SmartCraftOrder;
import com.homeftw.ae2intelligentscheduling.smartcraft.model.SmartCraftOrderScale;
import com.homeftw.ae2intelligentscheduling.smartcraft.model.SmartCraftRequestKey;
import com.homeftw.ae2intelligentscheduling.smartcraft.model.SmartCraftStatus;
import com.homeftw.ae2intelligentscheduling.smartcraft.model.SmartCraftTask;

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

        SmartCraftOrder afterFirstTick = orderManager.get(orderId).get();
        assertEquals(SmartCraftStatus.SUBMITTING, afterFirstTick.currentLayer().tasks().get(0).status());

        coordinator.tick();

        SmartCraftOrder afterSecondTick = orderManager.get(orderId).get();
        assertEquals(SmartCraftStatus.RUNNING, afterSecondTick.currentLayer().tasks().get(0).status());

        linkState.done = true;
        coordinator.tick();

        SmartCraftOrder afterThirdTick = orderManager.get(orderId).get();
        assertEquals(SmartCraftStatus.COMPLETED, afterThirdTick.status());
        assertEquals(SmartCraftStatus.DONE, afterThirdTick.layers().get(0).tasks().get(0).status());
        assertEquals(3, sync.syncCount);
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

        SmartCraftOrder updated = orderManager.get(orderId).get();
        assertEquals(SmartCraftStatus.WAITING_CPU, updated.status());
        assertEquals(SmartCraftStatus.WAITING_CPU, updated.currentLayer().tasks().get(0).status());
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
    }
}
