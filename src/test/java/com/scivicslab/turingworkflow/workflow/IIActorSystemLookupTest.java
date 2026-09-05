package com.scivicslab.turingworkflow.workflow;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.scivicslab.pojoactor.core.ActionResult;
import com.scivicslab.pojoactor.core.ActorRef;

/**
 * Whether an actor added as an {@link IIActorRef} can be found by the plain
 * {@code ActorSystem.getActor} name lookup.
 *
 * <p>It has to be. {@code HttpActorServer} — the way another process reaches this one — resolves
 * names through {@code getActor} and nothing else, so an actor that only ever lands in the
 * interpreter-interfaced registry is unreachable from outside the JVM. Those are exactly the
 * actors a workflow can call, i.e. the ones a parent interpreter in another process needs.
 */
@DisplayName("IIActorSystem — finding an interpreter-interfaced actor by name")
class IIActorSystemLookupTest {

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
    void addIIActor_isFoundByGetActor() {
        IIActorSystem system = new IIActorSystem("lookup");
        try {
            Callable added = new Callable("project1/chat-01.chat", system);
            system.addIIActor(added);

            ActorRef<?> found = system.getActor("project1/chat-01.chat");

            assertNotNull(found, "getActor must find an actor added through addIIActor");
            assertSame(added, found);
        } finally {
            system.terminate();
        }
    }

    /** A plain actor of the same name keeps winning: the two registries must not swap places. */
    @Test
    void plainActorOfTheSameNameIsPreferred() {
        IIActorSystem system = new IIActorSystem("lookup-collision");
        try {
            ActorRef<String> plain = system.actorOf("both", "plain");
            system.addIIActor(new Callable("both", system));

            assertSame(plain, system.getActor("both"));
        } finally {
            system.terminate();
        }
    }
}
