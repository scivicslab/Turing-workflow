package com.scivicslab.turingworkflow.workflow;

import com.scivicslab.pojoactor.action.ActionResult;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Adding to a list held in the workflow's own state ({@code ActorsAsVariables_260914_oo01}).
 *
 * <p>Everything else a workflow did with a {@code list:} actor is already in {@code JsonState}:
 * writing by path, reading by path and index, the length. Adding to the end was the one thing
 * missing, because {@code putJson} names the index it writes and a workflow cannot compute that
 * index into the path. This is that one action.</p>
 */
@DisplayName("IIActorRef — appendJson")
class AppendJsonTest {

    private IIActorSystem system;
    private IIActorRef<Object> actor;

    /** A bare interpreter-facing actor: the JSON state actions come from IIActorRef itself. */
    static class Holder extends IIActorRef<Object> {
        Holder(String name, IIActorSystem system) {
            super(name, new Object(), system);
        }
    }

    @BeforeEach
    void setUp() {
        system = new IIActorSystem("append-json-test");
        actor = new Holder("holder", system);
        system.addIIActor(actor);
    }

    @AfterEach
    void tearDown() {
        system.terminate();
    }

    private ActionResult append(String path, String valueAsJson) {
        return actor.callByActionName("appendJson",
                "{\"path\":\"" + path + "\",\"value\":" + valueAsJson + "}");
    }

    @Test
    void aPathThatHoldsNothingYetBecomesAListOfOne() {
        ActionResult result = append("items", "\"first\"");

        assertTrue(result.isSuccess(), result.getResult());
        assertTrue(actor.json().select("items").isArray(), actor.json().toString());
        assertEquals(1, actor.json().select("items").size());
        assertEquals("first", actor.json().getString("items[0]"));
    }

    @Test
    void whatIsAddedGoesOnTheEnd() {
        append("items", "\"first\"");
        append("items", "\"second\"");
        append("items", "\"third\"");

        assertEquals(3, actor.json().select("items").size());
        assertEquals("first", actor.json().getString("items[0]"));
        assertEquals("third", actor.json().getString("items[2]"));
    }

    @Test
    void aNumberOrAnObjectIsAddedAsItself() {
        append("counts", "42");
        append("counts", "7");
        append("papers", "{\"title\":\"a paper\",\"year\":2026}");

        assertEquals(42, actor.json().getInt("counts[0]", -1));
        assertEquals(7, actor.json().getInt("counts[1]", -1));
        assertEquals("a paper", actor.json().getString("papers[0].title"));
        assertEquals(2026, actor.json().getInt("papers[0].year", -1));
    }

    @Test
    void aPathThatHoldsSomethingOtherThanAListIsRefused() {
        actor.putJson("name", "Mery");

        ActionResult result = append("name", "\"second\"");

        assertFalse(result.isSuccess(), "adding to a string would have to overwrite it");
        assertTrue(result.getResult().contains("name"), result.getResult());
        assertEquals("Mery", actor.json().getString("name"), "and what was there is untouched");
    }

    /** The same fix putJson needed: an object argument is stored as structure, not as its text. */
    @Test
    void anObjectGivenToPutJsonIsStoredAsStructure() {
        ActionResult result = actor.callByActionName("putJson",
                "{\"path\":\"paper\",\"value\":{\"title\":\"a paper\",\"year\":2026}}");

        assertTrue(result.isSuccess(), result.getResult());
        assertEquals("a paper", actor.json().getString("paper.title"),
                "the fields are reachable by path: " + actor.json());
        assertEquals(2026, actor.json().getInt("paper.year", -1));
    }

    @Test
    void aNestedPathWorksTheSameWay() {
        append("report.findings", "\"one\"");
        append("report.findings", "\"two\"");

        assertEquals(2, actor.json().select("report.findings").size());
        assertEquals("two", actor.json().getString("report.findings[1]"));
    }
}
