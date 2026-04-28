package com.homeftw.ae2intelligentscheduling.client.gui;

import java.util.Iterator;
import java.util.List;

import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.StatCollector;

import org.lwjgl.input.Mouse;

import com.homeftw.ae2intelligentscheduling.network.NetworkHandler;
import com.homeftw.ae2intelligentscheduling.network.packet.RequestSmartCraftActionPacket;
import com.homeftw.ae2intelligentscheduling.network.packet.SyncSmartCraftOrderPacket;
import com.homeftw.ae2intelligentscheduling.network.packet.SyncSmartCraftOrderPacket.TaskView;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

@SideOnly(Side.CLIENT)
public final class GuiSmartCraftStatus extends GuiScreen {

    private static final int TEXTURE_BELOW_TOP_ROW_Y = 41;
    private static final int TEXTURE_ABOVE_BOTTOM_ROW_Y = 51;
    private static final int SECTION_HEIGHT = 23;
    private static final int SOURCE_GUI_HEIGHT = 184;
    private static final int SOLID_PANEL_INSET_X = 7;
    private static final int SOLID_PANEL_BOTTOM_GAP = 28;
    private static final int SOLID_PANEL_BG_COLOR = 0xFFC6C6C6;
    private static final int SOLID_PANEL_BORDER_COLOR = 0xFF8B8B8B;

    // Scrollbar visual constants. Slim track + slightly wider thumb so the player notices it.
    // The grid scrollbar mirrors AE2's GuiCraftingCPU exactly: x=218, y=19, h=137, w=12.
    // The schedule scrollbar lives in our custom area below and stays narrower for visual contrast.
    private static final int SCROLLBAR_WIDTH = 6;
    private static final int SCROLLBAR_RIGHT_INSET = 10;
    private static final int GRID_SCROLLBAR_WIDTH = 12;
    private static final int GRID_SCROLLBAR_X = 218;
    private static final int GRID_SCROLLBAR_TOP_OFFSET = 19;
    private static final int GRID_SCROLLBAR_HEIGHT = 137;
    private static final int SCROLLBAR_TRACK_COLOR = 0xFF555555;
    private static final int SCROLLBAR_THUMB_COLOR = 0xFFB0B0B0;
    private static final int SCROLLBAR_THUMB_HIGHLIGHT_COLOR = 0xFFE0E0E0;
    private static final int SCROLLBAR_THUMB_MIN_HEIGHT = 8;

    // Top-grid metrics (mirrors SmartCraftOverlayRenderer's XO/YO/SECTION_LENGTH/ROW_HEIGHT).
    private static final int GRID_X_OFFSET = 9;
    private static final int GRID_Y_OFFSET = 22;
    private static final int GRID_CELL_WIDTH = 67;
    private static final int GRID_CELL_HEIGHT = 23;

    private enum DragMode {
        NONE,
        GRID_THUMB,
        SCHEDULE_THUMB
    }

    private static final ResourceLocation CRAFTING_CPU_TEXTURE = new ResourceLocation(
        "appliedenergistics2",
        "textures/guis/craftingcpu.png");

    private final SyncSmartCraftOrderPacket packet;
    private int refreshTicks = 0;
    private int scheduleScroll = 0;
    private DragMode dragMode = DragMode.NONE;
    private int dragOffsetWithinThumb = 0;
    // Cached button references so we can flip enabled state every frame based on current order data.
    private GuiButton cancelButton;
    private GuiButton retryButton;

    public GuiSmartCraftStatus(SyncSmartCraftOrderPacket packet) {
        this.packet = packet;
    }

