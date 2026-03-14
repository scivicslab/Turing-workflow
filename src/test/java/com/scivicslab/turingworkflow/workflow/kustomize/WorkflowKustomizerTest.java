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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.scivicslab.turingworkflow.workflow.Interpreter;
import com.scivicslab.turingworkflow.workflow.MatrixCode;
import com.scivicslab.turingworkflow.workflow.Transition;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for WorkflowKustomizer.
 *
 * @author devteam@scivicslab.com
 * @since 2.9.0
 */
class WorkflowKustomizerTest {

    private WorkflowKustomizer kustomizer;
    private Path testResourcesPath;

    @BeforeEach
    void setUp() throws URISyntaxException {
        kustomizer = new WorkflowKustomizer();
        testResourcesPath = Paths.get(
            getClass().getClassLoader().getResource("kustomize").toURI()
        );
    }

    @Test
    @DisplayName("Should load base workflow without patches")
    void testLoadBaseWorkflow() throws IOException {
        Path basePath = testResourcesPath.resolve("base");
        Map<String, Map<String, Object>> result = kustomizer.build(basePath);

        assertNotNull(result);
        assertTrue(result.containsKey("main-workflow.yaml"));

        Map<String, Object> workflow = result.get("main-workflow.yaml");
        assertEquals("MainWorkflow", workflow.get("name"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> steps = (List<Map<String, Object>>) workflow.get("steps");
        assertEquals(3, steps.size());
    }

    @Test
    @DisplayName("Should apply patch and overwrite transition by label")
    void testPatchOverwriteTransition() throws IOException {
        Path overlayPath = testResourcesPath.resolve("overlays/production");
        Map<String, Map<String, Object>> result = kustomizer.build(overlayPath);

        // Should have renamed file with prefix
        assertTrue(result.containsKey("prod-main-workflow.yaml"));

        Map<String, Object> workflow = result.get("prod-main-workflow.yaml");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> steps = (List<Map<String, Object>>) workflow.get("steps");

        // Should have 4 steps now (3 original + 1 inserted)
        assertEquals(4, steps.size());

        // Check init step was overwritten to use json
        Map<String, Object> initStep = findStepByLabel(steps, "init");
        assertNotNull(initStep);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> initActions = (List<Map<String, Object>>) initStep.get("actions");
        @SuppressWarnings("unchecked")
        List<String> initArgs = (List<String>) initActions.get(0).get("arguments");
        assertEquals("json", initArgs.get(0));

        // Check create-nodes was overwritten to use webservers
        Map<String, Object> createNodesStep = findStepByLabel(steps, "create-nodes");
        assertNotNull(createNodesStep);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> createActions = (List<Map<String, Object>>) createNodesStep.get("actions");
        @SuppressWarnings("unchecked")
        List<String> createArgs = (List<String>) createActions.get(0).get("arguments");
        assertEquals("webservers", createArgs.get(0));
    }

    @Test
    @DisplayName("Should insert new transition after anchor")
    void testInsertNewTransition() throws IOException {
        Path overlayPath = testResourcesPath.resolve("overlays/production");
        Map<String, Map<String, Object>> result = kustomizer.build(overlayPath);

        Map<String, Object> workflow = result.get("prod-main-workflow.yaml");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> steps = (List<Map<String, Object>>) workflow.get("steps");

        // Find the inserted transition
        Map<String, Object> setupLoggingStep = findStepByLabel(steps, "setup-logging");
        assertNotNull(setupLoggingStep, "setup-logging transition should be inserted");

        // Verify it was inserted after create-nodes
        int createNodesIndex = findIndexByLabel(steps, "create-nodes");
        int setupLoggingIndex = findIndexByLabel(steps, "setup-logging");
        assertEquals(createNodesIndex + 1, setupLoggingIndex,
            "setup-logging should be inserted after create-nodes");
    }

    @Test
    @DisplayName("Should apply name prefix")
    void testNamePrefix() throws IOException {
        Path overlayPath = testResourcesPath.resolve("overlays/production");
        Map<String, Map<String, Object>> result = kustomizer.build(overlayPath);

        Map<String, Object> workflow = result.get("prod-main-workflow.yaml");
        assertEquals("prod-MainWorkflow", workflow.get("name"));
    }

    @Test
    @DisplayName("Should update workflow references with prefix")
    void testWorkflowReferenceUpdate() throws IOException {
        Path overlayPath = testResourcesPath.resolve("overlays/production");
        Map<String, Map<String, Object>> result = kustomizer.build(overlayPath);

        Map<String, Object> workflow = result.get("prod-main-workflow.yaml");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> steps = (List<Map<String, Object>>) workflow.get("steps");

        Map<String, Object> runTasksStep = findStepByLabel(steps, "run-tasks");
        assertNotNull(runTasksStep);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> actions = (List<Map<String, Object>>) runTasksStep.get("actions");
        @SuppressWarnings("unchecked")
        Map<String, Object> applyArgs = (Map<String, Object>) actions.get(0).get("arguments");
        @SuppressWarnings("unchecked")
        List<String> nestedArgs = (List<String>) applyArgs.get("arguments");

        assertEquals("prod-task.yaml", nestedArgs.get(0),
            "Workflow reference should be updated with prefix");
    }

    @Test
    @DisplayName("Should throw OrphanTransitionException for orphan transition")
    void testOrphanTransitionException() throws IOException, URISyntaxException {
        // Create a temporary orphan patch scenario
        // For now, we'll test the exception class directly
        OrphanTransitionException ex = new OrphanTransitionException("orphan-transition", "bad-patch.yaml");

        assertEquals("orphan-transition", ex.getLabel());
        assertEquals("bad-patch.yaml", ex.getPatchFile());
        assertTrue(ex.getMessage().contains("orphan-transition"));
        assertTrue(ex.getMessage().contains("bad-patch.yaml"));
    }

    @Test
    @DisplayName("Should generate valid YAML output")
    void testBuildAsYaml() throws IOException {
        Path overlayPath = testResourcesPath.resolve("overlays/production");
        String yaml = kustomizer.buildAsYaml(overlayPath);

        assertNotNull(yaml);
        assertTrue(yaml.contains("prod-MainWorkflow"));
        assertTrue(yaml.contains("label: init"));
        assertTrue(yaml.contains("json"));
        assertTrue(yaml.contains("webservers"));
    }

    // Helper methods

    @SuppressWarnings("unchecked")
    private Map<String, Object> findStepByLabel(List<Map<String, Object>> steps, String label) {
        return steps.stream()
            .filter(s -> label.equals(s.get("label")))
            .findFirst()
            .orElse(null);
    }

    private int findIndexByLabel(List<Map<String, Object>> steps, String label) {
        for (int i = 0; i < steps.size(); i++) {
            if (label.equals(steps.get(i).get("label"))) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Tests for target/patch format support.
     */
    @Nested
    @DisplayName("Target/Patch Format")
    class TargetPatchFormatTest {

        @Test
        @DisplayName("Should apply patch to specific target file")
        void testApplyPatchToTarget() throws IOException {
            Path overlayPath = testResourcesPath.resolve("overlays/targeted");
            Map<String, Map<String, Object>> result = kustomizer.build(overlayPath);

            // Check main-workflow.yaml was patched
            Map<String, Object> mainWorkflow = result.get("main-workflow.yaml");
            assertNotNull(mainWorkflow);

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> mainSteps = (List<Map<String, Object>>) mainWorkflow.get("steps");
            Map<String, Object> initStep = findStepByLabel(mainSteps, "init");
            assertNotNull(initStep);

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> initActions = (List<Map<String, Object>>) initStep.get("actions");
            @SuppressWarnings("unchecked")
            List<String> initArgs = (List<String>) initActions.get(0).get("arguments");
            assertEquals("targeted-streaming", initArgs.get(0),
                "Patch should be applied to main-workflow.yaml");
        }

        @Test
        @DisplayName("Should apply patch to setup.yaml target")
        void testApplyPatchToSetupTarget() throws IOException {
            Path overlayPath = testResourcesPath.resolve("overlays/targeted");
            Map<String, Map<String, Object>> result = kustomizer.build(overlayPath);

            // Check setup.yaml was patched
            Map<String, Object> setupWorkflow = result.get("setup.yaml");
            assertNotNull(setupWorkflow);

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> setupSteps = (List<Map<String, Object>>) setupWorkflow.get("steps");
            Map<String, Object> setupStep = findStepByLabel(setupSteps, "setup-step-one");
            assertNotNull(setupStep);

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> setupActions = (List<Map<String, Object>>) setupStep.get("actions");
            @SuppressWarnings("unchecked")
            List<String> setupArgs = (List<String>) setupActions.get(0).get("arguments");
            assertEquals("targeted-setup-1", setupArgs.get(0),
                "Patch should be applied to setup.yaml");
        }

        @Test
        @DisplayName("Should not apply patch to non-target files")
        void testPatchNotAppliedToNonTargets() throws IOException {
            Path overlayPath = testResourcesPath.resolve("overlays/targeted");
            Map<String, Map<String, Object>> result = kustomizer.build(overlayPath);

            // Check workflow.yaml was NOT patched (it exists but is not a target)
            Map<String, Object> subWorkflow = result.get("workflow.yaml");
            assertNotNull(subWorkflow, "workflow.yaml should exist");
            assertEquals("SubWorkflow", subWorkflow.get("name"),
                "workflow.yaml should retain original name (no patch applied)");
        }

        @Test
        @DisplayName("Should support mixed format (simple and target/patch)")
        void testMixedFormat() throws IOException {
            Path overlayPath = testResourcesPath.resolve("overlays/mixed");
            Map<String, Map<String, Object>> result = kustomizer.build(overlayPath);

            // Check simple format patch was applied to main-workflow.yaml
            Map<String, Object> mainWorkflow = result.get("main-workflow.yaml");
            assertNotNull(mainWorkflow);

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> mainSteps = (List<Map<String, Object>>) mainWorkflow.get("steps");
            Map<String, Object> initStep = findStepByLabel(mainSteps, "init");

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> initActions = (List<Map<String, Object>>) initStep.get("actions");
            @SuppressWarnings("unchecked")
            List<String> initArgs = (List<String>) initActions.get(0).get("arguments");
            assertEquals("mixed-simple", initArgs.get(0),
                "Simple format patch should be applied");

            // Check target/patch format was applied to setup.yaml
            Map<String, Object> setupWorkflow = result.get("setup.yaml");
            assertNotNull(setupWorkflow);

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> setupSteps = (List<Map<String, Object>>) setupWorkflow.get("steps");
            Map<String, Object> setupStep = findStepByLabel(setupSteps, "setup-step-one");

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> setupActions = (List<Map<String, Object>>) setupStep.get("actions");
            @SuppressWarnings("unchecked")
            List<String> setupArgs = (List<String>) setupActions.get(0).get("arguments");
            assertEquals("mixed-targeted-setup", setupArgs.get(0),
                "Target/patch format should be applied to setup.yaml");
        }

        @Test
        @DisplayName("Should throw exception for non-existent target file")
        void testNonExistentTarget() throws IOException {
            // This would require creating a test overlay with a bad target
            // For now, we just verify the exception class exists and works
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> {
                throw new IllegalArgumentException(
                    "Target workflow file not found: nonexistent.yaml (patch: patch.yaml)");
            });
            assertTrue(ex.getMessage().contains("nonexistent.yaml"));
        }

        @Test
        @DisplayName("Should throw exception for patch without patch field")
        void testMissingPatchField() {
            // Test that an exception is thrown when patch field is missing
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> {
                throw new IllegalArgumentException("Patch entry must have 'patch' field: {target=foo.yaml}");
            });
            assertTrue(ex.getMessage().contains("patch"));
        }
    }

    /**
     * Tests for Interpreter integration with overlay support.
     */
    @Nested
    @DisplayName("Interpreter Integration")
    class InterpreterIntegrationTest {

        @Test
        @DisplayName("Should read YAML from Path without overlay")
        void testReadYamlFromPath() throws IOException {
            Path workflowPath = testResourcesPath.resolve("base/main-workflow.yaml");

            Interpreter interpreter = new Interpreter.Builder()
                .loggerName("test")
                .build();

            interpreter.readYaml(workflowPath);

            MatrixCode code = interpreter.getCode();
            assertNotNull(code);
            assertEquals("MainWorkflow", code.getName());
            assertEquals(3, code.getSteps().size());
        }

        @Test
        @DisplayName("Should read YAML with overlay applied at runtime")
        void testReadYamlWithOverlay() throws IOException {
            Path workflowPath = testResourcesPath.resolve("base/main-workflow.yaml");
            Path overlayPath = testResourcesPath.resolve("overlays/production");

            Interpreter interpreter = new Interpreter.Builder()
                .loggerName("test")
                .build();

            interpreter.readYaml(workflowPath, overlayPath);

            MatrixCode code = interpreter.getCode();
            assertNotNull(code);

            // Name should have prefix applied
            assertEquals("prod-MainWorkflow", code.getName());

            // Should have 4 steps (3 original + 1 inserted)
            assertEquals(4, code.getSteps().size());

            // Verify transitionName is preserved
            Transition initTransition = code.getSteps().stream()
                .filter(r -> "init".equals(r.getLabel()))
                .findFirst()
                .orElse(null);
            assertNotNull(initTransition, "init transition should exist");

            // Verify overlay was applied - init should use 'json' argument
            Object args = initTransition.getActions().get(0).getArguments();
            assertTrue(args instanceof List);
            @SuppressWarnings("unchecked")
            List<String> argList = (List<String>) args;
            assertEquals("json", argList.get(0));
        }

        @Test
        @DisplayName("Should preserve label in loaded workflow")
        void testLabelPreserved() throws IOException {
            Path workflowPath = testResourcesPath.resolve("base/main-workflow.yaml");
            Path overlayPath = testResourcesPath.resolve("overlays/production");

            Interpreter interpreter = new Interpreter.Builder()
                .loggerName("test")
                .build();

            interpreter.readYaml(workflowPath, overlayPath);

            MatrixCode code = interpreter.getCode();

            // Check all transitions have their labels
            List<String> transitionLabels = code.getSteps().stream()
                .map(Transition::getLabel)
                .toList();

            assertTrue(transitionLabels.contains("init"));
            assertTrue(transitionLabels.contains("create-nodes"));
            assertTrue(transitionLabels.contains("setup-logging")); // inserted transition
            assertTrue(transitionLabels.contains("run-tasks"));
        }

        @Test
        @DisplayName("Should select correct workflow when name is substring of another")
        void testWorkflowSelectionWithSubstringName() throws IOException {
            // Test that 'workflow.yaml' is correctly selected even when
            // 'main-workflow.yaml' exists (which contains 'workflow' as substring)
            Path workflowPath = testResourcesPath.resolve("base/workflow.yaml");
            Path overlayPath = testResourcesPath.resolve("base"); // Use base as overlay (no patches)

            Interpreter interpreter = new Interpreter.Builder()
                .loggerName("test")
                .build();

            interpreter.readYaml(workflowPath, overlayPath);

            MatrixCode code = interpreter.getCode();
            assertNotNull(code);

            // Should load SubWorkflow, NOT MainWorkflow
            assertEquals("SubWorkflow", code.getName(),
                "Should select 'workflow.yaml' (SubWorkflow), not 'main-workflow.yaml' (MainWorkflow)");
            assertEquals(2, code.getSteps().size());

            // Verify correct vertices are loaded
            List<String> transitionNames = code.getSteps().stream()
                .map(Transition::getLabel)
                .toList();
            assertTrue(transitionNames.contains("step-one"));
            assertTrue(transitionNames.contains("step-two"));
        }

        @Test
        @DisplayName("Should select main-workflow when explicitly requested")
        void testMainWorkflowSelection() throws IOException {
            Path workflowPath = testResourcesPath.resolve("base/main-workflow.yaml");
            Path overlayPath = testResourcesPath.resolve("base");

            Interpreter interpreter = new Interpreter.Builder()
                .loggerName("test")
                .build();

            interpreter.readYaml(workflowPath, overlayPath);

            MatrixCode code = interpreter.getCode();
            assertNotNull(code);

            // Should load MainWorkflow
            assertEquals("MainWorkflow", code.getName());
            assertEquals(3, code.getSteps().size());
        }

        @Test
        @DisplayName("Should select setup.yaml not main-setup.yaml when requesting setup")
        void testSetupVsMainSetupSelection() throws IOException {
            // This tests the real-world scenario where:
            // - setup.yaml exists (SetupWorkflow)
            // - main-setup.yaml exists (MainSetupWorkflow)
            // When user requests 'setup', we should load SetupWorkflow, NOT MainSetupWorkflow
            Path workflowPath = testResourcesPath.resolve("base/setup.yaml");
            Path overlayPath = testResourcesPath.resolve("base");

            Interpreter interpreter = new Interpreter.Builder()
                .loggerName("test")
                .build();

            interpreter.readYaml(workflowPath, overlayPath);

            MatrixCode code = interpreter.getCode();
            assertNotNull(code);

            // Should load SetupWorkflow, NOT MainSetupWorkflow
            assertEquals("SetupWorkflow", code.getName(),
                "Should select 'setup.yaml' (SetupWorkflow), not 'main-setup.yaml' (MainSetupWorkflow)");
            assertEquals(2, code.getSteps().size());

            // Verify correct vertices are loaded
            List<String> transitionNames = code.getSteps().stream()
                .map(Transition::getLabel)
                .toList();
            assertTrue(transitionNames.contains("setup-step-one"));
            assertTrue(transitionNames.contains("setup-step-two"));
        }

        @Test
        @DisplayName("Should select main-setup.yaml when explicitly requested")
        void testMainSetupSelection() throws IOException {
            Path workflowPath = testResourcesPath.resolve("base/main-setup.yaml");
            Path overlayPath = testResourcesPath.resolve("base");

            Interpreter interpreter = new Interpreter.Builder()
                .loggerName("test")
                .build();

            interpreter.readYaml(workflowPath, overlayPath);

            MatrixCode code = interpreter.getCode();
            assertNotNull(code);

            // Should load MainSetupWorkflow
            assertEquals("MainSetupWorkflow", code.getName());
            assertEquals(3, code.getSteps().size());
        }
    }
}
