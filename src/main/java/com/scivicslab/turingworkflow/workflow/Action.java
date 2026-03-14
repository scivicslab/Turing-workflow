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

/**
 * Represents a single action in a workflow transition.
 *
 * <p>An action specifies which actor to invoke, which method to call,
 * with what arguments, and how to execute it (direct call vs managed thread pool).</p>
 *
 * <p>The {@code arguments} field supports multiple formats:</p>
 * <ul>
 *   <li>String: Single argument (wrapped in JSON array when passed to actor)</li>
 *   <li>List: Multiple arguments (converted to JSON array)</li>
 *   <li>Map: Structured data (converted to JSON object, not wrapped in array)</li>
 * </ul>
 *
 * @author devteam@scivicslab.com
 * @since 2.8.0
 */
public class Action {

    private String actor;
    private String method;
    private Object arguments;  // String, List, or Map
    private ExecutionMode execution = ExecutionMode.POOL;  // Default: pool
    private int poolIndex = 0;

    /**
     * Constructs an empty Action.
     */
    public Action() {
    }

    /**
     * Constructs an Action with specified parameters.
     *
     * @param actor the name of the actor to invoke
     * @param method the method to call
     * @param arguments the arguments to pass (String, List, or Map)
     */
    public Action(String actor, String method, Object arguments) {
        this.actor = actor;
        this.method = method;
        this.arguments = arguments;
    }

    /**
     * Gets the actor name.
     *
     * @return the actor name
     */
    public String getActor() {
        return actor;
    }

    /**
     * Sets the actor name.
     *
     * @param actor the actor name
     */
    public void setActor(String actor) {
        this.actor = actor;
    }

    /**
     * Gets the method name.
     *
     * @return the method name
     */
    public String getMethod() {
        return method;
    }

    /**
     * Sets the method name.
     *
     * @param method the method name
     */
    public void setMethod(String method) {
        this.method = method;
    }

    /**
     * Gets the arguments (String, List, or Map).
     *
     * @return the arguments, or null if not set
     */
    public Object getArguments() {
        return arguments;
    }

    /**
     * Sets the arguments (String, List, or Map).
     *
     * @param arguments the arguments
     */
    public void setArguments(Object arguments) {
        this.arguments = arguments;
    }

    /**
     * Gets the execution mode.
     *
     * @return the execution mode
     */
    public ExecutionMode getExecution() {
        return execution;
    }

    /**
     * Sets the execution mode.
     *
     * @param execution the execution mode
     */
    public void setExecution(ExecutionMode execution) {
        this.execution = execution;
    }

    /**
     * Gets the pool index.
     *
     * @return the pool index
     */
    public int getPoolIndex() {
        return poolIndex;
    }

    /**
     * Sets the pool index.
     *
     * @param poolIndex the pool index (0-based)
     */
    public void setPoolIndex(int poolIndex) {
        this.poolIndex = poolIndex;
    }
}
