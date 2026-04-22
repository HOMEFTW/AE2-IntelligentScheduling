package com.homeftw.ae2intelligentscheduling.smartcraft.runtime;

import java.util.LinkedHashMap;
import java.util.Map;

import net.minecraftforge.common.util.ForgeDirection;

import com.google.common.collect.ImmutableSet;

import appeng.api.config.Actionable;
import appeng.api.networking.IGridNode;
import appeng.api.networking.crafting.ICraftingLink;
import appeng.api.networking.crafting.ICraftingRequester;
import appeng.api.storage.data.IAEItemStack;
import appeng.api.util.AECableType;

import com.homeftw.ae2intelligentscheduling.smartcraft.model.SmartCraftTask;

public final class SmartCraftRequesterBridge implements ICraftingRequester {

    private final Map<String, ICraftingLink> linksByTaskKey = new LinkedHashMap<String, ICraftingLink>();

    public void track(SmartCraftTask task, ICraftingLink link) {
        if (task == null || link == null) {
            return;
        }
        this.linksByTaskKey.put(task.taskKey(), link);
    }

    public ICraftingLink getTrackedLink(SmartCraftTask task) {
        return task == null ? null : this.linksByTaskKey.get(task.taskKey());
    }

    @Override
    public ImmutableSet<ICraftingLink> getRequestedJobs() {
        pruneFinishedLinks();
        return ImmutableSet.copyOf(this.linksByTaskKey.values());
    }

    @Override
    public IAEItemStack injectCraftedItems(ICraftingLink link, IAEItemStack items, Actionable mode) {
        return null;
    }

    @Override
    public void jobStateChange(ICraftingLink link) {
        if (link == null) {
            return;
        }
        pruneFinishedLinks();
        this.linksByTaskKey.values().remove(link);
    }

    @Override
    public IGridNode getGridNode(ForgeDirection dir) {
        return null;
    }

    @Override
    public AECableType getCableConnectionType(ForgeDirection dir) {
        return AECableType.NONE;
    }

    @Override
    public void securityBreak() {}

    @Override
    public IGridNode getActionableNode() {
        return null;
    }

    private void pruneFinishedLinks() {
        this.linksByTaskKey.values().removeIf(link -> link == null || link.isDone() || link.isCanceled());
    }
}
