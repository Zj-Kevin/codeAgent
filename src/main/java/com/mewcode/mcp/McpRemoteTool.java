package com.mewcode.mcp;

import com.mewcode.tool.Tool;
import com.mewcode.tool.ToolResult;

import java.util.Map;
import java.util.Set;

/**
 * Adapts an MCP remote tool to MewCode's {@link Tool} interface.
 *
 * <p>The tool name is normalized to {@code mcp__<server>__<original>}
 * to avoid collisions with built-in tools and other servers' tools.
 * Illegal characters are replaced with underscores, and the name
 * is truncated to 64 characters.
 */
public class McpRemoteTool implements Tool {

    private static final int MAX_NAME_LENGTH = 64;

    private final String name;
    private final String description;
    private final Map<String, Object> inputSchema;
    private final McpClient client;
    private final String originalName;
    private final Set<String> categories;

    public McpRemoteTool(McpClient client, String originalName, String description,
                         Map<String, Object> inputSchema, boolean destructiveHint, boolean readOnlyHint) {
        this.client = client;
        this.originalName = originalName;
        this.description = truncateDescription(description);
        this.inputSchema = inputSchema != null ? inputSchema : Map.of();

        // Build categories from annotations
        var cats = new java.util.LinkedHashSet<String>();
        if (destructiveHint) cats.add("write");
        if (readOnlyHint) cats.add("read-only");
        this.categories = Set.copyOf(cats);

        // Normalize: mcp__<server>__<tool>
        this.name = normalizeName(client.serverName(), originalName);
    }

    @Override
    public String name() { return name; }

    @Override
    public String description() { return description; }

    @Override
    public Map<String, Object> inputSchema() { return inputSchema; }

    @Override
    public ToolResult execute(Map<String, Object> params) {
        return client.callTool(originalName, params);
    }

    @Override
    public String source() {
        return "mcp:" + client.serverName();
    }

    @Override
    public Set<String> categories() {
        return categories;
    }

    // ── Name normalization ────────────────────────────────────

    static String normalizeName(String serverName, String toolName) {
        String combined = "mcp__" + serverName + "__" + toolName;
        // Replace any character that is not [a-zA-Z0-9_-] with underscore
        combined = combined.replaceAll("[^a-zA-Z0-9_\\-]", "_");
        // Truncate to max length
        if (combined.length() > MAX_NAME_LENGTH) {
            combined = combined.substring(0, MAX_NAME_LENGTH);
        }
        return combined;
    }

    private static String truncateDescription(String desc) {
        if (desc == null) return "";
        if (desc.length() > 2048) {
            return desc.substring(0, 2048) + "...";
        }
        return desc;
    }
}
