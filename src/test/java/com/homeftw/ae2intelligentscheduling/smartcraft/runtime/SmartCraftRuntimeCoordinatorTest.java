package com.homeftw.ae2intelligentscheduling.smartcraft.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.Collections;
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

class SmartCraftRuntimeCoordinatorTest {

    @Test
    void order_completion_notifier_fires_exactly_once_on_completed_transition_v0181() {
        // (v0.1.8.1 G8) End-to-end happy path. The notifier must fire exactly once on the tick
        // the order's status flips into COMPLETED, and must NOT re-fire on subsequent ticks
        // (terminal-orders-vanish removes the order on the next tick, so any re-fire would
        // indicate a bug in either the rising-edge detection or the vanish policy).
        SmartCraftOrderManager orderManager = new SmartCraftOrderManager();
        MutableLinkState linkState = new MutableLinkState();
        RecordingCompletionNotifier notifier = new RecordingCompletionNotifier();
        SmartCraftRuntimeCoordinator coordinator = new SmartCraftRuntimeCoordinator(
            orderManager,
            new Ae2CpuSelector(),
            (session, task) -> new CompletedFuture(job(false)),
            (session, task, cpu, job) -> link(linkState),
            new RecordingSync(),
            notifier);
        SmartCraftOrder order = order(task("task-1", SmartCraftStatus.PENDING));
        UUID orderId = orderManager.track(order);
        coordinator.register(orderId, session(cpu("cpu-1", false)));

        // Tick 1: planning starts. Tick 2: plan resolves + submit -> RUNNING. Notifier must NOT
        // have fired yet \u2014 the order status is still RUNNING through here.
        coordinator.tick();
        coordinator.tick();
        org.junit.jupiter.api.Assertions.assertEquals(
            0,
            notifier.firedCount(),
            "notifier must not fire while order is still RUNNING");

        // Tick 3: link.isDone() observed -> task DONE -> applyLayerStatus -> order COMPLETED.
        // This is the rising edge; notifier fires exactly once.
        linkState.done = true;
        coordinator.tick();
        org.junit.jupiter.api.Assertions.assertEquals(
            1,
            notifier.firedCount(),
            "notifier must fire exactly once on the COMPLETED transition tick");
        org.junit.jupiter.api.Assertions.assertEquals(orderId, notifier.completedOrders.get(0));

        // Tick 4+: order vanishes (terminal-orders-vanish 1-tick delay), notifier must NOT re-fire.
        coordinator.tick();
        coordinator.tick();
        org.junit.jupiter.api.Assertions.assertEquals(
            1,
            notifier.firedCount(),
            "notifier must not re-fire after the COMPLETED tick \u2014 vanish should remove the order");
    }

    @Test
    void order_completion_notifier_does_not_fire_for_failed_or_cancelled_orders_v0181() {
        // (v0.1.8.1 G8) Negative path. Notifier wiring must NOT confuse FAILED (or PAUSED, the
        // more common failure surface for multi-task orders) with COMPLETED. We use a simulation
        // planner + zero plan retry budget to drive the order into PAUSED on the second tick,
        // then spin many ticks and assert the notifier never fired.
        int savedPlanMax = com.homeftw.ae2intelligentscheduling.config.Config.PLAN_RETRY_MAX_ATTEMPTS;
        int savedAutoMax = com.homeftw.ae2intelligentscheduling.config.Config.ORDER_AUTO_RETRY_MAX_ATTEMPTS;
        com.homeftw.ae2intelligentscheduling.config.Config.PLAN_RETRY_MAX_ATTEMPTS = 0;
        com.homeftw.ae2intelligentscheduling.config.Config.ORDER_AUTO_RETRY_MAX_ATTEMPTS = 0;
        try {
            SmartCraftOrderManager orderManager = new SmartCraftOrderManager();
            RecordingCompletionNotifier notifier = new RecordingCompletionNotifier();
            SmartCraftRuntimeCoordinator coordinator = new SmartCraftRuntimeCoordinator(
                orderManager,
                new Ae2CpuSelector(),
                (session, task) -> new CompletedFuture(job(true)), // simulation = task FAILs
                (session, task, cpu, job) -> link(new MutableLinkState()),
                new RecordingSync(),
                notifier);
            SmartCraftOrder order = order(task("task-1", SmartCraftStatus.PENDING));
            UUID orderId = orderManager.track(order);
            coordinator.register(orderId, session(cpu("cpu-1", false)));

            for (int i = 0; i < 50; i++) {
                coordinator.tick();
            }
            org.junit.jupiter.api.Assertions.assertEquals(
                0,
                notifier.firedCount(),
                "notifier must never fire for FAILED/PAUSED orders \u2014 only COMPLETED");
            SmartCraftStatus finalStatus = orderManager.get(orderId)
                .get()
                .status();
            org.junit.jupiter.api.Assertions.assertTrue(
                finalStatus == SmartCraftStatus.PAUSED || finalStatus == SmartCraftStatus.FAILED,
                "test setup invariant: order must end in PAUSED/FAILED, got: " + finalStatus);
        } finally {
            com.homeftw.ae2intelligentscheduling.config.Config.PLAN_RETRY_MAX_ATTEMPTS = savedPlanMax;
            com.homeftw.ae2intelligentscheduling.config.Config.ORDER_AUTO_RETRY_MAX_ATTEMPTS = savedAutoMax;
        }
    }

    @Test
    void progresses_task_from_submitting_to_running_to_done() {
        SmartCraftOrderManager orderManager = new SmartCraftOrderManager();
        RecordingSync sync = new RecordingSync();
        MutableLinkState linkState = new MutableLinkState();
        SmartCraftRuntimeCoordinator coordinator = new SmartCraftRuntimeCoordinator(
            orderManager,
            new Ae2CpuSelector(),
            (session, task) -> new CompletedFuture(job(false)),
            (session, task, cpu, job) -> link(linkState),
            sync);
        SmartCraftOrder order = order(task("task-1", SmartCraftStatus.PENDING));
        UUID orderId = orderManager.track(order);
        coordinator.register(orderId, session(cpu("cpu-1", false)));

        coordinator.tick();

        SmartCraftOrder afterFirstTick = orderManager.get(orderId)
            .get();
        assertEquals(
            SmartCraftStatus.SUBMITTING,
            afterFirstTick.currentLayer()
                .tasks()
                .get(0)
                .status());

        coordinator.tick();

        SmartCraftOrder afterSecondTick = orderManager.get(orderId)
            .get();
        assertEquals(
            SmartCraftStatus.RUNNING,
            afterSecondTick.currentLayer()
                .tasks()
                .get(0)
                .status());

        linkState.done = true;
        coordinator.tick();

        SmartCraftOrder afterThirdTick = orderManager.get(orderId)
            .get();
        assertEquals(SmartCraftStatus.COMPLETED, afterThirdTick.status());
        assertEquals(
            SmartCraftStatus.DONE,
            afterThirdTick.layers()
                .get(0)
                .tasks()
                .get(0)
                .status());
        assertEquals(3, sync.syncCount);
    }

