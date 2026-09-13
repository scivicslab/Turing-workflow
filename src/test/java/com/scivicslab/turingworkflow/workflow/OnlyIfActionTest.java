package com.scivicslab.turingworkflow.workflow;

import com.scivicslab.pojoactor.action.ActionResult;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A condition on the data, written as an action ({@code ActorsAsVariables_260914_oo01}).
 *
 * <p>A {@code states} pattern selects on the state the workflow is in and cannot reach the values
 * it has stored. A condition on those values belongs in the transition's actions, where a false
 * one fails the transition and the engine falls through to the next candidate — the way a loop
 * over a list used to end when {@code list:items.get} was asked for an index past the end.</p>
 */
@DisplayName("Interpreter — onlyIf")
class OnlyIfActionTest {

    private IIActorSystem system;
    private InterpreterIIAR interpreterActor;

    @BeforeEach
    void setUp() {
        system = new IIActorSystem("onlyif-test");
        Interpreter interpreter = new Interpreter.Builder()
                .loggerName("onlyif-test")
                .team(system)
                .build();
        interpreterActor = new InterpreterIIAR("interpreter", interpreter, system);
        interpreter.setSelfActorRef(interpreterActor);
        system.addIIActor(interpreterActor);
    }

    @AfterEach
    void tearDown() {
        system.terminate();
    }

    @Test
    void aConditionThatHoldsLetsTheTransitionThrough() {
        assertTrue(interpreterActor.callByActionName("onlyIf", "[true]").isSuccess());
    }

    @Test
    void aConditionThatDoesNotHoldFailsTheTransition() {
        ActionResult result = interpreterActor.callByActionName("onlyIf", "[false]");

        assertFalse(result.isSuccess());
        assertTrue(result.getResult().contains("false"), result.getResult());
    }

    /** An expression that could not be evaluated answers null, which is not a condition holding. */
    @Test
    void anExpressionThatFailedIsNotTreatedAsTrue() {
        assertFalse(interpreterActor.callByActionName("onlyIf", "[null]").isSuccess());
        assertFalse(interpreterActor.callByActionName("onlyIf", "[]").isSuccess());
    }

    /** Something that is not a yes-or-no answer is refused rather than guessed at. */
    @Test
    void aValueThatIsNotAConditionIsRefused() {
        ActionResult result = interpreterActor.callByActionName("onlyIf", "[3]");

        assertFalse(result.isSuccess());
        assertTrue(result.getResult().contains("3"), result.getResult());
    }

    /**
     * A failed transition keeps what ran before the failure.
     *
     * <p>A failed action fails the transition at that point. The state does not change and the
     * next step whose from-pattern matches runs instead — but what already ran is not undone, so
     * a write placed in front of the condition is kept by a transition that was not taken. Which
     * order a workflow wants is its own business: a loop usually puts the condition first, while
     * a transition whose condition reads what an earlier action fetched cannot
     * ({@code ExitConditionRidingOnAFailure_260914_oo01}).</p>
     */
    @Test
    void whatRanBeforeTheConditionIsKeptEvenThoughTheTransitionFailed() {
        InputStream yaml = getClass().getResourceAsStream("/workflows/effect-before-onlyif.yaml");
        assertNotNull(yaml, "the workflow is on the test classpath");
        interpreterActor.tell((Interpreter i) -> i.readYaml(yaml)).join();

        ActionResult result = interpreterActor.callByActionName("runUntilEnd", "[50]");

        assertTrue(result.isSuccess(), result.getResult());
        assertEquals(1, interpreterActor.json().select("written").size(),
                "the write in front of the condition stayed: " + interpreterActor.json());
    }

    /**
     * An argument expression that cannot be evaluated fails the action that was given it.
     *
     * <p>The expression is not the actor's work — it is what the workflow wrote. Handing the
     * action a null instead lets a mistyped method name run to the end of the workflow and report
     * success ({@code ExitConditionRidingOnAFailure_260914_oo01} 以降の調査).</p>
     */
    @Test
    void anArgumentExpressionThatCannotBeEvaluatedFailsTheAction() {
        InputStream yaml = getClass().getResourceAsStream("/workflows/broken-argument-expression.yaml");
        assertNotNull(yaml, "the workflow is on the test classpath");
        interpreterActor.tell((Interpreter i) -> i.readYaml(yaml)).join();

        ActionResult result = interpreterActor.callByActionName("runUntilEnd", "[50]");

        assertTrue(result.isSuccess(), result.getResult());
        assertFalse(interpreterActor.json().has("taken"),
                "the action was not run on a null: " + interpreterActor.json());
        assertTrue(interpreterActor.json().getBoolean("fellThrough", false),
                "and the state stayed put, so the next matching step ran: " + interpreterActor.json());
    }

    /** The whole point: a list in the workflow's own state can be walked to its end. */
    @Test
    void aListInTheStateIsWalkedToItsEnd() {
        InputStream yaml = getClass().getResourceAsStream("/workflows/state-list-loop.yaml");
        assertNotNull(yaml, "the workflow is on the test classpath");
        interpreterActor.tell((Interpreter i) -> i.readYaml(yaml)).join();

        ActionResult result = interpreterActor.callByActionName("runUntilEnd", "[50]");

        assertTrue(result.isSuccess(), result.getResult());
        assertEquals(2, interpreterActor.json().select("seen").size(),
                "one pass per item: " + interpreterActor.json());
        assertEquals("alpha", interpreterActor.json().getString("seen[0]"));
        assertEquals("beta", interpreterActor.json().getString("seen[1]"));
        assertEquals(2, interpreterActor.json().getInt("i", -1), "and it stopped at the end");
    }
}
