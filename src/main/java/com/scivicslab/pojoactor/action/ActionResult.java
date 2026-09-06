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

    @Override
    public String toString() {
        return "ActionResult{success=" + success + ", result='" + result + "'}";
    }
}