    @Test
    void cancel_then_retry_revives_cancelled_tasks_and_resumes_dispatch() {
        // Regression: cancel used to drop the session from the coordinator, leaving retry()
        // unable to advance the order on the next tick. Now sessions are kept for the lifetime
        // of the order in the manager, and retry revives both FAILED and CANCELLED tasks.
        SmartCraftOrderManager orderManager = new SmartCraftOrderManager();
        RecordingSync sync = new RecordingSync();
        MutableLinkState linkState = new MutableLinkState();
        SmartCraftRuntimeCoordinator coordinator = new SmartCraftRuntimeCoordinator(
            orderManager,
            new Ae2CpuSelector(),
            (session, task) -> new CompletedFuture(job(false)),
            (session, task, cpu, job) -> link(linkState),
            sync);
        SmartCraftOrder order = order(task("task-1", SmartCraftStatus.PENDING));
        UUID orderId = orderManager.track(order);
        coordinator.register(orderId, session(cpu("cpu-1", false)));

        // First tick begins planning, second tick submits and reaches RUNNING
        coordinator.tick();
        coordinator.tick();
        assertEquals(
            SmartCraftStatus.RUNNING,
            orderManager.get(orderId)
                .get()
                .currentLayer()
                .tasks()
                .get(0)
                .status());

        // User cancels the order
        coordinator.cancel(orderId);
        SmartCraftOrder cancelled = orderManager.get(orderId)
            .get();
        assertEquals(SmartCraftStatus.CANCELLED, cancelled.status());
        assertEquals(
            SmartCraftStatus.CANCELLED,
            cancelled.currentLayer()
                .tasks()
                .get(0)
                .status());

        // Ticking on a CANCELLED order should NOT remove the session — retry must still work
        coordinator.tick();
        assertEquals(
            SmartCraftStatus.CANCELLED,
            orderManager.get(orderId)
                .get()
                .status());

        // Retry: previously a no-op for CANCELLED tasks, now reset to PENDING and order to QUEUED
        coordinator.retryFailed(orderId);
        SmartCraftOrder retried = orderManager.get(orderId)
            .get();
        assertEquals(SmartCraftStatus.QUEUED, retried.status());
        assertEquals(
            SmartCraftStatus.PENDING,
            retried.currentLayer()
                .tasks()
                .get(0)
                .status());

        // Next tick should pick up the revived task and begin planning
        coordinator.tick();
        assertEquals(
            SmartCraftStatus.SUBMITTING,
            orderManager.get(orderId)
                .get()
                .currentLayer()
                .tasks()
                .get(0)
                .status());
    }

    @Test
    void terminal_tasks_are_not_overwritten_by_canceled_link_state() {
        // Regression: a task that was just CANCELLED by orderManager.cancel(...) used to be flipped
        // back to FAILED on the next tick because the cancelled link reports isCanceled() == true.
        // The reconcile path now early-returns for terminal tasks and clears their execution entry.
        SmartCraftOrderManager orderManager = new SmartCraftOrderManager();
        MutableLinkState linkState = new MutableLinkState();
        SmartCraftRuntimeCoordinator coordinator = new SmartCraftRuntimeCoordinator(
            orderManager,
            new Ae2CpuSelector(),
            (session, task) -> new CompletedFuture(job(false)),
            (session, task, cpu, job) -> link(linkState),
            new RecordingSync());
        SmartCraftOrder order = order(task("task-1", SmartCraftStatus.PENDING));
        UUID orderId = orderManager.track(order);
        coordinator.register(orderId, session(cpu("cpu-1", false)));

        coordinator.tick();
        coordinator.tick();
        coordinator.cancel(orderId);
        // AE2 link reports the cancellation only after the next tick; reconcile must NOT overwrite.
        linkState.canceled = true;
        coordinator.tick();

        SmartCraftOrder afterTick = orderManager.get(orderId)
            .get();
        assertEquals(SmartCraftStatus.CANCELLED, afterTick.status());
        assertEquals(
            SmartCraftStatus.CANCELLED,
            afterTick.currentLayer()
                .tasks()
                .get(0)
                .status());
    }

    @Test
    void planning_starts_for_all_tasks_even_when_only_one_cpu_is_idle() {
        // Regression: dispatchReadyTasks used to consume one CPU per planning task, which serialized
        // planning behind CPU count and produced bogus WAITING_CPU labels for tasks that hadn't even
        // started planning. Planning now runs in parallel; CPU is consumed only at submission.
        SmartCraftOrderManager orderManager = new SmartCraftOrderManager();
        SmartCraftRuntimeCoordinator coordinator = new SmartCraftRuntimeCoordinator(
            orderManager,
            new Ae2CpuSelector(),
            (session, task) -> new CompletedFuture(job(false)),
            (session, task, cpu, job) -> link(new MutableLinkState()),
            new RecordingSync());
        SmartCraftOrder order = order(
            task("task-1", SmartCraftStatus.PENDING),
            task("task-2", SmartCraftStatus.PENDING),
            task("task-3", SmartCraftStatus.PENDING));
        UUID orderId = orderManager.track(order);
        // Only one idle CPU available — old behavior would leave task-2 / task-3 stuck on WAITING_CPU
        coordinator.register(orderId, session(cpu("cpu-1", false)));

        coordinator.tick();

        SmartCraftOrder afterTick = orderManager.get(orderId)
            .get();
        for (int i = 0; i < 3; i++) {
            assertEquals(
                SmartCraftStatus.SUBMITTING,
                afterTick.currentLayer()
                    .tasks()
                    .get(i)
                    .status(),
                "task-" + (i + 1) + " must enter SUBMITTING regardless of idle CPU count");
        }
    }

    @Test
    void waits_for_cpu_after_planning_finishes_without_idle_cpu() {
        SmartCraftOrderManager orderManager = new SmartCraftOrderManager();
        MutableLinkState linkState = new MutableLinkState();
        SmartCraftRuntimeCoordinator coordinator = new SmartCraftRuntimeCoordinator(
            orderManager,
            new Ae2CpuSelector(),
            (session, task) -> new CompletedFuture(job(false)),
            (session, task, cpu, job) -> link(linkState),
            new RecordingSync());
        SmartCraftOrder order = order(task("task-1", SmartCraftStatus.PENDING));
        UUID orderId = orderManager.track(order);
        coordinator.register(orderId, session(cpu("cpu-busy", true)));

        coordinator.tick();
        coordinator.tick();

        SmartCraftOrder updated = orderManager.get(orderId)
            .get();
        assertEquals(SmartCraftStatus.WAITING_CPU, updated.status());
        assertEquals(
            SmartCraftStatus.WAITING_CPU,
            updated.currentLayer()
                .tasks()
                .get(0)
                .status());
    }

