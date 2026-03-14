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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.json.JSONArray;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.scivicslab.pojoactor.core.ActionResult;

/**
 * Tests for conditional branching in the Workflow Interpreter.
 *
 * <p>These tests verify that the finite automaton semantics work correctly:</p>
 * <ul>
 *   <li>Actions return boolean via ActionResult.isSuccess()</li>
 *   <li>When an action returns false, the step is aborted and next step is tried</li>
 *   <li>Multiple steps with the same from-state enable conditional branching</li>
 *   <li>The first step whose all actions succeed determines the transition</li>
 * </ul>
 *
 * @author devteam@scivicslab.com
 * @version 2.7.0
 */
@DisplayName("Conditional Branching Tests")
public class ConditionalBranchingTest {

    /**
     * Helper method to extract the first element from a JSON array string.
     * If the input is not a JSON array, returns the input as-is.
     */
    private static String getFirstArg(String args) {
        if (args == null || args.isEmpty()) {
            return args;
        }
        if (args.startsWith("[")) {
            JSONArray jsonArray = new JSONArray(args);
            return jsonArray.length() > 0 ? jsonArray.getString(0) : "";
        }
        return args;
    }

    private IIActorSystem system;
    private ConditionalActor conditionalActor;

    @BeforeEach
    void setUp() {
        system = new IIActorSystem("conditional-test-system");
        conditionalActor = new ConditionalActor();

        // Register the conditional actor
        ConditionalIIAR actorRef = new ConditionalIIAR("checker", conditionalActor, system);
        system.addIIActor(actorRef);
    }

    /**
     * Test actor that can return configurable boolean results.
     */
    static class ConditionalActor {
        private List<String> executedActions = new ArrayList<>();
        private String currentValue = "";

        public void reset() {
            executedActions.clear();
            currentValue = "";
        }

        public void setValue(String value) {
            this.currentValue = value;
        }

        public String getValue() {
            return currentValue;
        }

        public List<String> getExecutedActions() {
            return new ArrayList<>(executedActions);
        }

        public boolean matchValue(String expected) {
            executedActions.add("matchValue(" + expected + ")");
            return currentValue.equals(expected);
        }

        public void doAction(String name) {
            executedActions.add("doAction(" + name + ")");
        }

        public boolean alwaysTrue() {
            executedActions.add("alwaysTrue()");
            return true;
        }

        public boolean alwaysFalse() {
            executedActions.add("alwaysFalse()");
            return false;
        }
    }

    /**
     * IIActorRef wrapper for ConditionalActor.
     */
    static class ConditionalIIAR extends IIActorRef<ConditionalActor> {

        public ConditionalIIAR(String actorName, ConditionalActor object, IIActorSystem system) {
            super(actorName, object, system);
        }

        @Override
        public ActionResult callByActionName(String actionName, String args) {
            String arg = getFirstArg(args);  // Parse JSON array
            switch (actionName) {
                case "matchValue":
                    boolean matched = this.object.matchValue(arg);
                    return new ActionResult(matched, "matchValue(" + arg + ")=" + matched);

                case "doAction":
                    this.object.doAction(arg);
                    return new ActionResult(true, "doAction(" + arg + ")");

                case "alwaysTrue":
                    this.object.alwaysTrue();
                    return new ActionResult(true, "alwaysTrue");

                case "alwaysFalse":
                    this.object.alwaysFalse();
                    return new ActionResult(false, "alwaysFalse");

                case "setValue":
                    this.object.setValue(arg);
                    return new ActionResult(true, "setValue(" + arg + ")");

                default:
                    return new ActionResult(false, "Unknown action: " + actionName);
            }
        }
    }

    // ==================== Helper Methods ====================

    private Interpreter createInterpreter() {
        return new Interpreter.Builder()
                .loggerName("test-interpreter")
                .team(system)
                .build();
    }

    private MatrixCode createCode(String name, Transition... steps) {
        MatrixCode code = new MatrixCode();
        code.setName(name);
        code.setSteps(Arrays.asList(steps));
        return code;
    }

