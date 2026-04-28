package com.homeftw.ae2intelligentscheduling.smartcraft.model;

import javax.annotation.Nullable;

import net.minecraft.item.ItemStack;

public interface SmartCraftRequestKey {

    String id();

    @Nullable
    ItemStack itemStack();
}
