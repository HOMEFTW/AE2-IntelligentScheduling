package com.homeftw.ae2intelligentscheduling;

import net.minecraft.client.Minecraft;

import com.homeftw.ae2intelligentscheduling.client.gui.GuiSmartCraftStatus;
import com.homeftw.ae2intelligentscheduling.network.packet.SyncSmartCraftOrderPacket;

import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLPostInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;

public class ClientProxy extends CommonProxy {

    @Override
    public void preInit(FMLPreInitializationEvent event) {
        super.preInit(event);
    }

    @Override
    public void init(FMLInitializationEvent event) {
        super.init(event);
    }

    @Override
    public void postInit(FMLPostInitializationEvent event) {
        super.postInit(event);
    }

    @Override
    public void openSmartCraftStatus(SyncSmartCraftOrderPacket packet) {
        Minecraft minecraft = Minecraft.getMinecraft();
        if (minecraft == null) {
            return;
        }

        if (minecraft.currentScreen instanceof GuiSmartCraftStatus) {
            GuiSmartCraftStatus existing = (GuiSmartCraftStatus) minecraft.currentScreen;
            if (!existing.orderId().equals(packet.getOrderId())) {
                minecraft.displayGuiScreen(new GuiSmartCraftStatus(packet));
                return;
            }
            existing.update(packet);
            return;
        }

        minecraft.displayGuiScreen(new GuiSmartCraftStatus(packet));
    }
}