    private Transition createTransition(String fromState, String toState, Action... actions) {
        Transition transition = new Transition();
        transition.setStates(Arrays.asList(fromState, toState));
        transition.setActions(Arrays.asList(actions));
        return transition;
    }

    private Action createAction(String actor, String method, String argument) {
        Action action = new Action();
        action.setActor(actor);
        action.setMethod(method);
        if (argument != null && !argument.isEmpty()) {
            action.setArguments(argument);
        }
        return action;
    }

    private void setInterpreterCode(Interpreter interpreter, MatrixCode code) {
        try {
            java.lang.reflect.Field codeField = Interpreter.class.getDeclaredField("code");
            codeField.setAccessible(true);
            codeField.set(interpreter, code);
        } catch (Exception e) {
            fail("Failed to set code field: " + e.getMessage());
        }
    }

    // ==================== Basic State Matching Tests ====================

    @Nested
    @DisplayName("matchesCurrentState()")
    class MatchesCurrentStateTests {

        @Test
        @DisplayName("should return true when from-state matches current state")
        void shouldMatchWhenFromStateMatchesCurrentState() {
            Interpreter interpreter = createInterpreter();
            Transition row = createTransition("0", "1", createAction("checker", "alwaysTrue", ""));

            assertTrue(interpreter.matchesCurrentState(row));
        }

        @Test
        @DisplayName("should return false when from-state does not match current state")
        void shouldNotMatchWhenFromStateDiffers() {
            Interpreter interpreter = createInterpreter();
            Transition row = createTransition("1", "2", createAction("checker", "alwaysTrue", ""));

            assertFalse(interpreter.matchesCurrentState(row));
        }

        @Test
        @DisplayName("should return false when states list is too short")
        void shouldReturnFalseForShortStatesList() {
            Interpreter interpreter = createInterpreter();
            Transition row = new Transition();
            row.setStates(Arrays.asList("0")); // Only one state
            row.setActions(Arrays.asList());

            assertFalse(interpreter.matchesCurrentState(row));
        }
    }

    // ==================== Action Boolean Return Tests ====================

    @Nested
    @DisplayName("Action Boolean Return Behavior")
    class ActionBooleanTests {

        @Test
        @DisplayName("should succeed when all actions return true")
        void shouldSucceedWhenAllActionsReturnTrue() {
            Interpreter interpreter = createInterpreter();

            MatrixCode code = createCode("test",
                createTransition("0", "1",
                    createAction("checker", "alwaysTrue", ""),
                    createAction("checker", "alwaysTrue", ""),
                    createAction("checker", "alwaysTrue", "")
                )
            );
            setInterpreterCode(interpreter, code);

            ActionResult result = interpreter.execCode();

            assertTrue(result.isSuccess(), "Should succeed when all actions return true");
            assertEquals("1", interpreter.getCurrentState());
        }

        @Test
        @DisplayName("should fail step when first action returns false")
        void shouldFailWhenFirstActionReturnsFalse() {
            Interpreter interpreter = createInterpreter();
            conditionalActor.reset();

            MatrixCode code = createCode("test",
                createTransition("0", "1",
                    createAction("checker", "alwaysFalse", ""),
                    createAction("checker", "doAction", "should-not-run")
                )
            );
            setInterpreterCode(interpreter, code);

            ActionResult result = interpreter.execCode();

            assertFalse(result.isSuccess(), "Step should fail when action returns false");
            assertEquals("0", interpreter.getCurrentState(), "State should not change");

            // Verify second action was NOT executed
            List<String> executed = conditionalActor.getExecutedActions();
            assertEquals(1, executed.size(), "Only first action should be executed");
            assertEquals("alwaysFalse()", executed.get(0));
        }

