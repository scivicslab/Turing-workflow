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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.json.JSONObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.scivicslab.pojoactor.action.ActionResult;

/**
 * Verifies that {@code parallel-map run} is false when the sub-workflow failed for any item, naming the item and why,
 * while every item's output, a failed item's {@code error: ...} included, is still appended in input order.
 */
@DisplayName("parallel-map - one failed item makes the run false, naming it; every output is kept")
class ParallelMapActorFailureTest {

    // Has no transition for the item "beta", so the sub-workflow fails for that item only.
    private static final String ALL_BUT_BETA_YAML =
            "name: all-but-beta\n"
          + "steps:\n"
          + "  - states: [\"0\", \"end\"]\n"
          + "    label: echo-unless-beta\n"
          + "    actions:\n"
          + "      - actor: this\n"
          + "        method: onlyIf\n"
          + "        arguments: \"jexl: actors.get('str:input').get() != 'beta'\"\n"
          + "      - actor: str:output\n"
          + "        method: set\n"
          + "        arguments: \"jexl: 'processed: ' + actors.get('str:input').get()\"\n";

    @Test
    void run_subWorkflowFailsForOneItem_isFalseNamingItAndKeepsEveryOutput(@TempDir Path dir) throws Exception {
        Path yaml = dir.resolve("all-but-beta.yaml");
        Files.writeString(yaml, ALL_BUT_BETA_YAML);

        IIActorSystem system = new IIActorSystem("pm-failure-test");
        try {
            IIActorRef<?> in = system.getIIActor("list:in");
            in.callByActionName("add", "{\"value\":\"alpha\"}");
            in.callByActionName("add", "{\"value\":\"beta\"}");
            in.callByActionName("add", "{\"value\":\"gamma\"}");

            ActionResult r = system.getIIActor("parallel-map").callByActionName("run", new JSONObject()
                    .put("subWorkflow", yaml.toAbsolutePath().toString())
                    .put("inputList", "list:in")
                    .put("outputList", "list:out")
                    .put("maxParallel", 3)
                    .toString());

            assertFalse(r.isSuccess(), r.getResult());
            assertTrue(r.getResult().startsWith("1 of 3 failed: 1 (beta): error: sub-workflow failed: "), r.getResult());
            assertTrue(r.getResult().endsWith(". parallel-map: mapped 3 item(s)"), r.getResult());
            IIActorRef<?> out = system.getIIActor("list:out");
            assertEquals("3", out.callByActionName("size", "").getResult());
            assertEquals("processed: alpha", out.callByActionName("get", "{\"index\":0}").getResult());
            String failed = out.callByActionName("get", "{\"index\":1}").getResult();
            assertTrue(failed.startsWith("error: sub-workflow failed: "), failed);
            assertEquals("processed: gamma", out.callByActionName("get", "{\"index\":2}").getResult());
        } finally {
            system.terminateIIActors();
        }
    }
}
