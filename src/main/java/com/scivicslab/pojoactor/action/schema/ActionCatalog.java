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

package com.scivicslab.pojoactor.action.schema;

import java.util.SortedSet;

import com.fasterxml.jackson.databind.JsonNode;
import com.scivicslab.pojoactor.action.Action;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.scivicslab.pojoactor.action.ActionDispatcher;
import com.scivicslab.pojoactor.action.ActionResult;
import com.scivicslab.pojoactor.core.ActorSystem;
import com.scivicslab.pojoactor.action.CallableByActionName;

/**
 * Answers, for callers in other processes, what they may call on this actor system.
 *
 * <p>A parent interpreter that drives actors here has to write the call before making it: which
 * actions an actor has, and what one of them takes. Both are answered here, as actions, so they
 * travel the same published port the calls themselves do and need no endpoint of their own
 * ({@code ActionArgumentSchema_260807_oo01} step 3).
 *
 * <p>The two questions are separate on purpose. A caller writing one step of a workflow wants one
 * actor's actions, not every schema in the system; returning them all would put the whole set into
 * whatever prompt is being built.
 *
 * <h2>Actions</h2>
 * <pre>
 * listActions    {"actor": "project1/chat-01.chat"}
 *                → {"actor": "...", "actions": ["finish", "getResult", ...]}
 *
 * describeAction {"actor": "project1/chat-01.chat", "action": "sendPrompt"}
 *                → {"actor": "...", "action": "...", "schema": { ... }}
 *                → {"actor": "...", "action": "...", "schema": null, "note": "..."}
 * </pre>
 *
 * <p>Register it in the system it describes, under whatever name suits that application:
 *
 * <pre>{@code
 * system.actorOf("actionCatalog", new ActionCatalog(system, new ActionSchemaRegistry()));
 * }</pre>
 */
public class ActionCatalog implements CallableByActionName {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final ActorSystem actorSystem;
    private final ActionSchemaRegistry schemaRegistry;
    private final ActionManifest manifest;

    /**
     * @param actorSystem    the system whose actors are described — the same one this is
     *                       registered in, so that an actor's name resolves the way a caller's
     *                       call would resolve it
     * @param schemaRegistry the schemas loaded from the classpath
     */
    public ActionCatalog(ActorSystem actorSystem, ActionSchemaRegistry schemaRegistry) {
        this(actorSystem, schemaRegistry, new ActionManifest(ActionCatalog.class.getClassLoader()));
    }

    /**
     * @param manifest the Javadoc prose the doclet wrote into the jars, merged into each description
     */
    public ActionCatalog(ActorSystem actorSystem, ActionSchemaRegistry schemaRegistry, ActionManifest manifest) {
        this.actorSystem = actorSystem;
        this.schemaRegistry = schemaRegistry;
        this.manifest = manifest;
    }

    @Override
    public ActionResult callByActionName(String actionName, String args) {
        try {
            JsonNode request = JSON.readTree(args == null || args.isBlank() ? "{}" : args);
            return switch (actionName) {
                case "listActions" -> listActions(request.path("actor").asText(""));
                case "describeAction" -> describeAction(
                        request.path("actor").asText(""), request.path("action").asText(""));
                default -> new ActionResult(false, "Unknown action: " + actionName);
            };
        } catch (Exception e) {
            return new ActionResult(false, actionName + " failed: " + e.getMessage());
        }
    }

    private ActionResult listActions(String actorName) throws Exception {
        Object actor = targetOf(actorName);
        if (actor == null) {
            return new ActionResult(false, "Actor not found: " + actorName);
        }
        SortedSet<String> names = new ActionDispatcher(actor).actionNames();
        ObjectNode answer = JSON.createObjectNode();
        answer.put("actor", actorName);
        answer.put("class", actor.getClass().getName());
        answer.set("actions", JSON.valueToTree(names));
        return new ActionResult(true, JSON.writeValueAsString(answer));
    }

    private ActionResult describeAction(String actorName, String action) throws Exception {
        Object actor = targetOf(actorName);
        if (actor == null) {
            return new ActionResult(false, "Actor not found: " + actorName);
        }
        if (!new ActionDispatcher(actor).has(action)) {
            return new ActionResult(false, "Actor " + actorName + " has no action " + action);
        }
        ObjectNode answer = describe(actor.getClass(), action, schemaRegistry, manifest);
        answer.put("actor", actorName);
        return new ActionResult(true, JSON.writeValueAsString(answer));
    }

