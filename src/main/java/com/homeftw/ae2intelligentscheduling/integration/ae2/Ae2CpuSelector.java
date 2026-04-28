package com.homeftw.ae2intelligentscheduling.integration.ae2;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import appeng.api.networking.crafting.ICraftingCPU;
import appeng.api.networking.crafting.ICraftingGrid;

public final class Ae2CpuSelector {

    public Optional<ICraftingCPU> findIdleCpu(ICraftingGrid craftingGrid) {
        return findIdleCpu(craftingGrid.getCpus());
    }

    public Optional<ICraftingCPU> findIdleCpu(Iterable<ICraftingCPU> craftingCpus) {
        List<ICraftingCPU> idleCpus = idleCpus(craftingCpus);
        return idleCpus.isEmpty() ? Optional.<ICraftingCPU>empty() : Optional.of(idleCpus.get(0));
    }

    public List<ICraftingCPU> idleCpus(Iterable<ICraftingCPU> craftingCpus) {
        List<ICraftingCPU> idleCpus = new ArrayList<ICraftingCPU>();
        for (ICraftingCPU cpu : craftingCpus) {
            if (cpu != null && !cpu.isBusy()) {
                idleCpus.add(cpu);
            }
        }
        return idleCpus;
    }
}
