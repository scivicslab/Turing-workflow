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
import com.sun.source.doctree.ParamTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.util.DocTrees;
import jdk.javadoc.doclet.Doclet;
import jdk.javadoc.doclet.DocletEnvironment;
import jdk.javadoc.doclet.Reporter;

import javax.lang.model.SourceVersion;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.Element;
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
 * <p>Extracts action names, Javadoc descriptions, and @param tags for each @Action method,
 * writing structured JSON metadata into the JAR's META-INF directory. The Workflow Editor
 * reads this file directly from JAR entries without loading the plugin into the JVM.</p>
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
                action.put("argsFormat", inferArgsFormat(method, docTrees));

                DocCommentTree docTree = docTrees.getDocCommentTree(method);
                if (docTree != null) {
                    String description = docTree.getFirstSentence().stream()
                            .map(Object::toString)
                            .collect(Collectors.joining(" "))
                            .trim();
                    if (!description.isEmpty()) {
                        action.put("description", description);
                    }

                    List<Map<String, String>> params = extractParams(docTree);
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
                String desc = paramTag.getDescription().stream()
                        .map(Object::toString)
                        .collect(Collectors.joining(" "))
                        .trim();
                Map<String, String> param = new LinkedHashMap<>();
                param.put("name", paramTag.getName().toString());
                param.put("description", desc);
                params.add(param);
            }
        }
        return params;
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

    private String quoted(String s) {
        return "\"" + s.replace("\\", "\\\\")
                       .replace("\"", "\\\"")
                       .replace("\n", "\\n")
                       .replace("\r", "")
                       .replace("{@code ", "")
                       .replace("{@link ", "")
                       .replace("}", "") + "\"";
    }
}
