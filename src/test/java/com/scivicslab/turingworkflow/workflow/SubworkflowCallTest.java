/*
 * Copyright 2025 devteam@scivicslab.com
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package com.scivicslab.turingworkflow.workflow;

import static org.junit.jupiter.api.Assertions.*;

import java.io.InputStream;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.scivicslab.pojoactor.action.ActionResult;

/**
 * Unit tests for subworkflow call functionality.
 *
 * <p>Tests the following features:</p>
 * <ul>
 *   <li>call method: subworkflow execution via child interpreter</li>
 *   <li>Child actor naming: timestamp + random (generateChildName)</li>
 *   <li>Child actor cleanup after execution</li>
 *   <li>workflowBaseDir: filesystem-based workflow loading</li>
 * </ul>
 *
 * @author devteam@scivicslab.com
 * @version 2.9.0
 */
@Tag("Y_base.03")
@DisplayName("Subworkflow Call Specification")
public class SubworkflowCallTest {

    private IIActorSystem system;
    private Interpreter interpreter;
    private InterpreterIIAR interpreterActor;

    @BeforeEach
    public void setUp() {
        system = new IIActorSystem("subworkflow-call-test-system");

        interpreter = new Interpreter.Builder()
                .loggerName("subworkflow-call-test")
                .team(system)
                .build();

        interpreterActor = new InterpreterIIAR("main", interpreter, system);
        interpreter.setSelfActorRef(interpreterActor);
        system.addIIActor(interpreterActor);
    }

    @AfterEach
    public void tearDown() {
        if (system != null) {
            system.terminateIIActors();
            system.terminate();
        }
    }

    // ==================== Child Name Generation Tests ====================

    @Nested
    @DisplayName("Child Name Generation")
    class ChildNameGenerationTests {

        @Test
        @DisplayName("Should generate unique names with timestamp and random")
        public void testGenerateChildName() {
            String name1 = interpreter.generateChildName("test.yaml");
            String name2 = interpreter.generateChildName("test.yaml");

            assertTrue(name1.startsWith("subwf-test-"));
            assertTrue(name2.startsWith("subwf-test-"));
            assertNotEquals(name1, name2);
        }

        @Test
        @DisplayName("Should handle yaml extension removal")
        public void testYamlExtensionRemoval() {
            String name = interpreter.generateChildName("my-workflow.yaml");
            assertTrue(name.startsWith("subwf-my-workflow-"));
            assertFalse(name.contains(".yaml"));
        }

        @Test
        @DisplayName("Should handle json extension removal")
        public void testJsonExtensionRemoval() {
            String name = interpreter.generateChildName("my-workflow.json");
            assertTrue(name.startsWith("subwf-my-workflow-"));
            assertFalse(name.contains(".json"));
        }
    }

    // ==================== Call Method Tests ====================

    @Nested
    @DisplayName("Call Method (subworkflow execution)")
    class CallMethodTests {

        @Test
        @DisplayName("Should execute subworkflow and return success")
        public void testCallSubworkflow() {
            AtomicInteger count = new AtomicInteger(0);
            IIActorRef<Object> counter = new IIActorRef<Object>("counter", new Object(), system) {
                @Override
                public ActionResult callByActionName(String actionName, String args) {
                    if ("increment".equals(actionName)) {
                        count.incrementAndGet();
                        return new ActionResult(true, "count=" + count.get());
                    }
                    if ("getCount".equals(actionName)) {
                        return new ActionResult(true, String.valueOf(count.get()));
                    }
                    return new ActionResult(false, "Unknown action: " + actionName);
                }
            };
            system.addIIActor(counter);

            ActionResult result = interpreter.call("sub-counter.yaml");

            assertTrue(result.isSuccess());
            assertEquals(3, count.get());
        }

        @Test
        @DisplayName("Should remove child actor after execution")
        public void testChildActorRemoval() {
            AtomicInteger count = new AtomicInteger(0);
            IIActorRef<Object> counter = new IIActorRef<Object>("counter", new Object(), system) {
                @Override
                public ActionResult callByActionName(String actionName, String args) {
                    if ("increment".equals(actionName)) {
                        count.incrementAndGet();
                        return new ActionResult(true, "count=" + count.get());
                    }
                    return new ActionResult(true, "ok");
                }
            };
            system.addIIActor(counter);

            int actorCountBefore = system.getIIActorCount();
            interpreter.call("sub-counter.yaml");
            int actorCountAfter = system.getIIActorCount();

            assertEquals(actorCountBefore, actorCountAfter);
            for (String name : system.listActorNames()) {
                assertFalse(name.startsWith("subwf-"), "Child actor should be removed: " + name);
            }
        }

