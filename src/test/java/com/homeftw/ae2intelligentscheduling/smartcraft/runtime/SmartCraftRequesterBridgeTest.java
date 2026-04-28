package com.homeftw.ae2intelligentscheduling.smartcraft.runtime;

import static org.junit.jupiter.api.Assertions.assertSame;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

import org.junit.jupiter.api.Test;

import appeng.api.config.Actionable;
import appeng.api.networking.crafting.ICraftingLink;
import appeng.api.storage.data.IAEItemStack;

class SmartCraftRequesterBridgeTest {

    @Test
    void injectCraftedItems_returns_full_stack_so_ae2_routes_output_back_to_me_storage() {
        // Regression: previously the bridge returned null which told AE2 the requester accepted
        // every crafted item. Since the bridge has no real inventory the items silently vanished
        // (visible to the player as "task submitted, link active, but stockVerifier never sees the
        // product → stuck in VERIFYING_OUTPUT forever"). Returning the full incoming stack means
        // AE2 puts the items back into ME storage via Platform.poweredInsert(...).
        SmartCraftRequesterBridge bridge = new SmartCraftRequesterBridge(null);

        IAEItemStack input = stack();
        IAEItemStack returned = bridge.injectCraftedItems(link(), input, Actionable.MODULATE);

        assertSame(input, returned, "Bridge must refuse all items so AE2 routes them back to ME storage");
    }

    private static IAEItemStack stack() {
        return (IAEItemStack) Proxy.newProxyInstance(
            SmartCraftRequesterBridgeTest.class.getClassLoader(),
            new Class<?>[] { IAEItemStack.class },
            new InvocationHandler() {

                @Override
                public Object invoke(Object proxy, Method method, Object[] args) {
                    return null;
                }
            });
    }

    private static ICraftingLink link() {
        return (ICraftingLink) Proxy.newProxyInstance(
            SmartCraftRequesterBridgeTest.class.getClassLoader(),
            new Class<?>[] { ICraftingLink.class },
            new InvocationHandler() {

                @Override
                public Object invoke(Object proxy, Method method, Object[] args) {
                    return null;
                }
            });
    }
}
