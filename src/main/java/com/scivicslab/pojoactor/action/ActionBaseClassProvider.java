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

/**
 * SPI interface for registering base classes that support the {@link Action} annotation.
 *
 * <p>The {@link ActionAnnotationProcessor} uses {@link java.util.ServiceLoader} to discover
 * implementations of this interface at compile time. Each implementation provides the
 * fully qualified name of a base class whose subclasses are allowed to use {@code @Action}.</p>
 *
 * <h2>Usage</h2>
 * <ol>
 *   <li>Implement this interface in your library</li>
 *   <li>Register it in {@code META-INF/services/com.scivicslab.pojoactor.action.ActionBaseClassProvider}</li>
 * </ol>
 *
 * <h2>Example</h2>
 * <pre>{@code
 * public class IIActorRefProvider implements ActionBaseClassProvider {
 *     @Override
 *     public String getBaseClassName() {
 *         return "com.scivicslab.turingworkflow.workflow.IIActorRef";
 *     }
 * }
 * }</pre>
 *
 * @author devteam@scivicslab.com
 * @since 3.0.0
 */
public interface ActionBaseClassProvider {

    /**
     * Returns the fully qualified class name of a base class
     * whose subclasses are allowed to use the {@link Action} annotation.
     *
     * @return the fully qualified class name
     */
    String getBaseClassName();
}
