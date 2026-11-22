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
import java.util.Arrays;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.scivicslab.pojoactor.action.ActionResult;

/**
 * {@code Interpreter.addToAccumulator} is gone. It looked up an actor named {@code accumulator},
 * which nothing registers (the CLI's output goes to {@code outputMultiplexer}), and it was never an
 * action a workflow could call, so it could only fail. A workflow writes to the multiplexer with
 * {@code outputMultiplexer.add}.
 */
@DisplayName("Interpreter.addToAccumulator is gone")
class AddToAccumulatorRemovedTest {

    private IIActorSystem system;

    @AfterEach
    void tearDown() {
        if (system != null) {
            system.terminate();
        }
    }

    @Test
    @DisplayName("Interpreter has no addToAccumulator method")
    void noSuchMethod() {
        assertTrue(Arrays.stream(Interpreter.class.getMethods())
                .noneMatch(m -> m.getName().equals("addToAccumulator")),
            "Interpreter still has addToAccumulator");
    }

    @Test
    @DisplayName("a workflow calling this.addToAccumulator gets an unknown action")
    void notAnAction() {
        system = new IIActorSystem("add-to-accumulator-removed");
        Interpreter interpreter = new Interpreter.Builder().loggerName("removed").team(system).build();
        InterpreterIIAR self = new InterpreterIIAR("interpreter", interpreter, system);
        interpreter.setSelfActorRef(self);
        system.addIIActor(self);
        interpreter.readYaml(new ByteArrayInputStream("""
            name: calls-add-to-accumulator
            steps:
              - states: ["0", "end"]
                actions:
                  - actor: this
                    method: addToAccumulator
                    execution: direct
                    arguments: {type: "test", data: "hello"}
            """.getBytes(StandardCharsets.UTF_8)));

        ActionResult result = interpreter.runUntilEnd(5);

        assertFalse(result.isSuccess());
        assertNotNull(self.getLastResult());
        assertTrue(self.getLastResult().getResult().contains("addToAccumulator"),
            self.getLastResult().getResult());
    }
}
