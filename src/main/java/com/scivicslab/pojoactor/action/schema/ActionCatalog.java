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

    /**
     * @param actorSystem    the system whose actors are described — the same one this is
     *                       registered in, so that an actor's name resolves the way a caller's
     *                       call would resolve it
     * @param schemaRegistry the schemas loaded from the classpath
     */
    public ActionCatalog(ActorSystem actorSystem, ActionSchemaRegistry schemaRegistry) {
        this.actorSystem = actorSystem;
        this.schemaRegistry = schemaRegistry;
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
        ObjectNode answer = JSON.createObjectNode();
        answer.put("actor", actorName);
        answer.put("action", action);
        JsonNode schema = schemaRegistry.schemaFor(actor.getClass(), action);
        answer.set("schema", schema == null ? JSON.nullNode() : schema);
        if (schema == null) {
            // Not an error: most actions declare no argsType and parse a raw String themselves.
            // Saying so keeps a caller from reading an absent schema as "takes no arguments".
            answer.put("note", "This action takes a raw String; its shape is not declared.");
        }
        return new ActionResult(true, JSON.writeValueAsString(answer));
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
