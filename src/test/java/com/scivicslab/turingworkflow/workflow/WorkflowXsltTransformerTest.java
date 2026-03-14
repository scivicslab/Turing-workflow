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

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.io.InputStream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.scivicslab.turingworkflow.workflow.WorkflowXsltTransformer;

/**
 * Tests for WorkflowXsltTransformer functionality.
 *
 * <p>This test suite verifies XSLT transformation of XML workflows to HTML.</p>
 *
 * @author devteam@scivicslab.com
 * @version 2.5.0
 */
@DisplayName("Workflow XSLT Transformer Tests")
public class WorkflowXsltTransformerTest {

    @TempDir
    File tempDir;

    /**
     * Test 1: Transform XML workflow to HTML table view (File to File).
     */
    @Test
    @DisplayName("Should transform XML to HTML table view (File)")
    public void testTransformToTableFile() throws Exception {
        // Get test XML file from resources
        InputStream xmlStream = getClass().getResourceAsStream("/workflows/simple-math.xml");
        assertNotNull(xmlStream, "Test XML file should exist");

        // Create temporary XML file
        File xmlFile = new File(tempDir, "simple-math.xml");
        java.nio.file.Files.copy(xmlStream, xmlFile.toPath());

        // Transform to HTML
        File htmlFile = new File(tempDir, "simple-math-table.html");
        WorkflowXsltTransformer.transformToTable(xmlFile, htmlFile);

        // Verify output exists and contains expected content
        assertTrue(htmlFile.exists(), "HTML output file should exist");
        assertTrue(htmlFile.length() > 0, "HTML file should not be empty");

        String content = java.nio.file.Files.readString(htmlFile.toPath());
        assertTrue(content.contains("<html"), "Should contain HTML tag");
        assertTrue(content.contains("simple-math-workflow"), "Should contain workflow name");
        assertTrue(content.contains("<table"), "Should contain table tag");
        assertTrue(content.contains("math"), "Should contain actor name");
        assertTrue(content.contains("add"), "Should contain method name");
    }

    /**
     * Test 2: Transform XML workflow to HTML graph view (File to File).
     */
    @Test
    @DisplayName("Should transform XML to HTML graph view (File)")
    public void testTransformToGraphFile() throws Exception {
        // Get test XML file from resources
        InputStream xmlStream = getClass().getResourceAsStream("/workflows/simple-math.xml");
        assertNotNull(xmlStream, "Test XML file should exist");

        // Create temporary XML file
        File xmlFile = new File(tempDir, "simple-math.xml");
        java.nio.file.Files.copy(xmlStream, xmlFile.toPath());

        // Transform to HTML
        File htmlFile = new File(tempDir, "simple-math-graph.html");
        WorkflowXsltTransformer.transformToGraph(xmlFile, htmlFile);

        // Verify output exists and contains expected content
        assertTrue(htmlFile.exists(), "HTML output file should exist");
        assertTrue(htmlFile.length() > 0, "HTML file should not be empty");

        String content = java.nio.file.Files.readString(htmlFile.toPath());
        assertTrue(content.contains("<html"), "Should contain HTML tag");
        assertTrue(content.contains("simple-math-workflow"), "Should contain workflow name");
        assertTrue(content.contains("state-node"), "Should contain state node CSS class");
        assertTrue(content.contains("math"), "Should contain actor name");
        assertTrue(content.contains("add"), "Should contain method name");
    }

