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
import java.util.Arrays;
import java.util.List;

import org.json.JSONArray;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.scivicslab.pojoactor.core.Action;
import com.scivicslab.pojoactor.core.ActionResult;

/**
 * Tests for parallel execution and value-based conditional branching.
 *
 * @author devteam@scivicslab.com
 * @version 1.0.0
 */
@Tag("Y_base.05")
@DisplayName("Parallel Execution Specification by Example")
public class ParallelExecutionTest {

    private IIActorSystem system;

    @BeforeEach
    public void setUp() {
        system = new IIActorSystem("parallel-execution-test");
    }

    @AfterEach
    public void tearDown() {
        if (system != null) {
            system.terminate();
        }
    }

    static class ParallelActor extends IIActorRef<Void> {
        private final List<String> executionOrder = new ArrayList<>();
        private final String actorId;

        public ParallelActor(String actorId, IIActorSystem system) {
            super(actorId, null, system);
            this.actorId = actorId;
        }

        @Action("execute")
        public ActionResult execute(String args) {
            String entry = actorId + ":" + args;
            synchronized (executionOrder) {
                executionOrder.add(entry);
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return new ActionResult(true, "Executed: " + entry);
        }

        public List<String> getExecutionOrder() {
            synchronized (executionOrder) {
                return new ArrayList<>(executionOrder);
            }
        }
    }

    static class DecisionActor extends IIActorRef<Void> {
        private int value = 0;
        private String lastDecision = "";

        public DecisionActor(String name, IIActorSystem system) {
            super(name, null, system);
        }

        @Action("setValue")
        public ActionResult setValue(String args) {
            String firstArg = getFirstArg(args);
            value = Integer.parseInt(firstArg);
            return new ActionResult(true, "Value set to: " + value);
        }

        @Action("checkValue")
        public ActionResult checkValue(String args) {
            if (value > 10) {
                lastDecision = "high";
                return new ActionResult(true, "high");
            } else {
                lastDecision = "low";
                return new ActionResult(true, "low");
            }
        }

        @Action("processHigh")
        public ActionResult processHigh(String args) {
            return new ActionResult(true, "Processed as high value");
        }

        @Action("processLow")
        public ActionResult processLow(String args) {
            return new ActionResult(true, "Processed as low value");
        }

        public String getLastDecision() { return lastDecision; }
        public int getValue() { return value; }

        private static String getFirstArg(String args) {
            if (args == null || args.isEmpty()) return args;
            if (args.startsWith("[")) {
                JSONArray jsonArray = new JSONArray(args);
                return jsonArray.length() > 0 ? jsonArray.getString(0) : "";
            }
            return args;
        }
    }

    @Test
    @DisplayName("Should demonstrate parallel execution pattern")
    public void testParallelExecution() throws InterruptedException {
        ParallelActor actor1 = new ParallelActor("actor1", system);
        ParallelActor actor2 = new ParallelActor("actor2", system);
        ParallelActor actor3 = new ParallelActor("actor3", system);

        system.addIIActor(actor1);
        system.addIIActor(actor2);
        system.addIIActor(actor3);

        Interpreter interpreter = new Interpreter.Builder()
            .loggerName("parallel-test")
            .team(system)
            .build();

        InputStream yamlInput = getClass().getResourceAsStream("/workflows/parallel-execution.yaml");
        assertNotNull(yamlInput, "Parallel execution YAML should exist");

        interpreter.readYaml(yamlInput);

        ActionResult result = interpreter.execCode();
        assertTrue(result.isSuccess());

        Thread.sleep(200);

        assertEquals(1, actor1.getExecutionOrder().size());
        assertEquals(1, actor2.getExecutionOrder().size());
        assertEquals(1, actor3.getExecutionOrder().size());
    }

    @Test
    @DisplayName("Should demonstrate conditional branching pattern")
    public void testConditionalBranching() {
        DecisionActor decisionActor = new DecisionActor("decision", system);
        system.addIIActor(decisionActor);

        Interpreter interpreter = new Interpreter.Builder()
            .loggerName("conditional-high")
            .team(system)
            .build();

        InputStream yamlInput = getClass().getResourceAsStream("/workflows/conditional-branch.yaml");
        assertNotNull(yamlInput, "Conditional branch YAML should exist");

        interpreter.readYaml(yamlInput);

        ActionResult result1 = interpreter.execCode(); // state 0->1: setValue 15
        assertTrue(result1.isSuccess());

        ActionResult result2 = interpreter.execCode(); // state 1->2: checkValue
        assertTrue(result2.isSuccess());

        assertEquals("high", decisionActor.getLastDecision());
        assertEquals(15, decisionActor.getValue());
    }

    @Test
    @DisplayName("Should take low path when value is at or below threshold")
    public void testConditionalBranchingLow() {
        DecisionActor decisionActor = new DecisionActor("decision", system);
        system.addIIActor(decisionActor);

        Interpreter interpreter = new Interpreter.Builder()
            .loggerName("conditional-low")
            .team(system)
            .build();

        Transition step1 = new Transition();
        step1.setStates(Arrays.asList("0", "1"));
        com.scivicslab.turingworkflow.workflow.Action a1 = new com.scivicslab.turingworkflow.workflow.Action();
        a1.setActor("decision");
        a1.setMethod("setValue");
        a1.setArguments("5"); // 5 <= 10 → low
        step1.setActions(Arrays.asList(a1));

        Transition step2 = new Transition();
        step2.setStates(Arrays.asList("1", "end"));
        com.scivicslab.turingworkflow.workflow.Action a2 = new com.scivicslab.turingworkflow.workflow.Action();
        a2.setActor("decision");
        a2.setMethod("checkValue");
        step2.setActions(Arrays.asList(a2));

        MatrixCode code = new MatrixCode();
        code.setName("conditional-low-workflow");
        code.setSteps(Arrays.asList(step1, step2));
        interpreter.setCode(code);

        interpreter.execCode(); // 0->1: setValue 5
        interpreter.execCode(); // 1->end: checkValue

        assertEquals("low", decisionActor.getLastDecision());
        assertEquals(5, decisionActor.getValue());
    }
}
