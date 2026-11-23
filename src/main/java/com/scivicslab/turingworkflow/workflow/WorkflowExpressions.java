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

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import org.apache.commons.jexl3.JexlBuilder;
import org.apache.commons.jexl3.JexlContext;
import org.apache.commons.jexl3.JexlEngine;
import org.apache.commons.jexl3.JexlFeatures;
import org.apache.commons.jexl3.MapContext;
import org.apache.commons.jexl3.introspection.JexlPermissions;

import com.fasterxml.jackson.databind.JsonNode;
import com.scivicslab.pojoactor.core.ActorRef;
import com.scivicslab.pojoactor.core.JsonState;


/**
 * Evaluates an expression with the actors of one {@link IIActorSystem} in reach.
 *
 * <p>An expression reads values and keeps their type: {@code state.getInt("i", 0)} is an
 * {@code int}, not the text {@code "3"}. Another actor is read through {@code actors.get(name)},
 * which answers an {@link ActorView}.</p>
 *
 * <h2>What an expression may do</h2>
 * By default an expression has the reach of Java: it may create objects with {@code new}, read and
 * write files, and call any public method of any class, as the code of the host can
 * ({@link JexlPermissions#UNRESTRICTED}, with all of JEXL's features). A workflow is a program, and
 * the person who writes it may use what Java can do. JEXL 3.7.1's {@code RESTRICTED} set, which this
 * library used before the allow-list, no longer allows file streams, so the reach of Java needs the
 * unrestricted set.
 *
 * <p>A host that runs workflows it does not trust limits expressions to an allow-list, with
 * {@link IIActorSystem#setExpressionAllowListEnabled(boolean)} (or {@code run --expression-allow-list}).
 * Then an expression may call only the methods in {@code ARGUMENT_METHODS}, for an action's
 * arguments, and {@code STATE_METHODS}, for the patterns in {@code states}; it cannot create an
 * object, read or write a file, or call a method of an object an actor wraps. The list leaves out
 * {@code matches} and {@code replaceAll} on text: a regular expression cannot be stopped part-way, so
 * a text that makes it take very long would hold the run for as long.</p>
 *
 * <h2>Why a lookup rather than names in scope</h2>
 * Actor names carry characters an expression cannot use as an identifier — {@code node:web-1} has a
 * colon, {@code project1/chat-01.chat} has a slash and a dot. Putting them in scope by name is
 * therefore not possible; the lookup takes the name as text instead.
 *
 * <h2>Examples</h2>
 * <pre>{@code
 * state.getInt("i", 0) + 1                  // arithmetic on a stored number, still a number
 * "processed: " + state.getString("item")    // text built from a stored value
 * actors.has("node:web-1")                   // whether an actor of that name exists
 * actors.get("node:web-1").result            // what that actor's last action answered
 * actors.get("node:web-1").state.getString("host")  // a value that actor stored
 * result                                     // what the previous action answered
 * state.get("dir")                           // a value this actor stored
 * state.getInt("chunk.size", 0)              // the same, read as a whole number
 * state.select("items").size()               // how long a list in that state is
 * state.select("items").get(0).asText()      // one item of it
 * self                                       // this actor's own registered name
 * currentState                               // the state the workflow is in
 * }</pre>
 *
 * @author devteam@scivicslab.com
 */
public final class WorkflowExpressions {

