package com.homeftw.ae2intelligentscheduling.smartcraft.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.Collections;
import java.util.Optional;
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

class SmartCraftSchedulerTest {

    @Test
    void waits_when_no_idle_cpu_is_available() {
        // Updated contract: planning runs unconditionally; CPU is consumed only at submission. So
        // the task goes SUBMITTING on tick 1 (planning starts) and WAITING_CPU on tick 2 (planning
        // is done but submission has no idle CPU to bind to).
        SmartCraftOrderManager orderManager = new SmartCraftOrderManager();
        SmartCraftOrder order = order(layer(task("processor", 1_000_000_000L, 0, 1, 1, SmartCraftStatus.PENDING)));
        UUID orderId = orderManager.track(order);
        SmartCraftRuntimeCoordinator coordinator = coordinator(orderManager);
        coordinator.register(orderId, session(cpu("busy", true)));

        coordinator.tick();
        coordinator.tick();

        SmartCraftOrder updated = trackedOrder(orderManager, orderId);
        assertEquals(SmartCraftStatus.WAITING_CPU, updated.status());
        assertEquals(
            SmartCraftStatus.WAITING_CPU,
            updated.currentLayer()
                .tasks()
                .get(0)
                .status());
    }

    @Test
    void does_not_start_parent_layer_before_children_finish() {
        SmartCraftOrderManager orderManager = new SmartCraftOrderManager();
        SmartCraftOrder order = order(
            layer(task("child", 64L, 0, 1, 1, SmartCraftStatus.RUNNING)),
            layer(task("parent", 1L, 1, 1, 1, SmartCraftStatus.PENDING)));
        UUID orderId = orderManager.track(order);
        SmartCraftRuntimeCoordinator coordinator = coordinator(orderManager);
        coordinator.register(orderId, session(cpu("idle", false)));

        coordinator.tick();

        SmartCraftOrder updated = trackedOrder(orderManager, orderId);
        assertEquals(0, updated.currentLayerIndex());
        assertEquals(
            SmartCraftStatus.PENDING,
            updated.layers()
                .get(1)
                .tasks()
                .get(0)
                .status());
    }

    @Test
    void advances_to_next_layer_after_current_layer_completes() {
        SmartCraftOrderManager orderManager = new SmartCraftOrderManager();
        SmartCraftOrder order = order(
            layer(task("child", 64L, 0, 1, 1, SmartCraftStatus.DONE)),
            layer(task("parent", 1L, 1, 1, 1, SmartCraftStatus.PENDING)));
        UUID orderId = orderManager.track(order);
        SmartCraftRuntimeCoordinator coordinator = coordinator(orderManager);
        coordinator.register(orderId, session(cpu("idle", false)));

        coordinator.tick();

        SmartCraftOrder updated = trackedOrder(orderManager, orderId);
        assertEquals(1, updated.currentLayerIndex());
        assertEquals(SmartCraftStatus.RUNNING, updated.status());
        assertEquals(
            SmartCraftStatus.SUBMITTING,
            updated.currentLayer()
                .tasks()
                .get(0)
                .status());
    }

    private static SmartCraftRuntimeCoordinator coordinator(SmartCraftOrderManager orderManager) {
        return new SmartCraftRuntimeCoordinator(
            orderManager,
            new Ae2CpuSelector(),
            (session, task) -> new CompletedFuture(job(false)),
            (session, task, cpu, job) -> link(),
            (session, orderId) -> {});
    }

    private static SmartCraftOrder trackedOrder(SmartCraftOrderManager orderManager, UUID orderId) {
        Optional<SmartCraftOrder> order = orderManager.get(orderId);
        return order.orElseThrow(IllegalStateException::new);
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
            SmartCraftSchedulerTest.class.getClassLoader(),
            new Class<?>[] { ICraftingGrid.class },
            handler);
    }

    private static SmartCraftOrder order(SmartCraftLayer... layers) {
        return new SmartCraftOrder(
            new FakeRequestKey("target"),
            1L,
            SmartCraftOrderScale.SMALL,
            SmartCraftStatus.QUEUED,
            Arrays.asList(layers),
            0);
    }

    private static SmartCraftLayer layer(SmartCraftTask... tasks) {
        int depth = tasks.length == 0 ? 0 : tasks[0].depth();
        return new SmartCraftLayer(depth, Arrays.asList(tasks));
    }

    private static SmartCraftTask task(String id, long amount, int depth, int splitIndex, int splitCount,
        SmartCraftStatus status) {
        return new SmartCraftTask(new FakeRequestKey(id), amount, depth, splitIndex, splitCount, status, null);
    }

    private static ICraftingCPU cpu(final String name, final boolean busy) {
        InvocationHandler handler = new InvocationHandler() {

            @Override
            public Object invoke(Object proxy, Method method, Object[] args) {
                String methodName = method.getName();
                if ("isBusy".equals(methodName)) {
                    return busy;
                }
                if ("getName".equals(methodName)) {
                    return name;
                }
                return defaultValue(method.getReturnType());
            }
        };

        return (ICraftingCPU) Proxy.newProxyInstance(
            SmartCraftSchedulerTest.class.getClassLoader(),
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
            SmartCraftSchedulerTest.class.getClassLoader(),
            new Class<?>[] { ICraftingJob.class },
            handler);
    }

    private static ICraftingLink link() {
        InvocationHandler handler = new InvocationHandler() {

            @Override
            public Object invoke(Object proxy, Method method, Object[] args) {
                return defaultValue(method.getReturnType());
            }
        };

        return (ICraftingLink) Proxy.newProxyInstance(
            SmartCraftSchedulerTest.class.getClassLoader(),
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
