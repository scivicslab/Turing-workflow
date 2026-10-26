package com.scivicslab.turingworkflow.cli;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.DefaultParser;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link DescribeCLI} with an overlay.
 *
 * <p>Uses the kustomize fixtures under {@code src/test/resources/kustomize}: the production
 * overlay sets {@code namePrefix: prod-}, so the overlaid {@code main-workflow.yaml} is keyed
 * {@code prod-main-workflow.yaml}, and its patch inserts the step {@code setup-logging}.</p>
 */
class DescribeCLITest {

    private static final Path KUSTOMIZE = Path.of("src/test/resources/kustomize");

    @Test
    void describe_withNamePrefixOverlay_showsTheOverlaidWorkflow() throws Exception {
        String output = describe(
                "-d", KUSTOMIZE.resolve("base").toString(),
                "-w", "main-workflow.yaml",
                "-o", KUSTOMIZE.resolve("overlays/production").toString(),
                "--steps");

        assertTrue(output.contains("Workflow: prod-MainWorkflow"), output);
        assertTrue(output.contains("setup-logging"), output);
    }

    private static String describe(String... args) throws Exception {
        CommandLine cl = new DefaultParser().parse(DescribeCLI.options(), args);
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        PrintStream original = System.out;
        System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));
        try {
            new DescribeCLI().execute(cl);
        } finally {
            System.setOut(original);
        }
        return captured.toString(StandardCharsets.UTF_8);
    }
}