        @Test
        @DisplayName("should fail step when middle action returns false")
        void shouldFailWhenMiddleActionReturnsFalse() {
            Interpreter interpreter = createInterpreter();
            conditionalActor.reset();

            MatrixCode code = createCode("test",
                createTransition("0", "1",
                    createAction("checker", "alwaysTrue", ""),
                    createAction("checker", "alwaysFalse", ""),
                    createAction("checker", "doAction", "should-not-run")
                )
            );
            setInterpreterCode(interpreter, code);

            ActionResult result = interpreter.execCode();

            assertFalse(result.isSuccess());

            // Verify third action was NOT executed
            List<String> executed = conditionalActor.getExecutedActions();
            assertEquals(2, executed.size(), "Only first two actions should be executed");
            assertFalse(executed.contains("doAction(should-not-run)"));
        }
    }

    // ==================== Conditional Branching Tests ====================

    @Nested
    @DisplayName("Conditional Branching with Same From-State")
    class ConditionalBranchingTests {

        @Test
        @DisplayName("should try next step when first step's action fails")
        void shouldTryNextStepWhenFirstFails() {
            Interpreter interpreter = createInterpreter();
            conditionalActor.reset();

            // Two steps with same from-state "0"
            // First step: condition fails -> should try second
            // Second step: condition succeeds -> should transition
            MatrixCode code = createCode("test",
                createTransition("0", "path_a",
                    createAction("checker", "alwaysFalse", "")
                ),
                createTransition("0", "path_b",
                    createAction("checker", "alwaysTrue", "")
                )
            );
            setInterpreterCode(interpreter, code);

            ActionResult result = interpreter.execCode();

            assertTrue(result.isSuccess());
            assertEquals("path_b", interpreter.getCurrentState(),
                "Should transition to path_b when first step fails");
        }

        @Test
        @DisplayName("should take first matching path when condition succeeds")
        void shouldTakeFirstMatchingPath() {
            Interpreter interpreter = createInterpreter();
            conditionalActor.reset();
            conditionalActor.setValue("A");

            // Multiple steps with same from-state
            // matchValue("A") should succeed on first step
            MatrixCode code = createCode("test",
                createTransition("0", "path_a",
                    createAction("checker", "matchValue", "A"),
                    createAction("checker", "doAction", "action_a")
                ),
                createTransition("0", "path_b",
                    createAction("checker", "matchValue", "B"),
                    createAction("checker", "doAction", "action_b")
                ),
                createTransition("0", "path_c",
                    createAction("checker", "doAction", "action_c")
                )
            );
            setInterpreterCode(interpreter, code);

            ActionResult result = interpreter.execCode();

            assertTrue(result.isSuccess());
            assertEquals("path_a", interpreter.getCurrentState());

            List<String> executed = conditionalActor.getExecutedActions();
            assertTrue(executed.contains("doAction(action_a)"));
            assertFalse(executed.contains("doAction(action_b)"));
            assertFalse(executed.contains("doAction(action_c)"));
        }

        @Test
        @DisplayName("should fall through to default when all conditions fail")
        void shouldFallThroughToDefault() {
            Interpreter interpreter = createInterpreter();
            conditionalActor.reset();
            conditionalActor.setValue("X"); // Neither A nor B

            MatrixCode code = createCode("test",
                createTransition("0", "path_a",
                    createAction("checker", "matchValue", "A")
                ),
                createTransition("0", "path_b",
                    createAction("checker", "matchValue", "B")
                ),
                createTransition("0", "default",
                    createAction("checker", "doAction", "default_action")
                )
            );
            setInterpreterCode(interpreter, code);

            ActionResult result = interpreter.execCode();

            assertTrue(result.isSuccess());
            assertEquals("default", interpreter.getCurrentState(),
                "Should fall through to default path");

            List<String> executed = conditionalActor.getExecutedActions();
            assertTrue(executed.contains("doAction(default_action)"));
        }

