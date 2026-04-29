package com.homeftw.ae2intelligentscheduling.smartcraft.model;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

public final class SmartCraftOrder {

    private final SmartCraftRequestKey targetRequestKey;
    private final long targetAmount;
    private final SmartCraftOrderScale orderScale;
    private final SmartCraftStatus status;
    private final List<SmartCraftLayer> layers;
    private final int currentLayerIndex;
    /**
     * v0.1.9 (G12) Captured at order-creation time so the runtime can re-bind a session to this
     * order after a server restart. Empty string means "unknown" (legacy orders / unit tests);
     * the auto-rebind logic skips orders without an owner.
     */
    private final String ownerName;

    public SmartCraftOrder(SmartCraftRequestKey targetRequestKey, long targetAmount, SmartCraftOrderScale orderScale,
        SmartCraftStatus status, List<SmartCraftLayer> layers, int currentLayerIndex) {
        this(targetRequestKey, targetAmount, orderScale, status, layers, currentLayerIndex, "");
    }

    public SmartCraftOrder(SmartCraftRequestKey targetRequestKey, long targetAmount, SmartCraftOrderScale orderScale,
        SmartCraftStatus status, List<SmartCraftLayer> layers, int currentLayerIndex, String ownerName) {
        this.targetRequestKey = targetRequestKey;
        this.targetAmount = targetAmount;
        this.orderScale = orderScale;
        this.status = status;
        this.layers = layers;
        this.currentLayerIndex = currentLayerIndex;
        this.ownerName = ownerName == null ? "" : ownerName;
    }

    public static SmartCraftOrder queued(SmartCraftRequestKey targetRequestKey, long targetAmount,
        SmartCraftOrderScale orderScale, List<SmartCraftLayer> layers) {
        return queued(targetRequestKey, targetAmount, orderScale, layers, "");
    }

    /**
     * v0.1.9 (G12) Owner-aware variant. Production code (SmartCraftOrderBuilder) calls this with
     * the issuing player's username so the persistence layer can rebuild the session after
     * server restart.
     */
    public static SmartCraftOrder queued(SmartCraftRequestKey targetRequestKey, long targetAmount,
        SmartCraftOrderScale orderScale, List<SmartCraftLayer> layers, String ownerName) {
        return new SmartCraftOrder(
            targetRequestKey,
            targetAmount,
            orderScale,
            SmartCraftStatus.QUEUED,
            new ArrayList<>(layers),
            0,
            ownerName);
    }

    public String ownerName() {
        return this.ownerName;
    }

    public SmartCraftOrder withOwnerName(String nextOwnerName) {
        return new SmartCraftOrder(
            this.targetRequestKey,
            this.targetAmount,
            this.orderScale,
            this.status,
            new ArrayList<SmartCraftLayer>(this.layers),
            this.currentLayerIndex,
            nextOwnerName);
    }

    public SmartCraftRequestKey targetRequestKey() {
        return this.targetRequestKey;
    }

    public long targetAmount() {
        return this.targetAmount;
    }

    public SmartCraftOrderScale orderScale() {
        return this.orderScale;
    }

    public SmartCraftStatus status() {
        return this.status;
    }

    public List<SmartCraftLayer> layers() {
        return this.layers;
    }

    public int currentLayerIndex() {
        return this.currentLayerIndex;
    }

    public SmartCraftLayer currentLayer() {
        if (this.currentLayerIndex < 0 || this.currentLayerIndex >= this.layers.size()) {
            return null;
        }
        return this.layers.get(this.currentLayerIndex);
    }

    public boolean isFinished() {
        return this.currentLayerIndex >= this.layers.size() || this.status == SmartCraftStatus.COMPLETED
            || this.status == SmartCraftStatus.CANCELLED
            || this.status == SmartCraftStatus.FAILED;
    }

    public SmartCraftOrder withStatus(SmartCraftStatus nextStatus) {
        return new SmartCraftOrder(
            this.targetRequestKey,
            this.targetAmount,
            this.orderScale,
            nextStatus,
            new ArrayList<SmartCraftLayer>(this.layers),
            this.currentLayerIndex,
            this.ownerName);
    }

    public SmartCraftOrder withLayers(List<SmartCraftLayer> nextLayers) {
        return new SmartCraftOrder(
            this.targetRequestKey,
            this.targetAmount,
            this.orderScale,
            this.status,
            new ArrayList<SmartCraftLayer>(nextLayers),
            this.currentLayerIndex,
            this.ownerName);
    }

