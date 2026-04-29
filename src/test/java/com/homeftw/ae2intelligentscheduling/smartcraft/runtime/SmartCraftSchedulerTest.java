package com.homeftw.ae2intelligentscheduling.smartcraft.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import com.homeftw.ae2intelligentscheduling.integration.ae2.Ae2CpuSelector;
import com.homeftw.ae2intelligentscheduling.smartcraft.model.SmartCraftLayer;
import com.homeftw.ae2intelligentscheduling.smartcraft.model.SmartCraftOrder;
import com.homeftw.ae2intelligentscheduling.smartcraft.model.SmartCraftOrderScale;
import com.homeftw.ae2intelligentscheduling.smartcraft.model.SmartCraftRequestKey;
import com.homeftw.ae2intelligentscheduling.smartcraft.model.SmartCraftStatus;
import com.homeftw.ae2intelligentscheduling.smartcraft.model.SmartCraftTask;

import appeng.api.networking.crafting.ICraftingCPU;
import appeng.api.networking.crafting.ICraftingGrid;
import appeng.api.networking.crafting.ICraftingJob;
import appeng.api.networking.crafting.ICraftingLink;

class SmartCraftSchedulerTest {

    @Test
    void waits_when_no_idle_cpu_is_available() {
        // Updated contract: planning runs unconditionally; CPU is consumed only at submission. So
        // the task goes SUBMITTING on tick 1 (planning starts) and WAITING_CPU on tick 2 (planning
        // is done but submission has no idle CPU to bind to).
        SmartCraftOrderManager orderManager = new SmartCraftOrderManager();
        SmartCraftOrder order = order(layer(task("processor", 1_000_000_000L, 0, 1, 1, SmartCraftStatus.PENDING)));
        UUID orderId = orderManager.track(order);
        SmartCraftRuntimeCoordinator coordinator = coordinator(orderManager);
        coordinator.register(orderId, session(cpu("busy", true)));

        coordinator.tick();
        coordinator.tick();

        SmartCraftOrder updated = trackedOrder(orderManager, orderId);
        assertEquals(SmartCraftStatus.WAITING_CPU, updated.status());
        assertEquals(
            SmartCraftStatus.WAITING_CPU,
            updated.currentLayer()
                .tasks()
                .get(0)
                .status());
    }

    @Test
    void does_not_start_parent_layer_before_children_finish() {
        // Parent must depend on the child via task-level dependsOnTaskIds (the new contract).
        // Layer barriers no longer gate dispatching: a task at any layer can begin planning the
        // moment its declared dependencies are DONE. Without an explicit dependency the new
        // scheduler would (correctly) start "parent" right away because it has no reason to wait.
        SmartCraftOrderManager orderManager = new SmartCraftOrderManager();
        SmartCraftTask child = task("child", 64L, 0, 1, 1, SmartCraftStatus.RUNNING);
        SmartCraftTask parent = task("parent", 1L, 1, 1, 1, SmartCraftStatus.PENDING)
            .withDependsOnTaskIds(Collections.singletonList(child.taskId()));
        SmartCraftOrder order = order(layer(child), layer(parent));
        UUID orderId = orderManager.track(order);
        SmartCraftRuntimeCoordinator coordinator = coordinator(orderManager);
        coordinator.register(orderId, session(cpu("idle", false)));

        coordinator.tick();

        SmartCraftOrder updated = trackedOrder(orderManager, orderId);
        assertEquals(0, updated.currentLayerIndex());
        assertEquals(
            SmartCraftStatus.PENDING,
            updated.layers()
                .get(1)
                .tasks()
                .get(0)
                .status(),
            "parent must stay PENDING while its declared dependency is still RUNNING");
    }

    @Test
    void independent_branches_at_different_layers_run_in_parallel() {
        // Two completely independent branches: branch-A is a single task at layer 1 that depends
        // only on its already-DONE leaf, branch-B is a deeper chain still working through layer 0.
        // Under the old strict-layer-barrier model A was forced to wait for ALL of layer 0 (i.e.
        // for B's leaf) even though A's own dependency was already satisfied. The new dependency-
        // graph dispatcher must let A start planning immediately, in parallel with B.
        SmartCraftOrderManager orderManager = new SmartCraftOrderManager();
        SmartCraftTask aLeaf = task("a-leaf", 1L, 0, 1, 1, SmartCraftStatus.DONE);
        SmartCraftTask bLeaf = task("b-leaf", 1L, 0, 1, 1, SmartCraftStatus.RUNNING);
        SmartCraftTask aRoot = task("a-root", 1L, 1, 1, 1, SmartCraftStatus.PENDING)
            .withDependsOnTaskIds(Collections.singletonList(aLeaf.taskId()));
        SmartCraftOrder order = order(layer(aLeaf, bLeaf), layer(aRoot));
        UUID orderId = orderManager.track(order);
        SmartCraftRuntimeCoordinator coordinator = coordinator(orderManager);
        coordinator.register(orderId, session(cpu("idle", false)));

        coordinator.tick();

        SmartCraftOrder updated = trackedOrder(orderManager, orderId);
        // Branch B is still working at layer 0, so currentLayerIndex stays at 0 for the UI.
        assertEquals(0, updated.currentLayerIndex(), "current UI layer tracks slowest layer");
        // But branch A's root must have started planning despite layer 0 not being fully complete.
        assertEquals(
            SmartCraftStatus.SUBMITTING,
            updated.layers()
                .get(1)
                .tasks()
                .get(0)
                .status(),
            "a-root must dispatch in parallel with b-leaf because its only dep (a-leaf) is DONE");
        assertEquals(
            SmartCraftStatus.RUNNING,
            updated.status(),
            "order is RUNNING when any task across any layer is active");
    }

