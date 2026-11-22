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
 * An expression reaches only the values a workflow reads, not the objects behind them.
 *
 * <p>Before the allow-list, permission went by package: every class under
 * {@code com.scivicslab.turingworkflow.workflow}, {@code com.scivicslab.pojoactor} and Jackson's
 * databind was callable, sub-packages included, and {@code actors.get(name)} answered the object an
 * actor wraps. {@code FileAccumulator} and {@code ObjectMapper.writeValue(File, ...)} then wrote
 * files, and every public method of every wrapped object was a call away (chat-85, H1).</p>
 */
@Tag("ExpressionReach_ObjectReach_261122_oo01")
@DisplayName("An expression reaches values, not the objects behind them")
class ExpressionObjectReachTest {

    private static final String OM = "com.fasterxml.jackson.databind.ObjectMapper";

    private ExpressionRuns runs;

    @BeforeEach
    void setUp() {
        runs = new ExpressionRuns("object-reach");
    }

    @AfterEach
    void tearDown() {
        runs.close();
    }

    private static String fileAccumulatorWrites(Path file) {
        return "new('com.scivicslab.turingworkflow.workflow.accumulator.FileAccumulator', '" + file
                + "').add('x', 't', 'written')";
    }

    /** Jackson turns the text of a path into a {@code java.io.File} without naming the class to JEXL. */
    private static String jacksonWrites(Path file) {
        String fileOf = "new('" + OM + "').readValue('\"" + file + "\"', new('" + OM
                + "').getTypeFactory().constructFromCanonical('java.io.File'))";
        return "new('" + OM + "').writeValue(" + fileOf + ", {'owned': 'yes'})";
    }

    @Test
    @DisplayName("an argument cannot write a file through FileAccumulator")
    void argument_fileAccumulator_writesNothing(@TempDir Path dir) {
        Path file = dir.resolve("accumulated.txt");

        runs.run(ExpressionRuns.recording(fileAccumulatorWrites(file)), 5);

        assertFalse(Files.exists(file), "the expression wrote " + file);
    }

    @Test
    @DisplayName("a from-state cannot write a file through FileAccumulator")
    void fromState_fileAccumulator_writesNothing(@TempDir Path dir) {
        Path file = dir.resolve("accumulated.txt");

        runs.run(ExpressionRuns.fromState(fileAccumulatorWrites(file) + " == null && false"), 5);

        assertFalse(Files.exists(file), "the pattern wrote " + file);
    }

    @Test
    @DisplayName("an argument cannot write a file through Jackson's ObjectMapper")
    void argument_jacksonWriteValue_writesNothing(@TempDir Path dir) {
        Path file = dir.resolve("owned.json");

        runs.run(ExpressionRuns.recording(jacksonWrites(file)), 5);

        assertFalse(Files.exists(file), "the expression wrote " + file);
    }
}
