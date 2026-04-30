package com.homeftw.ae2intelligentscheduling.smartcraft.runtime;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.homeftw.ae2intelligentscheduling.config.Config;
import com.homeftw.ae2intelligentscheduling.integration.ae2.Ae2CpuSelector;
import com.homeftw.ae2intelligentscheduling.smartcraft.model.SmartCraftLayer;
import com.homeftw.ae2intelligentscheduling.smartcraft.model.SmartCraftOrder;
import com.homeftw.ae2intelligentscheduling.smartcraft.model.SmartCraftStatus;
import com.homeftw.ae2intelligentscheduling.smartcraft.model.SmartCraftTask;

import appeng.api.networking.crafting.ICraftingCPU;
import appeng.api.networking.crafting.ICraftingJob;
import appeng.api.networking.crafting.ICraftingLink;

public final class SmartCraftRuntimeCoordinator {

    public interface JobPlanner {

        Future<ICraftingJob> begin(SmartCraftRuntimeSession session, SmartCraftTask task);
    }

    public interface JobSubmitter {

        ICraftingLink submit(SmartCraftRuntimeSession session, SmartCraftTask task, ICraftingCPU cpu, ICraftingJob job);
    }

    public interface OrderSync {

        void sync(SmartCraftRuntimeSession session, UUID orderId);
    }

    /**
     * v0.1.8.1 (G8) Order-level completion hook. Fired by the runtime exactly once per order on
     * the tick its status transitions from anything-not-COMPLETED to COMPLETED. Production wires
     * this to a chat-message + sound packet sent to the order's owner; tests use a no-op or a
     * recording variant. Never fired for CANCELLED / FAILED / PAUSED transitions — those have
     * different UX (the player already saw a Retry banner or initiated the cancel themselves).
     *
     * <p>The notifier is optional: passing {@code null} disables order-completion notifications
     * entirely (used by every existing test that doesn't care about this hook).
     */
    public interface OrderCompletionNotifier {

        void onOrderCompleted(SmartCraftRuntimeSession session, UUID orderId, SmartCraftOrder order);
    }

    private static final String FAILED_TO_BEGIN_REASON = "Failed to begin AE2 crafting job calculation";
    private static final String FAILED_TO_FINISH_REASON = "Failed to finish AE2 crafting job calculation";
    private static final String FAILED_TO_SUBMIT_REASON = "Failed to submit AE2 crafting job";
    private static final String CRAFTING_LINK_CANCELLED_REASON = "AE2 crafting link was cancelled by the network";
    private static final String NO_IDLE_CPU_REASON = "No idle AE2 crafting CPU available";

    /**
     * v0.1.7.4: build a human-readable blocking-reason for the FAILED task plus a multi-line
     * warning to stderr when AE2's {@code submitJob} rejects our submission. The most common
     * underlying cause (job's byte total exceeds every idle CPU's storage) becomes immediately
     * obvious from the tooltip; rarer causes (chosen CPU just turned busy, network instability)
     * also surface concrete numbers so the player can self-diagnose.
     *
     * @param task        the task we tried to submit; only used for the log preamble
     * @param plannedJob  the AE2 job we passed to submit (its {@code getByteTotal} drives the size
     *                    requirement comparison against each CPU's {@code getAvailableStorage})
     * @param chosenCpu   the CPU our scheduler picked (the one AE2 ultimately rejected)
     * @param session     used to query {@code craftingGrid().getCpus()} for the network-wide
     *                    snapshot of every CPU's busy / storage state
     * @return a single tooltip line ({@code <= ~120 chars}) suitable for {@link
     *         SmartCraftTask#blockingReason()}
     */
    private static String diagnoseSubmitFailure(SmartCraftTask task, ICraftingJob plannedJob, ICraftingCPU chosenCpu,
        SmartCraftRuntimeSession session) {
        long needed = plannedJob == null ? -1L : plannedJob.getByteTotal();
        StringBuilder allCpus = new StringBuilder();
        long maxIdleStorage = -1L;
        int idleCount = 0, busyCount = 0;
        try {
            for (ICraftingCPU cpu : session.craftingGrid()
                .getCpus()) {
                if (allCpus.length() > 0) allCpus.append(", ");
                String name = cpu.getName();
                if (name == null || name.isEmpty()) name = "<unnamed>";
                long avail = cpu.getAvailableStorage();
                boolean busy = cpu.isBusy();
                allCpus.append(name)
                    .append("[busy=")
                    .append(busy)
                    .append(",avail=")
                    .append(avail)
                    .append("B,co=")
                    .append(cpu.getCoProcessors())
                    .append("]");
                if (busy) busyCount++;
                else {
                    idleCount++;
                    if (avail > maxIdleStorage) maxIdleStorage = avail;
                }
            }
        } catch (Throwable t) {
            // Defensive: getCpus() is part of the AE2 API but we've seen rare NPEs during grid
            // teardown. Don't let diagnostic logging itself throw out of dispatch.
            allCpus.append("<unavailable: ")
                .append(t.getClass()
                    .getSimpleName())
                .append('>');
        }

        String chosenName = chosenCpu == null ? "<null>" : chosenCpu.getName();
        if (chosenName == null || chosenName.isEmpty()) chosenName = "<unnamed>";
        long chosenAvail = chosenCpu == null ? -1L : chosenCpu.getAvailableStorage();
        boolean chosenBusy = chosenCpu != null && chosenCpu.isBusy();

        // Heuristic root-cause classifier: the most probable cause goes into the short tooltip.
        String hint;
        if (needed > 0 && maxIdleStorage >= 0 && needed > maxIdleStorage) {
            hint = String.format(
                "no idle CPU large enough (needs %d B, biggest idle = %d B across %d idle CPUs)",
                Long.valueOf(needed),
                Long.valueOf(maxIdleStorage),
                Integer.valueOf(idleCount));
        } else if (chosenBusy) {
            hint = "chosen CPU '" + chosenName + "' became busy between idle-detection and submit";
        } else if (needed > 0 && chosenAvail >= 0 && needed > chosenAvail) {
            hint = String.format(
                "chosen CPU '%s' too small (needs %d B, has %d B)",
                chosenName,
                Long.valueOf(needed),
                Long.valueOf(chosenAvail));
        } else {
            hint = "AE2 rejected (chosen CPU '" + chosenName + "', " + idleCount + " idle / " + busyCount + " busy)";
        }

        LOGGER.warn(
            "AE2 submit rejected for task {} (key={}): byteTotal={}, chosen={}[busy={},avail={}], allCpus=[{}]; root cause hint: {}",
            task.taskId(),
            task.taskKey(),
            Long.valueOf(needed),
            chosenName,
            Boolean.valueOf(chosenBusy),
            Long.valueOf(chosenAvail),
            allCpus,
            hint);
        return FAILED_TO_SUBMIT_REASON + ": " + hint;
    }

    private static final String PLANNING_TIMEOUT_REASON = "AE2 planning did not complete within timeout";
    /**
     * G2 reason: emitted when {@link Config#WAITING_CPU_STALE_SECONDS} elapses without the
     * task ever obtaining a CPU. The cached plan is discarded and the task drops back to PENDING
     * for a fresh re-plan against current ME stock.
     */
    private static final String WAITING_CPU_STALE_REASON = "Cached plan stale (waited too long for CPU), re-planning";

    /**
     * G1 retry-state record. Tracked per-taskKey; survives across PENDING\u2194SUBMITTING transitions
     * within a retry window. Removed on any of (successful plan / retries exhausted / order
     * cancellation). Limits memory growth: at most one entry per task, and entries clear
     * themselves naturally as orders complete or cancel.
     */
    private static final class PlanRetryState {

        /** Number of plan attempts that have ALREADY failed for this task. Starts at 1 after the first failure. */
        final int attempts;
        /** Earliest tick at which dispatch is allowed to start the next planning attempt. */
        final long nextAllowedTick;

        PlanRetryState(int attempts, long nextAllowedTick) {
            this.attempts = attempts;
            this.nextAllowedTick = nextAllowedTick;
        }
    }

    /**
     * v0.1.8 (G5) Submit-failure retry state. Distinct from {@link PlanRetryState} because
     * submit failures keep the cached plan (only the CPU assignment is invalidated), so the next
     * dispatch attempt skips the plan stage entirely. Tracked per-taskKey just like plan retries.
     */
    private static final class SubmitRetryState {

        final int attempts;
        final long nextAllowedTick;

        SubmitRetryState(int attempts, long nextAllowedTick) {
            this.attempts = attempts;
            this.nextAllowedTick = nextAllowedTick;
        }
    }

    /**
     * v0.1.8 (G6) Link-cancel retry state. Tracked per-taskKey. When AE2 cancels a RUNNING
     * task's link the cached plan is wiped (the plan was based on stock that may have shifted
     * during the canceled craft) so unlike submit retries this DOES go through full re-planning;
     * we just delay it on the minutes-tens-of-minutes scale to avoid hammering AE2 in tight
     * cancel loops.
     */
    private static final class LinkCancelRetryState {

        final int attempts;
        final long nextAllowedTick;

        LinkCancelRetryState(int attempts, long nextAllowedTick) {
            this.attempts = attempts;
            this.nextAllowedTick = nextAllowedTick;
        }
    }

    /**
     * v0.1.8 (G7) Per-order auto-retry state. Recorded the first tick a particular order was
     * observed in the FAILED state. Cleared on a successful retry that pulls the order back to
     * QUEUED, on player-initiated cancel, or when the auto-retry budget is exhausted.
     */
    private static final class OrderAutoRetryState {

