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

package com.scivicslab.turingworkflow.workflow;

import com.scivicslab.pojoactor.action.ActionResult;

/**
 * The reason a workflow reads when it uses something removed in 5.0.0.
 *
 * <p>A workflow written for 4.x may still name a removed built-in actor or call a removed action.
 * The action is false, and the next transition reads in {@code jexl: result} what was removed and
 * what to write instead, always in one form:</p>
 *
 * <pre>error: &lt;what&gt; was removed in 5.0.0; &lt;what to write instead&gt; (in &lt;where&gt;)</pre>
 *
 * <p>Every removal builds its reason here, so they all read alike.</p>
 */
public final class Removal {

    private Removal() {
    }

    /**
     * Why a workflow whose top level has {@code transitions:} is refused when it is loaded. The key
     * was accepted in place of {@code steps:} until 4.x.
     */
    public static final String TRANSITIONS_KEY = reason("the top-level key 'transitions:'", "rename it to 'steps:'");

    /**
     * Refuses a workflow, as it was read from YAML, whose top level still has the removed
     * {@code transitions:} key, also when it has {@code steps:} as well.
     *
     * @param workflow the workflow's top-level map; {@code null} is let through
     * @throws IllegalArgumentException with {@link #TRANSITIONS_KEY} when the key is there
     */
    public static void refuseTransitionsKey(java.util.Map<String, Object> workflow) {
        if (workflow != null && workflow.containsKey("transitions")) {
            throw new IllegalArgumentException(TRANSITIONS_KEY);
        }
    }

    /**
     * Why a workflow with an action written with the singular {@code argument:} key is refused when
     * it is loaded. The engine only ever read {@code arguments:}, so until 4.x the singular key was
     * dropped and the action was called with no arguments.
     */
    public static final String ARGUMENT_KEY = reason("the step key 'argument:'", "write 'arguments:'");

    /**
     * Refuses an action, as it was read from YAML, written with the singular {@code argument:} key.
     *
     * @param action the action's map; {@code null} is let through
     * @throws IllegalArgumentException with {@link #ARGUMENT_KEY} when the key is there
     */
    public static void refuseArgumentKey(java.util.Map<String, Object> action) {
        if (action != null && action.containsKey("argument")) {
            throw new IllegalArgumentException(ARGUMENT_KEY);
        }
    }

    /**
     * Returns the reason, without the {@code error:} prefix or the place.
     *
     * @param what    what was removed, for example {@code "the built-in actor 'calc:i'"}
     * @param instead what to write instead, for example {@code "keep the value in ... instead: ..."}
     * @return {@code <what> was removed in 5.0.0; <instead>}
     */
    public static String reason(String what, String instead) {
        return what + " was removed in 5.0.0; " + instead;
    }

    /**
     * Returns the failed result of an action that used something removed.
     *
     * @param reason the reason, from {@link #reason}
     * @param where  where it was used, usually {@code actor.method}
     * @return a false result whose text is {@code error: <reason> (in <where>)}
     */
    public static ActionResult failure(String reason, String where) {
        return new ActionResult(false, "error: " + reason + " (in " + where + ")");
    }
}
