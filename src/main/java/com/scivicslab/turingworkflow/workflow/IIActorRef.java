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

import com.scivicslab.pojoactor.core.Action;
import com.scivicslab.pojoactor.core.ActionDispatcher;
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

    private final ActionDispatcher dispatcher = new ActionDispatcher(this);

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
