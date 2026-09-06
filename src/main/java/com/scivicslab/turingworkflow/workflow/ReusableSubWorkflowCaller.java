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

import java.io.InputStream;

import org.json.JSONArray;

import com.scivicslab.pojoactor.action.Action;
import com.scivicslab.pojoactor.action.ActionResult;

/**
 * Reusable sub-workflow caller that reuses a single Interpreter instance.
 *
 * <p>This class is an alternative to {@link SubWorkflowCaller} that reuses
 * a single {@link Interpreter} instance across multiple sub-workflow calls.
 * This reduces object allocation overhead at the cost of thread synchronization.</p>
 *
 * <h2>Comparison with SubWorkflowCaller:</h2>
 * <table border="1">
 *   <caption>Comparison of SubWorkflowCaller patterns</caption>
 *   <tr>
 *     <th>Aspect</th>
 *     <th>SubWorkflowCaller</th>
 *     <th>ReusableSubWorkflowCaller</th>
 *   </tr>
 *   <tr>
 *     <td>Interpreter creation</td>
 *     <td>New instance per call</td>
 *     <td>Single instance, reused</td>
 *   </tr>
 *   <tr>
 *     <td>Memory allocation</td>
 *     <td>Higher (creates objects)</td>
 *     <td>Lower (reuses objects)</td>
 *   </tr>
 *   <tr>
 *     <td>GC pressure</td>
 *     <td>Higher</td>
 *     <td>Lower</td>
 *   </tr>
 *   <tr>
 *     <td>Thread safety</td>
 *     <td>Fully concurrent</td>
 *     <td>Synchronized (serialized calls)</td>
 *   </tr>
 *   <tr>
 *     <td>Complexity</td>
 *     <td>Simple</td>
 *     <td>More complex</td>
 *   </tr>
 *   <tr>
 *     <td>Best for</td>
 *     <td>General use, concurrent calls</td>
 *     <td>High-frequency calls, low concurrency</td>
 *   </tr>
 * </table>
 *
 * <h2>Usage Example:</h2>
 * <pre>{@code
 * // Create ReusableSubWorkflowCaller actor and add to system
 * ReusableSubWorkflowCaller caller = new ReusableSubWorkflowCaller("caller", system);
 * system.addIIActor(caller);
 *
 * // In workflow YAML:
 * matrix:
 *   - states: ["0", "1"]
 *     actions:
 *       - [caller, call, "sub-workflow.yaml"]
 * }</pre>
 *
 * <h2>Performance Considerations:</h2>
 * <ul>
 *   <li><strong>When to use</strong>: High-frequency sub-workflow calls with
 *       low concurrency (sequential calls)</li>
 *   <li><strong>When NOT to use</strong>: High concurrency scenarios where
 *       multiple threads call sub-workflows simultaneously (use {@link SubWorkflowCaller} instead)</li>
 *   <li><strong>Bottleneck</strong>: The {@code synchronized} method becomes
 *       a bottleneck under high concurrency</li>
 * </ul>
 *
 * <h2>Thread Safety:</h2>
 * <p>This class uses method-level synchronization to ensure thread safety.
 * Only one thread can execute a sub-workflow at a time. If multiple threads
 * call this actor concurrently, they will be serialized.</p>
 *
 * @author devteam@scivicslab.com
 * @since 2.5.0
 * @see SubWorkflowCaller
 * @see Interpreter#reset()
 * @see IIActorSystem
 */
public class ReusableSubWorkflowCaller extends IIActorRef<Void> {

    private final Interpreter reusableInterpreter;
    private int callCount = 0;

    /**
     * Constructs a new ReusableSubWorkflowCaller.
     *
     * <p>Creates a single {@link Interpreter} instance that will be reused
     * across all sub-workflow calls.</p>
     *
     * @param name the actor name used to register this actor in the system
     * @param system the actor system to use for sub-workflow execution.
     *               This system is shared between main and sub-workflows.
     */
    public ReusableSubWorkflowCaller(String name, IIActorSystem system) {
        super(name, null, system);

        // Create the reusable Interpreter instance
        this.reusableInterpreter = new Interpreter.Builder()
            .loggerName("reusable-sub-workflow")
            .team(system)
            .build();
    }

