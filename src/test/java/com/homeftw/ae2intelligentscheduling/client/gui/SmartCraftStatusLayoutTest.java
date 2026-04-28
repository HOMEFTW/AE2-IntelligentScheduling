package com.homeftw.ae2intelligentscheduling.client.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SmartCraftStatusLayoutTest {

    @Test
    void gui_height_stays_inside_normal_screen_margin() {
        int height = SmartCraftStatusLayout.guiHeight(480, 27);

        assertTrue(height <= 480 - SmartCraftStatusLayout.OUTER_MARGIN * 2);
        assertTrue(height >= SmartCraftStatusLayout.TOP_SECTION_HEIGHT);
    }

    @Test
    void gui_height_preserves_internal_schedule_space_on_tiny_screens() {
        int height = SmartCraftStatusLayout.guiHeight(260, 27);

        assertTrue(
            height >= SmartCraftStatusLayout.SCHEDULE_BUTTON_TOP + SmartCraftOverlayRenderer.LIST_ROW_HEIGHT
                + SmartCraftStatusLayout.ACTION_AREA_HEIGHT);
    }

    @Test
    void schedule_scroll_is_limited_by_visible_task_rows() {
        int visibleRows = 6;

        assertEquals(22, SmartCraftStatusLayout.maxScheduleScroll(27, visibleRows));
        assertEquals(22, SmartCraftStatusLayout.clampScheduleScroll(99, 27, visibleRows));
        assertEquals(0, SmartCraftStatusLayout.clampScheduleScroll(-4, 27, visibleRows));
    }

    @Test
    void visible_task_count_excludes_overview_row() {
        assertEquals(5, SmartCraftStatusLayout.visibleTaskCount(0, 27, 6));
        assertEquals(2, SmartCraftStatusLayout.visibleTaskCount(25, 27, 6));
    }

    @Test
    void visible_schedule_rows_is_capped_so_list_area_stays_compact() {
        // Even on a huge screen with many tasks the schedule list must not grow past
        // MAX_VISIBLE_TASK_ROWS + 1 (overview row).
        int rows = SmartCraftStatusLayout.visibleScheduleRows(2000, 200);
        assertTrue(rows <= SmartCraftStatusLayout.MAX_VISIBLE_TASK_ROWS + 1, "schedule list must not exceed cap");
    }

    @Test
    void grid_scroll_helpers_clamp_to_visible_task_grid() {
        // 6×3 grid = 18 cells. With 27 tasks we need 9 rows total, so maxGridScroll = 9 - 6 = 3.
        assertEquals(9, SmartCraftStatusLayout.totalGridRows(27));
        assertEquals(3, SmartCraftStatusLayout.maxGridScroll(27));
        assertEquals(3, SmartCraftStatusLayout.clampGridScroll(99, 27));
        assertEquals(0, SmartCraftStatusLayout.clampGridScroll(-7, 27));
    }

    @Test
    void grid_fits_all_tasks_when_total_rows_below_grid_capacity() {
        // 6×3 = 18 cells, so 18 tasks fit on a single page (no scroll).
        assertEquals(0, SmartCraftStatusLayout.maxGridScroll(18));
        assertEquals(0, SmartCraftStatusLayout.maxGridScroll(0));
    }
}
