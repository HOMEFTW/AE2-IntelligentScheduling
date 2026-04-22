package com.homeftw.ae2intelligentscheduling.network.packet;

import java.util.UUID;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.ChatComponentText;

import com.homeftw.ae2intelligentscheduling.AE2IntelligentScheduling;
import com.homeftw.ae2intelligentscheduling.integration.ae2.Ae2CraftingJobSnapshotFactory;
import com.homeftw.ae2intelligentscheduling.mixin.ae2.ContainerCraftConfirmAccessor;
import com.homeftw.ae2intelligentscheduling.smartcraft.analysis.SmartCraftOrderBuilder;
import com.homeftw.ae2intelligentscheduling.smartcraft.model.SmartCraftOrder;

import appeng.api.networking.crafting.ICraftingJob;
import appeng.container.implementations.ContainerCraftConfirm;
import appeng.crafting.v2.CraftingJobV2;
import cpw.mods.fml.common.network.ByteBufUtils;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

public final class OpenSmartCraftPreviewPacket implements IMessage {

    private static final String ACTION_PREVIEW = "preview";

    private String action;
    private String orderId;

    public OpenSmartCraftPreviewPacket() {
        this(ACTION_PREVIEW, "");
    }

    public OpenSmartCraftPreviewPacket(String action, String orderId) {
        this.action = action;
        this.orderId = orderId;
    }

    public static OpenSmartCraftPreviewPacket preview() {
        return new OpenSmartCraftPreviewPacket(ACTION_PREVIEW, "");
    }

    public String getAction() {
        return this.action;
    }

    public String getOrderId() {
        return this.orderId;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        this.action = ByteBufUtils.readUTF8String(buf);
        this.orderId = ByteBufUtils.readUTF8String(buf);
    }

    @Override
    public void toBytes(ByteBuf buf) {
        ByteBufUtils.writeUTF8String(buf, this.action);
        ByteBufUtils.writeUTF8String(buf, this.orderId);
    }

    public static final class Handler implements IMessageHandler<OpenSmartCraftPreviewPacket, IMessage> {

        @Override
        public IMessage onMessage(OpenSmartCraftPreviewPacket message, MessageContext ctx) {
            EntityPlayer player = ctx.getServerHandler().playerEntity;

            if (!(player.openContainer instanceof ContainerCraftConfirm)) {
                return null;
            }
            ContainerCraftConfirm craftConfirm = (ContainerCraftConfirm) player.openContainer;

            ICraftingJob craftingJob = ((ContainerCraftConfirmAccessor) craftConfirm).ae2is$getResult();
            if (!(craftingJob instanceof CraftingJobV2)) {
                player.addChatMessage(
                    new ChatComponentText("\u667A\u80FD\u5408\u6210\u65E0\u6CD5\u542F\u52A8\uFF1AAE2 \u5408\u6210\u8BA1\u5212\u5C1A\u672A\u51C6\u5907\u5B8C\u6210"));
                return null;
            }
            CraftingJobV2 craftingJobV2 = (CraftingJobV2) craftingJob;
            if (craftingJobV2.originalRequest == null) {
                player.addChatMessage(
                    new ChatComponentText("\u667A\u80FD\u5408\u6210\u65E0\u6CD5\u542F\u52A8\uFF1AAE2 \u5408\u6210\u8BA1\u5212\u5C1A\u672A\u51C6\u5907\u5B8C\u6210"));
                return null;
            }

            SmartCraftOrder order = new SmartCraftOrderBuilder()
                    .build(new Ae2CraftingJobSnapshotFactory().fromRequest(craftingJobV2.originalRequest));
            UUID trackedOrderId = AE2IntelligentScheduling.SMART_CRAFT_ORDER_MANAGER.track(order);
            if (player instanceof EntityPlayerMP) {
                AE2IntelligentScheduling.SMART_CRAFT_ORDER_SYNC.sync((EntityPlayerMP) player, trackedOrderId);
            }

            AE2IntelligentScheduling.LOG.info(
                "Created smart craft preview {} for {} with scale {} and {} layers",
                trackedOrderId,
                order.targetRequestKey().id(),
                order.orderScale(),
                Integer.valueOf(order.layers().size()));

            return null;
        }
    }
}
