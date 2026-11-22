/*
 * Copyright 2025 devteam@scivicslab.com
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package com.scivicslab.turingworkflow.workflow;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.scivicslab.pojoactor.action.ActionResult;

/**
 * Verifies how {@code parallel-map run} fails. When the sub-workflow failed for any item, the run is
 * false, naming each failed item and why. A fatal error from an item's run is not one item's failure:
 * it is recorded as the calling workflow's last result and rethrown out of its run.
 */
@DisplayName("parallel-map - a failed item makes the run false, naming it; a fatal one is rethrown")
class ParallelMapActorFailureTest {

    /** Has no transition for the item "beta", so the sub-workflow fails for that item only. */
    private static final String ALL_BUT_BETA_YAML = """
            name: all-but-beta
            steps:
              - states: ["0", "end"]
                label: echo-unless-beta
                actions:
                  - actor: this
                    method: onlyIf
                    arguments: "jexl: state.getString('input') != 'beta'"
                  - actor: this
                    method: putJson
                    arguments: {path: output, value: "jexl: 'processed: ' + state.getString('input')"}
            """;

    /** Calls a {@link Bomb} for every item, so every item's run ends in a fatal error. */
    private static final String BOMB_YAML = """
            name: bomb
            steps:
              - states: ["0", "1"]
                actions:
                  - actor: loader
                    method: createChild
                    arguments: ["ROOT", "bomb", "com.scivicslab.turingworkflow.workflow.ParallelMapActorFailureTest$Bomb"]
              - states: ["1", "end"]
                actions:
                  - actor: bomb
                    method: explode
            """;

    /** An actor whose every action throws a test-only fatal error. */
    public static final class Bomb extends IIActorRef<Object> {
        public Bomb(String name, IIActorSystem system) {
            super(name, new Object(), system);
        }

        @Override
        public ActionResult callByActionName(String actionName, String args) {
            throw new ActionErrorTest.TestFatalError("dying in an item");
        }
    }

    @Test
    void run_subWorkflowFailsForOneItem_isFalseNamingIt(@TempDir Path dir) throws Exception {
        Path yaml = dir.resolve("all-but-beta.yaml");
        Files.writeString(yaml, ALL_BUT_BETA_YAML);

        IIActorSystem system = new IIActorSystem("pm-failure-test");
        try {
            ActionResult r = system.getIIActor("parallel-map").callByActionName("run", new JSONObject()
                    .put("subWorkflow", yaml.toAbsolutePath().toString())
                    .put("items", new JSONArray().put("alpha").put("beta").put("gamma"))
                    .put("maxParallel", 3)
                    .toString());

            assertFalse(r.isSuccess(), r.getResult());
            assertTrue(r.getResult().startsWith("1 of 3 failed: 1 (beta): error: sub-workflow failed: "), r.getResult());
            assertTrue(r.getResult().endsWith(". parallel-map: mapped 3 item(s)"), r.getResult());
            assertFalse(r.getResult().contains("(alpha)") || r.getResult().contains("(gamma)"),
                    "only the failed item is named: " + r.getResult());
        } finally {
            system.terminateIIActors();
        }
    }

    /** Has no transition for the items "beta" and "delta", so the sub-workflow fails for those two. */
    private static final String ALL_BUT_BETA_AND_DELTA_YAML = """
            name: all-but-beta-and-delta
            steps:
              - states: ["0", "end"]
                label: echo-unless-beta-or-delta
                actions:
                  - actor: this
                    method: onlyIf
                    arguments: "jexl: state.getString('input') != 'beta' && state.getString('input') != 'delta'"
                  - actor: this
                    method: putJson
                    arguments: {path: output, value: "jexl: 'processed: ' + state.getString('input')"}
            """;

    @Test
    void run_twoOfFiveItemsFail_isFalseNamingEveryFailedItemInOrder(@TempDir Path dir) throws Exception {
        Path yaml = dir.resolve("all-but-beta-and-delta.yaml");
        Files.writeString(yaml, ALL_BUT_BETA_AND_DELTA_YAML);

        IIActorSystem system = new IIActorSystem("pm-two-failures-test");
        try {
            ActionResult r = system.getIIActor("parallel-map").callByActionName("run", new JSONObject()
                    .put("subWorkflow", yaml.toAbsolutePath().toString())
                    .put("items", new JSONArray().put("alpha").put("beta").put("gamma").put("delta").put("epsilon"))
                    .put("maxParallel", 5)
                    .toString());

            String text = r.getResult();
            assertFalse(r.isSuccess(), text);
            assertTrue(text.startsWith("2 of 5 failed: 1 (beta): error: sub-workflow failed: "), text);
            int beta = text.indexOf("1 (beta): error: sub-workflow failed: ");
            int delta = text.indexOf("; 3 (delta): error: sub-workflow failed: ");
            assertTrue(delta > beta, "both failed items are named, in item order: " + text);
            assertTrue(text.endsWith(". parallel-map: mapped 5 item(s)"), text);
            for (String passed : new String[] {"(alpha)", "(gamma)", "(epsilon)"}) {
                assertFalse(text.contains(passed), "only the failed items are named: " + text);
            }
        } finally {
            system.terminateIIActors();
        }
    }

    @Test
    void run_fatalErrorFromAnItem_isRecordedAndRethrown(@TempDir Path dir) throws Exception {
        Path yaml = dir.resolve("bomb.yaml");
        Files.writeString(yaml, BOMB_YAML);

        IIActorSystem system = new IIActorSystem("pm-fatal-test");
        try {
            Interpreter interpreter = new Interpreter.Builder().loggerName("pm-fatal-test").team(system).build();
            InterpreterIIAR self = new InterpreterIIAR("main", interpreter, system);
            interpreter.setSelfActorRef(self);
            system.addIIActor(self);
            String main = """
                    name: map-into-a-bomb
                    steps:
                      - states: ["0", "end"]
                        actions:
                          - actor: parallel-map
                            method: run
                            arguments:
                              subWorkflow: "%s"
                              items: ["alpha", "beta"]
                      - states: ["0", "end"]
                        actions:
                          - actor: out
                            method: print
                            arguments: {message: "the run went on after a fatal error"}
                    """.formatted(yaml.toAbsolutePath());
            interpreter.readYaml(new ByteArrayInputStream(main.getBytes(StandardCharsets.UTF_8)));

            ExecutionException e = assertThrows(ExecutionException.class,
                    () -> CompletableFuture.supplyAsync(() -> interpreter.runUntilEnd(10)).get(30, TimeUnit.SECONDS),
                    "the fatal error must be rethrown out of the calling run");

            assertInstanceOf(ActionErrorTest.TestFatalError.class, e.getCause());
            String last = self.getLastResult().getResult();
            assertTrue(last.startsWith("error: ") && last.contains("TestFatalError: dying in an item"), last);
        } finally {
            system.terminateIIActors();
        }
    }
}
