package com.homeftw.ae2intelligentscheduling.smartcraft.runtime;

import com.homeftw.ae2intelligentscheduling.integration.ae2.Ae2RequestKey;
import com.homeftw.ae2intelligentscheduling.smartcraft.model.SmartCraftRequestKey;
import com.homeftw.ae2intelligentscheduling.smartcraft.model.SmartCraftTask;

import appeng.api.networking.storage.IStorageGrid;
import appeng.api.storage.data.IAEItemStack;
import appeng.api.storage.data.IItemList;

public final class SmartCraftStockVerifier {

    /** Query current AE network stock for the task's output item. Returns -1 if not queryable. */
    public long currentStock(SmartCraftRuntimeSession session, SmartCraftTask task) {
        if (session == null || task == null) return -1L;
        SmartCraftRequestKey key = task.requestKey();
        if (!(key instanceof Ae2RequestKey)) return -1L;

        IAEItemStack probe = ((Ae2RequestKey) key).createCraftRequest(1L);
        if (probe == null) return -1L;

        IStorageGrid storageGrid = session.grid()
            .getCache(IStorageGrid.class);
        if (storageGrid == null) return -1L;

        IItemList<IAEItemStack> list = storageGrid.getItemInventory()
            .getStorageList();
        IAEItemStack found = list.findPrecise(probe);
        return found == null ? 0L : found.getStackSize();
    }

    /** Returns true if the network stock has grown by at least task.amount() since the baseline was recorded. */
    public boolean isOutputAvailable(SmartCraftRuntimeSession session, SmartCraftTask task) {
        long current = currentStock(session, task);
        if (current < 0L) return true; // not queryable — assume done
        long baseline = session.stockBaseline(task);
        return (current - baseline) >= task.amount();
    }
}
