/*
 * Copyright 2025 SCIVICS Lab
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.scivicslab.pojoactor.action;

/**
 * Base class that provides automatic {@link Action @Action} annotation dispatch for
 * {@link CallableByActionName} implementors running on the JVM.
 *
 * <p>Subclasses annotate their methods with {@link Action} and gain a working
 * {@link #callByActionName(String, String)} implementation for free — no switch
 * statement required.  Method discovery is performed lazily on the first call
 * and cached by {@link ActionDispatcher}.</p>
 *
 * <p><strong>JVM only.</strong> This class uses reflection and is not compatible with
 * GraalVM Native Image without additional {@code reflect-config.json} configuration.
 * For Native Image targets, implement {@link CallableByActionName} directly and use a
 * {@code switch} statement instead.</p>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * public class MathActor extends AbstractCallableByActionName {
 *     private int lastResult = 0;
 *
 *     @Action("add")
 *     public ActionResult add(String args) {
 *         String[] p = args.split(",");
 *         lastResult = Integer.parseInt(p[0].trim()) + Integer.parseInt(p[1].trim());
 *         return new ActionResult(true, String.valueOf(lastResult));
 *     }
 *
 *     @Action("getLastResult")
 *     public ActionResult getLastResult(String args) {
 *         return new ActionResult(true, String.valueOf(lastResult));
 *     }
 * }
 * }</pre>
 *
 * <p>For classes that already extend another base class, use {@link ActionDispatcher}
 * directly as a delegate field instead.</p>
 *
 * @since 2.0.0
 * @see Action
 * @see ActionDispatcher
 * @see CallableByActionName
 */
public abstract class AbstractCallableByActionName implements CallableByActionName {

    private final ActionDispatcher dispatcher = new ActionDispatcher(this);

    /**
     * Invokes the {@link Action @Action}-annotated method whose name matches {@code actionName}.
     *
     * @return the method's {@link ActionResult}, or {@code null} if no matching method exists
     */
    protected ActionResult invokeAnnotatedAction(String actionName, String args) {
        return dispatcher.invoke(actionName, args);
    }

    /**
     * Returns {@code true} if an {@link Action @Action}-annotated method is registered
     * for the given name.
     */
    protected boolean hasAnnotatedAction(String actionName) {
        return dispatcher.has(actionName);
    }

    /**
     * Dispatches to the matching {@link Action @Action}-annotated method, or returns a
     * failure result for unknown action names.
     *
     * <p>Subclasses that need additional dispatch stages should override this method,
     * call {@link #invokeAnnotatedAction(String, String)} first, and handle remaining
     * cases when it returns {@code null}.</p>
     */
    @Override
    public ActionResult callByActionName(String actionName, String args) {
        ActionResult result = invokeAnnotatedAction(actionName, args);
        if (result != null) {
            return result;
        }
        return new ActionResult(false, "Unknown action: " + actionName);
    }
}
