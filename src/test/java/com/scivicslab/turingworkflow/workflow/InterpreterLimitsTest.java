/*
 * Copyright 2025 SCIVICS Lab
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.scivicslab.turingworkflow.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.scivicslab.pojoactor.action.ActionResult;

/**
 * The interpreter's security limits: each one refuses with a false result what used to run on.
 *
 * <ol>
 *   <li>Sub-workflow depth: {@code this.call} and {@code runWorkflow} stop at a nesting limit.</li>
 *   <li>{@code putJson}: an index large enough to fill memory is refused.</li>
 *   <li>A {@code jexl:} failure names the class and position, not the text that was parsed.</li>
 *   <li>Path loads ({@code readYaml}, {@code runWorkflow}) read only under the base directory.</li>
 *   <li>The loader's INFO log carries no credentials from its arguments.</li>
 * </ol>
 */
@DisplayName("Interpreter security limits")
class InterpreterLimitsTest {

    private static final String MAX_CALL_DEPTH_PROPERTY = "turingworkflow.maxCallDepth";

    private IIActorSystem system;
    private Interpreter interpreter;
    private InterpreterIIAR self;
    private final List<String> recorded = new CopyOnWriteArrayList<>();
    private final List<LogRecord> logged = new CopyOnWriteArrayList<>();
    private final Handler capture = new Handler() {
        @Override public void publish(LogRecord record) { logged.add(record); }
        @Override public void flush() {}
        @Override public void close() {}
    };

    @BeforeEach
    void setUp() {
        system = new IIActorSystem("interpreter-limits-test", 2);
        interpreter = new Interpreter.Builder().loggerName("interpreter-limits-test").team(system).build();
        self = new InterpreterIIAR("main", interpreter, system);
        interpreter.setSelfActorRef(self);
        system.addIIActor(self);
        system.addIIActor(new IIActorRef<Object>("recorder", new Object(), system) {
            @Override
            public ActionResult callByActionName(String actionName, String args) {
                recorded.add(args);
                return new ActionResult(true, "recorded");
            }
        });
        Logger.getLogger("").addHandler(capture);
    }

    @AfterEach
    void tearDown() {
        Logger.getLogger("").removeHandler(capture);
        System.clearProperty(MAX_CALL_DEPTH_PROPERTY);
        system.terminateIIActors();
        system.terminate();
    }

