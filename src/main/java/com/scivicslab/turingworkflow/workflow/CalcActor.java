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
public class CalcActor extends IIActorRef<Accumulator> {

    public CalcActor(String name, IIActorSystem system) {
        super(name, new Accumulator(), system);
    }

    /**
     * A number to store as the accumulator's value.
     *
     * <p>A workflow that needs the number from elsewhere writes it as an expression —
     * {@code value: "jexl: actors.get('calc:other').get()"} — which arrives as a number.</p>
     *
     * @param value the number to store
     */
    public record ValueArgs(@NotNull Double value) {}

    /**
     * The other number in an arithmetic or comparison action.
     *
     * @param operand the number the accumulator is combined with or compared against
     */
    public record OperandArgs(@NotNull Double operand) {}

    /**
     * An arithmetic expression to evaluate.
     *
     * @param expression the expression, in the syntax the JEXL engine accepts
     */
    public record ExpressionArgs(@NotNull String expression) {}

    @Action(value = "set", argsType = ValueArgs.class)
    public ActionResult set(ValueArgs args) {
        object.set(args.value());
        return new ActionResult(true, object.toString());
    }

    @Action("get")
    public ActionResult get(String args) {
        return new ActionResult(true, object.toString());
    }

    @Action("inc")
    public ActionResult inc(String args) {
        return new ActionResult(true, fmt(object.increment()));
    }

    @Action("dec")
    public ActionResult dec(String args) {
        return new ActionResult(true, fmt(object.decrement()));
    }

    @Action(value = "add", argsType = OperandArgs.class)
    public ActionResult add(OperandArgs args) {
        return arithmetic(args.operand(), '+');
    }

    @Action(value = "sub", argsType = OperandArgs.class)
    public ActionResult sub(OperandArgs args) {
        return arithmetic(args.operand(), '-');
    }

    @Action(value = "mul", argsType = OperandArgs.class)
    public ActionResult mul(OperandArgs args) {
        return arithmetic(args.operand(), '*');
    }

    @Action(value = "div", argsType = OperandArgs.class)
    public ActionResult div(OperandArgs args) {
        return arithmetic(args.operand(), '/');
    }

    @Action(value = "mod", argsType = OperandArgs.class)
    public ActionResult mod(OperandArgs args) {
        return arithmetic(args.operand(), '%');
    }

    @Action("reset")
    public ActionResult reset(String args) {
        object.reset();
        return new ActionResult(true, "0");
    }

    @Action(value = "eval", argsType = ExpressionArgs.class)
    public ActionResult eval(ExpressionArgs args) {
        try {
            object.evaluate(args.expression());
            return new ActionResult(true, object.toString());
        } catch (Exception e) {
            return new ActionResult(false, "calc.eval: " + e.getMessage());
        }
    }

    /** Returns success=true if value &lt; arg; success=false otherwise. */
    @Action(value = "lt", argsType = OperandArgs.class)
    public ActionResult lt(OperandArgs args) { return compare(args.operand(), '<'); }

    /** Returns success=true if value &lt;= arg; success=false otherwise. */
    @Action(value = "lte", argsType = OperandArgs.class)
    public ActionResult lte(OperandArgs args) { return compare(args.operand(), 'L'); }

    /** Returns success=true if value &gt; arg; success=false otherwise. */
    @Action(value = "gt", argsType = OperandArgs.class)
    public ActionResult gt(OperandArgs args) { return compare(args.operand(), '>'); }

    /** Returns success=true if value &gt;= arg; success=false otherwise. */
    @Action(value = "gte", argsType = OperandArgs.class)
    public ActionResult gte(OperandArgs args) { return compare(args.operand(), 'G'); }

    /** Returns success=true if value == arg; success=false otherwise. */
    @Action(value = "eq", argsType = OperandArgs.class)
    public ActionResult eq(OperandArgs args) { return compare(args.operand(), '='); }

    private ActionResult compare(double operand, char op) {
        return new ActionResult(object.compare(operand, op), object.toString());
    }

    private ActionResult arithmetic(double operand, char op) {
        object.apply(operand, op);
        return new ActionResult(true, object.toString());
    }

    private static String fmt(double v) {
        return format(v);
    }

    private static String format(double v) {
        // Return integer string when value is whole number
        if (v == Math.floor(v) && !Double.isInfinite(v)) {
            return String.valueOf((long) v);
        }
        return String.valueOf(v);
    }
}
