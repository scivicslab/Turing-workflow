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

package com.scivicslab.turingworkflow.plugin;

import org.json.JSONArray;
import org.json.JSONException;

import com.scivicslab.pojoactor.core.ActionResult;
import com.scivicslab.pojoactor.core.CallableByActionName;

/**
 * Sample plugin class for testing dynamic actor loading with string-based invocation.
 *
 * This class demonstrates the actor-WF approach where plugin methods can be invoked
 * using string-based action names, enabling:
 * <ul>
 *   <li>YAML/JSON-based workflow execution</li>
 *   <li>Distributed system support (actions serializable as strings)</li>
 *   <li>No reflection overhead in production</li>
 * </ul>
 *
 * <p>The class provides both traditional type-safe methods and string-based invocation
 * via {@link CallableByActionName}.</p>
 *
 * @author devteam@scivicslab.com
 * @version 2.0.0
 */
public class MathPlugin implements CallableByActionName {

    private int lastResult = 0;

    /**
     * Public no-argument constructor required for dynamic loading.
     */
    public MathPlugin() {
        // Required for Class.newInstance()
    }

    /**
     * Adds two numbers and stores the result.
     *
     * @param a first number
     * @param b second number
     * @return the sum
     */
    public int add(int a, int b) {
        lastResult = a + b;
        return lastResult;
    }

    /**
     * Multiplies two numbers and stores the result.
     *
     * @param a first number
     * @param b second number
     * @return the product
     */
    public int multiply(int a, int b) {
        lastResult = a * b;
        return lastResult;
    }

    /**
     * Returns the last calculated result.
     *
     * @return the last result
     */
    public int getLastResult() {
        return lastResult;
    }

    /**
     * Returns a greeting message.
     *
     * @param name the name to greet
     * @return greeting message
     */
    public String greet(String name) {
        return "Hello, " + name + " from MathPlugin!";
    }

    /**
     * Executes an action by name using string-based arguments.
     *
     * <p>This method enables workflow-driven execution and distributed system support.
     * Actions can be invoked from YAML/JSON workflows or sent across network boundaries.</p>
     *
     * <p>Supports both JSON array format (new) and comma-separated format (legacy):</p>
     * <ul>
     *   <li>JSON array: {@code ["5", "3"]} or {@code ["5","3"]}</li>
     *   <li>Comma-separated: {@code "5,3"} (for backward compatibility)</li>
     * </ul>
     *
     * <h3>Supported Actions</h3>
     * <ul>
     *   <li><strong>add</strong>: Args format: ["a","b"] or "a,b"</li>
     *   <li><strong>multiply</strong>: Args format: ["a","b"] or "a,b"</li>
     *   <li><strong>getLastResult</strong>: No args needed (empty array [] or empty string)</li>
     *   <li><strong>greet</strong>: Args format: ["name"] or "name"</li>
     * </ul>
     *
     * @param actionName the name of the action to execute
     * @param args string arguments (JSON array format or comma-separated)
     * @return an {@link ActionResult} indicating success or failure
     */
    @Override
    public ActionResult callByActionName(String actionName, String args) {
        try {
            switch (actionName) {
                case "add":
                    String[] addArgs = parseArguments(args, 2);
                    int a = Integer.parseInt(addArgs[0].trim());
                    int b = Integer.parseInt(addArgs[1].trim());
                    int sum = add(a, b);
                    return new ActionResult(true, String.valueOf(sum));

                case "multiply":
                    String[] mulArgs = parseArguments(args, 2);
                    int x = Integer.parseInt(mulArgs[0].trim());
                    int y = Integer.parseInt(mulArgs[1].trim());
                    int product = multiply(x, y);
                    return new ActionResult(true, String.valueOf(product));

                case "getLastResult":
                    int result = getLastResult();
                    return new ActionResult(true, String.valueOf(result));

                case "greet":
                    String[] greetArgs = parseArguments(args, 1);
                    String greeting = greet(greetArgs[0].trim());
                    return new ActionResult(true, greeting);

                default:
                    return new ActionResult(false, "Unknown action: " + actionName);
            }
        } catch (NumberFormatException e) {
            return new ActionResult(false, "Invalid number format: " + e.getMessage());
        } catch (IllegalArgumentException e) {
            return new ActionResult(false, e.getMessage());
        } catch (Exception e) {
            return new ActionResult(false, "Error: " + e.getMessage());
        }
    }

    /**
     * Parses arguments from either JSON array format or comma-separated format.
     *
     * @param args the arguments string (JSON array or comma-separated)
     * @param expectedCount the expected number of arguments
     * @return array of parsed arguments
     * @throws IllegalArgumentException if argument count doesn't match expected
     */
    private String[] parseArguments(String args, int expectedCount) {
        if (args == null || args.trim().isEmpty()) {
            if (expectedCount == 0) {
                return new String[0];
            }
            throw new IllegalArgumentException("Action requires " + expectedCount + " argument(s) but got none");
        }

        String trimmedArgs = args.trim();

        // Try parsing as JSON array first
        if (trimmedArgs.startsWith("[")) {
            try {
                JSONArray jsonArray = new JSONArray(trimmedArgs);
                if (jsonArray.length() != expectedCount) {
                    throw new IllegalArgumentException(
                        "Action requires " + expectedCount + " argument(s) but got " + jsonArray.length());
                }
                String[] result = new String[jsonArray.length()];
                for (int i = 0; i < jsonArray.length(); i++) {
                    result[i] = jsonArray.getString(i);
                }
                return result;
            } catch (JSONException e) {
                throw new IllegalArgumentException("Invalid JSON array format: " + e.getMessage());
            }
        }

        // Fall back to comma-separated format (backward compatibility)
        // Special case: if expecting 1 argument, treat entire string as single argument
        // (don't split on comma - allows names like "Smith, John")
        if (expectedCount == 1) {
            return new String[] { trimmedArgs };
        }

        String[] parts = trimmedArgs.split(",");
        if (parts.length != expectedCount) {
            throw new IllegalArgumentException(
                "Action requires " + expectedCount + " argument(s) but got " + parts.length);
        }
        return parts;
    }
}
