package com.mewcode.mcp;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Loads MCP server configurations from two-tier JSON files.
 *
 * <p>Global: {@code ~/.mewcode/mcp.json}
 * <br>Project: {@code ./.mewcode/mcp.json} (overrides same-name servers)
 *
 * <p>Supports {@code ${ENV_VAR}} interpolation in {@code env} values and {@code headers} values.
 * Deduplicates servers by content signature (not by name).
 */
public class McpConfigLoader {

    private static final Logger log = LoggerFactory.getLogger(McpConfigLoader.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern ENV_VAR = Pattern.compile("\\$\\{(\\w+)\\}");

    private final Path globalFile;
    private final Path projectFile;

    public McpConfigLoader() {
        this.globalFile = Path.of(System.getProperty("user.home"), ".mewcode", "mcp.json");
        this.projectFile = Path.of("").toAbsolutePath().resolve(".mewcode/mcp.json").normalize();
    }

    public McpConfigLoader(Path cwd) {
        this.globalFile = Path.of(System.getProperty("user.home"), ".mewcode", "mcp.json");
        this.projectFile = cwd.resolve(".mewcode/mcp.json").normalize();
    }

    /**
     * Load and merge configurations.
     *
     * @return map of server name → McpConfig (project overrides global; deduped)
     */
    public Map<String, McpConfig> load() {
        // Layer 1: global
        Map<String, RawServerConfig> merged = new LinkedHashMap<>();
        loadFile(globalFile).forEach((name, raw) -> merged.put(name, raw));

        // Layer 2: project (overrides same-name entries)
        loadFile(projectFile).forEach((name, raw) -> {
            if (merged.containsKey(name)) {
                log.info("MCP config: {} overridden by project-level", name);
            }
            merged.put(name, raw);
        });

        // Convert to McpConfig with defaults filled
        Map<String, McpConfig> result = new LinkedHashMap<>();
        for (var entry : merged.entrySet()) {
            if (!entry.getValue().enabled) {
                log.info("MCP server '{}' is disabled, skipping", entry.getKey());
                continue;
            }
            result.put(entry.getKey(), toMcpConfig(entry.getKey(), entry.getValue()));
        }

        // Deduplicate by content signature
        Map<String, McpConfig> deduped = new LinkedHashMap<>();
        for (var entry : result.entrySet()) {
            String sig = entry.getValue().signature();
            boolean duplicate = false;
            for (var existing : deduped.values()) {
                if (existing.signature().equals(sig)) {
                    log.info("MCP server '{}' deduplicated (same signature as '{}')",
                        entry.getKey(), existing.name());
                    duplicate = true;
                    break;
                }
            }
            if (!duplicate) {
                deduped.put(entry.getKey(), entry.getValue());
            }
        }

        return deduped;
    }

    // ── JSON loading ──────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private Map<String, RawServerConfig> loadFile(Path file) {
        if (!Files.exists(file)) return Map.of();
        try {
            String content = Files.readString(file);
            if (content.isBlank()) return Map.of();
            var root = JSON.readValue(content, Map.class);
            Object serversObj = root.get("mcpServers");
            if (!(serversObj instanceof Map<?, ?> servers)) return Map.of();

            Map<String, RawServerConfig> result = new LinkedHashMap<>();
            for (var es : servers.entrySet()) {
                if (es.getValue() instanceof Map<?, ?> raw) {
                    result.put(es.getKey().toString(), parseRaw((Map<String, Object>) raw));
                }
            }
            return result;
        } catch (IOException e) {
            log.warn("Failed to load MCP config from {}: {}", file, e.getMessage());
            return Map.of();
        }
    }

    @SuppressWarnings("unchecked")
    private RawServerConfig parseRaw(Map<String, Object> map) {
        var raw = new RawServerConfig();
        raw.type = str(map, "type", "stdio");
        raw.command = str(map, "command", null);
        raw.args = list(map, "args");
        raw.env = map(map, "env");
        raw.cwd = str(map, "cwd", null);
        raw.url = str(map, "url", null);
        raw.headers = map(map, "headers");
        raw.enabled = bool(map, "enabled", true);
        raw.required = bool(map, "required", false);
        raw.startupTimeoutSec = integer(map, "startupTimeoutSec", 30);
        raw.toolTimeoutSec = integer(map, "toolTimeoutSec", 60);
        raw.enabledTools = list(map, "enabledTools");
        raw.disabledTools = list(map, "disabledTools");
        raw.autoConnect = bool(map, "autoConnect", true);
        return raw;
    }

    // ── Value extraction helpers ──────────────────────────────

    private static String str(Map<String, Object> m, String key, String def) {
        Object v = m.get(key);
        return v instanceof String s ? s : def;
    }

    private static boolean bool(Map<String, Object> m, String key, boolean def) {
        Object v = m.get(key);
        return v instanceof Boolean b ? b : def;
    }

    private static int integer(Map<String, Object> m, String key, int def) {
        Object v = m.get(key);
        return v instanceof Number n ? n.intValue() : def;
    }

    @SuppressWarnings("unchecked")
    private static List<String> list(Map<String, Object> m, String key) {
        Object v = m.get(key);
        if (v instanceof List<?> l) {
            return l.stream().filter(o -> o instanceof String).map(o -> (String) o).toList();
        }
        return List.of();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> map(Map<String, Object> m, String key) {
        Object v = m.get(key);
        if (v instanceof Map<?, ?> mv) {
            Map<String, String> result = new LinkedHashMap<>();
            for (var e : ((Map<String, Object>) mv).entrySet()) {
                String val = e.getValue() != null ? e.getValue().toString() : "";
                result.put(e.getKey(), resolveEnvVars(val));
            }
            return result;
        }
        return Map.of();
    }

    // ── Env var interpolation ─────────────────────────────────

    static String resolveEnvVars(String value) {
        if (value == null) return null;
        return ENV_VAR.matcher(value).replaceAll(mr -> {
            String varName = mr.group(1);
            String envVal = System.getenv(varName);
            if (envVal == null) {
                log.warn("MCP env var {} not resolved", varName);
                return "";
            }
            return envVal;
        });
    }

    // ── Conversion to McpConfig ───────────────────────────────

    private McpConfig toMcpConfig(String name, RawServerConfig raw) {
        return new McpConfig(
            name, raw.type, raw.command != null ? resolveEnvVars(raw.command) : null,
            raw.args, raw.env, raw.cwd, raw.url, raw.headers,
            raw.enabled, raw.required, raw.startupTimeoutSec, raw.toolTimeoutSec,
            raw.enabledTools, raw.disabledTools, raw.autoConnect
        );
    }

    // ── Internal DTO ──────────────────────────────────────────

    private static class RawServerConfig {
        String type, command, cwd, url;
        List<String> args = List.of();
        Map<String, String> env = Map.of();
        Map<String, String> headers = Map.of();
        boolean enabled = true, required = false, autoConnect = true;
        int startupTimeoutSec = 30, toolTimeoutSec = 60;
        List<String> enabledTools = List.of(), disabledTools = List.of();
    }
}
