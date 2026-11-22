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
package com.scivicslab.pojoactor.action;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.scivicslab.turingworkflow.workflow.IIActorRef;

/**
 * Stress test: the first calls by name on a fresh actor, made by several threads at once, all
 * find their action. ActionDispatcher builds its table of actions on the first call; a thread
 * that saw the table while another was filling it answered "Unknown action".
 */
@Tag("S_svc.04")
@DisplayName("ActionDispatcher — concurrent first calls on a fresh actor all find their action")
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class ActionDispatcherConcurrencyTest {

    private static final int REPS = 1000;
    private static final int THREADS = 8;

    @Test
    @DisplayName("8 virtual threads make the first calls on each of 1000 fresh actors: none is unknown")
    void concurrentFirstCallsFindTheirAction() throws Exception {
        AtomicInteger failedReps = new AtomicInteger();
        AtomicReference<String> sample = new AtomicReference<>();
        for (int rep = 0; rep < REPS; rep++) {
            WideActor actor = new WideActor("wide" + rep);
            CyclicBarrier barrier = new CyclicBarrier(THREADS);
            AtomicInteger failures = new AtomicInteger();
            Thread[] threads = new Thread[THREADS];
            for (int t = 0; t < THREADS; t++) {
                String action = String.format("a%02d", (t * 7 + rep) % WideActor.N);
                threads[t] = Thread.ofVirtual().start(() -> {
                    try {
                        barrier.await();
                        ActionResult result = actor.callByActionName(action, "");
                        if (!result.isSuccess() || !action.equals(result.getResult())) {
                            failures.incrementAndGet();
                            sample.set(action + " -> " + result.getResult());
                        }
                    } catch (Exception e) {
                        failures.incrementAndGet();
                        sample.set(action + " -> " + e);
                    }
                });
            }
            for (Thread thread : threads) {
                thread.join();
            }
            if (failures.get() > 0) {
                failedReps.incrementAndGet();
            }
            actor.close();
        }
        assertEquals(0, failedReps.get(), "fresh actors where a first call failed: " + failedReps.get()
                + " of " + REPS + ", e.g. " + sample.get());
    }

    /** An actor with many actions, so that building the table takes a while. */
    public static class WideActor extends IIActorRef<Object> {
        static final int N = 40;

        WideActor(String name) {
            super(name, new Object());
        }
        @Action("a00") public ActionResult a00(String s) { return new ActionResult(true, "a00"); }
        @Action("a01") public ActionResult a01(String s) { return new ActionResult(true, "a01"); }
        @Action("a02") public ActionResult a02(String s) { return new ActionResult(true, "a02"); }
        @Action("a03") public ActionResult a03(String s) { return new ActionResult(true, "a03"); }
        @Action("a04") public ActionResult a04(String s) { return new ActionResult(true, "a04"); }
        @Action("a05") public ActionResult a05(String s) { return new ActionResult(true, "a05"); }
        @Action("a06") public ActionResult a06(String s) { return new ActionResult(true, "a06"); }
        @Action("a07") public ActionResult a07(String s) { return new ActionResult(true, "a07"); }
        @Action("a08") public ActionResult a08(String s) { return new ActionResult(true, "a08"); }
        @Action("a09") public ActionResult a09(String s) { return new ActionResult(true, "a09"); }
        @Action("a10") public ActionResult a10(String s) { return new ActionResult(true, "a10"); }
        @Action("a11") public ActionResult a11(String s) { return new ActionResult(true, "a11"); }
        @Action("a12") public ActionResult a12(String s) { return new ActionResult(true, "a12"); }
        @Action("a13") public ActionResult a13(String s) { return new ActionResult(true, "a13"); }
        @Action("a14") public ActionResult a14(String s) { return new ActionResult(true, "a14"); }
        @Action("a15") public ActionResult a15(String s) { return new ActionResult(true, "a15"); }
        @Action("a16") public ActionResult a16(String s) { return new ActionResult(true, "a16"); }
        @Action("a17") public ActionResult a17(String s) { return new ActionResult(true, "a17"); }
        @Action("a18") public ActionResult a18(String s) { return new ActionResult(true, "a18"); }
        @Action("a19") public ActionResult a19(String s) { return new ActionResult(true, "a19"); }
        @Action("a20") public ActionResult a20(String s) { return new ActionResult(true, "a20"); }
        @Action("a21") public ActionResult a21(String s) { return new ActionResult(true, "a21"); }
        @Action("a22") public ActionResult a22(String s) { return new ActionResult(true, "a22"); }
        @Action("a23") public ActionResult a23(String s) { return new ActionResult(true, "a23"); }
        @Action("a24") public ActionResult a24(String s) { return new ActionResult(true, "a24"); }
        @Action("a25") public ActionResult a25(String s) { return new ActionResult(true, "a25"); }
        @Action("a26") public ActionResult a26(String s) { return new ActionResult(true, "a26"); }
        @Action("a27") public ActionResult a27(String s) { return new ActionResult(true, "a27"); }
        @Action("a28") public ActionResult a28(String s) { return new ActionResult(true, "a28"); }
        @Action("a29") public ActionResult a29(String s) { return new ActionResult(true, "a29"); }
        @Action("a30") public ActionResult a30(String s) { return new ActionResult(true, "a30"); }
        @Action("a31") public ActionResult a31(String s) { return new ActionResult(true, "a31"); }
        @Action("a32") public ActionResult a32(String s) { return new ActionResult(true, "a32"); }
        @Action("a33") public ActionResult a33(String s) { return new ActionResult(true, "a33"); }
        @Action("a34") public ActionResult a34(String s) { return new ActionResult(true, "a34"); }
        @Action("a35") public ActionResult a35(String s) { return new ActionResult(true, "a35"); }
        @Action("a36") public ActionResult a36(String s) { return new ActionResult(true, "a36"); }
        @Action("a37") public ActionResult a37(String s) { return new ActionResult(true, "a37"); }
        @Action("a38") public ActionResult a38(String s) { return new ActionResult(true, "a38"); }
        @Action("a39") public ActionResult a39(String s) { return new ActionResult(true, "a39"); }
    }
}
