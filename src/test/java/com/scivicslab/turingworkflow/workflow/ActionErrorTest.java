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

package com.scivicslab.turingworkflow.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.scivicslab.pojoactor.action.Action;
import com.scivicslab.pojoactor.action.ActionResult;
import com.scivicslab.pojoactor.core.JsonState;

/**
 * Verifies that an {@code Error} thrown by an action follows the same rule as an exception: the
 * action is false, and its reason is in the last result as
 * {@code error: <class>: <message> (in <actor>.<method>)}, so the workflow goes on and decides.
 *
 * <p>The fatal rule is POJO-actor's: a {@link VirtualMachineError} other than
 * {@link StackOverflowError} is recorded as the last result and then rethrown out of the run, so a
 * failing JVM does not carry on. {@code StackOverflowError} and every other {@code Error} only make
 * the action false.</p>
 */
@DisplayName("An Error thrown by an action is false; a fatal one is recorded, then rethrown")
class ActionErrorTest {

    /** A test-only fatal error, as POJO-actor's ActorRefErrorTest uses. */
    static final class TestFatalError extends VirtualMachineError {
        TestFatalError(String message) {
            super(message);
        }
    }

    private IIActorSystem system;
    private Interpreter interpreter;
    private InterpreterIIAR interpreterActor;
    private final List<String> recorded = new CopyOnWriteArrayList<>();

    /** An actor whose {@code connect} throws what the test gives it, from callByActionName. */
    static final class Thrower extends IIActorRef<Object> {
        private final Supplier<Throwable> failure;

        Thrower(String name, IIActorSystem system, Supplier<Throwable> failure) {
            super(name, new Object(), system);
            this.failure = failure;
        }

        @Override
        public ActionResult callByActionName(String actionName, String args) {
            if ("connect".equals(actionName) || "work".equals(actionName)) {
                throw ActionFailureReasonTest.sneaky(failure.get());
            }
            return super.callByActionName(actionName, args);
        }
    }

    /** An actor whose {@code @Action connect} throws a fatal error, dispatched by reflection. */
    public static final class FatalByAction extends IIActorRef<Object> {
        public FatalByAction(IIActorSystem system) {
            super("net", new Object(), system);
        }

        @Action("connect")
        public ActionResult connect(String args) {
            throw new TestFatalError("dying");
        }
    }

    @BeforeEach
    void setUp() {
        system = new IIActorSystem("action-error-test", 2);
        interpreter = new Interpreter.Builder()
                .loggerName("action-error-test")
                .team(system)
                .build();
        interpreterActor = new InterpreterIIAR("main", interpreter, system);
        interpreter.setSelfActorRef(interpreterActor);
        system.addIIActor(interpreterActor);
        system.addIIActor(new IIActorRef<Object>("recorder", new Object(), system) {
            @Override
            public ActionResult callByActionName(String actionName, String args) {
                recorded.add(args);
                return new ActionResult(true, "recorded");
            }
        });
    }

    @AfterEach
    void tearDown() {
        system.terminateIIActors();
        system.terminate();
    }

    private void net(Supplier<Throwable> failure) {
        system.addIIActor(new Thrower("net", system, failure));
    }

    private ActionResult runResource(String resource) throws Exception {
        interpreter.readYaml(getClass().getResourceAsStream(resource));
        return CompletableFuture.supplyAsync(() -> interpreter.runUntilEnd(20))
                .get(10, TimeUnit.SECONDS);
    }

