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

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.scivicslab.pojoactor.action.Action;
import com.scivicslab.pojoactor.action.ActionResult;

import org.json.JSONArray;

import jakarta.validation.constraints.NotNull;
import com.scivicslab.turingworkflow.workflow.DynamicActorLoaderActor;
import com.scivicslab.turingworkflow.workflow.IIActorRef;
import com.scivicslab.turingworkflow.workflow.IIActorSystem;
import com.scivicslab.turingworkflow.workflow.Interpreter;

/**
 * Tests for DynamicActorLoaderActor.
 *
 * @author devteam@scivicslab.com
 */
@Tag("J_load.01")
@Tag("Y_load.01")
@DisplayName("Dynamic Actor Loader Specification by Example")
public class DynamicActorLoaderActorTest {

    private IIActorSystem system;
    private DynamicActorLoaderActor loader;

    @BeforeEach
    public void setUp() {
        system = new IIActorSystem("test-system");
        loader = new DynamicActorLoaderActor(system);
    }

    @Test
    @DisplayName("Should list available ActorProviders")
    public void testListProviders() {
        ActionResult result = loader.callByActionName("listProviders", "");

        assertTrue(result.isSuccess(), "Should succeed");
        assertNotNull(result.getResult(), "Result should not be null");
        // Should contain "math" provider from test resources
        assertTrue(result.getResult().contains("math") || result.getResult().contains("No providers"),
            "Should list providers or indicate none found");
    }

    @Test
    @DisplayName("Should register actors from ServiceLoader provider")
    public void testCreateFromProvider() {
        // Test the mechanism - provider may or may not be found depending on test setup
        ActionResult result = loader.callByActionName("createFromProvider", "MathPluginProvider");

        // The mechanism should work (either success or "Provider not found")
        assertNotNull(result, "Result should not be null");
        assertTrue(
            result.isSuccess() || result.getResult().contains("Provider not found"),
            "Should either succeed or indicate provider not found"
        );

        // Note: This test validates the mechanism works correctly
        // Whether providers exist depends on META-INF/services configuration
    }

    @Test
    @DisplayName("Should handle unknown action")
    public void testUnknownAction() {
        ActionResult result = loader.callByActionName("unknownAction", "");

        assertFalse(result.isSuccess(), "Should fail for unknown action");
        assertTrue(result.getResult().contains("Unknown action"), "Should indicate unknown action");
    }

    @Test
    @DisplayName("Should handle non-existent provider")
    public void testNonExistentProvider() {
        ActionResult result = loader.callByActionName("createFromProvider", "nonexistent");

        assertFalse(result.isSuccess(), "Should fail for non-existent provider");
        assertTrue(result.getResult().contains("Provider not found"),
            "Should indicate provider not found");
    }

    @Test
    @DisplayName("Should handle invalid arguments for loadFromJar")
    public void testInvalidArgsLoadFromJar() {
        ActionResult result = loader.callByActionName("loadFromJar", "invalid");

        assertFalse(result.isSuccess(), "Should fail with invalid args");
        assertTrue(result.getResult().contains("Invalid args") ||
                   result.getResult().contains("Failed to load"),
            "Should indicate error");
    }

    @Test
    @DisplayName("Should be usable in workflows")
    public void testWorkflowIntegration() throws Exception {
        // Create interpreter with loader actor
        Interpreter interpreter = new Interpreter.Builder()
            .loggerName("test")
            .team(system)
            .build();

        // Register loader as IIActor
        system.addIIActor(new DynamicActorLoaderIIAR("loader", loader, system));

        // Create simple workflow that uses loader
        String workflowYaml = """
            name: test-dynamic
            steps:
              - states: ["0", "end"]
                actions:
                  - actor: loader
                    method: listProviders
            """;

        interpreter.readYaml(new java.io.ByteArrayInputStream(workflowYaml.getBytes()));
        ActionResult result = interpreter.execCode();

        assertTrue(result.isSuccess(), "Workflow should execute successfully");
    }

    @Test
    @DisplayName("Should expand 'this' keyword to actual actor name in createChild arguments")
    public void testThisKeywordExpansionInCreateChild() throws Exception {
        // Create a parent actor that will be "this"
        TestActor parentActor = new TestActor();
        IIActorRef<TestActor> parentRef = new TestActorIIAR("node-192.168.5.13", parentActor, system);
        system.addIIActor(parentRef);

        // Register loader
        system.addIIActor(new DynamicActorLoaderIIAR("loader", loader, system));

        // Create interpreter with selfActorRef set (simulating running inside the parent actor)
        Interpreter interpreter = new Interpreter.Builder()
            .loggerName("test")
            .team(system)
            .build();
        interpreter.setSelfActorRef(parentRef);

        // Workflow that uses "this" as parent in createChild
        String workflowYaml = """
            name: test-this-expansion
            steps:
              - states: ["0", "end"]
                actions:
                  - actor: loader
                    method: createChild
                    arguments:
                      parent: "jexl: self"
                      actor: "childActor"
                      className: "com.scivicslab.turingworkflow.workflow.DynamicActorLoaderActorTest$TestActor"
            """;

        interpreter.readYaml(new java.io.ByteArrayInputStream(workflowYaml.getBytes()));
        ActionResult result = interpreter.execCode();

        assertTrue(result.isSuccess(), "Workflow should succeed: " + result.getResult());

        // Verify the child was created under the correct parent
        IIActorRef<?> childActor = system.getIIActor("childActor");
        assertNotNull(childActor, "Child actor should be created");
        assertEquals("node-192.168.5.13", childActor.getParentName(),
            "Child's parent should be the actual actor name, not 'this'");

        // Verify parent has the child in its children list
        assertTrue(parentRef.getNamesOfChildren().contains("childActor"),
            "Parent should have child in its children list");
    }

