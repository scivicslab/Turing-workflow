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
 * Represents a patch entry in overlay-conf.yaml.
 *
 * <p>Supports two formats:</p>
 * <ul>
 *   <li>Simple: just a patch file name (target is null, applies to all matching workflows)</li>
 *   <li>Targeted: specifies both target workflow and patch file</li>
 * </ul>
 *
 * <p>Example YAML formats:</p>
 * <pre>
 * # Simple format (target is null)
 * patches:
 *   - patch.yaml
 *
 * # Targeted format
 * patches:
 *   - target: setup.yaml
 *     patch: patch.yaml
 *   - target: build.yaml
 *     patch: patch-build.yaml
 * </pre>
 *
 * @author devteam@scivicslab.com
 * @since 2.9.1
 */
public class PatchEntry {

    private String target;
    private String patch;

    /**
     * Default constructor for YAML deserialization.
     */
    public PatchEntry() {
    }

    /**
     * Constructs a PatchEntry with just a patch file (no specific target).
     *
     * @param patch the patch file name
     */
    public PatchEntry(String patch) {
        this.patch = patch;
        this.target = null;
    }

    /**
     * Constructs a PatchEntry with both target and patch.
     *
     * @param target the target workflow file name
     * @param patch the patch file name
     */
    public PatchEntry(String target, String patch) {
        this.target = target;
        this.patch = patch;
    }

    /**
     * Gets the target workflow file name.
     *
     * @return the target file name, or null if patch applies to all matching workflows
     */
    public String getTarget() {
        return target;
    }

    /**
     * Sets the target workflow file name.
     *
     * @param target the target file name
     */
    public void setTarget(String target) {
        this.target = target;
    }

    /**
     * Gets the patch file name.
     *
     * @return the patch file name
     */
    public String getPatch() {
        return patch;
    }

    /**
     * Sets the patch file name.
     *
     * @param patch the patch file name
     */
    public void setPatch(String patch) {
        this.patch = patch;
    }

    /**
     * Checks if this patch entry has a specific target.
     *
     * @return true if target is specified, false otherwise
     */
    public boolean hasTarget() {
        return target != null && !target.isEmpty();
    }

    @Override
    public String toString() {
        if (hasTarget()) {
            return "PatchEntry{target='" + target + "', patch='" + patch + "'}";
        } else {
            return "PatchEntry{patch='" + patch + "'}";
        }
    }
}
