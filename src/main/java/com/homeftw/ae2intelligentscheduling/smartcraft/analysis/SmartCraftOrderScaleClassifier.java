package com.homeftw.ae2intelligentscheduling.smartcraft.analysis;

import com.homeftw.ae2intelligentscheduling.smartcraft.model.SmartCraftOrderScale;

public final class SmartCraftOrderScaleClassifier {

    public static final long INT_MAX_GAP = 2_147_483_647L;
    public static final long SIXTEEN_G = 16_000_000_000L;

    private SmartCraftOrderScaleClassifier() {}

    public static SmartCraftOrderScale classify(long maxMissingAmount) {
        if (maxMissingAmount >= SIXTEEN_G) {
            return SmartCraftOrderScale.LARGE;
        }
        if (maxMissingAmount >= INT_MAX_GAP) {
            return SmartCraftOrderScale.MEDIUM;
        }
        return SmartCraftOrderScale.SMALL;
    }
}