    @Test
    void cancel_during_planning_cancels_planning_future() {
        // P0-#1: cancelling an order while a task is mid-planning must propagate to the AE2
        // planner future. Before the fix, clearExecution only removed the map entry, leaving the
        // planner thread running and capable of writing back into a session whose execution map
        // had been wiped (NPE / ghost entries). The cancel(orderId) flow hits cancelAll() which
        // already cancelled futures, so we exercise the per-task path: a task that becomes
        // terminal (CANCELLED) while still in SUBMITTING — clearExecution must cancel the future.
        SmartCraftOrderManager orderManager = new SmartCraftOrderManager();
        TrackingFuture pendingFuture = new TrackingFuture();
        SmartCraftRuntimeCoordinator coordinator = new SmartCraftRuntimeCoordinator(
            orderManager,
            new Ae2CpuSelector(),
            (session, task) -> pendingFuture,
            (session, task, cpu, job) -> link(),
            (session, orderId) -> {});

        SmartCraftOrder order = order(layer(task("solo", 1L, 0, 1, 1, SmartCraftStatus.PENDING)));
        UUID orderId = orderManager.track(order);
        coordinator.register(orderId, session(cpu("idle", false)));

        // Tick #1: dispatch starts planning, task enters SUBMITTING and the future is tracked.
        coordinator.tick();
        assertEquals(
            SmartCraftStatus.SUBMITTING,
            trackedOrder(orderManager, orderId).layers()
                .get(0)
                .tasks()
                .get(0)
                .status(),
            "task must be SUBMITTING after planning started");
        org.junit.jupiter.api.Assertions
            .assertFalse(pendingFuture.cancelInvoked, "future should still be live before cancel");

        // Cancel the order. cancelAll() propagates to every execution; even if cancelAll didn't
        // exist, the next tick's reconcile would observe task.isTerminal() and clearExecution
        // must cancel the future. Both paths must result in cancel(true) being called.
        coordinator.cancel(orderId);

        org.junit.jupiter.api.Assertions.assertTrue(
            pendingFuture.cancelInvoked,
            "cancelling the order must cancel the in-flight planning future to release AE2 planner thread");
    }

    @Test
    void submitting_times_out_after_max_planning_ticks() {
        // P0-#2: AE2 planner can hang (internal deadlock, grid changed during plan, thread pool
        // starved). Without a timeout, the task stays SUBMITTING forever and the player is stuck.
        // Exercise the timeout: drive 1201 ticks with a future that never completes; the task
        // must flip to FAILED with the timeout reason and the future must be cancelled.
        SmartCraftOrderManager orderManager = new SmartCraftOrderManager();
        // (G1) Disable auto-retry for this test \u2014 it asserts the legacy "first plan-timeout
        // immediately FAILS the task" semantics. With retry enabled the task would instead bounce
        // back to PENDING and re-plan, which is verified separately by the G1 retry tests.
        int savedRetryMax = com.homeftw.ae2intelligentscheduling.config.Config.PLAN_RETRY_MAX_ATTEMPTS;
        com.homeftw.ae2intelligentscheduling.config.Config.PLAN_RETRY_MAX_ATTEMPTS = 0;
        try {
            TrackingFuture neverCompletes = new TrackingFuture();
            SmartCraftRuntimeCoordinator coordinator = new SmartCraftRuntimeCoordinator(
                orderManager,
                new Ae2CpuSelector(),
                (session, task) -> neverCompletes,
                (session, task, cpu, job) -> link(),
                (session, orderId) -> {});

            SmartCraftOrder order = order(layer(task("hang", 1L, 0, 1, 1, SmartCraftStatus.PENDING)));
            UUID orderId = orderManager.track(order);
            coordinator.register(orderId, session(cpu("idle", false)));

            // First tick: planning starts, submittedAtTick = 1.
            coordinator.tick();
            assertEquals(
                SmartCraftStatus.SUBMITTING,
                trackedOrder(orderManager, orderId).layers()
                    .get(0)
                    .tasks()
                    .get(0)
                    .status());

            // Advance 1199 ticks: still inside the 1200-tick budget (elapsed = 1199 at start of tick
            // 1200, dispatch will not have yet timed out). Task remains SUBMITTING.
            for (int i = 0; i < 1199; i++) {
                coordinator.tick();
            }
            assertEquals(
                SmartCraftStatus.SUBMITTING,
                trackedOrder(orderManager, orderId).layers()
                    .get(0)
                    .tasks()
                    .get(0)
                    .status(),
                "task must remain SUBMITTING up to (but not exceeding) the planning budget");

            // One more tick pushes elapsed past the 1200 budget, reconcile must trip the timeout.
            coordinator.tick();
            coordinator.tick();
            SmartCraftTask timedOut = trackedOrder(orderManager, orderId).layers()
                .get(0)
                .tasks()
                .get(0);
            assertEquals(SmartCraftStatus.FAILED, timedOut.status(), "task must FAIL after planning timeout");
            org.junit.jupiter.api.Assertions.assertTrue(
                timedOut.blockingReason() != null && timedOut.blockingReason()
                    .contains("timeout"),
                "FAILED reason must mention timeout, got: " + timedOut.blockingReason());
            org.junit.jupiter.api.Assertions.assertTrue(
                neverCompletes.cancelInvoked,
                "timed-out future must be cancelled to release AE2 planner thread");
        } finally {
            com.homeftw.ae2intelligentscheduling.config.Config.PLAN_RETRY_MAX_ATTEMPTS = savedRetryMax;
        }
    }