        /** Tick on which we first saw the order in FAILED \u2014 used to gate the next auto-retry. */
        final long firstFailedTick;
        /** How many times the server-side timer has already auto-pressed Retry on this order. */
        final int attempts;

        OrderAutoRetryState(long firstFailedTick, int attempts) {
            this.firstFailedTick = firstFailedTick;
            this.attempts = attempts;
        }
    }

    private static final Logger LOGGER = LogManager.getLogger("AE2IS-Scheduler");

    /**
     * G1 backoff curve. Linear-doubling schedule keeps early retries fast (handle most transient
     * errors quickly) while ensuring later retries don't hammer AE2 if the failure is genuinely
     * sticky. Indexed by {@code attempts}; clamped to the table tail for any attempt beyond the
     * configured retry limit.
     */
    private static final long[] PLAN_BACKOFF_TICKS = { 5L, 10L, 20L, 40L, 80L };

    /**
     * v0.1.8 (G5) Submit-failure backoff curve. AE2 {@code submitJob} returning null usually
     * means a CPU briefly turned busy or our chosen CPU isn't large enough — both clear on the
     * seconds-to-minute scale, NOT the sub-second scale that plan retries use. Spacing the
     * retries 1s/3s/10s/30s/60s avoids spinning on the dispatch loop while still getting back
     * in fast enough that a freshly-freed CPU will see us before another order claims it.
     */
    private static final long[] SUBMIT_BACKOFF_TICKS = { 20L, 60L, 200L, 600L, 1200L };

    /**
     * v0.1.8 (G6) Link-cancel backoff curve. When AE2 cancels a RUNNING link mid-craft (pattern
     * changed, automation broken, cluster reformed) re-planning a deep dependency tree is
     * expensive AND the underlying network condition usually doesn't clear in seconds. Wait
     * minutes; if it still cancels twice in a row it's structural, mark FAILED for the player.
     */
    private static final long[] LINK_CANCEL_BACKOFF_TICKS = { 6000L, 18000L, 36000L }; // 5min, 15min, 30min

    private static long backoffForAttempt(int attempts) {
        int idx = Math.max(0, Math.min(attempts - 1, PLAN_BACKOFF_TICKS.length - 1));
        return PLAN_BACKOFF_TICKS[idx];
    }

    private static long submitBackoffForAttempt(int attempts) {
        int idx = Math.max(0, Math.min(attempts - 1, SUBMIT_BACKOFF_TICKS.length - 1));
        return SUBMIT_BACKOFF_TICKS[idx];
    }

    private static long linkCancelBackoffForAttempt(int attempts) {
        int idx = Math.max(0, Math.min(attempts - 1, LINK_CANCEL_BACKOFF_TICKS.length - 1));
        return LINK_CANCEL_BACKOFF_TICKS[idx];
    }

    /**
     * G1: route every plan-failure path through here. Either schedules a retry (returning a
     * PENDING task with a descriptive blocking reason) or, if retries are exhausted, returns the
     * task in FAILED with the original failure reason. Caller is responsible for already having
     * called {@code session.clearExecution(task)} so the planning future / partial state is gone.
     */
    private SmartCraftTask handlePlanFailure(SmartCraftTask task, String failureReason) {
        PlanRetryState existing = this.planRetries.get(task.taskKey());
        int attempts = existing == null ? 1 : existing.attempts + 1;
        if (attempts > Config.PLAN_RETRY_MAX_ATTEMPTS) {
            this.planRetries.remove(task.taskKey());
            this.plansFailedPermanently++;
            return task.withStatus(SmartCraftStatus.FAILED, failureReason);
        }
        long delay = backoffForAttempt(attempts);
        this.planRetries.put(task.taskKey(), new PlanRetryState(attempts, this.tickCounter + delay));
        this.plansAutoRetried++;
        // Keep the human-readable retry banner short \u2014 it surfaces in the UI's blocking-reason
        // tooltip and we don't want to push other badges off-screen on long task lists.
        return task.withStatus(
            SmartCraftStatus.PENDING,
            "Retrying plan in " + delay
                + " ticks (attempt "
                + (attempts + 1)
                + "/"
                + (Config.PLAN_RETRY_MAX_ATTEMPTS + 1)
                + "): "
                + failureReason);
    }

    /**
     * G1: returns true if dispatch is allowed to start a fresh planning attempt for this task this
     * tick. False = task is mid-backoff, leave it PENDING for now.
     */
    private boolean planRetryReady(SmartCraftTask task) {
        PlanRetryState state = this.planRetries.get(task.taskKey());
        return state == null || this.tickCounter >= state.nextAllowedTick;
    }

    /**
     * v0.1.8 (G5) Route every submit-failure path through here. Either schedules a retry that
     * KEEPS the cached plan (only re-picks CPU + re-submits) or, if retries are exhausted, marks
     * the task FAILED and clears its execution. Caller MUST NOT have already called
     * {@code session.clearExecution(task)} \u2014 we want the plannedJob preserved for the retry.
     */
    private SmartCraftTask handleSubmitFailure(SmartCraftRuntimeSession session, SmartCraftTask task,
        String diagnosticReason) {
        SubmitRetryState existing = this.submitRetries.get(task.taskKey());
        int attempts = existing == null ? 1 : existing.attempts + 1;
        if (attempts > Config.SUBMIT_RETRY_MAX_ATTEMPTS) {
            this.submitRetries.remove(task.taskKey());
            session.clearExecution(task);
            this.submitsFailedPermanently++;
            return task.withStatus(SmartCraftStatus.FAILED, diagnosticReason);
        }
        long delay = submitBackoffForAttempt(attempts);
        this.submitRetries.put(task.taskKey(), new SubmitRetryState(attempts, this.tickCounter + delay));
        this.submitsAutoRetried++;
        // Detach the rejected CPU pin so next dispatch picks a fresh one. Plan stays cached so we
        // don't pay the planning cost again \u2014 submit failures are about resource availability,
        // not plan validity.
        session.detachAssignedCpu(task);
        return task.withStatus(
            SmartCraftStatus.WAITING_CPU,
            "Retrying submit in " + delay
                + " ticks (attempt "
                + (attempts + 1)
                + "/"
                + (Config.SUBMIT_RETRY_MAX_ATTEMPTS + 1)
                + "): "
                + diagnosticReason);
    }

    /** v0.1.8 (G5) True if submit retry backoff has elapsed and dispatch may try submitJob again. */
    private boolean submitRetryReady(SmartCraftTask task) {
        SubmitRetryState state = this.submitRetries.get(task.taskKey());
        return state == null || this.tickCounter >= state.nextAllowedTick;
    }

    /**
     * v0.1.8 (G6) Route AE2 link-cancel events through here. Unlike submit retries this clears
     * execution entirely (pattern may have changed during the canceled craft, so the cached plan
     * is no longer trustworthy) \u2014 next dispatch will re-plan from PENDING. The backoff window
     * is on the minutes scale because re-planning a deep tree is expensive and the typical
     * underlying causes (pattern provider broken, automation chain missing) take human action to
     * fix anyway.
     */
    private SmartCraftTask handleLinkCancelFailure(SmartCraftRuntimeSession session, SmartCraftTask task) {
        LinkCancelRetryState existing = this.linkCancelRetries.get(task.taskKey());
        int attempts = existing == null ? 1 : existing.attempts + 1;
        if (attempts > Config.LINK_CANCEL_RETRY_MAX_ATTEMPTS) {
            this.linkCancelRetries.remove(task.taskKey());
            session.clearExecution(task);
            this.linkCancelsFailedPermanently++;
            return task.withStatus(SmartCraftStatus.FAILED, CRAFTING_LINK_CANCELLED_REASON);
        }
        long delay = linkCancelBackoffForAttempt(attempts);
        this.linkCancelRetries.put(task.taskKey(), new LinkCancelRetryState(attempts, this.tickCounter + delay));
        this.linkCancelsAutoRetried++;
        session.clearExecution(task);
        return task.withStatus(
            SmartCraftStatus.PENDING,
            "Retrying canceled craft in " + delay
                + " ticks (attempt "
                + (attempts + 1)
                + "/"
                + (Config.LINK_CANCEL_RETRY_MAX_ATTEMPTS + 1)
                + "): "
                + CRAFTING_LINK_CANCELLED_REASON);
    }

    /** v0.1.8 (G6) True if link-cancel retry backoff has elapsed. */
    private boolean linkCancelRetryReady(SmartCraftTask task) {
        LinkCancelRetryState state = this.linkCancelRetries.get(task.taskKey());
        return state == null || this.tickCounter >= state.nextAllowedTick;
    }

    /**
     * v0.1.8.4 (G11) Sum of currently-pending failure attempts across the three retry layers
     * (plan / submit / link-cancel) for a single task. Returned to the client via
     * {@link com.homeftw.ae2intelligentscheduling.network.packet.SyncSmartCraftOrderPacket} so the
     * detail panel can surface "failed N times so far" badges to the player.
     *
     * <p>Semantic note: each {@code RetryState.attempts} counts failures that have actually
     * occurred (not retries scheduled), and the entry is removed from its map once the task
     * recovers. So this returns the number of failures observed in the CURRENT retry window \u2014
     * if the task ever managed to plan + submit + run cleanly, the counter resets to 0 even if
     * earlier retry windows had non-zero attempts. That matches what we want to display: the
     * player cares about "is this task currently struggling" more than "did it ever fail".
     */
    public int totalFailuresFor(String taskKey) {
        if (taskKey == null) return 0;
        int n = 0;
        PlanRetryState p = this.planRetries.get(taskKey);
        if (p != null) n += p.attempts;
        SubmitRetryState s = this.submitRetries.get(taskKey);
        if (s != null) n += s.attempts;
        LinkCancelRetryState l = this.linkCancelRetries.get(taskKey);
        if (l != null) n += l.attempts;
        return n;
    }

