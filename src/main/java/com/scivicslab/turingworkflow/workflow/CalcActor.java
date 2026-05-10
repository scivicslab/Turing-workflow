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

import com.scivicslab.pojoactor.core.Action;
import com.scivicslab.pojoactor.core.ActionResult;
import org.apache.commons.jexl3.*;

/**
 * Built-in numeric variable actor. Auto-created on first use.
 *
 * <p>Named instances ({@code calc:x}, {@code calc:y}) are independent.
 * All arithmetic methods return the new value as a string.</p>
 *
 * <h2>Actions</h2>
 * <ul>
 *   <li>{@code set} — set value</li>
 *   <li>{@code get} — read value</li>
 *   <li>{@code inc} — increment by 1</li>
 *   <li>{@code dec} — decrement by 1</li>
 *   <li>{@code add} / {@code sub} / {@code mul} / {@code div} / {@code mod} — arithmetic</li>
 *   <li>{@code reset} — reset to 0</li>
 *   <li>{@code eval} — evaluate JEXL expression; {@code v} = current value</li>
 * </ul>
 */
public class CalcActor extends IIActorRef<double[]> {

    private static final JexlEngine JEXL = new JexlBuilder().silent(false).strict(true).create();

    public CalcActor(String name, IIActorSystem system) {
        super(name, new double[]{0.0}, system);
    }

    @Action("set")
    public ActionResult set(String args) {
        String arg = parseFirstArgument(args);
        try {
            object[0] = Double.parseDouble(arg.trim());
            return new ActionResult(true, format(object[0]));
        } catch (NumberFormatException e) {
            return new ActionResult(false, "calc.set: invalid number: " + arg);
        }
    }

    @Action("get")
    public ActionResult get(String args) {
        return new ActionResult(true, format(object[0]));
    }

    @Action("inc")
    public ActionResult inc(String args) {
        return new ActionResult(true, format(++object[0]));
    }

    @Action("dec")
    public ActionResult dec(String args) {
        return new ActionResult(true, format(--object[0]));
    }

    @Action("add")
    public ActionResult add(String args) {
        return arithmetic(parseFirstArgument(args), '+');
    }

    @Action("sub")
    public ActionResult sub(String args) {
        return arithmetic(parseFirstArgument(args), '-');
    }

    @Action("mul")
    public ActionResult mul(String args) {
        return arithmetic(parseFirstArgument(args), '*');
    }

    @Action("div")
    public ActionResult div(String args) {
        return arithmetic(parseFirstArgument(args), '/');
    }

    @Action("mod")
    public ActionResult mod(String args) {
        return arithmetic(parseFirstArgument(args), '%');
    }

    @Action("reset")
    public ActionResult reset(String args) {
        object[0] = 0.0;
        return new ActionResult(true, "0");
    }

    @Action("eval")
    public ActionResult eval(String args) {
        String expression = parseFirstArgument(args);
        try {
            JexlContext ctx = new MapContext();
            ctx.set("v", object[0]);
            Object result = JEXL.createExpression(expression).evaluate(ctx);
            if (result instanceof Number n) {
                object[0] = n.doubleValue();
                return new ActionResult(true, format(object[0]));
            }
            return new ActionResult(true, String.valueOf(result));
        } catch (Exception e) {
            return new ActionResult(false, "calc.eval: " + e.getMessage());
        }
    }

    private ActionResult arithmetic(String arg, char op) {
        try {
            double n = Double.parseDouble(arg.trim());
            object[0] = switch (op) {
                case '+' -> object[0] + n;
                case '-' -> object[0] - n;
                case '*' -> object[0] * n;
                case '/' -> object[0] / n;
                case '%' -> object[0] % n;
                default  -> object[0];
            };
            return new ActionResult(true, format(object[0]));
        } catch (NumberFormatException e) {
            return new ActionResult(false, "calc." + op + ": invalid number: " + arg);
        }
    }

    private static String format(double v) {
        // Return integer string when value is whole number
        if (v == Math.floor(v) && !Double.isInfinite(v)) {
            return String.valueOf((long) v);
        }
        return String.valueOf(v);
    }
}
