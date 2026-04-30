package com.homeftw.ae2intelligentscheduling.smartcraft.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.homeftw.ae2intelligentscheduling.smartcraft.model.SmartCraftLayer;
import com.homeftw.ae2intelligentscheduling.smartcraft.model.SmartCraftOrder;
import com.homeftw.ae2intelligentscheduling.smartcraft.model.SmartCraftOrderScale;
import com.homeftw.ae2intelligentscheduling.smartcraft.model.SmartCraftRequestKey;
import com.homeftw.ae2intelligentscheduling.smartcraft.model.SmartCraftRequestKeyRegistry;
import com.homeftw.ae2intelligentscheduling.smartcraft.model.SmartCraftStatus;
import com.homeftw.ae2intelligentscheduling.smartcraft.model.SmartCraftTask;

/**
 * v0.1.9.3 (G12-fix) End-to-end I/O tests for the new persistence layer. Each test uses a JUnit 5
 * {@link TempDir} so we hit the real filesystem (write-then-read, atomic rename, corrupt-file
 * resilience) without mocking. The {@code SmartCraftPersistence} class is intentionally framework-
 * free so these tests run in plain JUnit + the GTNH 1.7.10 forge libraries we already pull in.
 */
class SmartCraftPersistenceTest {

    @BeforeAll
    static void registerKeyType() {
        // FakePersistKey is the test-local key type. Persistence code routes deserialization via
        // the registry so we have to register the read function once before any read path runs.
        // Idempotent: registering the same type repeatedly across tests is a no-op put().
        SmartCraftRequestKeyRegistry.register("test.persist", FakePersistKey::readFromNBT);
    }

    @Test
    void writeToFile_then_readFromFile_round_trips_orders(@TempDir Path tempDir) throws IOException {
        SmartCraftOrderManager source = new SmartCraftOrderManager();
        UUID id1 = source.track(makeOrder("alpha"));
        UUID id2 = source.track(makeOrder("beta"));

        File target = SmartCraftPersistence.dataFile(tempDir.toFile());
        SmartCraftPersistence.writeToFile(target, source);

        // The dat file must actually have been created. If it isn't here, the v0.1.9 bug we're
        // fixing is back: writeToFile silently dropped a file somewhere. Asserting existence and
        // size > 0 catches that immediately.
        assertTrue(target.exists(), "writeToFile must produce the target file");
        assertTrue(target.length() > 0L, "written file must not be empty");
        // No stale .tmp file should be left lying around -- atomic rename is supposed to clean it.
        File tmp = new File(target.getParentFile(), target.getName() + ".tmp");
        assertFalse(tmp.exists(), "tmp file must be removed after successful rename");

        SmartCraftOrderManager reloaded = new SmartCraftOrderManager();
        SmartCraftPersistence.readFromFile(target, reloaded);

        assertEquals(2, reloaded.snapshot()
            .size(), "round-trip must preserve order count");
        assertNotNull(reloaded.get(id1)
            .orElse(null), "first order must round-trip");
        assertNotNull(reloaded.get(id2)
            .orElse(null), "second order must round-trip");
    }

    @Test
    void readFromFile_silently_skips_when_target_does_not_exist(@TempDir Path tempDir) {
        SmartCraftOrderManager mgr = new SmartCraftOrderManager();
        // Pre-load a sentinel so we can assert readFromFile didn't clobber state on missing-file.
        UUID sentinel = mgr.track(makeOrder("sentinel"));
        File missing = new File(tempDir.toFile(), "nonexistent.dat");

        // Must not throw, must not touch the manager.
        SmartCraftPersistence.readFromFile(missing, mgr);

        assertEquals(1, mgr.snapshot()
            .size(), "manager must be untouched when source is missing");
        assertNotNull(mgr.get(sentinel)
            .orElse(null));
    }

    @Test
    void readFromFile_silently_skips_when_target_is_corrupt(@TempDir Path tempDir) throws IOException {
        // Write garbage bytes to the expected target. Persistence must NOT crash -- a corrupt
        // save file should never brick server start. Manager keeps its in-memory state.
        File target = SmartCraftPersistence.dataFile(tempDir.toFile());
        try (FileOutputStream fos = new FileOutputStream(target)) {
            fos.write("this is not valid gzipped NBT".getBytes("UTF-8"));
        }

        SmartCraftOrderManager mgr = new SmartCraftOrderManager();
        UUID sentinel = mgr.track(makeOrder("survivor"));

        SmartCraftPersistence.readFromFile(target, mgr);

        assertEquals(
            1,
            mgr.snapshot()
                .size(),
            "corrupt persistence file must NOT clobber existing in-memory orders");
        assertNotNull(mgr.get(sentinel)
            .orElse(null));
    }

