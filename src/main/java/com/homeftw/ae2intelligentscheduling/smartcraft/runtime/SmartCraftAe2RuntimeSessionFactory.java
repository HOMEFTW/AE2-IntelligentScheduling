package com.homeftw.ae2intelligentscheduling.smartcraft.runtime;

import net.minecraft.entity.player.EntityPlayerMP;

import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;
import appeng.api.networking.crafting.ICraftingGrid;
import appeng.api.networking.security.BaseActionSource;
import appeng.api.networking.security.IActionHost;
import appeng.api.networking.security.PlayerSource;

public final class SmartCraftAe2RuntimeSessionFactory {

    public SmartCraftRuntimeSession create(EntityPlayerMP player, BaseActionSource actionSource) {
        IActionHost actionHost = extractActionHost(actionSource);
        IGridNode actionableNode = actionHost == null ? null : actionHost.getActionableNode();
        IGrid grid = actionableNode == null ? null : actionableNode.getGrid();
        ICraftingGrid craftingGrid = grid == null ? null : grid.getCache(ICraftingGrid.class);

        if (player == null || actionHost == null || actionableNode == null || grid == null || craftingGrid == null) {
            return null;
        }

        return new SmartCraftRuntimeSession(
            player,
            player.worldObj,
            actionHost,
            actionSource,
            grid,
            craftingGrid,
            new SmartCraftRequesterBridge(actionHost));
    }

    private IActionHost extractActionHost(BaseActionSource actionSource) {
        if (actionSource instanceof PlayerSource) {
            return ((PlayerSource) actionSource).via;
        }
        return null;
    }
}
