package com.scivicslab.turingworkflow.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link PluginsConfig} YAML parsing and Maven coordinate resolution.
 * These tests do not touch the real filesystem or any external service; they exercise
 * {@link PluginsConfig#parse(String, Path)} and {@link PluginsConfig#resolveArtifact(String, Path)}
 * directly against an in-test repository root.
 */
class PluginsConfigTest {

    private static final Path REPO = Path.of("/repo");

    @Test
    void resolveArtifact_followsMavenRepositoryLayout() {
        Path jar = PluginsConfig.resolveArtifact("com.scivicslab:db-logger:1.0.0", REPO);
        assertEquals(
                Path.of("/repo/com/scivicslab/db-logger/1.0.0/db-logger-1.0.0.jar"),
                jar);
    }

    @Test
    void resolveArtifact_supportsSnapshotVersions() {
        Path jar = PluginsConfig.resolveArtifact("com.scivicslab:db-logger:1.0.0-SNAPSHOT", REPO);
        assertEquals(
                Path.of("/repo/com/scivicslab/db-logger/1.0.0-SNAPSHOT/db-logger-1.0.0-SNAPSHOT.jar"),
                jar);
    }

    @Test
    void resolveArtifact_returnsNullForMalformedCoordinate() {
        assertNull(PluginsConfig.resolveArtifact("not-a-coordinate", REPO));
        assertNull(PluginsConfig.resolveArtifact("group:artifact", REPO));
        assertNull(PluginsConfig.resolveArtifact("group::version", REPO));
    }

    @Test
    void parse_resolvesArtifactCoordinatesInOrder() {
        String yaml = """
                plugins:
                  - name: db-logger
                    artifact: com.scivicslab:db-logger:1.0.0
                    repo: https://github.com/scivicslab/Turing-workflow-db-logger
                  - name: math
                    artifact: org.example:math-commands:2.1.0
                """;
        List<Path> jars = PluginsConfig.parse(yaml, REPO);
        assertEquals(2, jars.size());
        assertEquals(Path.of("/repo/com/scivicslab/db-logger/1.0.0/db-logger-1.0.0.jar"), jars.get(0));
        assertEquals(Path.of("/repo/org/example/math-commands/2.1.0/math-commands-2.1.0.jar"), jars.get(1));
    }

    @Test
    void parse_fallsBackToJarPathWhenNoArtifact() {
        String home = System.getProperty("user.home");
        String yaml = """
                plugins:
                  - name: legacy
                    jar: ~/plugins/legacy-1.0.0.jar
                """;
        List<Path> jars = PluginsConfig.parse(yaml, REPO);
        assertEquals(1, jars.size());
        assertEquals(Path.of(home, "plugins", "legacy-1.0.0.jar"), jars.get(0));
    }

    @Test
    void parse_skipsMalformedCoordinatesAndEntriesWithoutJar() {
        String yaml = """
                plugins:
                  - name: bad-coord
                    artifact: not-a-coordinate
                  - name: no-target
                  - name: ok
                    artifact: com.scivicslab:ok:1.0.0
                """;
        List<Path> jars = PluginsConfig.parse(yaml, REPO);
        assertEquals(1, jars.size());
        assertEquals(Path.of("/repo/com/scivicslab/ok/1.0.0/ok-1.0.0.jar"), jars.get(0));
    }

    @Test
    void parse_blankOrMissingPluginsKeyReturnsEmpty() {
        assertTrue(PluginsConfig.parse("", REPO).isEmpty());
        assertTrue(PluginsConfig.parse("   ", REPO).isEmpty());
        assertTrue(PluginsConfig.parse(null, REPO).isEmpty());
        assertTrue(PluginsConfig.parse("other: value", REPO).isEmpty());
    }
}
