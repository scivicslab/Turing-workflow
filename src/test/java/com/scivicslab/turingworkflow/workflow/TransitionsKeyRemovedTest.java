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

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.scivicslab.turingworkflow.workflow.kustomize.WorkflowKustomizer;

/**
 * Verifies that a workflow whose top level has {@code transitions:}, removed in 5.0.0, is refused
 * when it is loaded, with a reason saying to rename it to {@code steps:}. It must not load as an
 * empty workflow. The same holds when both keys are present, and on the kustomize overlay path.
 */
@DisplayName("the top-level transitions: key is removed")
class TransitionsKeyRemovedTest {

    static final String EXPECTED = "the top-level key `transitions:` was removed in 5.0.0; rename it to `steps:`";

    private static final String TRANSITIONS_ONLY = """
            name: old-style
            transitions:
              - states: ["0", "end"]
                actions:
                  - actor: this
                    method: doNothing
            """;

    private static final String BOTH_KEYS = """
            name: both-keys
            steps:
              - states: ["0", "end"]
                actions:
                  - actor: this
                    method: doNothing
            transitions:
              - states: ["0", "end"]
                actions:
                  - actor: this
                    method: doNothing
            """;

    @TempDir
    Path dir;

    private IIActorSystem system;
    private Interpreter interpreter;

    @BeforeEach
    void setUp() {
        system = new IIActorSystem("transitions-key-removed-test");
        interpreter = new Interpreter.Builder().loggerName("transitions-key-removed-test").team(system).build();
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
    @DisplayName("Interpreter: a workflow with transitions: is refused at load")
    void interpreterRefusesTransitions() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> load(TRANSITIONS_ONLY));
        assertEquals(EXPECTED, e.getMessage());
    }

    @Test
    @DisplayName("Interpreter: a workflow with both steps: and transitions: is refused too")
    void interpreterRefusesBothKeys() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> load(BOTH_KEYS));
        assertEquals(EXPECTED, e.getMessage());
    }

    @Test
    @DisplayName("Interpreter: a workflow with steps: still loads")
    void interpreterLoadsSteps() {
        load("""
                name: new-style
                steps:
                  - states: ["0", "end"]
                    actions:
                      - actor: this
                        method: doNothing
                """);
        assertEquals(1, interpreter.getCode().getTransitions().size());
    }

    @Test
    @DisplayName("WorkflowKustomizer: a base workflow with transitions: is refused when the overlay is built")
    void kustomizerRefusesTransitions() throws Exception {
        Files.writeString(dir.resolve("overlay-conf.yaml"), """
                apiVersion: pojoactor.scivicslab.com/v1
                kind: OverlayConf
                """);
        Files.writeString(dir.resolve("old-style.yaml"), TRANSITIONS_ONLY);

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> new WorkflowKustomizer().build(dir));
        assertEquals(EXPECTED, e.getMessage());
    }

    @Test
    @DisplayName("WorkflowKustomizer: a patch with transitions: is refused when the overlay is built")
    void kustomizerRefusesTransitionsInAPatch() throws Exception {
        Path base = Files.createDirectories(dir.resolve("base"));
        Files.writeString(base.resolve("overlay-conf.yaml"), """
                apiVersion: pojoactor.scivicslab.com/v1
                kind: OverlayConf
                """);
        Files.writeString(base.resolve("main.yaml"), """
                name: main
                steps:
                  - states: ["0", "end"]
                    label: only
                    actions:
                      - actor: this
                        method: doNothing
                """);
        Path overlay = Files.createDirectories(dir.resolve("overlay"));
        Files.writeString(overlay.resolve("overlay-conf.yaml"), """
                apiVersion: pojoactor.scivicslab.com/v1
                kind: OverlayConf
                bases:
                  - ../base
                patches:
                  - patch.yaml
                """);
        Files.writeString(overlay.resolve("patch.yaml"), """
                name: main
                transitions:
                  - states: ["0", "end"]
                    label: only
                    actions:
                      - actor: this
                        method: print
                """);

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> new WorkflowKustomizer().build(overlay));
        assertEquals(EXPECTED, e.getMessage());
    }
}
