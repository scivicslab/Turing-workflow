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

import org.json.JSONObject;

import com.scivicslab.pojoactor.action.Action;
import com.scivicslab.pojoactor.action.ActionDispatcher;
import com.scivicslab.pojoactor.action.schema.ActionSchemaRegistry;
import com.scivicslab.pojoactor.core.ActorRef;
import com.scivicslab.pojoactor.action.CallableByActionName;
import com.scivicslab.pojoactor.action.ActionResult;

/**
 * An interpreter-interfaced actor reference that can be invoked by action name strings.
 *
 * <p>This abstract class extends {@link ActorRef} and implements {@link CallableByActionName},
 * providing a bridge between the POJO-actor framework and the workflow interpreter.
 * It allows actors to be invoked dynamically using string-based action names, which is
 * essential for data-driven workflow execution.</p>
 *
 * @param <T> the type of the actor object being referenced
 * @author devteam@scivicslab.com
 */
public abstract class IIActorRef<T> extends ActorRef<T> implements CallableByActionName {

    /**
     * The JSON Schemas of every {@code argsType} action on the classpath, read once for the whole
     * JVM. {@link ActionSchemaRegistry} scans the classpath when it is built, so one shared
     * instance rather than one per actor.
     */
    private static final ActionSchemaRegistry SCHEMAS = new ActionSchemaRegistry();

    /**
     * The object this actor wraps, for Java code that holds this adapter.
     *
     * <p>{@code ActorRef} keeps it as a protected field, so only a subclass can hand it out. An
     * adapter's actions call the plain object through it, and tests read it back. A workflow's
     * expression does not get it from {@code actors.get(name)}, which answers a
     * {@link WorkflowExpressions.ActorView}, the actor's last result and state. (With the
     * expression allow-list off, an expression has the reach of Java and may still build or find
     * objects by other means.)</p>
     *
     * <p>Reading it here does not go through the mailbox. Actions invoked by name do not either,
     * so this adds no sharing that was not already present.</p>
     *
     * @return the wrapped object, or {@code null} when this actor wraps nothing
     */
    public T wrapped() {
        return object;
    }

    /**
     * The one registry every actor in this JVM validates against.
     *
     * <p>Exposed so that whoever adds a jar while the process is running can call
     * {@link ActionSchemaRegistry#addFrom(ClassLoader)} on it. Dispatchers hold this object
     * rather than a copy, so schemas added afterwards take effect for actors already created.</p>
     *
     * @return the shared registry; never {@code null}
     */
    public static ActionSchemaRegistry sharedSchemaRegistry() {
        return SCHEMAS;
    }

    private final ActionDispatcher dispatcher = new ActionDispatcher(this, SCHEMAS);

    /**
     * The actor whose own workflow is running the action now, on this thread; null when the action
     * was not sent by the workflow of the actor it is called on.
     *
     * <p>Set around one action by {@link #callAsStep}, which {@code Interpreter.action()} uses for
     * every step. It says where the call comes from, not which thread runs it: a step that names the
     * actor running the workflow ({@code actor: this}, or its own name) is that actor's own work.</p>
     */
    private static final ThreadLocal<IIActorRef<?>> OWN_STEP = new ThreadLocal<>();

    /**
     * Calls {@code actionName} on {@code target} as a step of {@code caller}'s workflow, and marks the
     * call as the target's own step when {@code caller} is {@code target}.
     *
     * <p>The marker covers this one call on this thread and is put back afterwards, so a call a
     * step sends to another actor, and a sub-workflow's steps, never see their caller's marker.</p>
     *
     * @param caller the actor whose workflow runs the step, or null when the interpreter has no actor
     * @param target the actor the step names
     * @param actionName the action the step names
     * @param args the arguments as a JSON string
     * @return the action's result
     */
    static ActionResult callAsStep(IIActorRef<?> caller, IIActorRef<?> target, String actionName, String args) {
        IIActorRef<?> previous = OWN_STEP.get();
        if (caller != null && caller == target) {
            OWN_STEP.set(target);
        } else {
            OWN_STEP.remove();
        }
        try {
            return target.callByActionName(actionName, args);
        } finally {
            if (previous == null) {
                OWN_STEP.remove();
            } else {
                OWN_STEP.set(previous);
            }
        }
    }

    /**
     * Whether the action running now was sent by the workflow of this very actor ({@code actor: this}
     * or its own name).
     *
     * <p>Such an action runs as part of the message that is running this actor's workflow, so it
     * must not send itself to this actor's mailbox and wait: the mailbox's virtual thread takes the
     * next message only when the one it runs has ended, and that one would wait for the next. An
     * action that works on its own POJO does that work directly when this is true, and through the
     * mailbox ({@code tell}, {@code ask}) otherwise, as when another actor's step calls it.</p>
     *
     * @return true during an action that this actor's own workflow sent to itself
     */
    protected final boolean isOwnStep() {
        return OWN_STEP.get() == this;
    }

