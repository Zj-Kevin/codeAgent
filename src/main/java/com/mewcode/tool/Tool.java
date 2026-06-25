
package com.mewcode.tool;

import java.util.Map;

/**
 * Unified interface for all tools.
 *
 * <p>Each tool exposes its metadata (name, description, JSON Schema parameters)
 * and an execute method that takes resolved arguments and returns a structured result.
 */
public interface Tool {

    /** Unique tool name, used in API tool definitions. */
    String name();

    /** Human-readable description shown to the model. */
    String description();

    /** JSON Schema of the parameters as a Map (serialized to JSON for the API). */
    Map<String, Object> inputSchema();

    /** Execute the tool with resolved arguments. Must not throw — return ToolResult.fail() on error. */
    ToolResult execute(Map<String, Object> params);

    /** Where this tool came from. Default {@code "builtin"}. */
    default String source() { return "builtin"; }

    /** Categories for SecurityManager classification. e.g. {@code "write"}, {@code "file"}, {@code "read-only"}. */
    default java.util.Set<String> categories() { return java.util.Set.of(); }
}
