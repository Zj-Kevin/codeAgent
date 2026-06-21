package com.mewcode.provider;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mewcode.config.MewCodeProperties;
import com.mewcode.http.SseClient;
import com.mewcode.prompt.PromptBuilder;

import java.io.IOException;
import java.net.http.HttpClient;
import java.util.*;
import java.util.stream.Stream;

public abstract class OpenAICompatibleProvider implements LLMProvider {

    protected final MewCodeProperties props;
    protected final SseClient sseClient;
    protected final ObjectMapper json;
    protected final PromptBuilder promptBuilder;

    protected OpenAICompatibleProvider(MewCodeProperties props, HttpClient httpClient,
                                       ObjectMapper json, PromptBuilder promptBuilder) {
        this.props = props;
        this.sseClient = new SseClient(httpClient, props.getProvider());
        this.json = json;
        this.promptBuilder = promptBuilder;
    }

    protected abstract String defaultBaseUrl();

    @Override
    public ToolFormat toolFormat() { return ToolFormat.OPENAI; }

    @Override
    public Stream<StreamChunk> streamChat(List<Message> history, Message newMessage,
                                           List<Map<String, Object>> tools) {
        try {
            String baseUrl = props.getProvider().resolveBaseUrl();
            if (baseUrl == null || baseUrl.isBlank()) baseUrl = defaultBaseUrl();
            String url = baseUrl + "/v1/chat/completions";
            String apiKey = props.getProvider().resolveApiKey();

            var headers = new HashMap<String, String>();
            headers.put("Authorization", "Bearer " + apiKey);

            String body = buildRequestBody(history, newMessage, tools);

            return sseClient.stream(url, headers, body)
                .flatMap(dataLine -> {
                    if ("[DONE]".equals(dataLine.strip()))
                        return Stream.of(StreamChunk.done());
                    try {
                        return parseChunk(dataLine).stream();
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
    private List<StreamChunk> parseChunk(String dataLine) throws JsonProcessingException {
        var event = (Map<String, Object>) json.readValue(dataLine, Map.class);
        var chunks = new ArrayList<StreamChunk>();

        var choices = (List<Map<String, Object>>) event.get("choices");
        if (choices == null || choices.isEmpty()) return chunks;

        var choice = choices.getFirst();
        var delta = (Map<String, Object>) choice.get("delta");

        if (delta != null) {
            if (delta.containsKey("reasoning_content")) {
                String reasoning = (String) delta.get("reasoning_content");
                if (reasoning != null && !reasoning.isEmpty())
                    chunks.add(StreamChunk.thinking(reasoning));
            }
            if (delta.containsKey("content")) {
                String content = (String) delta.get("content");
                if (content != null && !content.isEmpty())
                    chunks.add(StreamChunk.text(content));
            }
            var toolCalls = (List<Map<String, Object>>) delta.get("tool_calls");
            if (toolCalls != null) {
                for (var tc : toolCalls) {
                    var func = (Map<String, Object>) tc.get("function");
                    if (func != null) {
                        String id = (String) tc.get("id");
                        String name = (String) func.get("name");
                        if (id != null && name != null)
                            chunks.add(StreamChunk.toolCallStart(id, name));
                        String args = (String) func.get("arguments");
                        if (args != null && !args.isEmpty())
                            chunks.add(StreamChunk.toolCallDelta(args));
                    }
                }
            }
        }

        String finishReason = (String) choice.get("finish_reason");
        if ("tool_calls".equals(finishReason)) chunks.add(StreamChunk.toolCallEnd());
        if (finishReason != null && !"tool_calls".equals(finishReason))
            chunks.add(StreamChunk.done());

        var error = (Map<String, Object>) event.get("error");
        if (error != null) chunks.add(StreamChunk.error(
            "API error: " + error.getOrDefault("message", "Unknown")));

        return chunks;
    }

    private String buildRequestBody(List<Message> history, Message newMessage,
                                     List<Map<String, Object>> tools) throws JsonProcessingException {
        var body = new HashMap<String, Object>();
        body.put("model", props.getProvider().getModel());
        body.put("stream", true);

        var messages = new ArrayList<Map<String, Object>>();

        // Stable system prompt as first system message
        var vars = promptBuilder.collectVars();
        messages.add(Map.of("role", "system",
            "content", promptBuilder.buildStablePrefix(vars, tools)));

        // Environment as second system message
        var envMsg = promptBuilder.buildEnvironmentMessage();
        messages.add(Map.of("role", "system", "content", envMsg.content()));

        for (var msg : history) messages.add(toOpenAIMessage(msg));
        messages.add(toOpenAIMessage(newMessage));
        body.put("messages", messages);

        if (tools != null && !tools.isEmpty()) {
            body.put("tools", tools);
            body.put("tool_choice", "auto");
        }
        return json.writeValueAsString(body);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> toOpenAIMessage(Message msg) {
        var map = new LinkedHashMap<String, Object>();
        if ("tool".equals(msg.role())) {
            map.put("role", "tool");
            map.put("tool_call_id", msg.thinking() != null ? msg.thinking() : "");
            map.put("content", msg.content() != null ? msg.content() : "");
            return map;
        }
        map.put("role", msg.role());
        if ("assistant".equals(msg.role()) && msg.content() != null
            && msg.content().contains("\"tool_use\"")) {
            try {
                var blocks = (List<Map<String, Object>>) json.readValue(msg.content(), List.class);
                var toolCalls = new ArrayList<Map<String, Object>>();
                for (var block : blocks) {
                    if ("tool_use".equals(block.get("type"))) {
                        var func = new LinkedHashMap<String, Object>();
                        func.put("name", block.get("name"));
                        func.put("arguments", json.writeValueAsString(block.get("input")));
                        var tc = new LinkedHashMap<String, Object>();
                        tc.put("id", block.get("id"));
                        tc.put("type", "function");
                        tc.put("function", func);
                        toolCalls.add(tc);
                    }
                }
                map.put("tool_calls", toolCalls);
                return map;
            } catch (JsonProcessingException ignored) {}
        }
        map.put("content", msg.content() != null ? msg.content() : "");
        return map;
    }
}
