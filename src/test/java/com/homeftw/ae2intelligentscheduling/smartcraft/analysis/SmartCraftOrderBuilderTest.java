package com.homeftw.ae2intelligentscheduling.smartcraft.analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.homeftw.ae2intelligentscheduling.smartcraft.analysis.SmartCraftOrderBuilder.TreeNode;
import com.homeftw.ae2intelligentscheduling.smartcraft.model.SmartCraftOrder;
import com.homeftw.ae2intelligentscheduling.smartcraft.model.SmartCraftOrderScale;
import com.homeftw.ae2intelligentscheduling.smartcraft.model.SmartCraftRequestKey;

class SmartCraftOrderBuilderTest {

    @Test
    void builds_bottom_up_layers_after_stock_deduction() {
        FakeTreeNode ironPlate = node("iron_plate", 1_500_000_000L, 300_000_000L);
        FakeTreeNode machineHull = node("machine_hull", 2L, 0L, ironPlate);

        // Use the merging-disabled builder: this test fixes node amounts (1.5G + tiny) chosen for
        // SMALL-scale classification and in-stock deduction, not for merge-threshold behaviour.
        SmartCraftOrder order = SmartCraftOrderBuilder.withMergingDisabled()
            .build(machineHull);

        assertEquals(SmartCraftOrderScale.SMALL, order.orderScale());
        assertEquals(
            2,
            order.layers()
                .size());
        assertEquals(
            2,
            order.layers()
                .get(0)
                .tasks()
                .size());
        assertEquals(
            "machine_hull",
            order.layers()
                .get(1)
                .tasks()
                .get(0)
                .requestKey()
                .id());
    }

    @Test
    void wires_task_dependencies_so_parents_wait_for_their_children_only() {
        // Tree: root depends on left and right; left depends on leaf-l; right depends on leaf-r.
        // The dependency graph must be: leaf-l (no deps), leaf-r (no deps), left (deps={leaf-l}),
        // right (deps={leaf-r}), root (deps={left, right}). Crucially `left` MUST NOT depend on
        // leaf-r and vice versa — that would resurrect the strict layer barrier we just removed.
        FakeTreeNode leafL = node("leaf-l", 64L, 0L);
        FakeTreeNode leafR = node("leaf-r", 64L, 0L);
        FakeTreeNode left = node("left", 1L, 0L, leafL);
        FakeTreeNode right = node("right", 1L, 0L, leafR);
        FakeTreeNode root = node("root", 1L, 0L, left, right);

        // Tiny-amount tree: every node is below the default 1M SMALL threshold and would be
        // merged out, leaving only root with no dependencies. Disable merging because this test
        // is verifying the dependency-graph wiring, not the merge filter.
        SmartCraftOrder order = SmartCraftOrderBuilder.withMergingDisabled()
            .build(root);

        com.homeftw.ae2intelligentscheduling.smartcraft.model.SmartCraftTask leafLTask = findTask(order, "leaf-l");
        com.homeftw.ae2intelligentscheduling.smartcraft.model.SmartCraftTask leafRTask = findTask(order, "leaf-r");
        com.homeftw.ae2intelligentscheduling.smartcraft.model.SmartCraftTask leftTask = findTask(order, "left");
        com.homeftw.ae2intelligentscheduling.smartcraft.model.SmartCraftTask rightTask = findTask(order, "right");
        com.homeftw.ae2intelligentscheduling.smartcraft.model.SmartCraftTask rootTask = findTask(order, "root");

        assertEquals(Collections.emptyList(), leafLTask.dependsOnTaskIds());
        assertEquals(Collections.emptyList(), leafRTask.dependsOnTaskIds());
        assertEquals(Collections.singletonList(leafLTask.taskId()), leftTask.dependsOnTaskIds());
        assertEquals(Collections.singletonList(leafRTask.taskId()), rightTask.dependsOnTaskIds());
        assertEquals(
            java.util.Arrays.asList(leftTask.taskId(), rightTask.taskId()),
            rootTask.dependsOnTaskIds(),
            "root depends on its direct children only, not on its grand-children");
    }

