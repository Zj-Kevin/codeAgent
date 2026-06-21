package com.mewcode.http;

import com.mewcode.config.MewCodeProperties;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Spliterators;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/**
 * Minimal SSE client wrapping {@link java.net.http.HttpClient}.
 */
public class SseClient {

    private final HttpClient httpClient;
    private final int timeoutSeconds;

    public SseClient(HttpClient httpClient, MewCodeProperties.Provider config) {
        this(httpClient, 120);
    }

    public SseClient(HttpClient httpClient, int timeoutSeconds) {
        this.httpClient = httpClient;
        this.timeoutSeconds = timeoutSeconds;
    }

    public Stream<String> stream(String url, Map<String, String> headers, String body)
            throws IOException, InterruptedException {

        var requestBuilder = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(java.time.Duration.ofSeconds(timeoutSeconds));

        headers.forEach(requestBuilder::header);
        requestBuilder.header("Content-Type", "application/json");
        requestBuilder.header("Accept", "text/event-stream");

        var request = requestBuilder
            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
            .build();

        var response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());

        int status = response.statusCode();
        if (status < 200 || status >= 300) {
            String errorBody;
            try (var is = response.body();
                 var reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                errorBody = reader.lines().limit(20).reduce("", (a, b) -> a + b);
            }
            throw new IOException("HTTP " + status + ": " + errorBody);
        }

        var reader = new BufferedReader(
            new InputStreamReader(response.body(), StandardCharsets.UTF_8));

        var iterator = reader.lines()
            .filter(line -> !line.isBlank())
            .filter(line -> !line.startsWith(":"))
            .filter(line -> line.startsWith("data: "))
            .map(line -> line.substring("data: ".length()))
            .onClose(() -> { try { reader.close(); } catch (IOException ignored) { } })
            .iterator();

        var spliterator = Spliterators.spliteratorUnknownSize(
            iterator, java.util.Spliterator.ORDERED | java.util.Spliterator.NONNULL);

        return StreamSupport.stream(spliterator, false)
            .onClose(() -> { try { reader.close(); } catch (IOException ignored) { } });
    }
}
