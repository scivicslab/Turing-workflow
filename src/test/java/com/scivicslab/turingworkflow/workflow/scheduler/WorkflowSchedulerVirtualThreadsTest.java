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

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.scivicslab.pojoactor.action.Action;
import com.scivicslab.pojoactor.action.ActionResult;
import com.scivicslab.turingworkflow.workflow.IIActorRef;
import com.scivicslab.turingworkflow.workflow.IIActorSystem;

/**
 * Verifies that the WorkflowScheduler fires its ticks on virtual threads and starts no real thread
 * of its own: the only real-thread pool is the ManagedThreadPool.
 */
@Tag("J_sched")
@DisplayName("WorkflowScheduler — ticks fire on virtual threads, no real thread of its own")
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class WorkflowSchedulerVirtualThreadsTest {

    /** Counts down a latch in its one action. */
    public static class Target extends IIActorRef<CountDownLatch> {
        Target(CountDownLatch latch, IIActorSystem system) {
            super("tgt", latch, system);
        }

        @Action("hit")
        public ActionResult hit(String args) {
            wrapped().countDown();
            return new ActionResult(true, "ok");
        }
    }

    @Test
    @DisplayName("after ticks have fired, no real thread named WorkflowScheduler-Worker exists")
    void noRealSchedulerThreads() throws Exception {
        IIActorSystem system = new IIActorSystem("ws-virtual", 1);
        CountDownLatch ticked = new CountDownLatch(8);
        system.addIIActor(new Target(ticked, system));
        try (WorkflowScheduler scheduler = new WorkflowScheduler(system, 4)) {
            for (int i = 0; i < 4; i++) {
                scheduler.scheduleAtFixedRate("t" + i, "tgt", "hit", "", 0, 5, TimeUnit.MILLISECONDS);
            }
            assertTrue(ticked.await(10, TimeUnit.SECONDS), "the tasks ticked");
            // Thread.getAllStackTraces() lists the live real (platform) threads only
            List<String> realSchedulerThreads = Thread.getAllStackTraces().keySet().stream()
                    .map(Thread::getName)
                    .filter(name -> name.startsWith("WorkflowScheduler-Worker"))
                    .toList();
            assertEquals(List.of(), realSchedulerThreads, "real threads started by the WorkflowScheduler");
        } finally {
            system.terminate();
        }
    }
}
