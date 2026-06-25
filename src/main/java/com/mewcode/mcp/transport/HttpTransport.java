package com.mewcode.mcp.transport;

import com.mewcode.mcp.JsonRpcCodec;
import com.mewcode.mcp.JsonRpcMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.function.Consumer;

/**
 * MCP transport over Streamable HTTP (2025 spec).
 *
 * <p>Sends JSON-RPC requests via POST to the server's MCP endpoint,
 * receives responses synchronously or via SSE streaming. Maintains
 * session identity via the {@code Mcp-Session-Id} header.
 *
 * <p>Each request gets a <em>fresh</em> timeout signal — never reuse
 * a stale {@code AbortSignal} across calls.
 */
public class HttpTransport implements McpTransport {

    private static final Logger log = LoggerFactory.getLogger(HttpTransport.class);

    private final String url;
    private final Map<String, String> headers;
    private final int timeoutSec;
    private final HttpClient httpClient;

    private String sessionId;
    private final List<Consumer<JsonRpcMessage>> messageHandlers = new CopyOnWriteArrayList<>();
    private volatile boolean connected;

    public HttpTransport(String url, Map<String, String> headers, int timeoutSec) {
        this.url = url;
        this.headers = headers != null ? headers : Map.of();
        this.timeoutSec = timeoutSec > 0 ? timeoutSec : 60;
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    }

    @Override
    public void start() throws IOException {
        // Verify server is reachable with a quick GET or OPTIONS
        // For MCP, we just set connected=true; first request will validate
        connected = true;
        log.info("HTTP transport started: {}", url);
    }

    @Override
    public CompletableFuture<JsonRpcMessage> sendRequest(JsonRpcMessage.Request request) {
        if (!connected) {
            return CompletableFuture.failedFuture(
                new IOException("Transport not connected"));
        }

        var future = new CompletableFuture<JsonRpcMessage>();
        // Fresh timeout per request — avoids stale AbortSignal bug
        future.orTimeout(timeoutSec, TimeUnit.SECONDS);

        Thread.ofVirtual().start(() -> {
            try {
                String body = JsonRpcCodec.encode(request);
                var resp = doPost(body);
                var msg = JsonRpcCodec.decode(resp);
                if (msg != null) {
                    future.complete(msg);
                } else {
                    future.complete(new JsonRpcMessage.Error(request.id(),
                        JsonRpcMessage.PARSE_ERROR, "Failed to parse server response", resp));
                }
            } catch (Exception e) {
                future.complete(new JsonRpcMessage.Error(request.id(),
                    JsonRpcMessage.INTERNAL_ERROR, "HTTP request failed: " + e.getMessage(), null));
            }
        });

        return future;
    }

    @Override
    public void sendNotification(JsonRpcMessage.Notification notification) {
        if (!connected) return;
        Thread.ofVirtual().start(() -> {
            try {
                doPost(JsonRpcCodec.encode(notification));
            } catch (Exception e) {
                log.warn("HTTP notification failed: {}", e.getMessage());
            }
        });
    }

    @Override
    public void setMessageHandler(Consumer<JsonRpcMessage> handler) {
        messageHandlers.add(handler);
    }

    private String doPost(String body) throws IOException, InterruptedException {
        var reqBuilder = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(Duration.ofSeconds(timeoutSec))
            .header("Content-Type", "application/json")
            .header("Accept", "application/json, text/event-stream")
            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));

        // Session continuation
        if (sessionId != null) {
            reqBuilder.header("Mcp-Session-Id", sessionId);
        }

        // Custom headers
        headers.forEach(reqBuilder::header);

        var response = httpClient.send(reqBuilder.build(),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

        // Capture session ID from response
        var sessionHeader = response.headers().firstValue("Mcp-Session-Id");
        sessionHeader.ifPresent(s -> sessionId = s);

        int status = response.statusCode();
        if (status == 404) {
            connected = false;
            throw new IOException("MCP session expired (HTTP 404)");
        }
        if (status < 200 || status >= 300) {
            throw new IOException("HTTP " + status + ": " + response.body());
        }

        return response.body();
    }

    @Override
    public void close() {
        connected = false;
        // Send a graceful close POST if session was established
        if (sessionId != null) {
            try {
                var req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Mcp-Session-Id", sessionId)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .timeout(Duration.ofSeconds(5))
                    .build();
                httpClient.send(req, HttpResponse.BodyHandlers.discarding());
            } catch (Exception ignored) {}
        }
        sessionId = null;
        log.info("HTTP transport closed: {}", url);
    }

    @Override
    public boolean isConnected() {
        return connected;
    }
}
