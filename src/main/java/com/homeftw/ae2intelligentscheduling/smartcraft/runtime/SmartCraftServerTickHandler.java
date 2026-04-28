package com.homeftw.ae2intelligentscheduling.smartcraft.runtime;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

public final class SmartCraftServerTickHandler {

    private final SmartCraftRuntimeCoordinator runtimeCoordinator;

    public SmartCraftServerTickHandler(SmartCraftRuntimeCoordinator runtimeCoordinator) {
        this.runtimeCoordinator = runtimeCoordinator;
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase == TickEvent.Phase.END) {
            this.runtimeCoordinator.tick();
        }
    }
}
