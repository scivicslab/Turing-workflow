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

import com.scivicslab.pojoactor.action.AbstractCallableByActionName;
import com.scivicslab.pojoactor.action.Action;
import com.scivicslab.pojoactor.action.ActionResult;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

@Tag("S_svc.05")
@DisplayName("ActionSchemaGenerator — build-time JSON Schema generation for @Action(argsType=...)")
class ActionSchemaGeneratorTest {

    // Fixture: a record and an @Action method using it. Scanned from this test's own compiled
    // output (target/test-classes), the same way ActionSchemaGenerator scans a real project's
    // target/classes — no separate compilation step needed inside the test.
    record FixtureArgs(String hostname, int port, boolean ssl) {}

    static class FixtureActor extends AbstractCallableByActionName {
        @Action(value = "configure", argsType = FixtureArgs.class)
        public ActionResult configure(FixtureArgs args) {
            return new ActionResult(true, args.hostname());
        }

        @Action("greet")
        public ActionResult greet(String args) {
            return new ActionResult(true, "Hello, " + args);
        }
    }

    private static Path fixtureClassesDir() {
        // generate() reconstructs each class's fully-qualified name by relativizing against
        // classesDir, so classesDir must be the classpath root (matching real usage, where a
        // project always points this at target/classes) — not a package subdirectory.
        return Path.of("target", "test-classes");
    }

    @Test
    @DisplayName("generate: writes a schema for the argsType method but not for the plain-String method")
    void generate_writesSchemaForArgsTypeMethodOnly(@TempDir Path outputDir) throws Exception {
        // target/test-classes holds every test class in the project, not just this file's
        // fixtures, so the total count isn't asserted here — only that this fixture's own
        // configure (argsType) produced a file and its own greet (plain String) did not.
        ActionSchemaGenerator.generate(fixtureClassesDir(), outputDir);

        assertTrue(Files.exists(outputDir.resolve(FixtureActor.class.getName() + ".configure.schema.json")));
        assertFalse(Files.exists(outputDir.resolve(FixtureActor.class.getName() + ".greet.schema.json")),
                "greet (plain String, no argsType) must not produce a schema");
    }

    @Test
    @DisplayName("generate: schema file is named <ClassName>.<actionName>.schema.json")
    void generate_schemaFileNamedByClassAndActionName(@TempDir Path outputDir) throws Exception {
        ActionSchemaGenerator.generate(fixtureClassesDir(), outputDir);

        Path expected = outputDir.resolve(FixtureActor.class.getName() + ".configure.schema.json");
        assertTrue(Files.exists(expected), "expected schema file: " + expected);
    }

    @Test
    @DisplayName("generate: schema content declares the record's field names as required properties")
    void generate_schemaDeclaresRecordFieldNames(@TempDir Path outputDir) throws Exception {
        ActionSchemaGenerator.generate(fixtureClassesDir(), outputDir);

        Path schemaFile = outputDir.resolve(FixtureActor.class.getName() + ".configure.schema.json");
        String schema = Files.readString(schemaFile);

        assertTrue(schema.contains("\"hostname\""), schema);
        assertTrue(schema.contains("\"port\""), schema);
        assertTrue(schema.contains("\"ssl\""), schema);
    }

    @Test
    @DisplayName("generate: an empty directory produces zero schemas without error")
    void generate_emptyDirectory_producesZeroSchemas(@TempDir Path emptyDir, @TempDir Path outputDir)
            throws Exception {
        int count = ActionSchemaGenerator.generate(emptyDir, outputDir);
        assertEquals(0, count);
    }
}
