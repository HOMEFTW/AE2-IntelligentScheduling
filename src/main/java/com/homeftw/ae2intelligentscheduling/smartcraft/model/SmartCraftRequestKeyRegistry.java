package com.homeftw.ae2intelligentscheduling.smartcraft.model;

import java.util.HashMap;
import java.util.Map;

import net.minecraft.nbt.NBTTagCompound;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * v0.1.9 (G12) Type-routed deserialization registry for {@link SmartCraftRequestKey}. The
 * concrete key implementations register themselves once at mod init under a stable type string;
 * {@link #readFromNBT(NBTTagCompound)} dispatches to the matching factory by reading the
 * {@code "type"} tag the writer stored.
 *
 * <p>Routing through a registry rather than hardcoding {@code Ae2RequestKey} into persistence
 * decouples the data model from the AE2 integration layer, which matters because the model lives
 * in {@code smartcraft.model} (no AE2 imports allowed) while {@code Ae2RequestKey} sits under
 * {@code integration.ae2}. Keeping the model package free of AE2 references is a deliberate
 * boundary: it lets the planning / scheduling logic be unit-tested against fakes without
 * needing the full AE2 + Forge runtime, which would otherwise make the test suite painful.
 */
public final class SmartCraftRequestKeyRegistry {

    private static final Logger LOGGER = LogManager.getLogger("AE2IS-RequestKeyRegistry");

    @FunctionalInterface
    public interface Reader {

        SmartCraftRequestKey readFromNBT(NBTTagCompound tag);
    }

    private static final Map<String, Reader> READERS = new HashMap<String, Reader>();

    private SmartCraftRequestKeyRegistry() {}

    /**
     * Register a {@link Reader} for a given type string. Call once at mod-init for each concrete
     * RequestKey implementation. Re-registering the same {@code type} silently overwrites \u2014 this
     * is intentional so dev-environment hot-reload doesn't blow up.
     */
    public static synchronized void register(String type, Reader reader) {
        if (type == null || type.isEmpty() || reader == null) return;
        READERS.put(type, reader);
    }

    /**
     * Read a {@link SmartCraftRequestKey} by routing through its {@code "type"} tag. Returns
     * {@code null} when the tag is missing, the type is unregistered, or the reader threw \u2014 in
     * any of those cases the caller (typically {@link com.homeftw.ae2intelligentscheduling.smartcraft.runtime.SmartCraftOrderManager})
     * must skip the order rather than crash the server.
     */
    public static SmartCraftRequestKey readFromNBT(NBTTagCompound tag) {
        if (tag == null) return null;
        String type = tag.hasKey("type") ? tag.getString("type") : null;
        if (type == null || type.isEmpty()) {
            LOGGER.warn("SmartCraftRequestKey NBT missing 'type' tag; cannot deserialize");
            return null;
        }
        Reader reader = READERS.get(type);
        if (reader == null) {
            LOGGER.warn("SmartCraftRequestKey type '{}' not registered; persisted order will be skipped", type);
            return null;
        }
        try {
            return reader.readFromNBT(tag);
        } catch (RuntimeException e) {
            LOGGER.warn("SmartCraftRequestKey reader for type '{}' threw, dropping entry", type, e);
            return null;
        }
    }
}
