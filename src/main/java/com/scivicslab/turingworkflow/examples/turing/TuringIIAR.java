/*
 * Copyright 2025 devteam@scivics-lab.com
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

package com.scivicslab.turingworkflow.examples.turing;

import com.scivicslab.pojoactor.core.ActionResult;
import org.json.JSONArray;
import com.scivicslab.turingworkflow.workflow.IIActorRef;
import com.scivicslab.turingworkflow.workflow.IIActorSystem;

import java.util.concurrent.ExecutionException;

/**
 * Interpreter-interfaced actor reference for Turing machine instances.
 *
 * <p>This class wraps a Turing machine object and allows it to be invoked
 * by the workflow interpreter using string-based action names.</p>
 *
 * @author devteam@scivics-lab.com
 */
public class TuringIIAR extends IIActorRef<Turing> {

    /**
     * Constructs a new TuringIIAR for dynamic loading.
     * Creates a new Turing machine instance automatically.
     *
     * @param actorName the name of the actor
     * @param system the actor system managing this actor
     */
    public TuringIIAR(String actorName, IIActorSystem system) {
        super(actorName, new Turing(), system);
    }

    /**
     * Constructs a new TuringIIAR with the specified actor name and Turing instance.
     *
     * @param actorName the name of the actor
     * @param turing the Turing machine instance
     * @param system the actor system managing this actor
     */
    public TuringIIAR(String actorName, Turing turing, IIActorSystem system) {
        super(actorName, turing, system);
    }

    /**
     * Parses the first element from a JSON array string.
     * For example, '["0"]' returns "0", '["R"]' returns "R".
     *
     * @param args the JSON array string
     * @return the first element as a string, or empty string if empty
     */
    private String parseFirstArg(String args) {
        if (args == null || args.isEmpty() || args.equals("[]")) {
            return "";
        }
        JSONArray array = new JSONArray(args);
        if (array.length() > 0) {
            return array.getString(0);
        }
        return "";
    }

    /**
     * Invokes a Turing machine action by name.
     *
     * <p>Supported actions:</p>
     * <ul>
     * <li>initMachine - Initialize the Turing machine</li>
     * <li>put - Write a value to the current tape position (requires argument)</li>
     * <li>move - Move the tape head (requires "L" or "R" argument)</li>
     * <li>printTape - Print the current tape contents</li>
     * <li>increment - Increment the iteration counter</li>
     * <li>matchCurrentValue - Check if current tape value matches argument (returns boolean)</li>
     * <li>isAny - Check if current tape value is "0" or "1" (returns boolean)</li>
     * <li>isNone - Check if current tape value is blank " " (returns boolean)</li>
     * </ul>
     *
     * @param actionName the name of the action to execute
     * @param args arguments for the action (can be empty string)
     * @return an ActionResult indicating success or failure
     */
    @Override
    public ActionResult callByActionName(String actionName, String args) {
        try {
            switch (actionName) {
                case "initMachine":
                    this.tell(t -> t.initMachine()).get();
                    return new ActionResult(true, "Machine initialized");

                case "put":
                    String putValue = parseFirstArg(args);
                    this.tell(t -> t.put(putValue)).get();
                    return new ActionResult(true, "Put " + putValue);

                case "move":
                    String direction = parseFirstArg(args);
                    this.tell(t -> t.move(direction)).get();
                    return new ActionResult(true, "Moved " + direction);

                case "printTape":
                    this.tell(t -> t.printTape()).get();
                    return new ActionResult(true, "Tape printed");

                case "increment":
                    int count = this.ask(t -> t.increment()).get();
                    return new ActionResult(true, "Counter: " + count);

                // Condition checking actions (return boolean for workflow branching)
                case "matchCurrentValue":
                    String matchValue = parseFirstArg(args);
                    boolean matchResult = this.ask(t -> t.matchCurrentValue(matchValue)).get();
                    return new ActionResult(matchResult, "matchCurrentValue(" + matchValue + ")=" + matchResult);

                case "isAny":
                    boolean isAnyResult = this.ask(t -> t.isAny()).get();
                    return new ActionResult(isAnyResult, "isAny=" + isAnyResult);

                case "isNone":
                    boolean isNoneResult = this.ask(t -> t.isNone()).get();
                    return new ActionResult(isNoneResult, "isNone=" + isNoneResult);

                default:
                    return new ActionResult(false, "Unknown action: " + actionName);
            }
        } catch (InterruptedException | ExecutionException e) {
            return new ActionResult(false, "Error: " + e.getMessage());
        }
    }
}