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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.scivicslab.pojoactor.action.ActionResult;

/**
 * {@code parallel-map} runs each item's sub-workflow under the base directory of the interpreter that
 * called it: a sub-workflow outside that directory is refused, and so is an explicit {@code baseDir}
 * outside it. A caller with no base directory restricts nothing, as an interpreter does.
 */
@DisplayName("parallel-map reads sub-workflows only under the caller's base directory")
class ParallelMapBaseDirTest {

    @TempDir
    Path dir;
    Path base;
    Path outside;

    private IIActorSystem system;
    private Interpreter interpreter;
    private final List<String> recorded = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        base = Files.createDirectories(dir.resolve("base"));
        outside = Files.createDirectories(dir.resolve("outside"));
        Files.writeString(base.resolve("echo.yaml"), ParallelMapActorTest.ECHO_YAML);
        Files.writeString(outside.resolve("echo.yaml"), ParallelMapActorTest.ECHO_YAML);

        system = new IIActorSystem("parallel-map-base-dir-test", 2);
        interpreter = new Interpreter.Builder().loggerName("parallel-map-base-dir-test").team(system).build();
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

    /**
     * Runs a workflow whose first step calls parallel-map with {@code args}; when that step is false,
     * the fallback records why. Answers the reason, or null when the step was true.
     */
    private String runParallelMap(JSONObject args) {
        String yaml = String.join("\n",
                "name: calls-parallel-map",
                "steps:",
                "  - states: [\"0\", \"end\"]",
                "    actions:",
                "      - actor: parallel-map",
                "        method: run",
                "        arguments: " + args,
                "  - states: [\"0\", \"recorded\"]",
                "    actions:",
                "      - actor: recorder",
                "        method: record",
                "        arguments: [\"jexl: result\"]",
                "  - states: [\"recorded\", \"end\"]",
                "    actions:",
                "      - actor: this",
                "        method: doNothing",
                "");
        interpreter.readYaml(new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)));
        ActionResult run = interpreter.runUntilEnd(10);
        assertTrue(run.isSuccess(), run.getResult());
        return recorded.isEmpty() ? null : recorded.get(0);
    }

    private static JSONObject mapOne(Path subWorkflow) {
        return new JSONObject()
                .put("subWorkflow", subWorkflow.toAbsolutePath().toString())
                .put("items", new JSONArray().put("alpha"));
    }

    @Test
    @DisplayName("a sub-workflow inside the caller's base directory, by absolute path, runs")
    void subWorkflow_insideCallerBase_runs() {
        interpreter.setWorkflowBaseDir(base.toString());

        String reason = runParallelMap(mapOne(base.resolve("echo.yaml")));

        assertEquals(null, reason, "the map should succeed: " + reason);
    }

    @Test
    @DisplayName("a sub-workflow outside the caller's base directory is refused")
    void subWorkflow_outsideCallerBase_isRefused() {
        interpreter.setWorkflowBaseDir(base.toString());

        String reason = runParallelMap(mapOne(outside.resolve("echo.yaml")));

        assertTrue(reason != null && reason.contains("outside the base directory"), String.valueOf(reason));
    }

    @Test
    @DisplayName("with no base directory on the caller, a sub-workflow anywhere runs")
    void subWorkflow_callerWithoutBase_runs() {
        String reason = runParallelMap(mapOne(outside.resolve("echo.yaml")));

        assertEquals(null, reason, "the map should succeed: " + reason);
    }

    @Test
    @DisplayName("an explicit baseDir outside the caller's base directory is refused")
    void explicitBaseDir_outsideCallerBase_isRefused() {
        interpreter.setWorkflowBaseDir(base.toString());

        String reason = runParallelMap(mapOne(outside.resolve("echo.yaml")).put("baseDir", outside.toString()));

        assertTrue(reason != null && reason.contains("outside the base directory"), String.valueOf(reason));
    }
}
