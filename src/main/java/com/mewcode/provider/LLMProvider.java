package com.mewcode.provider;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Unified interface for all LLM backends.
 */
public interface LLMProvider {

    /** Which API-native tool format this provider uses. */
    enum ToolFormat { ANTHROPIC, OPENAI }

    /**
     * Send a conversation to the model with optional tool definitions.
     */
    Stream<StreamChunk> streamChat(List<Message> history, Message newMessage,
                                   List<Map<String, Object>> tools);

    /** The tool format this provider natively expects. */
    default ToolFormat toolFormat() { return ToolFormat.ANTHROPIC; }
}
