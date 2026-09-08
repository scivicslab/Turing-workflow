/*
 * Copyright 2025 devteam@scivicslab.com
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package com.scivicslab.turingworkflow.workflow;

import com.scivicslab.pojoactor.action.ActionResult;
import org.json.JSONArray;

/**
 * Built-in string variable actor. Auto-created on first use.
 *
 * <p>Named instances ({@code str:title}, {@code str:body}) are independent.
 * Wraps a mutable {@code String[1]} for lambda-safe mutation.</p>
 *
 * <h2>Actions</h2>
 * <ul>
 *   <li>{@code set} — set the stored string</li>
 *   <li>{@code get} — read the stored string</li>
 *   <li>{@code clear} — reset to empty string</li>
 *   <li>{@code append} — append text to the stored string</li>
 *   <li>{@code length} — character count</li>
 *   <li>{@code trim} — remove leading/trailing whitespace from stored string</li>
 *   <li>{@code toUpperCase} — convert stored string to uppercase</li>
 *   <li>{@code toLowerCase} — convert stored string to lowercase</li>
 *   <li>{@code contains} — true if stored string contains argument</li>
 *   <li>{@code startsWith} — true if stored string starts with argument</li>
 *   <li>{@code endsWith} — true if stored string ends with argument</li>
 *   <li>{@code replace} — replace occurrences ([target, replacement])</li>
 *   <li>{@code substring} — extract substring ([start] or [start, end])</li>
 *   <li>{@code isEmpty} — true if stored string is empty</li>
 *   <li>{@code escapeJson} — JSON-escape a string (argument); does not modify stored value</li>
 *   <li>{@code escapeJsonStored} — JSON-escape the stored string in-place</li>
 * </ul>
 */
public class StringActor extends IIActorRef<TextSlot> {

    public StringActor(String name, IIActorSystem system) {
        super(name, new TextSlot(), system);
    }

    @Override
    public ActionResult callByActionName(String actionName, String args) {
        String arg = parseFirstArgument(args);
        return switch (actionName) {
            case "set"             -> set(arg);
            case "get"             -> get();
            case "clear"           -> clear();
            case "append"          -> append(arg);
            case "length"          -> length();
            case "trim"            -> trim();
            case "toUpperCase"     -> toUpperCase();
            case "toLowerCase"     -> toLowerCase();
            case "contains"        -> contains(arg);
            case "startsWith"      -> startsWith(arg);
            case "endsWith"        -> endsWith(arg);
            case "replace"         -> replace(args);
            case "substring"       -> substring(args);
            case "isEmpty"         -> isEmpty();
            case "escapeJson"      -> escapeJson(arg);
            case "escapeJsonStored"-> escapeJsonStored();
            default                -> super.callByActionName(actionName, args);
        };
    }

    private ActionResult set(String value) {
        object.set(value);
        return new ActionResult(true, object.get());
    }

    private ActionResult get() {
        return new ActionResult(true, object.get());
    }

    private ActionResult clear() {
        object.clear();
        return new ActionResult(true, "");
    }

    private ActionResult append(String value) {
        object.append(value);
        return new ActionResult(true, object.get());
    }

    private ActionResult length() {
        return new ActionResult(true, String.valueOf(object.length()));
    }

    private ActionResult trim() {
        object.trim();
        return new ActionResult(true, object.get());
    }

    private ActionResult toUpperCase() {
        object.toUpperCase();
        return new ActionResult(true, object.get());
    }

    private ActionResult toLowerCase() {
        object.toLowerCase();
        return new ActionResult(true, object.get());
    }

    private ActionResult contains(String value) {
        return new ActionResult(true, String.valueOf(object.contains(value)));
    }

    private ActionResult startsWith(String prefix) {
        return new ActionResult(true, String.valueOf(object.startsWith(prefix)));
    }

    private ActionResult endsWith(String suffix) {
        return new ActionResult(true, String.valueOf(object.endsWith(suffix)));
    }

    private ActionResult replace(String args) {
        try {
            JSONArray arr = new JSONArray(args);
            String target = arr.getString(0);
            String replacement = arr.getString(1);
            object.replace(target, replacement);
            return new ActionResult(true, object.get());
        } catch (Exception e) {
            return new ActionResult(false, "str.replace: expected [target, replacement]: " + e.getMessage());
        }
    }

    private ActionResult substring(String args) {
        try {
            JSONArray arr = new JSONArray(args);
            int start = arr.getInt(0);
            if (arr.length() >= 2) {
                int end = arr.getInt(1);
                return new ActionResult(true, object.substring(start, end));
            }
            return new ActionResult(true, object.substring(start));
        } catch (Exception e) {
            return new ActionResult(false, "str.substring: expected [start] or [start, end]: " + e.getMessage());
        }
    }

    private ActionResult isEmpty() {
        return new ActionResult(true, String.valueOf(object.isEmpty()));
    }

    private ActionResult escapeJson(String value) {
        if (value == null) value = "";
        return new ActionResult(true, TextSlot.escapeForJson(value));
    }

    private ActionResult escapeJsonStored() {
        object.escapeJson();
        return new ActionResult(true, object.get());
    }

}
