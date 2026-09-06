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

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.scivicslab.pojoactor.action.ActionResult;

/**
 * Unit tests for subworkflow apply functionality.
 *
 * <p>Tests the following features:</p>
 * <ul>
 *   <li>apply method: broadcast action to multiple child actors</li>
 *   <li>Wildcard patterns in actor names (*, prefix-*, *-suffix)</li>
 *   <li>Partial failure handling (continue all, report failures)</li>
 * </ul>
 *
 * @author devteam@scivicslab.com
 * @version 2.9.0
 */
@Tag("Y_base.04")
@DisplayName("Subworkflow Apply Specification")
public class SubworkflowApplyTest {

    private IIActorSystem system;
    private Interpreter interpreter;
    private InterpreterIIAR interpreterActor;

    @BeforeEach
    public void setUp() {
        system = new IIActorSystem("subworkflow-apply-test-system");

        interpreter = new Interpreter.Builder()
                .loggerName("subworkflow-apply-test")
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

    // ==================== Wildcard Pattern Tests ====================

    @Nested
    @DisplayName("Wildcard Pattern Matching")
    class WildcardPatternTests {

        @Test
        @DisplayName("Should find all actors with * pattern")
        public void testWildcardAll() {
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
            assertEquals(3, callCount.get());
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
}