    @Override
    public void initGui() {
        super.initGui();
        SmartCraftConfirmGuiEventHandler.OVERLAY.update(this.packet);

        int guiLeft = this.guiLeft();
        int guiTop = this.guiTop();
        int guiHeight = this.guiHeight();
        SmartCraftConfirmButtonLayout.Position cancel = SmartCraftConfirmButtonLayout
            .cancelPosition(guiLeft, guiTop, SmartCraftStatusLayout.GUI_WIDTH, guiHeight);
        SmartCraftConfirmButtonLayout.Position retry = SmartCraftConfirmButtonLayout
            .retryPosition(guiLeft, guiTop, SmartCraftStatusLayout.GUI_WIDTH, guiHeight);

        this.cancelButton = new GuiButton(
            SmartCraftConfirmButtonLayout.CANCEL_BUTTON_ID,
            cancel.x(),
            cancel.y(),
            cancel.width(),
            cancel.height(),
            StatCollector.translateToLocal("gui.ae2intelligentscheduling.cancelOrder"));
        this.retryButton = new GuiButton(
            SmartCraftConfirmButtonLayout.RETRY_BUTTON_ID,
            retry.x(),
            retry.y(),
            retry.width(),
            retry.height(),
            StatCollector.translateToLocal("gui.ae2intelligentscheduling.retryFailed"));
        this.buttonList.add(this.cancelButton);
        this.buttonList.add(this.retryButton);
        this.refreshActionButtonStates();
        this.syncScheduleButtons(guiLeft, guiTop, guiHeight);
    }

    /**
     * Greys out the action buttons based on the current order state:
     * <ul>
     * <li><b>Cancel</b> is disabled when no task is non-terminal — there is nothing left to cancel.</li>
     * <li><b>Retry Failed</b> is disabled when the order has no FAILED / CANCELLED task — there is
     * nothing the server can revive.</li>
     * </ul>
     * Called from {@code initGui} (after creation) and every frame from {@code drawScreen} so the state
     * tracks the latest sync packet.
     */
    private void refreshActionButtonStates() {
        if (this.cancelButton != null) {
            this.cancelButton.enabled = SmartCraftConfirmGuiEventHandler.OVERLAY.isOrderActive();
        }
        if (this.retryButton != null) {
            this.retryButton.enabled = SmartCraftConfirmGuiEventHandler.OVERLAY.hasRetriableTasks();
        }
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        this.drawDefaultBackground();

        int guiLeft = this.guiLeft();
        int guiTop = this.guiTop();
        int guiHeight = this.guiHeight();

        this.drawLongAe2Background(guiLeft, guiTop, guiHeight);
        this.drawSolidPanelOverlay(guiLeft, guiTop, guiHeight);

        SmartCraftConfirmGuiEventHandler.OVERLAY.draw(
            guiLeft,
            guiTop,
            SmartCraftStatusLayout.GUI_WIDTH,
            SmartCraftStatusLayout.TOP_SECTION_HEIGHT,
            mouseX,
            mouseY);
        this.drawScheduleTitle(guiLeft, guiTop);
        this.refreshActionButtonStates();
        this.syncScheduleButtons(guiLeft, guiTop, guiHeight);

        this.fontRendererObj.drawString(
            StatCollector.translateToLocal("gui.ae2intelligentscheduling.statusTitle"),
            guiLeft + 8,
            guiTop + 7,
            0x404040);
        super.drawScreen(mouseX, mouseY, partialTicks);

        // Draw scrollbars on top of buttons / overlay so they always remain visible.
        this.drawGridScrollbar(guiLeft, guiTop, mouseX, mouseY);
        this.drawScheduleScrollbar(guiLeft, guiTop, guiHeight, mouseX, mouseY);
    }

    @Override
    public void updateScreen() {
        super.updateScreen();
        this.refreshTicks++;
        if (this.refreshTicks >= 20) {
            this.refreshTicks = 0;
            SmartCraftConfirmGuiEventHandler.OVERLAY.sendRefresh();
        }
    }

