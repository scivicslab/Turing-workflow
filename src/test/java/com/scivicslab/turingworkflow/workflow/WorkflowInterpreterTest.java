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

import java.io.IOException;
import java.io.InputStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.scivicslab.pojoactor.core.ActionResult;
import com.scivicslab.turingworkflow.plugin.MathPlugin;
import com.scivicslab.turingworkflow.workflow.IIActorRef;
import com.scivicslab.turingworkflow.workflow.IIActorSystem;
import com.scivicslab.turingworkflow.workflow.Interpreter;
import com.scivicslab.turingworkflow.workflow.MatrixCode;
import com.scivicslab.turingworkflow.workflow.Transition;
import com.scivicslab.turingworkflow.workflow.Action;

/**
 * Comprehensive tests for Workflow Interpreter functionality.
 *
 * <p>This test suite verifies the workflow execution engine, which reads
 * YAML/JSON/XML workflow definitions and executes them using CallableByActionName.</p>
 *
 * @author devteam@scivicslab.com
 * @version 2.5.0
 */
@DisplayName("Workflow Interpreter Specification by Example")
public class WorkflowInterpreterTest {

    private IIActorSystem system;
    private MathPlugin mathPlugin;

    @BeforeEach
    public void setUp() {
        system = new IIActorSystem("workflow-test-system");
        mathPlugin = new MathPlugin();

        // Register math actor
        TestMathIIAR mathActor = new TestMathIIAR("math", mathPlugin, system);
        system.addIIActor(mathActor);
    }

    /**
     * Test implementation of IIActorRef for MathPlugin.
     */
    private static class TestMathIIAR extends IIActorRef<MathPlugin> {

        public TestMathIIAR(String actorName, MathPlugin object, IIActorSystem system) {
            super(actorName, object, system);
        }

        @Override
        public ActionResult callByActionName(String actionName, String args) {
            return this.object.callByActionName(actionName, args);
        }
    }

    /**
     * Example 1: Load YAML workflow definition.
     */
    @Test
    @DisplayName("Should load workflow from YAML")
    public void testLoadWorkflowFromYaml() {
        Interpreter interpreter = new Interpreter.Builder()
                .loggerName("test-interpreter")
                .team(system)
                .build();

        InputStream yamlInput = getClass().getResourceAsStream("/workflows/simple-math.yaml");
        assertNotNull(yamlInput, "YAML resource should exist");

        interpreter.readYaml(yamlInput);

        MatrixCode code = interpreter.getCode();
        assertNotNull(code, "Code should be loaded");
        assertEquals("simple-math-workflow", code.getName());
        assertEquals(3, code.getSteps().size(), "Should have 3 steps");
    }

    /**
     * Example 2: Execute single-step workflow.
     */
    @Test
    @DisplayName("Should execute single-step workflow")
    public void testExecuteSingleStepWorkflow() {
        Interpreter interpreter = new Interpreter.Builder()
                .loggerName("test-interpreter")
                .team(system)
                .build();

        InputStream yamlInput = getClass().getResourceAsStream("/workflows/simple-math.yaml");
        interpreter.readYaml(yamlInput);

        // Execute first step: state 0 -> 1, action: add 10,5
        ActionResult result = interpreter.execCode();

        assertTrue(result.isSuccess(), "Step should succeed");
        assertTrue(result.getResult().contains("State: 1"), "Should transition to state 1");

        // Verify the action was executed
        assertEquals(15, mathPlugin.getLastResult(), "Math operation should have been executed");
    }

    /**
     * Example 3: Execute multi-step workflow.
     */
    @Test
    @DisplayName("Should execute multi-step workflow with state transitions")
    public void testExecuteMultiStepWorkflow() {
        Interpreter interpreter = new Interpreter.Builder()
                .loggerName("test-interpreter")
                .team(system)
                .build();

        InputStream yamlInput = getClass().getResourceAsStream("/workflows/simple-math.yaml");
        interpreter.readYaml(yamlInput);

        // Step 1: 0 -> 1, add 10,5 (result: 15)
        ActionResult result1 = interpreter.execCode();
        assertTrue(result1.isSuccess());
        assertEquals(15, mathPlugin.getLastResult());

        // Step 2: 1 -> 2, multiply 3,4 (result: 12)
        ActionResult result2 = interpreter.execCode();
        assertTrue(result2.isSuccess());
        assertEquals(12, mathPlugin.getLastResult());

        // Step 3: 2 -> end, getLastResult (result: 12)
        ActionResult result3 = interpreter.execCode();
        assertTrue(result3.isSuccess());
        assertEquals(12, mathPlugin.getLastResult());
    }

