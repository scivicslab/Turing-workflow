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

/**
 * Interface for actors that need access to their own IIActorRef.
 *
 * <p>When a class implements this interface and is loaded as a plugin via
 * {@link DynamicActorLoaderActor}, the actor's own IIActorRef will be injected
 * after construction, allowing the plugin to access its children and other
 * actor-level information.</p>
 *
 * <p><strong>Example usage in a plugin:</strong></p>
 * <pre>{@code
 * public class MyPlugin implements CallableByActionName, IIActorRefAware {
 *     private IIActorRef<?> selfRef;
 *
 *     @Override
 *     public void setIIActorRef(IIActorRef<?> actorRef) {
 *         this.selfRef = actorRef;
 *     }
 *
 *     public void processChildren() {
 *         // Can now access children
 *         Set<String> childNames = selfRef.getNamesOfChildren();
 *         for (String childName : childNames) {
 *             // Process each child...
 *         }
 *     }
 * }
 * }</pre>
 *
 * @author devteam@scivicslab.com
 * @since 2.16.0
 */
public interface IIActorRefAware {

    /**
     * Sets the actor's own IIActorRef.
     *
     * <p>This method is called automatically by {@link DynamicActorLoaderActor}
     * after the plugin's IIActorRef is created, if the plugin implements this interface.</p>
     *
     * @param actorRef the IIActorRef wrapping this plugin
     */
    void setIIActorRef(IIActorRef<?> actorRef);
}