    private ActionResult runText(String yaml) throws Exception {
        interpreter.readYaml(new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)));
        return CompletableFuture.supplyAsync(() -> interpreter.runUntilEnd(20)).get(30, TimeUnit.SECONDS);
    }

    /**
     * A workflow whose first step runs {@code actor.method} with {@code arguments} (flow YAML); when
     * that step is false, the fallback records why.
     */
    private static String withFallback(String actor, String method, String arguments) {
        return String.join("\n",
                "name: limits",
                "steps:",
                "  - states: [\"0\", \"end\"]",
                "    actions:",
                "      - actor: " + actor,
                "        method: " + method,
                "        arguments: " + arguments,
                "  - states: [\"0\", \"recorded\"]",
                "    actions:",
                "      - actor: recorder",
                "        method: record",
                "        arguments: [\"jexl: result\"]",
                "  - states: [\"recorded\", \"end\"]",
                "    actions:",
                "      - actor: this",
                "        method: doNothing",
                "");
    }

    private String onlyReason() {
        assertEquals(1, recorded.size(), "the first step should be false and its fallback should record why: " + recorded);
        return recorded.get(0);
    }

    // ---- 1. sub-workflow depth -------------------------------------------------------------------

    @Nested
    @DisplayName("1. sub-workflow depth")
    class Depth {

        @Test
        @DisplayName("this.call one level deeper than the limit is false, and says the limit was reached")
        void call_deeperThanLimit_isFalse() throws Exception {
            System.setProperty(MAX_CALL_DEPTH_PROPERTY, "1");

            ActionResult result = runText(withFallback("this", "call", "[\"t274-level1.yaml\"]"));

            assertTrue(result.isSuccess(), result.getResult());
            String reason = onlyReason();
            assertTrue(reason.contains("sub-workflow depth limit"), reason);
        }

        @Test
        @DisplayName("this.call within the limit runs")
        void call_withinLimit_runs() throws Exception {
            System.setProperty(MAX_CALL_DEPTH_PROPERTY, "2");

            ActionResult result = runText(withFallback("this", "call", "[\"t274-level1.yaml\"]"));

            assertTrue(result.isSuccess(), result.getResult());
            assertTrue(recorded.isEmpty(), "both levels should run: " + recorded);
        }

        @Test
        @DisplayName("with the default limit, a workflow calling itself stops at the limit, not at the end of the stack")
        void recursiveCall_defaultLimit_stopsAtLimit() throws Exception {
            String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
            Process p = new ProcessBuilder(java, "-cp", System.getProperty("java.class.path"),
                    RecursiveCallMain.class.getName(), "capped")
                    .redirectErrorStream(true)
                    .start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!p.waitFor(120, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                fail("the recursive run did not end in 120 s");
            }
            String tail = out.length() > 4000 ? out.substring(out.length() - 4000) : out;
            assertTrue(out.contains("RESULT success=false"), tail);
            assertTrue(out.contains("sub-workflow depth limit"), tail);
            assertFalse(out.contains("StackOverflowError"), "the limit should stop it before the stack runs out:\n" + tail);
        }
    }

    // ---- 2. putJson index -------------------------------------------------------------------------

    @Nested
    @DisplayName("2. putJson index")
    class PutJsonIndex {

        @Test
        @DisplayName("an index above the limit is false and nothing is stored")
        void putJson_indexAboveLimit_isFalse() {
            ActionResult result = self.callByActionName("putJson", "{\"path\": \"items[100001]\", \"value\": 1}");

            assertFalse(result.isSuccess(), result.getResult());
            assertTrue(result.getResult().contains("index"), result.getResult());
            assertEquals("false", self.callByActionName("hasJson", "items").getResult(), "nothing should be stored");
        }

        @Test
        @DisplayName("an index above the limit inside a path is false too")
        void putJson_innerIndexAboveLimit_isFalse() {
            ActionResult result = self.callByActionName("putJson", "{\"path\": \"rows[100001].name\", \"value\": \"x\"}");

            assertFalse(result.isSuccess(), result.getResult());
        }

        @Test
        @DisplayName("an index at the limit is stored")
        void putJson_indexAtLimit_isStored() {
            ActionResult result = self.callByActionName("putJson", "{\"path\": \"items[100000]\", \"value\": 1}");

            assertTrue(result.isSuccess(), result.getResult());
        }
    }

    // ---- 3. jexl failure text ---------------------------------------------------------------------

    @Nested
    @DisplayName("3. jexl: failure text")
    class JexlFailureText {

        @Test
        @DisplayName("a failure inside an expression names the class and position, not the text it failed on")
        void expressionFails_reasonAndLogs_doNotEchoContent() throws Exception {
            // A value from the state, as a file's contents would be, used where the expression fails
            // on it: as a regular expression, which does not compile and whose message quotes it.
            self.callByActionName("putJson", "{\"path\": \"secret\", \"value\": \"TOPSECRET line (\"}");

            ActionResult result = runText(withFallback("recorder", "record",
                    "[\"jexl: 'x'.matches(state.getString('secret'))\"]"));

            assertTrue(result.isSuccess(), result.getResult());
            String reason = onlyReason();
            assertFalse(reason.contains("TOPSECRET"), reason);
            assertTrue(reason.contains("PatternSyntaxException"), reason);
            assertTrue(reason.contains("at index"), reason);
            for (LogRecord r : logged) {
                String text = String.valueOf(r.getMessage()) + " " + (r.getThrown() == null ? "" : r.getThrown().getMessage());
                assertFalse(text.contains("TOPSECRET"), "logged: " + text);
            }
        }
    }

    // ---- 4. base directory ------------------------------------------------------------------------

    private static final String WORKFLOW = """
            name: anywhere
            steps:
              - states: ["0", "end"]
                actions:
                  - actor: this
                    method: doNothing
            """;

    @Nested
    @DisplayName("4. path loads read only under the base directory")
    class BaseDir {

        @TempDir
        Path dir;
        Path base;
        Path outside;

        @BeforeEach
        void files() throws Exception {
            base = Files.createDirectories(dir.resolve("base"));
            outside = Files.createDirectories(dir.resolve("outside")).resolve("secret.yaml");
            Files.writeString(outside, WORKFLOW);
            Files.writeString(base.resolve("ok.yaml"), WORKFLOW);
            interpreter.setWorkflowBaseDir(base.toString());
        }

        private void assertRefused(ActionResult result) {
            assertFalse(result.isSuccess(), result.getResult());
            assertTrue(result.getResult().contains("outside the base directory"), result.getResult());
        }

        @Test
        @DisplayName("readYaml of an absolute path outside the base is false")
        void readYaml_absoluteOutside_isRefused() {
            assertRefused(self.callByActionName("readYaml", outside.toString()));
        }

        @Test
        @DisplayName("readYaml of a path that climbs out with .. is false")
        void readYaml_dotDotOutside_isRefused() {
            assertRefused(self.callByActionName("readYaml", "../outside/secret.yaml"));
        }

        @Test
        @DisplayName("readYaml of a symlink inside the base that points outside is false")
        void readYaml_symlinkOutside_isRefused() throws Exception {
            Files.createSymbolicLink(base.resolve("link.yaml"), outside);
            assertRefused(self.callByActionName("readYaml", "link.yaml"));
        }

        @Test
        @DisplayName("readYaml of a file under the base, named relative to the base, loads")
        void readYaml_insideBase_loads() {
            ActionResult result = self.callByActionName("readYaml", "ok.yaml");
            assertTrue(result.isSuccess(), result.getResult());
        }

        @Test
        @DisplayName("runWorkflow of an absolute path outside the base is false")
        void runWorkflow_absoluteOutside_isRefused() {
            assertRefused(interpreter.runWorkflow(outside.toString()));
        }

        @Test
        @DisplayName("runWorkflow of a path that climbs out with .. is false")
        void runWorkflow_dotDotOutside_isRefused() {
            assertRefused(interpreter.runWorkflow("../outside/secret.yaml"));
        }
    }

    // ---- 5. loader logs ---------------------------------------------------------------------------

    @Nested
    @DisplayName("5. the loader logs no credentials")
    class LoaderLogs {

        @Test
        @DisplayName("an argument carrying a password reaches no log record, at INFO or at FINE")
        void loadJar_urlWithPassword_isNotLogged() {
            Logger loaderLogger = Logger.getLogger(DynamicActorLoaderActor.class.getName());
            Level before = loaderLogger.getLevel();
            loaderLogger.setLevel(Level.FINE);
            try {
                new DynamicActorLoaderActor(system).callByActionName(
                        "loadJar", "https://deploy:s3cret@repo.example.invalid/plugin.jar?token=t0ken");
            } finally {
                loaderLogger.setLevel(before);
            }
            assertFalse(logged.isEmpty(), "the loader should log the action");
            for (LogRecord r : logged) {
                String text = String.valueOf(r.getMessage()) + " " + java.util.Arrays.toString(r.getParameters());
                assertFalse(text.contains("s3cret"), "logged: " + text);
                assertFalse(text.contains("t0ken"), "logged: " + text);
            }
        }

        @Test
        @DisplayName("the loader logs its actions' arguments at FINE, not at INFO")
        void loaderAction_argumentsLine_isNotInfo() {
            new DynamicActorLoaderActor(system).callByActionName("listLoadedJars", "marker-argument");
            for (LogRecord r : logged) {
                if (String.valueOf(r.getMessage()).contains("marker-argument")) {
                    assertTrue(r.getLevel().intValue() < Level.INFO.intValue(), "logged at " + r.getLevel() + ": " + r.getMessage());
                }
            }
        }
    }
}
