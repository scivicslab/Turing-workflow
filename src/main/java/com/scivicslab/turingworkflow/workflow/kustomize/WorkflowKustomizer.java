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

import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Processes YAML overlays to generate customized workflows.
 *
 * <p>WorkflowKustomizer implements a Kustomize-like overlay system for POJO-actor
 * workflows. It allows base workflows to be customized for different environments
 * (development, staging, production) without duplicating code.</p>
 *
 * <h2>Key Features:</h2>
 * <ul>
 *   <li>Strategic merge patches using label as the matching key</li>
 *   <li>Variable substitution with ${varName} syntax</li>
 *   <li>Name prefix/suffix for workflow names</li>
 *   <li>Transition insertion with anchor-based positioning</li>
 * </ul>
 *
 * <h2>Usage:</h2>
 * <pre>
 * WorkflowKustomizer kustomizer = new WorkflowKustomizer();
 * Map&lt;String, Object&gt; result = kustomizer.build(Path.of("overlays/production"));
 * String yaml = kustomizer.buildAsYaml(Path.of("overlays/production"));
 * </pre>
 *
 * @author devteam@scivicslab.com
 * @since 2.9.0
 */
public class WorkflowKustomizer {

    private static final String OVERLAY_CONF_FILE = "overlay-conf.yaml";
    private static final Pattern VAR_PATTERN = Pattern.compile("\\$\\{([^}]+)}");

    private final Yaml yaml;

    /**
     * Constructs a new WorkflowKustomizer.
     */
    public WorkflowKustomizer() {
        this.yaml = new Yaml();
    }

    /**
     * Builds the customized workflows from the specified directory.
     *
     * @param overlayDir directory containing overlay-conf.yaml
     * @return map of workflow file names to their merged content
     * @throws IOException if files cannot be read
     */
    public Map<String, Map<String, Object>> build(Path overlayDir) throws IOException {
        // Load overlay-conf.yaml
        OverlayConf overlayConf = loadOverlayConf(overlayDir);

        // Load base workflows
        Map<String, Map<String, Object>> workflows = new LinkedHashMap<>();
        for (String basePath : overlayConf.getBases()) {
            Path baseDir = overlayDir.resolve(basePath);
            Map<String, Map<String, Object>> baseWorkflows = loadWorkflowsFromDir(baseDir);
            workflows.putAll(baseWorkflows);
        }

        // If no bases, load workflows from current directory
        if (overlayConf.getBases().isEmpty()) {
            workflows.putAll(loadWorkflowsFromDir(overlayDir));
        }

        // Apply patches
        for (PatchEntry patchEntry : overlayConf.getPatches()) {
            Path patchPath = overlayDir.resolve(patchEntry.getPatch());
            Map<String, Object> patch = loadYamlFile(patchPath);
            if (patchEntry.hasTarget()) {
                // Apply patch only to the specified target workflow file
                applyPatchToTarget(workflows, patch, patchEntry.getPatch(), patchEntry.getTarget());
            } else {
                // Apply patch to all matching workflows (original behavior)
                applyPatch(workflows, patch, patchEntry.getPatch());
            }
        }

        // Apply variable substitution
        if (!overlayConf.getVars().isEmpty()) {
            substituteVariables(workflows, overlayConf.getVars());
        }

        // Apply name transformations
        if (overlayConf.getNamePrefix() != null || overlayConf.getNameSuffix() != null) {
            applyNameTransformations(workflows, overlayConf);
        }

        return workflows;
    }