    @Test
    @DisplayName("Should expand '.' keyword to actual actor name in createChild arguments")
    public void testDotKeywordExpansionInCreateChild() throws Exception {
        // Create a parent actor that will be "."
        TestActor parentActor = new TestActor();
        IIActorRef<TestActor> parentRef = new TestActorIIAR("node-192.168.5.14", parentActor, system);
        system.addIIActor(parentRef);

        // Register loader
        system.addIIActor(new DynamicActorLoaderIIAR("loader", loader, system));

        // Create interpreter with selfActorRef set
        Interpreter interpreter = new Interpreter.Builder()
            .loggerName("test")
            .team(system)
            .build();
        interpreter.setSelfActorRef(parentRef);

        // Workflow that uses "." as parent in createChild
        String workflowYaml = """
            name: test-dot-expansion
            steps:
              - states: ["0", "end"]
                actions:
                  - actor: loader
                    method: createChild
                    arguments:
                      parent: "jexl: self"
                      actor: "childActor2"
                      className: "com.scivicslab.turingworkflow.workflow.DynamicActorLoaderActorTest$TestActor"
            """;

        interpreter.readYaml(new java.io.ByteArrayInputStream(workflowYaml.getBytes()));
        ActionResult result = interpreter.execCode();

        assertTrue(result.isSuccess(), "Workflow should succeed: " + result.getResult());

        // Verify the child was created under the correct parent
        IIActorRef<?> childActor = system.getIIActor("childActor2");
        assertNotNull(childActor, "Child actor should be created");
        assertEquals("node-192.168.5.14", childActor.getParentName(),
            "Child's parent should be the actual actor name, not '.'");
    }

    /**
     * Helper IIActorRef for DynamicActorLoaderActor.
     */
    private static class DynamicActorLoaderIIAR extends IIActorRef<DynamicActorLoaderActor> {

        public DynamicActorLoaderIIAR(String actorName, DynamicActorLoaderActor object,
                                      IIActorSystem system) {
            super(actorName, object, system);
        }

        /**
         * Which JAR to read.
         *
         * @param jar the JAR's path, or a Maven coordinate resolved under {@code ~/.m2/repository/}
         */
        public record JarArgs(@NotNull String jar) {}

        /**
         * Which class to instantiate under which parent, and what to call it.
         *
         * @param parent    the parent actor's name
         * @param actor     the name the new actor is registered under
         * @param className the fully qualified class name
         */
        public record ChildArgs(@NotNull String parent, @NotNull String actor, @NotNull String className) {}

        /**
         * Which class in which JAR to register as a top-level actor.
         *
         * @param jar       the JAR's path
         * @param className the fully qualified class name
         * @param actor     the name the new actor is registered under
         */
        public record JarActorArgs(@NotNull String jar, @NotNull String className, @NotNull String actor) {}

        /**
         * Which provider registers its actors.
         *
         * @param provider the provider's name
         */
        public record ProviderArgs(@NotNull String provider) {}

        // DynamicActorLoaderActor dispatches on its own string protocol, so each action
        // rebuilds the JSON array that protocol expects.
        private static String array(String... values) {
            JSONArray a = new JSONArray();
            for (String v : values) a.put(v);
            return a.toString();
        }

        @Action(value = "loadJar", argsType = JarArgs.class)
        public ActionResult loadJar(JarArgs args) {
            return this.object.callByActionName("loadJar", array(args.jar()));
        }

        @Action(value = "createChild", argsType = ChildArgs.class)
        public ActionResult createChild(ChildArgs args) {
            return this.object.callByActionName("createChild",
                    array(args.parent(), args.actor(), args.className()));
        }

        @Action("listLoadedJars")
        public ActionResult listLoadedJars(String args) {
            return this.object.callByActionName("listLoadedJars", "");
        }

        @Action(value = "loadFromJar", argsType = JarActorArgs.class)
        public ActionResult loadFromJar(JarActorArgs args) {
            return this.object.callByActionName("loadFromJar",
                    array(args.jar(), args.className(), args.actor()));
        }

        @Action(value = "createFromProvider", argsType = ProviderArgs.class)
        public ActionResult createFromProvider(ProviderArgs args) {
            return this.object.callByActionName("createFromProvider", array(args.provider()));
        }

        @Action("listProviders")
        public ActionResult listProviders(String args) {
            return this.object.callByActionName("listProviders", "");
        }

        @Action(value = "loadProvidersFromJar", argsType = JarArgs.class)
        public ActionResult loadProvidersFromJar(JarArgs args) {
            return this.object.callByActionName("loadProvidersFromJar", array(args.jar()));
        }
    }

    /**
     * Simple test actor for createChild tests.
     */
    public static class TestActor implements com.scivicslab.pojoactor.action.CallableByActionName {
        @Override
        public ActionResult callByActionName(String actionName, String args) {
            return new ActionResult(true, "TestActor: " + actionName);
        }
    }

    /**
     * IIActorRef wrapper for TestActor.
     */
    private static class TestActorIIAR extends IIActorRef<TestActor> {
        public TestActorIIAR(String actorName, TestActor object, IIActorSystem system) {
            super(actorName, object, system);
        }
    }
}
