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

    @Action("print")
    public ActionResult print(String args) {
        String msg = parseFirstArgument(args);
        System.out.println(msg);
        return new ActionResult(true, msg);
    }

    @Action("error")
    public ActionResult error(String args) {
        String msg = parseFirstArgument(args);
        System.err.println(msg);
        return new ActionResult(true, msg);
    }

    @Action("printf")
    public ActionResult printf(String args) {
        try {
            org.json.JSONArray arr = new org.json.JSONArray(args);
            String fmt = arr.getString(0);
            Object[] params = new Object[arr.length() - 1];
            for (int i = 1; i < arr.length(); i++) {
                params[i - 1] = arr.get(i);
            }
            String result = String.format(fmt, params);
            System.out.print(result);
            return new ActionResult(true, result);
        } catch (Exception e) {
            return new ActionResult(false, "out.printf: " + e.getMessage());
        }
    }
}