    /**
     * The methods an action's argument expression may call, by the type of the value it calls them on.
     *
     * <p>Used only when the allow-list is on ({@link IIActorSystem#setExpressionAllowListEnabled}); by
     * default an expression is not limited to it. The list leaves out {@code String.matches} and
     * {@code String.replaceAll}: a regular expression cannot be stopped part-way, so an input that
     * makes it take very long would hold the run for as long. A workflow that needs them runs with the
     * allow-list off.</p>
     *
     * <p>An expression is text in a workflow YAML, and a host that runs workflows it does not trust does
     * not trust whoever writes the YAML with the JVM. Permission used to go by package — {@code RESTRICTED} plus this library, POJO-actor and
     * Jackson's databind — and that reached far more than a workflow reads: {@code java.io}'s streams
     * read and wrote files, {@code FileAccumulator} and {@code ObjectMapper} wrote them too, and a
     * {@code DynamicActorLoaderActor} built inside the expression loaded a jar and ran it
     * ({@code ExpressionReach_261122_oo01}). So the set is built from nothing, and holds what the
     * expressions in the workflows written so far call — {@code state}'s readers, the Jackson node a
     * list arrives as, and text — and nothing that writes. {@code actors.get(name)} answers an
     * {@link ActorView}, the actor's last result and its state, not the object the actor wraps.</p>
     *
     * <p>A type stands for its subclasses: {@code state.select(...)} answers an {@code ArrayNode} or an
     * {@code ObjectNode}, never a {@code JsonNode} itself. The names exclude every method of those
     * subclasses that changes a node ({@code put}, {@code set}, {@code add}, {@code remove}).</p>
     */
    private static final Map<Class<?>, Set<String>> ARGUMENT_METHODS = methods(
            Lookup.class, Set.of("get", "has"),
            ActorView.class, Set.of("getResult", "getState"),
            JsonState.class, Set.of("select", "getString", "getInt", "getLong", "getDouble",
                    "getBoolean", "get", "has"),
            JsonNode.class, Set.of("size", "get", "asText", "isArray", "toString"),
            String.class, Set.of("length", "indexOf", "lastIndexOf", "substring", "trim",
                    "startsWith", "endsWith", "contains", "toString"));

    /**
     * The methods a from-state or to-state pattern may call.
     *
     * <p>A pattern reads two values, {@code currentState} (text) and {@code n} (the state as a number,
     * or {@code null}). Comparison, arithmetic and {@code =~} are JEXL's own operators and call no
     * method; what remains is three tests on the text.</p>
     */
    private static final Map<Class<?>, Set<String>> STATE_METHODS = methods(
            String.class, Set.of("startsWith", "endsWith", "contains"));

    /**
     * What an expression may be written with: values, operators and method calls, nothing else.
     *
     * <p>Built from {@link JexlFeatures#createNone()} rather than by switching features off, for the
     * same reason the permissions are: a feature JEXL adds later stays off. {@code new} is the one that
     * mattered — every escape in {@code ExpressionReach_261122_oo01} begins with it — and assignment,
     * loops, lambdas, pragmas, annotations, namespaces and literal lists and maps are off with it; no
     * workflow uses them.</p>
     */
    private static final JexlFeatures FEATURES = JexlFeatures.createNone().methodCall(true);

    /**
     * The evaluator for an action's argument expressions.
     *
     * <p>{@code safe(false)} because JEXL navigates safely by default: a method the object does
     * not have answers {@code null} without raising, and so does a call on {@code null}. The
     * argument then reaches the action as JSON null and the action runs on it — a workflow calling
     * {@code inc()} where the object has {@code increment()} printed "null" and reported success,
     * with nothing in the log. Raising is caught where the argument is built, which logs the
     * expression and passes null, so the same run now says which expression went wrong. A method
     * outside {@code ARGUMENT_METHODS} raises the same way.</p>
     */
    private static final JexlEngine JEXL = engine(ARGUMENT_METHODS);

    /**
     * The evaluator for from-state and to-state patterns, reaching only {@code STATE_METHODS}.
     *
     * <p>Strict, so that a name the pattern does not have raises rather than reading as
     * {@code null}. Read as null, {@code jexl:state != 'end'} — a workflow written when the state
     * string was called {@code state} — is true in every state, and the transition that was meant
     * to be a catch-all fires immediately. Raising is caught by the caller, which logs the
     * expression and matches nothing, so the mistake stops the run instead of redirecting it.
     * {@code safe(false)} for the same reason one step further in: without it, a method the value
     * does not have answers {@code null} rather than raising, and a pattern built on it quietly
     * matches nothing.</p>
     */
    private static final JexlEngine STATES = engine(STATE_METHODS);

