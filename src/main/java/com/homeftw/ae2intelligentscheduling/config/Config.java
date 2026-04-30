package com.homeftw.ae2intelligentscheduling.config;

import java.io.File;

import net.minecraftforge.common.config.Configuration;

public class Config {

    public static final String GENERAL = "general";

    public static final String SCHEDULING = "scheduling";

    public static int MAX_CPU_PER_NODE = 16;
    public static boolean ENABLE_DEBUG_LOGGING = false;

    /**
     * v0.1.9.2 SmartCraft global submission cap. Maximum number of SmartCraft tasks that may be
     * holding an AE2 craftingLink (i.e. occupying a CraftingCPU cluster) at the same time across
     * ALL active orders and ALL players. Submission attempts beyond this cap are gated in
     * {@code SmartCraftRuntimeCoordinator.dispatchReadyTasks} and re-evaluated next tick.
     *
     * <p><b>Why this exists:</b> Programmable Hatches' "Auto CPU" multiblock ({@code TileCPU})
     * creates a brand-new {@code CraftingCPUCluster} on every tick whenever all existing
     * clusters are busy. SmartCraft can split a single big order into hundreds of parallel
     * tasks; each task acquires a cluster and the Auto CPU just keeps minting more, eventually
     * dragging the server tick into garbage-collection territory.
     *
     * <p><b>Player isolation:</b> the cap applies <i>only</i> to SmartCraft's dispatch path. A
     * player who manually presses "Start" on the AE2 Crafting Confirm screen goes through
     * vanilla AE2 ({@code ContainerCraftConfirm} \u2192 {@code submitJob}) which does NOT touch this
     * coordinator, so the cap never blocks a manual craft.
     *
     * <p>Default 50. Set 0 to disable the cap entirely (revert to pre-v0.1.9.2 behaviour).
     */
    public static int MAX_CONCURRENT_SMART_CRAFT_SUBMISSIONS = 50;

    /**
     * Seconds an AE2 planning future may take before the runtime declares it stuck and surfaces a
     * FAILED status. Default 60s covers legitimate big plans on busy networks; raise on heavily
     * modded servers where AE2 + GTNH recipe lookup can take noticeably longer per-node.
     * (Enhancement-G3.)
     */
    public static int PLANNING_TIMEOUT_SECONDS = 60;

    /**
     * Maximum auto-retry attempts before a plan failure is permanently marked FAILED.
     * Each transient plan failure (null future, RuntimeException, planning timeout) consumes one
     * attempt; we wait a backoff interval and re-try at the next dispatch tick. 0 disables auto-
     * retry entirely \u2014 first failure goes straight to FAILED, requiring manual UI retry.
     * (Enhancement-G1.)
     */
    public static int PLAN_RETRY_MAX_ATTEMPTS = 3;

    /**
     * v0.1.8 (G5) Auto-retry budget for submit failures (AE2 {@code submitJob} returning null:
     * picked CPU just turned busy, byte total exceeds every idle CPU). Distinct from the plan
     * retry budget because submit failures usually clear on the seconds-to-minutes scale (a CPU
     * frees up) whereas plan failures clear in ticks. Default 5 attempts. 0 disables submit
     * auto-retry \u2014 first {@code submitJob}=null becomes a permanent FAILED with a diagnostic
     * blocking-reason like before v0.1.8.
     */
    public static int SUBMIT_RETRY_MAX_ATTEMPTS = 5;

    /**
     * v0.1.8 (G6) Auto-retry budget for AE2 link cancellations on RUNNING tasks. AE2 may cancel
     * a link mid-craft when patterns change, automation chains break, or the cluster reforms.
     * For long-running orders (hours of craft time) we don't want a single transient cancel to
     * write off everything already invested. Default 2 attempts; the backoff is on the
     * minutes-tens-of-minutes scale because re-planning a deep dependency tree is expensive.
     * 0 disables \u2014 first {@code link.isCanceled()} becomes a permanent FAILED.
     */
    public static int LINK_CANCEL_RETRY_MAX_ATTEMPTS = 2;

    /**
     * v0.1.8 (G7) Server-side auto-retry of an order that has reached the FAILED state. Once an
     * order ends FAILED we wait this many seconds and then call {@code retryFailed(orderId)} as
     * if the player pressed the GUI button. Useful for unattended overnight crafts where a brief
     * network instability shouldn't require the player to log in. Default 600s (10min). Pairs
     * with {@link #ORDER_AUTO_RETRY_MAX_ATTEMPTS}.
     */
    public static int ORDER_AUTO_RETRY_INTERVAL_SECONDS = 600;

