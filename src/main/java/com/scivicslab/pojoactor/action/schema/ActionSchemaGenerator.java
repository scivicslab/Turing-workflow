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

import com.fasterxml.jackson.databind.JsonNode;
import com.github.victools.jsonschema.generator.OptionPreset;
import com.github.victools.jsonschema.generator.SchemaGenerator;
import com.github.victools.jsonschema.generator.SchemaGeneratorConfig;
import com.github.victools.jsonschema.generator.SchemaGeneratorConfigBuilder;
import com.github.victools.jsonschema.generator.SchemaVersion;
import com.scivicslab.pojoactor.action.Action;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;

/**
 * Build-time tool that scans compiled classes for {@link Action @Action}-annotated methods
 * with a non-default {@code argsType()}, and generates a JSON Schema file for each one.
 *
 * <p><strong>Build time only.</strong> Intended to be run via a Maven plugin (e.g.
 * {@code exec-maven-plugin}'s {@code exec:java} goal) bound to the {@code process-classes}
 * phase, against the invoking project's own {@code target/classes} — never at application
 * runtime. Generated schemas are written as plain {@code .json} resource files, so they carry
 * no runtime reflection cost: the application only ever reads static files (see
 * {@code ActionSchemaRegistry}).</p>
 *
 * <h2>Usage (command line / exec-maven-plugin)</h2>
 * <pre>{@code
 * java com.scivicslab.pojoactor.action.schema.ActionSchemaGenerator \
 *     target/classes \
 *     target/classes/action-schemas
 * }</pre>
 *
 * <h2>Maven binding</h2>
 * <pre>{@code
 * <plugin>
 *   <groupId>org.codehaus.mojo</groupId>
 *   <artifactId>exec-maven-plugin</artifactId>
 *   <executions>
 *     <execution>
 *       <id>generate-action-schemas</id>
 *       <phase>process-classes</phase>
 *       <goals><goal>java</goal></goals>
 *       <configuration>
 *         <mainClass>com.scivicslab.pojoactor.action.schema.ActionSchemaGenerator</mainClass>
 *       </configuration>
 *     </execution>
 *   </executions>
 * </plugin>
 * }</pre>
 *
 * <h2>Output naming</h2>
 *
 * <p>Each schema is keyed by the <em>declaring class</em>, not by an actor's runtime-assigned
 * instance name (a class may be registered under different names in different workflows, and
 * all instances share the same argument shape). The output file for action {@code "configure"}
 * declared on {@code com.example.NodeActor} is
 * {@code com.example.NodeActor.configure.schema.json}.</p>
 *
 * <h2>Classloading</h2>
 *
 * <p>{@link #generate(Path, Path)} builds its own {@link java.net.URLClassLoader} rooted at
 * {@code classesDir}, parented on this class's own defining classloader. It deliberately does
 * not rely on {@code Thread.currentThread().getContextClassLoader()} or on
 * {@code ActionSchemaGenerator.class.getClassLoader()} alone to already see {@code classesDir}:
 * under {@code exec-maven-plugin}, where this tool is typically declared as a {@code <plugin>}
 * dependency (see the Maven binding example above), neither of those ambient classloaders
 * reliably includes the invoking project's own compiled output. Building a dedicated loader
 * from the explicit {@code classesDir} parameter avoids depending on classloader wiring that
 * varies by caller.</p>
 *
 * @since 3.5.0
 * @see Action
 */
public final class ActionSchemaGenerator {

    private static final Logger logger = Logger.getLogger(ActionSchemaGenerator.class.getName());
    private static final String CLASS_SUFFIX = ".class";

    private ActionSchemaGenerator() {}

    public static void main(String[] args) throws IOException {
        Path classesDir = Path.of(args.length > 0 ? args[0] : "target/classes");
        Path outputDir = Path.of(args.length > 1 ? args[1] : "target/classes/action-schemas");
        int count = generate(classesDir, outputDir);
        System.out.println("ActionSchemaGenerator: wrote " + count + " schema(s) to " + outputDir);
    }