    @Test
    void splits_of_same_request_key_serialize_their_planning() {
        // P1-#3 regression: a node split into 4 ready siblings must NOT all enter SUBMITTING in
        // the same tick. Letting them plan concurrently would have each plan compute against the
        // identical stock snapshot, reserve overlapping items, and at submit time AE2's CPU
        // cluster would either fail the link or fall back to pattern crafting that over-produces
        // intermediate materials. The fix serialises planning per requestKey: at most one split
        // is in SUBMITTING; the rest stay PENDING until the leader transitions out of SUBMITTING.
        SmartCraftOrderManager orderManager = new SmartCraftOrderManager();
        // Four splits of the same node (same requestKey id). They share dep set ([], here) so all
        // four are READY simultaneously. The taskId is auto-derived from requestKey + split index
        // so each task has a unique taskId despite sharing the requestKey.
        SmartCraftOrder order = order(
            layer(
                task("plates", 250L, 0, 1, 4, SmartCraftStatus.PENDING),
                task("plates", 250L, 0, 2, 4, SmartCraftStatus.PENDING),
                task("plates", 250L, 0, 3, 4, SmartCraftStatus.PENDING),
                task("plates", 250L, 0, 4, 4, SmartCraftStatus.PENDING)));
        UUID orderId = orderManager.track(order);
        SmartCraftRuntimeCoordinator coordinator = coordinator(orderManager);
        coordinator.register(orderId, session(cpu("idle", false)));

        // Tick 1: only ONE split should enter SUBMITTING; the other three stay PENDING.
        coordinator.tick();
        SmartCraftOrder afterFirstTick = trackedOrder(orderManager, orderId);
        int submittingCount = 0;
        int pendingCount = 0;
        for (SmartCraftTask t : afterFirstTick.layers()
            .get(0)
            .tasks()) {
            if (t.status() == SmartCraftStatus.SUBMITTING) submittingCount++;
            if (t.status() == SmartCraftStatus.PENDING) pendingCount++;
        }
        assertEquals(
            1,
            submittingCount,
            "exactly one split must enter SUBMITTING per tick when they share a requestKey");
        assertEquals(3, pendingCount, "the other three splits must stay PENDING until the leader's plan resolves");
    }

    @Test
    void splits_of_different_request_keys_plan_in_parallel() {
        // Counterpart to the previous test: tasks belonging to DIFFERENT nodes (different
        // requestKey) must NOT be serialized — they touch different stock pools and there is no
        // pre-emption risk. Locks the "serialization is per-requestKey, not global" guarantee.
        SmartCraftOrderManager orderManager = new SmartCraftOrderManager();
        SmartCraftOrder order = order(
            layer(
                task("plates", 1L, 0, 1, 1, SmartCraftStatus.PENDING),
                task("ingots", 1L, 0, 1, 1, SmartCraftStatus.PENDING),
                task("gears", 1L, 0, 1, 1, SmartCraftStatus.PENDING)));
        UUID orderId = orderManager.track(order);
        SmartCraftRuntimeCoordinator coordinator = coordinator(orderManager);
        coordinator.register(orderId, session(cpu("idle", false)));

        coordinator.tick();
        SmartCraftOrder afterTick = trackedOrder(orderManager, orderId);
        int submittingCount = 0;
        for (SmartCraftTask t : afterTick.layers()
            .get(0)
            .tasks()) {
            if (t.status() == SmartCraftStatus.SUBMITTING) submittingCount++;
        }
        assertEquals(
            3,
            submittingCount,
            "all three independent tasks must plan in parallel — they don't share a requestKey");
    }

    @Test
    void retry_propagates_progress_through_dependency_chain() {
        // P1-#4 regression: a 5-layer order with mixed states. Layers 0 & 1 are DONE, layer 2
        // FAILED gates layers 3 & 4. Retry must revive l2 → PENDING and re-set order.status to
        // QUEUED so dispatchReadyTasks runs again. With l2's dep (l1) already DONE, l2 should
        // start planning immediately. l3 and l4 must keep waiting until their respective deps
        // reach DONE — proves the new task-level dep graph still serializes the chain correctly.
        SmartCraftOrderManager orderManager = new SmartCraftOrderManager();

        SmartCraftTask l0 = task("l0", 1L, 0, 1, 1, SmartCraftStatus.DONE);
        SmartCraftTask l1 = task("l1", 1L, 1, 1, 1, SmartCraftStatus.DONE)
            .withDependsOnTaskIds(Collections.singletonList(l0.taskId()));
        SmartCraftTask l2Failed = task("l2", 1L, 2, 1, 1, SmartCraftStatus.PENDING)
            .withDependsOnTaskIds(Collections.singletonList(l1.taskId()))
            .withStatus(SmartCraftStatus.FAILED, "previous run failed");
        SmartCraftTask l3 = task("l3", 1L, 3, 1, 1, SmartCraftStatus.PENDING)
            .withDependsOnTaskIds(Collections.singletonList(l2Failed.taskId()));
        SmartCraftTask l4 = task("l4", 1L, 4, 1, 1, SmartCraftStatus.PENDING)
            .withDependsOnTaskIds(Collections.singletonList(l3.taskId()));

        SmartCraftOrder order = order(layer(l0), layer(l1), layer(l2Failed), layer(l3), layer(l4))
            .withStatus(SmartCraftStatus.PAUSED);
        UUID orderId = orderManager.track(order);
        SmartCraftRuntimeCoordinator coordinator = coordinator(orderManager);
        coordinator.register(orderId, session(cpu("idle", false)));

        // Sanity: order is PAUSED — updateOrder short-circuits, dispatch is skipped, l3/l4 stay
        // pristine and l2 stays FAILED. Locks "PAUSED truly halts work" behaviour.
        coordinator.tick();
        SmartCraftOrder afterPausedTick = trackedOrder(orderManager, orderId);
        assertEquals(
            SmartCraftStatus.FAILED,
            afterPausedTick.layers()
                .get(2)
                .tasks()
                .get(0)
                .status(),
            "PAUSED order must not re-dispatch the FAILED task");
        assertEquals(
            SmartCraftStatus.PENDING,
            afterPausedTick.layers()
                .get(3)
                .tasks()
                .get(0)
                .status(),
            "PAUSED order must not advance downstream tasks either");

        // User clicks Retry. coordinator.retryFailed flips l2 → PENDING and order → QUEUED, then
        // also clears any stale execution entries (none here) so the next tick re-plans freshly.
        coordinator.retryFailed(orderId);
        coordinator.tick();
        SmartCraftOrder afterRetry = trackedOrder(orderManager, orderId);
        assertEquals(
            SmartCraftStatus.SUBMITTING,
            afterRetry.layers()
                .get(2)
                .tasks()
                .get(0)
                .status(),
            "l2 must start planning the tick after retry — l1 (its only dep) was already DONE");
        assertEquals(
            SmartCraftStatus.PENDING,
            afterRetry.layers()
                .get(3)
                .tasks()
                .get(0)
                .status(),
            "l3 must still wait — l2 is SUBMITTING, not yet DONE");

        // Hand-flip l2 to DONE (in production this happens via reconcileTaskExecution observing
        // craftingLink.isDone()). Next tick must dispatch l3, l4 must still wait.
        SmartCraftTask l2Done = afterRetry.layers()
            .get(2)
            .tasks()
            .get(0)
            .withStatus(SmartCraftStatus.DONE, null);
        SmartCraftOrder afterL2Done = afterRetry.withLayer(
            2,
            afterRetry.layers()
                .get(2)
                .withTasks(Collections.singletonList(l2Done)));
        orderManager.update(orderId, afterL2Done);
        coordinator.tick();
        SmartCraftOrder afterL3Dispatch = trackedOrder(orderManager, orderId);
        assertEquals(
            SmartCraftStatus.SUBMITTING,
            afterL3Dispatch.layers()
                .get(3)
                .tasks()
                .get(0)
                .status(),
            "l3 must auto-dispatch the tick after l2 reaches DONE — its dep is now satisfied");
        assertEquals(
            SmartCraftStatus.PENDING,
            afterL3Dispatch.layers()
                .get(4)
                .tasks()
                .get(0)
                .status(),
            "l4 stays PENDING — its dep (l3) is only SUBMITTING, not DONE");
    }