    /**
     * Example 4: Execute workflow with multiple actions in one step.
     */
    @Test
    @DisplayName("Should execute multiple actions in a single workflow step")
    public void testExecuteMultipleActionsInOneStep() {
        Interpreter interpreter = new Interpreter.Builder()
                .loggerName("test-interpreter")
                .team(system)
                .build();

        InputStream yamlInput = getClass().getResourceAsStream("/workflows/multi-action.yaml");
        interpreter.readYaml(yamlInput);

        MatrixCode code = interpreter.getCode();
        assertEquals("multi-action-workflow", code.getName());

        Transition firstTransition = code.getSteps().get(0);
        assertEquals(3, firstTransition.getActions().size(), "First row should have 3 actions");

        // Execute the step with multiple actions
        ActionResult result = interpreter.execCode();
        assertTrue(result.isSuccess());

        // The last action (getLastResult) doesn't change the result,
        // so we check the result of multiply (2,4)
        assertEquals(8, mathPlugin.getLastResult());
    }

    /**
     * Example 5: Verify workflow matrix structure.
     */
    @Test
    @DisplayName("Should parse workflow matrix structure correctly")
    public void testWorkflowMatrixStructure() {
        Interpreter interpreter = new Interpreter.Builder()
                .loggerName("test-interpreter")
                .team(system)
                .build();

        InputStream yamlInput = getClass().getResourceAsStream("/workflows/simple-math.yaml");
        interpreter.readYaml(yamlInput);

        MatrixCode code = interpreter.getCode();

        // Check first row
        Transition row0 = code.getSteps().get(0);
        assertEquals(2, row0.getStates().size());
        assertEquals("0", row0.getStates().get(0));
        assertEquals("1", row0.getStates().get(1));
        assertEquals(1, row0.getActions().size());
        assertEquals("math", row0.getActions().get(0).getActor());
        assertEquals("add", row0.getActions().get(0).getMethod());
        // arguments is now a List: ["10", "5"]
        @SuppressWarnings("unchecked")
        java.util.List<String> args = (java.util.List<String>) row0.getActions().get(0).getArguments();
        assertEquals(2, args.size());
        assertEquals("10", args.get(0));
        assertEquals("5", args.get(1));

        // Check second row
        Transition row1 = code.getSteps().get(1);
        assertEquals("1", row1.getStates().get(0));
        assertEquals("2", row1.getStates().get(1));
        assertEquals("multiply", row1.getActions().get(0).getMethod());
    }

    /**
     * Example 6: Handle missing actor.
     *
     * <p>When an action references a non-existent actor, the Interpreter
     * returns a failure result with "Actor not found" message.</p>
     */
    @Test
    @DisplayName("Should return failure when actor is not found")
    public void testHandleMissingActor() {
        // Create workflow with reference to non-existent actor
        MatrixCode code = new MatrixCode();
        code.setName("test-missing-actor");

        Transition row = new Transition();
        row.setStates(java.util.Arrays.asList("0", "1"));
        Action action = new Action();
        action.setActor("nonexistent");
        action.setMethod("someAction");
        action.setArguments("args");
        row.setActions(java.util.Arrays.asList(action));
        code.setSteps(java.util.Arrays.asList(row));

        Interpreter interpreter = new Interpreter.Builder()
                .loggerName("test-interpreter")
                .team(system)
                .build();

        // Manually set the code (bypass YAML loading)
        java.lang.reflect.Field codeField;
        try {
            codeField = Interpreter.class.getDeclaredField("code");
            codeField.setAccessible(true);
            codeField.set(interpreter, code);
        } catch (Exception e) {
            fail("Failed to set code field: " + e.getMessage());
        }

        // Execute should return failure when actor is not found
        ActionResult result = interpreter.action();
        assertFalse(result.isSuccess(), "Should fail when actor is not found");
        assertTrue(result.getResult().contains("Actor not found"), "Should contain error message");
    }

    /**
     * Example 7: Empty workflow code.
     */
    @Test
    @DisplayName("Should handle empty workflow code")
    public void testEmptyWorkflowCode() {
        Interpreter interpreter = new Interpreter.Builder()
                .loggerName("test-interpreter")
                .team(system)
                .build();

        // Don't load any code
        ActionResult result = interpreter.execCode();

        assertFalse(result.isSuccess(), "Empty code should fail");
        assertEquals("No code loaded", result.getResult());
    }

    /**
     * Example 8: Builder pattern for Interpreter construction.
     */
    @Test
    @DisplayName("Should construct Interpreter using Builder pattern")
    public void testInterpreterBuilder() {
        Interpreter interpreter = new Interpreter.Builder()
                .loggerName("custom-logger")
                .team(system)
                .build();

        assertNotNull(interpreter, "Interpreter should be created");
    }

    /**
     * Example 9: State transition validation.
     */
    @Test
    @DisplayName("Should validate state transitions")
    public void testStateTransitionValidation() {
        Interpreter interpreter = new Interpreter.Builder()
                .loggerName("test-interpreter")
                .team(system)
                .build();

        InputStream yamlInput = getClass().getResourceAsStream("/workflows/simple-math.yaml");
        interpreter.readYaml(yamlInput);

        // Execute first step
        ActionResult result1 = interpreter.execCode();
        assertTrue(result1.isSuccess());
        assertTrue(result1.getResult().contains("State: 1"));

        // Execute second step
        ActionResult result2 = interpreter.execCode();
        assertTrue(result2.isSuccess());
        assertTrue(result2.getResult().contains("State: 2"));
    }

