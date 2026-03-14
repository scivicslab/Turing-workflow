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

package com.scivicslab.turingworkflow.workflow.accumulator;

import java.util.concurrent.ExecutionException;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.json.JSONObject;

import com.scivicslab.pojoactor.core.ActionResult;
import com.scivicslab.pojoactor.core.accumulator.Accumulator;
import com.scivicslab.turingworkflow.workflow.IIActorRef;
import com.scivicslab.turingworkflow.workflow.IIActorSystem;

/**
 * Interpreter-interfaced actor reference for {@link Accumulator} implementations.
 *
 * <p>This class provides a common IIActorRef implementation for all Accumulator types.
 * The actual accumulation behavior is determined by the POJO passed to the constructor.</p>
 *
 * <h2>Supported Actions</h2>
 * <ul>
 *   <li>{@code add} - Adds a result (requires JSON object with source, type, data)</li>
 *   <li>{@code getSummary} - Returns formatted summary of all results</li>
 *   <li>{@code getCount} - Returns the number of added results</li>
 *   <li>{@code clear} - Clears all accumulated results</li>
 * </ul>
 *
 * <h2>Example Workflow Usage</h2>
 * <pre>{@code
 * steps:
 *   - states: ["0", "1"]
 *     actions:
 *       - actor: accumulator
 *         method: add
 *         arguments:
 *           source: "node-1"
 *           type: "cpu"
 *           data: "Intel Xeon"
 * }</pre>
 *
 * @author devteam@scivicslab.com
 * @since 2.8.0
 */
public class AccumulatorIIAR extends IIActorRef<Accumulator> {

    private final Logger logger;

    /**
     * Constructs a new AccumulatorIIAR.
     *
     * @param actorName the name of this actor
     * @param object the Accumulator implementation
     * @param system the actor system
     */
    public AccumulatorIIAR(String actorName, Accumulator object, IIActorSystem system) {
        super(actorName, object, system);
        this.logger = Logger.getLogger(actorName);
    }

    @Override
    public ActionResult callByActionName(String actionName, String arg) {
        logger.fine(String.format("actionName = %s, arg = %s", actionName, arg));

        try {
            switch (actionName) {
                case "add":
                    return handleAdd(arg);

                case "getSummary":
                    return handleGetSummary();

                case "getCount":
                    return handleGetCount();

                case "clear":
                    return handleClear();

                default:
                    logger.warning("Unknown action: " + actionName);
                    return new ActionResult(false, "Unknown action: " + actionName);
            }
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Error in " + actionName, e);
            return new ActionResult(false, "Error: " + e.getMessage());
        }
    }

    /**
     * Handles the add action.
     *
     * @param arg JSON object with source, type, and data fields
     * @return ActionResult indicating success or failure
     */
    private ActionResult handleAdd(String arg) throws ExecutionException, InterruptedException {
        JSONObject json = new JSONObject(arg);
        String source = json.getString("source");
        String type = json.getString("type");
        String data = json.getString("data");

        this.tell((Accumulator acc) -> acc.add(source, type, data)).get();

        return new ActionResult(true, "Added");
    }

    /**
     * Handles the getSummary action.
     *
     * @return ActionResult with the formatted summary
     */
    private ActionResult handleGetSummary() throws ExecutionException, InterruptedException {
        String summary = this.ask(Accumulator::getSummary).get();
        return new ActionResult(true, summary);
    }

    /**
     * Handles the getCount action.
     *
     * @return ActionResult with the count
     */
    private ActionResult handleGetCount() throws ExecutionException, InterruptedException {
        int count = this.ask(Accumulator::getCount).get();
        return new ActionResult(true, String.valueOf(count));
    }

    /**
     * Handles the clear action.
     *
     * @return ActionResult indicating success
     */
    private ActionResult handleClear() throws ExecutionException, InterruptedException {
        this.tell(Accumulator::clear).get();
        return new ActionResult(true, "Cleared");
    }
}
