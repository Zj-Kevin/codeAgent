package com.mewcode.mcp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Map;

import static com.mewcode.mcp.JsonRpcMessage.*;

/**
 * Encodes and decodes JSON-RPC 2.0 messages using Jackson.
 *
 * <p>Discriminates message type by inspecting JSON node presence:
 * <ul>
 *   <li>Has {@code id} + {@code method} → {@link Request}</li>
 *   <li>Has {@code id} + {@code result} → {@link Response}</li>
 *   <li>Has {@code id} + {@code error} → {@link Error}</li>
 *   <li>Has {@code method} but no {@code id} → {@link Notification}</li>
 * </ul>
 */
public class JsonRpcCodec {

    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * Serialize any JSON-RPC message to compact JSON string.
     */
    public static String encode(JsonRpcMessage msg) {
        try {
            return JSON.writeValueAsString(toWire(msg));
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to encode JSON-RPC message", e);
        }
    }

    /**
     * Deserialize a JSON-RPC message from a JSON string.
     * Returns null if the input cannot be parsed.
     */
    public static JsonRpcMessage decode(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return parse(JSON.readTree(json));
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static JsonRpcMessage parse(com.fasterxml.jackson.databind.JsonNode node) {
        boolean hasId = node.has("id") && !node.get("id").isNull();
        boolean hasMethod = node.has("method");
        boolean hasResult = node.has("result");
        boolean hasError = node.has("error");

        if (hasId && hasMethod) {
            int id = node.get("id").asInt();
            String method = node.get("method").asText();
            Map<String, Object> params = node.has("params")
                ? JSON.convertValue(node.get("params"), Map.class) : Map.of();
            return new Request(id, method, params);
        }
        if (hasId && hasError) {
            int id = node.get("id").asInt();
            var errNode = node.get("error");
            int code = errNode.has("code") ? errNode.get("code").asInt() : -32603;
            String message = errNode.has("message") ? errNode.get("message").asText() : "Unknown error";
            Object data = errNode.has("data") ? JSON.convertValue(errNode.get("data"), Object.class) : null;
            return new JsonRpcMessage.Error(id, code, message, data);
        }
        if (hasId && hasResult) {
            int id = node.get("id").asInt();
            Map<String, Object> result = JSON.convertValue(node.get("result"), Map.class);
            return new Response(id, result);
        }
        if (hasMethod && !hasId) {
            String method = node.get("method").asText();
            Map<String, Object> params = node.has("params")
                ? JSON.convertValue(node.get("params"), Map.class) : Map.of();
            return new Notification(method, params);
        }
        return null;
    }

    // ── Wire format ───────────────────────────────────────────

    private static Map<String, Object> toWire(JsonRpcMessage msg) {
        return switch (msg) {
            case Request r -> {
                var m = new java.util.LinkedHashMap<String, Object>();
                m.put("jsonrpc", "2.0");
                m.put("id", r.id());
                m.put("method", r.method());
                m.put("params", r.params() != null ? r.params() : Map.of());
                yield m;
            }
            case Response r -> {
                var m = new java.util.LinkedHashMap<String, Object>();
                m.put("jsonrpc", "2.0");
                m.put("id", r.id());
                m.put("result", r.result() != null ? r.result() : Map.of());
                yield m;
            }
            case JsonRpcMessage.Error e -> {
                var m = new java.util.LinkedHashMap<String, Object>();
                m.put("jsonrpc", "2.0");
                m.put("id", e.id());
                var err = new java.util.LinkedHashMap<String, Object>();
                err.put("code", e.code());
                err.put("message", e.message());
                if (e.data() != null) err.put("data", e.data());
                m.put("error", err);
                yield m;
            }
            case Notification n -> {
                var m = new java.util.LinkedHashMap<String, Object>();
                m.put("jsonrpc", "2.0");
                m.put("method", n.method());
                m.put("params", n.params() != null ? n.params() : Map.of());
                yield m;
            }
        };
    }
}
