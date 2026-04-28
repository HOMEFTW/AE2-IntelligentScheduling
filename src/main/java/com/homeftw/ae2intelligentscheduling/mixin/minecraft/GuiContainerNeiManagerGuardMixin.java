package com.homeftw.ae2intelligentscheduling.mixin.minecraft;

import net.minecraft.client.gui.inventory.GuiContainer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.homeftw.ae2intelligentscheduling.client.gui.NeiGuiContainerManagerGuard;

@Mixin(GuiContainer.class)
public abstract class GuiContainerNeiManagerGuardMixin {

    @Inject(method = "updateScreen", at = @At("HEAD"))
    private void ae2intelligentscheduling$ensureNeiManager(CallbackInfo ci) {
        NeiGuiContainerManagerGuard.ensureManager((GuiContainer) (Object) this);
    }
}
