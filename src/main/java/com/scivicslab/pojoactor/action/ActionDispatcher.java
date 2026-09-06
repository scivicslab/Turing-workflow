/*
 * Copyright 2025 SCIVICS Lab
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.scivicslab.pojoactor.action;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import com.scivicslab.pojoactor.action.schema.ActionSchemaRegistry;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * Reflection-based dispatch helper for {@link Action @Action}-annotated methods.
 *
 * <p>Lazily scans a target object's class for methods annotated with {@link Action},
 * validates their signatures, and caches the discovered methods for fast subsequent
 * invocations.  Designed to be used as a delegate field inside classes that cannot
 * extend {@link AbstractCallableByActionName} due to Java's single-inheritance
 * constraint (e.g. classes that already extend a framework base class).</p>
 *
 * <p><strong>JVM only.</strong> Uses reflection and is not compatible with GraalVM
 * Native Image without additional {@code reflect-config.json} configuration.</p>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * public class MyFrameworkActor extends FrameworkBase implements CallableByActionName {
 *     private final ActionDispatcher dispatcher = new ActionDispatcher(this);
 *
 *     @Action("doWork")
 *     public ActionResult doWork(String args) { ... }
 *
 *     @Override
 *     public ActionResult callByActionName(String actionName, String args) {
 *         ActionResult r = dispatcher.invoke(actionName, args);
 *         if (r != null) return r;
 *         // handle additional cases or return unknown-action failure
 *         return new ActionResult(false, "Unknown action: " + actionName);
 *     }
 * }
 * }</pre>
 *
 * @since 2.0.0
 * @see Action
 * @see AbstractCallableByActionName
 */
public class ActionDispatcher {

    private static final Logger logger = Logger.getLogger(ActionDispatcher.class.getName());
    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();

    // Lazy holder (initialization-on-demand holder idiom): com.networknt:json-schema-validator
    // is an <optional> dependency of POJO-actor. ActionDispatcher is loaded by every @Action
    // actor regardless of whether it uses argsType/schema validation at all, so referencing
    // JsonSchemaFactory as an eager static field here would force json-schema-validator's
    // classes to resolve the moment ActionDispatcher itself is loaded — breaking every consumer
    // that doesn't happen to have that optional dependency present (observed: NoClassDefFoundError
    // "Could not initialize class ActionDispatcher" in Turing-workflow, which has no
    // json-schema-validator dependency). SchemaFactoryHolder's class-loading is deferred until
    // validateAgainstSchema() actually runs, which only happens when schemaRegistry is non-null.
    private static final class SchemaFactoryHolder {
        static final JsonSchemaFactory INSTANCE = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
    }

    private final Object target;
    private final ActionSchemaRegistry schemaRegistry;
    private Map<String, Method> actionMethods = null;

    /**
     * Creates a dispatcher that will scan and invoke {@link Action @Action}-annotated
     * methods on {@code target}. Equivalent to {@code new ActionDispatcher(target, null)} —
     * no schema validation is performed even for {@code argsType} methods.
     *
     * @param target the object to scan; typically {@code this} from the owner class
     */
    public ActionDispatcher(Object target) {
        this(target, null);
    }

    /**
     * Creates a dispatcher that additionally validates {@code argsType} arguments against
     * {@code schemaRegistry} before deserializing them, when a schema is registered for the
     * target class and action name.
     *
     * <p>{@code schemaRegistry} scans the classpath once at its own construction time, so
     * callers should build one shared instance and pass it to every {@code ActionDispatcher}
     * that wants validation, rather than constructing a new registry per dispatcher.</p>
     *
     * @param target         the object to scan; typically {@code this} from the owner class
     * @param schemaRegistry the schema registry to validate {@code argsType} arguments against,
     *                       or {@code null} to skip validation (deserialize directly, as before)
     */
    public ActionDispatcher(Object target, ActionSchemaRegistry schemaRegistry) {
        this.target = target;
        this.schemaRegistry = schemaRegistry;
    }

