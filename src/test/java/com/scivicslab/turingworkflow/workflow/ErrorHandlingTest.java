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

import org.json.JSONArray;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.scivicslab.pojoactor.core.Action;
import com.scivicslab.pojoactor.core.ActionResult;
import com.scivicslab.turingworkflow.plugin.MathPlugin;
import com.scivicslab.turingworkflow.workflow.ReusableSubWorkflowCaller;
import com.scivicslab.turingworkflow.workflow.SubWorkflowCaller;

/**
 * Tests for error handling, retry, and sub-workflow reuse patterns.
 *
 * @author devteam@scivicslab.com
 * @version 1.0.0
 */
@Tag("Y_comp")
@DisplayName("Error Handling Specification by Example")
public class ErrorHandlingTest {

    private IIActorSystem system;

    @BeforeEach
    public void setUp() {
        system = new IIActorSystem("error-handling-test");
    }

    @AfterEach
    public void tearDown() {
        if (system != null) {
            system.terminate();
        }
    }

    private static String getFirstArg(String args) {
        if (args == null || args.isEmpty()) return args;
        if (args.startsWith("[")) {
            JSONArray jsonArray = new JSONArray(args);
            return jsonArray.length() > 0 ? jsonArray.getString(0) : "";
        }
        return args;
    }

    static class DecisionActor extends IIActorRef<Void> {
        private int value = 0;

        public DecisionActor(String name, IIActorSystem system) {
            super(name, null, system);
        }

        @Action("setValue")
        public ActionResult setValue(String args) {
            value = Integer.parseInt(getFirstArg(args));
            return new ActionResult(true, "Value set to: " + value);
        }

        @Action("checkValue")
        public ActionResult checkValue(String args) {
            return value > 10
                ? new ActionResult(true, "high")
                : new ActionResult(true, "low");
        }

        @Action("processHigh")
        public ActionResult processHigh(String args) {
            return new ActionResult(true, "Processed as high value");
        }

        @Action("processLow")
        public ActionResult processLow(String args) {
            return new ActionResult(true, "Processed as low value");
        }
    }

    private static class MathIIActorRef extends IIActorRef<MathPlugin> {
        public MathIIActorRef(String actorName, MathPlugin object, IIActorSystem system) {
            super(actorName, object, system);
        }

        @Action("add")
        public ActionResult add(String args) { return this.object.callByActionName("add", args); }

        @Action("multiply")
        public ActionResult multiply(String args) { return this.object.callByActionName("multiply", args); }

        @Action("getLastResult")
        public ActionResult getLastResult(String args) { return this.object.callByActionName("getLastResult", args); }

        @Action("greet")
        public ActionResult greet(String args) { return this.object.callByActionName("greet", args); }
    }

    @Deprecated
    static class SubWorkflowCoordinator extends IIActorRef<Void> {
        private int subWorkflowExecutions = 0;

        public SubWorkflowCoordinator(String name, IIActorSystem system) {
            super(name, null, system);
        }

        @Action("executeSubWorkflow")
        public ActionResult executeSubWorkflow(String args) {
            IIActorSystem actorSystem = (IIActorSystem) system();
            Interpreter subInterpreter = new Interpreter.Builder()
                .loggerName("sub-workflow")
                .team(actorSystem)
                .build();

            String workflowFile = getFirstArg(args);
            InputStream yamlInput = getClass().getResourceAsStream("/workflows/" + workflowFile);
            if (yamlInput != null) {
                subInterpreter.readYaml(yamlInput);
                subInterpreter.runUntilEnd();
                subWorkflowExecutions++;
                return new ActionResult(true, "Sub-workflow executed: " + args);
            }
            return new ActionResult(false, "Sub-workflow not found: " + args);
        }

        public int getSubWorkflowExecutions() { return subWorkflowExecutions; }
    }

    // Named inner class for testErrorHandling anonymous actor
    static class ErrorProneActor extends IIActorRef<Void> {
        private int attempts = 0;

        public ErrorProneActor(String name, IIActorSystem system) {
            super(name, null, system);
        }

        @Action("riskyOperation")
        public ActionResult riskyOperation(String args) {
            attempts++;
            if (attempts < 3) {
                return new ActionResult(false, "Operation failed (attempt " + attempts + ")");
            }
            return new ActionResult(true, "Operation succeeded after " + attempts + " attempts");
        }

        @Action("handleError")
        public ActionResult handleError(String args) {
            return new ActionResult(true, "Error handled");
        }
    }

