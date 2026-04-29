package com.homeftw.ae2intelligentscheduling.smartcraft.analysis;

import java.util.ArrayList;
import java.util.List;

import com.homeftw.ae2intelligentscheduling.smartcraft.model.SmartCraftLayer;
import com.homeftw.ae2intelligentscheduling.smartcraft.model.SmartCraftOrder;
import com.homeftw.ae2intelligentscheduling.smartcraft.model.SmartCraftOrderScale;
import com.homeftw.ae2intelligentscheduling.smartcraft.model.SmartCraftRequestKey;
import com.homeftw.ae2intelligentscheduling.smartcraft.model.SmartCraftStatus;
import com.homeftw.ae2intelligentscheduling.smartcraft.model.SmartCraftTask;

public final class SmartCraftOrderBuilder {

    public interface TreeNode {

        SmartCraftRequestKey requestKey();

        long requestedAmount();

        long availableAmount();

        List<? extends TreeNode> children();
    }

    /**
     * Resolves the merge threshold for the scale of the order being built. Production code uses
     * {@link SmartCraftMergeThreshold#fromConfig(SmartCraftOrderScale)}; tests inject 0 (disabled)
     * or scale-specific overrides without touching global Config state.
     */
    public interface MergeThresholdResolver {

        long thresholdFor(SmartCraftOrderScale scale);
    }

    private final MergeThresholdResolver mergeThresholds;

    public SmartCraftOrderBuilder() {
        this(SmartCraftMergeThreshold::fromConfig);
    }

    public SmartCraftOrderBuilder(MergeThresholdResolver mergeThresholds) {
        this.mergeThresholds = mergeThresholds;
    }

    /**
     * Test/operator helper: returns a builder with merging disabled across all scales. Equivalent
     * to setting every {@code MERGE_THRESHOLD_*} config key to 0. Use in tests that target
     * dependency-graph or layering behaviour and don't want merge folding to perturb task counts.
     */
    public static SmartCraftOrderBuilder withMergingDisabled() {
        return new SmartCraftOrderBuilder(scale -> SmartCraftMergeThreshold.DISABLED);
    }

    public SmartCraftOrder build(TreeNode root) {
        return build(root, "");
    }

    /**
     * v0.1.9 (G12) Owner-aware build entry point. Production callers pass the issuing player's
     * username so the resulting order can survive a server restart and re-bind to the same
     * player on demand.
     */
    public SmartCraftOrder build(TreeNode root, String ownerName) {
        SmartCraftOrderScale scale = SmartCraftOrderScaleClassifier.classify(findMaxMissing(root));
        List<SmartCraftLayer> layers = new ArrayList<>();
        int[] nextTaskId = new int[] { 0 };
        long mergeThreshold = this.mergeThresholds.thresholdFor(scale);
        visit(root, scale, layers, nextTaskId, mergeThreshold, true);
        return SmartCraftOrder.queued(root.requestKey(), missingAmount(root), scale, layers, ownerName);
    }

    /**
     * Visits a node, ensures every dependency subtree is built first, and returns the taskIds
     * emitted for THIS node so the parent can wire them as its own dependencies. The returned list
     * is what the parent must wait on before its own crafting can start. Layer index follows the
     * height-from-leaves rule (layerIndex = max(child.layerIndex) + 1) so the UI still gets a
     * stable layered view, but the actual scheduler keys off taskId-level dependencies and is free
     * to overlap layers when the dependency graph allows it.
     *
     * <p>
     * v0.1.5 (H1) merge folding: a non-root node whose missing amount is below
     * {@code mergeThreshold} emits NO task. Its children's task IDs propagate up so the eventual
     * parent's AE2 plan absorbs the merged sub-recipe. The root is always emitted, regardless of
     * size, otherwise the order would have zero tasks and never execute.
     */
    private VisitResult visit(TreeNode node, SmartCraftOrderScale scale, List<SmartCraftLayer> layers, int[] nextTaskId,
        long mergeThreshold, boolean isRoot) {
        int layerIndex = 0;
        boolean hasChildren = false;
        List<String> dependsOnTaskIds = new ArrayList<>();

        for (TreeNode child : node.children()) {
            hasChildren = true;
            VisitResult childResult = visit(child, scale, layers, nextTaskId, mergeThreshold, false);
            layerIndex = Math.max(layerIndex, childResult.layerIndex + 1);
            // A child that contributed no tasks (already in stock OR merged into us) does not gate
            // this node directly, but we DO chain its propagated taskIds because they represent
            // the deeper work that still has to run before our own AE2 plan can succeed.
            dependsOnTaskIds.addAll(childResult.emittedTaskIds);
        }

        if (!hasChildren) {
            layerIndex = 0;
        }

        long missingAmount = missingAmount(node);
        // (H1) Merge folding: a small non-root node defers its work into the parent's plan.
        // Threshold == 0 disables the optimisation and behaves as v0.1.4.
        boolean shouldMerge = !isRoot && missingAmount > 0L && mergeThreshold > 0L && missingAmount < mergeThreshold;
        List<String> emittedTaskIds;
        if (missingAmount > 0L && !shouldMerge) {
            List<SmartCraftTask> tasks = toTasks(
                node.requestKey(),
                missingAmount,
                layerIndex,
                scale,
                nextTaskId,
                dependsOnTaskIds);
            ensureLayer(layers, layerIndex).tasks()
                .addAll(tasks);
            emittedTaskIds = new ArrayList<>(tasks.size());
            for (SmartCraftTask task : tasks) {
                emittedTaskIds.add(task.taskId());
            }
        } else {
            // Either this node is already in stock, OR it was below the merge threshold. In both
            // cases we don't emit a task here and instead propagate our children's taskIds up so
            // the parent's plan correctly waits on the real underlying work.
            emittedTaskIds = dependsOnTaskIds;
        }

        return new VisitResult(layerIndex, emittedTaskIds);
    }

    private SmartCraftLayer ensureLayer(List<SmartCraftLayer> layers, int layerIndex) {
        while (layers.size() <= layerIndex) {
            layers.add(new SmartCraftLayer(layers.size()));
        }
        return layers.get(layerIndex);
    }

    private List<SmartCraftTask> toTasks(SmartCraftRequestKey requestKey, long missingAmount, int depth,
        SmartCraftOrderScale scale, int[] nextTaskId, List<String> dependsOnTaskIds) {
        List<Long> parts = SmartCraftSplitPlanner.splitAmount(scale, missingAmount);
        List<SmartCraftTask> tasks = new ArrayList<>(parts.size());
        for (int i = 0; i < parts.size(); i++) {
            tasks.add(
                new SmartCraftTask(
                    "task-" + nextTaskId[0]++,
                    requestKey,
                    parts.get(i)
                        .longValue(),
                    depth,
                    i + 1,
                    parts.size(),
                    SmartCraftStatus.PENDING,
                    null,
                    dependsOnTaskIds));
        }
        return tasks;
    }

    private static final class VisitResult {

        final int layerIndex;
        final List<String> emittedTaskIds;

        VisitResult(int layerIndex, List<String> emittedTaskIds) {
            this.layerIndex = layerIndex;
            this.emittedTaskIds = emittedTaskIds;
        }
    }

    private long findMaxMissing(TreeNode node) {
        long maxMissing = missingAmount(node);
        for (TreeNode child : node.children()) {
            maxMissing = Math.max(maxMissing, findMaxMissing(child));
        }
        return maxMissing;
    }

    private long missingAmount(TreeNode node) {
        return Math.max(0L, node.requestedAmount() - node.availableAmount());
    }
}
