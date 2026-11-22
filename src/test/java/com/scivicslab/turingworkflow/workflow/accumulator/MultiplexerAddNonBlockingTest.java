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

package com.scivicslab.turingworkflow.workflow.accumulator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.scivicslab.turingworkflow.workflow.IIActorSystem;

/**
 * Verifies that logging through the output multiplexer never blocks the caller and never logs
 * through JUL from inside the log path: a log line handed to a multiplexer whose message loop is
 * busy returns at once, and an {@code add} whose hand-over fails writes the record to
 * {@code System.err} instead of recursing into the same handler.
 */
@DisplayName("MultiplexerAccumulatorIIAR — logging hands the record over without waiting")
class MultiplexerAddNonBlockingTest {

    private IIActorSystem system;
    private ExecutorService virtualThreads;
    private final CountDownLatch release = new CountDownLatch(1);
    private final ByteArrayOutputStream errBytes = new ByteArrayOutputStream();
    private PrintStream originalErr;

    @BeforeEach
    void setUp() {
        system = new IIActorSystem("multiplexer-add-test", 2);
        virtualThreads = Executors.newVirtualThreadPerTaskExecutor();
        originalErr = System.err;
        System.setErr(new PrintStream(errBytes, true, StandardCharsets.UTF_8));
    }

    @AfterEach
    void tearDown() {
        System.setErr(originalErr);
        release.countDown();
        virtualThreads.shutdownNow();
        system.terminateIIActors();
        system.terminate();
    }

    private String err() {
        return errBytes.toString(StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("a log line handed to a multiplexer whose message loop is busy returns promptly")
    void publish_multiplexerLoopBusy_returnsPromptly() throws Exception {
        MultiplexerAccumulator accumulator = new MultiplexerAccumulator();
        MultiplexerAccumulatorIIAR multiplexer = new MultiplexerAccumulatorIIAR("outputMultiplexer", accumulator, system);
        system.addIIActor(multiplexer);
        multiplexer.tell(acc -> {
            try {
                release.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        MultiplexerLogHandler handler = new MultiplexerLogHandler(system);
        LogRecord record = new LogRecord(Level.INFO, "hello from a busy multiplexer");
        record.setLoggerName("com.scivicslab.turingworkflow.workflow.Interpreter");

        CompletableFuture.runAsync(() -> handler.publish(record), virtualThreads).get(2, TimeUnit.SECONDS);

        release.countDown();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (accumulator.getCount() == 0 && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertEquals(1, accumulator.getCount(), "the record should be written once the loop is free");
    }

    @Test
    @DisplayName("an add whose hand-over fails writes to System.err and does not recurse into the handler")
    void add_handOverFails_writesToStderrWithoutRecursion() throws Exception {
        MultiplexerAccumulator failing = new MultiplexerAccumulator() {
            @Override
            public void add(String source, String type, String data) {
                throw new IllegalStateException("target refused " + data);
            }
        };
        MultiplexerAccumulatorIIAR multiplexer = new MultiplexerAccumulatorIIAR("outputMultiplexer", failing, system);
        system.addIIActor(multiplexer);
        // The add action's own logger: with the handler on it, logging there re-enters add.
        Logger addLogger = Logger.getLogger("outputMultiplexer");
        MultiplexerLogHandler handler = new MultiplexerLogHandler(system);
        addLogger.addHandler(handler);
        addLogger.setUseParentHandlers(false);
        Throwable[] thrown = new Throwable[1];
        try {
            CompletableFuture.runAsync(() -> {
                try {
                    multiplexer.add(new MultiplexerAccumulatorIIAR.AddArgs("test", "stdout", "the-record-data"));
                } catch (Throwable t) {
                    thrown[0] = t;
                }
            }, virtualThreads).get(5, TimeUnit.SECONDS);
        } finally {
            addLogger.removeHandler(handler);
            addLogger.setUseParentHandlers(true);
        }

        assertNull(thrown[0], () -> "add threw " + thrown[0]);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (!err().contains("the-record-data") && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertTrue(err().contains("the-record-data"), "the record should be written to System.err: " + err());
    }
}
