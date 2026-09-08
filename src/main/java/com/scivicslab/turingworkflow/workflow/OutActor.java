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

import java.util.List;

/**
 * Built-in print output actor. Auto-created on first use.
 *
 * <h2>Actions</h2>
 * <ul>
 *   <li>{@code print} — print message to stdout with newline</li>
 *   <li>{@code error} — print message to stderr with newline</li>
 *   <li>{@code printf} — print formatted message (Java String.format)</li>
 * </ul>
 */
public class OutActor extends IIActorRef<Void> {

    public OutActor(String name, IIActorSystem system) {
        super(name, null, system);
    }

    /**
     * One line of text to write out.
     *
     * @param message the text to write
     */
    public record MessageArgs(@NotNull String message) {}

    /**
     * A format string and the values it consumes.
     *
     * @param format the {@link String#format} pattern
     * @param params the values substituted into it; may be omitted when the pattern takes none
     */
    public record PrintfArgs(@NotNull String format, List<Object> params) {}

    @Action(value = "print", argsType = MessageArgs.class)
    public ActionResult print(MessageArgs args) {
        String msg = args.message();
        System.out.println(msg);
        return new ActionResult(true, msg);
    }

    @Action(value = "error", argsType = MessageArgs.class)
    public ActionResult error(MessageArgs args) {
        String msg = args.message();
        System.err.println(msg);
        return new ActionResult(true, msg);
    }

    @Action(value = "printf", argsType = PrintfArgs.class)
    public ActionResult printf(PrintfArgs args) {
        try {
            List<Object> values = args.params() == null ? List.of() : args.params();
            String result = String.format(args.format(), values.toArray());
            System.out.print(result);
            return new ActionResult(true, result);
        } catch (Exception e) {
            return new ActionResult(false, "out.printf: " + e.getMessage());
        }
    }
}
