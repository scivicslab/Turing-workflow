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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import com.scivicslab.pojoactor.action.ActionResult;

/**
 * Nothing an action, its result or the engine's own bookkeeping throws escapes
 * {@link Interpreter#runUntilEnd}: between "step picked" and "result recorded" every non-fatal
 * {@code Throwable} makes the attempt false, with its reason as
 * {@code error: <class>: <message> (in <actor>.<method>)}.
 *
 * <p>Running out of the stack throws at points that are hard to reach on purpose: a
 * {@code BootstrapMethodError} from linking a string concatenation, an Error while the failure
 * text is built. These tests throw the same Errors there directly, so they fail or pass on every
 * run. One test runs the real recursive {@code this.call}, in a JVM of its own.</p>
 */
@DisplayName("No non-fatal Throwable escapes runUntilEnd between step picked and result recorded")
class ActionErrorEscapeTest {

    private IIActorSystem system;
    private Interpreter interpreter;
    private final List<String> recorded = new CopyOnWriteArrayList<>();

    /** An Error whose message cannot be read: building the failure text throws. */
    static final class UndescribableError extends Error {
        @Override
        public String getMessage() {
            throw new BootstrapMethodError("injected while the failure text is built");
        }
    }

    /** A result whose text throws when the engine records it. */
    static ActionResult resultWhoseTextThrows(Throwable thrown) {
        return new ActionResult(true, "never read") {
            @Override
            public String getResult() {
                throw ActionFailureReasonTest.sneaky(thrown);
            }
        };
    }

    /** An actor whose {@code connect} answers what the test gives it, or throws it. */
    static final class Net extends IIActorRef<Object> {
        private final Supplier<Object> answer;

        Net(IIActorSystem system, Supplier<Object> answer) {
            super("net", new Object(), system);
            this.answer = answer;
        }

        @Override
        public ActionResult callByActionName(String actionName, String args) {
            if ("connect".equals(actionName)) {
                Object a = answer.get();
                if (a instanceof Throwable t) {
                    throw ActionFailureReasonTest.sneaky(t);
                }
                return (ActionResult) a;
            }
            return super.callByActionName(actionName, args);
        }
    }

    /** An interpreter whose hook before a transition throws what the test gives it. */
    static final class FailingHookInterpreter extends Interpreter {
        private final Throwable onEnter;

        FailingHookInterpreter(Throwable onEnter) {
            this.onEnter = onEnter;
        }

        @Override
        protected void onEnterTransition(Transition transition) {
            if ("connected".equals(transition.getStates().get(1))) {
                throw ActionFailureReasonTest.sneaky(onEnter);
            }
        }
    }

    @BeforeEach
    void setUp() {
        system = new IIActorSystem("action-error-escape-test", 2);
        use(new Interpreter.Builder().loggerName("action-error-escape-test").team(system).build());
        system.addIIActor(new IIActorRef<Object>("recorder", new Object(), system) {
            @Override
            public ActionResult callByActionName(String actionName, String args) {
                recorded.add(args);
                return new ActionResult(true, "recorded");
            }
        });
    }

    private void use(Interpreter i) {
        interpreter = i;
        InterpreterIIAR self = new InterpreterIIAR("main", interpreter, system);
        interpreter.setSelfActorRef(self);
        system.addIIActor(self);
    }

    @AfterEach
    void tearDown() {
        system.terminateIIActors();
        system.terminate();
    }

