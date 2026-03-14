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
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.scivicslab.pojoactor.core.ActionResult;
import com.scivicslab.turingworkflow.plugin.MathPlugin;

/**
 * Tests for ExecutionMode functionality in workflow actions.
 *
 * <p>Verifies that actions are executed on the correct thread pool
 * based on the ExecutionMode setting (POOL or DIRECT).</p>
 *
 * @author devteam@scivicslab.com
 * @since 2.8.0
 */
@DisplayName("ExecutionMode Specification by Example")
public class ExecutionModeTest {

    private IIActorSystem system;
    private MathPlugin mathPlugin;
    private ThreadTrackingMathIIAR mathActor;

    @BeforeEach
    public void setUp() {
        system = new IIActorSystem("execution-mode-test-system");
        mathPlugin = new MathPlugin();
        mathActor = new ThreadTrackingMathIIAR("math", mathPlugin, system);
        system.addIIActor(mathActor);
    }

    @AfterEach
    public void tearDown() {
        system.terminate();
    }

    /**
     * Test IIActorRef that tracks which thread executed the action.
     */
    private static class ThreadTrackingMathIIAR extends IIActorRef<MathPlugin> {
        private final AtomicReference<String> lastExecutionThread = new AtomicReference<>();

        public ThreadTrackingMathIIAR(String actorName, MathPlugin object, IIActorSystem system) {
            super(actorName, object, system);
        }

        @Override
        public ActionResult callByActionName(String actionName, String args) {
            lastExecutionThread.set(Thread.currentThread().getName());
            return this.object.callByActionName(actionName, args);
        }

        public String getLastExecutionThread() {
            return lastExecutionThread.get();
        }
    }

    /**
     * Example 1: Default ExecutionMode should be POOL.
     */
    @Test
    @DisplayName("Should default to POOL execution mode when not specified")
    public void testDefaultExecutionModeIsPOOL() {
        Action action = new Action("math", "add", "10,5");

        assertEquals(ExecutionMode.POOL, action.getExecution(),
            "Default execution mode should be POOL");
    }

    /**
     * Example 2: ExecutionMode.fromString() parsing.
     */
    @Test
    @DisplayName("Should parse ExecutionMode from string correctly")
    public void testExecutionModeFromString() {
        assertEquals(ExecutionMode.POOL, ExecutionMode.fromString(null));
        assertEquals(ExecutionMode.POOL, ExecutionMode.fromString(""));
        assertEquals(ExecutionMode.POOL, ExecutionMode.fromString("pool"));
        assertEquals(ExecutionMode.POOL, ExecutionMode.fromString("POOL"));
        assertEquals(ExecutionMode.DIRECT, ExecutionMode.fromString("direct"));
        assertEquals(ExecutionMode.DIRECT, ExecutionMode.fromString("DIRECT"));
        assertEquals(ExecutionMode.POOL, ExecutionMode.fromString("unknown"));
    }

    /**
     * Example 3: YAML should parse execution field correctly.
     */
    @Test
    @DisplayName("Should parse execution field from YAML")
    public void testParseExecutionFieldFromYaml() {
        Interpreter interpreter = new Interpreter.Builder()
                .loggerName("test-interpreter")
                .team(system)
                .build();

        InputStream yamlInput = getClass().getResourceAsStream("/workflows/execution-mode-test.yaml");
        assertNotNull(yamlInput, "YAML resource should exist");

        interpreter.readYaml(yamlInput);

        MatrixCode code = interpreter.getCode();
        assertNotNull(code);

        // Step 0: no execution specified (should default to POOL)
        Action action0 = code.getSteps().get(0).getActions().get(0);
        assertEquals(ExecutionMode.POOL, action0.getExecution(),
            "Action without execution field should default to POOL");

        // Step 1: execution: pool
        Action action1 = code.getSteps().get(1).getActions().get(0);
        assertEquals(ExecutionMode.POOL, action1.getExecution(),
            "Action with execution: pool should be POOL");

        // Step 2: execution: direct
        Action action2 = code.getSteps().get(2).getActions().get(0);
        assertEquals(ExecutionMode.DIRECT, action2.getExecution(),
            "Action with execution: direct should be DIRECT");
    }

