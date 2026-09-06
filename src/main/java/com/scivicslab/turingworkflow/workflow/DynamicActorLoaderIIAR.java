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
 * IIActorRef wrapper for {@link DynamicActorLoaderActor}.
 *
 * <p>This class exposes {@code DynamicActorLoaderActor} (which implements
 * {@code CallableByActionName}) as an {@code IIActorRef} so that it can be
 * registered in an {@link IIActorSystem} under the name {@code "loader"} and
 * invoked from workflow YAML.</p>
 *
 * <p>Typical registration in a CLI or application entry point:</p>
 * <pre>{@code
 * IIActorSystem system = new IIActorSystem("my-workflow");
 * system.addIIActor(new DynamicActorLoaderIIAR("loader", system));
 * }</pre>
 *
 * <p>Once registered, workflows can load plugins via:</p>
 * <pre>{@code
 * steps:
 *   - states: ["0", "1"]
 *     actions:
 *       - actor: loader
 *         method: loadJar
 *         arguments: "com.example:my-plugin:1.0.0"
 *   - states: ["1", "2"]
 *     actions:
 *       - actor: loader
 *         method: createChild
 *         arguments: ["ROOT", "myActor", "com.example.MyActorClass"]
 * }</pre>
 *
 * @author devteam@scivicslab.com
 * @since 3.0.1
 */
public class DynamicActorLoaderIIAR extends IIActorRef<DynamicActorLoaderActor> {

    /**
     * Constructs a new DynamicActorLoaderIIAR.
     *
     * @param actorName the name to register this actor under (typically {@code "loader"})
     * @param system the actor system managing this actor
     */
    public DynamicActorLoaderIIAR(String actorName, IIActorSystem system) {
        super(actorName, new DynamicActorLoaderActor(system), system);
    }

    @Override
    public ActionResult callByActionName(String actionName, String args) {
        return object.callByActionName(actionName, args);
    }
}