    @Test
    void mixed_failed_and_waiting_cpu_must_not_pause_the_order() {
        // Regression: an order with [FAILED, WAITING_CPU] used to flip to PAUSED, which short-
        // circuits updateOrder so the WAITING_CPU sibling could never be dispatched even when a CPU
        // frees up later. Tasks in WAITING_CPU / PENDING / QUEUED must keep the order out of PAUSED
        // because they still represent live work that needs to make progress.
        // (G1) Disable auto-retry so a simulation-result plan failure FAILS the task on the first
        // attempt, preserving the original [FAILED, WAITING_CPU] precondition this test wants.
        int savedRetryMax = com.homeftw.ae2intelligentscheduling.config.Config.PLAN_RETRY_MAX_ATTEMPTS;
        com.homeftw.ae2intelligentscheduling.config.Config.PLAN_RETRY_MAX_ATTEMPTS = 0;
        try {
            SmartCraftOrderManager orderManager = new SmartCraftOrderManager();
            MutableLinkState linkState = new MutableLinkState();
            SmartCraftRuntimeCoordinator coordinator = new SmartCraftRuntimeCoordinator(
                orderManager,
                new Ae2CpuSelector(),
                // 'failed' task gets a simulation result → reconcile flips it to FAILED. 'waiting' task
                // gets a real (non-simulation) job → reconcile flips it to WAITING_CPU.
                (session, task) -> new CompletedFuture(job("failed".equals(task.taskKey()))),
                (session, task, cpu, job) -> link(linkState),
                new RecordingSync());
            SmartCraftOrder order = order(
                task("failed", SmartCraftStatus.PENDING),
                task("waiting", SmartCraftStatus.PENDING));
            UUID orderId = orderManager.track(order);
            // CPU is busy so 'waiting' task cannot submit and stays in WAITING_CPU after planning.
            coordinator.register(orderId, session(cpu("cpu-busy", true)));

            // Tick 1: planning begins for both → SUBMITTING.
            coordinator.tick();
            // Tick 2: 'failed' future returns simulation → FAILED; 'waiting' future returns real job →
            // WAITING_CPU (no idle CPU). Order should remain in WAITING_CPU, NOT PAUSED.
            coordinator.tick();

            SmartCraftOrder updated = orderManager.get(orderId)
                .get();
            assertEquals(SmartCraftStatus.WAITING_CPU, updated.status());
            assertEquals(
                SmartCraftStatus.FAILED,
                updated.currentLayer()
                    .tasks()
                    .get(0)
                    .status());
            assertEquals(
                SmartCraftStatus.WAITING_CPU,
                updated.currentLayer()
                    .tasks()
                    .get(1)
                    .status());
        } finally {
            com.homeftw.ae2intelligentscheduling.config.Config.PLAN_RETRY_MAX_ATTEMPTS = savedRetryMax;
        }
    }

    @Test
    void completed_link_advances_to_done_in_one_tick_without_verifying_output_trap() {
        // Regression: previously when craftingLink.isDone() became true the coordinator captured the
        // post-craft stock as a baseline and switched the task to VERIFYING_OUTPUT. Because AE2 routes
        // the crafted output to ME storage in the SAME call stack as completeJob() (which markDone()s
        // the link), the captured baseline already included the just-crafted items. The subsequent
        // isOutputAvailable() check waited for ANOTHER `task.amount()` items to appear above that
        // baseline — which never happens — so the task got stuck in VERIFYING_OUTPUT forever, the
        // current layer never completed, and follow-up layers never got dispatched. This was the
        // user-visible "智能合成卡在第一步不推进" symptom. The fix trusts AE2's terminal link state
        // and transitions directly RUNNING -> DONE.
        SmartCraftOrderManager orderManager = new SmartCraftOrderManager();
        MutableLinkState linkState = new MutableLinkState();
        SmartCraftRuntimeCoordinator coordinator = new SmartCraftRuntimeCoordinator(
            orderManager,
            new Ae2CpuSelector(),
            (session, task) -> new CompletedFuture(job(false)),
            (session, task, cpu, job) -> link(linkState),
            new RecordingSync());
        SmartCraftOrder order = order(task("layer-0-task", SmartCraftStatus.PENDING));
        UUID orderId = orderManager.track(order);
        coordinator.register(orderId, session(cpu("cpu-1", false)));

        // Tick 1 plans, tick 2 submits → RUNNING.
        coordinator.tick();
        coordinator.tick();
        assertEquals(
            SmartCraftStatus.RUNNING,
            orderManager.get(orderId)
                .get()
                .currentLayer()
                .tasks()
                .get(0)
                .status());

        // AE2 finishes crafting and marks the link done.
        linkState.done = true;

        // Tick 3 must transition straight to DONE (and the order to COMPLETED). The previous bug
        // would leave the task in VERIFYING_OUTPUT here.
        coordinator.tick();

        SmartCraftOrder finished = orderManager.get(orderId)
            .get();
        assertEquals(
            SmartCraftStatus.DONE,
            finished.layers()
                .get(0)
                .tasks()
                .get(0)
                .status(),
            "task must reach DONE on the same tick the link reports isDone()");
        assertEquals(
            SmartCraftStatus.COMPLETED,
            finished.status(),
            "single-task single-layer order must be COMPLETED once its only task is DONE");
    }

    @Test
    void terminal_order_survives_one_tick_then_vanishes_v017() {
        // v0.1.7 terminal-vanishes policy: the order stays in the manager for exactly one extra
        // tick after it reaches a terminal state, giving the OrderSync a chance to broadcast the
        // final status before the client tab strip stops listing it.
        SmartCraftOrderManager orderManager = new SmartCraftOrderManager();
        MutableLinkState linkState = new MutableLinkState();
        SmartCraftRuntimeCoordinator coordinator = new SmartCraftRuntimeCoordinator(
            orderManager,
            new Ae2CpuSelector(),
            (session, task) -> new CompletedFuture(job(false)),
            (session, task, cpu, job) -> link(linkState),
            new RecordingSync());
        SmartCraftOrder order = order(task("task-1", SmartCraftStatus.PENDING));
        UUID orderId = orderManager.track(order);
        coordinator.register(orderId, session(cpu("cpu-1", false)));

        // Drive the order to COMPLETED: tick 1 plans, tick 2 submits → RUNNING; flip the link to
        // done, tick 3 transitions to DONE/COMPLETED.
        coordinator.tick();
        coordinator.tick();
        linkState.done = true;
        coordinator.tick();

        // Immediately after the transition tick the order is still readable so the sync packet
        // that fired on this same tick is internally consistent with what the client receives.
        assertEquals(
            SmartCraftStatus.COMPLETED,
            orderManager.get(orderId)
                .get()
                .status(),
            "terminal order must survive the transition tick — the OrderSync needs it");

        // One more tick of nothing happening: the order is now removed.
        coordinator.tick();
        assertEquals(
            false,
            orderManager.get(orderId)
                .isPresent(),
            "terminal order must be removed on the tick after the transition tick");
        assertEquals(
            false,
            coordinator.session(orderId)
                .isPresent(),
            "session must be dropped together with the order");
    }