    private final SmartCraftOrderManager orderManager;
    private final Ae2CpuSelector cpuSelector;
    private final JobPlanner jobPlanner;
    private final JobSubmitter jobSubmitter;
    private final OrderSync orderSync;
    /**
     * v0.1.8.1 (G8) Optional. {@code null} disables order-completion notifications. Production
     * usage wires it to a chat + sound packet for {@link SmartCraftRuntimeSession#owner()}; unit
     * tests pass {@code null} via the 5-arg constructor or a recording stub via the 6-arg one.
     */
    private final OrderCompletionNotifier orderCompletionNotifier;
    private final Map<UUID, SmartCraftRuntimeSession> sessions = new LinkedHashMap<UUID, SmartCraftRuntimeSession>();
    /**
     * G1: per-task plan retry book-keeping. Keyed by {@link SmartCraftTask#taskKey()}. Cleared when
     * the task plans successfully, when retries are exhausted, or when its order is cancelled.
     */
    private final Map<String, PlanRetryState> planRetries = new HashMap<String, PlanRetryState>();
    /** v0.1.8 (G5): per-task submit retry book-keeping. Cleared on RUNNING transition or order cancel. */
    private final Map<String, SubmitRetryState> submitRetries = new HashMap<String, SubmitRetryState>();
    /** v0.1.8 (G6): per-task link-cancel retry book-keeping. Cleared on DONE / order cancel. */
    private final Map<String, LinkCancelRetryState> linkCancelRetries = new HashMap<String, LinkCancelRetryState>();
    /** v0.1.8 (G7): per-order auto-retry book-keeping. Keyed by orderId. */
    private final Map<UUID, OrderAutoRetryState> orderAutoRetries = new HashMap<UUID, OrderAutoRetryState>();
    /**
     * Monotonically increasing per-{@link #tick()} counter. Used as the timeline for planning
     * timeouts so tests can advance time deterministically by calling tick() in a loop and don't
     * have to mock the actual server clock.
     */
    private long tickCounter = 0L;

    // G4: scheduler health counters. All increments happen on the server thread inside tick(),
    // so plain longs are sufficient (no volatile / no AtomicLong needed). Reset never \u2014 they
    // accumulate across the server lifetime so admins can spot trends.
    private long plansAttempted = 0L;
    private long plansSucceeded = 0L;
    private long plansFailedPermanently = 0L;
    private long plansAutoRetried = 0L;
    private long runsCancelled = 0L;
    private long lastStatsLogTick = 0L;
    // v0.1.8: separate G5/G6/G7 retry counters so the periodic stats log surfaces which kind of
    // retry is responsible for any backlog.
    private long submitsAutoRetried = 0L;
    private long submitsFailedPermanently = 0L;
    private long linkCancelsAutoRetried = 0L;
    private long linkCancelsFailedPermanently = 0L;
    private long ordersAutoRetried = 0L;
    private long ordersAutoRetryExhausted = 0L;
    /**
     * v0.1.9.2 (G13) Counts how many task-dispatch attempts were gated by the global submission
     * cap ({@link Config#MAX_CONCURRENT_SMART_CRAFT_SUBMISSIONS}). A high value relative to
     * {@link #plansSucceeded} means the cap is the dominant throttle and the player may want to
     * raise it; a near-zero value means the cap is comfortably above natural concurrency.
     * Surfaced in the periodic stats log line.
     */
    private long submissionsThrottledByCap = 0L;

    public SmartCraftRuntimeCoordinator(SmartCraftOrderManager orderManager, Ae2CpuSelector cpuSelector,
        JobPlanner jobPlanner, JobSubmitter jobSubmitter, OrderSync orderSync) {
        this(orderManager, cpuSelector, jobPlanner, jobSubmitter, orderSync, null);
    }

    /**
     * v0.1.8.1 (G8) Full constructor. Pass a non-null {@link OrderCompletionNotifier} to receive
     * exactly-once callbacks when an order transitions to COMPLETED. Pass {@code null} for the
     * legacy behaviour (no order-level notification — e.g. unit tests).
     */
    public SmartCraftRuntimeCoordinator(SmartCraftOrderManager orderManager, Ae2CpuSelector cpuSelector,
        JobPlanner jobPlanner, JobSubmitter jobSubmitter, OrderSync orderSync,
        OrderCompletionNotifier orderCompletionNotifier) {
        this.orderManager = orderManager;
        this.cpuSelector = cpuSelector;
        this.jobPlanner = jobPlanner;
        this.jobSubmitter = jobSubmitter;
        this.orderSync = orderSync;
        this.orderCompletionNotifier = orderCompletionNotifier;
    }

    /**
     * v0.1.7 'terminal-vanishes' book-keeping. The first tick that observes
     * {@code afterTick.isFinished()==true} records the orderId here; the next tick that still
     * sees the order in a terminal state removes it. The 1-tick delay (≤100 ms wall time) lets
     * the {@link OrderSync} push a final "DONE/COMPLETED/CANCELLED/FAILED" packet for the order
     * BEFORE it disappears from the manager, and lets unit tests inspect the order's terminal
     * state via {@code orderManager.get(orderId)} immediately after the transition tick — they
     * just don't tick again.
     */
    private final java.util.Set<UUID> markedTerminalLastTick = new java.util.HashSet<UUID>();

    public void register(UUID orderId, SmartCraftRuntimeSession session) {
        if (orderId == null || session == null) return;
        this.sessions.put(orderId, session);
    }

    /**
     * v0.1.9.2 (G13) Sum of {@link SmartCraftRuntimeSession#countActiveSubmissions} across every
     * registered session. Drives the global submission cap from
     * {@link Config#MAX_CONCURRENT_SMART_CRAFT_SUBMISSIONS}: at any instant we never let the
     * total number of SmartCraft tasks holding an AE2 crafting link exceed that value.
     *
     * <p>O(total tasks across sessions) per call. Called once at the start of each
     * {@link #dispatchReadyTasks} (i.e. once per order per tick), so per-tick cost is O(orders \u00d7
     * tasks) which is the same asymptotic class as the rest of the dispatch pass.
     */
    public int globalActiveSubmissions() {
        int total = 0;
        for (SmartCraftRuntimeSession s : this.sessions.values()) {
            total += s.countActiveSubmissions();
        }
        return total;
    }

    /**
     * v0.1.9 (G12) Best-effort session rebuild after server restart. Called from action packet
     * handlers (cancel / retry / refresh) when the player triggers an interaction with a
     * persisted-but-orphaned order. Returns {@code true} when the session is now in place
     * (either it already was, or rebuild succeeded), {@code false} when rebuild was needed but
     * impossible (player has no AE2 container open / different player than the order's owner).
     *
     * <p>Idempotent and cheap: if the session map already contains the order it's a no-op.
     * The {@code factory} parameter is the production session factory; passing it explicitly
     * keeps the coordinator decoupled from the {@link AE2IntelligentScheduling} singleton, which
     * matters for testability.
     */
    public boolean attemptRebindSession(UUID orderId, net.minecraft.entity.player.EntityPlayerMP player,
        SmartCraftAe2RuntimeSessionFactory factory) {
        if (orderId == null || player == null || factory == null) return false;
        if (this.sessions.containsKey(orderId)) return true;
        Optional<com.homeftw.ae2intelligentscheduling.smartcraft.model.SmartCraftOrder> orderOpt = this.orderManager
            .get(orderId);
        if (!orderOpt.isPresent()) return false;
        // Owner gate: only the original player can resume their own order via this path. Without
        // this an admin could accidentally trigger a session for someone else's persisted order
        // and the resulting tick would route AE2 callbacks (chat / sound / craft completion) to
        // the wrong player.
        String ownerName = orderOpt.get()
            .ownerName();
        if (ownerName == null || ownerName.isEmpty()) return false;
        if (!ownerName.equals(player.getCommandSenderName())) return false;
        appeng.api.networking.security.BaseActionSource actionSource = SmartCraftAe2RuntimeSessionFactory
            .extractActionSourceFromOpenContainer(player);
        if (actionSource == null) return false;
        SmartCraftRuntimeSession session = factory.create(player, actionSource);
        if (session == null) return false;
        this.sessions.put(orderId, session);
        LOGGER.info(
            "Rebound SmartCraft session for order {} to player {} after server restart",
            orderId,
            player.getCommandSenderName());
        return true;
    }

    public Optional<SmartCraftRuntimeSession> session(UUID orderId) {
        return Optional.ofNullable(this.sessions.get(orderId));
    }

    /**
     * v0.1.7: read-only view of the sessions map used by the multi-order list sync. Returned map
     * is backed by the live map so callers see new sessions immediately, but they cannot mutate.
     * Callers must not iterate concurrently with a mutating tick — the only legitimate caller is
     * the order list sync which runs on the same server thread as tick().
     */
    public java.util.Map<UUID, SmartCraftRuntimeSession> sessionsView() {
        return java.util.Collections.unmodifiableMap(this.sessions);
    }

    public void syncLatestOrderForPlayer(net.minecraft.entity.player.EntityPlayerMP player) {
        for (java.util.Map.Entry<UUID, SmartCraftRuntimeSession> entry : this.sessions.entrySet()) {
            if (player.equals(
                entry.getValue()
                    .owner())) {
                this.orderSync.sync(entry.getValue(), entry.getKey());
                return;
            }
        }
    }

