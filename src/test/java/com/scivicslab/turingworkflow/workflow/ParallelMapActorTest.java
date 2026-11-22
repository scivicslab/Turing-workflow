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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.scivicslab.pojoactor.action.ActionResult;

/**
 * Verifies the built-in {@code parallel-map} actor without any LLM: it runs a trivial sub-workflow
 * over a list of items, in parallel and in isolated child systems, and answers the results as a
 * JSON array in item order.
 *
 * <p>Each item is in the sub-workflow's own state at {@code input}; the sub-workflow leaves its
 * answer in its state at {@code output}.</p>
 */
class ParallelMapActorTest {

    /** A self-contained sub-workflow with no plugins: its output is "processed: " + its input. */
    static final String ECHO_YAML = """
            name: echo
            steps:
              - states: ["0", "end"]
                label: echo
                actions:
                  - actor: this
                    method: putJson
                    arguments: {path: output, value: "jexl: 'processed: ' + state.getString('input')"}
              - states: ["!end", "end"]
                label: catch-all
                actions:
                  - actor: out
                    method: error
                    arguments: {message: "echo: unexpected state"}
            """;

    @Test
    void mapsEachItemThroughSubWorkflowInOrder(@TempDir Path dir) throws Exception {
        Path echo = dir.resolve("echo.yaml");
        Files.writeString(echo, ECHO_YAML);

        IIActorSystem system = new IIActorSystem("pm-test");
        try {
            String args = new JSONObject()
                    .put("subWorkflow", echo.toAbsolutePath().toString())
                    .put("items", new JSONArray().put("alpha").put("beta").put("gamma"))
                    .put("maxParallel", 3)
                    .toString();
            ActionResult r = system.getIIActor("parallel-map").callByActionName("run", args);

            assertTrue(r.isSuccess(), r.getResult());
            assertEquals("[\"processed: alpha\",\"processed: beta\",\"processed: gamma\"]", r.getResult());
        } finally {
            system.terminateIIActors();
        }
    }

    @Test
    void aWorkflowMapsAListFromItsStateAndStoresTheAnswers(@TempDir Path dir) throws Exception {
        Path echo = dir.resolve("echo.yaml");
        Files.writeString(echo, ECHO_YAML);

        IIActorSystem system = new IIActorSystem("pm-workflow-test");
        try {
            Interpreter interpreter = new Interpreter.Builder().loggerName("pm-workflow-test").team(system).build();
            InterpreterIIAR self = new InterpreterIIAR("main", interpreter, system);
            interpreter.setSelfActorRef(self);
            system.addIIActor(self);

            String yaml = """
                    name: map-a-list
                    steps:
                      - states: ["0", "1"]
                        actions:
                          - actor: this
                            method: appendJson
                            arguments: {path: xs, value: alpha}
                          - actor: this
                            method: appendJson
                            arguments: {path: xs, value: beta}
                          - actor: this
                            method: appendJson
                            arguments: {path: xs, value: gamma}
                      - states: ["1", "2"]
                        actions:
                          - actor: parallel-map
                            method: run
                            arguments:
                              subWorkflow: "%s"
                              items: "jexl: state.select('xs')"
                              maxParallel: 3
                      - states: ["2", "end"]
                        actions:
                          - actor: this
                            method: putJson
                            arguments: {path: ys, value: "jexl: result"}
                    """.formatted(echo.toAbsolutePath());
            interpreter.readYaml(new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)));
            ActionResult run = interpreter.runUntilEnd(10);

            assertTrue(run.isSuccess(), run.getResult());
            assertEquals(3, self.json().select("ys").size(), self.json().toString());
            assertEquals("processed: alpha", self.json().getString("ys[0]"));
            assertEquals("processed: beta", self.json().getString("ys[1]"));
            assertEquals("processed: gamma", self.json().getString("ys[2]"));
        } finally {
            system.terminateIIActors();
        }
    }
}