    @Test
    void failed_order_is_retained_indefinitely_for_retry_v0171() {
        // v0.1.7.1: FAILED is the only terminal state that does NOT auto-vanish, because the
        // player needs to be able to hit Retry on it. Discarding a FAILED order requires Cancel
        // (flipping it to CANCELLED, which then auto-vanishes via the standard 1-tick delay).
        com.homeftw.ae2intelligentscheduling.config.Config.PLAN_RETRY_MAX_ATTEMPTS = 0;
        try {
            SmartCraftOrderManager orderManager = new SmartCraftOrderManager();
            MutableLinkState linkState = new MutableLinkState();
            SmartCraftRuntimeCoordinator coordinator = new SmartCraftRuntimeCoordinator(
                orderManager,
                new Ae2CpuSelector(),
                // Simulation = AE2 reports infeasible → reconcile flips to FAILED.
                (session, task) -> new CompletedFuture(job(true)),
                (session, task, cpu, job) -> link(linkState),
                new RecordingSync());
            SmartCraftOrder order = order(task("task-1", SmartCraftStatus.PENDING));
            UUID orderId = orderManager.track(order);
            coordinator.register(orderId, session(cpu("cpu-1", false)));

            // Drive into FAILED.
            coordinator.tick();
            coordinator.tick();
            SmartCraftOrder afterFail = orderManager.get(orderId)
                .get();
            assertEquals(
                SmartCraftStatus.FAILED,
                afterFail.layers()
                    .get(0)
                    .tasks()
                    .get(0)
                    .status(),
                "the planner returns simulation-only so the task lands in FAILED");
            // Order-level status must be PAUSED (retry-eligible), NOT COMPLETED. Pre-v0.1.7.1
            // the advanceLayers bug surfaced COMPLETED here because layer.isComplete() returns
            // true for any all-terminal layer regardless of FAILED tasks.
            assertEquals(
                SmartCraftStatus.PAUSED,
                afterFail.status(),
                "all-tasks-failed order must be PAUSED so the GUI Retry path stays accessible");

            // Tick many more times: the FAILED order must NOT vanish.
            for (int i = 0; i < 10; i++) {
                coordinator.tick();
            }
            assertEquals(
                true,
                orderManager.get(orderId)
                    .isPresent(),
                "FAILED order must persist so the player can press Retry");
            assertEquals(
                true,
                coordinator.session(orderId)
                    .isPresent(),
                "session must stay alive — retry needs the AE2 grid handle");
        } finally {
            com.homeftw.ae2intelligentscheduling.config.Config.PLAN_RETRY_MAX_ATTEMPTS = 3;
        }
    }

    @Test
    void retry_after_terminal_clears_the_pending_removal_mark_v017() {
        // Regression for v0.1.7: a CANCELLED order that was retried before the second tick must
        // not be silently removed by the still-outstanding terminal mark. Retry resurrects the
        // order to QUEUED; the next tick must observe non-terminal status, drop the mark, and
        // let the order continue running.
        SmartCraftOrderManager orderManager = new SmartCraftOrderManager();
        MutableLinkState linkState = new MutableLinkState();
        SmartCraftRuntimeCoordinator coordinator = new SmartCraftRuntimeCoordinator(
            orderManager,
            new Ae2CpuSelector(),
            (session, task) -> new CompletedFuture(job(false)),
            (session, task, cpu, job) -> link(linkState),
            new RecordingSync());
        SmartCraftOrder order = order(task("task-1", SmartCraftStatus.PENDING));
        UUID orderId = orderManager.track(order);
        coordinator.register(orderId, session(cpu("cpu-1", false)));

        // Reach RUNNING then cancel to get the order into CANCELLED.
        coordinator.tick();
        coordinator.tick();
        coordinator.cancel(orderId);
        // First tick after cancel: marks the order for removal but doesn't remove it yet.
        coordinator.tick();
        assertEquals(
            SmartCraftStatus.CANCELLED,
            orderManager.get(orderId)
                .get()
                .status(),
            "cancelled order must still be present after the marking tick");

        // Retry while the mark is outstanding: order goes back to QUEUED + tasks PENDING.
        coordinator.retryFailed(orderId);
        // Next tick must NOT remove the order — the mark has to clear because isFinished()=false.
        coordinator.tick();
        assertEquals(
            true,
            orderManager.get(orderId)
                .isPresent(),
            "retry before the second tick must clear the terminal mark");
    }

    @Test
    void submit_failure_records_diagnostic_blocking_reason() {
        // (v0.1.7.4) Regression: when AE2's submitJob rejects our submission (ICraftingLink == null
        // from the submitter), the task must eventually transition to FAILED with a blockingReason
        // that explains *why* — not just the generic "Failed to submit" string. The
        // diagnoseSubmitFailure helper inspects the network's CPU snapshot and the chosen CPU to
        // produce a hint, e.g. "AE2 rejected (chosen CPU 'cpu-1', 1 idle / 0 busy)" when no
        // obvious size mismatch is detectable from the AE2 API surface.
        //
        // (v0.1.8 G5) Submit failures now go through handleSubmitFailure with backoff retries by
        // default; this test sets SUBMIT_RETRY_MAX_ATTEMPTS=0 to assert the FAIL-on-first-reject
        // path still produces a non-generic diagnostic reason.
        int savedSubmitMax = com.homeftw.ae2intelligentscheduling.config.Config.SUBMIT_RETRY_MAX_ATTEMPTS;
        com.homeftw.ae2intelligentscheduling.config.Config.SUBMIT_RETRY_MAX_ATTEMPTS = 0;
        try {
            SmartCraftOrderManager orderManager = new SmartCraftOrderManager();
            SmartCraftRuntimeCoordinator coordinator = new SmartCraftRuntimeCoordinator(
                orderManager,
                new Ae2CpuSelector(),
                (session, task) -> new CompletedFuture(job(false)),
                // Submitter ALWAYS returns null — simulates AE2 rejecting submitJob.
                (session, task, cpu, job) -> null,
                new RecordingSync());
            SmartCraftOrder order = order(task("task-1", SmartCraftStatus.PENDING));
            UUID orderId = orderManager.track(order);
            coordinator.register(orderId, session(cpu("cpu-1", false)));

            // Tick 1: planning starts. Tick 2: planning resolves, dispatch attempts submit, submitter
            // returns null, task flips to FAILED with diagnostic reason.
            coordinator.tick();
            coordinator.tick();

            SmartCraftTask failed = orderManager.get(orderId)
                .get()
                .currentLayer()
                .tasks()
                .get(0);
            assertEquals(SmartCraftStatus.FAILED, failed.status(), "submit reject must transition task to FAILED");
            String reason = failed.blockingReason();
            org.junit.jupiter.api.Assertions.assertNotNull(reason, "FAILED task must carry a blockingReason");
            org.junit.jupiter.api.Assertions.assertTrue(
                reason.startsWith("Failed to submit AE2 crafting job: "),
                "reason must use submit-failure prefix: " + reason);
            org.junit.jupiter.api.Assertions.assertTrue(
                reason.length() > "Failed to submit AE2 crafting job: ".length(),
                "reason must include diagnostic hint, got: " + reason);
            org.junit.jupiter.api.Assertions.assertTrue(
                reason.contains("cpu-1") || reason.contains("idle"),
                "reason must mention the chosen CPU or idle/busy counts: " + reason);
        } finally {
            com.homeftw.ae2intelligentscheduling.config.Config.SUBMIT_RETRY_MAX_ATTEMPTS = savedSubmitMax;
        }
    }

