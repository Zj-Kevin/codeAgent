package com.mewcode.provider;

/**
 * A single chunk of streamed output from the LLM.
 *
 * <p>For tool calls, the content field carries JSON fragments:
 * <ul>
 *   <li>{@code TOOL_CALL_START} — {@code {"id":"toolu_xxx","name":"read_file"}}</li>
 *   <li>{@code TOOL_CALL_DELTA} — partial JSON argument string</li>
 *   <li>{@code TOOL_CALL_END} — content is null</li>
 * </ul>
 */
public record StreamChunk(Type type, String content) {

    public enum Type {
        /** Regular text content */
        TEXT,
        /** Extended thinking / reasoning content */
        THINKING,
        /** An error occurred */
        ERROR,
        /** Stream completed normally */
        DONE,
        /** Tool call started — content = JSON with id + name */
        TOOL_CALL_START,
        /** Tool call argument fragment — content = partial JSON */
        TOOL_CALL_DELTA,
        /** Tool call arguments complete */
        TOOL_CALL_END
    }

    public static StreamChunk text(String content) {
        return new StreamChunk(Type.TEXT, content);
    }

    public static StreamChunk thinking(String content) {
        return new StreamChunk(Type.THINKING, content);
    }

    public static StreamChunk error(String message) {
        return new StreamChunk(Type.ERROR, message);
    }

    public static StreamChunk done() {
        return new StreamChunk(Type.DONE, null);
    }

    public static StreamChunk toolCallStart(String id, String name) {
        return new StreamChunk(Type.TOOL_CALL_START,
            "{\"id\":\"" + id + "\",\"name\":\"" + name + "\"}");
    }

    public static StreamChunk toolCallDelta(String jsonFragment) {
        return new StreamChunk(Type.TOOL_CALL_DELTA, jsonFragment);
    }

    public static StreamChunk toolCallEnd() {
        return new StreamChunk(Type.TOOL_CALL_END, null);
    }
}
