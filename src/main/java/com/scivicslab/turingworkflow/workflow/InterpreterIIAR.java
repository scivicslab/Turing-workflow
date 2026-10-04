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

import com.scivicslab.pojoactor.action.Action;
import com.scivicslab.pojoactor.action.ActionResult;

/**
 * Interpreter-interfaced actor reference for {@link Interpreter} instances.
 *
 * <p>This class provides a concrete implementation of {@link IIActorRef}
 * specifically for {@link Interpreter} objects. A workflow reaches it as {@code this} (also
 * {@code interpreter}): the actions below are the engine's own — conditions, sub-workflows,
 * the current state — and, inherited from {@link IIActorRef}, the JSON State actions
 * ({@code putJson}, {@code appendJson}, {@code getJson}, {@code hasJson}, {@code clearJson},
 * {@code printJson}). Each is one {@code @Action} method, so the doclet documents it and a
 * caller can list it without a run.</p>
 *
 * <p>A bare string under {@code arguments:} reaches an action as a JSON array of one element;
 * the actions that take one value therefore accept both the bare text and the array.</p>
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
     * Runs the loaded workflow code once from its current state; the message is the engine's result.
     *
     * <p>For a workflow that drives another interpreter it created. A workflow does not call it on
     * itself.</p>
     *
     * <pre>{@code
     * - actor: child
     *   method: execCode
     * }</pre>
     *
     * @param args ignored
     */
    @Action("execCode")
    public ActionResult execCode(String args) {
        return asked("execCode", args, i -> i.execCode());
    }

    /**
     * Runs the loaded workflow until it reaches the {@code end} state; the message is the engine's
     * result.
     *
     * <p>Stops after {@code maxIterations} transitions when the workflow has not ended, so a loop
     * that never ends cannot run forever; 10000 when not given. For a workflow that drives another
     * interpreter; {@code call} and {@code runWorkflow} do this for a sub-workflow in one step.</p>
     *
     * <pre>{@code
     * - actor: child
     *   method: runUntilEnd
     *   arguments: [50]
     * }</pre>
     *
     * @param args a JSON array whose first element is {@code maxIterations}; empty for the default
     */
    @Action("runUntilEnd")
    public ActionResult runUntilEnd(String args) {
        int maxIterations = 10000;
        if (args != null && !args.isEmpty() && !args.equals("[]")) {
            try {
                org.json.JSONArray array = new org.json.JSONArray(args);
                if (array.length() > 0) {
                    maxIterations = array.getInt(0);
                }
            } catch (Exception e) {
                // not a number: the default stands
            }
        }
        final int iterations = maxIterations;
        return asked("runUntilEnd", args, i -> i.runUntilEnd(iterations));
    }

    /**
     * Runs a sub-workflow file to its end in a child interpreter, then removes the child; the
     * message is the sub-workflow's result.
     *
     * <p>The child shares this actor system, so the sub-workflow can call every registered actor.
     * The file is resolved as {@link Interpreter#call(String)} resolves it.</p>
     *
     * <pre>{@code
     * - actor: this
     *   method: call
     *   arguments: ["sub-workflow.yaml"]
     * }</pre>
     *
     * @param args a JSON array whose first element is the workflow file
     */
    @Action("call")
    public ActionResult call(String args) {
        String workflowFile = parseFirstArgument(args);
        return asked("call", args, i -> i.call(workflowFile));
    }

    /**
     * Loads a workflow file into this interpreter and runs it to its end, without a child; the
     * message is the result.
     *
     * <p>Replaces the workflow this interpreter was running, so it is for an interpreter that
     * {@code apply} drives, not for a workflow to call on itself.</p>
     *
     * <pre>{@code
     * - actor: this
     *   method: apply
     *   arguments:
     *     actor: "node-*"
     *     method: runWorkflow
     *     arguments: ["task.yaml"]
     * }</pre>
     *
     * @param args a JSON array: the workflow file, then optionally {@code maxIterations}
     */
    @Action("runWorkflow")
    public ActionResult runWorkflow(String args) {
        org.json.JSONArray array = new org.json.JSONArray(args);
        String workflowFile = array.getString(0);
        int maxIterations = array.length() > 1 ? array.getInt(1) : 10000;
        return asked("runWorkflow", args, i -> i.runWorkflow(workflowFile, maxIterations));
    }

    /**
     * Calls one action on the child actors whose names match a pattern; the message is the combined
     * result.
     *
     * <p>{@code *} is all children, {@code node-*} those starting with {@code node-}. Unlike
     * {@code call}, the children stay.</p>
     *
     * <pre>{@code
     * - actor: this
     *   method: apply
     *   arguments:
     *     actor: "Species-*"
     *     method: mutate
     *     arguments: [0.05, 0.02, 0.5]
     * }</pre>
     *
     * @param args {@code {"actor": "<pattern>", "method": "<action>", "arguments": <any>}}
     */
    @Action("apply")
    public ActionResult apply(String args) {
        return asked("apply", args, i -> i.apply(args));
    }

    /**
     * Loads a YAML workflow file into this interpreter without running it.
     *
     * <pre>{@code
     * - actor: child
     *   method: readYaml
     *   arguments: "sub-workflow.yaml"
     * }</pre>
     *
     * @param args the file path, as a bare string or as the first element of a JSON array
     */
    @Action("readYaml")
    public ActionResult readYaml(String args) {
        String path = parseFirstArgument(args);
        try (InputStream input = new FileInputStream(new File(path))) {
            this.tell((Interpreter i) -> i.readYaml(input)).get();
            return new ActionResult(true, "YAML loaded successfully");
        } catch (FileNotFoundException e) {
            logger.log(Level.SEVERE, String.format("file not found: %s", path), e);
            return new ActionResult(false, "File not found: " + path);
        } catch (IOException e) {
            logger.log(Level.SEVERE, String.format("IOException: %s", path), e);
            return new ActionResult(false, "IO error: " + path);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new ActionResult(false, "Interrupted");
        } catch (ExecutionException e) {
            logger.log(Level.SEVERE, String.format("readYaml: %s", path), e);
            return new ActionResult(false, "Execution error");
        }
    }

    /**
     * Pauses the workflow for a number of milliseconds.
     *
     * <p>For spacing retries within one run. A retry that goes through the prompt queue is spaced
     * with the transition's {@code delay:} instead.</p>
     *
     * <pre>{@code
     * - actor: this
     *   method: sleep
     *   arguments: "1000"
     * }</pre>
     *
     * @param args the milliseconds, as a bare number or as the first element of a JSON array
     */
    @Action("sleep")
    public ActionResult sleep(String args) {
        String text = parseFirstArgument(args);
        try {
            long millis = Long.parseLong(text.strip());
            Thread.sleep(millis);
            return new ActionResult(true, "Slept for " + millis + "ms");
        } catch (NumberFormatException e) {
            logger.log(Level.SEVERE, String.format("Invalid sleep duration: %s", text), e);
            return new ActionResult(false, "Invalid sleep duration: " + text);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new ActionResult(false, "Interrupted");
        }
    }

    /**
     * Prints a line to standard output; the message repeats it.
     *
     * <p>For a note in the run's log, such as a catch-all transition saying why the run stopped.</p>
     *
     * <pre>{@code
     * - actor: this
     *   method: print
     *   arguments: "explain-approve-implement: rejected — stopped."
     * }</pre>
     *
     * @param args the text, as a bare string or as the first element of a JSON array
     */
    @Action("print")
    public ActionResult print(String args) {
        String text = parseFirstArgument(args);
        System.out.println(text);
        return new ActionResult(true, "Printed: " + text);
    }

    /**
     * Does nothing and succeeds; the message is the argument text.
     *
     * <p>For a transition that only changes state, or a placeholder while a workflow is written.</p>
     *
     * <pre>{@code
     * - actor: this
     *   method: doNothing
     *   arguments: ["setup-1"]
     * }</pre>
     *
     * @param args any text, repeated as the message
     */
    @Action("doNothing")
    public ActionResult doNothing(String args) {
        return new ActionResult(true, parseFirstArgument(args));
    }

    /**
     * Moves this interpreter to a state by name; the next transition is chosen from there.
     *
     * <p>A jump the transitions table did not write. For a workflow driving another interpreter,
     * or to reset a loop.</p>
     *
     * <pre>{@code
     * - actor: this
     *   method: setCurrentState
     *   arguments: "retry"
     * }</pre>
     *
     * @param args the state name, as a bare string or as the first element of a JSON array
     */
    @Action("setCurrentState")
    public ActionResult setCurrentState(String args) {
        final String state = parseFirstArgument(args);
        try {
            this.tell((Interpreter i) -> i.setCurrentState(state)).get();
            return new ActionResult(true, "currentState set to: " + state);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new ActionResult(false, "Interrupted");
        } catch (ExecutionException e) {
            logger.log(Level.SEVERE, String.format("setCurrentState: %s", state), e);
            return new ActionResult(false, "Execution error");
        }
    }

    /** Asks the interpreter on the managed pool and turns the two wait failures into results. */
    private ActionResult asked(String actionName, String args,
                               java.util.function.Function<Interpreter, ActionResult> work) {
        try {
            return this.ask(work, this.system().getManagedThreadPool()).get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.log(Level.SEVERE, String.format("actionName = %s, args = %s", actionName, args), e);
            return new ActionResult(false, "Interrupted");
        } catch (ExecutionException e) {
            logger.log(Level.SEVERE, String.format("actionName = %s, args = %s", actionName, args), e);
            return new ActionResult(false, "Execution error");
        }
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
    @Action("onlyIf")
    public ActionResult onlyIf(String arg) {
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
