/*
 * Copyright 2025 devteam@scivicslab.com
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

package com.scivicslab.pojoactor.doclet;

import com.sun.source.doctree.DocCommentTree;
import com.sun.source.doctree.DocTree;
import com.sun.source.doctree.EndElementTree;
import com.sun.source.doctree.EntityTree;
import com.sun.source.doctree.LinkTree;
import com.sun.source.doctree.LiteralTree;
import com.sun.source.doctree.ParamTree;
import com.sun.source.doctree.StartElementTree;
import com.sun.source.doctree.TextTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.util.DocTrees;
import jdk.javadoc.doclet.Doclet;
import jdk.javadoc.doclet.DocletEnvironment;
import jdk.javadoc.doclet.Reporter;

import javax.lang.model.SourceVersion;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.tools.Diagnostic;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Javadoc doclet that generates META-INF/turing-plugin.json from @Action annotated methods.
 *
 * <p>Extracts, for each @Action method, the action name, the first sentence of its Javadoc as
 * {@code description}, the rest of the Javadoc body as {@code details}, the first {@code <pre>}
 * block of the body as {@code example} (the usage example the author wrote, usually a YAML step),
 * and the @param tags as {@code params} — the argument record's when the method takes a record,
 * the method's own otherwise. The JSON goes into the JAR's META-INF directory, where the Workflow
 * Editor reads it from JAR entries without loading the plugin into the JVM and
 * {@code ActionCatalog} merges it with the JSON Schemas.</p>
 *
 * <p>Configure in maven-javadoc-plugin:</p>
 * <pre>{@code
 * <execution>
 *   <id>generate-plugin-manifest</id>
 *   <goals><goal>javadoc</goal></goals>
 *   <phase>prepare-package</phase>
 *   <configuration>
 *     <doclet>com.scivicslab.pojoactor.doclet.TuringPluginDoclet</doclet>
 *     <docletArtifact>
 *       <groupId>com.scivicslab</groupId>
 *       <artifactId>pojo-actor</artifactId>
 *       <version>3.0.1</version>
 *     </docletArtifact>
 *     <useStandardDocletOptions>false</useStandardDocletOptions>
 *     <outputDirectory>${project.build.outputDirectory}/META-INF</outputDirectory>
 *   </configuration>
 * </execution>
 * }</pre>
 */
public class TuringPluginDoclet implements Doclet {

    private String outputDir = "target/classes/META-INF";
    private Reporter reporter;

    @Override
    public void init(Locale locale, Reporter reporter) {
        this.reporter = reporter;
    }

    @Override
    public String getName() {
        return "TuringPluginDoclet";
    }

    @Override
    public SourceVersion getSupportedSourceVersion() {
        return SourceVersion.latest();
    }

    @Override
    public Set<? extends Option> getSupportedOptions() {
        Option dOption = new Option() {
            @Override public int getArgumentCount() { return 1; }
            @Override public String getDescription() { return "Javadoc output directory (standard, ignored)"; }
            @Override public Kind getKind() { return Kind.STANDARD; }
            @Override public List<String> getNames() { return List.of("-d"); }
            @Override public String getParameters() { return "<directory>"; }
            @Override public boolean process(String opt, List<String> arguments) {
                return true; // ignored — use -turingOutputDir instead
            }
        };
        Option turingOption = new Option() {
            @Override public int getArgumentCount() { return 1; }
            @Override public String getDescription() { return "Absolute output directory for turing-plugin.json"; }
            @Override public Kind getKind() { return Kind.OTHER; }
            @Override public List<String> getNames() { return List.of("-turingOutputDir"); }
            @Override public String getParameters() { return "<directory>"; }
            @Override public boolean process(String opt, List<String> arguments) {
                outputDir = arguments.get(0);
                return true;
            }
        };
        return Set.of(dOption, turingOption);
    }

