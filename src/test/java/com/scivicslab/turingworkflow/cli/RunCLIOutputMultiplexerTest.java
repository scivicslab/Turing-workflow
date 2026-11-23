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

package com.scivicslab.turingworkflow.cli;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.DefaultParser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.scivicslab.turingworkflow.workflow.IIActorSystem;
import com.scivicslab.turingworkflow.workflow.accumulator.MultiplexerAccumulatorIIAR;

/**
 * The run command registers its output multiplexer under the one name every reader looks it up
 * by, {@code outputMultiplexer}, and under no other.
 */
@DisplayName("RunCLI - the output multiplexer is outputMultiplexer")
class RunCLIOutputMultiplexerTest {

    @TempDir
    Path dir;

    private IIActorSystem system;

    @AfterEach
    void tearDown() {
        if (system != null) {
            system.terminate();
        }
    }

    @Test
    @DisplayName("registered as outputMultiplexer, not as log")
    void registeredUnderTheOneName() {
        system = new IIActorSystem("run-cli-multiplexer-name");

        RunCLI.registerOutputMultiplexer(system);

        assertInstanceOf(MultiplexerAccumulatorIIAR.class, system.getIIActor("outputMultiplexer"));
        assertNull(system.getIIActor("log"), "the multiplexer must not also be registered as log");
    }

    @Test
    @DisplayName("a workflow run by the CLI writes to outputMultiplexer")
    void workflowWritesThroughTheName() throws Exception {
        Files.writeString(dir.resolve("writes.yaml"), String.join("\n",
                "name: writes",
                "steps:",
                "  - states: [\"0\", \"end\"]",
                "    actions:",
                "      - actor: outputMultiplexer",
                "        method: add",
                "        arguments: {source: \"test\", type: \"stdout\", data: \"through the one name\"}",
                ""));
        system = new IIActorSystem("run-cli-multiplexer-workflow");
        CommandLine cl = new DefaultParser().parse(RunCLI.options(),
                new String[] {"-d", dir.toString(), "-w", "writes.yaml"});

        assertEquals(0, new RunCLI().run(cl, system));
    }
}
