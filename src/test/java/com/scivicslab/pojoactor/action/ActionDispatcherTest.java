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

import static org.junit.jupiter.api.Assertions.*;

import com.scivicslab.pojoactor.action.schema.ActionSchemaRegistry;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.scivicslab.pojoactor.core.ActorSystem;
import com.scivicslab.pojoactor.core.ActorRef;

/**
 * Verifies state S2.03: ActionDispatcher and AbstractCallableByActionName.
 */
@Tag("S_svc.04")
@DisplayName("ActionDispatcher — @Action annotation dispatch (S2.04)")
public class ActionDispatcherTest {

    // -------------------------------------------------------------------------
    // Test actor using AbstractCallableByActionName
    // -------------------------------------------------------------------------

    static class MathActorAnnotated extends AbstractCallableByActionName {
        private int lastResult = 0;

        @Action("add")
        public ActionResult add(String args) {
            String[] p = args.split(",");
            if (p.length != 2) return new ActionResult(false, "add requires a,b");
            lastResult = Integer.parseInt(p[0].trim()) + Integer.parseInt(p[1].trim());
            return new ActionResult(true, String.valueOf(lastResult));
        }

        @Action("multiply")
        public ActionResult multiply(String args) {
            String[] p = args.split(",");
            if (p.length != 2) return new ActionResult(false, "multiply requires a,b");
            lastResult = Integer.parseInt(p[0].trim()) * Integer.parseInt(p[1].trim());
            return new ActionResult(true, String.valueOf(lastResult));
        }

        @Action("getLastResult")
        public ActionResult getLastResult(String args) {
            return new ActionResult(true, String.valueOf(lastResult));
        }
    }

    // -------------------------------------------------------------------------
    // Tests — AbstractCallableByActionName
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("AbstractCallableByActionName dispatch")
    class AbstractBase {

        private MathActorAnnotated actor;

        @BeforeEach
        void setUp() { actor = new MathActorAnnotated(); }

        @Test
        @DisplayName("add: @Action dispatch returns correct result")
        void addReturnsCorrectResult() {
            ActionResult r = actor.callByActionName("add", "5,3");
            assertTrue(r.isSuccess());
            assertEquals("8", r.getResult());
        }

        @Test
        @DisplayName("multiply: @Action dispatch returns correct result")
        void multiplyReturnsCorrectResult() {
            ActionResult r = actor.callByActionName("multiply", "4,2");
            assertTrue(r.isSuccess());
            assertEquals("8", r.getResult());
        }

        @Test
        @DisplayName("getLastResult: returns result of previous operation")
        void getLastResultReturnsPreviousResult() {
            actor.callByActionName("add", "10,5");
            ActionResult r = actor.callByActionName("getLastResult", "");
            assertTrue(r.isSuccess());
            assertEquals("15", r.getResult());
        }

        @Test
        @DisplayName("unknown action name returns success=false")
        void unknownActionReturnsFalse() {
            ActionResult r = actor.callByActionName("divide", "10,2");
            assertFalse(r.isSuccess());
        }

        @Test
        @DisplayName("invalid args format returns success=false without throwing")
        void invalidArgsReturnsFalse() {
            ActionResult r = actor.callByActionName("add", "five,three");
            assertFalse(r.isSuccess());
        }

        @Test
        @DisplayName("state is local to each instance")
        void stateIsLocalToInstance() {
            MathActorAnnotated a1 = new MathActorAnnotated();
            MathActorAnnotated a2 = new MathActorAnnotated();

            a1.callByActionName("add", "10,5");

            assertEquals("15", a1.callByActionName("getLastResult", "").getResult());
            assertEquals("0",  a2.callByActionName("getLastResult", "").getResult());
        }

    }

    // -------------------------------------------------------------------------
    // Tests — ActionDispatcher used as delegate field
    // -------------------------------------------------------------------------

    static class MathActorDelegate implements CallableByActionName {
        private int lastResult = 0;
        private final ActionDispatcher dispatcher = new ActionDispatcher(this);

        @Action("add")
        public ActionResult add(String args) {
            String[] p = args.split(",");
            if (p.length != 2) return new ActionResult(false, "add requires a,b");
            lastResult = Integer.parseInt(p[0].trim()) + Integer.parseInt(p[1].trim());
            return new ActionResult(true, String.valueOf(lastResult));
        }

