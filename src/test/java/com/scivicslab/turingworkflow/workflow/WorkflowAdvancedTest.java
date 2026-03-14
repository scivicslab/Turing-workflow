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

import org.json.JSONArray;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.scivicslab.pojoactor.core.ActionResult;
import com.scivicslab.pojoactor.core.CallableByActionName;
import com.scivicslab.turingworkflow.plugin.MathPlugin;
import com.scivicslab.turingworkflow.workflow.IIActorRef;
import com.scivicslab.turingworkflow.workflow.IIActorSystem;
import com.scivicslab.turingworkflow.workflow.Interpreter;
import com.scivicslab.turingworkflow.workflow.ReusableSubWorkflowCaller;
import com.scivicslab.turingworkflow.workflow.SubWorkflowCaller;

/**
 * Advanced workflow pattern tests.
 *
 * <p>This test suite demonstrates advanced workflow patterns using the basic
 * Interpreter functionality, including parallel execution, conditional branching,
 * and sub-workflow patterns.</p>
 *
 * @author devteam@scivicslab.com
 * @version 1.0.0
 */
@DisplayName("Advanced Workflow Patterns Specification by Example")
public class WorkflowAdvancedTest {

    private IIActorSystem system;

    @BeforeEach
    public void setUp() {
        system = new IIActorSystem("workflow-advanced-test");
    }

    @AfterEach
    public void tearDown() {
        if (system != null) {
            system.terminate();
        }
    }

    /**
     * Test actor that records execution order and timing.
     */
    private static class ParallelActor implements CallableByActionName {
        private final List<String> executionOrder = new ArrayList<>();
        private final String actorId;

        public ParallelActor(String actorId) {
            this.actorId = actorId;
        }

        @Override
        public ActionResult callByActionName(String actionName, String args) {
            if (actionName.equals("execute")) {
                String entry = actorId + ":" + args;
                synchronized (executionOrder) {
                    executionOrder.add(entry);
                }

                // Simulate some work
                try {
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }

                return new ActionResult(true, "Executed: " + entry);
            }
            return new ActionResult(false, "Unknown action");
        }

        public List<String> getExecutionOrder() {
            synchronized (executionOrder) {
                return new ArrayList<>(executionOrder);
            }
        }
    }

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

    /**
     * Test actor that makes conditional decisions.
     */
    private static class DecisionActor implements CallableByActionName {
        private int value = 0;
        private String lastDecision = "";

        @Override
        public ActionResult callByActionName(String actionName, String args) {
            switch (actionName) {
                case "setValue":
                    value = Integer.parseInt(getFirstArg(args));
                    return new ActionResult(true, "Value set to: " + value);

                case "checkValue":
                    // Return different next state based on value
                    if (value > 10) {
                        lastDecision = "high";
                        return new ActionResult(true, "high");
                    } else {
                        lastDecision = "low";
                        return new ActionResult(true, "low");
                    }

                case "processHigh":
                    return new ActionResult(true, "Processed as high value");

                case "processLow":
                    return new ActionResult(true, "Processed as low value");

                default:
                    return new ActionResult(false, "Unknown action");
            }
        }

        public String getLastDecision() {
            return lastDecision;
        }

        public int getValue() {
            return value;
        }
    }

    /**
     * IIActorRef wrapper for MathPlugin used in sub-workflow tests.
     */
    private static class MathIIActorRef extends IIActorRef<MathPlugin> {

        public MathIIActorRef(String actorName, MathPlugin object, IIActorSystem system) {
            super(actorName, object, system);
        }

        @Override
        public ActionResult callByActionName(String actionName, String args) {
            return this.object.callByActionName(actionName, args);
        }
    }

    /**
     * Test actor that manages sub-workflow execution.
     *
     * @deprecated Use {@link SubWorkflowCaller} from the library instead.
     *             This class is kept for reference and will be removed in future versions.
     */
    @Deprecated
    private static class SubWorkflowCoordinator implements CallableByActionName {
        private final IIActorSystem system;
        private int subWorkflowExecutions = 0;

        public SubWorkflowCoordinator(IIActorSystem system) {
            this.system = system;
        }

