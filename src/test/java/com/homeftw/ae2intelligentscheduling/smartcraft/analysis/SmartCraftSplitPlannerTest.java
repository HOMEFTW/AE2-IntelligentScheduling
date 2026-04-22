package com.homeftw.ae2intelligentscheduling.smartcraft.analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.homeftw.ae2intelligentscheduling.smartcraft.model.SmartCraftOrderScale;

class SmartCraftSplitPlannerTest {

    @Test
    void classifies_int_max_as_medium_order() {
        assertEquals(
            SmartCraftOrderScale.MEDIUM,
            SmartCraftOrderScaleClassifier.classify(2_147_483_647L));
    }

    @Test
    void splits_one_g_into_two_tasks_for_small_orders() {
        List<Long> parts = SmartCraftSplitPlanner.splitAmount(SmartCraftOrderScale.SMALL, 1_000_000_000L);
        assertEquals(Arrays.asList(500_000_000L, 500_000_000L), parts);
    }

    @Test
    void splits_eight_g_into_six_tasks_for_medium_orders() {
        List<Long> parts = SmartCraftSplitPlanner.splitAmount(SmartCraftOrderScale.MEDIUM, 8_000_000_000L);
        assertEquals(6, parts.size());
        assertEquals(8_000_000_000L, parts.stream().mapToLong(Long::longValue).sum());
    }

    @Test
    void splits_sixty_four_g_into_sixteen_tasks_for_large_orders() {
        List<Long> parts = SmartCraftSplitPlanner.splitAmount(SmartCraftOrderScale.LARGE, 64_000_000_000L);
        assertEquals(16, parts.size());
        assertEquals(64_000_000_000L, parts.stream().mapToLong(Long::longValue).sum());
    }
}
