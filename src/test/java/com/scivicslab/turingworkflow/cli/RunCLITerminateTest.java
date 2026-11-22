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
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.scivicslab.pojoactor.action.ActionResult;
import com.scivicslab.turingworkflow.workflow.IIActorRef;
import com.scivicslab.turingworkflow.workflow.IIActorSystem;

/**
 * The run command must release the actor system even when the run throws, so that a JVM
 * (for example a k8s Job) ends instead of being kept alive by the pool's non-daemon threads.
 */
@Tag("RunCLI_260523_oo01")
@DisplayName("RunCLI - an exception out of the run still terminates the actor system")
class RunCLITerminateTest {

    /** An actor whose dispatch throws, as an adapter overriding callByActionName may. */
    static class ThrowingActor extends IIActorRef<Void> {
        ThrowingActor(String name, IIActorSystem system) {
            super(name, null, system);
        }

        @Override
        public ActionResult callByActionName(String actionName, String args) {
            throw new IllegalStateException("boom from " + actionName);
        }
    }

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
    void run_actionThrowsOutOfRunUntilEnd_terminatesSystemAndReturnsOne() throws Exception {
        Files.writeString(dir.resolve("throws.yaml"), String.join("\n",
                "name: throws",
                "steps:",
                "  - states: [\"0\", \"end\"]",
                "    actions:",
                "      - actor: boom",
                "        method: explode",
                "        execution: direct",
                ""));

        system = new IIActorSystem("run-cli-terminate-test");
        system.addIIActor(new ThrowingActor("boom", system));

        CommandLine cl = new DefaultParser().parse(RunCLI.options(),
                new String[] {"-d", dir.toString(), "-w", "throws.yaml"});

        int status = -1;
        Throwable escaped = null;
        try {
            status = new RunCLI().run(cl, system);
        } catch (Throwable t) {
            escaped = t;
        }

        assertTrue(system.getManagedThreadPool().isShutdown(),
                "the actor system's managed thread pool must be shut down after the run"
                + (escaped != null ? "; run() threw " + escaped : ""));
        assertNull(escaped, "run() must report the exception, not let it escape");
        assertEquals(1, status, "a run that threw must exit with status 1");
    }
}
