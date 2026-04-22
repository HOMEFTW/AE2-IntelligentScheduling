package com.homeftw.ae2intelligentscheduling.mixin.ae2;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

import appeng.api.networking.security.BaseActionSource;
import appeng.container.implementations.ContainerCraftConfirm;

@Mixin(value = ContainerCraftConfirm.class, remap = false)
public interface ContainerCraftConfirmInvoker {

    @Invoker(value = "getActionSrc", remap = false)
    BaseActionSource ae2is$invokeGetActionSrc();
}
