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
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.net.JarURLConnection;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLConnection;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.Map;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Runtime, read-only loader for the JSON Schema files that {@link ActionSchemaGenerator}
 * writes at build time (see that class's Javadoc for the file naming and directory
 * convention: {@code action-schemas/<ClassName>.<actionName>.schema.json}).
 *
 * <p>Loading happens once, at construction time, by scanning every classpath root (both
 * exploded directories and packaged jars) for that {@code action-schemas/} resource
 * directory. No reflection over application classes happens here — only static-file reading
 * — so this class carries no Native Image reflection burden on its own.</p>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * ActionSchemaRegistry registry = new ActionSchemaRegistry();
 * JsonNode schema = registry.schemaFor(NodeActor.class, "configure");
 * if (schema != null) {
 *     // validate incoming args against schema before dispatch
 * }
 * }</pre>
 *
 * @since 3.5.0
 * @see ActionSchemaGenerator
 */
public class ActionSchemaRegistry {

    /** The classpath resource directory {@link ActionSchemaGenerator} writes into by default. */
    public static final String DEFAULT_RESOURCE_ROOT = "action-schemas";

    private static final String SCHEMA_SUFFIX = ".schema.json";
    private static final Logger logger = Logger.getLogger(ActionSchemaRegistry.class.getName());
    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();

    private final Map<String, JsonNode> schemas = new ConcurrentHashMap<>();

    /** Loads every {@code action-schemas/*.schema.json} resource visible to this class's classloader. */
    public ActionSchemaRegistry() {
        this(ActionSchemaRegistry.class.getClassLoader(), DEFAULT_RESOURCE_ROOT);
    }

    /**
     * Loads every {@code <resourceRoot>/*.schema.json} resource visible to {@code classLoader}.
     *
     * @param classLoader  the classloader to scan (all its classpath roots are checked)
     * @param resourceRoot the resource directory name to scan under (e.g. {@code "action-schemas"})
     */
    public ActionSchemaRegistry(ClassLoader classLoader, String resourceRoot) {
        try {
            load(classLoader, resourceRoot);
        } catch (IOException e) {
            logger.log(Level.WARNING, "Failed to load action schemas from " + resourceRoot, e);
        }
    }

    /**
     * Returns the schema for the given actor class and action name, or {@code null} if none
     * was generated (e.g. the action's {@code @Action} has no {@code argsType}, or the class
     * was not part of the build-time scan).
     */
    public JsonNode schemaFor(Class<?> actorClass, String actionName) {
        return schemaFor(actorClass.getName(), actionName);
    }

    /** Same as {@link #schemaFor(Class, String)}, keyed by the actor class's fully-qualified name. */
    public JsonNode schemaFor(String className, String actionName) {
        return schemas.get(className + "." + actionName);
    }

    /**
     * The actor classes that have at least one schema, and for each the action names that do.
     *
     * <p>Names only — no schema bodies. Someone writing a workflow step wants "what can this
     * actor do, and what do I put in the arguments", which is two questions: this answers the
     * first, and {@link #schemaFor(String, String)} answers the second for the one action
     * chosen. Returning every schema at once would answer a question nobody asked, and put all
     * of them into whatever context the answer is carried in.
     *
     * @return class name to its action names, both sorted; empty when nothing was loaded
     */
    public SortedMap<String, SortedSet<String>> actionNames() {
        SortedMap<String, SortedSet<String>> byClass = new TreeMap<>();
        for (String key : schemas.keySet()) {
            int lastDot = key.lastIndexOf('.');
            if (lastDot <= 0 || lastDot == key.length() - 1) {
                continue;   // not "<class>.<action>"; nothing generated this, so nothing reads it
            }
            byClass.computeIfAbsent(key.substring(0, lastDot), c -> new TreeSet<>())
                   .add(key.substring(lastDot + 1));
        }
        return byClass;
    }

    /** The number of schemas currently loaded. */
    public int size() {
        return schemas.size();
    }

    // ── Loading ─────────────────────────────────────────────────────────────

    private void load(ClassLoader classLoader, String resourceRoot) throws IOException {
        Enumeration<URL> roots = classLoader.getResources(resourceRoot);
        while (roots.hasMoreElements()) {
            URL root = roots.nextElement();
            switch (root.getProtocol()) {
                case "file" -> loadFromDirectory(root);
                case "jar" -> loadFromJar(root);
                default -> logger.warning("Unsupported action-schemas URL protocol: " + root);
            }
        }
    }

    private void loadFromDirectory(URL dirUrl) throws IOException {
        Path dir;
        try {
            dir = Path.of(dirUrl.toURI());
        } catch (URISyntaxException e) {
            throw new IOException("Malformed action-schemas directory URL: " + dirUrl, e);
        }
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (var files = Files.list(dir)) {
            files.filter(p -> p.getFileName().toString().endsWith(SCHEMA_SUFFIX))
                 .forEach(this::loadFile);
        }
    }

    private void loadFile(Path schemaFile) {
        String fileName = schemaFile.getFileName().toString();
        String key = fileName.substring(0, fileName.length() - SCHEMA_SUFFIX.length());
        try (InputStream in = Files.newInputStream(schemaFile)) {
            schemas.put(key, JSON_MAPPER.readTree(in));
        } catch (IOException e) {
            logger.log(Level.WARNING, "Failed to read action schema " + schemaFile, e);
        }
    }

    private void loadFromJar(URL jarDirUrl) throws IOException {
        URLConnection connection = jarDirUrl.openConnection();
        if (!(connection instanceof JarURLConnection jarConnection)) {
            logger.warning("Expected JarURLConnection for " + jarDirUrl);
            return;
        }
        // The resource root itself (e.g. "action-schemas") — entries are matched by this prefix
        // rather than relying on jarConnection.getEntryName(), which for a directory resource
        // may or may not include a trailing slash depending on how the jar was built.
        String prefix = jarConnection.getEntryName();
        if (prefix == null) {
            return;
        }
        if (!prefix.endsWith("/")) {
            prefix = prefix + "/";
        }

        try (JarFile jarFile = jarConnection.getJarFile()) {
            Enumeration<JarEntry> entries = jarFile.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                String name = entry.getName();
                if (entry.isDirectory() || !name.startsWith(prefix) || !name.endsWith(SCHEMA_SUFFIX)) {
                    continue;
                }
                String fileName = name.substring(name.lastIndexOf('/') + 1);
                String key = fileName.substring(0, fileName.length() - SCHEMA_SUFFIX.length());
                try (InputStream in = jarFile.getInputStream(entry)) {
                    schemas.put(key, JSON_MAPPER.readTree(in));
                } catch (IOException e) {
                    logger.log(Level.WARNING, "Failed to read action schema jar entry " + name, e);
                }
            }
        }
    }
}
