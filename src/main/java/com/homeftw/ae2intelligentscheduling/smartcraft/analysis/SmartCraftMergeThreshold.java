package com.homeftw.ae2intelligentscheduling.smartcraft.analysis;

import com.homeftw.ae2intelligentscheduling.config.Config;
import com.homeftw.ae2intelligentscheduling.smartcraft.model.SmartCraftOrderScale;

/**
 * Per-{@link SmartCraftOrderScale} threshold for "merge small intermediate nodes into the parent's
 * AE2 plan instead of emitting an independent task". Without this filter every intermediate
 * tree node — even a leaf needing only a few thousand items — claimed an entire idle CPU for the
 * few seconds it took to craft, badly fragmenting the CPU pool on multi-CPU networks.
 *
 * <p>
 * Behaviour: a node whose {@code missingAmount} falls strictly below the threshold for the order's
 * scale is <b>not</b> emitted as a SmartCraftTask. Its children's emitted task IDs are propagated
 * upward as the parent's dependencies (matching the existing in-stock-node rule). The parent's
 * AE2 plan, when finally computed, will recursively pull these merged sub-recipes into a single
 * {@link appeng.api.networking.crafting.ICraftingJob} that runs on the parent's CPU.
 *
 * <p>
 * The root node is <b>never</b> merged, regardless of amount — a merged root would emit zero tasks
 * and the order would never execute.
 *
 * <p>
 * Set {@link Config#MERGE_THRESHOLD_SMALL} / {@code _MEDIUM} / {@code _LARGE} to {@code 0L} to
 * disable merging for that scale and restore the pre-v0.1.5 "every node = one task" behaviour.
 */
public final class SmartCraftMergeThreshold {

    public static final long DEFAULT_SMALL = 1_000_000L;
    public static final long DEFAULT_MEDIUM = 5_000_000L;
    public static final long DEFAULT_LARGE = 10_000_000L;

    /**
     * Sentinel returned for the "merging is disabled" path.
     */
    public static final long DISABLED = 0L;

    private SmartCraftMergeThreshold() {}

    /**
     * Returns the configured threshold for the given scale. Reads from {@link Config} at call time
     * so config reloads take effect immediately on the next order. {@code <= 0} means disabled.
     */
    public static long fromConfig(SmartCraftOrderScale scale) {
        switch (scale) {
            case SMALL:
                return Math.max(0L, Config.MERGE_THRESHOLD_SMALL);
            case MEDIUM:
                return Math.max(0L, Config.MERGE_THRESHOLD_MEDIUM);
            case LARGE:
                return Math.max(0L, Config.MERGE_THRESHOLD_LARGE);
            default:
                return DISABLED;
        }
    }
}
