/*
 * Copyright 2025 devteam@scivicslab.com
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package com.scivicslab.turingworkflow.cli;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;

import com.scivicslab.pluggablecli.CommandRepository;

/**
 * Subcommand to list workflows discovered under a directory.
 *
 * @author devteam@scivicslab.com
 * @since 3.0.0
 */
public class ListWorkflowsCLI {

    /**
     * Registers the "list" command with the given repository.
     *
     * @param repo the command repository
     */
    public static void registerCommand(CommandRepository repo) {
        Options opts = new Options();
        opts.addOption(Option.builder("d")
                .longOpt("dir")
                .hasArg(true)
                .argName("dir")
                .desc("Base directory. Defaults to current directory.")
                .build());
        opts.addOption(Option.builder("w")
                .longOpt("workflow")
                .hasArg(true)
                .argName("path")
                .desc("Workflow directory path (relative to -d)")
                .required(true)
                .build());
        opts.addOption(Option.builder("o")
                .longOpt("output")
                .hasArg(true)
                .argName("format")
                .desc("Output format: table, json, yaml (default: table)")
                .build());

        repo.addCommand("Workflow", "list", opts, "List workflows in the specified directory.",
                cl -> new ListWorkflowsCLI().execute(cl));
    }

    /**
     * Executes the list command.
     *
     * @param cl the parsed command line
     */
    public void execute(CommandLine cl) {
        File baseDir = new File(cl.getOptionValue("d", "."));
        String workflowPath = cl.getOptionValue("w");
        String outputFormat = cl.getOptionValue("o", "table");

        File workflowDir = new File(baseDir, workflowPath);
        if (!workflowDir.isDirectory()) {
            System.err.println("Not a directory: " + workflowDir);
            System.exit(1);
        }

        List<WorkflowInfo> workflows = scanWorkflowsForDisplay(workflowDir, workflowPath);
        if (workflows.isEmpty()) {
            if ("json".equalsIgnoreCase(outputFormat)) {
                System.out.println("[]");
            } else if ("yaml".equalsIgnoreCase(outputFormat)) {
                System.out.println("workflows: []");
            } else {
                System.out.println("No workflow files found in " + workflowDir.getPath());
            }
            return;
        }

        switch (outputFormat.toLowerCase()) {
            case "json" -> printJson(workflows);
            case "yaml" -> printYaml(workflows);
            default -> printTable(workflows, workflowDir.getPath());
        }
    }

    private static final int WRAP_WIDTH = 70;
    private static final String INDENT = "    ";

    private void printTable(List<WorkflowInfo> workflows, String dirPath) {
        System.out.println("Workflows in " + dirPath + ":");
        System.out.println();
        String separator = "-".repeat(WRAP_WIDTH + INDENT.length());
        for (int i = 0; i < workflows.size(); i++) {
            WorkflowInfo wf = workflows.get(i);
            System.out.println("  " + wf.path());
            if (wf.name() != null) {
                System.out.println(INDENT + "name: " + wf.name());
            }
            if (wf.description() != null) {
                printWrapped("description", wf.description());
            }
            if (i < workflows.size() - 1) {
                System.out.println(separator);
            }
        }
        System.out.println();
    }

    private void printWrapped(String label, String text) {
        String prefix = INDENT + label + ": ";
        String continuationIndent = INDENT + " ".repeat(label.length() + 2);
        int maxWidth = WRAP_WIDTH;

        String[] words = text.split("\\s+");
        StringBuilder line = new StringBuilder(prefix);
        boolean firstLine = true;

        for (String word : words) {
            if (line.length() + word.length() + 1 > maxWidth + prefix.length() && line.length() > (firstLine ? prefix.length() : continuationIndent.length())) {
                System.out.println(line.toString());
                line = new StringBuilder(continuationIndent);
                firstLine = false;
            }
            if (line.length() > (firstLine ? prefix.length() : continuationIndent.length())) {
                line.append(" ");
            }
            line.append(word);
        }
        if (line.length() > (firstLine ? prefix.length() : continuationIndent.length())) {
            System.out.println(line.toString());
        }
    }

    private void printJson(List<WorkflowInfo> workflows) {
        org.json.JSONArray arr = new org.json.JSONArray();
        for (WorkflowInfo wf : workflows) {
            org.json.JSONObject obj = new org.json.JSONObject();
            obj.put("path", wf.path());
            if (wf.name() != null) obj.put("name", wf.name());
            if (wf.description() != null) obj.put("description", wf.description());
            if (wf.note() != null) obj.put("note", wf.note());
            arr.put(obj);
        }
        System.out.println(arr.toString(2));
    }

    private void printYaml(List<WorkflowInfo> workflows) {
        System.out.println("workflows:");
        for (WorkflowInfo wf : workflows) {
            System.out.println("  - path: " + wf.path());
            if (wf.name() != null) {
                System.out.println("    name: " + wf.name());
            }
            if (wf.description() != null) {
                // Handle multi-line descriptions
                if (wf.description().contains("\n")) {
                    System.out.println("    description: |");
                    for (String line : wf.description().split("\n")) {
                        System.out.println("      " + line);
                    }
                } else {
                    System.out.println("    description: " + wf.description());
                }
            }
            if (wf.note() != null) {
                System.out.println("    note: " + wf.note());
            }
        }
    }