    @Test
    void readFromFile_unwraps_legacy_vanilla_data_tag(@TempDir Path tempDir) throws IOException {
        // Files written by the v0.1.9 / v0.1.9.1 / v0.1.9.2 WorldSavedData path have an extra
        // top-level "data" wrapper from vanilla MapStorage. The new readFromFile must transparently
        // unwrap that wrapper so existing saves continue to load after the v0.1.9.3 upgrade.
        SmartCraftOrderManager source = new SmartCraftOrderManager();
        UUID id = source.track(makeOrder("legacy"));
        NBTTagCompound payload = new NBTTagCompound();
        source.writeToNBT(payload);
        NBTTagCompound wrapped = new NBTTagCompound();
        wrapped.setTag("data", payload);

        File target = SmartCraftPersistence.dataFile(tempDir.toFile());
        try (BufferedOutputStream bos = new BufferedOutputStream(new FileOutputStream(target))) {
            CompressedStreamTools.writeCompressed(wrapped, bos);
        }

        SmartCraftOrderManager reloaded = new SmartCraftOrderManager();
        SmartCraftPersistence.readFromFile(target, reloaded);

        assertEquals(1, reloaded.snapshot()
            .size(), "legacy 'data'-wrapped saves must round-trip");
        assertNotNull(reloaded.get(id)
            .orElse(null));
    }

    @Test
    void writeToFile_overwrites_existing_target_atomically(@TempDir Path tempDir) throws IOException {
        File target = SmartCraftPersistence.dataFile(tempDir.toFile());

        // First write: 1 order.
        SmartCraftOrderManager v1 = new SmartCraftOrderManager();
        v1.track(makeOrder("v1"));
        SmartCraftPersistence.writeToFile(target, v1);
        long firstSize = target.length();

        // Second write: 3 orders, same target. Must replace cleanly with no .tmp leftover.
        SmartCraftOrderManager v2 = new SmartCraftOrderManager();
        v2.track(makeOrder("a"));
        v2.track(makeOrder("b"));
        v2.track(makeOrder("c"));
        SmartCraftPersistence.writeToFile(target, v2);

        assertTrue(target.length() >= firstSize, "second write should not truncate the file");
        File tmp = new File(target.getParentFile(), target.getName() + ".tmp");
        assertFalse(tmp.exists(), "no orphan .tmp after a successful overwrite");

        SmartCraftOrderManager reloaded = new SmartCraftOrderManager();
        SmartCraftPersistence.readFromFile(target, reloaded);
        assertEquals(3, reloaded.snapshot()
            .size(), "second write's contents must be visible after reload");
    }

    @Test
    void persistenceHandler_marks_dirty_on_track_and_clears_on_flush(@TempDir Path tempDir) {
        SmartCraftOrderManager mgr = new SmartCraftOrderManager();
        SmartCraftPersistenceHandler handler = new SmartCraftPersistenceHandler(mgr);

        // Before loadOnServerStart, the handler has no worldDir so saves are no-ops. We have to
        // call loadOnServerStart with the temp dir to give it a place to write. The dir is empty
        // so the load is a no-op.
        handler.loadOnServerStart(tempDir.toFile());
        assertFalse(handler.isDirty(), "handler must not be dirty after a clean start");

        // Mutating the manager must mark dirty via the listener wire-up.
        mgr.track(makeOrder("dirty-me"));
        assertTrue(handler.isDirty(), "track() must propagate to handler dirty flag");

        // flushOnServerStop forces a write even when the dirty flag was already set; should clear.
        handler.flushOnServerStop();
        assertFalse(handler.isDirty(), "flushOnServerStop must clear the dirty flag after success");

        // The data file must now exist on disk.
        File target = SmartCraftPersistence.dataFile(tempDir.toFile());
        assertTrue(target.exists(), "flushOnServerStop must produce the data file");
    }

