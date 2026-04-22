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

        SmartCraftOrder order = new SmartCraftOrderBuilder().build(machineHull);

        assertEquals(SmartCraftOrderScale.SMALL, order.orderScale());
        assertEquals(2, order.layers().size());
        assertEquals(2, order.layers().get(0).tasks().size());
        assertEquals("machine_hull", order.layers().get(1).tasks().get(0).requestKey().id());
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
    }
}
