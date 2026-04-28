package com.homeftw.ae2intelligentscheduling.client.gui;

import java.util.ArrayList;
import java.util.List;

import com.homeftw.ae2intelligentscheduling.network.packet.SyncSmartCraftOrderPacket.TaskView;

public final class SmartCraftScheduleButtonLayout {

    private static final int OVERVIEW_X_OFFSET = 8;
    private static final int TASK_X_OFFSET = 8;
    private static final int BUTTON_WIDTH = 222;

    private SmartCraftScheduleButtonLayout() {}

    public static List<ButtonSpec> buttons(int guiLeft, int listY, List<TaskView> tasks) {
        return buttons(guiLeft, listY, tasks, 0, tasks.size());
    }

    public static List<ButtonSpec> buttons(int guiLeft, int listY, List<TaskView> tasks, int firstTask, int maxTasks) {
        List<ButtonSpec> buttons = new ArrayList<ButtonSpec>();
        buttons.add(
            new ButtonSpec(
                SmartCraftConfirmButtonLayout.OVERVIEW_BUTTON_ID,
                guiLeft + OVERVIEW_X_OFFSET,
                listY,
                BUTTON_WIDTH,
                SmartCraftOverlayRenderer.LIST_BTN_HEIGHT,
                tr("overview"),
                true));

        int rowY = listY + SmartCraftOverlayRenderer.LIST_ROW_HEIGHT;
        int lastTask = Math.min(tasks.size(), Math.max(0, firstTask) + Math.max(0, maxTasks));
        for (int i = Math.max(0, firstTask); i < lastTask; i++) {
            TaskView task = tasks.get(i);
            buttons.add(
                new ButtonSpec(
                    SmartCraftConfirmButtonLayout.TASK_BUTTON_BASE + i,
                    guiLeft + TASK_X_OFFSET,
                    rowY,
                    BUTTON_WIDTH,
                    SmartCraftOverlayRenderer.LIST_BTN_HEIGHT,
                    taskLabel(task),
                    true));
            rowY += SmartCraftOverlayRenderer.LIST_ROW_HEIGHT;
        }
        return buttons;
    }

    private static String taskLabel(TaskView task) {
        String name = task.requestKeyId();
        int colon = name.lastIndexOf(':');
        if (colon >= 0 && colon < name.length() - 1) {
            name = name.substring(colon + 1);
        }
        if (name.length() > 8) {
            name = name.substring(0, 7) + "...";
        }
        return tr(
            "currentLayer") + " " + task.depth() + ": " + name + " " + task.splitIndex() + "/" + task.splitCount();
    }

    private static String tr(String key) {
        return net.minecraft.util.StatCollector.translateToLocal("gui.ae2intelligentscheduling." + key);
    }

    public static final class ButtonSpec {

        private final int id;
        private final int x;
        private final int y;
        private final int width;
        private final int height;
        private final String label;
        private final boolean enabled;

        private ButtonSpec(int id, int x, int y, int width, int height, String label, boolean enabled) {
            this.id = id;
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
            this.label = label;
            this.enabled = enabled;
        }

        public int id() {
            return this.id;
        }

        public int x() {
            return this.x;
        }

        public int y() {
            return this.y;
        }

        public int width() {
            return this.width;
        }

        public int height() {
            return this.height;
        }

        public String label() {
            return this.label;
        }

        public boolean enabled() {
            return this.enabled;
        }
    }
}
