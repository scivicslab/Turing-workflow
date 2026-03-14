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

import com.scivicslab.pojoactor.core.ActionResult;
import com.scivicslab.pojoactor.core.CallableByActionName;

/**
 * General-purpose sub-workflow caller actor.
 *
 * <p>This class provides a reusable pattern for calling sub-workflows from within
 * a main workflow. It creates a new {@link Interpreter} instance, loads a YAML workflow
 * definition, and executes it synchronously.</p>
 *
 * <h2>Usage Example:</h2>
 * <pre>{@code
 * // 1. Create SubWorkflowCaller actor
 * SubWorkflowCaller caller = new SubWorkflowCaller(system);
 * IIActorRef<SubWorkflowCaller> ref = new IIActorRef<>("caller", caller, system);
 * system.addIIActor(ref);
 *
 * // 2. In workflow YAML:
 * matrix:
 *   - states: ["0", "1"]
 *     actions:
 *       - [caller, call, "sub-workflow.yaml"]
 * }</pre>
 *
 * <h2>Action Methods:</h2>
 * <ul>
 *   <li>{@code call(yamlFilePath)} - Calls the specified sub-workflow synchronously</li>
 * </ul>
 *
 * <h2>Implementation Notes:</h2>
 * <ul>
 *   <li>Sub-workflows are called <strong>synchronously</strong> (blocking)</li>
 *   <li>The sub-workflow shares the same {@link IIActorSystem} as the main workflow</li>
 *   <li>The {@link Interpreter} instance is created and destroyed within each call</li>
 *   <li>YAML files are loaded from the classpath using {@link Class#getResourceAsStream}</li>
 * </ul>
 *
 * <h2>Lifecycle:</h2>
 * <ol>
 *   <li>Main workflow calls {@code call} action</li>
 *   <li>New {@link Interpreter} instance is created</li>
 *   <li>YAML file is loaded from {@code /workflows/[yamlFilePath]}</li>
 *   <li>Sub-workflow executes all steps until completion or error</li>
 *   <li>{@link Interpreter} goes out of scope and becomes eligible for GC</li>
 *   <li>Control returns to main workflow</li>
 * </ol>
 *
 * @author devteam@scivicslab.com
 * @since 2.5.0
 * @see Interpreter
 * @see IIActorSystem
 * @see CallableByActionName
 */
public class SubWorkflowCaller implements CallableByActionName {

    private final IIActorSystem system;
    private int callCount = 0;

    /**
     * Constructs a new SubWorkflowCaller.
     *
     * @param system the actor system to use for sub-workflow execution.
     *               This system is shared between main and sub-workflows.
     */
    public SubWorkflowCaller(IIActorSystem system) {
        if (system == null) {
            throw new IllegalArgumentException("ActorSystem cannot be null");
        }
        this.system = system;
    }

    /**
     * Executes actions by name.
     *
     * <p>Supported actions:</p>
     * <ul>
     *   <li>{@code call} - Calls a sub-workflow. The {@code args} parameter
     *       should contain the YAML filename (e.g., "my-workflow.yaml")</li>
     * </ul>
     *
     * @param actionName the name of the action to execute
     * @param args the arguments for the action (YAML filename for "call" action)
     * @return {@link ActionResult} indicating success or failure
     */
    @Override
    public ActionResult callByActionName(String actionName, String args) {
        if ("call".equals(actionName)) {
            return callSubWorkflow(args);
        }
        return new ActionResult(false, "Unknown action: " + actionName);
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
     * Calls a sub-workflow synchronously.
     *
     * <p>This method creates a new {@link Interpreter} instance, loads the specified
     * YAML workflow file, and executes all steps until completion or error.</p>
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

        try {
            // Create new Interpreter for sub-workflow
            Interpreter subInterpreter = new Interpreter.Builder()
                .loggerName("sub-workflow-" + callCount)
                .team(system)  // Share the same ActorSystem
                .build();

            // Load YAML file from classpath
            String resourcePath = "/workflows/" + actualFileName;
            InputStream yamlInput = getClass().getResourceAsStream(resourcePath);

            if (yamlInput == null) {
                return new ActionResult(false,
                    "Sub-workflow YAML not found: " + resourcePath);
            }

            subInterpreter.readYaml(yamlInput);

            // Execute all steps of sub-workflow until completion
            int stepCount = 0;
            int maxSteps = 1000; // Safety limit to prevent infinite loops

            while (stepCount < maxSteps) {
                ActionResult result = subInterpreter.execCode();
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
                " (steps: " + stepCount + ")");

        } catch (Exception e) {
            return new ActionResult(false,
                "Sub-workflow call error: " + e.getMessage());
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
