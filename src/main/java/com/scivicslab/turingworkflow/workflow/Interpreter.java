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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;

import org.json.JSONArray;
import org.json.JSONObject;

import com.scivicslab.pojoactor.action.ActionResult;
import com.scivicslab.pojoactor.core.ManagedThreadPool;
import com.scivicslab.turingworkflow.workflow.kustomize.WorkflowKustomizer;

import org.yaml.snakeyaml.Yaml;

import org.apache.commons.jexl3.JexlContext;
import org.apache.commons.jexl3.JexlEngine;
import org.apache.commons.jexl3.JexlExpression;
import org.apache.commons.jexl3.MapContext;

/**
 * Workflow interpreter that executes matrix-based workflow definitions.
 *
 * <p>The Interpreter reads workflow definitions in YAML or JSON format and
 * executes them by invoking actions on registered actors. Workflows are
 * represented as a matrix of states and actions, where each row defines
 * state transitions and the actions to execute during those transitions.</p>
 *
 * <p>This class maintains the current execution state, including the current
 * row and state, and coordinates with an {@link IIActorSystem} to invoke
 * actor methods dynamically.</p>
 *
 * @author devteam@scivicslab.com
 */
public class Interpreter {

    protected Logger logger = Logger.getLogger(Interpreter.class.getName());

    protected volatile MatrixCode code = null;

    protected volatile int currentTransitionIndex = 0;

    protected volatile String currentState = "0";

    protected volatile boolean stopRequested = false;

    protected volatile boolean paused = false;

    private final Object pauseLock = new Object();

    private volatile BreakpointListener breakpointListener = null;

    private volatile ActionFailureListener actionFailureListener = null;

    protected IIActorSystem system = null;

    /**
     * Reference to the actor executing this interpreter (for Unix-style path resolution).
     * When this interpreter is running inside an actor (e.g., Node extends Interpreter),
     * this field holds a reference to that actor, enabling relative path resolution
     * like "." (self), ".." (parent), "../sibling" (sibling actors), etc.
     */
    protected IIActorRef<?> selfActorRef = null;

    /**
     * The action being run, {@code actor.method}, while {@link #action()} runs one; null between
     * actions. Names the place of a failure that the attempt's single catch in {@link #execCode()}
     * takes, so the reason keeps the form {@code (in actor.method)}.
     */
    private String runningWhere = null;

    /**
     * The interpreter whose action is being dispatched on this thread, while one is. An actor that runs
     * work for its caller ({@code parallel-map}) reads the caller's base directory from it; it is set
     * on the thread that runs the action, inline or a ManagedThreadPool real thread.
     */
    private static final ThreadLocal<Interpreter> DISPATCHING = new ThreadLocal<>();

    /**
     * The base directory of the interpreter whose action is running on this thread, or null when none
     * is, or it has no base directory set.
     */
    static String baseDirOfDispatchingInterpreter() {
        Interpreter caller = DISPATCHING.get();
        return caller == null ? null : caller.workflowBaseDir;
    }

    /** Calls {@code actionName} on {@code actor} with this interpreter recorded as the one dispatching it. */
    private ActionResult dispatch(IIActorRef<?> actor, String actionName, String args) {
        Interpreter previous = DISPATCHING.get();
        DISPATCHING.set(this);
        try {
            return actor.callByActionName(actionName, args);
        } finally {
            if (previous == null) {
                DISPATCHING.remove();
            } else {
                DISPATCHING.set(previous);
            }
        }
    }

    /**
     * The sub-workflow nesting limit when {@value #MAX_CALL_DEPTH_PROPERTY} is not set. Workflows in
     * use nest {@code this.call} and {@code runWorkflow} two levels deep at most; a recursive
     * {@code this.call} ran out of a real (platform) thread's stack at about 366 levels, and the
     * frames a level takes grow with the actors and hooks it runs, so the limit is far below that.
     */
    public static final int DEFAULT_MAX_CALL_DEPTH = 64;

    /** The system property that sets the sub-workflow nesting limit. */
    public static final String MAX_CALL_DEPTH_PROPERTY = "turingworkflow.maxCallDepth";

    /** How deep this interpreter's run is nested: 0 for a top-level run, its caller's plus one for a sub-workflow. */
    private int callDepth = 0;

    /** How many runWorkflow runs are in progress on this interpreter, each nested in the one before. */
    private int nestedRuns = 0;

    /** The nesting limit now in force: the system property when it is set to a number of at least 1. */
    static int maxCallDepth() {
        Integer configured = Integer.getInteger(MAX_CALL_DEPTH_PROPERTY);
        return configured != null && configured >= 1 ? configured : DEFAULT_MAX_CALL_DEPTH;
    }

    /** How deep a run started from here would be nested. */
    private int depthOfNextRun() {
        return callDepth + nestedRuns + 1;
    }

    /** The false result for a run that would nest deeper than the limit. */
    private static ActionResult depthLimitReached(int depth, String what) {
        int limit = maxCallDepth();
        return new ActionResult(false, "sub-workflow depth limit reached: " + what + " would run at depth "
                + depth + ", the limit is " + limit + " (system property " + MAX_CALL_DEPTH_PROPERTY + ")");
    }

    /**
     * Base directory for resolving relative workflow file paths.
     * When set, runWorkflow will look for files relative to this directory.
     */
    protected String workflowBaseDir = null;


    /**
     * Builder for constructing Interpreter instances.
     *
     * <p>Provides a fluent API for configuring the interpreter with
     * a logger name and actor system.</p>
     */
     public static class Builder {

         IIActorSystem system = null;

         String loggerName = null;

         /**
          * Constructs a new Builder.
          */
         public Builder() {}

         /**
          * Sets the logger name for this interpreter.
          *
          * @param name the logger name
          * @return this builder for method chaining
          */
         public Builder loggerName(String name) {
             loggerName = name;
             return this;
         }

         /**
          * Sets the actor system for this interpreter.
          *
          * @param system the actor system managing the workflow actors
          * @return this builder for method chaining
          */
         public Builder team(IIActorSystem system) {
             this.system = system;
             return this;
         }

         /**
          * Builds and returns a new Interpreter instance.
          *
          * @return a new configured Interpreter
          */
         public Interpreter build() {
             Interpreter obj = new Interpreter();
             obj.logger = Logger.getLogger(loggerName);
             obj.system = this.system;
             return obj;
         }
     }

    /**
     * Executes the actions in the current row of the workflow matrix.
     *
     * <p>For each action in the current row, this method retrieves the
     * corresponding actor(s) from the system and invokes the specified action
     * by name with the provided arguments.</p>
     *
     * <p>Supports Unix-style actor path resolution when {@code selfActorRef} is set:</p>
     * <ul>
     *   <li>{@code .} or {@code this} - self (the current interpreter's actor)</li>
     *   <li>{@code ..} - parent actor</li>
     *   <li>{@code ./*} - all children</li>
     *   <li>{@code ../*} - all siblings</li>
     *   <li>{@code ../sibling} - specific sibling by name</li>
     *   <li>{@code ../web*} - siblings matching wildcard pattern</li>
     * </ul>
     *
     * <p>If {@code selfActorRef} is not set, falls back to absolute actor name lookup.</p>
     *
     * <p>Supports both legacy {@code argument} (String) and new {@code arguments} (List/Map) formats.
     * If {@code arguments} is present, it is converted to JSON array format before passing to the actor.</p>
     *
     * @return an {@link ActionResult} indicating success or failure
     */
    private static final String CLASS_NAME = Interpreter.class.getName();

