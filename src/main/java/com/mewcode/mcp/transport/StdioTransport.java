package com.mewcode.mcp.transport;

import com.mewcode.mcp.JsonRpcCodec;
import com.mewcode.mcp.JsonRpcMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.function.Consumer;

/**
 * MCP transport over a local subprocess stdin/stdout.
 *
 * <p>Launches a child process, writes JSON-RPC messages to its stdin
 * (one per line), and reads responses from stdout. stderr is merged
 * into stdout via {@code redirectErrorStream(true)}.
 *
 * <p>Request-response matching is done via an internal {@code ConcurrentHashMap}
 * keyed by request id. The read thread dispatches incoming messages
 * to the matching {@code CompletableFuture} or to the notification handler.
 */
public class StdioTransport implements McpTransport {

    private static final Logger log = LoggerFactory.getLogger(StdioTransport.class);

    private final String command;
    private final List<String> args;
    private final Map<String, String> env;
    private final String cwd;

    private Process process;
    private BufferedWriter stdinWriter;
    private final Object writerLock = new Object();
    private volatile boolean connected;

    private final ConcurrentHashMap<Integer, CompletableFuture<JsonRpcMessage>> pendingRequests = new ConcurrentHashMap<>();
    private final List<Consumer<JsonRpcMessage>> messageHandlers = new CopyOnWriteArrayList<>();
    private Thread readThread;
    private volatile boolean running;

    public StdioTransport(String command, List<String> args, Map<String, String> env, String cwd) {
        this.command = command;
        this.args = args != null ? args : List.of();
        this.env = env != null ? env : Map.of();
        this.cwd = cwd;
    }

    @Override
    public void start() throws IOException {
        var cmdList = new ArrayList<String>();

        // On Windows, wrap bare commands (no path separator) in cmd /c to resolve via system PATH
        if (isWindows() && !command.contains("/") && !command.contains("\\")
            && !command.endsWith(".exe") && !command.endsWith(".cmd") && !command.endsWith(".bat")) {
            cmdList.add("cmd");
            cmdList.add("/c");
        }
        cmdList.add(command);
        cmdList.addAll(args);

        var pb = new ProcessBuilder(cmdList);
        pb.redirectErrorStream(true); // merge stderr into stdout

        // Environment
        var processEnv = pb.environment();
        env.forEach(processEnv::put);

        // Working directory
        if (cwd != null && !cwd.isBlank()) {
            pb.directory(new File(cwd));
        }

        process = pb.start();
        stdinWriter = new BufferedWriter(
            new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
        connected = true;
        running = true;

        // Start read thread
        readThread = Thread.ofVirtual().start(this::readLoop);

        log.info("Stdio transport started: {} {}", command, String.join(" ", args));
    }

    private void readLoop() {
        try (var reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while (running && (line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                dispatch(line);
            }
        } catch (IOException e) {
            if (running) {
                log.warn("Stdio read error: {}", e.getMessage());
            }
        } finally {
            connected = false;
            // Fail all pending requests
            var failMsg = new JsonRpcMessage.Error(0, JsonRpcMessage.INTERNAL_ERROR,
                "Transport closed", null);
            pendingRequests.values().forEach(f -> f.complete(failMsg));
            pendingRequests.clear();
        }
    }

    private void dispatch(String line) {
        var msg = JsonRpcCodec.decode(line);
        if (msg == null) {
            log.debug("Unparseable line from stdio server: {}", line);
            return;
        }

        if (msg instanceof JsonRpcMessage.Response resp) {
            var future = pendingRequests.remove(resp.id());
            if (future != null) {
                future.complete(resp);
            }
        } else if (msg instanceof JsonRpcMessage.Error err) {
            var future = pendingRequests.remove(err.id());
            if (future != null) {
                future.complete(err);
            }
        } else if (msg instanceof JsonRpcMessage.Notification) {
            for (var handler : messageHandlers) {
                try { handler.accept(msg); } catch (Exception ignored) {}
            }
        }
        // Request from server → unexpected but pass to handlers
    }

    @Override
    public CompletableFuture<JsonRpcMessage> sendRequest(JsonRpcMessage.Request request) {
        var future = new CompletableFuture<JsonRpcMessage>();
        pendingRequests.put(request.id(), future);
        writeLine(JsonRpcCodec.encode(request));
        return future;
    }

    @Override
    public void sendNotification(JsonRpcMessage.Notification notification) {
        writeLine(JsonRpcCodec.encode(notification));
    }

    @Override
    public void setMessageHandler(Consumer<JsonRpcMessage> handler) {
        messageHandlers.add(handler);
    }

    private void writeLine(String json) {
        synchronized (writerLock) {
            if (stdinWriter == null) return;
            try {
                stdinWriter.write(json);
                stdinWriter.newLine();
                stdinWriter.flush();
            } catch (IOException e) {
                log.warn("Stdio write error: {}", e.getMessage());
                connected = false;
            }
        }
    }

    @Override
    public void close() {
        running = false;
        connected = false;

        // Close stdin first so subprocess can exit gracefully
        synchronized (writerLock) {
            if (stdinWriter != null) {
                try { stdinWriter.close(); } catch (IOException ignored) {}
                stdinWriter = null;
            }
        }

        // Wait briefly, then force kill
        if (process != null) {
            try { process.waitFor(2, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
            if (process.isAlive()) {
                process.destroyForcibly();
            }
            process = null;
        }

        // Interrupt read thread
        if (readThread != null) {
            readThread.interrupt();
            readThread = null;
        }

        log.info("Stdio transport closed: {}", command);
    }

    @Override
    public boolean isConnected() {
        return connected && process != null && process.isAlive();
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("windows");
    }
}
