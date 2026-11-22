package com.scivicslab.turingworkflow.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The expressions workflows are written with still evaluate under the allow-list.
 *
 * <p>Each case is one shape found in the tracked workflow YAMLs and Java sources under
 * {@code ~/works} (Turing-workflow, Turing-workflow-plugins, chat-ui-with-audit-trail,
 * quarkus-chat-ui, frame-viewer, fcitx5-slm-ime, quarkus-english-toolkit, w206-IaC), with the
 * literals replaced. The allow-list was derived from these shapes; a case failing here is a workflow
 * the allow-list broke.</p>
 */
@Tag("ExpressionReach_AllowList_261122_oo01")
@DisplayName("The expressions workflows use still evaluate")
class ExpressionAllowListTest {

    private IIActorSystem system;
    private IIActorRef<Object> actor;
    private WorkflowExpressions expressions;

    @BeforeEach
    void setUp() {
        system = new IIActorSystem("allow-list");
        actor = new IIActorRef<Object>("main", new Object(), system) {};
        system.addIIActor(actor);
        actor.json().put("a", "alpha");
        actor.json().put("b", "beta");
        actor.json().put("path", "/docs/dir/file.md");
        actor.json().put("text", "  head [inside] tail  ");
        actor.json().put("i", 1);
        actor.json().put("flag", true);
        actor.callByActionName("appendJson", "{\"path\":\"xs\",\"value\":\"x0\"}");
        actor.callByActionName("appendJson", "{\"path\":\"xs\",\"value\":\"x1\"}");
        actor.setLastResultValue("REVISE");
        expressions = new WorkflowExpressions(system, actor, "loop");
    }

    @AfterEach
    void tearDown() {
        system.terminateIIActors();
    }

    private Object eval(String expression) {
        return expressions.evaluate(expression);
    }

    @Nested
    @DisplayName("an action's argument")
    class Arguments {

        @Test
        @DisplayName("stored text, joined and compared")
        void storedText() {
            assertEquals("alpha", eval("state.getString('a')"));
            assertEquals("alpha and beta", eval("state.getString('a') + ' and ' + state.getString('b')"));
            assertEquals(true, eval("state.getString('a') == 'alpha'"));
            assertEquals(true, eval("state.getString('a') != 'beta' && state.getString('b') != 'alpha'"));
            assertEquals("none", eval("state.getString('missing', 'none')"));
            assertEquals("beta", eval("state.getString('a') == 'x' ? 'x' : state.getString('b')"));
        }

        @Test
        @DisplayName("text cut and searched")
        void textMethods() {
            assertEquals(true, eval("state.getString('text').indexOf('inside') >= 0"));
            assertEquals(true, eval("state.getString('text').indexOf('absent') < 0"));
            assertEquals("file.md", eval("state.getString('path').substring(state.getString('path').lastIndexOf('/') + 1)"));
            assertEquals("inside", eval("state.getString('text').substring(state.getString('text').indexOf('[') + 1,"
                    + " state.getString('text').indexOf(']', state.getString('text').indexOf('[') + 1)).trim()"));
            assertEquals(true, eval("!state.getString('a').matches('[0-9]+')"));
            assertEquals("ALPHA", eval("state.getString('a').replaceAll('a', 'A').replaceAll('lph', 'LPH')"));
            assertEquals(true, eval("state.getString('text').trim().endsWith('tail')"));
            assertEquals(true, eval("state.getString('a').startsWith('al')"));
            assertEquals(true, eval("state.getString('a').contains('lph')"));
            assertEquals("five", eval("state.getString('a').length() == 5 ? 'five' : 'other'"));
            assertEquals("alpha", eval("state.getString('a').toString()"));
        }

        @Test
        @DisplayName("stored numbers, booleans and lists")
        void numbersBooleansLists() {
            assertEquals(2, eval("state.getInt('i', 0) + 1"));
            assertEquals(true, eval("state.getInt('i', 0) < state.select('xs').size()"));
            assertEquals(true, eval("state.getBoolean('flag', false)"));
            assertEquals(2, eval("state.select('xs').size()"));
            assertEquals("x1", eval("state.select('xs').get(state.getInt('i', 0)).asText()"));
            assertEquals(true, eval("state.select('xs').isArray()"));
            assertEquals("[\"x0\",\"x1\"]", eval("state.select('xs').toString()"));
            assertNotNull(eval("state.select('xs')"));
            assertNotNull(eval("state.get('a')"));
        }

        @Test
        @DisplayName("the previous result, the actor's name and the state the workflow is in")
        void resultSelfCurrentState() {
            assertEquals("REVISE", eval("result"));
            assertEquals("verdict: REVISE", eval("'verdict: ' + result"));
            assertEquals(true, eval("result == 'REVISE'"));
            assertEquals("main", eval("self"));
            assertEquals("loop", eval("currentState"));
        }
    }

    @Nested
    @DisplayName("a pattern in states")
    class Patterns {

        private Interpreter interpreter;

        @BeforeEach
        void setUp() {
            interpreter = new Interpreter.Builder().loggerName("allow-list-patterns").team(system).build();
        }

        private boolean matches(String expression, String state) {
            return interpreter.matchesStatePattern("jexl:" + expression, state);
        }

        @Test
        @DisplayName("comparisons on the state")
        void comparisons() {
            assertTrue(matches("currentState == 'error'", "error"));
            assertTrue(matches("currentState != 'a' && currentState != 'b'", "c"));
            assertTrue(matches("currentState == 'a' || currentState == 'b'", "b"));
            assertTrue(matches("currentState =~ 'err.*'", "error-1"));
            assertFalse(matches("currentState =~ 'err.*'", "ok"));
        }

        @Test
        @DisplayName("tests on the state's text")
        void textTests() {
            assertTrue(matches("currentState.startsWith('err')", "error"));
            assertTrue(matches("currentState.endsWith('or')", "error"));
            assertTrue(matches("currentState.contains('rro')", "error"));
        }

        @Test
        @DisplayName("the state as a number")
        void numbers() {
            assertTrue(matches("n >= 5", "7"));
            assertTrue(matches("n >= 5 && n < 10", "7"));
            assertFalse(matches("n >= 5 && n < 10", "12"));
            assertTrue(matches("n == null", "loop"));
        }

        @Test
        @DisplayName("a to-state computed from the number")
        void toState() {
            ExpressionRuns runs = new ExpressionRuns("allow-list-to-state");
            try {
                runs.run("name: counter\nsteps:\n"
                        + "  - states: [\"0\", \"3\"]\n    actions: []\n"
                        + "  - states: [\"3\", \"jexl:n + 1\"]\n    actions: []\n", 2);
                assertEquals("4", runs.interpreter.getCurrentState());
            } finally {
                runs.close();
            }
        }
    }
}
