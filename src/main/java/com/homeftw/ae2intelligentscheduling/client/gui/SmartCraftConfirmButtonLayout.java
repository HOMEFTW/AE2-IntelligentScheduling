package com.homeftw.ae2intelligentscheduling.client.gui;

public final class SmartCraftConfirmButtonLayout {

    public static final int BUTTON_ID = 0xAE21;
    public static final int CANCEL_BUTTON_ID = 0xAE22;
    public static final int RETRY_BUTTON_ID = 0xAE23;
    public static final int VIEW_STATUS_BUTTON_ID = 0xAE40;

    // AE2-style 52x20 vanilla GuiButton — same dimensions as AE2's own Cancel/Start buttons.
    // Width fits 4 Chinese glyphs ("智能合成" / "查看调度") with normal Minecraft font padding.
    private static final int BUTTON_WIDTH = 52;
    private static final int BUTTON_HEIGHT = 20;
    /**
     * Right edge of the usable button strip on AE2's craftingcpu.png. The texture is 238 px wide
     * but its right portion is occupied by the AE2 scrollbar column ({@code [218..230]}) and the
     * GUI frame ({@code [230..238]}). AE2's own cancel button right-edges at exactly 213
     * ({@code CANCEL_LEFT_OFFSET=163 + CANCEL_WIDTH=50}). We anchor our retry button to the same
     * line so it never tramples the scrollbar / frame — that bug was visible in the
     * {@code 2026-04-29_12.34.07} screenshot where the 重试失败 button extended into the dark
     * area outside the GUI.
     */
    private static final int STATUS_BUTTON_RIGHT_USABLE = 213;
    // Right-side ear column: 4 px clear of AE2 GUI right edge so neither AE2 internals nor our
    // buttons can overlap. Buttons stack bottom-up: bottom slot aligns with the AE2 Start button
    // row (ySize - 25), upper slots step up by BUTTON_HEIGHT + 2 px gap.
    private static final int EAR_RIGHT_GAP = 4;
    private static final int EAR_BOTTOM_OFFSET = 25; // matches AE2 Start button's ySize - 25 row
    private static final int EAR_SLOT_GAP = 2;
    private static final int EAR_PITCH = BUTTON_HEIGHT + EAR_SLOT_GAP;

    private SmartCraftConfirmButtonLayout() {}

    /**
     * 智能合成 entry button on AE2 craft confirm: GUI right edge, one slot ABOVE the AE2 Start
     * button row so the player still sees AE2's bottom row intact and our button never overlaps
     * AE2's top-row tab buttons (switchDisplayMode etc.).
     */
    public static Position position(int guiLeft, int guiTop, int xSize, int ySize) {
        return earSlot(guiLeft, guiTop, xSize, ySize, 1);
    }

    // Cancel order button: bottom-left of GuiSmartCraftStatus's own panel.
    public static Position cancelPosition(int guiLeft, int guiTop, int xSize, int ySize) {
        return new Position(guiLeft + 6, guiTop + ySize - 25, BUTTON_WIDTH, BUTTON_HEIGHT);
    }

    // Retry failed button: bottom-right of GuiSmartCraftStatus's own panel. Right-edge anchored
    // at STATUS_BUTTON_RIGHT_USABLE (213) — NOT (xSize - 6) — so the button stays inside the
    // visually usable region of AE2's craftingcpu.png and never overlaps the scrollbar column or
    // the frame on the right. xSize is kept in the signature for consistency with cancelPosition.
    public static Position retryPosition(int guiLeft, int guiTop, int xSize, int ySize) {
        return new Position(
            guiLeft + STATUS_BUTTON_RIGHT_USABLE - BUTTON_WIDTH,
            guiTop + ySize - 25,
            BUTTON_WIDTH,
            BUTTON_HEIGHT);
    }

    /**
     * 查看调度 button on AE2 craft confirm: bottom slot, beside the AE2 Start button (just
     * outside the GUI right edge). Direct sibling of the AE2 Start button so the player can
     * cross-reference scheduling info while still on the confirm screen.
     */
    public static Position viewStatusOnConfirmPosition(int guiLeft, int guiTop, int xSize, int ySize) {
        return earSlot(guiLeft, guiTop, xSize, ySize, 0);
    }

    /**
     * 查看调度 button on AE2 terminal: bottom slot beside the GUI right edge — terminal has no
     * Start button, but matching the same y as on CraftConfirm keeps the button at a predictable
     * location for the player.
     */
    public static Position viewStatusOnTerminalPosition(int guiLeft, int guiTop, int xSize, int ySize) {
        return earSlot(guiLeft, guiTop, xSize, ySize, 0);
    }

    /**
     * 查看调度 button on AE2 GuiCraftingStatus: bottom slot, same convention as the others.
     */
    public static Position viewStatusOnCraftingStatusPosition(int guiLeft, int guiTop, int xSize, int ySize) {
        return earSlot(guiLeft, guiTop, xSize, ySize, 0);
    }

    /**
     * @deprecated Prefer the screen-kind specific variants. Retained for backwards compatibility.
     */
    @Deprecated
    public static Position viewStatusPosition(int guiLeft, int guiTop, int xSize, int ySize) {
        return viewStatusOnTerminalPosition(guiLeft, guiTop, xSize, ySize);
    }

    /**
     * Right-side ear slot indexed bottom-to-top. Slot 0 sits flush with the AE2 Start button
     * row ({@code ySize - 25}); slot N is offset {@code N * EAR_PITCH} px upward, so multiple
     * buttons stack vertically without overlapping AE2's GUI body or top-row tab buttons.
     */
    private static Position earSlot(int guiLeft, int guiTop, int xSize, int ySize, int slotIndex) {
        int x = guiLeft + xSize + EAR_RIGHT_GAP;
        int y = guiTop + ySize - EAR_BOTTOM_OFFSET - slotIndex * EAR_PITCH;
        return new Position(x, y, BUTTON_WIDTH, BUTTON_HEIGHT);
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
