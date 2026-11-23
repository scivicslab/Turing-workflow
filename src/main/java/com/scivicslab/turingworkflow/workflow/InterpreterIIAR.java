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
 * <h2>Own steps and steps from other actors</h2>
 *
 * <p>A step whose target resolves to the actor that runs the workflow is that actor's own step.
 * The target is written {@code this}, {@code .}, the actor's own name, {@code ../<own name>},
 * {@code ../*} or a {@code ../} pattern that matches the actor's name. {@code Interpreter.action()}
 * marks the call ({@link IIActorRef#callAsStep}), and the actions that work on the interpreter
 * ({@code setCurrentState}, {@code readYaml}, {@code execCode}, {@code runUntilEnd}, {@code call},
 * {@code runWorkflow}, {@code apply}) then do that work directly on the thread that runs the step: a
 * real thread of the ManagedThreadPool, or the actor's mailbox virtual thread when the workflow is
 * the actor's own message. They do not send themselves to the actor's mailbox and wait there. The
 * workflow runs inside one message, and the mailbox's virtual thread takes the next message only when
 * that message has ended, so a message that waits for the next one never ends and the actor hangs.</p>
 *
 * <p>An action that another actor calls, such as a parent's {@code actor: child, method: runUntilEnd},
 * is not an own step and goes through the actor's mailbox, which does not start it before the message
 * it is running has ended. A sub-workflow's step that names its parent is such a call too, even though
 * it runs inside the parent's own step.</p>
 *
 * <p>The tests that pin this are {@code OwnStepDispatchTest}, {@code OwnStepMatrixTest} (every action,
 * every way to name the actor, every way to start a workflow) and {@code OwnStepMailboxRuleTest}. Moving an
 * action back to "send to this actor and wait" turns them red.</p>
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
      * <p>Called by this actor's own workflow ({@code actor: this} and the other ways to name the actor) it
     * does this directly; called by another actor it goes through this actor's mailbox.</p>
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
      * <p>Called by this actor's own workflow ({@code actor: this} and the other ways to name the actor) it
     * does this directly; called by another actor it goes through this actor's mailbox.</p>
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
      * <p>Called by this actor's own workflow ({@code actor: this} and the other ways to name the actor) it
     * does this directly; called by another actor it goes through this actor's mailbox.</p>
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
      * <p>Called by this actor's own workflow ({@code actor: this} and the other ways to name the actor) it
     * does this directly; called by another actor it goes through this actor's mailbox.</p>
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
      * <p>Called by this actor's own workflow ({@code actor: this} and the other ways to name the actor) it
     * does this directly; called by another actor it goes through this actor's mailbox.</p>
     *
     * @param args {@code {"actor": "<pattern>", "method": "<action>", "arguments": <any>}}
     */
    @Action(value = "apply", orchestrates = true)
    public ActionResult apply(String args) {
        return this.object.apply(args);
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
      * <p>Called by this actor's own workflow ({@code actor: this} and the other ways to name the actor) it
     * does this directly; called by another actor it goes through this actor's mailbox.</p>
     *
     * @param args the file path, as a bare string or as the first element of a JSON array
     */
    @Action("readYaml")
    public ActionResult readYaml(String args) {
        String path = parseFirstArgument(args);
        // Only a file under the interpreter's base directory: a path that climbs out of it, is
        // absolute elsewhere or is a link to elsewhere is refused (PathUnderBase).
        try (InputStream input = java.nio.file.Files.newInputStream(
                PathUnderBase.resolve(this.object.getWorkflowBaseDir(), path))) {
            inItsTurn((Interpreter i) -> i.readYaml(input));
            return new ActionResult(true, "YAML loaded successfully");
        } catch (IOException e) {
            logger.log(Level.SEVERE, String.format("readYaml: %s", path), e);
            return ActionResult.error(e, getName() + ".readYaml " + path);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ActionResult.error(e, getName() + ".readYaml " + path);
        } catch (ExecutionException e) {
            ActionResult.rethrowIfFatal(e);   // an Error is false; a fatal one goes on out of the run
            logger.log(Level.SEVERE, String.format("readYaml: %s", path), e);
            return ActionResult.error(e, getName() + ".readYaml " + path);
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
            return ActionResult.error(e, getName() + ".sleep, expected a number of milliseconds");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ActionResult.error(e, getName() + ".sleep");
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
      * <p>Called by this actor's own workflow ({@code actor: this} and the other ways to name the actor) it
     * does this directly; called by another actor it goes through this actor's mailbox.</p>
     *
     * @param args the state name, as a bare string or as the first element of a JSON array
     */
    @Action("setCurrentState")
    public ActionResult setCurrentState(String args) {
        final String state = parseFirstArgument(args);
        try {
            inItsTurn((Interpreter i) -> i.setCurrentState(state));
            return new ActionResult(true, "currentState set to: " + state);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ActionResult.error(e, getName() + ".setCurrentState");
        } catch (ExecutionException e) {
            ActionResult.rethrowIfFatal(e);   // an Error is false; a fatal one goes on out of the run
            logger.log(Level.SEVERE, String.format("setCurrentState: %s", state), e);
            return ActionResult.error(e, getName() + ".setCurrentState");
        }
    }

    /**
     * Does {@code work} on the interpreter, in this actor's turn: directly when this actor's own
     * workflow sent the action ({@link #isOwnStep()}), through the mailbox, waiting for it, when
     * another actor did.
     *
     * <p>An own step runs inside the message that is running the workflow, so a message to this actor
     * would wait for that message to end while that message waits for it.</p>
     *
     * @throws ExecutionException carrying what the work threw
     */
    private void inItsTurn(java.util.function.Consumer<Interpreter> work)
            throws InterruptedException, ExecutionException {
        if (isOwnStep()) {
            try {
                work.accept(this.object);
            } catch (Throwable t) {
                throw new ExecutionException(t);   // the same shape as a failure that came back through the mailbox
            }
            return;
        }
        this.tell(work).get();
    }

    /**
     * Does {@code work} on the interpreter and turns the two wait failures into results: directly
     * when this actor's own workflow sent the action ({@link #isOwnStep()}), on the managed pool
     * through the mailbox when another actor did.
     */
    private ActionResult asked(String actionName, String args,
                               java.util.function.Function<Interpreter, ActionResult> work) {
        try {
            if (isOwnStep()) {
                try {
                    return work.apply(this.object);
                } catch (Throwable t) {
                    throw new ExecutionException(t);
                }
            }
            return this.ask(work, this.system().getManagedThreadPool()).get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.log(Level.SEVERE, String.format("actionName = %s, args = %s", actionName, args), e);
            return ActionResult.error(e, getName() + "." + actionName);
        } catch (ExecutionException e) {
            // The interpreter's work failed. An Error is false like an exception; a fatal one, which
            // the work has already recorded as the last result, goes on out of the run.
            ActionResult.rethrowIfFatal(e);
            logger.log(Level.SEVERE, String.format("actionName = %s, args = %s", actionName, args), e);
            return ActionResult.error(e, getName() + "." + actionName);
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
