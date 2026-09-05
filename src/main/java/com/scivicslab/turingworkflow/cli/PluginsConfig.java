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

package com.scivicslab.turingworkflow.cli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.yaml.snakeyaml.Yaml;

/**
 * Reads the plugins configuration file and returns the list of plugin JAR paths to load
 * at startup.
 *
 * <p>The config file lists, under the {@code plugins} key, entries that each have a
 * {@code name} and a Maven {@code artifact} coordinate ({@code groupId:artifactId:version}).
 * The coordinate is resolved deterministically to a JAR in the Maven local repository
 * ({@code ~/.m2/repository} by default). The optional {@code repo} field is a provenance
 * note and is ignored by the loader. An entry may instead carry a {@code jar} absolute path
 * as an escape hatch for a JAR that is not in the local repository.</p>
 *
 * <pre>
 * plugins:
 *   - name: db-logger
 *     artifact: com.scivicslab:db-logger:1.0.0
 *     repo: https://github.com/scivicslab/Turing-workflow-db-logger
 *   - name: legacy
 *     jar: /opt/plugins/legacy-1.0.0.jar
 * </pre>
 *
 * <p>The config path is the value of the {@code TURING_WORKFLOW_PLUGINS} environment
 * variable, or {@code ~/.turing-workflow/plugins.yaml} when that variable is unset. The
 * Maven local repository root is the value of {@code MAVEN_REPO_LOCAL}, or
 * {@code ~/.m2/repository} when unset.</p>
 */
public final class PluginsConfig {

    private static final Logger LOG = Logger.getLogger(PluginsConfig.class.getName());

    /** Environment variable that overrides the default config path. */
    public static final String ENV_CONFIG_PATH = "TURING_WORKFLOW_PLUGINS";

    /** Environment variable that overrides the Maven local repository root. */
    public static final String ENV_REPO_LOCAL = "MAVEN_REPO_LOCAL";

    private PluginsConfig() {
    }

    /**
     * Resolves the plugins config file path.
     *
     * @return the config path from {@code TURING_WORKFLOW_PLUGINS}, or the default
     *         {@code ~/.turing-workflow/plugins.yaml} when that variable is unset
     */
    public static Path configPath() {
        String env = System.getenv(ENV_CONFIG_PATH);
        if (env != null && !env.isBlank()) {
            return Path.of(expandHome(env.trim()));
        }
        return Path.of(System.getProperty("user.home"), ".turing-workflow", "plugins.yaml");
    }

    /**
     * Resolves the Maven local repository root.
     *
     * @return the path from {@code MAVEN_REPO_LOCAL}, or {@code ~/.m2/repository} when unset
     */
    public static Path repoRoot() {
        String env = System.getenv(ENV_REPO_LOCAL);
        if (env != null && !env.isBlank()) {
            return Path.of(expandHome(env.trim()));
        }
        return Path.of(System.getProperty("user.home"), ".m2", "repository");
    }

    /**
     * Loads the plugin JAR paths from the resolved config file.
     * Returns an empty list when the file is absent or unreadable, so that a missing
     * config does not stop startup.
     *
     * @return list of plugin JAR paths, never null
     */
    public static List<Path> load() {
        Path path = configPath();
        if (!Files.isRegularFile(path)) {
            return List.of();
        }
        try {
            return parse(Files.readString(path));
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Failed to read plugins config {0}: {1}",
                    new Object[] {path, e.getMessage()});
            return List.of();
        }
    }

    /**
     * Parses plugins config YAML content into a list of JAR paths, resolving Maven
     * coordinates against the default {@link #repoRoot()}.
     *
     * @param yamlContent YAML text of the config file
     * @return list of plugin JAR paths, never null
     */
    public static List<Path> parse(String yamlContent) {
        return parse(yamlContent, repoRoot());
    }

    /**
     * Parses plugins config YAML content into a list of JAR paths, resolving Maven
     * coordinates against the given repository root. Each entry contributes a JAR via its
     * {@code artifact} coordinate, or its {@code jar} path when no coordinate is present.
     * Entries that resolve to nothing are skipped with a warning.
     *
     * @param yamlContent YAML text of the config file
     * @param repoRoot    Maven local repository root used to resolve coordinates
     * @return list of plugin JAR paths, never null
     */
    static List<Path> parse(String yamlContent, Path repoRoot) {
        List<Path> jars = new ArrayList<>();
        if (yamlContent == null || yamlContent.isBlank()) {
            return jars;
        }
        Object root = new Yaml().load(yamlContent);
        if (!(root instanceof Map<?, ?> rootMap)) {
            return jars;
        }
        Object pluginsNode = rootMap.get("plugins");
        if (!(pluginsNode instanceof List<?> pluginList)) {
            return jars;
        }
        for (Object entry : pluginList) {
            if (!(entry instanceof Map<?, ?> entryMap)) {
                continue;
            }
            Object artifact = entryMap.get("artifact");
            if (artifact != null && !artifact.toString().isBlank()) {
                Path resolved = resolveArtifact(artifact.toString().trim(), repoRoot);
                if (resolved != null) {
                    jars.add(resolved);
                }
                continue;
            }
            Object jar = entryMap.get("jar");
            if (jar != null && !jar.toString().isBlank()) {
                jars.add(Path.of(expandHome(jar.toString().trim())));
            }
        }
        return jars;
    }

    /**
     * Resolves a Maven coordinate {@code groupId:artifactId:version} to a JAR path under the
     * given repository root, following the standard Maven repository layout
     * {@code <root>/<groupId as path>/<artifactId>/<version>/<artifactId>-<version>.jar}.
     *
     * @param coordinate Maven coordinate {@code groupId:artifactId:version}
     * @param repoRoot   Maven local repository root
     * @return the resolved JAR path, or {@code null} when the coordinate is malformed
     */
    static Path resolveArtifact(String coordinate, Path repoRoot) {
        String[] parts = coordinate.split(":");
        if (parts.length != 3 || parts[0].isBlank() || parts[1].isBlank() || parts[2].isBlank()) {
            LOG.log(Level.WARNING, "Skipping malformed plugin artifact coordinate: {0}", coordinate);
            return null;
        }
        String groupId = parts[0].trim();
        String artifactId = parts[1].trim();
        String version = parts[2].trim();
        Path dir = repoRoot;
        for (String segment : groupId.split("\\.")) {
            dir = dir.resolve(segment);
        }
        return dir.resolve(artifactId).resolve(version).resolve(artifactId + "-" + version + ".jar");
    }

    /**
     * Expands a leading {@code ~} or {@code ~/} to the user home directory.
     *
     * @param path a filesystem path that may start with {@code ~}
     * @return the path with the home directory substituted, or the path unchanged
     */
    private static String expandHome(String path) {
        if (path.equals("~")) {
            return System.getProperty("user.home");
        }
        if (path.startsWith("~/")) {
            return System.getProperty("user.home") + path.substring(1);
        }
        return path;
    }
}