        @Test
        @DisplayName("should handle self-transition loop with condition")
        void shouldHandleSelfTransitionLoop() {
            Interpreter interpreter = createInterpreter();
            conditionalActor.reset();

            // Loop: 0 -> 0 (while condition true), 0 -> end (when condition false)
            // Start with value "loop", change to "stop" after first iteration
            conditionalActor.setValue("loop");

            MatrixCode code = createCode("test",
                createTransition("0", "0",
                    createAction("checker", "matchValue", "loop"),
                    createAction("checker", "setValue", "stop")
                ),
                createTransition("0", "end",
                    createAction("checker", "doAction", "finished")
                )
            );
            setInterpreterCode(interpreter, code);

            // First execution: matches "loop", sets to "stop", stays at state "0"
            ActionResult result1 = interpreter.execCode();
            assertTrue(result1.isSuccess());
            assertEquals("0", interpreter.getCurrentState());

            // Second execution: doesn't match "loop", falls through to end
            ActionResult result2 = interpreter.execCode();
            assertTrue(result2.isSuccess());
            assertEquals("end", interpreter.getCurrentState());
        }
    }

    // ==================== State Transition Tests ====================

    @Nested
    @DisplayName("transitionTo()")
    class TransitionTests {

        @Test
        @DisplayName("should update current state")
        void shouldUpdateCurrentState() {
            Interpreter interpreter = createInterpreter();

            MatrixCode code = createCode("test",
                createTransition("0", "new_state"),
                createTransition("new_state", "end")
            );
            setInterpreterCode(interpreter, code);

            interpreter.transitionTo("new_state");

            assertEquals("new_state", interpreter.getCurrentState());
        }

        @Test
        @DisplayName("should find next matching row after transition")
        void shouldFindNextMatchingTransition() {
            Interpreter interpreter = createInterpreter();

            MatrixCode code = createCode("test",
                createTransition("0", "1"),
                createTransition("1", "2"),
                createTransition("2", "end")
            );
            setInterpreterCode(interpreter, code);

            interpreter.transitionTo("2");

            assertEquals("2", interpreter.getCurrentState());
            assertEquals(2, interpreter.getCurrentTransitionIndex(),
                "Should point to transition with from-state='2'");
        }
    }

    // ==================== Multi-Step Workflow Tests ====================

    @Nested
    @DisplayName("Multi-Step Workflow Execution")
    class MultiStepTests {

        @Test
        @DisplayName("should execute complete workflow with conditional branching")
        void shouldExecuteCompleteWorkflow() {
            Interpreter interpreter = createInterpreter();
            conditionalActor.reset();
            conditionalActor.setValue("B");

            MatrixCode code = createCode("test",
                // State 0: Check value, branch to A or B or default
                createTransition("0", "process_a",
                    createAction("checker", "matchValue", "A")
                ),
                createTransition("0", "process_b",
                    createAction("checker", "matchValue", "B")
                ),
                createTransition("0", "process_default",
                    createAction("checker", "alwaysTrue", "")
                ),
                // Processing states
                createTransition("process_a", "end",
                    createAction("checker", "doAction", "processed_A")
                ),
                createTransition("process_b", "end",
                    createAction("checker", "doAction", "processed_B")
                ),
                createTransition("process_default", "end",
                    createAction("checker", "doAction", "processed_default")
                )
            );
            setInterpreterCode(interpreter, code);

            // First step: branch based on value "B"
            ActionResult result1 = interpreter.execCode();
            assertTrue(result1.isSuccess());
            assertEquals("process_b", interpreter.getCurrentState());

            // Second step: process and go to end
            ActionResult result2 = interpreter.execCode();
            assertTrue(result2.isSuccess());
            assertEquals("end", interpreter.getCurrentState());

            // Verify correct processing path was taken
            List<String> executed = conditionalActor.getExecutedActions();
            assertTrue(executed.contains("doAction(processed_B)"));
            assertFalse(executed.contains("doAction(processed_A)"));
            assertFalse(executed.contains("doAction(processed_default)"));
        }
    }

    // ==================== Edge Case Tests ====================

    @Nested
    @DisplayName("Edge Cases")
    class EdgeCaseTests {