    /**
     * v0.1.8 (G7) Maximum number of times an order is auto-retried after it falls to FAILED
     * before giving up and leaving it FAILED for manual recovery. Default 3. 0 disables order-
     * level auto-retry entirely \u2014 every FAILED order waits for the player to click Retry.
     */
    public static int ORDER_AUTO_RETRY_MAX_ATTEMPTS = 3;

    /**
     * Seconds a task may sit in WAITING_CPU with a fully-computed plan before the runtime declares
     * the plan stale and forces a re-plan. Long waits here mean the ME stock the plan was computed
     * against has likely shifted (other orders, player I/O), so the cached plan would over- or
     * under-reserve at submit time. Default 600s = 10 minutes. Set 0 to disable (use cached plan
     * forever). (Enhancement-G2.)
     */
    public static int WAITING_CPU_STALE_SECONDS = 600;

    /**
     * Server-tick interval at which the runtime emits an INFO-level summary of planning attempts /
     * successes / failures / cancellations. Default 6000 ticks = 5 minutes. 0 disables the summary
     * entirely. Useful for spotting orders that quietly retry-loop or keep WAITING_CPU-aging.
     * (Enhancement-G4.)
     */
    public static int STATS_LOG_INTERVAL_TICKS = 6000;

    /**
     * v0.1.5 (H1) — merge threshold for SMALL-scale orders. An intermediate tree node whose
     * {@code missingAmount} is strictly below this value emits NO independent task; its children's
     * task IDs propagate up to its parent and the parent's AE2 plan absorbs the sub-recipe.
     *
     * <p>
     * Rationale: previously every intermediate node, no matter how small, claimed an idle CPU for
     * the few seconds its craft took. On multi-CPU networks a leaf needing only a few thousand
     * items could occupy a CPU that a 10M-amount sibling task was queued behind. Folding small
     * sub-trees into the parent's plan keeps those CPUs free for the genuinely big work.
     *
     * <p>
     * Set to {@code 0} to disable merging for SMALL-scale orders. Default 1M.
     */
    public static long MERGE_THRESHOLD_SMALL = SmartCraftMergeDefaults.SMALL;

    /**
     * v0.1.5 (H1) — merge threshold for MEDIUM-scale orders. See {@link #MERGE_THRESHOLD_SMALL} for
     * mechanics. Default 5M (higher than SMALL because medium-scale orders already split each
     * leaf-level node into multiple parallel tasks; the per-task amount we want to keep on its own
     * CPU is correspondingly larger).
     */
    public static long MERGE_THRESHOLD_MEDIUM = SmartCraftMergeDefaults.MEDIUM;

    /**
     * v0.1.5 (H1) — merge threshold for LARGE-scale orders. See {@link #MERGE_THRESHOLD_SMALL} for
     * mechanics. Default 10M.
     */
    public static long MERGE_THRESHOLD_LARGE = SmartCraftMergeDefaults.LARGE;

    private Config() {}

    /**
     * Compile-time defaults for the merge thresholds, kept here so the public static fields above
     * have valid initialisers without referencing the analysis package (avoiding a config →
     * analysis circular import while still giving the analysis package the single source of truth
     * for "what the defaults are").
     */
    private static final class SmartCraftMergeDefaults {

        static final long SMALL = 1_000_000L;
        static final long MEDIUM = 5_000_000L;
        static final long LARGE = 10_000_000L;
    }

