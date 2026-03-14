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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.scivicslab.pojoactor.core.ActionResult;

/**
 * Unit tests for Subworkflow functionality.
 *
 * <p>Tests the following features:</p>
 * <ul>
 *   <li>call method: 4-step subworkflow execution</li>
 *   <li>Child actor naming: timestamp + random</li>
 *   <li>Child actor cleanup after execution</li>
 *   <li>apply method: explicit child actor invocation</li>
 *   <li>Wildcard patterns in actor names</li>
 * </ul>
 *
 * @author devteam@scivicslab.com
 * @version 2.9.0
 */
@DisplayName("Subworkflow Specification")
public class SubworkflowTest {

    private IIActorSystem system;
    private Interpreter interpreter;
    private InterpreterIIAR interpreterActor;

    @BeforeEach
    public void setUp() {
        system = new IIActorSystem("subworkflow-test-system");

        interpreter = new Interpreter.Builder()
                .loggerName("subworkflow-test")
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

            // Names should start with expected prefix
            assertTrue(name1.startsWith("subwf-test-"));
            assertTrue(name2.startsWith("subwf-test-"));

            // Names should be unique
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
    @DisplayName("Call Method (4-step pattern)")
    class CallMethodTests {

        @Test
        @DisplayName("Should execute subworkflow and return success")
        public void testCallSubworkflow() {
            // Create a counter actor
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

            // Execute subworkflow directly
            ActionResult result = interpreter.call("sub-counter.yaml");

            assertTrue(result.isSuccess());
            assertEquals(3, count.get()); // sub-counter increments 3 times
        }

        @Test
        @DisplayName("Should remove child actor after execution")
        public void testChildActorRemoval() {
            // Create a counter actor
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

            // Count actors before
            int actorCountBefore = system.getIIActorCount();

            // Execute subworkflow
            interpreter.call("sub-counter.yaml");

            // Count actors after - should be same (child was removed)
            int actorCountAfter = system.getIIActorCount();
            assertEquals(actorCountBefore, actorCountAfter);

            // No subwf- actors should remain
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

    // ==================== Wildcard Pattern Tests ====================

    @Nested
    @DisplayName("Wildcard Pattern Matching")
    class WildcardPatternTests {

        @Test
        @DisplayName("Should find all actors with * pattern")
        public void testWildcardAll() {
            // Create multiple child actors
            for (int i = 1; i <= 3; i++) {
                String name = "worker-" + i;
                IIActorRef<Object> worker = new IIActorRef<Object>(name, new Object(), system) {
                    @Override
                    public ActionResult callByActionName(String actionName, String args) {
                        return new ActionResult(true, "ok");
                    }
                };
                worker.setParentName(interpreterActor.getName());
                interpreterActor.getNamesOfChildren().add(name);
                system.addIIActor(worker);
            }

            List<IIActorRef<?>> matched = interpreter.findMatchingChildActors("*");
            assertEquals(3, matched.size());
        }

        @Test
        @DisplayName("Should find actors with prefix-* pattern")
        public void testWildcardPrefix() {
            // Create mixed actors
            String[] names = {"worker-1", "worker-2", "manager-1"};
            for (String name : names) {
                IIActorRef<Object> actor = new IIActorRef<Object>(name, new Object(), system) {
                    @Override
                    public ActionResult callByActionName(String actionName, String args) {
                        return new ActionResult(true, "ok");
                    }
                };
                actor.setParentName(interpreterActor.getName());
                interpreterActor.getNamesOfChildren().add(name);
                system.addIIActor(actor);
            }

            List<IIActorRef<?>> matched = interpreter.findMatchingChildActors("worker-*");
            assertEquals(2, matched.size());
        }

        @Test
        @DisplayName("Should find actors with *-suffix pattern")
        public void testWildcardSuffix() {
            // Create mixed actors
            String[] names = {"cpu-worker", "gpu-worker", "cpu-manager"};
            for (String name : names) {
                IIActorRef<Object> actor = new IIActorRef<Object>(name, new Object(), system) {
                    @Override
                    public ActionResult callByActionName(String actionName, String args) {
                        return new ActionResult(true, "ok");
                    }
                };
                actor.setParentName(interpreterActor.getName());
                interpreterActor.getNamesOfChildren().add(name);
                system.addIIActor(actor);
            }

            List<IIActorRef<?>> matched = interpreter.findMatchingChildActors("*-worker");
            assertEquals(2, matched.size());
        }

        @Test
        @DisplayName("Should return empty list when no match")
        public void testWildcardNoMatch() {
            // Create actors that won't match
            String[] names = {"alpha", "beta", "gamma"};
            for (String name : names) {
                IIActorRef<Object> actor = new IIActorRef<Object>(name, new Object(), system) {
                    @Override
                    public ActionResult callByActionName(String actionName, String args) {
                        return new ActionResult(true, "ok");
                    }
                };
                actor.setParentName(interpreterActor.getName());
                interpreterActor.getNamesOfChildren().add(name);
                system.addIIActor(actor);
            }

            List<IIActorRef<?>> matched = interpreter.findMatchingChildActors("worker-*");
            assertTrue(matched.isEmpty());
        }
    }

    // ==================== Apply Method Tests ====================

    @Nested
    @DisplayName("Apply Method")
    class ApplyMethodTests {

        @Test
        @DisplayName("Should apply action to single child actor")
        public void testApplySingleActor() {
            AtomicInteger count = new AtomicInteger(0);
            String childName = "child-1";

            IIActorRef<Object> child = new IIActorRef<Object>(childName, new Object(), system) {
                @Override
                public ActionResult callByActionName(String actionName, String args) {
                    if ("increment".equals(actionName)) {
                        count.incrementAndGet();
                        return new ActionResult(true, "incremented");
                    }
                    return new ActionResult(false, "Unknown");
                }
            };
            child.setParentName(interpreterActor.getName());
            interpreterActor.getNamesOfChildren().add(childName);
            system.addIIActor(child);

            String actionDef = "{\"actor\": \"child-1\", \"method\": \"increment\"}";
            ActionResult result = interpreter.apply(actionDef);

            assertTrue(result.isSuccess());
            assertEquals(1, count.get());
        }

        @Test
        @DisplayName("Should apply action to multiple actors with wildcard")
        public void testApplyWildcard() {
            AtomicInteger totalCount = new AtomicInteger(0);

            for (int i = 1; i <= 3; i++) {
                String name = "species-" + i;
                IIActorRef<Object> actor = new IIActorRef<Object>(name, new Object(), system) {
                    @Override
                    public ActionResult callByActionName(String actionName, String args) {
                        if ("mutate".equals(actionName)) {
                            totalCount.incrementAndGet();
                            return new ActionResult(true, "mutated");
                        }
                        return new ActionResult(false, "Unknown");
                    }
                };
                actor.setParentName(interpreterActor.getName());
                interpreterActor.getNamesOfChildren().add(name);
                system.addIIActor(actor);
            }

            String actionDef = "{\"actor\": \"species-*\", \"method\": \"mutate\"}";
            ActionResult result = interpreter.apply(actionDef);

            assertTrue(result.isSuccess());
            assertEquals(3, totalCount.get());
            assertTrue(result.getResult().contains("Applied to 3 actors"));
        }

        @Test
        @DisplayName("Should report partial failure when some actors fail")
        public void testApplyPartialFailure() {
            AtomicInteger callCount = new AtomicInteger(0);

            for (int i = 1; i <= 3; i++) {
                final int index = i;
                String name = "node-" + i;
                IIActorRef<Object> actor = new IIActorRef<Object>(name, new Object(), system) {
                    @Override
                    public ActionResult callByActionName(String actionName, String args) {
                        callCount.incrementAndGet();
                        if (index == 2) {
                            return new ActionResult(false, "node-2 failed");
                        }
                        return new ActionResult(true, "ok");
                    }
                };
                actor.setParentName(interpreterActor.getName());
                interpreterActor.getNamesOfChildren().add(name);
                system.addIIActor(actor);
            }

            String actionDef = "{\"actor\": \"node-*\", \"method\": \"process\"}";
            ActionResult result = interpreter.apply(actionDef);

            assertFalse(result.isSuccess());
            // Parallel execution calls all actors
            assertEquals(3, callCount.get());
            // Result reports the failure
            assertTrue(result.getResult().contains("node-2"));
        }

        @Test
        @DisplayName("Should return failure when no actors match")
        public void testApplyNoMatch() {
            String actionDef = "{\"actor\": \"nonexistent-*\", \"method\": \"test\"}";
            ActionResult result = interpreter.apply(actionDef);

            assertFalse(result.isSuccess());
            assertTrue(result.getResult().contains("No actors matched"));
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
            // Create a temporary workflow file
            java.nio.file.Path tempDir = java.nio.file.Files.createTempDirectory("workflow-test");
            java.nio.file.Path workflowFile = tempDir.resolve("temp-workflow.yaml");

            // Create a simple workflow that just ends
            String yamlContent = """
                name: TempWorkflow
                steps:
                  - states: ["0", "end"]
                    actions:
                      - actor: main
                        method: doNothing
                """;
            java.nio.file.Files.writeString(workflowFile, yamlContent);

            // Add doNothing handler to interpreter
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

            // Set baseDir and run workflow
            interpreter.setWorkflowBaseDir(tempDir.toString());
            ActionResult result = interpreter.runWorkflow("temp-workflow.yaml");

            assertTrue(result.isSuccess(), "Workflow should succeed: " + result.getResult());

            // Cleanup
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

            // Cleanup
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
            // Create a counter actor
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

            // Load and run main workflow
            InputStream yamlStream = getClass().getResourceAsStream("/workflows/main-calls-sub.yaml");
            interpreter.readYaml(yamlStream);

            ActionResult result = interpreter.runUntilEnd(100);

            assertTrue(result.isSuccess());
            assertEquals(3, count.get()); // sub-counter increments 3 times
        }
    }
}
