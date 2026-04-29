package com.homeftw.ae2intelligentscheduling.network.packet;

import java.util.UUID;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.StatCollector;

import com.homeftw.ae2intelligentscheduling.AE2IntelligentScheduling;
import com.homeftw.ae2intelligentscheduling.integration.ae2.Ae2CraftConfirmAccess;
import com.homeftw.ae2intelligentscheduling.integration.ae2.Ae2CraftingJobSnapshotFactory;
import com.homeftw.ae2intelligentscheduling.smartcraft.analysis.SmartCraftOrderBuilder;
import com.homeftw.ae2intelligentscheduling.smartcraft.model.SmartCraftOrder;
import com.homeftw.ae2intelligentscheduling.smartcraft.runtime.SmartCraftRuntimeSession;

import appeng.api.networking.crafting.ICraftingJob;
import appeng.api.networking.security.BaseActionSource;
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

            ICraftingJob craftingJob = Ae2CraftConfirmAccess.result(craftConfirm);
            if (!(craftingJob instanceof CraftingJobV2)) {
                player.addChatMessage(
                    new ChatComponentText(
                        "\u667A\u80FD\u5408\u6210\u65E0\u6CD5\u542F\u52A8\uFF1AAE2 \u5408\u6210\u8BA1\u5212\u5C1A\u672A\u51C6\u5907\u5B8C\u6210"));
                return null;
            }
            CraftingJobV2 craftingJobV2 = (CraftingJobV2) craftingJob;
            if (craftingJobV2.originalRequest == null) {
                player.addChatMessage(
                    new ChatComponentText(
                        "\u667A\u80FD\u5408\u6210\u65E0\u6CD5\u542F\u52A8\uFF1AAE2 \u5408\u6210\u8BA1\u5212\u5C1A\u672A\u51C6\u5907\u5B8C\u6210"));
                return null;
            }

            // (v0.1.9 G12) Pass the player username so the order survives a server restart and
            // can rebind to the same player's session via SmartCraftOrderManager.resetForRestart.
            SmartCraftOrder order = new SmartCraftOrderBuilder()
                .build(
                    new Ae2CraftingJobSnapshotFactory().fromRequest(craftingJobV2.originalRequest),
                    player.getCommandSenderName());

            if (order.layers()
                .isEmpty()) {
                player.addChatMessage(
                    new ChatComponentText(StatCollector.translateToLocal("gui.ae2intelligentscheduling.allAvailable")));
                return null;
            }

            UUID trackedOrderId = AE2IntelligentScheduling.SMART_CRAFT_ORDER_MANAGER.track(order);
            if (player instanceof EntityPlayerMP) {
                BaseActionSource actionSource = Ae2CraftConfirmAccess.actionSource(craftConfirm, player);
                if (actionSource == null) {
                    // Roll back the tracked order so it does not become an orphan that lingers in the
                    // manager forever (no session means tick() will never touch it, no sync means the
                    // client never learns about it). Without this, repeatedly hitting "smart craft" on
                    // a misconfigured network leaks one order per click.
                    AE2IntelligentScheduling.SMART_CRAFT_ORDER_MANAGER.remove(trackedOrderId);
                    player.addChatMessage(
                        new ChatComponentText(
                            "\u667A\u80FD\u5408\u6210\u8FD0\u884C\u6001\u521D\u59CB\u5316\u5931\u8D25\uFF1AAE2 \u8282\u70B9\u4E0A\u4E0B\u6587\u4E0D\u53EF\u7528"));
                    return null;
                }
                SmartCraftRuntimeSession session = AE2IntelligentScheduling.SMART_CRAFT_SESSION_FACTORY
                    .create((EntityPlayerMP) player, actionSource);
                if (session == null) {
                    // Same orphan-prevention as above. Critically also skip the sync below — otherwise
                    // the client receives data, surfaces the "view schedule" button on AE2 GuiCraftingStatus,
                    // and a click ends up at syncLatestOrderForPlayer which finds no session and silently
                    // drops the request, leaving the player staring at a non-functional button.
                    AE2IntelligentScheduling.SMART_CRAFT_ORDER_MANAGER.remove(trackedOrderId);
                    player.addChatMessage(
                        new ChatComponentText(
                            "\u667A\u80FD\u5408\u6210\u8FD0\u884C\u6001\u521D\u59CB\u5316\u5931\u8D25\uFF1AAE2 \u8282\u70B9\u4E0A\u4E0B\u6587\u4E0D\u53EF\u7528"));
                    return null;
                }
                AE2IntelligentScheduling.SMART_CRAFT_RUNTIME.register(trackedOrderId, session);
                AE2IntelligentScheduling.SMART_CRAFT_ORDER_SYNC.sync((EntityPlayerMP) player, trackedOrderId);
            }

            AE2IntelligentScheduling.LOG.info(
                "Created smart craft preview {} for {} with scale {} and {} layers",
                trackedOrderId,
                order.targetRequestKey()
                    .id(),
                order.orderScale(),
                Integer.valueOf(
                    order.layers()
                        .size()));

            return null;
        }
    }
}