    public ActionResult action() {
        logger.entering(CLASS_NAME, "action");

        Transition transition = code.getTransitions().get(currentTransitionIndex);
        for (Action a: transition.getActions()) {
            String actorPath = a.getActor();
            String action    = a.getMethod();
            runningWhere = actorPath + "." + action;

            // Convert arguments to JSON format. An expression that cannot be evaluated fails the
            // action here, before the actor is asked to do anything with a null.
            String argumentString;
            try {
                argumentString = convertArgumentsToJson(a);
            } catch (ArgumentExpressionException e) {
                ActionResult badArgument = new ActionResult(false, e.getMessage());
                recordLastResult(badArgument);
                logger.exiting(CLASS_NAME, "action", badArgument);
                return badArgument;
            } catch (Throwable t) {
                // Anything else converting the arguments threw: false, or recorded and rethrown
                // when fatal (evaluateArgumentExpression lets a fatal error through).
                ActionResult badArgument = failedWith(t, actorPath + "." + action + ", arguments");
                logger.exiting(CLASS_NAME, "action", badArgument);
                return badArgument;
            }

            // Resolve actor path using Unix-style notation or wildcard
            List<IIActorRef<?>> actors;
            if (selfActorRef != null) {
                // Use path resolution relative to self
                actors = system.resolveActorPath(selfActorRef.getName(), actorPath);
            } else if (actorPath.contains("*")) {
                // Wildcard pattern without self reference - search all actors
                actors = findMatchingActors(actorPath);
            } else {
                // Fallback: treat as absolute actor name
                IIActorRef<?> actor = system.getIIActor(actorPath);
                actors = actor != null ? Arrays.asList(actor) : new ArrayList<>();
            }

            // Log actor resolution at FINER level
            logger.logp(Level.FINER, CLASS_NAME, "action",
                "actorPath={0}, selfActorRef={1}, actors.size={2}",
                new Object[]{actorPath, selfActorRef != null ? selfActorRef.getName() : "null", actors.size()});
            for (IIActorRef<?> a2 : actors) {
                logger.logp(Level.FINER, CLASS_NAME, "action",
                    "resolved actor: {0} class={1}", new Object[]{a2.getName(), a2.getClass().getName()});
            }

            // Fail if no actors found
            if (actors.isEmpty()) {
                logger.warning("Actor not found: " + actorPath);
                // A built-in value actor removed in 5.0.0 says so, and what to write instead.
                String removed = IIActorSystem.removedValueActorReason(actorPath);
                ActionResult notFoundResult = removed != null
                        ? Removal.failure(removed, actorPath + "." + action)
                        : new ActionResult(false, "Actor not found: " + actorPath);
                recordLastResult(notFoundResult);
                logger.exiting(CLASS_NAME, "action", notFoundResult);
                return notFoundResult;
            }

            // Execute action on all matching actors
            for (IIActorRef<?> actorAR : actors) {
                ActionResult result;

                // Execute based on ExecutionMode
                ExecutionMode executionMode = a.getExecution();
                if (executionMode == null) {
                    executionMode = ExecutionMode.POOL;  // Default to POOL
                }

                // A failed action is false, however it failed: an exception or an Error becomes a
                // failed result whose text names it, and the workflow reads that in jexl: result.
                // A fatal error is recorded the same way and then rethrown (failedWith).
                String where = actorAR.getName() + "." + action;
                try {
                    if (executionMode == ExecutionMode.POOL && system != null
                            && system.getManagedThreadPool() instanceof ManagedThreadPool pool
                            && pool.ownsCurrentThread()) {
                        // POOL, already on one of the pool's real threads: run here. This happens in
                        // a sub-workflow, which this.call runs on the real thread of the calling
                        // action; handing the action to the pool and waiting would deadlock once
                        // every real thread of the pool waits the same way (with one, at once).
                        result = dispatch(actorAR, action, argumentString);
                    } else if (executionMode == ExecutionMode.POOL && system != null) {
                        // POOL: Execute on managed thread pool (safe for heavy operations)
                        result = CompletableFuture.supplyAsync(
                            () -> dispatch(actorAR, action, argumentString),
                            system.getManagedThreadPool()
                        ).get();
                    } else {
                        // DIRECT: Direct synchronous call (for light operations)
                        result = dispatch(actorAR, action, argumentString);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    logger.log(Level.WARNING, "Action interrupted: " + where, e);
                    result = ActionResult.error(e, where);
                } catch (Throwable t) {
                    // DIRECT and inline POOL: what the action threw, an Error included. POOL on the
                    // pool: an ExecutionException carrying it.
                    result = failedWith(t, where);
                    logQuietly(Level.WARNING, "Action failed: " + where, t);
                }

                // Keep the result for the next action to read as jexl: result
                recordLastResult(result);

                // If any action returns false, abort this step
                if (!result.isSuccess()) {
                    logger.exiting(CLASS_NAME, "action", result);
                    return result;
                }
            }
        }
        runningWhere = null;
        ActionResult successResult = new ActionResult(true, "");
        logger.exiting(CLASS_NAME, "action", successResult);
        return successResult;
    }

    /**
     * Keeps an action's result as the last result ({@code jexl: result}), failed or not, so that
     * the next candidate transition can read why the action was false.
     */
    private void recordLastResult(ActionResult result) {
        if (selfActorRef != null) {
            selfActorRef.setLastResult(result);
        }
    }

    /**
     * Returns the failed result for what an action threw, and records it as the last result.
     *
     * <p>Any {@code Throwable} makes the action false, an {@code Error} as much as an exception, so
     * the workflow goes on and its YAML decides. The one exception follows POJO-actor: a fatal error
     * ({@link ActionResult#fatalIn}, a {@code VirtualMachineError} other than
     * {@code StackOverflowError}) is recorded as the last result and then rethrown out of the run,
     * so a failing JVM does not carry on. The result names the fatal error itself when it came
     * wrapped.</p>
     *
     * @param thrown what the action, or the wait for it, threw
     * @param where  where it happened, usually {@code actor.method}
     * @return the failed result, already recorded
     */
    private ActionResult failedWith(Throwable thrown, String where) {
        VirtualMachineError fatal = ActionResult.fatalIn(thrown);
        ActionResult failed = new ActionResult(false, describeFailure(fatal != null ? fatal : thrown, where));
        recordLastResult(failed);
        if (fatal != null) {
            logQuietly(Level.SEVERE, "Fatal error in " + where + "; rethrowing it out of the run", fatal);
            throw fatal;
        }
        return failed;
    }

    /**
     * The failure text {@code error: <class>: <message> (in <where>)}, or as much of it as can be
     * built. Building it can throw too: the message of what was thrown can throw, and with the stack
     * nearly used up any call can. Then the text names the class only, and as a last resort it is
     * {@code error: unknown error}, so describing a failure never becomes a failure of its own. A
     * fatal error while describing goes on out.
     */
    private static String describeFailure(Throwable thrown, String where) {
        try {
            return ActionResult.errorText(thrown, where);
        } catch (Throwable notFull) {
            ActionResult.rethrowIfFatal(notFull);
            try {
                return new StringBuilder("error: ").append(thrown.getClass().getName())
                        .append(" (in ").append(where).append(')').toString();
            } catch (Throwable notEven) {
                ActionResult.rethrowIfFatal(notEven);
                return UNDESCRIBED_FAILURE;
            }
        }
    }

    /** The failure text when not even the class of what was thrown can be named. */
    private static final String UNDESCRIBED_FAILURE = "error: unknown error";

    /**
     * Logs, and swallows anything logging throws except a fatal error: a handler formats the
     * throwable, which calls its message, and a log line must not turn a failure that was already
     * recorded into one that escapes the run.
     */
    private void logQuietly(Level level, String message, Throwable thrown) {
        try {
            logger.log(level, message, thrown);
        } catch (Throwable notLogged) {
            ActionResult.rethrowIfFatal(notLogged);
        }
    }

    /** Marks an argument value as an expression rather than text to substitute into. */
    private static final String JEXL_PREFIX = "jexl:";

    /**
     * Evaluates one argument written as an expression, keeping the type of what it answers.
     *
     * <p>An action's own message is text, so a number read from it arrives as {@code "3"} and an
     * argument declared as a number is refused. An expression reads the workflow's state, or the
     * object an actor wraps, and keeps the type: {@code jexl: state.getInt('i', 0)} arrives as
     * {@code 3}.</p>
     *
     * @param expression the expression, without the {@code jexl:} marker
     * @return whatever it evaluates to; {@code JSONObject.NULL} when it answers nothing
     * @throws ArgumentExpressionException when it cannot be evaluated at all
     */
    private Object evaluateArgumentExpression(String expression) {
        try {
            Object outcome = new WorkflowExpressions(system, selfActorRef, currentState).evaluate(expression);
            return outcome == null ? JSONObject.NULL : jsonValueOf(outcome);
        } catch (ArgumentExpressionException e) {
            throw e;
        } catch (Throwable t) {
            // An Error from a method the expression called fails the argument like an exception;
            // a fatal one, which the expression engine may carry as a cause, is rethrown.
            ActionResult.rethrowIfFatal(t);
            ArgumentExpressionException failed = new ArgumentExpressionException(expression, t);
            logger.warning(failed.getMessage());
            throw failed;
        }
    }

    /**
     * Turns what an expression answered into the value the action's JSON holds.
     *
     * <p>{@code state.select(...)} answers a Jackson node. The JSON handed to the action is built
     * with org.json, which does not know that type and would write the node as its text, so a list
     * read from the workflow's state would arrive as the string {@code "[\"a\",\"b\"]"}. A node is
     * therefore converted to the org.json value of the same shape: a list to a JSON array, an object
     * to a JSON object, text to its text, a number to its number. Any other value is passed as it
     * is.</p>
     *
     * @param outcome what the expression answered; not {@code null}
     * @return the value to put in the action's JSON
     */
    private static Object jsonValueOf(Object outcome) {
        if (outcome instanceof com.fasterxml.jackson.databind.JsonNode node) {
            if (node.isMissingNode() || node.isNull()) {
                return JSONObject.NULL;
            }
            if (node.isTextual()) {
                return node.asText();
            }
            return new org.json.JSONTokener(node.toString()).nextValue();
        }
        return outcome;
    }

    /**
     * An argument the workflow wrote as an expression that could not be evaluated.
     *
     * <p>Carried out of argument conversion so that the action is not run at all. The expression
     * is the workflow's own text, not the actor's work: a mistyped method name is a fault in what
     * was written, and handing the action {@code null} instead let such a workflow run to the end
     * and report success.</p>
     */
    private static class ArgumentExpressionException extends RuntimeException {
        ArgumentExpressionException(String expression, Throwable cause) {
            super("argument expression failed: " + expression + " -- " + describeWithoutContent(cause));
        }

        /**
         * What went wrong, without the text it went wrong on: the class of the innermost cause and,
         * where the cause carries one, the position. The messages of the causes are left out, except
         * a message the engine itself wrote ({@link #thrownByTheEngine}). An
         * expression that reads a file and parses it fails with a message that quotes what it read
         * (Jackson's {@code Unrecognized token 'TOPSECRET'}), and the failure text reaches the
         * workflow's {@code jexl: result} and the log. The expression itself is the workflow's own
         * text and is kept. The cause is not attached for the same reason: a log handler would
         * print its message.
         */
        static String describeWithoutContent(Throwable cause) {
            java.util.Set<Throwable> seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
            Throwable innermost = cause;
            String parsedAt = null;
            String expressionAt = null;
            for (Throwable t = cause; t != null && seen.add(t); t = t.getCause()) {
                innermost = t;
                if (t instanceof com.fasterxml.jackson.core.JsonProcessingException j && j.getLocation() != null) {
                    parsedAt = "at line " + j.getLocation().getLineNr() + ", column " + j.getLocation().getColumnNr();
                }
                if (t instanceof java.util.regex.PatternSyntaxException p && p.getIndex() >= 0) {
                    parsedAt = "at index " + p.getIndex();
                }
                if (expressionAt == null && t instanceof org.apache.commons.jexl3.JexlException je && je.getInfo() != null) {
                    expressionAt = "expression line " + je.getInfo().getLine() + ", column " + je.getInfo().getColumn();
                }
            }
            // The innermost cause is what actually went wrong; the expression engine's own exceptions
            // around it only wrap it. Its message is kept when the interpreter wrote it.
            if (innermost != null && thrownByTheEngine(innermost)
                    && innermost.getMessage() != null && !innermost.getMessage().isBlank()) {
                return innermost.getMessage();
            }
            StringBuilder text = new StringBuilder(innermost == null ? "unknown error" : innermost.getClass().getName());
            if (parsedAt != null) {
                text.append(' ').append(parsedAt);
            }
            if (expressionAt != null) {
                text.append(" (").append(expressionAt).append(')');
            }
            return text.toString();
        }

        /**
         * Whether {@code t} was thrown by the interpreter's own code, whose messages are reasons the
         * engine wrote (a removed actor, a refused lookup) rather than text it read. Plugins and
         * libraries are outside this package, so what they throw is described by class and position.
         */
        private static boolean thrownByTheEngine(Throwable t) {
            StackTraceElement[] trace = t.getStackTrace();
            return trace.length > 0 && trace[0].getClassName().startsWith(ENGINE_PACKAGE);
        }

        /** The package of the interpreter's own classes. */
        private static final String ENGINE_PACKAGE = "com.scivicslab.turingworkflow.workflow.";
    }

    /**
     * Converts an action's arguments, as the workflow wrote them, to the JSON handed to the actor.
     *
     * <p>Each argument written as {@code jexl:} is evaluated first; the shape is then decided by
     * {@link #argumentsToJson(Object)}.</p>
     *
     * @param action the action containing arguments
     * @return JSON string (object for a Map, array otherwise)
     */
    private String convertArgumentsToJson(Action action) {
        Object arguments = action.getArguments();
        Object resolved;
        if (arguments == null) {
            resolved = null;
        } else if (arguments instanceof List<?> list) {
            List<Object> values = new ArrayList<>();
            for (Object item : list) {
                values.add(argumentValue(item));
            }
            resolved = values;
        } else if (arguments instanceof Map<?, ?> map) {
            Map<String, Object> values = new java.util.LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                values.put(entry.getKey().toString(), argumentValue(entry.getValue()));
            }
            resolved = values;
        } else {
            // A single value stays one element even when its expression answers a map or a list.
            List<Object> values = new ArrayList<>();
            values.add(argumentValue(arguments));
            resolved = values;
        }
        return argumentsToJson(resolved);
    }

