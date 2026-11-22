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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * What the own-step tests share: the ways a workflow step can name the actor that runs it, the
 * action table (one entry per {@code @Action} of {@link InterpreterIIAR}), and the workflows the
 * tests load.
 *
 * <p>The rule these tests pin: a step whose target resolves to the actor running the workflow does
 * the interpreter's work directly; it never sends itself to that actor's mailbox and waits there. A
 * step another actor sends goes through the mailbox.</p>
 */
final class OwnStepFixtures {

    private OwnStepFixtures() {
    }

    /**
     * The time a case may take. A case that finishes does so in milliseconds; one that waits for its
     * own mailbox never does, so a hang fails the case instead of the build.
     */
    static final Duration LIMIT = Duration.ofSeconds(4);

    /** An interpreter registered as an actor. */
    record Workflow(Interpreter interpreter, InterpreterIIAR ref) {}

    /** Makes an interpreter, registers its actor in the system, and links it to its parent. */
    static Workflow workflow(IIActorSystem system, String name, String parentName) {
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
     * The ways a step of the workflow of the actor named {@code child}, whose parent is
     * {@code parent}, can name that actor itself, in the order the {@code IIActorSystem} resolves them:
     * {@code this}, {@code .}, the actor's own name, {@code ../<its name>}, {@code ../*} (every
     * sibling; the actor is the only one) and {@code ../chi*} (a pattern of sibling names).
     */
    static final List<String> SELF_FORMS = List.of("this", ".", "child", "../child", "../*", "../chi*");

    /** The forms every action is run with; the others are run with {@link #REPRESENTATIVE_ACTIONS}. */
    static final List<String> COMMON_SELF_FORMS = List.of("this", ".", "child");

    /** The actions that used to send themselves to their own actor, run with every self form. */
    static final List<String> REPRESENTATIVE_ACTIONS = List.of("setCurrentState", "readYaml", "call");

    /**
     * One action of {@link InterpreterIIAR}, as a step calls it on the actor itself.
     *
     * @param action the action name
     * @param prelude the actions the step runs first, as {@code method|arguments} (arguments in YAML), so that a
     *                nested {@code execCode} or {@code runUntilEnd} lands on a different transition than
     *                the one running
     * @param arguments the action's {@code arguments:} value in YAML; {@code {W}} is the workflow's file,
     *                  {@code {R}} a workflow that ends from every state the run can be in
     * @param mailbox whether another actor's call of it goes through the actor's mailbox
     * @param jsonArguments the same arguments as an actor receives them, for a call from outside
     */
    record ActionCase(String action, List<String> prelude, String arguments, boolean mailbox, String jsonArguments) {}

    /**
     * Every {@code @Action} an {@link InterpreterIIAR} has, with the arguments that make it succeed
     * as a step of a workflow. {@code OwnStepMatrixTest.everyActionHasACase} fails for an action that is
     * not here, so a new action must be added, and with it comes under every test that loops over this
     * table.
     */
    static final Map<String, ActionCase> CASES = new LinkedHashMap<>();

    static {
        List<String> toX = List.of("setCurrentState|\"x\"");
        add("execCode", toX, "[]", true, "[]");
        add("runUntilEnd", toX, "[20]", true, "[20]");
        add("call", List.of(), "\"t795-sub.yaml\"", true, "[\"t795-sub.yaml\"]");
        add("runWorkflow", List.of(), "[\"{R}\"]", true, "[\"{R}\"]");
        add("apply", List.of(), "{actor: kid, method: doNothing, arguments: [\"x\"]}", true,
            "{\"actor\":\"kid\",\"method\":\"doNothing\",\"arguments\":[\"x\"]}");
        add("readYaml", List.of(), "\"{W}\"", true, "[\"{W}\"]");
        add("setCurrentState", List.of(), "\"0\"", true, "[\"z\"]");
        add("sleep", List.of(), "\"1\"", false, "[\"1\"]");
        add("print", List.of(), "[\"hello\"]", false, "[\"hello\"]");
        add("doNothing", List.of(), "[\"x\"]", false, "[\"x\"]");
        add("onlyIf", List.of(), "\"jexl: true\"", false, "[true]");
        add("putJson", List.of(), "{path: a, value: 1}", false, "{\"path\":\"a\",\"value\":1}");
        add("appendJson", List.of(), "{path: l, value: 1}", false, "{\"path\":\"l\",\"value\":1}");
        add("getJson", List.of(), "\"a\"", false, "[\"a\"]");
        add("hasJson", List.of(), "\"a\"", false, "[\"a\"]");
        add("clearJson", List.of(), "[]", false, "[]");
        add("printJson", List.of(), "[]", false, "[]");
    }