    /**
     * Example 10: Workflow with end state.
     */
    @Test
    @DisplayName("Should handle workflow end state")
    public void testWorkflowEndState() {
        Interpreter interpreter = new Interpreter.Builder()
                .loggerName("test-interpreter")
                .team(system)
                .build();

        InputStream yamlInput = getClass().getResourceAsStream("/workflows/simple-math.yaml");
        interpreter.readYaml(yamlInput);

        // Execute all steps
        interpreter.execCode();  // 0 -> 1
        interpreter.execCode();  // 1 -> 2
        ActionResult result = interpreter.execCode();  // 2 -> end

        assertTrue(result.isSuccess());
        assertTrue(result.getResult().contains("State: end"));
    }

    // ==================== XML Workflow Tests ====================

    /**
     * Example 11: Load XML workflow definition.
     */
    @Test
    @DisplayName("Should load workflow from XML")
    public void testLoadWorkflowFromXml() {
        Interpreter interpreter = new Interpreter.Builder()
                .loggerName("test-interpreter")
                .team(system)
                .build();

        InputStream xmlInput = getClass().getResourceAsStream("/workflows/simple-math.xml");
        assertNotNull(xmlInput, "XML resource should exist");

        try {
            interpreter.readXml(xmlInput);
        } catch (Exception e) {
            fail("Failed to read XML workflow: " + e.getMessage());
        }

        MatrixCode code = interpreter.getCode();
        assertNotNull(code, "Code should be loaded");
        assertEquals("simple-math-workflow", code.getName());
        assertEquals(3, code.getSteps().size(), "Should have 3 steps");
    }

    /**
     * Example 12: Execute XML workflow with single step.
     */
    @Test
    @DisplayName("Should execute XML workflow single step")
    public void testExecuteXmlSingleStepWorkflow() {
        Interpreter interpreter = new Interpreter.Builder()
                .loggerName("test-interpreter")
                .team(system)
                .build();

        InputStream xmlInput = getClass().getResourceAsStream("/workflows/simple-math.xml");
        try {
            interpreter.readXml(xmlInput);
        } catch (Exception e) {
            fail("Failed to read XML workflow: " + e.getMessage());
        }

        // Execute first step: state 0 -> 1, action: add 10,5
        ActionResult result = interpreter.execCode();

        assertTrue(result.isSuccess(), "Step should succeed");
        assertTrue(result.getResult().contains("State: 1"), "Should transition to state 1");

        // Verify the action was executed
        assertEquals(15, mathPlugin.getLastResult(), "Math operation should have been executed");
    }

    /**
     * Example 13: Execute XML multi-step workflow.
     */
    @Test
    @DisplayName("Should execute XML multi-step workflow with state transitions")
    public void testExecuteXmlMultiStepWorkflow() {
        Interpreter interpreter = new Interpreter.Builder()
                .loggerName("test-interpreter")
                .team(system)
                .build();

        InputStream xmlInput = getClass().getResourceAsStream("/workflows/simple-math.xml");
        try {
            interpreter.readXml(xmlInput);
        } catch (Exception e) {
            fail("Failed to read XML workflow: " + e.getMessage());
        }

        // Step 1: 0 -> 1, add 10,5 (result: 15)
        ActionResult result1 = interpreter.execCode();
        assertTrue(result1.isSuccess());
        assertEquals(15, mathPlugin.getLastResult());

        // Step 2: 1 -> 2, multiply 3,4 (result: 12)
        ActionResult result2 = interpreter.execCode();
        assertTrue(result2.isSuccess());
        assertEquals(12, mathPlugin.getLastResult());

        // Step 3: 2 -> end, getLastResult (result: 12)
        ActionResult result3 = interpreter.execCode();
        assertTrue(result3.isSuccess());
        assertEquals(12, mathPlugin.getLastResult());
    }

    /**
     * Example 14: Execute XML workflow with multiple actions in one step.
     */
    @Test
    @DisplayName("Should execute multiple actions in a single XML workflow step")
    public void testExecuteXmlMultipleActionsInOneStep() {
        Interpreter interpreter = new Interpreter.Builder()
                .loggerName("test-interpreter")
                .team(system)
                .build();

        InputStream xmlInput = getClass().getResourceAsStream("/workflows/multi-action.xml");
        try {
            interpreter.readXml(xmlInput);
        } catch (Exception e) {
            fail("Failed to read XML workflow: " + e.getMessage());
        }

        MatrixCode code = interpreter.getCode();
        assertEquals("multi-action-workflow", code.getName());

        Transition firstTransition = code.getSteps().get(0);
        assertEquals(3, firstTransition.getActions().size(), "First row should have 3 actions");

        // Execute the step with multiple actions
        ActionResult result = interpreter.execCode();
        assertTrue(result.isSuccess());

        // The last action (getLastResult) doesn't change the result,
        // so we check the result of multiply (2,4)
        assertEquals(8, mathPlugin.getLastResult());
    }