    public SmartCraftOrder withLayer(int layerIndex, SmartCraftLayer nextLayer) {
        List<SmartCraftLayer> nextLayers = new ArrayList<SmartCraftLayer>(this.layers);
        nextLayers.set(layerIndex, nextLayer);
        return new SmartCraftOrder(
            this.targetRequestKey,
            this.targetAmount,
            this.orderScale,
            this.status,
            nextLayers,
            this.currentLayerIndex,
            this.ownerName);
    }

    public SmartCraftOrder withCurrentLayerIndex(int nextLayerIndex) {
        return new SmartCraftOrder(
            this.targetRequestKey,
            this.targetAmount,
            this.orderScale,
            this.status,
            new ArrayList<SmartCraftLayer>(this.layers),
            nextLayerIndex,
            this.ownerName);
    }

    public SmartCraftOrder advanceLayer() {
        return withCurrentLayerIndex(this.currentLayerIndex + 1);
    }

    /**
     * v0.1.9 (G12) Persist this order to NBT. Layout:
     * <ul>
     * <li>{@code targetRequestKey}: nested compound, written via {@link SmartCraftRequestKey#writeToNBT}.</li>
     * <li>{@code targetAmount}, {@code currentLayerIndex}: scalars.</li>
     * <li>{@code orderScale}, {@code status}: enum {@code name()} strings.</li>
     * <li>{@code layers}: list of layer compounds.</li>
     * </ul>
     */
    public void writeToNBT(NBTTagCompound tag) {
        if (this.targetRequestKey != null) {
            NBTTagCompound keyTag = new NBTTagCompound();
            this.targetRequestKey.writeToNBT(keyTag);
            tag.setTag("targetRequestKey", keyTag);
        }
        tag.setLong("targetAmount", this.targetAmount);
        tag.setString(
            "orderScale",
            this.orderScale == null ? SmartCraftOrderScale.MEDIUM.name() : this.orderScale.name());
        tag.setString("status", this.status == null ? SmartCraftStatus.QUEUED.name() : this.status.name());
        tag.setInteger("currentLayerIndex", this.currentLayerIndex);
        if (this.ownerName != null && !this.ownerName.isEmpty()) {
            tag.setString("ownerName", this.ownerName);
        }
        NBTTagList layerList = new NBTTagList();
        for (SmartCraftLayer layer : this.layers) {
            NBTTagCompound l = new NBTTagCompound();
            layer.writeToNBT(l);
            layerList.appendTag(l);
        }
        tag.setTag("layers", layerList);
    }

    /**
     * Inverse of {@link #writeToNBT}. Returns {@code null} if the order can't be reconstructed
     * (typically because {@code targetRequestKey} failed to deserialize). Layers that lose all
     * tasks during read are kept as empty placeholders so the layer count / index alignment
     * survives the round-trip; the caller's reset pass on top-level load decides whether to
     * resume or drop empty layers.
     */
    public static SmartCraftOrder readFromNBT(NBTTagCompound tag) {
        if (tag == null) return null;
        SmartCraftRequestKey targetKey = tag.hasKey("targetRequestKey")
            ? SmartCraftRequestKeyRegistry.readFromNBT(tag.getCompoundTag("targetRequestKey"))
            : null;
        if (targetKey == null) return null;
        long targetAmount = tag.getLong("targetAmount");
        SmartCraftOrderScale scale;
        try {
            scale = SmartCraftOrderScale.valueOf(tag.getString("orderScale"));
        } catch (IllegalArgumentException e) {
            scale = SmartCraftOrderScale.MEDIUM;
        }
        SmartCraftStatus status;
        try {
            status = SmartCraftStatus.valueOf(tag.getString("status"));
        } catch (IllegalArgumentException e) {
            status = SmartCraftStatus.QUEUED;
        }
        int currentLayerIndex = tag.getInteger("currentLayerIndex");
        NBTTagList layerList = tag.getTagList("layers", 10); // 10 = TAG_Compound
        List<SmartCraftLayer> layers = new ArrayList<SmartCraftLayer>(layerList.tagCount());
        for (int i = 0; i < layerList.tagCount(); i++) {
            SmartCraftLayer l = SmartCraftLayer.readFromNBT(layerList.getCompoundTagAt(i));
            if (l != null) layers.add(l);
        }
        String ownerName = tag.hasKey("ownerName") ? tag.getString("ownerName") : "";
        return new SmartCraftOrder(targetKey, targetAmount, scale, status, layers, currentLayerIndex, ownerName);
    }
}
