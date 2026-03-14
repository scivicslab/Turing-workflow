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

import com.scivicslab.pojoactor.core.ActionResult;

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
            } else if (actionName.equals("readJson")) {
                try (InputStream input = new FileInputStream(new File(arg))) {
                    this.tell((Interpreter i) -> {
                        try {
                            i.readJson(input);
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    }).get();
                    success = true;
                    message = "JSON loaded successfully";
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
            } else if (actionName.equals("doNothing")) {
                success = true;
                message = arg;
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

}