    @Test
    void in_stock_intermediate_node_propagates_grandchildren_as_parent_dependencies() {
        // If a middle node is already in stock (no task emitted) the parent must still depend on
        // the real work that gates the recipe, i.e. the in-stock node's own children. Otherwise a
        // root with a fully-stocked intermediate would race ahead of leaves that still need to be
        // crafted to satisfy the broader recipe requirements.
        FakeTreeNode leaf = node("leaf", 64L, 0L);
        FakeTreeNode inStockMiddle = node("middle", 1L, 1L, leaf); // requested == available => no task
        FakeTreeNode root = node("root", 1L, 0L, inStockMiddle);

        // Disable merging — verifying the in-stock-propagation rule, which must hold independently
        // of whether merge folding is active.
        SmartCraftOrder order = SmartCraftOrderBuilder.withMergingDisabled()
            .build(root);

        com.homeftw.ae2intelligentscheduling.smartcraft.model.SmartCraftTask leafTask = findTask(order, "leaf");
        com.homeftw.ae2intelligentscheduling.smartcraft.model.SmartCraftTask rootTask = findTask(order, "root");

        // Sanity: middle emitted no task.
        assertEquals(2, totalTasks(order), "middle is in stock so only leaf and root emit tasks");
        assertEquals(
            Collections.singletonList(leafTask.taskId()),
            rootTask.dependsOnTaskIds(),
            "root must transitively depend on leaf because middle skipped task emission");
    }

    // -------------------------------------------------------------------------
    // v0.1.5 (H1) merge folding regression tests
    // -------------------------------------------------------------------------

    /**
     * Small leaf (a few thousand items) folds into its parent's plan instead of claiming a CPU.
     * The parent is the only emitted task and has no dependencies because there is no other task
     * to wait on.
     */
    @Test
    void merge_folding_drops_below_threshold_leaf_into_parent_plan() {
        // 5_000 < 1_000_000 SMALL threshold → leaf is merged.
        FakeTreeNode tinyLeaf = node("tiny_leaf", 5_000L, 0L);
        // Parent stays large enough that the tree is still SMALL-scale and the parent is emitted.
        FakeTreeNode parent = node("parent", 100_000_000L, 0L, tinyLeaf);

        SmartCraftOrder order = new SmartCraftOrderBuilder(scale -> 1_000_000L).build(parent);

        assertEquals(1, totalTasks(order), "tiny_leaf must be merged into parent's plan");
        com.homeftw.ae2intelligentscheduling.smartcraft.model.SmartCraftTask parentTask = findTask(order, "parent");
        assertEquals(
            Collections.emptyList(),
            parentTask.dependsOnTaskIds(),
            "parent has no dependency because the only child was merged out");
    }

    /**
     * Root node is never merged regardless of how small its missing amount is — otherwise the
     * order would emit zero tasks and never execute. Guards against an off-by-one in the isRoot
     * check.
     */
    @Test
    void merge_folding_never_drops_the_root_even_if_below_threshold() {
        FakeTreeNode tinyRoot = node("tiny_root", 5L, 0L);

        SmartCraftOrder order = new SmartCraftOrderBuilder(scale -> 1_000_000L).build(tinyRoot);

        assertEquals(1, totalTasks(order), "root must always emit a task");
        com.homeftw.ae2intelligentscheduling.smartcraft.model.SmartCraftTask rootTask = findTask(order, "tiny_root");
        assertEquals(5L, rootTask.amount(), "root task carries its own missing amount");
    }

    /**
     * Mixed sub-trees: a parent has one big leaf (≥ threshold, kept) and one tiny leaf (< threshold,
     * merged). Big leaf must still gate the parent through dependsOnTaskIds; the merged tiny leaf
     * contributes nothing extra.
     */
    @Test
    void merge_folding_keeps_big_leaves_and_drops_small_siblings_only() {
        FakeTreeNode bigLeaf = node("big_leaf", 50_000_000L, 0L); // ≥ threshold → kept
        FakeTreeNode tinyLeaf = node("tiny_leaf", 5_000L, 0L); // < threshold → merged
        FakeTreeNode parent = node("parent", 100_000_000L, 0L, bigLeaf, tinyLeaf);

        SmartCraftOrder order = new SmartCraftOrderBuilder(scale -> 1_000_000L).build(parent);

        assertEquals(2, totalTasks(order), "big_leaf and parent emit; tiny_leaf is merged");
        com.homeftw.ae2intelligentscheduling.smartcraft.model.SmartCraftTask bigLeafTask = findTask(order, "big_leaf");
        com.homeftw.ae2intelligentscheduling.smartcraft.model.SmartCraftTask parentTask = findTask(order, "parent");
        assertEquals(
            Collections.singletonList(bigLeafTask.taskId()),
            parentTask.dependsOnTaskIds(),
            "parent depends only on the big leaf — tiny leaf was folded into parent's own plan");
    }

