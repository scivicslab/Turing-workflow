package com.scivicslab.pojoactor.action.schema;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.scivicslab.pojoactor.action.Action;
import com.scivicslab.pojoactor.action.ActionResult;

import jakarta.validation.constraints.NotNull;

/**
 * Whether a generated schema says which fields must be present.
 *
 * <p>A record component's type carries no nullability — {@code String prompt} and
 * {@code String model} are the same type — so the generator cannot tell a required field from an
 * optional one on its own. {@code @NotNull} is where that is said.
 */
@DisplayName("ActionSchemaGenerator — declaring which fields are required")
class RequiredFieldSchemaTest {

    public record ConfigureArgs(@NotNull String hostname, int port, String comment) {}

    public static class NodeActor {
        @Action(value = "configure", argsType = ConfigureArgs.class)
        public ActionResult configure(ConfigureArgs args) {
            return new ActionResult(true, args.hostname());
        }
    }

    private static JsonNode generateFor(Path tmp) throws Exception {
        Path classes = Path.of("target", "test-classes");
        Path out = tmp.resolve("out");
        ActionSchemaGenerator.generate(classes, out);
        Path schema = out.resolve(NodeActor.class.getName() + ".configure.schema.json");
        assertTrue(Files.exists(schema), "no schema written for " + NodeActor.class.getName());
        return new ObjectMapper().readTree(Files.readString(schema));
    }

    @Test
    void aNotNullFieldIsRequired(@TempDir Path tmp) throws Exception {
        JsonNode schema = generateFor(tmp);

        JsonNode required = schema.get("required");
        assertTrue(required != null && required.isArray(), "no required list: " + schema);
        assertEquals(1, required.size(), required.toString());
        assertEquals("hostname", required.get(0).asText());
    }

    /**
     * A field without the annotation stays optional, so a call that omits it still passes. The
     * shape has to be able to say "may be left out" — sendPrompt's model is genuinely optional.
     */
    @Test
    void afieldWithoutTheAnnotationIsNotRequired(@TempDir Path tmp) throws Exception {
        JsonNode schema = generateFor(tmp);

        String required = schema.get("required").toString();
        assertFalse(required.contains("comment"), required);
        assertFalse(required.contains("port"), required);
    }
}
