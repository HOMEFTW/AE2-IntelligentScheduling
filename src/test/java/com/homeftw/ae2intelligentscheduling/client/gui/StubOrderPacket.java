package com.homeftw.ae2intelligentscheduling.client.gui;

import java.lang.reflect.Field;

import com.homeftw.ae2intelligentscheduling.network.packet.SyncSmartCraftOrderPacket;

/**
 * Test helper: build a {@link SyncSmartCraftOrderPacket} with a specific orderId for
 * widget-level tests that only care about identity. Production paths build packets via
 * {@code SyncSmartCraftOrderPacket.from(UUID, SmartCraftOrder, session)} which requires a real
 * {@link com.homeftw.ae2intelligentscheduling.smartcraft.model.SmartCraftOrder}; that's heavy for
 * pure click-math tests, so we set the orderId field directly via reflection.
 */
final class StubOrderPacket {

    private StubOrderPacket() {}

    static SyncSmartCraftOrderPacket withId(String id) {
        try {
            SyncSmartCraftOrderPacket packet = new SyncSmartCraftOrderPacket();
            Field f = SyncSmartCraftOrderPacket.class.getDeclaredField("orderId");
            f.setAccessible(true);
            f.set(packet, id);
            // ownerName initialised to "" by the no-arg ctor; leaving it alone is fine for the
            // hit-test math which doesn't read it.
            return packet;
        } catch (NoSuchFieldException | IllegalAccessException e) {
            throw new RuntimeException("StubOrderPacket reflection failed — has the packet field renamed?", e);
        }
    }
}
