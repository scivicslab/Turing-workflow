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

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.json.JSONArray;

import com.scivicslab.pojoactor.core.ActionResult;

/**
 * Actor that holds workflow variables.
 *
 * <p>This actor provides a simple key-value store for workflow variables.
 * Variables can be set via CLI options (-Dname=value) or within the workflow.</p>
 *
 * <h2>Actor Tree Position</h2>
 * <pre>
 * ROOT
 * ├── interpreter
 * └── vars
 * </pre>
 *
 * <h2>Available Actions</h2>
 * <ul>
 *   <li>{@code get} - Get a variable value by name</li>
 *   <li>{@code set} - Set a variable value</li>
 *   <li>{@code list} - List all variable names</li>
 * </ul>
 *
 * <h2>Usage in Workflow</h2>
 * <pre>
 * - actor: vars
 *   method: get
 *   arguments: "name"
 * </pre>
 *
 * @author devteam@scivicslab.com
 * @since 2.13.0
 */
public class VarsActor extends IIActorRef<Map<String, String>> {

    /** The name of the vars actor. */
    public static final String VARS_NAME = "vars";

    /**
     * Constructs a new VarsActor with an empty variable map.
     *
     * @param system the actor system
     */
    public VarsActor(IIActorSystem system) {
        super(VARS_NAME, new ConcurrentHashMap<>(), system);
    }

    /**
     * Constructs a new VarsActor with initial variables.
     *
     * @param system the actor system
     * @param initialVars initial variables to set
     */
    public VarsActor(IIActorSystem system, Map<String, String> initialVars) {
        super(VARS_NAME, new ConcurrentHashMap<>(initialVars), system);
    }

    @Override
    public ActionResult callByActionName(String actionName, String args) {
        return switch (actionName) {
            case "get" -> get(args);
            case "set" -> set(args);
            case "list" -> list();
            default -> super.callByActionName(actionName, args);
        };
    }

    /**
     * Gets a variable value by name.
     *
     * @param args the variable name (may be JSON array format)
     * @return ActionResult with the variable value, or empty string if not found
     */
    private ActionResult get(String args) {
        String name = parseFirstArgument(args);
        String value = object.getOrDefault(name, "");
        return new ActionResult(true, value);
    }

    /**
     * Sets a variable value.
     *
     * @param args JSON array with [name, value]
     * @return ActionResult indicating success
     */
    private ActionResult set(String args) {
        try {
            JSONArray arr = new JSONArray(args);
            if (arr.length() >= 2) {
                String name = arr.getString(0);
                String value = arr.getString(1);
                object.put(name, value);
                return new ActionResult(true, "Set " + name + "=" + value);
            }
            return new ActionResult(false, "Expected [name, value]");
        } catch (Exception e) {
            return new ActionResult(false, "Invalid arguments: " + e.getMessage());
        }
    }

    /**
     * Lists all variable names.
     *
     * @return ActionResult with comma-separated list of variable names
     */
    private ActionResult list() {
        String names = String.join(", ", object.keySet());
        return new ActionResult(true, names.isEmpty() ? "(no variables)" : names);
    }

    /**
     * Expands variables in a string.
     *
     * <p>Replaces ${varName} patterns with their values from the variable map.</p>
     *
     * @param template the string containing ${...} patterns
     * @return the expanded string
     */
    public String expand(String template) {
        if (template == null) {
            return "";
        }
        String result = template;
        for (Map.Entry<String, String> entry : object.entrySet()) {
            result = result.replace("${" + entry.getKey() + "}", entry.getValue());
        }
        return result;
    }
}