    public appeng.api.networking.crafting.ICraftingGrid craftingGridForPlayer(
        net.minecraft.entity.player.EntityPlayerMP player) {
        for (SmartCraftRuntimeSession session : this.sessions.values()) {
            if (player.equals(session.owner())) {
                return session.craftingGrid();
            }
        }
        return null;
    }

    public void tick() {
        this.tickCounter++;
        List<UUID> orphanedSessions = new ArrayList<UUID>();

        for (Map.Entry<UUID, SmartCraftRuntimeSession> entry : this.sessions.entrySet()) {
            UUID orderId = entry.getKey();
            SmartCraftRuntimeSession session = entry.getValue();
            Optional<SmartCraftOrder> existing = this.orderManager.get(orderId);
            if (!existing.isPresent()) {
                // Order has been removed from the manager (e.g. external admin command). Drop the
                // session along with any in-flight AE2 jobs to avoid leaking links.
                session.cancelAll();
                orphanedSessions.add(orderId);
                continue;
            }

            SmartCraftOrder order = existing.get();
            SmartCraftOrder updated = updateOrder(session, order);
            if (updated != order) {
                this.orderManager.update(orderId, updated);
                this.orderSync.sync(session, orderId);
                // (v0.1.8.1 G8) Exactly-once order-completion notification. We compare the
                // pre-update status (captured before updateOrder) against the post-update status
                // and fire the notifier on the rising edge into COMPLETED. Other terminal
                // transitions (CANCELLED / FAILED / PAUSED) are intentionally NOT notified —
                // CANCELLED was player-initiated, FAILED already surfaces a Retry banner, and
                // PAUSED is a partial-failure state where some sub-tasks may still get retried.
                // Re-entering COMPLETED is impossible (terminal-order-vanish removes the order on
                // the tick after, so the next tick's `existing` lookup returns Optional.empty),
                // which means this fires at most once per order lifetime.
                if (this.orderCompletionNotifier != null
                    && order.status() != SmartCraftStatus.COMPLETED
                    && updated.status() == SmartCraftStatus.COMPLETED) {
                    try {
                        this.orderCompletionNotifier.onOrderCompleted(session, orderId, updated);
                    } catch (Throwable t) {
                        // Defensive: a flaky notifier (e.g. broken player handle, network hiccup)
                        // must never derail the runtime tick — log and continue.
                        LOGGER.warn(
                            "OrderCompletionNotifier threw for order {}: {}",
                            orderId,
                            t);
                    }
                }
            }
            // (v0.1.7.1) Terminal-orders-vanish policy with FAILED retention: COMPLETED and
            // CANCELLED orders disappear automatically (success → no need to keep, cancellation
            // → player explicitly dropped it). FAILED orders STAY VISIBLE so the player can hit
            // Retry from the GUI; to discard a failed order the player clicks Cancel which
            // flips its status to CANCELLED and re-enters this branch on the next tick.
            // The 1-tick delay still applies for the auto-vanishing terminal states so OrderSync
            // gets one final packet through before removal.
            SmartCraftOrder afterTick = updated == order ? order : updated;
            // A finished order whose status is FAILED or PAUSED is "retry-eligible" — keep it.
            // applyOrderStatus surfaces PAUSED when every task reached a terminal state but at
            // least one was FAILED (the more common path), and FAILED only in rarer ones. Both
            // mean the player should be able to press Retry; CANCELLED + COMPLETED do not.
            SmartCraftStatus orderStatus = afterTick.status();
            boolean retryEligible = orderStatus == SmartCraftStatus.FAILED || orderStatus == SmartCraftStatus.PAUSED;
            boolean autoRemoveTerminal = afterTick.isFinished() && !retryEligible;
            if (autoRemoveTerminal) {
                // 1-tick delay: mark on the transition tick, remove on the next tick that still
                // sees the order finished. Lets the OrderSync push a final packet (this tick's
                // sync above just fired with the terminal status), and lets tests inspect the
                // terminal state via orderManager.get(orderId) before it vanishes.
                if (this.markedTerminalLastTick.contains(orderId)) {
                    this.orderManager.remove(orderId);
                    orphanedSessions.add(orderId);
                    this.markedTerminalLastTick.remove(orderId);
                    // (G1) Sweep retry book-keeping for the gone tasks so the retry budget map
                    // can't leak entries keyed on a vanished order.
                    for (SmartCraftLayer layer : afterTick.layers()) {
                        for (SmartCraftTask t : layer.tasks()) {
                            this.planRetries.remove(t.taskKey());
                            this.submitRetries.remove(t.taskKey());
                            this.linkCancelRetries.remove(t.taskKey());
                        }
                    }
                    this.orderAutoRetries.remove(orderId);
                } else {
                    this.markedTerminalLastTick.add(orderId);
                }
            } else {
                // Order is no longer finished (e.g. retry resurrected it). Drop the mark so a
                // future terminal transition gets the full 1-tick grace again.
                this.markedTerminalLastTick.remove(orderId);
            }
        }

        for (UUID orderId : orphanedSessions) {
            this.sessions.remove(orderId);
        }

        // (G7) Order-level server-side auto-retry. Walks every still-attached session whose order
        // sits in a retry-eligible terminal state (FAILED / PAUSED) and, after the configured
        // interval, server-presses Retry on the player's behalf. {@link OrderAutoRetryState} is
        // shaped so {@code firstFailedTick == -1L} means "order has been retried but the new run
        // hasn't yet observed a fresh failure"; that lets us count cumulative attempts across
        // the order's whole lifetime instead of resetting on each retry.
        if (Config.ORDER_AUTO_RETRY_MAX_ATTEMPTS > 0) {
            long intervalTicks = (long) Config.ORDER_AUTO_RETRY_INTERVAL_SECONDS * 20L;
            // Snapshot so we can safely doRetryFailed() inside the loop (which mutates orderManager).
            List<UUID> orderIdSnapshot = new ArrayList<UUID>(this.sessions.keySet());
            for (UUID orderId : orderIdSnapshot) {
                Optional<SmartCraftOrder> orderOpt = this.orderManager.get(orderId);
                if (!orderOpt.isPresent()) continue;
                SmartCraftStatus orderStatus = orderOpt.get()
                    .status();
                boolean retryEligibleStatus = orderStatus == SmartCraftStatus.FAILED
                    || orderStatus == SmartCraftStatus.PAUSED;
                OrderAutoRetryState st = this.orderAutoRetries.get(orderId);
                if (retryEligibleStatus) {
                    if (st == null) {
                        // First time we observe this order in a retry-eligible terminal state.
                        // Stamp the tick; next eligible tick after the interval will trigger retry.
                        this.orderAutoRetries.put(orderId, new OrderAutoRetryState(this.tickCounter, 0));
                    } else if (st.firstFailedTick == -1L) {
                        // Order was previously auto-retried, ran for a while, and now landed back
                        // in FAILED. Re-stamp the timer but PRESERVE attempts so the
                        // ORDER_AUTO_RETRY_MAX_ATTEMPTS budget is cumulative.
                        this.orderAutoRetries.put(orderId, new OrderAutoRetryState(this.tickCounter, st.attempts));
                    } else if (st.attempts < Config.ORDER_AUTO_RETRY_MAX_ATTEMPTS
                        && this.tickCounter - st.firstFailedTick >= intervalTicks) {
                        // Backoff window elapsed and we still have budget — server-press Retry.
                        this.ordersAutoRetried++;
                        LOGGER.info(
                            "SmartCraft auto-retry: server-pressing Retry on FAILED order {} (attempt {}/{})",
                            orderId,
                            Integer.valueOf(st.attempts + 1),
                            Integer.valueOf(Config.ORDER_AUTO_RETRY_MAX_ATTEMPTS));
                        doRetryFailed(orderId, false);
                        // Mark as "retried, awaiting next failure observation" with attempts++.
                        this.orderAutoRetries
                            .put(orderId, new OrderAutoRetryState(-1L, st.attempts + 1));
                    } else if (st.attempts >= Config.ORDER_AUTO_RETRY_MAX_ATTEMPTS
                        && st.firstFailedTick >= 0L) {
                        // Budget exhausted on this round: bump exhaustion counter exactly once
                        // by sentinel-marking firstFailedTick = -2L ("exhausted, do not log again").
                        this.ordersAutoRetryExhausted++;
                        LOGGER.warn(
                            "SmartCraft auto-retry: order {} exhausted {} attempts, leaving FAILED for player intervention",
                            orderId,
                            Integer.valueOf(Config.ORDER_AUTO_RETRY_MAX_ATTEMPTS));
                        this.orderAutoRetries.put(orderId, new OrderAutoRetryState(-2L, st.attempts));
                    }
                } else if (st != null && st.firstFailedTick >= 0L) {
                    // Order is no longer in a retry-eligible state (e.g. moved to RUNNING after a
                    // dispatch tick this same tick or a previous retry). Pause the timer; if it
                    // FAILs again later we'll re-stamp via the firstFailedTick == -1L branch.
                    this.orderAutoRetries.put(orderId, new OrderAutoRetryState(-1L, st.attempts));
                }
            }
        }

        // (G4) Periodic stats summary. Cheap (one comparison per tick when disabled, one log per
        // STATS_LOG_INTERVAL_TICKS otherwise). Counters are never reset \u2014 the log line reads as a
        // running total since server start.
        int statsInterval = Config.STATS_LOG_INTERVAL_TICKS;
        if (statsInterval > 0 && this.tickCounter - this.lastStatsLogTick >= (long) statsInterval) {
            this.lastStatsLogTick = this.tickCounter;
            LOGGER.info(
                "SmartCraft stats: plansAttempted={} succeeded={} planRetried={} planPermFailed={} submitRetried={} submitPermFailed={} linkCancelRetried={} linkCancelPermFailed={} ordersAutoRetried={} ordersAutoRetryExhausted={} runsCancelled={} activeSessions={} pendingPlanRetries={} pendingSubmitRetries={} pendingLinkCancelRetries={} pendingOrderAutoRetries={} submissionsThrottledByCap={} activeSubmissions={}/cap={}",
                Long.valueOf(this.plansAttempted),
                Long.valueOf(this.plansSucceeded),
                Long.valueOf(this.plansAutoRetried),
                Long.valueOf(this.plansFailedPermanently),
                Long.valueOf(this.submitsAutoRetried),
                Long.valueOf(this.submitsFailedPermanently),
                Long.valueOf(this.linkCancelsAutoRetried),
                Long.valueOf(this.linkCancelsFailedPermanently),
                Long.valueOf(this.ordersAutoRetried),
                Long.valueOf(this.ordersAutoRetryExhausted),
                Long.valueOf(this.runsCancelled),
                Integer.valueOf(this.sessions.size()),
                Integer.valueOf(this.planRetries.size()),
                Integer.valueOf(this.submitRetries.size()),
                Integer.valueOf(this.linkCancelRetries.size()),
                Integer.valueOf(this.orderAutoRetries.size()),
                Long.valueOf(this.submissionsThrottledByCap),
                Integer.valueOf(this.globalActiveSubmissions()),
                Integer.valueOf(Config.MAX_CONCURRENT_SMART_CRAFT_SUBMISSIONS));
        }
    }