    /**
     * Example 15: Verify XML workflow matrix structure.
     */
    @Test
    @DisplayName("Should parse XML workflow matrix structure correctly")
    public void testXmlWorkflowMatrixStructure() {
        Interpreter interpreter = new Interpreter.Builder()
                .loggerName("test-interpreter")
                .team(system)
                .build();

        InputStream xmlInput = getClass().getResourceAsStream("/workflows/simple-math.xml");
        try {
            interpreter.readXml(xmlInput);
        } catch (Exception e) {
            fail("Failed to read XML workflow: " + e.getMessage());
        }

        MatrixCode code = interpreter.getCode();

        // Check first row
        Transition row0 = code.getSteps().get(0);
        assertEquals(2, row0.getStates().size());
        assertEquals("0", row0.getStates().get(0));
        assertEquals("1", row0.getStates().get(1));
        assertEquals(1, row0.getActions().size());
        assertEquals("math", row0.getActions().get(0).getActor());
        assertEquals("add", row0.getActions().get(0).getMethod());
        // arguments is now a List: ["10", "5"]
        @SuppressWarnings("unchecked")
        java.util.List<String> xmlArgs = (java.util.List<String>) row0.getActions().get(0).getArguments();
        assertEquals(2, xmlArgs.size());
        assertEquals("10", xmlArgs.get(0));
        assertEquals("5", xmlArgs.get(1));

        // Check second row
        Transition row1 = code.getSteps().get(1);
        assertEquals("1", row1.getStates().get(0));
        assertEquals("2", row1.getStates().get(1));
        assertEquals("multiply", row1.getActions().get(0).getMethod());
    }

    /**
     * Example 16: XML workflow with complex branching.
     */
    @Test
    @DisplayName("Should load complex branching XML workflow")
    public void testComplexBranchingXmlWorkflow() {
        Interpreter interpreter = new Interpreter.Builder()
                .loggerName("test-interpreter")
                .team(system)
                .build();

        InputStream xmlInput = getClass().getResourceAsStream("/workflows/complex-branching.xml");
        assertNotNull(xmlInput, "complex-branching.xml should exist");

        try {
            interpreter.readXml(xmlInput);
        } catch (Exception e) {
            fail("Failed to read XML workflow: " + e.getMessage());
        }

        MatrixCode code = interpreter.getCode();
        assertNotNull(code, "Code should be loaded");
        assertEquals("complex-branching", code.getName());
        assertEquals(16, code.getSteps().size(), "Should have 16 transitions");

        // Verify first transition
        Transition firstTransition = code.getSteps().get(0);
        assertEquals("init", firstTransition.getStates().get(0));
        assertEquals("state_A", firstTransition.getStates().get(1));
        assertEquals("checker", firstTransition.getActions().get(0).getActor());
        assertEquals("check_condition1", firstTransition.getActions().get(0).getMethod());
    }

    /**
     * Example 17: Empty argument in XML action.
     */
    @Test
    @DisplayName("Should handle empty arguments in XML actions")
    public void testXmlEmptyArgument() {
        Interpreter interpreter = new Interpreter.Builder()
                .loggerName("test-interpreter")
                .team(system)
                .build();

        InputStream xmlInput = getClass().getResourceAsStream("/workflows/simple-math.xml");
        try {
            interpreter.readXml(xmlInput);
        } catch (Exception e) {
            fail("Failed to read XML workflow: " + e.getMessage());
        }

        MatrixCode code = interpreter.getCode();
        Transition lastTransition = code.getSteps().get(2);  // The last row has getLastResult with no arguments

        assertNull(lastTransition.getActions().get(0).getArguments(), "No arguments should be null");
    }

    // ==================== New Arguments Format Tests ====================

    /**
     * Example 18: Load YAML workflow with arguments list format.
     */
    @Test
    @DisplayName("Should load and execute YAML workflow with arguments list format")
    public void testYamlWithArgumentsListFormat() {
        Interpreter interpreter = new Interpreter.Builder()
                .loggerName("test-interpreter")
                .team(system)
                .build();

        InputStream yamlInput = getClass().getResourceAsStream("/workflows/arguments-list-format.yaml");
        assertNotNull(yamlInput, "arguments-list-format.yaml should exist");

        interpreter.readYaml(yamlInput);

        MatrixCode code = interpreter.getCode();
        assertNotNull(code);
        assertEquals("arguments-list-format-workflow", code.getName());

        // Step 1: 0 -> 1, add ["10", "5"] (result: 15)
        ActionResult result1 = interpreter.execCode();
        assertTrue(result1.isSuccess());
        assertEquals(15, mathPlugin.getLastResult());

        // Step 2: 1 -> 2, multiply ["3", "4"] (result: 12)
        ActionResult result2 = interpreter.execCode();
        assertTrue(result2.isSuccess());
        assertEquals(12, mathPlugin.getLastResult());

        // Step 3: 2 -> end, getLastResult [] (result: 12)
        ActionResult result3 = interpreter.execCode();
        assertTrue(result3.isSuccess());
        assertEquals(12, mathPlugin.getLastResult());
    }