    /**
     * Shapes the arguments of one action call into the JSON string the actor receives.
     *
     * <p>Both a step's own action and each child call made by {@link #apply(String)} pass their
     * arguments through here, so an action receives the same JSON however it is reached:</p>
     * <ul>
     *   <li>a Map becomes a JSON object (e.g. {@code {"command":"hostname"}}), which an action
     *       declaring named arguments ({@code argsType}) requires</li>
     *   <li>a List becomes a JSON array (e.g. {@code ["a","b"]})</li>
     *   <li>null becomes the empty array {@code []}</li>
     *   <li>any single value (a String, a number) becomes a one-element array
     *       (e.g. {@code "value"} becomes {@code ["value"]})</li>
     * </ul>
     *
     * @param arguments the arguments, with expressions already evaluated
     * @return JSON string (object for a Map, array otherwise)
     */
    public static String argumentsToJson(Object arguments) {
        if (arguments == null) {
            return "[]";
        }
        if (arguments instanceof Map<?, ?> map) {
            JSONObject jsonObject = new JSONObject();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                jsonObject.put(entry.getKey().toString(), entry.getValue());
            }
            return jsonObject.toString();
        }
        JSONArray jsonArray = new JSONArray();
        if (arguments instanceof List<?> list) {
            for (Object item : list) {
                jsonArray.put(item);
            }
        } else {
            jsonArray.put(arguments);
        }
        return jsonArray.toString();
    }

    /**
     * Turns one argument as the workflow wrote it into the value that reaches the action.
     *
     * <p>Text beginning with {@code jexl:} is evaluated as an expression and reaches the action
     * with the type of what it answers. Everything else reaches the action as written.</p>
     *
     * @param written the argument as the workflow wrote it
     * @return the value to place in the JSON handed to the action
     */
    private Object argumentValue(Object written) {
        if (written instanceof String text && text.startsWith(JEXL_PREFIX)) {
            return evaluateArgumentExpression(text.substring(JEXL_PREFIX.length()));
        }
        return written;
    }

    
    /**
     * Returns the currently loaded workflow code.
     *
     * @return the {@link MatrixCode} representing the workflow
     */
    public MatrixCode getCode() {
        return this.code;
    }

    /**
     * Sets the workflow code directly (for testing purposes).
     *
     * @param code the workflow code to set
     */
    public void setCode(MatrixCode code) {
        this.code = code;
    }

    /**
     * Reads and parses a workflow definition from a YAML input stream.
     *
     * <p>This method uses a reflection-free approach to parse YAML,
     * making it compatible with GraalVM Native Image.</p>
     *
     * @param yamlInput the YAML input stream containing the workflow definition
     */
    @SuppressWarnings("unchecked")
    public void readYaml(InputStream yamlInput) {
        Yaml yaml = new Yaml();
        Map<String, Object> data = yaml.load(yamlInput);
        code = mapToMatrixCode(data);
    }

    /**
     * Reads and parses a workflow definition from a YAML file.
     *
     * @param yamlPath the path to the YAML file containing the workflow definition
     * @throws IOException if the file cannot be read
     */
    public void readYaml(Path yamlPath) throws IOException {
        try (InputStream is = Files.newInputStream(yamlPath)) {
            readYaml(is);
        }
        // Set the workflow base directory for relative path resolution
        if (yamlPath.getParent() != null) {
            this.workflowBaseDir = yamlPath.getParent().toString();
        }
    }

    /**
     * Reads and parses a workflow definition from a YAML file with overlay applied.
     *
     * <p>This method applies the overlay configuration from the specified directory
     * to the base workflow before loading. The overlay can modify vertices, add new
     * steps, substitute variables, and apply name transformations.</p>
     *
     * <p>Example usage:</p>
     * <pre>
     * interpreter.readYaml(
     *     Path.of("workflows/base/main-workflow.yaml"),
     *     Path.of("workflows/overlays/production")
     * );
     * </pre>
     *
     * @param yamlPath the path to the base YAML workflow file
     * @param overlayDir the directory containing overlay-conf.yaml
     * @throws IOException if files cannot be read
     * @since 2.9.0
     */
    public void readYaml(Path yamlPath, Path overlayDir) throws IOException {
        WorkflowKustomizer kustomizer = new WorkflowKustomizer();
        String workflowFileName = yamlPath.getFileName().toString();

        // Build the overlay and get the specific workflow
        Map<String, Map<String, Object>> workflows = kustomizer.build(overlayDir);

        // Debug: log available workflow keys
        if (logger != null && logger.isLoggable(java.util.logging.Level.FINE)) {
            logger.fine("readYaml: looking for '" + workflowFileName + "'");
            logger.fine("readYaml: available workflows: " + workflows.keySet());
        }

        Map<String, Object> workflowData = WorkflowKustomizer.findWorkflow(workflows, workflowFileName);

        if (workflowData == null) {
            throw new IOException("Workflow not found after overlay: " + workflowFileName);
        }

        // Convert the merged data to MatrixCode
        code = mapToMatrixCode(workflowData);

        // Set the workflow base directory
        if (yamlPath.getParent() != null) {
            this.workflowBaseDir = yamlPath.getParent().toString();
        }
    }

    /**
     * Converts a Map representation to MatrixCode.
     * Used when loading workflows with overlay applied.
     */
    @SuppressWarnings("unchecked")
    private MatrixCode mapToMatrixCode(Map<String, Object> data) {
        MatrixCode mc = new MatrixCode();
        mc.setName((String) data.get("name"));

        // The transitions: key was removed in 5.0.0: refuse it rather than load an empty workflow.
        Removal.refuseTransitionsKey(data);
        List<Map<String, Object>> stepsData = (List<Map<String, Object>>) data.get("steps");
        if (stepsData != null) {
            List<Transition> vertices = new ArrayList<>();
            for (Map<String, Object> stepData : stepsData) {
                Transition transition = new Transition();
                transition.setStates((List<String>) stepData.get("states"));
                transition.setLabel((String) stepData.get("label"));
                transition.setNote((String) stepData.get("note"));

                List<Map<String, Object>> actionsData = (List<Map<String, Object>>) stepData.get("actions");
                if (actionsData != null) {
                    List<Action> actions = new ArrayList<>();
                    for (Map<String, Object> actionData : actionsData) {
                        // Only arguments: is read; refuse argument: rather than call with no arguments.
                        Removal.refuseArgumentKey(actionData);
                        Action action = new Action();
                        action.setActor((String) actionData.get("actor"));
                        action.setMethod((String) actionData.get("method"));
                        action.setArguments(actionData.get("arguments"));
                        Object executionValue = actionData.get("execution");
                        if (executionValue != null) {
                            action.setExecution(ExecutionMode.fromString(executionValue.toString()));
                        }
                        actions.add(action);
                    }
                    transition.setActions(actions);
                }

                // Parse delay
                Object delayVal = stepData.get("delay");
                if (delayVal instanceof Number) {
                    transition.setDelay(((Number) delayVal).longValue());
                }

                // Parse breakpoint
                Object bpVal = stepData.get("breakpoint");
                if (bpVal instanceof Boolean && (Boolean) bpVal) {
                    transition.setBreakpoint(true);
                }

                vertices.add(transition);
            }
            mc.setSteps(vertices);
        }

        return mc;
    }

    /**
     * Executes the loaded workflow code.
     *
     * <p>This method implements finite automaton semantics:</p>
     * <ol>
     *   <li>Find a step whose from-state matches the current state</li>
     *   <li>Execute the actions in that step</li>
     *   <li>If any action returns false, skip to the next step and retry</li>
     *   <li>If all actions succeed, transition to the to-state</li>
     * </ol>
     *
     * <p>This enables conditional branching: multiple steps can have the same
     * from-state, and the first one whose actions all succeed will be taken.</p>
     *
     * @return an {@link ActionResult} indicating success or failure
     */
    public ActionResult execCode() {
        logger.entering(CLASS_NAME, "execCode");

        if (stopRequested) {
            ActionResult stopResult = new ActionResult(false, "Stopped by request");
            logger.exiting(CLASS_NAME, "execCode", stopResult);
            return stopResult;
        }

        if (!hasCodeLoaded()) {
            ActionResult noCodeResult = new ActionResult(false, "No code loaded");
            logger.exiting(CLASS_NAME, "execCode", noCodeResult);
            return noCodeResult;
        }

        // Try all steps, starting from currentTransitionIndex and wrapping around
        ActionResult lastFailure = null;
        int stepsCount = code.getTransitions().size();
        for (int attempts = 0; attempts < stepsCount; attempts++) {
            // Wrap around to the beginning when reaching the end
            if (currentTransitionIndex >= stepsCount) {
                currentTransitionIndex = 0;
            }

            Transition transition = code.getTransitions().get(currentTransitionIndex);

            // Check if from-state matches current state
            if (matchesCurrentState(transition)) {
                // One catch for everything from this step being picked to its result being
                // recorded: the wait at a breakpoint or a delay, the hook before it, the actions
                // (arguments, dispatch, the result and its recording), the hook after it and the
                // bookkeeping of a failure. A non-fatal Throwable anywhere in it makes this attempt
                // false, as an action's own failure does, and the next step whose from-pattern
                // matches is tried; a fatal one is recorded and rethrown (failedWith).
                runningWhere = null;
                try {
                    // Handle breakpoint: pause before executing
                    if (transition.isBreakpoint()) {
                        paused = true;
                        if (breakpointListener != null) {
                            breakpointListener.onBreakpoint(transition, currentState);
                        }
                        synchronized (pauseLock) {
                            while (paused && !stopRequested) {
                                try {
                                    pauseLock.wait();
                                } catch (InterruptedException e) {
                                    Thread.currentThread().interrupt();
                                    return ActionResult.error(e, "waiting at a breakpoint in state '" + currentState + "'");
                                }
                            }
                        }
                        if (stopRequested) {
                            return new ActionResult(false, "Stopped at breakpoint");
                        }
                    }

                    // Handle delay: wait before executing
                    if (transition.getDelay() > 0) {
                        try {
                            Thread.sleep(transition.getDelay());
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            return ActionResult.error(e, "the delay before a transition from '" + currentState + "'");
                        }
                    }

                    // Hook: notify subclasses that we're entering this transition
                    onEnterTransition(transition);

                    // Try to execute actions
                    ActionResult actionResult = action();
                    // A failure from here on is the transition's, not the last action's.
                    runningWhere = null;

                    if (actionResult.isSuccess()) {
                        // Hook: notify subclasses that transition succeeded
                        onExitTransition(transition, true, actionResult);
                        // All actions succeeded, transition to to-state
                        transitionTo(getToState(transition));
                        ActionResult stateResult = new ActionResult(true, "State: " + currentState);
                        logger.exiting(CLASS_NAME, "execCode", stateResult);
                        return stateResult;
                    }
                    lastFailure = actionResult;
                    // Hook: notify subclasses that transition failed
                    onExitTransition(transition, false, actionResult);
                    logger.finest("Action failed at state '" + currentState + "' transition " + transition.getStates() + ": " + actionResult.getResult());
                    if (actionFailureListener != null) {
                        actionFailureListener.onActionFailure(transition, currentState, actionResult);
                    }
                } catch (Throwable t) {
                    String place = runningWhere != null ? runningWhere : "transition " + transition.getStates();
                    runningWhere = null;
                    lastFailure = failedWith(t, place);
                    logQuietly(Level.WARNING, "Attempt failed: " + place, t);
                }
                // Action failed, try next step
            }
            currentTransitionIndex++;
        }

        // Say where the workflow stopped and, when an action failed on the way, why: the last
        // failure's reason would otherwise be lost to this message.
        String noMatch = "No matching state transition from '" + currentState + "'";
        if (lastFailure != null) {
            noMatch += "; last failed action: " + lastFailure.getResult();
        }
        ActionResult noMatchResult = new ActionResult(false, noMatch);
        logger.exiting(CLASS_NAME, "execCode", noMatchResult);
        return noMatchResult;
    }

    /**
     * Executes the workflow until reaching the "end" state.
     *
     * <p>This method provides a self-contained way to run workflows that have
     * a defined termination state. The workflow author defines transitions to
     * the "end" state in the YAML file, and this method handles the execution
     * loop automatically.</p>
     *
     * <p>The method repeatedly calls {@link #execCode()} until:</p>
     * <ul>
     *   <li>The current state becomes "end" (success)</li>
     *   <li>An action returns failure (error)</li>
     *   <li>No matching state transition is found (error)</li>
     *   <li>Maximum iterations exceeded (error - prevents infinite loops)</li>
     * </ul>
     *
     * <p><strong>Usage Example:</strong></p>
     * <pre>{@code
     * // YAML workflow with "end" state:
     * // steps:
     * //   - states: ["0", "1"]
     * //     actions:
     * //       - actor: worker
     * //         method: process
     * //   - states: ["1", "end"]
     * //     actions:
     * //       - actor: worker
     * //         method: finish
     *
     * Interpreter interpreter = new Interpreter.Builder()
     *     .loggerName("workflow")
     *     .team(system)
     *     .build();
     *
     * interpreter.readYaml(workflowStream);
     * ActionResult result = interpreter.runUntilEnd();
     *
     * if (result.isSuccess()) {
     *     System.out.println("Workflow completed successfully");
     * } else {
     *     System.out.println("Workflow failed: " + result.getResult());
     * }
     * }</pre>
     *
     * @return an {@link ActionResult} with success=true if "end" state reached,
     *         or success=false with error message if workflow failed
     * @see #runUntilEnd(int) for specifying custom maximum iterations
     * @since 2.8.0
     */
    public ActionResult runUntilEnd() {
        return runUntilEnd(10000);  // Default max iterations
    }

    /**
     * Loads and runs a workflow file to completion.
     *
     * <p>This is a convenience method that combines loading a workflow and
     * running it until the "end" state is reached. The workflow file is
     * reloaded each time this method is called, ensuring fresh state.</p>
     *
     * <p>The method:</p>
     * <ol>
     *   <li>Resets the interpreter state</li>
     *   <li>Loads the workflow from classpath or file system</li>
     *   <li>Runs until "end" state is reached</li>
     * </ol>
     *
     * @param workflowFile the workflow file path (YAML or JSON)
     * @return ActionResult with success=true if completed, false otherwise
     * @since 2.8.0
     */
    public ActionResult runWorkflow(String workflowFile) {
        return runWorkflow(workflowFile, 10000);
    }

    /**
     * Loads and runs a workflow file to completion with custom iteration limit.
     *
     * @param workflowFile the workflow file path (YAML or JSON)
     * @param maxIterations maximum number of state transitions allowed
     * @return ActionResult with success=true if completed, false otherwise
     * @since 2.8.0
     */
    public ActionResult runWorkflow(String workflowFile, int maxIterations) {
        int depth = depthOfNextRun();
        if (depth > maxCallDepth()) {
            return depthLimitReached(depth, "runWorkflow " + workflowFile);
        }
        nestedRuns++;
        try {
            // Reset state for fresh execution
            reset();

            // Load workflow from classpath, or else from a file under the base directory: a path
            // that climbs out of it, is absolute elsewhere or is a link to elsewhere is refused.
            InputStream stream = loadWorkflowFromClasspath(workflowFile);
            if (stream == null) {
                stream = java.nio.file.Files.newInputStream(PathUnderBase.resolve(workflowBaseDir, workflowFile));
            }

            readYaml(stream);

            // Run until end
            return runUntilEnd(maxIterations);

        } catch (Throwable t) {
            // An Error is false like an exception; a fatal one goes on out of the run.
            ActionResult.rethrowIfFatal(t);
            logger.log(Level.SEVERE, "Error running workflow: " + workflowFile, t);
            return ActionResult.error(t, "runWorkflow " + workflowFile);
        } finally {
            nestedRuns--;
        }
    }

    /**
     * Executes the workflow until reaching the "end" state with a custom iteration limit.
     *
     * <p>This method runs the workflow with the following termination conditions:</p>
     * <ul>
     *   <li>Success: current state becomes "end" (automaton accepted)</li>
     *   <li>Failure: an action fails and no alternative row matches the current state</li>
     *   <li>Failure: maxIterations exceeded without reaching "end" (automaton did not accept)</li>
     * </ul>
     *
     * @param maxIterations maximum number of state transitions allowed
     * @return an {@link ActionResult} with success=true if "end" state reached,
     *         or success=false with error message if workflow failed or iterations exceeded
     * @see #runUntilEnd() for default iteration limit
     * @since 2.8.0
     */
    public ActionResult runUntilEnd(int maxIterations) {
        if (!hasCodeLoaded()) {
            return new ActionResult(false, "No code loaded");
        }

        for (int iteration = 0; iteration < maxIterations && !stopRequested; iteration++) {
            // Check for end state before executing
            if ("end".equals(currentState)) {
                return new ActionResult(true, "Workflow completed");
            }

            ActionResult result = execCode();

            if (!result.isSuccess()) {
                return new ActionResult(false, "Workflow failed at iteration " + iteration + ": " + result.getResult());
            }
        }

        return new ActionResult(false, "Maximum iterations (" + maxIterations + ") exceeded");
    }

    /**
     * Checks if workflow code is loaded.
     *
     * @return true if code is loaded and has at least one step
     */
    public boolean hasCodeLoaded() {
        return code != null && !code.getTransitions().isEmpty();
    }

    /**
     * Checks if a transition's from-state matches the current state.
     *
     * <p>A transition matches if it has at least 2 states (from and to) and
     * the from-state pattern (index 0) matches the interpreter's current state.</p>
     *
     * <p>Supported state patterns:</p>
     * <ul>
     *   <li>Exact match: {@code "1"} - matches only state "1"</li>
     *   <li>Wildcard: {@code "*"} - matches any state</li>
     *   <li>Negation: {@code "!end"} - matches any state except "end"</li>
     *   <li>OR condition: {@code "1|2|3"} - matches "1", "2", or "3"</li>
     *   <li>Numeric comparison: {@code ">=1"}, {@code "<=5"}, {@code ">1"}, {@code "<5"}</li>
     * </ul>
     *
     * @param transition the transition to check
     * @return true if the transition's from-state pattern matches current state
     */
    public boolean matchesCurrentState(Transition transition) {
        List<String> states = transition.getStates();
        if (states.size() < 2) {
            return false;
        }
        return matchesStatePattern(states.get(0), currentState);
    }

    /**
     * Checks if a state pattern matches a given state value.
     *
     * <p>Supported patterns:</p>
     * <ul>
     *   <li>Exact match: "1" matches only "1"</li>
     *   <li>Wildcard: "*" matches any state</li>
     *   <li>Negation: "!end" matches anything except "end"</li>
     *   <li>OR condition: "1|2|3" matches "1", "2", or "3"</li>
     *   <li>Numeric comparison: ">=1", "&lt;5" etc.</li>
     *   <li>JEXL expression: "jexl:currentState == 'error'", or "jexl:n &gt;= 5 &amp;&amp; n &lt; 10"
     *       on a numeric state. The state the workflow is in is {@code currentState}, the same name
     *       an action's argument expression calls it by; {@code state} is an action argument's
     *       name for the workflow's stored values and means nothing here</li>
     * </ul>
     *
     * @param pattern the state pattern (may contain wildcards, operators, etc.)
     * @param state the actual state value to match against
     * @return true if the pattern matches the state
     */
    protected boolean matchesStatePattern(String pattern, String state) {
        if (pattern == null || state == null) {
            return false;
        }

        // JEXL expression: starts with "jexl:"
        if (pattern.startsWith("jexl:")) {
            return matchesJexlExpression(pattern.substring(5), state);
        }

        // Wildcard: "*" matches any state
        if ("*".equals(pattern)) {
            return true;
        }

        // Negation: "!xxx" matches any state except "xxx"
        if (pattern.startsWith("!")) {
            String negated = pattern.substring(1);
            return !state.equals(negated);
        }

        // OR condition: "a|b|c" matches "a", "b", or "c"
        if (pattern.contains("|")) {
            String[] options = pattern.split("\\|");
            for (String option : options) {
                if (option.trim().equals(state)) {
                    return true;
                }
            }
            return false;
        }

        // Numeric comparisons: ">=1", "<=5", ">1", "<5"
        if (pattern.startsWith(">=") || pattern.startsWith("<=") ||
            pattern.startsWith(">") || pattern.startsWith("<")) {
            return matchesNumericComparison(pattern, state);
        }

        // Default: exact match
        return pattern.equals(state);
    }

    /**
     * The evaluator for the expressions in {@code states}: {@link WorkflowExpressions#statesEngine()},
     * which says what a pattern may reach and why it raises rather than answering nothing.
     */
    private static JexlEngine getJexlEngine() {
        return WorkflowExpressions.statesEngine();
    }

    /**
     * Evaluates a JEXL expression against the current state.
     *
     * <p>The expression has access to the following variables:</p>
     * <ul>
     *   <li>{@code currentState} - the state the workflow is in, as a string. An action's
     *       argument expression calls it by the same name; {@code state} there is the workflow's
     *       stored values, which a pattern cannot reach</li>
     *   <li>{@code n} - the state parsed as a number (or null if not numeric)</li>
     * </ul>
     *
     * <p>Example expressions:</p>
     * <ul>
     *   <li>{@code state == 'error'}</li>
     *   <li>{@code n >= 5 && n < 10}</li>
     *   <li>{@code state =~ 'error.*'}</li>
     *   <li>{@code state.startsWith('err')}</li>
     * </ul>
     *
     * @param expression the JEXL expression to evaluate
     * @param state the current state value
     * @return true if the expression evaluates to true
     */
    private boolean matchesJexlExpression(String expression, String state) {
        try {
            JexlEngine engine = getJexlEngine();
            JexlExpression jexlExpr = engine.createExpression(expression);

            JexlContext context = new MapContext();
            context.set(WorkflowExpressions.CURRENT_STATE_NAME, state);

            // Try to parse state as a number
            try {
                double numericState = Double.parseDouble(state);
                context.set("n", numericState);
            } catch (NumberFormatException e) {
                context.set("n", null);
            }

            Object result = jexlExpr.evaluate(context);

            if (result instanceof Boolean) {
                return (Boolean) result;
            }
            // For non-boolean results, treat non-null as true
            return result != null;

        } catch (Exception e) {
            logger.log(Level.WARNING, "JEXL expression evaluation failed: " + expression, e);
            return false;
        }
    }

    /**
     * Handles numeric comparison patterns.
     *
     * @param pattern comparison pattern like ">=1", "<=5", ">1", "<5"
     * @param state the state value to compare
     * @return true if the comparison is satisfied
     */
    private boolean matchesNumericComparison(String pattern, String state) {
        try {
            String operator;
            String valueStr;

            if (pattern.startsWith(">=")) {
                operator = ">=";
                valueStr = pattern.substring(2);
            } else if (pattern.startsWith("<=")) {
                operator = "<=";
                valueStr = pattern.substring(2);
            } else if (pattern.startsWith(">")) {
                operator = ">";
                valueStr = pattern.substring(1);
            } else if (pattern.startsWith("<")) {
                operator = "<";
                valueStr = pattern.substring(1);
            } else {
                return false;
            }

            // Try to parse both as numbers
            double patternValue = Double.parseDouble(valueStr.trim());
            double stateValue = Double.parseDouble(state);

            switch (operator) {
                case ">=": return stateValue >= patternValue;
                case "<=": return stateValue <= patternValue;
                case ">":  return stateValue > patternValue;
                case "<":  return stateValue < patternValue;
                default:   return false;
            }
        } catch (NumberFormatException e) {
            // If parsing fails, fall back to string comparison
            return false;
        }
    }

    /**
     * Gets the to-state from a transition.
     *
     * @param transition the transition containing the state transition
     * @return the to-state (second element), or null if not present
     */
    public String getToState(Transition transition) {
        List<String> states = transition.getStates();
        if (states.size() < 2) return null;
        String to = states.get(1);
        if (to != null && to.startsWith("jexl:")) {
            to = evaluateToStateJexl(to.substring(5), currentState);
        }
        return to;
    }

    private String evaluateToStateJexl(String expression, String state) {
        try {
            JexlEngine engine = getJexlEngine();
            JexlContext context = new MapContext();
            context.set(WorkflowExpressions.CURRENT_STATE_NAME, state);
            try {
                double n = Double.parseDouble(state);
                context.set("n", n);
            } catch (NumberFormatException e) {
                context.set("n", null);
            }
            Object result = engine.createExpression(expression).evaluate(context);
            if (result == null) return state;
            // Return integer string when result is whole number
            if (result instanceof Number num) {
                double v = num.doubleValue();
                if (v == Math.floor(v) && !Double.isInfinite(v)) {
                    return String.valueOf((long) v);
                }
                return String.valueOf(v);
            }
            return String.valueOf(result);
        } catch (Exception e) {
            logger.log(Level.WARNING, "JEXL to-state evaluation failed: " + expression, e);
            return state;
        }
    }

    /**
     * Transitions to a new state and finds the next matching row.
     *
     * <p>This method:</p>
     * <ol>
     *   <li>Updates the current state to the specified to-state</li>
     *   <li>Searches for the first step whose from-state matches the new current state</li>
     *   <li>Updates currentTransitionIndex to point to that step</li>
     * </ol>
     *
     * @param toState the state to transition to
     */
    public void transitionTo(String toState) {
        setCurrentState(toState);
        findNextMatchingTransition();
    }

    /**
     * Finds the first step whose from-state pattern matches the current state.
     *
     * <p>Always searches from index 0 (top of the transition list), consistent
     * with the documented semantics: "the engine scans the transition list from
     * top to bottom." This ensures that catch-all transitions such as
     * {@code ["!end", "end"]} do not shadow loop-entry transitions after a
     * loop-body transition returns to the loop state.</p>
     */
    protected void findNextMatchingTransition() {
        int stepsCount = code.getTransitions().size();
        for (int i = 0; i < stepsCount; i++) {
            Transition nextTransition = code.getTransitions().get(i);
            if (!nextTransition.getStates().isEmpty() &&
                matchesStatePattern(nextTransition.getStates().get(0), currentState)) {
                currentTransitionIndex = i;
                return;
            }
        }
    }

    /**
     * Sets the current state directly without repositioning the transition cursor.
     *
     * <p>Unlike {@link #transitionTo(String)}, this method only assigns
     * {@code currentState} and does not call {@link #findNextMatchingTransition()}.
     * The next {@link #execCode()} call will resume scanning from
     * {@code currentTransitionIndex} and match steps against the new state.</p>
     *
     * @param state the state value to assign to currentState
     */
    public void setCurrentState(String state) {
        currentState = state;
    }

    /**
     * Gets the current state of the interpreter.
     *
     * @return the current state string
     */
    public String getCurrentState() {
        return currentState;
    }

    /**
     * Gets the current step index.
     *
     * @return the current step index
     */
    public int getCurrentTransitionIndex() {
        return currentTransitionIndex;
    }

    /**
     * Resets the interpreter to its initial state.
     *
     * <p>This method resets the execution state of the interpreter, allowing it
     * to be reused for executing a new workflow without creating a new instance.
     * The following state is reset:</p>
     * <ul>
     *   <li>Current row index is reset to 0</li>
     *   <li>Current state is reset to "0" (initial state)</li>
     *   <li>Loaded workflow code is cleared</li>
     * </ul>
     *
     * <p>The following state is preserved:</p>
     * <ul>
     *   <li>Logger instance</li>
     *   <li>Actor system reference</li>
     * </ul>
     *
     * <p>This method is primarily used by {@link ReusableSubWorkflowCaller}
     * to reuse a single Interpreter instance across multiple sub-workflow calls,
     * reducing object allocation overhead.</p>
     *
     * <p><strong>Usage Example:</strong></p>
     * <pre>{@code
     * Interpreter interpreter = new Interpreter.Builder()
     *     .loggerName("workflow")
     *     .team(system)
     *     .build();
     *
     * // First workflow execution
     * interpreter.readYaml(workflow1);
     * while (true) {
     *     ActionResult result = interpreter.execCode();
     *     if (result.getResult().contains("end")) break;
     * }
     *
     * // Reset and reuse for second workflow
     * interpreter.reset();
     * interpreter.readYaml(workflow2);
     * while (true) {
     *     ActionResult result = interpreter.execCode();
     *     if (result.getResult().contains("end")) break;
     * }
     * }</pre>
     *
     * @see ReusableSubWorkflowCaller
     * @since 2.5.0
     */
    public void reset() {
        this.currentTransitionIndex = 0;
        this.currentState = "0";
        this.code = null;
        // Under the lock requestStop takes, so a stop and a reset are ordered one after the other.
        synchronized (pauseLock) {
            this.stopRequested = false;
        }
    }

    /**
     * Requests the interpreter to stop execution as soon as possible.
     * This method is thread-safe and can be called from any thread,
     * including via {@code tellNow()} to bypass the actor message queue.
     *
     * @since 2.14.0
     */
    public void requestStop() {
        // A run paused at a breakpoint waits on pauseLock; wake it so it sees the stop.
        synchronized (pauseLock) {
            this.stopRequested = true;
            pauseLock.notifyAll();
        }
    }

    /**
     * Clears the stop request flag, allowing execution to proceed again.
     *
     * @since 2.14.0
     */
    public void clearStop() {
        this.stopRequested = false;
    }

    /**
     * Returns whether a stop has been requested.
     *
     * @return true if stop has been requested
     * @since 2.14.0
     */
    public boolean isStopRequested() {
        return this.stopRequested;
    }

    /**
     * Hook method called when entering a transition during workflow execution.
     *
     * <p>This method is called just before executing the actions of a matching transition.
     * Subclasses can override this method to provide custom behavior such as
     * logging, visualization, or debugging output.</p>
     *
     * <p>The default implementation does nothing.</p>
     *
     * @param transition the transition being entered
     * @since 2.9.0
     */
    protected void onEnterTransition(Transition transition) {
        // Default: do nothing. Subclasses can override.
    }

    /**
     * Hook method called after a transition completes (success or failure).
     *
     * <p>Subclasses can override this method to provide custom behavior such as
     * logging transition results, timing, or debugging output.</p>
     *
     * <p>The default implementation does nothing.</p>
     *
     * @param transition the transition that was attempted
     * @param success true if the transition succeeded, false if it failed
     * @param result the ActionResult from executing the transition's actions
     * @since 2.12.2
     */
    protected void onExitTransition(Transition transition, boolean success, ActionResult result) {
        // Default: do nothing. Subclasses can override.
    }

    @FunctionalInterface
    public interface BreakpointListener {
        void onBreakpoint(Transition transition, String currentState);
    }

    public void setBreakpointListener(BreakpointListener listener) {
        this.breakpointListener = listener;
    }

    @FunctionalInterface
    public interface ActionFailureListener {
        void onActionFailure(Transition transition, String currentState, ActionResult result);
    }

    public void setActionFailureListener(ActionFailureListener listener) {
        this.actionFailureListener = listener;
    }

    public void resume() {
        synchronized (pauseLock) {
            paused = false;
            pauseLock.notifyAll();
        }
    }

    public boolean isPaused() {
        return paused;
    }

    /**
     * Sets the reference to the actor executing this interpreter.
     *
     * <p>This method is called by IIActorRef implementations to establish the
     * link between the interpreter and its actor wrapper, enabling Unix-style
     * path resolution within workflows.</p>
     *
     * @param actorRef the actor reference wrapping this interpreter
     */
    public void setSelfActorRef(IIActorRef<?> actorRef) {
        this.selfActorRef = actorRef;
    }

    /**
     * Sets the base directory for resolving relative workflow file paths.
     *
     * @param baseDir the directory path
     */
    public void setWorkflowBaseDir(String baseDir) {
        this.workflowBaseDir = baseDir;
    }

    /**
     * Gets the base directory for resolving relative workflow file paths.
     *
     * @return the directory path, or null if not set
     */
    public String getWorkflowBaseDir() {
        return this.workflowBaseDir;
    }

    // ========================================================================
    // Subworkflow Support
    // ========================================================================

    /**
     * Generates a unique child actor name for subworkflow execution.
     *
     * <p>The name format is: {@code subwf-{workflowName}-{timestamp}-{random}}</p>
     * <p>Example: {@code subwf-user-validation-1735812345678-04821}</p>
     *
     * <p>The combination of timestamp and random number ensures collision-free
     * naming even under parallel invocation or nested subworkflows.</p>
     *
     * @param workflowFile the workflow file name (e.g., "user-validation.yaml")
     * @return a unique child actor name
     * @since 2.9.0
     */
    protected String generateChildName(String workflowFile) {
        String baseName = workflowFile.replace(".yaml", "").replace(".json", "");
        return "subwf-" + baseName + "-" + CHILD_NUMBERS.incrementAndGet();
    }

    /**
     * The numbers sub-workflow children are named with, one counter for the whole JVM: two calls,
     * even from two interpreters that share an actor system, never draw the same name.
     */
    private static final java.util.concurrent.atomic.AtomicLong CHILD_NUMBERS = new java.util.concurrent.atomic.AtomicLong();

    /**
     * Calls a subworkflow by creating a child interpreter, executing it, and removing it.
     *
     * <p>This method implements the 4-step subworkflow pattern:</p>
     * <ol>
     *   <li><strong>createChild</strong> — Create a child InterpreterIIAR and register it</li>
     *   <li><strong>loadWorkflow</strong> — Load the YAML workflow into the child interpreter</li>
     *   <li><strong>runUntilEnd</strong> — Execute the subworkflow until "end" state</li>
     *   <li><strong>removeChild</strong> — Remove the child actor (always executed via finally)</li>
     * </ol>
     *
     * <p>The child interpreter shares the same {@link IIActorSystem} as the parent,
     * so all registered actors are accessible from the subworkflow.</p>
     *
     * <p><strong>Usage in YAML:</strong></p>
     * <pre>{@code
     * - states: ["0", "1"]
     *   actions:
     *     - actor: this
     *       method: call
     *       arguments: ["sub-workflow.yaml"]
     * }</pre>
     *
     * @param workflowFile the workflow file name to execute
     * @return ActionResult indicating success or failure
     * @since 2.9.0
     */
    public ActionResult call(String workflowFile) {
        if (system == null) {
            return new ActionResult(false, "No actor system configured");
        }

        int childDepth = depthOfNextRun();
        if (childDepth > maxCallDepth()) {
            return depthLimitReached(childDepth, "call " + workflowFile);
        }

        String childName = generateChildName(workflowFile);
        // The child this call registered, if it got that far: only that one is removed at the end,
        // so a call whose name was taken cannot close another call's child under the same name.
        InterpreterIIAR registered = null;

        try {
            // === Step 1: createChild (親と同じクラスの子アクターを作成) ===
            // まずInterpreterを作成
            Interpreter childInterpreter = new Interpreter.Builder()
                .loggerName(childName)
                .team(system)
                .build();

            // InterpreterIIARでラップ（親と同じクラス）
            InterpreterIIAR childActor = new InterpreterIIAR(childName, childInterpreter, system);
            childInterpreter.setSelfActorRef(childActor);
            childInterpreter.callDepth = childDepth;

            // 親子関係を設定
            if (selfActorRef != null) {
                childActor.setParentName(selfActorRef.getName());
                selfActorRef.getNamesOfChildren().add(childName);
            }
            system.addIIActor(childActor);
            registered = childActor;

            // === Step 2: loadWorkflow ===
            InputStream yamlStream = loadWorkflowFromClasspath(workflowFile);
            if (yamlStream == null) {
                return new ActionResult(false, "Workflow not found: " + workflowFile);
            }
            childInterpreter.readYaml(yamlStream);

            // === Step 3: runUntilEnd ===
            ActionResult result = childInterpreter.runUntilEnd(1000);

            return result;

        } catch (Throwable t) {
            // An Error is false like an exception; a fatal one, which the sub-workflow has already
            // recorded as its own last result, goes on out of the calling run.
            ActionResult.rethrowIfFatal(t);
            logger.log(Level.SEVERE, "Subworkflow error: " + workflowFile, t);
            return ActionResult.error(t, "call " + workflowFile);

        } finally {
            // === Step 4: removeChild (always executed) ===
            if (registered != null && (Object) system.getIIActor(childName) == registered) {
                removeChildActor(childName);
            } else if (registered == null && selfActorRef != null && system.getIIActor(childName) == null) {
                // Not registered, and nobody else holds the name: take back the name this call added
                // to its parent's children.
                selfActorRef.getNamesOfChildren().remove(childName);
            }
        }
    }

    /**
     * Loads a workflow from the classpath.
     *
     * <p>Searches for the workflow in the following locations:</p>
     * <ol>
     *   <li>{@code /workflows/{workflowFile}}</li>
     *   <li>{@code /{workflowFile}}</li>
     * </ol>
     *
     * @param workflowFile the workflow file name
     * @return InputStream for the workflow, or null if not found
     */
    protected InputStream loadWorkflowFromClasspath(String workflowFile) {
        // Try /workflows/ directory first
        InputStream stream = getClass().getResourceAsStream("/workflows/" + workflowFile);
        if (stream != null) {
            return stream;
        }
        // Try root classpath
        return getClass().getResourceAsStream("/" + workflowFile);
    }

    /**
     * Removes a child actor from the system and parent-child relationship.
     *
     * @param childName the name of the child actor to remove
     */
    protected void removeChildActor(String childName) {
        if (system != null) {
            system.removeIIActor(childName);
        }
        if (selfActorRef != null) {
            selfActorRef.getNamesOfChildren().remove(childName);
        }
    }

    /**
     * Applies an action to existing child actors (with wildcard support).
     *
     * <p>Unlike {@link #call(String)} which creates and deletes child actors,
     * this method operates on existing child actors without removing them.</p>
     *
     * <p>Supports wildcard patterns for actor names:</p>
     * <ul>
     *   <li>{@code *} — all child actors</li>
     *   <li>{@code Species-*} — child actors starting with "Species-"</li>
     *   <li>{@code *-worker} — child actors ending with "-worker"</li>
     *   <li>{@code node-*-primary} — child actors matching the pattern</li>
     * </ul>
     *
     * <p><strong>Usage in YAML:</strong></p>
     * <pre>{@code
     * - actor: this
     *   method: apply
     *   arguments:
     *     - actor: "Species-*"
     *       method: mutate
     *       arguments: [0.05, 0.02, 0.5]
     * }</pre>
     *
     * @param actionDefinition JSON string containing actor, method, and arguments
     * @return ActionResult indicating success or failure
     * @since 2.9.0
     */
    public ActionResult apply(String actionDefinition) {
        if (selfActorRef == null) {
            return new ActionResult(false, "No self actor reference configured");
        }

        try {
            JSONObject action = new JSONObject(actionDefinition);
            String actorPattern = action.getString("actor");
            String methodName = action.getString("method");

            // Shape the children's arguments as a step's own action call shapes them, so an
            // object stays an object for an action declaring named arguments.
            Object argsObj = action.has("arguments") ? action.get("arguments") : null;
            if (argsObj instanceof JSONObject object) {
                argsObj = object.toMap();
            } else if (argsObj instanceof JSONArray array) {
                argsObj = array.toList();
            }
            String args = argumentsToJson(argsObj);

            // Find matching child actors
            List<IIActorRef<?>> matchedActors = findMatchingChildActors(actorPattern);

            if (matchedActors.isEmpty()) {
                return new ActionResult(false, "No actors matched pattern: " + actorPattern);
            }

            logger.info(String.format("Applying method '%s' to %d actors matching '%s' (parallel)",
                methodName, matchedActors.size(), actorPattern));

            // Thread-safe collections for gathering results
            AtomicInteger successCount = new AtomicInteger(0);
            Map<String, String> failures = new ConcurrentHashMap<>();

            // Create async tasks for all actors
            List<CompletableFuture<Void>> futures = new ArrayList<>();
            final String finalArgs = args;

            for (IIActorRef<?> actor : matchedActors) {
                CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                    try {
                        ActionResult result = actor.callByActionName(methodName, finalArgs);
                        if (!result.isSuccess()) {
                            failures.put(actor.getName(), result.getResult());
                            logger.warning(String.format("Failed on %s: %s",
                                actor.getName(), result.getResult()));
                        } else {
                            successCount.incrementAndGet();
                            logger.fine(String.format("Applied to %s: %s",
                                actor.getName(), result.getResult()));
                        }
                    } catch (Throwable t) {
                        // An Error is this child's failure like an exception. A fatal one is noted
                        // too, and then rethrown: it fails this future, and the join below rethrows it.
                        failures.put(actor.getName(), ActionResult.errorText(t, actor.getName() + "." + methodName));
                        logger.log(Level.WARNING, "Exception on " + actor.getName(), t);
                        ActionResult.rethrowIfFatal(t);
                    }
                });
                futures.add(future);
            }

            // Wait for all async tasks to complete. No time limit on purpose: each actor's
            // action may legitimately run a long time, and every future here now always
            // completes. Following TimeoutAndScheduler_251012_oo01, a caller that needs a limit
            // puts it on its own wait.
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

            if (failures.isEmpty()) {
                return new ActionResult(true,
                    String.format("Applied to %d actors", successCount.get()));
            } else {
                List<String> failureMessages = new ArrayList<>();
                failures.forEach((name, msg) -> failureMessages.add(name + ": " + msg));
                return new ActionResult(false,
                    String.format("Applied to %d/%d actors. Failures: %s",
                        successCount.get(), matchedActors.size(),
                        String.join("; ", failureMessages)));
            }

        } catch (Throwable t) {
            // A fatal error from a child arrives here inside a CompletionException: rethrow it.
            ActionResult.rethrowIfFatal(t);
            logger.log(Level.SEVERE, "Apply error", t);
            return ActionResult.error(t, "apply");
        }
    }

    /**
     * Finds all actors in the system matching a wildcard pattern.
     *
     * <p>This method searches all registered actors in the IIActorSystem,
     * not just child actors of the current interpreter.</p>
     *
     * @param pattern the pattern (exact name or wildcard like "Species-*")
     * @return list of matching actors
     * @since 2.9.0
     */
    protected List<IIActorRef<?>> findMatchingActors(String pattern) {
        List<IIActorRef<?>> matched = new ArrayList<>();

        if (system == null) {
            return matched;
        }

        // Exact match (no wildcard)
        if (!pattern.contains("*")) {
            IIActorRef<?> actor = system.getIIActor(pattern);
            if (actor != null) {
                matched.add(actor);
            }
            return matched;
        }

        // Wildcard pattern - convert to regex
        String regex = pattern
            .replace(".", "\\.")
            .replace("*", ".*");
        Pattern compiled = Pattern.compile(regex);

        for (String actorName : system.listActorNames()) {
            if (compiled.matcher(actorName).matches()) {
                IIActorRef<?> actor = system.getIIActor(actorName);
                if (actor != null) {
                    matched.add(actor);
                }
            }
        }

        return matched;
    }

    /**
     * Finds child actors matching a wildcard pattern.
     *
     * @param pattern the pattern (exact name or wildcard like "Species-*")
     * @return list of matching child actors
     */
    protected List<IIActorRef<?>> findMatchingChildActors(String pattern) {
        List<IIActorRef<?>> matched = new ArrayList<>();

        if (selfActorRef == null || system == null) {
            return matched;
        }

        Set<String> childNames = selfActorRef.getNamesOfChildren();

        // Exact match (no wildcard)
        if (!pattern.contains("*")) {
            if (childNames.contains(pattern)) {
                IIActorRef<?> actor = system.getIIActor(pattern);
                if (actor != null) {
                    matched.add(actor);
                }
            }
            return matched;
        }

        // Wildcard pattern - convert to regex
        String regex = pattern
            .replace(".", "\\.")
            .replace("*", ".*");
        Pattern compiled = Pattern.compile(regex);

        for (String childName : childNames) {
            if (compiled.matcher(childName).matches()) {
                IIActorRef<?> actor = system.getIIActor(childName);
                if (actor != null) {
                    matched.add(actor);
                }
            }
        }

        return matched;
    }

    // ========================================================================
    // Accumulator Support
    // ========================================================================

    /**
     * Adds a result to the accumulator actor in the system.
     *
     * <p>This is a convenience method for child workflows to report results
     * back to an accumulator. It looks up the accumulator actor by name
     * (default: "accumulator") and calls its "add" action.</p>
     *
     * <p><strong>Usage in YAML:</strong></p>
     * <pre>{@code
     * - states: ["0", "1"]
     *   actions:
     *     - actor: this
     *       method: addToAccumulator
     *       arguments:
     *         type: "cpu"
     *         data: "Intel Xeon E5-2680"
     * }</pre>
     *
     * @param type the type of result (e.g., "cpu", "memory")
     * @param data the result data
     * @return ActionResult indicating success or failure
     * @since 2.8.0
     */
    public ActionResult addToAccumulator(String type, String data) {
        return addToAccumulator("accumulator", type, data);
    }

    /**
     * Adds a result to a named accumulator actor in the system.
     *
     * @param accumulatorName the name of the accumulator actor
     * @param type the type of result (e.g., "cpu", "memory")
     * @param data the result data
     * @return ActionResult indicating success or failure
     * @since 2.8.0
     */
    public ActionResult addToAccumulator(String accumulatorName, String type, String data) {
        if (system == null) {
            return new ActionResult(false, "No actor system available");
        }

        IIActorRef<?> accumulator = system.getIIActor(accumulatorName);
        if (accumulator == null) {
            return new ActionResult(false, "Accumulator not found: " + accumulatorName);
        }

        // Build the argument JSON
        JSONObject addArg = new JSONObject();
        addArg.put("source", selfActorRef != null ? selfActorRef.getName() : "unknown");
        addArg.put("type", type);
        addArg.put("data", data);

        return accumulator.callByActionName("add", addArg.toString());
    }

}
