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

import java.net.ConnectException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.scivicslab.pojoactor.action.Action;
import com.scivicslab.pojoactor.action.ActionResult;

/**
 * Verifies that a failed action is false and that its reason reaches the workflow in
 * {@code ${result}}: an exception's class and message, in one fixed form, for every way an action
 * can fail, and the last failure's reason when no transition matches. The YAML then decides.
 */
@DisplayName("A failed action is false, and its reason is in ${result}")
class ActionFailureReasonTest {

    private IIActorSystem system;
    private Interpreter interpreter;
    private InterpreterIIAR interpreterActor;
    private final List<String> recorded = new CopyOnWriteArrayList<>();

    /** Throws a checked exception without declaring it, as an action's own code may. */
    @SuppressWarnings("unchecked")
    static <E extends Throwable> RuntimeException sneaky(Throwable t) throws E {
        throw (E) t;
    }

    /** An actor whose {@code connect} throws ConnectException from callByActionName itself. */
    static final class FailingByCall extends IIActorRef<Object> {
        FailingByCall(IIActorSystem system) {
            super("net", new Object(), system);
        }

        @Override
        public ActionResult callByActionName(String actionName, String args) {
            if ("connect".equals(actionName)) {
                throw sneaky(new ConnectException("Connection refused"));
            }
            return super.callByActionName(actionName, args);
        }
    }

    /** An actor whose {@code @Action connect} throws ConnectException, dispatched by reflection. */
    public static final class FailingByAction extends IIActorRef<Object> {
        public FailingByAction(IIActorSystem system) {
            super("net", new Object(), system);
        }

        @Action("connect")
        public ActionResult connect(String args) {
            throw sneaky(new ConnectException("Connection refused"));
        }
    }

    @BeforeEach
    void setUp() {
        system = new IIActorSystem("failure-reason-test", 2);
        interpreter = new Interpreter.Builder()
                .loggerName("failure-reason-test")
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

    private ActionResult run(String resource) throws Exception {
        interpreter.readYaml(getClass().getResourceAsStream(resource));
        return CompletableFuture.supplyAsync(() -> interpreter.runUntilEnd(20))
                .get(10, TimeUnit.SECONDS);
    }

    private void assertRecordedReasonIsTheConnectException() {
        assertEquals(1, recorded.size(), "the fallback transition should run once");
        String reason = recorded.get(0);
        assertTrue(reason.contains("error: java.net.ConnectException: Connection refused"), reason);
        assertTrue(reason.contains("(in net.connect)"), reason);
    }

    @Test
    @DisplayName("POOL action throws: the next transition's ${result} names java.net.ConnectException")
    void poolAction_throwsConnectException_nextTransitionSeesItInResult() throws Exception {
        system.addIIActor(new FailingByCall(system));

        ActionResult result = run("/workflows/failure-reason.yaml");

        assertTrue(result.isSuccess(), result.getResult());
        assertRecordedReasonIsTheConnectException();
    }

    @Test
    @DisplayName("DIRECT action throws: the workflow goes on, and ${result} names the exception")
    void directAction_throwsConnectException_nextTransitionSeesItInResult() throws Exception {
        system.addIIActor(new FailingByCall(system));

        ActionResult result = run("/workflows/failure-reason-direct.yaml");

        assertTrue(result.isSuccess(), result.getResult());
        assertRecordedReasonIsTheConnectException();
    }

    @Test
    @DisplayName("@Action method throws: the dispatcher's result names the root exception, not the wrapper")
    void annotatedAction_throwsConnectException_resultNamesRootCause() throws Exception {
        system.addIIActor(new FailingByAction(system));

        ActionResult result = run("/workflows/failure-reason.yaml");

        assertTrue(result.isSuccess(), result.getResult());
        assertRecordedReasonIsTheConnectException();
        assertFalse(recorded.get(0).contains("InvocationTargetException"), recorded.get(0));
    }

    @Test
    @DisplayName("InterpreterIIAR: a failure inside the interpreter names the exception, not 'Execution error'")
    void interpreterActor_workThrows_resultNamesException() {
        Interpreter throwing = new Interpreter() {
            @Override
            public ActionResult execCode() {
                throw sneaky(new ConnectException("Connection refused"));
            }
        };
        InterpreterIIAR actor = new InterpreterIIAR("throwing", throwing, system);
        system.addIIActor(actor);

        ActionResult result = actor.callByActionName("execCode", "");

        assertFalse(result.isSuccess());
        assertFalse(result.getResult().contains("Execution error"), result.getResult());
        assertTrue(result.getResult().contains("error: java.net.ConnectException: Connection refused"), result.getResult());
    }

    @Test
    @DisplayName("no transition matches: the result names the state and the last failure's reason")
    void noMatchingTransition_resultNamesStateAndLastReason() throws Exception {
        system.addIIActor(new FailingByCall(system));

        ActionResult result = run("/workflows/failure-no-fallback.yaml");

        assertFalse(result.isSuccess());
        assertTrue(result.getResult().contains("No matching state transition from '0'"), result.getResult());
        assertTrue(result.getResult().contains("java.net.ConnectException"), result.getResult());
    }

    /** The sample's lookup actor: an expected false, or an error, as the test chooses. */
    private void addLookup(boolean throwsError) {
        system.addIIActor(new IIActorRef<Object>("lookup", new Object(), system) {
            @Override
            public ActionResult callByActionName(String actionName, String args) {
                if (!"find".equals(actionName)) {
                    return super.callByActionName(actionName, args);
                }
                if (throwsError) {
                    throw sneaky(new ConnectException("Connection refused"));
                }
                return new ActionResult(false, "not found: item-42");
            }
        });
    }

    @Test
    @DisplayName("sample branch-on-failure-reason: an expected false takes the not-found route")
    void sample_expectedFalse_takesNotFoundRoute() throws Exception {
        addLookup(false);

        ActionResult result = run("/examples/branch-on-failure-reason.yaml");

        assertTrue(result.isSuccess(), result.getResult());
        assertEquals("not-found", interpreterActor.json().getString("route"));
        assertEquals("not found: item-42", interpreterActor.json().getString("reason"));
    }

    @Test
    @DisplayName("sample branch-on-failure-reason: an error takes the error route")
    void sample_error_takesErrorRoute() throws Exception {
        addLookup(true);

        ActionResult result = run("/examples/branch-on-failure-reason.yaml");

        assertTrue(result.isSuccess(), result.getResult());
        assertEquals("error", interpreterActor.json().getString("route"));
        assertTrue(interpreterActor.json().getString("reason").startsWith("error: java.net.ConnectException"),
                interpreterActor.json().getString("reason"));
    }
}
