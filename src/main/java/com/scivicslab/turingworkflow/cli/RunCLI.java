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
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;

import com.scivicslab.pluggablecli.CommandRepository;
import com.scivicslab.pojoactor.core.ActionResult;
import com.scivicslab.turingworkflow.workflow.IIActorSystem;
import com.scivicslab.turingworkflow.workflow.Interpreter;
import com.scivicslab.turingworkflow.workflow.InterpreterIIAR;
import com.scivicslab.turingworkflow.workflow.VarsActor;

/**
 * CLI subcommand for running YAML/JSON workflows.
 *
 * <p>Actor tree structure: ROOT -&gt; InterpreterIIAR</p>
 *
 * <p>Usage:</p>
 * <pre>
 * pojo-actor run -d ./workflows -w hello.yaml
 * pojo-actor run -w ./hello.yaml
 * </pre>
 *
 * @author devteam@scivicslab.com
 * @since 3.0.0
 */
public class RunCLI {

    /**
     * Registers the "run" command with the given repository.
     *
     * @param repo the command repository
     */
    public static void registerCommand(CommandRepository repo) {
        Options opts = new Options();
        opts.addOption(Option.builder("d")
                .longOpt("directory")
                .hasArg(true)
                .argName("dir")
                .desc("Base directory for workflow files (default: .)")
                .build());
        opts.addOption(Option.builder("w")
                .longOpt("workflow")
                .hasArg(true)
                .argName("file")
                .desc("Workflow file path (relative to base directory)")
                .required(true)
                .build());
        opts.addOption(Option.builder("m")
                .longOpt("max-iterations")
                .hasArg(true)
                .argName("n")
                .desc("Maximum iterations (default: 10000)")
                .build());
        opts.addOption(Option.builder("o")
                .longOpt("overlay")
                .hasArg(true)
                .argName("dir")
                .desc("Overlay directory for kustomize")
                .build());
        opts.addOption(Option.builder("P")
                .hasArg(true)
                .argName("key=value")
                .desc("Define a variable (e.g., -Pname=value)")
                .build());

        repo.addCommand("Workflow", "run", opts, "Run a YAML/JSON workflow",
                cl -> new RunCLI().execute(cl));
    }

    /**
     * Executes the run command.
     *
     * @param cl the parsed command line
     */
    public void execute(CommandLine cl) {
        // Parse options
        File baseDirectory = new File(cl.getOptionValue("d", "."));
        String workflowFile = cl.getOptionValue("w");
        int maxIterations = Integer.parseInt(cl.getOptionValue("m", "10000"));
        String overlayPath = cl.getOptionValue("o");
        File overlayDirectory = overlayPath != null ? new File(overlayPath) : null;

        // Parse -P key=value pairs
        Map<String, String> variables = new HashMap<>();
        String[] pValues = cl.getOptionValues("P");
        if (pValues != null) {
            for (String pv : pValues) {
                int eq = pv.indexOf('=');
                if (eq > 0) {
                    variables.put(pv.substring(0, eq), pv.substring(eq + 1));
                }
            }
        }

        // Resolve workflow path
        Path workflowPath = baseDirectory.toPath().resolve(workflowFile);
        if (!workflowPath.toFile().exists()) {
            System.err.println("Workflow file not found: " + workflowPath);
            System.exit(1);
        }

        // Create actor system
        IIActorSystem system = new IIActorSystem("pojo-actor");

        // Create interpreter
        Interpreter interpreter = new Interpreter.Builder()
                .loggerName("interpreter")
                .team(system)
                .build();
        interpreter.setWorkflowBaseDir(baseDirectory.getAbsolutePath());

        // Create vars actor and register
        VarsActor varsActor = new VarsActor(system, variables);
        system.addIIActor(varsActor);

        // Create interpreter actor and register
        InterpreterIIAR interpreterActor = new InterpreterIIAR("interpreter", interpreter, system);
        interpreter.setSelfActorRef(interpreterActor);
        system.addIIActor(interpreterActor);

        // Load workflow
        try {
            if (overlayDirectory != null) {
                interpreter.readYaml(workflowPath, overlayDirectory.toPath());
            } else {
                interpreter.readYaml(workflowPath);
            }
        } catch (java.io.IOException e) {
            System.err.println("Failed to load workflow: " + e.getMessage());
            System.exit(1);
        }

        // Run workflow
        ActionResult result = interpreter.runUntilEnd(maxIterations);

        // Output result
        if (result.isSuccess()) {
            System.out.println("Workflow completed successfully.");
        } else {
            System.err.println("Workflow failed: " + result.getResult());
            System.exit(1);
        }
    }
}
