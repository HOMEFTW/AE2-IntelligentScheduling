package com.homeftw.ae2intelligentscheduling.integration.ae2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import appeng.api.networking.crafting.ICraftingCPU;

class Ae2CpuSelectorTest {

    @Test
    void returns_first_idle_cpu_when_available() {
        Optional<ICraftingCPU> selected = new Ae2CpuSelector().findIdleCpu(
            Arrays.asList(cpu("busy", true), cpu("idle-1", false), cpu("idle-2", false)));

        assertTrue(selected.isPresent());
        assertEquals("idle-1", selected.get().getName());
    }

    @Test
    void returns_empty_when_all_cpus_are_busy() {
        Optional<ICraftingCPU> selected = new Ae2CpuSelector().findIdleCpu(
            Arrays.asList(cpu("busy-1", true), cpu("busy-2", true)));

        assertFalse(selected.isPresent());
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
            Ae2CpuSelectorTest.class.getClassLoader(),
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
}
