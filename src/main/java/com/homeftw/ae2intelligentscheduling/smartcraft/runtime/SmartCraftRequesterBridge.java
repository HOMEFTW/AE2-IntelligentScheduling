package com.homeftw.ae2intelligentscheduling.smartcraft.runtime;

import java.util.LinkedHashMap;
import java.util.Map;

import net.minecraftforge.common.util.ForgeDirection;

import com.google.common.collect.ImmutableSet;
import com.homeftw.ae2intelligentscheduling.smartcraft.model.SmartCraftTask;

import appeng.api.config.Actionable;
import appeng.api.networking.IGridNode;
import appeng.api.networking.crafting.ICraftingLink;
import appeng.api.networking.crafting.ICraftingRequester;
import appeng.api.networking.security.IActionHost;
import appeng.api.storage.data.IAEItemStack;
import appeng.api.util.AECableType;

public final class SmartCraftRequesterBridge implements ICraftingRequester {

    private final IActionHost delegateHost;
    private final Map<String, ICraftingLink> linksByTaskKey = new LinkedHashMap<String, ICraftingLink>();

    public SmartCraftRequesterBridge(IActionHost delegateHost) {
        this.delegateHost = delegateHost;
    }

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
        // CRITICAL: return the FULL stack as "refused" so AE2 routes the crafted output to ME storage
        // via Platform.poweredInsert(...). Returning null would tell AE2 the bridge accepted everything,
        // and since this bridge has no real inventory the items would silently vanish. That bug
        // manifested as "task submitted, link active, but stockVerifier never sees the product → stuck
        // in VERIFYING_OUTPUT forever".
        return items;
    }

    @Override
    public void jobStateChange(ICraftingLink link) {
        if (link == null) {
            return;
        }
        pruneFinishedLinks();
        this.linksByTaskKey.values()
            .remove(link);
    }

    @Override
    public IGridNode getGridNode(ForgeDirection dir) {
        return this.delegateHost == null ? null : this.delegateHost.getGridNode(dir);
    }

    @Override
    public AECableType getCableConnectionType(ForgeDirection dir) {
        return this.delegateHost == null ? AECableType.NONE : this.delegateHost.getCableConnectionType(dir);
    }

    @Override
    public void securityBreak() {
        if (this.delegateHost != null) {
            this.delegateHost.securityBreak();
        }
    }

    @Override
    public IGridNode getActionableNode() {
        return this.delegateHost == null ? null : this.delegateHost.getActionableNode();
    }

    private void pruneFinishedLinks() {
        this.linksByTaskKey.values()
            .removeIf(link -> link == null || link.isDone() || link.isCanceled());
    }
}