    @Test
    void persistenceHandler_loadOnServerStart_loads_existing_file_and_resets_dirty(@TempDir Path tempDir) {
        // Simulate a previous server: write a file, then start a new manager + handler from the
        // same world dir. The handler must load the file and NOT mark itself dirty afterwards
        // (otherwise the next save would re-write the file we just loaded for no reason).
        SmartCraftOrderManager previous = new SmartCraftOrderManager();
        UUID id = previous.track(makeOrder("survivor"));
        try {
            SmartCraftPersistence
                .writeToFile(SmartCraftPersistence.dataFile(tempDir.toFile()), previous);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        SmartCraftOrderManager next = new SmartCraftOrderManager();
        SmartCraftPersistenceHandler handler = new SmartCraftPersistenceHandler(next);
        handler.loadOnServerStart(tempDir.toFile());

        assertEquals(1, next.snapshot()
            .size(), "loadOnServerStart must populate the manager from disk");
        assertNotNull(next.get(id)
            .orElse(null));
        assertFalse(
            handler.isDirty(),
            "loadOnServerStart must clear the dirty flag set by loadFromNBT's markDirty call");
    }

    @Test
    void persistenceHandler_save_event_routing_rejects_invalid_inputs() {
        // Pure-function unit test for the SmartCraftPersistenceHandler.shouldHandleSaveEvent gate.
        // Constructing a real WorldServer is impractical (its 1.7.10 ctor immediately calls into
        // saveHandler.loadWorldInfo() which NPEs on null), so we just verify the gate's null-
        // safety here. The "only dim 0 fires" + "DIM 1 ignored" branches are exercised in
        // integration testing where a real WorldServer exists. The null-safety tested here is
        // the most likely vector for an actual production NPE if Forge ever fires a save event
        // with partial state during shutdown -- it's worth nailing down explicitly.
        assertFalse(
            SmartCraftPersistenceHandler.shouldHandleSaveEvent(null),
            "null event must not crash the handler");
        // Other branches (event with null world, non-WorldServer world, null provider, non-zero
        // dimensionId) are guarded by the same null-checks and would each require constructing a
        // partial WorldServer to drive. We accept the integration coverage gap here in exchange
        // for keeping the test suite framework-free.
    }

    @Test
    void persistenceHandler_onWorldSave_safe_against_null_event(@TempDir Path tempDir) {
        SmartCraftOrderManager mgr = new SmartCraftOrderManager();
        SmartCraftPersistenceHandler handler = new SmartCraftPersistenceHandler(mgr);
        handler.loadOnServerStart(tempDir.toFile());

        mgr.track(makeOrder("dirty"));
        assertTrue(handler.isDirty());

        // A null event must be a no-op (dirty stays true, no IOException, no NPE).
        handler.onWorldSave(null);
        assertTrue(handler.isDirty(), "null event must not trigger flush");
        assertFalse(
            SmartCraftPersistence.dataFile(tempDir.toFile())
                .exists(),
            "null event must not produce an on-disk file");
    }

    /**
     * v0.1.9.5 (G15) The {@code interruptedByRestart} flag must round-trip through SmartCraftOrder's
     * NBT layer. Tested directly on the order (not via the manager) because the manager's
     * loadFromNBT path runs through {@code resetForRestart} which would itself flip
     * non-terminal tasks to interrupted, interfering with the assertion. The persistence-level
     * test (round-trip-orders above) covers the file IO path; this one covers just the
     * order-level field.
     */
    @org.junit.jupiter.api.Test
    void interruptedByRestart_field_round_trips_through_nbt() {
        SmartCraftOrder original = makeOrder("interrupted-once").withInterruptedByRestart(true);
        NBTTagCompound tag = new NBTTagCompound();
        original.writeToNBT(tag);
        SmartCraftOrder reloaded = SmartCraftOrder.readFromNBT(tag);
        assertNotNull(reloaded, "order must round-trip");
        assertTrue(
            reloaded.interruptedByRestart(),
            "interruptedByRestart=true must persist across writeToNBT / readFromNBT");
    }

    @org.junit.jupiter.api.Test
    void interruptedByRestart_defaults_to_false_when_not_persisted() {
        // Defensive: writeToNBT must NOT emit the tag when the flag is false (saves bytes), AND
        // readFromNBT must default a missing tag to false. A missing-tag-defaults-to-true would
        // flip every legacy v0.1.9.4 save to historical on first load with v0.1.9.5.
        SmartCraftOrder original = makeOrder("normal"); // default: interruptedByRestart=false
        assertFalse(
            original.interruptedByRestart(),
            "makeOrder helper must produce non-interrupted orders by default");
        NBTTagCompound tag = new NBTTagCompound();
        original.writeToNBT(tag);
        assertFalse(
            tag.hasKey("interruptedByRestart"),
            "false flag must not be persisted (NBT bloat avoidance)");
        SmartCraftOrder reloaded = SmartCraftOrder.readFromNBT(tag);
        assertNotNull(reloaded);
        assertFalse(
            reloaded.interruptedByRestart(),
            "missing tag must read back as false (no legacy save misclassified as historical)");
    }

    private static SmartCraftOrder makeOrder(String requestKeyId) {
        SmartCraftRequestKey key = new FakePersistKey(requestKeyId);
        SmartCraftTask task = new SmartCraftTask(
            "task-" + requestKeyId,
            key,
            1L,
            0,
            1,
            1,
            SmartCraftStatus.PENDING,
            null);
        List<SmartCraftLayer> layers = Collections.singletonList(new SmartCraftLayer(0, Arrays.asList(task)));
        return new SmartCraftOrder(key, 1L, SmartCraftOrderScale.SMALL, SmartCraftStatus.QUEUED, layers, 0);
    }

    /**
     * Lightweight RequestKey for persistence tests. Registers itself under "test.persist" so the
     * read path can route NBT entries back to a real instance instead of returning null.
     */
    static final class FakePersistKey implements SmartCraftRequestKey {

        private final String id;

        FakePersistKey(String id) {
            this.id = id;
        }

        @Override
        public String id() {
            return this.id;
        }

        @Override
        public net.minecraft.item.ItemStack itemStack() {
            return null;
        }

        @Override
        public void writeToNBT(NBTTagCompound tag) {
            tag.setString("type", "test.persist");
            tag.setString("id", this.id);
        }

        static FakePersistKey readFromNBT(NBTTagCompound tag) {
            String id = tag.hasKey("id") ? tag.getString("id") : "";
            if (id == null || id.isEmpty()) {
                // Unreachable under the tests above (we always write a non-empty id), but the
                // contract for SmartCraftRequestKey readers is to return null on bad data so the
                // registry can drop the entry. Hold the line on that contract here too.
                return null;
            }
            return new FakePersistKey(id);
        }
    }
}
