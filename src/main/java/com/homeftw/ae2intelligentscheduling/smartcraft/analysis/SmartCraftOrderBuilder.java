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

    public SmartCraftOrder build(TreeNode root) {
        SmartCraftOrderScale scale = SmartCraftOrderScaleClassifier.classify(findMaxMissing(root));
        List<SmartCraftLayer> layers = new ArrayList<>();
        visit(root, scale, layers);
        return SmartCraftOrder.queued(root.requestKey(), missingAmount(root), scale, layers);
    }

    private int visit(TreeNode node, SmartCraftOrderScale scale, List<SmartCraftLayer> layers) {
        int layerIndex = 0;
        boolean hasChildren = false;

        for (TreeNode child : node.children()) {
            hasChildren = true;
            layerIndex = Math.max(layerIndex, visit(child, scale, layers) + 1);
        }

        if (!hasChildren) {
            layerIndex = 0;
        }

        long missingAmount = missingAmount(node);
        if (missingAmount > 0L) {
            ensureLayer(layers, layerIndex).tasks().addAll(toTasks(node.requestKey(), missingAmount, layerIndex, scale));
        }

        return layerIndex;
    }

    private SmartCraftLayer ensureLayer(List<SmartCraftLayer> layers, int layerIndex) {
        while (layers.size() <= layerIndex) {
            layers.add(new SmartCraftLayer(layers.size()));
        }
        return layers.get(layerIndex);
    }

    private List<SmartCraftTask> toTasks(SmartCraftRequestKey requestKey, long missingAmount, int depth,
            SmartCraftOrderScale scale) {
        List<Long> parts = SmartCraftSplitPlanner.splitAmount(scale, missingAmount);
        List<SmartCraftTask> tasks = new ArrayList<>(parts.size());
        for (int i = 0; i < parts.size(); i++) {
            tasks.add(new SmartCraftTask(
                requestKey,
                parts.get(i).longValue(),
                depth,
                i + 1,
                parts.size(),
                SmartCraftStatus.PENDING,
                null));
        }
        return tasks;
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
