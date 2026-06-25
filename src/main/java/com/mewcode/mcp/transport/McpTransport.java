package com.mewcode.mcp.transport;

import com.mewcode.mcp.JsonRpcMessage;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Transport abstraction for MCP protocol communication.
 *
 * <p>Implementations handle the actual I/O — stdio subprocess,
 * Streamable HTTP, etc.
 */
public interface McpTransport {

    /** Establish the transport channel. */
    void start() throws IOException;

    /**
     * Send a request and return a future that completes with the matching response.
     * The future fails with {@link java.util.concurrent.TimeoutException} if no response
     * arrives within the configured timeout.
     */
    CompletableFuture<JsonRpcMessage> sendRequest(JsonRpcMessage.Request request);

    /**
     * Send a notification (fire-and-forget, no response expected).
     */
    void sendNotification(JsonRpcMessage.Notification notification);

    /**
     * Register a handler for unsolicited messages from the server
     * (e.g. {@code notifications/tools/list_changed}).
     */
    void setMessageHandler(Consumer<JsonRpcMessage> handler);

    /** Tear down the transport, release resources. */
    void close();

    /** Whether the transport channel is currently open. */
    boolean isConnected();
}
