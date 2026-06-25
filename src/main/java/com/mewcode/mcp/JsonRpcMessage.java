package com.mewcode.mcp;

import java.util.Map;

/**
 * JSON-RPC 2.0 message types as a sealed interface.
 *
 * <p>Four variants:
 * <ul>
 *   <li>{@link Request} — client→server with {@code id}</li>
 *   <li>{@link Response} — server→client result</li>
 *   <li>{@link Error} — server→client error</li>
 *   <li>{@link Notification} — either direction, no {@code id}</li>
 * </ul>
 */
public sealed interface JsonRpcMessage
    permits JsonRpcMessage.Request, JsonRpcMessage.Response, JsonRpcMessage.Error, JsonRpcMessage.Notification {

    /** Outbound request with unique id. */
    record Request(int id, String method, Map<String, Object> params) implements JsonRpcMessage {
        public String jsonrpc() { return "2.0"; }
    }

    /** Successful response from server. */
    record Response(int id, Map<String, Object> result) implements JsonRpcMessage {
        public String jsonrpc() { return "2.0"; }
    }

    /** Error response from server. */
    record Error(int id, int code, String message, Object data) implements JsonRpcMessage {
        public String jsonrpc() { return "2.0"; }
    }

    /** Notification (no id, no response expected). */
    record Notification(String method, Map<String, Object> params) implements JsonRpcMessage {
        public String jsonrpc() { return "2.0"; }
    }

    // ── Standard JSON-RPC error codes ─────────────────────────

    int PARSE_ERROR      = -32700;
    int INVALID_REQUEST  = -32600;
    int METHOD_NOT_FOUND = -32601;
    int INVALID_PARAMS   = -32602;
    int INTERNAL_ERROR   = -32603;
}
