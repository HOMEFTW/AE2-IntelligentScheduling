package com.homeftw.ae2intelligentscheduling.network.packet;

import java.util.Optional;
import java.util.UUID;

import net.minecraft.entity.player.EntityPlayerMP;

import com.homeftw.ae2intelligentscheduling.AE2IntelligentScheduling;
import com.homeftw.ae2intelligentscheduling.smartcraft.model.SmartCraftOrder;

import cpw.mods.fml.common.network.ByteBufUtils;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

public final class RequestSmartCraftActionPacket implements IMessage {

    public enum Action {
        CANCEL_ORDER,
        /**
         * Soft cancel: leave already-RUNNING tasks alone, cancel only PENDING / SUBMITTING /
         * WAITING_CPU. The order keeps ticking until the spared tasks naturally finish, at which
         * point applyLayerStatus flips it to COMPLETED or PAUSED. Spares the intermediate
         * materials AE2 has already invested in mid-flight crafts.
         */
        CANCEL_ORDER_SOFT,
        RETRY_FAILED,
        REFRESH_ORDER
    }

    private String orderId;
    private String action;

    public RequestSmartCraftActionPacket() {
        this("", Action.CANCEL_ORDER);
    }

    public RequestSmartCraftActionPacket(UUID orderId, Action action) {
        this(orderId.toString(), action);
    }

    public RequestSmartCraftActionPacket(String orderId, Action action) {
        this.orderId = orderId;
        this.action = action.name();
    }

    public UUID getOrderId() {
        return UUID.fromString(this.orderId);
    }

    public Action getAction() {
        return Action.valueOf(this.action);
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        this.orderId = ByteBufUtils.readUTF8String(buf);
        this.action = ByteBufUtils.readUTF8String(buf);
    }

    @Override
    public void toBytes(ByteBuf buf) {
        ByteBufUtils.writeUTF8String(buf, this.orderId);
        ByteBufUtils.writeUTF8String(buf, this.action);
    }

    public static final class Handler implements IMessageHandler<RequestSmartCraftActionPacket, IMessage> {

        @Override
        public IMessage onMessage(RequestSmartCraftActionPacket message, MessageContext ctx) {
            EntityPlayerMP player = ctx.getServerHandler().playerEntity;
            // (v0.1.9 G12) After a server restart the in-memory session map is empty even though
            // the OrderManager has persisted orders. Best-effort rebuild before dispatching the
            // action so retry / cancel / refresh actually drive the runtime forward instead of
            // silently no-oping. Owner-gate inside attemptRebindSession ensures we never bind a
            // different player to someone else's order. No-op when session is already present.
            try {
                AE2IntelligentScheduling.SMART_CRAFT_RUNTIME.attemptRebindSession(
                    message.getOrderId(),
                    player,
                    AE2IntelligentScheduling.SMART_CRAFT_SESSION_FACTORY);
            } catch (IllegalArgumentException e) {
                // malformed orderId; downstream handlers will return Optional.empty() naturally.
            }
            Optional<SmartCraftOrder> updated;

            switch (message.getAction()) {
                case CANCEL_ORDER:
                    updated = AE2IntelligentScheduling.SMART_CRAFT_RUNTIME.cancel(message.getOrderId());
                    break;
                case CANCEL_ORDER_SOFT:
                    updated = AE2IntelligentScheduling.SMART_CRAFT_RUNTIME.cancelGracefully(message.getOrderId());
                    break;
                case RETRY_FAILED:
                    updated = AE2IntelligentScheduling.SMART_CRAFT_RUNTIME.retryFailed(message.getOrderId());
                    break;
                case REFRESH_ORDER:
                    updated = AE2IntelligentScheduling.SMART_CRAFT_ORDER_MANAGER.get(message.getOrderId());
                    break;
                default:
                    updated = Optional.empty();
                    break;
            }

            if (updated.isPresent()) {
                // v0.1.7: push the entire list so the acting player's tab bar updates atomically
                // (the affected order's status flips, and if it just terminated the next refresh
                // tick will see it gone from the manager). The single-order sync used to be enough
                // pre-v0.1.7 because the client only ever knew about one order at a time.
                AE2IntelligentScheduling.SMART_CRAFT_ORDER_SYNC.syncListTo(player);
            }
            return null;
        }
    }
}
