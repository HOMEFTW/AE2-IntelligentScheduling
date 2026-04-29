package com.homeftw.ae2intelligentscheduling.smartcraft.runtime;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.homeftw.ae2intelligentscheduling.smartcraft.model.SmartCraftLayer;
import com.homeftw.ae2intelligentscheduling.smartcraft.model.SmartCraftOrder;
import com.homeftw.ae2intelligentscheduling.smartcraft.model.SmartCraftStatus;
import com.homeftw.ae2intelligentscheduling.smartcraft.model.SmartCraftTask;

public final class SmartCraftOrderManager {

    private static final Logger LOGGER = LogManager.getLogger("AE2IS-OrderManager");

    /**
     * v0.1.9 (G12) Mutation listener. Called whenever the manager's {@code orders} map is
     * structurally modified (track / update / remove / cancel / cancelGracefully /
     * retryFailedTasks / loadFromNBT). Production wires this to a {@code WorldSavedData.markDirty}
     * call so the world save picks up the change; tests pass a no-op or a recording stub.
     */
    @FunctionalInterface
    public interface DirtyListener {

        void onDirty();
    }

    private final Map<UUID, SmartCraftOrder> orders = new LinkedHashMap<UUID, SmartCraftOrder>();
    /**
     * v0.1.9 (G12) Defaults to no-op so unit tests don't need to wire a listener. Production
     * setup ({@code AE2IntelligentScheduling#serverStarted}) injects a real listener that
     * delegates to {@code SmartCraftOrderWorldData.markDirty()}.
     */
    private DirtyListener dirtyListener = () -> {};

    /**
     * v0.1.9 (G12) Replace the dirty listener. Idempotent: passing {@code null} reverts to no-op.
     */
    public void setDirtyListener(DirtyListener listener) {
        this.dirtyListener = listener == null ? () -> {} : listener;
    }

    private void markDirty() {
        try {
            this.dirtyListener.onDirty();
        } catch (RuntimeException e) {
            LOGGER.warn("DirtyListener threw while marking SmartCraftOrderManager dirty", e);
        }
    }

    public UUID track(SmartCraftOrder order) {
        UUID orderId = UUID.randomUUID();
        this.orders.put(orderId, order);
        markDirty();
        return orderId;
    }

    /**
     * v0.1.9 (G12) Insert a tracked order under a caller-supplied UUID. Used during NBT load to
     * preserve order IDs across server restarts so existing client GUI sessions and packet
     * routing continue to address the same orders.
     */
    public void trackWithId(UUID orderId, SmartCraftOrder order) {
        if (orderId == null || order == null) return;
        this.orders.put(orderId, order);
        markDirty();
    }

    public Optional<SmartCraftOrder> get(UUID orderId) {
        return Optional.ofNullable(this.orders.get(orderId));
    }

    public void update(UUID orderId, SmartCraftOrder order) {
        this.orders.put(orderId, order);
        markDirty();
    }

    public void remove(UUID orderId) {
        if (this.orders.remove(orderId) != null) {
            markDirty();
        }
    }

    public Collection<SmartCraftOrder> all() {
        return this.orders.values();
    }

    /**
     * Insertion-order-preserving snapshot for the multi-order tab UI (v0.1.7). Returns a fresh
     * {@link LinkedHashMap} so callers can iterate without holding the manager's reference and
     * without worrying about concurrent modification when the runtime tick fires mid-iteration.
     * The map keys remain {@link UUID order IDs} and the values are the order snapshots at the
     * time of the call.
     */
    public LinkedHashMap<UUID, SmartCraftOrder> snapshot() {
        return new LinkedHashMap<UUID, SmartCraftOrder>(this.orders);
    }

    public Optional<SmartCraftOrder> cancel(UUID orderId) {
        Optional<SmartCraftOrder> existing = get(orderId);
        if (!existing.isPresent()) {
            return Optional.empty();
        }

        SmartCraftOrder cancelled = existing.get()
            .withLayers(
                updateAllTasks(
                    existing.get()
                        .layers(),
                    true,
                    false))
            .withStatus(SmartCraftStatus.CANCELLED);
        this.orders.put(orderId, cancelled);
        markDirty();
        return Optional.of(cancelled);
    }