    private static void add(String action, List<String> prelude, String arguments, boolean mailbox, String json) {
        CASES.put(action, new ActionCase(action, prelude, arguments, mailbox, json));
    }

    /** The case of an action the table does not know yet: no arguments. */
    static ActionCase caseOf(String action) {
        return CASES.getOrDefault(action, new ActionCase(action, List.of(), "[]", false, "[]"));
    }

    /** What the actions that run a workflow end with: it ends from every state a run of {@link #ownStepYaml} can be in. */
    static final String RUN_TARGET_YAML = """
        name: run-target
        steps:
          - states: ["0", "end"]
            actions:
              - actor: this
                method: doNothing
                arguments: ["x"]
          - states: ["1", "end"]
            actions:
              - actor: this
                method: doNothing
                arguments: ["x"]
          - states: ["x", "end"]
            actions:
              - actor: this
                method: doNothing
                arguments: ["x"]
        """;

    /**
     * A workflow whose first step runs {@code case}'s action on the actor itself, named by {@code selfForm},
     * after its prelude; the other steps end the run from the states a nested call can leave.
     */
    static String ownStepYaml(String selfForm, ActionCase c, Path workflowFile, Path runTarget) {
        StringBuilder y = new StringBuilder();
        y.append("name: own-step\nsteps:\n  - states: [\"0\", \"1\"]\n    actions:\n");
        for (String p : c.prelude()) {
            String[] part = p.split("\\|", 2);
            y.append(action(selfForm, part[0], part[1]));
        }
        y.append(action(selfForm, c.action(), fill(c.arguments(), workflowFile, runTarget)));
        y.append("  - states: [\"x\", \"end\"]\n    actions:\n").append(action("this", "doNothing", "[\"x\"]"));
        y.append("  - states: [\"1\", \"end\"]\n    actions:\n").append(action("this", "doNothing", "[\"x\"]"));
        return y.toString();
    }

    /** A step's action, as YAML. */
    static String action(String actor, String method, String arguments) {
        return "      - actor: \"" + actor + "\"\n        method: " + method + "\n        arguments: " + arguments + "\n";
    }

    /** Puts the files of a case into an arguments value. */
    static String fill(String arguments, Path workflowFile, Path runTarget) {
        return arguments.replace("{W}", String.valueOf(workflowFile)).replace("{R}", String.valueOf(runTarget));
    }

    /**
     * The workflow of the start-form tests: its one step calls the three actions that waited for their own
     * mailbox the most often, on the actor named by {@code selfForm}.
     */
    static String tripleYaml(String selfForm, Path workflowFile) {
        return "name: triple\nsteps:\n  - states: [\"0\", \"1\"]\n    actions:\n"
            + action(selfForm, "setCurrentState", "\"0\"")
            + action(selfForm, "readYaml", "\"" + workflowFile + "\"")
            + action(selfForm, "call", "\"t795-sub.yaml\"")
            + "  - states: [\"1\", \"end\"]\n    actions:\n" + action(selfForm, "doNothing", "[\"x\"]");
    }

    /** Deletes a test's temporary directory. */
    static void delete(Path dir) {
        try (Stream<Path> tree = Files.walk(dir)) {
            tree.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        } catch (IOException ignored) {
            // the directory is in the system's temporary files
        }
    }
}
