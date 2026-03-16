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

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import org.json.JSONArray;
import org.json.JSONObject;

import com.scivicslab.pojoactor.core.ActionResult;
import com.scivicslab.turingworkflow.workflow.kustomize.WorkflowKustomizer;

import org.yaml.snakeyaml.Yaml;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.apache.commons.jexl3.JexlBuilder;
import org.apache.commons.jexl3.JexlContext;
import org.apache.commons.jexl3.JexlEngine;
import org.apache.commons.jexl3.JexlExpression;
import org.apache.commons.jexl3.MapContext;

/**
 * Workflow interpreter that executes matrix-based workflow definitions.
 *
 * <p>The Interpreter reads workflow definitions in YAML or JSON format and
 * executes them by invoking actions on registered actors. Workflows are
 * represented as a matrix of states and actions, where each row defines
 * state transitions and the actions to execute during those transitions.</p>
 *
 * <p>This class maintains the current execution state, including the current
 * row and state, and coordinates with an {@link IIActorSystem} to invoke
 * actor methods dynamically.</p>
 *
 * @author devteam@scivicslab.com
 */
public class Interpreter {

    protected Logger logger = Logger.getLogger(Interpreter.class.getName());

    protected MatrixCode code = null;

    protected int currentTransitionIndex = 0;

    protected String currentState = "0";

    protected volatile boolean stopRequested = false;

    protected volatile boolean paused = false;

    private final Object pauseLock = new Object();

    private BreakpointListener breakpointListener = null;

    private ActionFailureListener actionFailureListener = null;

    protected IIActorSystem system = null;

    /**
     * Reference to the actor executing this interpreter (for Unix-style path resolution).
     * When this interpreter is running inside an actor (e.g., Node extends Interpreter),
     * this field holds a reference to that actor, enabling relative path resolution
     * like "." (self), ".." (parent), "../sibling" (sibling actors), etc.
     */
    protected IIActorRef<?> selfActorRef = null;

    /**
     * Base directory for resolving relative workflow file paths.
     * When set, runWorkflow will look for files relative to this directory.
     */
    protected String workflowBaseDir = null;

    /**
     * Random number generator for child actor name generation.
     * Shared across all interpreters for efficiency.
     */
    private static final Random random = new Random();

    /**
     * Builder for constructing Interpreter instances.
     *
     * <p>Provides a fluent API for configuring the interpreter with
     * a logger name and actor system.</p>
     */
     public static class Builder {

         IIActorSystem system = null;

         String loggerName = null;

         /**
          * Constructs a new Builder.
          */
         public Builder() {}

         /**
          * Sets the logger name for this interpreter.
          *
          * @param name the logger name
          * @return this builder for method chaining
          */
         public Builder loggerName(String name) {
             loggerName = name;
             return this;
         }

         /**
          * Sets the actor system for this interpreter.
          *
          * @param system the actor system managing the workflow actors
          * @return this builder for method chaining
          */
         public Builder team(IIActorSystem system) {
             this.system = system;
             return this;
         }

         /**
          * Builds and returns a new Interpreter instance.
          *
          * @return a new configured Interpreter
          */
         public Interpreter build() {
             Interpreter obj = new Interpreter();
             obj.logger = Logger.getLogger(loggerName);
             obj.system = this.system;
             return obj;
         }
     }

    /**
     * Executes the actions in the current row of the workflow matrix.
     *
     * <p>For each action in the current row, this method retrieves the
     * corresponding actor(s) from the system and invokes the specified action
     * by name with the provided arguments.</p>
     *
     * <p>Supports Unix-style actor path resolution when {@code selfActorRef} is set:</p>
     * <ul>
     *   <li>{@code .} or {@code this} - self (the current interpreter's actor)</li>
     *   <li>{@code ..} - parent actor</li>
     *   <li>{@code ./*} - all children</li>
     *   <li>{@code ../*} - all siblings</li>
     *   <li>{@code ../sibling} - specific sibling by name</li>
     *   <li>{@code ../web*} - siblings matching wildcard pattern</li>
     * </ul>
     *
     * <p>If {@code selfActorRef} is not set, falls back to absolute actor name lookup.</p>
     *
     * <p>Supports both legacy {@code argument} (String) and new {@code arguments} (List/Map) formats.
     * If {@code arguments} is present, it is converted to JSON array format before passing to the actor.</p>
     *
     * @return an {@link ActionResult} indicating success or failure
     */
    private static final String CLASS_NAME = Interpreter.class.getName();

    public ActionResult action() {
        logger.entering(CLASS_NAME, "action");

        Transition transition = code.getTransitions().get(currentTransitionIndex);
        for (Action a: transition.getActions()) {
            String actorPath = a.getActor();
            String action    = a.getMethod();

            // Convert arguments to JSON format
            String argumentString = convertArgumentsToJson(a);

            // Resolve actor path using Unix-style notation or wildcard
            List<IIActorRef<?>> actors;
            if (selfActorRef != null) {
                // Use path resolution relative to self
                actors = system.resolveActorPath(selfActorRef.getName(), actorPath);
            } else if (actorPath.contains("*")) {
                // Wildcard pattern without self reference - search all actors
                actors = findMatchingActors(actorPath);
            } else {
                // Fallback: treat as absolute actor name
                IIActorRef<?> actor = system.getIIActor(actorPath);
                actors = actor != null ? Arrays.asList(actor) : new ArrayList<>();
            }

            // Log actor resolution at FINER level
            logger.logp(Level.FINER, CLASS_NAME, "action",
                "actorPath={0}, selfActorRef={1}, actors.size={2}",
                new Object[]{actorPath, selfActorRef != null ? selfActorRef.getName() : "null", actors.size()});
            for (IIActorRef<?> a2 : actors) {
                logger.logp(Level.FINER, CLASS_NAME, "action",
                    "resolved actor: {0} class={1}", new Object[]{a2.getName(), a2.getClass().getName()});
            }

            // Fail if no actors found
            if (actors.isEmpty()) {
                logger.warning("Actor not found: " + actorPath);
                ActionResult notFoundResult = new ActionResult(false, "Actor not found: " + actorPath);
                logger.exiting(CLASS_NAME, "action", notFoundResult);
                return notFoundResult;
            }

            // Execute action on all matching actors
            for (IIActorRef<?> actorAR : actors) {
                ActionResult result;

                // Execute based on ExecutionMode
                ExecutionMode executionMode = a.getExecution();
                if (executionMode == null) {
                    executionMode = ExecutionMode.POOL;  // Default to POOL
                }

                if (executionMode == ExecutionMode.POOL && system != null) {
                    // POOL: Execute on managed thread pool (safe for heavy operations)
                    try {
                        result = CompletableFuture.supplyAsync(
                            () -> actorAR.callByActionName(action, argumentString),
                            system.getManagedThreadPool()
                        ).get();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        logger.log(Level.WARNING, "Action interrupted", e);
                        ActionResult failResult = new ActionResult(false, "Action interrupted: " + e.getMessage());
                        logger.exiting(CLASS_NAME, "action", failResult);
                        return failResult;
                    } catch (ExecutionException e) {
                        logger.log(Level.WARNING, "Action execution failed", e);
                        ActionResult failResult = new ActionResult(false, "Action failed: " + e.getCause().getMessage());
                        logger.exiting(CLASS_NAME, "action", failResult);
                        return failResult;
                    }
                } else {
                    // DIRECT: Direct synchronous call (for light operations)
                    result = actorAR.callByActionName(action, argumentString);
                }

                // Save the result for variable expansion (${result})
                if (selfActorRef != null) {
                    selfActorRef.setLastResult(result);
                }

                // If any action returns false, abort this step
                if (!result.isSuccess()) {
                    logger.exiting(CLASS_NAME, "action", result);
                    return result;
                }
            }
        }
        ActionResult successResult = new ActionResult(true, "");
        logger.exiting(CLASS_NAME, "action", successResult);
        return successResult;
    }

