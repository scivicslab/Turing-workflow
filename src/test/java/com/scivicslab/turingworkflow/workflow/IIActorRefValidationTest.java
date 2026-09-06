package com.scivicslab.turingworkflow.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.scivicslab.pojoactor.action.Action;
import com.scivicslab.pojoactor.action.ActionResult;

/**
 * Whether an argument that does not match the declared shape is refused.
 *
 * <p>The shape is declared as a record, a JSON Schema is generated from it at build time, and
 * {@code ActionCatalog} hands that schema to whoever is about to call. Until the dispatcher was
 * given the schemas, none of that reached the call: Jackson coerced whatever arrived, and
 * {@code {"hostname": 123}} was accepted as the string "123".
 */
@DisplayName("IIActorRef — refusing an argument that does not match its declared shape")
class IIActorRefValidationTest {

    public record ConfigureArgs(String hostname, int port) {}

    /** The schema for this class's configure action is in src/test/resources/action-schemas. */
    public static class NodeIIAR extends IIActorRef<Object> {
        NodeIIAR(IIActorSystem system) {
            super("node", new Object(), system);
        }

        @Action(value = "configure", argsType = ConfigureArgs.class)
        public ActionResult configure(ConfigureArgs args) {
            return new ActionResult(true, args.hostname() + ":" + args.port());
        }
    }

    /** An actor with no argsType at all. */
    public static class PlainIIAR extends IIActorRef<Object> {
        PlainIIAR(IIActorSystem system) {
            super("plain", new Object(), system);
        }

        @Action("echo")
        public ActionResult echo(String args) {
            return new ActionResult(true, args);
        }
    }

    @Test
    void acceptsAnArgumentThatMatches() {
        IIActorSystem system = new IIActorSystem("validation");
        try {
            NodeIIAR node = new NodeIIAR(system);

            ActionResult r = node.callByActionName("configure",
                    "{\"hostname\":\"worker1.internal\",\"port\":8080}");

            assertTrue(r.isSuccess(), r.getResult());
            assertEquals("worker1.internal:8080", r.getResult());
        } finally {
            system.terminate();
        }
    }

    /** A number where the schema says string: Jackson would coerce it, the schema must not. */
    @Test
    void refusesAValueOfTheWrongType() {
        IIActorSystem system = new IIActorSystem("validation-wrong-type");
        try {
            NodeIIAR node = new NodeIIAR(system);

            ActionResult r = node.callByActionName("configure",
                    "{\"hostname\":123,\"port\":8080}");

            assertFalse(r.isSuccess(), "123 is not a string");
            assertTrue(r.getResult().contains("Argument validation failed"), r.getResult());
        } finally {
            system.terminate();
        }
    }

    /** Actions that declare no argsType keep taking a raw String, unvalidated as before. */
    @Test
    void leavesUndeclaredActionsAlone() {
        IIActorSystem system = new IIActorSystem("validation-untyped");
        try {
            PlainIIAR plain = new PlainIIAR(system);

            ActionResult r = plain.callByActionName("echo", "anything at all");

            assertTrue(r.isSuccess());
            assertEquals("anything at all", r.getResult());
        } finally {
            system.terminate();
        }
    }
}