    /**
     * Example 19: Load JSON workflow with arguments list format.
     */
    @Test
    @DisplayName("Should load and execute JSON workflow with arguments list format")
    public void testJsonWithArgumentsListFormat() {
        Interpreter interpreter = new Interpreter.Builder()
                .loggerName("test-interpreter")
                .team(system)
                .build();

        InputStream jsonInput = getClass().getResourceAsStream("/workflows/arguments-list-format.json");
        assertNotNull(jsonInput, "arguments-list-format.json should exist");

        try {
            interpreter.readJson(jsonInput);
        } catch (Exception e) {
            fail("Failed to read JSON workflow: " + e.getMessage());
        }

        MatrixCode code = interpreter.getCode();
        assertNotNull(code);
        assertEquals("arguments-list-format-workflow", code.getName());

        // Step 1: 0 -> 1, add ["10", "5"] (result: 15)
        ActionResult result1 = interpreter.execCode();
        assertTrue(result1.isSuccess());
        assertEquals(15, mathPlugin.getLastResult());

        // Step 2: 1 -> 2, multiply ["3", "4"] (result: 12)
        ActionResult result2 = interpreter.execCode();
        assertTrue(result2.isSuccess());
        assertEquals(12, mathPlugin.getLastResult());

        // Step 3: 2 -> end, getLastResult [] (result: 12)
        ActionResult result3 = interpreter.execCode();
        assertTrue(result3.isSuccess());
        assertEquals(12, mathPlugin.getLastResult());
    }

    /**
     * Example 20: Load XML workflow with arguments list format.
     */
    @Test
    @DisplayName("Should load and execute XML workflow with arguments list format")
    public void testXmlWithArgumentsListFormat() {
        Interpreter interpreter = new Interpreter.Builder()
                .loggerName("test-interpreter")
                .team(system)
                .build();

        InputStream xmlInput = getClass().getResourceAsStream("/workflows/arguments-list-format.xml");
        assertNotNull(xmlInput, "arguments-list-format.xml should exist");

        try {
            interpreter.readXml(xmlInput);
        } catch (Exception e) {
            fail("Failed to read XML workflow: " + e.getMessage());
        }

        MatrixCode code = interpreter.getCode();
        assertNotNull(code);
        assertEquals("arguments-list-format-workflow", code.getName());

        // Step 1: 0 -> 1, add ["10", "5"] (result: 15)
        ActionResult result1 = interpreter.execCode();
        assertTrue(result1.isSuccess());
        assertEquals(15, mathPlugin.getLastResult());

        // Step 2: 1 -> 2, multiply ["3", "4"] (result: 12)
        ActionResult result2 = interpreter.execCode();
        assertTrue(result2.isSuccess());
        assertEquals(12, mathPlugin.getLastResult());

        // Step 3: 2 -> end, getLastResult [] (result: 12)
        ActionResult result3 = interpreter.execCode();
        assertTrue(result3.isSuccess());
        assertEquals(12, mathPlugin.getLastResult());
    }

    /**
     * Example 21: Load YAML workflow with mixed arguments format (string + array).
     * Demonstrates that both string and array formats are supported.
     */
    @Test
    @DisplayName("Should load and execute YAML workflow with mixed arguments format")
    public void testYamlWithMixedArguments() {
        Interpreter interpreter = new Interpreter.Builder()
                .loggerName("test-interpreter")
                .team(system)
                .build();

        InputStream yamlInput = getClass().getResourceAsStream("/workflows/arguments-mixed-format.yaml");
        assertNotNull(yamlInput, "arguments-mixed-format.yaml should exist");

        interpreter.readYaml(yamlInput);
        MatrixCode code = interpreter.getCode();
        assertNotNull(code);
        assertEquals("arguments-mixed-format-workflow", code.getName());

        // Verify workflow loaded correctly with mixed argument formats
        assertEquals(3, code.getSteps().size());

        // Step 1: greet with string format (no array brackets) - action executed but result in state
        ActionResult result1 = interpreter.execCode();
        assertTrue(result1.isSuccess());

        // Step 2: add with array format
        ActionResult result2 = interpreter.execCode();
        assertTrue(result2.isSuccess());
        assertEquals(15, mathPlugin.getLastResult());

        // Step 3: getLastResult with empty string
        ActionResult result3 = interpreter.execCode();
        assertTrue(result3.isSuccess());
        assertEquals(15, mathPlugin.getLastResult());
    }

