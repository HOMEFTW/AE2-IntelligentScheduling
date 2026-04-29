package com.homeftw.ae2intelligentscheduling.smartcraft.model;

import javax.annotation.Nullable;

import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;

public interface SmartCraftRequestKey {

    String id();

    @Nullable
    ItemStack itemStack();

    /**
     * v0.1.9 (G12) Persistence hook. Implementations MUST write a {@code "type"} string tag that
     * matches a key registered with {@link SmartCraftRequestKeyRegistry} on server startup, so the
     * inverse read path can route back to the right factory. Default implementation writes a
     * placeholder type that the registry refuses to read \u2014 forcing real implementations to
     * override this and explicitly opt into persistence.
     */
    default void writeToNBT(NBTTagCompound tag) {
        tag.setString("type", "_unsupported");
        tag.setString("id", this.id());
    }
}
