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

import java.nio.file.Files;
import java.nio.file.Path;

import org.json.JSONArray;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.scivicslab.pojoactor.action.ActionResult;

/**
 * Verifies the built-in {@code parallel-map} actor without any LLM: it runs a trivial sub-workflow
 * (str:output = "processed: " + str:input) over an input list, in parallel and in isolated child
 * systems, and collects the results into the output list in input order.
 */
class ParallelMapActorTest {

    // A self-contained sub-workflow with NO plugins: echoes str:input into str:output.
    private static final String ECHO_YAML =
            "name: echo\n"
          + "steps:\n"
          + "  - states: [\"0\", \"end\"]\n"
          + "    label: echo\n"
          + "    actions:\n"
          + "      - actor: str:output\n"
          + "        method: set\n"
          + "        arguments: \"processed: $(str:input.get)\"\n"
          + "  - states: [\"!end\", \"end\"]\n"
          + "    label: catch-all\n"
          + "    actions:\n"
          + "      - actor: out\n"
          + "        method: error\n"
          + "        arguments: \"echo: unexpected state\"\n";

    @Test
    void mapsEachItemThroughSubWorkflowInOrder(@TempDir Path dir) throws Exception {
        Path echo = dir.resolve("echo.yaml");
        Files.writeString(echo, ECHO_YAML);

        IIActorSystem system = new IIActorSystem("pm-test");
        try {
            IIActorRef<?> in = system.getIIActor("list:in");
            in.callByActionName("add", "alpha");
            in.callByActionName("add", "beta");
            in.callByActionName("add", "gamma");

            String args = new JSONArray()
                    .put(echo.toAbsolutePath().toString())
                    .put("list:in")
                    .put("list:out")
                    .put(3)
                    .toString();
            ActionResult r = system.getIIActor("parallel-map").callByActionName("run", args);
            assertTrue(r.isSuccess(), r.getResult());

            IIActorRef<?> out = system.getIIActor("list:out");
            assertEquals("3", out.callByActionName("size", "").getResult());
            assertEquals("processed: alpha", out.callByActionName("get", "0").getResult());
            assertEquals("processed: beta", out.callByActionName("get", "1").getResult());
            assertEquals("processed: gamma", out.callByActionName("get", "2").getResult());
        } finally {
            system.terminateIIActors();
        }
    }
}
