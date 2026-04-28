package com.homeftw.ae2intelligentscheduling.network.packet;

import net.minecraft.item.ItemStack;

import com.homeftw.ae2intelligentscheduling.AE2IntelligentScheduling;

import appeng.api.networking.crafting.ICraftingCPU;
import appeng.api.storage.data.IAEItemStack;
import cpw.mods.fml.common.network.ByteBufUtils;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

public final class SyncCpuDetailPacket implements IMessage {

    private String cpuName;
    private ItemStack finalOutput;
    private long remainingCount;
    private long startCount;
    private long elapsedTime;

    public SyncCpuDetailPacket() {}

    public static SyncCpuDetailPacket from(ICraftingCPU cpu) {
        SyncCpuDetailPacket p = new SyncCpuDetailPacket();
        p.cpuName = cpu.getName();
        IAEItemStack out = cpu.getFinalOutput();
        p.finalOutput = out == null ? null : out.getItemStack();
        p.remainingCount = cpu.getRemainingItemCount();
        p.startCount = cpu.getStartItemCount();
        p.elapsedTime = cpu.getElapsedTime();
        return p;
    }

    public String getCpuName() {
        return this.cpuName;
    }

    public ItemStack getFinalOutput() {
        return this.finalOutput;
    }

    public long getRemainingCount() {
        return this.remainingCount;
    }

    public long getStartCount() {
        return this.startCount;
    }

    public long getElapsedTime() {
        return this.elapsedTime;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        this.cpuName = ByteBufUtils.readUTF8String(buf);
        this.finalOutput = ByteBufUtils.readItemStack(buf);
        this.remainingCount = buf.readLong();
        this.startCount = buf.readLong();
        this.elapsedTime = buf.readLong();
    }

    @Override
    public void toBytes(ByteBuf buf) {
        ByteBufUtils.writeUTF8String(buf, this.cpuName == null ? "" : this.cpuName);
        ByteBufUtils.writeItemStack(buf, this.finalOutput);
        buf.writeLong(this.remainingCount);
        buf.writeLong(this.startCount);
        buf.writeLong(this.elapsedTime);
    }

    public static final class Handler implements IMessageHandler<SyncCpuDetailPacket, IMessage> {

        @Override
        public IMessage onMessage(SyncCpuDetailPacket message, MessageContext ctx) {
            AE2IntelligentScheduling.proxy.updateCpuDetail(message);
            return null;
        }
    }
}