    /**
     * Soft-cancel: tasks already RUNNING (or VERIFYING_OUTPUT, transient) are spared so the AE2
     * crafting work in flight is not thrown away \u2014 cancelling RUNNING tasks would orphan all the
     * intermediate materials AE2 has already routed to the CPU's storage clusters and the player
     * would lose the time/items already invested. Tasks that have NOT started executing yet
     * (PENDING / QUEUED / WAITING_CPU / SUBMITTING) are flipped to CANCELLED so they don't kick
     * off new work. The order itself is left in its previous status when there are spared tasks
     * so the runtime keeps ticking; once the last spared task finishes, applyLayerStatus will
     * transition the order to COMPLETED or PAUSED naturally.
     *
     * <p>
     * Returns {@link Optional#empty()} if there is nothing to cancel (no in-flight tasks AND
     * no pending tasks). Callers can use this to skip a redundant UI sync packet.
     */
    public Optional<SmartCraftOrder> cancelGracefully(UUID orderId) {
        Optional<SmartCraftOrder> existing = get(orderId);
        if (!existing.isPresent()) {
            return Optional.empty();
        }
        SmartCraftOrder current = existing.get();
        boolean anySpared = false;
        boolean anyCancelled = false;
        List<SmartCraftLayer> nextLayers = new ArrayList<SmartCraftLayer>(
            current.layers()
                .size());
        for (SmartCraftLayer layer : current.layers()) {
            List<SmartCraftTask> nextTasks = new ArrayList<SmartCraftTask>(
                layer.tasks()
                    .size());
            for (SmartCraftTask task : layer.tasks()) {
                SmartCraftStatus s = task.status();
                if (s == SmartCraftStatus.RUNNING || s == SmartCraftStatus.VERIFYING_OUTPUT) {
                    // Let in-flight crafts run to completion. The committed AE2 link is the
                    // authority on when these become DONE; we don't disturb session state.
                    anySpared = true;
                    nextTasks.add(task);
                } else if (!task.isTerminal()) {
                    // Cancellable: PENDING / QUEUED / WAITING_CPU / SUBMITTING. The runtime
                    // coordinator will sweep planning futures via clearExecution on its next tick
                    // when it sees the CANCELLED status here.
                    anyCancelled = true;
                    nextTasks.add(task.withStatus(SmartCraftStatus.CANCELLED, task.blockingReason()));
                } else {
                    nextTasks.add(task);
                }
            }
            nextLayers.add(layer.withTasks(nextTasks));
        }
        if (!anySpared && !anyCancelled) {
            return Optional.empty();
        }
        // If there are no spared (running) tasks, this degenerates into a hard cancel and the
        // order itself becomes CANCELLED. Otherwise leave the order status alone \u2014 the runtime
        // re-derives it next tick from the surviving RUNNING set.
        SmartCraftOrder updated = current.withLayers(nextLayers);
        if (!anySpared) {
            updated = updated.withStatus(SmartCraftStatus.CANCELLED);
        }
        this.orders.put(orderId, updated);
        markDirty();
        return Optional.of(updated);
    }

    /**
     * Bring failed and cancelled tasks back into rotation. Reset to {@code PENDING} and clear the
     * blocking reason so {@code dispatchReadyTasks} can plan them again. The order itself is bumped to
     * {@code QUEUED} so the next tick picks it up — this is the single mechanism that makes a paused or
     * cancelled order recoverable.
     *
     * <p>
     * Returns the updated order, or {@link Optional#empty()} if the order is unknown or has nothing
     * retriable (no FAILED / CANCELLED leaf tasks). The empty case lets callers know they should not
     * spam UI sync packets.
     */
    public Optional<SmartCraftOrder> retryFailedTasks(UUID orderId) {
        Optional<SmartCraftOrder> existing = get(orderId);
        if (!existing.isPresent()) {
            return Optional.empty();
        }

        SmartCraftOrder current = existing.get();
        if (!hasRetriableTasks(current)) {
            return Optional.empty();
        }

        SmartCraftOrder retried = current.withLayers(retryTerminalFailures(current.layers()))
            .withStatus(SmartCraftStatus.QUEUED);
        this.orders.put(orderId, retried);
        markDirty();
        return Optional.of(retried);
    }