    @Test
    void submit_failure_keeps_cached_plan_and_retries_after_backoff_v0180() {
        // (v0.1.8 G5) Regression: when AE2 submitJob returns null we must NOT discard the cached
        // plan. The task drops back to WAITING_CPU with a "Retrying submit in N ticks" banner;
        // after the backoff window expires dispatch retries the submit, and (if the next call
        // succeeds) the task reaches RUNNING without paying the planning cost again. Hugely
        // important for long orders: the plan stage on a deep dependency tree can take seconds
        // and we don't want a transient AE2 rejection to throw it away.
        final int[] submitCalls = { 0 };
        SmartCraftOrderManager orderManager = new SmartCraftOrderManager();
        MutableLinkState linkState = new MutableLinkState();
        SmartCraftRuntimeCoordinator coordinator = new SmartCraftRuntimeCoordinator(
            orderManager,
            new Ae2CpuSelector(),
            (session, task) -> new CompletedFuture(job(false)),
            (session, task, cpu, job) -> {
                submitCalls[0]++;
                // First call rejects; subsequent calls succeed. Models a CPU that briefly turned
                // busy between idle-detection and submit, then freed up by the retry tick.
                return submitCalls[0] == 1 ? null : link(linkState);
            },
            new RecordingSync());
        SmartCraftOrder order = order(task("task-1", SmartCraftStatus.PENDING));
        UUID orderId = orderManager.track(order);
        coordinator.register(orderId, session(cpu("cpu-1", false)));

        // Tick 1 plans. Tick 2 attempts submit → null, handleSubmitFailure schedules backoff and
        // flips the task back to WAITING_CPU keeping the cached plan.
        coordinator.tick();
        coordinator.tick();
        SmartCraftTask afterReject = orderManager.get(orderId)
            .get()
            .currentLayer()
            .tasks()
            .get(0);
        assertEquals(
            SmartCraftStatus.WAITING_CPU,
            afterReject.status(),
            "first submit reject must NOT FAIL the task — it must wait for backoff retry");
        org.junit.jupiter.api.Assertions.assertNotNull(
            afterReject.blockingReason(),
            "retry-pending task must carry a Retrying-submit banner");
        org.junit.jupiter.api.Assertions.assertTrue(
            afterReject.blockingReason()
                .startsWith("Retrying submit in "),
            "banner must start with 'Retrying submit in ': " + afterReject.blockingReason());

        // SUBMIT_BACKOFF_TICKS[0] = 20 ticks; spin until the backoff expires AND the retry runs.
        // The retry is not allowed to call submitJob before nextAllowedTick, so we must tick
        // through the full window. 25 ticks is comfortably past the 20-tick first-attempt delay.
        for (int i = 0; i < 25; i++) {
            coordinator.tick();
        }

        SmartCraftTask afterRetry = orderManager.get(orderId)
            .get()
            .currentLayer()
            .tasks()
            .get(0);
        assertEquals(
            SmartCraftStatus.RUNNING,
            afterRetry.status(),
            "second submit (post-backoff) must succeed, lifting the task to RUNNING");
        org.junit.jupiter.api.Assertions.assertEquals(
            2,
            submitCalls[0],
            "submit must have been retried exactly once after the initial rejection");
    }

    @Test
    void order_auto_retry_triggers_after_interval_for_failed_order_v0180() {
        // (v0.1.8 G7) End-to-end happy path for the server-side order-level auto retry. When an
        // order ends FAILED/PAUSED the runtime waits ORDER_AUTO_RETRY_INTERVAL_SECONDS and then
        // server-presses Retry as if the player had clicked the GUI button. We compress the
        // intervals so the test runs in a few hundred ticks instead of tens of thousands:
        //   - ORDER_AUTO_RETRY_INTERVAL_SECONDS = 1s = 20 ticks
        //   - ORDER_AUTO_RETRY_MAX_ATTEMPTS = 2
        //   - PLAN_RETRY_MAX_ATTEMPTS = 0 so the simulation-only planner FAILs the task on its
        //     very first attempt (no inner G1 backoff hiding the order-level FAILED transition).
        int savedInterval = com.homeftw.ae2intelligentscheduling.config.Config.ORDER_AUTO_RETRY_INTERVAL_SECONDS;
        int savedMax = com.homeftw.ae2intelligentscheduling.config.Config.ORDER_AUTO_RETRY_MAX_ATTEMPTS;
        int savedPlanMax = com.homeftw.ae2intelligentscheduling.config.Config.PLAN_RETRY_MAX_ATTEMPTS;
        com.homeftw.ae2intelligentscheduling.config.Config.ORDER_AUTO_RETRY_INTERVAL_SECONDS = 1;
        com.homeftw.ae2intelligentscheduling.config.Config.ORDER_AUTO_RETRY_MAX_ATTEMPTS = 2;
        com.homeftw.ae2intelligentscheduling.config.Config.PLAN_RETRY_MAX_ATTEMPTS = 0;
        try {
            // Stateful planner: every call returns simulation=true so the task FAILs on plan
            // resolution. We use an int counter so the test can assert how many distinct
            // planning attempts were spawned (one per auto-retry pass plus the initial one).
            final int[] planCalls = { 0 };
            SmartCraftOrderManager orderManager = new SmartCraftOrderManager();
            SmartCraftRuntimeCoordinator coordinator = new SmartCraftRuntimeCoordinator(
                orderManager,
                new Ae2CpuSelector(),
                (session, task) -> {
                    planCalls[0]++;
                    return new CompletedFuture(job(true)); // simulation = true => FAILED
                },
                (session, task, cpu, job) -> link(new MutableLinkState()),
                new RecordingSync());
            SmartCraftOrder order = order(task("task-1", SmartCraftStatus.PENDING));
            UUID orderId = orderManager.track(order);
            coordinator.register(orderId, session(cpu("cpu-1", false)));

            // Drive into FAILED/PAUSED. Tick 1 plans, tick 2 plan resolves => simulation =>
            // PLAN_RETRY_MAX_ATTEMPTS=0 means task immediately FAILED, order surfaces PAUSED.
            coordinator.tick();
            coordinator.tick();
            assertEquals(
                SmartCraftStatus.PAUSED,
                orderManager.get(orderId)
                    .get()
                    .status(),
                "order with all-failed tasks must surface PAUSED so G7 sees a retry-eligible state");

            // Within the interval window the auto-retry must NOT yet fire (G7 latch). 20-tick
            // window starts at tick 2, so ticks 3..21 are still inside the window.
            for (int i = 0; i < 15; i++) {
                coordinator.tick();
            }
            assertEquals(
                1,
                planCalls[0],
                "no auto-retry must have fired yet within the 20-tick interval window");
            assertEquals(
                SmartCraftStatus.PAUSED,
                orderManager.get(orderId)
                    .get()
                    .status());

            // Spin past the interval. By tick ~22 the auto-retry triggers, the task drops back to
            // PENDING and the planner is called again. It still returns simulation so we end up
            // FAILED again, but planCalls must have incremented.
            for (int i = 0; i < 30; i++) {
                coordinator.tick();
            }
            org.junit.jupiter.api.Assertions.assertTrue(
                planCalls[0] >= 2,
                "auto-retry must have invoked the planner at least once more, got: " + planCalls[0]);
        } finally {
            com.homeftw.ae2intelligentscheduling.config.Config.ORDER_AUTO_RETRY_INTERVAL_SECONDS = savedInterval;
            com.homeftw.ae2intelligentscheduling.config.Config.ORDER_AUTO_RETRY_MAX_ATTEMPTS = savedMax;
            com.homeftw.ae2intelligentscheduling.config.Config.PLAN_RETRY_MAX_ATTEMPTS = savedPlanMax;
        }
    }

