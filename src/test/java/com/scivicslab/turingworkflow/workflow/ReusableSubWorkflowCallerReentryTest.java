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

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.scivicslab.pojoactor.action.ActionResult;

/**
 * A sub-workflow run by {@code ReusableSubWorkflowCaller.call} whose apply children call the same
 * caller again.
 *
 * <p>{@code t275-outer.yaml} applies {@code callInner} to the children of {@code group}; each child
 * calls the caller with {@code t275-inner.yaml}, which marks {@code innerDone}; the outer then marks
 * {@code outerDone}. Called from a thread that is not a ManagedThreadPool real thread, the outer
 * call's apply runs on a pool real thread whose children wait for the caller that the calling
 * thread holds while it waits for them. Called on a pool real thread, everything runs on that one
 * real thread, and the inner calls reset and reload the interpreter the outer call is running.
 * Each test runs under a time limit, so a hang fails it.</p>
 */
@DisplayName("ReusableSubWorkflowCaller: a call from inside its own sub-workflow")
class ReusableSubWorkflowCallerReentryTest {

    private static final Duration LIMIT = Duration.ofSeconds(20);

    private IIActorSystem system;
    private ReusableSubWorkflowCaller caller;
    private final AtomicInteger inner = new AtomicInteger();
    private final AtomicInteger outer = new AtomicInteger();

    @AfterEach
    void tearDown() {
        if (system != null) {
            system.terminate();
        }
    }

    private void build(int poolThreads, int children) {
        system = new IIActorSystem("reusable-caller-reentry", poolThreads);
        caller = new ReusableSubWorkflowCaller("caller", system);
        system.addIIActor(caller);
        system.addIIActor(new IIActorRef<Object>("marks", new Object(), system) {
            @Override
            public ActionResult callByActionName(String actionName, String args) {
                (actionName.equals("innerDone") ? inner : outer).incrementAndGet();
                return new ActionResult(true, actionName);
            }
        });
        Interpreter groupInterpreter = new Interpreter.Builder().loggerName("group").team(system).build();
        InterpreterIIAR group = new InterpreterIIAR("group", groupInterpreter, system);
        groupInterpreter.setSelfActorRef(group);
        system.addIIActor(group);
        for (int i = 0; i < children; i++) {
            String name = "group-child-" + i;
            IIActorRef<Object> child = new IIActorRef<Object>(name, new Object(), system) {
                @Override
                public ActionResult callByActionName(String actionName, String args) {
                    return caller.callByActionName("call", "{\"yaml\":\"t275-inner.yaml\"}");
                }
            };
            child.setParentName("group");
            group.getNamesOfChildren().add(name);
            system.addIIActor(child);
        }
    }

    private ActionResult callOuter() {
        return caller.callByActionName("call", "{\"yaml\":\"t275-outer.yaml\"}");
    }

    @Test
    @DisplayName("called off the pool: the children's calls do not deadlock")
    void fromOffThePool() {
        build(1, 2);

        ActionResult result = assertTimeoutPreemptively(LIMIT, this::callOuter);

        assertTrue(result.isSuccess(), result.getResult());
        assertTrue(result.getResult().contains("t275-outer.yaml"), result.getResult());
        assertEquals(2, inner.get());
        assertEquals(1, outer.get());
    }

    @Test
    @DisplayName("called on a pool real thread: the inner calls leave the outer run intact")
    void fromAPoolRealThread() throws Exception {
        build(2, 2);

        ActionResult result = assertTimeoutPreemptively(LIMIT,
            () -> system.getManagedThreadPool().submit(this::callOuter).get());

        assertTrue(result.isSuccess(), result.getResult());
        assertTrue(result.getResult().contains("t275-outer.yaml"), result.getResult());
        assertEquals(2, inner.get());
        assertEquals(1, outer.get(), "the outer sub-workflow ran its last step");
        assertEquals(3, caller.getCallCount());
    }
}