    /**
     * Cancel an order: propagate to AE2 (cancel planning futures + cancel crafting links), flip every
     * non-terminal task to CANCELLED and the order itself to CANCELLED. Keeps the session alive so the
     * player can still trigger a retry from the UI without re-opening the terminal.
     */
    public Optional<SmartCraftOrder> cancel(UUID orderId) {
        SmartCraftRuntimeSession session = this.sessions.get(orderId);
        if (session != null) {
            session.cancelAll();
        }
        Optional<SmartCraftOrder> cancelled = this.orderManager.cancel(orderId);
        if (cancelled.isPresent()) {
            // (G4) Count this run, regardless of how many of its tasks had reached CPUs.
            this.runsCancelled++;
            // (G1) Sweep any pending retry state — the order's gone, no point holding the entries.
            for (SmartCraftLayer layer : cancelled.get()
                .layers()) {
                for (SmartCraftTask t : layer.tasks()) {
                    this.planRetries.remove(t.taskKey());
                    this.submitRetries.remove(t.taskKey());
                    this.linkCancelRetries.remove(t.taskKey());
                }
            }
            this.orderAutoRetries.remove(orderId);
            if (session != null) {
                this.orderSync.sync(session, orderId);
            }
        }
        return cancelled;
    }

    /**
     * v0.1.9.5 (G15) Remove an order from the manager outright. Used by the {@code CANCEL_ORDER}
     * packet handler when the target order is {@code interruptedByRestart} (the player's intent
     * is to drop the historical entry, not to cancel any active work which by definition no
     * longer exists). Distinct from {@link #cancel(UUID)} which only marks status {@code CANCELLED}
     * and relies on the next tick's auto-vanish to remove a non-retry-eligible terminal order
     * (which doesn't trigger for orders without a session, i.e. exactly the post-restart case).
     *
     * <p>Cleans up session bookkeeping and retry maps the same way {@code cancel} does so the
     * coordinator state is fully drained for the gone order. Returns the removed order or
     * {@link Optional#empty()} if the order id was unknown.
     */
    public Optional<SmartCraftOrder> removeOrder(UUID orderId) {
        Optional<SmartCraftOrder> existing = this.orderManager.get(orderId);
        if (!existing.isPresent()) {
            return Optional.empty();
        }
        SmartCraftRuntimeSession session = this.sessions.remove(orderId);
        if (session != null) {
            // Defensive: an interrupted-by-restart order should not have a session, but if some
            // future code path register()s one anyway, draining its links matches what cancel
            // would have done.
            session.cancelAll();
        }
        for (SmartCraftLayer layer : existing.get().layers()) {
            for (SmartCraftTask t : layer.tasks()) {
                this.planRetries.remove(t.taskKey());
                this.submitRetries.remove(t.taskKey());
                this.linkCancelRetries.remove(t.taskKey());
            }
        }
        this.orderAutoRetries.remove(orderId);
        this.markedTerminalLastTick.remove(orderId);
        this.orderManager.remove(orderId);
        // The packet handler is responsible for the player-facing sync (it has the player handle
        // we don't). Returning Optional.of(existing) is the signal that the action was applied.
        return existing;
    }

    /**
     * Soft-cancel an order: spare tasks already RUNNING (let AE2 finish them) but cancel everything
     * not-yet-started so no new crafts begin. Compared to {@link #cancel(UUID)} this preserves the
     * intermediate materials a half-done craft has already routed into AE2 storage clusters —
     * pulling the rug on a RUNNING task would orphan those items in the CPU's internal state.
     *
     * <p>
     * Mechanics: the OrderManager flips non-running cancellable statuses to CANCELLED. We then
     * sweep the session for executions tied to those cancelled tasks and drop them (which also
     * cancels in-flight planning futures). RUNNING tasks' executions \u2014 craftingLink, assignedCpu
     * \u2014 stay untouched so the next reconcile tick continues observing them to DONE.
     */
    public Optional<SmartCraftOrder> cancelGracefully(UUID orderId) {
        Optional<SmartCraftOrder> updated = this.orderManager.cancelGracefully(orderId);
        if (!updated.isPresent()) {
            return updated;
        }
        // (G4) Bump the same counter as hard cancel \u2014 from a "did this order finish naturally?"
        // perspective the answer is no in either case.
        this.runsCancelled++;
        SmartCraftRuntimeSession session = this.sessions.get(orderId);
        // (G1) Sweep retry state for tasks that just got CANCELLED. RUNNING tasks (spared) keep
        // their state because their plan already succeeded; this is a no-op for them.
        for (SmartCraftLayer layer : updated.get()
            .layers()) {
            for (SmartCraftTask task : layer.tasks()) {
                if (task.status() == SmartCraftStatus.CANCELLED) {
                    this.planRetries.remove(task.taskKey());
                    this.submitRetries.remove(task.taskKey());
                    this.linkCancelRetries.remove(task.taskKey());
                    if (session != null) {
                        // Drop session execution entries ONLY for tasks that just transitioned to
                        // CANCELLED. RUNNING tasks must keep their craftingLink so
                        // reconcileTaskExecution can observe link.isDone() / link.isCanceled()
                        // and finalize them on a future tick.
                        session.clearExecution(task);
                    }
                }
            }
        }
        if (session != null) {
            this.orderSync.sync(session, orderId);
        }
        return updated;
    }

    /**
     * Retry failed AND cancelled tasks. Clears any leftover execution entries so the next dispatch
     * pass can re-plan them from scratch instead of skipping due to stale {@link
     * SmartCraftRuntimeSession.TaskExecution} state.
     */
    public Optional<SmartCraftOrder> retryFailed(UUID orderId) {
        return doRetryFailed(orderId, true);
    }

    /**
     * v0.1.8 (G7) Internal retry implementation. {@code clearAutoRetryBudget} controls whether we
     * remove the {@link #orderAutoRetries} entry: player-initiated retry clears (player wants a
     * fresh budget); server-initiated auto-retry keeps the entry so {@link
     * Config#ORDER_AUTO_RETRY_MAX_ATTEMPTS} bounds the total automatic attempts across the
     * order's whole lifetime.
     */
    private Optional<SmartCraftOrder> doRetryFailed(UUID orderId, boolean clearAutoRetryBudget) {
        SmartCraftRuntimeSession session = this.sessions.get(orderId);
        Optional<SmartCraftOrder> retried = this.orderManager.retryFailedTasks(orderId);
        if (retried.isPresent()) {
            // Wipe any stale execution / stock baseline AND any G1 retry book-keeping left over
            // from the original run — manual retry must reset the per-task auto-retry budgets,
            // otherwise a task that exhausted its 3-attempt budget would immediately FAILED again
            // on the very next plan attempt.
            for (SmartCraftLayer layer : retried.get()
                .layers()) {
                for (SmartCraftTask task : layer.tasks()) {
                    if (task.status() == SmartCraftStatus.PENDING) {
                        this.planRetries.remove(task.taskKey());
                        this.submitRetries.remove(task.taskKey());
                        this.linkCancelRetries.remove(task.taskKey());
                        if (session != null) {
                            session.clearExecution(task);
                        }
                    }
                }
            }
            if (clearAutoRetryBudget) {
                this.orderAutoRetries.remove(orderId);
            }
            if (session != null) {
                this.orderSync.sync(session, orderId);
            }
        }
        return retried;
    }

    private SmartCraftOrder updateOrder(SmartCraftRuntimeSession session, SmartCraftOrder order) {
        SmartCraftOrder updated = reconcileTaskExecutions(session, order);
        updated = advanceLayers(updated);

        if (updated.status() == SmartCraftStatus.CANCELLED || updated.status() == SmartCraftStatus.FAILED
            || updated.status() == SmartCraftStatus.COMPLETED
            || updated.status() == SmartCraftStatus.PAUSED) {
            return updated;
        }

        updated = dispatchReadyTasks(session, updated);
        return applyLayerStatus(updated);
    }

