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

package com.scivicslab.turingworkflow.workflow.scheduler;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.scivicslab.pojoactor.core.ActionResult;
import com.scivicslab.pojoactor.core.CallableByActionName;
import com.scivicslab.turingworkflow.workflow.IIActorRef;
import com.scivicslab.turingworkflow.workflow.IIActorSystem;

/**
 * Tests for SchedulerIIAR (workflow) functionality.
 *
 * <p>This test suite verifies the SchedulerIIAR wrapper that provides
 * callByActionName interface for scheduler operations in workflows.</p>
 *
 * @author devteam@scivicslab.com
 * @since 2.11.0
 */
@DisplayName("SchedulerIIAR (Workflow) Specification by Example")
public class SchedulerIIARTest {

    private IIActorSystem system;
    private WorkflowScheduler scheduler;
    private SchedulerIIAR schedulerRef;

    @BeforeEach
    public void setUp() {
        system = new IIActorSystem("scheduler-iiar-test-system");
        scheduler = new WorkflowScheduler(system);
        schedulerRef = new SchedulerIIAR("scheduler", scheduler, system);
        system.addIIActor(schedulerRef);
    }

    @AfterEach
    public void tearDown() {
        if (scheduler != null) {
            scheduler.close();
        }
        if (system != null) {
            system.terminate();
        }
    }

    /**
     * Test actor that counts invocations.
     */
    private static class CounterActor implements CallableByActionName {
        private final AtomicInteger count = new AtomicInteger(0);

        @Override
        public ActionResult callByActionName(String actionName, String args) {
            if (actionName.equals("increment")) {
                int newCount = count.incrementAndGet();
                return new ActionResult(true, "Count: " + newCount);
            }
            return new ActionResult(false, "Unknown action");
        }

        public int getCount() {
            return count.get();
        }
    }

    /**
     * IIActorRef wrapper for CounterActor.
     */
    private static class CounterActorIIAR extends IIActorRef<CounterActor> {
        public CounterActorIIAR(String actorName, CounterActor object, IIActorSystem system) {
            super(actorName, object, system);
        }

        @Override
        public ActionResult callByActionName(String actionName, String args) {
            return this.object.callByActionName(actionName, args);
        }
    }

    /**
     * Example 1: Use SchedulerIIAR for action-based invocation.
     */
    @Test
    @DisplayName("Should work through SchedulerIIAR")
    public void testSchedulerIIAR() throws InterruptedException {
        CounterActor counter = new CounterActor();
        CounterActorIIAR counterRef = new CounterActorIIAR("counter", counter, system);
        system.addIIActor(counterRef);

        ActionResult result = schedulerRef.callByActionName("scheduleAtFixedRate",
            "iiar-task,counter,increment,,0,100,MILLISECONDS");

        assertTrue(result.isSuccess(), "Should schedule successfully");
        assertTrue(result.getResult().contains("Scheduled"));

        Thread.sleep(350);
        assertTrue(counter.getCount() >= 3, "Should execute through IIAR");

        ActionResult cancelResult = schedulerRef.callByActionName("cancel", "iiar-task");
        assertTrue(cancelResult.isSuccess());
        assertTrue(cancelResult.getResult().contains("Cancelled"));
    }

    /**
     * Example 2: Get task count through action.
     */
    @Test
    @DisplayName("Should get task count through action")
    public void testGetTaskCount() {
        CounterActor counter = new CounterActor();
        CounterActorIIAR counterRef = new CounterActorIIAR("counter", counter, system);
        system.addIIActor(counterRef);

        ActionResult result = schedulerRef.callByActionName("getTaskCount", "");
        assertTrue(result.isSuccess());
        assertEquals("0", result.getResult());

        scheduler.scheduleAtFixedRate("t1", "counter", "increment", "", 0, 1000, TimeUnit.MILLISECONDS);
        scheduler.scheduleAtFixedRate("t2", "counter", "increment", "", 0, 1000, TimeUnit.MILLISECONDS);

        result = schedulerRef.callByActionName("getTaskCount", "");
        assertTrue(result.isSuccess());
        assertEquals("2", result.getResult());

        scheduler.cancelTask("t1");

        result = schedulerRef.callByActionName("getTaskCount", "");
        assertTrue(result.isSuccess());
        assertEquals("1", result.getResult());
    }

    /**
     * Example 3: Check if task is scheduled through action.
     */
    @Test
    @DisplayName("Should check if task is scheduled through action")
    public void testIsScheduled() {
        CounterActor counter = new CounterActor();
        CounterActorIIAR counterRef = new CounterActorIIAR("counter", counter, system);
        system.addIIActor(counterRef);

        ActionResult result = schedulerRef.callByActionName("isScheduled", "check-task");
        assertTrue(result.isSuccess());
        assertEquals("false", result.getResult());

        scheduler.scheduleAtFixedRate("check-task", "counter", "increment", "", 0, 1000, TimeUnit.MILLISECONDS);

        result = schedulerRef.callByActionName("isScheduled", "check-task");
        assertTrue(result.isSuccess());
        assertEquals("true", result.getResult());

        scheduler.cancelTask("check-task");

        result = schedulerRef.callByActionName("isScheduled", "check-task");
        assertTrue(result.isSuccess());
        assertEquals("false", result.getResult());
    }