    private static List<WorkflowInfo> scanWorkflowsForDisplay(File directory, String workflowPath) {
        if (directory == null) {
            return List.of();
        }

        // Non-recursive scan - only files in immediate directory
        try (Stream<Path> paths = Files.list(directory.toPath())) {
            return paths.filter(Files::isRegularFile)
                 .filter(path -> {
                     String name = path.getFileName().toString().toLowerCase();
                     return name.endsWith(".yaml") || name.endsWith(".yml")
                         || name.endsWith(".json") || name.endsWith(".xml");
                 })
                 .map(path -> {
                     File file = path.toFile();
                     String relPath = workflowPath.endsWith("/")
                         ? workflowPath + file.getName()
                         : workflowPath + "/" + file.getName();
                     return extractWorkflowInfo(file, relPath);
                 })
                 .sorted(Comparator.comparing(WorkflowInfo::path, String.CASE_INSENSITIVE_ORDER))
                 .collect(Collectors.toList());
        } catch (IOException e) {
            System.err.println("Failed to scan workflows: " + e.getMessage());
            return List.of();
        }
    }

    private static WorkflowInfo extractWorkflowInfo(File file, String path) {
        String fileName = file.getName().toLowerCase();
        if (fileName.endsWith(".yaml") || fileName.endsWith(".yml")) {
            return extractWorkflowInfoFromYaml(file, path);
        } else if (fileName.endsWith(".json")) {
            return extractWorkflowInfoFromJson(file, path);
        }
        return new WorkflowInfo(path, null, null, null);
    }

    private static WorkflowInfo extractWorkflowInfoFromYaml(File file, String path) {
        String name = null;
        String description = null;
        String note = null;

        try (java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.FileReader(file))) {
            String line;
            StringBuilder currentValue = new StringBuilder();
            String currentField = null;
            boolean inMultiLine = false;

            while ((line = reader.readLine()) != null) {
                // Stop at steps or vertices
                if (line.trim().startsWith("steps:") || line.trim().startsWith("vertices:")) {
                    break;
                }

                if (inMultiLine) {
                    if (line.startsWith("  ") || line.startsWith("\t") || line.trim().isEmpty()) {
                        if (!line.trim().isEmpty()) {
                            currentValue.append(line.trim()).append(" ");
                        }
                        continue;
                    } else {
                        // End of multi-line
                        String value = currentValue.toString().trim();
                        if ("name".equals(currentField)) name = value;
                        else if ("description".equals(currentField)) description = value;
                        else if ("note".equals(currentField)) note = value;
                        inMultiLine = false;
                        currentValue = new StringBuilder();
                        currentField = null;
                    }
                }

                if (line.trim().startsWith("name:") && name == null) {
                    String value = line.substring(line.indexOf(':') + 1).trim();
                    if (value.equals("|") || value.equals(">")) {
                        inMultiLine = true;
                        currentField = "name";
                    } else if (!value.isEmpty()) {
                        name = stripQuotes(value);
                    }
                } else if (line.trim().startsWith("description:") && description == null) {
                    String value = line.substring(line.indexOf(':') + 1).trim();
                    if (value.equals("|") || value.equals(">")) {
                        inMultiLine = true;
                        currentField = "description";
                    } else if (!value.isEmpty()) {
                        description = stripQuotes(value);
                    }
                } else if (line.trim().startsWith("note:") && note == null) {
                    String value = line.substring(line.indexOf(':') + 1).trim();
                    if (value.equals("|") || value.equals(">")) {
                        inMultiLine = true;
                        currentField = "note";
                    } else if (!value.isEmpty()) {
                        note = stripQuotes(value);
                    }
                }
            }

            // Handle trailing multi-line
            if (inMultiLine && currentValue.length() > 0) {
                String value = currentValue.toString().trim();
                if ("name".equals(currentField)) name = value;
                else if ("description".equals(currentField)) description = value;
                else if ("note".equals(currentField)) note = value;
            }
        } catch (IOException e) {
            // Ignore
        }
        return new WorkflowInfo(path, name, description, note);
    }

    private static String stripQuotes(String value) {
        if ((value.startsWith("\"") && value.endsWith("\"")) ||
            (value.startsWith("'") && value.endsWith("'"))) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    private static WorkflowInfo extractWorkflowInfoFromJson(File file, String path) {
        try (java.io.FileReader reader = new java.io.FileReader(file)) {
            org.json.JSONTokener tokener = new org.json.JSONTokener(reader);
            org.json.JSONObject json = new org.json.JSONObject(tokener);
            return new WorkflowInfo(
                path,
                json.optString("name", null),
                json.optString("description", null),
                json.optString("note", null)
            );
        } catch (Exception e) {
            // Ignore
        }
        return new WorkflowInfo(path, null, null, null);
    }

    static record WorkflowInfo(String path, String name, String description, String note) {}
}