    private SmartCraftOrder reconcileTaskExecutions(SmartCraftRuntimeSession session, SmartCraftOrder order) {
        SmartCraftOrder updated = order;
        for (int layerIndex = 0; layerIndex < updated.layers()
            .size(); layerIndex++) {
            SmartCraftLayer layer = updated.layers()
                .get(layerIndex);
            List<SmartCraftTask> nextTasks = new ArrayList<SmartCraftTask>(
                layer.tasks()
                    .size());
            boolean layerChanged = false;

            for (SmartCraftTask task : layer.tasks()) {
                SmartCraftTask nextTask = reconcileTaskExecution(session, order, task);
                if (nextTask != task) {
                    layerChanged = true;
                }
                nextTasks.add(nextTask);
            }

            if (layerChanged) {
                updated = updated.withLayer(layerIndex, layer.withTasks(nextTasks));
            }
        }
        return updated;
    }

    private SmartCraftTask reconcileTaskExecution(SmartCraftRuntimeSession session, SmartCraftOrder order,
        SmartCraftTask task) {
        // Tasks already in a terminal state must not be re-touched by AE2 link state.
        // Without this guard, a task that was just CANCELLED by `orderManager.cancel(...)` could be
        // flipped to FAILED on the next tick when the cancelled link reports `isCanceled()`.
        if (task.isTerminal()) {
            session.clearExecution(task);
            return task;
        }

        // VERIFYING_OUTPUT is kept around for backward compatibility (orders that were already in
        // this state when the fix below shipped). Treat it as a one-tick passthrough to DONE so we
        // never strand a task here. The previous "wait for stock to grow by amount" logic was broken:
        // by the time we observed link.isDone() the items were ALREADY in ME storage (AE2's
        // completeJob() runs in the same call stack as Platform.poweredInsert for the final output),
        // so the captured baseline already included the crafted output and the delta never reached
        // task.amount(). Trust AE2's terminal link state instead.
        if (task.status() == SmartCraftStatus.VERIFYING_OUTPUT) {
            session.clearExecution(task);
            return task.withStatus(SmartCraftStatus.DONE, null);
        }

        SmartCraftRuntimeSession.TaskExecution execution = session.executionFor(task);
        if (execution == null) {
            return task;
        }

        ICraftingLink craftingLink = execution.craftingLink();
        if (craftingLink != null) {
            if (craftingLink.isDone()) {
                // AE2's CraftingCPUCluster.injectItems decrements finalOutput, calls our bridge, then
                // invokes completeJob() (which markDone()s the link) — all in the same call stack
                // that subsequently routes the leftover stack into ME storage via the storage handler
                // chain. By the time we observe link.isDone() on the next server tick the items are
                // already queryable in the network. There is no need (and previously no correct way)
                // to verify the output via a stock baseline here; just transition to DONE so the next
                // layer can be dispatched.
                session.clearExecution(task);
                return task.withStatus(SmartCraftStatus.DONE, null);
            }
            if (craftingLink.isCanceled()) {
                // (G6) Order-level cancel takes precedence: if the player explicitly cancelled the
                // whole order then any link cancel is a downstream consequence of that, not an AE2-
                // side failure. Just record CANCELLED and don't burn link-cancel retry budget.
                if (order.status() == SmartCraftStatus.CANCELLED) {
                    session.clearExecution(task);
                    return task.withStatus(SmartCraftStatus.CANCELLED, CRAFTING_LINK_CANCELLED_REASON);
                }
                // (G6) AE2 cancelled the link mid-craft (pattern changed, automation broken,
                // cluster reformed). Route through handleLinkCancelFailure: clears the cached
                // plan (stock baseline likely shifted during the canceled craft) and either
                // schedules a minutes-scale backoff retry or, when the budget is exhausted,
                // marks the task FAILED.
                return handleLinkCancelFailure(session, task);
            }
            // RUNNING tasks have a working link — a previous link-cancel retry's bookkeeping
            // (if any) is now obsolete because we made it back to RUNNING successfully.
            this.linkCancelRetries.remove(task.taskKey());
            return task.withStatus(SmartCraftStatus.RUNNING, null);
        }

        ICraftingJob plannedJob = execution.plannedJob();
        if (plannedJob != null) {
            // (G2) WAITING_CPU stale-plan check: a task that has held a fully-computed plan for
            // longer than the configured threshold has likely outlived the ME stock snapshot the
            // plan was computed against (other orders, player I/O). Discard the cached plan and
            // drop back to PENDING so the next dispatch tick re-plans against current stock. This
            // is NOT a failure \u2014 we don't bump retry counters; the task simply gets a fresh plan.
            if (Config.WAITING_CPU_STALE_SECONDS > 0 && execution.waitingCpuSinceTick() >= 0L
                && task.status() == SmartCraftStatus.WAITING_CPU
                && this.tickCounter - execution.waitingCpuSinceTick() > (long) Config.WAITING_CPU_STALE_SECONDS * 20L) {
                session.clearExecution(task);
                return task.withStatus(SmartCraftStatus.PENDING, WAITING_CPU_STALE_REASON);
            }
            return task;
        }

        Future<ICraftingJob> planningFuture = execution.planningFuture();
        if (planningFuture == null || !planningFuture.isDone()) {
            // (G3) config-driven planning timeout. Default 60s = 1200 tick. Hitting the timeout
            // routes through (G1) handlePlanFailure so a transient AE2 stall gets retried before
            // we permanently FAIL the task.
            long timeoutTicks = (long) Config.PLANNING_TIMEOUT_SECONDS * 20L;
            if (this.tickCounter - execution.submittedAtTick() > timeoutTicks) {
                session.clearExecution(task);
                return handlePlanFailure(task, PLANNING_TIMEOUT_REASON);
            }
            return task.withStatus(SmartCraftStatus.SUBMITTING, null);
        }

        try {
            ICraftingJob readyJob = planningFuture.get();
            if (readyJob == null || readyJob.isSimulation()) {
                session.clearExecution(task);
                return handlePlanFailure(task, FAILED_TO_FINISH_REASON);
            }
            // Plan succeeded \u2014 clear any retry book-keeping and bump the success counter.
            this.planRetries.remove(task.taskKey());
            this.plansSucceeded++;
            session.attachPlannedJob(task, readyJob);
            return task.withStatus(SmartCraftStatus.WAITING_CPU, NO_IDLE_CPU_REASON);
        } catch (InterruptedException e) {
            Thread.currentThread()
                .interrupt();
            session.clearExecution(task);
            return handlePlanFailure(task, FAILED_TO_FINISH_REASON);
        } catch (ExecutionException e) {
            session.clearExecution(task);
            return handlePlanFailure(task, FAILED_TO_FINISH_REASON);
        }
    }

    /**
     * Bumps {@code currentLayerIndex} to the smallest layer that still has non-terminal-success
     * work. Used to drive the {@code Layer X/Y} UI label only — actual scheduling no longer keys
     * off this index because tasks dispatch as soon as their per-task dependencies are DONE,
     * regardless of which layer they live on. When every layer is fully DONE the index goes one
     * past the last layer and the order is marked COMPLETED.
     */
    private SmartCraftOrder advanceLayers(SmartCraftOrder order) {
        if (order.isFinished()) {
            return order;
        }
        if (order.layers()
            .isEmpty()) {
            return order.withStatus(SmartCraftStatus.COMPLETED);
        }

        int newIndex = order.layers()
            .size();
        for (int i = 0; i < order.layers()
            .size(); i++) {
            if (!order.layers()
                .get(i)
                .isComplete()) {
                newIndex = i;
                break;
            }
        }

        if (newIndex >= order.layers()
            .size()) {
            // (v0.1.7.1) Layer.isComplete() considers any terminal task complete (DONE / FAILED /
            // CANCELLED), so a fully-failed layer used to drive the order to COMPLETED here. Now
            // we only auto-mark COMPLETED when every task is DONE; other terminal mixes fall
            // through and applyLayerStatus surfaces PAUSED so the GUI Retry button stays usable.
            boolean allDone = true;
            outer: for (SmartCraftLayer layer : order.layers()) {
                for (SmartCraftTask t : layer.tasks()) {
                    if (t.status() != SmartCraftStatus.DONE) {
                        allDone = false;
                        break outer;
                    }
                }
            }
            if (allDone) {
                return order.withCurrentLayerIndex(newIndex)
                    .withStatus(SmartCraftStatus.COMPLETED);
            }
            return order.withCurrentLayerIndex(newIndex);
        }
        if (newIndex == order.currentLayerIndex()) {
            return order;
        }
        return order.withCurrentLayerIndex(newIndex);
    }

