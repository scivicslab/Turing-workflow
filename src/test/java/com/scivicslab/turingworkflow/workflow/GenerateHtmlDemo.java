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

package com.scivicslab.turingworkflow.workflow;

import java.io.File;
import java.io.InputStream;

import com.scivicslab.turingworkflow.workflow.WorkflowXsltTransformer;

/**
 * Demo program to generate HTML visualizations from XML workflows.
 *
 * <p>This program demonstrates the XSLT transformation capabilities by
 * generating HTML files from the test XML workflows.</p>
 *
 * @author devteam@scivicslab.com
 */
public class GenerateHtmlDemo {

    public static void main(String[] args) {
        try {
            String outputDir = "target/workflow-html-demos";
            new File(outputDir).mkdirs();

            System.out.println("Generating HTML demos from XML workflows...\n");

            // Generate demos for each workflow
            generateDemos(outputDir, "simple-math");
            generateDemos(outputDir, "multi-action");
            generateDemos(outputDir, "complex-branching");

            System.out.println("\n✅ All HTML demos generated successfully!");
            System.out.println("\nOutput directory: " + new File(outputDir).getAbsolutePath());
            System.out.println("\nOpen the HTML files in a web browser to view the visualizations.");

        } catch (Exception e) {
            System.err.println("❌ Error generating demos: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private static void generateDemos(String outputDir, String workflowName) throws Exception {
        // Copy XML file
        InputStream xmlStream = GenerateHtmlDemo.class.getResourceAsStream("/workflows/" + workflowName + ".xml");
        if (xmlStream == null) {
            System.err.println("⚠️  XML file not found: " + workflowName + ".xml");
            return;
        }

        File xmlFile = new File(outputDir, workflowName + ".xml");
        java.nio.file.Files.copy(xmlStream, xmlFile.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);

        // Generate table view
        File tableHtml = new File(outputDir, workflowName + "-table.html");
        WorkflowXsltTransformer.transformToTable(xmlFile, tableHtml);
        System.out.println("📊 Generated: " + tableHtml.getName());

        // Generate graph view
        File graphHtml = new File(outputDir, workflowName + "-graph.html");
        WorkflowXsltTransformer.transformToGraph(xmlFile, graphHtml);
        System.out.println("🔀 Generated: " + graphHtml.getName());
    }
}
