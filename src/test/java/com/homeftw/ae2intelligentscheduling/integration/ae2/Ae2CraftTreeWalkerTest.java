package com.homeftw.ae2intelligentscheduling.integration.ae2;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Collections;

import net.minecraft.inventory.InventoryCrafting;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.world.World;

import org.junit.jupiter.api.Test;

import appeng.api.config.CraftingMode;
import appeng.api.config.FuzzyMode;
import appeng.api.networking.crafting.ICraftingPatternDetails;
import appeng.api.storage.StorageChannel;
import appeng.api.storage.data.IAETagCompound;
import appeng.api.storage.data.IAEItemStack;
import appeng.crafting.v2.CraftingRequest;
import appeng.crafting.v2.CraftingRequest.SubstitutionMode;
import appeng.crafting.v2.resolvers.CraftableItemResolver.CraftFromPatternTask;
import appeng.crafting.v2.resolvers.CraftableItemResolver.RequestAndPerCraftAmount;
import io.netty.buffer.ByteBuf;

class Ae2CraftTreeWalkerTest {

    private static final Item INPUT_ITEM = new Item();
    private static final Item OUTPUT_ITEM = new Item();
    private static final Item PATTERN_ITEM = new Item();

    @Test
    void walks_child_requests_from_craft_from_pattern_tasks() throws Exception {
        CraftingRequest<IAEItemStack> childRequest = request(INPUT_ITEM, 1_500_000_000L);
        CraftingRequest<IAEItemStack> rootRequest = request(OUTPUT_ITEM, 2L);

        CraftFromPatternTask rootTask = new CraftFromPatternTask(
            rootRequest,
            new FakePattern(
                stack(INPUT_ITEM, 1L),
                stack(OUTPUT_ITEM, 1L)),
            0,
            false,
            false);
        rootTask.loadChildren(Collections.singletonList(new RequestAndPerCraftAmount(childRequest, 1L)));
        rootRequest.usedResolvers.add(new CraftingRequest.UsedResolverEntry<IAEItemStack>(rootRequest, rootTask, rootRequest.stack.copy()));

        Ae2CraftTreeWalker.Ae2TreeNodeSnapshot snapshot = new Ae2CraftTreeWalker().walk(rootRequest);

        assertEquals(2L, snapshot.requestedAmount());
        assertEquals(1, snapshot.children().size());
        assertEquals(1_500_000_000L, snapshot.children().get(0).requestedAmount());
    }

    private static CraftingRequest<IAEItemStack> request(net.minecraft.item.Item item, long amount) {
        IAEItemStack stack = stack(item, amount);
        return new CraftingRequest<IAEItemStack>(
            stack,
            SubstitutionMode.PRECISE_FRESH,
            IAEItemStack.class,
            false,
            CraftingMode.STANDARD);
    }

    private static IAEItemStack stack(net.minecraft.item.Item item, long amount) {
        IAEItemStack stack = new FakeAeItemStack(new ItemStack(item, 1, 0));
        stack.setStackSize(amount);
        return stack;
    }

    private static final class FakePattern implements ICraftingPatternDetails {

        private final IAEItemStack[] inputs;
        private final IAEItemStack[] outputs;

        private FakePattern(IAEItemStack input, IAEItemStack output) {
            this.inputs = new IAEItemStack[] { input };
            this.outputs = new IAEItemStack[] { output };
        }

        @Override
        public ItemStack getPattern() {
            return new ItemStack(PATTERN_ITEM, 1, 0);
        }

        @Override
        public boolean isValidItemForSlot(int slotIndex, ItemStack itemStack, World world) {
            return true;
        }

        @Override
        public boolean isCraftable() {
            return true;
        }

        @Override
        public IAEItemStack[] getInputs() {
            return this.inputs;
        }

        @Override
        public IAEItemStack[] getCondensedInputs() {
            return this.inputs;
        }

        @Override
        public IAEItemStack[] getCondensedOutputs() {
            return this.outputs;
        }

        @Override
        public IAEItemStack[] getOutputs() {
            return this.outputs;
        }

        @Override
        public boolean canSubstitute() {
            return false;
        }

        @Override
        public ItemStack getOutput(InventoryCrafting craftingInv, World world) {
            return new ItemStack(OUTPUT_ITEM, 1, 0);
        }

        @Override
        public int getPriority() {
            return 0;
        }

        @Override
        public void setPriority(int priority) {}
    }

    private static final class FakeAeItemStack implements IAEItemStack {

        private final ItemStack itemStack;
        private long stackSize;
        private long countRequestable;
        private long countRequestableCrafts;
        private float usedPercent;
        private boolean craftable;

        private FakeAeItemStack(ItemStack itemStack) {
            this.itemStack = itemStack.copy();
            this.stackSize = itemStack.stackSize;
        }