    @Override
    public boolean run(DocletEnvironment env) {
        DocTrees docTrees = env.getDocTrees();
        List<Map<String, Object>> actors = new ArrayList<>();

        for (Element element : env.getIncludedElements()) {
            if (!(element instanceof TypeElement typeEl)) continue;

            List<Map<String, Object>> actions = new ArrayList<>();

            for (Element enclosed : typeEl.getEnclosedElements()) {
                if (!(enclosed instanceof ExecutableElement method)) continue;

                String actionName = findActionName(method);
                if (actionName == null) continue;

                Map<String, Object> action = new LinkedHashMap<>();
                action.put("name", actionName);
                // A method whose one parameter is a record takes a JSON object whose keys are the
                // record's components; its field prose is the record's own @param tags
                // (ActionCatalogWithJavadoc_260930_oo01).
                TypeElement argsRecord = argumentRecord(method, env);
                action.put("argsFormat", argsRecord != null ? "object" : inferArgsFormat(method, docTrees));

                DocCommentTree docTree = docTrees.getDocCommentTree(method);
                if (docTree != null) {
                    String description = textOf(docTree.getFirstSentence()).trim();
                    if (!description.isEmpty()) {
                        action.put("description", description);
                    }
                    // The body after the first sentence: its first <pre> block is the usage example,
                    // the prose around it the details.
                    String example = firstPreBlock(docTree.getBody());
                    if (!example.isEmpty()) {
                        action.put("example", example);
                    }
                    String details = withoutPreBlocks(docTree.getBody());
                    if (!details.isEmpty()) {
                        action.put("details", details);
                    }
                }
                DocCommentTree paramsTree = argsRecord != null ? docTrees.getDocCommentTree(argsRecord) : docTree;
                if (paramsTree != null) {
                    List<Map<String, String>> params = extractParams(paramsTree);
                    if (!params.isEmpty()) {
                        action.put("params", params);
                    }
                }

                actions.add(action);
            }

            if (!actions.isEmpty()) {
                Map<String, Object> actor = new LinkedHashMap<>();
                actor.put("class", typeEl.getQualifiedName().toString());
                actor.put("actions", actions);
                actors.add(actor);
            }
        }

        if (actors.isEmpty()) {
            reporter.print(Diagnostic.Kind.NOTE, "TuringPluginDoclet: no @Action methods found, skipping manifest generation");
            return true;
        }

        return writeJson(actors);
    }

    private String inferArgsFormat(ExecutableElement method, DocTrees docTrees) {
        if (method.getParameters().isEmpty()) return "none";

        // 1. Check method body AST for direct JSON parsing calls
        try {
            var tree = docTrees.getTree(method);
            if (tree instanceof MethodTree mt && mt.getBody() != null) {
                String body = mt.getBody().toString();
                if (body.contains("JSONArray")) return "array";
                if (body.contains("JSONObject")) return "object";
                if (body.contains("parseInt") || body.contains("parseLong") || body.contains("parseDouble")) return "number";
            }
        } catch (Exception e) {
            // ignore
        }

        // 2. Fall back to @param description text for delegation patterns
        DocCommentTree docTree = docTrees.getDocCommentTree(method);
        if (docTree != null) {
            for (DocTree tag : docTree.getBlockTags()) {
                if (tag instanceof ParamTree paramTag) {
                    String desc = paramTag.getDescription().stream()
                            .map(Object::toString).collect(Collectors.joining(" ")).toLowerCase();
                    if (desc.contains("json array")) return "array";
                    if (desc.contains("json object")) return "object";
                    if (desc.contains("number") || desc.contains("integer") || desc.contains("millisecond")) return "number";
                }
            }
        }

        return "string";
    }

    /** The record type of the method's single parameter, or null when the parameter is not a record. */
    private static TypeElement argumentRecord(ExecutableElement method, DocletEnvironment env) {
        if (method.getParameters().size() != 1) return null;
        Element type = env.getTypeUtils().asElement(method.getParameters().get(0).asType());
        if (type instanceof TypeElement te && te.getKind() == ElementKind.RECORD) return te;
        return null;
    }

