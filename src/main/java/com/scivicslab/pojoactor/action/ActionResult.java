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

package com.scivicslab.pojoactor.action;

/**
 * Represents the result of executing an action on an actor.
 *
 * <p>This class encapsulates the outcome of an action execution, including
 * whether it succeeded and any result message or data. It is primarily used
 * with {@link CallableByActionName} for string-based method invocation.</p>
 *
 * <h2>Usage Example</h2>
 * <pre>{@code
 * public class MathPlugin implements CallableByActionName {
 *     @Override
 *     public ActionResult callByActionName(String actionName, String args) {
 *         if ("add".equals(actionName)) {
 *             String[] parts = args.split(",");
 *             int result = Integer.parseInt(parts[0]) + Integer.parseInt(parts[1]);
 *             return new ActionResult(true, String.valueOf(result));
 *         }
 *         return new ActionResult(false, "Unknown action: " + actionName);
 *     }
 * }
 * }</pre>
 *
 * @author devteam@scivicslab.com
 * @since 2.0.0
 * @since 2.0.0
 * @see CallableByActionName
 */
public class ActionResult {

    private final boolean success;
    private final String result;

    /**
     * Constructs a new ActionResult.
     *
     * @param success {@code true} if the action completed successfully, {@code false} otherwise
     * @param result a message or data describing the action result
     */
    public ActionResult(boolean success, String result) {
        this.success = success;
        this.result = result;
    }

    /**
     * Returns whether the action completed successfully.
     *
     * @return {@code true} if successful, {@code false} otherwise
     */
    public boolean isSuccess() {
        return success;
    }

    /**
     * Returns the result message or data.
     *
     * @return the result string
     */
    public String getResult() {
        return result;
    }

    /**
     * The failed result for an action that ended with an exception.
     *
     * <p>A failed action is false, and the workflow reads why in {@code ${result}}, so the reason
     * must say what went wrong. Every place that turns an exception into a failed result builds it
     * here, in one fixed form:</p>
     *
     * <pre>error: java.net.ConnectException: Connection refused (in net.connect)</pre>
     *
     * <p>The exception named is the root one: an {@link java.util.concurrent.ExecutionException},
     * {@link java.util.concurrent.CompletionException} or
     * {@link java.lang.reflect.InvocationTargetException} only carries the action's own
     * exception, and is unwrapped. An exception without a message is named alone. A workflow tells
     * an error from an expected false by the {@code error:} prefix.</p>
     *
     * @param failure what the action threw
     * @param where   where it happened, usually {@code actor.method}; may add a hint, such as the
     *                arguments expected
     * @return a failed result whose text is the reason
     */
    public static ActionResult error(Throwable failure, String where) {
        return new ActionResult(false, errorText(failure, where));
    }

    /**
     * The reason text {@link #error} puts in a failed result, for a caller that builds its own.
     *
     * @param failure what was thrown
     * @param where   where it happened
     * @return {@code error: <class>: <message> (in <where>)}
     */
    public static String errorText(Throwable failure, String where) {
        Throwable root = rootOf(failure);
        StringBuilder text = new StringBuilder("error: ");
        if (root == null) {
            text.append("unknown error");
        } else {
            text.append(root.getClass().getName());
            if (root.getMessage() != null && !root.getMessage().isEmpty()) {
                text.append(": ").append(root.getMessage());
            }
        }
        if (where != null && !where.isEmpty()) {
            text.append(" (in ").append(where).append(')');
        }
        return text.toString();
    }

    /** Unwraps the exceptions that only carry another one. */
    private static Throwable rootOf(Throwable failure) {
        Throwable current = failure;
        while (current != null && current.getCause() != null
                && (current instanceof java.util.concurrent.ExecutionException
                    || current instanceof java.util.concurrent.CompletionException
                    || current instanceof java.lang.reflect.InvocationTargetException)) {
            current = current.getCause();
        }
        return current;
    }

    @Override
    public String toString() {
        return "ActionResult{success=" + success + ", result='" + result + "'}";
    }
}
