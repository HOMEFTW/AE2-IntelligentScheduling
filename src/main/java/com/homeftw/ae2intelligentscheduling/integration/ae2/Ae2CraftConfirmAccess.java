package com.homeftw.ae2intelligentscheduling.integration.ae2;

import java.lang.reflect.Field;

import net.minecraft.entity.player.EntityPlayer;

import appeng.api.networking.crafting.ICraftingJob;
import appeng.api.networking.security.BaseActionSource;
import appeng.api.networking.security.IActionHost;
import appeng.api.networking.security.PlayerSource;
import appeng.container.implementations.ContainerCraftConfirm;
import cpw.mods.fml.relauncher.ReflectionHelper;

public final class Ae2CraftConfirmAccess {

    private static final Field RESULT = ReflectionHelper.findField(ContainerCraftConfirm.class, "result");

    private Ae2CraftConfirmAccess() {}

    public static ICraftingJob result(ContainerCraftConfirm container) {
        if (container == null) {
            return null;
        }

        try {
            return (ICraftingJob) RESULT.get(container);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Unable to read AE2 ContainerCraftConfirm result", e);
        }
    }

    public static BaseActionSource actionSource(ContainerCraftConfirm container, EntityPlayer player) {
        Object target = container == null ? null : container.getTarget();
        if (!(target instanceof IActionHost)) {
            return null;
        }
        return new PlayerSource(player, (IActionHost) target);
    }
}