    /**
     * Walks every layer and dispatches any task whose dependencies are already DONE. Two
     * completely independent branches at different layer depths can therefore run in parallel as
     * long as idle CPUs are available, instead of being held back by the slowest sibling layer
     * (the old strict-layer-barrier behaviour). Tasks within the SAME node share their
     * dependencies (all splits depend on the same set of children), so the dependency graph stays
     * shallow and lookups are O(deps) per task.
     *
     * <p>
     * Dispatch is run in <strong>global priority order</strong> across layers, not the natural
     * iteration order:
     * <ul>
     * <li><b>(C) Critical-path-first</b>: a task's priority is the length of the longest dependency
     * chain that ends at it. Long chains start working before short chains so the longest path
     * (which dictates total order completion time) doesn't sit idle waiting for CPUs.</li>
     * <li><b>(E) Anti-starvation tiebreaker</b>: among equal-priority tasks, the one that has been
     * sitting in WAITING_CPU the longest gets first dibs on the next freed CPU. Prevents new
     * arrivals from continuously pre-empting tasks that arrived first.</li>
     * </ul>
     * Layer reconstruction at the end is purely a presentation concern \u2014 the dispatch decisions
     * themselves are made on a flat, sorted task list.
     */
    private SmartCraftOrder dispatchReadyTasks(SmartCraftRuntimeSession session, SmartCraftOrder order) {
        if (order.layers()
            .isEmpty()) {
            return order;
        }

        // ===== Phase 0: gather global per-tick state =====
        // Build a one-shot taskId \u2192 task lookup over the CURRENT order snapshot. Status changes we
        // make this pass are reflected in taskUpdates / nextLayers, but per-tick consistency only
        // requires that we don't re-dispatch the same task twice and that dep-checks see the
        // pre-dispatch state.
        Map<String, SmartCraftTask> tasksById = new HashMap<String, SmartCraftTask>();
        // FIX (P1-#3): track which requestKeys already have a sibling in SUBMITTING (i.e. mid-
        // planning). When a node is split into N parallel tasks, all N share the same requestKey.
        // If we let them plan concurrently, every plan is computed against the SAME stock snapshot
        // and the AE2 cluster ends up either failing the link or running fallback pattern crafts
        // that over-produce material. Serialize planning per requestKey: at most one split is in
        // SUBMITTING at a time. Once that split transitions to RUNNING (its plan has consumed its
        // share of stock from ME), the next sibling can plan against updated stock.
        java.util.Set<String> planningInFlightRequestKeys = new java.util.HashSet<String>();
        List<SmartCraftTask> allTasks = new ArrayList<SmartCraftTask>();
        for (SmartCraftLayer layer : order.layers()) {
            for (SmartCraftTask task : layer.tasks()) {
                tasksById.put(task.taskId(), task);
                allTasks.add(task);
                if (task.status() == SmartCraftStatus.SUBMITTING && task.requestKey() != null) {
                    planningInFlightRequestKeys.add(
                        task.requestKey()
                            .id());
                }
            }
        }

        // (C) Pre-compute critical-path length for every task. Cost is O(N + E) once per tick.
        Map<String, Integer> criticalPathLength = computeCriticalPathLengths(allTasks);

        // CPU pool is consulted ONLY when we are about to submit a fully-planned job. Planning itself
        // does not consume a CPU; we want as many tasks planning in parallel as the AE2 grid allows.
        List<ICraftingCPU> availableCpus = this.cpuSelector.idleCpus(
            session.craftingGrid()
                .getCpus());

        // ===== Phase 1: build dispatch comparators =====
        // Submit candidates (C+E): critical path desc, then waiting-CPU age (longest waiting wins).
        // Plan candidates (C only): critical path desc \u2014 plan stage doesn't compete for CPUs.
        final Map<String, Integer> cplFinal = criticalPathLength;
        java.util.Comparator<SmartCraftTask> submitOrderCmp = new java.util.Comparator<SmartCraftTask>() {

            @Override
            public int compare(SmartCraftTask a, SmartCraftTask b) {
                // Same C-then-E ordering, this time consulting waitingCpuSinceTick from the session.
                int cmp = Integer.compare(
                    cplFinal.getOrDefault(b.taskId(), 0)
                        .intValue(),
                    cplFinal.getOrDefault(a.taskId(), 0)
                        .intValue());
                if (cmp != 0) return cmp;
                long aWait = waitingTickFor(session, a);
                long bWait = waitingTickFor(session, b);
                // Smaller waitingTick = waited longer = HIGHER priority. -1 means never waited so
                // we sentinel it to Long.MAX_VALUE to push it AFTER any task that has been waiting.
                long aKey = aWait < 0L ? Long.MAX_VALUE : aWait;
                long bKey = bWait < 0L ? Long.MAX_VALUE : bWait;
                cmp = Long.compare(aKey, bKey);
                if (cmp != 0) return cmp;
                return a.taskId()
                    .compareTo(b.taskId());
            }
        };

        // ===== Phase 2: collect dispatch candidates by category =====
        List<SmartCraftTask> submitCandidates = new ArrayList<SmartCraftTask>();
        List<SmartCraftTask> planCandidates = new ArrayList<SmartCraftTask>();
        for (SmartCraftTask task : allTasks) {
            if (task.isTerminal()) continue;
            SmartCraftRuntimeSession.TaskExecution execution = session.executionFor(task);
            if (execution != null && execution.plannedJob() != null && execution.craftingLink() == null) {
                // (G5) Mid-backoff after a submitJob rejection: keep the cached plan but don't try
                // submitJob again until the backoff window expires. The task already carries the
                // "Retrying submit in N ticks" blocking reason from handleSubmitFailure.
                if (!submitRetryReady(task)) {
                    continue;
                }
                submitCandidates.add(task);
            } else if (execution == null && task.isReadyForSubmission() && areDependenciesDone(task, tasksById)) {
                String requestKeyId = task.requestKey() == null ? null
                    : task.requestKey()
                        .id();
                if (requestKeyId != null && planningInFlightRequestKeys.contains(requestKeyId)) {
                    continue;
                }
                // (G1) Skip tasks that are mid-backoff after a recent plan failure. Their status is
                // already PENDING with a "Retrying in N ticks" banner; we just don't let dispatch
                // pick them up until nextAllowedTick is reached.
                if (!planRetryReady(task)) {
                    continue;
                }
                // (G6) Same gate for link-cancel backoff: a task whose RUNNING link was just
                // canceled by AE2 lives on the minutes-scale retry timer. Don't let dispatch
                // re-plan it before that window expires — the underlying root cause (broken
                // automation, missing pattern provider) typically needs human action to clear.
                if (!linkCancelRetryReady(task)) {
                    continue;
                }
                planCandidates.add(task);
            }
        }
        // Submit candidates compete for the scarce CPU pool \u2014 sort them by full C+E priority.
        java.util.Collections.sort(submitCandidates, submitOrderCmp);
        // Plan candidates don't compete for any scarce resource other than same-requestKey
        // serialization (already filtered above). Sort by C only (+ taskId stable order) so longest
        // chains start planning earliest, which is what actually moves total completion time.
        java.util.Collections.sort(planCandidates, new java.util.Comparator<SmartCraftTask>() {

            @Override
            public int compare(SmartCraftTask a, SmartCraftTask b) {
                int cmp = Integer.compare(
                    cplFinal.getOrDefault(b.taskId(), 0)
                        .intValue(),
                    cplFinal.getOrDefault(a.taskId(), 0)
                        .intValue());
                if (cmp != 0) return cmp;
                return a.taskId()
                    .compareTo(b.taskId());
            }
        });

        // ===== Phase 3: apply decisions in priority order =====
        // (v0.1.9.2 G13) Compute the global submission budget ONCE per dispatchReadyTasks call.
        // Cap = Config.MAX_CONCURRENT_SMART_CRAFT_SUBMISSIONS (0 disables). Currently active
        // submissions across all sessions count against the cap; we only get to submit
        // (cap - active) more THIS call. Each successful link grant in the loop below decrements
        // the local budget so we never bind more than the cap allows in a single tick. Failed
        // submits (link == null) do NOT consume budget because they don't actually claim a
        // CraftingCPU cluster.
        int cap = Config.MAX_CONCURRENT_SMART_CRAFT_SUBMISSIONS;
        int globalBudget = cap <= 0 ? Integer.MAX_VALUE : Math.max(0, cap - this.globalActiveSubmissions());
        Map<String, SmartCraftTask> taskUpdates = new HashMap<String, SmartCraftTask>();
        for (SmartCraftTask task : submitCandidates) {
            SmartCraftRuntimeSession.TaskExecution execution = session.executionFor(task);
            if (execution == null || execution.plannedJob() == null || execution.craftingLink() != null) continue;
            // (G13) Cap gate: when the global budget is exhausted, keep the cached plan and the
            // assigned CPU pin (if any) but do NOT call submitJob. The task lives on with a
            // WAITING_CPU status and a throttle banner; next tick we recompute the budget and
            // re-try as soon as some other task's link finishes. Player-initiated AE2 crafts
            // never hit this code path \u2014 they go through ContainerCraftConfirm \u2192 submitJob
            // directly \u2014 so the cap never blocks a manual craft.
            if (globalBudget <= 0) {
                String banner = "Throttled: SmartCraft global submission cap reached (" + cap + ")";
                SmartCraftTask gated = task.withStatus(SmartCraftStatus.WAITING_CPU, banner);
                session.markWaitingCpu(task, this.tickCounter);
                this.submissionsThrottledByCap++;
                if (gated != task) {
                    taskUpdates.put(task.taskKey(), gated);
                }
                continue;
            }
            SmartCraftTask nextTask = task;
            Optional<ICraftingCPU> selectedCpu = takeNextCpu(availableCpus);
            if (!selectedCpu.isPresent()) {
                // (E) First time entering WAITING_CPU on this execution \u2014 stamp the tick so the
                // next dispatch can rank us by waiting age. Idempotent: re-entering WAITING_CPU
                // does not bump the timestamp.
                session.markWaitingCpu(task, this.tickCounter);
                nextTask = task.withStatus(SmartCraftStatus.WAITING_CPU, NO_IDLE_CPU_REASON);
            } else {
                session.attachAssignedCpu(task, selectedCpu.get());
                ICraftingLink link = this.jobSubmitter.submit(session, task, selectedCpu.get(), execution.plannedJob());
                if (link != null) {
                    session.attachCraftingLink(task, link);
                    // (G5) Successful submit clears the retry budget so a future link cancel that
                    // brings us back through submit gets a fresh 5-attempt allowance.
                    this.submitRetries.remove(task.taskKey());
                    nextTask = task.withStatus(SmartCraftStatus.RUNNING, null);
                    // (G13) Successful link claims one slot of the global budget. Decrement so
                    // subsequent candidates in this same loop respect the cap.
                    globalBudget--;
                } else {
                    String diagnosticReason = diagnoseSubmitFailure(
                        task,
                        execution.plannedJob(),
                        selectedCpu.get(),
                        session);
                    // (G5) Route through handleSubmitFailure: keep cached plan, drop CPU pin,
                    // either schedule a backoff retry (next dispatch tick after delay) or, when
                    // the budget is exhausted, finalize as FAILED with the diagnostic reason.
                    nextTask = handleSubmitFailure(session, task, diagnosticReason);
                }
            }
            if (nextTask != task) {
                taskUpdates.put(task.taskKey(), nextTask);
            }
        }

        for (SmartCraftTask task : planCandidates) {
            // Re-check sibling-planning gate: an earlier candidate in this same loop may have just
            // taken the requestKey slot. (P1-#3 contract: at most one in-flight planner per key.)
            String requestKeyId = task.requestKey() == null ? null
                : task.requestKey()
                    .id();
            if (requestKeyId != null && planningInFlightRequestKeys.contains(requestKeyId)) {
                continue;
            }
            SmartCraftTask nextTask = task;
            // (G4) Bump attempted-count BEFORE the call so we count even if begin() throws or
            // returns null. plansSucceeded is incremented downstream when the future resolves.
            this.plansAttempted++;
            try {
                Future<ICraftingJob> planningFuture = this.jobPlanner.begin(session, task);
                if (planningFuture == null) {
                    // (G1) Don't go straight to FAILED \u2014 the planner returning null is exactly the
                    // kind of transient symptom retry was designed for (planner thread pool busy,
                    // grid not yet stable on first tick of register, etc.).
                    nextTask = handlePlanFailure(task, FAILED_TO_BEGIN_REASON);
                } else {
                    session.trackPlanning(task, planningFuture, this.tickCounter);
                    nextTask = task.withStatus(SmartCraftStatus.SUBMITTING, null);
                    if (requestKeyId != null) {
                        planningInFlightRequestKeys.add(requestKeyId);
                    }
                }
            } catch (RuntimeException e) {
                // (G1) Same: a RuntimeException out of begin() is most often transient (NPE inside
                // AE2 storage chain when grid mutates mid-call). Retry instead of giving up.
                nextTask = handlePlanFailure(task, FAILED_TO_BEGIN_REASON);
            }
            if (nextTask != task) {
                taskUpdates.put(task.taskKey(), nextTask);
            }
        }

        // ===== Phase 4: rebuild layers from the updated task map =====
        if (taskUpdates.isEmpty()) {
            return order;
        }
        List<SmartCraftLayer> nextLayers = new ArrayList<SmartCraftLayer>(
            order.layers()
                .size());
        for (SmartCraftLayer layer : order.layers()) {
            boolean layerChanged = false;
            List<SmartCraftTask> nextTasks = new ArrayList<SmartCraftTask>(
                layer.tasks()
                    .size());
            for (SmartCraftTask task : layer.tasks()) {
                SmartCraftTask updated = taskUpdates.get(task.taskKey());
                if (updated != null) {
                    nextTasks.add(updated);
                    layerChanged = true;
                } else {
                    nextTasks.add(task);
                }
            }
            nextLayers.add(layerChanged ? layer.withTasks(nextTasks) : layer);
        }
        return order.withLayers(nextLayers);
    }