        @Action("getLastResult")
        public ActionResult getLastResult(String args) {
            return new ActionResult(true, String.valueOf(lastResult));
        }

        @Override
        public ActionResult callByActionName(String actionName, String args) {
            ActionResult r = dispatcher.invoke(actionName, args);
            if (r != null) return r;
            return new ActionResult(false, "Unknown action: " + actionName);
        }
    }

    @Nested
    @DisplayName("ActionDispatcher as delegate field")
    class DelegateField {

        private MathActorDelegate actor;

        @BeforeEach
        void setUp() { actor = new MathActorDelegate(); }

        @Test
        @DisplayName("add via delegate dispatcher returns correct result")
        void addViaDelegate() {
            ActionResult r = actor.callByActionName("add", "6,7");
            assertTrue(r.isSuccess());
            assertEquals("13", r.getResult());
        }

        @Test
        @DisplayName("unknown action returns success=false via delegate")
        void unknownActionViaDelegate() {
            ActionResult r = actor.callByActionName("unknown", "");
            assertFalse(r.isSuccess());
        }
    }

    // -------------------------------------------------------------------------
    // Test actor using @Action(argsType=...) — record-typed arguments
    // -------------------------------------------------------------------------

    record ConfigureArgs(String hostname, int port, boolean ssl) {}

    static class NodeActorAnnotated extends AbstractCallableByActionName {

        // Mixed with a plain-String @Action in the same class, matching the existing
        // "3 formats may coexist" convention — only the format per method changes.
        @Action("greet")
        public ActionResult greet(String args) {
            return new ActionResult(true, "Hello, " + args);
        }

        @Action(value = "configure", argsType = ConfigureArgs.class)
        public ActionResult configure(ConfigureArgs args) {
            return new ActionResult(true,
                    args.hostname() + ":" + args.port() + " ssl=" + args.ssl());
        }
    }

    @Nested
    @DisplayName("@Action(argsType=...) — record-typed argument dispatch")
    class ArgsTypeDispatch {

        private NodeActorAnnotated actor;

        @BeforeEach
        void setUp() { actor = new NodeActorAnnotated(); }

        @Test
        @DisplayName("configure: valid JSON is deserialized into the record before invocation")
        void configure_validJson_deserializesIntoRecord() {
            ActionResult r = actor.callByActionName("configure",
                    "{\"hostname\":\"worker1.internal\",\"port\":8080,\"ssl\":true}");
            assertTrue(r.isSuccess());
            assertEquals("worker1.internal:8080 ssl=true", r.getResult());
        }

        @Test
        @DisplayName("configure: malformed JSON returns success=false without throwing")
        void configure_malformedJson_returnsFalse() {
            ActionResult r = actor.callByActionName("configure", "not-json");
            assertFalse(r.isSuccess());
            assertTrue(r.getResult().contains("configure"));
        }

        @Test
        @DisplayName("greet: plain-String @Action on the same class is unaffected by argsType methods")
        void greet_plainStringAction_stillWorks() {
            ActionResult r = actor.callByActionName("greet", "World");
            assertTrue(r.isSuccess());
            assertEquals("Hello, World", r.getResult());
        }

        @Test
        @DisplayName("a method whose parameter doesn't match argsType is not registered")
        void argsTypeMismatch_methodNotRegistered() {
            class Mismatched extends AbstractCallableByActionName {
                @Action(value = "configure", argsType = ConfigureArgs.class)
                public ActionResult configure(String args) {  // wrong: should be ConfigureArgs
                    return new ActionResult(true, args);
                }
            }
            ActionResult r = new Mismatched().callByActionName("configure", "{}");
            assertFalse(r.isSuccess());
        }
    }

    // -------------------------------------------------------------------------
    // Test actor using ActionDispatcher(target, ActionSchemaRegistry) — dispatch-time validation
    // -------------------------------------------------------------------------

    static class NodeActorWithSchema implements CallableByActionName {
        private final ActionDispatcher dispatcher;

        NodeActorWithSchema(ActionSchemaRegistry registry) {
            this.dispatcher = new ActionDispatcher(this, registry);
        }

        @Action(value = "configure", argsType = ConfigureArgs.class)
        public ActionResult configure(ConfigureArgs args) {
            return new ActionResult(true, args.hostname());
        }

