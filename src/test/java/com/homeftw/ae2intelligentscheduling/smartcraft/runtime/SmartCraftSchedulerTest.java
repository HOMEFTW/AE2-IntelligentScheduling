package com.homeftw.ae2intelligentscheduling.smartcraft.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.Collections;

import org.junit.jupiter.api.Test;

import appeng.api.networking.crafting.ICraftingCPU;

import com.homeftw.ae2intelligentscheduling.integration.ae2.Ae2CpuSelector;
import com.homeftw.ae2intelligentscheduling.smartcraft.model.SmartCraftLayer;
import com.homeftw.ae2intelligentscheduling.smartcraft.model.SmartCraftOrder;
import com.homeftw.ae2intelligentscheduling.smartcraft.model.SmartCraftOrderScale;
import com.homeftw.ae2intelligentscheduling.smartcraft.model.SmartCraftRequestKey;
import com.homeftw.ae2intelligentscheduling.smartcraft.model.SmartCraftStatus;
import com.homeftw.ae2intelligentscheduling.smartcraft.model.SmartCraftTask;

class SmartCraftSchedulerTest {

    @Test
    void waits_when_no_idle_cpu_is_available() {
        SmartCraftOrder order = order(
            layer(task("processor", 1_000_000_000L, 0, 1, 1, SmartCraftStatus.PENDING)));
        SmartCraftScheduler scheduler = new SmartCraftScheduler(new Ae2CpuSelector(), (task, cpu) -> true);

        SmartCraftOrder updated = scheduler.tick(order, Collections.singletonList(cpu("busy", true)));

        assertEquals(SmartCraftStatus.WAITING_CPU, updated.status());
        assertEquals(SmartCraftStatus.WAITING_CPU, updated.currentLayer().tasks().get(0).status());
    }

    @Test
    void does_not_start_parent_layer_before_children_finish() {
        SmartCraftOrder order = order(
            layer(task("child", 64L, 0, 1, 1, SmartCraftStatus.RUNNING)),
            layer(task("parent", 1L, 1, 1, 1, SmartCraftStatus.PENDING)));
        SmartCraftScheduler scheduler = new SmartCraftScheduler(new Ae2CpuSelector(), (task, cpu) -> true);

        SmartCraftOrder updated = scheduler.tick(order, Collections.singletonList(cpu("idle", false)));

        assertEquals(0, updated.currentLayerIndex());
        assertEquals(SmartCraftStatus.PENDING, updated.layers().get(1).tasks().get(0).status());
    }

    @Test
    void advances_to_next_layer_after_current_layer_completes() {
        SmartCraftOrder order = order(
            layer(task("child", 64L, 0, 1, 1, SmartCraftStatus.DONE)),
            layer(task("parent", 1L, 1, 1, 1, SmartCraftStatus.PENDING)));
        SmartCraftScheduler scheduler = new SmartCraftScheduler(new Ae2CpuSelector(), (task, cpu) -> true);

        SmartCraftOrder updated = scheduler.tick(order, Arrays.asList(cpu("idle", false)));

        assertEquals(1, updated.currentLayerIndex());
        assertEquals(SmartCraftStatus.QUEUED, updated.status());
        assertEquals(SmartCraftStatus.PENDING, updated.currentLayer().tasks().get(0).status());
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