        @Test
        @DisplayName("Should return failure for non-existent workflow")
        public void testCallNonExistentWorkflow() {
            ActionResult result = interpreter.call("non-existent.yaml");

            assertFalse(result.isSuccess());
            assertTrue(result.getResult().contains("not found"));
        }
    }

    // ==================== Workflow Base Directory Tests ====================

    @Nested
    @DisplayName("Workflow Base Directory")
    class WorkflowBaseDirTests {

        @Test
        @DisplayName("Should set and get workflowBaseDir")
        public void testSetGetWorkflowBaseDir() {
            assertNull(interpreter.getWorkflowBaseDir());

            interpreter.setWorkflowBaseDir("/path/to/workflows");
            assertEquals("/path/to/workflows", interpreter.getWorkflowBaseDir());
        }

        @Test
        @DisplayName("Should find workflow from baseDir when classpath fails")
        public void testRunWorkflowFromBaseDir() throws Exception {
            java.nio.file.Path tempDir = java.nio.file.Files.createTempDirectory("workflow-test");
            java.nio.file.Path workflowFile = tempDir.resolve("temp-workflow.yaml");

            String yamlContent = """
                name: TempWorkflow
                steps:
                  - states: ["0", "end"]
                    actions:
                      - actor: main
                        method: doNothing
                """;
            java.nio.file.Files.writeString(workflowFile, yamlContent);

            interpreterActor = new InterpreterIIAR("main", interpreter, system) {
                @Override
                public ActionResult callByActionName(String actionName, String args) {
                    if ("doNothing".equals(actionName)) {
                        return new ActionResult(true, "did nothing");
                    }
                    return super.callByActionName(actionName, args);
                }
            };
            system.addIIActor(interpreterActor);

            interpreter.setWorkflowBaseDir(tempDir.toString());
            ActionResult result = interpreter.runWorkflow("temp-workflow.yaml");

            assertTrue(result.isSuccess(), "Workflow should succeed: " + result.getResult());

            java.nio.file.Files.delete(workflowFile);
            java.nio.file.Files.delete(tempDir);
        }

        @Test
        @DisplayName("Should fail when workflow not found in baseDir")
        public void testRunWorkflowNotFoundInBaseDir() throws Exception {
            java.nio.file.Path tempDir = java.nio.file.Files.createTempDirectory("workflow-test-empty");

            interpreter.setWorkflowBaseDir(tempDir.toString());
            ActionResult result = interpreter.runWorkflow("nonexistent.yaml");

            assertFalse(result.isSuccess());
            assertTrue(result.getResult().contains("not found"));

            java.nio.file.Files.delete(tempDir);
        }
    }

    // ==================== Integration Tests ====================

    @Nested
    @DisplayName("Integration Tests")
    class IntegrationTests {

        @Test
        @DisplayName("Should execute main workflow that calls subworkflow")
        public void testMainCallsSubworkflow() {
            AtomicInteger count = new AtomicInteger(0);
            IIActorRef<Object> counter = new IIActorRef<Object>("counter", new Object(), system) {
                @Override
                public ActionResult callByActionName(String actionName, String args) {
                    if ("increment".equals(actionName)) {
                        count.incrementAndGet();
                        return new ActionResult(true, "count=" + count.get());
                    }
                    if ("getCount".equals(actionName)) {
                        return new ActionResult(true, String.valueOf(count.get()));
                    }
                    return new ActionResult(false, "Unknown action: " + actionName);
                }
            };
            system.addIIActor(counter);

            InputStream yamlStream = getClass().getResourceAsStream("/workflows/main-calls-sub.yaml");
            interpreter.readYaml(yamlStream);

            ActionResult result = interpreter.runUntilEnd(100);

            assertTrue(result.isSuccess());
            assertEquals(3, count.get());
        }
    }
}
