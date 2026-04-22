package com.homeftw.ae2intelligentscheduling.client.gui.widget;

import java.util.List;

import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;

import com.homeftw.ae2intelligentscheduling.network.packet.SyncSmartCraftOrderPacket.TaskView;

public final class SmartCraftTaskList {

    private final List<TaskView> tasks;

    public SmartCraftTaskList(List<TaskView> tasks) {
        this.tasks = tasks;
    }

    public void draw(FontRenderer fontRenderer, int left, int top, int width, int rowHeight) {
        if (fontRenderer == null) {
            return;
        }

        int y = top;
        for (TaskView task : this.tasks) {
            Gui.drawRect(left, y - 2, left + width, y + rowHeight - 2, 0x44000000);
            fontRenderer.drawString(task.requestKeyId(), left + 4, y + 2, 0xFFFFFF);
            fontRenderer.drawString("x" + task.amount(), left + width - 80, y + 2, 0xAAAAAA);
            fontRenderer.drawString(
                "L" + task.depth() + " " + task.status().name() + " " + task.splitIndex() + "/" + task.splitCount(),
                left + 4,
                y + 12,
                0xCCCCCC);
            y += rowHeight;
        }
    }
}