    /**
     * Example 22: Verify that omitted arguments and empty array arguments
     * result in the same value being passed to the actor.
     *
     * <p>When arguments are not needed, either format should work:</p>
     * <ul>
     *   <li>Omit the arguments field entirely</li>
     *   <li>Specify an empty array: arguments: []</li>
     * </ul>
     *
     * <p>The actor should receive an empty JSON array "[]" in both cases.</p>
     */
    @Test
    @DisplayName("Should pass empty array to actor when arguments omitted or empty array")
    public void testOmittedArgumentsVsEmptyArray() {
        // Create an actor that records what arguments it receives
        java.util.List<String> receivedArgs = new java.util.ArrayList<>();

        IIActorRef<Object> argRecorder = new IIActorRef<Object>("argRecorder", new Object(), system) {
            @Override
            public ActionResult callByActionName(String actionName, String args) {
                receivedArgs.add(args);
                return new ActionResult(true, "recorded");
            }
        };
        system.addIIActor(argRecorder);

        Interpreter interpreter = new Interpreter.Builder()
                .loggerName("test-interpreter")
                .team(system)
                .build();

        // Test 1: Create workflow with null arguments (omitted)
        MatrixCode code1 = new MatrixCode();
        code1.setName("test-null-args");
        Transition row1 = new Transition();
        row1.setStates(java.util.Arrays.asList("0", "1"));
        Action action1 = new Action();
        action1.setActor("argRecorder");
        action1.setMethod("record");
        action1.setArguments(null);  // Omitted
        row1.setActions(java.util.Arrays.asList(action1));
        code1.setSteps(java.util.Arrays.asList(row1));

        // Set code and execute
        try {
            java.lang.reflect.Field codeField = Interpreter.class.getDeclaredField("code");
            codeField.setAccessible(true);
            codeField.set(interpreter, code1);
        } catch (Exception e) {
            fail("Failed to set code field: " + e.getMessage());
        }

        interpreter.action();
        String argsWhenNull = receivedArgs.get(0);

        // Test 2: Create workflow with empty array arguments
        receivedArgs.clear();
        interpreter.reset();

        MatrixCode code2 = new MatrixCode();
        code2.setName("test-empty-args");
        Transition row2 = new Transition();
        row2.setStates(java.util.Arrays.asList("0", "1"));
        Action action2 = new Action();
        action2.setActor("argRecorder");
        action2.setMethod("record");
        action2.setArguments(new java.util.ArrayList<>());  // Empty array
        row2.setActions(java.util.Arrays.asList(action2));
        code2.setSteps(java.util.Arrays.asList(row2));

        try {
            java.lang.reflect.Field codeField = Interpreter.class.getDeclaredField("code");
            codeField.setAccessible(true);
            codeField.set(interpreter, code2);
        } catch (Exception e) {
            fail("Failed to set code field: " + e.getMessage());
        }

        interpreter.action();
        String argsWhenEmpty = receivedArgs.get(0);

        // Both should result in the same value passed to actor: empty JSON array "[]"
        assertEquals(argsWhenNull, argsWhenEmpty,
            "Arguments should be the same whether omitted (null) or empty array. " +
            "Received: null=" + argsWhenNull + ", empty=" + argsWhenEmpty);

        // Verify the value is "[]" (empty JSON array), not null
        assertEquals("[]", argsWhenNull, "Omitted arguments should be passed as empty JSON array");
        assertEquals("[]", argsWhenEmpty, "Empty array arguments should be passed as empty JSON array");
    }

    /**
     * Example 23: Verify that empty JSON array "[]" means zero arguments,
     * not a single null argument.
     *
     * <p>This is an important distinction in programming:</p>
     * <ul>
     *   <li>"[]" → zero arguments (POJO method receives nothing)</li>
     *   <li>"[null]" → one argument with null value</li>
     * </ul>
     */
    @Test
    @DisplayName("Should distinguish between zero arguments and one null argument")
    public void testEmptyArrayMeansZeroArguments() {
        // Track how many arguments were parsed
        java.util.concurrent.atomic.AtomicInteger argCount = new java.util.concurrent.atomic.AtomicInteger(-1);
        java.util.concurrent.atomic.AtomicReference<String> firstArg = new java.util.concurrent.atomic.AtomicReference<>("NOT_CALLED");

        IIActorRef<Object> argCounter = new IIActorRef<Object>("argCounter", new Object(), system) {
            @Override
            public ActionResult callByActionName(String actionName, String args) {
                // Parse the JSON array to count actual arguments
                if (args == null) {
                    argCount.set(-1);  // null passed (should not happen)
                    firstArg.set("NULL_ARGS");
                } else if (args.startsWith("[")) {
                    org.json.JSONArray jsonArray = new org.json.JSONArray(args);
                    argCount.set(jsonArray.length());
                    if (jsonArray.length() > 0) {
                        firstArg.set(jsonArray.isNull(0) ? "NULL_VALUE" : jsonArray.getString(0));
                    } else {
                        firstArg.set("NO_ARGS");
                    }
                }
                return new ActionResult(true, "counted");
            }
        };
        system.addIIActor(argCounter);

        Interpreter interpreter = new Interpreter.Builder()
                .loggerName("test-interpreter")
                .team(system)
                .build();

        // Test: Empty arguments (omitted)
        MatrixCode code = new MatrixCode();
        code.setName("test-zero-args");
        Transition row = new Transition();
        row.setStates(java.util.Arrays.asList("0", "1"));
        Action action = new Action();
        action.setActor("argCounter");
        action.setMethod("count");
        action.setArguments(null);  // Omitted - should become []
        row.setActions(java.util.Arrays.asList(action));
        code.setSteps(java.util.Arrays.asList(row));

        try {
            java.lang.reflect.Field codeField = Interpreter.class.getDeclaredField("code");
            codeField.setAccessible(true);
            codeField.set(interpreter, code);
        } catch (Exception e) {
            fail("Failed to set code field: " + e.getMessage());
        }

        interpreter.action();

        // Verify: zero arguments, not null
        assertEquals(0, argCount.get(), "Empty/omitted arguments should result in zero arguments");
        assertEquals("NO_ARGS", firstArg.get(), "There should be no first argument");
    }

