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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.scivicslab.pojoactor.action.ActionResult;

/**
 * A stop requested while the interpreter is paused at a breakpoint must end the run.
 */
@Tag("execCode_StopAtBreakpoint_260512_oo01")
@DisplayName("Interpreter - requestStop() ends a run paused at a breakpoint")
public class StopAtBreakpointTest {

    private static final String WORKFLOW = String.join("\n",
            "name: stop-at-breakpoint",
            "steps:",
            "  - states: [\"0\", \"end\"]",
            "    breakpoint: true",
            "");

    private IIActorSystem system;
    private ExecutorService runner;

    @BeforeEach
    public void setUp() {
        system = new IIActorSystem("stop-at-breakpoint-test");
        runner = Executors.newSingleThreadExecutor();
    }

    @AfterEach
    public void tearDown() {
        runner.shutdownNow();
        system.terminate();
    }

    @Test
    public void requestStop_whilePausedAtBreakpoint_runUntilEndReturnsStopped() throws Exception {
        Interpreter interpreter = new Interpreter.Builder()
                .loggerName("stop-at-breakpoint")
                .team(system)
                .build();
        interpreter.readYaml(new ByteArrayInputStream(WORKFLOW.getBytes(StandardCharsets.UTF_8)));

        CountDownLatch atBreakpoint = new CountDownLatch(1);
        Thread[] runnerThread = new Thread[1];
        interpreter.setBreakpointListener((transition, state) -> {
            runnerThread[0] = Thread.currentThread();
            atBreakpoint.countDown();
        });

        Future<ActionResult> run = runner.submit(() -> interpreter.runUntilEnd(10));
        assertTrue(atBreakpoint.await(5, TimeUnit.SECONDS), "run never reached the breakpoint");

        // Request the stop only once the runner is really waiting on the pause lock;
        // a stop that arrives before the wait starts is seen by the loop condition and
        // would not exercise the lost-wakeup path.
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (runnerThread[0].getState() != Thread.State.WAITING) {
            assertTrue(System.nanoTime() < deadline, "runner never started waiting at the breakpoint");
            Thread.sleep(10);
        }

        interpreter.requestStop();

        ActionResult result;
        try {
            result = run.get(5, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            // Release the stuck runner so the test JVM is not left with a waiting thread.
            interpreter.resume();
            fail("runUntilEnd did not return within 5 s after requestStop() at a breakpoint");
            return;
        }

        assertFalse(result.isSuccess(), "a stopped run must not report success");
        assertTrue(result.getResult().contains("Stopped at breakpoint"),
                "the result must report the stop, was: " + result.getResult());
    }
}
