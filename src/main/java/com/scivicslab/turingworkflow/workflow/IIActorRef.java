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

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.json.JSONObject;

import com.scivicslab.pojoactor.core.Action;
import com.scivicslab.pojoactor.core.ActorRef;
import com.scivicslab.pojoactor.core.CallableByActionName;
import com.scivicslab.pojoactor.core.ActionResult;

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

    private static final Logger logger = Logger.getLogger(IIActorRef.class.getName());

    /**
     * Map of action names to methods discovered via @Action annotation.
     * Populated lazily on first callByActionName invocation.
     */
    private Map<String, Method> actionMethods = null;

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
     * Discovers methods annotated with @Action on the IIActorRef subclass.
     *
     * <p>This method scans the concrete IIActorRef implementation for methods
     * annotated with {@link Action}. This keeps the POJO clean - the wrapped
     * object doesn't need to know about workflow-related annotations.</p>
     *
     * <p>Valid action methods must:</p>
     * <ul>
     *   <li>Return {@link ActionResult}</li>
     *   <li>Accept a single {@code String} parameter</li>
     * </ul>
     *
     * <p>Discovery is performed lazily on first call and cached for subsequent calls.</p>
     */
    private void discoverActionMethods() {
        if (actionMethods != null) {
            return; // Already discovered
        }

        actionMethods = new HashMap<>();

        // Scan IIActorRef subclass methods
        for (Method method : this.getClass().getMethods()) {
            Action action = method.getAnnotation(Action.class);
            if (action == null) {
                continue;
            }

            // Validate method signature: ActionResult methodName(String args)
            if (method.getReturnType() != ActionResult.class) {
                logger.warning(String.format(
                    "@Action method %s.%s has invalid return type %s (expected ActionResult)",
                    this.getClass().getSimpleName(), method.getName(), method.getReturnType().getSimpleName()));
                continue;
            }

            Class<?>[] params = method.getParameterTypes();
            if (params.length != 1 || params[0] != String.class) {
                logger.warning(String.format(
                    "@Action method %s.%s has invalid parameters (expected single String parameter)",
                    this.getClass().getSimpleName(), method.getName()));
                continue;
            }

            String actionName = action.value();
            if (actionMethods.containsKey(actionName)) {
                logger.warning(String.format(
                    "Duplicate @Action(\"%s\") found on %s.%s (already defined)",
                    actionName, this.getClass().getSimpleName(), method.getName()));
                continue;
            }

            actionMethods.put(actionName, method);
            logger.fine(String.format("Discovered @Action(\"%s\") -> %s.%s",
                actionName, this.getClass().getSimpleName(), method.getName()));
        }

        logger.fine(String.format("Discovered %d @Action methods on %s",
            actionMethods.size(), this.getClass().getSimpleName()));
    }

    /**
     * Invokes an @Action annotated method on this IIActorRef instance.
     *
     * @param actionName the action name
     * @param args the arguments string
     * @return ActionResult if handled, null if no matching @Action method
     */
    protected ActionResult invokeAnnotatedAction(String actionName, String args) {
        discoverActionMethods();

        Method method = actionMethods.get(actionName);
        if (method == null) {
            return null; // Not found, let caller handle fallback
        }

        try {
            return (ActionResult) method.invoke(this, args);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            String message = cause != null ? cause.getMessage() : e.getMessage();
            logger.log(Level.WARNING, "Error invoking @Action " + actionName, e);
            return new ActionResult(false, "Error in " + actionName + ": " + message);
        } catch (IllegalAccessException e) {
            logger.log(Level.SEVERE, "Cannot access @Action method " + actionName, e);
            return new ActionResult(false, "Cannot access " + actionName + ": " + e.getMessage());
        }
    }

    /**
     * Checks if an action name is registered via @Action annotation.
     *
     * @param actionName the action name to check
     * @return true if the action is registered
     */
    protected boolean hasAnnotatedAction(String actionName) {
        discoverActionMethods();
        return actionMethods.containsKey(actionName);
    }


    /**
     * Invokes an action by name on this actor.
     *
     * <p>This method uses a three-stage dispatch mechanism:</p>
     * <ol>
     *   <li><strong>@Action annotation:</strong> Checks the IIActorRef subclass for methods
     *       annotated with {@link Action} matching the action name. This keeps the POJO
     *       clean - only the IIActorRef adapter needs workflow-related code.</li>
     *   <li><strong>Built-in JSON State API:</strong> Handles putJson, getJson, hasJson,
     *       clearJson, and printJson actions.</li>
     *   <li><strong>Unknown action:</strong> Returns failure for unrecognized actions.</li>
     * </ol>
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
        // Stage 1: Try @Action annotated methods on the wrapped object
        ActionResult annotatedResult = invokeAnnotatedAction(actionName, args);
        if (annotatedResult != null) {
            return annotatedResult;
        }

        // Stage 2: Built-in JSON State API actions
        return switch (actionName) {
            case "putJson" -> handlePutJson(args);
            case "getJson" -> handleGetJson(args);
            case "hasJson" -> handleHasJson(args);
            case "clearJson" -> handleClearJson();
            case "printJson" -> handlePrintJson();
            default -> new ActionResult(false, "Unknown action: " + actionName);
        };
    }

    /**
     * Handles putJson action.
     * Expected args: {"path": "key.path", "value": <any>}
     */
    private ActionResult handlePutJson(String args) {
        try {
            JSONObject json = new JSONObject(args);
            String path = json.getString("path");
            Object value = json.get("value");
            putJson(path, value);
            return new ActionResult(true, "Stored " + path + "=" + value);
        } catch (Exception e) {
            return new ActionResult(false, "putJson error: " + e.getMessage());
        }
    }

    /**
     * Handles getJson action.
     * Expected args: ["path"] or "path"
     */
    private ActionResult handleGetJson(String args) {
        try {
            String path = parseFirstArgument(args);
            String value = getJsonString(path);
            return new ActionResult(true, value != null ? value : "");
        } catch (Exception e) {
            return new ActionResult(false, "getJson error: " + e.getMessage());
        }
    }

    /**
     * Handles hasJson action.
     * Expected args: ["path"] or "path"
     */
    private ActionResult handleHasJson(String args) {
        try {
            String path = parseFirstArgument(args);
            boolean exists = hasJson(path);
            return new ActionResult(true, exists ? "true" : "false");
        } catch (Exception e) {
            return new ActionResult(false, "hasJson error: " + e.getMessage());
        }
    }

    /**
     * Handles clearJson action.
     */
    private ActionResult handleClearJson() {
        clearJsonState();
        return new ActionResult(true, "JSON state cleared");
    }

    /**
     * Handles printJson action.
     */
    private ActionResult handlePrintJson() {
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
