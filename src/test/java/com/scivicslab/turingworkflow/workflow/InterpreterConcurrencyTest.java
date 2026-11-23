/*
 * Copyright 2025 SCIVICS Lab
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.scivicslab.turingworkflow.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.scivicslab.pojoactor.action.ActionResult;

/**
 * Interpreter state shared between the thread a run is on and the threads that look at it or call
 * into it: child names of concurrent {@code this.call}s, the stop request across {@code reset()},
 * and the fields a UI reads while a run goes on. Every wait is a latch; nothing sleeps.
 */
@DisplayName("Interpreter state shared across threads")
class InterpreterConcurrencyTest {

    private IIActorSystem system;
    private Interpreter interpreter;
    private CountDownLatch entered;
    private CountDownLatch release;

    /** An interpreter whose sub-workflow children all get the same name, as two colliding draws would. */
    static final class OneChildName extends Interpreter {
        static final String NAME = "subwf-collided";

        @Override
        protected String generateChildName(String workflowFile) {
            return NAME;
        }
    }

    @BeforeEach
    void setUp() {
        system = new IIActorSystem("interpreter-concurrency-test", 2);
        entered = new CountDownLatch(1);
        release = new CountDownLatch(1);
        // gate.hold: says it has been entered, then waits until the test lets it go.
        system.addIIActor(new IIActorRef<Object>("gate", new Object(), system) {
            @Override
            public ActionResult callByActionName(String actionName, String args) {
                if (!"hold".equals(actionName)) {
                    return super.callByActionName(actionName, args);
                }
                entered.countDown();
                try {
                    return new ActionResult(release.await(30, TimeUnit.SECONDS), "released");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return new ActionResult(false, "interrupted");
                }
            }
        });
    }

    @AfterEach
    void tearDown() {
        release.countDown();
        system.terminateIIActors();
        system.terminate();
    }

    private void use(Interpreter i) {
        interpreter = i;
        interpreter.system = system;
        InterpreterIIAR self = new InterpreterIIAR("main", interpreter, system);
        interpreter.setSelfActorRef(self);
        system.addIIActor(self);
    }

    /** Starts {@code work} on a real (platform) thread of the test and returns the thread. */
    private static Thread start(Runnable work) {
        return Thread.ofPlatform().name("interpreter-concurrency-test").start(work);
    }

    // ---- 1. child names --------------------------------------------------------------------------

    @Test
    @DisplayName("a this.call whose child name is taken leaves the other call's child alone")
    void call_sameChildName_losingCallLeavesTheOtherChildAlone() throws Exception {
        use(new OneChildName());
        AtomicReference<ActionResult> first = new AtomicReference<>();
        Thread a = start(() -> first.set(interpreter.call("t274-gated.yaml")));
        assertTrue(entered.await(30, TimeUnit.SECONDS), "the first call's sub-workflow should be running");

        ActionResult second = interpreter.call("t274-gated.yaml");

        assertFalse(second.isSuccess(), "the second call cannot register a taken name: " + second.getResult());
        assertTrue(system.hasIIActor(OneChildName.NAME), "the first call's child must still be registered");
        release.countDown();
        a.join(TimeUnit.SECONDS.toMillis(30));
        assertTrue(first.get().isSuccess(), first.get().getResult());
        assertFalse(system.hasIIActor(OneChildName.NAME), "the first call removes its own child when it ends");
    }

    @Test
    @DisplayName("two interpreters calling the same file draw different child names")
    void generateChildName_twoInterpreters_differ() {
        Interpreter one = new Interpreter();
        Interpreter two = new Interpreter();
        assertFalse(one.generateChildName("x.yaml").equals(two.generateChildName("x.yaml")));
    }

    // ---- 2. stop request across reset ------------------------------------------------------------

    @Test
    @DisplayName("a stop requested after reset() has returned is kept for the run")
    void requestStop_afterReset_isKept() {
        use(new Interpreter());
        interpreter.reset();
        interpreter.requestStop();
        assertTrue(interpreter.isStopRequested());
    }

    // ---- 3, 4. fields read from other threads ----------------------------------------------------

    @Test
    @DisplayName("the run's fields and the listeners are volatile")
    void sharedFields_areVolatile() throws Exception {
        for (String name : List.of("code", "currentTransitionIndex", "currentState",
                "breakpointListener", "actionFailureListener")) {
            assertTrue(Modifier.isVolatile(Interpreter.class.getDeclaredField(name).getModifiers()),
                    name + " is written on the run's thread and read on others, so it must be volatile");
        }
    }

    @Test
    @DisplayName("another thread reads the state, the step and the code a run is at")
    void runningState_readFromAnotherThread_isCurrent() throws Exception {
        use(new Interpreter());
        String yaml = String.join("\n",
                "name: two-steps",
                "steps:",
                "  - states: [\"0\", \"1\"]",
                "    actions:",
                "      - actor: this",
                "        method: doNothing",
                "  - states: [\"1\", \"end\"]",
                "    actions:",
                "      - actor: gate",
                "        method: hold",
                "");
        interpreter.readYaml(new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)));
        AtomicReference<ActionResult> run = new AtomicReference<>();
        Thread t = start(() -> run.set(interpreter.runUntilEnd(10)));
        assertTrue(entered.await(30, TimeUnit.SECONDS), "the run should reach the second step");

        assertEquals("1", interpreter.getCurrentState());
        assertNotNull(interpreter.getCode());

        release.countDown();
        t.join(TimeUnit.SECONDS.toMillis(30));
        assertTrue(run.get().isSuccess(), run.get().getResult());
        assertEquals("end", interpreter.getCurrentState());
    }
}