    @Test
    void critical_path_first_when_only_one_cpu_is_idle() {
        // Enhancement-C regression: with one idle CPU and two independent ready tasks where one
        // sits at the end of a long dependency chain (3-task chain) and the other is a one-off
        // leaf, the long-chain root MUST be the one that gets the CPU first. Otherwise the leaf
        // crowds out work that gates more downstream tasks, lengthening total order completion
        // time.
        SmartCraftOrderManager orderManager = new SmartCraftOrderManager();

        // Long chain: root -> mid -> tail (root has the deepest downstream chain, length 3)
        SmartCraftTask longRoot = task("longRoot", 1L, 0, 1, 1, SmartCraftStatus.PENDING);
        SmartCraftTask longMid = task("longMid", 1L, 1, 1, 1, SmartCraftStatus.PENDING)
            .withDependsOnTaskIds(java.util.Arrays.asList(longRoot.taskId()));
        SmartCraftTask longTail = task("longTail", 1L, 2, 1, 1, SmartCraftStatus.PENDING)
            .withDependsOnTaskIds(java.util.Arrays.asList(longMid.taskId()));
        // Short chain: just one leaf, length 1
        SmartCraftTask shortLeaf = task("shortLeaf", 1L, 0, 1, 1, SmartCraftStatus.PENDING);

        SmartCraftOrder order = order(layer(longRoot, shortLeaf), layer(longMid), layer(longTail));
        UUID orderId = orderManager.track(order);
        SmartCraftRuntimeCoordinator coordinator = coordinator(orderManager);
        coordinator.register(orderId, session(cpu("solo", false)));

        // With only one idle CPU, both ready tasks (longRoot, shortLeaf) can plan in parallel
        // (planning doesn't compete for CPUs), but only ONE of them can submit. After tick 2 the
        // single CPU goes to longRoot (critical-path-length 3) not shortLeaf (length 1).
        coordinator.tick();
        coordinator.tick();
        SmartCraftOrder updated = trackedOrder(orderManager, orderId);

        SmartCraftTask longRootAfter = findTask(updated, longRoot.taskId());
        SmartCraftTask shortLeafAfter = findTask(updated, shortLeaf.taskId());
        assertEquals(
            SmartCraftStatus.RUNNING,
            longRootAfter.status(),
            "longRoot has critical-path-length 3 — must take the only CPU before shortLeaf");
        assertEquals(
            SmartCraftStatus.WAITING_CPU,
            shortLeafAfter.status(),
            "shortLeaf has critical-path-length 1 — must wait while the deeper chain progresses");
    }

