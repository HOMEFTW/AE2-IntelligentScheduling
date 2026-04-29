package com.homeftw.ae2intelligentscheduling.smartcraft.model;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

public final class SmartCraftLayer {

    private final int depth;
    private final List<SmartCraftTask> tasks;

    public SmartCraftLayer(int depth) {
        this(depth, new ArrayList<SmartCraftTask>());
    }

    public SmartCraftLayer(int depth, List<SmartCraftTask> tasks) {
        this.depth = depth;
        this.tasks = tasks;
    }

    public int depth() {
        return this.depth;
    }

    public List<SmartCraftTask> tasks() {
        return this.tasks;
    }

    public SmartCraftLayer withTasks(List<SmartCraftTask> nextTasks) {
        return new SmartCraftLayer(this.depth, new ArrayList<SmartCraftTask>(nextTasks));
    }

    public boolean isComplete() {
        for (SmartCraftTask task : this.tasks) {
            if (!task.isTerminal()) {
                return false;
            }
        }
        return true;
    }

    /**
     * v0.1.9 (G12) Persist this layer to NBT. Tasks whose {@link SmartCraftTask#writeToNBT}
     * succeed are stored individually; the {@code depth} field doubles as a sanity check on read.
     */
    public void writeToNBT(NBTTagCompound tag) {
        tag.setInteger("depth", this.depth);
        NBTTagList taskList = new NBTTagList();
        for (SmartCraftTask task : this.tasks) {
            NBTTagCompound t = new NBTTagCompound();
            task.writeToNBT(t);
            taskList.appendTag(t);
        }
        tag.setTag("tasks", taskList);
    }

    /**
     * Inverse of {@link #writeToNBT}. Tasks that fail to deserialize (e.g. unregistered request
     * key type) are silently dropped so a single bad task doesn't poison the whole layer.
     */
    public static SmartCraftLayer readFromNBT(NBTTagCompound tag) {
        if (tag == null) return null;
        int depth = tag.getInteger("depth");
        NBTTagList taskList = tag.getTagList("tasks", 10); // 10 = TAG_Compound
        List<SmartCraftTask> tasks = new ArrayList<SmartCraftTask>(taskList.tagCount());
        for (int i = 0; i < taskList.tagCount(); i++) {
            SmartCraftTask t = SmartCraftTask.readFromNBT(taskList.getCompoundTagAt(i));
            if (t != null) tasks.add(t);
        }
        return new SmartCraftLayer(depth, tasks);
    }
}
