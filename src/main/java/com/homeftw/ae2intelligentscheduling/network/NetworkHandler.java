package com.homeftw.ae2intelligentscheduling.network;

import cpw.mods.fml.common.network.NetworkRegistry;
import cpw.mods.fml.common.network.simpleimpl.SimpleNetworkWrapper;
import cpw.mods.fml.relauncher.Side;

import com.homeftw.ae2intelligentscheduling.AE2IntelligentScheduling;
import com.homeftw.ae2intelligentscheduling.network.packet.OpenSmartCraftPreviewPacket;
import com.homeftw.ae2intelligentscheduling.network.packet.RequestSmartCraftActionPacket;
import com.homeftw.ae2intelligentscheduling.network.packet.SyncSmartCraftOrderPacket;

public final class NetworkHandler {

    public static final SimpleNetworkWrapper INSTANCE = NetworkRegistry.INSTANCE
            .newSimpleChannel(AE2IntelligentScheduling.MODID);

    private static int nextMessageId = 0;
    private static boolean initialized = false;

    private NetworkHandler() {}

    public static void init() {
        if (initialized) {
            return;
        }

        INSTANCE.registerMessage(
            OpenSmartCraftPreviewPacket.Handler.class,
            OpenSmartCraftPreviewPacket.class,
            nextMessageId++,
            Side.SERVER);
        INSTANCE.registerMessage(
            RequestSmartCraftActionPacket.Handler.class,
            RequestSmartCraftActionPacket.class,
            nextMessageId++,
            Side.SERVER);
        INSTANCE.registerMessage(
            SyncSmartCraftOrderPacket.Handler.class,
            SyncSmartCraftOrderPacket.class,
            nextMessageId++,
            Side.CLIENT);

        initialized = true;
    }
}
