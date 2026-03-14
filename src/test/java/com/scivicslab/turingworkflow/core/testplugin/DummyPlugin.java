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

package com.scivicslab.turingworkflow.core.testplugin;

import com.scivicslab.pojoactor.core.ActionResult;
import com.scivicslab.pojoactor.core.CallableByActionName;
import com.scivicslab.turingworkflow.workflow.ActorSystemAware;
import com.scivicslab.turingworkflow.workflow.IIActorSystem;

/**
 * Dummy plugin for testing DynamicActorLoader functionality.
 *
 * <p>This class is used exclusively for unit testing within POJO-actor.
 * It implements CallableByActionName and ActorSystemAware to test
 * the dynamic actor loading and ActorSystem injection features.</p>
 */
public class DummyPlugin implements CallableByActionName, ActorSystemAware {

    private IIActorSystem system;
    private boolean connected = false;

    @Override
    public void setActorSystem(IIActorSystem system) {
        this.system = system;
    }

    @Override
    public ActionResult callByActionName(String actionName, String args) {
        return switch (actionName) {
            case "echo" -> echo(args);
            case "connect" -> connect(args);
            case "disconnect" -> disconnect();
            case "list-items" -> listItems();
            case "get-system-info" -> getSystemInfo();
            default -> new ActionResult(false, "Unknown action: " + actionName);
        };
    }

    /**
     * Echo action returns the input argument.
     */
    private ActionResult echo(String args) {
        return new ActionResult(true, "Echo: " + args);
    }

    /**
     * Connect action simulates connecting to a resource.
     */
    private ActionResult connect(String args) {
        if (args == null || args.trim().isEmpty() || args.equals("[]")) {
            return new ActionResult(false, "Connection path required");
        }
        connected = true;
        return new ActionResult(true, "Connected to: " + args);
    }

    /**
     * Disconnect action simulates disconnecting from a resource.
     */
    private ActionResult disconnect() {
        if (!connected) {
            return new ActionResult(false, "Not connected");
        }
        connected = false;
        return new ActionResult(true, "Disconnected");
    }

    /**
     * List items action returns items only if connected.
     */
    private ActionResult listItems() {
        if (!connected) {
            return new ActionResult(false, "Not connected. Use 'connect' first.");
        }
        return new ActionResult(true, "item1, item2, item3");
    }

    /**
     * Get system info action returns whether ActorSystem is injected.
     */
    private ActionResult getSystemInfo() {
        if (system == null) {
            return new ActionResult(false, "ActorSystem not injected");
        }
        return new ActionResult(true, "ActorSystem injected: " + system.toString());
    }
}
