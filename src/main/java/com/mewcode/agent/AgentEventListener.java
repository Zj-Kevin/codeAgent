package com.mewcode.agent;

import com.mewcode.tool.ToolResult;

/**
 * Callback interface for consuming Agent Loop events.
 */
public interface AgentEventListener {

    default void onThinking(String text) {}
    default void onTextDelta(String text) {}
    default void onToolCallStart(String id, String name) {}
    default void onToolResult(String id, String name, ToolResult result) {}
    default void onRoundEnd(int round) {}
    default void onError(String message) {}
    default void onDone() {}

    /**
     * A tool needs human approval before execution.
     * The listener should block until the user decides, then return the decision.
     * @return ALLOW to execute, DENY to reject, SAVE to allow + persist permanently
     */
    enum AskDecision { ALLOW, DENY, SAVE }
    default AskDecision onToolAsk(String tool, String value) { return AskDecision.ALLOW; }
}
