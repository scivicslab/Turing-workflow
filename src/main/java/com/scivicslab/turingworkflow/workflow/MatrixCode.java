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

import java.util.List;

/**
 * Represents a matrix-based workflow definition.
 *
 * <p>A MatrixCode contains a name and a list of {@link Transition} objects that define
 * the workflow's state transitions and actions. Each transition in the matrix represents
 * a state change with associated actions to execute.</p>
 *
 * <p>This class is designed to be populated from YAML or JSON workflow definitions
 * using deserialization frameworks like SnakeYAML or Jackson.</p>
 *
 * @author devteam@scivicslab.com
 */
public class MatrixCode {

    String name;
    String description;
    List<Transition> steps;

    /**
     * Constructs an empty MatrixCode.
     *
     * <p>This no-argument constructor is required for deserialization.</p>
     */
    public MatrixCode() {}

    /**
     * Returns the name of this workflow.
     *
     * @return the workflow name
     */
    public String getName() {
        return name;
    }

    /**
     * Sets the name of this workflow.
     *
     * @param name the workflow name
     */
    public void setName(String name) {
        this.name = name;
    }

    /**
     * Returns the description of this workflow.
     *
     * <p>The description provides human-readable documentation about
     * what the workflow does. It is optional and not used at runtime.</p>
     *
     * @return the workflow description, or null if not set
     */
    public String getDescription() {
        return description;
    }

    /**
     * Sets the description of this workflow.
     *
     * @param description the workflow description
     */
    public void setDescription(String description) {
        this.description = description;
    }

    /**
     * Returns the workflow transitions.
     *
     * @return a list of {@link Transition} objects representing the workflow transitions
     * @since 2.12.0
     */
    public List<Transition> getTransitions() {
        return steps;
    }

    /**
     * Sets the workflow transitions.
     *
     * @param transitions a list of {@link Transition} objects representing the workflow transitions
     * @since 2.12.0
     */
    public void setTransitions(List<Transition> transitions) {
        this.steps = transitions;
    }

    /**
     * Returns the workflow transitions (alias for YAML 'steps' key).
     *
     * @return a list of {@link Transition} objects
     * @since 2.12.0
     */
    public List<Transition> getSteps() {
        return steps;
    }

    /**
     * Sets the workflow transitions (alias for YAML 'steps' key).
     *
     * @param steps a list of {@link Transition} objects
     * @since 2.12.0
     */
    public void setSteps(List<Transition> steps) {
        this.steps = steps;
    }
}
