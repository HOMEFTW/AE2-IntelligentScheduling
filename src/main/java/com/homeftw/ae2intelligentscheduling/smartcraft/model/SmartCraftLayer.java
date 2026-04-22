package com.homeftw.ae2intelligentscheduling.smartcraft.model;

import java.util.ArrayList;
import java.util.List;

public final class SmartCraftLayer {

    private final int depth;
    private final List<SmartCraftTask> tasks;

    public SmartCraftLayer(int depth) {
        this(depth, new ArrayList<SmartCraftTask>());
    }

    public SmartCraftLayer(int depth, List<SmartCraftTask> tasks) {
        this.depth = depth;
        this.tasks = tasks;
    }

    public int depth() {
        return this.depth;
    }

    public List<SmartCraftTask> tasks() {
        return this.tasks;
    }
}
