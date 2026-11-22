package com.scivicslab.turingworkflow.workflow;

import static org.junit.jupiter.api.Assertions.assertFalse;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * An expression in a workflow YAML cannot read or write a file, in either of the two places an
 * expression is written: an action's argument, and a step's from-state or to-state.
 *
 * <p>JEXL 3.3's {@code RESTRICTED} set denies {@code java.io.File} but allows the rest of
 * {@code java.io}, so {@code new('java.io.FileInputStream', path)} read any file the JVM could read
 * and {@code new('java.io.FileOutputStream', path)} wrote one (chat-85, C2).</p>
 */
@Tag("ExpressionReach_FileAccess_261122_oo01")
@DisplayName("An expression cannot read or write a file")
class ExpressionFileAccessTest {

    private static final String SECRET = "secret-c95-file-access";

    private ExpressionRuns runs;

    @BeforeEach
    void setUp() {
        runs = new ExpressionRuns("file-access");
    }

    @AfterEach
    void tearDown() {
        runs.close();
    }

    private static String read(Path file) {
        return "new('java.lang.String', new('java.io.FileInputStream', '" + file + "').readAllBytes(), 'UTF-8')";
    }

    private static String write(Path file) {
        return "new('java.io.FileOutputStream', '" + file + "').write('written'.getBytes())";
    }

    @Test
    @DisplayName("an argument cannot read a file")
    void argument_readsFile_answersNothingOfIt(@TempDir Path dir) throws Exception {
        Path file = Files.writeString(dir.resolve("secret.txt"), SECRET);

        runs.run(ExpressionRuns.recording(read(file)), 5);

        assertFalse(String.join("\n", runs.recorded).contains(SECRET), "the action received " + runs.recorded);
        assertFalse(String.valueOf(runs.self.getLastResultValue()).contains(SECRET),
                "the last result holds " + runs.self.getLastResultValue());
    }

    @Test
    @DisplayName("an argument cannot write a file")
    void argument_writesFile_writesNothing(@TempDir Path dir) {
        Path file = dir.resolve("written.txt");

        runs.run(ExpressionRuns.recording(write(file)), 5);

        assertFalse(Files.exists(file), "the expression wrote " + file);
    }

    @Test
    @DisplayName("a to-state cannot read a file")
    void toState_readsFile_answersNothingOfIt(@TempDir Path dir) throws Exception {
        Path file = Files.writeString(dir.resolve("secret.txt"), SECRET);

        runs.run(ExpressionRuns.toState(read(file)), 5);

        assertFalse(String.valueOf(runs.interpreter.getCurrentState()).contains(SECRET),
                "the workflow moved to the file's content: " + runs.interpreter.getCurrentState());
    }

    @Test
    @DisplayName("a from-state cannot write a file")
    void fromState_writesFile_writesNothing(@TempDir Path dir) {
        Path file = dir.resolve("written.txt");

        runs.run(ExpressionRuns.fromState(write(file) + " == null && false"), 5);

        assertFalse(Files.exists(file), "the pattern wrote " + file);
    }
}