    @Test
    void order_auto_retry_stops_after_max_attempts_v0180() {
        // (v0.1.8 G7) Budget exhaustion: after ORDER_AUTO_RETRY_MAX_ATTEMPTS server-pressed Retry
        // calls have all failed in turn the runtime stops auto-retrying and leaves the order
        // FAILED/PAUSED for player intervention. We use MAX_ATTEMPTS=1 so we only need one cycle
        // of (FAIL -> wait 20 ticks -> auto-retry -> FAIL again -> never auto-retry again).
        int savedInterval = com.homeftw.ae2intelligentscheduling.config.Config.ORDER_AUTO_RETRY_INTERVAL_SECONDS;
        int savedMax = com.homeftw.ae2intelligentscheduling.config.Config.ORDER_AUTO_RETRY_MAX_ATTEMPTS;
        int savedPlanMax = com.homeftw.ae2intelligentscheduling.config.Config.PLAN_RETRY_MAX_ATTEMPTS;
        com.homeftw.ae2intelligentscheduling.config.Config.ORDER_AUTO_RETRY_INTERVAL_SECONDS = 1;
        com.homeftw.ae2intelligentscheduling.config.Config.ORDER_AUTO_RETRY_MAX_ATTEMPTS = 1;
        com.homeftw.ae2intelligentscheduling.config.Config.PLAN_RETRY_MAX_ATTEMPTS = 0;
        try {
            final int[] planCalls = { 0 };
            SmartCraftOrderManager orderManager = new SmartCraftOrderManager();
            SmartCraftRuntimeCoordinator coordinator = new SmartCraftRuntimeCoordinator(
                orderManager,
                new Ae2CpuSelector(),
                (session, task) -> {
                    planCalls[0]++;
                    return new CompletedFuture(job(true));
                },
                (session, task, cpu, job) -> link(new MutableLinkState()),
                new RecordingSync());
            SmartCraftOrder order = order(task("task-1", SmartCraftStatus.PENDING));
            UUID orderId = orderManager.track(order);
            coordinator.register(orderId, session(cpu("cpu-1", false)));

            // Run through enough ticks for the original failure + one auto-retry + a second
            // failure. After that the budget is exhausted and additional ticks must NOT spawn
            // any further plan attempts.
            for (int i = 0; i < 100; i++) {
                coordinator.tick();
            }
            int callsAfterBudget = planCalls[0];
            org.junit.jupiter.api.Assertions.assertTrue(
                callsAfterBudget >= 2,
                "first auto-retry must have run by now, got plan calls: " + callsAfterBudget);

            // Many additional ticks: planner count must stay flat \u2014 no further auto-retries.
            for (int i = 0; i < 200; i++) {
                coordinator.tick();
            }
            org.junit.jupiter.api.Assertions.assertEquals(
                callsAfterBudget,
                planCalls[0],
                "auto-retry must stop firing once ORDER_AUTO_RETRY_MAX_ATTEMPTS is reached");

            // Order must still be in a retry-eligible state for the player to hit manual Retry.
            SmartCraftStatus finalStatus = orderManager.get(orderId)
                .get()
                .status();
            org.junit.jupiter.api.Assertions.assertTrue(
                finalStatus == SmartCraftStatus.PAUSED || finalStatus == SmartCraftStatus.FAILED,
                "order must remain retry-eligible (FAILED/PAUSED) after auto-retry exhaustion, got: " + finalStatus);
        } finally {
            com.homeftw.ae2intelligentscheduling.config.Config.ORDER_AUTO_RETRY_INTERVAL_SECONDS = savedInterval;
            com.homeftw.ae2intelligentscheduling.config.Config.ORDER_AUTO_RETRY_MAX_ATTEMPTS = savedMax;
            com.homeftw.ae2intelligentscheduling.config.Config.PLAN_RETRY_MAX_ATTEMPTS = savedPlanMax;
        }
    }

    @Test
    void link_cancel_marks_task_failed_immediately_when_retry_budget_zero_v0180() {
        // (v0.1.8 G6) Regression-fence: with LINK_CANCEL_RETRY_MAX_ATTEMPTS=0 the runtime keeps
        // its pre-v0.1.8 behaviour \u2014 the very first link.isCanceled() observation flips the task
        // to FAILED with the canonical CRAFTING_LINK_CANCELLED_REASON. This guards the no-retry
        // path which power users may want for short orders where a cancel almost certainly means
        // "automation chain broken, fix it manually".
        int savedMax = com.homeftw.ae2intelligentscheduling.config.Config.LINK_CANCEL_RETRY_MAX_ATTEMPTS;
        com.homeftw.ae2intelligentscheduling.config.Config.LINK_CANCEL_RETRY_MAX_ATTEMPTS = 0;
        try {
            SmartCraftOrderManager orderManager = new SmartCraftOrderManager();
            MutableLinkState linkState = new MutableLinkState();
            SmartCraftRuntimeCoordinator coordinator = new SmartCraftRuntimeCoordinator(
                orderManager,
                new Ae2CpuSelector(),
                (session, task) -> new CompletedFuture(job(false)),
                (session, task, cpu, job) -> link(linkState),
                new RecordingSync());
            SmartCraftOrder order = order(task("task-1", SmartCraftStatus.PENDING));
            UUID orderId = orderManager.track(order);
            coordinator.register(orderId, session(cpu("cpu-1", false)));

            // Tick 1 plans, tick 2 submits \u2192 RUNNING. AE2 then cancels the link mid-craft.
            coordinator.tick();
            coordinator.tick();
            linkState.canceled = true;
            coordinator.tick();

            SmartCraftTask failed = orderManager.get(orderId)
                .get()
                .layers()
                .get(0)
                .tasks()
                .get(0);
            assertEquals(
                SmartCraftStatus.FAILED,
                failed.status(),
                "with retry budget 0 the very first link cancel must FAIL the task");
            org.junit.jupiter.api.Assertions.assertEquals(
                "AE2 crafting link was cancelled by the network",
                failed.blockingReason(),
                "FAILED reason must be the canonical link-cancel string");
        } finally {
            com.homeftw.ae2intelligentscheduling.config.Config.LINK_CANCEL_RETRY_MAX_ATTEMPTS = savedMax;
        }
    }

