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

import java.io.InputStream;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import com.scivicslab.pojoactor.action.Action;
import com.scivicslab.pojoactor.action.ActionResult;

/**
 * Reusable sub-workflow caller that reuses a single Interpreter instance.
 *
 * <p>This class is an alternative to {@link SubWorkflowCaller} that reuses
 * a single {@link Interpreter} instance across multiple sub-workflow calls.
 * This reduces object allocation overhead. A call that finds the reused instance busy (another
 * call is running on it, or this one was made from inside a sub-workflow running on it) runs on an
 * instance of its own instead of waiting.</p>
 *
 * <h2>Comparison with SubWorkflowCaller:</h2>
 * <table border="1">
 *   <caption>Comparison of SubWorkflowCaller patterns</caption>
 *   <tr>
 *     <th>Aspect</th>
 *     <th>SubWorkflowCaller</th>
 *     <th>ReusableSubWorkflowCaller</th>
 *   </tr>
 *   <tr>
 *     <td>Interpreter creation</td>
 *     <td>New instance per call</td>
 *     <td>Single instance, reused</td>
 *   </tr>
 *   <tr>
 *     <td>Memory allocation</td>
 *     <td>Higher (creates objects)</td>
 *     <td>Lower (reuses objects)</td>
 *   </tr>
 *   <tr>
 *     <td>GC pressure</td>
 *     <td>Higher</td>
 *     <td>Lower</td>
 *   </tr>
 *   <tr>
 *     <td>Thread safety</td>
 *     <td>Fully concurrent</td>
 *     <td>Fully concurrent; a call that finds the reused instance busy uses its own</td>
 *   </tr>
 *   <tr>
 *     <td>Complexity</td>
 *     <td>Simple</td>
 *     <td>More complex</td>
 *   </tr>
 *   <tr>
 *     <td>Best for</td>
 *     <td>General use, concurrent calls</td>
 *     <td>High-frequency calls, low concurrency</td>
 *   </tr>
 * </table>
 *
 * <h2>Usage Example:</h2>
 * <pre>{@code
 * // Create ReusableSubWorkflowCaller actor and add to system
 * ReusableSubWorkflowCaller caller = new ReusableSubWorkflowCaller("caller", system);
 * system.addIIActor(caller);
 *
 * // In workflow YAML:
 * matrix:
 *   - states: ["0", "1"]
 *     actions:
 *       - [caller, call, "sub-workflow.yaml"]
 * }</pre>
 *
 * <h2>Performance Considerations:</h2>
 * <ul>
 *   <li><strong>When to use</strong>: High-frequency sub-workflow calls with
 *       low concurrency (sequential calls)</li>
 *   <li><strong>Under concurrency</strong>: only one call at a time reuses the instance; the
 *       others allocate as {@link SubWorkflowCaller} does</li>
 * </ul>
 *
 * <h2>Thread Safety:</h2>
 * <p>No lock is held while a sub-workflow runs. A lock held there would be waited for by a call
 * made from inside that sub-workflow (an apply child calling this caller, for example) on another
 * thread, while the holder waits for that thread: a deadlock. On the same thread it would let the
 * inner call reset the interpreter the outer call is still running. Instead an atomic flag hands
 * the reused instance to one call at a time, and a call that does not get it builds its own.</p>
 *
 * @author devteam@scivicslab.com
 * @since 2.5.0
 * @see SubWorkflowCaller
 * @see Interpreter#reset()
 * @see IIActorSystem
 */
public class ReusableSubWorkflowCaller extends IIActorRef<Void> {

    private final Interpreter reusableInterpreter;
    /** True while a call is running on {@link #reusableInterpreter}. */
    private final AtomicBoolean reusableInUse = new AtomicBoolean();
    private final AtomicInteger callCount = new AtomicInteger();

    /**
     * Constructs a new ReusableSubWorkflowCaller.
     *
     * <p>Creates a single {@link Interpreter} instance that will be reused
     * across all sub-workflow calls.</p>
     *
     * @param name the actor name used to register this actor in the system
     * @param system the actor system to use for sub-workflow execution.
     *               This system is shared between main and sub-workflows.
     */
    public ReusableSubWorkflowCaller(String name, IIActorSystem system) {
        super(name, null, system);

        // Create the reusable Interpreter instance
        this.reusableInterpreter = new Interpreter.Builder()
            .loggerName("reusable-sub-workflow")
            .team(system)
            .build();
    }

