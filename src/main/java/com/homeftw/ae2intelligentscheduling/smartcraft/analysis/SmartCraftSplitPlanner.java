package com.homeftw.ae2intelligentscheduling.smartcraft.analysis;

import java.util.ArrayList;
import java.util.List;

import com.homeftw.ae2intelligentscheduling.smartcraft.model.SmartCraftOrderScale;

public final class SmartCraftSplitPlanner {

    public static final long ONE_G = 1_000_000_000L;
    public static final long INT_MAX_GAP = 2_147_483_647L;
    public static final long FOUR_G = 4_000_000_000L;
    public static final long EIGHT_G = 8_000_000_000L;
    public static final long SIXTEEN_G = 16_000_000_000L;
    public static final long SIXTY_FOUR_G = 64_000_000_000L;

    private SmartCraftSplitPlanner() {}

    public static List<Long> splitAmount(SmartCraftOrderScale scale, long missingAmount) {
        if (missingAmount < 0L) {
            throw new IllegalArgumentException("missingAmount must be >= 0");
        }

        int partitions = partitionCount(scale, missingAmount);
        long base = missingAmount / partitions;
        long remainder = missingAmount % partitions;
        List<Long> result = new ArrayList<>(partitions);
        for (int i = 0; i < partitions; i++) {
            result.add(Long.valueOf(base + (i < remainder ? 1 : 0)));
        }
        return result;
    }

    private static int partitionCount(SmartCraftOrderScale scale, long missingAmount) {
        switch (scale) {
            case SMALL:
                if (missingAmount >= INT_MAX_GAP) {
                    return 3;
                }
                if (missingAmount >= ONE_G) {
                    return 2;
                }
                return 1;
            case MEDIUM:
                if (missingAmount >= EIGHT_G) {
                    return 6;
                }
                if (missingAmount >= FOUR_G) {
                    return 4;
                }
                if (missingAmount >= INT_MAX_GAP) {
                    return 3;
                }
                if (missingAmount >= ONE_G) {
                    return 2;
                }
                return 1;
            case LARGE:
                if (missingAmount >= SIXTY_FOUR_G) {
                    return 16;
                }
                if (missingAmount >= SIXTEEN_G) {
                    return 8;
                }
                if (missingAmount >= FOUR_G) {
                    return 4;
                }
                if (missingAmount >= ONE_G) {
                    return 2;
                }
                return 1;
            default:
                throw new IllegalArgumentException("Unknown order scale: " + scale);
        }
    }
}
