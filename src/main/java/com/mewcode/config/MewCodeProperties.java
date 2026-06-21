package com.mewcode.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;

/**
 * Type-safe configuration for MewCode, bound from {@code application.yml}.
 *
 * <p>Prefix: {@code mewcode}
 */
@ConfigurationProperties(prefix = "mewcode")
public class MewCodeProperties {

    @NestedConfigurationProperty
    private final Provider provider = new Provider();

    @NestedConfigurationProperty
    private final Agent agent = new Agent();

    @NestedConfigurationProperty
    private final Http http = new Http();

    @NestedConfigurationProperty
    private final History history = new History();

    public Provider getProvider() { return provider; }
    public Agent getAgent() { return agent; }
    public Http getHttp() { return http; }
    public History getHistory() { return history; }

    // ── Nested classes ─────────────────────────────────────

    public static class Provider {
        /** API protocol: anthropic | openai | deepseek */
        private String protocol;
        /** Model name */
        private String model;
        /** API base URL (optional, defaults per protocol) */
        private String baseUrl;
        /** API key (optional, falls back to env var) */
        private String apiKey;

        public String getProtocol() { return protocol; }
        public void setProtocol(String protocol) { this.protocol = protocol; }

        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }

        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }

        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }

        /** Resolve effective API key: config value first, then env var. */
        public String resolveApiKey() {
            if (apiKey != null && !apiKey.isBlank()) return apiKey.strip();
            var envVar = switch (protocol != null ? protocol.toLowerCase() : "") {
                case "openai" -> "OPENAI_API_KEY";
                case "deepseek" -> "DEEPSEEK_API_KEY";
                default -> "ANTHROPIC_API_KEY";
            };
            var envVal = System.getenv(envVar);
            if (envVal != null && !envVal.isBlank()) return envVal.strip();
            throw new IllegalStateException(
                "api-key is empty and environment variable " + envVar + " is not set");
        }

        /** Resolve effective base URL, applying protocol default. */
        public String resolveBaseUrl() {
            if (baseUrl != null && !baseUrl.isBlank()) return baseUrl.strip();
            return switch (protocol != null ? protocol.toLowerCase() : "") {
                case "openai" -> "https://api.openai.com";
                case "deepseek" -> "https://api.deepseek.com";
                default -> "https://api.anthropic.com";
            };
        }
    }

    public static class Agent {
        /** Maximum tool-calling rounds before forced termination. */
        private int maxRounds = 25;
        /** Include tool definitions in LLM requests. */
        private boolean includeTools = true;

        public int getMaxRounds() { return maxRounds; }
        public void setMaxRounds(int maxRounds) { this.maxRounds = maxRounds; }

        public boolean isIncludeTools() { return includeTools; }
        public void setIncludeTools(boolean includeTools) { this.includeTools = includeTools; }
    }

    public static class Http {
        /** SSE connect/read timeout in seconds. */
        private int timeoutSeconds = 120;

        public int getTimeoutSeconds() { return timeoutSeconds; }
        public void setTimeoutSeconds(int timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }
    }

    public static class History {
        /** Directory for history JSON files. */
        private String dir = System.getProperty("user.home") + "/.mewcode/history";

        public String getDir() { return dir; }
        public void setDir(String dir) { this.dir = dir; }
    }
}