    @Override
    public void handleMouseInput() {
        int wheel = Mouse.getEventDWheel();
        if (wheel != 0) {
            int mouseX = Mouse.getEventX() * this.width / this.mc.displayWidth;
            int mouseY = this.height - Mouse.getEventY() * this.height / this.mc.displayHeight - 1;
            int delta = wheel < 0 ? 1 : -1;
            if (this.isMouseInGridArea(mouseX, mouseY)) {
                SmartCraftConfirmGuiEventHandler.OVERLAY.scroll(delta);
                return;
            }
            if (this.isMouseInScheduleList(mouseX, mouseY)) {
                this.scrollSchedule(delta);
                return;
            }
        }
        super.handleMouseInput();
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) {
        if (mouseButton == 0) {
            if (this.tryStartScrollbarDrag(mouseX, mouseY, true)) {
                return;
            }
            if (this.tryStartScrollbarDrag(mouseX, mouseY, false)) {
                return;
            }
        }
        super.mouseClicked(mouseX, mouseY, mouseButton);
    }

    @Override
    protected void mouseClickMove(int mouseX, int mouseY, int clickedMouseButton, long timeSinceLastClick) {
        if (this.dragMode == DragMode.GRID_THUMB) {
            this.dragGridThumb(mouseY);
            return;
        }
        if (this.dragMode == DragMode.SCHEDULE_THUMB) {
            this.dragScheduleThumb(mouseY);
            return;
        }
        super.mouseClickMove(mouseX, mouseY, clickedMouseButton, timeSinceLastClick);
    }

    @Override
    protected void mouseMovedOrUp(int mouseX, int mouseY, int eventButton) {
        if (eventButton == 0 && this.dragMode != DragMode.NONE) {
            this.dragMode = DragMode.NONE;
            this.dragOffsetWithinThumb = 0;
        }
        super.mouseMovedOrUp(mouseX, mouseY, eventButton);
    }

