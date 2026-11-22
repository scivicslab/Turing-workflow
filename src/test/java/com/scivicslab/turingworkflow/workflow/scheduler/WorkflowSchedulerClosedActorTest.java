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
package com.scivicslab.turingworkflow.workflow.scheduler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.scivicslab.pojoactor.action.Action;
import com.scivicslab.pojoactor.action.ActionResult;
import com.scivicslab.turingworkflow.workflow.IIActorRef;
import com.scivicslab.turingworkflow.workflow.IIActorSystem;

/**
 * Verifies that a scheduled task whose actor is closed, or not registered, stops with one WARNING
 * naming it, instead of logging at every tick; and that a tick does not create the actor it names.
 */
@Tag("J_sched")
@DisplayName("WorkflowScheduler — a task whose actor is closed or not registered stops")
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class WorkflowSchedulerClosedActorTest {

    /** Counts the runs of its one action. */
    public static class Target extends IIActorRef<AtomicInteger> {
        Target(String name, IIActorSystem system) {
            super(name, new AtomicInteger(), system);
        }

        @Action("hit")
        public ActionResult hit(String args) {
            wrapped().incrementAndGet();
            return new ActionResult(true, "ok");
        }
    }

    private final Logger schedulerLogger = Logger.getLogger(WorkflowScheduler.class.getName());
    private final List<LogRecord> records = new CopyOnWriteArrayList<>();
    private final Handler capture = new Handler() {
        @Override
        public void publish(LogRecord record) {
            records.add(record);
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }
    };
    private boolean savedUseParent;
    private IIActorSystem system;
    private WorkflowScheduler scheduler;

    @BeforeEach
    void setUp() {
        savedUseParent = schedulerLogger.getUseParentHandlers();
        schedulerLogger.setUseParentHandlers(false);
        schedulerLogger.addHandler(capture);
        system = new IIActorSystem("ws-closed", 2);
        scheduler = new WorkflowScheduler(system, 2);
    }

    @AfterEach
    void tearDown() {
        scheduler.close();
        system.terminate();
        schedulerLogger.removeHandler(capture);
        schedulerLogger.setUseParentHandlers(savedUseParent);
    }

    @Test
    @DisplayName("fixed-rate and fixed-delay tasks of a closed actor stop, one WARNING each")
    void tasksOfClosedActorStop() throws Exception {
        Target target = new Target("tgt", system);
        system.addIIActor(target);
        scheduler.scheduleAtFixedRate("rate", "tgt", "hit", "", 0, 10, TimeUnit.MILLISECONDS);
        scheduler.scheduleWithFixedDelay("delay", "tgt", "hit", "", 0, 10, TimeUnit.MILLISECONDS);
        Thread.sleep(100);
        assertTrue(target.wrapped().get() > 0, "the tasks ran before the actor was closed");

        records.clear(); // before close(): a tick during close() already logs its task as stopped
        target.close();
        Thread.sleep(1000);

        assertEquals(2, stopped(), "one WARNING per stopped task: " + messages());
        assertEquals(2, records.size(), "nothing else logged in the second after close: " + messages());
        assertFalse(scheduler.isScheduled("rate"));
        assertFalse(scheduler.isScheduled("delay"));
        assertEquals(0, scheduler.getScheduledTaskCount());
    }

    @Test
    @DisplayName("a task naming an actor that is not registered stops, and the tick does not create it")
    void taskOfMissingActorStopsWithoutCreatingIt() throws Exception {
        scheduler.scheduleAtFixedRate("rate", "out", "println", "x", 0, 10, TimeUnit.MILLISECONDS);
        Thread.sleep(500);
        assertNull(system.getActor("out"), "a tick created the actor it names");
        assertEquals(1, stopped(), "one WARNING for the stopped task: " + messages());
        assertFalse(scheduler.isScheduled("rate"));
    }

    private long stopped() {
        return records.stream()
                .filter(r -> r.getLevel() == Level.WARNING && r.getMessage().startsWith("Stopped scheduled task"))
                .count();
    }

    private List<String> messages() {
        return records.stream().map(r -> r.getLevel() + " " + r.getMessage()).toList();
    }
}
