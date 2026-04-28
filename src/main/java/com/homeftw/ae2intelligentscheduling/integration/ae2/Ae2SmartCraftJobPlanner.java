package com.homeftw.ae2intelligentscheduling.integration.ae2;

import java.util.concurrent.Future;

import com.homeftw.ae2intelligentscheduling.smartcraft.model.SmartCraftTask;
import com.homeftw.ae2intelligentscheduling.smartcraft.runtime.SmartCraftRuntimeCoordinator;
import com.homeftw.ae2intelligentscheduling.smartcraft.runtime.SmartCraftRuntimeSession;

import appeng.api.networking.crafting.ICraftingJob;
import appeng.api.storage.data.IAEItemStack;

public final class Ae2SmartCraftJobPlanner implements SmartCraftRuntimeCoordinator.JobPlanner {

    @Override
    public Future<ICraftingJob> begin(SmartCraftRuntimeSession session, SmartCraftTask task) {
        if (!(task.requestKey() instanceof Ae2RequestKey)) {
            throw new IllegalArgumentException("Unsupported request key type: " + task.requestKey());
        }

        Ae2RequestKey requestKey = (Ae2RequestKey) task.requestKey();
        IAEItemStack request = requestKey.createCraftRequest(task.amount());
        if (request == null) {
            throw new IllegalArgumentException("Unable to create AE2 request for task " + task.taskKey());
        }

        return session.craftingGrid()
            .beginCraftingJob(session.world(), session.grid(), session.actionSource(), request, null);
    }
}