    /**
     * Example 4: Use scheduleWithFixedDelay through callByActionName.
     */
    @Test
    @DisplayName("Should schedule with fixed delay through callByActionName")
    public void testScheduleWithFixedDelayThroughAction() throws InterruptedException {
        CounterActor counter = new CounterActor();
        CounterActorIIAR counterRef = new CounterActorIIAR("counter", counter, system);
        system.addIIActor(counterRef);

        ActionResult result = schedulerRef.callByActionName("scheduleWithFixedDelay",
            "delay-task,counter,increment,,0,100,MILLISECONDS");

        assertTrue(result.isSuccess(), "Should schedule successfully");
        assertTrue(result.getResult().contains("Scheduled"));

        Thread.sleep(350);
        assertTrue(counter.getCount() >= 3, "Should execute multiple times");

        scheduler.cancelTask("delay-task");
    }

    /**
     * Example 5: Use scheduleOnce through callByActionName.
     */
    @Test
    @DisplayName("Should schedule once through callByActionName")
    public void testScheduleOnceThroughAction() throws InterruptedException {
        CounterActor counter = new CounterActor();
        CounterActorIIAR counterRef = new CounterActorIIAR("counter", counter, system);
        system.addIIActor(counterRef);

        ActionResult result = schedulerRef.callByActionName("scheduleOnce",
            "once-task,counter,increment,,100,MILLISECONDS");

        assertTrue(result.isSuccess(), "Should schedule successfully");
        assertTrue(result.getResult().contains("Scheduled"));

        Thread.sleep(50);
        assertEquals(0, counter.getCount(), "Should not execute yet");

        Thread.sleep(100);
        assertEquals(1, counter.getCount(), "Should execute exactly once");
    }

    /**
     * Example 6: Handle unknown action in callByActionName.
     */
    @Test
    @DisplayName("Should handle unknown action gracefully")
    public void testUnknownAction() {
        ActionResult result = schedulerRef.callByActionName("unknownAction", "some,args");

        assertFalse(result.isSuccess(), "Unknown action should fail");
        assertTrue(result.getResult().contains("Unknown action"), "Should report unknown action");
    }

    /**
     * Example 7: Handle invalid arguments for scheduleAtFixedRate.
     */
    @Test
    @DisplayName("Should handle invalid arguments for scheduleAtFixedRate")
    public void testInvalidArgumentsForFixedRate() {
        ActionResult result = schedulerRef.callByActionName("scheduleAtFixedRate",
            "task,counter,action,args,0,100");

        assertFalse(result.isSuccess(), "Should fail with invalid arguments");
        assertTrue(result.getResult().contains("requires"), "Should report required arguments");
    }

    /**
     * Example 8: Handle invalid arguments for scheduleWithFixedDelay.
     */
    @Test
    @DisplayName("Should handle invalid arguments for scheduleWithFixedDelay")
    public void testInvalidArgumentsForFixedDelay() {
        ActionResult result = schedulerRef.callByActionName("scheduleWithFixedDelay",
            "task,counter,action,args,0,100");

        assertFalse(result.isSuccess(), "Should fail with invalid arguments");
        assertTrue(result.getResult().contains("requires"), "Should report required arguments");
    }

    /**
     * Example 9: Handle invalid arguments for scheduleOnce.
     */
    @Test
    @DisplayName("Should handle invalid arguments for scheduleOnce")
    public void testInvalidArgumentsForOnce() {
        ActionResult result = schedulerRef.callByActionName("scheduleOnce",
            "task,counter,action,args,100");

        assertFalse(result.isSuccess(), "Should fail with invalid arguments");
        assertTrue(result.getResult().contains("requires"), "Should report required arguments");
    }

    /**
     * Example 10: Handle number format error in callByActionName.
     */
    @Test
    @DisplayName("Should handle number format error")
    public void testNumberFormatError() {
        ActionResult result = schedulerRef.callByActionName("scheduleAtFixedRate",
            "task,counter,action,args,abc,100,SECONDS");

        assertFalse(result.isSuccess(), "Should fail with number format error");
        assertTrue(result.getResult().contains("Error"), "Should report error");
    }

    /**
     * Example 11: Handle invalid TimeUnit value.
     */
    @Test
    @DisplayName("Should handle invalid TimeUnit value")
    public void testInvalidTimeUnit() {
        ActionResult result = schedulerRef.callByActionName("scheduleAtFixedRate",
            "task,counter,action,args,0,100,INVALID_UNIT");

        assertFalse(result.isSuccess(), "Should fail with invalid TimeUnit");
        assertTrue(result.getResult().contains("Error"), "Should report error");
    }
}
