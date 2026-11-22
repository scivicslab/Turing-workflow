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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import com.scivicslab.pojoactor.action.ActionDispatcher;
import com.scivicslab.pojoactor.action.ActionResult;

/**
 * A step that names the actor running the workflow does not wait for that actor's mailbox, for every
 * action, every way of naming the actor, and every way a workflow is started.
 *
 * <p>The rule ({@code OwnStepDispatch_261123_oo01}): when the Interpreter resolves a step's target to
 * the actor running the workflow, the action does the interpreter's work directly. Sending the action
 * to that actor's mailbox and waiting for it hangs the actor: the mailbox's virtual thread takes the
 * next message only when the one it runs has ended, and that one waits for the next. Each case runs under
 * {@link OwnStepFixtures#LIMIT}, so a hang fails the case. The cases build the actor system with
 * the default pool, whose size is the number of processors, so running the class with
 * {@code -XX:ActiveProcessorCount=1}, {@code 2} and {@code 4} runs it with that many
 * ManagedThreadPool real threads.</p>
 */
@DisplayName("a step that names the workflow's own actor never waits for that actor's mailbox")
class OwnStepMatrixTest {

    /** Every action {@link InterpreterIIAR} has, as the dispatcher lists them. */
    private static Set<String> actualActions() {
        IIActorSystem system = new IIActorSystem("own-step-actions", 1);
        try {
            Workflow any = workflow(system, "any", null);
            return new ActionDispatcher(any.ref()).actionNames();
        } finally {
            system.terminate();
        }
    }

    @Test
    @DisplayName("every action of InterpreterIIAR has an entry in the action table, and the table has no other")
    void everyActionHasACase() {
        Set<String> actual = actualActions();
        Set<String> missing = new TreeSet<>(actual);
        missing.removeAll(CASES.keySet());
        Set<String> stale = new TreeSet<>(CASES.keySet());
        stale.removeAll(actual);

        assertTrue(missing.isEmpty(), "actions with no entry in OwnStepFixtures.CASES (add them, with arguments): " + missing);
        assertTrue(stale.isEmpty(), "entries of OwnStepFixtures.CASES for actions that no longer exist: " + stale);
    }

    /**
     * (a) The workflow is run by {@code Interpreter.runUntilEnd()} on a real thread that is not the
     * actor's, as the {@code run} command does; its first step runs one action on the actor itself.
     */
    private static void directRun(String selfForm, ActionCase c) throws Exception {
        Path dir = Files.createTempDirectory("own-step");
        IIActorSystem system = new IIActorSystem("own-step-direct");
        try {
            workflow(system, "parent", null);
            Workflow child = workflow(system, "child", "parent");
            workflow(system, "kid", "child");
            Path file = dir.resolve("w.yaml");
            Path runTarget = dir.resolve("r.yaml");
            Files.writeString(runTarget, RUN_TARGET_YAML);
            Files.writeString(file, ownStepYaml(selfForm, c, file, runTarget));
            child.interpreter().readYaml(file);

            ActionResult result = assertTimeoutPreemptively(LIMIT, () -> child.interpreter().runUntilEnd(20));

            assertTrue(result.isSuccess(), selfForm + "." + c.action() + ": " + result.getResult());
            assertEquals("end", child.interpreter().getCurrentState());
        } finally {
            system.terminate();
            delete(dir);
        }
    }

    @TestFactory
    @DisplayName("every action, named on the actor by this, . and its own name")
    Stream<DynamicTest> everyActionOnTheCommonSelfForms() {
        List<DynamicTest> tests = new ArrayList<>();
        Set<String> actions = new TreeSet<>(actualActions());
        actions.addAll(CASES.keySet());
        for (String form : COMMON_SELF_FORMS) {
            for (String action : actions) {
                tests.add(dynamicTest("actor: " + form + " × " + action + " (runUntilEnd on the run command's thread)",
                    () -> directRun(form, caseOf(action))));
            }
        }
        return tests.stream();
    }

    @TestFactory
    @DisplayName("the actions that waited for their own mailbox, named by the other forms that resolve to the actor")
    Stream<DynamicTest> representativeActionsOnTheOtherSelfForms() {
        List<DynamicTest> tests = new ArrayList<>();
        for (String form : SELF_FORMS) {
            if (COMMON_SELF_FORMS.contains(form)) {
                continue;
            }
            for (String action : REPRESENTATIVE_ACTIONS) {
                tests.add(dynamicTest("actor: " + form + " × " + action,
                    () -> directRun(form, caseOf(action))));
            }
        }
        return tests.stream();
    }

