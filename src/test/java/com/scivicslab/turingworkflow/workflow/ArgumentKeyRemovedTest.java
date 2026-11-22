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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.scivicslab.pojoactor.action.ActionResult;

/**
 * Verifies that an action written with the singular {@code argument:} key is refused when the
 * workflow is loaded, with a reason saying to write {@code arguments:}. The engine only ever read
 * {@code arguments:}, so before 5.0.0 the singular key was dropped and the action was called with no
 * arguments at all.
 */
@DisplayName("the step key argument: is refused")
class ArgumentKeyRemovedTest {

    static final String EXPECTED = "the step key 'argument:' was removed in 5.0.0; write 'arguments:'";

    private static final String SINGULAR = """
            name: singular
            steps:
              - states: ["0", "end"]
                actions:
                  - actor: recorder
                    method: record
                    argument: "x"
            """;

    @TempDir
    Path dir;

    private IIActorSystem system;
    private Interpreter interpreter;
    private final List<String> recorded = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() {
        system = new IIActorSystem("argument-key-removed-test");
        interpreter = new Interpreter.Builder().loggerName("argument-key-removed-test").team(system).build();
        InterpreterIIAR self = new InterpreterIIAR("main", interpreter, system);
        interpreter.setSelfActorRef(self);
        system.addIIActor(self);
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

    private void load(String yaml) {
        interpreter.readYaml(new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    @DisplayName("arguments: [\"x\"] reaches the actor as [\"x\"]")
    void pluralKeyReachesTheActor() {
        load("""
                name: plural
                steps:
                  - states: ["0", "end"]
                    actions:
                      - actor: recorder
                        method: record
                        arguments: ["x"]
                """);
        ActionResult run = interpreter.runUntilEnd(10);

        assertTrue(run.isSuccess(), run.getResult());
        assertEquals(List.of("[\"x\"]"), recorded);
    }

    @Test
    @DisplayName("argument: \"x\" is refused at load, and nothing is called")
    void singularKeyIsRefused() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> load(SINGULAR));
        assertEquals(EXPECTED, e.getMessage());
        assertTrue(recorded.isEmpty(), "no action may run: " + recorded);
    }

    @Test
    @DisplayName("argument: is refused on the overlay path too")
    void singularKeyIsRefusedThroughAnOverlay() throws Exception {
        Files.writeString(dir.resolve("overlay-conf.yaml"), """
                apiVersion: pojoactor.scivicslab.com/v1
                kind: OverlayConf
                """);
        Path workflow = dir.resolve("singular.yaml");
        Files.writeString(workflow, SINGULAR);

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> interpreter.readYaml(workflow, dir));
        assertEquals(EXPECTED, e.getMessage());
    }
}
