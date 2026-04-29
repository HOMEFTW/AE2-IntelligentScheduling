package com.homeftw.ae2intelligentscheduling.client.gui;

public final class SmartCraftStatusLayout {

    public static final int GUI_WIDTH = 238;
    /**
     * Number of task rows visible in the top grid before the player has to scroll. Increased from 5 to 6
     * to give more space to the grid / detail panel; combined with {@link #MAX_VISIBLE_TASK_ROWS} this
     * shifts the layout so the schedule list takes less vertical space.
     */
    public static final int GRID_ROWS = 6;
    public static final int GRID_COLS = 3;
    /**
     * Cap on how many task rows the schedule list shows at once. Anything beyond this becomes scroll
     * content. Keeps the schedule list compact so the top grid / detail panel can grow.
     */
    public static final int MAX_VISIBLE_TASK_ROWS = 4;
    public static final int TOP_SECTION_HEIGHT = 188;
    public static final int INFO_BAR_TOP = 161;
    public static final int SCHEDULE_TITLE_TOP = TOP_SECTION_HEIGHT + 8;
    public static final int SCHEDULE_BUTTON_TOP = TOP_SECTION_HEIGHT + 20;
    public static final int ACTION_AREA_HEIGHT = 51;
    public static final int OUTER_MARGIN = 16;
    public static final int MIN_VISIBLE_SCHEDULE_ROWS = 2;

    /**
     * v0.1.7 tab strip height. The strip is rendered above the GUI body (acts like a vertical
     * 'ear' similar to vanilla creative tab pages) so the existing INFO_BAR_TOP /
     * SCHEDULE_TITLE_TOP coordinates don't need to shift. Pairs with
     * {@link SmartCraftOrderTabsWidget#STRIP_HEIGHT} so changing one updates the other.
     */
    public static final int TABS_AREA_HEIGHT = SmartCraftOrderTabsWidget.STRIP_HEIGHT;
    /** Gap between the tab strip and the top edge of the GUI body. */
    public static final int TABS_TOP_GAP = 2;
    /**
     * Total vertical space the tab strip claims above the GUI body, factored into the minimum
     * top margin. Keeps the strip from being clipped on screens just tall enough for the body.
     */
    public static final int TABS_TOTAL_RESERVED = TABS_AREA_HEIGHT + TABS_TOP_GAP;

    private SmartCraftStatusLayout() {}

    public static int visibleScheduleRows(int screenHeight, int taskCount) {
        int totalRows = totalScheduleRows(taskCount);
        int availableRows = (screenHeight - OUTER_MARGIN * 2
            - TABS_TOTAL_RESERVED
            - SCHEDULE_BUTTON_TOP
            - ACTION_AREA_HEIGHT) / SmartCraftOverlayRenderer.LIST_ROW_HEIGHT;
        // Cap to MAX_VISIBLE_TASK_ROWS + 1 (the +1 is the 总调度 overview row) so the schedule list never
        // gets larger than the design budget, even on very tall screens.
        int hardCap = MAX_VISIBLE_TASK_ROWS + 1;
        int visibleRows = Math.min(hardCap, Math.max(MIN_VISIBLE_SCHEDULE_ROWS, availableRows));
        return Math.max(1, Math.min(totalRows, visibleRows));
    }

    public static int guiHeight(int screenHeight, int taskCount) {
        int rows = visibleScheduleRows(screenHeight, taskCount);
        int desiredHeight = SCHEDULE_BUTTON_TOP + rows * SmartCraftOverlayRenderer.LIST_ROW_HEIGHT + ACTION_AREA_HEIGHT;
        int minHeight = SCHEDULE_BUTTON_TOP + SmartCraftOverlayRenderer.LIST_ROW_HEIGHT + ACTION_AREA_HEIGHT;
        int maxHeight = Math.max(minHeight, screenHeight - OUTER_MARGIN * 2);
        return Math.min(desiredHeight, maxHeight);
    }

    public static int maxScheduleScroll(int taskCount, int visibleRows) {
        int visibleTaskRows = Math.max(0, visibleRows - 1);
        return Math.max(0, taskCount - visibleTaskRows);
    }

    public static int clampScheduleScroll(int scroll, int taskCount, int visibleRows) {
        return Math.max(0, Math.min(scroll, maxScheduleScroll(taskCount, visibleRows)));
    }

    public static int firstVisibleTask(int scroll, int taskCount, int visibleRows) {
        return clampScheduleScroll(scroll, taskCount, visibleRows);
    }

    public static int visibleTaskCount(int firstVisibleTask, int taskCount, int visibleRows) {
        return Math.max(0, Math.min(taskCount - firstVisibleTask, Math.max(0, visibleRows - 1)));
    }

    private static int totalScheduleRows(int taskCount) {
        return 1 + Math.max(0, taskCount);
    }

    // --- Top grid scroll helpers ---

    /** Total number of rows the top grid would need to render every task at {@link #GRID_COLS} per row. */
    public static int totalGridRows(int taskCount) {
        if (taskCount <= 0) return 0;
        return (taskCount + GRID_COLS - 1) / GRID_COLS;
    }

    public static int maxGridScroll(int taskCount) {
        return Math.max(0, totalGridRows(taskCount) - GRID_ROWS);
    }

    public static int clampGridScroll(int scroll, int taskCount) {
        return Math.max(0, Math.min(scroll, maxGridScroll(taskCount)));
    }
}
