package com.homeftw.ae2intelligentscheduling.network.packet;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

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
}