    /**
     * A merged middle node still propagates ITS children's task IDs to the parent: dropping the
     * middle task does not also drop the deeper work that the parent's plan must wait on. This
     * mirrors the in-stock-propagation rule but for the merged-out case.
     */
    @Test
    void merge_folding_propagates_grandchildren_through_merged_middle() {
        FakeTreeNode bigLeaf = node("big_leaf", 50_000_000L, 0L); // kept
        FakeTreeNode tinyMiddle = node("tiny_middle", 5_000L, 0L, bigLeaf); // merged → must transmit
        FakeTreeNode root = node("root", 100_000_000L, 0L, tinyMiddle);

        SmartCraftOrder order = new SmartCraftOrderBuilder(scale -> 1_000_000L).build(root);

        assertEquals(2, totalTasks(order), "big_leaf and root only — middle is merged out");
        com.homeftw.ae2intelligentscheduling.smartcraft.model.SmartCraftTask bigLeafTask = findTask(order, "big_leaf");
        com.homeftw.ae2intelligentscheduling.smartcraft.model.SmartCraftTask rootTask = findTask(order, "root");
        assertEquals(
            Collections.singletonList(bigLeafTask.taskId()),
            rootTask.dependsOnTaskIds(),
            "root must still gate on big_leaf even though tiny_middle was merged out between them");
    }

    /**
     * Threshold = 0 disables merging entirely. Same tiny-leaf tree as the first merge test, but
     * with the disabled resolver every node emits a task, recovering the v0.1.4 behaviour.
     */
    @Test
    void merge_folding_disabled_when_threshold_is_zero() {
        FakeTreeNode tinyLeaf = node("tiny_leaf", 5_000L, 0L);
        FakeTreeNode parent = node("parent", 100_000_000L, 0L, tinyLeaf);

        SmartCraftOrder order = SmartCraftOrderBuilder.withMergingDisabled()
            .build(parent);

        assertEquals(2, totalTasks(order), "merging disabled → tiny_leaf still emits its own task");
    }

    private static com.homeftw.ae2intelligentscheduling.smartcraft.model.SmartCraftTask findTask(SmartCraftOrder order,
        String requestKeyId) {
        for (com.homeftw.ae2intelligentscheduling.smartcraft.model.SmartCraftLayer layer : order.layers()) {
            for (com.homeftw.ae2intelligentscheduling.smartcraft.model.SmartCraftTask task : layer.tasks()) {
                if (requestKeyId.equals(
                    task.requestKey()
                        .id())) {
                    return task;
                }
            }
        }
        throw new IllegalStateException("no task for requestKeyId=" + requestKeyId);
    }

    private static int totalTasks(SmartCraftOrder order) {
        int total = 0;
        for (com.homeftw.ae2intelligentscheduling.smartcraft.model.SmartCraftLayer layer : order.layers()) {
            total += layer.tasks()
                .size();
        }
        return total;
    }

    private static FakeTreeNode node(String id, long requestedAmount, long availableAmount, FakeTreeNode... children) {
        return new FakeTreeNode(id, requestedAmount, availableAmount, java.util.Arrays.asList(children));
    }

    private static final class FakeTreeNode implements TreeNode {

        private final SmartCraftRequestKey requestKey;
        private final long requestedAmount;
        private final long availableAmount;
        private final List<FakeTreeNode> children;

        private FakeTreeNode(String id, long requestedAmount, long availableAmount, List<FakeTreeNode> children) {
            this.requestKey = new FakeRequestKey(id);
            this.requestedAmount = requestedAmount;
            this.availableAmount = availableAmount;
            this.children = Collections.unmodifiableList(children);
        }

        @Override
        public SmartCraftRequestKey requestKey() {
            return this.requestKey;
        }

        @Override
        public long requestedAmount() {
            return this.requestedAmount;
        }

        @Override
        public long availableAmount() {
            return this.availableAmount;
        }

        @Override
        public List<? extends TreeNode> children() {
            return this.children;
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
