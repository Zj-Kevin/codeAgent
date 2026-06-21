package com.mewcode.provider;

/**
 * A single message in a conversation.
 *
 * <p>The {@code thinking} field is also used to carry {@code tool_use_id}
 * for tool_result messages.
 */
public record Message(String role, String content, String thinking) {

    public static final String ROLE_USER = "user";
    public static final String ROLE_ASSISTANT = "assistant";
    public static final String ROLE_SYSTEM = "system";
    public static final String ROLE_TOOL = "tool";

    public Message {
        if (role == null || role.isBlank()) {
            throw new IllegalArgumentException("role must not be blank");
        }
        if (content == null) {
            content = "";
        }
    }

    public static Message user(String content) {
        return new Message(ROLE_USER, content, null);
    }

    public static Message assistant(String content) {
        return new Message(ROLE_ASSISTANT, content, null);
    }

    public static Message assistant(String content, String thinking) {
        return new Message(ROLE_ASSISTANT, content, thinking);
    }

    public static Message system(String content) {
        return new Message(ROLE_SYSTEM, content, null);
    }

    /**
     * Create a tool_use message (assistant role with structured content).
     * Providers convert this to their native format.
     *
     * @param toolUseJson JSON in the provider-native tool_use format
     */
    public static Message toolUse(String toolUseJson) {
        return new Message(ROLE_ASSISTANT, toolUseJson, null);
    }

    /**
     * Create a tool_result message.
     *
     * @param toolUseId  the tool call ID to match
     * @param resultText the execution result content
     */
    public static Message toolResult(String toolUseId, String resultText) {
        // role="tool" is recognized by OpenAI; Anthropic converts to "user" with content blocks
        return new Message("tool", resultText, toolUseId);
    }
}
