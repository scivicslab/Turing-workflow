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

import static com.scivicslab.turingworkflow.workflow.OwnStepFixtures.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import com.scivicslab.pojoactor.action.ActionResult;

/**
 * What the own-step rule leaves as it was ({@code OwnStepDispatch_261123_oo01}): an action that
 * another actor sends, or a workflow's step that names another actor, goes through the target's
 * mailbox, and the target's mailbox does not take it until the message it runs has ended.
 *
 * <p>These cases hold one actor's turn: its mailbox's virtual thread is inside a message that
 * waits for a latch. Work sent to that actor from outside must wait for the latch, and must not
 * start before it. Each case runs under {@link OwnStepFixtures#LIMIT}.</p>
 */
@DisplayName("what another actor sends still goes through the actor's mailbox")
class OwnStepMailboxRuleTest {

    /** How long a call that must wait is watched for completing. */
    private static final long WATCH_MILLIS = 500;

    /** Holds {@code actor}'s turn until {@code release}: its mailbox virtual thread is inside this message. */
    private static void holdTurn(Workflow actor, CountDownLatch release) throws InterruptedException {
        CountDownLatch holding = new CountDownLatch(1);
        actor.ref().ask((Interpreter i) -> {
            holding.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return null;
        });
        assertTrue(holding.await(10, TimeUnit.SECONDS), "the actor's turn is held");
    }

    /** The actions another actor sends through the mailbox, and the ones this test does not assert about. */
    @TestFactory
    @DisplayName("an action called from outside waits for the actor's turn: every action of the action table")
    Stream<DynamicTest> externalCallsQueue() {
        List<DynamicTest> tests = new ArrayList<>();
        for (ActionCase c : CASES.values()) {
            if (!c.mailbox()) {
                continue;   // putJson, getJson, sleep and the rest do not touch the interpreter's state
            }
            tests.add(dynamicTest(c.action() + " called from outside waits, then runs", () -> {
                Path dir = Files.createTempDirectory("own-step");
                IIActorSystem system = new IIActorSystem("own-step-external");
                CountDownLatch release = new CountDownLatch(1);
                try (ExecutorService virtual = Executors.newVirtualThreadPerTaskExecutor()) {
                    workflow(system, "parent", null);
                    Workflow child = workflow(system, "child", "parent");
                    workflow(system, "kid", "child");
                    Path file = dir.resolve("w.yaml");
                    Path runTarget = dir.resolve("r.yaml");
                    Files.writeString(runTarget, RUN_TARGET_YAML);
                    Files.writeString(file, "name: w\nsteps:\n  - states: [\"0\", \"end\"]\n    actions:\n"
                        + action("this", "doNothing", "[\"x\"]"));
                    child.interpreter().readYaml(file);
                    holdTurn(child, release);

                    // The caller is another actor's step, or any caller by name: a virtual thread of its own.
                    CompletableFuture<ActionResult> call = CompletableFuture.supplyAsync(
                        () -> child.ref().callByActionName(c.action(), fill(c.jsonArguments(), file, runTarget)), virtual);

                    assertThrows(TimeoutException.class, () -> call.get(WATCH_MILLIS, TimeUnit.MILLISECONDS),
                        c.action() + " ran while the actor's turn was held");
                    release.countDown();
                    ActionResult result = assertTimeoutPreemptively(LIMIT, () -> call.get());
                    assertNotNull(result);
                } finally {
                    release.countDown();
                    system.terminate();
                    delete(dir);
                }
            }));
        }
        return tests.stream();
    }

    /** A step that waits for a latch, so that a workflow can be held inside a step. */
    private static final class Blocker extends IIActorRef<Object> {
        private final CountDownLatch inside = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);

        Blocker(IIActorSystem system) {
            super("blocker", new Object(), system);
        }