    /**
     * Constructs a new IIActorRef with the specified actor name and object.
     *
     * @param actorName the name of the actor
     * @param object the actor object instance
     */
    public IIActorRef(String actorName, T object) {
        super(actorName, object);
    }

    /**
     * Constructs a new IIActorRef with the specified actor name, object, and actor system.
     *
     * @param actorName the name of the actor
     * @param object the actor object instance
     * @param system the actor system managing this actor
     */
    public IIActorRef(String actorName, T object, IIActorSystem system) {
        super(actorName, object, system);
    }

    /**
     * Constructs a new IIActorRef and runs a companion-setup callback at the end of
     * construction.
     *
     * <p>The {@code companionSetup} callback receives this actor (which, being an
     * {@code IIActorRef}, knows the {@link IIActorSystem}) and can create and attach companion
     * child actors — for example a dedicated watchdog whose {@code trip}/{@code close()} stops
     * this actor. Placing this on the {@code IIActorRef} layer keeps a wrapped POJO, which
     * knows nothing about actors or the actor system, free of this responsibility. Use
     * {@link #addChildActor(IIActorRef)} from the callback to attach the companion as a child
     * of this actor.</p>
     *
     * <p>The callback runs before this actor's subclass fields are initialised (it receives a
     * {@code this} reference from inside the constructor), so it must only store a reference to
     * this actor, never use its not-yet-initialised state.</p>
     *
     * @param actorName      the name of the actor
     * @param object         the actor object instance
     * @param system         the actor system managing this actor
     * @param companionSetup callback to create/attach companion actors, or {@code null}
     */
    public IIActorRef(String actorName, T object, IIActorSystem system,
            java.util.function.Consumer<IIActorRef<T>> companionSetup) {
        super(actorName, object, system);
        if (companionSetup != null) {
            companionSetup.accept(this);
        }
    }

    /**
     * Stores what an action returned, which the next action's argument reads as
     * {@code jexl: result}.
     *
     * <p>{@code ActorRef} keeps the value as a plain string, since what an action returned is
     * this project's vocabulary rather than the actor model's. This pair is the typed view of
     * that same slot.
     *
     * @param result the result to store, or {@code null} to clear it
     */
    public void setLastResult(ActionResult result) {
        setLastResultValue(result == null ? null : result.getResult());
    }

    /**
     * @return the last stored result, or {@code null} if no action has run
     */
    public ActionResult getLastResult() {
        String value = getLastResultValue();
        return value == null ? null : new ActionResult(true, value);
    }

    /**
     * Attaches an already-constructed IIActorRef as a child of this actor and registers it in
     * the actor system. Used from a companion-setup callback (see
     * {@link #IIActorRef(String, Object, IIActorSystem, java.util.function.Consumer)}).
     *
     * @param child the actor to attach as a child of this actor
     */
    public void addChildActor(IIActorRef<?> child) {
        child.setParentName(this.getName());
        this.getNamesOfChildren().add(child.getName());
        ((IIActorSystem) system()).addIIActor(child);
    }

    /**
     * Invokes the {@link Action @Action}-annotated method whose name matches {@code actionName}.
     * Delegates to {@link ActionDispatcher} from POJO-actor.
     *
     * @param actionName the action name
     * @param args the arguments string
     * @return ActionResult if handled, null if no matching @Action method
     */
    protected ActionResult invokeAnnotatedAction(String actionName, String args) {
        return dispatcher.invoke(actionName, args);
    }

    /**
     * Returns {@code true} if an {@link Action @Action}-annotated method is registered
     * for the given name.
     */
    protected boolean hasAnnotatedAction(String actionName) {
        return dispatcher.has(actionName);
    }

    /**
     * Whether the action of that name orchestrates ({@link Action#orchestrates()}): a workflow step
     * that names it without an {@code execution:} key runs it on the calling interpreter's mailbox
     * virtual thread rather than on the ManagedThreadPool.
     *
     * @param actionName the action name
     * @return true for an {@code @Action(orchestrates = true)} method of that name
     */
    public boolean orchestrates(String actionName) {
        return dispatcher.orchestrates(actionName);
    }


