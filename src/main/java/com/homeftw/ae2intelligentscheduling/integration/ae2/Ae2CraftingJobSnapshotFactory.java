package com.homeftw.ae2intelligentscheduling.integration.ae2;

import appeng.api.storage.data.IAEItemStack;
import appeng.crafting.v2.CraftingRequest;

public final class Ae2CraftingJobSnapshotFactory {

    private final Ae2CraftTreeWalker treeWalker;

    public Ae2CraftingJobSnapshotFactory() {
        this(new Ae2CraftTreeWalker());
    }

    public Ae2CraftingJobSnapshotFactory(Ae2CraftTreeWalker treeWalker) {
        this.treeWalker = treeWalker;
    }

    public Ae2CraftTreeWalker.Ae2TreeNodeSnapshot fromRequest(CraftingRequest<IAEItemStack> request) {
        return this.treeWalker.walk(request);
    }
}
