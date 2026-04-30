package com.homeftw.ae2intelligentscheduling.smartcraft.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.homeftw.ae2intelligentscheduling.smartcraft.model.SmartCraftLayer;
import com.homeftw.ae2intelligentscheduling.smartcraft.model.SmartCraftOrder;
import com.homeftw.ae2intelligentscheduling.smartcraft.model.SmartCraftOrderScale;
import com.homeftw.ae2intelligentscheduling.smartcraft.model.SmartCraftRequestKey;
import com.homeftw.ae2intelligentscheduling.smartcraft.model.SmartCraftStatus;
import com.homeftw.ae2intelligentscheduling.smartcraft.model.SmartCraftTask;

class SmartCraftOrderManagerTest {

    @Test
    void snapshot_preserves_insertion_order() {
        // v0.1.7 multi-order tab UI relies on this: the tab strip iterates the snapshot in order
        // so the leftmost tab is the oldest order and the rightmost is the newest. If the
        // snapshot ever returned a HashMap or sorted by UUID, the player's tab order would jump
        // around between every refresh.
        SmartCraftOrderManager manager = new SmartCraftOrderManager();
        UUID a = manager.track(makeOrder("a"));
        UUID b = manager.track(makeOrder("b"));
        UUID c = manager.track(makeOrder("c"));

        LinkedHashMap<UUID, SmartCraftOrder> snap = manager.snapshot();
        assertEquals(Arrays.asList(a, b, c), new ArrayList<UUID>(snap.keySet()));
    }

    @Test
    void snapshot_is_a_defensive_copy() {
        // The runtime tick mutates the underlying map (update / remove). The list-sync service
        // iterates the snapshot on the same server thread but right after a long-running packet
        // build; if the snapshot were the live map, a tick that fired between snapshot and
        // serialization would ConcurrentModificationException. Defensive copy is the contract.
        SmartCraftOrderManager manager = new SmartCraftOrderManager();
        UUID a = manager.track(makeOrder("a"));

        LinkedHashMap<UUID, SmartCraftOrder> snap = manager.snapshot();
        manager.remove(a);
        // Snapshot keeps its entry even after the underlying manager removed it.
        assertEquals(1, snap.size());
        assertNotNull(snap.get(a));
    }

    @Test
    void snapshot_returns_distinct_instance_each_call() {
        // Callers may stash the snapshot temporarily; if the manager handed back the same map
        // every time, two concurrent network handlers each iterating their own copy would in
        // fact be sharing one and racing each other. Easy to verify, cheap insurance.
        SmartCraftOrderManager manager = new SmartCraftOrderManager();
        manager.track(makeOrder("a"));
        assertNotSame(manager.snapshot(), manager.snapshot());
    }

    @Test
    void snapshot_reflects_subsequent_track_only_via_a_new_call() {
        // Pair with the defensive-copy test: caller-held snapshots are frozen. They only see new
        // entries when the caller asks for a fresh snapshot.
        SmartCraftOrderManager manager = new SmartCraftOrderManager();
        manager.track(makeOrder("a"));
        LinkedHashMap<UUID, SmartCraftOrder> first = manager.snapshot();
        manager.track(makeOrder("b"));

        assertEquals(1, first.size(), "first snapshot was frozen at the time of the call");
        assertEquals(
            2,
            manager.snapshot()
                .size(),
            "second snapshot picks up the new track");
    }