    /**
     * Invokes an action by name on this actor.
     *
     * <p>The action is the public method annotated {@link Action @Action} with that name, on the
     * IIActorRef subclass or on this class: the six JSON State actions (putJson, appendJson,
     * getJson, hasJson, clearJson, printJson) are such methods here, so every actor has them and
     * the doclet documents them once. This keeps the POJO clean - only the IIActorRef adapter
     * needs workflow-related code. A name no method carries is a failure.</p>
     *
     * <p><strong>DO NOT OVERRIDE THIS METHOD.</strong> Use {@link Action @Action} annotation
     * on your methods instead. The {@code @Action} annotation provides cleaner, more
     * maintainable code compared to overriding with switch statements.</p>
     *
     * <p><strong>Recommended pattern:</strong></p>
     * <pre>{@code
     * public class MyActor extends IIActorRef<Void> {
     *     public MyActor(String name, IIActorSystem system) {
     *         super(name, null, system);
     *     }
     *
     *     @Action("doSomething")
     *     public ActionResult doSomething(String args) {
     *         // implementation
     *         return new ActionResult(true, "done");
     *     }
     * }
     * }</pre>
     *
     * <p><strong>Deprecated pattern (do not use):</strong></p>
     * <pre>{@code
     * // BAD: Don't override callByActionName with switch statement
     * @Override
     * public ActionResult callByActionName(String actionName, String args) {
     *     return switch (actionName) {
     *         case "doSomething" -> doSomething(args);
     *         default -> super.callByActionName(actionName, args);
     *     };
     * }
     * }</pre>
     *
     * @param actionName the name of the action to invoke
     * @param args the arguments as a JSON string
     * @return the result of the action
     */
    @Override
    public ActionResult callByActionName(String actionName, String args) {
        ActionResult result = invokeAnnotatedAction(actionName, args);
        if (result != null) {
            return result;
        }
        return new ActionResult(false, "Unknown action: " + actionName);
    }

    /**
     * Stores one value in this actor's JSON state; the message names the path and the value.
     *
     * <p>Every actor has this action; a workflow usually calls it on itself as {@code this.putJson}
     * to keep what an earlier action answered. {@code value} may be any JSON value; written as a
     * {@code jexl:} expression it reaches the state with the type the expression answers, and
     * {@code result} in that expression is the previous action's message. An object or a list is
     * stored as structure, so a later path can reach its fields. A path that does not exist yet is
     * created; {@code items[2]} writes that index.</p>
     *
     * <pre>{@code
     * - actor: this
     *   method: putJson
     *   arguments: {path: judge.verdict, value: "jexl: result"}
     * }</pre>
     *
     * @param args {@code {"path": "key.path", "value": <any>}}
     */
    @Action("putJson")
    public ActionResult handlePutJson(String args) {
        try {
            JSONObject json = new JSONObject(args);
            String path = json.getString("path");
            String tooLarge = indexAboveLimit(path);
            if (tooLarge != null) {
                return new ActionResult(false, "putJson: index " + tooLarge + " in path " + path
                        + " is above the limit of " + MAX_PUT_JSON_INDEX);
            }
            Object value = valueOf(json.get("value"));
            putJson(path, value);
            return new ActionResult(true, "Stored " + path + "=" + value);
        } catch (Exception e) {
            return ActionResult.error(e, getName() + ".putJson");
        }
    }

    /**
     * The largest list index a {@code putJson} path may name ({@code items[100000]}). The JSON state
     * fills a list up to the index it is given, one new element at a time, so one path with a large
     * index allocates that many elements: {@code items[2000000000]} runs the process out of memory
     * from inside the actor's work. A list a workflow fills element by element stays far below this;
     * {@code appendJson} adds to the end without naming an index and is not limited.
     */
    public static final int MAX_PUT_JSON_INDEX = 100_000;

    /**
     * The first list index in {@code path} above {@link #MAX_PUT_JSON_INDEX}, as written, or null.
     * Only a bracket holding digits alone is an index, as in the JSON state itself.
     */
    static String indexAboveLimit(String path) {
        int open = path.indexOf('[');
        while (open >= 0) {
            int close = path.indexOf(']', open + 1);
            if (close < 0) {
                return null;
            }
            String inside = path.substring(open + 1, close).trim();
            if (!inside.isEmpty() && inside.chars().allMatch(Character::isDigit)) {
                if (inside.length() > 9 || Integer.parseInt(inside) > MAX_PUT_JSON_INDEX) {
                    return inside;
                }
            }
            open = path.indexOf('[', close + 1);
        }
        return null;
    }

