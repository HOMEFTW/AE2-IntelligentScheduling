package com.homeftw.ae2intelligentscheduling.network.packet;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.homeftw.ae2intelligentscheduling.AE2IntelligentScheduling;
import com.homeftw.ae2intelligentscheduling.smartcraft.model.SmartCraftOrder;
import com.homeftw.ae2intelligentscheduling.smartcraft.runtime.SmartCraftRuntimeSession;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

/**
 * v0.1.7 multi-order tab UI packet. Carries every active order (full data) in insertion order so
 * the client tab bar can render every player's tabs without a separate metadata-then-detail round
 * trip. Each entry is a fully-formed {@link SyncSmartCraftOrderPacket} so the list packet is
 * literally a length-prefixed sequence of the existing single-order payload — no schema drift.
 *
 * <p>
 * Server pushes this packet in three situations:
 *
 * <ul>
 * <li>Client refresh poll ({@link RequestOrderStatusPacket}) — once per second from
 * {@code GuiSmartCraftStatus.updateScreen}.</li>
 * <li>Action follow-up ({@link RequestSmartCraftActionPacket}) — after cancel/retry/refresh so the
 * acting player sees the state change before the next refresh tick.</li>
 * <li>Initial open ({@link OpenSmartCraftPreviewPacket}) — supplements the single-order packet so
 * the GUI opens with every other player's tabs already populated.</li>
 * </ul>
 *
 * <p>
 * Size budget: each entry is ~50 bytes overhead + ~80 bytes per task. With 5 active orders × 10
 * tasks each, ~5 KB per round trip; even 30 active orders × 30 tasks each ≈ 75 KB which is below
 * the 1 MB Forge SimpleImpl payload cap. If we ever blow that, the fallback is to switch to the
 * detail-on-demand variant discussed in the v0.1.7 design plan.
 */
public final class SyncSmartCraftOrderListPacket implements IMessage {

    private List<SyncSmartCraftOrderPacket> orders;

    public SyncSmartCraftOrderListPacket() {
        this.orders = new ArrayList<SyncSmartCraftOrderPacket>();
    }

    private SyncSmartCraftOrderListPacket(List<SyncSmartCraftOrderPacket> orders) {
        this.orders = new ArrayList<SyncSmartCraftOrderPacket>(orders);
    }

    /**
     * Build a list packet from a snapshot of the order manager + a uuid→session lookup. The
     * caller (sync service) owns iterating the orderManager.snapshot() and the sessions map; this
     * factory only does the per-entry packet construction so it stays unit-testable without a
     * runtime coordinator. Orders without a session (theoretically transient mid-lifecycle states)
     * still get an entry — ownerName falls back to empty string.
     */
    public static SyncSmartCraftOrderListPacket from(LinkedHashMap<UUID, SmartCraftOrder> orderSnapshot,
        Map<UUID, SmartCraftRuntimeSession> sessions) {
        List<SyncSmartCraftOrderPacket> entries = new ArrayList<SyncSmartCraftOrderPacket>(orderSnapshot.size());
        for (Map.Entry<UUID, SmartCraftOrder> entry : orderSnapshot.entrySet()) {
            UUID orderId = entry.getKey();
            SmartCraftOrder order = entry.getValue();
            SmartCraftRuntimeSession session = sessions == null ? null : sessions.get(orderId);
            entries.add(SyncSmartCraftOrderPacket.from(orderId, order, session));
        }
        return new SyncSmartCraftOrderListPacket(entries);
    }

    public List<SyncSmartCraftOrderPacket> getOrders() {
        return Collections.unmodifiableList(this.orders);
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        int count = buf.readInt();
        this.orders = new ArrayList<SyncSmartCraftOrderPacket>(count);
        for (int i = 0; i < count; i++) {
            SyncSmartCraftOrderPacket entry = new SyncSmartCraftOrderPacket();
            entry.fromBytes(buf);
            this.orders.add(entry);
        }
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(this.orders.size());
        for (SyncSmartCraftOrderPacket entry : this.orders) {
            entry.toBytes(buf);
        }
    }

    public static final class Handler implements IMessageHandler<SyncSmartCraftOrderListPacket, IMessage> {

        @Override
        public IMessage onMessage(SyncSmartCraftOrderListPacket message, MessageContext ctx) {
            // Delegated to the proxy so the headless server doesn't try to touch the client-side
            // overlay state. ClientProxy reconciles the orders map and refreshes the active tab.
            AE2IntelligentScheduling.proxy.applySmartCraftOrderList(message);
            return null;
        }
    }
}