        @Override
        public ActionResult callByActionName(String actionName, String args) {
            if (actionName.equals("executeSubWorkflow")) {
                // Execute sub-workflow
                Interpreter subInterpreter = new Interpreter.Builder()
                    .loggerName("sub-workflow")
                    .team(system)
                    .build();

                String workflowFile = getFirstArg(args);
                InputStream yamlInput = getClass().getResourceAsStream("/workflows/" + workflowFile);
                if (yamlInput != null) {
                    subInterpreter.readYaml(yamlInput);

                    // Execute sub-workflow until "end" state
                    subInterpreter.runUntilEnd();

                    subWorkflowExecutions++;
                    return new ActionResult(true, "Sub-workflow executed: " + args);
                }

                return new ActionResult(false, "Sub-workflow not found: " + args);
            }
            return new ActionResult(false, "Unknown action");
        }

        public int getSubWorkflowExecutions() {
            return subWorkflowExecutions;
        }
    }

    /**
     * Example 1: Parallel execution pattern.
     *
     * Multiple actors execute concurrently when messages are sent to them
     * from a single workflow step.
     */
    @Test
    @DisplayName("Should demonstrate parallel execution pattern")
    public void testParallelExecution() throws InterruptedException {
        // Setup multiple actors
        ParallelActor actor1 = new ParallelActor("actor1");
        ParallelActor actor2 = new ParallelActor("actor2");
        ParallelActor actor3 = new ParallelActor("actor3");

        IIActorRef<ParallelActor> ref1 = new IIActorRef<>("actor1", actor1, system);
        IIActorRef<ParallelActor> ref2 = new IIActorRef<>("actor2", actor2, system);
        IIActorRef<ParallelActor> ref3 = new IIActorRef<>("actor3", actor3, system);

        system.addIIActor(ref1);
        system.addIIActor(ref2);
        system.addIIActor(ref3);

        // Load workflow with parallel actions
        Interpreter interpreter = new Interpreter.Builder()
            .loggerName("parallel-test")
            .team(system)
            .build();

        InputStream yamlInput = getClass().getResourceAsStream("/workflows/parallel-execution.yaml");
        assertNotNull(yamlInput, "Parallel execution YAML should exist");

        interpreter.readYaml(yamlInput);

        // Execute step - all three actors receive messages
        ActionResult result = interpreter.execCode();
        assertTrue(result.isSuccess());

        // Wait for parallel execution
        Thread.sleep(200);

        // All actors should have executed
        assertEquals(1, actor1.getExecutionOrder().size());
        assertEquals(1, actor2.getExecutionOrder().size());
        assertEquals(1, actor3.getExecutionOrder().size());
    }

    /**
     * Example 2: Conditional branching pattern.
     *
     * Workflow branches to different states based on actor's decision.
     */
    @Test
    @DisplayName("Should demonstrate conditional branching pattern")
    public void testConditionalBranching() {
        DecisionActor decisionActor = new DecisionActor();
        IIActorRef<DecisionActor> decisionRef = new IIActorRef<>("decision", decisionActor, system);
        system.addIIActor(decisionRef);

        // Test high value branch
        Interpreter interpreter1 = new Interpreter.Builder()
            .loggerName("conditional-high")
            .team(system)
            .build();

        InputStream yamlInput1 = getClass().getResourceAsStream("/workflows/conditional-branch.yaml");
        assertNotNull(yamlInput1, "Conditional branch YAML should exist");

        interpreter1.readYaml(yamlInput1);

        // Set high value
        ActionResult result1 = interpreter1.execCode(); // state 0->1: setValue 15
        assertTrue(result1.isSuccess());

        // Check value (should branch to high)
        ActionResult result2 = interpreter1.execCode(); // state 1->2: checkValue
        assertTrue(result2.isSuccess());

        assertEquals("high", decisionActor.getLastDecision());
        assertEquals(15, decisionActor.getValue());
    }

