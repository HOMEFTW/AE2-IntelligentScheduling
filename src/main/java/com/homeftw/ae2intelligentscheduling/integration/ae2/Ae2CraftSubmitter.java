package com.homeftw.ae2intelligentscheduling.integration.ae2;

import appeng.api.networking.crafting.ICraftingCPU;
import appeng.api.networking.crafting.ICraftingGrid;
import appeng.api.networking.crafting.ICraftingJob;
import appeng.api.networking.crafting.ICraftingLink;
import appeng.api.networking.security.BaseActionSource;

import com.homeftw.ae2intelligentscheduling.smartcraft.model.SmartCraftTask;
import com.homeftw.ae2intelligentscheduling.smartcraft.runtime.SmartCraftRequesterBridge;

public final class Ae2CraftSubmitter {

    public ICraftingLink submit(ICraftingGrid craftingGrid, ICraftingJob craftingJob, SmartCraftTask task,
            ICraftingCPU targetCpu, SmartCraftRequesterBridge requesterBridge, BaseActionSource actionSource) {
        ICraftingLink craftingLink = craftingGrid.submitJob(craftingJob, requesterBridge, targetCpu, false, actionSource);
        if (craftingLink != null) {
            requesterBridge.track(task, craftingLink);
        }
        return craftingLink;
    }
}