        @Test
        @DisplayName("should fail when no matching step found")
        void shouldFailWhenNoMatchingStep() {
            Interpreter interpreter = createInterpreter();

            // All steps fail their conditions
            MatrixCode code = createCode("test",
                createTransition("0", "1",
                    createAction("checker", "alwaysFalse", "")
                ),
                createTransition("0", "2",
                    createAction("checker", "alwaysFalse", "")
                )
            );
            setInterpreterCode(interpreter, code);

            ActionResult result = interpreter.execCode();

            assertFalse(result.isSuccess());
            assertEquals("No matching state transition", result.getResult());
        }

        @Test
        @DisplayName("should handle empty actions list")
        void shouldHandleEmptyActionsList() {
            Interpreter interpreter = createInterpreter();

            // Step with no actions should succeed
            Transition row = createTransition("0", "1");
            row.setActions(Arrays.asList());

            MatrixCode code = createCode("test", row);
            setInterpreterCode(interpreter, code);

            ActionResult result = interpreter.execCode();

            assertTrue(result.isSuccess());
            assertEquals("1", interpreter.getCurrentState());
        }
    }

    // ==================== runUntilEnd() Tests ====================

    @Nested
    @DisplayName("runUntilEnd()")
    class RunUntilEndTests {

        @Test
        @DisplayName("should complete workflow when reaching end state")
        void shouldCompleteWhenReachingEndState() {
            Interpreter interpreter = createInterpreter();
            conditionalActor.reset();

            MatrixCode code = createCode("test",
                createTransition("0", "1",
                    createAction("checker", "doAction", "step1")
                ),
                createTransition("1", "2",
                    createAction("checker", "doAction", "step2")
                ),
                createTransition("2", "end",
                    createAction("checker", "doAction", "step3")
                )
            );
            setInterpreterCode(interpreter, code);

            ActionResult result = interpreter.runUntilEnd();

            assertTrue(result.isSuccess());
            assertEquals("Workflow completed", result.getResult());
            assertEquals("end", interpreter.getCurrentState());

            // Verify all steps were executed
            List<String> executed = conditionalActor.getExecutedActions();
            assertEquals(3, executed.size());
            assertTrue(executed.contains("doAction(step1)"));
            assertTrue(executed.contains("doAction(step2)"));
            assertTrue(executed.contains("doAction(step3)"));
        }

        @Test
        @DisplayName("should fail when action returns false")
        void shouldFailWhenActionReturnsFalse() {
            Interpreter interpreter = createInterpreter();
            conditionalActor.reset();

            MatrixCode code = createCode("test",
                createTransition("0", "1",
                    createAction("checker", "doAction", "step1")
                ),
                createTransition("1", "2",
                    createAction("checker", "alwaysFalse", "") // This will fail
                ),
                createTransition("2", "end",
                    createAction("checker", "doAction", "step3")
                )
            );
            setInterpreterCode(interpreter, code);

            ActionResult result = interpreter.runUntilEnd();

            assertFalse(result.isSuccess());
            assertTrue(result.getResult().contains("Workflow failed"));
            assertEquals("1", interpreter.getCurrentState()); // Stopped at state 1

            // Step 3 should not have been executed
            List<String> executed = conditionalActor.getExecutedActions();
            assertFalse(executed.contains("doAction(step3)"));
        }

        @Test
        @DisplayName("should fail when no code is loaded")
        void shouldFailWhenNoCodeLoaded() {
            Interpreter interpreter = createInterpreter();
            // Don't set any code

            ActionResult result = interpreter.runUntilEnd();

            assertFalse(result.isSuccess());
            assertEquals("No code loaded", result.getResult());
        }

        @Test
        @DisplayName("should fail when maximum iterations exceeded")
        void shouldFailWhenMaxIterationsExceeded() {
            Interpreter interpreter = createInterpreter();
            conditionalActor.reset();

            // Infinite loop: 0 -> 0 (no end state)
            MatrixCode code = createCode("test",
                createTransition("0", "0",
                    createAction("checker", "alwaysTrue", "")
                )
            );
            setInterpreterCode(interpreter, code);

            ActionResult result = interpreter.runUntilEnd(5); // Max 5 iterations

            assertFalse(result.isSuccess());
            assertTrue(result.getResult().contains("Maximum iterations"));
            assertTrue(result.getResult().contains("5"));
        }

