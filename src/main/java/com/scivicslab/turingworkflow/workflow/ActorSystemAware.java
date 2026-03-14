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
 * Interface for actors that need access to the IIActorSystem.
 *
 * <p>When a class implements this interface and is loaded as a plugin via
 * {@link DynamicActorLoaderActor}, the actor system will be injected after
 * construction, allowing the plugin to query other actors.</p>
 *
 * <p><strong>Example usage in a plugin:</strong></p>
 * <pre>{@code
 * public class MyPlugin implements CallableByActionName, ActorSystemAware {
 *     private IIActorSystem system;
 *
 *     @Override
 *     public void setActorSystem(IIActorSystem system) {
 *         this.system = system;
 *     }
 *
 *     @Override
 *     public ActionResult callByActionName(String actionName, String args) {
 *         // Can now query other actors
 *         IIActorRef<?> nodeGroup = system.getIIActor("nodeGroup");
 *         ActionResult result = nodeGroup.callByActionName("getSessionId", "");
 *         String sessionId = result.getResult();
 *         // ...
 *     }
 * }
 * }</pre>
 *
 * @author devteam@scivicslab.com
 * @since 2.12.0
 */
public interface ActorSystemAware {

    /**
     * Sets the actor system reference.
     *
     * <p>This method is called automatically by {@link DynamicActorLoaderActor}
     * after the plugin is instantiated, if the plugin implements this interface.</p>
     *
     * @param system the actor system managing this plugin
     */
    void setActorSystem(IIActorSystem system);
}