    @Test
    void link_cancel_retries_after_backoff_and_resubmits_with_fresh_plan_v0180() {
        // (v0.1.8 G6) End-to-end happy path: AE2 cancels the first link, the runtime waits the
        // configured minutes-scale backoff, re-plans the task from scratch (cached plan was
        // wiped because stock baseline shifted during the canceled craft), submits to a fresh
        // CPU, and the second link finishes cleanly. We compress the test to LINK_CANCEL_
        // RETRY_MAX_ATTEMPTS=1 so the first cancel consumes the only retry; LINK_CANCEL_BACKOFF_
        // TICKS[0] = 6000 ticks (5 in-game minutes), so we spin a hair past that.
        int savedMax = com.homeftw.ae2intelligentscheduling.config.Config.LINK_CANCEL_RETRY_MAX_ATTEMPTS;
        com.homeftw.ae2intelligentscheduling.config.Config.LINK_CANCEL_RETRY_MAX_ATTEMPTS = 1;
        try {
            // Two mock link states: state[0] starts canceled (the AE2 mid-craft cancel we want to
            // recover from), state[1] is a clean link that the retry submit will use. The
            // submitter dispenses them in order so we can test that the retry actually got a
            // fresh link rather than re-attaching the broken one.
            final MutableLinkState[] states = { new MutableLinkState(), new MutableLinkState() };
            states[0].canceled = true;
            final int[] submitCalls = { 0 };

            SmartCraftOrderManager orderManager = new SmartCraftOrderManager();
            SmartCraftRuntimeCoordinator coordinator = new SmartCraftRuntimeCoordinator(
                orderManager,
                new Ae2CpuSelector(),
                (session, task) -> new CompletedFuture(job(false)),
                (session, task, cpu, job) -> {
                    int idx = submitCalls[0]++;
                    return link(states[Math.min(idx, states.length - 1)]);
                },
                new RecordingSync());
            SmartCraftOrder order = order(task("task-1", SmartCraftStatus.PENDING));
            UUID orderId = orderManager.track(order);
            coordinator.register(orderId, session(cpu("cpu-1", false)));

            // Tick 1 plans, tick 2 submits \u2192 RUNNING (gets state[0] which is already canceled).
            coordinator.tick();
            coordinator.tick();
            // Tick 3: reconcile observes link.isCanceled(), routes to handleLinkCancelFailure,
            // task drops back to PENDING with "Retrying canceled craft in 6000 ticks" banner.
            coordinator.tick();
            SmartCraftTask afterCancel = orderManager.get(orderId)
                .get()
                .layers()
                .get(0)
                .tasks()
                .get(0);
            assertEquals(
                SmartCraftStatus.PENDING,
                afterCancel.status(),
                "first cancel must NOT FAIL the task \u2014 it must drop to PENDING for backoff retry");
            org.junit.jupiter.api.Assertions.assertNotNull(afterCancel.blockingReason());
            org.junit.jupiter.api.Assertions.assertTrue(
                afterCancel.blockingReason()
                    .startsWith("Retrying canceled craft in "),
                "banner must start with 'Retrying canceled craft in ': " + afterCancel.blockingReason());

            // Spin past the 6000-tick first-attempt backoff. handleLinkCancelFailure schedules
            // the retry at tickCounter+6000, so we need to be safely past that mark for dispatch
            // to pick up the planning candidate.
            for (int i = 0; i < 6010; i++) {
                coordinator.tick();
            }

            // After the backoff window the second submit succeeds with state[1] (clean link).
            // The order should now be RUNNING again, and the submit must have been called twice
            // total (initial + retry).
            SmartCraftTask afterRetry = orderManager.get(orderId)
                .get()
                .layers()
                .get(0)
                .tasks()
                .get(0);
            assertEquals(
                SmartCraftStatus.RUNNING,
                afterRetry.status(),
                "post-backoff retry must lift the task back to RUNNING with a fresh link");
            org.junit.jupiter.api.Assertions.assertEquals(
                2,
                submitCalls[0],
                "submitter must have been called exactly once more for the retry");

            // Final sanity: mark the second link done and confirm the order completes.
            states[1].done = true;
            coordinator.tick();
            assertEquals(
                SmartCraftStatus.COMPLETED,
                orderManager.get(orderId)
                    .get()
                    .status(),
                "order must complete once the retry's link reports done");
        } finally {
            com.homeftw.ae2intelligentscheduling.config.Config.LINK_CANCEL_RETRY_MAX_ATTEMPTS = savedMax;
        }
    }

    @Test
    void submit_failure_marks_task_failed_after_exhausting_retry_budget_v0180() {
        // (v0.1.8 G5) Once the 5-attempt budget is exhausted the task transitions to FAILED with
        // the same diagnostic reason it would have produced pre-v0.1.8. We collapse the wait by
        // setting SUBMIT_RETRY_MAX_ATTEMPTS=2 so the test runs in a few hundred ticks instead of
        // several thousand. Backoff schedule in 0.1.8 is 20/60/200/600/1200 ticks: with budget=2
        // we need the 20+60 windows plus a couple of extra ticks for the FAILED transition.
        int savedSubmitMax = com.homeftw.ae2intelligentscheduling.config.Config.SUBMIT_RETRY_MAX_ATTEMPTS;
        com.homeftw.ae2intelligentscheduling.config.Config.SUBMIT_RETRY_MAX_ATTEMPTS = 2;
        try {
            SmartCraftOrderManager orderManager = new SmartCraftOrderManager();
            SmartCraftRuntimeCoordinator coordinator = new SmartCraftRuntimeCoordinator(
                orderManager,
                new Ae2CpuSelector(),
                (session, task) -> new CompletedFuture(job(false)),
                (session, task, cpu, job) -> null, // every submit attempt fails
                new RecordingSync());
            SmartCraftOrder order = order(task("task-1", SmartCraftStatus.PENDING));
            UUID orderId = orderManager.track(order);
            coordinator.register(orderId, session(cpu("cpu-1", false)));

            // Tick a generous upper-bound: 20 (first backoff) + 60 (second) + planning ticks.
            // After the budget is exhausted the third submitJob=null call routes through
            // handleSubmitFailure which removes the retry entry and finalizes FAILED.
            for (int i = 0; i < 200; i++) {
                coordinator.tick();
            }

            // Use layers().get(0) instead of currentLayer(): once the task FAILs the layer is
            // "complete" (every task terminal) and advanceLayers bumps currentLayerIndex past the
            // last layer, which would NPE on currentLayer().tasks() in this single-layer order.
            SmartCraftTask failed = orderManager.get(orderId)
                .get()
                .layers()
                .get(0)
                .tasks()
                .get(0);
            assertEquals(
                SmartCraftStatus.FAILED,
                failed.status(),
                "after exhausting SUBMIT_RETRY_MAX_ATTEMPTS the task must FAIL with the diagnostic reason");
            org.junit.jupiter.api.Assertions.assertNotNull(failed.blockingReason());
            org.junit.jupiter.api.Assertions.assertTrue(
                failed.blockingReason()
                    .startsWith("Failed to submit AE2 crafting job: "),
                "final FAILED reason must NOT be the retrying-banner: " + failed.blockingReason());
        } finally {
            com.homeftw.ae2intelligentscheduling.config.Config.SUBMIT_RETRY_MAX_ATTEMPTS = savedSubmitMax;
        }
    }

    /**
     * v0.1.9.2 (G13) Global submission cap throttles excess concurrent submissions.
     *
     * <p>Setup:
     * <ul>
     *   <li>4 independent tasks (distinct requestKeys to bypass the split-serialization gate)</li>
     *   <li>4 idle CPUs (so the CPU pool itself is never the bottleneck)</li>
     *   <li>Cap = 2</li>
     * </ul>
     *
     * <p>After tick 1 (planning) + tick 2 (submission attempt for all four), exactly 2 tasks
     * must reach RUNNING and the other 2 must be in WAITING_CPU carrying the throttle banner.
     * This is the core guarantee that protects Programmable Hatches' Auto CPU from minting
     * unbounded clusters under SmartCraft load.
     */
    @Test
    void global_submission_cap_throttles_excess_tasks_v0192() {
        int savedCap = com.homeftw.ae2intelligentscheduling.config.Config.MAX_CONCURRENT_SMART_CRAFT_SUBMISSIONS;
        com.homeftw.ae2intelligentscheduling.config.Config.MAX_CONCURRENT_SMART_CRAFT_SUBMISSIONS = 2;
        try {
            SmartCraftOrderManager orderManager = new SmartCraftOrderManager();
            MutableLinkState linkState = new MutableLinkState();
            SmartCraftRuntimeCoordinator coordinator = new SmartCraftRuntimeCoordinator(
                orderManager,
                new Ae2CpuSelector(),
                (session, task) -> new CompletedFuture(job(false)),
                (session, task, cpu, job) -> link(linkState),
                new RecordingSync());

            SmartCraftOrder order = order(
                task("task-1", SmartCraftStatus.PENDING),
                task("task-2", SmartCraftStatus.PENDING),
                task("task-3", SmartCraftStatus.PENDING),
                task("task-4", SmartCraftStatus.PENDING));
            UUID orderId = orderManager.track(order);
            coordinator.register(
                orderId,
                sessionWithCpus(
                    cpu("cpu-1", false),
                    cpu("cpu-2", false),
                    cpu("cpu-3", false),
                    cpu("cpu-4", false)));

            // Tick 1: plan all four. Tick 2: try to submit all four; cap=2 admits only 2.
            coordinator.tick();
            coordinator.tick();

            SmartCraftOrder after = orderManager.get(orderId)
                .get();
            int running = 0;
            int throttled = 0;
            for (SmartCraftTask t : after.layers()
                .get(0)
                .tasks()) {
                if (t.status() == SmartCraftStatus.RUNNING) {
                    running++;
                } else if (t.status() == SmartCraftStatus.WAITING_CPU
                    && t.blockingReason() != null
                    && t.blockingReason()
                        .startsWith("Throttled: SmartCraft global submission cap")) {
                    throttled++;
                }
            }
            assertEquals(2, running, "exactly cap (2) tasks must reach RUNNING");
            assertEquals(2, throttled, "the remaining 2 tasks must carry the throttle banner");
            assertEquals(
                2,
                coordinator.globalActiveSubmissions(),
                "globalActiveSubmissions() must equal the number of links granted (= cap)");
        } finally {
            com.homeftw.ae2intelligentscheduling.config.Config.MAX_CONCURRENT_SMART_CRAFT_SUBMISSIONS = savedCap;
        }
    }

