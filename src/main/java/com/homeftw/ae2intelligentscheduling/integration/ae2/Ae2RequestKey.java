package com.homeftw.ae2intelligentscheduling.integration.ae2;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraftforge.fluids.Fluid;
import net.minecraftforge.fluids.FluidStack;

import com.homeftw.ae2intelligentscheduling.smartcraft.model.SmartCraftRequestKey;

import appeng.api.AEApi;
import appeng.api.storage.data.IAEFluidStack;
import appeng.api.storage.data.IAEItemStack;
import appeng.api.storage.data.IAEStack;

public final class Ae2RequestKey implements SmartCraftRequestKey {

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
}
