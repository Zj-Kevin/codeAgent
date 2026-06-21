package com.mewcode.provider;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mewcode.config.MewCodeProperties;
import com.mewcode.http.SseClient;
import com.mewcode.prompt.PromptBuilder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.http.HttpClient;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

@Component
@ConditionalOnProperty(name = "mewcode.provider.protocol", havingValue = "anthropic")
public class AnthropicProvider implements LLMProvider {

    private static final String ANTHROPIC_VERSION = "2023-06-01";

    private final MewCodeProperties props;
    private final SseClient sseClient;
    private final ObjectMapper json;
    private final PromptBuilder promptBuilder;

    public AnthropicProvider(MewCodeProperties props, HttpClient httpClient,
                             ObjectMapper json, PromptBuilder promptBuilder) {
        this.props = props;
        this.sseClient = new SseClient(httpClient, props.getProvider());
        this.json = json;
        this.promptBuilder = promptBuilder;
    }

    @Override
    public Stream<StreamChunk> streamChat(List<Message> history, Message newMessage,
                                           List<Map<String, Object>> tools) {
        try {
            String url = props.getProvider().resolveBaseUrl() + "/v1/messages";
            String apiKey = props.getProvider().resolveApiKey();

            var headers = new HashMap<String, String>();
            headers.put("x-api-key", apiKey);
            headers.put("anthropic-version", ANTHROPIC_VERSION);

            String body = buildRequestBody(history, newMessage, tools);

            var streamStarted = new AtomicBoolean(false);

            return sseClient.stream(url, headers, body)
                .flatMap(dataLine -> {
                    try {
                        @SuppressWarnings("unchecked")
                        var event = (Map<String, Object>) json.readValue(dataLine, Map.class);
                        return parseEvent(event, streamStarted).stream();
                    } catch (JsonProcessingException e) {
                        return Stream.of(StreamChunk.error("Parse error: " + e.getMessage()));
                    }
                })
                .takeWhile(chunk -> chunk.type() != StreamChunk.Type.DONE);

        } catch (IOException | InterruptedException e) {
            return Stream.of(StreamChunk.error(
                "Connection error: " + e.getClass().getSimpleName() + " - " + e.getMessage()));
        }
    }

    @SuppressWarnings("unchecked")
    private List<StreamChunk> parseEvent(Map<String, Object> event, AtomicBoolean streamStarted) {
        var chunks = new ArrayList<StreamChunk>();
        String eventType = (String) event.get("type");

        switch (eventType) {
            case "message_start" -> streamStarted.set(true);
            case "content_block_start" -> {
                var block = (Map<String, Object>) event.get("content_block");
                if (block != null) {
                    String blockType = (String) block.get("type");
                    if ("tool_use".equals(blockType)) {
                        chunks.add(StreamChunk.toolCallStart(
                            (String) block.get("id"), (String) block.get("name")));
                    }
                }
            }
            case "content_block_delta" -> {
                var delta = (Map<String, Object>) event.get("delta");
                if (delta == null) break;
                switch ((String) delta.get("type")) {
                    case "text_delta" -> {
                        String text = (String) delta.get("text");
                        if (text != null) chunks.add(StreamChunk.text(text));
                    }
                    case "thinking_delta" -> {
                        String thinking = (String) delta.get("thinking");
                        if (thinking != null) chunks.add(StreamChunk.thinking(thinking));
                    }
                    case "input_json_delta" -> {
                        String partial = (String) delta.get("partial_json");
                        if (partial != null) chunks.add(StreamChunk.toolCallDelta(partial));
                    }
                }
            }
            case "message_delta" -> {
                var delta = (Map<String, Object>) event.get("delta");
                if (delta != null && "tool_use".equals(delta.get("stop_reason"))) {
                    chunks.add(StreamChunk.toolCallEnd());
                }
            }
            case "message_stop" -> chunks.add(StreamChunk.done());
            case "error" -> {
                var errorBlock = (Map<String, Object>) event.get("error");
                chunks.add(StreamChunk.error("Anthropic error: "
                    + (errorBlock != null ? errorBlock.get("message") : "Unknown")));
            }
        }
        return chunks;
    }

    private String buildRequestBody(List<Message> history, Message newMessage,
                                     List<Map<String, Object>> tools) throws JsonProcessingException {
        var body = new HashMap<String, Object>();
        body.put("model", props.getProvider().getModel());
        body.put("max_tokens", 4096);
        body.put("stream", true);

        // Stable system prompt (cacheable prefix)
        var vars = promptBuilder.collectVars();
        body.put("system", promptBuilder.buildStablePrefix(vars, tools));

        // Messages
        var messages = new ArrayList<Map<String, Object>>();
        // Environment as first user message
        var envMsg = promptBuilder.buildEnvironmentMessage();
        messages.add(convertMessage(envMsg));
        for (var msg : history) messages.add(convertMessage(msg));
        messages.add(convertMessage(newMessage));
        body.put("messages", messages);

        if (tools != null && !tools.isEmpty()) {
            body.put("tools", tools);
            body.put("tool_choice", Map.of("type", "auto"));
        }
        return json.writeValueAsString(body);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> convertMessage(Message msg) {
        var map = new HashMap<String, Object>();
        if ("tool".equals(msg.role())) {
            map.put("role", "user");
            map.put("content", List.of(Map.of(
                "type", "tool_result",
                "tool_use_id", msg.thinking() != null ? msg.thinking() : "",
                "content", msg.content() != null ? msg.content() : "")));
            return map;
        }
        map.put("role", msg.role());
        if ("assistant".equals(msg.role()) && msg.content() != null
            && msg.content().startsWith("[") && msg.content().contains("\"type\":\"tool_use\"")) {
            try {
                map.put("content", json.readValue(msg.content(), List.class));
                return map;
            } catch (JsonProcessingException ignored) {}
        }
        if (msg.thinking() != null && !msg.thinking().isBlank()) {
            var content = new ArrayList<Map<String, Object>>();
            content.add(Map.of("type", "thinking", "thinking", msg.thinking()));
            content.add(Map.of("type", "text", "text", msg.content()));
            map.put("content", content);
        } else {
            map.put("content", msg.content());
        }
        return map;
    }

    @Override
    public ToolFormat toolFormat() { return ToolFormat.ANTHROPIC; }
}
