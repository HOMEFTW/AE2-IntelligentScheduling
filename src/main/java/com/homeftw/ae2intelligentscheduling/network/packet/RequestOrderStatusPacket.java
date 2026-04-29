package com.homeftw.ae2intelligentscheduling.network.packet;

import net.minecraft.entity.player.EntityPlayerMP;

import com.homeftw.ae2intelligentscheduling.AE2IntelligentScheduling;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

public final class RequestOrderStatusPacket implements IMessage {

    public RequestOrderStatusPacket() {}

    @Override
    public void fromBytes(ByteBuf buf) {}

    @Override
    public void toBytes(ByteBuf buf) {}

    public static final class Handler implements IMessageHandler<RequestOrderStatusPacket, IMessage> {

        @Override
        public IMessage onMessage(RequestOrderStatusPacket message, MessageContext ctx) {
            EntityPlayerMP player = ctx.getServerHandler().playerEntity;
            // v0.1.7: ship the entire active-order list (all players, time-ordered). Client
            // reconciles its tab bar from this packet. Empty list still ships so the client
            // can clear stale tabs after every order ended.
            AE2IntelligentScheduling.SMART_CRAFT_ORDER_SYNC.syncListTo(player);
            return null;
        }
    }
}
