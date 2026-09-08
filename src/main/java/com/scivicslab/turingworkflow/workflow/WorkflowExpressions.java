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
import org.apache.commons.jexl3.JexlContext;
import org.apache.commons.jexl3.JexlEngine;
import org.apache.commons.jexl3.MapContext;
import org.apache.commons.jexl3.introspection.JexlPermissions;


/**
 * Evaluates an expression with the actors of one {@link IIActorSystem} in reach.
 *
 * <p>An expression reaches an actor through {@code actors.get(name)}, which answers the object the
 * actor wraps. The object's own methods are then called, so the outcome keeps its type:
 * {@code actors.get("calc:i").get()} is a {@code double}, not the text {@code "3"}.</p>
 *
 * <h2>Why a lookup rather than names in scope</h2>
 * Actor names carry characters an expression cannot use as an identifier — {@code calc:i} has a
 * colon, {@code project1/chat-01.chat} has a slash and a dot. Putting them in scope by name is
 * therefore not possible; the lookup takes the name as text instead.
 *
 * <h2>Examples</h2>
 * <pre>{@code
 * actors.get("calc:i").get()                 // the number that actor holds
 * actors.get("calc:i").get() + 1             // arithmetic on it, still a number
 * actors.get("str:name").get()               // the text that actor holds
 * "processed: " + actors.get("str:in").get() // text built from it
 * actors.get("list:items").size()            // an int
 * result                                     // what the previous action answered
 * state.get("dir")                           // a value this actor stored
 * state.getInt("chunk.size", 0)              // the same, read as a whole number
 * self                                       // this actor's own registered name
 * currentState                               // the state the workflow is in
 * }</pre>
 *
 * @author devteam@scivicslab.com
 */
public final class WorkflowExpressions {

    /**
     * Packages whose classes an expression may call methods on.
     *
     * <p>JEXL 3.3 refuses reflection outside a permitted set, and its default set does not include
     * application classes: without this, {@code actors.get("calc:i").get()} answers {@code null}
     * and nothing says why. Composing onto {@code RESTRICTED} rather than using
     * {@code UNRESTRICTED} keeps JEXL's own refusals in place — {@code java.lang.System} stays out
     * of reach.</p>
     */
    private static final String[] PERMITTED_PACKAGES = {
        "com.scivicslab.turingworkflow.workflow.*",
        "com.scivicslab.pojoactor.*",
    };

    private static final JexlEngine JEXL = new JexlBuilder()
            .silent(false)
            .strict(true)
            .permissions(JexlPermissions.RESTRICTED.compose(PERMITTED_PACKAGES))
            .create();

    /** The name an expression uses to reach the actor lookup. */
    public static final String LOOKUP_NAME = "actors";

    /** The name an expression uses to reach the running actor's stored state. */
    public static final String STATE_NAME = "state";

    /** The name an expression uses to reach what the previous action answered. */
    public static final String RESULT_NAME = "result";

    /** The name an expression uses to reach the running actor's own registered name. */
    public static final String SELF_NAME = "self";

    /** The name an expression uses to reach the state the workflow is in. */
    public static final String CURRENT_STATE_NAME = "currentState";

    private final IIActorSystem system;
    private final IIActorRef<?> self;
    private final String currentState;

    /**
     * Builds an evaluator over one actor system, with no actor of its own.
     *
     * <p>{@code state} and {@code result} are absent from expressions built this way.</p>
     *
     * @param system the system whose actors expressions may reach; must not be {@code null}
     */
    public WorkflowExpressions(IIActorSystem system) {
        this(system, null, null);
    }

    /**
     * Builds an evaluator over one actor system, on behalf of one actor.
     *
     * @param system the system whose actors expressions may reach; must not be {@code null}
     * @param self   the actor the workflow is running as, whose stored state and previous
     *               answer expressions may reach; may be {@code null}
     */
    public WorkflowExpressions(IIActorSystem system, IIActorRef<?> self) {
        this(system, self, null);
    }

    /**
     * Builds an evaluator over one actor system, on behalf of one actor, in one state.
     *
     * @param system       the system whose actors expressions may reach; must not be {@code null}
     * @param self         the actor the workflow is running as; may be {@code null}
     * @param currentState the state the workflow is in; may be {@code null}
     */
    public WorkflowExpressions(IIActorSystem system, IIActorRef<?> self, String currentState) {
        this.system = system;
        this.self = self;
        this.currentState = currentState;
    }

    /**
     * Evaluates {@code expression}.
     *
     * @param expression the expression to evaluate
     * @return whatever it evaluates to, with its type intact; {@code null} when the expression
     *         itself evaluates to nothing
     */
    public Object evaluate(String expression) {
        JexlContext context = new MapContext();
        context.set(LOOKUP_NAME, new Lookup());
        context.set(STATE_NAME, self == null ? null : self.json());
        context.set(RESULT_NAME, self == null ? null : self.getLastResultValue());
        context.set(SELF_NAME, self == null ? null : self.getName());
        context.set(CURRENT_STATE_NAME, currentState);
        return JEXL.createExpression(expression).evaluate(context);
    }

    /**
     * Answers, for a name, the object that actor wraps.
     *
     * <p>Held as an object in the expression's scope rather than as a bare function, so that the
     * name reaching it is plain text and may contain any character.</p>
     */
    public final class Lookup {

        /**
         * @param name the actor's registered name, for example {@code "calc:i"}
         * @return the object that actor wraps, or {@code null} when no actor has that name or the
         *         actor wraps nothing
         */
        public Object get(String name) {
            IIActorRef<?> actor = system.getIIActor(name);
            return actor == null ? null : actor.wrapped();
        }

        /**
         * @param name the actor's registered name
         * @return whether an actor with that name exists
         */
        public boolean has(String name) {
            return system.hasIIActor(name);
        }
    }
}
