package com.scivicslab.turingworkflow.workflow;

import com.scivicslab.pojoactor.action.ActionResult;
import com.scivicslab.pojoactor.core.ActorRef;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * One registry for one population of actors
 * ({@code TwoRegistriesForOneActorPopulation_260914_oo01}).
 *
 * <p>{@link IIActorRef} extends {@code ActorRef}, so the actors a workflow can call are a subset of
 * the actors this system holds, told apart by their type. Keeping them in a map of their own made
 * the subset invisible to half the system's own methods: a name could be registered twice, one
 * entry per map; {@code getIIActor} answered "no such actor" for an actor the tree was showing;
 * and {@code terminate} closed only half of what it had started.</p>
 */
@DisplayName("IIActorSystem — one registry")
class IIActorRegistryTest {

    /** Minimal interpreter-interfaced actor: it answers one action and holds nothing. */
    static class Callable extends IIActorRef<Object> {
        Callable(String name, IIActorSystem system) {
            super(name, new Object(), system);
        }

        @Override
        public ActionResult callByActionName(String actionName, String args) {
            return new ActionResult(true, actionName);
        }
    }

    @Test
    void bothKindsOfActorAreInTheOneRegistry() {
        IIActorSystem system = new IIActorSystem("one-registry");
        try {
            ActorRef<String> plain = system.actorOf("plain-1", "a plain actor");
            Callable facing = new Callable("facing-1", system);
            system.addIIActor(facing);

            assertSame(plain, system.getActor("plain-1"));
            assertSame(facing, system.getActor("facing-1"));
            assertTrue(system.listActorNames().contains("plain-1"),
                    "the names of every actor, not of half of them: " + system.listActorNames());
            assertTrue(system.listActorNames().contains("facing-1"), system.listActorNames().toString());
        } finally {
            system.terminate();
        }
    }

    @Test
    void aNameRegisteredTwiceLeavesOneActorUnderIt() {
        IIActorSystem system = new IIActorSystem("one-registry-collision");
        try {
            system.actorOf("both", "a plain actor");
            Callable facing = new Callable("both", system);
            system.addIIActor(facing);

            assertSame(facing, system.getActor("both"), "the second registration is the one that stands");
            assertSame(facing, system.getIIActor("both"), "and both doors lead to it");
        } finally {
            system.terminate();
        }
    }

    /** A built-in name is still made on first use, which is what workflows are written against. */
    @Test
    void aFreeBuiltInNameIsStillMadeOnFirstUse() {
        IIActorSystem system = new IIActorSystem("one-registry-builtin");
        try {
            IIActorRef<?> made = system.getIIActor("calc:i");

            assertNotNull(made, "calc:i is made on first use");
            assertSame(made, system.getActor("calc:i"), "and is in the one registry, under that name");
        } finally {
            system.terminate();
        }
    }

    /**
     * A plain actor already under a built-in name is not replaced by one.
     *
     * <p>With two registries this could not happen — the built-in went into the other map. With one,
     * making it would overwrite an actor someone else registered, at a lookup.</p>
     */
    @Test
    void aBuiltInNameTakenByAPlainActorIsNotOverwritten() {
        IIActorSystem system = new IIActorSystem("one-registry-squatted");
        try {
            ActorRef<String> plain = system.actorOf("calc", "a plain actor of that name");

            assertNull(system.getIIActor("calc"), "no actor a workflow can call is registered as calc");
            assertSame(plain, system.getActor("calc"), "and what is registered as calc is untouched");
        } finally {
            system.terminate();
        }
    }

    @Test
    void whatWasRemovedIsGoneFromTheOneRegistry() {
        IIActorSystem system = new IIActorSystem("one-registry-remove");
        try {
            system.addIIActor(new Callable("facing-2", system));
            system.removeIIActor("facing-2");

            assertNull(system.getActor("facing-2"));
            assertFalse(system.hasIIActor("facing-2"));
            assertFalse(system.getRoot().getNamesOfChildren().contains("facing-2"));
        } finally {
            system.terminate();
        }
    }

    /** Whatever the system started, the system closes. */
    @Test
    void terminateClosesTheInterpreterFacingActorsToo() {
        IIActorSystem system = new IIActorSystem("one-registry-terminate");
        Callable facing = new Callable("facing-3", system);
        system.addIIActor(facing);
        ActorRef<String> plain = system.actorOf("plain-3", "a plain actor");

        system.terminate();

        assertFalse(plain.isAlive(), "the plain actor is closed");
        assertFalse(facing.isAlive(), "and so is the one a workflow could call");
    }
}