    /**
     * Test 3: Transform XML workflow to HTML table view (InputStream to String).
     */
    @Test
    @DisplayName("Should transform XML to HTML table view (String)")
    public void testTransformToTableString() throws Exception {
        InputStream xmlStream = getClass().getResourceAsStream("/workflows/simple-math.xml");
        assertNotNull(xmlStream, "Test XML file should exist");

        String html = WorkflowXsltTransformer.transformToTableString(xmlStream);

        assertNotNull(html, "HTML output should not be null");
        assertFalse(html.isEmpty(), "HTML output should not be empty");
        assertTrue(html.contains("<html"), "Should contain HTML tag");
        assertTrue(html.contains("simple-math-workflow"), "Should contain workflow name");
        assertTrue(html.contains("<table"), "Should contain table tag");
        assertTrue(html.contains("math"), "Should contain actor name");
        assertTrue(html.contains("add"), "Should contain method name");
        // Arguments are now in separate <arg> elements: <arg>10</arg><arg>5</arg>
        assertTrue(html.contains("10") && html.contains("5"), "Should contain arguments");
    }

    /**
     * Test 4: Transform XML workflow to HTML graph view (InputStream to String).
     */
    @Test
    @DisplayName("Should transform XML to HTML graph view (String)")
    public void testTransformToGraphString() throws Exception {
        InputStream xmlStream = getClass().getResourceAsStream("/workflows/simple-math.xml");
        assertNotNull(xmlStream, "Test XML file should exist");

        String html = WorkflowXsltTransformer.transformToGraphString(xmlStream);

        assertNotNull(html, "HTML output should not be null");
        assertFalse(html.isEmpty(), "HTML output should not be empty");
        assertTrue(html.contains("<html"), "Should contain HTML tag");
        assertTrue(html.contains("simple-math-workflow"), "Should contain workflow name");
        assertTrue(html.contains("state-node"), "Should contain state node CSS class");
        assertTrue(html.contains("math"), "Should contain actor name");
        assertTrue(html.contains("add"), "Should contain method name");
    }

    /**
     * Test 5: Transform complex branching workflow to table view.
     */
    @Test
    @DisplayName("Should transform complex workflow to HTML table view")
    public void testTransformComplexWorkflowToTable() throws Exception {
        InputStream xmlStream = getClass().getResourceAsStream("/workflows/complex-branching.xml");
        assertNotNull(xmlStream, "Complex workflow XML should exist");

        String html = WorkflowXsltTransformer.transformToTableString(xmlStream);

        assertNotNull(html, "HTML output should not be null");
        assertTrue(html.contains("complex-branching"), "Should contain workflow name");
        assertTrue(html.contains("init"), "Should contain init state");
        assertTrue(html.contains("state_A"), "Should contain state_A");
        assertTrue(html.contains("state_B"), "Should contain state_B");
        assertTrue(html.contains("checker"), "Should contain checker actor");
        assertTrue(html.contains("processor"), "Should contain processor actor");
    }

    /**
     * Test 6: Transform complex branching workflow to graph view.
     */
    @Test
    @DisplayName("Should transform complex workflow to HTML graph view")
    public void testTransformComplexWorkflowToGraph() throws Exception {
        InputStream xmlStream = getClass().getResourceAsStream("/workflows/complex-branching.xml");
        assertNotNull(xmlStream, "Complex workflow XML should exist");

        String html = WorkflowXsltTransformer.transformToGraphString(xmlStream);

        assertNotNull(html, "HTML output should not be null");
        assertTrue(html.contains("complex-branching"), "Should contain workflow name");
        assertTrue(html.contains("init"), "Should contain init state");
        assertTrue(html.contains("state_A"), "Should contain state_A");
        assertTrue(html.contains("state_B"), "Should contain state_B");
        assertTrue(html.contains("checker"), "Should contain checker actor");
        assertTrue(html.contains("processor"), "Should contain processor actor");
        assertTrue(html.contains("transition-box"), "Should contain transition box CSS class");
    }