    /**
     * v0.1.9.2 (G13) Cap = 0 is a sentinel meaning "disabled" \u2014 every task must be free to
     * submit. Without this carve-out, configuring 0 to disable would lock SmartCraft out
     * entirely, which is the opposite of the intended semantics.
     */
    @Test
    void global_submission_cap_zero_disables_throttling_v0192() {
        int savedCap = com.homeftw.ae2intelligentscheduling.config.Config.MAX_CONCURRENT_SMART_CRAFT_SUBMISSIONS;
        com.homeftw.ae2intelligentscheduling.config.Config.MAX_CONCURRENT_SMART_CRAFT_SUBMISSIONS = 0;
        try {
            SmartCraftOrderManager orderManager = new SmartCraftOrderManager();
            MutableLinkState linkState = new MutableLinkState();
            SmartCraftRuntimeCoordinator coordinator = new SmartCraftRuntimeCoordinator(
                orderManager,
                new Ae2CpuSelector(),
                (session, task) -> new CompletedFuture(job(false)),
                (session, task, cpu, job) -> link(linkState),
                new RecordingSync());

            SmartCraftOrder order = order(
                task("task-1", SmartCraftStatus.PENDING),
                task("task-2", SmartCraftStatus.PENDING),
                task("task-3", SmartCraftStatus.PENDING));
            UUID orderId = orderManager.track(order);
            coordinator.register(
                orderId,
                sessionWithCpus(cpu("cpu-1", false), cpu("cpu-2", false), cpu("cpu-3", false)));

            coordinator.tick();
            coordinator.tick();

            SmartCraftOrder after = orderManager.get(orderId)
                .get();
            int running = 0;
            for (SmartCraftTask t : after.layers()
                .get(0)
                .tasks()) {
                if (t.status() == SmartCraftStatus.RUNNING) running++;
            }
            assertEquals(3, running, "cap=0 must let every task reach RUNNING (no throttling)");
        } finally {
            com.homeftw.ae2intelligentscheduling.config.Config.MAX_CONCURRENT_SMART_CRAFT_SUBMISSIONS = savedCap;
        }
    }

    private static SmartCraftRuntimeSession sessionWithCpus(ICraftingCPU... cpus) {
        return new SmartCraftRuntimeSession(
            null,
            null,
            null,
            null,
            null,
            craftingGrid(Arrays.asList(cpus)),
            new SmartCraftRequesterBridge(null));
    }

    private static SmartCraftOrder order(SmartCraftTask... tasks) {
        return new SmartCraftOrder(
            new FakeRequestKey("target"),
            1L,
            SmartCraftOrderScale.SMALL,
            SmartCraftStatus.QUEUED,
            Arrays.asList(new SmartCraftLayer(0, Arrays.asList(tasks))),
            0);
    }

    private static SmartCraftTask task(String taskId, SmartCraftStatus status) {
        // Use taskId as the requestKey id too: distinct tasks get distinct requestKeys, which
        // matters because P1-#3 (split-planning serialization) defers tasks that share a
        // requestKey with an in-flight planning sibling. These tests model independent nodes —
        // one requestKey per task — so they must NOT trip the split-serialization gate. Tests
        // that specifically exercise split behaviour live in SmartCraftSchedulerTest and build
        // tasks with an explicitly shared requestKey.
        return new SmartCraftTask(taskId, new FakeRequestKey(taskId), 64L, 0, 1, 1, status, null);
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
            SmartCraftRuntimeCoordinatorTest.class.getClassLoader(),
            new Class<?>[] { ICraftingGrid.class },
            handler);
    }

    private static ICraftingCPU cpu(final String name, final boolean busy) {
        InvocationHandler handler = new InvocationHandler() {

            @Override
            public Object invoke(Object proxy, Method method, Object[] args) {
                if ("isBusy".equals(method.getName())) {
                    return busy;
                }
                if ("getName".equals(method.getName())) {
                    return name;
                }
                return defaultValue(method.getReturnType());
            }
        };

        return (ICraftingCPU) Proxy.newProxyInstance(
            SmartCraftRuntimeCoordinatorTest.class.getClassLoader(),
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
            SmartCraftRuntimeCoordinatorTest.class.getClassLoader(),
            new Class<?>[] { ICraftingJob.class },
            handler);
    }

    private static ICraftingLink link(final MutableLinkState state) {
        InvocationHandler handler = new InvocationHandler() {

            @Override
            public Object invoke(Object proxy, Method method, Object[] args) {
                if ("isDone".equals(method.getName())) {
                    return state.done;
                }
                if ("isCanceled".equals(method.getName())) {
                    return state.canceled;
                }
                return defaultValue(method.getReturnType());
            }
        };

        return (ICraftingLink) Proxy.newProxyInstance(
            SmartCraftRuntimeCoordinatorTest.class.getClassLoader(),
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

    private static final class MutableLinkState {

        private boolean done;
        private boolean canceled;
    }

    private static final class RecordingSync implements SmartCraftRuntimeCoordinator.OrderSync {

        private int syncCount;

        @Override
        public void sync(SmartCraftRuntimeSession session, UUID orderId) {
            this.syncCount++;
        }
    }

    /**
     * v0.1.8.1 (G8) Test stub for {@link SmartCraftRuntimeCoordinator.OrderCompletionNotifier}.
     * Records every invocation so tests can assert exactly-once firing on COMPLETED transitions
     * and zero firings on CANCELLED / FAILED / PAUSED outcomes.
     */
    private static final class RecordingCompletionNotifier
        implements SmartCraftRuntimeCoordinator.OrderCompletionNotifier {

        private final java.util.List<UUID> completedOrders = new java.util.ArrayList<UUID>();

        @Override
        public void onOrderCompleted(SmartCraftRuntimeSession session, UUID orderId,
            com.homeftw.ae2intelligentscheduling.smartcraft.model.SmartCraftOrder order) {
            this.completedOrders.add(orderId);
        }

        int firedCount() {
            return this.completedOrders.size();
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