    /**
     * Example 3: Sub-workflow execution pattern.
     *
     * Main workflow delegates part of execution to sub-workflow.
     */
    @Test
    @DisplayName("Should demonstrate sub-workflow pattern")
    public void testSubWorkflow() {
        // Setup sub-workflow coordinator
        SubWorkflowCoordinator coordinator = new SubWorkflowCoordinator(system);
        IIActorRef<SubWorkflowCoordinator> coordinatorRef =
            new IIActorRef<>("coordinator", coordinator, system);
        system.addIIActor(coordinatorRef);

        // Setup actors for sub-workflow
        DecisionActor processor = new DecisionActor();
        IIActorRef<DecisionActor> processorRef = new IIActorRef<>("processor", processor, system);
        system.addIIActor(processorRef);

        // Load main workflow
        Interpreter mainInterpreter = new Interpreter.Builder()
            .loggerName("main-workflow")
            .team(system)
            .build();

        InputStream yamlInput = getClass().getResourceAsStream("/workflows/main-with-subworkflow.yaml");
        assertNotNull(yamlInput, "Main workflow YAML should exist");

        mainInterpreter.readYaml(yamlInput);

        // Execute main workflow which calls sub-workflow
        ActionResult result = mainInterpreter.execCode();
        assertTrue(result.isSuccess());

        // Sub-workflow should have been executed
        assertEquals(1, coordinator.getSubWorkflowExecutions());
    }

    /**
     * Example 3b: Sub-workflow pattern using library SubWorkflowCaller.
     *
     * Demonstrates using the provided SubWorkflowCaller class from the library.
     */
    @Test
    @DisplayName("Should demonstrate sub-workflow pattern with SubWorkflowCaller")
    public void testSubWorkflowWithLibraryCaller() {
        // Use the library-provided SubWorkflowCaller
        SubWorkflowCaller caller = new SubWorkflowCaller(system);
        IIActorRef<SubWorkflowCaller> callerRef =
            new IIActorRef<>("caller", caller, system);
        system.addIIActor(callerRef);

        // Setup actors for sub-workflow (simple-math.yaml requires "math" actor)
        MathPlugin mathPlugin = new MathPlugin();
        MathIIActorRef mathRef = new MathIIActorRef("math", mathPlugin, system);
        system.addIIActor(mathRef);

        // Load main workflow (using caller with "call" action)
        Interpreter mainInterpreter = new Interpreter.Builder()
            .loggerName("main-workflow-with-caller")
            .team(system)
            .build();

        InputStream yamlInput = getClass().getResourceAsStream("/workflows/main-with-caller.yaml");
        assertNotNull(yamlInput, "Main workflow with caller YAML should exist");

        mainInterpreter.readYaml(yamlInput);

        // Execute main workflow which calls sub-workflow
        ActionResult result = mainInterpreter.execCode();
        assertTrue(result.isSuccess());

        // Sub-workflow should have been called
        assertEquals(1, caller.getCallCount());
    }

    /**
     * Example 3c: Sub-workflow pattern using ReusableSubWorkflowCaller.
     *
     * Demonstrates using the ReusableSubWorkflowCaller which reuses a single
     * Interpreter instance for better performance in high-frequency scenarios.
     */
    @Test
    @DisplayName("Should demonstrate sub-workflow pattern with ReusableSubWorkflowCaller")
    public void testSubWorkflowWithReusableCaller() {
        // Use the library-provided ReusableSubWorkflowCaller
        ReusableSubWorkflowCaller caller = new ReusableSubWorkflowCaller(system);
        IIActorRef<ReusableSubWorkflowCaller> callerRef =
            new IIActorRef<>("caller", caller, system);
        system.addIIActor(callerRef);

        // Setup actors for sub-workflow (simple-math.yaml requires "math" actor)
        MathPlugin mathPlugin = new MathPlugin();
        MathIIActorRef mathRef = new MathIIActorRef("math", mathPlugin, system);
        system.addIIActor(mathRef);

        // Load main workflow (using caller with "call" action)
        Interpreter mainInterpreter = new Interpreter.Builder()
            .loggerName("main-workflow-with-reusable-caller")
            .team(system)
            .build();

        InputStream yamlInput = getClass().getResourceAsStream("/workflows/main-with-caller.yaml");
        assertNotNull(yamlInput, "Main workflow with caller YAML should exist");

        mainInterpreter.readYaml(yamlInput);

        // Execute main workflow which calls sub-workflow twice
        ActionResult result1 = mainInterpreter.execCode(); // First call
        assertTrue(result1.isSuccess());

        ActionResult result2 = mainInterpreter.execCode(); // Second call
        assertTrue(result2.isSuccess());

        // Sub-workflow should have been called twice with the same Interpreter
        assertEquals(2, caller.getCallCount());
    }