        @Test
        @DisplayName("should handle conditional branching to end state")
        void shouldHandleConditionalBranchingToEnd() {
            Interpreter interpreter = createInterpreter();
            conditionalActor.reset();
            conditionalActor.setValue("B");

            MatrixCode code = createCode("test",
                // State 0: branch based on value
                createTransition("0", "process_a",
                    createAction("checker", "matchValue", "A")
                ),
                createTransition("0", "process_b",
                    createAction("checker", "matchValue", "B")
                ),
                // Processing states lead to end
                createTransition("process_a", "end",
                    createAction("checker", "doAction", "done_a")
                ),
                createTransition("process_b", "end",
                    createAction("checker", "doAction", "done_b")
                )
            );
            setInterpreterCode(interpreter, code);

            ActionResult result = interpreter.runUntilEnd();

            assertTrue(result.isSuccess());
            assertEquals("end", interpreter.getCurrentState());

            // Only path B should have been executed
            List<String> executed = conditionalActor.getExecutedActions();
            assertTrue(executed.contains("doAction(done_b)"));
            assertFalse(executed.contains("doAction(done_a)"));
        }

        @Test
        @DisplayName("should handle loop with exit condition to end state")
        void shouldHandleLoopWithExitCondition() {
            Interpreter interpreter = createInterpreter();
            conditionalActor.reset();

            // Counter: starts at 0, increments each loop, exits at 3
            final int[] counter = {0};

            // Create a custom actor for counting
            IIActorRef<Object> counterActor = new IIActorRef<Object>("counter", new Object(), system) {
                @Override
                public ActionResult callByActionName(String actionName, String args) {
                    if ("increment".equals(actionName)) {
                        counter[0]++;
                        return new ActionResult(true, "count=" + counter[0]);
                    } else if ("lessThan".equals(actionName)) {
                        String arg = getFirstArg(args);
                        int limit = Integer.parseInt(arg);
                        boolean result = counter[0] < limit;
                        return new ActionResult(result, "lessThan(" + arg + ")=" + result);
                    }
                    return new ActionResult(false, "Unknown");
                }
            };
            system.addIIActor(counterActor);

            MatrixCode code = createCode("test",
                // Loop: increment and check if less than 3
                createTransition("0", "0",
                    createAction("counter", "lessThan", "3"),
                    createAction("counter", "increment", "")
                ),
                // Exit to end when condition fails
                createTransition("0", "end",
                    createAction("checker", "doAction", "finished")
                )
            );
            setInterpreterCode(interpreter, code);

            ActionResult result = interpreter.runUntilEnd();

            assertTrue(result.isSuccess());
            assertEquals("end", interpreter.getCurrentState());
            assertEquals(3, counter[0]); // Should have incremented 3 times
        }

        @Test
        @DisplayName("should immediately complete if initial state is end")
        void shouldImmediatelyCompleteIfInitialStateIsEnd() {
            Interpreter interpreter = createInterpreter();

            // Start at state "end" by forcing current state
            try {
                java.lang.reflect.Field stateField = Interpreter.class.getDeclaredField("currentState");
                stateField.setAccessible(true);
                stateField.set(interpreter, "end");
            } catch (Exception e) {
                fail("Failed to set state: " + e.getMessage());
            }

            MatrixCode code = createCode("test",
                createTransition("0", "1",
                    createAction("checker", "doAction", "should-not-run")
                )
            );
            setInterpreterCode(interpreter, code);

            ActionResult result = interpreter.runUntilEnd();

            assertTrue(result.isSuccess());
            assertEquals("Workflow completed", result.getResult());

            // No actions should have been executed
            conditionalActor.reset();
            List<String> executed = conditionalActor.getExecutedActions();
            assertTrue(executed.isEmpty());
        }
    }
}
