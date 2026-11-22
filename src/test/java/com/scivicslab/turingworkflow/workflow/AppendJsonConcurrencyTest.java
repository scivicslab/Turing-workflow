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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.scivicslab.pojoactor.action.ActionResult;

/**
 * Stress test: the built-in putJson and appendJson actions run on whatever thread calls them, so
 * two threads calling them on one actor at once must lose nothing. appendJson read the end of the
 * list and wrote past it in two steps, and two callers wrote the same index.
 */
@Tag("JsonState_Threads_260510_oo01")
@DisplayName("IIActorRef — appendJson and putJson from two threads at once lose nothing")
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class AppendJsonConcurrencyTest {

    private static final int RUNS = 5;
    private static final int PER_THREAD = 20_000;

    /** A bare interpreter-facing actor: the JSON state actions come from IIActorRef itself. */
    static class Holder extends IIActorRef<Object> {
        Holder(String name, IIActorSystem system) {
            super(name, new Object(), system);
        }
    }

    @Test
    @DisplayName("two virtual threads call appendJson 20 000 times each: all 40 000 values are in the list, in 5 runs")
    void concurrentAppendsKeepEveryValue() throws Exception {
        List<String> lossy = new ArrayList<>();
        for (int run = 0; run < RUNS; run++) {
            IIActorSystem system = new IIActorSystem("append-race-" + run, 1);
            try {
                Holder actor = new Holder("holder", system);
                system.addIIActor(actor);
                AtomicInteger failed = new AtomicInteger();
                CyclicBarrier barrier = new CyclicBarrier(2);
                Thread a = Thread.ofVirtual().start(() -> call(actor, "appendJson", "items", "a", barrier, failed));
                Thread b = Thread.ofVirtual().start(() -> call(actor, "appendJson", "items", "b", barrier, failed));
                a.join();
                b.join();
                int size = actor.json().select("items").size();
                if (size != 2 * PER_THREAD || failed.get() != 0) {
                    lossy.add("run" + run + ":size=" + size + ",failedResults=" + failed.get());
                }
            } finally {
                system.terminate();
            }
        }
        assertEquals(List.of(), lossy, "runs that lost values: " + lossy.size() + " of " + RUNS);
    }

    @Test
    @DisplayName("two virtual threads call putJson with 20 000 distinct paths each: all 40 000 are there")
    void concurrentPutsKeepEveryPath() throws Exception {
        IIActorSystem system = new IIActorSystem("put-race", 1);
        try {
            Holder actor = new Holder("holder", system);
            system.addIIActor(actor);
            AtomicInteger failed = new AtomicInteger();
            CyclicBarrier barrier = new CyclicBarrier(2);
            Thread a = Thread.ofVirtual().start(() -> call(actor, "putJson", "a", null, barrier, failed));
            Thread b = Thread.ofVirtual().start(() -> call(actor, "putJson", "b", null, barrier, failed));
            a.join();
            b.join();
            assertEquals(0, failed.get());
            assertEquals(2 * PER_THREAD, actor.json().size());
        } finally {
            system.terminate();
        }
    }

    /** appendJson: adds prefix+i to path; putJson (prefix null): writes path+i. */
    private static void call(IIActorRef<?> actor, String action, String path, String prefix,
                             CyclicBarrier barrier, AtomicInteger failed) {
        try {
            barrier.await();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        for (int i = 0; i < PER_THREAD; i++) {
            String args = prefix != null
                    ? "{\"path\":\"" + path + "\",\"value\":\"" + prefix + i + "\"}"
                    : "{\"path\":\"" + path + i + "\",\"value\":" + i + "}";
            ActionResult result = actor.callByActionName(action, args);
            if (!result.isSuccess()) {
                failed.incrementAndGet();
            }
        }
    }
}