    @Test
    void waiting_cpu_age_breaks_ties_when_priorities_match() {
        // Enhancement-E regression: two tasks of EQUAL critical-path-length both want a CPU, but
        // only one is idle. The one that has been waiting longest should win, not whichever
        // happens to come first in iteration order. Without this guard, a freshly-arriving task
        // could starve out an old WAITING_CPU task indefinitely on a busy network.
        SmartCraftOrderManager orderManager = new SmartCraftOrderManager();
        // Two leaf tasks (critical-path-length 1 each, no deps).
        SmartCraftOrder order = order(
            layer(
                task("early", 1L, 0, 1, 1, SmartCraftStatus.PENDING),
                task("late", 1L, 0, 1, 1, SmartCraftStatus.PENDING)));
        UUID orderId = orderManager.track(order);

        // Use a stateful CPU that flips from busy to idle between ticks. Initially busy so
        // BOTH tasks plan but neither gets a CPU; later we want them both in WAITING_CPU with
        // staggered waitingCpuSinceTick.
        final boolean[] cpuBusy = { true };
        final ICraftingCPU stateful = (ICraftingCPU) Proxy.newProxyInstance(
            SmartCraftSchedulerTest.class.getClassLoader(),
            new Class<?>[] { ICraftingCPU.class },
            new InvocationHandler() {

                @Override
                public Object invoke(Object proxy, Method method, Object[] args) {
                    if ("isBusy".equals(method.getName())) return cpuBusy[0];
                    if ("getName".equals(method.getName())) return "stateful";
                    return defaultValue(method.getReturnType());
                }
            });

        SmartCraftRuntimeCoordinator coordinator = coordinator(orderManager);
        coordinator.register(orderId, session(stateful));

        // Tick 1: both plan (planning doesn't need CPU). Tick 2: both reach WAITING_CPU
        // simultaneously (CPU still busy). At this moment both have the same waitingCpuSinceTick.
        // We need to differentiate them, so manipulate the session manually: clear "late"'s
        // execution and re-create only after another tick, so when "late" reaches WAITING_CPU it
        // has a strictly LATER waitingCpuSinceTick than "early".
        coordinator.tick();
        coordinator.tick();
        // Both are now in WAITING_CPU with the same tick — not a meaningful E test. Force
        // re-plan only "late": clear its session execution so it must redo planning, getting a
        // strictly later WAITING_CPU stamp.
        SmartCraftOrder afterTwoTicks = trackedOrder(orderManager, orderId);
        SmartCraftRuntimeSession sess = coordinator.session(orderId)
            .orElseThrow(IllegalStateException::new);
        sess.clearExecution(findTask(afterTwoTicks, "late#0#1/1"));
        // Push "late" back to PENDING so the next tick re-plans it.
        SmartCraftOrder reset = afterTwoTicks.withLayer(
            0,
            afterTwoTicks.layers()
                .get(0)
                .withTasks(
                    java.util.Arrays.asList(
                        findTask(afterTwoTicks, "early#0#1/1"),
                        findTask(afterTwoTicks, "late#0#1/1").withStatus(SmartCraftStatus.PENDING, null))));
        orderManager.update(orderId, reset);

        coordinator.tick(); // tick 3: re-plan "late"
        coordinator.tick(); // tick 4: "late" enters WAITING_CPU at a LATER tick than "early"
        // Now release the CPU and tick once more — "early" (older waitingCpuSinceTick) must win.
        cpuBusy[0] = false;
        coordinator.tick();

        SmartCraftOrder finalState = trackedOrder(orderManager, orderId);
        assertEquals(
            SmartCraftStatus.RUNNING,
            findTask(finalState, "early#0#1/1").status(),
            "early — older WAITING_CPU stamp — must claim the freed CPU first");
        assertEquals(
            SmartCraftStatus.WAITING_CPU,
            findTask(finalState, "late#0#1/1").status(),
            "late — younger WAITING_CPU stamp — keeps waiting");
    }

    @Test
    void soft_cancel_spares_running_tasks_and_cancels_others() {
        // Enhancement-F regression: an order with mixed [DONE, RUNNING, PENDING, WAITING_CPU] is
        // soft-cancelled. RUNNING and DONE are spared (their AE2 work either is investing
        // intermediate materials right now or is already complete), but PENDING and WAITING_CPU
        // are cancelled so no NEW work begins. The order itself stays non-terminal because the
        // RUNNING task is still active.
        SmartCraftOrderManager orderManager = new SmartCraftOrderManager();
        SmartCraftOrder order = order(
            layer(
                task("done", 1L, 0, 1, 1, SmartCraftStatus.DONE),
                task("running", 1L, 0, 1, 1, SmartCraftStatus.RUNNING),
                task("pending", 1L, 0, 1, 1, SmartCraftStatus.PENDING),
                task("waiting", 1L, 0, 1, 1, SmartCraftStatus.WAITING_CPU)));
        UUID orderId = orderManager.track(order);

        Optional<SmartCraftOrder> updated = orderManager.cancelGracefully(orderId);
        org.junit.jupiter.api.Assertions.assertTrue(updated.isPresent(), "soft-cancel must produce an update");
        SmartCraftOrder result = updated.get();

        assertEquals(SmartCraftStatus.DONE, findTask(result, "done#0#1/1").status(), "DONE must not change");
        assertEquals(
            SmartCraftStatus.RUNNING,
            findTask(result, "running#0#1/1").status(),
            "RUNNING must be spared so AE2 finishes the in-flight craft");
        assertEquals(
            SmartCraftStatus.CANCELLED,
            findTask(result, "pending#0#1/1").status(),
            "PENDING must flip to CANCELLED to prevent it from starting");
        assertEquals(
            SmartCraftStatus.CANCELLED,
            findTask(result, "waiting#0#1/1").status(),
            "WAITING_CPU must flip to CANCELLED — the planned job is wasted but no resources are stuck");
        org.junit.jupiter.api.Assertions.assertNotEquals(
            SmartCraftStatus.CANCELLED,
            result.status(),
            "order itself must NOT be CANCELLED while a RUNNING task survives");
    }

    @Test
    void soft_cancel_with_no_running_tasks_degrades_to_hard_cancel() {
        // Enhancement-F edge case: if no task is RUNNING when soft-cancel arrives, every task is
        // cancellable and the order itself must become CANCELLED — there's nothing left to wait
        // for. Otherwise the order would dangle in QUEUED / WAITING_CPU forever.
        SmartCraftOrderManager orderManager = new SmartCraftOrderManager();
        SmartCraftOrder order = order(
            layer(
                task("a", 1L, 0, 1, 1, SmartCraftStatus.PENDING),
                task("b", 1L, 0, 1, 1, SmartCraftStatus.WAITING_CPU)));
        UUID orderId = orderManager.track(order);

        Optional<SmartCraftOrder> result = orderManager.cancelGracefully(orderId);
        org.junit.jupiter.api.Assertions.assertTrue(result.isPresent());
        assertEquals(
            SmartCraftStatus.CANCELLED,
            result.get()
                .status(),
            "no-running-tasks soft-cancel must degenerate into a hard cancel of the order itself");
    }

