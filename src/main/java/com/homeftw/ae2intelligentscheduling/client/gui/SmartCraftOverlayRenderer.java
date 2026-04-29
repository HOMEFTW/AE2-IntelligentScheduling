package com.homeftw.ae2intelligentscheduling.client.gui;

import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.client.renderer.entity.RenderItem;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.StatCollector;

import org.lwjgl.opengl.GL11;

import com.homeftw.ae2intelligentscheduling.network.NetworkHandler;
import com.homeftw.ae2intelligentscheduling.network.packet.RequestCpuDetailPacket;
import com.homeftw.ae2intelligentscheduling.network.packet.RequestSmartCraftActionPacket;
import com.homeftw.ae2intelligentscheduling.network.packet.SyncCpuDetailPacket;
import com.homeftw.ae2intelligentscheduling.network.packet.SyncSmartCraftOrderListPacket;
import com.homeftw.ae2intelligentscheduling.network.packet.SyncSmartCraftOrderPacket;
import com.homeftw.ae2intelligentscheduling.network.packet.SyncSmartCraftOrderPacket.TaskView;
import com.homeftw.ae2intelligentscheduling.smartcraft.model.SmartCraftStatus;

import appeng.core.localization.GuiColors;
import appeng.core.localization.GuiText;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

@SideOnly(Side.CLIENT)
public final class SmartCraftOverlayRenderer {

    // AE2 craftingreport.png layout constants
    private static final int SECTION_LENGTH = 67;
    private static final int ROW_HEIGHT = 23;
    private static final int XO = 9;
    private static final int YO = 22;
    private static final int LINE_HEIGHT = 10;

    // Schedule list constants. LIST_ROW_HEIGHT controls the schedule list row size; bumped from
    // 12 to 18 to fit the 16x16 ItemStack icon rendered by SmartCraftScheduleListWidget. The
    // visible-rows / GUI-height math in SmartCraftStatusLayout automatically tracks this constant
    // so widening rows simply gives the player a taller list area without touching layout code.
    public static final int LIST_ROW_HEIGHT = SmartCraftScheduleListWidget.ROW_HEIGHT;
    /**
     * No longer used by the schedule list (widget renders rows directly). Kept in case other code paths reference it.
     */
    public static final int LIST_BTN_WIDTH = 60;
    /**
     * No longer used by the schedule list (widget renders rows directly). Kept in case other code paths reference it.
     */
    public static final int LIST_BTN_HEIGHT = 10;

    private static final DecimalFormat COMPACT_FMT = new DecimalFormat("0.#");
    private static final long K = 1_000L, M = 1_000_000L, G = 1_000_000_000L;

    /**
     * v0.1.7 multi-order book-keeping. {@code orders} is keyed by UUID-as-string (matches
     * {@link SyncSmartCraftOrderPacket#getOrderId()}) and is a LinkedHashMap so iteration order
     * mirrors the server-side insertion order. {@code currentOrderId} is the tab the player has
     * focused; when null no tab is selected (typically because the manager just emptied out).
     */
    private final LinkedHashMap<String, SyncSmartCraftOrderPacket> orders = new LinkedHashMap<String, SyncSmartCraftOrderPacket>();
    private String currentOrderId;
    private SyncCpuDetailPacket cpuDetail;
    private String selectedCpuName; // null = overview mode
    private int selectedTaskIndex = -1; // -1 = no task selected
    private int scrollOffset = 0;

    /**
     * Single-order entry point used by {@link com.homeftw.ae2intelligentscheduling.ClientProxy}
     * when the GUI first opens. Adds the packet to the orders map AND switches the current tab
     * to it so the player sees their own order immediately. The follow-up
     * {@link #applyOrderList(SyncSmartCraftOrderListPacket)} call from the next refresh tick
     * will populate the rest of the tab bar with other players' orders.
     */
    public void update(SyncSmartCraftOrderPacket packet) {
        if (packet == null) return;
        this.orders.put(packet.getOrderId(), packet);
        this.currentOrderId = packet.getOrderId();
        this.scrollOffset = 0;
    }

    /**
     * v0.1.7 reconcile entry point: replaces the orders map with the server's authoritative list
     * so terminal-but-removed orders disappear and new orders show up. Preserves the current
     * scroll / selectedTaskIndex / cpuDetail when the focused order is still present, otherwise
     * picks a sensible fallback tab.
     */
    public void applyOrderList(SyncSmartCraftOrderListPacket packet) {
        if (packet == null) return;
        this.orders.clear();
        for (SyncSmartCraftOrderPacket entry : packet.getOrders()) {
            this.orders.put(entry.getOrderId(), entry);
        }
        // If the currentOrderId is gone, fall back to a non-terminal order owned by THIS client
        // (the client's own orders are the most likely thing the player wants to focus next), and
        // failing that, the most recently-inserted order in the list.
        if (this.currentOrderId == null || !this.orders.containsKey(this.currentOrderId)) {
            this.currentOrderId = pickFallbackOrderId();
            // Reset per-order view state because the new tab almost certainly has a different
            // task layout. Without this the scroll offset / selected task index would silently
            // point into the wrong order.
            this.scrollOffset = 0;
            this.selectedTaskIndex = -1;
            this.selectedCpuName = null;
            this.cpuDetail = null;
        }
    }

