package com.homeftw.ae2intelligentscheduling.client.gui;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class NeiGuiContainerManagerGuardTest {

    @Test
    void creates_missing_manager_from_inherited_nei_field_and_loads_it() throws Exception {
        ChildContainer container = new ChildContainer();

        boolean changed = NeiGuiContainerManagerGuard
            .ensureManager(container, (target, managerType) -> new FakeManager());

        assertTrue(changed);
        assertNotNull(container.manager);
        assertTrue(container.manager.loaded);
    }

    @Test
    void keeps_existing_manager() throws Exception {
        ChildContainer container = new ChildContainer();
        FakeManager manager = new FakeManager();
        container.manager = manager;

        boolean changed = NeiGuiContainerManagerGuard.ensureManager(
            container,
            (target, managerType) -> {
                throw new AssertionError("factory should not be called when manager already exists");
            });

        assertFalse(changed);
        assertSame(manager, container.manager);
        assertFalse(manager.loaded);
    }

    @Test
    void ignores_container_without_nei_manager_field() throws Exception {
        boolean changed = NeiGuiContainerManagerGuard
            .ensureManager(new PlainContainer(), (target, managerType) -> new FakeManager());

        assertFalse(changed);
    }

    static class BaseContainer {

        public FakeManager manager;
    }

    static final class ChildContainer extends BaseContainer {
    }

    static final class PlainContainer {
    }

    static final class FakeManager {

        boolean loaded;

        public void load() {
            loaded = true;
        }
    }
}
