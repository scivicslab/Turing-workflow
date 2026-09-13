package com.scivicslab.turingworkflow.workflow;

import com.scivicslab.pojoactor.action.ActionResult;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A workflow removing an actor it created ({@code loader.removeChild}).
 *
 * <p>Exercises the load-bearing path: the actor and everything below it leaves the registry, the
 * parent no longer lists it, and the root refuses to be removed. Whether anything still holds a
 * reference is deliberately not checked — the workflow that created the actor decides when it is
 * over.</p>
 */
@DisplayName("DynamicActorLoaderActor — removeChild")
class RemoveChildTest {

    private IIActorSystem system;
    private DynamicActorLoaderIIAR loader;

    @BeforeEach
    void setUp() {
        system = new IIActorSystem("remove-child-test");
        loader = new DynamicActorLoaderIIAR("loader", system);
        system.addIIActor(loader);
    }

    @AfterEach
    void tearDown() {
        system.terminate();
    }

    private void createChild(String parent, String name, String className) {
        ActionResult created = loader.callByActionName("createChild",
                "[\"" + parent + "\",\"" + name + "\",\"" + className + "\"]");
        assertTrue(created.isSuccess(), created.getResult());
    }

    @Test
    void anActorItCreated_leavesTheRegistryAndItsParent() {
        createChild("ROOT", "unit-1", "com.scivicslab.turingworkflow.workflow.CalcActor");
        assertNotNull(system.getIIActor("unit-1"));

        ActionResult removed = loader.callByActionName("removeChild", "[\"unit-1\"]");

        assertTrue(removed.isSuccess(), removed.getResult());
        assertTrue(removed.getResult().contains("unit-1"), removed.getResult());
        assertNull(system.getIIActor("unit-1"), "gone from the registry");
        assertFalse(system.getRoot().getNamesOfChildren().contains("unit-1"), "gone from its parent");
    }

    @Test
    void whatHungBelowItGoesTooRatherThanBeingOrphaned() {
        createChild("ROOT", "parent", "com.scivicslab.turingworkflow.workflow.CalcActor");
        createChild("parent", "child", "com.scivicslab.turingworkflow.workflow.CalcActor");
        createChild("child", "grandchild", "com.scivicslab.turingworkflow.workflow.CalcActor");

        ActionResult removed = loader.callByActionName("removeChild", "[\"parent\"]");

        assertTrue(removed.isSuccess(), removed.getResult());
        assertNull(system.getIIActor("parent"));
        assertNull(system.getIIActor("child"), "a child left registered would have no parent");
        assertNull(system.getIIActor("grandchild"));
        assertTrue(removed.getResult().contains("grandchild"), "and each one is named: " + removed.getResult());
    }

    @Test
    void theParentMayBeNamedFirst_asCreateChildTakesIt() {
        createChild("ROOT", "unit-1", "com.scivicslab.turingworkflow.workflow.CalcActor");

        ActionResult removed = loader.callByActionName("removeChild", "[\"ROOT\",\"unit-1\"]");

        assertTrue(removed.isSuccess(), removed.getResult());
        assertNull(system.getIIActor("unit-1"));
    }

    @Test
    void whatDoesNotExist_isSaidSoRatherThanPassedOver() {
        ActionResult removed = loader.callByActionName("removeChild", "[\"nobody\"]");

        assertFalse(removed.isSuccess());
        assertTrue(removed.getResult().contains("nobody"), removed.getResult());
    }

    @Test
    void aPlainActorGoesToo_notOnlyTheInterpreterFacingKind() {
        // In chat-ui one conversation's subtree crosses both registries: the anchor actor
        // (ActorRef<ConversationTab>, registered by createChild) is in the plain one, its
        // ChatSession (a ChatSessionIIAR, registered by addIIActor) in the interpreter-facing one.
        com.scivicslab.pojoactor.core.ActorRef<String> tab = system.actorOf("tab-1", "a plain actor");
        createChild("ROOT", "tab-1.chat", "com.scivicslab.turingworkflow.workflow.CalcActor");
        tab.getNamesOfChildren().add("tab-1.chat");
        system.getIIActor("tab-1.chat").setParentName("tab-1");

        ActionResult removed = loader.callByActionName("removeChild", "[\"tab-1\"]");

        assertTrue(removed.isSuccess(), removed.getResult());
        assertNull(system.getActor("tab-1"), "the plain actor is gone");
        assertNull(system.getIIActor("tab-1.chat"), "and the interpreter-facing child with it");
    }

    @Test
    void theRootIsRefused() {
        ActionResult removed = loader.callByActionName("removeChild", "[\"ROOT\"]");

        assertFalse(removed.isSuccess());
        assertNotNull(system.getRoot());
    }
}
