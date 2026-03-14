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

import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.stream.StreamResult;
import javax.xml.transform.stream.StreamSource;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.StringWriter;

/**
 * Utility class for transforming XML workflow definitions into HTML using XSLT.
 *
 * <p>This class provides methods to convert XML workflow files into human-readable
 * HTML visualizations. Two visualization styles are supported:</p>
 * <ul>
 *   <li><strong>Table View</strong>: Displays workflow transitions in a structured table format</li>
 *   <li><strong>Graph View</strong>: Displays workflow as a visual state transition graph</li>
 * </ul>
 *
 * <p><strong>Example Usage:</strong></p>
 * <pre>{@code
 * // Transform to table view
 * File xmlFile = new File("workflow.xml");
 * File htmlFile = new File("workflow-table.html");
 * WorkflowXsltTransformer.transformToTable(xmlFile, htmlFile);
 *
 * // Transform to graph view
 * File graphFile = new File("workflow-graph.html");
 * WorkflowXsltTransformer.transformToGraph(xmlFile, graphFile);
 *
 * // Transform to string
 * String htmlContent = WorkflowXsltTransformer.transformToTableString(xmlFile);
 * }</pre>
 *
 * @author devteam@scivicslab.com
 * @since 2.5.0
 */
public class WorkflowXsltTransformer {

    private static final String TABLE_XSLT_PATH = "/xslt/workflow-to-table.xsl";
    private static final String GRAPH_XSLT_PATH = "/xslt/workflow-to-graph.xsl";

    /**
     * Transforms an XML workflow file to HTML table view.
     *
     * @param xmlFile the input XML workflow file
     * @param outputFile the output HTML file
     * @throws TransformerException if an error occurs during transformation
     */
    public static void transformToTable(File xmlFile, File outputFile) throws TransformerException {
        InputStream xsltStream = WorkflowXsltTransformer.class.getResourceAsStream(TABLE_XSLT_PATH);
        if (xsltStream == null) {
            throw new TransformerException("XSLT resource not found: " + TABLE_XSLT_PATH);
        }
        transform(new StreamSource(xmlFile), new StreamSource(xsltStream), new StreamResult(outputFile));
    }

    /**
     * Transforms an XML workflow file to HTML graph view.
     *
     * @param xmlFile the input XML workflow file
     * @param outputFile the output HTML file
     * @throws TransformerException if an error occurs during transformation
     */
    public static void transformToGraph(File xmlFile, File outputFile) throws TransformerException {
        InputStream xsltStream = WorkflowXsltTransformer.class.getResourceAsStream(GRAPH_XSLT_PATH);
        if (xsltStream == null) {
            throw new TransformerException("XSLT resource not found: " + GRAPH_XSLT_PATH);
        }
        transform(new StreamSource(xmlFile), new StreamSource(xsltStream), new StreamResult(outputFile));
    }

    /**
     * Transforms an XML workflow input stream to HTML table view.
     *
     * @param xmlInput the input XML workflow stream
     * @param output the output stream for HTML
     * @throws TransformerException if an error occurs during transformation
     */
    public static void transformToTable(InputStream xmlInput, OutputStream output) throws TransformerException {
        InputStream xsltStream = WorkflowXsltTransformer.class.getResourceAsStream(TABLE_XSLT_PATH);
        if (xsltStream == null) {
            throw new TransformerException("XSLT resource not found: " + TABLE_XSLT_PATH);
        }
        transform(new StreamSource(xmlInput), new StreamSource(xsltStream), new StreamResult(output));
    }

    /**
     * Transforms an XML workflow input stream to HTML graph view.
     *
     * @param xmlInput the input XML workflow stream
     * @param output the output stream for HTML
     * @throws TransformerException if an error occurs during transformation
     */
    public static void transformToGraph(InputStream xmlInput, OutputStream output) throws TransformerException {
        InputStream xsltStream = WorkflowXsltTransformer.class.getResourceAsStream(GRAPH_XSLT_PATH);
        if (xsltStream == null) {
            throw new TransformerException("XSLT resource not found: " + GRAPH_XSLT_PATH);
        }
        transform(new StreamSource(xmlInput), new StreamSource(xsltStream), new StreamResult(output));
    }

    /**
     * Transforms an XML workflow file to HTML table view and returns as String.
     *
     * @param xmlFile the input XML workflow file
     * @return the HTML content as a String
     * @throws TransformerException if an error occurs during transformation
     */
    public static String transformToTableString(File xmlFile) throws TransformerException {
        InputStream xsltStream = WorkflowXsltTransformer.class.getResourceAsStream(TABLE_XSLT_PATH);
        if (xsltStream == null) {
            throw new TransformerException("XSLT resource not found: " + TABLE_XSLT_PATH);
        }
        StringWriter writer = new StringWriter();
        transform(new StreamSource(xmlFile), new StreamSource(xsltStream), new StreamResult(writer));
        return writer.toString();
    }

    /**
     * Transforms an XML workflow file to HTML graph view and returns as String.
     *
     * @param xmlFile the input XML workflow file
     * @return the HTML content as a String
     * @throws TransformerException if an error occurs during transformation
     */
    public static String transformToGraphString(File xmlFile) throws TransformerException {
        InputStream xsltStream = WorkflowXsltTransformer.class.getResourceAsStream(GRAPH_XSLT_PATH);
        if (xsltStream == null) {
            throw new TransformerException("XSLT resource not found: " + GRAPH_XSLT_PATH);
        }
        StringWriter writer = new StringWriter();
        transform(new StreamSource(xmlFile), new StreamSource(xsltStream), new StreamResult(writer));
        return writer.toString();
    }

    /**
     * Transforms an XML workflow input stream to HTML table view and returns as String.
     *
     * @param xmlInput the input XML workflow stream
     * @return the HTML content as a String
     * @throws TransformerException if an error occurs during transformation
     */
    public static String transformToTableString(InputStream xmlInput) throws TransformerException {
        InputStream xsltStream = WorkflowXsltTransformer.class.getResourceAsStream(TABLE_XSLT_PATH);
        if (xsltStream == null) {
            throw new TransformerException("XSLT resource not found: " + TABLE_XSLT_PATH);
        }
        StringWriter writer = new StringWriter();
        transform(new StreamSource(xmlInput), new StreamSource(xsltStream), new StreamResult(writer));
        return writer.toString();
    }

    /**
     * Transforms an XML workflow input stream to HTML graph view and returns as String.
     *
     * @param xmlInput the input XML workflow stream
     * @return the HTML content as a String
     * @throws TransformerException if an error occurs during transformation
     */
    public static String transformToGraphString(InputStream xmlInput) throws TransformerException {
        InputStream xsltStream = WorkflowXsltTransformer.class.getResourceAsStream(GRAPH_XSLT_PATH);
        if (xsltStream == null) {
            throw new TransformerException("XSLT resource not found: " + GRAPH_XSLT_PATH);
        }
        StringWriter writer = new StringWriter();
        transform(new StreamSource(xmlInput), new StreamSource(xsltStream), new StreamResult(writer));
        return writer.toString();
    }

    /**
     * Internal method to perform XSLT transformation.
     *
     * @param xmlSource the XML source
     * @param xsltSource the XSLT stylesheet source
     * @param result the transformation result
     * @throws TransformerException if an error occurs during transformation
     */
    private static void transform(StreamSource xmlSource, StreamSource xsltSource, StreamResult result)
            throws TransformerException {
        TransformerFactory factory = TransformerFactory.newInstance();
        Transformer transformer = factory.newTransformer(xsltSource);
        transformer.transform(xmlSource, result);
    }
}