    private static boolean hasRetriableTasks(SmartCraftOrder order) {
        for (SmartCraftLayer layer : order.layers()) {
            for (SmartCraftTask task : layer.tasks()) {
                if (task.status() == SmartCraftStatus.FAILED || task.status() == SmartCraftStatus.CANCELLED) {
                    return true;
                }
            }
        }
        return false;
    }

    private List<SmartCraftLayer> updateAllTasks(List<SmartCraftLayer> layers, boolean cancelActive,
        boolean retryFailed) {
        List<SmartCraftLayer> nextLayers = new ArrayList<SmartCraftLayer>(layers.size());

        for (SmartCraftLayer layer : layers) {
            List<SmartCraftTask> nextTasks = new ArrayList<SmartCraftTask>(
                layer.tasks()
                    .size());
            for (SmartCraftTask task : layer.tasks()) {
                SmartCraftTask nextTask = task;
                if (cancelActive && !task.isTerminal()) {
                    nextTask = task.withStatus(SmartCraftStatus.CANCELLED, task.blockingReason());
                } else if (retryFailed && task.status() == SmartCraftStatus.FAILED) {
                    nextTask = task.withStatus(SmartCraftStatus.PENDING, null);
                }
                nextTasks.add(nextTask);
            }
            nextLayers.add(layer.withTasks(nextTasks));
        }

        return nextLayers;
    }

    /**
     * Variant of {@link #updateAllTasks} that revives both FAILED and CANCELLED tasks back to PENDING.
     * Used by retry to make cancelled orders recoverable, not just paused ones.
     */
    private List<SmartCraftLayer> retryTerminalFailures(List<SmartCraftLayer> layers) {
        List<SmartCraftLayer> nextLayers = new ArrayList<SmartCraftLayer>(layers.size());
        for (SmartCraftLayer layer : layers) {
            List<SmartCraftTask> nextTasks = new ArrayList<SmartCraftTask>(
                layer.tasks()
                    .size());
            for (SmartCraftTask task : layer.tasks()) {
                if (task.status() == SmartCraftStatus.FAILED || task.status() == SmartCraftStatus.CANCELLED) {
                    nextTasks.add(task.withStatus(SmartCraftStatus.PENDING, null));
                } else {
                    nextTasks.add(task);
                }
            }
            nextLayers.add(layer.withTasks(nextTasks));
        }
        return nextLayers;
    }

    // -----------------------------------------------------------------------
    // v0.1.9 (G12) NBT persistence
    // -----------------------------------------------------------------------

    /**
     * Serialize every tracked order into a fresh NBT compound. Iteration order is preserved
     * (LinkedHashMap) so the post-load multi-order tab UI matches the pre-save layout.
     */
    public void writeToNBT(NBTTagCompound tag) {
        NBTTagList orderList = new NBTTagList();
        for (Map.Entry<UUID, SmartCraftOrder> entry : this.orders.entrySet()) {
            NBTTagCompound entryTag = new NBTTagCompound();
            entryTag.setString("orderId", entry.getKey().toString());
            NBTTagCompound orderTag = new NBTTagCompound();
            entry.getValue().writeToNBT(orderTag);
            entryTag.setTag("order", orderTag);
            orderList.appendTag(entryTag);
        }
        tag.setTag("orders", orderList);
    }