    /**
     * Switch focus to a different order. Resets per-order state (scroll / task selection / cpu
     * detail) because they are meaningless after a tab change. No-op when the requested ID is not
     * (yet) in the orders map — the next list-sync should populate it.
     */
    public void selectOrder(String orderId) {
        if (orderId == null || !this.orders.containsKey(orderId)) return;
        if (orderId.equals(this.currentOrderId)) return;
        this.currentOrderId = orderId;
        this.scrollOffset = 0;
        this.selectedTaskIndex = -1;
        this.selectedCpuName = null;
        this.cpuDetail = null;
    }

    /**
     * Currently focused order packet, or null when no tab is selected (empty manager). Internal
     * paths use this instead of the old {@code this.data} field.
     */
    private SyncSmartCraftOrderPacket currentOrder() {
        return this.currentOrderId == null ? null : this.orders.get(this.currentOrderId);
    }

    /**
     * UUID of the currently focused order as a string, or null when no tab is selected. Used by
     * {@link com.homeftw.ae2intelligentscheduling.client.gui.SmartCraftOrderTabsWidget} to render
     * the active-tab highlight.
     */
    public String currentOrderId() {
        return this.currentOrderId;
    }

    /**
     * Insertion-order list of every active order's packet, used by the tab widget to render one
     * tab per entry. Returns an unmodifiable view.
     */
    public List<SyncSmartCraftOrderPacket> tabOrders() {
        return Collections.unmodifiableList(new ArrayList<SyncSmartCraftOrderPacket>(this.orders.values()));
    }

    /**
     * Pick a fallback tab when the current one disappears. Heuristic: prefer the most recently
     * added order owned by this client (last entry in the LinkedHashMap whose ownerName matches
     * the local player), otherwise the most recently added order overall. Both fall back to null
     * when the manager is empty.
     */
    private String pickFallbackOrderId() {
        String localPlayerName = localPlayerName();
        String mostRecentOwn = null;
        String mostRecentAny = null;
        for (Map.Entry<String, SyncSmartCraftOrderPacket> e : this.orders.entrySet()) {
            mostRecentAny = e.getKey();
            if (localPlayerName != null && localPlayerName.equals(
                e.getValue()
                    .getOwnerName())) {
                mostRecentOwn = e.getKey();
            }
        }
        return mostRecentOwn != null ? mostRecentOwn : mostRecentAny;
    }