    /**
     * Builds the customized workflows and returns them as a YAML string.
     *
     * @param overlayDir directory containing overlay-conf.yaml
     * @return YAML string containing all merged workflows
     * @throws IOException if files cannot be read
     */
    public String buildAsYaml(Path overlayDir) throws IOException {
        Map<String, Map<String, Object>> workflows = build(overlayDir);

        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Map<String, Object>> entry : workflows.entrySet()) {
            if (sb.length() > 0) {
                sb.append("---\n");
            }
            sb.append("# ").append(entry.getKey()).append("\n");
            sb.append(yaml.dump(entry.getValue()));
        }
        return sb.toString();
    }

    /**
     * Loads overlay-conf.yaml from the specified directory.
     */
    private OverlayConf loadOverlayConf(Path dir) throws IOException {
        Path overlayConfPath = dir.resolve(OVERLAY_CONF_FILE);
        if (!Files.exists(overlayConfPath)) {
            throw new IOException("overlay-conf.yaml not found in " + dir);
        }

        try (InputStream is = Files.newInputStream(overlayConfPath)) {
            Map<String, Object> data = yaml.load(is);
            return mapToOverlayConf(data);
        }
    }

    /**
     * Converts a Map to an OverlayConf object.
     */
    @SuppressWarnings("unchecked")
    private OverlayConf mapToOverlayConf(Map<String, Object> data) {
        OverlayConf k = new OverlayConf();
        k.setApiVersion((String) data.get("apiVersion"));
        k.setKind((String) data.get("kind"));

        if (data.get("bases") instanceof List) {
            k.setBases((List<String>) data.get("bases"));
        }
        if (data.get("patches") instanceof List) {
            List<?> patchList = (List<?>) data.get("patches");
            List<PatchEntry> patchEntries = new ArrayList<>();
            for (Object item : patchList) {
                if (item instanceof String) {
                    // Simple format: just patch file name
                    patchEntries.add(new PatchEntry((String) item));
                } else if (item instanceof Map) {
                    // Target/patch format: {target: "file.yaml", patch: "patch.yaml"}
                    Map<String, Object> patchMap = (Map<String, Object>) item;
                    String target = (String) patchMap.get("target");
                    String patch = (String) patchMap.get("patch");
                    if (patch == null) {
                        throw new IllegalArgumentException(
                            "Patch entry must have 'patch' field: " + patchMap);
                    }
                    patchEntries.add(new PatchEntry(target, patch));
                } else {
                    throw new IllegalArgumentException(
                        "Invalid patch entry type: " + item.getClass().getName());
                }
            }
            k.setPatches(patchEntries);
        }
        if (data.get("vars") instanceof Map) {
            Map<String, Object> vars = (Map<String, Object>) data.get("vars");
            Map<String, String> stringVars = new HashMap<>();
            for (Map.Entry<String, Object> e : vars.entrySet()) {
                stringVars.put(e.getKey(), String.valueOf(e.getValue()));
            }
            k.setVars(stringVars);
        }
        k.setNamePrefix((String) data.get("namePrefix"));
        k.setNameSuffix((String) data.get("nameSuffix"));

        if (data.get("commonLabels") instanceof Map) {
            k.setCommonLabels((Map<String, String>) data.get("commonLabels"));
        }

        return k;
    }

    /**
     * Loads all YAML workflow files from a directory.
     */
    private Map<String, Map<String, Object>> loadWorkflowsFromDir(Path dir) throws IOException {
        Map<String, Map<String, Object>> workflows = new LinkedHashMap<>();

        if (!Files.exists(dir)) {
            throw new IOException("Base directory not found: " + dir);
        }

        try (Stream<Path> stream = Files.list(dir)) {
            List<Path> yamlFiles = stream
                .filter((Path p) -> p.toString().endsWith(".yaml") || p.toString().endsWith(".yml"))
                .filter((Path p) -> !p.getFileName().toString().equals(OVERLAY_CONF_FILE))
                .sorted()
                .collect(Collectors.toList());

            for (Path yamlFile : yamlFiles) {
                Map<String, Object> content = loadYamlFile(yamlFile);
                workflows.put(yamlFile.getFileName().toString(), content);
            }
        }

        return workflows;
    }

    /**
     * Loads a single YAML file.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> loadYamlFile(Path path) throws IOException {
        try (InputStream is = Files.newInputStream(path)) {
            return yaml.load(is);
        }
    }

    /**
     * Applies a patch to the workflows.
     *
     * <p>Matching rules:</p>
     * <ul>
     *   <li>Transitions with matching label are overwritten</li>
     *   <li>New transitions are inserted after their anchor (preceding matched transition)</li>
     *   <li>Patches with only new transitions (no anchor) throw OrphanTransitionException</li>
     * </ul>
     */
    @SuppressWarnings("unchecked")
    private void applyPatch(Map<String, Map<String, Object>> workflows,
                            Map<String, Object> patch,
                            String patchFile) {
        String patchWorkflowName = (String) patch.get("name");
        if (patchWorkflowName == null) {
            throw new IllegalArgumentException("Patch must have a 'name' field: " + patchFile);
        }

        // Find the target workflow
        Map<String, Object> targetWorkflow = findTargetWorkflow(workflows, patchWorkflowName, patchFile);

        // Get steps from both (support both "steps" and "transitions" keys)
        List<Map<String, Object>> baseSteps = getStepsOrTransitions(targetWorkflow);
        List<Map<String, Object>> patchSteps = getStepsOrTransitions(patch);

        if (patchSteps == null || patchSteps.isEmpty()) {
            return;
        }

        // Build index by label
        Map<String, Integer> baseLabelIndex = buildLabelIndex(baseSteps);

        // Check for orphan transitions
        validatePatchSteps(patchSteps, baseLabelIndex, patchFile);

        // Apply patches and replace steps
        List<Map<String, Object>> newSteps = applyPatchSteps(baseSteps, patchSteps, baseLabelIndex, patchFile);
        targetWorkflow.put("steps", newSteps);
    }

    /**
     * Applies a patch to a specific target workflow file.
     *
     * <p>Unlike {@link #applyPatch}, this method matches by file name instead of
     * workflow name field, allowing patches to be applied to specific files.</p>
     *
     * @param workflows the map of workflows keyed by file name
     * @param patch the patch content
     * @param patchFile the patch file name for error messages
     * @param targetFile the target workflow file name to apply the patch to
     */
    @SuppressWarnings("unchecked")
    private void applyPatchToTarget(Map<String, Map<String, Object>> workflows,
                                     Map<String, Object> patch,
                                     String patchFile,
                                     String targetFile) {
        // Find the workflow by file name
        Map<String, Object> targetWorkflow = workflows.get(targetFile);
        if (targetWorkflow == null) {
            throw new IllegalArgumentException(
                "Target workflow file not found: " + targetFile + " (patch: " + patchFile + ")");
        }

        // Get steps from both (support both "steps" and "transitions" keys)
        List<Map<String, Object>> baseSteps = getStepsOrTransitions(targetWorkflow);
        List<Map<String, Object>> patchSteps = getStepsOrTransitions(patch);

        if (patchSteps == null || patchSteps.isEmpty()) {
            return;
        }

        // Build index by label
        Map<String, Integer> baseLabelIndex = buildLabelIndex(baseSteps);

        // Check for orphan transitions
        validatePatchSteps(patchSteps, baseLabelIndex, patchFile);

        // Apply patches and replace steps
        List<Map<String, Object>> newSteps = applyPatchSteps(baseSteps, patchSteps, baseLabelIndex, patchFile);
        targetWorkflow.put("steps", newSteps);
    }

    /**
     * Finds the target workflow by name.
     *
     * @param workflows the map of workflows
     * @param workflowName the name to search for
     * @param patchFile the patch file name for error messages
     * @return the target workflow
     * @throws IllegalArgumentException if workflow not found
     */
    private Map<String, Object> findTargetWorkflow(
            Map<String, Map<String, Object>> workflows,
            String workflowName,
            String patchFile) {
        for (Map.Entry<String, Map<String, Object>> entry : workflows.entrySet()) {
            if (workflowName.equals(entry.getValue().get("name"))) {
                return entry.getValue();
            }
        }
        throw new IllegalArgumentException(
            "Patch target workflow not found: " + workflowName + " in " + patchFile);
    }

    /**
     * Gets the steps/transitions list from a workflow map.
     * Supports both "steps" and "transitions" keys for backward compatibility.
     *
     * @param workflow the workflow map
     * @return the list of steps/transitions, or null if not found
     */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> getStepsOrTransitions(Map<String, Object> workflow) {
        List<Map<String, Object>> steps = (List<Map<String, Object>>) workflow.get("steps");
        if (steps == null) {
            steps = (List<Map<String, Object>>) workflow.get("transitions");
        }
        return steps;
    }

    /**
     * Builds an index mapping label to position in the steps list.
     *
     * @param steps the list of workflow steps
     * @return map of label to index
     */
    private Map<String, Integer> buildLabelIndex(List<Map<String, Object>> steps) {
        Map<String, Integer> index = new HashMap<>();
        for (int i = 0; i < steps.size(); i++) {
            String label = (String)steps.get(i).get("label");
            if (label != null) {
                index.put(label, i);
            }
        }
        return index;
    }

    /**
     * Validates that patch transitions have proper anchors.
     *
     * @param patchSteps the patch steps to validate
     * @param baseLabelIndex the index of base transitions
     * @param patchFile the patch file name for error messages
     * @throws OrphanTransitionException if orphan transitions are found
     */
    private void validatePatchSteps(
            List<Map<String, Object>> patchSteps,
            Map<String, Integer> baseLabelIndex,
            String patchFile) {
        boolean hasAnchor = false;
        List<String> newLabels = new ArrayList<>();

        for (Map<String, Object> patchStep : patchSteps) {
            String label = (String)patchStep.get("label");
            if (label == null) {
                throw new IllegalArgumentException(
                    "Patch step must have label:" + patchFile);
            }
            if (baseLabelIndex.containsKey(label)) {
                hasAnchor = true;
            } else {
                newLabels.add(label);
            }
        }

        if (!hasAnchor && !newLabels.isEmpty()) {
            throw new OrphanTransitionException(newLabels.get(0), patchFile);
        }
    }

    /**
     * Applies patch steps to base steps.
     *
     * @param baseSteps the original steps
     * @param patchSteps the patch steps to apply
     * @param baseLabelIndex the index of base transitions
     * @param patchFile the patch file name for error messages
     * @return the new steps list with patches applied
     */
    private List<Map<String, Object>> applyPatchSteps(
            List<Map<String, Object>> baseSteps,
            List<Map<String, Object>> patchSteps,
            Map<String, Integer> baseLabelIndex,
            String patchFile) {
        List<Map<String, Object>> newSteps = new ArrayList<>(baseSteps);
        int insertionOffset = 0;
        int lastAnchorNewIndex = -1;

        for (Map<String, Object> patchStep : patchSteps) {
            String label = (String)patchStep.get("label");
            Boolean deleteMarker = (Boolean) patchStep.get("$delete");

            if (baseLabelIndex.containsKey(label)) {
                // This is an anchor - update or delete existing step
                int originalIndex = baseLabelIndex.get(label);
                int newIndex = originalIndex + insertionOffset;

                if (Boolean.TRUE.equals(deleteMarker)) {
                    newSteps.remove(newIndex);
                    insertionOffset--;
                    lastAnchorNewIndex = newIndex - 1;
                } else {
                    Map<String, Object> baseStep = newSteps.get(newIndex);
                    mergeStep(baseStep, patchStep);
                    lastAnchorNewIndex = newIndex;
                }
            } else {
                // This is a new transition - insert after the last anchor
                if (lastAnchorNewIndex < 0) {
                    throw new OrphanTransitionException(label, patchFile);
                }
                int insertIndex = lastAnchorNewIndex + 1;
                newSteps.add(insertIndex, new LinkedHashMap<>(patchStep));
                insertionOffset++;
                lastAnchorNewIndex = insertIndex;
            }
        }

        return newSteps;
    }

    /**
     * Merges a patch step into a base step.
     *
     * <p>Actions are matched by actor+method. Non-matching actions are added.</p>
     */
    @SuppressWarnings("unchecked")
    private void mergeStep(Map<String, Object> baseStep, Map<String, Object> patchStep) {
        // Update states if provided in patch
        if (patchStep.containsKey("states")) {
            baseStep.put("states", patchStep.get("states"));
        }

        // Merge actions
        List<Map<String, Object>> baseActions = (List<Map<String, Object>>) baseStep.get("actions");
        List<Map<String, Object>> patchActions = (List<Map<String, Object>>) patchStep.get("actions");

        if (patchActions != null && !patchActions.isEmpty()) {
            if (baseActions == null) {
                baseActions = new ArrayList<>();
                baseStep.put("actions", baseActions);
            }

            // Build index of base actions by actor+method
            Map<String, Integer> baseActionIndex = new HashMap<>();
            for (int i = 0; i < baseActions.size(); i++) {
                Map<String, Object> action = baseActions.get(i);
                String key = action.get("actor") + "." + action.get("method");
                baseActionIndex.put(key, i);
            }

            // Apply patch actions
            for (Map<String, Object> patchAction : patchActions) {
                String key = patchAction.get("actor") + "." + patchAction.get("method");
                if (baseActionIndex.containsKey(key)) {
                    // Overwrite existing action
                    int index = baseActionIndex.get(key);
                    baseActions.set(index, new LinkedHashMap<>(patchAction));
                } else {
                    // Add new action
                    baseActions.add(new LinkedHashMap<>(patchAction));
                }
            }
        }
    }

    /**
     * Substitutes variables in all workflows.
     */
    private void substituteVariables(Map<String, Map<String, Object>> workflows,
                                     Map<String, String> vars) {
        for (Map<String, Object> workflow : workflows.values()) {
            substituteInMap(workflow, vars);
        }
    }

    /**
     * Recursively substitutes variables in a map.
     */
    @SuppressWarnings("unchecked")
    private void substituteInMap(Map<String, Object> map, Map<String, String> vars) {
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof String) {
                entry.setValue(substituteVars((String) value, vars));
            } else if (value instanceof Map) {
                substituteInMap((Map<String, Object>) value, vars);
            } else if (value instanceof List) {
                substituteInList((List<Object>) value, vars);
            }
        }
    }

    /**
     * Recursively substitutes variables in a list.
     */
    @SuppressWarnings("unchecked")
    private void substituteInList(List<Object> list, Map<String, String> vars) {
        for (int i = 0; i < list.size(); i++) {
            Object value = list.get(i);
            if (value instanceof String) {
                list.set(i, substituteVars((String) value, vars));
            } else if (value instanceof Map) {
                substituteInMap((Map<String, Object>) value, vars);
            } else if (value instanceof List) {
                substituteInList((List<Object>) value, vars);
            }
        }
    }

    /**
     * Substitutes ${varName} patterns in a string.
     */
    private String substituteVars(String input, Map<String, String> vars) {
        Matcher matcher = VAR_PATTERN.matcher(input);
        StringBuffer sb = new StringBuffer();

        while (matcher.find()) {
            String varName = matcher.group(1);
            String replacement = vars.get(varName);

            if (replacement == null) {
                // Check for default value syntax: ${VAR:-default}
                if (varName.contains(":-")) {
                    int sepIndex = varName.indexOf(":-");
                    String actualVarName = varName.substring(0, sepIndex);
                    String defaultValue = varName.substring(sepIndex + 2);
                    replacement = vars.getOrDefault(actualVarName, defaultValue);
                } else {
                    // Keep the original if no value found
                    replacement = matcher.group(0);
                }
            }

            matcher.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(sb);

        return sb.toString();
    }

    /**
     * Applies name prefix/suffix transformations.
     */
    @SuppressWarnings("unchecked")
    private void applyNameTransformations(Map<String, Map<String, Object>> workflows,
                                          OverlayConf overlayConf) {
        String prefix = overlayConf.getNamePrefix() != null ? overlayConf.getNamePrefix() : "";
        String suffix = overlayConf.getNameSuffix() != null ? overlayConf.getNameSuffix() : "";

        // Collect original names for reference updates
        Map<String, String> nameMapping = new HashMap<>();

        for (Map<String, Object> workflow : workflows.values()) {
            String originalName = (String) workflow.get("name");
            if (originalName != null) {
                String newName = prefix + originalName + suffix;
                nameMapping.put(originalName, newName);
                workflow.put("name", newName);
            }
        }

        // Update workflow references in runWorkflow arguments
        for (Map<String, Object> workflow : workflows.values()) {
            updateWorkflowReferences(workflow, nameMapping, prefix, suffix);
        }

        // Rename the workflow files in the map
        Map<String, Map<String, Object>> renamedWorkflows = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, Object>> entry : workflows.entrySet()) {
            String oldFileName = entry.getKey();
            String newFileName = prefix + oldFileName;
            if (!suffix.isEmpty()) {
                int dotIndex = newFileName.lastIndexOf('.');
                if (dotIndex > 0) {
                    newFileName = newFileName.substring(0, dotIndex) + suffix + newFileName.substring(dotIndex);
                } else {
                    newFileName = newFileName + suffix;
                }
            }
            renamedWorkflows.put(newFileName, entry.getValue());
        }

        workflows.clear();
        workflows.putAll(renamedWorkflows);
    }

    /**
     * Updates workflow references in runWorkflow arguments.
     */
    @SuppressWarnings("unchecked")
    private void updateWorkflowReferences(Map<String, Object> workflow,
                                          Map<String, String> nameMapping,
                                          String prefix, String suffix) {
        List<Map<String, Object>> steps = getStepsOrTransitions(workflow);
        if (steps == null) return;

        for (Map<String, Object> step : steps) {
            List<Map<String, Object>> actions = (List<Map<String, Object>>) step.get("actions");
            if (actions == null) continue;

            for (Map<String, Object> action : actions) {
                String method = (String) action.get("method");
                if ("runWorkflow".equals(method) || "call".equals(method)) {
                    Object args = action.get("arguments");
                    if (args instanceof List) {
                        List<Object> argList = (List<Object>) args;
                        if (!argList.isEmpty() && argList.get(0) instanceof String) {
                            String workflowRef = (String) argList.get(0);
                            // Apply prefix/suffix to workflow references
                            String newRef = prefix + workflowRef;
                            if (!suffix.isEmpty()) {
                                int dotIndex = newRef.lastIndexOf('.');
                                if (dotIndex > 0) {
                                    newRef = newRef.substring(0, dotIndex) + suffix + newRef.substring(dotIndex);
                                } else {
                                    newRef = newRef + suffix;
                                }
                            }
                            argList.set(0, newRef);
                        }
                    }
                }
                // Handle apply method with nested arguments
                if ("apply".equals(method)) {
                    Object args = action.get("arguments");
                    if (args instanceof Map) {
                        Map<String, Object> applyArgs = (Map<String, Object>) args;
                        String nestedMethod = (String) applyArgs.get("method");
                        if ("runWorkflow".equals(nestedMethod)) {
                            Object nestedArgs = applyArgs.get("arguments");
                            if (nestedArgs instanceof List) {
                                List<Object> nestedArgList = (List<Object>) nestedArgs;
                                if (!nestedArgList.isEmpty() && nestedArgList.get(0) instanceof String) {
                                    String workflowRef = (String) nestedArgList.get(0);
                                    String newRef = prefix + workflowRef;
                                    if (!suffix.isEmpty()) {
                                        int dotIndex = newRef.lastIndexOf('.');
                                        if (dotIndex > 0) {
                                            newRef = newRef.substring(0, dotIndex) + suffix + newRef.substring(dotIndex);
                                        } else {
                                            newRef = newRef + suffix;
                                        }
                                    }
                                    nestedArgList.set(0, newRef);
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
