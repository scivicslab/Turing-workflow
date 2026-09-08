package com.scivicslab.turingworkflow.workflow;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Checks that an expression reaching an actor keeps the type of what it finds.
 *
 * <p>These exist because the mechanism they replace, {@code $(actor.method)}, answered through
 * {@code ActionResult}, whose value is text. A number arriving as {@code "3"} rather than
 * {@code 3} is what makes a numeric argument declaration impossible.</p>
 */
@DisplayName("WorkflowExpressions")
class WorkflowExpressionsTest {

    private IIActorSystem system;
    private WorkflowExpressions expressions;

    @BeforeEach
    void setUp() {
        system = new IIActorSystem("expr-test");
        expressions = new WorkflowExpressions(system);
    }

    @AfterEach
    void tearDown() {
        system.terminateIIActors();
    }

    @Nested
    @DisplayName("types survive")
    class TypesSurvive {

        @Test
        @DisplayName("a number reached through an actor arrives as a number")
        void number_staysNumber() {
            system.getIIActor("calc:i").callByActionName("set", "{\"value\":3}");

            Object outcome = expressions.evaluate("actors.get(\"calc:i\").get()");

            assertInstanceOf(Double.class, outcome, "the accumulator holds a double");
            assertEquals(3.0, (Double) outcome, 1e-9);
        }

        @Test
        @DisplayName("arithmetic on it is done as arithmetic, not as text")
        void number_canBeComputedWith() {
            system.getIIActor("calc:i").callByActionName("set", "{\"value\":3}");

            Object outcome = expressions.evaluate("actors.get(\"calc:i\").get() + 1");

            assertInstanceOf(Number.class, outcome);
            assertEquals(4.0, ((Number) outcome).doubleValue(), 1e-9,
                         "text concatenation would have produced \"3.01\"");
        }

        @Test
        @DisplayName("a length arrives as a whole number")
        void length_staysInteger() {
            system.getIIActor("str:name").callByActionName("set", "hello");

            Object outcome = expressions.evaluate("actors.get(\"str:name\").length()");

            assertInstanceOf(Integer.class, outcome);
            assertEquals(5, outcome);
        }

        @Test
        @DisplayName("a test arrives as a boolean")
        void contains_staysBoolean() {
            system.getIIActor("str:name").callByActionName("set", "hello world");

            Object outcome = expressions.evaluate("actors.get(\"str:name\").contains(\"world\")");

            assertInstanceOf(Boolean.class, outcome);
            assertEquals(true, outcome);
        }
    }

    @Nested
    @DisplayName("the workflow's own position")
    class OwnPosition {

        @Test
        @DisplayName("the state the workflow is in is reachable")
        void currentState_isReachable() {
            var here = new WorkflowExpressions(system, null, "loop-body");
            assertEquals("loop-body", here.evaluate("currentState"));
        }

        @Test
        @DisplayName("with no state given, it is absent rather than wrong")
        void currentState_absentWhenNotGiven() {
            assertNull(expressions.evaluate("currentState"));
        }
    }

    @Nested
    @DisplayName("names and text")
    class NamesAndText {

        @Test
        @DisplayName("a name containing a colon is reached, being passed as text")
        void nameWithColon_isReached() {
            system.getIIActor("str:with:colons").callByActionName("set", "ok");
            assertEquals("ok", expressions.evaluate("actors.get(\"str:with:colons\").get()"));
        }

        @Test
        @DisplayName("text built from an actor's value is still text")
        void interpolation_staysText() {
            system.getIIActor("str:in").callByActionName("set", "alpha");

            Object outcome = expressions.evaluate(
                    "\"processed: \" + actors.get(\"str:in\").get()");

            assertEquals("processed: alpha", outcome);
        }

        @Test
        @DisplayName("an unknown name answers nothing rather than failing")
        void unknownName_answersNull() {
            assertNull(expressions.evaluate("actors.get(\"nobody:here\")"));
        }

        @Test
        @DisplayName("existence can be asked without reaching the object")
        void has_reportsExistence() {
            system.getIIActor("str:present").callByActionName("set", "x");
            assertEquals(true,  expressions.evaluate("actors.has(\"str:present\")"));
            assertEquals(false, expressions.evaluate("actors.has(\"str:absent\")"));
        }
    }
}
