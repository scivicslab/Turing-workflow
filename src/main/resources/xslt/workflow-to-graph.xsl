<?xml version="1.0" encoding="UTF-8"?>
<xsl:stylesheet version="1.0" xmlns:xsl="http://www.w3.org/1999/XSL/Transform">
    <xsl:output method="html" indent="yes" encoding="UTF-8" doctype-public="-//W3C//DTD HTML 4.01//EN"/>

    <xsl:key name="transitions-by-from" match="transition" use="@from"/>

    <xsl:template match="/workflow">
        <html>
            <head>
                <title>Workflow Graph: <xsl:value-of select="@name"/></title>
                <meta charset="UTF-8"/>
                <style>
                    body {
                        font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, 'Helvetica Neue', Arial, sans-serif;
                        margin: 20px;
                        background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
                        min-height: 100vh;
                        padding: 20px;
                    }
                    .container {
                        max-width: 1400px;
                        margin: 0 auto;
                        background-color: white;
                        padding: 30px;
                        border-radius: 12px;
                        box-shadow: 0 10px 40px rgba(0,0,0,0.2);
                    }
                    h1 {
                        color: #333;
                        border-bottom: 4px solid #667eea;
                        padding-bottom: 15px;
                        margin-bottom: 30px;
                        font-size: 32px;
                    }
                    .workflow-info {
                        background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
                        color: white;
                        padding: 20px;
                        border-radius: 8px;
                        margin-bottom: 30px;
                        display: flex;
                        justify-content: space-around;
                        flex-wrap: wrap;
                    }
                    .info-item {
                        text-align: center;
                        padding: 10px;
                    }
                    .info-label {
                        font-size: 12px;
                        opacity: 0.9;
                        text-transform: uppercase;
                        letter-spacing: 1px;
                    }
                    .info-value {
                        font-size: 28px;
                        font-weight: bold;
                        margin-top: 5px;
                    }
                    .graph-container {
                        display: flex;
                        flex-direction: column;
                        gap: 25px;
                    }
                    .state-node {
                        background: linear-gradient(135deg, #e3f2fd 0%, #bbdefb 100%);
                        border: 3px solid #2196F3;
                        border-radius: 12px;
                        padding: 20px;
                        box-shadow: 0 4px 12px rgba(33, 150, 243, 0.2);
                        transition: all 0.3s ease;
                    }
                    .state-node:hover {
                        transform: translateY(-2px);
                        box-shadow: 0 8px 20px rgba(33, 150, 243, 0.3);
                    }
                    .state-header {
                        display: flex;
                        align-items: center;
                        margin-bottom: 15px;
                        padding-bottom: 12px;
                        border-bottom: 2px solid #2196F3;
                    }
                    .state-icon {
                        font-size: 32px;
                        margin-right: 15px;
                    }
                    .state-name {
                        font-size: 24px;
                        font-weight: bold;
                        color: #1565c0;
                        font-family: 'Courier New', monospace;
                    }
                    .transition-count {
                        margin-left: auto;
                        background-color: #2196F3;
                        color: white;
                        padding: 5px 15px;
                        border-radius: 20px;
                        font-size: 14px;
                        font-weight: bold;
                    }
                    .transitions {
                        display: grid;
                        grid-template-columns: repeat(auto-fill, minmax(350px, 1fr));
                        gap: 15px;
                    }
                    .transition-box {
                        background-color: white;
                        border: 2px solid #e0e0e0;
                        border-radius: 8px;
                        padding: 15px;
                        transition: all 0.2s ease;
                    }
                    .transition-box:hover {
                        border-color: #4CAF50;
                        box-shadow: 0 2px 8px rgba(76, 175, 80, 0.2);
                    }
                    .transition-header {
                        display: flex;
                        align-items: center;
                        margin-bottom: 12px;
                        padding-bottom: 10px;
                        border-bottom: 1px solid #e0e0e0;
                    }
                    .arrow {
                        color: #4CAF50;
                        font-weight: bold;
                        font-size: 20px;
                        margin-right: 10px;
                    }
                    .to-state {
                        font-weight: bold;
                        color: #2e7d32;
                        font-family: 'Courier New', monospace;
                        font-size: 18px;
                    }
                    .actions {
                        display: flex;
                        flex-direction: column;
                        gap: 8px;
                    }
                    .action-item {
                        display: flex;
                        align-items: flex-start;
                        padding: 8px;
                        background-color: #f5f5f5;
                        border-radius: 5px;
                        font-size: 14px;
                    }
                    .action-icon {
                        margin-right: 8px;
                        font-size: 16px;
                    }
                    .action-actor {
                        color: #1976d2;
                        font-weight: bold;
                        margin-right: 5px;
                    }
                    .action-method {
                        color: #388e3c;
                        font-family: 'Courier New', monospace;
                        margin-right: 5px;
                    }
                    .action-args {
                        color: #666;
                        font-style: italic;
                        font-family: 'Courier New', monospace;
                        margin-left: 5px;
                    }
                    .empty-arg {
                        color: #999;
                        font-size: 11px;
                    }
                    .legend {
                        background-color: #f9f9f9;
                        border: 1px solid #ddd;
                        border-radius: 8px;
                        padding: 20px;
                        margin-top: 30px;
                    }
                    .legend-title {
                        font-weight: bold;
                        margin-bottom: 15px;
                        font-size: 18px;
                        color: #333;
                    }
                    .legend-items {
                        display: flex;
                        flex-wrap: wrap;
                        gap: 20px;
                    }
                    .legend-item {
                        display: flex;
                        align-items: center;
                        gap: 8px;
                    }
                    .legend-color {
                        width: 20px;
                        height: 20px;
                        border-radius: 4px;
                        border: 2px solid #ddd;
                    }
                    .color-state { background-color: #e3f2fd; }
                    .color-transition { background-color: white; }
                    .color-actor { background-color: #1976d2; width: 50px; }
                    .color-method { background-color: #388e3c; width: 50px; }
                    footer {
                        margin-top: 30px;
                        padding-top: 20px;
                        border-top: 2px solid #ddd;
                        color: #666;
                        font-size: 12px;
                        text-align: center;
                    }
                </style>
            </head>
            <body>
                <div class="container">
                    <h1>🔀 Workflow Graph: <xsl:value-of select="@name"/></h1>

                    <div class="workflow-info">
                        <div class="info-item">
                            <div class="info-label">States</div>
                            <div class="info-value">
                                <xsl:value-of select="count(steps/transition[not(@from = preceding-sibling::transition/@from)]) + 1"/>
                            </div>
                        </div>
                        <div class="info-item">
                            <div class="info-label">Transitions</div>
                            <div class="info-value">
                                <xsl:value-of select="count(steps/transition)"/>
                            </div>
                        </div>
                        <div class="info-item">
                            <div class="info-label">Actions</div>
                            <div class="info-value">
                                <xsl:value-of select="count(steps/transition/action)"/>
                            </div>
                        </div>
                    </div>

                    <div class="graph-container">
                        <xsl:for-each select="steps/transition[generate-id() = generate-id(key('transitions-by-from', @from)[1])]">
                            <xsl:sort select="@from"/>
                            <xsl:variable name="currentFrom" select="@from"/>

                            <div class="state-node">
                                <div class="state-header">
                                    <span class="state-icon">📍</span>
                                    <span class="state-name">
                                        <xsl:value-of select="$currentFrom"/>
                                    </span>
                                    <span class="transition-count">
                                        <xsl:value-of select="count(key('transitions-by-from', $currentFrom))"/> transitions
                                    </span>
                                </div>

                                <div class="transitions">
                                    <xsl:for-each select="key('transitions-by-from', $currentFrom)">
                                        <div class="transition-box">
                                            <div class="transition-header">
                                                <span class="arrow">→</span>
                                                <span class="to-state">
                                                    <xsl:value-of select="@to"/>
                                                </span>
                                            </div>

                                            <div class="actions">
                                                <xsl:for-each select="action">
                                                    <div class="action-item">
                                                        <span class="action-icon">⚙️</span>
                                                        <div>
                                                            <span class="action-actor">
                                                                <xsl:value-of select="@actor"/>
                                                            </span>
                                                            <span>.</span>
                                                            <span class="action-method">
                                                                <xsl:value-of select="@method"/>()
                                                            </span>
                                                            <xsl:if test="normalize-space(.) != ''">
                                                                <span class="action-args">
                                                                    (<xsl:value-of select="."/>)
                                                                </span>
                                                            </xsl:if>
                                                            <xsl:if test="normalize-space(.) = ''">
                                                                <span class="empty-arg">(empty)</span>
                                                            </xsl:if>
                                                        </div>
                                                    </div>
                                                </xsl:for-each>
                                            </div>
                                        </div>
                                    </xsl:for-each>
                                </div>
                            </div>
                        </xsl:for-each>
                    </div>

                    <div class="legend">
                        <div class="legend-title">📖 Legend</div>
                        <div class="legend-items">
                            <div class="legend-item">
                                <div class="legend-color color-state"></div>
                                <span>State Node</span>
                            </div>
                            <div class="legend-item">
                                <div class="legend-color color-transition"></div>
                                <span>Transition</span>
                            </div>
                            <div class="legend-item">
                                <span class="action-actor">Blue Text</span>
                                <span>= Actor Name</span>
                            </div>
                            <div class="legend-item">
                                <span class="action-method">Green Text</span>
                                <span>= Method Name</span>
                            </div>
                        </div>
                    </div>

                    <footer>
                        Generated by POJO-actor Workflow XSLT Transformer (Graph View) |
                        <xsl:value-of select="count(steps/transition)"/> transitions,
                        <xsl:value-of select="count(steps/transition/action)"/> actions
                    </footer>
                </div>
            </body>
        </html>
    </xsl:template>

</xsl:stylesheet>
