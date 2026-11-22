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
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CyclicBarrier;
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

/**
 * Stress test: several threads look up the same name, which the system creates at a lookup, at
 * once. Every lookup answers the one actor that got registered, none throws, and every instance
 * made for a lookup that lost the race is closed instead of keeping its message loop alive.
 */
@Tag("WorkflowTab_Lookup_260906_oo01")
@DisplayName("IIActorSystem — concurrent lookups of a name made at a lookup share one actor, the others are closed")
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class IIActorSystemAutoCreateRaceTest {

    private static final int REPS = 300;
    private static final int THREADS = 8;
    private final Logger systemLogger = Logger.getLogger(IIActorSystem.class.getName());
    private Level savedLevel;

    @BeforeEach
    void quiet() {
        savedLevel = systemLogger.getLevel();
        systemLogger.setLevel(Level.SEVERE); // the made-at-a-lookup WARNING, once per rep
    }

    @AfterEach
    void restore() {
        systemLogger.setLevel(savedLevel);
    }

    /** An actor the factory below makes for the name "probe". */
    static final class ProbeActor extends IIActorRef<Object> {
        ProbeActor(String name, IIActorSystem system) {
            super(name, new Object(), system);
        }
    }

    @Test
    @DisplayName("8 virtual threads look up \"probe\" at once: one actor answers all, none throws, the losers are closed")
    void concurrentLookupsShareOneActor() throws Exception {
        int failedReps = 0;
        List<String> samples = new ArrayList<>();
        for (int rep = 0; rep < REPS; rep++) {
            IIActorSystem system = new IIActorSystem("race-" + rep, 1);
            List<ProbeActor> made = new CopyOnWriteArrayList<>();
            system.addActorFactory(name -> {
                if (!name.equals("probe")) {
                    return null;
                }
                ProbeActor probe = new ProbeActor(name, system);
                made.add(probe);
                return probe;
            });
            CyclicBarrier barrier = new CyclicBarrier(THREADS);
            Set<IIActorRef<?>> answered = Collections.synchronizedSet(Collections.newSetFromMap(new IdentityHashMap<>()));
            AtomicInteger thrown = new AtomicInteger();
            Thread[] threads = new Thread[THREADS];
            for (int t = 0; t < THREADS; t++) {
                threads[t] = Thread.ofVirtual().start(() -> {
                    try {
                        barrier.await();
                        IIActorRef<?> found = system.getIIActor("probe");
                        if (found != null) {
                            answered.add(found);
                        }
                    } catch (Exception e) {
                        thrown.incrementAndGet();
                    }
                });
            }
            for (Thread thread : threads) {
                thread.join();
            }
            Object registered = system.getActor("probe");
            long leaked = made.stream().filter(p -> p != registered && p.isAlive()).count();
            if (thrown.get() > 0 || answered.size() != 1 || !answered.contains(registered) || leaked > 0) {
                failedReps++;
                if (samples.size() < 3) {
                    samples.add("rep" + rep + ":thrown=" + thrown.get() + ",answered=" + answered.size()
                            + ",made=" + made.size() + ",leaked=" + leaked);
                }
            }
            system.terminate();
        }
        assertEquals(0, failedReps, "reps that threw, answered two actors or leaked one: " + failedReps
                + " of " + REPS + " " + samples);
    }
}
