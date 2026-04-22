package com.homeftw.ae2intelligentscheduling.mixin.ae2;

import net.minecraft.client.gui.GuiButton;
import net.minecraft.inventory.Container;
import net.minecraft.util.StatCollector;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.homeftw.ae2intelligentscheduling.network.NetworkHandler;
import com.homeftw.ae2intelligentscheduling.network.packet.OpenSmartCraftPreviewPacket;

import appeng.client.gui.AEBaseGui;
import appeng.client.gui.implementations.GuiCraftConfirm;

@Mixin(value = GuiCraftConfirm.class, remap = false)
public abstract class GuiCraftConfirmMixin extends AEBaseGui {

    @Unique
    private static final int AE2IS_SMART_CRAFT_BUTTON_ID = 0xAE21;

    @Unique
    private GuiButton ae2is$smartCraftButton;

    protected GuiCraftConfirmMixin(Container inventorySlotsIn) {
        super(inventorySlotsIn);
    }

    @Inject(method = "initGui", at = @At("TAIL"), remap = false)
    private void ae2is$addSmartCraftButton(CallbackInfo ci) {
        this.ae2is$smartCraftButton = new GuiButton(
            AE2IS_SMART_CRAFT_BUTTON_ID,
            this.guiLeft + (this.xSize - 84) / 2,
            this.guiTop + this.ySize - 47,
            84,
            20,
            StatCollector.translateToLocal("gui.ae2intelligentscheduling.smartCraft"));
        this.buttonList.add(this.ae2is$smartCraftButton);
    }

    @Inject(method = "actionPerformed", at = @At("HEAD"), cancellable = true, remap = false)
    private void ae2is$handleSmartCraftButton(GuiButton button, CallbackInfo ci) {
        if (button == this.ae2is$smartCraftButton) {
            NetworkHandler.INSTANCE.sendToServer(OpenSmartCraftPreviewPacket.preview());
            ci.cancel();
        }
    }
}