    private ActionResult runText(String yaml) throws Exception {
        interpreter.readYaml(new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)));
        return CompletableFuture.supplyAsync(() -> interpreter.runUntilEnd(20))
                .get(10, TimeUnit.SECONDS);
    }

    /** Runs the resource and returns what the run threw out of runUntilEnd. */
    private Throwable runResourceExpectingThrow(String resource) {
        interpreter.readYaml(getClass().getResourceAsStream(resource));
        ExecutionException e = assertThrows(ExecutionException.class,
                () -> CompletableFuture.supplyAsync(() -> interpreter.runUntilEnd(20))
                        .get(10, TimeUnit.SECONDS),
                "the fatal error should be rethrown out of the run");
        return e.getCause();
    }

    private void assertRecordedReason(String expected) {
        assertEquals(1, recorded.size(), "the fallback transition should run once and read the reason");
        assertTrue(recorded.get(0).contains(expected), recorded.get(0));
    }

    // ---- DIRECT ----------------------------------------------------------------------------------

    @Test
    @DisplayName("An AssertionError from a DIRECT action is false, and the next transition reads it")
    void directError() throws Exception {
        net(() -> new AssertionError("boom"));

        ActionResult result = runResource("/workflows/failure-reason-direct.yaml");

        assertTrue(result.isSuccess(), result.getResult());
        assertRecordedReason("error: java.lang.AssertionError: boom (in net.connect)");
    }

    @Test
    @DisplayName("A StackOverflowError from a DIRECT action is false, and the run goes on")
    void directStackOverflow() throws Exception {
        net(() -> new StackOverflowError("deep"));

        ActionResult result = runResource("/workflows/failure-reason-direct.yaml");

        assertTrue(result.isSuccess(), result.getResult());
        assertRecordedReason("error: java.lang.StackOverflowError: deep (in net.connect)");
    }

    @Test
    @DisplayName("A fatal error from a DIRECT action is recorded as the last result, then rethrown")
    void directFatal() {
        TestFatalError fatal = new TestFatalError("dying");
        net(() -> fatal);

        Throwable thrown = runResourceExpectingThrow("/workflows/failure-reason-direct.yaml");

        assertSame(fatal, thrown);
        assertTrue(recorded.isEmpty(), "no transition should run after a fatal error");
        assertLastResultNames("ActionErrorTest$TestFatalError: dying (in net.connect)");
    }

    // ---- POOL ------------------------------------------------------------------------------------

    @Test
    @DisplayName("An AssertionError from a POOL action is false, and the next transition reads it")
    void poolError() throws Exception {
        net(() -> new AssertionError("boom"));

        ActionResult result = runResource("/workflows/failure-reason.yaml");

        assertTrue(result.isSuccess(), result.getResult());
        assertRecordedReason("error: java.lang.AssertionError: boom (in net.connect)");
    }

    @Test
    @DisplayName("A fatal error from a POOL action is recorded as the last result, then rethrown")
    void poolFatal() {
        TestFatalError fatal = new TestFatalError("dying");
        net(() -> fatal);

        Throwable thrown = runResourceExpectingThrow("/workflows/failure-reason.yaml");

        assertSame(fatal, thrown);
        assertTrue(recorded.isEmpty(), "no transition should run after a fatal error");
        assertLastResultNames("ActionErrorTest$TestFatalError: dying (in net.connect)");
    }

    @Test
    @DisplayName("A fatal error from an @Action method is recorded as the last result, then rethrown")
    void actionDispatchFatal() {
        system.addIIActor(new FatalByAction(system));

        Throwable thrown = runResourceExpectingThrow("/workflows/failure-reason.yaml");

        assertInstanceOf(TestFatalError.class, thrown);
        assertTrue(recorded.isEmpty(), "no transition should run after a fatal error");
        assertLastResultNames("ActionErrorTest$TestFatalError: dying (in net.connect)");
    }

    // ---- apply -----------------------------------------------------------------------------------

    private void child(String name, Supplier<Throwable> failure) {
        IIActorRef<Object> child = failure == null
                ? new IIActorRef<Object>(name, new Object(), system) {
                    @Override
                    public ActionResult callByActionName(String actionName, String args) {
                        return new ActionResult(true, "worked");
                    }
                }
                : new Thrower(name, system, failure);
        child.setParentName(interpreterActor.getName());
        interpreterActor.getNamesOfChildren().add(name);
        system.addIIActor(child);
    }

    @Test
    @DisplayName("apply: an AssertionError from one child is that child's failure, named with its reason")
    void applyError() {
        child("child-1", () -> new AssertionError("boom"));
        child("child-2", null);

        ActionResult result = interpreter.apply("{\"actor\": \"child-*\", \"method\": \"work\"}");

        assertFalse(result.isSuccess());
        assertTrue(result.getResult().contains("Applied to 1/2 actors"), result.getResult());
        assertTrue(result.getResult().contains(
                "child-1: error: java.lang.AssertionError: boom (in child-1.work)"), result.getResult());
    }

    @Test
    @DisplayName("apply: a fatal error from a child is rethrown out of apply")
    void applyFatal() {
        TestFatalError fatal = new TestFatalError("dying");
        child("child-1", () -> fatal);
        child("child-2", null);

        Throwable thrown = assertThrows(Throwable.class,
                () -> interpreter.apply("{\"actor\": \"child-*\", \"method\": \"work\"}"));

        assertSame(fatal, thrown);
    }

    // ---- call ------------------------------------------------------------------------------------

    private static final String MAIN_CALLS_SUB_ERROR = """
            name: main-calls-sub-error
            steps:
              - states: ["0", "end"]
                actions:
                  - actor: this
                    method: call
                    arguments: ["sub-error-direct.yaml"]
            """;

    @Test
    @DisplayName("call: an AssertionError from a DIRECT action in the sub-workflow is false there, and its fallback reads it")
    void callError() throws Exception {
        net(() -> new AssertionError("boom"));

        ActionResult result = runText(MAIN_CALLS_SUB_ERROR);

        assertTrue(result.isSuccess(), result.getResult());
        assertRecordedReason("error: java.lang.AssertionError: boom (in net.connect)");
    }

    @Test
    @DisplayName("call: a fatal error in the sub-workflow is rethrown out of the calling run")
    void callFatal() {
        TestFatalError fatal = new TestFatalError("dying");
        net(() -> fatal);

        interpreter.readYaml(new ByteArrayInputStream(MAIN_CALLS_SUB_ERROR.getBytes(StandardCharsets.UTF_8)));
        ExecutionException e = assertThrows(ExecutionException.class,
                () -> CompletableFuture.supplyAsync(() -> interpreter.runUntilEnd(20))
                        .get(10, TimeUnit.SECONDS));

        assertSame(fatal, e.getCause());
        assertTrue(recorded.isEmpty(), "no transition should run after a fatal error");
        assertLastResultNames("ActionErrorTest$TestFatalError: dying");
    }

    // ---- an argument expression ------------------------------------------------------------------

    private static final String EXPRESSION_WORKFLOW = """
            name: argument-expression-error
            steps:
              - states: ["0", "1"]
                actions:
                  - actor: recorder
                    method: record
                    arguments: ["jexl: state.getString('bomb')"]
              - states: ["0", "end"]
                actions:
                  - actor: recorder
                    method: record
                    arguments: ["jexl: result"]
            """;

    /**
     * Makes the interpreter run as an actor whose state throws {@code failure} when {@code bomb} is
     * read. An expression may call only the readers of values, so the Error has to come from one of
     * them; a method of an object an actor wraps is out of an expression's reach.
     */
    private void runAsActorWhoseStateThrows(Supplier<Throwable> failure) {
        JsonState state = new JsonState() {
            @Override
            public String getString(String path) {
                if ("bomb".equals(path)) {
                    throw ActionFailureReasonTest.sneaky(failure.get());
                }
                return super.getString(path);
            }
        };
        interpreterActor.close();
        interpreterActor = new InterpreterIIAR("main", interpreter, system) {
            @Override
            public JsonState json() {
                return state;
            }
        };
        interpreter.setSelfActorRef(interpreterActor);
        system.addIIActor(interpreterActor);
    }

    @Test
    @DisplayName("An AssertionError from an argument expression fails the action, and the run goes on")
    void argumentExpressionError() throws Exception {
        runAsActorWhoseStateThrows(() -> new AssertionError("from the expression"));

        ActionResult result = runText(EXPRESSION_WORKFLOW);

        assertTrue(result.isSuccess(), result.getResult());
        assertRecordedReason("argument expression failed: ");
    }

    @Test
    @DisplayName("A fatal error from an argument expression is recorded as the last result, then rethrown")
    void argumentExpressionFatal() {
        runAsActorWhoseStateThrows(() -> new TestFatalError("from the expression"));

        interpreter.readYaml(new ByteArrayInputStream(
                EXPRESSION_WORKFLOW.getBytes(StandardCharsets.UTF_8)));
        ExecutionException e = assertThrows(ExecutionException.class,
                () -> CompletableFuture.supplyAsync(() -> interpreter.runUntilEnd(20))
                        .get(10, TimeUnit.SECONDS));

        assertInstanceOf(TestFatalError.class, e.getCause());
        assertTrue(recorded.isEmpty(), "no transition should run after a fatal error");
        assertLastResultNames("ActionErrorTest$TestFatalError: from the expression");
    }

    /**
     * The last result keeps only its text (getLastResult always answers success), which is what
     * {@code jexl: result} reads; the fatal error must be named there.
     */
    private void assertLastResultNames(String expected) {
        ActionResult last = interpreterActor.getLastResult();
        assertTrue(last != null, "the fatal error should be recorded as the last result");
        assertTrue(last.getResult().startsWith("error: "), last.getResult());
        assertTrue(last.getResult().contains(expected), last.getResult());
    }
}