        @Override
        public ActionResult callByActionName(String actionName, String args) {
            inside.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return new ActionResult(true, "released");
        }
    }

    @Test
    @DisplayName("while the actor runs its workflow as its own message, a call from outside starts only after the workflow ended")
    void externalCallStartsAfterTheRunningWorkflowEnded() throws Exception {
        IIActorSystem system = new IIActorSystem("own-step-after-run");
        Blocker blocker = new Blocker(system);
        system.addIIActor(blocker);
        try (ExecutorService virtual = Executors.newVirtualThreadPerTaskExecutor()) {
            workflow(system, "parent", null);
            Workflow child = workflow(system, "child", "parent");
            child.interpreter().readYaml(new java.io.ByteArrayInputStream((
                "name: holds\nsteps:\n  - states: [\"0\", \"end\"]\n    actions:\n"
                + action("blocker", "hold", "[\"x\"]")).getBytes(StandardCharsets.UTF_8)));
            AtomicReference<String> stateWhenTheRunEnded = new AtomicReference<>();
            CompletableFuture<ActionResult> run = new CompletableFuture<>();
            // The loop is the actor's own message, on its mailbox virtual thread.
            child.ref().tell((Interpreter i) -> {
                ActionResult r = i.runUntilEnd(10);
                stateWhenTheRunEnded.set(i.getCurrentState());
                run.complete(r);
            });
            assertTrue(blocker.inside.await(10, TimeUnit.SECONDS), "the workflow is inside its step");

            // Another actor's step: a parent's actor: child, method: setCurrentState.
            CompletableFuture<ActionResult> outside = CompletableFuture.supplyAsync(
                () -> child.ref().callByActionName("setCurrentState", "[\"outside\"]"), virtual);

            assertThrows(TimeoutException.class, () -> outside.get(WATCH_MILLIS, TimeUnit.MILLISECONDS),
                "the call from outside ran while the workflow was running");
            assertNotEquals("outside", child.interpreter().getCurrentState());
            blocker.release.countDown();

            assertTrue(assertTimeoutPreemptively(LIMIT, () -> run.get()).isSuccess());
            assertTrue(assertTimeoutPreemptively(LIMIT, () -> outside.get()).isSuccess());
            assertEquals("end", stateWhenTheRunEnded.get(), "the workflow ended before the call from outside started");
            assertEquals("outside", child.interpreter().getCurrentState(), "the call from outside ran after it");
        } finally {
            blocker.release.countDown();
            system.terminate();
        }
    }

    @Test
    @DisplayName("a sub-workflow's step that names its parent goes through the parent's mailbox, inside the parent's own step")
    void subWorkflowStepToTheParentQueues() throws Exception {
        IIActorSystem system = new IIActorSystem("own-step-to-parent");
        CountDownLatch release = new CountDownLatch(1);
        try (ExecutorService virtual = Executors.newVirtualThreadPerTaskExecutor()) {
            Workflow parent = workflow(system, "parent", null);
            parent.interpreter().readYaml(new java.io.ByteArrayInputStream((
                "name: parent\nsteps:\n  - states: [\"0\", \"end\"]\n    actions:\n"
                + action("this", "call", "\"t795-to-parent.yaml\"")).getBytes(StandardCharsets.UTF_8)));
            holdTurn(parent, release);

            // The parent runs this.call as its own step; the sub-workflow's step names the parent (..).
            CompletableFuture<ActionResult> run = CompletableFuture.supplyAsync(
                () -> parent.interpreter().runUntilEnd(10), virtual);

            assertThrows(TimeoutException.class, () -> run.get(WATCH_MILLIS, TimeUnit.MILLISECONDS),
                "the sub-workflow's step to its parent ran while the parent's turn was held: the own-step marker leaked");
            assertNotEquals("viaSubWorkflow", parent.interpreter().getCurrentState());
            release.countDown();

            ActionResult result = assertTimeoutPreemptively(LIMIT, () -> run.get());
            assertTrue(result.isSuccess(), result.getResult());
        } finally {
            release.countDown();
            system.terminate();
        }
    }
}
