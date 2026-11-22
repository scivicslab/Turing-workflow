package com.scivicslab.turingworkflow.workflow;

import static org.junit.jupiter.api.Assertions.assertTrue;

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
 * A step that names the loader still loads a jar and initialises a class from it.
 *
 * <p>The expression allow-list takes the loader out of an expression's reach; it does not take
 * it away from the operator. The counterpart of
 * {@code ExpressionCodeExecutionTest.argument_reachesRegisteredLoader_loadsNothing}: the same jar,
 * loaded by a step instead of by an expression.</p>
 */
@Tag("DynamicActorLoader_StepOnly_260509_oo01")
@DisplayName("loader: an explicit step loads a jar")
class LoaderStepTest {

    private ExpressionRuns runs;

    @BeforeEach
    void setUp() {
        runs = new ExpressionRuns("loader-step");
    }

    @AfterEach
    void tearDown() {
        runs.close();
    }

    @Test
    @DisplayName("loadJar and createChild, named in steps, load the jar and initialise its class")
    void explicitSteps_loadJarAndInitialiseClass(@TempDir Path dir) throws Exception {
        Path marker = dir.resolve("ran.txt");
        Path jar = dir.resolve("operator.jar");
        Files.write(jar, ExpressionRuns.payloadJar(dir.resolve("build"), "Operator", marker));
        DynamicActorLoaderIIAR loader = new DynamicActorLoaderIIAR("loader", runs.system);
        runs.system.addIIActor(loader);
        String yaml = "name: operator\nsteps:\n"
                + "  - states: [\"0\", \"1\"]\n    actions:\n      - actor: loader\n        method: loadJar\n"
                + "        arguments: [\"" + jar + "\"]\n"
                + "  - states: [\"1\", \"end\"]\n    actions:\n      - actor: loader\n        method: createChild\n"
                + "        arguments: [\"ROOT\", \"op\", \"payload.Operator\"]\n"
                + "  - states: [\"1\", \"end\"]\n    actions: []\n";

        runs.run(yaml, 5);

        ActionResult loaded = loader.callByActionName("listLoadedJars", "[]");
        assertTrue(loaded.getResult().contains(jar.toString()), "the step did not load the jar: " + loaded.getResult());
        assertTrue(Files.exists(marker), "the class from the jar was not initialised");
    }
}
