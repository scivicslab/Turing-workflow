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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.scivicslab.pojoactor.action.ActionResult;
import com.scivicslab.pojoactor.core.ManagedThreadPool;

/**
 * Verifies that a POOL action inside a sub-workflow started by {@code this.call} does not
 * deadlock the managed pool. {@code this.call} runs the child workflow on the pool real thread
 * that runs the calling action; with one pool real thread, a child's POOL action that waited on
 * the pool would wait for itself. It also verifies that a top-level POOL action still runs on one
 * of the pool's real threads.
 */
@Tag("Y_base.03")
@DisplayName("POOL actions in a sub-workflow on a 1-thread pool")
class NestedPoolCallTest {

    private IIActorSystem system;
    private Interpreter interpreter;
    private final AtomicInteger count = new AtomicInteger();
    private final AtomicInteger onPool = new AtomicInteger();
    private final AtomicInteger offPool = new AtomicInteger();
    private ExecutorService virtualThreads;

    @BeforeEach
    void setUp() {
        system = new IIActorSystem("nested-pool-call-test", 1);
        interpreter = new Interpreter.Builder()
                .loggerName("nested-pool-call-test")
                .team(system)
                .build();
        InterpreterIIAR interpreterActor = new InterpreterIIAR("main", interpreter, system);
        interpreter.setSelfActorRef(interpreterActor);
        system.addIIActor(interpreterActor);

        ManagedThreadPool pool = (ManagedThreadPool) system.getManagedThreadPool();
        IIActorRef<Object> counter = new IIActorRef<Object>("counter", new Object(), system) {
            @Override
            public ActionResult callByActionName(String actionName, String args) {
                if ("increment".equals(actionName)) {
                    count.incrementAndGet();
                    (pool.ownsCurrentThread() ? onPool : offPool).incrementAndGet();
                    return new ActionResult(true, "count=" + count.get());
                }
                if ("getCount".equals(actionName)) {
                    return new ActionResult(true, String.valueOf(count.get()));
                }
                return new ActionResult(false, "Unknown action: " + actionName);
            }
        };
        system.addIIActor(counter);
        virtualThreads = Executors.newVirtualThreadPerTaskExecutor();
    }

    @AfterEach
    void tearDown() {
        virtualThreads.shutdownNow();
        system.terminateIIActors();
        system.terminate();
    }

    private ActionResult runWithTimeout(String resource) throws Exception {
        InputStream yaml = getClass().getResourceAsStream(resource);
        interpreter.readYaml(yaml);
        return CompletableFuture.supplyAsync(() -> interpreter.runUntilEnd(100), virtualThreads)
                .get(10, TimeUnit.SECONDS);
    }

    @Test
    @DisplayName("this.call of a sub-workflow with POOL actions reaches end on a 1-thread pool")
    void call_subWorkflowWithPoolActions_onOneThreadPool_reachesEnd() throws Exception {
        ActionResult result = runWithTimeout("/workflows/main-calls-sub.yaml");

        assertTrue(result.isSuccess(), result.getResult());
        assertEquals(3, count.get());
        assertEquals(3, onPool.get(), "the child's POOL actions run on the pool's real thread");
        assertEquals(0, offPool.get());
    }

    @Test
    @DisplayName("a top-level POOL action started from a virtual thread runs on the pool's real thread")
    void poolAction_fromVirtualThread_runsOnPoolThread() throws Exception {
        ActionResult result = runWithTimeout("/workflows/pool-action-top.yaml");

        assertTrue(result.isSuccess(), result.getResult());
        assertEquals(1, onPool.get());
        assertEquals(0, offPool.get());
    }

    @Test
    @DisplayName("a top-level POOL action started from the test's real thread runs on the pool's real thread")
    void poolAction_fromTestRealThread_runsOnPoolThread() throws Exception {
        assertFalse(((ManagedThreadPool) system.getManagedThreadPool()).ownsCurrentThread());
        interpreter.readYaml(getClass().getResourceAsStream("/workflows/pool-action-top.yaml"));

        ActionResult result = interpreter.runUntilEnd(100);

        assertTrue(result.isSuccess(), result.getResult());
        assertEquals(1, onPool.get());
        assertEquals(0, offPool.get());
    }
}
