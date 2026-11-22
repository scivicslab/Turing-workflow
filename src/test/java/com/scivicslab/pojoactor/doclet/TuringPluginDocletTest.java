package com.scivicslab.pojoactor.doclet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import javax.tools.DocumentationTool;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * The doclet run by the javadoc tool on one source file: what of the Javadoc of an {@code @Action}
 * method reaches its class's manifest file, and in what form.
 */
@Tag("ActionCatalogWithJavadoc_260930_oo01")
@DisplayName("TuringPluginDoclet — the Javadoc of an action, as JSON")
class TuringPluginDocletTest {

    private static final String SOURCE = """
            package sample;

            import com.scivicslab.pojoactor.action.Action;
            import com.scivicslab.pojoactor.action.ActionResult;

            public class Greeter {

                /**
                 * Greets whoever the text names; the message is the greeting.
                 *
                 * <p>The text is used as is. A {@code blank} text greets nobody,
                 * see {@link #wave}.</p>
                 *
                 * <pre>{@code
                 * - actor: greeter
                 *   method: greet
                 *   arguments: "Ada <ada@example.org>"
                 * }</pre>
                 *
                 * @param args the name to greet, as plain text
                 */
                @Action("greet")
                public ActionResult greet(String args) { return new ActionResult(true, "hello " + args); }

                /** Waves without a word. */
                @Action("wave")
                public ActionResult wave(String args) { return new ActionResult(true, "o/"); }
            }
            """;

    @Test
    void firstSentenceDetailsExampleAndTheStringParameter(@TempDir Path dir) throws Exception {
        Path src = dir.resolve("sample/Greeter.java");
        Files.createDirectories(src.getParent());
        Files.writeString(src, SOURCE);
        Path classes = dir.resolve("classes");

        DocumentationTool javadoc = ToolProvider.getSystemDocumentationTool();
        StringWriter log = new StringWriter();
        try (StandardJavaFileManager files = javadoc.getStandardFileManager(null, null, null)) {
            Iterable<? extends JavaFileObject> units = files.getJavaFileObjects(src);
            List<String> options = List.of(
                    "-classpath", System.getProperty("java.class.path"),
                    "-turingClassesDir", classes.toString());
            Boolean ok = javadoc.getTask(log, files, null, TuringPluginDoclet.class, options, units).call();
            assertTrue(ok, log.toString());
        }

        JsonNode root = new ObjectMapper().readTree(
                classes.resolve("META-INF/turing-actions/sample.Greeter.json").toFile());
        JsonNode actor = root.get("actors").get(0);
        assertEquals("sample.Greeter", actor.get("class").asText());
        JsonNode greet = actor.get("actions").get(0);
        assertEquals("greet", greet.get("name").asText());
        assertEquals("string", greet.get("argsFormat").asText());
        assertEquals("Greets whoever the text names; the message is the greeting.", greet.get("description").asText());
        assertEquals("The text is used as is. A blank text greets nobody, see #wave.", greet.get("details").asText(),
                "inline tags become their text, the source's line wrap is not a break, the <pre> block is not part of the details");
        assertEquals("- actor: greeter\n  method: greet\n  arguments: \"Ada <ada@example.org>\"", greet.get("example").asText(),
                "the <pre>{@code ...}</pre> block, unindented, with its braces and angle brackets intact");
        assertEquals("args", greet.get("params").get(0).get("name").asText());
        assertEquals("the name to greet, as plain text", greet.get("params").get(0).get("description").asText());

        JsonNode wave = actor.get("actions").get(1);
        assertEquals("Waves without a word.", wave.get("description").asText());
        assertFalse(wave.has("details"));
        assertFalse(wave.has("example"));
        assertFalse(wave.has("params"));
    }

    /**
     * One file per class, named after the class's binary name, which is what {@code Class.getName()}
     * answers and so what {@code ActionManifest} looks up; nothing under {@code META-INF/services/},
     * which may name only provider classes.
     */
    @Test
    void oneFilePerClass_namedByBinaryName_nothingUnderServices(@TempDir Path dir) throws Exception {
        Path src = dir.resolve("sample/Greeter.java");
        Files.createDirectories(src.getParent());
        Files.writeString(src, SOURCE.replace("    /** Waves without a word. */", """
                    /** An actor nested in another. */
                    public static class Inner {
                        /** Says hi from inside. */
                        @Action("hi")
                        public ActionResult hi(String args) { return new ActionResult(true, "hi"); }
                    }

                    /** Waves without a word. */"""));
        Path classes = dir.resolve("classes");

        DocumentationTool javadoc = ToolProvider.getSystemDocumentationTool();
        StringWriter log = new StringWriter();
        try (StandardJavaFileManager files = javadoc.getStandardFileManager(null, null, null)) {
            List<String> options = List.of(
                    "-classpath", System.getProperty("java.class.path"),
                    "-turingClassesDir", classes.toString());
            Boolean ok = javadoc.getTask(log, files, null, TuringPluginDoclet.class, options,
                    files.getJavaFileObjects(src)).call();
            assertTrue(ok, log.toString());
        }

        Path dirOf = classes.resolve("META-INF/turing-actions");
        try (var listed = Files.list(dirOf)) {
            assertEquals(List.of("sample.Greeter$Inner.json", "sample.Greeter.json"),
                    listed.map(p -> p.getFileName().toString()).sorted().toList());
        }
        JsonNode inner = new ObjectMapper().readTree(dirOf.resolve("sample.Greeter$Inner.json").toFile());
        assertEquals("sample.Greeter$Inner", inner.get("actors").get(0).get("class").asText());
        assertEquals("Says hi from inside.", inner.get("actors").get(0).get("actions").get(0).get("description").asText());
        assertFalse(Files.exists(classes.resolve("META-INF/services")), "nothing is written under META-INF/services");
        assertFalse(Files.exists(classes.resolve("META-INF/turing-plugin.json")), "no shared file");
    }
}
