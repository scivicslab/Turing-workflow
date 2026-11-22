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

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
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
 * Stress test: a once-task takes out of the map only itself, and a task is in the map before its
 * tick can run. Before, a once-task that finished removed whatever was registered under its id,
 * so a fixed-rate task scheduled under the same id kept firing with no entry cancelTask could
 * reach; and a once-task with no delay could run and finish before it was registered, leaving an
 * entry that never went away.
 */
@Tag("J_sched")
@DisplayName("WorkflowScheduler — registered before it ticks, a once-task removes only itself")
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class WorkflowSchedulerIdRaceTest {

    /** Counts the runs of its one action per argument. */
    public static class Target extends IIActorRef<Object> {
        final ConcurrentHashMap<String, AtomicInteger> runs = new ConcurrentHashMap<>();

        Target(IIActorSystem system) {
            super("tgt", new Object(), system);
        }

        @Action("hit")
        public ActionResult hit(String id) {
            runs.computeIfAbsent(id, k -> new AtomicInteger()).incrementAndGet();
            return new ActionResult(true, "ok");
        }

        int count(String id) {
            AtomicInteger c = runs.get(id);
            return c == null ? 0 : c.get();
        }
    }

    private final Logger schedulerLogger = Logger.getLogger(WorkflowScheduler.class.getName());
    private Level savedLevel;
    private IIActorSystem system;
    private Target target;
    private WorkflowScheduler scheduler;

    @BeforeEach
    void setUp() {
        savedLevel = schedulerLogger.getLevel();
        schedulerLogger.setLevel(Level.SEVERE);
        system = new IIActorSystem("ws-id-race", 2);
        target = new Target(system);
        system.addIIActor(target);
        scheduler = new WorkflowScheduler(system, 4);
    }

    @AfterEach
    void tearDown() {
        scheduler.close();
        system.terminate();
        schedulerLogger.setLevel(savedLevel);
    }

    @Test
    @DisplayName("once-task, then a fixed-rate task under the same id, then cancelTask: nothing keeps firing")
    void reusedIdIsCancellable() throws Exception {
        int reps = 300;
        for (int i = 0; i < reps; i++) {
            String id = "x" + i;
            scheduler.scheduleOnce(id, "tgt", "hit", id + "-once", 0, TimeUnit.MILLISECONDS);
            scheduler.scheduleAtFixedRate(id, "tgt", "hit", id, 1, 1, TimeUnit.MILLISECONDS);
            scheduler.cancelTask(id);
        }
        Thread.sleep(200);
        int[] before = new int[reps];
        for (int i = 0; i < reps; i++) {
            before[i] = target.count("x" + i);
        }
        Thread.sleep(500);
        int orphans = 0;
        for (int i = 0; i < reps; i++) {
            if (target.count("x" + i) > before[i]) {
                orphans++;
            }
        }
        assertEquals(0, orphans, "fixed-rate tasks still firing after cancelTask: " + orphans + " of " + reps);
        assertEquals(0, scheduler.getScheduledTaskCount());
    }

    @Test
    @DisplayName("5000 once-tasks with no delay: once all have run, no entry is left")
    void onceTasksLeaveNoEntry() throws Exception {
        int reps = 5000;
        for (int i = 0; i < reps; i++) {
            scheduler.scheduleOnce("o" + i, "tgt", "hit", "o" + i, 0, TimeUnit.MILLISECONDS);
        }
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (target.runs.size() < reps && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        Thread.sleep(200);
        assertEquals(reps, target.runs.size(), "once-tasks that ran");
        int stale = scheduler.getScheduledTaskCount();
        assertEquals(0, stale, "entries left after every once-task ran: " + stale + " of " + reps);
    }
}
