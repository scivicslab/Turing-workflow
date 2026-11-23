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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
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
 * Which thread runs {@code Interpreter.apply}'s children, which thread waits for them, and that
 * apply does not hang a ManagedThreadPool.
 *
 * <p>Each child here does what a sub-workflow's POOL action does: it gives work to the
 * ManagedThreadPool and waits for it. A child running on a thread that is not one of the pool's
 * real threads (a commonPool real thread, before this fix) queues that work behind every pool real
 * thread; when apply's own pool real thread is the only one, or every one is held by an apply,
 * nothing is left to run it. Each test therefore runs under a time limit, so a hang fails the test
 * instead of the build.</p>
 */
@DisplayName("Interpreter.apply: threads and no hang")
class ApplyThreadsTest {

    private static final Duration LIMIT = Duration.ofSeconds(20);

    private IIActorSystem system;

    @AfterEach
    void tearDown() {
        if (system != null) {
            // Fails any pool task still queued, which also releases a hung apply.
            system.terminate();
        }
    }

    /** What one child saw when it ran. */
    record ChildRun(String child, Thread thread, boolean onPoolRealThread) {}

    /** A parent interpreter, registered as an actor, and what its children record. */
    final class Parent {
        final Interpreter interpreter;
        final InterpreterIIAR ref;
        final List<ChildRun> runs = new CopyOnWriteArrayList<>();
        final AtomicInteger running = new AtomicInteger();
        final AtomicInteger mostAtOnce = new AtomicInteger();

        Parent(String name, int children, CyclicBarrier together) {
            interpreter = new Interpreter.Builder().loggerName(name).team(system).build();
            ref = new InterpreterIIAR(name, interpreter, system);
            interpreter.setSelfActorRef(ref);
            system.addIIActor(ref);
            for (int i = 0; i < children; i++) {
                addChild(name + "-child-" + i, together);
            }
        }

        private void addChild(String childName, CyclicBarrier together) {
            IIActorRef<Object> child = new IIActorRef<Object>(childName, new Object(), system) {
                @Override
                public ActionResult callByActionName(String actionName, String args) {
                    ManagedThreadPool pool = (ManagedThreadPool) system.getManagedThreadPool();
                    runs.add(new ChildRun(childName, Thread.currentThread(), pool.ownsCurrentThread()));
                    mostAtOnce.accumulateAndGet(running.incrementAndGet(), Math::max);
                    try {
                        if (together != null) {
                            // Only children that run at the same time get past this.
                            together.await(10, TimeUnit.SECONDS);
                        }
                        // Pool work from the child, as a sub-workflow's POOL action gives it.
                        String answer = this.ask(o -> "pool work done", pool).get();
                        return new ActionResult(true, childName + ": " + answer);
                    } catch (Exception e) {
                        return ActionResult.error(e, childName + "." + actionName);
                    } finally {
                        running.decrementAndGet();
                    }
                }
            };
            child.setParentName(ref.getName());
            ref.getNamesOfChildren().add(childName);
            system.addIIActor(child);
        }

        String applyAll() {
            return "{\"actor\":\"*\",\"method\":\"work\"}";
        }
    }

    @Test
    @DisplayName("one ManagedThreadPool real thread, one apply from a workflow step: it completes")
    void oneRealThreadOneApply() {
        system = new IIActorSystem("apply-one-thread", 1);
        Parent parent = new Parent("main", 2, null);
        parent.interpreter.readYaml(new ByteArrayInputStream("""
            name: apply-on-one-thread
            steps:
              - states: ["0", "end"]
                actions:
                  - actor: this
                    method: apply
                    arguments:
                      actor: "*"
                      method: work
            """.getBytes(StandardCharsets.UTF_8)));

        ActionResult result = assertTimeoutPreemptively(LIMIT, () -> parent.interpreter.runUntilEnd(10));

        assertTrue(result.isSuccess(), result.getResult());
        assertEquals(2, parent.runs.size());
    }

    @Test
    @DisplayName("on the mailbox virtual thread: children run in parallel on pool real threads, the virtual thread waits")
    void fromMailboxVirtualThread() {
        system = new IIActorSystem("apply-from-mailbox", 2);
        Parent parent = new Parent("main", 2, new CyclicBarrier(2));
        AtomicReference<Thread> waiter = new AtomicReference<>();

        ActionResult result = assertTimeoutPreemptively(LIMIT, () -> parent.ref.ask(i -> {
            waiter.set(Thread.currentThread());
            return i.apply(parent.applyAll());
        }).get());

        assertTrue(result.isSuccess(), result.getResult());
        assertEquals("Applied to 2 actors", result.getResult());
        assertTrue(waiter.get().isVirtual(), "apply waited on " + waiter.get());
        Set<Thread> threads = new HashSet<>();
        for (ChildRun run : parent.runs) {
            assertTrue(run.onPoolRealThread(), run.child() + " ran on " + run.thread());
            assertFalse(run.thread().isVirtual(), run.child() + " ran on " + run.thread());
            threads.add(run.thread());
        }
        assertEquals(2, threads.size(), "both children at once, each on its own pool real thread");
        assertEquals(2, parent.mostAtOnce.get());
    }

    @Test
    @DisplayName("on a pool real thread: apply ends, and every child runs on a pool real thread")
    void fromPoolRealThread() {
        system = new IIActorSystem("apply-from-pool", 2);
        Parent parent = new Parent("main", 3, null);
        AtomicReference<Thread> applying = new AtomicReference<>();

        ActionResult result = assertTimeoutPreemptively(LIMIT, () -> parent.ref.ask(i -> {
            applying.set(Thread.currentThread());
            return i.apply(parent.applyAll());
        }, system.getManagedThreadPool()).get());

        assertTrue(result.isSuccess(), result.getResult());
        assertEquals("Applied to 3 actors", result.getResult());
        assertFalse(applying.get().isVirtual());
        assertEquals(3, parent.runs.size());
        // A child's message is queued and taken in its actor's turn (tell/ask with a ManagedThreadPool). The
        // waiting pool real thread helps the pool while it waits, but another idle real thread may run a
        // child as well, so which pool real thread runs which child is not fixed.
        for (ChildRun run : parent.runs) {
            assertTrue(run.onPoolRealThread(), run.child() + " ran on " + run.thread());
        }
    }

    @Test
    @DisplayName("as many applies at once as the pool has real threads: none hangs")
    void parentsAsManyAsPoolThreads() {
        int n = 4;
        system = new IIActorSystem("apply-n-parents", n);
        List<Parent> parents = new ArrayList<>();
        for (int p = 0; p < n; p++) {
            parents.add(new Parent("parent-" + p, 2, null));
        }
        // Every parent holds a pool real thread before any of them applies.
        CountDownLatch allHeld = new CountDownLatch(n);

        List<ActionResult> results = assertTimeoutPreemptively(LIMIT, () -> {
            List<CompletableFuture<ActionResult>> futures = new ArrayList<>();
            for (Parent parent : parents) {
                futures.add(parent.ref.ask(i -> {
                    allHeld.countDown();
                    try {
                        allHeld.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return i.apply(parent.applyAll());
                }, system.getManagedThreadPool()));
            }
            List<ActionResult> done = new ArrayList<>();
            for (CompletableFuture<ActionResult> f : futures) {
                done.add(f.get());
            }
            return done;
        });

        for (ActionResult result : results) {
            assertTrue(result.isSuccess(), result.getResult());
            assertEquals("Applied to 2 actors", result.getResult());
        }
    }
}
