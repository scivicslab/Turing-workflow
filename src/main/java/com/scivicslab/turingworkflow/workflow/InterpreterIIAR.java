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

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.ExecutionException;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.scivicslab.pojoactor.action.ActionResult;

/**
 * Interpreter-interfaced actor reference for {@link Interpreter} instances.
 *
 * <p>This class provides a concrete implementation of {@link IIActorRef}
 * specifically for {@link Interpreter} objects. It handles action invocations
 * by name, supporting actions such as reading YAML/JSON workflow definitions
 * and executing workflow code.</p>
 *
 * <p>Supported actions include:</p>
 * <ul>
 * <li>{@code execCode} - Executes the loaded workflow code</li>
 * <li>{@code readYaml} - Reads a YAML workflow definition from a file path</li>
 * <li>{@code readJson} - Reads a JSON workflow definition from a file path</li>
 * <li>{@code setCurrentState} - Sets the interpreter's current state to the specified value</li>
 * <li>{@code doNothing} - No-op action; returns success immediately without doing anything</li>
 * </ul>
 *
 * @author devteam@scivicslab.com
 */
public class InterpreterIIAR extends IIActorRef<Interpreter> {

    Logger logger = null;


    /**
     * Constructs a new InterpreterIIAR with the specified actor name and interpreter object.
     *
     * @param actorName the name of this actor
     * @param object the {@link Interpreter} instance managed by this actor reference
     */
    public InterpreterIIAR(String actorName, Interpreter object) {
        super(actorName, object);
        logger = Logger.getLogger(actorName);
    }

    /**
     * Constructs a new InterpreterIIAR with the specified actor name, interpreter object,
     * and actor system.
     *
     * @param actorName the name of this actor
     * @param object the {@link Interpreter} instance managed by this actor reference
     * @param system the actor system managing this actor
     */
    public InterpreterIIAR(String actorName, Interpreter object, IIActorSystem system) {
        super(actorName, object, system);
        logger = Logger.getLogger(actorName);
    }

    /**
     * Invokes an action on the interpreter by name with the given arguments.
     *
     * <p>This method handles the following actions:</p>
     * <ul>
     * <li>{@code execCode} - Executes the workflow code and returns the result</li>
     * <li>{@code readYaml} - Reads a YAML file from the path specified in {@code arg}</li>
     * <li>{@code readJson} - Reads a JSON file from the path specified in {@code arg}</li>
     * </ul>
     *
     * @param actionName the name of the action to execute
     * @param arg the argument string (typically a file path for read operations)
     * @return an {@link ActionResult} indicating success or failure with a message
     */
    @Override
    public ActionResult callByActionName(String actionName, String arg) {

        logger.fine(String.format("actionName = %s, args = %s", actionName, arg));

        boolean success = false;
        String message = "";

        try {
            if (actionName.equals("execCode")) {
                ActionResult result = this.ask((Interpreter i) -> i.execCode(), this.system().getManagedThreadPool()).get();
                return result;
            }
            else if (actionName.equals("runUntilEnd")) {
                // Parse optional maxIterations argument
                int maxIterations = 10000;
                if (arg != null && !arg.isEmpty() && !arg.equals("[]")) {
                    try {
                        org.json.JSONArray args = new org.json.JSONArray(arg);
                        if (args.length() > 0) {
                            maxIterations = args.getInt(0);
                        }
                    } catch (Exception e) {
                        // Use default if parsing fails
                    }
                }
                final int iterations = maxIterations;
                ActionResult result = this.ask((Interpreter i) -> i.runUntilEnd(iterations), this.system().getManagedThreadPool()).get();
                return result;
            }
            else if (actionName.equals("call")) {
                // Subworkflow call (creates child actor)
                org.json.JSONArray args = new org.json.JSONArray(arg);
                String workflowFile = args.getString(0);
                ActionResult result = this.ask((Interpreter i) -> i.call(workflowFile), this.system().getManagedThreadPool()).get();
                return result;
            }
            else if (actionName.equals("runWorkflow")) {
                // Load and run workflow directly (no child actor)
                org.json.JSONArray args = new org.json.JSONArray(arg);
                String workflowFile = args.getString(0);
                int maxIterations = args.length() > 1 ? args.getInt(1) : 10000;
                ActionResult result = this.ask((Interpreter i) -> i.runWorkflow(workflowFile, maxIterations), this.system().getManagedThreadPool()).get();
                return result;
            }
            else if (actionName.equals("apply")) {
                // Apply action to child actors
                ActionResult result = this.ask((Interpreter i) -> i.apply(arg), this.system().getManagedThreadPool()).get();
                return result;
            }
            else if (actionName.equals("readYaml")) {
                try (InputStream input = new FileInputStream(new File(arg))) {
                    this.tell((Interpreter i) -> i.readYaml(input)).get();
                    success = true;
                    message = "YAML loaded successfully";
                } catch (FileNotFoundException e) {
                    logger.log(Level.SEVERE, String.format("file not found: %s", arg), e);
                    message = "File not found: " + arg;
                } catch (IOException e) {
                    logger.log(Level.SEVERE, String.format("IOException: %s", arg), e);
                    message = "IO error: " + arg;
                }
            } else if (actionName.equals("sleep")) {
                try {
                    long millis = Long.parseLong(arg);
                    Thread.sleep(millis);
                    success = true;
                    message = "Slept for " + millis + "ms";
                } catch (NumberFormatException e) {
                    logger.log(Level.SEVERE, String.format("Invalid sleep duration: %s", arg), e);
                    message = "Invalid sleep duration: " + arg;
                }
            } else if (actionName.equals("print")) {
                System.out.println(arg);
                success = true;
                message = "Printed: " + arg;
            } else if (actionName.equals("onlyIf")) {
                return onlyIf(arg);
            } else if (actionName.equals("doNothing")) {
                success = true;
                message = arg;
            } else if (actionName.equals("setCurrentState")) {
                String targetState = arg;
                if (arg != null && arg.startsWith("[")) {
                    org.json.JSONArray args = new org.json.JSONArray(arg);
                    targetState = args.length() > 0 ? args.getString(0) : arg;
                }
                final String state = targetState;
                this.tell((Interpreter i) -> i.setCurrentState(state)).get();
                success = true;
                message = "currentState set to: " + state;
            } else {
                // Delegate to parent for JSON State API and other common actions
                return super.callByActionName(actionName, arg);
            }
        }
        catch (InterruptedException e) {
            logger.log(Level.SEVERE, String.format("actionName = %s, args = %s", actionName, arg), e);
            message = "Interrupted";
        } catch (ExecutionException e) {
            logger.log(Level.SEVERE, String.format("actionName = %s, args = %s", actionName, arg), e);
            message = "Execution error";
        }

        return new ActionResult(success, message);
    }

