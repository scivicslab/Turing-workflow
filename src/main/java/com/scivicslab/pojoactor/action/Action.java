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

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a method as callable by action name from workflow YAML.
 *
 * <p>Methods annotated with {@code @Action} are automatically discovered via reflection
 * and can be invoked through {@link CallableByActionName#callByActionName(String, String)}.
 * This enables a mixin-like pattern where common actions can be defined in interfaces
 * with default methods and shared across multiple actor types.</p>
 *
 * <h2>Usage</h2>
 *
 * <p>Annotate methods that should be callable from workflow YAML:</p>
 * <pre>{@code
 * public class NodeInterpreter extends Interpreter {
 *     @Action("executeCommand")
 *     public ActionResult executeCommand(String args) {
 *         // Implementation
 *     }
 * }
 * }</pre>
 *
 * <h2>Mixin Pattern with Interface Default Methods</h2>
 *
 * <p>Define reusable actions in interfaces:</p>
 * <pre>{@code
 * public interface CommandExecutable {
 *     Node getNode();  // Abstract method for implementation to provide
 *
 *     @Action("executeCommand")
 *     default ActionResult executeCommand(String args) {
 *         String cmd = parseFirstArg(args);
 *         return getNode().executeCommand(cmd);
 *     }
 * }
 *
 * // Multiple classes can implement the interface to gain the action
 * public class NodeInterpreter implements CommandExecutable {
 *     private final Node node;
 *
 *     @Override
 *     public Node getNode() { return node; }
 * }
 * }</pre>
 *
 * <h2>Method Signature Requirements</h2>
 *
 * <p>Annotated methods must have the following signature:</p>
 * <ul>
 *   <li>Return type: {@link ActionResult}</li>
 *   <li>Parameters: A single {@code String} argument (JSON-formatted)</li>
 * </ul>
 *
 * <h2>Workflow YAML Example</h2>
 * <pre>{@code
 * steps:
 *   - states: ["0", "1"]
 *     actions:
 *       - actor: this
 *         method: executeCommand  # Matches @Action("executeCommand")
 *         arguments: ["ls -la"]
 * }</pre>
 *
 * <h2>GraalVM Native Image</h2>
 *
 * <p>When using GraalVM native image, annotated methods need to be registered
 * for reflection in {@code reflect-config.json}:</p>
 * <pre>{@code
 * [
 *   {
 *     "name": "com.example.MyInterpreter",
 *     "allDeclaredMethods": true,
 *     "allPublicMethods": true
 *   }
 * ]
 * }</pre>
 *
 * @author devteam@scivicslab.com
 * @since 2.15.0
 * @see CallableByActionName
 * @see ActionResult
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Action {

    /**
     * The action name used in workflow YAML to invoke this method.
     *
     * <p>This name is used in the {@code method} field of workflow actions.
     * It should be a valid identifier, typically in camelCase format.</p>
     *
     * @return the action name
     */
    String value();

    /**
     * The Java type the method's single argument is deserialized into, or
     * {@code Void.class} (the default) to keep the current raw-{@code String} behavior.
     *
     * <p>When set, {@link ActionDispatcher} deserializes the incoming JSON {@code args}
     * string into an instance of this type (via Jackson) before invoking the method, and the
     * annotated method must declare that type as its single parameter instead of
     * {@code String}. Declare a {@code record} — see below:</p>
     *
     * <pre>{@code
     * public record ConfigureArgs(String hostname, int port, boolean ssl) {}
     *
     * @Action(value = "configure", argsType = ConfigureArgs.class)
     * public ActionResult configure(ConfigureArgs args) { ... }
     * }</pre>
     *
     * <p>This is purely opt-in: methods that omit {@code argsType} (the default,
     * {@code Void.class}) are discovered and invoked exactly as before — unchanged
     * {@code ActionResult method(String args)} signature, no deserialization.</p>
     *
     * <h2>Declare a record, not an array</h2>
     *
     * <p>Any type Jackson can deserialize works here, including {@code String[]} and
     * {@code int[]}, which produce a JSON Schema of {@code "type": "array"} and accept a
     * workflow's {@code arguments: ["a", "b"]}. <strong>Do not use them.</strong> Declare a
     * record, so that every argument arrives under a name.</p>
     *
     * <p>An array carries meaning in the order of its elements, and nothing checks that order.
     * Swapping two arguments of the same type passes deserialization, passes schema validation,
     * and calls the method with the values exchanged. Under a record, the same mistake is a key
     * that does not match a component.</p>
     *
     * <p>Cost of the rule: a one-argument call is written {@code arguments: {name: "World"}}
     * rather than {@code arguments: "World"}. See {@code ActionArgumentSchema_260807_oo01}.</p>
     *
     * @return the argument type to deserialize into, or {@code Void.class} for the
     *         default raw-{@code String} behavior
     * @since 3.5.0
     */
    Class<?> argsType() default Void.class;

    /**
     * Whether this action orchestrates: it hands its work to other actors and waits for them,
     * doing no heavy work of its own.
     *
     * <p>A workflow step that names an orchestrating action and gives no {@code execution:} key is
     * not handed to the ManagedThreadPool. The Interpreter runs it on its own mailbox virtual
     * thread instead, and the step's thread waits for it. The action's waiting then happens on that
     * virtual thread, while the work it hands out runs on the pool's real threads, in parallel.
     * The Interpreter runs it right where the step is instead when the step's thread is already its
     * mailbox virtual thread, or is one of the pool's real threads (waiting there for work that
     * needs the pool could hang it), or when it has no actor of its own. A step's explicit
     * {@code execution: pool} or {@code execution: direct} wins over this.</p>
     *
     * <p>An orchestrating action must:</p>
     * <ul>
     *   <li>run its own code on the thread it is called on: it does not hand itself to the pool;</li>
     *   <li>give the heavy work to other actors, for example with {@code ask(action, pool)},
     *       whose inline rule also keeps it safe when it is called on a pool real thread;</li>
     *   <li>not ask the calling interpreter through its mailbox and wait, since it runs on that
     *       mailbox.</li>
     * </ul>
     *
     * <pre>{@code
     * @Action(value = "apply", orchestrates = true)
     * public ActionResult apply(String args) { ... }
     * }</pre>
     *
     * @return true for an action that only hands out work and waits for it
     * @since 5.0.0
     */
    boolean orchestrates() default false;
}
