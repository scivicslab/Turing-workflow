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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Represents an overlay-conf.yaml configuration file.
 *
 * <p>This class is designed to be populated from YAML using SnakeYAML or Jackson.
 * It defines which base workflows to use and which patches to apply.</p>
 *
 * <p>Example overlay-conf.yaml:</p>
 * <pre>
 * apiVersion: pojoactor.scivicslab.com/v1
 * kind: OverlayConf
 *
 * bases:
 *   - ../../base
 *
 * # Simple format (applies to all matching workflows)
 * patches:
 *   - patch-prod.yaml
 *
 * # OR Targeted format (applies patch to specific workflow file)
 * patches:
 *   - target: setup.yaml
 *     patch: patch-setup.yaml
 *   - target: build.yaml
 *     patch: patch-build.yaml
 *
 * vars:
 *   environment: production
 *   nodeGroup: webservers
 *
 * namePrefix: prod-
 * </pre>
 *
 * @author devteam@scivicslab.com
 * @since 2.9.0
 */
public class OverlayConf {

    private String apiVersion;
    private String kind;
    private List<String> bases = new ArrayList<>();
    private List<PatchEntry> patches = new ArrayList<>();
    private Map<String, String> vars = new HashMap<>();
    private String namePrefix;
    private String nameSuffix;
    private Map<String, String> commonLabels = new HashMap<>();

    /**
     * Gets the API version.
     *
     * @return the API version string
     */
    public String getApiVersion() {
        return apiVersion;
    }

    /**
     * Sets the API version.
     *
     * @param apiVersion the API version string
     */
    public void setApiVersion(String apiVersion) {
        this.apiVersion = apiVersion;
    }

    /**
     * Gets the kind.
     *
     * @return the kind string (typically "OverlayConf")
     */
    public String getKind() {
        return kind;
    }

    /**
     * Sets the kind.
     *
     * @param kind the kind string
     */
    public void setKind(String kind) {
        this.kind = kind;
    }

    /**
     * Gets the list of base directories.
     *
     * @return list of relative paths to base kustomization directories
     */
    public List<String> getBases() {
        return bases;
    }

    /**
     * Sets the list of base directories.
     *
     * @param bases list of relative paths to base directories
     */
    public void setBases(List<String> bases) {
        this.bases = bases;
    }

    /**
     * Gets the list of patch entries.
     *
     * @return list of patch entries (each may have target and patch fields)
     */
    public List<PatchEntry> getPatches() {
        return patches;
    }

    /**
     * Sets the list of patch entries.
     *
     * @param patches list of patch entries
     */
    public void setPatches(List<PatchEntry> patches) {
        this.patches = patches;
    }

    /**
     * Gets the variable substitution map.
     *
     * @return map of variable names to values
     */
    public Map<String, String> getVars() {
        return vars;
    }

    /**
     * Sets the variable substitution map.
     *
     * @param vars map of variable names to values
     */
    public void setVars(Map<String, String> vars) {
        this.vars = vars;
    }

    /**
     * Gets the name prefix.
     *
     * @return the prefix to add to workflow names
     */
    public String getNamePrefix() {
        return namePrefix;
    }

    /**
     * Sets the name prefix.
     *
     * @param namePrefix the prefix to add to workflow names
     */
    public void setNamePrefix(String namePrefix) {
        this.namePrefix = namePrefix;
    }

    /**
     * Gets the name suffix.
     *
     * @return the suffix to add to workflow names
     */
    public String getNameSuffix() {
        return nameSuffix;
    }

    /**
     * Sets the name suffix.
     *
     * @param nameSuffix the suffix to add to workflow names
     */
    public void setNameSuffix(String nameSuffix) {
        this.nameSuffix = nameSuffix;
    }

    /**
     * Gets the common labels map.
     *
     * @return map of label names to values
     */
    public Map<String, String> getCommonLabels() {
        return commonLabels;
    }

    /**
     * Sets the common labels map.
     *
     * @param commonLabels map of label names to values
     */
    public void setCommonLabels(Map<String, String> commonLabels) {
        this.commonLabels = commonLabels;
    }
}
