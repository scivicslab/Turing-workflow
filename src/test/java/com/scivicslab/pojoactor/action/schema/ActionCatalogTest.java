package com.scivicslab.pojoactor.action.schema;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.scivicslab.pojoactor.action.Action;
import com.scivicslab.pojoactor.action.ActionResult;
import com.scivicslab.pojoactor.core.ActorSystem;

/**
 * Answering, from inside this actor system, what another process may call on it.
 *
 * <p>A parent interpreter reaches this through the same port it calls actors on, so the two
 * questions it has — which actions does this actor have, and what does one of them take — are
 * themselves actions ({@code ActionArgumentSchema_260807_oo01} step 3).
 */
@Tag("SchemaRetrievalRoutes_260906_oo01")
@DisplayName("ActionCatalog — telling another process what it may call")
class ActionCatalogTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    public record GreetArgs(String name) {}

    /** Stands in for an actor a caller wants to drive. */
    public static class Greeter {
        @Action("greet")
        public ActionResult greet(String args) { return new ActionResult(true, args); }

        @Action(value = "greetTyped", argsType = GreetArgs.class)
        public ActionResult greetTyped(GreetArgs args) { return new ActionResult(true, args.name()); }
    }

    private static ActorSystem systemWithGreeter() {
        ActorSystem system = new ActorSystem("catalog");
        system.actorOf("greeter", new Greeter());
        return system;
    }

    @Test
    void listsTheActionsOfOneActor() throws Exception {
        ActorSystem system = systemWithGreeter();
        try {
            ActionCatalog catalog = new ActionCatalog(system, new ActionSchemaRegistry());

            ActionResult r = catalog.callByActionName("listActions", "{\"actor\":\"greeter\"}");

            assertTrue(r.isSuccess(), r.getResult());
            var json = JSON.readTree(r.getResult());
            assertEquals("greeter", json.get("actor").asText());
            var names = json.get("actions");
            assertEquals(2, names.size());
            assertEquals("greet", names.get(0).asText());
            assertEquals("greetTyped", names.get(1).asText());
        } finally {
            system.terminate();
        }
    }

    /** Naming an actor that is not there is a question with an answer, not a crash. */
    @Test
    void sayingWhichActorDoesNotExist() {
        ActorSystem system = systemWithGreeter();
        try {
            ActionCatalog catalog = new ActionCatalog(system, new ActionSchemaRegistry());

            ActionResult r = catalog.callByActionName("listActions", "{\"actor\":\"nobody\"}");

            assertFalse(r.isSuccess());
            assertTrue(r.getResult().contains("nobody"), r.getResult());
        } finally {
            system.terminate();
        }
    }

    /**
     * The schema is looked up by the actor's Java class, which the caller does not know and must
     * not have to: it names the actor, and this resolves the class on its behalf.
     */
    @Test
    void describesOneActionByTheActorsInstanceName() throws Exception {
        ActorSystem system = systemWithGreeter();
        try {
            ActionSchemaRegistry registry = new ActionSchemaRegistry(
                    ActionCatalogTest.class.getClassLoader(), "test-action-schemas");
            ActionCatalog catalog = new ActionCatalog(system, registry);

            ActionResult r = catalog.callByActionName("describeAction",
                    "{\"actor\":\"greeter\",\"action\":\"greetTyped\"}");

            assertTrue(r.isSuccess(), r.getResult());
            var json = JSON.readTree(r.getResult());
            assertEquals("object", json.get("schema").get("type").asText());
            assertTrue(json.get("schema").get("properties").has("name"), r.getResult());
        } finally {
            system.terminate();
        }
    }

    @Test
    void mergesTheManifestProseIntoTheDescription() throws Exception {
        ActorSystem system = systemWithGreeter();
        try {
            ActionSchemaRegistry registry = new ActionSchemaRegistry(
                    ActionCatalogTest.class.getClassLoader(), "test-action-schemas");
            ActionManifest manifest = new ActionManifest(
                    ActionCatalogTest.class.getClassLoader(), "test-turing-plugin.json");
            assertEquals(2, manifest.size());
            ActionCatalog catalog = new ActionCatalog(system, registry, manifest);
            ActionResult r = catalog.callByActionName("describeAction",
                    "{\"actor\":\"greeter\",\"action\":\"greetTyped\"}");
            assertTrue(r.isSuccess(), r.getResult());
            var json = JSON.readTree(r.getResult());
            assertEquals("Greets the named person.", json.get("description").asText());
            assertEquals("who to greet", json.get("schema").get("properties").get("name").get("description").asText(),
                    "the record's @param prose sits on the schema property");

            ActionResult raw = catalog.callByActionName("describeAction",
                    "{\"actor\":\"greeter\",\"action\":\"greet\"}");
            var rawJson = JSON.readTree(raw.getResult());
            assertEquals("Greets whoever the text names.", rawJson.get("description").asText());
            assertTrue(rawJson.get("schema").isNull());
            assertEquals("string", rawJson.get("argsFormat").asText(), "with no schema the doclet's guess is still told");
            assertEquals("args", rawJson.get("argument").get("name").asText(),
                    "with no schema the method's own @param is what is declared of the String");
            assertEquals("the name to greet, as plain text", rawJson.get("argument").get("description").asText());
            assertEquals("The text is used as is; a blank text greets nobody.", rawJson.get("details").asText());
            assertTrue(rawJson.get("example").asText().startsWith("- actor: greeter"), rawJson.toString());
            assertFalse(json.has("example"), "an action whose Javadoc has no <pre> block has no example");
        } finally {
            system.terminate();
        }
    }

    @Test
    void describesByClassWithoutARunningActor() {
        var names = ActionCatalog.actionNamesOf(Greeter.class);
        assertEquals(java.util.List.of("greet", "greetTyped"), new java.util.ArrayList<>(names));
        var json = ActionCatalog.describe(Greeter.class, "greetTyped",
                new ActionSchemaRegistry(ActionCatalogTest.class.getClassLoader(), "test-action-schemas"),
                new ActionManifest(ActionCatalogTest.class.getClassLoader(), "test-turing-plugin.json"));
        assertEquals(Greeter.class.getName(), json.get("class").asText());
        assertTrue(json.get("schema").get("properties").has("name"));
    }

    /**
     * Most actions take a raw String and have no declared shape. Saying so is the answer — a
     * caller that gets "no schema" knows to read the documentation, whereas an empty object
     * would read as "takes nothing".
     */
    @Test
    void sayingAnActionHasNoDeclaredShape() throws Exception {
        ActorSystem system = systemWithGreeter();
        try {
            ActionCatalog catalog = new ActionCatalog(system, new ActionSchemaRegistry());

            ActionResult r = catalog.callByActionName("describeAction",
                    "{\"actor\":\"greeter\",\"action\":\"greet\"}");

            assertTrue(r.isSuccess(), r.getResult());
            var json = JSON.readTree(r.getResult());
            assertTrue(json.get("schema").isNull(), r.getResult());
            assertTrue(json.get("note").asText().contains("String"), r.getResult());
        } finally {
            system.terminate();
        }
    }

    @Test
    void refusesAnActionItDoesNotHave() {
        ActorSystem system = systemWithGreeter();
        try {
            ActionCatalog catalog = new ActionCatalog(system, new ActionSchemaRegistry());

            assertFalse(catalog.callByActionName("noSuchThing", "{}").isSuccess());
        } finally {
            system.terminate();
        }
    }
}