    /**
     * Example 24: Verify that execCode() wraps around from the last Transition to the first.
     *
     * <p>When the interpreter is at the end of the Transition list and fails to find a match,
     * it should wrap around to the beginning to continue searching.</p>
     */
    @Test
    @DisplayName("Should wrap around from last Transition to first when searching for matching state")
    public void testExecCodeWrapsAround() {
        // Create an actor that tracks which actions were called
        java.util.List<String> calledActions = new java.util.ArrayList<>();

        IIActorRef<Object> tracker = new IIActorRef<Object>("tracker", new Object(), system) {
            @Override
            public ActionResult callByActionName(String actionName, String args) {
                calledActions.add(actionName);
                // "fail" action returns false, others return true
                if ("fail".equals(actionName)) {
                    return new ActionResult(false, "intentional failure");
                }
                return new ActionResult(true, "success");
            }
        };
        system.addIIActor(tracker);

        Interpreter interpreter = new Interpreter.Builder()
                .loggerName("test-interpreter")
                .team(system)
                .build();

        // Create workflow where:
        // - Transition 0: state "A" -> "B" (at the beginning)
        // - Transition 1: state "0" -> "A" (fail action, should try next)
        // - Transition 2: state "0" -> "A" (success action)
        // - Transition 3: state "B" -> "end"
        //
        // After transitioning to "A", the interpreter is at Transition 3.
        // When looking for "A", it should wrap around to Transition 0.
        MatrixCode code = new MatrixCode();
        code.setName("wrap-around-test");

        // Transition 0: A -> B
        Transition row0 = new Transition();
        row0.setStates(java.util.Arrays.asList("A", "B"));
        Action action0 = new Action();
        action0.setActor("tracker");
        action0.setMethod("actionAtVertex0");
        row0.setActions(java.util.Arrays.asList(action0));

        // Transition 1: 0 -> A (fails)
        Transition row1 = new Transition();
        row1.setStates(java.util.Arrays.asList("0", "A"));
        Action action1 = new Action();
        action1.setActor("tracker");
        action1.setMethod("fail");
        row1.setActions(java.util.Arrays.asList(action1));

        // Transition 2: 0 -> A (succeeds)
        Transition row2 = new Transition();
        row2.setStates(java.util.Arrays.asList("0", "A"));
        Action action2 = new Action();
        action2.setActor("tracker");
        action2.setMethod("actionAtVertex2");
        row2.setActions(java.util.Arrays.asList(action2));

        // Transition 3: B -> end
        Transition row3 = new Transition();
        row3.setStates(java.util.Arrays.asList("B", "end"));
        Action action3 = new Action();
        action3.setActor("tracker");
        action3.setMethod("actionAtVertex3");
        row3.setActions(java.util.Arrays.asList(action3));

        code.setSteps(java.util.Arrays.asList(row0, row1, row2, row3));

        try {
            java.lang.reflect.Field codeField = Interpreter.class.getDeclaredField("code");
            codeField.setAccessible(true);
            codeField.set(interpreter, code);
        } catch (Exception e) {
            fail("Failed to set code field: " + e.getMessage());
        }

        // Run until end
        ActionResult result = interpreter.runUntilEnd();

        // Verify success
        assertTrue(result.isSuccess(), "Workflow should complete successfully");

        // Verify the execution order:
        // 1. Transition 1 (fail) -> Transition 2 (actionAtVertex2) -> transition to A
        // 2. From Transition 3, wrap around to Transition 0 (actionAtVertex0) -> transition to B
        // 3. Transition 3 (actionAtVertex3) -> transition to end
        assertEquals(4, calledActions.size(), "Should have called 4 actions");
        assertEquals("fail", calledActions.get(0), "First action should be fail");
        assertEquals("actionAtVertex2", calledActions.get(1), "Second action should be at Transition 2");
        assertEquals("actionAtVertex0", calledActions.get(2), "Third action should be at Transition 0 (wrapped around)");
        assertEquals("actionAtVertex3", calledActions.get(3), "Fourth action should be at Transition 3");
    }