    /**
     * Scans every {@code .class} file under {@code classesDir}, and for each
     * {@link Action @Action}-annotated method whose {@code argsType()} is not the default
     * {@code Void.class}, writes a generated JSON Schema for that type into {@code outputDir}.
     *
     * @param classesDir directory containing compiled {@code .class} files (e.g. {@code target/classes})
     * @param outputDir  directory to write {@code <ClassName>.<actionName>.schema.json} files into
     * @return the number of schema files written
     */
    public static int generate(Path classesDir, Path outputDir) throws IOException {
        Files.createDirectories(outputDir);

        SchemaGeneratorConfigBuilder configBuilder =
                new SchemaGeneratorConfigBuilder(SchemaVersion.DRAFT_2020_12, OptionPreset.PLAIN_JSON);
        SchemaGeneratorConfig config = configBuilder.build();
        SchemaGenerator generator = new SchemaGenerator(config);

        // A dedicated loader rooted at classesDir, built here rather than relying on any
        // ambient classloader (this class's own defining loader, or the thread's context
        // classloader). Both were observed to NOT reliably see the caller's target/classes
        // under exec-maven-plugin — the tool is declared as a <plugin> dependency there (see
        // ActionArgumentSchema_260807_oo01), which puts it in a different classloader than the
        // invoking project's compiled output. Parenting on this class's own loader still
        // resolves framework classes (Action, ActionResult, etc.).
        int count = 0;
        try (URLClassLoader classpathLoader = new URLClassLoader(
                new URL[]{classesDir.toUri().toURL()}, ActionSchemaGenerator.class.getClassLoader())) {
            try (Stream<Path> walk = Files.walk(classesDir)) {
                for (Path classFile : (Iterable<Path>) walk.filter(ActionSchemaGenerator::isClassFile)::iterator) {
                    Class<?> clazz = loadClass(classesDir, classFile, classpathLoader);
                    if (clazz == null) {
                        continue;
                    }
                    count += writeSchemasFor(clazz, generator, outputDir);
                }
            }
        }
        return count;
    }

    private static boolean isClassFile(Path p) {
        // Deliberately does not exclude nested-class files (e.g. "Outer$Inner.class") — a
        // fixture or plugin's @Action methods are commonly declared on a static nested class.
        // Unloadable/irrelevant classes (anonymous classes, package-info, etc.) are simply
        // skipped in loadClass() instead.
        return p.getFileName().toString().endsWith(CLASS_SUFFIX);
    }

    /** Loads a class by its file path relative to {@code classesDir}; returns null if unloadable. */
    private static Class<?> loadClass(Path classesDir, Path classFile, ClassLoader classpathLoader) {
        String relative = classesDir.relativize(classFile).toString();
        String className = relative.substring(0, relative.length() - CLASS_SUFFIX.length())
                .replace(File.separatorChar, '.');
        try {
            // initialize=false: this tool only inspects method annotations via reflection, so
            // running the class's static initializers would be unnecessary (and, for actor
            // classes with heavier static setup, unwanted) side effects at build time.
            return Class.forName(className, false, classpathLoader);
        } catch (Throwable t) {
            // Unloadable classes (missing optional deps, package-info, module-info, etc.) are
            // simply not scanned — this generator only cares about classes it can reflect on.
            logger.log(Level.FINE, "Skipping unloadable class " + className, t);
            return null;
        }
    }

    private static int writeSchemasFor(Class<?> clazz, SchemaGenerator generator, Path outputDir)
            throws IOException {
        int count = 0;
        for (Method method : clazz.getDeclaredMethods()) {
            Action action = method.getAnnotation(Action.class);
            if (action == null || action.argsType() == Void.class) {
                continue;
            }
            JsonNode schema = generator.generateSchema(action.argsType());
            String fileName = clazz.getName() + "." + action.value() + ".schema.json";
            Files.writeString(outputDir.resolve(fileName), schema.toPrettyString(), StandardCharsets.UTF_8);
            count++;
        }
        return count;
    }
}