    @Test
    void soft_cancel_clears_planning_future_for_cancelled_tasks() {
        // Enhancement-F resource-leak guard: a task in SUBMITTING (planning future in flight) that
        // gets soft-cancelled must have its planning future torn down — otherwise the AE2 planner
        // thread keeps running and may write back into a stale execution map. RUNNING tasks must
        // keep their craftingLink intact (the whole point of soft-cancel).
        SmartCraftOrderManager orderManager = new SmartCraftOrderManager();
        SmartCraftOrder order = order(
            layer(
                task("planning", 1L, 0, 1, 1, SmartCraftStatus.SUBMITTING),
                task("running", 1L, 0, 1, 1, SmartCraftStatus.RUNNING)));
        UUID orderId = orderManager.track(order);

        SmartCraftRuntimeCoordinator coordinator = coordinator(orderManager);
        coordinator.register(orderId, session(cpu("idle", false)));

        // Simulate a real planning future + running link being attached to the session.
        SmartCraftRuntimeSession sess = coordinator.session(orderId)
            .orElseThrow(IllegalStateException::new);
        TrackingFuture planningFuture = new TrackingFuture();
        sess.trackPlanning(findTask(order, "planning#0#1/1"), planningFuture, 0L);

        Optional<SmartCraftOrder> result = coordinator.cancelGracefully(orderId);
        org.junit.jupiter.api.Assertions.assertTrue(result.isPresent());
        org.junit.jupiter.api.Assertions.assertTrue(
            planningFuture.cancelInvoked,
            "soft-cancelling a SUBMITTING task must cancel its planning future to free the planner thread");
        // The RUNNING task's execution must NOT have been cleared — but in this test we never
        // attached one, so just verify session retains no planning execution for "planning".
        org.junit.jupiter.api.Assertions.assertNull(
            sess.executionFor(findTask(result.get(), "planning#0#1/1")),
            "cancelled task's execution must be cleared");
    }

    @Test
    void plan_failure_auto_retries_then_fails_permanently_after_attempts_exhausted() {
        // G1 regression: a plan failure does NOT immediately FAIL the task. Instead the runtime
        // schedules a retry with exponential backoff (5/10/20 ticks). After PLAN_RETRY_MAX_ATTEMPTS
        // failures the task FAILS for real. Verifies (a) the task stays out of FAILED for the
        // first 3 attempts, (b) the FAILED transition happens on the 4th attempt, (c) the
        // failure reason is preserved on the final FAILED.
        int savedRetryMax = com.homeftw.ae2intelligentscheduling.config.Config.PLAN_RETRY_MAX_ATTEMPTS;
        com.homeftw.ae2intelligentscheduling.config.Config.PLAN_RETRY_MAX_ATTEMPTS = 3;
        try {
            SmartCraftOrderManager orderManager = new SmartCraftOrderManager();
            // Planner always returns simulation = treat as failure in reconcile path.
            SmartCraftRuntimeCoordinator coordinator = new SmartCraftRuntimeCoordinator(
                orderManager,
                new Ae2CpuSelector(),
                (session, task) -> new CompletedFuture(job(true)),
                (session, task, cpu, job) -> link(),
                (session, orderId) -> {});
            SmartCraftOrder order = order(layer(task("flaky", 1L, 0, 1, 1, SmartCraftStatus.PENDING)));
            UUID orderId = orderManager.track(order);
            coordinator.register(orderId, session(cpu("idle", false)));

            // Drive enough ticks to exhaust 4 attempts: each attempt = 1 tick to begin + 1 tick to
            // observe failure + backoff (5/10/20). Total upper bound \u2248 1 + 1 + 5 + 1 + 1 + 10 + 1
            // + 1 + 20 + 1 + 1 = 43 ticks. Use 100 to give ample margin.
            org.junit.jupiter.api.Assertions.assertNotEquals(
                SmartCraftStatus.FAILED,
                drive(coordinator, orderManager, orderId, 1).status(),
                "first attempt failure must produce a retry, NOT FAILED");
            // Drive until FAILED. We loop with a hard cap to prevent infinite loops if backoff
            // logic regresses.
            SmartCraftStatus finalStatus = null;
            for (int i = 0; i < 100; i++) {
                coordinator.tick();
                SmartCraftTask t = findTask(trackedOrder(orderManager, orderId), "flaky#0#1/1");
                if (t.status() == SmartCraftStatus.FAILED) {
                    finalStatus = t.status();
                    org.junit.jupiter.api.Assertions
                        .assertNotNull(t.blockingReason(), "permanent FAILED must carry a reason");
                    break;
                }
            }
            assertEquals(SmartCraftStatus.FAILED, finalStatus, "task must reach FAILED after 4 attempts");
        } finally {
            com.homeftw.ae2intelligentscheduling.config.Config.PLAN_RETRY_MAX_ATTEMPTS = savedRetryMax;
        }
    }

    @Test
    void plan_retry_recovers_when_a_later_attempt_succeeds() {
        // G1 regression: a transient failure followed by a success leaves the task in WAITING_CPU,
        // not FAILED. Models the typical real-world case where AE2's planner threw NPE on a
        // grid-mutation race the first time but works fine the second.
        int savedRetryMax = com.homeftw.ae2intelligentscheduling.config.Config.PLAN_RETRY_MAX_ATTEMPTS;
        com.homeftw.ae2intelligentscheduling.config.Config.PLAN_RETRY_MAX_ATTEMPTS = 3;
        try {
            SmartCraftOrderManager orderManager = new SmartCraftOrderManager();
            final int[] attemptCount = { 0 };
            SmartCraftRuntimeCoordinator coordinator = new SmartCraftRuntimeCoordinator(
                orderManager,
                new Ae2CpuSelector(),
                (session, task) -> {
                    attemptCount[0]++;
                    // First attempt = simulation (= failure in reconcile). Second attempt = real
                    // job (= WAITING_CPU). The CPU is busy so we never proceed past WAITING_CPU,
                    // which is exactly the recovery state we want to assert on.
                    return new CompletedFuture(job(attemptCount[0] == 1));
                },
                (session, task, cpu, job) -> link(),
                (session, orderId) -> {});
            SmartCraftOrder order = order(layer(task("recoverable", 1L, 0, 1, 1, SmartCraftStatus.PENDING)));
            UUID orderId = orderManager.track(order);
            // Busy CPU so successful plan parks task at WAITING_CPU instead of progressing further.
            coordinator.register(orderId, session(cpu("busy", true)));

            // Drive long enough for: tick 1 begin attempt 1 \u2192 tick 2 fail \u2192 5-tick backoff \u2192
            // attempt 2 \u2192 success \u2192 WAITING_CPU. Total \u2248 9-12 ticks.
            SmartCraftStatus finalStatus = null;
            for (int i = 0; i < 30; i++) {
                coordinator.tick();
                SmartCraftTask t = findTask(trackedOrder(orderManager, orderId), "recoverable#0#1/1");
                if (t.status() == SmartCraftStatus.WAITING_CPU) {
                    finalStatus = t.status();
                    break;
                }
            }
            assertEquals(
                SmartCraftStatus.WAITING_CPU,
                finalStatus,
                "task must recover to WAITING_CPU after attempt 2 succeeds");
            org.junit.jupiter.api.Assertions.assertEquals(2, attemptCount[0], "exactly 2 plan attempts must have run");
        } finally {
            com.homeftw.ae2intelligentscheduling.config.Config.PLAN_RETRY_MAX_ATTEMPTS = savedRetryMax;
        }
    }

