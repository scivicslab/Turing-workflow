/*
 * Copyright 2025 devteam@scivicslab.com
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package com.scivicslab.pojoactor.action.schema;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

@Tag("S_svc.05")
@DisplayName("ActionSchemaRegistry — runtime loading of build-time-generated schemas")
class ActionSchemaRegistryTest {

    private static final String SAMPLE_SCHEMA =
            "{\"type\":\"object\",\"properties\":{\"hostname\":{\"type\":\"string\"}},"
            + "\"required\":[\"hostname\"]}";

    // ── Directory-based classpath root (dev / exploded-classes mode) ──────────

    @Nested
    @DisplayName("loading from an exploded directory on the classpath")
    class DirectoryBased {

        private URLClassLoader loaderFor(Path root) throws IOException {
            return new URLClassLoader(new URL[]{root.toUri().toURL()}, null);
        }

        @Test
        @DisplayName("actionNames groups the actions under their actor class, names only")
        void actionNames_groupsByClassWithoutSchemaBodies(@TempDir Path root) throws IOException {
            Path schemasDir = root.resolve("action-schemas");
            Files.createDirectories(schemasDir);
            Files.writeString(schemasDir.resolve("com.example.NodeActor.configure.schema.json"), SAMPLE_SCHEMA);
            Files.writeString(schemasDir.resolve("com.example.NodeActor.restart.schema.json"), SAMPLE_SCHEMA);
            Files.writeString(schemasDir.resolve("com.example.DiskActor.mount.schema.json"), SAMPLE_SCHEMA);

            var byClass = new ActionSchemaRegistry(loaderFor(root), "action-schemas").actionNames();

            assertEquals(java.util.List.of("com.example.DiskActor", "com.example.NodeActor"),
                    java.util.List.copyOf(byClass.keySet()));
            assertEquals(java.util.List.of("configure", "restart"),
                    java.util.List.copyOf(byClass.get("com.example.NodeActor")));
            assertEquals(java.util.List.of("mount"),
                    java.util.List.copyOf(byClass.get("com.example.DiskActor")));
        }

        @Test
        @DisplayName("actionNames is empty when nothing was loaded")
        void actionNames_empty_whenNothingLoaded(@TempDir Path root) throws IOException {
            assertTrue(new ActionSchemaRegistry(loaderFor(root), "action-schemas").actionNames().isEmpty());
        }

        @Test
        @DisplayName("finds a schema written directly under the resource root")
        void load_findsSchemaUnderResourceRoot(@TempDir Path root) throws IOException {
            Path schemasDir = root.resolve("action-schemas");
            Files.createDirectories(schemasDir);
            Files.writeString(schemasDir.resolve("com.example.NodeActor.configure.schema.json"), SAMPLE_SCHEMA);

            ActionSchemaRegistry registry = new ActionSchemaRegistry(loaderFor(root), "action-schemas");

            assertEquals(1, registry.size());
            JsonNode schema = registry.schemaFor("com.example.NodeActor", "configure");
            assertNotNull(schema);
            assertTrue(schema.has("required"));
        }

        @Test
        @DisplayName("schemaFor(Class, String) and schemaFor(String, String) agree")
        void schemaFor_classOverload_matchesStringOverload(@TempDir Path root) throws IOException {
            Path schemasDir = root.resolve("action-schemas");
            Files.createDirectories(schemasDir);
            Files.writeString(
                    schemasDir.resolve(ActionSchemaRegistryTest.class.getName() + ".doStuff.schema.json"),
                    SAMPLE_SCHEMA);

            ActionSchemaRegistry registry = new ActionSchemaRegistry(loaderFor(root), "action-schemas");

            JsonNode byClass = registry.schemaFor(ActionSchemaRegistryTest.class, "doStuff");
            JsonNode byName = registry.schemaFor(ActionSchemaRegistryTest.class.getName(), "doStuff");
            assertNotNull(byClass);
            assertEquals(byName, byClass);
        }

        @Test
        @DisplayName("unknown (class, action) pair returns null")
        void schemaFor_unknownAction_returnsNull(@TempDir Path root) throws IOException {
            ActionSchemaRegistry registry = new ActionSchemaRegistry(loaderFor(root), "action-schemas");
            assertNull(registry.schemaFor("com.example.Nope", "doesNotExist"));
        }

        @Test
        @DisplayName("non-.schema.json files under the resource root are ignored")
        void load_ignoresUnrelatedFiles(@TempDir Path root) throws IOException {
            Path schemasDir = root.resolve("action-schemas");
            Files.createDirectories(schemasDir);
            Files.writeString(schemasDir.resolve("README.md"), "not a schema");
            Files.writeString(schemasDir.resolve("com.example.NodeActor.configure.schema.json"), SAMPLE_SCHEMA);

            ActionSchemaRegistry registry = new ActionSchemaRegistry(loaderFor(root), "action-schemas");

            assertEquals(1, registry.size());
        }

        @Test
        @DisplayName("a resource root that does not exist on the classpath loads zero schemas")
        void load_missingResourceRoot_loadsZero(@TempDir Path root) throws IOException {
            // root has no action-schemas/ subdirectory at all
            ActionSchemaRegistry registry = new ActionSchemaRegistry(loaderFor(root), "action-schemas");
            assertEquals(0, registry.size());
        }
    }

    // ── Jar-based classpath root (packaged / uber-jar mode) ────────────────────

    @Nested
    @DisplayName("loading from a packaged jar on the classpath")
    class JarBased {

        private Path buildJarWithSchema(Path dir, String entryName, String content) throws IOException {
            Path jarPath = dir.resolve("fixture.jar");
            try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(jarPath))) {
                jar.putNextEntry(new JarEntry("action-schemas/"));
                jar.closeEntry();
                jar.putNextEntry(new JarEntry(entryName));
                jar.write(content.getBytes());
                jar.closeEntry();
            }
            return jarPath;
        }

        @Test
        @DisplayName("finds a schema packaged inside a jar's action-schemas/ directory")
        void load_findsSchemaInsideJar(@TempDir Path dir) throws IOException {
            Path jar = buildJarWithSchema(dir, "action-schemas/com.example.NodeActor.configure.schema.json",
                    SAMPLE_SCHEMA);

            try (URLClassLoader loader = new URLClassLoader(new URL[]{jar.toUri().toURL()}, null)) {
                ActionSchemaRegistry registry = new ActionSchemaRegistry(loader, "action-schemas");

                assertEquals(1, registry.size());
                JsonNode schema = registry.schemaFor("com.example.NodeActor", "configure");
                assertNotNull(schema);
                assertTrue(schema.has("required"));
            }
        }
    }
}
