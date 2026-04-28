package com.homeftw.ae2intelligentscheduling.smartcraft.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class SmartCraftNode {

    private final SmartCraftRequestKey requestKey;
    private final long requestedAmount;
    private final long availableAmount;
    private final long missingAmount;
    private final int depth;
    private final List<SmartCraftNode> children;

    public SmartCraftNode(SmartCraftRequestKey requestKey, long requestedAmount, long availableAmount, int depth,
        List<SmartCraftNode> children) {
        this.requestKey = requestKey;
        this.requestedAmount = requestedAmount;
        this.availableAmount = availableAmount;
        this.missingAmount = Math.max(0L, requestedAmount - availableAmount);
        this.depth = depth;
        this.children = Collections.unmodifiableList(new ArrayList<>(children));
    }

    public SmartCraftRequestKey requestKey() {
        return this.requestKey;
    }

    public long requestedAmount() {
        return this.requestedAmount;
    }

    public long availableAmount() {
        return this.availableAmount;
    }

    public long missingAmount() {
        return this.missingAmount;
    }

    public int depth() {
        return this.depth;
    }

    public List<SmartCraftNode> children() {
        return this.children;
    }
}
