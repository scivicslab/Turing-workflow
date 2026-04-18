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

import com.scivicslab.pojoactor.core.ActionResult;
import org.json.JSONArray;

import java.util.ArrayList;
import java.util.List;

/**
 * Built-in string list actor. Auto-created on first use.
 *
 * <p>Named instances ({@code list:files}, {@code list:errors}) are independent.
 * Wraps {@code ArrayList<String>}.</p>
 *
 * <h2>Actions</h2>
 * <ul>
 *   <li>{@code add} — append element</li>
 *   <li>{@code get} — get element by index</li>
 *   <li>{@code set} — replace element at index ([index, value])</li>
 *   <li>{@code remove} — remove element by index</li>
 *   <li>{@code size} — list length</li>
 *   <li>{@code isEmpty} — true if empty</li>
 *   <li>{@code clear} — remove all elements</li>
 *   <li>{@code contains} — true if element exists</li>
 *   <li>{@code indexOf} — index of element (-1 if not found)</li>
 *   <li>{@code join} — join with separator</li>
 * </ul>
 */
public class ListActor extends IIActorRef<List<String>> {

    public ListActor(String name, IIActorSystem system) {
        super(name, new ArrayList<>(), system);
    }

    @Override
    public ActionResult callByActionName(String actionName, String args) {
        String arg = parseFirstArgument(args);
        return switch (actionName) {
            case "add"      -> add(arg);
            case "get"      -> get(arg);
            case "set"      -> set(args);
            case "remove"   -> remove(arg);
            case "size"     -> size();
            case "isEmpty"  -> isEmpty();
            case "clear"    -> clear();
            case "contains" -> contains(arg);
            case "indexOf"  -> indexOf(arg);
            case "join"     -> join(arg);
            default         -> super.callByActionName(actionName, args);
        };
    }

    private ActionResult add(String value) {
        object.add(value);
        return new ActionResult(true, "true");
    }

    private ActionResult get(String indexStr) {
        try {
            int i = (indexStr == null || indexStr.isBlank()) ? 0 : Integer.parseInt(indexStr.trim());
            if (i < 0 || i >= object.size()) {
                return new ActionResult(false,
                        "list.get: index " + i + " out of range (size=" + object.size() + ")");
            }
            return new ActionResult(true, object.get(i));
        } catch (NumberFormatException e) {
            return new ActionResult(false, "list.get: invalid index: " + indexStr);
        }
    }

    private ActionResult set(String args) {
        try {
            JSONArray arr = new JSONArray(args);
            int i = arr.getInt(0);
            String value = arr.getString(1);
            if (i < 0 || i >= object.size()) {
                return new ActionResult(false,
                        "list.set: index " + i + " out of range (size=" + object.size() + ")");
            }
            String old = object.set(i, value);
            return new ActionResult(true, old);
        } catch (Exception e) {
            return new ActionResult(false, "list.set: expected [index, value]: " + e.getMessage());
        }
    }

    private ActionResult remove(String indexStr) {
        try {
            int i = Integer.parseInt(indexStr.trim());
            if (i < 0 || i >= object.size()) {
                return new ActionResult(false,
                        "list.remove: index " + i + " out of range (size=" + object.size() + ")");
            }
            return new ActionResult(true, object.remove(i));
        } catch (NumberFormatException e) {
            return new ActionResult(false, "list.remove: invalid index: " + indexStr);
        }
    }

    private ActionResult size() {
        return new ActionResult(true, String.valueOf(object.size()));
    }

    private ActionResult isEmpty() {
        return new ActionResult(true, String.valueOf(object.isEmpty()));
    }

    private ActionResult clear() {
        object.clear();
        return new ActionResult(true, "cleared");
    }

    private ActionResult contains(String value) {
        return new ActionResult(true, String.valueOf(object.contains(value)));
    }

    private ActionResult indexOf(String value) {
        return new ActionResult(true, String.valueOf(object.indexOf(value)));
    }

    private ActionResult join(String separator) {
        String sep = (separator == null || separator.isEmpty()) ? ", " : separator;
        return new ActionResult(true, String.join(sep, object));
    }
}