    @Test
    @DisplayName("Should demonstrate error handling pattern")
    public void testErrorHandling() {
        system.addIIActor(new ErrorProneActor("errorActor", system));

        Interpreter interpreter = new Interpreter.Builder()
            .loggerName("error-test")
            .team(system)
            .build();

        InputStream yamlInput = getClass().getResourceAsStream("/workflows/error-handling.yaml");
        assertNotNull(yamlInput, "Error handling YAML should exist");

        interpreter.readYaml(yamlInput);

        ActionResult result = interpreter.execCode();
        assertTrue(result.isSuccess());
    }

    @Test
    @DisplayName("Should demonstrate sub-workflow pattern")
    public void testSubWorkflow() {
        SubWorkflowCoordinator coordinator = new SubWorkflowCoordinator("coordinator", system);
        system.addIIActor(coordinator);

        DecisionActor processor = new DecisionActor("processor", system);
        system.addIIActor(processor);

        Interpreter mainInterpreter = new Interpreter.Builder()
            .loggerName("main-workflow")
            .team(system)
            .build();

        InputStream yamlInput = getClass().getResourceAsStream("/workflows/main-with-subworkflow.yaml");
        assertNotNull(yamlInput, "Main workflow YAML should exist");

        mainInterpreter.readYaml(yamlInput);

        ActionResult result = mainInterpreter.execCode();
        assertTrue(result.isSuccess());

        assertEquals(1, coordinator.getSubWorkflowExecutions());
    }

    @Test
    @DisplayName("Should demonstrate sub-workflow pattern with SubWorkflowCaller")
    public void testSubWorkflowWithLibraryCaller() {
        SubWorkflowCaller caller = new SubWorkflowCaller("caller", system);
        system.addIIActor(caller);

        MathPlugin mathPlugin = new MathPlugin();
        system.addIIActor(new MathIIActorRef("math", mathPlugin, system));

        Interpreter mainInterpreter = new Interpreter.Builder()
            .loggerName("main-workflow-with-caller")
            .team(system)
            .build();

        InputStream yamlInput = getClass().getResourceAsStream("/workflows/main-with-caller.yaml");
        assertNotNull(yamlInput, "Main workflow with caller YAML should exist");

        mainInterpreter.readYaml(yamlInput);

        ActionResult result = mainInterpreter.execCode();
        assertTrue(result.isSuccess());

        assertEquals(1, caller.getCallCount());
    }

    @Test
    @DisplayName("Should demonstrate sub-workflow pattern with ReusableSubWorkflowCaller")
    public void testSubWorkflowWithReusableCaller() {
        ReusableSubWorkflowCaller caller = new ReusableSubWorkflowCaller("caller", system);
        system.addIIActor(caller);

        MathPlugin mathPlugin = new MathPlugin();
        system.addIIActor(new MathIIActorRef("math", mathPlugin, system));

        Interpreter mainInterpreter = new Interpreter.Builder()
            .loggerName("main-workflow-with-reusable-caller")
            .team(system)
            .build();

        InputStream yamlInput = getClass().getResourceAsStream("/workflows/main-with-caller.yaml");
        assertNotNull(yamlInput, "Main workflow with caller YAML should exist");

        mainInterpreter.readYaml(yamlInput);

        ActionResult result1 = mainInterpreter.execCode();
        assertTrue(result1.isSuccess());

        ActionResult result2 = mainInterpreter.execCode();
        assertTrue(result2.isSuccess());

        assertEquals(2, caller.getCallCount());
    }

    @Test
    @DisplayName("Should reset Interpreter state correctly")
    public void testInterpreterReset() {
        Interpreter interpreter = new Interpreter.Builder()
            .loggerName("reset-test")
            .team(system)
            .build();

        DecisionActor actor = new DecisionActor("decision", system);
        system.addIIActor(actor);

        InputStream yamlInput1 = getClass().getResourceAsStream("/workflows/conditional-branch.yaml");
        interpreter.readYaml(yamlInput1);

        ActionResult result1 = interpreter.execCode();
        assertTrue(result1.isSuccess());
        assertFalse(result1.getResult().contains("State: 0"));

        interpreter.reset();

        InputStream yamlInput2 = getClass().getResourceAsStream("/workflows/conditional-branch.yaml");
        interpreter.readYaml(yamlInput2);

        ActionResult result2 = interpreter.execCode();
        assertTrue(result2.isSuccess());
        assertTrue(result2.getResult().contains("State: 1"));
    }
}