        @Override
        public ActionResult callByActionName(String actionName, String args) {
            ActionResult r = dispatcher.invoke(actionName, args);
            return r != null ? r : new ActionResult(false, "Unknown action: " + actionName);
        }
    }

    @Nested
    @DisplayName("@Action(argsType=...) — dispatch-time schema validation via ActionSchemaRegistry")
    class ArgsTypeDispatchWithSchemaValidation {

        private static final String SCHEMA_REQUIRING_HOSTNAME =
                "{\"type\":\"object\",\"properties\":{"
                + "\"hostname\":{\"type\":\"string\"},\"port\":{\"type\":\"integer\"},\"ssl\":{\"type\":\"boolean\"}},"
                + "\"required\":[\"hostname\"]}";

        private ActionSchemaRegistry registryWithSchema(Path tempDir, String schemaJson) throws IOException {
            Path schemasDir = tempDir.resolve("action-schemas");
            Files.createDirectories(schemasDir);
            Files.writeString(
                    schemasDir.resolve(NodeActorWithSchema.class.getName() + ".configure.schema.json"),
                    schemaJson);
            URLClassLoader loader = new URLClassLoader(new URL[]{tempDir.toUri().toURL()}, null);
            return new ActionSchemaRegistry(loader, "action-schemas");
        }

        @Test
        @DisplayName("valid args satisfying the schema are deserialized and invoked as usual")
        void validArgs_satisfiesSchema_dispatchesNormally(@TempDir Path tempDir) throws IOException {
            NodeActorWithSchema actor =
                    new NodeActorWithSchema(registryWithSchema(tempDir, SCHEMA_REQUIRING_HOSTNAME));

            ActionResult r = actor.callByActionName("configure",
                    "{\"hostname\":\"worker1\",\"port\":8080,\"ssl\":true}");

            assertTrue(r.isSuccess());
            assertEquals("worker1", r.getResult());
        }

        @Test
        @DisplayName("args missing a required field are rejected before deserialization/invocation")
        void missingRequiredField_rejectedBySchema_beforeInvocation(@TempDir Path tempDir) throws IOException {
            NodeActorWithSchema actor =
                    new NodeActorWithSchema(registryWithSchema(tempDir, SCHEMA_REQUIRING_HOSTNAME));

            ActionResult r = actor.callByActionName("configure", "{\"port\":8080,\"ssl\":true}");

            assertFalse(r.isSuccess());
            assertTrue(r.getResult().contains("configure"));
        }

        @Test
        @DisplayName("no registered schema for the action: validation is skipped, dispatch proceeds")
        void noSchemaRegisteredForAction_validationSkipped(@TempDir Path tempDir) throws IOException {
            URLClassLoader emptyLoader = new URLClassLoader(new URL[]{tempDir.toUri().toURL()}, null);
            ActionSchemaRegistry emptyRegistry = new ActionSchemaRegistry(emptyLoader, "action-schemas");
            NodeActorWithSchema actor = new NodeActorWithSchema(emptyRegistry);

            // Would fail the "hostname required" schema if one were registered, but none is here
            // — falls through to plain deserialization, which succeeds (hostname defaults null).
            ActionResult r = actor.callByActionName("configure", "{\"port\":8080,\"ssl\":true}");

            assertTrue(r.isSuccess());
        }

        @Test
        @DisplayName("schemaRegistry=null: validation is skipped entirely, matching the 1-arg constructor")
        void nullSchemaRegistry_validationSkipped() {
            NodeActorWithSchema actor = new NodeActorWithSchema(null);

            ActionResult r = actor.callByActionName("configure", "{\"port\":8080,\"ssl\":true}");

            assertTrue(r.isSuccess());
        }
    }

    // -------------------------------------------------------------------------
    // Tests — ActorRef integration
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("ActorRef integration")
    class WithActorRef {

        private ActorSystem system;

        @BeforeEach
        void setUp() { system = new ActorSystem("test"); }

        @AfterEach
        void tearDown() { system.terminate(); }

        @Test
        @DisplayName("@Action actor processes callByActionName via ask()")
        void annotationActorViaActorRef() throws Exception {
            ActorRef<MathActorAnnotated> math = system.actorOf("math", new MathActorAnnotated());

            ActionResult result = math.ask(m -> m.callByActionName("multiply", "6,7"))
                                      .get(3, TimeUnit.SECONDS);

            assertTrue(result.isSuccess());
            assertEquals("42", result.getResult());
        }
    }
}
