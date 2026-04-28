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

    public SmartCraftLayer withTasks(List<SmartCraftTask> nextTasks) {
        return new SmartCraftLayer(this.depth, new ArrayList<SmartCraftTask>(nextTasks));
    }

    public boolean isComplete() {
        for (SmartCraftTask task : this.tasks) {
            if (!task.isTerminal()) {
                return false;
            }
        }
        return true;
    }
}
