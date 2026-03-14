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

package com.scivicslab.turingworkflow.pojo;

/**
 * The root actor class that serves as the top-level actor in the system hierarchy.
 * This class provides basic functionality for the root actor in an actor system.
 * 
 * @author devteam@scivicslab.com
 * @since 1.0.0
 */
public class Root {

    /**
     * Returns a greeting message from the root actor.
     * 
     * @return a greeting string from the root actor
     */
    public String hello() {
        return "Hello from the root actor.";
    }

}
