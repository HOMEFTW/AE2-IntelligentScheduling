package com.homeftw.ae2intelligentscheduling.smartcraft.runtime;

import java.io.File;
import java.io.IOException;

import net.minecraft.world.WorldServer;
import net.minecraftforge.event.world.WorldEvent;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;

/**
 * v0.1.9.3 (G12-fix) Forge event glue that drives {@link SmartCraftPersistence}. Lifecycle:
 *
 * <ol>
 * <li>{@link #loadOnServerStart} is called once from {@code FMLServerStartedEvent}. It resolves
 * the overworld save directory and reads the persisted file (if any) into the manager.</li>
 * <li>The handler is registered on the {@code MinecraftForge.EVENT_BUS}; vanilla fires
 * {@link WorldEvent.Save} on every world save round (autosave + chunk-save + stop). We only act
 * on DIM 0 saves and only when the manager has marked itself dirty since the last successful
 * write -- this keeps autosave overhead at "one comparison" when no orders changed.</li>
 * <li>{@link #flushOnServerStop} is called from {@code FMLServerStoppingEvent} for a final
 * forced flush. WorldEvent.Save typically already fired during stop, but the explicit final
 * flush guards against forks that skip it on certain stop paths.</li>
 * </ol>
 *
 * <p>Why this replaces the v0.1.9 {@code SmartCraftOrderWorldData} approach:
 *
 * <ul>
 * <li>vanilla 1.7.10 {@code MapStorage.saveAllData} is not reliably called on every save tick.
 * In testing, single-player saves frequently hit a path where SmartCraft data was never written
 * to disk, even though the {@code WorldSavedData.markDirty} flag was set. The result was that
 * "smart craft tab bar empty after restart" reported by the user on v0.1.9.2.</li>
 * <li>The {@code attach} dance -- {@code loadData} that reflectively constructs us, calls
 * {@code readFromNBT} when the manager isn't wired yet, then we replay the deferred tag --
 * had a fragile two-phase structure that was hard to debug when it broke.</li>
 * <li>Direct File I/O on the standard {@code WorldEvent.Save} entry point is what every other
 * GTNH mod that needs reliable persistence uses (e.g. NewHorizonsCoreMod, GT++). It's the
 * battle-tested pattern, and corruption / time-of-write semantics are obvious from the code.</li>
 * </ul>
 *
 * <p>Thread-safety: all event handlers run on the server thread (Forge event bus dispatches
 * synchronously). The manager's dirty flag is read + cleared on the same thread that mutates
 * orders, so no cross-thread visibility concerns arise.
 */
public final class SmartCraftPersistenceHandler {

    private static final Logger LOGGER = LogManager.getLogger("AE2IS-Persist");

    private final SmartCraftOrderManager manager;
    /**
     * Cached overworld save directory captured at server start. Save events fire per-dimension,
     * but we always serialize to DIM 0's data dir regardless of which world Forge happens to be
     * saving at the moment. Captured once at start so we don't have to resolve the
     * {@code MinecraftServer} singleton on every save call.
     */
    private File worldDir;
    /**
     * Set by {@link SmartCraftOrderManager#setDirtyListener}. We persist on the next eligible
     * world-save event when this is true; cleared after a successful write.
     */
    private boolean dirty;

    public SmartCraftPersistenceHandler(SmartCraftOrderManager manager) {
        if (manager == null) throw new IllegalArgumentException("manager must not be null");
        this.manager = manager;
        // Wire the dirty listener once during construction. Production hands us the same manager
        // singleton that AE2IntelligentScheduling holds, so this listener stays installed for the
        // entire JVM lifetime, surviving server restarts (the JVM is not torn down between
        // single-player worlds in the integrated server).
        manager.setDirtyListener(() -> this.dirty = true);
    }

