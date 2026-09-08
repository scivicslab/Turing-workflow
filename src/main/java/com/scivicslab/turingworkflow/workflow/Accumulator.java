/*
 * Copyright 2026 devteam@scivicslab.com
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

package com.scivicslab.turingworkflow.workflow;

import org.apache.commons.jexl3.JexlBuilder;
import org.apache.commons.jexl3.JexlEngine;
import org.apache.commons.jexl3.MapContext;

/**
 * One number a workflow can read, change and compare.
 *
 * <p>Every method returns or takes {@code double}, so a caller that reaches this object through an
 * expression receives a number rather than its printed form. {@code CalcActor} wraps this and
 * carries the workflow vocabulary.</p>
 *
 * <p>Not thread-safe on its own. The actor that wraps it serialises access.</p>
 *
 * @author devteam@scivicslab.com
 */
public class Accumulator {

    private static final JexlEngine JEXL = new JexlBuilder().create();

    private double value;

    /** Starts at zero. */
    public Accumulator() {
        this(0.0);
    }

    /**
     * Starts at a given number.
     *
     * @param value the number to start from
     */
    public Accumulator(double value) {
        this.value = value;
    }

    /** @return the number held now */
    public double get() {
        return value;
    }

    /**
     * Replaces the number held.
     *
     * @param value the number to hold
     * @return the number now held
     */
    public double set(double value) {
        this.value = value;
        return this.value;
    }

    /** @return the number after adding one */
    public double increment() {
        return ++value;
    }

    /** @return the number after subtracting one */
    public double decrement() {
        return --value;
    }

    /** @return the number after setting it back to zero */
    public double reset() {
        value = 0.0;
        return value;
    }

    /**
     * Applies an arithmetic operation between the number held and {@code operand}.
     *
     * @param operand the other number
     * @param op      one of {@code + - * / %}
     * @return the number now held
     * @throws IllegalArgumentException if {@code op} is not one of those five
     */
    public double apply(double operand, char op) {
        value = switch (op) {
            case '+' -> value + operand;
            case '-' -> value - operand;
            case '*' -> value * operand;
            case '/' -> value / operand;
            case '%' -> value % operand;
            default  -> throw new IllegalArgumentException("unknown operator: " + op);
        };
        return value;
    }

    /**
     * Compares the number held against {@code operand}.
     *
     * @param operand the other number
     * @param op      {@code '<'} less, {@code 'L'} less-or-equal, {@code '>'} greater,
     *                {@code 'G'} greater-or-equal, {@code '='} equal
     * @return the outcome of the comparison
     * @throws IllegalArgumentException if {@code op} is not one of those five
     */
    public boolean compare(double operand, char op) {
        return switch (op) {
            case '<' -> value <  operand;
            case 'L' -> value <= operand;
            case '>' -> value >  operand;
            case 'G' -> value >= operand;
            case '=' -> value == operand;
            default  -> throw new IllegalArgumentException("unknown operator: " + op);
        };
    }

    /**
     * Evaluates an arithmetic expression and holds the outcome.
     *
     * <p>{@code v} names the number held now, so {@code "v * 2 + 1"} works. {@code value} is
     * accepted as well.</p>
     *
     * @param expression the expression, in the syntax the JEXL engine accepts
     * @return the number now held
     */
    public double evaluate(String expression) {
        var context = new MapContext();
        context.set("v", value);
        context.set("value", value);
        Object outcome = JEXL.createExpression(expression).evaluate(context);
        value = outcome instanceof Number n ? n.doubleValue() : Double.parseDouble(String.valueOf(outcome));
        return value;
    }

    /**
     * Prints the number the way workflows expect: whole numbers without a trailing {@code .0}.
     *
     * @return the number as text
     */
    @Override
    public String toString() {
        return value == Math.rint(value) && !Double.isInfinite(value)
             ? String.valueOf((long) value)
             : String.valueOf(value);
    }
}
