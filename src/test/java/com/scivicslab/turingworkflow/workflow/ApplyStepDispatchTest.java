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

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.scivicslab.pojoactor.action.ActionResult;
import com.scivicslab.pojoactor.core.ManagedThreadPool;

/**
 * Where a workflow step's {@code this.apply} runs. apply orchestrates
 * ({@code @Action(orchestrates = true)}), so a step with no {@code execution:} key runs it on the
 * interpreter's mailbox virtual thread, which waits while the children run in parallel on the
 * ManagedThreadPool's real threads. An explicit {@code execution: pool}, or a step whose workflow
 * loop itself runs on a pool real thread, runs it on that real thread, the children inline.
 * Each test runs under a time limit, so a hang fails it.
 */
@DisplayName("a workflow step's apply: where it runs")
class ApplyStepDispatchTest {

    private static final Duration LIMIT = Duration.ofSeconds(20);

    private IIActorSystem system;

    @AfterEach
    void tearDown() {
        if (system != null) {
            system.terminate();
        }
    }

    /** Where apply itself ran. */
    record ApplyRun(Thread thread, boolean onOwnMailbox, boolean onPoolRealThread) {}

    /** The workflow's interpreter, with what its apply and its children saw. */
    final class Parent {
        final List<ApplyRun> applies = new CopyOnWriteArrayList<>();
        final List<Thread> childThreads = new CopyOnWriteArrayList<>();
        final AtomicInteger running = new AtomicInteger();
        final AtomicInteger mostAtOnce = new AtomicInteger();
        final AtomicReference<InterpreterIIAR> ref = new AtomicReference<>();
        final Interpreter interpreter;

        Parent(int children, CyclicBarrier together) {
            interpreter = new Interpreter() {
                @Override
                public ActionResult apply(String actionDefinition) {
                    applies.add(new ApplyRun(Thread.currentThread(), ref.get().ownsCurrentThread(), onPool()));
                    return super.apply(actionDefinition);
                }
            };
            interpreter.logger = java.util.logging.Logger.getLogger("apply-step-dispatch");
            interpreter.system = system;
            InterpreterIIAR self = new InterpreterIIAR("main", interpreter, system);
            ref.set(self);
            interpreter.setSelfActorRef(self);
            system.addIIActor(self);
            for (int i = 0; i < children; i++) {
                String name = "main-child-" + i;
                IIActorRef<Object> child = new IIActorRef<Object>(name, new Object(), system) {
                    @Override
                    public ActionResult callByActionName(String actionName, String args) {
                        childThreads.add(Thread.currentThread());
                        mostAtOnce.accumulateAndGet(running.incrementAndGet(), Math::max);
                        try {
                            if (together != null) {
                                together.await(10, TimeUnit.SECONDS);   // only children in flight at once pass
                            }
                            // Pool work from the child, as a sub-workflow's POOL action gives it.
                            String answer = this.ask(o -> "done", system.getManagedThreadPool()).get();
                            return new ActionResult(true, answer);
                        } catch (Exception e) {
                            return ActionResult.error(e, name + "." + actionName);
                        } finally {
                            running.decrementAndGet();
                        }
                    }
                };
                child.setParentName("main");
                self.getNamesOfChildren().add(name);
                system.addIIActor(child);
            }
        }

        void read(String execution) {
            interpreter.readYaml(new ByteArrayInputStream(("""
                name: apply-step
                steps:
                  - states: ["0", "end"]
                    actions:
                      - actor: this
                        method: apply
                %s        arguments:
                          actor: "*"
                          method: work
                """.formatted(execution == null ? "" : "        execution: " + execution + "\n"))
                .getBytes(StandardCharsets.UTF_8)));
        }

        boolean onPool() {
            return ((ManagedThreadPool) system.getManagedThreadPool()).ownsCurrentThread();
        }
    }

    @Test
    @DisplayName("no execution key: apply waits on the mailbox virtual thread, the children run in parallel on pool real threads")
    void plainStepRunsChildrenInParallel() {
        system = new IIActorSystem("apply-step-plain", 2);
        Parent parent = new Parent(2, new CyclicBarrier(2));
        parent.read(null);

        // The workflow loop runs on this test's thread, which is not a pool real thread, as RunCLI's does.
        ActionResult result = assertTimeoutPreemptively(LIMIT, () -> parent.interpreter.runUntilEnd(5));

        assertTrue(result.isSuccess(), result.getResult());
        assertEquals(1, parent.applies.size());
        ApplyRun apply = parent.applies.get(0);
        assertTrue(apply.thread().isVirtual(), "apply ran on " + apply.thread());
        assertTrue(apply.onOwnMailbox(), "apply ran on the interpreter's mailbox virtual thread");
        Set<Thread> distinct = new HashSet<>(parent.childThreads);
        assertEquals(2, distinct.size(), "each child on its own thread");
        for (Thread t : distinct) {
            assertFalse(t.isVirtual(), "a child ran on " + t);
        }
        assertEquals(2, parent.mostAtOnce.get(), "both children in flight at once");
    }

    @Test
    @DisplayName("execution: pool: apply runs on a pool real thread and the children run there, one after another")
    void explicitPoolRunsChildrenInline() {
        system = new IIActorSystem("apply-step-pool", 1);
        Parent parent = new Parent(3, null);
        parent.read("pool");

        ActionResult result = assertTimeoutPreemptively(LIMIT, () -> parent.interpreter.runUntilEnd(5));

        assertTrue(result.isSuccess(), result.getResult());
        ApplyRun apply = parent.applies.get(0);
        assertTrue(apply.onPoolRealThread(), "apply ran on " + apply.thread());
        for (Thread t : parent.childThreads) {
            assertSame(apply.thread(), t);
        }
        assertEquals(1, parent.mostAtOnce.get());
    }

    @Test
    @DisplayName("a loop that runs on a pool real thread: apply runs there inline, and one pool real thread does not hang")
    void loopOnAPoolRealThreadFallsBackInline() {
        system = new IIActorSystem("apply-step-loop-on-pool", 1);
        Parent parent = new Parent(2, null);
        parent.read(null);

        // InterpreterIIAR.runUntilEnd runs the loop on the pool, as chat-89's S01 drives it.
        ActionResult result = assertTimeoutPreemptively(LIMIT,
            () -> parent.ref.get().callByActionName("runUntilEnd", "[5]"));

        assertTrue(result.isSuccess(), result.getResult());
        ApplyRun apply = parent.applies.get(0);
        assertTrue(apply.onPoolRealThread(), "apply ran on " + apply.thread());
        assertFalse(apply.onOwnMailbox());
        assertEquals(1, parent.mostAtOnce.get());
    }
}
