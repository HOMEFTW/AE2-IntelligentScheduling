package com.homeftw.ae2intelligentscheduling.smartcraft.runtime;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.world.World;
import net.minecraft.world.WorldSavedData;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * v0.1.9 (G12) {@link WorldSavedData} that persists {@link SmartCraftOrderManager} state into
 * the overworld save folder. Lifecycle:
 *
 * <ol>
 * <li>{@link #attach(World, SmartCraftOrderManager)} is called from {@code FMLServerStartedEvent}
 * with the overworld. The method either retrieves the existing data instance from the world or
 * creates a fresh one, loads its NBT into the manager, then wires the manager's
 * {@link SmartCraftOrderManager.DirtyListener} to {@link #markDirty} so subsequent mutations
 * trigger world-save rounds.</li>
 * <li>Vanilla world save calls {@link #writeToNBT} which delegates to the manager's
 * {@link SmartCraftOrderManager#writeToNBT serializer}.</li>
 * <li>Server stop / world unload happens automatically: the data instance is GC'd along with the
 * world; the next start re-attaches.</li>
 * </ol>
 *
 * <p>Storage key: {@value #DATA_KEY}. Stored under {@code <world>/data/AE2IS_SmartCraftOrders.dat}
 * via the standard Forge WorldSavedData mechanism. Per-dimension data would be wrong here \u2014
 * the manager is global to the server, not per-dimension; we always attach to the overworld
 * (DIM 0) and ignore the rest.
 */
public final class SmartCraftOrderWorldData extends WorldSavedData {

    private static final Logger LOGGER = LogManager.getLogger("AE2IS-WorldData");
    public static final String DATA_KEY = "AE2IS_SmartCraftOrders";

    /**
     * v0.1.9 (G12) Reference back to the manager so {@link #writeToNBT} can serialize it on
     * world save. Set during {@link #attach}; null after construction in case Forge instantiates
     * this class via reflection before the manager is wired.
     */
    private SmartCraftOrderManager manager;

    /**
     * Required by Forge's {@link WorldSavedData} reflection-based instantiation. The {@code key}
     * argument is the {@link #DATA_KEY} string passed in by the world's MapStorage.
     */
    public SmartCraftOrderWorldData(String key) {
        super(key);
    }

    /**
     * v0.1.9 (G12) Lifecycle entry point. Idempotent \u2014 calling twice on the same world reuses
     * the existing data instance. Returns the attached instance so callers can inspect it
     * (e.g. for tests / logging).
     */
    public static SmartCraftOrderWorldData attach(World world, SmartCraftOrderManager manager) {
        if (world == null || world.mapStorage == null || manager == null) {
            LOGGER.warn(
                "SmartCraftOrderWorldData.attach skipped: world={}, mapStorage={}, manager={}",
                world,
                world == null ? null : world.mapStorage,
                manager);
            return null;
        }
        SmartCraftOrderWorldData data = (SmartCraftOrderWorldData) world.mapStorage
            .loadData(SmartCraftOrderWorldData.class, DATA_KEY);
        if (data == null) {
            data = new SmartCraftOrderWorldData(DATA_KEY);
            world.mapStorage.setData(DATA_KEY, data);
        }
        data.manager = manager;
        // If readFromNBT already ran during loadData() above (existing-data path), it stashed the
        // tag in pendingLoadTag because the manager wasn't wired yet. Drain it now.
        data.replayPendingLoad();
        // Wire manager mutations to the world's dirty flag so the next save round picks them up.
        // Server start ordering matters: this must run BEFORE any code path can call
        // orderManager.track(), otherwise we'd clobber freshly-created orders with stale disk
        // data on the wrong load order.
        manager.setDirtyListener(data::markDirty);
        return data;
    }

    /**
     * Forge calls this when the world reads its data files at startup (only when the file exists).
     * Forwards to {@link SmartCraftOrderManager#loadFromNBT}; the manager is not always set yet
     * here (loadData runs before {@link #attach}), so we defer until attach if so.
     */
    @Override
    public void readFromNBT(NBTTagCompound tag) {
        if (this.manager == null) {
            // Constructor path: the manager wiring happens in attach() which is always called
            // immediately after loadData(). We stash the tag on a sidecar field so attach() can
            // replay it. Avoid keeping the original tag reference longer than necessary \u2014 it's
            // harmless but unnecessary memory pressure.
            this.pendingLoadTag = tag;
            return;
        }
        this.manager.loadFromNBT(tag);
    }

    /**
     * Sidecar storage so the constructor / readFromNBT path can defer the actual load until
     * {@link #attach} wires the manager. See javadoc on readFromNBT for why.
     */
    private NBTTagCompound pendingLoadTag;

    /**
     * Called from {@link #attach} after the manager wire-up to drain any deferred load tag the
     * constructor stashed. Public-package visibility so attach() can call it without exposing
     * the field publicly.
     */
    void replayPendingLoad() {
        if (this.pendingLoadTag != null && this.manager != null) {
            this.manager.loadFromNBT(this.pendingLoadTag);
            this.pendingLoadTag = null;
        }
    }

    /**
     * Forge calls this when the world is dirtied and persists the data file. Empty manager
     * produces an empty {@code orders} list, which is fine \u2014 next load just sees an empty list.
     */
    @Override
    public void writeToNBT(NBTTagCompound tag) {
        if (this.manager != null) {
            this.manager.writeToNBT(tag);
        }
    }
}