    private String findActionName(ExecutableElement method) {
        for (AnnotationMirror mirror : method.getAnnotationMirrors()) {
            String annotationName = mirror.getAnnotationType().asElement().getSimpleName().toString();
            if ("Action".equals(annotationName)) {
                return mirror.getElementValues().values().stream()
                        .map(v -> v.getValue().toString())
                        .findFirst()
                        .orElse(method.getSimpleName().toString());
            }
        }
        return null;
    }

    private List<Map<String, String>> extractParams(DocCommentTree docTree) {
        List<Map<String, String>> params = new ArrayList<>();
        for (DocTree tag : docTree.getBlockTags()) {
            if (tag instanceof ParamTree paramTag) {
                String desc = textOf(paramTag.getDescription()).trim();
                Map<String, String> param = new LinkedHashMap<>();
                param.put("name", paramTag.getName().toString());
                param.put("description", desc);
                params.add(param);
            }
        }
        return params;
    }

    /**
     * The plain text of a run of doc trees: inline tags give their content ({@code {@code x}} is
     * {@code x}, {@code {@link Foo}} is {@code Foo}), entities their character, a {@code <p>} a
     * paragraph break; other HTML elements are dropped and their content kept.
     */
    static String textOf(List<? extends DocTree> trees) {
        StringBuilder sb = new StringBuilder();
        for (DocTree tree : trees) {
            appendText(tree, sb);
        }
        // replacement strings read a backslash as an escape, so the newlines are real ones
        return sb.toString().replaceAll("[ \\t]+\\n", "\n").replaceAll("\\n{3,}", "\n\n");
    }

    private static void appendText(DocTree tree, StringBuilder sb) {
        if (tree instanceof TextTree text) {
            sb.append(text.getBody());
        } else if (tree instanceof LiteralTree literal) {
            sb.append(literal.getBody().getBody());
        } else if (tree instanceof LinkTree link) {
            if (link.getLabel().isEmpty()) {
                sb.append(link.getReference().getSignature());
            } else {
                for (DocTree t : link.getLabel()) appendText(t, sb);
            }
        } else if (tree instanceof EntityTree entity) {
            sb.append(switch (entity.getName().toString()) {
                case "lt" -> "<";
                case "gt" -> ">";
                case "amp" -> "&";
                case "quot" -> "\"";
                case "apos" -> "'";
                case "nbsp" -> " ";
                default -> "&" + entity.getName() + ";";
            });
        } else if (tree instanceof StartElementTree start) {
            String name = start.getName().toString().toLowerCase(Locale.ROOT);
            if (name.equals("p") || name.equals("br") || name.equals("li")) sb.append("\n");
        } else if (tree instanceof EndElementTree end) {
            String name = end.getName().toString().toLowerCase(Locale.ROOT);
            if (name.equals("p") || name.equals("ul") || name.equals("ol")) sb.append("\n");
        } else {
            // other inline tags ({@value}, {@inheritDoc}, unknown ones) carry no prose to show
        }
    }

    /** The text inside the first {@code <pre>...</pre>} of the trees, trimmed; "" when there is none. */
    static String firstPreBlock(List<? extends DocTree> trees) {
        StringBuilder sb = new StringBuilder();
        boolean inside = false;
        for (DocTree tree : trees) {
            if (tree instanceof StartElementTree start && start.getName().toString().equalsIgnoreCase("pre")) {
                inside = true;
                continue;
            }
            if (tree instanceof EndElementTree end && end.getName().toString().equalsIgnoreCase("pre")) {
                if (inside) break;
                continue;
            }
            if (inside) appendText(tree, sb);
        }
        return stripIndent(sb.toString());
    }

    /** The text of the trees with every {@code <pre>...</pre>} left out, trimmed. */
    static String withoutPreBlocks(List<? extends DocTree> trees) {
        List<DocTree> kept = new ArrayList<>();
        boolean inside = false;
        for (DocTree tree : trees) {
            if (tree instanceof StartElementTree start && start.getName().toString().equalsIgnoreCase("pre")) {
                inside = true;
                continue;
            }
            if (tree instanceof EndElementTree end && end.getName().toString().equalsIgnoreCase("pre")) {
                inside = false;
                continue;
            }
            if (!inside) kept.add(tree);
        }
        return textOf(kept).trim();
    }