    /**
     * Converts action arguments to JSON format.
     *
     * <p>Handles multiple {@code arguments} formats:</p>
     * <ul>
     *   <li>If {@code arguments} is a String: wraps in JSON array (e.g., {@code "value"} → {@code ["value"]})</li>
     *   <li>If {@code arguments} is a List: converts to JSON array string (e.g., {@code ["a","b"]})</li>
     *   <li>If {@code arguments} is a Map: converts to JSON object string (e.g., {@code {"key":"value"}})</li>
     *   <li>If {@code arguments} is null: returns empty array {@code []}</li>
     * </ul>
     *
     * @param action the action containing arguments
     * @return JSON string (array for String/List, object for Map)
     */
    private String convertArgumentsToJson(Action action) {
        Object arguments = action.getArguments();

        // null or empty list → return empty JSON array "[]"
        if (arguments == null) {
            return "[]";
        }

        if (arguments instanceof String) {
            // Single string argument: expand variables and wrap in JSON array
            String expanded = expandVariables((String) arguments);
            JSONArray jsonArray = new JSONArray();
            jsonArray.put(expanded);
            return jsonArray.toString();
        } else if (arguments instanceof List) {
            // Convert List to JSON array, expanding variables in string elements
            JSONArray jsonArray = new JSONArray();
            for (Object item : (List<?>) arguments) {
                if (item instanceof String) {
                    jsonArray.put(expandVariables((String) item));
                } else {
                    jsonArray.put(item);
                }
            }
            return jsonArray.toString();
        } else if (arguments instanceof Map) {
            // Convert Map to JSON object, expanding variables in string values
            Map<?, ?> map = (Map<?, ?>) arguments;
            JSONObject jsonObject = new JSONObject();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                Object value = entry.getValue();
                if (value instanceof String) {
                    jsonObject.put(entry.getKey().toString(), expandVariables((String) value));
                } else {
                    jsonObject.put(entry.getKey().toString(), value);
                }
            }
            return jsonObject.toString();
        } else {
            throw new IllegalArgumentException(
                "Unsupported arguments type: " + arguments.getClass().getName() +
                ". Expected String, List, or Map.");
        }
    }

    /**
     * Expands variable references in a string.
     *
     * <p>Supports two types of expansion:</p>
     * <ul>
     *   <li>{@code this} or {@code .} - expands to the current actor's name</li>
     *   <li>{@code ${varName}} - expands from JSON state or last result</li>
     * </ul>
     *
     * @param input the string containing patterns to expand
     * @return the expanded string
     */
    private String expandVariables(String input) {
        if (selfActorRef != null) {
            // Resolve "this" and "." to the actual actor name
            if ("this".equals(input) || ".".equals(input)) {
                return selfActorRef.getName();
            }
            return selfActorRef.expandVariables(input);
        }
        return input;
    }

    /**
     * Returns the currently loaded workflow code.
     *
     * @return the {@link MatrixCode} representing the workflow
     */
    public MatrixCode getCode() {
        return this.code;
    }

    /**
     * Sets the workflow code directly (for testing purposes).
     *
     * @param code the workflow code to set
     */
    public void setCode(MatrixCode code) {
        this.code = code;
    }

    /**
     * Reads and parses a workflow definition from a YAML input stream.
     *
     * <p>This method uses a reflection-free approach to parse YAML,
     * making it compatible with GraalVM Native Image.</p>
     *
     * @param yamlInput the YAML input stream containing the workflow definition
     */
    @SuppressWarnings("unchecked")
    public void readYaml(InputStream yamlInput) {
        Yaml yaml = new Yaml();
        Map<String, Object> data = yaml.load(yamlInput);
        code = mapToMatrixCode(data);
    }

    /**
     * Reads and parses a workflow definition from a YAML file.
     *
     * @param yamlPath the path to the YAML file containing the workflow definition
     * @throws IOException if the file cannot be read
     */
    public void readYaml(Path yamlPath) throws IOException {
        try (InputStream is = Files.newInputStream(yamlPath)) {
            readYaml(is);
        }
        // Set the workflow base directory for relative path resolution
        if (yamlPath.getParent() != null) {
            this.workflowBaseDir = yamlPath.getParent().toString();
        }
    }

    /**
     * Reads and parses a workflow definition from a YAML file with overlay applied.
     *
     * <p>This method applies the overlay configuration from the specified directory
     * to the base workflow before loading. The overlay can modify vertices, add new
     * steps, substitute variables, and apply name transformations.</p>
     *
     * <p>Example usage:</p>
     * <pre>
     * interpreter.readYaml(
     *     Path.of("workflows/base/main-workflow.yaml"),
     *     Path.of("workflows/overlays/production")
     * );
     * </pre>
     *
     * @param yamlPath the path to the base YAML workflow file
     * @param overlayDir the directory containing overlay-conf.yaml
     * @throws IOException if files cannot be read
     * @since 2.9.0
     */
    public void readYaml(Path yamlPath, Path overlayDir) throws IOException {
        WorkflowKustomizer kustomizer = new WorkflowKustomizer();
        String workflowFileName = yamlPath.getFileName().toString();
        String baseName = workflowFileName.replace(".yaml", "").replace(".yml", "");

        // Build the overlay and get the specific workflow
        Map<String, Map<String, Object>> workflows = kustomizer.build(overlayDir);

        // Debug: log available workflow keys
        if (logger != null && logger.isLoggable(java.util.logging.Level.FINE)) {
            logger.fine("readYaml: looking for '" + workflowFileName + "' (baseName='" + baseName + "')");
            logger.fine("readYaml: available workflows: " + workflows.keySet());
        }

        // Find the workflow with priority:
        // 1. Exact match (workflow.yaml)
        // 2. Exact match with prefix/suffix (prod-workflow.yaml)
        // 3. Partial match (fallback for compatibility)
        Map<String, Object> workflowData = findWorkflowByName(workflows, workflowFileName, baseName);

        if (workflowData == null) {
            throw new IOException("Workflow not found after overlay: " + workflowFileName);
        }

        // Convert the merged data to MatrixCode
        code = mapToMatrixCode(workflowData);

        // Set the workflow base directory
        if (yamlPath.getParent() != null) {
            this.workflowBaseDir = yamlPath.getParent().toString();
        }
    }

    /**
     * Finds a workflow by name with priority matching.
     *
     * <p>Priority order:</p>
     * <ol>
     *   <li>Exact match (workflow.yaml)</li>
     *   <li>Exact match with prefix (prod-workflow.yaml)</li>
     *   <li>Exact match with suffix (workflow-prod.yaml)</li>
     *   <li>Partial match containing base name (fallback)</li>
     * </ol>
     *
     * @param workflows the map of workflow file names to their data
     * @param workflowFileName the original workflow file name (e.g., "workflow.yaml")
     * @param baseName the base name without extension (e.g., "workflow")
     * @return the workflow data, or null if not found
     */
    private Map<String, Object> findWorkflowByName(
            Map<String, Map<String, Object>> workflows,
            String workflowFileName,
            String baseName) {

        // 1. Exact match
        if (workflows.containsKey(workflowFileName)) {
            if (logger != null && logger.isLoggable(java.util.logging.Level.FINE)) {
                logger.fine("findWorkflowByName: exact match found for '" + workflowFileName + "'");
            }
            return workflows.get(workflowFileName);
        }
        if (logger != null && logger.isLoggable(java.util.logging.Level.FINE)) {
            logger.fine("findWorkflowByName: no exact match for '" + workflowFileName + "'");
        }

        // 2. Exact match with prefix/suffix (e.g., prod-workflow.yaml or workflow-prod.yaml)
        // Collect all candidates and pick the shortest one (most specific match)
        // This prevents "oogasawa-main-setup" from matching when "oogasawa-setup" exists
        String bestMatch = null;
        Map<String, Object> bestResult = null;

        for (Map.Entry<String, Map<String, Object>> entry : workflows.entrySet()) {
            String key = entry.getKey();
            String keyBaseName = key.replace(".yaml", "").replace(".yml", "");

            // Check if key ends with the base name (prefix case: prod-workflow)
            if (keyBaseName.endsWith("-" + baseName) || keyBaseName.endsWith("_" + baseName)) {
                if (bestMatch == null || keyBaseName.length() < bestMatch.length()) {
                    bestMatch = keyBaseName;
                    bestResult = entry.getValue();
                }
            }
            // Check if key starts with the base name (suffix case: workflow-prod)
            if (keyBaseName.startsWith(baseName + "-") || keyBaseName.startsWith(baseName + "_")) {
                if (bestMatch == null || keyBaseName.length() < bestMatch.length()) {
                    bestMatch = keyBaseName;
                    bestResult = entry.getValue();
                }
            }
        }

        if (bestResult != null) {
            return bestResult;
        }

        // 3. Partial match (fallback for backwards compatibility)
        // Only match if the base name appears as a complete segment
        for (Map.Entry<String, Map<String, Object>> entry : workflows.entrySet()) {
            String key = entry.getKey();
            String keyBaseName = key.replace(".yaml", "").replace(".yml", "");

            // Check for exact word boundary match to avoid "workflow" matching "main-workflow"
            // This should rarely be reached if prefix/suffix patterns are used correctly
            if (keyBaseName.equals(baseName)) {
                return entry.getValue();
            }
        }

        return null;
    }

    /**
     * Converts a Map representation to MatrixCode.
     * Used when loading workflows with overlay applied.
     */
    @SuppressWarnings("unchecked")
    private MatrixCode mapToMatrixCode(Map<String, Object> data) {
        MatrixCode mc = new MatrixCode();
        mc.setName((String) data.get("name"));

        // Support both "steps" and "transitions" keys for backward compatibility
        List<Map<String, Object>> stepsData = (List<Map<String, Object>>) data.get("steps");
        if (stepsData == null) {
            stepsData = (List<Map<String, Object>>) data.get("transitions");
        }
        if (stepsData != null) {
            List<Transition> vertices = new ArrayList<>();
            for (Map<String, Object> stepData : stepsData) {
                Transition transition = new Transition();
                transition.setStates((List<String>) stepData.get("states"));
                transition.setLabel((String) stepData.get("label"));
                transition.setNote((String) stepData.get("note"));

                List<Map<String, Object>> actionsData = (List<Map<String, Object>>) stepData.get("actions");
                if (actionsData != null) {
                    List<Action> actions = new ArrayList<>();
                    for (Map<String, Object> actionData : actionsData) {
                        Action action = new Action();
                        action.setActor((String) actionData.get("actor"));
                        action.setMethod((String) actionData.get("method"));
                        action.setArguments(actionData.get("arguments"));
                        Object executionValue = actionData.get("execution");
                        if (executionValue != null) {
                            action.setExecution(ExecutionMode.fromString(executionValue.toString()));
                        }
                        actions.add(action);
                    }
                    transition.setActions(actions);
                }

                // Parse delay
                Object delayVal = stepData.get("delay");
                if (delayVal instanceof Number) {
                    transition.setDelay(((Number) delayVal).longValue());
                }

                // Parse breakpoint
                Object bpVal = stepData.get("breakpoint");
                if (bpVal instanceof Boolean && (Boolean) bpVal) {
                    transition.setBreakpoint(true);
                }

                vertices.add(transition);
            }
            mc.setSteps(vertices);
        }

        return mc;
    }

    /**
     * Reads and parses a workflow definition from a JSON input stream.
     *
     * @param jsonInput the JSON input stream containing the workflow definition
     * @throws IOException if an I/O error occurs during parsing
     */
    public void readJson(InputStream jsonInput) throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        code = mapper.readValue(jsonInput, MatrixCode.class);
    }

    /**
     * Reads and parses a workflow definition from an XML input stream.
     *
     * <p>The XML format follows this structure:</p>
     * <pre>{@code
     * <workflow name="workflow-name">
     *   <steps>
     *     <transition from="state1" to="state2">
     *       <action actor="actorName" method="methodName">argument</action>
     *     </transition>
     *   </steps>
     * </workflow>
     * }</pre>
     *
     * @param xmlInput the XML input stream containing the workflow definition
     * @throws IOException if an I/O error occurs during parsing
     * @throws ParserConfigurationException if the XML parser cannot be configured
     * @throws SAXException if the XML is malformed
     */
    public void readXml(InputStream xmlInput) throws IOException, ParserConfigurationException, SAXException {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        DocumentBuilder builder = factory.newDocumentBuilder();
        Document doc = builder.parse(xmlInput);
        doc.getDocumentElement().normalize();

        // Create MatrixCode instance
        code = new MatrixCode();

        // Get workflow name
        Element workflowElement = doc.getDocumentElement();
        String workflowName = workflowElement.getAttribute("name");
        code.setName(workflowName);

        // Parse steps
        List<Transition> steps = new ArrayList<>();
        NodeList stepsNodes = workflowElement.getElementsByTagName("steps");

        if (stepsNodes.getLength() > 0) {
            Element stepsElement = (Element) stepsNodes.item(0);
            NodeList transitionNodes = stepsElement.getElementsByTagName("transition");

            for (int i = 0; i < transitionNodes.getLength(); i++) {
                Element transitionElement = (Element) transitionNodes.item(i);

                // Create Transition instance
                Transition transition = new Transition();

                // Parse states (from, to)
                List<String> states = new ArrayList<>();
                states.add(transitionElement.getAttribute("from"));
                states.add(transitionElement.getAttribute("to"));
                transition.setStates(states);

                // Parse actions
                List<Action> actions = new ArrayList<>();
                NodeList actionNodes = transitionElement.getElementsByTagName("action");

                for (int j = 0; j < actionNodes.getLength(); j++) {
                    Element actionElement = (Element) actionNodes.item(j);

                    Action action = new Action();
                    action.setActor(actionElement.getAttribute("actor"));
                    action.setMethod(actionElement.getAttribute("method"));

                    // Check for new <arguments> element
                    NodeList argumentsNodes = actionElement.getElementsByTagName("arguments");
                    if (argumentsNodes.getLength() > 0) {
                        Element argumentsElement = (Element) argumentsNodes.item(0);
                        NodeList argNodes = argumentsElement.getElementsByTagName("arg");

                        if (argNodes.getLength() > 0) {
                            // Parse as list of arguments: <arguments><arg>a</arg><arg>b</arg></arguments>
                            List<String> argsList = new ArrayList<>();
                            for (int k = 0; k < argNodes.getLength(); k++) {
                                Element argElement = (Element) argNodes.item(k);
                                argsList.add(argElement.getTextContent().trim());
                            }
                            action.setArguments(argsList);
                        } else {
                            // Check if <arguments> has text content (single string format)
                            String textContent = argumentsElement.getTextContent().trim();
                            if (!textContent.isEmpty()) {
                                // Single string argument: <arguments>value</arguments>
                                action.setArguments(textContent);
                            } else {
                                // Empty <arguments/> element
                                action.setArguments(new ArrayList<>());
                            }
                        }
                    }
                    // No arguments element means no arguments

                    actions.add(action);
                }

                transition.setActions(actions);
                steps.add(transition);
            }
        }

        code.setSteps(steps);
    }

    /**
     * Executes the loaded workflow code.
     *
     * <p>This method implements finite automaton semantics:</p>
     * <ol>
     *   <li>Find a step whose from-state matches the current state</li>
     *   <li>Execute the actions in that step</li>
     *   <li>If any action returns false, skip to the next step and retry</li>
     *   <li>If all actions succeed, transition to the to-state</li>
     * </ol>
     *
     * <p>This enables conditional branching: multiple steps can have the same
     * from-state, and the first one whose actions all succeed will be taken.</p>
     *
     * @return an {@link ActionResult} indicating success or failure
     */
    public ActionResult execCode() {
        logger.entering(CLASS_NAME, "execCode");

        if (stopRequested) {
            ActionResult stopResult = new ActionResult(false, "Stopped by request");
            logger.exiting(CLASS_NAME, "execCode", stopResult);
            return stopResult;
        }

        if (!hasCodeLoaded()) {
            ActionResult noCodeResult = new ActionResult(false, "No code loaded");
            logger.exiting(CLASS_NAME, "execCode", noCodeResult);
            return noCodeResult;
        }

        // Try all steps, starting from currentTransitionIndex and wrapping around
        int stepsCount = code.getTransitions().size();
        for (int attempts = 0; attempts < stepsCount; attempts++) {
            // Wrap around to the beginning when reaching the end
            if (currentTransitionIndex >= stepsCount) {
                currentTransitionIndex = 0;
            }

            Transition transition = code.getTransitions().get(currentTransitionIndex);

            // Check if from-state matches current state
            if (matchesCurrentState(transition)) {
                // Handle breakpoint: pause before executing
                if (transition.isBreakpoint()) {
                    paused = true;
                    if (breakpointListener != null) {
                        breakpointListener.onBreakpoint(transition, currentState);
                    }
                    synchronized (pauseLock) {
                        while (paused && !stopRequested) {
                            try {
                                pauseLock.wait();
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                return new ActionResult(false, "Interrupted at breakpoint");
                            }
                        }
                    }
                    if (stopRequested) {
                        return new ActionResult(false, "Stopped at breakpoint");
                    }
                }

                // Handle delay: wait before executing
                if (transition.getDelay() > 0) {
                    try {
                        Thread.sleep(transition.getDelay());
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return new ActionResult(false, "Interrupted during delay");
                    }
                }

                // Hook: notify subclasses that we're entering this transition
                onEnterTransition(transition);

                // Try to execute actions
                ActionResult actionResult = action();

                if (actionResult.isSuccess()) {
                    // Hook: notify subclasses that transition succeeded
                    onExitTransition(transition, true, actionResult);
                    // All actions succeeded, transition to to-state
                    transitionTo(getToState(transition));
                    ActionResult stateResult = new ActionResult(true, "State: " + currentState);
                    logger.exiting(CLASS_NAME, "execCode", stateResult);
                    return stateResult;
                }
                // Hook: notify subclasses that transition failed
                onExitTransition(transition, false, actionResult);
                logger.finest("Action failed at state '" + currentState + "' transition " + transition.getStates() + ": " + actionResult.getResult());
                if (actionFailureListener != null) {
                    actionFailureListener.onActionFailure(transition, currentState, actionResult);
                }
                // Action failed, try next step
            }
            currentTransitionIndex++;
        }

        ActionResult noMatchResult = new ActionResult(false, "No matching state transition");
        logger.exiting(CLASS_NAME, "execCode", noMatchResult);
        return noMatchResult;
    }

    /**
     * Executes the workflow until reaching the "end" state.
     *
     * <p>This method provides a self-contained way to run workflows that have
     * a defined termination state. The workflow author defines transitions to
     * the "end" state in the YAML file, and this method handles the execution
     * loop automatically.</p>
     *
     * <p>The method repeatedly calls {@link #execCode()} until:</p>
     * <ul>
     *   <li>The current state becomes "end" (success)</li>
     *   <li>An action returns failure (error)</li>
     *   <li>No matching state transition is found (error)</li>
     *   <li>Maximum iterations exceeded (error - prevents infinite loops)</li>
     * </ul>
     *
     * <p><strong>Usage Example:</strong></p>
     * <pre>{@code
     * // YAML workflow with "end" state:
     * // steps:
     * //   - states: ["0", "1"]
     * //     actions:
     * //       - actor: worker
     * //         method: process
     * //   - states: ["1", "end"]
     * //     actions:
     * //       - actor: worker
     * //         method: finish
     *
     * Interpreter interpreter = new Interpreter.Builder()
     *     .loggerName("workflow")
     *     .team(system)
     *     .build();
     *
     * interpreter.readYaml(workflowStream);
     * ActionResult result = interpreter.runUntilEnd();
     *
     * if (result.isSuccess()) {
     *     System.out.println("Workflow completed successfully");
     * } else {
     *     System.out.println("Workflow failed: " + result.getResult());
     * }
     * }</pre>
     *
     * @return an {@link ActionResult} with success=true if "end" state reached,
     *         or success=false with error message if workflow failed
     * @see #runUntilEnd(int) for specifying custom maximum iterations
     * @since 2.8.0
     */
    public ActionResult runUntilEnd() {
        return runUntilEnd(10000);  // Default max iterations
    }

    /**
     * Loads and runs a workflow file to completion.
     *
     * <p>This is a convenience method that combines loading a workflow and
     * running it until the "end" state is reached. The workflow file is
     * reloaded each time this method is called, ensuring fresh state.</p>
     *
     * <p>The method:</p>
     * <ol>
     *   <li>Resets the interpreter state</li>
     *   <li>Loads the workflow from classpath or file system</li>
     *   <li>Runs until "end" state is reached</li>
     * </ol>
     *
     * @param workflowFile the workflow file path (YAML or JSON)
     * @return ActionResult with success=true if completed, false otherwise
     * @since 2.8.0
     */
    public ActionResult runWorkflow(String workflowFile) {
        return runWorkflow(workflowFile, 10000);
    }

    /**
     * Loads and runs a workflow file to completion with custom iteration limit.
     *
     * @param workflowFile the workflow file path (YAML or JSON)
     * @param maxIterations maximum number of state transitions allowed
     * @return ActionResult with success=true if completed, false otherwise
     * @since 2.8.0
     */
    public ActionResult runWorkflow(String workflowFile, int maxIterations) {
        try {
            // Reset state for fresh execution
            reset();

            // Load workflow from classpath or file system
            InputStream stream = loadWorkflowFromClasspath(workflowFile);

            // If not found in classpath, try workflowBaseDir
            if (stream == null && workflowBaseDir != null) {
                java.io.File baseDirFile = new java.io.File(workflowBaseDir, workflowFile);
                if (baseDirFile.exists()) {
                    try {
                        stream = new java.io.FileInputStream(baseDirFile);
                    } catch (java.io.FileNotFoundException e) {
                        // Continue to try other options
                    }
                }
            }

            // If still not found, try as absolute or relative path
            if (stream == null) {
                try {
                    stream = new java.io.FileInputStream(workflowFile);
                } catch (java.io.FileNotFoundException e) {
                    return new ActionResult(false, "Workflow not found: " + workflowFile);
                }
            }

            // Determine file type and load
            if (workflowFile.endsWith(".json")) {
                readJson(stream);
            } else {
                readYaml(stream);
            }

            // Run until end
            return runUntilEnd(maxIterations);

        } catch (Exception e) {
            logger.log(Level.SEVERE, "Error running workflow: " + workflowFile, e);
            return new ActionResult(false, "Error: " + e.getMessage());
        }
    }

    /**
     * Executes the workflow until reaching the "end" state with a custom iteration limit.
     *
     * <p>This method runs the workflow with the following termination conditions:</p>
     * <ul>
     *   <li>Success: current state becomes "end" (automaton accepted)</li>
     *   <li>Failure: an action fails and no alternative row matches the current state</li>
     *   <li>Failure: maxIterations exceeded without reaching "end" (automaton did not accept)</li>
     * </ul>
     *
     * @param maxIterations maximum number of state transitions allowed
     * @return an {@link ActionResult} with success=true if "end" state reached,
     *         or success=false with error message if workflow failed or iterations exceeded
     * @see #runUntilEnd() for default iteration limit
     * @since 2.8.0
     */
    public ActionResult runUntilEnd(int maxIterations) {
        if (!hasCodeLoaded()) {
            return new ActionResult(false, "No code loaded");
        }

        for (int iteration = 0; iteration < maxIterations && !stopRequested; iteration++) {
            // Check for end state before executing
            if ("end".equals(currentState)) {
                return new ActionResult(true, "Workflow completed");
            }

            ActionResult result = execCode();

            if (!result.isSuccess()) {
                return new ActionResult(false, "Workflow failed at iteration " + iteration + ": " + result.getResult());
            }
        }

        return new ActionResult(false, "Maximum iterations (" + maxIterations + ") exceeded");
    }

    /**
     * Checks if workflow code is loaded.
     *
     * @return true if code is loaded and has at least one step
     */
    public boolean hasCodeLoaded() {
        return code != null && !code.getTransitions().isEmpty();
    }

    /**
     * Checks if a transition's from-state matches the current state.
     *
     * <p>A transition matches if it has at least 2 states (from and to) and
     * the from-state pattern (index 0) matches the interpreter's current state.</p>
     *
     * <p>Supported state patterns:</p>
     * <ul>
     *   <li>Exact match: {@code "1"} - matches only state "1"</li>
     *   <li>Wildcard: {@code "*"} - matches any state</li>
     *   <li>Negation: {@code "!end"} - matches any state except "end"</li>
     *   <li>OR condition: {@code "1|2|3"} - matches "1", "2", or "3"</li>
     *   <li>Numeric comparison: {@code ">=1"}, {@code "<=5"}, {@code ">1"}, {@code "<5"}</li>
     * </ul>
     *
     * @param transition the transition to check
     * @return true if the transition's from-state pattern matches current state
     */
    public boolean matchesCurrentState(Transition transition) {
        List<String> states = transition.getStates();
        if (states.size() < 2) {
            return false;
        }
        return matchesStatePattern(states.get(0), currentState);
    }

    /**
     * Checks if a state pattern matches a given state value.
     *
     * <p>Supported patterns:</p>
     * <ul>
     *   <li>Exact match: "1" matches only "1"</li>
     *   <li>Wildcard: "*" matches any state</li>
     *   <li>Negation: "!end" matches anything except "end"</li>
     *   <li>OR condition: "1|2|3" matches "1", "2", or "3"</li>
     *   <li>Numeric comparison: ">=1", "&lt;5" etc.</li>
     *   <li>JEXL expression: "jexl:state >= 5 &amp;&amp; state &lt; 10"</li>
     * </ul>
     *
     * @param pattern the state pattern (may contain wildcards, operators, etc.)
     * @param state the actual state value to match against
     * @return true if the pattern matches the state
     */
    protected boolean matchesStatePattern(String pattern, String state) {
        if (pattern == null || state == null) {
            return false;
        }

        // JEXL expression: starts with "jexl:"
        if (pattern.startsWith("jexl:")) {
            return matchesJexlExpression(pattern.substring(5), state);
        }

        // Wildcard: "*" matches any state
        if ("*".equals(pattern)) {
            return true;
        }

        // Negation: "!xxx" matches any state except "xxx"
        if (pattern.startsWith("!")) {
            String negated = pattern.substring(1);
            return !state.equals(negated);
        }

        // OR condition: "a|b|c" matches "a", "b", or "c"
        if (pattern.contains("|")) {
            String[] options = pattern.split("\\|");
            for (String option : options) {
                if (option.trim().equals(state)) {
                    return true;
                }
            }
            return false;
        }

        // Numeric comparisons: ">=1", "<=5", ">1", "<5"
        if (pattern.startsWith(">=") || pattern.startsWith("<=") ||
            pattern.startsWith(">") || pattern.startsWith("<")) {
            return matchesNumericComparison(pattern, state);
        }

        // Default: exact match
        return pattern.equals(state);
    }

    /** JEXL engine for expression evaluation (lazy initialized) */
    private static JexlEngine jexlEngine;

    /**
     * Gets the JEXL engine instance (lazy initialization).
     */
    private static synchronized JexlEngine getJexlEngine() {
        if (jexlEngine == null) {
            jexlEngine = new JexlBuilder()
                    .silent(true)
                    .strict(false)
                    .create();
        }
        return jexlEngine;
    }

    /**
     * Evaluates a JEXL expression against the current state.
     *
     * <p>The expression has access to the following variables:</p>
     * <ul>
     *   <li>{@code state} - the current state as a string</li>
     *   <li>{@code s} - alias for state</li>
     *   <li>{@code n} - the state parsed as a number (or null if not numeric)</li>
     * </ul>
     *
     * <p>Example expressions:</p>
     * <ul>
     *   <li>{@code state == 'error'}</li>
     *   <li>{@code n >= 5 && n < 10}</li>
     *   <li>{@code state =~ 'error.*'}</li>
     *   <li>{@code state.startsWith('err')}</li>
     * </ul>
     *
     * @param expression the JEXL expression to evaluate
     * @param state the current state value
     * @return true if the expression evaluates to true
     */
    private boolean matchesJexlExpression(String expression, String state) {
        try {
            JexlEngine engine = getJexlEngine();
            JexlExpression jexlExpr = engine.createExpression(expression);

            JexlContext context = new MapContext();
            context.set("state", state);
            context.set("s", state);

            // Try to parse state as a number
            try {
                double numericState = Double.parseDouble(state);
                context.set("n", numericState);
            } catch (NumberFormatException e) {
                context.set("n", null);
            }

            Object result = jexlExpr.evaluate(context);

            if (result instanceof Boolean) {
                return (Boolean) result;
            }
            // For non-boolean results, treat non-null as true
            return result != null;

        } catch (Exception e) {
            logger.log(Level.WARNING, "JEXL expression evaluation failed: " + expression, e);
            return false;
        }
    }

    /**
     * Handles numeric comparison patterns.
     *
     * @param pattern comparison pattern like ">=1", "<=5", ">1", "<5"
     * @param state the state value to compare
     * @return true if the comparison is satisfied
     */
    private boolean matchesNumericComparison(String pattern, String state) {
        try {
            String operator;
            String valueStr;

            if (pattern.startsWith(">=")) {
                operator = ">=";
                valueStr = pattern.substring(2);
            } else if (pattern.startsWith("<=")) {
                operator = "<=";
                valueStr = pattern.substring(2);
            } else if (pattern.startsWith(">")) {
                operator = ">";
                valueStr = pattern.substring(1);
            } else if (pattern.startsWith("<")) {
                operator = "<";
                valueStr = pattern.substring(1);
            } else {
                return false;
            }

            // Try to parse both as numbers
            double patternValue = Double.parseDouble(valueStr.trim());
            double stateValue = Double.parseDouble(state);

            switch (operator) {
                case ">=": return stateValue >= patternValue;
                case "<=": return stateValue <= patternValue;
                case ">":  return stateValue > patternValue;
                case "<":  return stateValue < patternValue;
                default:   return false;
            }
        } catch (NumberFormatException e) {
            // If parsing fails, fall back to string comparison
            return false;
        }
    }

    /**
     * Gets the to-state from a transition.
     *
     * @param transition the transition containing the state transition
     * @return the to-state (second element), or null if not present
     */
    public String getToState(Transition transition) {
        List<String> states = transition.getStates();
        return states.size() >= 2 ? states.get(1) : null;
    }

    /**
     * Transitions to a new state and finds the next matching row.
     *
     * <p>This method:</p>
     * <ol>
     *   <li>Updates the current state to the specified to-state</li>
     *   <li>Searches for the first step whose from-state matches the new current state</li>
     *   <li>Updates currentTransitionIndex to point to that step</li>
     * </ol>
     *
     * @param toState the state to transition to
     */
    public void transitionTo(String toState) {
        currentState = toState;
        findNextMatchingTransition();
    }

    /**
     * Finds the first step whose from-state pattern matches the current state.
     *
     * <p>Searches from the beginning of the steps list and updates
     * currentTransitionIndex to the index of the first matching step. Supports
     * state patterns including wildcards, negations, and numeric comparisons.</p>
     */
    protected void findNextMatchingTransition() {
        int stepsCount = code.getTransitions().size();
        for (int i = 0; i < stepsCount; i++) {
            Transition nextTransition = code.getTransitions().get(i);
            if (!nextTransition.getStates().isEmpty() &&
                matchesStatePattern(nextTransition.getStates().get(0), currentState)) {
                currentTransitionIndex = i;
                return;
            }
        }
    }

    /**
     * Gets the current state of the interpreter.
     *
     * @return the current state string
     */
    public String getCurrentState() {
        return currentState;
    }

    /**
     * Gets the current step index.
     *
     * @return the current step index
     */
    public int getCurrentTransitionIndex() {
        return currentTransitionIndex;
    }

    /**
     * Resets the interpreter to its initial state.
     *
     * <p>This method resets the execution state of the interpreter, allowing it
     * to be reused for executing a new workflow without creating a new instance.
     * The following state is reset:</p>
     * <ul>
     *   <li>Current row index is reset to 0</li>
     *   <li>Current state is reset to "0" (initial state)</li>
     *   <li>Loaded workflow code is cleared</li>
     * </ul>
     *
     * <p>The following state is preserved:</p>
     * <ul>
     *   <li>Logger instance</li>
     *   <li>Actor system reference</li>
     * </ul>
     *
     * <p>This method is primarily used by {@link ReusableSubWorkflowCaller}
     * to reuse a single Interpreter instance across multiple sub-workflow calls,
     * reducing object allocation overhead.</p>
     *
     * <p><strong>Usage Example:</strong></p>
     * <pre>{@code
     * Interpreter interpreter = new Interpreter.Builder()
     *     .loggerName("workflow")
     *     .team(system)
     *     .build();
     *
     * // First workflow execution
     * interpreter.readYaml(workflow1);
     * while (true) {
     *     ActionResult result = interpreter.execCode();
     *     if (result.getResult().contains("end")) break;
     * }
     *
     * // Reset and reuse for second workflow
     * interpreter.reset();
     * interpreter.readYaml(workflow2);
     * while (true) {
     *     ActionResult result = interpreter.execCode();
     *     if (result.getResult().contains("end")) break;
     * }
     * }</pre>
     *
     * @see ReusableSubWorkflowCaller
     * @since 2.5.0
     */
    public void reset() {
        this.currentTransitionIndex = 0;
        this.currentState = "0";
        this.code = null;
        this.stopRequested = false;
    }

    /**
     * Requests the interpreter to stop execution as soon as possible.
     * This method is thread-safe and can be called from any thread,
     * including via {@code tellNow()} to bypass the actor message queue.
     *
     * @since 2.14.0
     */
    public void requestStop() {
        this.stopRequested = true;
    }

    /**
     * Clears the stop request flag, allowing execution to proceed again.
     *
     * @since 2.14.0
     */
    public void clearStop() {
        this.stopRequested = false;
    }

    /**
     * Returns whether a stop has been requested.
     *
     * @return true if stop has been requested
     * @since 2.14.0
     */
    public boolean isStopRequested() {
        return this.stopRequested;
    }

    /**
     * Hook method called when entering a transition during workflow execution.
     *
     * <p>This method is called just before executing the actions of a matching transition.
     * Subclasses can override this method to provide custom behavior such as
     * logging, visualization, or debugging output.</p>
     *
     * <p>The default implementation does nothing.</p>
     *
     * @param transition the transition being entered
     * @since 2.9.0
     */
    protected void onEnterTransition(Transition transition) {
        // Default: do nothing. Subclasses can override.
    }

    /**
     * Hook method called after a transition completes (success or failure).
     *
     * <p>Subclasses can override this method to provide custom behavior such as
     * logging transition results, timing, or debugging output.</p>
     *
     * <p>The default implementation does nothing.</p>
     *
     * @param transition the transition that was attempted
     * @param success true if the transition succeeded, false if it failed
     * @param result the ActionResult from executing the transition's actions
     * @since 2.12.2
     */
    protected void onExitTransition(Transition transition, boolean success, ActionResult result) {
        // Default: do nothing. Subclasses can override.
    }

    @FunctionalInterface
    public interface BreakpointListener {
        void onBreakpoint(Transition transition, String currentState);
    }

    public void setBreakpointListener(BreakpointListener listener) {
        this.breakpointListener = listener;
    }

    @FunctionalInterface
    public interface ActionFailureListener {
        void onActionFailure(Transition transition, String currentState, ActionResult result);
    }

    public void setActionFailureListener(ActionFailureListener listener) {
        this.actionFailureListener = listener;
    }

    public void resume() {
        synchronized (pauseLock) {
            paused = false;
            pauseLock.notifyAll();
        }
    }

    public boolean isPaused() {
        return paused;
    }

    /**
     * Sets the reference to the actor executing this interpreter.
     *
     * <p>This method is called by IIActorRef implementations to establish the
     * link between the interpreter and its actor wrapper, enabling Unix-style
     * path resolution within workflows.</p>
     *
     * @param actorRef the actor reference wrapping this interpreter
     */
    public void setSelfActorRef(IIActorRef<?> actorRef) {
        this.selfActorRef = actorRef;
    }

    /**
     * Sets the base directory for resolving relative workflow file paths.
     *
     * @param baseDir the directory path
     */
    public void setWorkflowBaseDir(String baseDir) {
        this.workflowBaseDir = baseDir;
    }

    /**
     * Gets the base directory for resolving relative workflow file paths.
     *
     * @return the directory path, or null if not set
     */
    public String getWorkflowBaseDir() {
        return this.workflowBaseDir;
    }

    // ========================================================================
    // Subworkflow Support
    // ========================================================================

    /**
     * Generates a unique child actor name for subworkflow execution.
     *
     * <p>The name format is: {@code subwf-{workflowName}-{timestamp}-{random}}</p>
     * <p>Example: {@code subwf-user-validation-1735812345678-04821}</p>
     *
     * <p>The combination of timestamp and random number ensures collision-free
     * naming even under parallel invocation or nested subworkflows.</p>
     *
     * @param workflowFile the workflow file name (e.g., "user-validation.yaml")
     * @return a unique child actor name
     * @since 2.9.0
     */
    protected String generateChildName(String workflowFile) {
        String baseName = workflowFile.replace(".yaml", "").replace(".json", "");
        long timestamp = System.currentTimeMillis();
        int rand = random.nextInt(100000);
        return String.format("subwf-%s-%d-%05d", baseName, timestamp, rand);
    }

    /**
     * Calls a subworkflow by creating a child interpreter, executing it, and removing it.
     *
     * <p>This method implements the 4-step subworkflow pattern:</p>
     * <ol>
     *   <li><strong>createChild</strong> — Create a child InterpreterIIAR and register it</li>
     *   <li><strong>loadWorkflow</strong> — Load the YAML workflow into the child interpreter</li>
     *   <li><strong>runUntilEnd</strong> — Execute the subworkflow until "end" state</li>
     *   <li><strong>removeChild</strong> — Remove the child actor (always executed via finally)</li>
     * </ol>
     *
     * <p>The child interpreter shares the same {@link IIActorSystem} as the parent,
     * so all registered actors are accessible from the subworkflow.</p>
     *
     * <p><strong>Usage in YAML:</strong></p>
     * <pre>{@code
     * - states: ["0", "1"]
     *   actions:
     *     - actor: this
     *       method: call
     *       arguments: ["sub-workflow.yaml"]
     * }</pre>
     *
     * @param workflowFile the workflow file name to execute
     * @return ActionResult indicating success or failure
     * @since 2.9.0
     */
    public ActionResult call(String workflowFile) {
        if (system == null) {
            return new ActionResult(false, "No actor system configured");
        }

        String childName = generateChildName(workflowFile);

        try {
            // === Step 1: createChild (親と同じクラスの子アクターを作成) ===
            // まずInterpreterを作成
            Interpreter childInterpreter = new Interpreter.Builder()
                .loggerName(childName)
                .team(system)
                .build();

            // InterpreterIIARでラップ（親と同じクラス）
            InterpreterIIAR childActor = new InterpreterIIAR(childName, childInterpreter, system);
            childInterpreter.setSelfActorRef(childActor);

            // 親子関係を設定
            if (selfActorRef != null) {
                childActor.setParentName(selfActorRef.getName());
                selfActorRef.getNamesOfChildren().add(childName);
            }
            system.addIIActor(childActor);

            // === Step 2: loadWorkflow ===
            InputStream yamlStream = loadWorkflowFromClasspath(workflowFile);
            if (yamlStream == null) {
                return new ActionResult(false, "Workflow not found: " + workflowFile);
            }
            childInterpreter.readYaml(yamlStream);

            // === Step 3: runUntilEnd ===
            ActionResult result = childInterpreter.runUntilEnd(1000);

            return result;

        } catch (Exception e) {
            logger.log(Level.SEVERE, "Subworkflow error: " + workflowFile, e);
            return new ActionResult(false, "Subworkflow error: " + e.getMessage());

        } finally {
            // === Step 4: removeChild (always executed) ===
            removeChildActor(childName);
        }
    }

    /**
     * Loads a workflow from the classpath.
     *
     * <p>Searches for the workflow in the following locations:</p>
     * <ol>
     *   <li>{@code /workflows/{workflowFile}}</li>
     *   <li>{@code /{workflowFile}}</li>
     * </ol>
     *
     * @param workflowFile the workflow file name
     * @return InputStream for the workflow, or null if not found
     */
    protected InputStream loadWorkflowFromClasspath(String workflowFile) {
        // Try /workflows/ directory first
        InputStream stream = getClass().getResourceAsStream("/workflows/" + workflowFile);
        if (stream != null) {
            return stream;
        }
        // Try root classpath
        return getClass().getResourceAsStream("/" + workflowFile);
    }

    /**
     * Removes a child actor from the system and parent-child relationship.
     *
     * @param childName the name of the child actor to remove
     */
    protected void removeChildActor(String childName) {
        if (system != null) {
            system.removeIIActor(childName);
        }
        if (selfActorRef != null) {
            selfActorRef.getNamesOfChildren().remove(childName);
        }
    }

    /**
     * Applies an action to existing child actors (with wildcard support).
     *
     * <p>Unlike {@link #call(String)} which creates and deletes child actors,
     * this method operates on existing child actors without removing them.</p>
     *
     * <p>Supports wildcard patterns for actor names:</p>
     * <ul>
     *   <li>{@code *} — all child actors</li>
     *   <li>{@code Species-*} — child actors starting with "Species-"</li>
     *   <li>{@code *-worker} — child actors ending with "-worker"</li>
     *   <li>{@code node-*-primary} — child actors matching the pattern</li>
     * </ul>
     *
     * <p><strong>Usage in YAML:</strong></p>
     * <pre>{@code
     * - actor: this
     *   method: apply
     *   arguments:
     *     - actor: "Species-*"
     *       method: mutate
     *       arguments: [0.05, 0.02, 0.5]
     * }</pre>
     *
     * @param actionDefinition JSON string containing actor, method, and arguments
     * @return ActionResult indicating success or failure
     * @since 2.9.0
     */
    public ActionResult apply(String actionDefinition) {
        if (selfActorRef == null) {
            return new ActionResult(false, "No self actor reference configured");
        }

        try {
            JSONObject action = new JSONObject(actionDefinition);
            String actorPattern = action.getString("actor");
            String methodName = action.getString("method");

            // Get arguments - handle both array and single value
            String args;
            if (action.has("arguments")) {
                Object argsObj = action.get("arguments");
                if (argsObj instanceof JSONArray) {
                    args = argsObj.toString();
                } else {
                    args = new JSONArray().put(argsObj).toString();
                }
            } else {
                args = "[]";
            }

            // Find matching child actors
            List<IIActorRef<?>> matchedActors = findMatchingChildActors(actorPattern);

            if (matchedActors.isEmpty()) {
                return new ActionResult(false, "No actors matched pattern: " + actorPattern);
            }

            logger.info(String.format("Applying method '%s' to %d actors matching '%s' (parallel)",
                methodName, matchedActors.size(), actorPattern));

            // Thread-safe collections for gathering results
            AtomicInteger successCount = new AtomicInteger(0);
            Map<String, String> failures = new ConcurrentHashMap<>();

            // Create async tasks for all actors
            List<CompletableFuture<Void>> futures = new ArrayList<>();
            final String finalArgs = args;

            for (IIActorRef<?> actor : matchedActors) {
                CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                    try {
                        ActionResult result = actor.callByActionName(methodName, finalArgs);
                        if (!result.isSuccess()) {
                            failures.put(actor.getName(), result.getResult());
                            logger.warning(String.format("Failed on %s: %s",
                                actor.getName(), result.getResult()));
                        } else {
                            successCount.incrementAndGet();
                            logger.fine(String.format("Applied to %s: %s",
                                actor.getName(), result.getResult()));
                        }
                    } catch (Exception e) {
                        failures.put(actor.getName(), e.getMessage());
                        logger.log(Level.WARNING, "Exception on " + actor.getName(), e);
                    }
                });
                futures.add(future);
            }

            // Wait for all async tasks to complete
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

            if (failures.isEmpty()) {
                return new ActionResult(true,
                    String.format("Applied to %d actors", successCount.get()));
            } else {
                List<String> failureMessages = new ArrayList<>();
                failures.forEach((name, msg) -> failureMessages.add(name + ": " + msg));
                return new ActionResult(false,
                    String.format("Applied to %d/%d actors. Failures: %s",
                        successCount.get(), matchedActors.size(),
                        String.join("; ", failureMessages)));
            }

        } catch (Exception e) {
            logger.log(Level.SEVERE, "Apply error", e);
            return new ActionResult(false, "Apply error: " + e.getMessage());
        }
    }

    /**
     * Finds all actors in the system matching a wildcard pattern.
     *
     * <p>This method searches all registered actors in the IIActorSystem,
     * not just child actors of the current interpreter.</p>
     *
     * @param pattern the pattern (exact name or wildcard like "Species-*")
     * @return list of matching actors
     * @since 2.9.0
     */
    protected List<IIActorRef<?>> findMatchingActors(String pattern) {
        List<IIActorRef<?>> matched = new ArrayList<>();

        if (system == null) {
            return matched;
        }

        // Exact match (no wildcard)
        if (!pattern.contains("*")) {
            IIActorRef<?> actor = system.getIIActor(pattern);
            if (actor != null) {
                matched.add(actor);
            }
            return matched;
        }

        // Wildcard pattern - convert to regex
        String regex = pattern
            .replace(".", "\\.")
            .replace("*", ".*");
        Pattern compiled = Pattern.compile(regex);

        for (String actorName : system.listActorNames()) {
            if (compiled.matcher(actorName).matches()) {
                IIActorRef<?> actor = system.getIIActor(actorName);
                if (actor != null) {
                    matched.add(actor);
                }
            }
        }

        return matched;
    }

    /**
     * Finds child actors matching a wildcard pattern.
     *
     * @param pattern the pattern (exact name or wildcard like "Species-*")
     * @return list of matching child actors
     */
    protected List<IIActorRef<?>> findMatchingChildActors(String pattern) {
        List<IIActorRef<?>> matched = new ArrayList<>();

        if (selfActorRef == null || system == null) {
            return matched;
        }

        Set<String> childNames = selfActorRef.getNamesOfChildren();

        // Exact match (no wildcard)
        if (!pattern.contains("*")) {
            if (childNames.contains(pattern)) {
                IIActorRef<?> actor = system.getIIActor(pattern);
                if (actor != null) {
                    matched.add(actor);
                }
            }
            return matched;
        }

        // Wildcard pattern - convert to regex
        String regex = pattern
            .replace(".", "\\.")
            .replace("*", ".*");
        Pattern compiled = Pattern.compile(regex);

        for (String childName : childNames) {
            if (compiled.matcher(childName).matches()) {
                IIActorRef<?> actor = system.getIIActor(childName);
                if (actor != null) {
                    matched.add(actor);
                }
            }
        }

        return matched;
    }

    // ========================================================================
    // Accumulator Support
    // ========================================================================

    /**
     * Adds a result to the accumulator actor in the system.
     *
     * <p>This is a convenience method for child workflows to report results
     * back to an accumulator. It looks up the accumulator actor by name
     * (default: "accumulator") and calls its "add" action.</p>
     *
     * <p><strong>Usage in YAML:</strong></p>
     * <pre>{@code
     * - states: ["0", "1"]
     *   actions:
     *     - actor: this
     *       method: addToAccumulator
     *       arguments:
     *         type: "cpu"
     *         data: "Intel Xeon E5-2680"
     * }</pre>
     *
     * @param type the type of result (e.g., "cpu", "memory")
     * @param data the result data
     * @return ActionResult indicating success or failure
     * @since 2.8.0
     */
    public ActionResult addToAccumulator(String type, String data) {
        return addToAccumulator("accumulator", type, data);
    }

    /**
     * Adds a result to a named accumulator actor in the system.
     *
     * @param accumulatorName the name of the accumulator actor
     * @param type the type of result (e.g., "cpu", "memory")
     * @param data the result data
     * @return ActionResult indicating success or failure
     * @since 2.8.0
     */
    public ActionResult addToAccumulator(String accumulatorName, String type, String data) {
        if (system == null) {
            return new ActionResult(false, "No actor system available");
        }

        IIActorRef<?> accumulator = system.getIIActor(accumulatorName);
        if (accumulator == null) {
            return new ActionResult(false, "Accumulator not found: " + accumulatorName);
        }

        // Build the argument JSON
        JSONObject addArg = new JSONObject();
        addArg.put("source", selfActorRef != null ? selfActorRef.getName() : "unknown");
        addArg.put("type", type);
        addArg.put("data", data);

        return accumulator.callByActionName("add", addArg.toString());
    }

}
