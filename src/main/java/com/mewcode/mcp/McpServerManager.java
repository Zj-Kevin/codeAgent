package com.mewcode.mcp;

import com.mewcode.config.MewCodeProperties;
import com.mewcode.mcp.transport.HttpTransport;
import com.mewcode.mcp.transport.StdioTransport;
import com.mewcode.security.SecurityManager;
import com.mewcode.tool.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.*;

/**
 * Manages the lifecycle of all configured MCP servers.
 *
 * <p>On startup, loads configs, creates transports and clients for
 * {@code autoConnect=true} servers, performs the initialize handshake,
 * discovers tools, and registers them into the {@link ToolRegistry}.
 *
 * <p>Supports reconnect with exponential backoff (1s → 2s → 4s, max 3 retries).
 */
@Component
public class McpServerManager {

    private static final Logger log = LoggerFactory.getLogger(McpServerManager.class);

    private final ToolRegistry toolRegistry;
    private final SecurityManager securityManager;
    private final McpConfigLoader configLoader;
    private final MewCodeProperties.Mcp mcpProps;

    private final ConcurrentHashMap<String, McpClient> clients = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, McpConfig> configs = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, List<String>> serverToolNames = new ConcurrentHashMap<>(); // server → registered tool names

    @Autowired
    public McpServerManager(ToolRegistry toolRegistry, SecurityManager securityManager,
                             MewCodeProperties props) {
        this.toolRegistry = toolRegistry;
        this.securityManager = securityManager;
        this.configLoader = new McpConfigLoader();
        this.mcpProps = props.getMcp();
    }

    /** For testing: inject custom config loader. */
    public McpServerManager(ToolRegistry toolRegistry, SecurityManager securityManager,
                             McpConfigLoader configLoader, MewCodeProperties.Mcp mcpProps) {
        this.toolRegistry = toolRegistry;
        this.securityManager = securityManager;
        this.configLoader = configLoader;
        this.mcpProps = mcpProps;
    }

    // ── Startup ───────────────────────────────────────────────

    /**
     * Initialize all auto-connect servers on startup.
     * Servers connect in parallel.
     */
    @PostConstruct
    public void initialize() {
        if (!mcpProps.isEnabled()) {
            log.info("MCP is disabled (mewcode.mcp.enabled=false), skipping initialization");
            return;
        }
        var serverConfigs = configLoader.load();
        if (serverConfigs.isEmpty()) {
            log.info("No MCP servers configured");
            return;
        }

        log.info("Initializing {} MCP server(s)...", serverConfigs.size());

        // Filter and connect auto-connect servers in parallel
        var futures = new ArrayList<CompletableFuture<Void>>();
        for (var entry : serverConfigs.entrySet()) {
            McpConfig config = entry.getValue();
            configs.put(entry.getKey(), config);

            if (config.autoConnect()) {
                futures.add(CompletableFuture.runAsync(() -> {
                    try {
                        connectServer(config);
                    } catch (Exception e) {
                        if (config.required()) {
                            log.error("Required MCP server '{}' failed to connect: {}", config.name(), e.getMessage());
                            // Don't rethrow — let other servers continue, error is logged
                        } else {
                            log.warn("MCP server '{}' failed to connect (optional): {}", config.name(), e.getMessage());
                        }
                    }
                }));
            }
        }

        // Wait for all connections
        if (!futures.isEmpty()) {
            try {
                CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .get(60, TimeUnit.SECONDS);
            } catch (TimeoutException e) {
                log.warn("MCP server initialization timed out waiting for some servers");
            } catch (Exception e) {
                log.warn("MCP server initialization interrupted: {}", e.getMessage());
            }
        }

        log.info("MCP initialization complete: {} connected, {} total",
            clients.size(), serverConfigs.size());
    }

    // ── Connect / Disconnect ──────────────────────────────────

