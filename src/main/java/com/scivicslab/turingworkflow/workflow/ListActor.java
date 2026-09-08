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

import com.scivicslab.pojoactor.action.Action;
import com.scivicslab.pojoactor.action.ActionResult;

import jakarta.validation.constraints.NotNull;
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

    /**
     * An element's text.
     *
     * @param value the text to add, look for, or store
     */
    public record ValueArgs(@NotNull String value) {}

    /**
     * A position in the list.
     *
     * <p>A workflow that needs the position from elsewhere writes it as an expression —
     * {@code index: "jexl: actors.get('calc:i').get()"} — which arrives as a number.</p>
     *
     * @param index zero-based position
     */
    public record IndexArgs(@NotNull Integer index) {}

    /**
     * Which position to overwrite, and with what.
     *
     * @param index zero-based position
     * @param value the text to store there
     */
    public record SetArgs(@NotNull Integer index, @NotNull String value) {}

    /**
     * What to put between elements when joining them.
     *
     * @param separator the text placed between elements
     */
    public record SeparatorArgs(@NotNull String separator) {}

    @Action(value = "add", argsType = ValueArgs.class)
    public ActionResult add(ValueArgs args) {
        String value = args.value();
        object.add(value);
        return new ActionResult(true, "true");
    }

    @Action(value = "get", argsType = IndexArgs.class)
    public ActionResult get(IndexArgs args) {
        int i = args.index();
        if (i < 0 || i >= object.size()) {
            return new ActionResult(false,
                    "list.get: index " + i + " out of range (size=" + object.size() + ")");
        }
        return new ActionResult(true, object.get(i));
    }

    @Action(value = "set", argsType = SetArgs.class)
    public ActionResult set(SetArgs args) {
        int i = args.index();
        if (i < 0 || i >= object.size()) {
            return new ActionResult(false,
                    "list.set: index " + i + " out of range (size=" + object.size() + ")");
        }
        return new ActionResult(true, object.set(i, args.value()));
    }

    @Action(value = "remove", argsType = IndexArgs.class)
    public ActionResult remove(IndexArgs args) {
        int i = args.index();
        if (i < 0 || i >= object.size()) {
            return new ActionResult(false,
                    "list.remove: index " + i + " out of range (size=" + object.size() + ")");
        }
        return new ActionResult(true, object.remove(i));
    }

    @Action("size")
    public ActionResult size(String args) {
        return new ActionResult(true, String.valueOf(object.size()));
    }

    @Action("isEmpty")
    public ActionResult isEmpty(String args) {
        return new ActionResult(true, String.valueOf(object.isEmpty()));
    }

    @Action("clear")
    public ActionResult clear(String args) {
        object.clear();
        return new ActionResult(true, "cleared");
    }

    @Action(value = "contains", argsType = ValueArgs.class)
    public ActionResult contains(ValueArgs args) {
        String value = args.value();
        return new ActionResult(true, String.valueOf(object.contains(value)));
    }

    @Action(value = "indexOf", argsType = ValueArgs.class)
    public ActionResult indexOf(ValueArgs args) {
        String value = args.value();
        return new ActionResult(true, String.valueOf(object.indexOf(value)));
    }

    @Action(value = "join", argsType = SeparatorArgs.class)
    public ActionResult join(SeparatorArgs args) {
        String separator = args.separator();
        String sep = (separator == null || separator.isEmpty()) ? ", " : separator;
        return new ActionResult(true, String.join(sep, object));
    }
}
