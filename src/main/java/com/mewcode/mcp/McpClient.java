package com.mewcode.mcp;

import com.mewcode.mcp.transport.McpTransport;
import com.mewcode.tool.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Manages a single MCP server session: handshake, tool discovery, and tool invocation.
 *
 * <p>Lifecycle:
 * <ol>
 *   <li>{@link #connect()} — {@code initialize} → await response → {@code notifications/initialized}</li>
 *   <li>{@link #listTools()} — discover available tools</li>
 *   <li>{@link #callTool(String, Map)} — invoke a tool by name</li>
 *   <li>{@link #disconnect()} — tear down transport</li>
 * </ol>
 *
 * <p>Async matching is handled by the transport layer.
 * This client simply awaits the {@code CompletableFuture} returned by {@link McpTransport#sendRequest}.
 */
public class McpClient {

    private static final Logger log = LoggerFactory.getLogger(McpClient.class);
    private static final String PROTOCOL_VERSION = "2025-06-18";

    private final String serverName;
    private final McpTransport transport;
    private final int startupTimeoutSec;
    private final int toolTimeoutSec;

    private final AtomicInteger nextId = new AtomicInteger(1);

    public enum State { PENDING, CONNECTED, FAILED, NEEDS_AUTH, DISABLED }

    private volatile State state = State.PENDING;

    public McpClient(String serverName, McpTransport transport, int startupTimeoutSec, int toolTimeoutSec) {
        this.serverName = serverName;
        this.transport = transport;
        this.startupTimeoutSec = startupTimeoutSec;
        this.toolTimeoutSec = toolTimeoutSec;

        // Hook into transport for unsolicited server messages
        transport.setMessageHandler(this::handleServerMessage);
    }

    public String serverName() { return serverName; }
    public State state() { return state; }

    // ── Session handshake ─────────────────────────────────────

    /**
     * Establish the connection and complete the MCP initialize handshake.
     *
     * @throws IOException if transport fails to start
     * @throws RuntimeException if handshake times out or protocol mismatch
     */
    public void connect() throws IOException {
        state = State.PENDING;
        transport.start();

        try {
            // Phase 1: initialize request
            var initReq = new JsonRpcMessage.Request(nextId(), "initialize", Map.of(
                "protocolVersion", PROTOCOL_VERSION,
                "clientInfo", Map.of("name", "MewCode", "version", "0.1.0"),
                "capabilities", Map.of()
            ));

            var future = transport.sendRequest(initReq);
            var response = future.get(startupTimeoutSec, TimeUnit.SECONDS);

            if (response instanceof JsonRpcMessage.Error err) {
                state = State.FAILED;
                throw new RuntimeException("Initialize failed: [" + err.code() + "] " + err.message());
            }

            if (response instanceof JsonRpcMessage.Response resp) {
                @SuppressWarnings("unchecked")
                var result = (Map<String, Object>) resp.result();
                String serverVersion = (String) result.getOrDefault("protocolVersion", "unknown");
                if (!PROTOCOL_VERSION.equals(serverVersion)) {
                    log.warn("MCP server '{}' protocol version {} differs from client {}",
                        serverName, serverVersion, PROTOCOL_VERSION);
                }
            }

            // Phase 2: send initialized notification
            transport.sendNotification(
                new JsonRpcMessage.Notification("notifications/initialized", Map.of()));

            state = State.CONNECTED;
            log.info("MCP client '{}' connected successfully", serverName);

        } catch (TimeoutException e) {
            state = State.FAILED;
            throw new RuntimeException("Initialize handshake timed out after " + startupTimeoutSec + "s");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            state = State.FAILED;
            throw new RuntimeException("Initialize handshake interrupted");
        } catch (java.util.concurrent.ExecutionException e) {
            state = State.FAILED;
            throw new RuntimeException("Initialize handshake failed: " + e.getCause().getMessage());
        }
    }

    // ── Tool discovery ────────────────────────────────────────

    /** Definition of a tool as returned by the MCP server. */
    public record ToolDef(String name, String description, Map<String, Object> inputSchema,
                          boolean destructiveHint, boolean readOnlyHint) {
        @SuppressWarnings("unchecked")
        static ToolDef fromMap(Map<String, Object> map) {
            String name = (String) map.get("name");
            String description = (String) map.getOrDefault("description", "");
            // Truncate description to 2048 chars
            if (description != null && description.length() > 2048) {
                description = description.substring(0, 2048) + "...";
            }
            Map<String, Object> schema = (Map<String, Object>) map.getOrDefault("inputSchema", Map.of());
            Map<String, Object> annotations = (Map<String, Object>) map.get("annotations");
            boolean destructive = annotations != null && Boolean.TRUE.equals(annotations.get("destructiveHint"));
            boolean readOnly = annotations != null && Boolean.TRUE.equals(annotations.get("readOnlyHint"));
            return new ToolDef(name, description, schema, destructive, readOnly);
        }
    }

    /**
     * Discover tools available on this server.
     *
     * @return list of tool definitions
     * @throws RuntimeException if the call fails
     */
    @SuppressWarnings("unchecked")
    public List<ToolDef> listTools() {
        if (state != State.CONNECTED) {
            throw new IllegalStateException("Client not connected (state=" + state + ")");
        }

        try {
            var req = new JsonRpcMessage.Request(nextId(), "tools/list", Map.of());
            var future = transport.sendRequest(req);
            var response = future.get(toolTimeoutSec, TimeUnit.SECONDS);

            if (response instanceof JsonRpcMessage.Error err) {
                throw new RuntimeException("tools/list failed: [" + err.code() + "] " + err.message());
            }

            if (response instanceof JsonRpcMessage.Response resp) {
                var result = (Map<String, Object>) resp.result();
                var tools = (List<Map<String, Object>>) result.get("tools");
                if (tools == null) return List.of();
                return tools.stream().map(ToolDef::fromMap).toList();
            }

            return List.of();
        } catch (TimeoutException e) {
            throw new RuntimeException("tools/list timed out after " + toolTimeoutSec + "s");
        } catch (Exception e) {
            throw new RuntimeException("tools/list failed: " + e.getMessage(), e);
        }
    }

    // ── Tool invocation ───────────────────────────────────────

    /**
     * Call a tool on the MCP server.
     *
     * @param toolName  original tool name (without mcp__ prefix)
     * @param arguments tool arguments
     * @return structured result
     */
    @SuppressWarnings("unchecked")
    public ToolResult callTool(String toolName, Map<String, Object> arguments) {
        if (state != State.CONNECTED) {
            return ToolResult.fail("MCP server '" + serverName + "' not connected");
        }

        try {
            var req = new JsonRpcMessage.Request(nextId(), "tools/call", Map.of(
                "name", toolName,
                "arguments", arguments != null ? arguments : Map.of()
            ));
            var future = transport.sendRequest(req);
            var response = future.get(toolTimeoutSec, TimeUnit.SECONDS);

            if (response instanceof JsonRpcMessage.Error err) {
                return ToolResult.fail("MCP tool error [" + err.code() + "]: " + err.message());
            }

            if (response instanceof JsonRpcMessage.Response resp) {
                var result = (Map<String, Object>) resp.result();
                var content = (List<Map<String, Object>>) result.get("content");
                return extractContent(content);
            }

            return ToolResult.fail("Unexpected response from MCP server");

        } catch (TimeoutException e) {
            return ToolResult.fail("MCP tool call '" + toolName + "' timed out after " + toolTimeoutSec + "s");
        } catch (Exception e) {
            return ToolResult.fail("MCP tool call failed: " + e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private ToolResult extractContent(List<Map<String, Object>> content) {
        if (content == null || content.isEmpty()) {
            return ToolResult.ok("");
        }

        StringBuilder sb = new StringBuilder();
        for (var item : content) {
            String type = (String) item.getOrDefault("type", "text");
            switch (type) {
                case "text" -> {
                    String text = (String) item.getOrDefault("text", "");
                    if (!text.isEmpty()) sb.append(text).append("\n");
                }
                case "image" -> {
                    String mimeType = (String) item.getOrDefault("mimeType", "image/png");
                    String data = (String) item.getOrDefault("data", "");
                    sb.append("[image: ").append(mimeType).append(" (").append(data.length()).append(" bytes)]\n");
                }
                case "resource" -> {
                    var resource = (Map<String, Object>) item.get("resource");
                    if (resource != null) {
                        String uri = (String) resource.getOrDefault("uri", "");
                        String text = (String) resource.getOrDefault("text", "");
                        sb.append("[resource: ").append(uri).append("]\n");
                        if (text != null && !text.isEmpty()) sb.append(text).append("\n");
                    }
                }
                default -> {
                    sb.append("[unknown content type: ").append(type).append("]\n");
                }
            }
        }

        String output = sb.toString().stripTrailing();
        return ToolResult.ok(output);
    }

    // ── Connection teardown ───────────────────────────────────

    public void disconnect() {
        state = State.DISABLED;
        transport.close();
        log.info("MCP client '{}' disconnected", serverName);
    }

    // ── Server-push messages ──────────────────────────────────

    private void handleServerMessage(JsonRpcMessage msg) {
        if (msg instanceof JsonRpcMessage.Notification notif) {
            switch (notif.method()) {
                case "notifications/tools/list_changed" ->
                    log.info("MCP server '{}' reported tools changed (v1 ignores)", serverName);
                default ->
                    log.debug("MCP server '{}' sent notification: {}", serverName, notif.method());
            }
        }
    }

    // ── Helpers ───────────────────────────────────────────────

    private int nextId() {
        return nextId.getAndIncrement();
    }
}