    /** How the workflow of the child is started. */
    enum Start {
        /** (b) the parent's step {@code actor: child, method: runUntilEnd} */
        PARENT_RUN_UNTIL_END,
        /** (b) the parent's step {@code actor: child, method: execCode}: one step of the child */
        PARENT_EXEC_CODE,
        /** (b) the parent's step {@code actor: child, method: runWorkflow}: the file replaces the child's code */
        PARENT_RUN_WORKFLOW,
        /** (b) the parent's step {@code actor: this, method: apply} over its child */
        PARENT_APPLY,
        /** (d) {@code ref.tell(i -> i.runUntilEnd())}: the loop is the actor's own message, on its mailbox virtual thread */
        OWN_MESSAGE
    }

    /** The child's workflow, whose one step calls three actions on the actor itself, started the way {@code start} says. */
    private static void startedBy(Start start, String selfForm) throws Exception {
        Path dir = Files.createTempDirectory("own-step");
        IIActorSystem system = new IIActorSystem("own-step-start");
        try (ExecutorService virtual = Executors.newVirtualThreadPerTaskExecutor()) {
            Workflow parent = workflow(system, "parent", null);
            Workflow child = workflow(system, "child", "parent");
            Path file = dir.resolve("triple.yaml");
            Files.writeString(file, tripleYaml(selfForm, file));
            child.interpreter().readYaml(file);
            String parentStep = switch (start) {
                case PARENT_RUN_UNTIL_END -> action("child", "runUntilEnd", "[20]");
                case PARENT_EXEC_CODE -> action("child", "execCode", "[]");
                case PARENT_RUN_WORKFLOW -> action("child", "runWorkflow", "[\"" + file + "\"]");
                case PARENT_APPLY -> action("this", "apply", "{actor: child, method: runUntilEnd, arguments: [20]}");
                case OWN_MESSAGE -> null;
            };
            ActionResult result;
            if (start == Start.OWN_MESSAGE) {
                CompletableFuture<ActionResult> run = new CompletableFuture<>();
                child.ref().tell((Interpreter i) -> run.complete(i.runUntilEnd(20)));
                result = assertTimeoutPreemptively(LIMIT, () -> run.get());
            } else {
                parent.interpreter().readYaml(new java.io.ByteArrayInputStream((
                    "name: parent\nsteps:\n  - states: [\"0\", \"end\"]\n    actions:\n" + parentStep)
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8)));
                result = assertTimeoutPreemptively(LIMIT, () -> parent.interpreter().runUntilEnd(10));
            }

            assertTrue(result.isSuccess(), start + " × " + selfForm + ": " + result.getResult());
            assertEquals(start == Start.PARENT_EXEC_CODE ? "1" : "end", child.interpreter().getCurrentState());
        } finally {
            system.terminate();
            delete(dir);
        }
    }

    @TestFactory
    @DisplayName("every way a workflow is started × the ways to name the actor")
    Stream<DynamicTest> everyStart() {
        List<DynamicTest> tests = new ArrayList<>();
        for (Start start : Start.values()) {
            for (String form : List.of("this", ".", "child", "../child")) {
                tests.add(dynamicTest(start + " × actor: " + form, () -> startedBy(start, form)));
            }
        }
        return tests.stream();
    }

    /** (c) a sub-workflow run by {@code this.call}: its steps name its own actor, the one {@code call} made. */
    @TestFactory
    @DisplayName("inside a sub-workflow run by this.call, the sub-workflow's steps name its own actor")
    Stream<DynamicTest> insideThisCall() {
        List<DynamicTest> tests = new ArrayList<>();
        for (String resource : List.of("t795-own-this.yaml", "t795-own-dot.yaml")) {
            tests.add(dynamicTest(resource, () -> {
                IIActorSystem system = new IIActorSystem("own-step-call");
                try {
                    Workflow parent = workflow(system, "parent", null);
                    parent.interpreter().readYaml(new java.io.ByteArrayInputStream((
                        "name: parent\nsteps:\n  - states: [\"0\", \"end\"]\n    actions:\n"
                        + action("this", "call", "\"" + resource + "\"")).getBytes(java.nio.charset.StandardCharsets.UTF_8)));

                    ActionResult result = assertTimeoutPreemptively(LIMIT, () -> parent.interpreter().runUntilEnd(10));

                    assertTrue(result.isSuccess(), resource + ": " + result.getResult());
                    assertEquals("end", parent.interpreter().getCurrentState());
                } finally {
                    system.terminate();
                }
            }));
        }
        return tests.stream();
    }
}