    /**
     * The description of one action of a class: its JSON Schema with the record's {@code @param}
     * prose as each property's {@code description}, and from the action method's Javadoc the first
     * sentence as {@code description}, the rest of the body as {@code details} and its first
     * {@code <pre>} block as {@code example}. Static so a caller that knows the class but has no
     * running actor (a workflow editor before the run) gets the same answer
     * ({@code ActionCatalogWithJavadoc_260930_oo01}).
     *
     * @return {@code {"action", "class", "description", "details"?, "example"?, "schema", "note"?,
     *         "argsFormat"?, "argument"?}}; {@code schema} is null and {@code note} says so when the
     *         action declares no {@code argsType}, and then {@code argument} is the method's own
     *         {@code @param} line ({@code {"name", "description"}}) when the Javadoc has one
     */
    public static ObjectNode describe(Class<?> actorClass, String action,
                                      ActionSchemaRegistry schemaRegistry, ActionManifest manifest) {
        ObjectNode answer = JSON.createObjectNode();
        answer.put("action", action);
        answer.put("class", actorClass.getName());
        ActionManifest.ActionDoc doc = manifest == null ? null : manifest.docFor(actorClass, action);
        answer.put("description", doc == null ? "" : doc.description());
        if (doc != null && !doc.details().isEmpty()) answer.put("details", doc.details());
        if (doc != null && !doc.example().isEmpty()) answer.put("example", doc.example());
        JsonNode schema = schemaRegistry == null ? null : schemaRegistry.schemaFor(actorClass, action);
        if (schema == null) {
            // Not an error: most actions declare no argsType and parse a raw String themselves.
            // Saying so keeps a caller from reading an absent schema as "takes no arguments".
            answer.set("schema", JSON.nullNode());
            answer.put("note", "This action takes a raw String; its shape is not declared.");
            if (doc != null && !doc.argsFormat().isEmpty()) answer.put("argsFormat", doc.argsFormat());
            // What the method's Javadoc says of its one String parameter is all that is declared of it.
            if (doc != null && !doc.params().isEmpty()) {
                Map.Entry<String, String> p = doc.params().entrySet().iterator().next();
                ObjectNode argument = JSON.createObjectNode();
                argument.put("name", p.getKey());
                argument.put("description", p.getValue());
                answer.set("argument", argument);
            }
            return answer;
        }
        ObjectNode merged = schema.deepCopy();
        JsonNode props = merged.get("properties");
        if (doc != null && props instanceof ObjectNode propsNode) {
            for (Map.Entry<String, String> p : doc.params().entrySet()) {
                JsonNode prop = propsNode.get(p.getKey());
                if (prop instanceof ObjectNode propNode && !p.getValue().isEmpty()) {
                    propNode.put("description", p.getValue());
                }
            }
        }
        answer.set("schema", merged);
        return answer;
    }

    /**
     * The action names a class declares with {@code @Action}, on itself, its superclasses and its
     * interfaces, without an instance. For the same caller as {@link #describe}.
     */
    public static SortedSet<String> actionNamesOf(Class<?> actorClass) {
        SortedSet<String> names = new TreeSet<>();
        Set<Class<?>> seen = new HashSet<>();
        collectActionNames(actorClass, names, seen);
        return names;
    }

    private static void collectActionNames(Class<?> c, SortedSet<String> names, Set<Class<?>> seen) {
        if (c == null || c == Object.class || !seen.add(c)) return;
        for (Method m : c.getDeclaredMethods()) {
            Action a = m.getAnnotation(Action.class);
            if (a != null) names.add(a.value());
        }
        collectActionNames(c.getSuperclass(), names, seen);
        for (Class<?> i : c.getInterfaces()) collectActionNames(i, names, seen);
    }

    /**
     * The object whose {@code @Action} methods are the actor's actions.
     *
     * <p>A reference that dispatches by action name itself — Turing-workflow's {@code IIActorRef} —
     * carries the annotations on the reference, while an ordinary actor carries them on the object
     * it holds. Asking the wrong one of the two reports no actions at all.
     */
    private Object targetOf(String actorName) {
        var ref = actorSystem.getActor(actorName);
        if (ref == null) {
            return null;
        }
        if (ref instanceof CallableByActionName) {
            return ref;
        }
        return ref.ask(a -> a).join();
    }
}
