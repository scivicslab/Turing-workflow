/*
 * Copyright 2025 devteam@scivics-lab.com
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

package com.scivicslab.turingworkflow.examples.turing;

import com.scivicslab.pojoactor.core.ActionResult;
import com.scivicslab.turingworkflow.workflow.IIActorRef;
import com.scivicslab.turingworkflow.workflow.IIActorSystem;
import com.scivicslab.turingworkflow.workflow.Interpreter;

import java.io.InputStream;

/**
 * Application that executes Turing machine workflows from YAML files.
 *
 * <p>This application demonstrates how to use the actor-WF workflow interpreter
 * to execute Turing machine operations defined in YAML workflow files.</p>
 *
 * <p>Usage:</p>
 * <pre>
 * # Part 2-1/2-2: Run with callByActionName() version (TuringIIAR)
 * mvn exec:java -Dexec.mainClass="com.scivicslab.turing.TuringWorkflowApp" -Dexec.args="turing83"
 * mvn exec:java -Dexec.mainClass="com.scivicslab.turing.TuringWorkflowApp" -Dexec.args="turing87"
 *
 * # Part 2-3: Run with @Action annotation version (TuringActionIIAR)
 * mvn exec:java -Dexec.mainClass="com.scivicslab.turing.TuringWorkflowApp" -Dexec.args="turing87-array --action"
 * </pre>
 *
 * @author devteam@scivics-lab.com
 */
public class TuringWorkflowApp {

    /**
     * Main entry point for the Turing workflow application.
     *
     * @param args command line arguments - workflow name and optional --action flag
     */
    public static void main(String[] args) {
        if (args.length == 0) {
            System.err.println("Usage: TuringWorkflowApp <workflow-name> [--action]");
            System.err.println();
            System.err.println("Examples:");
            System.err.println("  TuringWorkflowApp turing83           # Part 2-1/2-2 (callByActionName)");
            System.err.println("  TuringWorkflowApp turing87           # Part 2-1/2-2 (callByActionName)");
            System.err.println("  TuringWorkflowApp turing87-array --action  # Part 2-3 (@Action annotation)");
            System.exit(1);
        }

        String workflowName = args[0];
        boolean useActionAnnotation = args.length > 1 && args[1].equals("--action");
        String yamlPath = "/examples/" + workflowName + ".yaml";

        TuringWorkflowApp app = new TuringWorkflowApp();
        app.runWorkflow(yamlPath, useActionAnnotation);
    }

    /**
     * Executes a Turing machine workflow from a YAML file.
     *
     * @param yamlPath the resource path to the YAML workflow file
     * @param useActionAnnotation if true, use TuringActionIIAR (@Action version)
     */
    public void runWorkflow(String yamlPath, boolean useActionAnnotation) {
        IIActorSystem system = new IIActorSystem("turing-system");

        try {
            // Create Turing machine actor
            Turing turing = new Turing();
            IIActorRef<Turing> turingActor;

            if (useActionAnnotation) {
                // Part 2-3: @Action annotation version
                turingActor = new TuringActionIIAR("turing", turing, system);
                System.out.println("Using @Action annotation version (TuringActionIIAR)");
            } else {
                // Part 2-1/2-2: callByActionName() version
                turingActor = new TuringIIAR("turing", turing, system);
                System.out.println("Using callByActionName() version (TuringIIAR)");
            }
            system.addIIActor(turingActor);

            // Create interpreter
            Interpreter interpreter = new Interpreter.Builder()
                    .loggerName("TuringWorkflow")
                    .team(system)
                    .build();

            // Load workflow
            System.out.println("Loading workflow from: " + yamlPath);
            InputStream yamlStream = getClass().getResourceAsStream(yamlPath);
            if (yamlStream == null) {
                System.err.println("ERROR: Workflow file not found: " + yamlPath);
                System.exit(1);
            }

            interpreter.readYaml(yamlStream);
            System.out.println("Workflow loaded successfully");

            // Execute workflow (200 iterations maximum)
            System.out.println("Executing workflow...\n");
            ActionResult result = interpreter.runUntilEnd(200);

            // Show result
            if (result.isSuccess()) {
                System.out.println("\nWorkflow completed successfully: " + result.getResult());
            } else {
                System.out.println("\nWorkflow finished: " + result.getResult());
            }

        } finally {
            // Clean up
            system.terminateIIActors();
            system.terminate();
        }
    }

    /**
     * Executes a Turing machine workflow (backward compatible).
     *
     * @param yamlPath the resource path to the YAML workflow file
     */
    public void runWorkflow(String yamlPath) {
        runWorkflow(yamlPath, false);
    }
}