    public static void synchronizeConfiguration(File configFile) {
        Configuration configuration = new Configuration(configFile);

        MAX_CPU_PER_NODE = configuration.getInt(
            "maxCpuPerNode",
            GENERAL,
            MAX_CPU_PER_NODE,
            1,
            64,
            "Maximum number of CPUs that smart craft may assign to one node.");
        ENABLE_DEBUG_LOGGING = configuration.getBoolean(
            "enableDebugLogging",
            GENERAL,
            ENABLE_DEBUG_LOGGING,
            "Enable verbose logging for smart craft analysis and scheduling.");

        MAX_CONCURRENT_SMART_CRAFT_SUBMISSIONS = configuration.getInt(
            "maxConcurrentSmartCraftSubmissions",
            SCHEDULING,
            MAX_CONCURRENT_SMART_CRAFT_SUBMISSIONS,
            0,
            1024,
            "Max number of SmartCraft tasks that may simultaneously hold an AE2 crafting link"
                + " (i.e. occupy a CraftingCPU cluster) across all orders and players. Prevents"
                + " Programmable Hatches' Auto CPU from minting unbounded clusters when a large"
                + " order splits into hundreds of parallel tasks. Player-initiated crafts via the"
                + " AE2 Crafting Confirm screen bypass the cap entirely. 0 = disabled. Default 50.");

        PLANNING_TIMEOUT_SECONDS = configuration.getInt(
            "planningTimeoutSeconds",
            SCHEDULING,
            PLANNING_TIMEOUT_SECONDS,
            5,
            600,
            "Seconds before an AE2 planning future is declared stuck. Default 60s.");
        PLAN_RETRY_MAX_ATTEMPTS = configuration.getInt(
            "planRetryMaxAttempts",
            SCHEDULING,
            PLAN_RETRY_MAX_ATTEMPTS,
            0,
            10,
            "Auto-retries before a plan failure becomes permanent. 0 = no auto-retry. Default 3.");
        SUBMIT_RETRY_MAX_ATTEMPTS = configuration.getInt(
            "submitRetryMaxAttempts",
            SCHEDULING,
            SUBMIT_RETRY_MAX_ATTEMPTS,
            0,
            10,
            "Auto-retries when AE2 submitJob returns null (CPU busy / capacity short). 0 = no auto-retry, first failure becomes permanent FAILED. Default 5.");
        LINK_CANCEL_RETRY_MAX_ATTEMPTS = configuration.getInt(
            "linkCancelRetryMaxAttempts",
            SCHEDULING,
            LINK_CANCEL_RETRY_MAX_ATTEMPTS,
            0,
            5,
            "Auto-retries when AE2 cancels a RUNNING task's craftingLink (pattern changed, automation broken). 0 = no auto-retry. Default 2.");
        ORDER_AUTO_RETRY_INTERVAL_SECONDS = configuration.getInt(
            "orderAutoRetryIntervalSeconds",
            SCHEDULING,
            ORDER_AUTO_RETRY_INTERVAL_SECONDS,
            10,
            7200,
            "Seconds to wait after an order reaches FAILED before the server auto-presses Retry. Default 600s (10min).");
        ORDER_AUTO_RETRY_MAX_ATTEMPTS = configuration.getInt(
            "orderAutoRetryMaxAttempts",
            SCHEDULING,
            ORDER_AUTO_RETRY_MAX_ATTEMPTS,
            0,
            10,
            "Max times the server auto-retries a FAILED order. 0 disables order-level auto-retry. Default 3.");
        WAITING_CPU_STALE_SECONDS = configuration.getInt(
            "waitingCpuStaleSeconds",
            SCHEDULING,
            WAITING_CPU_STALE_SECONDS,
            0,
            3600,
            "Seconds a WAITING_CPU task may keep its cached plan before the runtime forces a re-plan. 0 = never. Default 600s (10min).");
        STATS_LOG_INTERVAL_TICKS = configuration.getInt(
            "statsLogIntervalTicks",
            SCHEDULING,
            STATS_LOG_INTERVAL_TICKS,
            0,
            72000,
            "Tick interval between scheduler stats summary log lines. 0 = disabled. Default 6000 ticks (5min).");

        // v0.1.5 (H1) merge thresholds. Forge's getInt is fine for these — even the largest
        // sensible value (LARGE-scale order, 100M) fits in int. Setting any of these to 0 disables
        // merging for that scale and reverts to the v0.1.4 "every node = one task" behaviour.
        MERGE_THRESHOLD_SMALL = configuration.getInt(
            "mergeThresholdSmall",
            SCHEDULING,
            (int) MERGE_THRESHOLD_SMALL,
            0,
            Integer.MAX_VALUE,
            "Min amount for a SMALL-scale node to emit its own task; below this, the node is folded into the parent's plan. 0 = disabled. Default 1000000.");
        MERGE_THRESHOLD_MEDIUM = configuration.getInt(
            "mergeThresholdMedium",
            SCHEDULING,
            (int) MERGE_THRESHOLD_MEDIUM,
            0,
            Integer.MAX_VALUE,
            "Min amount for a MEDIUM-scale node to emit its own task. 0 = disabled. Default 5000000.");
        MERGE_THRESHOLD_LARGE = configuration.getInt(
            "mergeThresholdLarge",
            SCHEDULING,
            (int) MERGE_THRESHOLD_LARGE,
            0,
            Integer.MAX_VALUE,
            "Min amount for a LARGE-scale node to emit its own task. 0 = disabled. Default 10000000.");

        if (configuration.hasChanged()) {
            configuration.save();
        }
    }
}
