package com.mewcode.mcp;

import java.util.List;
import java.util.Map;

/**
 * Configuration for a single MCP server.
 *
 * @param name              server name (user-assigned, used as namespace prefix)
 * @param type              transport type: {@code "stdio"} or {@code "http"}
 * @param command           executable for stdio transport
 * @param args              CLI arguments
 * @param env               environment variables injected into subprocess (supports {@code ${VAR}} syntax)
 * @param cwd               working directory for subprocess
 * @param url               endpoint URL for HTTP transport
 * @param headers           HTTP headers (supports {@code ${VAR}} syntax)
 * @param enabled           if false, skip this server without deleting config
 * @param required          if true, connection failure prevents startup
 * @param startupTimeoutSec timeout for connect + initialize handshake (seconds)
 * @param toolTimeoutSec    timeout for a single {@code tools/call} (seconds)
 * @param enabledTools      allowlist of tool names; empty = all
 * @param disabledTools     denylist of tool names; applied after allowlist
 * @param autoConnect       connect at startup
 */
public record McpConfig(
    String name,
    String type,
    String command,
    List<String> args,
    Map<String, String> env,
    String cwd,
    String url,
    Map<String, String> headers,
    boolean enabled,
    boolean required,
    int startupTimeoutSec,
    int toolTimeoutSec,
    List<String> enabledTools,
    List<String> disabledTools,
    boolean autoConnect
) {
    /** Transport type constants. */
    public enum TransportType {
        STDIO, HTTP;

        public static TransportType from(String s) {
            return switch (s.toLowerCase()) {
                case "stdio" -> STDIO;
                case "http" -> HTTP;
                default -> throw new IllegalArgumentException("Unknown transport type: " + s);
            };
        }
    }

    public TransportType transportType() {
        return TransportType.from(type);
    }

    /**
     * Compute a content-based signature for deduplication.
     * Two servers with different names but identical command+args (or URL)
     * produce the same signature.
     */
    public String signature() {
        return switch (transportType()) {
            case STDIO -> "stdio:" + (command != null ? command : "")
                + ":" + (args != null ? String.join(",", args) : "");
            case HTTP -> "http:" + (url != null ? url : "");
        };
    }

    // ── Builder for defaults ──────────────────────────────────

    public static McpConfig withDefaults(String name, String type) {
        return new McpConfig(name, type, null, List.of(), Map.of(), null,
            null, Map.of(), true, false, 30, 60, List.of(), List.of(), true);
    }

    public McpConfig withCommand(String cmd) {
        return new McpConfig(name, type, cmd, args, env, cwd, url, headers,
            enabled, required, startupTimeoutSec, toolTimeoutSec,
            enabledTools, disabledTools, autoConnect);
    }

    public McpConfig withArgs(List<String> a) {
        return new McpConfig(name, type, command, a, env, cwd, url, headers,
            enabled, required, startupTimeoutSec, toolTimeoutSec,
            enabledTools, disabledTools, autoConnect);
    }

    public McpConfig withEnv(Map<String, String> e) {
        return new McpConfig(name, type, command, args, e, cwd, url, headers,
            enabled, required, startupTimeoutSec, toolTimeoutSec,
            enabledTools, disabledTools, autoConnect);
    }

    public McpConfig withUrl(String u) {
        return new McpConfig(name, type, command, args, env, cwd, u, headers,
            enabled, required, startupTimeoutSec, toolTimeoutSec,
            enabledTools, disabledTools, autoConnect);
    }

    public McpConfig withHeaders(Map<String, String> h) {
        return new McpConfig(name, type, command, args, env, cwd, url, h,
            enabled, required, startupTimeoutSec, toolTimeoutSec,
            enabledTools, disabledTools, autoConnect);
    }

    public McpConfig withEnabled(boolean e) {
        return new McpConfig(name, type, command, args, env, cwd, url, headers,
            e, required, startupTimeoutSec, toolTimeoutSec,
            enabledTools, disabledTools, autoConnect);
    }
}
