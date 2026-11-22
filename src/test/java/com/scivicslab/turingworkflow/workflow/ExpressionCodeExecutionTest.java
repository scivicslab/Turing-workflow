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

import com.scivicslab.pojoactor.action.ActionResult;

/**
 * An expression in a workflow YAML cannot run code of its own choosing.
 *
 * <p>Before the allow-list, one argument expression could write a jar with
 * {@code java.io.FileOutputStream}, build a {@code DynamicActorLoaderActor} over an
 * {@code IIActorSystem} of its own, load the jar and initialise a class from it. The host did not
 * need to register a loader at all (chat-85, C1).</p>
 */
@Tag("ExpressionReach_CodeExecution_261122_oo01")
@DisplayName("An expression cannot write, load and run a jar")
class ExpressionCodeExecutionTest {

    private static final String W = "com.scivicslab.turingworkflow.workflow.";

    private ExpressionRuns runs;

    @BeforeEach
    void setUp() {
        runs = new ExpressionRuns("code-execution");
    }

    @AfterEach
    void tearDown() {
        runs.close();
    }

    @Test
    @DisplayName("one argument cannot write a jar, build its own loader and run a class from it")
    void argument_writesJarAndBuildsOwnLoader_runsNothing(@TempDir Path dir) throws Exception {
        Path marker = dir.resolve("ran.txt");
        Path jar = dir.resolve("dropped.jar");
        byte[] bytes = ExpressionRuns.payloadJar(dir.resolve("build"), "Dropped", marker);
        String expression = "[ new('java.io.FileOutputStream', '" + jar + "').write(\""
                + ExpressionRuns.jexlByteString(bytes) + "\".getBytes('ISO-8859-1')),"
                + " (l = new('" + W + "DynamicActorLoaderActor', new('" + W + "IIActorSystem', 'own')))"
                + ".callByActionName('loadJar', '[\"" + jar + "\"]').getResult(),"
                + " l.callByActionName('createChild', '[\"ROOT\",\"p\",\"payload.Dropped\"]').getResult() ]";

        runs.run(ExpressionRuns.recording(expression), 5);

        assertFalse(runs.system.hasIIActor("loader"), "the host registered no loader");
        assertFalse(Files.exists(jar), "the expression wrote a jar");
        assertFalse(Files.exists(marker), "a class from the jar was initialised");
    }

    @Test
    @DisplayName("an expression cannot reach a loader the host did register")
    void argument_reachesRegisteredLoader_loadsNothing(@TempDir Path dir) throws Exception {
        Path marker = dir.resolve("ran.txt");
        Path jar = dir.resolve("placed.jar");
        Files.write(jar, ExpressionRuns.payloadJar(dir.resolve("build"), "Placed", marker));
        DynamicActorLoaderIIAR loader = new DynamicActorLoaderIIAR("loader", runs.system);
        runs.system.addIIActor(loader);
        String expression = "[ actors.get('loader').callByActionName('loadJar', '[\"" + jar + "\"]').getResult(),"
                + " actors.get('loader').callByActionName('createChild', '[\"ROOT\",\"p\",\"payload.Placed\"]').getResult() ]";

        runs.run(ExpressionRuns.recording(expression), 5);

        ActionResult loaded = loader.callByActionName("listLoadedJars", "[]");
        assertFalse(loaded.getResult().contains(jar.toString()), "the expression loaded " + loaded.getResult());
        assertFalse(Files.exists(marker), "a class from the jar was initialised");
    }

    @Test
    @DisplayName("a from-state pattern cannot write a jar and run a class from it")
    void fromState_writesJarAndBuildsOwnLoader_runsNothing(@TempDir Path dir) throws Exception {
        Path marker = dir.resolve("ran.txt");
        Path jar = dir.resolve("dropped.jar");
        byte[] bytes = ExpressionRuns.payloadJar(dir.resolve("build"), "FromState", marker);
        String expression = "new('java.io.FileOutputStream', '" + jar + "').write(\""
                + ExpressionRuns.jexlByteString(bytes) + "\".getBytes('ISO-8859-1')) == null"
                + " && new('" + W + "DynamicActorLoaderActor', new('" + W + "IIActorSystem', 'own'))"
                + ".callByActionName('loadJar', '[\"" + jar + "\"]') != null && false";

        runs.run(ExpressionRuns.fromState(expression), 5);

        assertFalse(Files.exists(jar), "the pattern wrote a jar");
        assertFalse(Files.exists(marker), "a class from the jar was initialised");
    }
}
