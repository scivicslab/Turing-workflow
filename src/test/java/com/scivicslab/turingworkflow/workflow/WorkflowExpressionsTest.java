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

    /** An actor named {@code name} whose state holds {@code value} at {@code path}. */
    private IIActorRef<Object> holding(String name, String path, Object value) {
        IIActorRef<Object> holder = new IIActorRef<Object>(name, new Object(), system) {};
        system.addIIActor(holder);
        holder.json().put(path, value);
        return holder;
    }

    /** Expressions evaluated as {@code actor}, so that {@code state} is its state. */
    private WorkflowExpressions as(IIActorRef<?> actor) {
        return new WorkflowExpressions(system, actor, "loop");
    }

    @Nested
    @DisplayName("types survive")
    class TypesSurvive {

        @Test
        @DisplayName("a stored number arrives as a number")
        void number_staysNumber() {
            Object outcome = as(holding("counter", "n", 3)).evaluate("state.getInt(\"n\", 0)");

            assertInstanceOf(Integer.class, outcome);
            assertEquals(3, outcome);
        }

        @Test
        @DisplayName("arithmetic on it is done as arithmetic, not as text")
        void number_canBeComputedWith() {
            Object outcome = as(holding("counter", "n", 3)).evaluate("state.getInt(\"n\", 0) + 1");

            assertInstanceOf(Number.class, outcome);
            assertEquals(4, ((Number) outcome).intValue(), "text concatenation would have produced \"31\"");
        }

        @Test
        @DisplayName("a length arrives as a whole number")
        void length_staysInteger() {
            Object outcome = as(holding("name", "s", "hello")).evaluate("state.getString(\"s\").length()");

            assertInstanceOf(Integer.class, outcome);
            assertEquals(5, outcome);
        }

        @Test
        @DisplayName("a test arrives as a boolean")
        void contains_staysBoolean() {
            Object outcome = as(holding("name", "s", "hello world"))
                    .evaluate("state.getString(\"s\").contains(\"world\")");

            assertInstanceOf(Boolean.class, outcome);
            assertEquals(true, outcome);
        }
    }

    @Nested
    @DisplayName("another actor, read through actors.get")
    class AnotherActor {

        @Test
        @DisplayName("its last result is readable")
        void result_isReadable() {
            holding("worker", "s", "x").setLastResultValue("ACCEPT");

            assertEquals("ACCEPT", expressions.evaluate("actors.get('worker').result"));
            assertEquals("ACCEPT", expressions.evaluate("actors.get('worker').getResult()"));
        }

        @Test
        @DisplayName("what it stored is readable, with its type")
        void state_isReadable() {
            holding("worker", "n", 3);

            assertEquals(4, expressions.evaluate("actors.get('worker').state.getInt('n', 0) + 1"));
        }

        @Test
        @DisplayName("what it stored cannot be changed")
        void state_cannotBeWritten() {
            IIActorRef<Object> worker = holding("worker", "n", 3);

            assertThrows(RuntimeException.class,
                    () -> expressions.evaluate("actors.get('worker').state.put('n', 9)"));
            assertThrows(RuntimeException.class,
                    () -> expressions.evaluate("actors.get('worker').state.clear()"));
            assertEquals(3, worker.json().getInt("n", 0));
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
    @DisplayName("a list held in the workflow's own state")
    class ListsInState {

        /** An actor whose state the expressions read, standing for the one a workflow runs as. */
        private WorkflowExpressions expressionsOf(IIActorRef<?> actor) {
            return new WorkflowExpressions(system, actor, "loop");
        }

        private IIActorRef<Object> actorHoldingThreeItems() {
            IIActorRef<Object> holder = new IIActorRef<Object>("holder", new Object(), system) {};
            system.addIIActor(holder);
            holder.callByActionName("appendJson", "{\"path\":\"xs\",\"value\":\"a\"}");
            holder.callByActionName("appendJson", "{\"path\":\"xs\",\"value\":\"b\"}");
            holder.callByActionName("appendJson", "{\"path\":\"xs\",\"value\":\"c\"}");
            return holder;
        }

        /**
         * How long a list is, which is what a loop over it needs.
         *
         * <p>{@code select} answers a Jackson node, and calling a method on a class JEXL has not
         * been given permission for answers {@code null} rather than failing. Before the state's
         * own data model was permitted, {@code size()} on a three-item list answered {@code 1} —
         * JEXL's size of one opaque object — and a loop written against it ran once.</p>
         */
        @Test
        @DisplayName("its length is the number of items in it")
        void size_isTheNumberOfItems() {
            IIActorRef<Object> holder = actorHoldingThreeItems();

            assertEquals(3, expressionsOf(holder).evaluate("state.select(\"xs\").size()"));
        }

        @Test
        @DisplayName("an item is reachable by index")
        void item_isReachableByIndex() {
            IIActorRef<Object> holder = actorHoldingThreeItems();

            assertEquals("b", expressionsOf(holder).evaluate("state.select(\"xs\").get(1).asText()"));
            assertEquals("a", expressionsOf(holder).evaluate("state.getString(\"xs[0]\")"));
        }

        @Test
        @DisplayName("whether a path holds a list can be asked")
        void isArray_answersTrue() {
            IIActorRef<Object> holder = actorHoldingThreeItems();

            assertEquals(true, expressionsOf(holder).evaluate("state.select(\"xs\").isArray()"));
        }
    }

    @Nested
    @DisplayName("names and text")
    class NamesAndText {

        @Test
        @DisplayName("a name containing a colon is reached, being passed as text")
        void nameWithColon_isReached() {
            holding("node:with:colons", "s", "ok");
            assertEquals(true, expressions.evaluate("actors.has(\"node:with:colons\")"));
        }

        @Test
        @DisplayName("text built from a stored value is still text")
        void interpolation_staysText() {
            Object outcome = as(holding("in", "s", "alpha")).evaluate("\"processed: \" + state.getString(\"s\")");

            assertEquals("processed: alpha", outcome);
        }

        /**
         * A method the object does not have is an error, not an answer of nothing.
         *
         * <p>JEXL navigates safely by default: an unsolvable method answers {@code null} without
         * raising, and so does a call on {@code null}. The argument then reaches the action as
         * JSON null and the action runs on it — a workflow calling {@code inc()} where the object
         * has {@code increment()} printed "null" and reported success. Raising puts the
         * expression in the log and the mistake in front of whoever wrote it.</p>
         */
        @Test
        @DisplayName("a method the object does not have raises rather than answering nothing")
        void unknownMethod_raises() {
            WorkflowExpressions here = as(holding("x", "s", "text"));

            assertThrows(RuntimeException.class, () -> here.evaluate("state.noSuchMethod()"));
        }

        @Test
        @DisplayName("a call on nothing raises rather than answering nothing")
        void callOnNull_raises() {
            assertThrows(RuntimeException.class,
                    () -> expressions.evaluate("actors.get(\"nobody:here\").get()"));
        }

        @Test
        @DisplayName("an unknown name answers nothing rather than failing")
        void unknownName_answersNull() {
            assertNull(expressions.evaluate("actors.get(\"nobody:here\")"));
        }

        @Test
        @DisplayName("existence can be asked without reaching the object")
        void has_reportsExistence() {
            holding("present", "s", "x");
            assertEquals(true,  expressions.evaluate("actors.has(\"present\")"));
            assertEquals(false, expressions.evaluate("actors.has(\"absent\")"));
        }
    }
}