    /**
     * Replace the manager's order map from a previously-serialized NBT compound. Existing entries
     * are cleared first so this is a true "load from disk" operation, not an append. Each
     * recovered order is passed through {@link #resetForRestart} which folds in-flight task
     * statuses (RUNNING / SUBMITTING / WAITING_CPU / VERIFYING_OUTPUT) back to PENDING because
     * the AE2 craftingLink and plannedJob references they held are not serializable and won't
     * survive the restart.
     *
     * <p>Orders whose target requestKey can't be deserialized (mod that defined the requestKey
     * type was uninstalled) are silently dropped — the alternative would be to crash the server
     * load, which is unfriendly when the player just wants to remove a mod.
     */
    public void loadFromNBT(NBTTagCompound tag) {
        this.orders.clear();
        if (tag == null || !tag.hasKey("orders")) {
            markDirty();
            return;
        }
        NBTTagList orderList = tag.getTagList("orders", 10); // 10 = TAG_Compound
        int loaded = 0, skipped = 0;
        for (int i = 0; i < orderList.tagCount(); i++) {
            NBTTagCompound entryTag = orderList.getCompoundTagAt(i);
            UUID orderId;
            try {
                orderId = UUID.fromString(entryTag.getString("orderId"));
            } catch (IllegalArgumentException e) {
                skipped++;
                continue;
            }
            SmartCraftOrder order = SmartCraftOrder.readFromNBT(entryTag.getCompoundTag("order"));
            if (order == null) {
                skipped++;
                continue;
            }
            this.orders.put(orderId, resetForRestart(order));
            loaded++;
        }
        if (loaded > 0 || skipped > 0) {
            LOGGER.info("SmartCraftOrderManager loaded {} orders from disk ({} skipped)", loaded, skipped);
        }
        markDirty();
    }

    /**
     * v0.1.9 (G12) Apply the post-restart task-status fold-back. AE2 internal state (planning
     * futures, planned jobs, crafting links) cannot be persisted, so any task that was actively
     * using those references gets reset to PENDING with a recovery banner. Terminal task states
     * (DONE / FAILED / CANCELLED) are preserved — they don't depend on AE2 runtime objects.
     *
     * <p>The order-level status is also re-synthesized: if any task is now non-terminal the order
     * becomes QUEUED so the runtime picks it up; if every task is terminal we keep whatever
     * existed (typically COMPLETED / PAUSED / CANCELLED).
     */
    public static SmartCraftOrder resetForRestart(SmartCraftOrder order) {
        if (order == null) return null;
        boolean anyResumable = false;
        List<SmartCraftLayer> nextLayers = new ArrayList<SmartCraftLayer>(order.layers().size());
        for (SmartCraftLayer layer : order.layers()) {
            List<SmartCraftTask> nextTasks = new ArrayList<SmartCraftTask>(layer.tasks().size());
            for (SmartCraftTask task : layer.tasks()) {
                SmartCraftStatus s = task.status();
                if (s == null) {
                    nextTasks.add(task.withStatus(SmartCraftStatus.PENDING, null));
                    anyResumable = true;
                } else if (s.isTerminalTaskState()) {
                    // DONE / FAILED / CANCELLED / COMPLETED — no AE2 runtime state needed.
                    nextTasks.add(task);
                } else {
                    // PENDING / QUEUED / WAITING_CPU / SUBMITTING / RUNNING / VERIFYING_OUTPUT /
                    // PAUSED / ANALYZING all fold to PENDING; their previous blockingReason
                    // (e.g. retry banner) is wiped because the retry counters live in the
                    // RuntimeCoordinator and don't survive restart either.
                    nextTasks.add(task.withStatus(SmartCraftStatus.PENDING, "Resumed after server restart"));
                    anyResumable = true;
                }
            }
            nextLayers.add(layer.withTasks(nextTasks));
        }
        SmartCraftOrder reset = order.withLayers(nextLayers);
        if (anyResumable && reset.status() != SmartCraftStatus.CANCELLED) {
            reset = reset.withStatus(SmartCraftStatus.QUEUED);
        }
        // Reset currentLayerIndex to the first non-complete layer so applyLayerStatus has a sane
        // starting point on the first post-restart tick.
        int firstIncomplete = nextLayers.size();
        for (int i = 0; i < nextLayers.size(); i++) {
            if (!nextLayers.get(i).isComplete()) {
                firstIncomplete = i;
                break;
            }
        }
        return reset.withCurrentLayerIndex(firstIncomplete);
    }
}