    private static String localPlayerName() {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.thePlayer == null) return null;
        return mc.thePlayer.getCommandSenderName();
    }

    public void updateCpuDetail(SyncCpuDetailPacket packet) {
        this.cpuDetail = packet;
        this.selectedCpuName = packet == null ? null : packet.getCpuName();
    }

    public void selectOverview() {
        this.selectedCpuName = null;
        this.cpuDetail = null;
        this.selectedTaskIndex = -1;
    }

    public void selectCpu(String cpuName) {
        this.selectedCpuName = cpuName;
        this.cpuDetail = null;
        if (cpuName != null && !cpuName.isEmpty()) {
            NetworkHandler.INSTANCE.sendToServer(new RequestCpuDetailPacket(cpuName));
        }
    }

    /**
     * Select a task row from the schedule list. When the task already has an AE2 CPU assigned, route to
     * the CPU detail panel (richer progress info). Otherwise, fall back to a task-only detail panel that
     * still surfaces status / layer / split / blocking reason for the queued task.
     */
    public void selectTask(int taskIndex) {
        SyncSmartCraftOrderPacket cur = currentOrder();
        if (cur == null) return;
        List<TaskView> tasks = cur.getTasks();
        if (taskIndex < 0 || taskIndex >= tasks.size()) return;

        this.selectedTaskIndex = taskIndex;
        TaskView task = tasks.get(taskIndex);
        String cpuName = task.assignedCpuName();
        if (cpuName != null && !cpuName.isEmpty()) {
            selectCpu(cpuName);
        } else {
            this.selectedCpuName = null;
            this.cpuDetail = null;
        }
    }

    public void clear() {
        this.orders.clear();
        this.currentOrderId = null;
        this.cpuDetail = null;
        this.selectedCpuName = null;
        this.selectedTaskIndex = -1;
        this.scrollOffset = 0;
    }

    public boolean hasData() {
        return currentOrder() != null;
    }

    public String orderId() {
        SyncSmartCraftOrderPacket cur = currentOrder();
        return cur == null ? null : cur.getOrderId();
    }

    public boolean isOverviewMode() {
        return this.selectedCpuName == null;
    }

    public void draw(int guiLeft, int guiTop, int xSize, int ySize, int mouseX, int mouseY) {
        if (currentOrder() == null) return;
        FontRenderer fr = Minecraft.getMinecraft().fontRenderer;

        int panelY = guiTop + SmartCraftStatusLayout.INFO_BAR_TOP;
        drawInfoBar(fr, guiLeft, panelY, xSize);

        if (this.selectedCpuName != null) {
            drawCpuDetailGrid(fr, guiLeft, guiTop, mouseX, mouseY);
        } else if (this.selectedTaskIndex >= 0) {
            drawTaskDetailGrid(fr, guiLeft, guiTop);
        } else {
            drawTaskGrid(fr, guiLeft, guiTop, mouseX, mouseY);
        }
    }

    // --- Grid drawing ---

    /**
     * Render the overview grid in the same visual style as AE2's {@code GuiCraftingCPU}:
     * <ul>
     * <li>6×3 cells, item icon anchored to the right of each cell at {@code cellX + SECTION_LENGTH - 19}.</li>
     * <li>Background color band from {@code cellY - 3} to {@code cellY + offY - 1} with
     * {@link GuiColors#CraftingCPUActive} / {@link GuiColors#CraftingCPUInactive} based on the task
     * being active vs scheduled.</li>
     * <li>Status text rendered at half scale via {@code GL11.glScaled(0.5, 0.5, 0.5)}, horizontally
     * centered to the LEFT of the icon, mirroring AE2's font layout. Reuses AE2's
     * {@link GuiText#Stored} / {@link GuiText#Crafting} / {@link GuiText#Scheduled} so the labels
     * read identically to the vanilla CPU status screen.</li>
     * </ul>
     */
    private void drawTaskGrid(FontRenderer fr, int guiLeft, int guiTop, int mouseX, int mouseY) {
        SyncSmartCraftOrderPacket cur = currentOrder();
        if (cur == null) return;
        // (v0.1.8.2) Filter DONE tasks out of the top-grid view to mirror AE2 vanilla
        // GuiCraftingCPU behaviour: once a sub-craft completes its tile vanishes from the active
        // grid (AE2 routes the result into the network and the cell is recycled). Keeping DONE
        // tasks here would clutter the player's mental model of "what is the cluster still
        // working on". The schedule list on the bottom-left panel still shows DONE tasks so the
        // player can review what has been finished.
        List<TaskView> tasks = activeGridTasks(cur);
        if (tasks.isEmpty()) return;

        List<String> hoverTooltip = null;
        int tooltipX = 0, tooltipY = 0;

        final int offY = ROW_HEIGHT;

        for (int row = 0; row < SmartCraftStatusLayout.GRID_ROWS; row++) {
            for (int col = 0; col < SmartCraftStatusLayout.GRID_COLS; col++) {
                int idx = (this.scrollOffset + row) * SmartCraftStatusLayout.GRID_COLS + col;
                if (idx >= tasks.size()) break;

                TaskView task = tasks.get(idx);
                int cellX = guiLeft + XO + col * (SECTION_LENGTH + 1);
                int cellY = guiTop + YO + row * offY;

                // AE2-style status band: covers the full SECTION_LENGTH from cellY-3 to cellY+offY-1.
                int bg = cellBg(task.status());
                if (bg != 0) {
                    Gui.drawRect(cellX, cellY - 3, cellX + SECTION_LENGTH, cellY + offY - 1, bg);
                }

                ItemStack stack = task.itemStack();
                if (stack != null) {
                    renderItem(stack, cellX + SECTION_LENGTH - 19, cellY);
                }

                String statusText = aeStatusLabel(task) + ": " + compact(task.amount());
                int statusColor = aeStatusColor(task.status());

                // AE2's pattern: push matrix, scale 0.5x, draw with doubled coordinates so the text
                // renders at half size. The text is centered horizontally over the area to the left
                // of the 16x16 item icon.
                GL11.glPushMatrix();
                GL11.glScaled(0.5, 0.5, 0.5);
                int textW = fr.getStringWidth(statusText);
                int textCenterScaled = (cellX + (SECTION_LENGTH - 19) / 2) * 2;
                int textY = (cellY + offY / 2 - 2) * 2;
                fr.drawString(statusText, textCenterScaled - textW / 2, textY, statusColor);
                GL11.glPopMatrix();

                if (mouseX >= cellX && mouseX < cellX + SECTION_LENGTH
                    && mouseY >= cellY - 3
                    && mouseY < cellY + offY - 1) {
                    hoverTooltip = buildTaskTooltip(task);
                    tooltipX = mouseX;
                    tooltipY = mouseY;
                }
            }
        }
        if (hoverTooltip != null) drawTooltipLines(hoverTooltip, tooltipX, tooltipY, fr);
    }

    /**
     * Map our task status to AE2's vanilla CPU status labels (Stored / Crafting / Scheduled) so the
     * cells read like a normal AE2 crafting status screen.
     */
    private static String aeStatusLabel(TaskView task) {
        SmartCraftStatus s = task.status();
        if (s == null) return "";
        switch (s) {
            case RUNNING:
            case SUBMITTING:
            case VERIFYING_OUTPUT:
                return GuiText.Crafting.getLocal();
            case PENDING:
            case QUEUED:
            case WAITING_CPU:
            case PAUSED:
                return GuiText.Scheduled.getLocal();
            case DONE:
            case COMPLETED:
                return GuiText.Stored.getLocal();
            case FAILED:
            case CANCELLED:
            default:
                return s.name();
        }
    }

    /**
     * Map our task status to AE2's CraftingCPU* color palette, falling back to our cell-status palette
     * for failure / cancellation states that AE2 doesn't have a direct counterpart for.
     */
    private static int aeStatusColor(SmartCraftStatus s) {
        if (s == null) return GuiColors.DefaultBlack.getColor();
        switch (s) {
            case RUNNING:
            case SUBMITTING:
            case VERIFYING_OUTPUT:
                return GuiColors.CraftingCPUAmount.getColor();
            case DONE:
            case COMPLETED:
                return GuiColors.CraftingCPUStored.getColor();
            case PENDING:
            case QUEUED:
            case WAITING_CPU:
            case PAUSED:
                return GuiColors.CraftingCPUScheduled.getColor();
            case FAILED:
                return GuiColors.CellStatusRed.getColor();
            case CANCELLED:
                return GuiColors.SearchboxText.getColor();
            default:
                return GuiColors.DefaultBlack.getColor();
        }
    }

    private void drawCpuDetailGrid(FontRenderer fr, int guiLeft, int guiTop, int mouseX, int mouseY) {
        // Show CPU detail in the grid area: output item + progress bar
        int cellX = guiLeft + XO;
        int cellY = guiTop + YO;

        // Dim all cells across the full grid (6 rows, 3 cols)
        Gui.drawRect(
            cellX,
            cellY - 1,
            cellX + SmartCraftStatusLayout.GRID_COLS * (SECTION_LENGTH + 1),
            cellY + SmartCraftStatusLayout.GRID_ROWS * ROW_HEIGHT - 2,
            0x88000000);

        if (this.cpuDetail == null) {
            fr.drawString(tr("loading"), cellX + 4, cellY + 4, 0xAAAAAA);
            return;
        }

        // Item icon
        ItemStack out = this.cpuDetail.getFinalOutput();
        if (out != null) {
            net.minecraft.client.renderer.RenderHelper.enableGUIStandardItemLighting();
            net.minecraft.client.renderer.entity.RenderItem renderItem = new net.minecraft.client.renderer.entity.RenderItem();
            renderItem.renderItemAndEffectIntoGUI(
                Minecraft.getMinecraft().fontRenderer,
                Minecraft.getMinecraft()
                    .getTextureManager(),
                out,
                cellX + 2,
                cellY + 2);
            net.minecraft.client.renderer.RenderHelper.disableStandardItemLighting();
        }

        // CPU name
        fr.drawString(this.cpuDetail.getCpuName(), cellX + 22, cellY + 2, GuiColors.DefaultBlack.getColor());

        // Progress
        long start = this.cpuDetail.getStartCount();
        long remaining = this.cpuDetail.getRemainingCount();
        long done = start - remaining;
        String progress = compact(done) + "/" + compact(start);
        fr.drawString(progress, cellX + 22, cellY + 12, GuiColors.CellStatusBlue.getColor());

        // Progress bar (width spans the full grid width minus 4px padding)
        int barW = SmartCraftStatusLayout.GRID_COLS * (SECTION_LENGTH + 1) - 4;
        int filled = start > 0 ? (int) (barW * done / start) : 0;
        Gui.drawRect(cellX + 2, cellY + 28, cellX + 2 + barW, cellY + 34, 0xFF333333);
        if (filled > 0) Gui.drawRect(cellX + 2, cellY + 28, cellX + 2 + filled, cellY + 34, 0xFF00AA00);

        // Elapsed time
        long secs = this.cpuDetail.getElapsedTime() / 1000;
        fr.drawString(tr("time") + ": " + secs + "s", cellX + 4, cellY + 40, GuiColors.SearchboxText.getColor());

        // Optional: show remaining count below the elapsed time when there is room (6-row grid).
        fr.drawString(
            tr("amount") + ": " + compact(remaining),
            cellX + 4,
            cellY + 52,
            GuiColors.DefaultBlack.getColor());
    }

    /**
     * Render the task-only detail panel for queued / waiting tasks that have not been bound to an AE2 CPU
     * yet. Surfaces requestKey / status / layer / split / amount / blocking reason so the player still
     * gets actionable feedback when clicking a layer button.
     */
    private void drawTaskDetailGrid(FontRenderer fr, int guiLeft, int guiTop) {
        SyncSmartCraftOrderPacket cur = currentOrder();
        if (cur == null) return;
        List<TaskView> tasks = cur.getTasks();
        if (tasks.isEmpty() || this.selectedTaskIndex < 0 || this.selectedTaskIndex >= tasks.size()) {
            return;
        }
        TaskView task = tasks.get(this.selectedTaskIndex);

        int cellX = guiLeft + XO;
        int cellY = guiTop + YO;
        int gridWidth = SmartCraftStatusLayout.GRID_COLS * (SECTION_LENGTH + 1);
        int gridHeight = SmartCraftStatusLayout.GRID_ROWS * ROW_HEIGHT - 2;

        // Dim background spanning the entire 6-row grid so the detail panel "adapts" to the new size.
        Gui.drawRect(cellX, cellY - 1, cellX + gridWidth, cellY + gridHeight, 0x88000000);

        ItemStack stack = task.itemStack();
        if (stack != null) {
            renderItem(stack, cellX + 4, cellY + 4);
        }

        String header = stack != null ? stack.getDisplayName() : task.requestKeyId();
        fr.drawString(header, cellX + 24, cellY + 4, 0xFFFFFF);

        SmartCraftStatus parsed = task.status();
        fr.drawString(
            tr("status") + ": " + (parsed == null ? "-" : parsed.name()),
            cellX + 24,
            cellY + 14,
            statusColor(parsed));

        // Separator under the icon header
        Gui.drawRect(cellX + 4, cellY + 26, cellX + gridWidth - 4, cellY + 27, 0x55FFFFFF);

        fr.drawString(
            tr("currentLayer") + ": "
                + task.depth()
                + "      "
                + tr("split")
                + ": "
                + task.splitIndex()
                + "/"
                + task.splitCount(),
            cellX + 4,
            cellY + 32,
            0xCCCCCC);

        fr.drawString(tr("amount") + ": " + compact(task.amount()), cellX + 4, cellY + 44, 0xCCCCCC);

        int infoY = cellY + 58;
        // (v0.1.8.4 G11) Surface failure history before CPU / blocking-reason so the player sees
        // "this task has failed N times so far" front-and-center on the detail panel. Only drawn
        // when failureCount > 0 to avoid noise on healthy tasks. Red so it stands out from the
        // blue cpu line and orange blocking-reason line.
        if (task.failureCount() > 0) {
            fr.drawString(
                tr("failedBefore") + "  " + tr("failureCount") + ": " + task.failureCount(),
                cellX + 4,
                infoY,
                GuiColors.CellStatusRed.getColor());
            infoY += 12;
        }

        String cpuName = task.assignedCpuName();
        if (cpuName != null && !cpuName.isEmpty()) {
            fr.drawString(tr("cpu") + ": " + cpuName, cellX + 4, infoY, GuiColors.CellStatusBlue.getColor());
            infoY += 12;
        }

        String blocking = task.blockingReason();
        if (blocking != null && !blocking.isEmpty()) {
            // Wrap blocking reason across multiple lines so it stays inside the dim panel
            int maxWidth = gridWidth - 8;
            String prefix = tr("blockingReason") + ": ";
            String message = prefix + blocking;
            for (String line : fr.listFormattedStringToWidth(message, maxWidth)) {
                if (infoY + 10 > cellY + gridHeight - 4) break;
                fr.drawString(line, cellX + 4, infoY, GuiColors.CellStatusOrange.getColor());
                infoY += 10;
            }
        }
    }

    // --- Info bar ---

    private void drawInfoBar(FontRenderer fr, int guiLeft, int panelY, int xSize) {
        SyncSmartCraftOrderPacket cur = currentOrder();
        if (cur == null) return;
        String status = cur.getStatus();
        SmartCraftStatus parsed = parseStatus(status);

        String left = tr("scale") + ": "
            + cur.getOrderScale()
            + "  "
            + tr("currentLayer")
            + ": "
            + (cur.getTotalLayers() <= 0 ? "-" : (cur.getCurrentLayer() + 1) + "/" + cur.getTotalLayers());
        fr.drawString(left, guiLeft + 8, panelY, GuiColors.DefaultBlack.getColor());
        fr.drawString(tr("status") + ": " + status, guiLeft + 8, panelY + LINE_HEIGHT, statusColor(parsed));

        String right = tr("taskCount") + ": "
            + cur.getTasks()
                .size()
            + "  "
            + tr("amount")
            + ": "
            + compact(cur.getTargetAmount());
        fr.drawString(right, guiLeft + xSize - 8 - fr.getStringWidth(right), panelY, GuiColors.DefaultBlack.getColor());

        // (v0.1.8.3 G10) Scheduling stats: per-status counters appended to the right of the
        // status line. Bucketing follows the same logical grouping as the cell-bg / status-dot
        // helpers so a player can correlate the row colors with the numeric stats:
        //   - Crafting (blue): RUNNING / SUBMITTING / VERIFYING_OUTPUT
        //   - Done (green):    DONE (task-level terminal success)
        //   - Pending (gray):  PENDING / QUEUED / WAITING_CPU / PAUSED
        //   - Failed (red):    FAILED
        //   CANCELLED is intentionally excluded \u2014 it represents player-initiated removal, not a
        //   scheduling outcome to be tallied here. COMPLETED is order-level, not task-level, so
        //   it can never appear on a TaskView and is also skipped.
        int crafting = 0, completed = 0, pending = 0, failed = 0;
        for (TaskView t : cur.getTasks()) {
            SmartCraftStatus s = t.status();
            if (s == null) continue;
            switch (s) {
                case RUNNING:
                case SUBMITTING:
                case VERIFYING_OUTPUT:
                    crafting++;
                    break;
                case DONE:
                    completed++;
                    break;
                case PENDING:
                case QUEUED:
                case WAITING_CPU:
                case PAUSED:
                    pending++;
                    break;
                case FAILED:
                    failed++;
                    break;
                case CANCELLED:
                case COMPLETED:
                case ANALYZING:
                default:
                    // Don't bucket player-cancelled tasks or transient analysis states; they
                    // would skew the "what is the cluster doing" mental model the stats serve.
                    break;
            }
        }
        // \u00a7-color codes match cellBg() / statusDotColor() so the colors read consistently with
        // the grid tiles. \u00a7-codes are stripped by FontRenderer#getStringWidth so width math is
        // accurate without manual sanitisation.
        String stats = EnumChatFormatting.AQUA + tr("statsCrafting") + ":" + crafting
            + "  " + EnumChatFormatting.GREEN + tr("statsCompleted") + ":" + completed
            + "  " + EnumChatFormatting.GRAY + tr("statsPending") + ":" + pending
            + "  " + EnumChatFormatting.RED + tr("statsFailed") + ":" + failed;
        int statsW = fr.getStringWidth(stats);
        fr.drawString(stats, guiLeft + xSize - 8 - statsW, panelY + LINE_HEIGHT, GuiColors.DefaultBlack.getColor());

        if (this.selectedCpuName != null) {
            String viewing = tr("viewing") + ": " + this.selectedCpuName;
            fr.drawString(viewing, guiLeft + 8, panelY + LINE_HEIGHT * 2, GuiColors.CellStatusBlue.getColor());
        }
    }

    // --- Schedule list (drawn by event handler after button injection) ---

    /** Returns the Y position where the schedule list starts (below the GUI). */
    public int scheduleListY(int guiTop, int ySize) {
        return guiTop + ySize + 4;
    }

    /**
     * Returns the currently selected task index, or {@code -1} if no task is selected.
     * Used by {@link SmartCraftScheduleListWidget} to highlight the matching row.
     */
    public int getSelectedTaskIndex() {
        return this.selectedTaskIndex;
    }

    // --- Tooltip helpers ---

    /**
     * Build the full task tooltip shown when hovering a cell in our own status grid. Three sections:
     * <ol>
     * <li>Vanilla item tooltip (or requestKeyId fallback).</li>
     * <li>Smart craft section: amount + status (color-coded) + layer/split.</li>
     * <li>Optional CPU / blocking reason rows.</li>
     * </ol>
     * Uses {@link EnumChatFormatting} so the multi-line tooltip reads cleanly: titles in white, values in
     * light gray, status colored by state, blocking reason in orange.
     */
    @SuppressWarnings("unchecked")
    private List<String> buildTaskTooltip(TaskView task) {
        List<String> lines = new ArrayList<String>();
        ItemStack is = task.itemStack();
        if (is != null) {
            lines.addAll(
                is.getTooltip(
                    Minecraft.getMinecraft().thePlayer,
                    Minecraft.getMinecraft().gameSettings.advancedItemTooltips));
        } else {
            lines.add(EnumChatFormatting.WHITE + task.requestKeyId());
        }

        lines.add("");
        lines.add(EnumChatFormatting.GOLD + tr("smartCraftHintHeader"));
        appendSmartCraftLines(lines, task);
        return lines;
    }

    /**
     * Append the smart-craft-specific lines (amount / status / layer / CPU / blocking) shared between
     * our own task tooltip and the supplementary tooltip we draw on top of AE2 {@code GuiCraftingStatus}.
     */
    private void appendSmartCraftLines(List<String> lines, TaskView task) {
        lines.add(EnumChatFormatting.GRAY + tr("amount") + ": " + EnumChatFormatting.WHITE + compact(task.amount()));

        SmartCraftStatus parsed = task.status();
        EnumChatFormatting statusColor = chatColorForStatus(parsed);
        String statusName = parsed == null ? "-" : parsed.name();
        lines.add(EnumChatFormatting.GRAY + tr("status") + ": " + statusColor + statusName);

        lines.add(
            EnumChatFormatting.GRAY + tr("currentLayer")
                + ": "
                + EnumChatFormatting.WHITE
                + task.depth()
                + EnumChatFormatting.GRAY
                + "   "
                + tr("split")
                + ": "
                + EnumChatFormatting.WHITE
                + task.splitIndex()
                + "/"
                + task.splitCount());

        String cpuName = task.assignedCpuName();
        if (cpuName != null && !cpuName.isEmpty()) {
            lines.add(EnumChatFormatting.GRAY + tr("cpu") + ": " + EnumChatFormatting.AQUA + cpuName);
        }

        // (v0.1.8.4 G11) Tooltip variant of the failure-history line. Mirrors the inline detail
        // panel rendering so hovering a row in the schedule list / grid shows the same info
        // without the player having to click into detail mode.
        if (task.failureCount() > 0) {
            lines.add(
                EnumChatFormatting.GRAY + tr("failureCount")
                    + ": "
                    + EnumChatFormatting.RED
                    + task.failureCount());
        }

        String blocking = task.blockingReason();
        if (blocking != null && !blocking.isEmpty()) {
            lines.add(EnumChatFormatting.GRAY + tr("blockingReason") + ": " + EnumChatFormatting.GOLD + blocking);
        }
    }

    /**
     * Build a compact tooltip used when hovering an item on AE2's {@code GuiCraftingStatus} that ALSO
     * happens to belong to our smart craft order. Single header line + the same smart-craft lines, so
     * the AE2 native tooltip and our supplementary tooltip together give the player a complete picture
     * without duplicating the item name / vanilla tooltip.
     */
    public List<String> buildAe2SupplementHintLines(TaskView task) {
        List<String> lines = new ArrayList<String>();
        lines.add(EnumChatFormatting.GOLD + tr("smartCraftHintHeader"));
        appendSmartCraftLines(lines, task);
        return lines;
    }

    /**
     * Return the {@link TaskView} that produces an item matching {@code stack}, or {@code null} when
     * the hovered AE2 item does not belong to any of our scheduled tasks. Matching is done by item +
     * meta + NBT to align with AE2's own {@code IAEItemStack.equals}.
     */
    public TaskView findMatchingTask(ItemStack stack) {
        SyncSmartCraftOrderPacket cur = currentOrder();
        if (stack == null || cur == null) return null;
        for (TaskView task : cur.getTasks()) {
            ItemStack candidate = task.itemStack();
            if (candidate == null) continue;
            if (stacksMatch(candidate, stack)) {
                return task;
            }
        }
        return null;
    }

    private static boolean stacksMatch(ItemStack a, ItemStack b) {
        if (a == null || b == null) return false;
        if (a.getItem() != b.getItem()) return false;
        if (a.getItemDamage() != b.getItemDamage()) return false;
        return ItemStack.areItemStackTagsEqual(a, b);
    }

    private static EnumChatFormatting chatColorForStatus(SmartCraftStatus s) {
        if (s == null) return EnumChatFormatting.WHITE;
        switch (s) {
            case RUNNING:
            case SUBMITTING:
            case VERIFYING_OUTPUT:
                return EnumChatFormatting.AQUA;
            case PENDING:
            case QUEUED:
            case WAITING_CPU:
                return EnumChatFormatting.YELLOW;
            case PAUSED:
                return EnumChatFormatting.GOLD;
            case DONE:
            case COMPLETED:
                return EnumChatFormatting.GREEN;
            case FAILED:
                return EnumChatFormatting.RED;
            case CANCELLED:
                return EnumChatFormatting.DARK_GRAY;
            default:
                return EnumChatFormatting.WHITE;
        }
    }

    private void drawTooltipLines(List<String> lines, int x, int y, FontRenderer fr) {
        if (lines.isEmpty()) return;
        int maxW = 0;
        for (String l : lines) maxW = Math.max(maxW, fr.getStringWidth(l));
        int bx = x + 12, by = y - 12;
        Gui.drawRect(bx - 3, by - 3, bx + maxW + 3, by + lines.size() * 10 + 3, 0xC0000000);
        for (int i = 0; i < lines.size(); i++) fr.drawString(lines.get(i), bx, by + i * 10, 0xFFFFFF);
    }

    /**
     * Public variant of {@link #drawTooltipLines} used by {@link SmartCraftConfirmGuiEventHandler} to
     * paint the supplementary tooltip on top of AE2's own GUI without exposing the renderer internals.
     */
    public void drawSupplementaryTooltip(List<String> lines, int x, int y) {
        if (lines == null || lines.isEmpty()) return;
        drawTooltipLines(lines, x, y, Minecraft.getMinecraft().fontRenderer);
    }

    private static void renderItem(ItemStack stack, int x, int y) {
        RenderHelper.enableGUIStandardItemLighting();
        RenderItem renderItem = new RenderItem();
        Minecraft minecraft = Minecraft.getMinecraft();
        renderItem.renderItemAndEffectIntoGUI(minecraft.fontRenderer, minecraft.getTextureManager(), stack, x, y);
        RenderHelper.disableStandardItemLighting();
    }

    // --- Actions ---

    public void scroll(int delta) {
        SyncSmartCraftOrderPacket cur = currentOrder();
        if (cur == null) return;
        // (v0.1.8.2) Grid scroll bounds derive from the FILTERED grid task list (DONE-excluded)
        // because the grid only renders those rows. Using full task count here would let the
        // player scroll past the end into empty rows.
        int taskCount = activeGridTasks(cur).size();
        this.scrollOffset = SmartCraftStatusLayout.clampGridScroll(this.scrollOffset + delta, taskCount);
    }

    /** Returns the current top-grid scroll offset (in rows). */
    public int getGridScroll() {
        return this.scrollOffset;
    }

    /** Set the top-grid scroll offset (in rows), clamped to the valid range. */
    public int setGridScroll(int scroll) {
        SyncSmartCraftOrderPacket cur = currentOrder();
        if (cur == null) {
            this.scrollOffset = 0;
            return 0;
        }
        // (v0.1.8.2) Same DONE-filtering rationale as #scroll: the grid only knows about the
        // visible (non-DONE) rows, so the scrollbar drag math must clamp against that count.
        this.scrollOffset = SmartCraftStatusLayout.clampGridScroll(
            scroll,
            activeGridTasks(cur).size());
        return this.scrollOffset;
    }

    public void sendCancel() {
        SyncSmartCraftOrderPacket cur = currentOrder();
        if (cur == null) return;
        NetworkHandler.INSTANCE.sendToServer(
            new RequestSmartCraftActionPacket(cur.getOrderId(), RequestSmartCraftActionPacket.Action.CANCEL_ORDER));
    }

    /**
     * Soft-cancel the current order: lets RUNNING crafts finish so the AE2 storage clusters'
     * intermediate materials aren't orphaned, while preventing any new crafts from starting.
     * Triggered by Shift+clicking the cancel button (Enhancement-F).
     */
    public void sendSoftCancel() {
        SyncSmartCraftOrderPacket cur = currentOrder();
        if (cur == null) return;
        NetworkHandler.INSTANCE.sendToServer(
            new RequestSmartCraftActionPacket(
                cur.getOrderId(),
                RequestSmartCraftActionPacket.Action.CANCEL_ORDER_SOFT));
    }

    /**
     * Used by GuiSmartCraftStatus.updateScreen as the 1-second refresh heartbeat. v0.1.7 ignores
     * the focused-order check on purpose: even when no tab is focused (empty manager) we still
     * want to ask the server for the latest list, otherwise a player who entered with no orders
     * would never see new tabs appear. Routes through the catch-all RequestOrderStatusPacket
     * which the server now answers with the full list packet.
     */
    public void sendRefresh() {
        NetworkHandler.INSTANCE
            .sendToServer(new com.homeftw.ae2intelligentscheduling.network.packet.RequestOrderStatusPacket());
    }

    /**
     * Resurrect every FAILED / CANCELLED task back to PENDING and bump the order to QUEUED so the
     * scheduler picks the order up on the next tick. The server-side handler is the single
     * authority on which task statuses count as retriable; this client method is just the trigger.
     */
    public void sendRetry() {
        SyncSmartCraftOrderPacket cur = currentOrder();
        if (cur == null) return;
        NetworkHandler.INSTANCE.sendToServer(
            new RequestSmartCraftActionPacket(cur.getOrderId(), RequestSmartCraftActionPacket.Action.RETRY_FAILED));
    }

    /** Returns all tasks for the schedule list, scoped to the focused order. */
    public List<TaskView> getTasks() {
        SyncSmartCraftOrderPacket cur = currentOrder();
        return cur == null ? new ArrayList<TaskView>() : cur.getTasks();
    }

    /**
     * (v0.1.8.2) Returns the count of tasks currently visible in the top-grid view (i.e. all
     * tasks except those in {@link SmartCraftStatus#DONE}). The schedule scrollbar / hit-test
     * helpers in {@code GuiSmartCraftStatus} use this so dragging never overshoots into empty
     * rows after some tasks have completed.
     */
    public int getGridTaskCount() {
        SyncSmartCraftOrderPacket cur = currentOrder();
        if (cur == null) return 0;
        int n = 0;
        for (TaskView t : cur.getTasks()) {
            if (t.status() != SmartCraftStatus.DONE) n++;
        }
        return n;
    }

    /**
     * (v0.1.8.2) Internal helper for grid rendering / scroll math. Builds a fresh list each call
     * because task completion happens on the server and we don't have a structural change
     * notification (we'd have to compare across packet refreshes). With typical task counts
     * (< 100) and 60 fps × maybe 3 calls per frame this is well under a microsecond per call —
     * not worth caching given how rarely the result is structurally needed.
     */
    private static List<TaskView> activeGridTasks(SyncSmartCraftOrderPacket cur) {
        List<TaskView> all = cur.getTasks();
        List<TaskView> out = new ArrayList<TaskView>(all.size());
        for (TaskView t : all) {
            if (t.status() != SmartCraftStatus.DONE) {
                out.add(t);
            }
        }
        return out;
    }

    /**
     * True if there is at least one task in {@link SmartCraftStatus#FAILED} or
     * {@link SmartCraftStatus#CANCELLED} state — the only states that "Retry Failed" can revive.
     * Used by the GUI to grey out the button when no retriable tasks exist.
     */
    public boolean hasRetriableTasks() {
        SyncSmartCraftOrderPacket cur = currentOrder();
        if (cur == null) return false;
        for (TaskView task : cur.getTasks()) {
            SmartCraftStatus s = task.status();
            if (s == SmartCraftStatus.FAILED || s == SmartCraftStatus.CANCELLED) {
                return true;
            }
        }
        return false;
    }

    /**
     * True if the order still has at least one non-terminal task — i.e. there is something left to
     * cancel. When all tasks are DONE / COMPLETED / FAILED / CANCELLED the cancel button becomes a
     * no-op so we grey it out.
     */
    public boolean isOrderActive() {
        SyncSmartCraftOrderPacket cur = currentOrder();
        if (cur == null) return false;
        for (TaskView task : cur.getTasks()) {
            SmartCraftStatus s = task.status();
            if (s != null && !s.isTerminalTaskState()) {
                return true;
            }
        }
        return false;
    }

    // --- Color helpers ---

    private SmartCraftStatus parseStatus(String s) {
        try {
            return SmartCraftStatus.valueOf(s);
        } catch (IllegalArgumentException e) {
            return SmartCraftStatus.PENDING;
        }
    }

    private int statusColor(SmartCraftStatus s) {
        if (s == null) return GuiColors.DefaultBlack.getColor();
        switch (s) {
            case FAILED:
                return GuiColors.CellStatusRed.getColor();
            case WAITING_CPU:
            case PAUSED:
                return GuiColors.CellStatusOrange.getColor();
            case RUNNING:
            case SUBMITTING:
            case VERIFYING_OUTPUT:
                return GuiColors.CellStatusBlue.getColor();
            case DONE:
            case COMPLETED:
                return GuiColors.CellStatusGreen.getColor();
            case CANCELLED:
                return GuiColors.SearchboxText.getColor();
            default:
                return GuiColors.DefaultBlack.getColor();
        }
    }

    /**
     * Cell background band, mirroring AE2's coloring rules in {@code GuiCraftingCPU}: active tasks
     * (currently being crafted) use {@link GuiColors#CraftingCPUActive}; scheduled / waiting tasks use
     * {@link GuiColors#CraftingCPUInactive}; failure overlays a translucent red so the player can spot
     * them at a glance.
     */
    private int cellBg(SmartCraftStatus s) {
        if (s == null) return 0;
        switch (s) {
            case RUNNING:
            case SUBMITTING:
            case VERIFYING_OUTPUT:
                return GuiColors.CraftingCPUActive.getColor();
            case PENDING:
            case QUEUED:
            case WAITING_CPU:
            case PAUSED:
                return GuiColors.CraftingCPUInactive.getColor();
            case FAILED:
                return 0x55FF3030;
            default:
                return 0;
        }
    }

    private String compact(long v) {
        long a = Math.abs(v);
        if (a >= G) return COMPACT_FMT.format((double) v / G) + "G";
        if (a >= M) return COMPACT_FMT.format((double) v / M) + "M";
        if (a >= K) return COMPACT_FMT.format((double) v / K) + "K";
        return String.valueOf(v);
    }

    private static String tr(String key) {
        return StatCollector.translateToLocal("gui.ae2intelligentscheduling." + key);
    }
}