    /**
     * Calls the specified sub-workflow synchronously, on the reusable Interpreter when it is free.
     *
     * <p>The {@code args} parameter should contain the YAML filename
     * (e.g., "my-workflow.yaml"), either as a plain string or as a JSON array.</p>
     *
     * <p>No lock is held while the sub-workflow runs, so a call made from inside it (by an apply
     * child, say) neither waits for this one nor disturbs it: it finds the reusable Interpreter
     * busy and runs on an Interpreter of its own.</p>
     *
     * @param args the YAML filename (plain string or JSON array)
     * @return {@link ActionResult} indicating success or failure
     */
    @Action(value = "call", argsType = SubWorkflowCaller.SubWorkflowArgs.class)
    public ActionResult call(SubWorkflowCaller.SubWorkflowArgs args) {
        if (reusableInUse.compareAndSet(false, true)) {
            try {
                return callSubWorkflow(args.yaml(), reusableInterpreter);
            } finally {
                reusableInUse.set(false);
            }
        }
        // The reused instance is busy: run on one of this call's own, without waiting.
        Interpreter own = new Interpreter.Builder()
            .loggerName("reusable-sub-workflow")
            .team((IIActorSystem) system())
            .build();
        return callSubWorkflow(args.yaml(), own);
    }

    /**
     * Calls a sub-workflow synchronously on {@code interpreter}.
     *
     * <p><strong>Important</strong>: This method resets the Interpreter state
     * before each execution using {@link Interpreter#reset()}.</p>
     *
     * @param yamlFileName the name of the YAML file (e.g., "sub-workflow.yaml").
     *                     The file is loaded from {@code /workflows/[yamlFileName]}.
     * @param interpreter the Interpreter to run it on, which no other call is using
     * @return {@link ActionResult} indicating success or failure
     */
    private ActionResult callSubWorkflow(String yamlFileName, Interpreter interpreter) {
        String actualFileName = yamlFileName;
        if (actualFileName == null || actualFileName.trim().isEmpty()) {
            return new ActionResult(false, "YAML filename cannot be null or empty");
        }

        int depth = SubWorkflowCaller.callDepth.get();
        SubWorkflowCaller.callDepth.set(depth + 1);
        String baseName = actualFileName.replaceAll("\\.(yaml|yml)$", "");
        System.out.println("[SUBWORKFLOW_START:" + baseName + ":" + depth + "]");

        try {
            // Reset the interpreter state
            interpreter.reset();

            // Load YAML file from classpath
            String resourcePath = "/workflows/" + actualFileName;
            InputStream yamlInput = getClass().getResourceAsStream(resourcePath);

            if (yamlInput == null) {
                return new ActionResult(false,
                    "Sub-workflow YAML not found: " + resourcePath);
            }

            interpreter.readYaml(yamlInput);

            // Execute all steps of sub-workflow until completion
            int stepCount = 0;
            int maxSteps = 1000; // Safety limit to prevent infinite loops

            while (stepCount < maxSteps) {
                ActionResult result = interpreter.execCode();
                stepCount++;

                if (!result.isSuccess()) {
                    return new ActionResult(false,
                        "Sub-workflow failed at step " + stepCount + ": " + result.getResult());
                }

                if (result.getResult().contains("end")) {
                    break;
                }
            }

            if (stepCount >= maxSteps) {
                return new ActionResult(false,
                    "Sub-workflow exceeded maximum steps (" + maxSteps + ")");
            }

            callCount.incrementAndGet();
            return new ActionResult(true,
                "Sub-workflow called successfully: " + actualFileName +
                " (steps: " + stepCount + ", reused interpreter)");

        } catch (Exception e) {
            return ActionResult.error(e, "sub-workflow call");
        } finally {
            System.out.println("[SUBWORKFLOW_END:" + baseName + ":" + depth + "]");
            SubWorkflowCaller.callDepth.set(depth);
        }
    }

    /**
     * Returns the number of successful sub-workflow calls.
     *
     * @return the call count
     */
    public int getCallCount() {
        return callCount.get();
    }
}
