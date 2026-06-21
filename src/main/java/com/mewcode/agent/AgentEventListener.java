package com.mewcode.agent;

import com.mewcode.tool.ToolResult;

/**
 * Callback interface for consuming Agent Loop events.
 *
 * <p>All methods are {@code default} no-ops — override only what you need.
 * Events are called synchronously from the agent thread.
 */
public interface AgentEventListener {

    /** A thinking/reasoning token was produced. */
    default void onThinking(String text) {}

    /** A text delta was produced (may be called many times per turn). */
    default void onTextDelta(String text) {}

    /** A tool call started. {@code id} is the tool use ID, {@code name} is the tool name. */
    default void onToolCallStart(String id, String name) {}

    /** A tool execution completed. */
    default void onToolResult(String id, String name, ToolResult result) {}

    /** A single round of the agent loop ended. {@code round} is 0-indexed. */
    default void onRoundEnd(int round) {}

    /** A non-fatal error occurred (max rounds reached, etc.). */
    default void onError(String message) {}

    /** The agent loop completed normally. */
    default void onDone() {}
}
