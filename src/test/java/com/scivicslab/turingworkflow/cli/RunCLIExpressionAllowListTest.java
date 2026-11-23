package com.scivicslab.turingworkflow.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.DefaultParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import com.scivicslab.turingworkflow.workflow.IIActorSystem;

/**
 * The {@code run} command limits the expressions of the workflow to the allow-list only when it is
 * given {@code --expression-allow-list}; without it an expression has the reach of Java.
 */
@Tag("ExpressionAllowListOption_261123_oo01")
@DisplayName("RunCLI --expression-allow-list")
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class RunCLIExpressionAllowListTest {

    /** A workflow whose to-state is an expression that calls {@code matches}, a method the allow-list leaves out. */
    private static final String REGEX_WORKFLOW = String.join("\n",
            "name: regex",
            "steps:",
            "  - states: [\"0\", \"jexl:'abc'.matches('a.*') ? 'end' : '0'\"]",
            "    actions: []",
            "");

    private int run(Path dir, String... extra) throws Exception {
        Files.writeString(dir.resolve("regex.yaml"), REGEX_WORKFLOW);
        String[] base = {"-d", dir.toString(), "-w", "regex.yaml", "-m", "5"};
        String[] args = new String[base.length + extra.length];
        System.arraycopy(base, 0, args, 0, base.length);
        System.arraycopy(extra, 0, args, base.length, extra.length);
        CommandLine cl = new DefaultParser().parse(RunCLI.options(), args);
        return new RunCLI().run(cl, new IIActorSystem("run-cli-expression-allow-list"));
    }

    @Test
    @DisplayName("the option is a flag with no value")
    void option_isAFlag() {
        assertTrue(RunCLI.options().hasOption("expression-allow-list"));
        assertFalse(RunCLI.options().getOption("expression-allow-list").hasArg());
    }

    @Test
    @DisplayName("without the option an expression may call matches")
    void withoutTheOption_matchesAnswers(@TempDir Path dir) throws Exception {
        assertEquals(0, run(dir));
    }

    @Test
    @DisplayName("with the option an expression that calls matches is refused, so the run does not reach end")
    void withTheOption_matchesIsRefused(@TempDir Path dir) throws Exception {
        assertEquals(1, run(dir, "--expression-allow-list"));
    }
}