    private static SmartCraftOrder makeOrder(String requestKeyId) {
        SmartCraftRequestKey key = new FakeRequestKey(requestKeyId);
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
     * v0.1.9 (G12) NBT round-trip: persisted manager re-loaded into a fresh manager must produce
     * an equivalent set of orders. Verifies the SmartCraftOrder / Layer / Task / RequestKey
     * serialization chain end-to-end. FakeRequestKey overrides writeToNBT and registers itself
     * with the registry so the read path can route back to it.
     */
    @Test
    void manager_round_trips_orders_through_nbt_v019() {
        com.homeftw.ae2intelligentscheduling.smartcraft.model.SmartCraftRequestKeyRegistry
            .register("test.fake", FakeRequestKey::readFromNBT);
        SmartCraftOrderManager mgr = new SmartCraftOrderManager();
        UUID id1 = mgr.track(makeOrder("item-a"));
        UUID id2 = mgr.track(makeOrder("item-b"));

        net.minecraft.nbt.NBTTagCompound tag = new net.minecraft.nbt.NBTTagCompound();
        mgr.writeToNBT(tag);

        SmartCraftOrderManager reloaded = new SmartCraftOrderManager();
        reloaded.loadFromNBT(tag);

        assertEquals(2, reloaded.snapshot()
            .size(), "round-trip must preserve order count");
        // Insertion order must survive (LinkedHashMap)
        java.util.Iterator<UUID> it = reloaded.snapshot()
            .keySet()
            .iterator();
        assertEquals(id1, it.next());
        assertEquals(id2, it.next());

        SmartCraftOrder reloaded1 = reloaded.get(id1)
            .get();
        assertEquals(
            "item-a",
            reloaded1.targetRequestKey()
                .id());
        assertEquals(1L, reloaded1.targetAmount());
        assertEquals(SmartCraftOrderScale.SMALL, reloaded1.orderScale());
        assertEquals(
            1,
            reloaded1.layers()
                .size());
        assertEquals(
            1,
            reloaded1.layers()
                .get(0)
                .tasks()
                .size());
    }

    /**
     * v0.1.9 (G12) DirtyListener wiring: every mutator must trigger the listener so the
     * WorldSavedData picks up changes. Track / update / remove / cancel / cancelGracefully /
     * retryFailedTasks are all covered.
     */
    @Test
    void mutators_call_dirty_listener_v019() {
        SmartCraftOrderManager mgr = new SmartCraftOrderManager();
        int[] dirtyCount = { 0 };
        mgr.setDirtyListener(() -> dirtyCount[0]++);

        UUID id = mgr.track(makeOrder("item"));
        org.junit.jupiter.api.Assertions.assertTrue(dirtyCount[0] >= 1, "track must mark dirty");
        int afterTrack = dirtyCount[0];

        mgr.update(id, makeOrder("item"));
        org.junit.jupiter.api.Assertions.assertTrue(dirtyCount[0] > afterTrack, "update must mark dirty");
        int afterUpdate = dirtyCount[0];

        mgr.remove(id);
        org.junit.jupiter.api.Assertions.assertTrue(dirtyCount[0] > afterUpdate, "remove must mark dirty");
    }

    /**
     * v0.1.9.5 (G15) resetForRestart now folds in-flight tasks to CANCELLED and marks the order
     * {@code interruptedByRestart=true}. Aligned with AE2 vanilla which doesn't persist crafting
     * jobs across restarts. Terminal tasks (DONE / FAILED / CANCELLED) keep their original
     * status because they were already settled when the server stopped.
     */
    @Test
    void resetForRestart_folds_in_flight_tasks_to_cancelled_v0195() {
        FakeRequestKey key = new FakeRequestKey("item");
        SmartCraftTask running = new SmartCraftTask(
            "task-running",
            key,
            1L,
            0,
            0,
            1,
            SmartCraftStatus.RUNNING,
            "Crafting on cpu-1");
        SmartCraftTask done = new SmartCraftTask(
            "task-done",
            key,
            1L,
            0,
            0,
            1,
            SmartCraftStatus.DONE,
            null);
        SmartCraftTask failed = new SmartCraftTask(
            "task-failed",
            key,
            1L,
            0,
            0,
            1,
            SmartCraftStatus.FAILED,
            "blew up");
        List<SmartCraftLayer> layers = Collections
            .singletonList(new SmartCraftLayer(0, Arrays.asList(running, done, failed)));
        SmartCraftOrder order = new SmartCraftOrder(
            key,
            3L,
            SmartCraftOrderScale.SMALL,
            SmartCraftStatus.RUNNING,
            layers,
            0);

        SmartCraftOrder reset = SmartCraftOrderManager.resetForRestart(order);

        assertEquals(
            SmartCraftStatus.CANCELLED,
            reset.status(),
            "v0.1.9.5: any-interrupted order must reset to CANCELLED (history-only)");
        org.junit.jupiter.api.Assertions
            .assertTrue(reset.interruptedByRestart(), "interruptedByRestart flag must be true");
        java.util.List<SmartCraftTask> tasks = reset.layers()
            .get(0)
            .tasks();
        assertEquals(SmartCraftStatus.CANCELLED, tasks.get(0)
            .status(), "RUNNING task must fold to CANCELLED");
        assertEquals(SmartCraftStatus.DONE, tasks.get(1)
            .status(), "DONE task must keep its terminal status");
        assertEquals(SmartCraftStatus.FAILED, tasks.get(2)
            .status(), "FAILED task must keep its terminal status");
        org.junit.jupiter.api.Assertions.assertEquals(
            "Interrupted by server restart",
            tasks.get(0)
                .blockingReason(),
            "interrupted tasks get a recovery banner the player can read in the GUI");
    }

    /**
     * v0.1.9.5 (G15) An order whose every task was already terminal pre-restart (e.g. fully
     * COMPLETED + PAUSED orders) doesn't get the interruptedByRestart marker -- nothing was
     * actually interrupted, the order just sat in its final state across the restart. This
     * matters because such orders should remain Retry-eligible if any task is FAILED.
     */
    @Test
    void resetForRestart_does_not_mark_interrupted_when_no_task_was_in_flight() {
        FakeRequestKey key = new FakeRequestKey("item");
        SmartCraftTask done = new SmartCraftTask(
            "task-done",
            key,
            1L,
            0,
            0,
            1,
            SmartCraftStatus.DONE,
            null);
        SmartCraftTask failed = new SmartCraftTask(
            "task-failed",
            key,
            1L,
            0,
            0,
            1,
            SmartCraftStatus.FAILED,
            "previously failed");
        List<SmartCraftLayer> layers = Collections
            .singletonList(new SmartCraftLayer(0, Arrays.asList(done, failed)));
        SmartCraftOrder order = new SmartCraftOrder(
            key,
            2L,
            SmartCraftOrderScale.SMALL,
            SmartCraftStatus.PAUSED,
            layers,
            0);

        SmartCraftOrder reset = SmartCraftOrderManager.resetForRestart(order);

        org.junit.jupiter.api.Assertions
            .assertFalse(reset.interruptedByRestart(), "no in-flight tasks -> not interrupted");
        // Status untouched: PAUSED was already a valid terminal-with-retry state.
        assertEquals(SmartCraftStatus.PAUSED, reset.status());
    }

    /**
     * v0.1.9.5 (G15) retryFailedTasks is a no-op on interrupted-by-restart orders. The player
     * has to submit a fresh order to trigger new AE2 work; reviving the historical entry would
     * silently submit fresh AE2 jobs whose intermediate refunds can't be reconciled with what
     * the AE2 cluster already cancelled at restart time.
     */
    @Test
    void retryFailedTasks_rejects_interrupted_orders() {
        FakeRequestKey key = new FakeRequestKey("item");
        SmartCraftTask cancelled = new SmartCraftTask(
            "task-cancelled",
            key,
            1L,
            0,
            0,
            1,
            SmartCraftStatus.CANCELLED,
            "Interrupted by server restart");
        SmartCraftOrder interrupted = new SmartCraftOrder(
            key,
            1L,
            SmartCraftOrderScale.SMALL,
            SmartCraftStatus.CANCELLED,
            Collections.singletonList(new SmartCraftLayer(0, Collections.singletonList(cancelled))),
            0,
            "tester",
            true /* interruptedByRestart */);
        SmartCraftOrderManager mgr = new SmartCraftOrderManager();
        java.util.UUID id = java.util.UUID.randomUUID();
        mgr.trackWithId(id, interrupted);

        java.util.Optional<SmartCraftOrder> retried = mgr.retryFailedTasks(id);

        org.junit.jupiter.api.Assertions
            .assertFalse(retried.isPresent(), "interrupted orders must not be retriable");
        // The order still in the manager unchanged -- retry must not silently mutate it.
        assertEquals(
            SmartCraftStatus.CANCELLED,
            mgr.get(id)
                .get()
                .status());
        org.junit.jupiter.api.Assertions
            .assertTrue(mgr.get(id).get().interruptedByRestart(), "interruptedByRestart flag still set");
    }

    private static final class FakeRequestKey implements SmartCraftRequestKey {

        private final String id;

        FakeRequestKey(String id) {
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
        public void writeToNBT(net.minecraft.nbt.NBTTagCompound tag) {
            tag.setString("type", "test.fake");
            tag.setString("id", this.id);
        }

        static FakeRequestKey readFromNBT(net.minecraft.nbt.NBTTagCompound tag) {
            return new FakeRequestKey(tag.hasKey("id") ? tag.getString("id") : "");
        }
    }
}
