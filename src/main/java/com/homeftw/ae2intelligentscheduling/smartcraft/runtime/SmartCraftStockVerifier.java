package com.homeftw.ae2intelligentscheduling.smartcraft.runtime;

import com.homeftw.ae2intelligentscheduling.smartcraft.model.SmartCraftTask;

public final class SmartCraftStockVerifier {

    public boolean hasOutstandingWork(SmartCraftTask task) {
        return task != null && task.amount() > 0L && !task.isTerminal();
    }
}
