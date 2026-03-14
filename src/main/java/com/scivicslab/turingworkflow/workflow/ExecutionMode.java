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
 * Execution mode for workflow actions.
 *
 * <p>Determines how an action is executed:</p>
 * <ul>
 *   <li>POOL - Execute on managed thread pool (default, safe for heavy operations)</li>
 *   <li>DIRECT - Direct synchronous call (optimization for light operations)</li>
 * </ul>
 *
 * @author devteam@scivicslab.com
 * @since 2.7.0
 */
public enum ExecutionMode {
    /**
     * Execute action on managed thread pool.
     *
     * <p>This is the default and recommended mode. Actions execute on
     * real CPU threads from the managed thread pool, preventing heavy
     * computations from blocking virtual threads.</p>
     *
     * <p>Use for:</p>
     * <ul>
     *   <li>CPU-intensive operations</li>
     *   <li>Long-running computations</li>
     *   <li>Most workflow actions (safe default)</li>
     * </ul>
     */
    POOL,

    /**
     * Execute action as direct synchronous call.
     *
     * <p>Action executes on the caller thread without going through
     * the actor's message queue or thread pool. Lowest overhead but
     * can block the workflow thread.</p>
     *
     * <p>Use for:</p>
     * <ul>
     *   <li>Very light operations (getters, setters)</li>
     *   <li>Logging</li>
     *   <li>Simple state updates</li>
     *   <li>When minimal latency is critical</li>
     * </ul>
     *
     * <p><strong>Warning:</strong> Heavy operations will block the workflow thread.</p>
     */
    DIRECT;

    /**
     * Parses execution mode from string (case-insensitive).
     *
     * @param value the string value ("pool", "direct")
     * @return the corresponding ExecutionMode, or POOL if null/empty
     */
    public static ExecutionMode fromString(String value) {
        if (value == null || value.isEmpty()) {
            return POOL;  // Default
        }

        String normalized = value.trim().toLowerCase();
        return switch (normalized) {
            case "direct" -> DIRECT;
            case "pool" -> POOL;
            default -> POOL;  // Unknown values default to POOL (safe)
        };
    }
}
