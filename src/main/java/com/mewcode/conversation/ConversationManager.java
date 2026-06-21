package com.mewcode.conversation;

import com.mewcode.provider.Message;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Manages the in-memory conversation state.
 *
 * <p>Handles message accumulation, context truncation, and
 * provides the full history for LLM requests.
 */
@Component
public class ConversationManager {

    /** Rough token estimate: ~4 chars per token. Conservative threshold. */
    private static final int MAX_CONTEXT_TOKENS = 100_000;
    private static final int CHARS_PER_TOKEN_ESTIMATE = 4;
    private static final int MAX_CHARS = MAX_CONTEXT_TOKENS * CHARS_PER_TOKEN_ESTIMATE;

    private final List<Message> messages = new ArrayList<>();
    private boolean truncated = false;

    /**
     * Add a user message to the conversation.
     */
    public void addUserMessage(String content) {
        messages.add(Message.user(content));
    }

    /**
     * Add an assistant reply (with optional thinking) to the conversation.
     */
    public void addAssistantMessage(String content, String thinking) {
        messages.add(Message.assistant(content, thinking));
    }

    /**
     * Add a pre-built message to the conversation.
     */
    public void addMessage(Message msg) {
        messages.add(msg);
    }

    /**
     * Add an assistant reply without thinking.
     */
    public void addAssistantMessage(String content) {
        addAssistantMessage(content, null);
    }

    /**
     * Returns the full message list (immutable copy).
     */
    public List<Message> getMessages() {
        return Collections.unmodifiableList(new ArrayList<>(messages));
    }

    /**
     * Returns the full history for an LLM request, applying truncation if needed.
     *
     * <p>If the total estimated tokens exceed the limit, the oldest non-system
     * messages are removed and a system notice is prepended.
     */
    public List<Message> getHistoryForRequest() {
        int totalChars = messages.stream()
            .mapToInt(m -> (m.content() != null ? m.content().length() : 0))
            .sum();

        if (totalChars <= MAX_CHARS) {
            return new ArrayList<>(messages);
        }

        // Truncate: keep latest messages that fit within limit
        var result = new ArrayList<Message>();
        int running = 0;

        // Iterate from the end
        for (int i = messages.size() - 1; i >= 0; i--) {
            var msg = messages.get(i);
            int msgLen = msg.content() != null ? msg.content().length() : 0;
            if (running + msgLen > MAX_CHARS) {
                break;
            }
            running += msgLen;
            result.addFirst(msg);
        }

        // Prepend truncation notice
        int removed = messages.size() - result.size();
        if (removed > 0) {
            result.addFirst(Message.system(
                "[上下文已截断] 最早 " + removed + " 条消息已移除"));
            truncated = true;
        }

        return result;
    }

    /**
     * Whether this conversation has been truncated.
     */
    public boolean isTruncated() {
        return truncated;
    }

    /**
     * Clear the conversation.
     */
    public void clear() {
        messages.clear();
        truncated = false;
    }

    /**
     * Number of messages in the conversation.
     */
    public int size() {
        return messages.size();
    }
}
