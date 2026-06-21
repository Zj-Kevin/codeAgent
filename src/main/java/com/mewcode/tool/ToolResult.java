
package com.mewcode.tool;

/**
 * Structured result from a tool execution.
 *
 * <p>On success: {@code success=true}, content contains the tool output.
 * On failure: {@code success=false}, error describes what went wrong.
 *
 * <p>The error message is designed to be fed back to the model so it can adjust.
 */
public record ToolResult(boolean success, String content, String error) {

    public static ToolResult ok(String content) {
        return new ToolResult(true, content != null ? content : "", null);
    }

    public static ToolResult fail(String error) {
        return new ToolResult(false, "", error != null ? error : "Unknown error");
    }
}