    /**
     * What an expression may be written with when the allow-list is off: every JEXL feature
     * ({@code new}, assignment, loops, lambdas, literal lists and maps), and assignment to a name the
     * expression has not declared, which sets it in the expression's scope as JEXL has always done.
     *
     * <p>{@link JexlFeatures#createAll()} also turns on lexical scoping, which refuses
     * {@code (l = new(...)).run(); l.stop()} with "variable 'l' is undefined" unless every name is
     * declared with {@code var}; an expression written before the allow-list does not declare its
     * names, so lexical scoping is turned off again.</p>
     */
    private static final JexlFeatures OPEN_FEATURES = JexlFeatures.createAll().lexical(false).lexicalShade(false);

    /**
     * The evaluator for an action's argument expressions when the allow-list is off, the default.
     *
     * <p>All of JEXL's features ({@code OPEN_FEATURES}: {@code new}, assignment, loops,
     * lambdas) and {@link JexlPermissions#UNRESTRICTED}: an expression has the reach of Java. Strict and not safe
     * for the reasons given at {@link #JEXL}.</p>
     */
    private static final JexlEngine OPEN_JEXL = new JexlBuilder()
            .silent(false)
            .strict(true)
            .safe(false)
            .features(OPEN_FEATURES)
            .permissions(JexlPermissions.UNRESTRICTED)
            .create();

    /**
     * The evaluator for from-state and to-state patterns when the allow-list is off, the default:
     * all of JEXL's features ({@code OPEN_FEATURES}) and {@link JexlPermissions#UNRESTRICTED}, strict and not safe for the
     * reasons given at {@link #STATES}.
     */
    private static final JexlEngine OPEN_STATES = new JexlBuilder()
            .silent(false)
            .strict(true)
            .safe(false)
            .features(OPEN_FEATURES)
            .permissions(JexlPermissions.UNRESTRICTED)
            .create();

    private static boolean limited(IIActorSystem system) {
        return system != null && system.isExpressionAllowListEnabled();
    }

    private static JexlEngine engine(Map<Class<?>, Set<String>> methods) {
        return new JexlBuilder()
                .silent(false)
                .strict(true)
                .safe(false)
                .features(FEATURES)
                .permissions(new AllowList(methods))
                .create();
    }

    /**
     * @param system the system the workflow runs in; {@code null} for an interpreter that has none
     * @return the evaluator {@link Interpreter} uses for the expressions in {@code states}: the
     *         allow-list one when {@code system} has the allow-list on, the open one otherwise
     */
    static JexlEngine statesEngine(IIActorSystem system) {
        return limited(system) ? STATES : OPEN_STATES;
    }

    private static Map<Class<?>, Set<String>> methods(Object... typeThenNames) {
        Map<Class<?>, Set<String>> methods = new LinkedHashMap<>();
        for (int i = 0; i < typeThenNames.length; i += 2) {
            @SuppressWarnings("unchecked")
            Set<String> names = (Set<String>) typeThenNames[i + 1];
            methods.put((Class<?>) typeThenNames[i], names);
        }
        return Map.copyOf(methods);
    }

    /**
     * JEXL permissions that allow a public instance method when its name is listed for a type the
     * receiver is, and nothing else: no constructor, no field, no static method, no class that is
     * not one of the types or a subclass of one.
     *
     * <p>Written here rather than parsed from JEXL's permission syntax so that it starts from
     * nothing: the syntax adds to a base set such as {@code RESTRICTED}, which is a deny list over
     * whole packages, and what it lets through changes with each JEXL release.</p>
     */
    private static final class AllowList implements JexlPermissions {

        private final Map<Class<?>, Set<String>> methods;

        AllowList(Map<Class<?>, Set<String>> methods) {
            this.methods = methods;
        }