    /**
     * Example 25: Verify conditional branching with fallback.
     *
     * <p>Tests that when conditions fail, the interpreter continues to the next
     * matching Transition until finding one that succeeds (fallback/default case).</p>
     */
    @Test
    @DisplayName("Should fall through to default when conditions fail")
    public void testConditionalBranchingWithFallback() {
        java.util.List<String> calledActions = new java.util.ArrayList<>();

        IIActorRef<Object> tracker = new IIActorRef<Object>("tracker", new Object(), system) {
            @Override
            public ActionResult callByActionName(String actionName, String args) {
                calledActions.add(actionName);
                if (actionName.startsWith("fail")) {
                    return new ActionResult(false, "intentional failure");
                }
                return new ActionResult(true, "success");
            }
        };
        system.addIIActor(tracker);

        Interpreter interpreter = new Interpreter.Builder()
                .loggerName("test-interpreter")
                .team(system)
                .build();

        // Scenario: Conditional branching with fallback
        // - Transition 0: state "0" -> "check"
        // - Transition 1: state "check" -> "pathA" (fails)
        // - Transition 2: state "check" -> "pathB" (fails)
        // - Transition 3: state "check" -> "end" (default/fallback - succeeds)
        //
        // After transitioning to "check", findNextMatchingTransition() finds Transition 1.
        // Transition 1 fails, Transition 2 fails, Transition 3 succeeds.

        MatrixCode code = new MatrixCode();
        code.setName("conditional-fallback-test");

        // Transition 0: 0 -> check
        Transition row0 = new Transition();
        row0.setStates(java.util.Arrays.asList("0", "check"));
        Action action0 = new Action();
        action0.setActor("tracker");
        action0.setMethod("init");
        row0.setActions(java.util.Arrays.asList(action0));

        // Transition 1: check -> pathA (fails)
        Transition row1 = new Transition();
        row1.setStates(java.util.Arrays.asList("check", "pathA"));
        Action action1 = new Action();
        action1.setActor("tracker");
        action1.setMethod("failConditionA");
        row1.setActions(java.util.Arrays.asList(action1));

        // Transition 2: check -> pathB (fails)
        Transition row2 = new Transition();
        row2.setStates(java.util.Arrays.asList("check", "pathB"));
        Action action2 = new Action();
        action2.setActor("tracker");
        action2.setMethod("failConditionB");
        row2.setActions(java.util.Arrays.asList(action2));

        // Transition 3: check -> end (default)
        Transition row3 = new Transition();
        row3.setStates(java.util.Arrays.asList("check", "end"));
        Action action3 = new Action();
        action3.setActor("tracker");
        action3.setMethod("defaultPath");
        row3.setActions(java.util.Arrays.asList(action3));

        code.setSteps(java.util.Arrays.asList(row0, row1, row2, row3));

        try {
            java.lang.reflect.Field codeField = Interpreter.class.getDeclaredField("code");
            codeField.setAccessible(true);
            codeField.set(interpreter, code);
        } catch (Exception e) {
            fail("Failed to set code field: " + e.getMessage());
        }

        ActionResult result = interpreter.runUntilEnd();

        assertTrue(result.isSuccess(), "Workflow should complete successfully");
        assertEquals("end", interpreter.getCurrentState(), "Should reach end state");

        // Verify execution:
        // 1. init (0 -> check)
        // 2. failConditionA (fails, try next)
        // 3. failConditionB (fails, try next)
        // 4. defaultPath (check -> end, succeeds)
        assertEquals(4, calledActions.size(), "Should have called 4 actions");
        assertEquals("init", calledActions.get(0));
        assertEquals("failConditionA", calledActions.get(1));
        assertEquals("failConditionB", calledActions.get(2));
        assertEquals("defaultPath", calledActions.get(3));
    }

    /**
     * Example 26: Verify that YAML with 'transitions' key is accepted.
     *
     * <p>Tests backward compatibility: both 'steps' and 'transitions' keys
     * should be accepted in YAML workflow files.</p>
     */
    @Test
    @DisplayName("Should accept 'transitions' key in YAML as alias for 'steps'")
    public void testTransitionsKeyInYaml() throws Exception {
        // YAML using 'transitions' instead of 'steps'
        String yamlContent = """
            name: TransitionsKeyTest
            transitions:
              - states: ["0", "1"]
                label: init
                actions:
                  - actor: testActor
                    method: doInit
              - states: ["1", "end"]
                label: finish
                actions:
                  - actor: testActor
                    method: doFinish
            """;

        // Track called actions
        java.util.List<String> calledActions = new java.util.ArrayList<>();

        IIActorRef<Object> testActor = new IIActorRef<Object>("testActor", new Object(), system) {
            @Override
            public ActionResult callByActionName(String actionName, String args) {
                calledActions.add(actionName);
                return new ActionResult(true, "success");
            }
        };
        system.addIIActor(testActor);

        Interpreter interpreter = new Interpreter.Builder()
                .loggerName("test-interpreter")
                .team(system)
                .build();

        // Load YAML with 'transitions' key
        org.yaml.snakeyaml.Yaml yaml = new org.yaml.snakeyaml.Yaml();
        java.util.Map<String, Object> data = yaml.load(yamlContent);

        // Use reflection to call private mapToMatrixCode method
        java.lang.reflect.Method mapMethod = Interpreter.class.getDeclaredMethod(
                "mapToMatrixCode", java.util.Map.class);
        mapMethod.setAccessible(true);
        MatrixCode code = (MatrixCode) mapMethod.invoke(interpreter, data);

        // Verify the code was parsed correctly
        assertNotNull(code, "MatrixCode should be created");
        assertEquals("TransitionsKeyTest", code.getName());
        assertEquals(2, code.getTransitions().size(), "Should have 2 transitions");
        assertEquals("init", code.getTransitions().get(0).getLabel());
        assertEquals("finish", code.getTransitions().get(1).getLabel());
    }
}