    /**
     * Example 4: Actions with different ExecutionModes should all execute successfully.
     */
    @Test
    @DisplayName("Should execute actions with different ExecutionModes successfully")
    public void testExecuteActionsWithDifferentModes() {
        Interpreter interpreter = new Interpreter.Builder()
                .loggerName("test-interpreter")
                .team(system)
                .build();

        InputStream yamlInput = getClass().getResourceAsStream("/workflows/execution-mode-test.yaml");
        interpreter.readYaml(yamlInput);

        // Execute all steps
        ActionResult result1 = interpreter.execCode();
        assertTrue(result1.isSuccess(), "Step 1 should succeed");
        assertEquals(15, mathPlugin.getLastResult(), "add(10,5) should equal 15");

        ActionResult result2 = interpreter.execCode();
        assertTrue(result2.isSuccess(), "Step 2 should succeed");
        assertEquals(12, mathPlugin.getLastResult(), "multiply(3,4) should equal 12");

        ActionResult result3 = interpreter.execCode();
        assertTrue(result3.isSuccess(), "Step 3 should succeed");
    }

    /**
     * Example 5: POOL mode should execute on WorkStealingPool thread.
     */
    @Test
    @DisplayName("POOL mode should execute on WorkStealingPool thread")
    public void testPOOLModeExecutesOnWorkerThread() {
        // Verify actor is registered
        assertTrue(system.hasIIActor("math"), "math actor should be registered");

        Interpreter interpreter = new Interpreter.Builder()
                .loggerName("test-interpreter")
                .team(system)
                .build();

        // Create a workflow with POOL mode programmatically
        MatrixCode code = new MatrixCode();
        code.setName("pool-test");

        Transition row = new Transition();
        row.setStates(Arrays.asList("0", "end"));

        Action action = new Action("math", "add", Arrays.asList("1", "2"));
        action.setExecution(ExecutionMode.POOL);
        row.setActions(Arrays.asList(action));

        code.setSteps(Arrays.asList(row));
        interpreter.setCode(code);

        ActionResult result = interpreter.execCode();
        assertTrue(result.isSuccess(), "Action should succeed, but got: " + result.getResult());

        String threadName = mathActor.getLastExecutionThread();
        assertNotNull(threadName, "Execution thread should be recorded");
        // WorkStealingPool uses threads named "pool-X-thread-Y"
        assertTrue(threadName.contains("pool") || threadName.contains("ForkJoinPool"),
            "POOL mode should execute on pool thread, but was: " + threadName);
    }

    /**
     * Example 6: DIRECT mode should execute on workflow thread.
     */
    @Test
    @DisplayName("DIRECT mode should execute on caller thread")
    public void testDIRECTModeExecutesOnCallerThread() {
        Interpreter interpreter = new Interpreter.Builder()
                .loggerName("test-interpreter")
                .team(system)
                .build();

        // Create a workflow with DIRECT mode programmatically
        MatrixCode code = new MatrixCode();
        code.setName("direct-test");

        Transition row = new Transition();
        row.setStates(Arrays.asList("0", "end"));

        Action action = new Action("math", "add", Arrays.asList("1", "2"));
        action.setExecution(ExecutionMode.DIRECT);
        row.setActions(Arrays.asList(action));

        code.setSteps(Arrays.asList(row));
        interpreter.setCode(code);

        String callerThread = Thread.currentThread().getName();
        ActionResult result = interpreter.execCode();
        assertTrue(result.isSuccess(), "Action should succeed, but got: " + result.getResult());

        String executionThread = mathActor.getLastExecutionThread();
        assertNotNull(executionThread, "Execution thread should be recorded");
        assertEquals(callerThread, executionThread,
            "DIRECT mode should execute on caller thread");
    }
}
