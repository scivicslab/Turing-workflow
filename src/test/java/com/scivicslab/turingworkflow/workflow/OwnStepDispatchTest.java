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

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.scivicslab.pojoactor.action.ActionResult;

/**
 * A step that names the actor running the workflow ({@code actor: this}, or the actor's own name)
 * does not wait for that actor's mailbox.
 *
 * <p>{@code setCurrentState}, {@code readYaml} and {@code call} of {@link InterpreterIIAR} used to
 * send themselves to their own actor and wait ({@code this.tell(..).get()}, {@code this.ask(..).get()}).
 * The workflow runs as the actor's own work: the mailbox's virtual thread does not take the next
 * message until the one it runs has ended, and that one waited for the next message to end. The
 * actor hung. When the Interpreter resolves a step's target to the actor running the workflow, the
 * action now does the interpreter's work directly. A step that another actor sends (a parent's
 * {@code actor: child}) still goes through the mailbox.</p>
 *
 * <p>Each test runs under a time limit, so a hang fails the test instead of the build.</p>
 */
@DisplayName("a step that names the workflow's own actor does not wait for its mailbox")
class OwnStepDispatchTest {

    private static final Duration LIMIT = Duration.ofSeconds(20);

    @TempDir
    Path dir;

    private IIActorSystem system;

    @AfterEach
    void tearDown() {
        if (system != null) {
            system.terminate();
        }
    }

    /** An interpreter registered as an actor. */
    record Workflow(Interpreter interpreter, InterpreterIIAR ref) {}

    private Workflow workflow(String name, String parentName) {
        Interpreter interpreter = new Interpreter.Builder().loggerName(name).team(system).build();
        InterpreterIIAR ref = new InterpreterIIAR(name, interpreter, system);
        interpreter.setSelfActorRef(ref);
        if (parentName != null) {
            ref.setParentName(parentName);
            system.getIIActor(parentName).getNamesOfChildren().add(name);
        }
        system.addIIActor(ref);
        return new Workflow(interpreter, ref);
    }

    /**
     * Makes the child workflow: one step that calls {@code setCurrentState}, {@code readYaml}
     * (reloading this same file) and {@code call} on {@code self}, then an end step.
     */
    private Workflow childWorkflow(String self) throws Exception {
        Path file = dir.resolve("child.yaml");
        Files.writeString(file, """
            name: child
            steps:
              - states: ["0", "1"]
                actions:
                  - actor: %1$s
                    method: setCurrentState
                    arguments: "0"
                  - actor: %1$s
                    method: readYaml
                    arguments: "%2$s"
                  - actor: %1$s
                    method: call
                    arguments: "t795-sub.yaml"
              - states: ["1", "end"]
                actions:
                  - actor: %1$s
                    method: doNothing
                    arguments: ["x"]
            """.formatted(self, file));
        Workflow child = workflow("child", "parent");
        child.interpreter().readYaml(file);
        return child;
    }

    private static void readYaml(Workflow workflow, String yaml) {
        workflow.interpreter().readYaml(new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    @DisplayName("a parent runs the child with runUntilEnd; the child's steps call this.setCurrentState, this.readYaml, this.call")
    void childRunByParentCallsThis() throws Exception {
        system = new IIActorSystem("own-step-parent-child", 2);
        Workflow parent = workflow("parent", null);
        Workflow child = childWorkflow("this");
        MatrixCode loaded = child.interpreter().getCode();
        readYaml(parent, """
            name: parent
            steps:
              - states: ["0", "end"]
                actions:
                  - actor: child
                    method: runUntilEnd
                    arguments: [50]
            """);

        // The parent's loop runs on this test's real thread, as RunCLI's does.
        ActionResult result = assertTimeoutPreemptively(LIMIT, () -> parent.interpreter().runUntilEnd(10));

        assertTrue(result.isSuccess(), result.getResult());
        assertEquals("end", child.interpreter().getCurrentState());
        assertNotSame(loaded, child.interpreter().getCode(), "this.readYaml ran: the child's code was reloaded");
    }

    @Test
    @DisplayName("the same, when the child's steps name it by its own name instead of this")
    void childRunByParentCallsItselfByName() throws Exception {
        system = new IIActorSystem("own-step-own-name", 2);
        Workflow parent = workflow("parent", null);
        Workflow child = childWorkflow("child");
        MatrixCode loaded = child.interpreter().getCode();
        readYaml(parent, """
            name: parent
            steps:
              - states: ["0", "end"]
                actions:
                  - actor: child
                    method: runUntilEnd
                    arguments: [50]
            """);

        ActionResult result = assertTimeoutPreemptively(LIMIT, () -> parent.interpreter().runUntilEnd(10));

        assertTrue(result.isSuccess(), result.getResult());
        assertEquals("end", child.interpreter().getCurrentState());
        assertNotSame(loaded, child.interpreter().getCode());
    }

    @Test
    @DisplayName("the workflow runs as its own actor's mailbox work (ref.ask on a virtual thread); its steps call this.*")
    void workflowRunAsItsOwnActorsWork() throws Exception {
        system = new IIActorSystem("own-step-own-work", 2);
        workflow("parent", null);
        Workflow child = childWorkflow("this");
        MatrixCode loaded = child.interpreter().getCode();

        // The loop runs on the child actor's mailbox virtual thread, which holds the child's turn.
        ActionResult result = assertTimeoutPreemptively(LIMIT,
            () -> child.ref().ask((Interpreter i) -> i.runUntilEnd(50)).get());

        assertTrue(result.isSuccess(), result.getResult());
        assertEquals("end", child.interpreter().getCurrentState());
        assertNotSame(loaded, child.interpreter().getCode());
    }

    @Test
    @DisplayName("a step another actor sends (the parent's actor: child) still goes through the child's mailbox")
    void stepSentByAnotherActorStillQueuesInTheMailbox() throws Exception {
        system = new IIActorSystem("own-step-queued", 2);
        Workflow parent = workflow("parent", null);
        Workflow child = workflow("child", "parent");
        readYaml(parent, """
            name: parent
            steps:
              - states: ["0", "end"]
                actions:
                  - actor: child
                    method: setCurrentState
                    arguments: "queued"
            """);
        CountDownLatch holding = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        // Hold the child's turn: its mailbox virtual thread is inside this message until released.
        child.ref().ask((Interpreter i) -> {
            holding.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return null;
        });
        assertTrue(holding.await(10, TimeUnit.SECONDS));

        try (ExecutorService virtual = Executors.newVirtualThreadPerTaskExecutor()) {
            CompletableFuture<ActionResult> run = CompletableFuture.supplyAsync(
                () -> parent.interpreter().runUntilEnd(10), virtual);

            assertThrows(TimeoutException.class, () -> run.get(500, TimeUnit.MILLISECONDS),
                "the step waits for the child's mailbox, which is held");
            assertNotEquals("queued", child.interpreter().getCurrentState());

            release.countDown();
            ActionResult result = assertTimeoutPreemptively(LIMIT, () -> run.get());
            assertTrue(result.isSuccess(), result.getResult());
            assertEquals("queued", child.interpreter().getCurrentState());
        }
    }
}