    /** Removes the common leading indentation of the non-blank lines and the blank edges. */
    static String stripIndent(String text) {
        String[] lines = text.replace("\r", "").split("\n", -1);
        int indent = Integer.MAX_VALUE;
        for (String line : lines) {
            if (line.isBlank()) continue;
            int i = 0;
            while (i < line.length() && line.charAt(i) == ' ') i++;
            indent = Math.min(indent, i);
        }
        if (indent == Integer.MAX_VALUE) return "";
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            sb.append(line.isBlank() ? "" : line.substring(indent)).append('\n');
        }
        return sb.toString().strip();
    }

    private boolean writeJson(List<Map<String, Object>> actors) {
        try {
            Path dir = Paths.get(outputDir);
            Files.createDirectories(dir);
            Path output = dir.resolve("turing-plugin.json");
            Files.writeString(output, buildJson(actors));
            reporter.print(Diagnostic.Kind.NOTE, "TuringPluginDoclet: wrote " + output);
            return true;
        } catch (IOException e) {
            reporter.print(Diagnostic.Kind.ERROR, "TuringPluginDoclet: failed to write manifest: " + e.getMessage());
            return false;
        }
    }

    @SuppressWarnings("unchecked")
    private String buildJson(List<Map<String, Object>> actors) {
        StringBuilder sb = new StringBuilder("{\n  \"actors\": [\n");
        for (int i = 0; i < actors.size(); i++) {
            Map<String, Object> actor = actors.get(i);
            sb.append("    {\n");
            sb.append("      \"class\": ").append(quoted(actor.get("class").toString())).append(",\n");
            sb.append("      \"actions\": [\n");

            List<Map<String, Object>> actions = (List<Map<String, Object>>) actor.get("actions");
            for (int j = 0; j < actions.size(); j++) {
                Map<String, Object> action = actions.get(j);
                sb.append("        {\n");
                sb.append("          \"name\": ").append(quoted(action.get("name").toString()));

                if (action.containsKey("argsFormat")) {
                    sb.append(",\n          \"argsFormat\": ").append(quoted(action.get("argsFormat").toString()));
                }

                if (action.containsKey("description")) {
                    sb.append(",\n          \"description\": ").append(quoted(action.get("description").toString()));
                }
                if (action.containsKey("details")) {
                    sb.append(",\n          \"details\": ").append(quoted(action.get("details").toString()));
                }
                if (action.containsKey("example")) {
                    sb.append(",\n          \"example\": ").append(quoted(action.get("example").toString()));
                }

                if (action.containsKey("params")) {
                    List<Map<String, String>> params = (List<Map<String, String>>) action.get("params");
                    sb.append(",\n          \"params\": [");
                    for (int k = 0; k < params.size(); k++) {
                        Map<String, String> p = params.get(k);
                        sb.append("\n            {\"name\": ").append(quoted(p.get("name")));
                        sb.append(", \"description\": ").append(quoted(p.get("description"))).append("}");
                        if (k < params.size() - 1) sb.append(",");
                    }
                    sb.append("\n          ]");
                }

                sb.append("\n        }");
                if (j < actions.size() - 1) sb.append(",");
                sb.append("\n");
            }

            sb.append("      ]\n    }");
            if (i < actors.size() - 1) sb.append(",");
            sb.append("\n");
        }
        sb.append("  ]\n}");
        return sb.toString();
    }

    /** JSON string literal; the inline tags were turned into text by {@link #textOf}, so braces stay. */
    private String quoted(String s) {
        return "\"" + s.replace("\\", "\\\\")
                       .replace("\"", "\\\"")
                       .replace("\t", "\\t")
                       .replace("\n", "\\n")
                       .replace("\r", "") + "\"";
    }
}
