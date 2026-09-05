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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;

import org.json.JSONObject;
import org.yaml.snakeyaml.Yaml;

import com.scivicslab.pluggablecli.CommandRepository;
import com.scivicslab.pojoactor.core.ActionResult;
import com.scivicslab.turingworkflow.workflow.DynamicActorLoaderIIAR;
import com.scivicslab.turingworkflow.workflow.IIActorSystem;
import com.scivicslab.turingworkflow.workflow.Interpreter;
import com.scivicslab.turingworkflow.workflow.InterpreterIIAR;
import com.scivicslab.turingworkflow.workflow.VarsActor;
import com.scivicslab.turingworkflow.workflow.accumulator.ConsoleAccumulator;
import com.scivicslab.turingworkflow.workflow.accumulator.MultiplexerAccumulator;
import com.scivicslab.turingworkflow.workflow.accumulator.MultiplexerAccumulatorIIAR;

/**
 * Definition of the {@code run} subcommand: the class where the command-line options for
 * running a YAML/JSON workflow are actually declared.
 *
 * <p>{@link #registerCommand(CommandRepository)} builds the Commons CLI {@link Options} for
 * {@code run} (such as {@code -w}, {@code -d}, {@code -m}, {@code -o}, {@code -P},
 * {@code -l}) and registers the command name, its description, and its action into the
 * shared {@link CommandRepository}. It is invoked by {@link App} during startup.
 * In other words, {@link App} owns the dispatch loop, while this class owns the
 * structured definition of the {@code run} command's options.</p>
 *
 * <p>{@link #execute(CommandLine)} is the action body: it reads the parsed options off the
 * {@link CommandLine} and runs the workflow.</p>
 *
 * <p>Actor tree structure: ROOT -&gt; InterpreterIIAR</p>
 *
 * <p>Usage:</p>
 * <pre>
 * pojo-actor run -w ./hello.yaml
 * pojo-actor run -d ./workflows -w hello.yaml
 * pojo-actor run -w ./hello.yaml -P key=value
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
                .desc("Override a workflow parameter (e.g., -P key=value); overrides params.default in YAML")
                .build());
        opts.addOption(Option.builder("l")
                .longOpt("log-level")
                .hasArg(true)
                .argName("level")
                .desc("Log level: OFF, SEVERE, WARNING, INFO, FINE, FINER, FINEST (default: INFO)")
                .build());

        // "Workflow" category: groups run/list/describe together under "## Workflow",
        // parallel to plugin-contributed categories such as "## Log".
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

        // Apply log level
        java.util.logging.Level logLevel = java.util.logging.Level.INFO;
        String logLevelStr = cl.getOptionValue("l", "INFO");
        try {
            logLevel = java.util.logging.Level.parse(logLevelStr.toUpperCase());
        } catch (IllegalArgumentException e) {
            System.err.println("Unknown log level: " + logLevelStr + " — using INFO");
        }
        for (String name : new String[]{"workflow", "com.scivicslab.pojoactor", "com.scivicslab.turingworkflow"}) {
            java.util.logging.Logger l = java.util.logging.Logger.getLogger(name);
            l.setLevel(logLevel);
            for (java.util.logging.Handler h : java.util.logging.Logger.getLogger("").getHandlers()) {
                if (h.getLevel().intValue() > logLevel.intValue()) {
                    h.setLevel(logLevel);
                }
            }
        }

        // Install ISO 8601 timestamp formatter on all root handlers
        java.util.logging.Formatter isoFormatter = new java.util.logging.Formatter() {
            private final java.time.format.DateTimeFormatter dtf =
                java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME;
            @Override
            public String format(java.util.logging.LogRecord record) {
                java.time.ZonedDateTime zdt = java.time.ZonedDateTime.ofInstant(
                    record.getInstant(), java.time.ZoneId.systemDefault());
                String thrown = "";
                if (record.getThrown() != null) {
                    java.io.StringWriter sw = new java.io.StringWriter();
                    record.getThrown().printStackTrace(new java.io.PrintWriter(sw));
                    thrown = sw.toString();
                }
                return dtf.format(zdt) + " " + record.getLevel()
                    + " " + record.getLoggerName()
                    + " - " + formatMessage(record)
                    + System.lineSeparator() + thrown;
            }
        };
        for (java.util.logging.Handler h : java.util.logging.Logger.getLogger("").getHandlers()) {
            h.setFormatter(isoFormatter);
        }

        // Load variables from -P key=value (overrides params.default in YAML)
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

        // Register built-in actors
        DynamicActorLoaderIIAR loaderActor = new DynamicActorLoaderIIAR("loader", system);
        system.addIIActor(loaderActor);

        MultiplexerAccumulator mux = new MultiplexerAccumulator();
        mux.addTarget(new ConsoleAccumulator());
        MultiplexerAccumulatorIIAR logActor = new MultiplexerAccumulatorIIAR("log", mux, system);
        system.addIIActor(logActor);

        // Create vars actor and register
        VarsActor varsActor = new VarsActor(system, variables);
        system.addIIActor(varsActor);

        // Create interpreter actor and register
        InterpreterIIAR interpreterActor = new InterpreterIIAR("interpreter", interpreter, system);
        interpreter.setSelfActorRef(interpreterActor);
        system.addIIActor(interpreterActor);

        // Put -P / -V variables into interpreter JSON state for ${varName} expansion
        for (Map.Entry<String, String> entry : variables.entrySet()) {
            String jsonArg = new JSONObject()
                .put("path", entry.getKey())
                .put("value", entry.getValue())
                .toString();
            interpreterActor.callByActionName("putJson", jsonArg);
        }

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

        // Apply params.default values for variables not already set by -P or -V
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> doc = new Yaml().load(Files.readString(workflowPath));
            if (doc != null && doc.get("params") instanceof Map<?, ?> params) {
                params.forEach((key, meta) -> {
                    String k = String.valueOf(key);
                    if (!variables.containsKey(k) && meta instanceof Map<?, ?> m
                            && m.containsKey("default")) {
                        String defaultVal = String.valueOf(m.get("default"));
                        String jsonArg = new JSONObject()
                            .put("path", k)
                            .put("value", defaultVal)
                            .toString();
                        interpreterActor.callByActionName("putJson", jsonArg);
                    }
                });
            }
        } catch (Exception e) {
            // params section is optional; ignore parse errors
        }

        // Run workflow
        ActionResult result = interpreter.runUntilEnd(maxIterations);

        // Terminate the actor system so non-daemon threads are released.
        // terminateIIActors() closes the IIActor wrappers (e.g. their HTTP clients, which
        // otherwise keep a non-daemon thread alive); terminate() shuts down the worker pools.
        system.terminateIIActors();
        system.terminate();

        // Output result and exit with an appropriate code
        if (result.isSuccess()) {
            System.out.println("Workflow completed successfully.");
            System.exit(0);
        } else {
            System.err.println("Workflow failed: " + result.getResult());
            System.exit(1);
        }
    }
}
