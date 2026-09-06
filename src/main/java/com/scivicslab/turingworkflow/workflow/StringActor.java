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
public class StringActor extends IIActorRef<String[]> {

    public StringActor(String name, IIActorSystem system) {
        super(name, new String[]{""}, system);
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
        object[0] = value == null ? "" : value;
        return new ActionResult(true, object[0]);
    }

    private ActionResult get() {
        return new ActionResult(true, object[0]);
    }

    private ActionResult clear() {
        object[0] = "";
        return new ActionResult(true, "");
    }

    private ActionResult append(String value) {
        object[0] = object[0] + (value == null ? "" : value);
        return new ActionResult(true, object[0]);
    }

    private ActionResult length() {
        return new ActionResult(true, String.valueOf(object[0].length()));
    }

    private ActionResult trim() {
        object[0] = object[0].trim();
        return new ActionResult(true, object[0]);
    }

    private ActionResult toUpperCase() {
        object[0] = object[0].toUpperCase();
        return new ActionResult(true, object[0]);
    }

    private ActionResult toLowerCase() {
        object[0] = object[0].toLowerCase();
        return new ActionResult(true, object[0]);
    }

    private ActionResult contains(String value) {
        return new ActionResult(true, String.valueOf(object[0].contains(value == null ? "" : value)));
    }

    private ActionResult startsWith(String prefix) {
        return new ActionResult(true, String.valueOf(object[0].startsWith(prefix == null ? "" : prefix)));
    }

    private ActionResult endsWith(String suffix) {
        return new ActionResult(true, String.valueOf(object[0].endsWith(suffix == null ? "" : suffix)));
    }

    private ActionResult replace(String args) {
        try {
            JSONArray arr = new JSONArray(args);
            String target = arr.getString(0);
            String replacement = arr.getString(1);
            object[0] = object[0].replace(target, replacement);
            return new ActionResult(true, object[0]);
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
                return new ActionResult(true, object[0].substring(start, end));
            }
            return new ActionResult(true, object[0].substring(start));
        } catch (Exception e) {
            return new ActionResult(false, "str.substring: expected [start] or [start, end]: " + e.getMessage());
        }
    }

    private ActionResult isEmpty() {
        return new ActionResult(true, String.valueOf(object[0].isEmpty()));
    }

    private ActionResult escapeJson(String value) {
        if (value == null) value = "";
        return new ActionResult(true, escapeForJson(value));
    }

    private ActionResult escapeJsonStored() {
        object[0] = escapeForJson(object[0]);
        return new ActionResult(true, object[0]);
    }

    private static String escapeForJson(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"'  -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }
}
