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
            Optional<SmartCraftOrder> updated;

            switch (message.getAction()) {
                case CANCEL_ORDER:
                    updated = AE2IntelligentScheduling.SMART_CRAFT_RUNTIME.cancel(message.getOrderId());
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
                AE2IntelligentScheduling.SMART_CRAFT_ORDER_SYNC.sync(
                    player,
                    message.getOrderId(),
                    AE2IntelligentScheduling.SMART_CRAFT_RUNTIME.session(message.getOrderId())
                        .orElse(null));
            }
            return null;
        }
    }
}
