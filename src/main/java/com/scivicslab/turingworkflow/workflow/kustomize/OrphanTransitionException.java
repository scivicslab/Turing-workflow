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

package com.scivicslab.turingworkflow.workflow.kustomize;

/**
 * Exception thrown when a patch contains a new transition without an anchor.
 *
 * <p>When adding new transitions via overlay, the patch must include at least
 * one transition that matches an existing transition in the base workflow (an "anchor").
 * New transitions are inserted relative to the anchor's position.</p>
 *
 * <p>If a patch contains only transitions that don't exist in the base,
 * this exception is thrown because the insertion position cannot be determined.</p>
 *
 * @author devteam@scivicslab.com
 * @since 2.12.0
 */
public class OrphanTransitionException extends RuntimeException {

    private final String label;
    private final String patchFile;

    /**
     * Constructs a new OrphanTransitionException.
     *
     * @param label the label of the orphan transition
     * @param patchFile the patch file containing the orphan transition
     */
    public OrphanTransitionException(String label, String patchFile) {
        super(String.format(
            "Orphan transition '%s' in patch '%s'. " +
            "New transitions must be accompanied by at least one transition that exists in the base workflow.",
            label, patchFile));
        this.label = label;
        this.patchFile = patchFile;
    }

    /**
     * Gets the label of the orphan transition.
     *
     * @return the transition label
     */
    public String getLabel() {
        return label;
    }

    /**
     * Gets the patch file name.
     *
     * @return the patch file name
     */
    public String getPatchFile() {
        return patchFile;
    }
}
