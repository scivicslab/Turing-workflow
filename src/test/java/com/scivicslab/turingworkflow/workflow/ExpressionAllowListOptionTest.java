package com.scivicslab.turingworkflow.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import com.scivicslab.pojoactor.action.ActionResult;

/**
 * The expression allow-list is an option, and it is off by default: an expression in a workflow YAML
 * has the reach of Java (it may create objects, read and write files, run a class from a jar it
 * wrote), and only a host that turns the allow-list on limits it.
 *
 * <p>Off, the tests of {@code ExpressionReach_261122_oo01} (the expression cannot write a file, load a
 * jar, read a file) do not hold; on, they do, and so do the two tests here that name the methods the
 * allow-list leaves out, {@code matches} and {@code replaceAll}: a regular expression cannot be
 * stopped part-way, so a text that makes it take very long would hold the run for as long.</p>
 */
@Tag("ExpressionAllowListOption_261123_oo01")
@DisplayName("The expression allow-list is an option, off by default")
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class ExpressionAllowListOptionTest {

    private static final String SECRET = "secret-expression-option";
    private static final String W = "com.scivicslab.turingworkflow.workflow.";

    private static String write(Path file) {
        return "new('java.io.FileOutputStream', '" + file + "').write('written'.getBytes())";
    }

    private static String read(Path file) {
        return "new('java.lang.String', new('java.io.FileInputStream', '" + file + "').readAllBytes(), 'UTF-8')";
    }

    // ------------------------------------------------------------------ the default

    @Test
    @DisplayName("a system and an interpreter start with the allow-list off")
    void default_isOff() {
        ExpressionRuns runs = new ExpressionRuns("option-default", false);
        try {
            assertFalse(runs.system.isExpressionAllowListEnabled());
            assertFalse(runs.interpreter.isExpressionAllowListEnabled());
        } finally {
            runs.close();
        }
    }

    @Test
    @DisplayName("the interpreter's switch is the system's, so a second interpreter over it follows")
    void interpreterSwitch_isTheSystems() {
        ExpressionRuns runs = new ExpressionRuns("option-switch", false);
        try {
            Interpreter other = new Interpreter.Builder().loggerName("other").team(runs.system).build();

            runs.interpreter.setExpressionAllowListEnabled(true);

            assertTrue(runs.system.isExpressionAllowListEnabled());
            assertTrue(other.isExpressionAllowListEnabled());
            runs.interpreter.setExpressionAllowListEnabled(false);
            assertFalse(other.isExpressionAllowListEnabled());
        } finally {
            runs.close();
        }
    }

    @Test
    @DisplayName("an interpreter with no actor system cannot hold the setting and says so")
    void interpreterWithoutSystem_refusesTheSwitch() {
        Interpreter alone = new Interpreter();

        assertFalse(alone.isExpressionAllowListEnabled());
        assertThrows(IllegalStateException.class, () -> alone.setExpressionAllowListEnabled(true));
    }

    // ------------------------------------------------------------------ off: the reach of Java

    @Test
    @DisplayName("off: an argument writes a file and reads it back")
    void off_argumentWritesAndReadsFile(@TempDir Path dir) throws Exception {
        ExpressionRuns runs = new ExpressionRuns("option-off-file", false);
        try {
            Path file = dir.resolve("written.txt");
            runs.run(ExpressionRuns.recording(write(file)), 5);
            assertTrue(Files.exists(file), "the expression wrote nothing");
            assertEquals("written", Files.readString(file));

            Path secret = Files.writeString(dir.resolve("secret.txt"), SECRET);
            runs.run(ExpressionRuns.recording(read(secret)), 5);
            assertTrue(String.join("\n", runs.recorded).contains(SECRET), "the action received " + runs.recorded);
        } finally {
            runs.close();
        }
    }

    @Test
    @DisplayName("off: a to-state reads a file and a from-state writes one")
    void off_statePatternsReadAndWriteFiles(@TempDir Path dir) throws Exception {
        Path secret = Files.writeString(dir.resolve("secret.txt"), SECRET);
        ExpressionRuns toState = new ExpressionRuns("option-off-to-state", false);
        try {
            toState.run(ExpressionRuns.toState(read(secret)), 5);
            assertTrue(String.valueOf(toState.interpreter.getCurrentState()).contains(SECRET),
                    "the workflow is in " + toState.interpreter.getCurrentState());
        } finally {
            toState.close();
        }

        Path file = dir.resolve("written.txt");
        ExpressionRuns fromState = new ExpressionRuns("option-off-from-state", false);
        try {
            fromState.run(ExpressionRuns.fromState(write(file) + " == null && false"), 5);
            assertTrue(Files.exists(file), "the pattern wrote nothing");
        } finally {
            fromState.close();
        }
    }

    @Test
    @DisplayName("off: an argument writes a jar, builds its own loader and runs a class from it")
    void off_argumentBuildsOwnLoaderAndRunsAClass(@TempDir Path dir) throws Exception {
        Path marker = dir.resolve("ran.txt");
        Path jar = dir.resolve("dropped.jar");
        byte[] bytes = ExpressionRuns.payloadJar(dir.resolve("build"), "Dropped", marker);
        String expression = "[ new('java.io.FileOutputStream', '" + jar + "').write(\""
                + ExpressionRuns.jexlByteString(bytes) + "\".getBytes('ISO-8859-1')),"
                + " (l = new('" + W + "DynamicActorLoaderActor', new('" + W + "IIActorSystem', 'own')))"
                + ".callByActionName('loadJar', '[\"" + jar + "\"]').getResult(),"
                + " l.callByActionName('createChild', '[\"ROOT\",\"p\",\"payload.Dropped\"]').getResult() ]";
        ExpressionRuns runs = new ExpressionRuns("option-off-loader", false);
        try {
            runs.run(ExpressionRuns.recording(expression), 5);

            assertTrue(Files.exists(jar), "the expression wrote no jar");
            assertTrue(Files.exists(marker), "no class from the jar was initialised; the action received " + runs.recorded);
        } finally {
            runs.close();
        }
    }

    @Test
    @DisplayName("off: matches and replaceAll answer")
    void off_matchesAndReplaceAllAnswer() {
        ExpressionRuns runs = new ExpressionRuns("option-off-regex", false);
        try {
            runs.run(ExpressionRuns.recording("'abc'.matches('a.*')"), 5);
            runs.run(ExpressionRuns.recording("'a-b'.replaceAll('-', '+')"), 5);

            assertEquals(2, runs.recorded.size(), "recorded " + runs.recorded);
            assertTrue(runs.recorded.get(0).contains("true"), runs.recorded.get(0));
            assertTrue(runs.recorded.get(1).contains("a+b"), runs.recorded.get(1));
        } finally {
            runs.close();
        }
    }

    // ------------------------------------------------------------------ on

    @Test
    @DisplayName("on: matches is refused in an argument")
    void on_matchesIsRefused() {
        ExpressionRuns runs = new ExpressionRuns("option-on-matches", true);
        try {
            runs.run(ExpressionRuns.recording("'abc'.matches('a.*')"), 5);

            assertTrue(runs.recorded.isEmpty(), "the action received " + runs.recorded);
        } finally {
            runs.close();
        }
    }

    @Test
    @DisplayName("on: replaceAll is refused in an argument")
    void on_replaceAllIsRefused() {
        ExpressionRuns runs = new ExpressionRuns("option-on-replace-all", true);
        try {
            runs.run(ExpressionRuns.recording("'a-b'.replaceAll('-', '+')"), 5);

            assertTrue(runs.recorded.isEmpty(), "the action received " + runs.recorded);
        } finally {
            runs.close();
        }
    }

    @Test
    @DisplayName("on: the text methods that stay on the list still answer")
    void on_theOtherTextMethodsStillAnswer() {
        ExpressionRuns runs = new ExpressionRuns("option-on-text", true);
        try {
            runs.run(ExpressionRuns.recording("'abc'.startsWith('a') && 'abc'.contains('c')"), 5);

            assertEquals(1, runs.recorded.size(), "recorded " + runs.recorded);
            assertTrue(runs.recorded.get(0).contains("true"), runs.recorded.get(0));
        } finally {
            runs.close();
        }
    }

    // ------------------------------------------------------------------ the switch is what changes the outcome

    @Test
    @DisplayName("the same argument writes a file with the allow-list off and writes nothing with it on")
    void sameArgument_theSwitchDecides(@TempDir Path dir) {
        Path off = dir.resolve("off.txt");
        Path on = dir.resolve("on.txt");
        ExpressionRuns withoutList = new ExpressionRuns("option-pair-off", false);
        ExpressionRuns withList = new ExpressionRuns("option-pair-on", false);
        try {
            withList.interpreter.setExpressionAllowListEnabled(true);

            withoutList.run(ExpressionRuns.recording(write(off)), 5);
            withList.run(ExpressionRuns.recording(write(on)), 5);

            assertTrue(Files.exists(off), "off: the expression wrote nothing");
            assertFalse(Files.exists(on), "on: the expression wrote " + on);
        } finally {
            withoutList.close();
            withList.close();
        }
    }

    // ------------------------------------------------------------------ a sub-workflow follows the system that started it

    private static final String REGEX_SUB_WORKFLOW = """
            name: regex
            steps:
              - states: ["0", "end"]
                actions:
                  - actor: this
                    method: putJson
                    arguments: {path: output, value: "jexl: 'abc'.matches('a.*')"}
            """;

    private static ActionResult mapOneItem(IIActorSystem system, Path subWorkflow) {
        String args = new JSONObject()
                .put("subWorkflow", subWorkflow.toAbsolutePath().toString())
                .put("items", new JSONArray().put("x"))
                .toString();
        return system.getIIActor("parallel-map").callByActionName("run", args);
    }

    @Test
    @DisplayName("a parallel-map sub-workflow follows the setting of the system that started it")
    void parallelMapSubWorkflow_followsTheParentSystem(@TempDir Path dir) throws Exception {
        Path sub = Files.writeString(dir.resolve("regex.yaml"), REGEX_SUB_WORKFLOW);

        IIActorSystem off = new IIActorSystem("option-pm-off");
        IIActorSystem on = new IIActorSystem("option-pm-on");
        try {
            on.setExpressionAllowListEnabled(true);

            ActionResult withoutList = mapOneItem(off, sub);
            ActionResult withList = mapOneItem(on, sub);

            assertTrue(withoutList.isSuccess(), withoutList.getResult());
            assertEquals("[true]", withoutList.getResult());
            assertFalse(withList.isSuccess(), "the sub-workflow evaluated matches under the allow-list: " + withList.getResult());
        } finally {
            off.terminateIIActors();
            on.terminateIIActors();
        }
    }
}
