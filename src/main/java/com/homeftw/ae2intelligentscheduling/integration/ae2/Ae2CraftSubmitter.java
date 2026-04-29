package com.homeftw.ae2intelligentscheduling.integration.ae2;

import com.homeftw.ae2intelligentscheduling.smartcraft.model.SmartCraftTask;
import com.homeftw.ae2intelligentscheduling.smartcraft.runtime.SmartCraftRequesterBridge;

import appeng.api.networking.crafting.ICraftingCPU;
import appeng.api.networking.crafting.ICraftingGrid;
import appeng.api.networking.crafting.ICraftingJob;
import appeng.api.networking.crafting.ICraftingLink;
import appeng.api.networking.security.BaseActionSource;
import appeng.api.networking.security.MachineSource;

public final class Ae2CraftSubmitter {

    /**
     * Submit a planned AE2 crafting job to a chosen CPU.
     *
     * <p>v0.1.8.1: the {@code actionSource} parameter is intentionally NOT forwarded to AE2's
     * {@code submitJob} \u2014 we always pass a {@link MachineSource} wrapping our requester bridge
     * instead. Two independent reasons converge on this choice:
     *
     * <ol>
     * <li><b>Vanilla AE2 followCraft path.</b> {@code CraftingGridCache.submitJob} adds the
     * submitting player to the CPU's {@code playersFollowingCurrentCraft} list iff
     * {@code src instanceof PlayerSource && followCraft}. We already pass {@code followCraft=false}
     * implicitly (5-arg overload), so this is a defence-in-depth: even if some downstream pathway
     * flipped it on, MachineSource would still skip the auto-follow.</li>
     * <li><b>AE2Things wireless-terminal notification path.</b> AE2Things ships
     * {@code MixinCraftingCPUCluster.submitJob @Inject(at = RETURN)} which captures
     * {@code (player, output, networkKey)} ONLY when {@code src instanceof PlayerSource}, and on
     * {@code completeJob} fires {@code SPacketMEItemInvUpdate(NOTIFICATION)} to that player if
     * they hold an {@code INetworkEncodable} item (a wireless terminal). When SmartCraft splits
     * a big order into N sub-tasks each completion would trigger one notification \u2014 the
     * "popup keeps spamming" symptom users hit pre-v0.1.8.1. MachineSource silences the mixin
     * because its instanceof check fails. Order-level completion notifications come from our own
     * {@code OrderCompletionNotifier} hook fired exactly once when the whole order finishes.</li>
     * </ol>
     *
     * The {@code actionSource} parameter is kept in the signature for backward compatibility and
     * because the planning phase ({@code Ae2SmartCraftJobPlanner.beginCraftingJob}) still uses
     * the player-source for permission checks against secured patterns.
     */
    public ICraftingLink submit(ICraftingGrid craftingGrid, ICraftingJob craftingJob, SmartCraftTask task,
        ICraftingCPU targetCpu, SmartCraftRequesterBridge requesterBridge, BaseActionSource actionSource) {
        BaseActionSource submitSource = new MachineSource(requesterBridge);
        ICraftingLink craftingLink = craftingGrid
            .submitJob(craftingJob, requesterBridge, targetCpu, false, submitSource);
        if (craftingLink != null) {
            requesterBridge.track(task, craftingLink);
        }
        return craftingLink;
    }
}