    /**
     * Test 7: Transform multi-action workflow.
     */
    @Test
    @DisplayName("Should transform multi-action workflow to HTML")
    public void testTransformMultiActionWorkflow() throws Exception {
        InputStream xmlStream = getClass().getResourceAsStream("/workflows/multi-action.xml");
        assertNotNull(xmlStream, "Multi-action workflow XML should exist");

        String htmlTable = WorkflowXsltTransformer.transformToTableString(xmlStream);

        assertNotNull(htmlTable, "HTML output should not be null");
        assertTrue(htmlTable.contains("multi-action-workflow"), "Should contain workflow name");
        // Arguments are now in separate <arg> elements
        assertTrue(htmlTable.contains("5") && htmlTable.contains("3"), "Should contain first action arguments");
        assertTrue(htmlTable.contains("2") && htmlTable.contains("4"), "Should contain second action arguments");

        // Test graph view
        xmlStream = getClass().getResourceAsStream("/workflows/multi-action.xml");
        String htmlGraph = WorkflowXsltTransformer.transformToGraphString(xmlStream);

        assertNotNull(htmlGraph, "HTML graph output should not be null");
        assertTrue(htmlGraph.contains("multi-action-workflow"), "Should contain workflow name");
    }

    /**
     * Test 8: Verify table view HTML structure.
     */
    @Test
    @DisplayName("Should generate valid HTML table structure")
    public void testTableViewHtmlStructure() throws Exception {
        InputStream xmlStream = getClass().getResourceAsStream("/workflows/simple-math.xml");
        String html = WorkflowXsltTransformer.transformToTableString(xmlStream);

        // Check for essential HTML elements
        assertTrue(html.contains("<!DOCTYPE"), "Should have DOCTYPE declaration");
        assertTrue(html.contains("<head>"), "Should have head section");
        assertTrue(html.contains("<style>"), "Should have style section");
        assertTrue(html.contains("<body>"), "Should have body section");
        assertTrue(html.contains("<thead>"), "Should have table header");
        assertTrue(html.contains("<tbody>"), "Should have table body");
        assertTrue(html.contains("From State"), "Should have 'From State' column");
        assertTrue(html.contains("To State"), "Should have 'To State' column");
        assertTrue(html.contains("Actor"), "Should have 'Actor' column");
        assertTrue(html.contains("Method"), "Should have 'Method' column");
    }

    /**
     * Test 9: Verify graph view HTML structure.
     */
    @Test
    @DisplayName("Should generate valid HTML graph structure")
    public void testGraphViewHtmlStructure() throws Exception {
        InputStream xmlStream = getClass().getResourceAsStream("/workflows/simple-math.xml");
        String html = WorkflowXsltTransformer.transformToGraphString(xmlStream);

        // Check for essential HTML elements
        assertTrue(html.contains("<!DOCTYPE"), "Should have DOCTYPE declaration");
        assertTrue(html.contains("<head>"), "Should have head section");
        assertTrue(html.contains("<style>"), "Should have style section");
        assertTrue(html.contains("<body>"), "Should have body section");
        assertTrue(html.contains("graph-container"), "Should have graph container");
        assertTrue(html.contains("state-node"), "Should have state nodes");
        assertTrue(html.contains("transition-box"), "Should have transition boxes");
    }

    /**
     * Test 10: Verify CSS styling is included.
     */
    @Test
    @DisplayName("Should include CSS styling in output")
    public void testCssStylingIncluded() throws Exception {
        InputStream xmlStream = getClass().getResourceAsStream("/workflows/simple-math.xml");
        String htmlTable = WorkflowXsltTransformer.transformToTableString(xmlStream);

        assertTrue(htmlTable.contains("font-family"), "Should contain CSS font-family");
        assertTrue(htmlTable.contains("background-color"), "Should contain CSS background-color");
        assertTrue(htmlTable.contains("border"), "Should contain CSS border");

        xmlStream = getClass().getResourceAsStream("/workflows/simple-math.xml");
        String htmlGraph = WorkflowXsltTransformer.transformToGraphString(xmlStream);

        assertTrue(htmlGraph.contains("font-family"), "Should contain CSS font-family");
        assertTrue(htmlGraph.contains("gradient"), "Should contain CSS gradient");
    }
}