        private boolean listed(Class<?> receiver, String name) {
            for (Map.Entry<Class<?>, Set<String>> entry : methods.entrySet()) {
                if (entry.getKey().isAssignableFrom(receiver) && (name == null || entry.getValue().contains(name))) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public boolean allow(Class<?> clazz) {
            return clazz != null && listed(clazz, null);
        }

        @Override
        public boolean allow(Class<?> clazz, Method method) {
            return clazz != null && method != null
                    && Modifier.isPublic(method.getModifiers())
                    && !Modifier.isStatic(method.getModifiers())
                    && listed(clazz, method.getName());
        }

        @Override
        public boolean allow(Method method) {
            return method != null && allow(method.getDeclaringClass(), method);
        }

        @Override
        public boolean allow(Constructor<?> constructor) {
            return false;
        }

        @Override
        public boolean allow(Field field) {
            return false;
        }

        @Override
        public boolean allow(Class<?> clazz, Field field) {
            return false;
        }

        @Override
        public boolean allow(Package pack) {
            if (pack == null) {
                return false;
            }
            for (Class<?> type : methods.keySet()) {
                if (pack.equals(type.getPackage())) {
                    return true;
                }
            }
            return false;
        }

        /** Refused: the set is the list above, and nothing is added to it by text. */
        @Override
        public JexlPermissions compose(String... src) {
            throw new UnsupportedOperationException("the expression allow-list is not extended by text");
        }
    }

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
        JexlEngine engine = limited(system) ? JEXL : OPEN_JEXL;
        return engine.createExpression(expression).evaluate(context);
    }

    /**
     * Answers, for a name, the object that actor wraps.
     *
     * <p>Held as an object in the expression's scope rather than as a bare function, so that the
     * name reaching it is plain text and may contain any character.</p>
     */
    public final class Lookup {

        /**
         * Answers what an expression may read of the actor named {@code name}.
         *
         * <p>Lookup only: an unregistered name answers {@code null} and is not created, not even
         * for the names {@code IIActorSystem.getIIActor} makes on demand ({@code out},
         * {@code parallel-map}). An expression that created an actor would have no one to remove it
         * ({@code LookupThatCreates_260914_oo01}).</p>
         *
         * @param name the actor's registered name, for example {@code "counter"}
         * @return a read-only view of that actor, or {@code null} when no actor has that name
         * @throws IllegalArgumentException when {@code name} is a built-in value actor removed in
         *         5.0.0 ({@link IIActorSystem#removedValueActorReason}); the argument then fails
         *         with that reason instead of calling a method on nothing
         */
        public ActorView get(String name) {
            ActorRef<?> actor = system.getActor(name);
            if (actor == null) {
                String removed = IIActorSystem.removedValueActorReason(name);
                if (removed != null) {
                    throw new IllegalArgumentException(removed);
                }
                return null;
            }
            return new ActorView(actor);
        }

        /**
         * @param name the actor's registered name
         * @return whether an actor with that name exists, that is, whether {@link #get} answers one
         */
        public boolean has(String name) {
            return system.getActor(name) != null;
        }
    }

    /**
     * What an expression may read of an actor: the result of its last action, and its stored state.
     *
     * <p>Not the object the actor wraps. Every public method of that object would be a call away, and
     * an object chosen by whoever registered the actor — a loader, a file writer, the interpreter
     * itself — is not something a workflow's text should drive. What the actor answered and what it
     * stored are values, read here with the readers on the allow-list; nothing here changes the
     * actor.</p>
     *
     * <pre>{@code
     * actors.get("worker").result                      // what its last action answered
     * actors.get("worker").state.getString("output")   // a value it stored
     * }</pre>
     */
    public static final class ActorView {

        private final ActorRef<?> actor;

        ActorView(ActorRef<?> actor) {
            this.actor = actor;
        }

        /**
         * @return the text of the actor's last action result, or {@code null} when it has none;
         *         {@code .result} in an expression
         */
        public String getResult() {
            return actor.getLastResultValue();
        }

        /**
         * @return the actor's stored state, on which an expression may call only the readers;
         *         {@code .state} in an expression
         */
        public JsonState getState() {
            return actor.json();
        }

        @Override
        public String toString() {
            return "actor " + actor.getName();
        }
    }
}
