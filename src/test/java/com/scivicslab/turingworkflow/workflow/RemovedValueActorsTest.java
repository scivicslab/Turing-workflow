/*
 * Copyright 2025 SCIVICS Lab
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.scivicslab.turingworkflow.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.scivicslab.pojoactor.action.ActionResult;

/**
 * Verifies that the built-in value actors {@code calc}, {@code list} and {@code str}, removed in
 * 5.0.0, fail clearly where a workflow names them, and that {@code out} is still the supported
 * built-in way to print.
 *
 * <p>A workflow naming one of the removed actors, plain or with a {@code :name}, gets a false
 * action whose result names the actor, says it was removed in 5.0.0 and names the replacement.
 * Nothing is created for that name.</p>
 */
@DisplayName("calc, list and str are removed; out stays")
class RemovedValueActorsTest {

    private IIActorSystem system;
    private Interpreter interpreter;
    private InterpreterIIAR interpreterActor;
    private final List<String> recorded = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() {
        system = new IIActorSystem("removed-value-actors-test");
        interpreter = new Interpreter.Builder()
                .loggerName("removed-value-actors-test")
                .team(system)
                .build();
        interpreterActor = new InterpreterIIAR("main", interpreter, system);
        interpreter.setSelfActorRef(interpreterActor);
        system.addIIActor(interpreterActor);
        system.addIIActor(new IIActorRef<Object>("recorder", new Object(), system) {
            @Override
            public ActionResult callByActionName(String actionName, String args) {
                recorded.add(args);
                return new ActionResult(true, "recorded");
            }
        });
    }

    @AfterEach
    void tearDown() {
        system.terminateIIActors();
        system.terminate();
    }

    /** The reason a removed actor's action fails with, as the workflow reads it in jexl: result. */
    static String expectedReason(String actorName, String method) {
        return "error: the built-in actor '" + actorName + "' was removed in 5.0.0; keep the value in"
                + " the workflow's own state instead: write it with this.putJson or this.appendJson,"
                + " read it with jexl: state... (in " + actorName + "." + method + ")";
    }

    /**
     * Runs a workflow whose first action calls {@code method} on {@code actorName}, with a fallback
     * that records the last result, and returns what the fallback recorded.
     */
    private String reasonFor(String actorName, String method) {
        recorded.clear();
        String yaml = """
                name: removed-value-actor
                steps:
                  - states: ["0", "used"]
                    actions:
                      - actor: "%s"
                        method: %s
                  - states: ["0", "end"]
                    actions:
                      - actor: recorder
                        method: record
                        arguments: ["jexl: result"]
                """.formatted(actorName, method);
        interpreter.reset();
        interpreter.readYaml(new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)));
        ActionResult run = interpreter.runUntilEnd(10);

        assertTrue(run.isSuccess(), "the fallback should end the run: " + run.getResult());
        assertEquals(1, recorded.size(), "the action naming " + actorName + " must be false");
        assertFalse(system.hasIIActor(actorName), "nothing may be created under " + actorName);
        return recorded.get(0);
    }

    @Test
    @DisplayName("calc and calc:<name> fail with the removal reason")
    void calcIsRemoved() {
        assertEquals("[\"" + expectedReason("calc", "set") + "\"]", reasonFor("calc", "set"));
        assertEquals("[\"" + expectedReason("calc:i", "inc") + "\"]", reasonFor("calc:i", "inc"));
    }

    @Test
    @DisplayName("list and list:<name> fail with the removal reason")
    void listIsRemoved() {
        assertEquals("[\"" + expectedReason("list", "add") + "\"]", reasonFor("list", "add"));
        assertEquals("[\"" + expectedReason("list:items", "size") + "\"]", reasonFor("list:items", "size"));
    }

    @Test
    @DisplayName("str and str:<name> fail with the removal reason")
    void strIsRemoved() {
        assertEquals("[\"" + expectedReason("str", "set") + "\"]", reasonFor("str", "set"));
        assertEquals("[\"" + expectedReason("str:name", "get") + "\"]", reasonFor("str:name", "get"));
    }

    @Test
    @DisplayName("an expression reaching a removed actor fails its argument with the removal reason")
    void expressionReachingARemovedActorFails() {
        String yaml = """
                name: removed-value-actor-in-expression
                steps:
                  - states: ["0", "used"]
                    actions:
                      - actor: recorder
                        method: record
                        arguments: ["jexl: actors.get('calc:i').get()"]
                  - states: ["0", "end"]
                    actions:
                      - actor: recorder
                        method: record
                        arguments: ["jexl: result"]
                """;
        interpreter.readYaml(new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)));
        ActionResult run = interpreter.runUntilEnd(10);

        assertTrue(run.isSuccess(), run.getResult());
        assertEquals(1, recorded.size(), "the argument naming calc:i must fail its action");
        assertTrue(recorded.get(0).contains("the built-in actor 'calc:i' was removed in 5.0.0"), recorded.get(0));
        assertFalse(system.hasIIActor("calc:i"), "nothing may be created under calc:i");
    }

    @Test
    @DisplayName("out prints and logs no deprecation warning")
    void outLogsNoDeprecationWarning() {
        Logger logger = Logger.getLogger(IIActorSystem.class.getName());
        List<LogRecord> warnings = new CopyOnWriteArrayList<>();
        Handler handler = new Handler() {
            @Override public void publish(LogRecord r) {
                if (r.getLevel().intValue() >= Level.WARNING.intValue()) warnings.add(r);
            }
            @Override public void flush() { }
            @Override public void close() { }
        };
        logger.addHandler(handler);
        try {
            String yaml = """
                    name: out-prints
                    steps:
                      - states: ["0", "end"]
                        actions:
                          - actor: out
                            method: print
                            arguments: {message: "hello"}
                    """;
            interpreter.readYaml(new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)));
            ActionResult run = interpreter.runUntilEnd(10);

            assertTrue(run.isSuccess(), run.getResult());
            assertTrue(system.hasIIActor("out"), "out is still made on first use");
            assertTrue(warnings.isEmpty(), "out must log no warning, got: "
                    + warnings.stream().map(LogRecord::getMessage).toList());
        } finally {
            logger.removeHandler(handler);
        }
    }
}
