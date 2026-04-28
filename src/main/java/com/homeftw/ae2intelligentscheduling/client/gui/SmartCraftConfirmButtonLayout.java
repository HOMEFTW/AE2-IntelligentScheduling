package com.homeftw.ae2intelligentscheduling.client.gui;

public final class SmartCraftConfirmButtonLayout {

    public static final int BUTTON_ID = 0xAE21;
    public static final int CANCEL_BUTTON_ID = 0xAE22;
    public static final int RETRY_BUTTON_ID = 0xAE23;
    public static final int OVERVIEW_BUTTON_ID = 0xAE30;
    public static final int TASK_BUTTON_BASE = 0xAE31;
    public static final int VIEW_STATUS_BUTTON_ID = 0xAE40;

    private static final int BUTTON_WIDTH = 52;
    private static final int BUTTON_HEIGHT = 20;
    private static final int RIGHT_PADDING = 6;
    private static final int BUTTON_GAP = 4;
    private static final int START_ROW_OFFSET = 47;
    private static final int STATUS_HEADER_TOP = 4;
    private static final int STATUS_HEADER_HEIGHT = 14;

    private SmartCraftConfirmButtonLayout() {}

    /**
     * 智能合成 entry button on AE2 craft confirm: middle band, right side, above the AE2 Start row.
     */
    public static Position position(int guiLeft, int guiTop, int xSize, int ySize) {
        return new Position(
            guiLeft + xSize - RIGHT_PADDING - BUTTON_WIDTH,
            guiTop + ySize - START_ROW_OFFSET,
            BUTTON_WIDTH,
            BUTTON_HEIGHT);
    }

    // Cancel order button: bottom-left of the overlay info area
    public static Position cancelPosition(int guiLeft, int guiTop, int xSize, int ySize) {
        return new Position(guiLeft + 6, guiTop + ySize - 25, BUTTON_WIDTH, BUTTON_HEIGHT);
    }

    // Retry failed button: bottom-right of the overlay info area
    public static Position retryPosition(int guiLeft, int guiTop, int xSize, int ySize) {
        return new Position(guiLeft + xSize - 6 - BUTTON_WIDTH, guiTop + ySize - 25, BUTTON_WIDTH, BUTTON_HEIGHT);
    }

    /**
     * 查看调度 button on AE2 craft confirm: shares the middle band with the 智能合成 entry button,
     * placed to the left of it so neither overlaps the AE2 Start / Cancel / StartWithFollow row.
     */
    public static Position viewStatusOnConfirmPosition(int guiLeft, int guiTop, int xSize, int ySize) {
        return new Position(
            guiLeft + xSize - RIGHT_PADDING - BUTTON_WIDTH - BUTTON_GAP - BUTTON_WIDTH,
            guiTop + ySize - START_ROW_OFFSET,
            BUTTON_WIDTH,
            BUTTON_HEIGHT);
    }

    /**
     * 查看调度 button on AE2 terminal: bottom-right of the GUI, matches AE2 button width.
     */
    public static Position viewStatusOnTerminalPosition(int guiLeft, int guiTop, int xSize, int ySize) {
        return new Position(
            guiLeft + xSize - RIGHT_PADDING - BUTTON_WIDTH,
            guiTop + ySize - 25,
            BUTTON_WIDTH,
            BUTTON_HEIGHT);
    }

    /**
     * 查看调度 button on AE2 GuiCraftingStatus: top header row, just to the right of the 状态 title,
     * sized to match AE2's top-row search field height so it never exceeds the AE2 GUI bounds.
     */
    public static Position viewStatusOnCraftingStatusPosition(int guiLeft, int guiTop, int xSize, int ySize) {
        return new Position(guiLeft + 60, guiTop + STATUS_HEADER_TOP, BUTTON_WIDTH, STATUS_HEADER_HEIGHT);
    }

    /**
     * @deprecated Prefer the screen-kind specific variants. Retained for backwards compatibility with
     *             existing call sites that have not yet migrated.
     */
    @Deprecated
    public static Position viewStatusPosition(int guiLeft, int guiTop, int xSize, int ySize) {
        return viewStatusOnTerminalPosition(guiLeft, guiTop, xSize, ySize);
    }

    public static final class Position {

        private final int x;
        private final int y;
        private final int width;
        private final int height;

        private Position(int x, int y, int width, int height) {
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
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
    }
}