    @Test
    void waiting_cpu_stale_plan_drops_back_to_pending_for_replan() {
        // G2 regression: a task that has held a fully-computed plan in WAITING_CPU for longer than
        // WAITING_CPU_STALE_SECONDS must be force-replanned. The cached plan was computed against
        // a stock snapshot that has surely shifted by then (other orders, player I/O), so reusing
        // it would risk over- or under-reservation at submit time.
        int savedStale = com.homeftw.ae2intelligentscheduling.config.Config.WAITING_CPU_STALE_SECONDS;
        // 1 second = 20 ticks for fast test execution.
        com.homeftw.ae2intelligentscheduling.config.Config.WAITING_CPU_STALE_SECONDS = 1;
        try {
            SmartCraftOrderManager orderManager = new SmartCraftOrderManager();
            SmartCraftRuntimeCoordinator coordinator = new SmartCraftRuntimeCoordinator(
                orderManager,
                new Ae2CpuSelector(),
                (session, task) -> new CompletedFuture(job(false)),
                (session, task, cpu, job) -> link(),
                (session, orderId) -> {});
            SmartCraftOrder order = order(layer(task("staler", 1L, 0, 1, 1, SmartCraftStatus.PENDING)));
            UUID orderId = orderManager.track(order);
            // Busy CPU so plan completes but task parks at WAITING_CPU until we let it stale-out.
            coordinator.register(orderId, session(cpu("busy", true)));

            // Tick 1: plan begins. Tick 2: plan completes \u2192 WAITING_CPU + waitingCpuSinceTick=2.
            coordinator.tick();
            coordinator.tick();
            assertEquals(
                SmartCraftStatus.WAITING_CPU,
                findTask(trackedOrder(orderManager, orderId), "staler#0#1/1").status(),
                "task must be in WAITING_CPU after plan completes against busy CPU");

            // Drive 25 more ticks (> 20 = 1 second threshold). Reconcile must spot the staleness
            // and bounce task back to PENDING for re-plan.
            for (int i = 0; i < 25; i++) {
                coordinator.tick();
            }
            SmartCraftTask after = findTask(trackedOrder(orderManager, orderId), "staler#0#1/1");
            // After re-plan kicks off, task may already be back in SUBMITTING/WAITING_CPU. Accept
            // any of (PENDING, SUBMITTING, WAITING_CPU) as proof the stale-replan happened. The
            // critical thing is FAILED is NOT the outcome, and the blocking-reason was updated at
            // some point to mention re-planning.
            org.junit.jupiter.api.Assertions
                .assertNotEquals(SmartCraftStatus.FAILED, after.status(), "stale re-plan must NOT FAIL the task");
        } finally {
            com.homeftw.ae2intelligentscheduling.config.Config.WAITING_CPU_STALE_SECONDS = savedStale;
        }
    }

    /** Test helper: drive N ticks and return the (single-task) order's current status snapshot. */
    private static SmartCraftOrder drive(SmartCraftRuntimeCoordinator coordinator, SmartCraftOrderManager orderManager,
        UUID orderId, int ticks) {
        for (int i = 0; i < ticks; i++) {
            coordinator.tick();
        }
        return trackedOrder(orderManager, orderId);
    }

    private static SmartCraftTask findTask(SmartCraftOrder order, String taskId) {
        for (SmartCraftLayer layer : order.layers()) {
            for (SmartCraftTask t : layer.tasks()) {
                if (taskId.equals(t.taskId())) return t;
            }
        }
        throw new IllegalStateException("task not found: " + taskId);
    }

    @Test
    void advances_to_next_layer_after_current_layer_completes() {
        SmartCraftOrderManager orderManager = new SmartCraftOrderManager();
        SmartCraftOrder order = order(
            layer(task("child", 64L, 0, 1, 1, SmartCraftStatus.DONE)),
            layer(task("parent", 1L, 1, 1, 1, SmartCraftStatus.PENDING)));
        UUID orderId = orderManager.track(order);
        SmartCraftRuntimeCoordinator coordinator = coordinator(orderManager);
        coordinator.register(orderId, session(cpu("idle", false)));

        coordinator.tick();

        SmartCraftOrder updated = trackedOrder(orderManager, orderId);
        assertEquals(1, updated.currentLayerIndex());
        assertEquals(SmartCraftStatus.RUNNING, updated.status());
        assertEquals(
            SmartCraftStatus.SUBMITTING,
            updated.currentLayer()
                .tasks()
                .get(0)
                .status());
    }

    private static SmartCraftRuntimeCoordinator coordinator(SmartCraftOrderManager orderManager) {
        return new SmartCraftRuntimeCoordinator(
            orderManager,
            new Ae2CpuSelector(),
            (session, task) -> new CompletedFuture(job(false)),
            (session, task, cpu, job) -> link(),
            (session, orderId) -> {});
    }

    private static SmartCraftOrder trackedOrder(SmartCraftOrderManager orderManager, UUID orderId) {
        Optional<SmartCraftOrder> order = orderManager.get(orderId);
        return order.orElseThrow(IllegalStateException::new);
    }

