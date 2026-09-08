package com.scivicslab.pojoactor.action.schema;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Checks that a registry can read schemas from a classloader created after construction.
 *
 * <p>A jar added while a workflow is running lands in a child classloader that the constructor
 * never scanned. These tests write schema files to a temporary directory, expose it through a
 * {@link URLClassLoader}, and confirm the registry picks them up. Nothing outside the JVM is
 * contacted.</p>
 */
@DisplayName("ActionSchemaRegistry.addFrom")
class ActionSchemaRegistryAddFromTest {

    private static final String SCHEMA_BODY = """
        {"type":"object","properties":{"prompt":{"type":"string"}},"required":["prompt"]}
        """;

    /** Writes one schema file under an {@code action-schemas} directory and returns the root. */
    private static URLClassLoader classLoaderWith(Path root, String className, String actionName)
            throws IOException {
        Path dir = root.resolve(ActionSchemaRegistry.DEFAULT_RESOURCE_ROOT);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(className + "." + actionName + ".schema.json"), SCHEMA_BODY);
        return new URLClassLoader(new URL[]{root.toUri().toURL()}, null);
    }

    @Test
    @DisplayName("a schema that appears after construction is read and becomes retrievable")
    void addFrom_readsSchemaAddedLater(@TempDir Path tmp) throws IOException {
        var registry = new ActionSchemaRegistry(new URLClassLoader(new URL[0], null),
                                                ActionSchemaRegistry.DEFAULT_RESOURCE_ROOT);
        assertNull(registry.schemaFor("com.example.LateActor", "sendPrompt"),
                   "the registry must not hold the schema before the classloader exists");

        try (var later = classLoaderWith(tmp, "com.example.LateActor", "sendPrompt")) {
            assertEquals(1, registry.addFrom(later), "one schema should have been gained");
        }

        assertNotNull(registry.schemaFor("com.example.LateActor", "sendPrompt"),
                      "the schema must be retrievable after addFrom");
    }

    @Test
    @DisplayName("reading the same classloader twice gains nothing the second time")
    void addFrom_isIdempotent(@TempDir Path tmp) throws IOException {
        var registry = new ActionSchemaRegistry(new URLClassLoader(new URL[0], null),
                                                ActionSchemaRegistry.DEFAULT_RESOURCE_ROOT);
        try (var later = classLoaderWith(tmp, "com.example.TwiceActor", "run")) {
            assertEquals(1, registry.addFrom(later));
            assertEquals(0, registry.addFrom(later), "the same key must not be counted again");
        }
        assertEquals(1, registry.size());
    }

    @Test
    @DisplayName("a classloader with no action-schemas directory gains nothing")
    void addFrom_emptyClassLoader_gainsNothing(@TempDir Path tmp) throws IOException {
        var registry = new ActionSchemaRegistry(new URLClassLoader(new URL[0], null),
                                                ActionSchemaRegistry.DEFAULT_RESOURCE_ROOT);
        try (var empty = new URLClassLoader(new URL[]{tmp.toUri().toURL()}, null)) {
            assertEquals(0, registry.addFrom(empty));
        }
    }

    @Test
    @DisplayName("the action appears in actionNames after being added")
    void addFrom_updatesActionNames(@TempDir Path tmp) throws IOException {
        var registry = new ActionSchemaRegistry(new URLClassLoader(new URL[0], null),
                                                ActionSchemaRegistry.DEFAULT_RESOURCE_ROOT);
        try (var later = classLoaderWith(tmp, "com.example.ListedActor", "configure")) {
            registry.addFrom(later);
        }
        assertTrue(registry.actionNames().containsKey("com.example.ListedActor"));
        assertTrue(registry.actionNames().get("com.example.ListedActor").contains("configure"));
    }
}
