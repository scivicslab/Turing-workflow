<?xml version="1.0" encoding="UTF-8"?>
<xsl:stylesheet version="1.0" xmlns:xsl="http://www.w3.org/1999/XSL/Transform">
    <xsl:output method="html" indent="yes" encoding="UTF-8" doctype-public="-//W3C//DTD HTML 4.01//EN"/>

    <xsl:template match="/workflow">
        <html>
            <head>
                <title>Workflow: <xsl:value-of select="@name"/></title>
                <meta charset="UTF-8"/>
                <style>
                    body {
                        font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, 'Helvetica Neue', Arial, sans-serif;
                        margin: 20px;
                        background-color: #f5f5f5;
                    }
                    .container {
                        max-width: 1400px;
                        margin: 0 auto;
                        background-color: white;
                        padding: 30px;
                        border-radius: 8px;
                        box-shadow: 0 2px 4px rgba(0,0,0,0.1);
                    }
                    h1 {
                        color: #333;
                        border-bottom: 3px solid #4CAF50;
                        padding-bottom: 10px;
                        margin-bottom: 30px;
                    }
                    .workflow-info {
                        background-color: #e8f5e9;
                        padding: 15px;
                        border-radius: 5px;
                        margin-bottom: 20px;
                    }
                    .workflow-info strong {
                        color: #2e7d32;
                    }
                    table {
                        border-collapse: collapse;
                        width: 100%;
                        margin-top: 20px;
                        box-shadow: 0 1px 3px rgba(0,0,0,0.1);
                    }
                    th {
                        background-color: #4CAF50;
                        color: white;
                        padding: 14px;
                        text-align: left;
                        border: 1px solid #45a049;
                        font-weight: 600;
                        text-transform: uppercase;
                        font-size: 12px;
                        letter-spacing: 0.5px;
                    }
                    td {
                        padding: 12px;
                        border: 1px solid #ddd;
                        vertical-align: top;
                    }
                    tr:nth-child(even) {
                        background-color: #f9f9f9;
                    }
                    tr:hover {
                        background-color: #f1f8f4;
                    }
                    .row-number {
                        font-weight: bold;
                        color: #666;
                        text-align: center;
                        background-color: #f5f5f5;
                    }
                    .state-from {
                        background-color: #e3f2fd;
                        font-weight: bold;
                        color: #1565c0;
                        font-family: 'Courier New', monospace;
                    }
                    .state-to {
                        background-color: #c8e6c9;
                        font-weight: bold;
                        color: #2e7d32;
                        font-family: 'Courier New', monospace;
                    }
                    .actor {
                        color: #1976d2;
                        font-weight: bold;
                    }
                    .method {
                        color: #388e3c;
                        font-family: 'Courier New', monospace;
                        background-color: #f5f5f5;
                        padding: 2px 6px;
                        border-radius: 3px;
                    }
                    .argument {
                        color: #666;
                        font-style: italic;
                        font-family: 'Courier New', monospace;
                        font-size: 13px;
                    }
                    .empty-arg {
                        color: #999;
                        font-size: 11px;
                    }
                    .arrow {
                        color: #4CAF50;
                        font-weight: bold;
                        font-size: 18px;
                    }
                    footer {
                        margin-top: 30px;
                        padding-top: 20px;
                        border-top: 1px solid #ddd;
                        color: #666;
                        font-size: 12px;
                        text-align: center;
                    }
                </style>
            </head>
            <body>
                <div class="container">
                    <h1>📊 Workflow: <xsl:value-of select="@name"/></h1>

                    <div class="workflow-info">
                        <strong>Total Transitions:</strong> <xsl:value-of select="count(steps/transition)"/> |
                        <strong>Total Actions:</strong> <xsl:value-of select="count(steps/transition/action)"/>
                    </div>

                    <table>
                        <thead>
                            <tr>
                                <th style="width: 40px;">#</th>
                                <th style="width: 150px;">From State</th>
                                <th style="width: 50px;"></th>
                                <th style="width: 150px;">To State</th>
                                <th style="width: 150px;">Actor</th>
                                <th style="width: 200px;">Method</th>
                                <th>Argument</th>
                            </tr>
                        </thead>
                        <tbody>
                            <xsl:apply-templates select="steps/transition"/>
                        </tbody>
                    </table>

                    <footer>
                        Generated by POJO-actor Workflow XSLT Transformer |
                        <xsl:value-of select="count(steps/transition)"/> transitions,
                        <xsl:value-of select="count(steps/transition/action)"/> actions
                    </footer>
                </div>
            </body>
        </html>
    </xsl:template>

    <xsl:template match="transition">
        <xsl:variable name="transitionNumber" select="position()"/>
        <xsl:variable name="actionCount" select="count(action)"/>

        <xsl:for-each select="action">
            <tr>
                <!-- Row number (only for first action) -->
                <xsl:if test="position() = 1">
                    <td class="row-number" rowspan="{$actionCount}">
                        <xsl:value-of select="$transitionNumber"/>
                    </td>
                    <td class="state-from" rowspan="{$actionCount}">
                        <xsl:value-of select="../@from"/>
                    </td>
                    <td class="arrow" rowspan="{$actionCount}">→</td>
                    <td class="state-to" rowspan="{$actionCount}">
                        <xsl:value-of select="../@to"/>
                    </td>
                </xsl:if>

                <!-- Action information -->
                <td class="actor">
                    <xsl:value-of select="@actor"/>
                </td>
                <td class="method">
                    <xsl:value-of select="@method"/>()
                </td>
                <td class="argument">
                    <xsl:choose>
                        <xsl:when test="normalize-space(.) = ''">
                            <span class="empty-arg">(empty)</span>
                        </xsl:when>
                        <xsl:otherwise>
                            <xsl:value-of select="."/>
                        </xsl:otherwise>
                    </xsl:choose>
                </td>
            </tr>
        </xsl:for-each>
    </xsl:template>

</xsl:stylesheet>
