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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.scivicslab.pojoactor.action.Action;
import com.scivicslab.pojoactor.action.ActionResult;
import com.scivicslab.turingworkflow.workflow.IIActorRef;
import com.scivicslab.turingworkflow.workflow.IIActorSystem;

/**
 * Stress test: a scheduled action runs in the target actor's mailbox. An unguarded counter is
 * incremented both by messages told to the actor and by a scheduled action; if the scheduler runs
 * the action on its own thread, the two race and increments are lost.
 */
@Tag("J_sched")
@DisplayName("WorkflowScheduler — a scheduled action runs in the target actor's mailbox")
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class WorkflowSchedulerMailboxTest {

    /** A plain counter, guarded only by the actor's one-message-at-a-time loop. */
    public static final class Counter {
        int n;
    }

    /** Increments the counter in its action. */
    public static class CountActor extends IIActorRef<Counter> {
        final AtomicLong calls = new AtomicLong();

        CountActor(String name, Counter counter, IIActorSystem system) {
            super(name, counter, system);
        }

        @Action("inc")
        public ActionResult inc(String args) {
            calls.incrementAndGet();
            wrapped().n++;
            return new ActionResult(true, "ok");
        }
    }

    @Test
    @DisplayName("300 000 told increments and a scheduled increment every 20 µs: none is lost, in 5 runs")
    void scheduledActionsAndToldMessagesDoNotRace() throws Exception {
        Logger schedulerLogger = Logger.getLogger(WorkflowScheduler.class.getName());
        Level saved = schedulerLogger.getLevel();
        schedulerLogger.setLevel(Level.SEVERE); // the skipped-tick WARNINGs
        int runs = 5;
        int tells = 300_000;
        List<String> lossy = new ArrayList<>();
        try {
            for (int run = 0; run < runs; run++) {
                IIActorSystem system = new IIActorSystem("ws-mailbox-" + run, 2);
                Counter counter = new Counter();
                CountActor target = new CountActor("tgt", counter, system);
                system.addIIActor(target);
                WorkflowScheduler scheduler = new WorkflowScheduler(system, 2);
                try {
                    scheduler.scheduleAtFixedRate("t", "tgt", "inc", "", 0, 20, TimeUnit.MICROSECONDS);
                    Thread teller = Thread.ofVirtual().start(() -> {
                        for (int i = 0; i < tells; i++) {
                            target.tell(c -> c.n++);
                        }
                    });
                    teller.join();
                    scheduler.cancelTask("t");
                    Thread.sleep(100);
                    target.ask(c -> 0).get(60, TimeUnit.SECONDS); // every message before this has run
                    assertTrue(target.calls.get() > 0, "the scheduled action ran");
                    long lost = tells + target.calls.get() - target.ask(c -> c.n).get(60, TimeUnit.SECONDS);
                    if (lost != 0) {
                        lossy.add("run" + run + ":lost=" + lost);
                    }
                } finally {
                    scheduler.close();
                    system.terminate();
                }
            }
        } finally {
            schedulerLogger.setLevel(saved);
        }
        assertEquals(List.of(), lossy, "runs that lost increments: " + lossy.size() + " of " + runs);
    }
}
