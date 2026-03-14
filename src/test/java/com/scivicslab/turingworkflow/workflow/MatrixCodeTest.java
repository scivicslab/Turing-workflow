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

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.Constructor;
import org.yaml.snakeyaml.LoaderOptions;

/**
 * Tests for MatrixCode class.
 *
 * @author devteam@scivicslab.com
 */
@DisplayName("MatrixCode Tests")
public class MatrixCodeTest {

    @Test
    @DisplayName("Should get and set name")
    public void testName() {
        MatrixCode code = new MatrixCode();
        assertNull(code.getName());

        code.setName("test-workflow");
        assertEquals("test-workflow", code.getName());
    }

    @Test
    @DisplayName("Should get and set description")
    public void testDescription() {
        MatrixCode code = new MatrixCode();
        assertNull(code.getDescription());

        code.setDescription("This is a test workflow description.");
        assertEquals("This is a test workflow description.", code.getDescription());
    }

    @Test
    @DisplayName("Should handle multiline description")
    public void testMultilineDescription() {
        MatrixCode code = new MatrixCode();
        String multilineDesc = "Line 1\nLine 2\nLine 3";

        code.setDescription(multilineDesc);
        assertEquals(multilineDesc, code.getDescription());
    }

    @Test
    @DisplayName("Should get and set steps/transitions")
    public void testSteps() {
        MatrixCode code = new MatrixCode();
        assertNull(code.getSteps());

        List<Transition> steps = new ArrayList<>();
        Transition t = new Transition();
        List<String> states = new ArrayList<>();
        states.add("0");
        states.add("1");
        t.setStates(states);
        steps.add(t);

        code.setSteps(steps);
        assertEquals(1, code.getSteps().size());
        assertEquals(steps, code.getTransitions());
    }

    @Test
    @DisplayName("Should deserialize YAML with description field")
    public void testYamlDeserializationWithDescription() {
        String yaml = """
            name: test-workflow
            description: |
              This is a test workflow.
              It has multiple lines.
            steps:
              - states: ["0", "end"]
                actions:
                  - actor: test
                    method: doSomething
            """;

        LoaderOptions options = new LoaderOptions();
        Yaml yamlParser = new Yaml(new Constructor(MatrixCode.class, options));
        MatrixCode code = yamlParser.load(yaml);

        assertEquals("test-workflow", code.getName());
        assertNotNull(code.getDescription());
        assertTrue(code.getDescription().contains("This is a test workflow"));
        assertTrue(code.getDescription().contains("It has multiple lines"));
        assertEquals(1, code.getSteps().size());
    }

    @Test
    @DisplayName("Should deserialize YAML without description field")
    public void testYamlDeserializationWithoutDescription() {
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

        assertEquals("simple-workflow", code.getName());
        assertNull(code.getDescription());
        assertEquals(1, code.getSteps().size());
    }
}
