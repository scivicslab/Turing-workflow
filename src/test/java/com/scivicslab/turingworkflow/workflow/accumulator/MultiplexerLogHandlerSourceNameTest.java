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

package com.scivicslab.turingworkflow.workflow.accumulator;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.logging.Level;
import java.util.logging.LogRecord;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Every log line the multiplexer collects carries the name of what produced it, derived from
 * the logger's name. Until 4.1.0 that mapping named two things that no longer exist and
 * mislabelled a third: it answered "actor-iac" for a project that has been removed, and
 * "pojo-actor" for this project's own loggers.
 */
@DisplayName("MultiplexerLogHandler — which project a log line is attributed to")
class MultiplexerLogHandlerSourceNameTest {

    private final MultiplexerLogHandler handler = new MultiplexerLogHandler(null);

    private String sourceOf(String loggerName) {
        LogRecord record = new LogRecord(Level.INFO, "message");
        record.setLoggerName(loggerName);
        return handler.getSourceName(record);
    }

    @Test
    void getSourceName_commandLineLogger_returnsCli() {
        assertEquals("cli", sourceOf("com.scivicslab.turingworkflow.cli.App"));
    }

    @Test
    void getSourceName_thisProjectsLogger_returnsTuringWorkflow() {
        assertEquals("turing-workflow", sourceOf("com.scivicslab.turingworkflow.workflow.Interpreter"));
    }

    @Test
    void getSourceName_actorLibraryLogger_returnsPojoActor() {
        assertEquals("pojo-actor", sourceOf("com.scivicslab.pojoactor.core.ActorSystem"));
    }

    @Test
    void getSourceName_pluginLogger_returnsTuringWorkflow() {
        assertEquals("turing-workflow",
                sourceOf("com.scivicslab.turingworkflow.plugins.ssh.NodeActor"));
    }

    @Test
    void getSourceName_unknownLogger_returnsItsLastSegment() {
        assertEquals("SomeClass", sourceOf("org.example.deep.package.SomeClass"));
    }

    @Test
    void getSourceName_loggerWithoutDots_returnsItWhole() {
        assertEquals("global", sourceOf("global"));
    }

    @Test
    void getSourceName_absentLoggerName_returnsSystem() {
        assertEquals("system", sourceOf(null));
        assertEquals("system", sourceOf(""));
    }
}
