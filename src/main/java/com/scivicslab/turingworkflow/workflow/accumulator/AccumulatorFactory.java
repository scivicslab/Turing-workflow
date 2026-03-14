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

import com.scivicslab.pojoactor.core.accumulator.Accumulator;
import com.scivicslab.pojoactor.core.accumulator.BufferedAccumulator;
import com.scivicslab.pojoactor.core.accumulator.JsonAccumulator;
import com.scivicslab.pojoactor.core.accumulator.StreamingAccumulator;
import com.scivicslab.pojoactor.core.accumulator.TableAccumulator;
import com.scivicslab.turingworkflow.workflow.IIActorSystem;

/**
 * Factory for creating Accumulator instances and actors.
 *
 * <h2>Usage Example</h2>
 * <pre>{@code
 * // Create just the POJO
 * Accumulator acc = AccumulatorFactory.create("streaming");
 *
 * // Create and register an actor
 * AccumulatorIIAR actor = AccumulatorFactory.createActor("buffered", "myAccumulator", system);
 * }</pre>
 *
 * @author devteam@scivicslab.com
 * @since 2.8.0
 */
public class AccumulatorFactory {

    /**
     * Creates an Accumulator instance of the specified type.
     *
     * @param type the accumulator type: "streaming", "buffered", "table", or "json"
     * @return the Accumulator instance
     * @throws IllegalArgumentException if the type is unknown
     */
    public static Accumulator create(String type) {
        return switch (type.toLowerCase()) {
            case "streaming" -> new StreamingAccumulator();
            case "buffered" -> new BufferedAccumulator();
            case "table" -> new TableAccumulator();
            case "json" -> new JsonAccumulator();
            default -> throw new IllegalArgumentException("Unknown accumulator type: " + type);
        };
    }

    /**
     * Creates an AccumulatorIIAR and registers it with the actor system.
     *
     * @param type the accumulator type: "streaming", "buffered", "table", or "json"
     * @param actorName the name for the actor
     * @param system the actor system to register with
     * @return the created AccumulatorIIAR
     * @throws IllegalArgumentException if the type is unknown
     */
    public static AccumulatorIIAR createActor(String type, String actorName, IIActorSystem system) {
        Accumulator accumulator = create(type);
        AccumulatorIIAR actor = new AccumulatorIIAR(actorName, accumulator, system);
        system.addIIActor(actor);
        return actor;
    }

    /**
     * Creates an AccumulatorIIAR with the default name "accumulator" and registers it.
     *
     * @param type the accumulator type: "streaming", "buffered", "table", or "json"
     * @param system the actor system to register with
     * @return the created AccumulatorIIAR
     * @throws IllegalArgumentException if the type is unknown
     */
    public static AccumulatorIIAR createActor(String type, IIActorSystem system) {
        return createActor(type, "accumulator", system);
    }
}
