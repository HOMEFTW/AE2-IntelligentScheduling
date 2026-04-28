package com.homeftw.ae2intelligentscheduling.client.gui;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

import net.minecraft.client.gui.inventory.GuiContainer;

import com.homeftw.ae2intelligentscheduling.AE2IntelligentScheduling;

public final class NeiGuiContainerManagerGuard {

    private static boolean warned;

    private NeiGuiContainerManagerGuard() {}

    public static void ensureManager(GuiContainer container) {
        if (container == null) return;

        try {
            ensureManager(container, NeiGuiContainerManagerGuard::createNeiManager);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            warnOnce(e);
        }
    }

    static boolean ensureManager(Object target, ManagerFactory factory) throws ReflectiveOperationException {
        Field manager = findManagerField(target.getClass());
        if (manager == null) return false;

        manager.setAccessible(true);
        if (manager.get(target) != null) return false;

        Class<?> managerType = manager.getType();
        Object replacement = factory.create(target, managerType);
        if (replacement == null) return false;
        if (!managerType.isInstance(replacement)) {
            throw new IllegalStateException(
                "Factory returned " + replacement.getClass()
                    .getName() + " for " + managerType.getName());
        }

        manager.set(target, replacement);
        invokeLoad(replacement);
        return true;
    }

    private static Object createNeiManager(Object target, Class<?> managerType) throws ReflectiveOperationException {
        Constructor<?> constructor = managerType.getConstructor(GuiContainer.class);
        return constructor.newInstance(target);
    }

    private static Field findManagerField(Class<?> type) {
        Class<?> cursor = type;
        while (cursor != null) {
            try {
                return cursor.getDeclaredField("manager");
            } catch (NoSuchFieldException ignored) {
                cursor = cursor.getSuperclass();
            }
        }
        return null;
    }

    private static void invokeLoad(Object manager) throws ReflectiveOperationException {
        Method load = manager.getClass()
            .getMethod("load");
        load.invoke(manager);
    }

    private static void warnOnce(Throwable error) {
        if (warned) return;
        warned = true;
        AE2IntelligentScheduling.LOG.warn("Unable to repair missing NEI GuiContainerManager", error);
    }

    @FunctionalInterface
    interface ManagerFactory {

        Object create(Object target, Class<?> managerType) throws ReflectiveOperationException;
    }
}