    /**
     * Example 3d: Verify Interpreter.reset() functionality.
     *
     * Tests that the reset() method properly clears state for reuse.
     */
    @Test
    @DisplayName("Should reset Interpreter state correctly")
    public void testInterpreterReset() {
        Interpreter interpreter = new Interpreter.Builder()
            .loggerName("reset-test")
            .team(system)
            .build();

        // Setup test actor
        DecisionActor actor = new DecisionActor();
        IIActorRef<DecisionActor> actorRef = new IIActorRef<>("decision", actor, system);
        system.addIIActor(actorRef);

        // First execution
        InputStream yamlInput1 = getClass().getResourceAsStream("/workflows/conditional-branch.yaml");
        interpreter.readYaml(yamlInput1);

        ActionResult result1 = interpreter.execCode();
        assertTrue(result1.isSuccess());
        assertFalse(result1.getResult().contains("State: 0")); // Should have moved from state 0

        // Reset
        interpreter.reset();

        // Second execution with same workflow
        InputStream yamlInput2 = getClass().getResourceAsStream("/workflows/conditional-branch.yaml");
        interpreter.readYaml(yamlInput2);

        ActionResult result2 = interpreter.execCode();
        assertTrue(result2.isSuccess());
        // Should start from state 0 again after reset
        assertTrue(result2.getResult().contains("State: 1"));
    }

    /**
     * Example 4: Loop pattern in workflow.
     *
     * Workflow can loop back to previous state.
     */
    @Test
    @DisplayName("Should demonstrate loop pattern")
    public void testLoopPattern() {
        AtomicInteger counter = new AtomicInteger(0);

        CallableByActionName loopActor = new CallableByActionName() {
            @Override
            public ActionResult callByActionName(String actionName, String args) {
                if (actionName.equals("increment")) {
                    int newValue = counter.incrementAndGet();
                    return new ActionResult(true, "Count: " + newValue);
                } else if (actionName.equals("checkLimit")) {
                    boolean shouldContinue = counter.get() < 5;
                    // Return false when limit reached, so workflow tries alternative path
                    return new ActionResult(shouldContinue, shouldContinue ? "continue" : "done");
                } else if (actionName.equals("finish")) {
                    return new ActionResult(true, "finished");
                }
                return new ActionResult(false, "Unknown action");
            }
        };

        IIActorRef<CallableByActionName> loopRef =
            new IIActorRef<>("loopActor", loopActor, system);
        system.addIIActor(loopRef);

        Interpreter interpreter = new Interpreter.Builder()
            .loggerName("loop-test")
            .team(system)
            .build();

        InputStream yamlInput = getClass().getResourceAsStream("/workflows/loop-pattern.yaml");
        assertNotNull(yamlInput, "Loop pattern YAML should exist");

        interpreter.readYaml(yamlInput);

        // Execute workflow until "end" state (max 10 iterations)
        ActionResult result = interpreter.runUntilEnd(10);
        assertTrue(result.isSuccess(), "Workflow should complete successfully");

        // Counter should have reached the limit
        assertEquals(5, counter.get());
    }

