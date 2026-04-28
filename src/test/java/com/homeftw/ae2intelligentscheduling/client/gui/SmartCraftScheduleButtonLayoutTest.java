package com.homeftw.ae2intelligentscheduling.client.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.homeftw.ae2intelligentscheduling.network.packet.SyncSmartCraftOrderPacket.TaskView;
import com.homeftw.ae2intelligentscheduling.smartcraft.model.SmartCraftStatus;

class SmartCraftScheduleButtonLayoutTest {

    @Test
    void first_button_is_overview_button() {
        List<SmartCraftScheduleButtonLayout.ButtonSpec> buttons = SmartCraftScheduleButtonLayout
            .buttons(10, 220, Arrays.asList(task("mod:item", 0, "CPU A")));

        assertEquals(
            SmartCraftConfirmButtonLayout.OVERVIEW_BUTTON_ID,
            buttons.get(0)
                .id());
        assertEquals(
            18,
            buttons.get(0)
                .x());
        assertEquals(
            220,
            buttons.get(0)
                .y());
        assertTrue(
            buttons.get(0)
                .enabled());
    }

    @Test
    void task_buttons_keep_task_index_in_button_id() {
        List<SmartCraftScheduleButtonLayout.ButtonSpec> buttons = SmartCraftScheduleButtonLayout
            .buttons(10, 220, Arrays.asList(task("mod:first", 0, "CPU A"), task("mod:second", 0, "CPU B")));

        assertEquals(
            SmartCraftConfirmButtonLayout.TASK_BUTTON_BASE,
            buttons.get(1)
                .id());
        assertEquals(
            SmartCraftConfirmButtonLayout.TASK_BUTTON_BASE + 1,
            buttons.get(2)
                .id());
    }

    @Test
    void visible_task_slice_keeps_original_task_index_in_button_id() {
        List<SmartCraftScheduleButtonLayout.ButtonSpec> buttons = SmartCraftScheduleButtonLayout.buttons(
            10,
            220,
            Arrays.asList(task("mod:first", 0, "CPU A"), task("mod:second", 0, "CPU B"), task("mod:third", 0, "CPU C")),
            1,
            1);

        assertEquals(2, buttons.size());
        assertEquals(
            SmartCraftConfirmButtonLayout.TASK_BUTTON_BASE + 1,
            buttons.get(1)
                .id());
    }

    @Test
    void task_button_is_always_enabled_so_player_can_open_task_detail() {
        // Even without an assigned AE2 CPU the button must stay clickable: the click is routed to a
        // task-only detail panel that surfaces status / layer / split / blocking reason.
        List<SmartCraftScheduleButtonLayout.ButtonSpec> buttonsNoCpu = SmartCraftScheduleButtonLayout
            .buttons(10, 220, Arrays.asList(task("mod:item", 0, "")));
        List<SmartCraftScheduleButtonLayout.ButtonSpec> buttonsWithCpu = SmartCraftScheduleButtonLayout
            .buttons(10, 220, Arrays.asList(task("mod:item", 0, "CPU A")));

        assertTrue(
            buttonsNoCpu.get(1)
                .enabled(),
            "Task button must remain clickable even when no CPU is assigned yet");
        assertTrue(
            buttonsWithCpu.get(1)
                .enabled(),
            "Task button must remain clickable when a CPU is assigned");
    }

    private static TaskView task(String requestKeyId, int depth, String cpuName) {
        return new TaskView(requestKeyId, 1, depth, 1, 1, SmartCraftStatus.RUNNING, "", "SUBMITTED", cpuName, null);
    }
}
