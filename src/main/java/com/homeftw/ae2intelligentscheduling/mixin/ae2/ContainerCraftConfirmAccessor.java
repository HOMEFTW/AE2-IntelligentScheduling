package com.homeftw.ae2intelligentscheduling.mixin.ae2;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import appeng.api.networking.crafting.ICraftingJob;
import appeng.container.implementations.ContainerCraftConfirm;

@Mixin(value = ContainerCraftConfirm.class, remap = false)
public interface ContainerCraftConfirmAccessor {

    @Accessor(value = "result", remap = false)
    ICraftingJob ae2is$getResult();
}