    @Override
    protected void actionPerformed(GuiButton button) {
        if (button.id == SmartCraftConfirmButtonLayout.CANCEL_BUTTON_ID) {
            SmartCraftConfirmGuiEventHandler.OVERLAY.sendCancel();
        } else if (button.id == SmartCraftConfirmButtonLayout.RETRY_BUTTON_ID
            && SmartCraftConfirmGuiEventHandler.OVERLAY.hasData()) {
                NetworkHandler.INSTANCE.sendToServer(
                    new RequestSmartCraftActionPacket(
                        SmartCraftConfirmGuiEventHandler.OVERLAY.orderId(),
                        RequestSmartCraftActionPacket.Action.RETRY_FAILED));
            } else if (button.id == SmartCraftConfirmButtonLayout.OVERVIEW_BUTTON_ID) {
                SmartCraftConfirmGuiEventHandler.OVERLAY.selectOverview();
            } else if (button.id >= SmartCraftConfirmButtonLayout.TASK_BUTTON_BASE) {
                SmartCraftConfirmGuiEventHandler.OVERLAY
                    .selectTask(button.id - SmartCraftConfirmButtonLayout.TASK_BUTTON_BASE);
            }
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

    private int guiLeft() {
        return (this.width - SmartCraftStatusLayout.GUI_WIDTH) / 2;
    }

    private int guiTop() {
        return Math.max(SmartCraftStatusLayout.OUTER_MARGIN, (this.height - this.guiHeight()) / 2);
    }

    private int guiHeight() {
        return SmartCraftStatusLayout.guiHeight(
            this.height,
            SmartCraftConfirmGuiEventHandler.OVERLAY.getTasks()
                .size());
    }

    private void syncScheduleButtons(int guiLeft, int guiTop, int guiHeight) {
        this.removeScheduleButtons();
        List<TaskView> tasks = SmartCraftConfirmGuiEventHandler.OVERLAY.getTasks();
        if (tasks.isEmpty()) {
            return;
        }

        int visibleRows = SmartCraftStatusLayout.visibleScheduleRows(this.height, tasks.size());
        this.scheduleScroll = SmartCraftStatusLayout
            .clampScheduleScroll(this.scheduleScroll, tasks.size(), visibleRows);
        int firstTask = SmartCraftStatusLayout.firstVisibleTask(this.scheduleScroll, tasks.size(), visibleRows);
        int visibleTaskCount = SmartCraftStatusLayout.visibleTaskCount(firstTask, tasks.size(), visibleRows);
        int listY = guiTop + SmartCraftStatusLayout.SCHEDULE_BUTTON_TOP;
        for (SmartCraftScheduleButtonLayout.ButtonSpec spec : SmartCraftScheduleButtonLayout
            .buttons(guiLeft, listY, tasks, firstTask, visibleTaskCount)) {
            GuiButton button = new GuiButton(spec.id(), spec.x(), spec.y(), spec.width(), spec.height(), spec.label());
            button.enabled = spec.enabled();
            this.buttonList.add(button);
        }
    }

    private void removeScheduleButtons() {
        Iterator<GuiButton> it = this.buttonList.iterator();
        while (it.hasNext()) {
            int id = it.next().id;
            if (id == SmartCraftConfirmButtonLayout.OVERVIEW_BUTTON_ID
                || id >= SmartCraftConfirmButtonLayout.TASK_BUTTON_BASE) {
                it.remove();
            }
        }
    }

    private void drawLongAe2Background(int guiLeft, int guiTop, int guiHeight) {
        this.mc.getTextureManager()
            .bindTexture(CRAFTING_CPU_TEXTURE);
        this.drawTexturedModalRect(guiLeft, guiTop, 0, 0, SmartCraftStatusLayout.GUI_WIDTH, TEXTURE_BELOW_TOP_ROW_Y);

        int y = TEXTURE_BELOW_TOP_ROW_Y;
        int middleHeight = guiHeight - TEXTURE_BELOW_TOP_ROW_Y - TEXTURE_ABOVE_BOTTOM_ROW_Y;
        while (middleHeight > 0) {
            int height = Math.min(SECTION_HEIGHT, middleHeight);
            this.drawTexturedModalRect(
                guiLeft,
                guiTop + y,
                0,
                TEXTURE_BELOW_TOP_ROW_Y,
                SmartCraftStatusLayout.GUI_WIDTH,
                height);
            y += height;
            middleHeight -= height;
        }

        this.drawTexturedModalRect(
            guiLeft,
            guiTop + y,
            0,
            SOURCE_GUI_HEIGHT - TEXTURE_ABOVE_BOTTOM_ROW_Y,
            SmartCraftStatusLayout.GUI_WIDTH,
            TEXTURE_ABOVE_BOTTOM_ROW_Y);
    }

    private void drawSolidPanelOverlay(int guiLeft, int guiTop, int guiHeight) {
        int x1 = guiLeft + SOLID_PANEL_INSET_X;
        int x2 = guiLeft + SmartCraftStatusLayout.GUI_WIDTH - SOLID_PANEL_INSET_X;
        int y1 = guiTop + SmartCraftStatusLayout.INFO_BAR_TOP - 3;
        int y2 = guiTop + guiHeight - SOLID_PANEL_BOTTOM_GAP;
        if (y2 <= y1) {
            return;
        }
        drawRect(x1 - 1, y1 - 1, x2 + 1, y2 + 1, SOLID_PANEL_BORDER_COLOR);
        drawRect(x1, y1, x2, y2, SOLID_PANEL_BG_COLOR);
    }

    private void drawScheduleTitle(int guiLeft, int guiTop) {
        List<TaskView> tasks = SmartCraftConfirmGuiEventHandler.OVERLAY.getTasks();
        if (tasks.isEmpty()) {
            return;
        }
        int titleY = guiTop + SmartCraftStatusLayout.SCHEDULE_TITLE_TOP;
        this.fontRendererObj.drawString(
            StatCollector.translateToLocal("gui.ae2intelligentscheduling.schedule"),
            guiLeft + 8,
            titleY,
            0x404040);

        int visibleRows = SmartCraftStatusLayout.visibleScheduleRows(this.height, tasks.size());
        int maxScroll = SmartCraftStatusLayout.maxScheduleScroll(tasks.size(), visibleRows);
        if (maxScroll > 0) {
            String page = (this.scheduleScroll + 1) + "/" + (maxScroll + 1);
            this.fontRendererObj.drawString(
                page,
                guiLeft + SmartCraftStatusLayout.GUI_WIDTH - 8 - this.fontRendererObj.getStringWidth(page),
                titleY,
                0x404040);
        }
    }

    private boolean isMouseInScheduleList(int mouseX, int mouseY) {
        int guiLeft = this.guiLeft();
        int guiTop = this.guiTop();
        int top = guiTop + SmartCraftStatusLayout.SCHEDULE_BUTTON_TOP;
        int bottom = guiTop + this.guiHeight() - SmartCraftStatusLayout.ACTION_AREA_HEIGHT;
        return mouseX >= guiLeft + 6 && mouseX <= guiLeft + SmartCraftStatusLayout.GUI_WIDTH - 6
            && mouseY >= top
            && mouseY <= bottom;
    }

    private boolean isMouseInGridArea(int mouseX, int mouseY) {
        int guiLeft = this.guiLeft();
        int guiTop = this.guiTop();
        int top = guiTop + GRID_Y_OFFSET;
        int bottom = top + SmartCraftStatusLayout.GRID_ROWS * GRID_CELL_HEIGHT;
        int left = guiLeft + GRID_X_OFFSET;
        int right = left + SmartCraftStatusLayout.GRID_COLS * (GRID_CELL_WIDTH + 1);
        return mouseX >= left && mouseX < right && mouseY >= top && mouseY < bottom;
    }

    private void scrollSchedule(int delta) {
        List<TaskView> tasks = SmartCraftConfirmGuiEventHandler.OVERLAY.getTasks();
        int visibleRows = SmartCraftStatusLayout.visibleScheduleRows(this.height, tasks.size());
        this.scheduleScroll = SmartCraftStatusLayout
            .clampScheduleScroll(this.scheduleScroll + delta, tasks.size(), visibleRows);
    }

    // --- Scrollbar drawing & input ---

    private int gridScrollbarX() {
        // AE2 GuiCraftingCPU.SCROLLBAR_LEFT = 218 (relative to guiLeft); width = 12
        return this.guiLeft() + GRID_SCROLLBAR_X;
    }

    private int gridScrollbarTop() {
        // AE2 GuiCraftingCPU.SCROLLBAR_TOP = 19 (relative to guiTop)
        return this.guiTop() + GRID_SCROLLBAR_TOP_OFFSET;
    }

    private int gridScrollbarBottom() {
        // AE2 GuiCraftingCPU.SCROLLBAR_HEIGHT = 137
        return this.gridScrollbarTop() + GRID_SCROLLBAR_HEIGHT;
    }

    private int scheduleScrollbarX() {
        return this.guiLeft() + SmartCraftStatusLayout.GUI_WIDTH - SCROLLBAR_RIGHT_INSET;
    }

    private int scheduleScrollbarTop() {
        return this.guiTop() + SmartCraftStatusLayout.SCHEDULE_BUTTON_TOP;
    }

    private int scheduleScrollbarBottom() {
        return this.guiTop() + this.guiHeight() - SmartCraftStatusLayout.ACTION_AREA_HEIGHT - 2;
    }

    private int gridScrollMax() {
        int taskCount = SmartCraftConfirmGuiEventHandler.OVERLAY.getTasks()
            .size();
        return SmartCraftStatusLayout.maxGridScroll(taskCount);
    }

    private int scheduleScrollMax() {
        List<TaskView> tasks = SmartCraftConfirmGuiEventHandler.OVERLAY.getTasks();
        int visibleRows = SmartCraftStatusLayout.visibleScheduleRows(this.height, tasks.size());
        return SmartCraftStatusLayout.maxScheduleScroll(tasks.size(), visibleRows);
    }

    private void drawGridScrollbar(int guiLeft, int guiTop, int mouseX, int mouseY) {
        int taskCount = SmartCraftConfirmGuiEventHandler.OVERLAY.getTasks()
            .size();
        int totalRows = SmartCraftStatusLayout.totalGridRows(taskCount);
        int visibleRows = SmartCraftStatusLayout.GRID_ROWS;
        int scroll = SmartCraftConfirmGuiEventHandler.OVERLAY.getGridScroll();
        int maxScroll = SmartCraftStatusLayout.maxGridScroll(taskCount);
        this.drawScrollbar(
            this.gridScrollbarX(),
            this.gridScrollbarTop(),
            this.gridScrollbarBottom(),
            GRID_SCROLLBAR_WIDTH,
            scroll,
            maxScroll,
            visibleRows,
            totalRows,
            mouseX,
            mouseY);
    }

    private void drawScheduleScrollbar(int guiLeft, int guiTop, int guiHeight, int mouseX, int mouseY) {
        List<TaskView> tasks = SmartCraftConfirmGuiEventHandler.OVERLAY.getTasks();
        int visibleRows = SmartCraftStatusLayout.visibleScheduleRows(this.height, tasks.size());
        // Total schedule rows = overview row + tasks; visible rows includes the overview row.
        int totalRows = 1 + tasks.size();
        int maxScroll = SmartCraftStatusLayout.maxScheduleScroll(tasks.size(), visibleRows);
        this.drawScrollbar(
            this.scheduleScrollbarX(),
            this.scheduleScrollbarTop(),
            this.scheduleScrollbarBottom(),
            SCROLLBAR_WIDTH,
            this.scheduleScroll,
            maxScroll,
            visibleRows,
            totalRows,
            mouseX,
            mouseY);
    }

    private void drawScrollbar(int x, int top, int bottom, int width, int scroll, int maxScroll, int visibleRows,
        int totalRows, int mouseX, int mouseY) {
        int trackHeight = bottom - top;
        if (trackHeight <= 0) return;
        // Track background
        drawRect(x, top, x + width, bottom, SCROLLBAR_TRACK_COLOR);
        if (maxScroll <= 0 || totalRows <= 0) {
            return;
        }
        int thumbHeight = Math.max(SCROLLBAR_THUMB_MIN_HEIGHT, trackHeight * visibleRows / totalRows);
        thumbHeight = Math.min(thumbHeight, trackHeight);
        int thumbTravel = trackHeight - thumbHeight;
        int thumbY = top + (thumbTravel * scroll) / maxScroll;
        boolean hover = mouseX >= x && mouseX < x + width && mouseY >= thumbY && mouseY < thumbY + thumbHeight;
        int color = hover ? SCROLLBAR_THUMB_HIGHLIGHT_COLOR : SCROLLBAR_THUMB_COLOR;
        drawRect(x, thumbY, x + width, thumbY + thumbHeight, color);
    }

    private boolean tryStartScrollbarDrag(int mouseX, int mouseY, boolean grid) {
        int x = grid ? this.gridScrollbarX() : this.scheduleScrollbarX();
        int top = grid ? this.gridScrollbarTop() : this.scheduleScrollbarTop();
        int bottom = grid ? this.gridScrollbarBottom() : this.scheduleScrollbarBottom();
        int width = grid ? GRID_SCROLLBAR_WIDTH : SCROLLBAR_WIDTH;
        int trackHeight = bottom - top;
        if (trackHeight <= 0) return false;
        if (mouseX < x || mouseX >= x + width || mouseY < top || mouseY >= bottom) return false;

        int maxScroll = grid ? this.gridScrollMax() : this.scheduleScrollMax();
        if (maxScroll <= 0) {
            // Still consume the click so it does not leak into list buttons hidden under the scrollbar
            return true;
        }
        int visibleRows = grid ? SmartCraftStatusLayout.GRID_ROWS
            : SmartCraftStatusLayout.visibleScheduleRows(
                this.height,
                SmartCraftConfirmGuiEventHandler.OVERLAY.getTasks()
                    .size());
        int totalRows = grid ? SmartCraftStatusLayout.totalGridRows(
            SmartCraftConfirmGuiEventHandler.OVERLAY.getTasks()
                .size())
            : 1 + SmartCraftConfirmGuiEventHandler.OVERLAY.getTasks()
                .size();
        int thumbHeight = Math.max(SCROLLBAR_THUMB_MIN_HEIGHT, trackHeight * visibleRows / totalRows);
        thumbHeight = Math.min(thumbHeight, trackHeight);
        int thumbTravel = trackHeight - thumbHeight;
        int currentScroll = grid ? SmartCraftConfirmGuiEventHandler.OVERLAY.getGridScroll() : this.scheduleScroll;
        int thumbY = top + (thumbTravel * currentScroll) / Math.max(1, maxScroll);
        if (mouseY >= thumbY && mouseY < thumbY + thumbHeight) {
            // Clicked inside the thumb itself: keep the relative grab offset for smooth dragging.
            this.dragOffsetWithinThumb = mouseY - thumbY;
        } else {
            // Clicked on the track: jump the thumb so its center aligns with the click.
            this.dragOffsetWithinThumb = thumbHeight / 2;
        }
        this.dragMode = grid ? DragMode.GRID_THUMB : DragMode.SCHEDULE_THUMB;
        if (grid) {
            this.dragGridThumb(mouseY);
        } else {
            this.dragScheduleThumb(mouseY);
        }
        return true;
    }

    private void dragGridThumb(int mouseY) {
        int top = this.gridScrollbarTop();
        int bottom = this.gridScrollbarBottom();
        int trackHeight = bottom - top;
        if (trackHeight <= 0) return;
        int taskCount = SmartCraftConfirmGuiEventHandler.OVERLAY.getTasks()
            .size();
        int totalRows = SmartCraftStatusLayout.totalGridRows(taskCount);
        int visibleRows = SmartCraftStatusLayout.GRID_ROWS;
        int maxScroll = SmartCraftStatusLayout.maxGridScroll(taskCount);
        if (maxScroll <= 0) return;
        int thumbHeight = Math.max(SCROLLBAR_THUMB_MIN_HEIGHT, trackHeight * visibleRows / totalRows);
        thumbHeight = Math.min(thumbHeight, trackHeight);
        int thumbTravel = Math.max(1, trackHeight - thumbHeight);
        int rawY = mouseY - top - this.dragOffsetWithinThumb;
        int clampedY = Math.max(0, Math.min(thumbTravel, rawY));
        int newScroll = (int) Math.round((double) clampedY * maxScroll / thumbTravel);
        SmartCraftConfirmGuiEventHandler.OVERLAY.setGridScroll(newScroll);
    }

    private void dragScheduleThumb(int mouseY) {
        int top = this.scheduleScrollbarTop();
        int bottom = this.scheduleScrollbarBottom();
        int trackHeight = bottom - top;
        if (trackHeight <= 0) return;
        List<TaskView> tasks = SmartCraftConfirmGuiEventHandler.OVERLAY.getTasks();
        int visibleRows = SmartCraftStatusLayout.visibleScheduleRows(this.height, tasks.size());
        int totalRows = 1 + tasks.size();
        int maxScroll = SmartCraftStatusLayout.maxScheduleScroll(tasks.size(), visibleRows);
        if (maxScroll <= 0) return;
        int thumbHeight = Math.max(SCROLLBAR_THUMB_MIN_HEIGHT, trackHeight * visibleRows / totalRows);
        thumbHeight = Math.min(thumbHeight, trackHeight);
        int thumbTravel = Math.max(1, trackHeight - thumbHeight);
        int rawY = mouseY - top - this.dragOffsetWithinThumb;
        int clampedY = Math.max(0, Math.min(thumbTravel, rawY));
        int newScroll = (int) Math.round((double) clampedY * maxScroll / thumbTravel);
        this.scheduleScroll = SmartCraftStatusLayout.clampScheduleScroll(newScroll, tasks.size(), visibleRows);
    }
}
