package com.homeftw.ae2intelligentscheduling.network.packet;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Arrays;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

import com.homeftw.ae2intelligentscheduling.smartcraft.model.SmartCraftLayer;
import com.homeftw.ae2intelligentscheduling.smartcraft.model.SmartCraftOrder;
import com.homeftw.ae2intelligentscheduling.smartcraft.model.SmartCraftOrderScale;
import com.homeftw.ae2intelligentscheduling.smartcraft.model.SmartCraftRequestKey;
import com.homeftw.ae2intelligentscheduling.smartcraft.model.SmartCraftStatus;
import com.homeftw.ae2intelligentscheduling.smartcraft.model.SmartCraftTask;

class SmartCraftPacketCodecTest {

    @Test
    void preview_packet_round_trips_order_id_and_action() {
        OpenSmartCraftPreviewPacket packet = new OpenSmartCraftPreviewPacket("preview", "order-123");
        ByteBuf buf = Unpooled.buffer();

        packet.toBytes(buf);

        OpenSmartCraftPreviewPacket decoded = new OpenSmartCraftPreviewPacket();
        decoded.fromBytes(buf);

        assertEquals(packet.getAction(), decoded.getAction());
        assertEquals(packet.getOrderId(), decoded.getOrderId());
    }

    @Test
    void sync_packet_round_trips_layer_and_status() {
        SmartCraftOrder order = new SmartCraftOrder(
            new FakeRequestKey("processor"),
            1024L,
            SmartCraftOrderScale.SMALL,
            SmartCraftStatus.RUNNING,
            Arrays.asList(
                new SmartCraftLayer(0, Arrays.asList(new SmartCraftTask(
                    new FakeRequestKey("processor_part"),
                    512L,
                    0,
                    1,
                    2,
                    SmartCraftStatus.RUNNING,
                    null))),
                new SmartCraftLayer(1, Arrays.asList(new SmartCraftTask(
                    new FakeRequestKey("processor"),
                    1024L,
                    1,
                    1,
                    1,
                    SmartCraftStatus.PENDING,
                    null)))),
            0);
        SyncSmartCraftOrderPacket packet = SyncSmartCraftOrderPacket.from(UUID.fromString("00000000-0000-0000-0000-000000000123"), order);
        ByteBuf buf = Unpooled.buffer();

        packet.toBytes(buf);

        SyncSmartCraftOrderPacket decoded = new SyncSmartCraftOrderPacket();
        decoded.fromBytes(buf);

        assertEquals(packet.getStatus(), decoded.getStatus());
        assertEquals(packet.getCurrentLayer(), decoded.getCurrentLayer());
        assertEquals(packet.getTasks().size(), decoded.getTasks().size());
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
