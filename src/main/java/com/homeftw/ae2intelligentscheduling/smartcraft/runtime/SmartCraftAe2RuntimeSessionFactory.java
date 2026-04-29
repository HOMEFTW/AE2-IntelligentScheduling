package com.homeftw.ae2intelligentscheduling.smartcraft.runtime;

import java.lang.reflect.Method;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.inventory.Container;

import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;
import appeng.api.networking.crafting.ICraftingGrid;
import appeng.api.networking.security.BaseActionSource;
import appeng.api.networking.security.IActionHost;
import appeng.api.networking.security.PlayerSource;

public final class SmartCraftAe2RuntimeSessionFactory {

    /**
     * v0.1.9 (G12) Best-effort helper for {@link com.homeftw.ae2intelligentscheduling.smartcraft.runtime.SmartCraftRuntimeCoordinator#attemptRebindSession}.
     *
     * <p>After a server restart the in-memory {@code sessions} map is empty even though the
     * OrderManager still holds the persisted orders. To resume execution we need to find an
     * {@link IActionHost} for the player; the cleanest source is whatever AE2 container the
     * player currently has open (ME terminal, crafting confirm, wireless terminal). All AE2
     * containers extend {@code AEBaseContainer} which exposes {@code getTarget()} returning an
     * {@link IActionHost}. We use reflection rather than a direct cast so this works across
     * AE2 forks (GTNH, AE2-Stuff, etc.) that subclass without exposing the same compile-time type.
     *
     * <p>Returns {@code null} when the player has no container open, when the open container is
     * not an AE2 container (no {@code getTarget()} method), or when the target isn't an
     * IActionHost (e.g. a plain GUI). Callers must treat null as "rebuild not possible right
     * now; ask the player to open a terminal".
     */
    public static BaseActionSource extractActionSourceFromOpenContainer(EntityPlayerMP player) {
        if (player == null) return null;
        Container container = player.openContainer;
        if (container == null) return null;
        try {
            Method getTarget = container.getClass()
                .getMethod("getTarget");
            Object target = getTarget.invoke(container);
            if (target instanceof IActionHost) {
                return new PlayerSource(player, (IActionHost) target);
            }
        } catch (NoSuchMethodException e) {
            // Not an AE2 container; nothing to do here.
        } catch (ReflectiveOperationException e) {
            // Defensive: getTarget exists but threw. Better to fail soft than crash the player.
        }
        return null;
    }

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
