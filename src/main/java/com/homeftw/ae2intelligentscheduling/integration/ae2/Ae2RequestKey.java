package com.homeftw.ae2intelligentscheduling.integration.ae2;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraftforge.fluids.Fluid;
import net.minecraftforge.fluids.FluidStack;

import com.homeftw.ae2intelligentscheduling.smartcraft.model.SmartCraftRequestKey;
import com.homeftw.ae2intelligentscheduling.smartcraft.model.SmartCraftRequestKeyRegistry;

import appeng.api.AEApi;
import appeng.api.storage.data.IAEFluidStack;
import appeng.api.storage.data.IAEItemStack;
import appeng.api.storage.data.IAEStack;

public final class Ae2RequestKey implements SmartCraftRequestKey {

    /**
     * v0.1.9 (G12) NBT type strings registered with {@link SmartCraftRequestKeyRegistry}. Stable
     * across versions because they're persisted into world saves \u2014 changing them would orphan
     * existing orders on disk.
     */
    public static final String NBT_TYPE = "ae2.requestKey";

    /**
     * Hook for mod init to wire this implementation into the registry exactly once. Calling more
     * than once is a no-op for the registry (idempotent put) so callers don't have to track state.
     */
    public static void registerNbtReader() {
        SmartCraftRequestKeyRegistry.register(NBT_TYPE, Ae2RequestKey::readFromNBT);
    }

    private final String id;
    private final ItemStack itemTemplate;

    private Ae2RequestKey(String id, ItemStack itemTemplate) {
        this.id = id;
        this.itemTemplate = itemTemplate == null ? null : itemTemplate.copy();
    }

    public static Ae2RequestKey from(IAEStack<?> stack) {
        if (stack instanceof IAEItemStack) {
            return fromItem((IAEItemStack) stack);
        }
        if (stack instanceof IAEFluidStack) {
            return fromFluid((IAEFluidStack) stack);
        }
        return new Ae2RequestKey("unknown:null", null);
    }

    private static Ae2RequestKey fromItem(IAEItemStack stack) {
        ItemStack itemStack = stack.getItemStack();
        if (itemStack == null || itemStack.getItem() == null) {
            return new Ae2RequestKey("item:null", null);
        }
        Item item = itemStack.getItem();
        String registryName = String.valueOf(Item.itemRegistry.getNameForObject(item));
        return new Ae2RequestKey("item:" + registryName + ":" + itemStack.getItemDamage(), itemStack);
    }

    private static Ae2RequestKey fromFluid(IAEFluidStack stack) {
        FluidStack fluidStack = stack.getFluidStack();
        Fluid fluid = fluidStack == null ? null : fluidStack.getFluid();
        String fluidName = fluid == null ? "null" : fluid.getName();
        return new Ae2RequestKey("fluid:" + fluidName, null);
    }

    public boolean canCreateCraftRequest() {
        return this.itemTemplate != null;
    }

    public IAEItemStack createCraftRequest(long amount) {
        if (this.itemTemplate == null) {
            return null;
        }

        ItemStack template = this.itemTemplate.copy();
        if (template.stackSize <= 0) {
            template.stackSize = 1;
        }

        IAEItemStack request = AEApi.instance()
            .storage()
            .createItemStack(template);
        if (request == null) {
            return null;
        }

        request.reset();
        request.setStackSize(amount);
        return request;
    }

    @Override
    public String id() {
        return this.id;
    }

    @Override
    public ItemStack itemStack() {
        return this.itemTemplate;
    }

    /**
     * v0.1.9 (G12) Persist this key. Layout:
     * <ul>
     * <li>{@code type}: {@link #NBT_TYPE} \u2014 routes the registry to {@link #readFromNBT}.</li>
     * <li>{@code id}: full key id (e.g. {@code "item:minecraft:iron_ingot:0"} or
     * {@code "fluid:water"}). Lets us recover the id without round-tripping through the
     * itemTemplate when the latter is null (fluids).</li>
     * <li>{@code itemStack}: optional sub-tag, only present when {@link #itemTemplate} is non-null.
     * Reading uses {@code ItemStack.loadItemStackFromNBT} to handle missing items / damaged NBT
     * gracefully.</li>
     * </ul>
     * Fluid-typed keys (where {@code itemTemplate == null}) cannot {@link #createCraftRequest} after
     * deserialization either; that mirrors the original {@link #fromFluid} behaviour.
     */
    @Override
    public void writeToNBT(NBTTagCompound tag) {
        tag.setString("type", NBT_TYPE);
        tag.setString("id", this.id == null ? "" : this.id);
        if (this.itemTemplate != null) {
            NBTTagCompound stackTag = new NBTTagCompound();
            this.itemTemplate.writeToNBT(stackTag);
            tag.setTag("itemStack", stackTag);
        }
    }

    /**
     * Inverse of {@link #writeToNBT}. Returns {@code null} when the id is missing AND no item
     * stack survived deserialization \u2014 the registry treats {@code null} as "drop the entry"
     * which is the right behaviour for keys whose backing item disappeared (mod removed).
     */
    public static Ae2RequestKey readFromNBT(NBTTagCompound tag) {
        if (tag == null) return null;
        String id = tag.hasKey("id") ? tag.getString("id") : "";
        ItemStack stack = null;
        if (tag.hasKey("itemStack")) {
            stack = ItemStack.loadItemStackFromNBT(tag.getCompoundTag("itemStack"));
        }
        if ((id == null || id.isEmpty()) && stack == null) {
            return null;
        }
        return new Ae2RequestKey(id == null ? "" : id, stack);
    }
}