    /**
     * Computes, for each task, the length of the longest dependency chain that ends at that task
     * (in number of nodes including itself). A leaf task (no dependents) has length 1; a task
     * whose deepest dependent chain has 4 more tasks has length 5.
     *
     * <p>
     * This is the metric we want for "critical path first": a task with a long downstream chain
     * cannot be deferred without delaying everything in that chain, so it must be scheduled early.
     */
    private static Map<String, Integer> computeCriticalPathLengths(List<SmartCraftTask> tasks) {
        // Build reverse adjacency: dependents.get(X) = tasks Y that have X in their dependsOnTaskIds.
        Map<String, List<String>> dependents = new HashMap<String, List<String>>();
        for (SmartCraftTask t : tasks) {
            for (String dep : t.dependsOnTaskIds()) {
                List<String> list = dependents.get(dep);
                if (list == null) {
                    list = new ArrayList<String>();
                    dependents.put(dep, list);
                }
                list.add(t.taskId());
            }
        }
        Map<String, SmartCraftTask> byId = new HashMap<String, SmartCraftTask>();
        for (SmartCraftTask t : tasks) byId.put(t.taskId(), t);

        Map<String, Integer> memo = new HashMap<String, Integer>();
        for (SmartCraftTask t : tasks) {
            longestDownstream(t.taskId(), dependents, byId, memo);
        }
        return memo;
    }

    /**
     * DFS helper that memoizes the longest-downstream-chain length for a task. The dependency
     * graph is a DAG (builder enforces this), so a simple memoized DFS terminates without cycle
     * detection. Tasks not in {@code byId} (defensive, shouldn't happen) are treated as length 0.
     */
    private static int longestDownstream(String taskId, Map<String, List<String>> dependents,
        Map<String, SmartCraftTask> byId, Map<String, Integer> memo) {
        Integer cached = memo.get(taskId);
        if (cached != null) return cached.intValue();
        if (!byId.containsKey(taskId)) {
            memo.put(taskId, Integer.valueOf(0));
            return 0;
        }
        List<String> nexts = dependents.get(taskId);
        int max = 0;
        if (nexts != null) {
            for (String next : nexts) {
                int sub = longestDownstream(next, dependents, byId, memo);
                if (sub > max) max = sub;
            }
        }
        int result = 1 + max;
        memo.put(taskId, Integer.valueOf(result));
        return result;
    }

    /** Convenience accessor used by the submit-candidate sort. Returns {@code -1} if no execution. */
    private static long waitingTickFor(SmartCraftRuntimeSession session, SmartCraftTask task) {
        SmartCraftRuntimeSession.TaskExecution exec = session.executionFor(task);
        return exec == null ? -1L : exec.waitingCpuSinceTick();
    }

    private boolean areDependenciesDone(SmartCraftTask task, Map<String, SmartCraftTask> tasksById) {
        List<String> deps = task.dependsOnTaskIds();
        if (deps.isEmpty()) {
            return true;
        }
        for (String depId : deps) {
            SmartCraftTask dep = tasksById.get(depId);
            // Defensive: an unknown depId means the order graph is corrupt; refuse to dispatch so
            // the task surfaces as stuck rather than firing into AE2 with missing prerequisites.
            if (dep == null || dep.status() != SmartCraftStatus.DONE) {
                return false;
            }
        }
        return true;
    }

    /**
     * Derives the order's status from the union of ALL tasks across ALL layers (not just the
     * current UI layer). With cross-layer parallel dispatch, work in layer N and layer N+1 can be
     * live at the same tick, so a layer-local view would mis-report the order as PAUSED whenever
     * the lowest layer happened to have only failed/done tasks while higher layers still had real
     * progress. Iterating the full task set is O(total_tasks) per tick; orders are typically far
     * under a thousand tasks so this is negligible.
     */
    private SmartCraftOrder applyLayerStatus(SmartCraftOrder order) {
        if (order.layers()
            .isEmpty()) {
            return order.withStatus(SmartCraftStatus.COMPLETED);
        }

        boolean hasFailed = false;
        boolean hasActive = false;
        boolean hasWaiting = false;
        boolean hasPlannable = false;
        boolean hasAnyNonDone = false;

        for (SmartCraftLayer layer : order.layers()) {
            for (SmartCraftTask task : layer.tasks()) {
                SmartCraftStatus s = task.status();
                if (s != SmartCraftStatus.DONE) {
                    hasAnyNonDone = true;
                }
                if (s == SmartCraftStatus.FAILED) {
                    hasFailed = true;
                }
                if (s == SmartCraftStatus.SUBMITTING || s == SmartCraftStatus.RUNNING
                    || s == SmartCraftStatus.VERIFYING_OUTPUT) {
                    hasActive = true;
                }
                if (s == SmartCraftStatus.WAITING_CPU) {
                    hasWaiting = true;
                }
                if (s == SmartCraftStatus.PENDING || s == SmartCraftStatus.QUEUED) {
                    hasPlannable = true;
                }
            }
        }

        if (!hasAnyNonDone) {
            return order.withStatus(SmartCraftStatus.COMPLETED);
        }
        // Anything currently being executed wins: keep the order RUNNING (and let the next tick
        // decide again) regardless of whether some siblings have failed.
        if (hasActive) {
            return order.withStatus(SmartCraftStatus.RUNNING);
        }
        // Nothing actively executing — but some task could still progress on its own next tick.
        if (hasPlannable) {
            return order.withStatus(SmartCraftStatus.QUEUED);
        }
        if (hasWaiting) {
            return order.withStatus(SmartCraftStatus.WAITING_CPU);
        }
        // Nothing left that could move forward; if any task failed, surface PAUSED so the player
        // can press "Retry Failed". This used to be reached even when the SLOWEST layer happened
        // to be done with failures while a fast independent branch was still running; now that
        // hasActive / hasPlannable see the whole order, PAUSED only fires when truly stuck.
        if (hasFailed) {
            return order.withStatus(SmartCraftStatus.PAUSED);
        }
        return order.withStatus(SmartCraftStatus.QUEUED);
    }

    private Optional<ICraftingCPU> takeNextCpu(List<ICraftingCPU> availableCpus) {
        return availableCpus.isEmpty() ? Optional.<ICraftingCPU>empty() : Optional.of(availableCpus.remove(0));
    }
}
