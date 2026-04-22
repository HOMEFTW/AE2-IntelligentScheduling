package com.homeftw.ae2intelligentscheduling.client.gui;

import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.util.StatCollector;

import com.homeftw.ae2intelligentscheduling.client.gui.widget.SmartCraftTaskList;
import com.homeftw.ae2intelligentscheduling.network.NetworkHandler;
import com.homeftw.ae2intelligentscheduling.network.packet.RequestSmartCraftActionPacket;
import com.homeftw.ae2intelligentscheduling.network.packet.SyncSmartCraftOrderPacket;

public class GuiSmartCraftStatus extends GuiScreen {

    private SyncSmartCraftOrderPacket packet;
    private GuiButton cancelButton;
    private GuiButton retryButton;

    public GuiSmartCraftStatus(SyncSmartCraftOrderPacket packet) {
        this.packet = packet;
    }

    public String orderId() {
        return this.packet.getOrderId();
    }

    public void update(SyncSmartCraftOrderPacket nextPacket) {
        this.packet = nextPacket;
        if (this.retryButton != null) {
            updateButtonStates();
        }
    }

    @Override
    public void initGui() {
        this.buttonList.clear();
        this.cancelButton = new GuiButton(
            1,
            this.width / 2 - 90,
            this.height - 36,
            80,
            20,
            StatCollector.translateToLocal("gui.ae2intelligentscheduling.cancelOrder"));
        this.retryButton = new GuiButton(
            2,
            this.width / 2 + 10,
            this.height - 36,
            80,
            20,
            StatCollector.translateToLocal("gui.ae2intelligentscheduling.retryFailed"));
        this.buttonList.add(this.cancelButton);
        this.buttonList.add(this.retryButton);
        updateButtonStates();
    }

    @Override
    protected void actionPerformed(GuiButton button) {
        if (button == this.cancelButton) {
            NetworkHandler.INSTANCE.sendToServer(
                new RequestSmartCraftActionPacket(this.packet.getOrderId(), RequestSmartCraftActionPacket.Action.CANCEL_ORDER));
        } else if (button == this.retryButton) {
            NetworkHandler.INSTANCE.sendToServer(
                new RequestSmartCraftActionPacket(this.packet.getOrderId(), RequestSmartCraftActionPacket.Action.RETRY_FAILED));
        }
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        this.drawDefaultBackground();

        int centerX = this.width / 2;
        this.drawCenteredString(
            this.fontRendererObj,
            StatCollector.translateToLocal("gui.ae2intelligentscheduling.statusTitle"),
            centerX,
            16,
            0xFFFFFF);
        this.drawString(this.fontRendererObj, "Order: " + this.packet.getOrderId(), 24, 36, 0xCCCCCC);
        this.drawString(
            this.fontRendererObj,
            StatCollector.translateToLocal("gui.ae2intelligentscheduling.scale") + ": " + this.packet.getOrderScale(),
            24,
            50,
            0xCCCCCC);
        this.drawString(
            this.fontRendererObj,
            StatCollector.translateToLocal("gui.ae2intelligentscheduling.status") + ": " + this.packet.getStatus(),
            24,
            64,
            0xCCCCCC);
        this.drawString(
            this.fontRendererObj,
            StatCollector.translateToLocal("gui.ae2intelligentscheduling.currentLayer") + ": "
                    + (this.packet.getCurrentLayer() + 1)
                    + "/"
                    + this.packet.getTotalLayers(),
            24,
            78,
            0xCCCCCC);
        this.drawString(this.fontRendererObj, "Target: " + this.packet.getTargetRequestKeyId(), 24, 92, 0xCCCCCC);

        new SmartCraftTaskList(this.packet.getTasks()).draw(this.fontRendererObj, 24, 118, this.width - 48, 28);

        super.drawScreen(mouseX, mouseY, partialTicks);
    }

    private void updateButtonStates() {
        String status = this.packet.getStatus();
        this.cancelButton.enabled = !"COMPLETED".equals(status) && !"CANCELLED".equals(status);
        this.retryButton.enabled = hasFailedTasks() && !"CANCELLED".equals(status);
    }

    private boolean hasFailedTasks() {
        for (SyncSmartCraftOrderPacket.TaskView task : this.packet.getTasks()) {
            if ("FAILED".equals(task.status().name())) {
                return true;
            }
        }
        return false;
    }
}
