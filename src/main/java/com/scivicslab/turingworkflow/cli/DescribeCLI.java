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
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.List;
import java.util.Map;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;

import com.scivicslab.pluggablecli.CommandRepository;
import com.scivicslab.turingworkflow.workflow.kustomize.WorkflowKustomizer;

/**
 * CLI subcommand for displaying workflow descriptions.
 *
 * @author devteam@scivicslab.com
 * @since 3.0.0
 */
public class DescribeCLI {

    /**
     * Registers the "describe" command with the given repository.
     *
     * @param repo the command repository
     */
    public static void registerCommand(CommandRepository repo) {
        Options opts = new Options();
        opts.addOption(Option.builder("d")
                .longOpt("dir")
                .hasArg(true).argName("dir")
                .desc("Base directory. Defaults to current directory.")
                .build());
        opts.addOption(Option.builder("w")
                .longOpt("workflow")
                .hasArg(true).argName("path")
                .desc("Workflow file path relative to -d (required)")
                .required(true)
                .build());
        opts.addOption(Option.builder("o")
                .longOpt("overlay")
                .hasArg(true).argName("dir")
                .desc("Overlay directory containing overlay-conf.yaml")
                .build());
        opts.addOption(Option.builder()
                .longOpt("steps")
                .desc("Also display note/description of each step")
                .build());

        repo.addCommand("Workflow", "describe", opts, "Display workflow and step descriptions.",
                cl -> new DescribeCLI().execute(cl));
    }

    /**
     * Executes the describe command.
     *
     * @param cl the parsed command line
     */
    public void execute(CommandLine cl) {
        File baseDir = new File(cl.getOptionValue("d", "."));
        String workflowPath = cl.getOptionValue("w");
        String overlayPath = cl.getOptionValue("o");
        File overlayDir = overlayPath != null ? new File(overlayPath) : null;
        boolean showSteps = cl.hasOption("steps");

        // Resolve workflow file (try extensions if needed)
        File resolvedFile = resolveWorkflowFile(new File(baseDir, workflowPath));
        if (resolvedFile == null || !resolvedFile.isFile()) {
            System.err.println("Workflow file not found: " + workflowPath);
            System.exit(1);
        }

        // Load YAML (with overlay if specified)
        Map<String, Object> yaml;
        if (overlayDir != null) {
            yaml = loadYamlWithOverlay(resolvedFile, overlayDir);
        } else {
            yaml = loadYamlFile(resolvedFile);
        }

        if (yaml == null) {
            System.err.println("Failed to load workflow: " + resolvedFile);
            System.exit(1);
        }

        // Print workflow description
        printWorkflowDescription(resolvedFile, yaml, overlayDir, showSteps);
    }

    private File resolveWorkflowFile(File file) {
        if (file.isFile()) {
            return file;
        }
        String[] extensions = {".yaml", ".yml", ".json", ".xml"};
        for (String ext : extensions) {
            File candidate = new File(file.getPath() + ext);
            if (candidate.isFile()) {
                return candidate;
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private void printWorkflowDescription(File file, Map<String, Object> yaml,
                                           File overlayDir, boolean showSteps) {
        String name = (String) yaml.getOrDefault("name", "(unnamed)");
        String description = (String) yaml.get("description");

        System.out.println("Workflow: " + name);
        System.out.println("File: " + file.getAbsolutePath());
        if (overlayDir != null) {
            System.out.println("Overlay: " + overlayDir.getAbsolutePath());
        }
        System.out.println();

        // Workflow-level description
        System.out.println("Description:");
        if (description != null && !description.isBlank()) {
            for (String line : description.split("\n")) {
                System.out.println("  " + line);
            }
        } else {
            System.out.println("  (no description)");
        }

        // Step descriptions (if --steps flag is set)
        if (showSteps) {
            System.out.println();
            System.out.println("Steps:");

            List<Map<String, Object>> steps = (List<Map<String, Object>>) yaml.get("steps");
            if (steps == null || steps.isEmpty()) {
                System.out.println("  (no steps defined)");
                return;
            }

            for (Map<String, Object> step : steps) {
                List<String> states = (List<String>) step.get("states");
                String label = (String) step.get("label");
                String stepNote = (String) step.get("note");

                String stateTransition = (states != null && states.size() >= 2)
                    ? states.get(0) + " -> " + states.get(1)
                    : "?";

                String displayName = (label != null) ? label : "(unnamed)";

                System.out.println();
                System.out.println("  [" + stateTransition + "] " + displayName);
                if (stepNote != null && !stepNote.isBlank()) {
                    for (String line : stepNote.split("\n")) {
                        System.out.println("    " + line);
                    }
                }
            }
        }
    }

    private Map<String, Object> loadYamlFile(File file) {
        try (InputStream is = new FileInputStream(file)) {
            org.yaml.snakeyaml.Yaml yaml = new org.yaml.snakeyaml.Yaml();
            return yaml.load(is);
        } catch (Exception e) {
            System.err.println("Failed to load YAML file: " + file + " - " + e.getMessage());
            return null;
        }
    }

    private Map<String, Object> loadYamlWithOverlay(File workflowFile, File overlayDir) {
        try {
            WorkflowKustomizer kustomizer = new WorkflowKustomizer();
            Map<String, Map<String, Object>> workflows = kustomizer.build(overlayDir.toPath());

            String targetName = workflowFile.getName();
            for (Map.Entry<String, Map<String, Object>> entry : workflows.entrySet()) {
                if (entry.getKey().equals(targetName)) {
                    return entry.getValue();
                }
            }

            // Fall back to raw file
            return loadYamlFile(workflowFile);
        } catch (Exception e) {
            System.err.println("Failed to apply overlay, loading raw file: " + e.getMessage());
            return loadYamlFile(workflowFile);
        }
    }
}
