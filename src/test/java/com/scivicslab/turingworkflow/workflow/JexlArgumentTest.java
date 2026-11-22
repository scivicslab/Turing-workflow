package com.scivicslab.turingworkflow.workflow;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import com.scivicslab.pojoactor.action.ActionResult;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Checks that an argument written as an expression reaches the action with its type intact.
 *
 * <p>An action's own message is text, so a number read back from it arrives as {@code "7"}. An
 * action declaring a numeric argument cannot be called that way. An expression reading the
 * workflow's state keeps the number a number.</p>
 */
@DisplayName("jexl: arguments")
class JexlArgumentTest {

    @Test
    @DisplayName("a number kept in the workflow's state arrives at an action as a number")
    void numberReachesActionAsNumber(@TempDir Path dir) throws Exception {
        Path wf = dir.resolve("jexl-args.yaml");
        Files.writeString(wf, """
            name: jexl-args
            steps:
              - states: ["0", "1"]
                actions:
                  - actor: this
                    method: putJson
                    arguments:
                      path: source
                      value: 7
              - states: ["1", "end"]
                actions:
                  - actor: target
                    method: add
                    arguments:
                      operand: "jexl: state.getInt('source', 0)"
            """);

        IIActorSystem system = new IIActorSystem("jexl-args-test");
        List<String> received = new CopyOnWriteArrayList<>();
        try {
            Interpreter interpreter = new Interpreter.Builder()
                    .loggerName("jexl-args-test")
                    .team(system)
                    .build();
            InterpreterIIAR self = new InterpreterIIAR("main", interpreter, system);
            interpreter.setSelfActorRef(self);
            system.addIIActor(self);
            system.addIIActor(new IIActorRef<Object>("target", new Object(), system) {
                @Override
                public ActionResult callByActionName(String actionName, String args) {
                    received.add(args);
                    return new ActionResult(true, "added");
                }
            });

            interpreter.readYaml(wf);
            ActionResult result = interpreter.runUntilEnd(100);

            assertTrue(result.isSuccess(), result.getResult());
            assertEquals(List.of("{\"operand\":7}"), received,
                         "the operand must arrive as the number 7, not as the text \"7\"");
        } finally {
            system.terminateIIActors();
        }
    }

    @Test
    @DisplayName("a list kept in the workflow's state arrives at an action as a JSON array")
    void listReachesActionAsJsonArray(@TempDir Path dir) throws Exception {
        Path wf = dir.resolve("jexl-list-arg.yaml");
        Files.writeString(wf, """
            name: jexl-list-arg
            steps:
              - states: ["0", "1"]
                actions:
                  - actor: this
                    method: appendJson
                    arguments: {path: xs, value: alpha}
                  - actor: this
                    method: appendJson
                    arguments: {path: xs, value: 2}
              - states: ["1", "end"]
                actions:
                  - actor: target
                    method: take
                    arguments:
                      items: "jexl: state.select('xs')"
                      first: "jexl: state.select('xs').get(0)"
            """);

        IIActorSystem system = new IIActorSystem("jexl-list-arg-test");
        List<String> received = new CopyOnWriteArrayList<>();
        try {
            Interpreter interpreter = new Interpreter.Builder()
                    .loggerName("jexl-list-arg-test")
                    .team(system)
                    .build();
            InterpreterIIAR self = new InterpreterIIAR("main", interpreter, system);
            interpreter.setSelfActorRef(self);
            system.addIIActor(self);
            system.addIIActor(new IIActorRef<Object>("target", new Object(), system) {
                @Override
                public ActionResult callByActionName(String actionName, String args) {
                    received.add(args);
                    return new ActionResult(true, "taken");
                }
            });

            interpreter.readYaml(wf);
            ActionResult result = interpreter.runUntilEnd(100);

            assertTrue(result.isSuccess(), result.getResult());
            assertEquals(1, received.size());
            org.json.JSONObject args = new org.json.JSONObject(received.get(0));
            assertTrue(args.get("items") instanceof org.json.JSONArray,
                       "a list from the state must arrive as a JSON array: " + received.get(0));
            assertEquals("[\"alpha\",2]", args.getJSONArray("items").toString());
            assertEquals("alpha", args.get("first"), "one item of it arrives as its value: " + received.get(0));
        } finally {
            system.terminateIIActors();
        }
    }
}
