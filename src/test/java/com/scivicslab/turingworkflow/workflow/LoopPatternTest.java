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
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.scivicslab.pojoactor.action.Action;
import com.scivicslab.pojoactor.action.ActionResult;

/**
 * Tests for loop patterns: single-state loop and multi-state loop.
 *
 * @author devteam@scivicslab.com
 * @version 1.0.0
 */
@Tag("Y_base.06")
@DisplayName("Loop Pattern Specification by Example")
public class LoopPatternTest {

    private IIActorSystem system;

    @BeforeEach
    public void setUp() {
        system = new IIActorSystem("loop-pattern-test");
    }

    /** Gives the interpreter the actor it runs as, which a transition reaches as {@code this}. */
    private InterpreterIIAR runsAs(Interpreter interpreter, String name) {
        InterpreterIIAR self = new InterpreterIIAR(name, interpreter, system);
        interpreter.setSelfActorRef(self);
        system.addIIActor(self);
        return self;
    }

    @AfterEach
    public void tearDown() {
        if (system != null) {
            system.terminate();
        }
    }

    // Named inner class for testLoopPattern
    static class LoopActorIIAR extends IIActorRef<Void> {
        private final AtomicInteger counter;

        LoopActorIIAR(String name, AtomicInteger counter, IIActorSystem system) {
            super(name, null, system);
            this.counter = counter;
        }

        @Action("increment")
        public ActionResult increment(String args) {
            int newValue = counter.incrementAndGet();
            return new ActionResult(true, "Count: " + newValue);
        }

        @Action("checkLimit")
        public ActionResult checkLimit(String args) {
            boolean shouldContinue = counter.get() < 5;
            return new ActionResult(shouldContinue, shouldContinue ? "continue" : "done");
        }

        @Action("finish")
        public ActionResult finish(String args) {
            return new ActionResult(true, "finished");
        }
    }

    // Named inner class for testCursorLoop
    static class CursorActorIIAR extends IIActorRef<Void> {
        private final List<String> items;
        private final AtomicInteger cursor;

        CursorActorIIAR(String name, List<String> items, AtomicInteger cursor, IIActorSystem system) {
            super(name, null, system);
            this.items = items;
            this.cursor = cursor;
        }

        @Action("getNext")
        public ActionResult getNext(String args) {
            int idx = cursor.getAndIncrement();
            return idx < items.size()
                ? new ActionResult(true, items.get(idx))
                : new ActionResult(false, "exhausted");
        }
    }

    // Named inner class for testMultiStateLoop
    static class CycleActorIIAR extends IIActorRef<Void> {
        private final List<String> stateHistory;
        private final AtomicInteger cycleCount;

        CycleActorIIAR(String name, List<String> stateHistory, AtomicInteger cycleCount,
                       IIActorSystem system) {
            super(name, null, system);
            this.stateHistory = stateHistory;
            this.cycleCount = cycleCount;
        }

        @Action("phase1")
        public ActionResult phase1(String args) {
            synchronized (stateHistory) {
                stateHistory.add("phase1");
            }
            return new ActionResult(true, "Executed: phase1");
        }

        @Action("phase2")
        public ActionResult phase2(String args) {
            synchronized (stateHistory) {
                stateHistory.add("phase2");
            }
            return new ActionResult(true, "Executed: phase2");
        }

        @Action("phase3")
        public ActionResult phase3(String args) {
            synchronized (stateHistory) {
                stateHistory.add("phase3");
            }
            return new ActionResult(true, "Executed: phase3");
        }

        @Action("checkContinue")
        public ActionResult checkContinue(String args) {
            synchronized (stateHistory) {
                stateHistory.add("checkContinue");
            }
            int cycles = cycleCount.incrementAndGet();
            boolean shouldContinue = cycles < 3;
            return new ActionResult(shouldContinue, shouldContinue ? "continue" : "done");
        }

        @Action("finalize")
        public ActionResult finalize(String args) {
            synchronized (stateHistory) {
                stateHistory.add("finalize");
            }
            return new ActionResult(true, "Executed: finalize");
        }
    }

    @Test
    @DisplayName("Should demonstrate loop pattern")
    public void testLoopPattern() {
        AtomicInteger counter = new AtomicInteger(0);

        system.addIIActor(new LoopActorIIAR("loopActor", counter, system));

        Interpreter interpreter = new Interpreter.Builder()
            .loggerName("loop-test")
            .team(system)
            .build();

        InputStream yamlInput = getClass().getResourceAsStream("/workflows/loop-pattern.yaml");
        assertNotNull(yamlInput, "Loop pattern YAML should exist");

        interpreter.readYaml(yamlInput);

        ActionResult result = interpreter.runUntilEnd(10);
        assertTrue(result.isSuccess(), "Workflow should complete successfully");

        assertEquals(5, counter.get());
    }

