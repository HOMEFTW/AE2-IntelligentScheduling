package com.homeftw.ae2intelligentscheduling.network.packet;

import net.minecraft.entity.player.EntityPlayerMP;

import com.homeftw.ae2intelligentscheduling.AE2IntelligentScheduling;
import com.homeftw.ae2intelligentscheduling.network.NetworkHandler;

import appeng.api.networking.crafting.ICraftingCPU;
import appeng.api.networking.crafting.ICraftingGrid;
import cpw.mods.fml.common.network.ByteBufUtils;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

public final class RequestCpuDetailPacket implements IMessage {

    private String cpuName;

    public RequestCpuDetailPacket() {}

    public RequestCpuDetailPacket(String cpuName) {
        this.cpuName = cpuName == null ? "" : cpuName;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        this.cpuName = ByteBufUtils.readUTF8String(buf);
    }

    @Override
    public void toBytes(ByteBuf buf) {
        ByteBufUtils.writeUTF8String(buf, this.cpuName == null ? "" : this.cpuName);
    }

    public static final class Handler implements IMessageHandler<RequestCpuDetailPacket, IMessage> {

        @Override
        public IMessage onMessage(RequestCpuDetailPacket message, MessageContext ctx) {
            EntityPlayerMP player = ctx.getServerHandler().playerEntity;
            ICraftingGrid grid = AE2IntelligentScheduling.SMART_CRAFT_RUNTIME.craftingGridForPlayer(player);
            if (grid == null) return null;

            for (ICraftingCPU cpu : grid.getCpus()) {
                if (message.cpuName.equals(cpu.getName()) && cpu.isBusy()) {
                    NetworkHandler.INSTANCE.sendTo(SyncCpuDetailPacket.from(cpu), player);
                    return null;
                }
            }
            return null;
        }
    }
}