    private void discover() {
        if (actionMethods != null) {
            return;
        }

        actionMethods = new HashMap<>();

        for (Method method : target.getClass().getMethods()) {
            Action action = method.getAnnotation(Action.class);
            if (action == null) {
                continue;
            }

            if (method.getReturnType() != ActionResult.class) {
                logger.warning(String.format(
                    "@Action method %s.%s has invalid return type %s (expected ActionResult)",
                    target.getClass().getSimpleName(), method.getName(),
                    method.getReturnType().getSimpleName()));
                continue;
            }

            // argsType() defaults to Void.class, meaning "no type — raw String, current
            // behavior". A non-default argsType() opts the method into deserializing args
            // (a JSON string) into that type before invocation; see Action.argsType().
            Class<?> argsType = action.argsType();
            Class<?> expectedParamType = (argsType == Void.class) ? String.class : argsType;

            Class<?>[] params = method.getParameterTypes();
            if (params.length != 1 || params[0] != expectedParamType) {
                logger.warning(String.format(
                    "@Action method %s.%s must accept exactly one %s parameter",
                    target.getClass().getSimpleName(), method.getName(),
                    expectedParamType.getSimpleName()));
                continue;
            }

            String actionName = action.value();
            if (actionMethods.containsKey(actionName)) {
                logger.warning(String.format(
                    "Duplicate @Action(\"%s\") on %s.%s — skipped",
                    actionName, target.getClass().getSimpleName(), method.getName()));
                continue;
            }

            method.setAccessible(true);
            actionMethods.put(actionName, method);
        }
    }

    /**
     * Invokes the {@link Action @Action}-annotated method whose name matches {@code actionName}.
     *
     * @param actionName the action name to look up
     * @param args the argument string to pass to the method
     * @return the {@link ActionResult} returned by the method, or {@code null} if no
     *         matching method was found (so the caller can fall through to other dispatch stages)
     */
    public ActionResult invoke(String actionName, String args) {
        discover();

        Method method = actionMethods.get(actionName);
        if (method == null) {
            return null;
        }

        // A method declared with a non-String parameter (via @Action(argsType=...))
        // receives a JSON-deserialized instance of that type instead of the raw String.
        Class<?> paramType = method.getParameterTypes()[0];
        Object arg = args;
        if (paramType != String.class) {
            if (schemaRegistry != null) {
                ActionResult validationFailure = validateAgainstSchema(actionName, args);
                if (validationFailure != null) {
                    return validationFailure;
                }
            }
            try {
                arg = JSON_MAPPER.readValue(args, paramType);
            } catch (JsonProcessingException e) {
                return new ActionResult(false, "Failed to parse arguments for " + actionName
                        + " as " + paramType.getSimpleName() + ": " + e.getMessage());
            }
        }

        try {
            return (ActionResult) method.invoke(target, arg);
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
     * Validates {@code args} against the schema registered for {@code target}'s class and
     * {@code actionName}, if any.
     *
     * @return an {@link ActionResult} failure to return immediately (invalid JSON, or JSON
     *         that does not satisfy the schema), or {@code null} if there is no registered
     *         schema for this action, or {@code args} satisfies it
     */
    private ActionResult validateAgainstSchema(String actionName, String args) {
        JsonNode schemaNode = schemaRegistry.schemaFor(target.getClass(), actionName);
        if (schemaNode == null) {
            return null;
        }

        JsonNode argsNode;
        try {
            argsNode = JSON_MAPPER.readTree(args);
        } catch (JsonProcessingException e) {
            return new ActionResult(false, "Failed to parse arguments for " + actionName
                    + " as JSON: " + e.getMessage());
        }

        JsonSchema schema = SchemaFactoryHolder.INSTANCE.getSchema(schemaNode);
        Set<ValidationMessage> errors = schema.validate(argsNode);
        if (errors.isEmpty()) {
            return null;
        }
        String joined = errors.stream().map(ValidationMessage::getMessage).collect(Collectors.joining("; "));
        return new ActionResult(false, "Argument validation failed for " + actionName + ": " + joined);
    }

    /**
     * Returns {@code true} if an {@link Action @Action}-annotated method is registered
     * for the given name.
     */
    /**
     * The names of every {@link Action @Action} this dispatcher can invoke.
     *
     * <p>What a caller in another process needs first: which actions exist on an actor, before
     * asking what one of them takes ({@code ActionArgumentSchema_260807_oo01} step 3). Sorted so
     * the same actor always reports them in the same order.
     *
     * @return the action names, in name order
     */
    public java.util.SortedSet<String> actionNames() {
        discover();
        return new java.util.TreeSet<>(actionMethods.keySet());
    }

    public boolean has(String actionName) {
        discover();
        return actionMethods.containsKey(actionName);
    }
}