    /**
     * Calls the specified sub-workflow synchronously using the reusable Interpreter.
     *
     * <p>The {@code args} parameter should contain the YAML filename
     * (e.g., "my-workflow.yaml"), either as a plain string or as a JSON array.</p>
     *
     * @param args the YAML filename (plain string or JSON array)
     * @return {@link ActionResult} indicating success or failure
     */
    @Action("call")
    public synchronized ActionResult call(String args) {
        return callSubWorkflow(args);
    }

    /**
     * Extracts the first element from a JSON array string.
     * If the input is not a JSON array, returns the input as-is.
     *
     * @param args the argument string (may be JSON array or plain string)
     * @return the first element if JSON array, otherwise the original string
     */
    private String getFirstArg(String args) {
        if (args == null || args.isEmpty()) {
            return args;
        }
        if (args.startsWith("[")) {
            JSONArray jsonArray = new JSONArray(args);
            return jsonArray.length() > 0 ? jsonArray.getString(0) : "";
        }
        return args;
    }

    /**
     * Calls a sub-workflow synchronously using the reusable Interpreter.
     *
     * <p><strong>Important</strong>: This method resets the Interpreter state
     * before each execution using {@link Interpreter#reset()}.</p>
     *
     * @param yamlFileName the name of the YAML file (e.g., "sub-workflow.yaml").
     *                     The file is loaded from {@code /workflows/[yamlFileName]}.
     *                     Can be a JSON array (e.g., {@code ["filename.yaml"]}) or plain string.
     * @return {@link ActionResult} indicating success or failure
     */
    private ActionResult callSubWorkflow(String yamlFileName) {
        String actualFileName = getFirstArg(yamlFileName);
        if (actualFileName == null || actualFileName.trim().isEmpty()) {
            return new ActionResult(false, "YAML filename cannot be null or empty");
        }

        int depth = SubWorkflowCaller.callDepth.get();
        SubWorkflowCaller.callDepth.set(depth + 1);
        String baseName = actualFileName.replaceAll("\\.(yaml|yml)$", "");
        System.out.println("[SUBWORKFLOW_START:" + baseName + ":" + depth + "]");

        try {
            // Reset the interpreter state
            reusableInterpreter.reset();

            // Load YAML file from classpath
            String resourcePath = "/workflows/" + actualFileName;
            InputStream yamlInput = getClass().getResourceAsStream(resourcePath);

            if (yamlInput == null) {
                return new ActionResult(false,
                    "Sub-workflow YAML not found: " + resourcePath);
            }

            reusableInterpreter.readYaml(yamlInput);

            // Execute all steps of sub-workflow until completion
            int stepCount = 0;
            int maxSteps = 1000; // Safety limit to prevent infinite loops

            while (stepCount < maxSteps) {
                ActionResult result = reusableInterpreter.execCode();
                stepCount++;

                if (!result.isSuccess()) {
                    return new ActionResult(false,
                        "Sub-workflow failed at step " + stepCount + ": " + result.getResult());
                }

                if (result.getResult().contains("end")) {
                    break;
                }
            }

            if (stepCount >= maxSteps) {
                return new ActionResult(false,
                    "Sub-workflow exceeded maximum steps (" + maxSteps + ")");
            }

            callCount++;
            return new ActionResult(true,
                "Sub-workflow called successfully: " + actualFileName +
                " (steps: " + stepCount + ", reused interpreter)");

        } catch (Exception e) {
            return new ActionResult(false,
                "Sub-workflow call error: " + e.getMessage());
        } finally {
            System.out.println("[SUBWORKFLOW_END:" + baseName + ":" + depth + "]");
            SubWorkflowCaller.callDepth.set(depth);
        }
    }

    /**
     * Returns the number of successful sub-workflow calls.
     *
     * @return the call count
     */
    public int getCallCount() {
        return callCount;
    }
}