        @Override
        public ItemStack getItemStack() {
            ItemStack copy = this.itemStack.copy();
            copy.stackSize = (int) Math.min(Integer.MAX_VALUE, this.stackSize);
            return copy;
        }

        @Override
        public boolean hasTagCompound() {
            return this.itemStack.hasTagCompound();
        }

        @Override
        public void add(IAEItemStack option) {
            if (option == null) {
                return;
            }
            this.stackSize += option.getStackSize();
            this.countRequestable += option.getCountRequestable();
            this.countRequestableCrafts += option.getCountRequestableCrafts();
            this.usedPercent += option.getUsedPercent();
            this.craftable = this.craftable || option.isCraftable();
        }

        @Override
        public IAEItemStack copy() {
            FakeAeItemStack copy = new FakeAeItemStack(this.itemStack);
            copy.stackSize = this.stackSize;
            copy.countRequestable = this.countRequestable;
            copy.countRequestableCrafts = this.countRequestableCrafts;
            copy.usedPercent = this.usedPercent;
            copy.craftable = this.craftable;
            return copy;
        }

        @Override
        public Item getItem() {
            return this.itemStack.getItem();
        }

        @Override
        public int getItemDamage() {
            return this.itemStack.getItemDamage();
        }

        @Override
        public boolean sameOre(IAEItemStack is) {
            return false;
        }

        @Override
        public boolean isSameType(IAEItemStack otherStack) {
            return otherStack != null && this.getItem() == otherStack.getItem()
                    && this.getItemDamage() == otherStack.getItemDamage();
        }

        @Override
        public boolean isSameType(ItemStack stored) {
            return stored != null && this.getItem() == stored.getItem() && this.getItemDamage() == stored.getItemDamage();
        }

        @Override
        public long getStackSize() {
            return this.stackSize;
        }

        @Override
        public IAEItemStack setStackSize(long stackSize) {
            this.stackSize = stackSize;
            return this;
        }

        @Override
        public float getUsedPercent() {
            return this.usedPercent;
        }

        @Override
        public IAEItemStack setUsedPercent(float percent) {
            this.usedPercent = percent;
            return this;
        }

        @Override
        public long getCountRequestable() {
            return this.countRequestable;
        }

        @Override
        public IAEItemStack setCountRequestable(long countRequestable) {
            this.countRequestable = countRequestable;
            return this;
        }

        @Override
        public long getCountRequestableCrafts() {
            return this.countRequestableCrafts;
        }

        @Override
        public IAEItemStack setCountRequestableCrafts(long countRequestableCrafts) {
            this.countRequestableCrafts = countRequestableCrafts;
            return this;
        }

        @Override
        public boolean isCraftable() {
            return this.craftable;
        }

        @Override
        public IAEItemStack setCraftable(boolean isCraftable) {
            this.craftable = isCraftable;
            return this;
        }

        @Override
        public IAEItemStack reset() {
            this.stackSize = 0L;
            this.countRequestable = 0L;
            this.countRequestableCrafts = 0L;
            this.usedPercent = 0.0F;
            this.craftable = false;
            return this;
        }

        @Override
        public boolean isMeaningful() {
            return this.stackSize > 0L || this.countRequestable > 0L || this.craftable;
        }

        @Override
        public void incStackSize(long i) {
            this.stackSize += i;
        }

        @Override
        public void decStackSize(long i) {
            this.stackSize -= i;
        }

        @Override
        public void incCountRequestable(long i) {
            this.countRequestable += i;
        }

        @Override
        public void decCountRequestable(long i) {
            this.countRequestable -= i;
        }

        @Override
        public void writeToNBT(NBTTagCompound i) {}

        @Override
        public boolean fuzzyComparison(Object st, FuzzyMode mode) {
            if (st instanceof IAEItemStack) {
                return isSameType((IAEItemStack) st);
            }
            if (st instanceof ItemStack) {
                return isSameType((ItemStack) st);
            }
            return false;
        }

        @Override
        public void writeToPacket(ByteBuf data) {}

        @Override
        public IAEItemStack empty() {
            return copy().reset();
        }

        @Override
        public IAETagCompound getTagCompound() {
            return null;
        }

        @Override
        public boolean isItem() {
            return true;
        }

        @Override
        public boolean isFluid() {
            return false;
        }

        @Override
        public StorageChannel getChannel() {
            return StorageChannel.ITEMS;
        }

        @Override
        public String getLocalizedName() {
            return "test:" + System.identityHashCode(this.getItem());
        }

        @Override
        public boolean equals(Object obj) {
            if (obj instanceof IAEItemStack) {
                return isSameType((IAEItemStack) obj);
            }
            if (obj instanceof ItemStack) {
                return isSameType((ItemStack) obj);
            }
            return false;
        }

        @Override
        public int hashCode() {
            return System.identityHashCode(this.getItem()) * 31 + this.getItemDamage();
        }
    }
}