    /**
     * v0.1.9.3 Server-start entry point. Captures the overworld save dir and triggers the initial
     * read into the manager. Called from {@code FMLServerStartedEvent} after every dimension is
     * loaded so {@link net.minecraft.world.chunk.storage.IChunkLoader} paths are valid.
     *
     * @param overworldWorldDir result of {@code WorldServer.getSaveHandler().getWorldDirectory()}
     *                          on DIM 0. Captured here so we don't have to keep a reference to
     *                          the {@link WorldServer} itself.
     */
    public void loadOnServerStart(File overworldWorldDir) {
        if (overworldWorldDir == null) {
            LOGGER.warn("loadOnServerStart called with null world dir; skipping persistence load");
            return;
        }
        this.worldDir = overworldWorldDir;
        File source = SmartCraftPersistence.dataFile(overworldWorldDir);
        SmartCraftPersistence.readFromFile(source, this.manager);
        // Loading from disk is not a mutation that needs to be re-saved -- the manager's
        // setDirtyListener fires from inside loadFromNBT (it always calls markDirty), so we
        // explicitly clear the flag afterwards. Otherwise the next save round would re-write
        // the file we just loaded, wasting an I/O cycle on every server start.
        this.dirty = false;
    }

    /**
     * v0.1.9.3 World save hook. Fires for every dimension on every save round; we only act on
     * DIM 0 (the overworld) and only when something actually changed since the last write.
     */
    @SubscribeEvent
    public void onWorldSave(WorldEvent.Save event) {
        if (this.worldDir == null) return; // server hasn't finished starting yet
        if (!shouldHandleSaveEvent(event)) return;
        flush("worldSave");
    }

    /**
     * Pure-function dimension/server gate for {@link #onWorldSave}. Pulled out as a static so
     * tests can verify the routing logic without having to construct a real {@code WorldServer}
     * (whose 1.7.10 constructor immediately NPEs on a null {@code ISaveHandler}). Returns
     * {@code true} only for DIM 0 server-side save events.
     */
    static boolean shouldHandleSaveEvent(WorldEvent.Save event) {
        if (event == null || event.world == null) return false;
        if (!(event.world instanceof WorldServer)) return false;
        if (event.world.provider == null) return false;
        return event.world.provider.dimensionId == 0;
    }

    /**
     * v0.1.9.3 Server-stop entry point. Force one final synchronous flush regardless of the dirty
     * flag, because:
     * <ul>
     * <li>If WorldEvent.Save already wrote within the same stop sequence, our flag is now false
     * and {@link #flush} returns early without doing extra I/O. Fast, harmless.</li>
     * <li>If a fork skipped the world-save path on stop (some optimised modpacks do), this is
     * our last chance to persist before the JVM exits.</li>
     * </ul>
     */
    public void flushOnServerStop() {
        if (this.worldDir == null) return;
        // Force a flush even when not dirty -- the cost is one disk write at shutdown, which is
        // dwarfed by the chunk save vanilla is doing right next to us.
        this.dirty = true;
        flush("serverStop");
    }

    /**
     * Internal flush helper. Returns silently when nothing is dirty so callers can fire it
     * unconditionally on the hot path. On I/O failure the dirty flag stays set so the next save
     * round retries; we only clear it after a successful write completes.
     */
    private void flush(String reason) {
        if (!this.dirty) return;
        File target = SmartCraftPersistence.dataFile(this.worldDir);
        try {
            SmartCraftPersistence.writeToFile(target, this.manager);
            this.dirty = false;
            LOGGER.info(
                "Smart craft persistence ({}): wrote {} orders to {}",
                reason,
                Integer.valueOf(
                    this.manager.snapshot()
                        .size()),
                target.getAbsolutePath());
        } catch (IOException e) {
            LOGGER.error(
                "Smart craft persistence ({}) failed to write {}: {}",
                reason,
                target.getAbsolutePath(),
                e.toString(),
                e);
            // Leave dirty=true; next save round retries.
        }
    }

    /**
     * Test hook: returns whether the next eligible save event would actually write. Lets unit
     * tests assert that mutations correctly mark dirty without having to subscribe to log
     * output. Not used by production code.
     */
    boolean isDirty() {
        return this.dirty;
    }
}