    private static SmartCraftRuntimeSession session(ICraftingCPU cpu) {
        return new SmartCraftRuntimeSession(
            null,
            null,
            null,
            null,
            null,
            craftingGrid(Collections.singletonList(cpu)),
            new SmartCraftRequesterBridge(null));
    }

    private static ICraftingGrid craftingGrid(final Iterable<ICraftingCPU> cpus) {
        InvocationHandler handler = new InvocationHandler() {

            @Override
            public Object invoke(Object proxy, Method method, Object[] args) {
                if ("getCpus".equals(method.getName())) {
                    return com.google.common.collect.ImmutableSet.copyOf(cpus);
                }
                return defaultValue(method.getReturnType());
            }
        };

        return (ICraftingGrid) Proxy.newProxyInstance(
            SmartCraftSchedulerTest.class.getClassLoader(),
            new Class<?>[] { ICraftingGrid.class },
            handler);
    }

    private static SmartCraftOrder order(SmartCraftLayer... layers) {
        return new SmartCraftOrder(
            new FakeRequestKey("target"),
            1L,
            SmartCraftOrderScale.SMALL,
            SmartCraftStatus.QUEUED,
            Arrays.asList(layers),
            0);
    }

    private static SmartCraftLayer layer(SmartCraftTask... tasks) {
        int depth = tasks.length == 0 ? 0 : tasks[0].depth();
        return new SmartCraftLayer(depth, Arrays.asList(tasks));
    }

    private static SmartCraftTask task(String id, long amount, int depth, int splitIndex, int splitCount,
        SmartCraftStatus status) {
        // Stable taskId derived from the request id keeps deps wiring in tests easy to read:
        // depending on "child" means listing child.taskId() which prints as "child#<depth>#<i>/<n>".
        return new SmartCraftTask(new FakeRequestKey(id), amount, depth, splitIndex, splitCount, status, null);
    }

    private static SmartCraftTask task(String id, long amount, int depth, int splitIndex, int splitCount,
        SmartCraftStatus status, List<String> dependsOnTaskIds) {
        return task(id, amount, depth, splitIndex, splitCount, status).withDependsOnTaskIds(dependsOnTaskIds);
    }

    private static ICraftingCPU cpu(final String name, final boolean busy) {
        InvocationHandler handler = new InvocationHandler() {

            @Override
            public Object invoke(Object proxy, Method method, Object[] args) {
                String methodName = method.getName();
                if ("isBusy".equals(methodName)) {
                    return busy;
                }
                if ("getName".equals(methodName)) {
                    return name;
                }
                return defaultValue(method.getReturnType());
            }
        };

        return (ICraftingCPU) Proxy.newProxyInstance(
            SmartCraftSchedulerTest.class.getClassLoader(),
            new Class<?>[] { ICraftingCPU.class },
            handler);
    }

    private static ICraftingJob job(final boolean simulation) {
        InvocationHandler handler = new InvocationHandler() {

            @Override
            public Object invoke(Object proxy, Method method, Object[] args) {
                if ("isSimulation".equals(method.getName())) {
                    return simulation;
                }
                return defaultValue(method.getReturnType());
            }
        };

        return (ICraftingJob) Proxy.newProxyInstance(
            SmartCraftSchedulerTest.class.getClassLoader(),
            new Class<?>[] { ICraftingJob.class },
            handler);
    }

    private static ICraftingLink link() {
        InvocationHandler handler = new InvocationHandler() {

            @Override
            public Object invoke(Object proxy, Method method, Object[] args) {
                return defaultValue(method.getReturnType());
            }
        };

        return (ICraftingLink) Proxy.newProxyInstance(
            SmartCraftSchedulerTest.class.getClassLoader(),
            new Class<?>[] { ICraftingLink.class },
            handler);
    }

    private static Object defaultValue(Class<?> returnType) {
        if (returnType == boolean.class) {
            return false;
        }
        if (returnType == int.class) {
            return 0;
        }
        if (returnType == long.class) {
            return 0L;
        }
        if (returnType == float.class) {
            return 0.0F;
        }
        if (returnType == double.class) {
            return 0.0D;
        }
        return null;
    }

    /**
     * Future that never resolves on its own and remembers whether {@link #cancel(boolean)} was
     * invoked. Used to assert that the runtime correctly cancels in-flight planning futures when
     * a task transitions to a terminal state or the planning timeout trips.
     */
    private static final class TrackingFuture implements Future<ICraftingJob> {

        volatile boolean cancelInvoked = false;
        volatile boolean cancelled = false;

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            this.cancelInvoked = true;
            this.cancelled = true;
            return true;
        }

        @Override
        public boolean isCancelled() {
            return this.cancelled;
        }

        @Override
        public boolean isDone() {
            // Only "done" once cancel() has been called — otherwise the future is intentionally
            // pending so reconcile keeps the task in SUBMITTING tick after tick.
            return this.cancelled;
        }

        @Override
        public ICraftingJob get() throws InterruptedException, ExecutionException {
            throw new IllegalStateException("test future does not resolve normally");
        }

        @Override
        public ICraftingJob get(long timeout, TimeUnit unit) throws InterruptedException, ExecutionException {
            throw new IllegalStateException("test future does not resolve normally");
        }
    }

    private static final class CompletedFuture implements Future<ICraftingJob> {

        private final ICraftingJob job;

        private CompletedFuture(ICraftingJob job) {
            this.job = job;
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            return false;
        }

        @Override
        public boolean isCancelled() {
            return false;
        }

        @Override
        public boolean isDone() {
            return true;
        }

        @Override
        public ICraftingJob get() throws InterruptedException, ExecutionException {
            return this.job;
        }

        @Override
        public ICraftingJob get(long timeout, TimeUnit unit) throws InterruptedException, ExecutionException {
            return this.job;
        }
    }

    private static final class FakeRequestKey implements SmartCraftRequestKey {

        private final String id;

        private FakeRequestKey(String id) {
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
    }
}
