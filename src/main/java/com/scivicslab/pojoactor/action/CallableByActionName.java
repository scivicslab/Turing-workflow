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
import com.scivicslab.pojoactor.core.ActorSystem;
import com.scivicslab.pojoactor.core.ActorRef;
import com.scivicslab.pojoactor.core.ActorProvider;


/**
 * Interface for actors that can be invoked dynamically by action name strings.
 *
 * <p>This interface enables flexible, data-driven execution of actor methods
 * using string-based action names, eliminating the need for compile-time coupling
 * to specific method signatures. This design is particularly valuable for:</p>
 *
 * <ul>
 *   <li><strong>Workflow execution</strong>: Actions defined in YAML/JSON can invoke actor methods</li>
 *   <li><strong>Distributed systems</strong>: Action names and arguments can be serialized and sent across network</li>
 *   <li><strong>Plugin systems</strong>: Dynamic plugins can expose capabilities without reflection</li>
 *   <li><strong>Configuration-driven behavior</strong>: Change behavior by modifying external configuration</li>
 * </ul>
 *
 * <h2>Design Philosophy</h2>
 *
 * <p>Unlike traditional reflection-based approaches where method information is discovered at runtime,
 * this interface requires plugin authors to explicitly declare and implement their action handling.
 * This provides several advantages:</p>
 *
 * <ul>
 *   <li>No reflection overhead after initial load</li>
 *   <li>Clear contract for supported actions</li>
 *   <li>String-based serialization for distributed systems</li>
 *   <li>Better GraalVM Native Image compatibility (action handling code is explicit)</li>
 * </ul>
 *
 * <h2>Usage Example</h2>
 *
 * <h3>Simple Plugin</h3>
 * <pre>{@code
 * public class MathPlugin implements CallableByActionName {
 *     private int lastResult = 0;
 *
 *     @Override
 *     public ActionResult callByActionName(String actionName, String args) {
 *         switch (actionName) {
 *             case "add":
 *                 String[] parts = args.split(",");
 *                 int a = Integer.parseInt(parts[0]);
 *                 int b = Integer.parseInt(parts[1]);
 *                 lastResult = a + b;
 *                 return new ActionResult(true, String.valueOf(lastResult));
 *
 *             case "multiply":
 *                 parts = args.split(",");
 *                 a = Integer.parseInt(parts[0]);
 *                 b = Integer.parseInt(parts[1]);
 *                 lastResult = a * b;
 *                 return new ActionResult(true, String.valueOf(lastResult));
 *
 *             case "getLastResult":
 *                 return new ActionResult(true, String.valueOf(lastResult));
 *
 *             default:
 *                 return new ActionResult(false, "Unknown action: " + actionName);
 *         }
 *     }
 * }
 * }</pre>
 *
 * <h3>With Actor System</h3>
 * <pre>{@code
 * ActorSystem system = new ActorSystem("system", 4);
 * ActorRef<MathPlugin> mathActor = system.actorOf("math", new MathPlugin());
 *
 * // Direct invocation (type-safe)
 * mathActor.tell(m -> m.add(5, 3));
 *
 * // String-based invocation (for workflows, distributed systems)
 * mathActor.tell(m -> m.callByActionName("add", "5,3"));
 * }</pre>
 *
 * <h3>YAML Workflow Example</h3>
 * <pre>{@code
 * # workflow.yaml
 * actions:
 *   - [math, add, "5,3"]
 *   - [math, multiply, "4,2"]
 *   - [math, getLastResult, ""]
 * }</pre>
 *
 * <h2>Distributed System Support</h2>
 *
 * <p>The string-based nature of this interface makes it ideal for distributed actor systems.
 * Actions can be serialized and sent across network boundaries:</p>
 *
 * <pre>{@code
 * // JSON message sent to remote node
 * {
 *   "targetNode": "node2",
 *   "actor": "math",
 *   "action": "add",
 *   "args": "5,3"
 * }
 * }</pre>
 *
 * <h2>Comparison with Reflection Approach</h2>
 *
 * <table border="1">
 * <caption>Comparison of CallableByActionName vs Reflection</caption>
 * <tr>
 *   <th>Aspect</th>
 *   <th>CallableByActionName</th>
 *   <th>Reflection</th>
 * </tr>
 * <tr>
 *   <td>Performance</td>
 *   <td>Fast (switch statement)</td>
 *   <td>Slower (method lookup)</td>
 * </tr>
 * <tr>
 *   <td>Native Image</td>
 *   <td>Compatible</td>
 *   <td>Requires configuration</td>
 * </tr>
 * <tr>
 *   <td>Serialization</td>
 *   <td>Built-in (strings)</td>
 *   <td>Complex</td>
 * </tr>
 * <tr>
 *   <td>Plugin Author Effort</td>
 *   <td>Must implement interface</td>
 *   <td>No code needed</td>
 * </tr>
 * <tr>
 *   <td>Discovery</td>
 *   <td>Explicit declaration</td>
 *   <td>Automatic</td>
 * </tr>
 * </table>
 *
 * @author devteam@scivicslab.com
 * @since 2.0.0
 * @since 2.0.0
 * @see ActionResult
 * @see ActorProvider
 */
public interface CallableByActionName {

    /**
     * Executes an action identified by its name with the given arguments.
     *
     * <p>Implementations should parse the {@code args} string according to
     * their own conventions. Common approaches include:</p>
     *
     * <ul>
     *   <li>Comma-separated values: {@code "5,3"}</li>
     *   <li>JSON: {@code "{\"a\":5,\"b\":3}"}</li>
     *   <li>Key-value pairs: {@code "a=5,b=3"}</li>
     * </ul>
     *
     * <p>The method should return an {@link ActionResult} indicating success
     * or failure, along with any result data serialized as a string.</p>
     *
     * @param actionName the name of the action to execute
     * @param args string arguments to pass to the action (format defined by implementation)
     * @return an {@link ActionResult} indicating success or failure and any result data
     */
    ActionResult callByActionName(String actionName, String args);
}