    /** Runs the fixture and fails the test, with what escaped, when runUntilEnd throws. */
    private ActionResult runWithoutEscape() throws Exception {
        interpreter.readYaml(getClass().getResourceAsStream("/workflows/failure-reason-direct.yaml"));
        try {
            return CompletableFuture.supplyAsync(() -> interpreter.runUntilEnd(20)).get(10, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            throw new AssertionError("escaped runUntilEnd: " + e.getCause(), e.getCause());
        }
    }

    /** The fallback transition ran once, and the reason it read starts with {@code expected}. */
    private void assertReason(String expectedStart, String expectedEnd) {
        assertEquals(1, recorded.size(), "the fallback transition should run once and read the reason");
        String reason = recorded.get(0);
        assertTrue(reason.contains(expectedStart), reason);
        assertTrue(reason.contains(expectedEnd), reason);
    }

    static Stream<Throwable> nonFatalErrors() {
        return Stream.of(
                new BootstrapMethodError("linking"),
                new LinkageError("linking"),
                new NoClassDefFoundError("Missing"),
                new ExceptionInInitializerError("static init"),
                new AssertionError("assert"),
                new StackOverflowError("deep"));
    }

    /** One case: a fresh system, the net actor built from the Error, the run, the reason. */
    private void runCase(Throwable error, java.util.function.Function<Throwable, Supplier<Object>> answer) throws Exception {
        tearDown();
        recorded.clear();
        setUp();
        system.addIIActor(new Net(system, answer.apply(error)));

        ActionResult result = runWithoutEscape();

        assertTrue(result.isSuccess(), result.getResult());
        assertReason("error: " + error.getClass().getName(), "(in net.connect)");
    }

    @TestFactory
    @DisplayName("dispatch: each non-fatal Error thrown by the action itself is false")
    Stream<DynamicTest> dispatch_nonFatalError_isFalse() {
        return nonFatalErrors().map(error -> DynamicTest.dynamicTest(error.getClass().getSimpleName(),
                () -> runCase(error, e -> () -> e)));
    }

    @TestFactory
    @DisplayName("recording: each non-fatal Error thrown while the returned result is recorded is false")
    Stream<DynamicTest> record_resultThrows_isFalse() {
        return nonFatalErrors().map(error -> DynamicTest.dynamicTest(error.getClass().getSimpleName(),
                () -> runCase(error, e -> () -> resultWhoseTextThrows(e))));
    }

    @Test
    @DisplayName("recording: an action that answers null is false")
    void record_nullResult_isFalse() throws Exception {
        system.addIIActor(new Net(system, () -> null));

        ActionResult result = runWithoutEscape();

        assertTrue(result.isSuccess(), result.getResult());
        assertReason("error: java.lang.NullPointerException", "(in net.connect)");
    }

    @Test
    @DisplayName("failure text: an Error whose message throws is still false, named by its class")
    void failureText_messageThrows_isFalse() throws Exception {
        system.addIIActor(new Net(system, UndescribableError::new));

        ActionResult result = runWithoutEscape();

        assertTrue(result.isSuccess(), result.getResult());
        assertReason("error: " + UndescribableError.class.getName(), "(in net.connect)");
    }

    @Test
    @DisplayName("hook: an Error from onEnterTransition is false, and the next transition runs")
    void hook_onEnterThrows_isFalse() throws Exception {
        system.terminateIIActors();
        system.terminate();
        system = new IIActorSystem("action-error-escape-hook", 2);
        FailingHookInterpreter hooked = new FailingHookInterpreter(new BootstrapMethodError("in the hook"));
        hooked.system = system;
        use(hooked);
        system.addIIActor(new IIActorRef<Object>("recorder", new Object(), system) {
            @Override
            public ActionResult callByActionName(String actionName, String args) {
                recorded.add(args);
                return new ActionResult(true, "recorded");
            }
        });
        system.addIIActor(new Net(system, () -> new ActionResult(true, "connected")));

        ActionResult result = runWithoutEscape();

        assertTrue(result.isSuccess(), result.getResult());
        assertReason("error: java.lang.BootstrapMethodError: in the hook", "(in ");
    }

    /** How many times the recursive run is started; on the old engine about two runs in five escaped. */
    private static final int RECURSIVE_RUNS = 5;

    @Test
    @DisplayName("real recursion: this.call on itself until the stack runs out ends false, nothing escapes")
    void recursiveCall_untilStackRunsOut_endsFalseWithoutEscape() throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        for (int run = 1; run <= RECURSIVE_RUNS; run++) {
            Process p = new ProcessBuilder(java, "-cp", System.getProperty("java.class.path"),
                    RecursiveCallMain.class.getName(), "uncapped")
                    .redirectErrorStream(true)
                    .start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!p.waitFor(120, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                fail("run " + run + ": the recursive run did not end in 120 s");
            }
            String tail = out.length() > 4000 ? out.substring(out.length() - 4000) : out;
            assertFalse(out.contains("ESCAPED"), "run " + run + ": something escaped runUntilEnd:\n" + tail);
            assertEquals(0, p.exitValue(), "run " + run + ":\n" + tail);
            assertTrue(out.contains("RESULT success=false"), "run " + run + ":\n" + tail);
        }
    }
}
