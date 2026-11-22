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
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import org.json.JSONArray;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.scivicslab.pojoactor.action.ActionResult;

/**
 * Verifies that {@code loader.loadFromJar}, removed in 5.0.0, fails where a workflow calls it: the
 * action is false, its result says what replaces it, and no jar is loaded and no actor created.
 */
@DisplayName("loader.loadFromJar is removed")
class LoadFromJarRemovedTest {

    static final String EXPECTED_REASON = "error: the action 'loader.loadFromJar' was removed in 5.0.0;"
            + " load the jar and create the actor in two steps instead: loader.loadJar, then"
            + " loader.createChild (in loader.loadFromJar)";

    /** A class on the test classpath that the old one-step load could instantiate. */
    private static final String ACTOR_CLASS = DynamicActorLoaderActorTest.TestActor.class.getName();

    @TempDir
    Path dir;

    private IIActorSystem system;
    private Interpreter interpreter;
    private DynamicActorLoaderIIAR loader;
    private final List<String> recorded = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() {
        system = new IIActorSystem("load-from-jar-removed-test");
        interpreter = new Interpreter.Builder().loggerName("load-from-jar-removed-test").team(system).build();
        InterpreterIIAR self = new InterpreterIIAR("main", interpreter, system);
        interpreter.setSelfActorRef(self);
        system.addIIActor(self);
        loader = new DynamicActorLoaderIIAR("loader", system);
        system.addIIActor(loader);
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

    /** An empty but valid jar; the class it names is found through the parent class loader. */
    private Path emptyJar() throws Exception {
        Path jar = dir.resolve("plugin.jar");
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().putValue("Manifest-Version", "1.0");
        try (OutputStream out = Files.newOutputStream(jar); JarOutputStream ignored = new JarOutputStream(out, manifest)) {
            // no entries
        }
        return jar;
    }

    /** Runs loader.loadFromJar with the given arguments line, and returns what the fallback recorded. */
    private String reasonFor(String argumentsLine) {
        String yaml = """
                name: load-from-jar
                steps:
                  - states: ["0", "loaded"]
                    actions:
                      - actor: loader
                        method: loadFromJar
                        arguments: %s
                  - states: ["0", "end"]
                    actions:
                      - actor: recorder
                        method: record
                        arguments: ["jexl: result"]
                """.formatted(argumentsLine);
        interpreter.readYaml(new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)));
        ActionResult run = interpreter.runUntilEnd(10);

        assertTrue(run.isSuccess(), "the fallback should end the run: " + run.getResult());
        assertEquals(1, recorded.size(), "loader.loadFromJar must be false");
        return new JSONArray(recorded.get(0)).getString(0);
    }

    private void assertNothingLoadedOrCreated() {
        assertFalse(system.hasIIActor("myactor"), "no actor may be created");
        assertEquals("No JARs loaded", loader.callByActionName("listLoadedJars", "").getResult(),
                "no jar may be loaded");
    }

    @Test
    @DisplayName("the array form fails with the removal reason, loading and creating nothing")
    void arrayFormIsRemoved() throws Exception {
        Path jar = emptyJar();

        String reason = reasonFor("[\"" + jar + "\", \"" + ACTOR_CLASS + "\", \"myactor\"]");

        assertEquals(EXPECTED_REASON, reason);
        assertNothingLoadedOrCreated();
    }

    @Test
    @DisplayName("the comma-separated form fails with the removal reason, loading and creating nothing")
    void commaSeparatedFormIsRemoved() throws Exception {
        Path jar = emptyJar();

        String reason = reasonFor("\"" + jar + "," + ACTOR_CLASS + ",myactor\"");

        assertEquals(EXPECTED_REASON, reason);
        assertNothingLoadedOrCreated();
    }
}