    /**
     * Adds one value to the end of the list at a path in this actor's JSON state.
     *
     * <p>The one list operation {@code putJson} cannot do. {@code putJson} writes the index it is
     * given ({@code items[2]}), and a workflow cannot compute that index into the path, so adding
     * to the end needed an action of its own ({@code ActorsAsVariables_260914_oo01}). A path holding
     * nothing becomes a list of one. A path holding anything that is not a list is refused rather
     * than overwritten.</p>
     *
     * <pre>{@code
     * - actor: this
     *   method: appendJson
     *   arguments: {path: items, value: "alpha"}
     * }</pre>
     *
     * @param args {@code {"path": "key.path", "value": <any>}}
     */
    @Action("appendJson")
    public ActionResult handleAppendJson(String args) {
        try {
            JSONObject json = new JSONObject(args);
            String path = json.getString("path");
            Object value = valueOf(json.get("value"));
            // A workflow calls this on its own actor while the workflow's message holds that
            // actor's mailbox, so it cannot be queued there; the state is guarded instead.
            // JsonState.append reads the end of the list and writes past it under one hold of
            // the state's lock, so a writer beside the mailbox never takes the same index.
            int end;
            try {
                end = json().append(path, value);
            } catch (IllegalStateException notAList) {
                return new ActionResult(false, "appendJson refused: " + notAList.getMessage());
            }
            return new ActionResult(true, "Added to " + path + "[" + end + "]=" + value);
        } catch (Exception e) {
            return ActionResult.error(e, getName() + ".appendJson");
        }
    }

    /**
     * The value to store, as the JSON state can keep it.
     *
     * <p>An object or a list arrives here as an {@code org.json} value, which the state has no
     * case for and would keep as its printed form — a string that happens to look like JSON,
     * whose fields no path can reach. Handed over as text, the state parses it back into
     * structure, which is what {@code JsonStatePutJson_260510_oo01} says a stored object is.</p>
     *
     * @param value what the action was given
     * @return the same value, as text when it is an object or a list
     */
    private static Object valueOf(Object value) {
        return (value instanceof JSONObject || value instanceof org.json.JSONArray)
                ? value.toString() : value;
    }

    /**
     * Reads one value from this actor's JSON state; the message is the value as text, "" when the
     * path holds nothing.
     *
     * <p>In a {@code jexl:} expression the state is read directly as
     * {@code state.getString('key.path')}; this action is for storing the value as the previous
     * message, or for a caller in another process.</p>
     *
     * <pre>{@code
     * - actor: this
     *   method: getJson
     *   arguments: "judge.verdict"
     * }</pre>
     *
     * @param args the path, as a bare string or as the first element of a JSON array
     */
    @Action("getJson")
    public ActionResult handleGetJson(String args) {
        try {
            String path = parseFirstArgument(args);
            String value = getJsonString(path);
            return new ActionResult(true, value != null ? value : "");
        } catch (Exception e) {
            return ActionResult.error(e, getName() + ".getJson");
        }
    }

    /**
     * Tells whether a path holds a value in this actor's JSON state; the message is {@code true} or
     * {@code false}.
     *
     * <p>The answer is a fact about the data, so the action succeeds either way: store the message
     * and decide with {@code onlyIf}, or read {@code state.has('key.path')} in the condition directly.</p>
     *
     * <pre>{@code
     * - actor: this
     *   method: hasJson
     *   arguments: "items"
     * }</pre>
     *
     * @param args the path, as a bare string or as the first element of a JSON array
     */
    @Action("hasJson")
    public ActionResult handleHasJson(String args) {
        try {
            String path = parseFirstArgument(args);
            boolean exists = hasJson(path);
            return new ActionResult(true, exists ? "true" : "false");
        } catch (Exception e) {
            return ActionResult.error(e, getName() + ".hasJson");
        }
    }

    /**
     * Empties this actor's JSON state.
     *
     * <pre>{@code
     * - actor: this
     *   method: clearJson
     * }</pre>
     *
     * @param args ignored
     */
    @Action("clearJson")
    public ActionResult handleClearJson(String args) {
        clearJsonState();
        return new ActionResult(true, "JSON state cleared");
    }

    /**
     * Prints this actor's JSON state to standard output, for reading a run's values while writing
     * the workflow.
     *
     * <pre>{@code
     * - actor: this
     *   method: printJson
     * }</pre>
     *
     * @param args ignored
     */
    @Action("printJson")
    public ActionResult handlePrintJson(String args) {
        System.out.println(json().toPrettyString());
        return new ActionResult(true, "Printed JSON state");
    }

    /**
     * Parses the first argument from a JSON array or returns the string as-is.
     */
    protected String parseFirstArgument(String arg) {
        if (arg == null || arg.isEmpty()) {
            return "";
        }
        if (arg.startsWith("[")) {
            try {
                org.json.JSONArray arr = new org.json.JSONArray(arg);
                if (arr.length() > 0) {
                    return arr.getString(0);
                }
            } catch (Exception e) {
                // Not a valid JSON array
            }
        }
        return arg;
    }

}
