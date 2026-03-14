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

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.Constructor;
import org.yaml.snakeyaml.LoaderOptions;

/**
 * Tests for Transition class.
 *
 * @author devteam@scivicslab.com
 */
@DisplayName("Transition Tests")
public class TransitionTest {

    @Test
    @DisplayName("Should get and set states")
    public void testStates() {
        Transition t = new Transition();
        assertNull(t.getStates());

        List<String> states = new ArrayList<>();
        states.add("0");
        states.add("1");
        t.setStates(states);

        assertEquals(2, t.getStates().size());
        assertEquals("0", t.getStates().get(0));
        assertEquals("1", t.getStates().get(1));
    }

    @Test
    @DisplayName("Should get and set label")
    public void testLabel() {
        Transition t = new Transition();
        assertNull(t.getLabel());

        t.setLabel("my-transition");
        assertEquals("my-transition", t.getLabel());
    }

    @Test
    @DisplayName("Should get and set note")
    public void testNote() {
        Transition t = new Transition();
        assertNull(t.getNote());

        t.setNote("This transition handles the main processing step.");
        assertEquals("This transition handles the main processing step.", t.getNote());
    }

    @Test
    @DisplayName("Should handle multiline note")
    public void testMultilineNote() {
        Transition t = new Transition();
        String multilineNote = "First line of note\nSecond line of note";

        t.setNote(multilineNote);
        assertEquals(multilineNote, t.getNote());
    }

    @Test
    @DisplayName("Should get and set actions")
    public void testActions() {
        Transition t = new Transition();
        assertNull(t.getActions());

        List<Action> actions = new ArrayList<>();
        Action action = new Action();
        action.setActor("test");
        action.setMethod("doSomething");
        actions.add(action);

        t.setActions(actions);
        assertEquals(1, t.getActions().size());
        assertEquals("test", t.getActions().get(0).getActor());
    }

    @Test
    @DisplayName("Should deserialize YAML with note field")
    public void testYamlDeserializationWithNote() {
        String yaml = """
            name: test-workflow
            steps:
              - states: ["0", "1"]
                note: Execute the initialization step
                actions:
                  - actor: init
                    method: initialize
              - states: ["1", "end"]
                note: Complete the workflow
                actions:
                  - actor: finish
                    method: complete
            """;

        LoaderOptions options = new LoaderOptions();
        Yaml yamlParser = new Yaml(new Constructor(MatrixCode.class, options));
        MatrixCode code = yamlParser.load(yaml);

        assertEquals(2, code.getSteps().size());

        Transition t1 = code.getSteps().get(0);
        assertEquals("Execute the initialization step", t1.getNote());
        assertEquals("0", t1.getStates().get(0));
        assertEquals("1", t1.getStates().get(1));

        Transition t2 = code.getSteps().get(1);
        assertEquals("Complete the workflow", t2.getNote());
    }

    @Test
    @DisplayName("Should deserialize YAML without note field")
    public void testYamlDeserializationWithoutNote() {
        String yaml = """
            name: simple-workflow
            steps:
              - states: ["0", "end"]
                actions:
                  - actor: test
                    method: doSomething
            """;

        LoaderOptions options = new LoaderOptions();
        Yaml yamlParser = new Yaml(new Constructor(MatrixCode.class, options));
        MatrixCode code = yamlParser.load(yaml);

        assertEquals(1, code.getSteps().size());
        assertNull(code.getSteps().get(0).getNote());
    }

    @Test
    @DisplayName("Should generate YAML string representation")
    public void testToYamlString() {
        Transition t = new Transition();
        List<String> states = new ArrayList<>();
        states.add("0");
        states.add("1");
        t.setStates(states);
        t.setLabel("test-label");

        List<Action> actions = new ArrayList<>();
        Action action = new Action();
        action.setActor("node");
        action.setMethod("executeCommand");
        List<String> args = new ArrayList<>();
        args.add("ls -la");
        action.setArguments(args);
        actions.add(action);
        t.setActions(actions);

        String yaml = t.toYamlString(0);
        assertTrue(yaml.contains("states: [\"0\", \"1\"]"));
        assertTrue(yaml.contains("label: test-label"));
        assertTrue(yaml.contains("actor: node"));
        assertTrue(yaml.contains("method: executeCommand"));
    }
}