    /**
     * A condition on the workflow's values, as an action.
     *
     * <p>A {@code states} pattern chooses on the state the workflow is in and cannot read the
     * values it has stored, so a condition on those values is written here instead: the transition
     * fails when it does not hold, and the engine falls to the next candidate transition from the
     * same state. That is how a loop over a list ends
     * ({@code ActorsAsVariables_260914_oo01}).</p>
     *
     * <pre>{@code
     * - actor: this
     *   method: onlyIf
     *   arguments: "jexl: state.getInt('i',0) < state.select('items').size()"
     * }</pre>
     *
     * <p>Only a yes or a no is accepted. An expression that could not be evaluated answers
     * {@code null}, and anything else — a number, a piece of text — is refused rather than read as
     * a yes, so a mistyped condition stops the workflow instead of choosing a branch.</p>
     *
     * @param arg the argument as the interpreter passes it: a JSON array holding the one value the
     *            expression answered
     * @return success when the condition holds, failure when it does not or was never a condition
     */
    private ActionResult onlyIf(String arg) {
        Object value = singleArgument(arg);
        if (Boolean.TRUE.equals(value)) {
            return new ActionResult(true, "onlyIf holds");
        }
        if (Boolean.FALSE.equals(value)) {
            return new ActionResult(false, "onlyIf does not hold: false");
        }
        return new ActionResult(false, "onlyIf was not given a condition: " + value);
    }

    /**
     * The one value an action's arguments carry.
     *
     * @param arg a JSON array, a JSON object with one value, or a bare string
     * @return that value, or {@code null} when there is none
     */
    private static Object singleArgument(String arg) {
        if (arg == null || arg.isBlank()) {
            return null;
        }
        String text = arg.strip();
        try {
            if (text.startsWith("[")) {
                org.json.JSONArray array = new org.json.JSONArray(text);
                return array.isEmpty() || array.isNull(0) ? null : array.get(0);
            }
            if (text.startsWith("{")) {
                org.json.JSONObject object = new org.json.JSONObject(text);
                String key = object.keys().hasNext() ? object.keys().next() : null;
                return key == null || object.isNull(key) ? null : object.get(key);
            }
        } catch (RuntimeException e) {
            return text;
        }
        return text;
    }

}
