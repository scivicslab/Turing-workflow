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

import static com.scivicslab.turingworkflow.workflow.OwnStepFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.scivicslab.pojoactor.action.ActionResult;
import com.scivicslab.pojoactor.core.ManagedThreadPool;

/**
 * Which thread runs an own step's action, for the ways a workflow is started.
 *
 * <p>Three threads are recorded for one workflow: the thread that runs the interpreter's
 * {@code runUntilEnd} (the loop), the thread that runs a step ({@code probe}, a plain actor the
 * first step calls), and the thread that runs the own-step action ({@code this.setCurrentState},
 * recorded by the interpreter's own method). The action runs directly on the thread that runs the
 * step, a real thread of the ManagedThreadPool, and never on the actor's message loop. That is the
 * table the design document gives for each way to start a workflow.</p>
 */
@DisplayName("which thread runs an own step's action")
class OwnStepThreadsTest {

    private IIActorSystem system;

    @AfterEach
    void tearDown() {
        if (system != null) {
            system.terminate();
        }
    }

    /** An interpreter that records the thread of its loop and of {@code setCurrentState}. */
    private static final class Recording extends Interpreter {
        final AtomicReference<Thread> loop = new AtomicReference<>();
        final AtomicReference<Thread> action = new AtomicReference<>();

        @Override
        public ActionResult runUntilEnd(int maxIterations) {
            loop.set(Thread.currentThread());
            return super.runUntilEnd(maxIterations);
        }

        @Override
        public void setCurrentState(String state) {
            action.set(Thread.currentThread());
            super.setCurrentState(state);
        }
    }

    /** A plain actor that records the thread that runs a step addressed to it. */
    private static final class Probe extends IIActorRef<Object> {
        final AtomicReference<Thread> step = new AtomicReference<>();

        Probe(IIActorSystem system) {
            super("probe", new Object(), system);
        }

        @Override
        public ActionResult callByActionName(String actionName, String args) {
            step.set(Thread.currentThread());
            return new ActionResult(true, "probed");
        }
    }

    private record Subject(Workflow parent, Recording interpreter, InterpreterIIAR ref, Probe probe) {}

    private Subject subject() {
        system = new IIActorSystem("own-step-threads", 2);
        Workflow parent = workflow(system, "parent", null);
        Probe probe = new Probe(system);
        system.addIIActor(probe);
        Recording interpreter = new Recording();
        interpreter.logger = java.util.logging.Logger.getLogger("own-step-threads");
        interpreter.system = system;
        InterpreterIIAR ref = new InterpreterIIAR("child", interpreter, system);
        interpreter.setSelfActorRef(ref);
        ref.setParentName("parent");
        system.getIIActor("parent").getNamesOfChildren().add("child");
        system.addIIActor(ref);
        interpreter.readYaml(new ByteArrayInputStream(("name: threads\nsteps:\n"
            + "  - states: [\"0\", \"1\"]\n    actions:\n"
            + action("probe", "here", "[\"x\"]")
            + action("this", "setCurrentState", "\"0\"")
            + "  - states: [\"1\", \"end\"]\n    actions:\n"
            + action("this", "doNothing", "[\"x\"]")).getBytes(StandardCharsets.UTF_8)));
        return new Subject(parent, interpreter, ref, probe);
    }

    /** Whether a thread is a real thread of a ManagedThreadPool, which names its real threads {@code managed-pool-N-thread-M}. */
    private static boolean onPool(Thread thread) {
        return !thread.isVirtual() && thread.getName().startsWith("managed-pool-");
    }

    @Test
    @DisplayName("run command: the loop is on the caller's real thread; the step and its action are on one ManagedThreadPool real thread")
    void loopOnTheCallersRealThread() {
        Subject s = subject();

        // The caller is a real thread of the test, as the run command's main thread is.
        ActionResult result = assertTimeoutPreemptively(LIMIT, () -> s.interpreter().runUntilEnd(20));

        assertTrue(result.isSuccess(), result.getResult());
        assertFalse(s.interpreter().loop.get().isVirtual(), "the loop ran on " + s.interpreter().loop.get());
        assertTrue(onPool(s.probe().step.get()), "the step ran on " + s.probe().step.get());
        assertSame(s.probe().step.get(), s.interpreter().action.get(),
            "the own-step action ran on the thread that ran the step");
        assertNotSame(s.interpreter().loop.get(), s.interpreter().action.get(),
            "the loop's real thread waits for the step, which a pool real thread runs");
    }

    @Test
    @DisplayName("a parent runs the child with runUntilEnd: the child's loop, its step and its action are on one ManagedThreadPool real thread")
    void parentRunsTheChild() {
        Subject s = subject();
        s.parent().interpreter().readYaml(new ByteArrayInputStream(("name: parent\nsteps:\n"
            + "  - states: [\"0\", \"end\"]\n    actions:\n"
            + action("child", "runUntilEnd", "[20]")).getBytes(StandardCharsets.UTF_8)));

        ActionResult result = assertTimeoutPreemptively(LIMIT, () -> s.parent().interpreter().runUntilEnd(10));

        assertTrue(result.isSuccess(), result.getResult());
        Thread loop = s.interpreter().loop.get();
        assertTrue(onPool(loop), "the child's loop ran on " + loop + ", a ManagedThreadPool real thread");
        assertSame(loop, s.probe().step.get(), "a POOL step already on a pool real thread runs there");
        assertSame(loop, s.interpreter().action.get(), "the own-step action ran on that thread");
    }

    @Test
    @DisplayName("the loop is the actor's own message: the loop is on its message loop's virtual thread; the step and its action are on a pool real thread")
    void loopIsTheActorsOwnMessage() throws Exception {
        Subject s = subject();
        CompletableFuture<ActionResult> run = new CompletableFuture<>();
        s.ref().tell((Interpreter i) -> run.complete(i.runUntilEnd(20)));

        ActionResult result = assertTimeoutPreemptively(LIMIT, () -> run.get());

        assertTrue(result.isSuccess(), result.getResult());
        assertTrue(s.interpreter().loop.get().isVirtual(), "the loop ran on " + s.interpreter().loop.get());
        assertTrue(onPool(s.probe().step.get()), "the step ran on " + s.probe().step.get());
        assertSame(s.probe().step.get(), s.interpreter().action.get(),
            "the own-step action ran on the thread that ran the step, not on the message loop");
        assertNotSame(s.interpreter().loop.get(), s.interpreter().action.get());
    }
}