    /**
     * Connect a server, discover tools, and register them.
     */
    public void connectServer(McpConfig config) throws Exception {
        log.info("Connecting MCP server '{}' (type={})...", config.name(), config.type());

        var transport = createTransport(config);
        var client = new McpClient(config.name(), transport,
            config.startupTimeoutSec(), config.toolTimeoutSec());

        client.connect();
        clients.put(config.name(), client);

        // Discover and register tools
        var toolDefs = client.listTools();
        var registeredNames = new ArrayList<String>();

        for (var def : toolDefs) {
            // Apply filters
            if (!config.enabledTools().isEmpty() && !config.enabledTools().contains(def.name())) {
                continue;
            }
            if (config.disabledTools().contains(def.name())) {
                continue;
            }

            var tool = new McpRemoteTool(client, def.name(), def.description(),
                def.inputSchema(), def.destructiveHint(), def.readOnlyHint());

            toolRegistry.register(tool);
            securityManager.registerToolCategories(tool.name(), tool.categories());
            registeredNames.add(tool.name());
        }

        serverToolNames.put(config.name(), registeredNames);
        log.info("MCP server '{}' ready with {} tools", config.name(), registeredNames.size());
    }

    /**
     * Disconnect a server and unregister its tools.
     */
    public void disconnectServer(String name) {
        var client = clients.remove(name);
        if (client != null) {
            client.disconnect();
        }

        // Unregister tools
        var names = serverToolNames.remove(name);
        if (names != null) {
            for (var toolName : names) {
                securityManager.unregisterTool(toolName);
            }
            toolRegistry.unregisterByPrefix("mcp__" + name + "__");
        }

        log.info("MCP server '{}' disconnected", name);
    }

    // ── Reconnect ─────────────────────────────────────────────

    /**
     * Attempt reconnection with exponential backoff.
     * Returns true if reconnect succeeded.
     */
    public boolean reconnect(String name) {
        var config = configs.get(name);
        if (config == null) {
            log.warn("No config found for MCP server '{}'", name);
            return false;
        }

        // Clean up old state
        var oldClient = clients.remove(name);
        if (oldClient != null) oldClient.disconnect();
        var oldNames = serverToolNames.remove(name);
        if (oldNames != null) {
            for (var n : oldNames) {
                securityManager.unregisterTool(n);
                toolRegistry.unregister(n);
            }
        }

        // Exponential backoff: 1s → 2s → 4s, max 3 tries
        for (int attempt = 0; attempt < 3; attempt++) {
            long delayMs = (long) Math.pow(2, attempt) * 1000; // 1000, 2000, 4000
            log.info("MCP reconnect '{}' attempt {}/3 (delay {}ms)...", name, attempt + 1, delayMs);

            try {
                Thread.sleep(delayMs);
                connectServer(config);
                log.info("MCP server '{}' reconnected successfully", name);
                return true;
            } catch (Exception e) {
                log.warn("MCP reconnect '{}' attempt {} failed: {}", name, attempt + 1, e.getMessage());
            }
        }

        log.error("MCP server '{}' reconnection failed after 3 attempts", name);
        return false;
    }

    // ── Query ─────────────────────────────────────────────────

    public record ServerInfo(String name, String state, int toolCount) {}

    public List<ServerInfo> getServers() {
        var list = new ArrayList<ServerInfo>();
        for (var entry : configs.entrySet()) {
            String name = entry.getKey();
            var client = clients.get(name);
            String state = client != null ? client.state().name().toLowerCase() : "unconfigured";
            var toolNames = serverToolNames.getOrDefault(name, List.of());
            list.add(new ServerInfo(name, state, toolNames.size()));
        }
        return list;
    }

    public boolean isEnabled() {
        return !configs.isEmpty();
    }

    // ── Internal ──────────────────────────────────────────────

    private com.mewcode.mcp.transport.McpTransport createTransport(McpConfig config) {
        return switch (config.transportType()) {
            case STDIO -> new StdioTransport(config.command(), config.args(), config.env(), config.cwd());
            case HTTP -> new HttpTransport(config.url(), config.headers(), config.startupTimeoutSec());
        };
    }
}
