package com.scivicslab.turingworkflow.workflow;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;

import com.scivicslab.pojoactor.action.ActionResult;

/**
 * What the expression tests share: a system with an interpreter running as {@code main}, an actor
 * {@code recorder} that keeps every argument it is given, and a jar whose class leaves a marker
 * file when it is initialised.
 */
final class ExpressionRuns {

    final IIActorSystem system;
    final Interpreter interpreter;
    final InterpreterIIAR self;
    final List<String> recorded = new CopyOnWriteArrayList<>();

    ExpressionRuns(String name) {
        system = new IIActorSystem(name);
        interpreter = new Interpreter.Builder().loggerName(name).team(system).build();
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
    }

    /** Reads {@code yaml} and runs it to its end, at most {@code steps} transitions. */
    ActionResult run(String yaml, int steps) {
        interpreter.readYaml(new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)));
        return interpreter.runUntilEnd(steps);
    }

    /**
     * A workflow of one step whose single action hands {@code expression} to {@code recorder} as its
     * argument.
     */
    static String recording(String expression) {
        return "name: recording\nsteps:\n"
                + "  - states: [\"0\", \"end\"]\n    actions:\n      - actor: recorder\n        method: record\n"
                + "        arguments: [" + yamlQuoted("jexl: " + expression) + "]\n";
    }

    /** A workflow of one step whose to-state is {@code expression}. */
    static String toState(String expression) {
        return "name: to-state\nsteps:\n"
                + "  - states: [\"0\", " + yamlQuoted("jexl:" + expression) + "]\n    actions: []\n";
    }

    /** A workflow whose first step has {@code expression} as its from-state pattern. */
    static String fromState(String expression) {
        return "name: from-state\nsteps:\n"
                + "  - states: [" + yamlQuoted("jexl:" + expression) + ", \"never\"]\n    actions: []\n"
                + "  - states: [\"0\", \"end\"]\n    actions: []\n";
    }

    /** {@code text} as a single-quoted YAML scalar. */
    static String yamlQuoted(String text) {
        return "'" + text.replace("'", "''") + "'";
    }

    /** {@code bytes} as the body of a JEXL double-quoted string, one escape per byte. */
    static String jexlByteString(byte[] bytes) {
        StringBuilder out = new StringBuilder();
        for (byte b : bytes) {
            out.append(String.format("\\u%04x", b & 0xff));
        }
        return out.toString();
    }

    /**
     * Compiles a class {@code payload.<simpleName>} whose static initialiser writes {@code marker},
     * and answers the bytes of a jar holding it.
     */
    static byte[] payloadJar(Path workDir, String simpleName, Path marker) throws IOException {
        Path source = workDir.resolve("payload/" + simpleName + ".java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "package payload; public class " + simpleName + " { static { try {"
                + " java.nio.file.Files.writeString(java.nio.file.Path.of(\""
                + marker.toString().replace("\\", "\\\\") + "\"), \"ran\"); } catch (Exception e) { } } }");
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        int rc = javac.run(null, null, null, "-d", workDir.toString(), source.toString());
        if (rc != 0) {
            throw new IllegalStateException("javac failed with " + rc);
        }
        ByteArrayOutputStream jar = new ByteArrayOutputStream();
        try (JarOutputStream out = new JarOutputStream(jar)) {
            out.putNextEntry(new JarEntry("payload/" + simpleName + ".class"));
            out.write(Files.readAllBytes(workDir.resolve("payload/" + simpleName + ".class")));
            out.closeEntry();
        }
        return jar.toByteArray();
    }

    void close() {
        system.terminateIIActors();
    }
}
