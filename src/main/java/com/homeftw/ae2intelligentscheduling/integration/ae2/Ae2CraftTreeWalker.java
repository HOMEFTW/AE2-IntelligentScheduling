package com.homeftw.ae2intelligentscheduling.integration.ae2;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.homeftw.ae2intelligentscheduling.smartcraft.analysis.SmartCraftOrderBuilder;
import com.homeftw.ae2intelligentscheduling.smartcraft.model.SmartCraftRequestKey;

import appeng.api.storage.data.IAEItemStack;
import appeng.crafting.v2.CraftingRequest;
import appeng.crafting.v2.CraftingRequest.UsedResolverEntry;
import appeng.crafting.v2.resolvers.CraftableItemResolver.CraftFromPatternTask;
import appeng.crafting.v2.resolvers.ExtractItemResolver.ExtractItemTask;

public final class Ae2CraftTreeWalker {

    public Ae2TreeNodeSnapshot walk(CraftingRequest<IAEItemStack> request) {
        List<Ae2TreeNodeSnapshot> children = new ArrayList<>();
        walkChildren(request, children);
        return new Ae2TreeNodeSnapshot(
            Ae2RequestKey.from(request.stack),
            request.stack.getStackSize(),
            availableFromStorage(request),
            children);
    }

    private void walkChildren(CraftingRequest<IAEItemStack> request, List<Ae2TreeNodeSnapshot> children) {
        for (UsedResolverEntry<IAEItemStack> entry : request.usedResolvers) {
            if (entry.task instanceof CraftFromPatternTask) {
                CraftFromPatternTask task = (CraftFromPatternTask) entry.task;
                for (CraftingRequest<IAEItemStack> child : task.getChildRequests()) {
                    children.add(walk(child));
                }
            }
        }
    }

    private long availableFromStorage(CraftingRequest<IAEItemStack> request) {
        long available = 0;
        for (UsedResolverEntry<IAEItemStack> entry : request.usedResolvers) {
            if (entry.task instanceof ExtractItemTask) {
                available += entry.resolvedStack.getStackSize();
            }
        }
        return available;
    }

    public static final class Ae2TreeNodeSnapshot implements SmartCraftOrderBuilder.TreeNode {

        private final SmartCraftRequestKey requestKey;
        private final long requestedAmount;
        private final long availableAmount;
        private final List<Ae2TreeNodeSnapshot> children;

        public Ae2TreeNodeSnapshot(SmartCraftRequestKey requestKey, long requestedAmount, long availableAmount,
            List<Ae2TreeNodeSnapshot> children) {
            this.requestKey = requestKey;
            this.requestedAmount = requestedAmount;
            this.availableAmount = availableAmount;
            this.children = Collections.unmodifiableList(new ArrayList<>(children));
        }

        @Override
        public SmartCraftRequestKey requestKey() {
            return this.requestKey;
        }

        @Override
        public long requestedAmount() {
            return this.requestedAmount;
        }

        @Override
        public long availableAmount() {
            return this.availableAmount;
        }

        @Override
        public List<Ae2TreeNodeSnapshot> children() {
            return this.children;
        }
    }
}