    @Test
    @DisplayName("Should demonstrate JEXL state counter loop (Tutorial Pattern 1)")
    public void testJexlCounterLoop() {
        Interpreter interpreter = new Interpreter.Builder()
            .loggerName("jexl-counter-test")
            .team(system)
            .build();

        InterpreterIIAR self = runsAs(interpreter, "jexl-counter-main");

        InputStream yamlInput = getClass().getResourceAsStream("/workflows/jexl-counter-loop.yaml");
        assertNotNull(yamlInput, "JEXL counter loop YAML should exist");

        interpreter.readYaml(yamlInput);

        ActionResult result = interpreter.runUntilEnd(20);
        assertTrue(result.isSuccess(), "JEXL counter loop should complete successfully");

        // The state number is the counter: the body ran once for each of 0..4.
        assertEquals(5, self.json().getInt("count", -1));
    }

    @Test
    @DisplayName("Should demonstrate list actor exhaustion loop (Tutorial Pattern 3)")
    public void testListActorLoop() {
        Interpreter interpreter = new Interpreter.Builder()
            .loggerName("list-loop-test")
            .team(system)
            .build();

        InterpreterIIAR self = runsAs(interpreter, "list-loop-main");

        InputStream yamlInput = getClass().getResourceAsStream("/workflows/list-actor-loop.yaml");
        assertNotNull(yamlInput, "List loop YAML should exist");

        interpreter.readYaml(yamlInput);

        ActionResult result = interpreter.runUntilEnd(20);
        assertTrue(result.isSuccess(), "List loop should complete successfully");

        // Each item was taken once, in order, and the loop left when the list was used up.
        assertEquals(3, self.json().getInt("count", -1));
        assertEquals(3, self.json().select("seen").size());
        assertEquals("Apple", self.json().getString("seen[0]"));
        assertEquals("Cherry", self.json().getString("seen[2]"));
    }

    @Test
    @DisplayName("Should demonstrate cursor-based exhaustion loop (Tutorial Pattern 4)")
    public void testCursorLoop() {
        List<String> items = List.of("A", "B", "C");
        AtomicInteger cursor = new AtomicInteger(0);

        system.addIIActor(new CursorActorIIAR("cursorActor", items, cursor, system));

        Interpreter interpreter = new Interpreter.Builder()
            .loggerName("cursor-loop-test")
            .team(system)
            .build();

        InterpreterIIAR self = runsAs(interpreter, "cursor-loop-main");

        InputStream yamlInput = getClass().getResourceAsStream("/workflows/cursor-loop.yaml");
        assertNotNull(yamlInput, "Cursor loop YAML should exist");

        interpreter.readYaml(yamlInput);

        ActionResult result = interpreter.runUntilEnd(20);
        assertTrue(result.isSuccess(), "Cursor loop should complete successfully");

        // The body ran once per item, and the cursor's failure on the fourth call ended the loop.
        assertEquals(3, self.json().getInt("count", -1));
        assertEquals(4, cursor.get(), "three items taken, and a fourth call that found none");
    }

    @Test
    @DisplayName("Should demonstrate multi-state loop pattern")
    public void testMultiStateLoop() {
        List<String> stateHistory = new ArrayList<>();
        AtomicInteger cycleCount = new AtomicInteger(0);

        system.addIIActor(new CycleActorIIAR("cycleActor", stateHistory, cycleCount, system));

        Interpreter interpreter = new Interpreter.Builder()
            .loggerName("multi-state-loop-test")
            .team(system)
            .build();

        InputStream yamlInput = getClass().getResourceAsStream("/workflows/multi-state-loop.yaml");
        assertNotNull(yamlInput, "Multi-state loop YAML should exist");

        interpreter.readYaml(yamlInput);

        ActionResult result = interpreter.runUntilEnd(20);
        assertTrue(result.isSuccess(), "Workflow should complete successfully");

        assertTrue(stateHistory.size() >= 12,
            "Should have executed multiple states in loop cycles, but was: " + stateHistory.size());

        long phase1Count = stateHistory.stream().filter(s -> s.contains("phase1")).count();
        long phase2Count = stateHistory.stream().filter(s -> s.contains("phase2")).count();
        long phase3Count = stateHistory.stream().filter(s -> s.contains("phase3")).count();

        assertTrue(phase1Count >= 3, "Phase1 should execute multiple times");
        assertTrue(phase2Count >= 3, "Phase2 should execute multiple times");
        assertTrue(phase3Count >= 3, "Phase3 should execute multiple times");
        assertTrue(cycleCount.get() >= 3, "Should have completed at least 3 cycles, was: " + cycleCount.get());
    }
}