    /**
     * Example 5: Multi-state loop pattern.
     *
     * Loop cycles through multiple states, not just back to a single state.
     */
    @Test
    @DisplayName("Should demonstrate multi-state loop pattern")
    public void testMultiStateLoop() {
        List<String> stateHistory = new ArrayList<>();
        AtomicInteger cycleCount = new AtomicInteger(0);

        CallableByActionName cycleActor = new CallableByActionName() {
            @Override
            public ActionResult callByActionName(String actionName, String args) {
                synchronized (stateHistory) {
                    stateHistory.add(actionName + ":" + args);
                }

                if (actionName.equals("checkContinue")) {
                    int cycles = cycleCount.incrementAndGet();
                    boolean shouldContinue = cycles < 3;
                    // Return false when done, so workflow tries alternative path to end
                    return new ActionResult(shouldContinue, shouldContinue ? "continue" : "done");
                }

                return new ActionResult(true, "Executed: " + actionName);
            }
        };

        IIActorRef<CallableByActionName> cycleRef =
            new IIActorRef<>("cycleActor", cycleActor, system);
        system.addIIActor(cycleRef);

        Interpreter interpreter = new Interpreter.Builder()
            .loggerName("multi-state-loop-test")
            .team(system)
            .build();

        InputStream yamlInput = getClass().getResourceAsStream("/workflows/multi-state-loop.yaml");
        assertNotNull(yamlInput, "Multi-state loop YAML should exist");

        interpreter.readYaml(yamlInput);

        // Execute workflow until "end" state (max 20 iterations)
        ActionResult result = interpreter.runUntilEnd(20);
        assertTrue(result.isSuccess(), "Workflow should complete successfully");

        // Should have cycled through states multiple times
        assertTrue(stateHistory.size() >= 12,
            "Should have executed multiple states in loop cycles, but was: " + stateHistory.size());

        // Verify we went through phase1, phase2, phase3 multiple times
        long phase1Count = stateHistory.stream().filter(s -> s.contains("phase1")).count();
        long phase2Count = stateHistory.stream().filter(s -> s.contains("phase2")).count();
        long phase3Count = stateHistory.stream().filter(s -> s.contains("phase3")).count();

        assertTrue(phase1Count >= 3, "Phase1 should execute multiple times");
        assertTrue(phase2Count >= 3, "Phase2 should execute multiple times");
        assertTrue(phase3Count >= 3, "Phase3 should execute multiple times");

        // checkContinue is called once per cycle, plus one final time to return "done"
        assertTrue(cycleCount.get() >= 3, "Should have completed at least 3 cycles, was: " + cycleCount.get());
    }

    /**
     * Example 6: Error handling pattern.
     *
     * Workflow handles errors from actors gracefully.
     */
    @Test
    @DisplayName("Should demonstrate error handling pattern")
    public void testErrorHandling() {
        CallableByActionName errorProneActor = new CallableByActionName() {
            private int attempts = 0;

            @Override
            public ActionResult callByActionName(String actionName, String args) {
                if (actionName.equals("riskyOperation")) {
                    attempts++;
                    if (attempts < 3) {
                        return new ActionResult(false, "Operation failed (attempt " + attempts + ")");
                    }
                    return new ActionResult(true, "Operation succeeded after " + attempts + " attempts");
                } else if (actionName.equals("handleError")) {
                    return new ActionResult(true, "Error handled");
                }
                return new ActionResult(false, "Unknown action");
            }
        };

        IIActorRef<CallableByActionName> errorRef =
            new IIActorRef<>("errorActor", errorProneActor, system);
        system.addIIActor(errorRef);

        Interpreter interpreter = new Interpreter.Builder()
            .loggerName("error-test")
            .team(system)
            .build();

        InputStream yamlInput = getClass().getResourceAsStream("/workflows/error-handling.yaml");
        assertNotNull(yamlInput, "Error handling YAML should exist");

        interpreter.readYaml(yamlInput);

        // Execute workflow with error handling
        ActionResult result = interpreter.execCode();
        assertTrue(result.isSuccess());
    }

    /**
     * IIActorRef wrapper for test actors.
     */
    private static class IIActorRef<T extends CallableByActionName>
            extends com.scivicslab.turingworkflow.workflow.IIActorRef<T> {

        public IIActorRef(String actorName, T object, IIActorSystem system) {
            super(actorName, object, system);
        }

        @Override
        public ActionResult callByActionName(String actionName, String args) {
            return this.object.callByActionName(actionName, args);
        }
    }
}
