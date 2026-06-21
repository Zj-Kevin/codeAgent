package com.mewcode.prompt;

import com.mewcode.provider.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/**
 * Assembles the final prompt: stable prefix (cacheable) + dynamic suffix.
 */
@Component
public class PromptBuilder {

    private static final Logger log = LoggerFactory.getLogger(PromptBuilder.class);

    private final ModuleRegistry registry;
    private final EnvironmentCollector environmentCollector;

    private String lastStableHash = "";

    public PromptBuilder(ModuleRegistry registry, EnvironmentCollector environmentCollector) {
        this.registry = registry;
        this.environmentCollector = environmentCollector;
    }

    /**
     * Build the cacheable stable prefix: system instructions + tool definitions.
     */
    public String buildStablePrefix(Map<String, String> vars, List<Map<String, Object>> tools) {
        var sb = new StringBuilder();
        sb.append(registry.renderStable(vars));

        // Tool definitions appended after instructions
        if (tools != null && !tools.isEmpty()) {
            sb.append("\n\n## Available Tools\n");
            // Tools are already JSON — just note them as available
            sb.append("Use the provided tool calling mechanism. Tool descriptions are in the function definitions.");
        }
        String result = sb.toString();

        // Track hash for cache validation
        String hash = sha256(result);
        if (!hash.equals(lastStableHash)) {
            log.debug("Prompt cache MISS — hash: {} (prev: {})", hash,
                lastStableHash.isEmpty() ? "none" : lastStableHash);
            lastStableHash = hash;
        } else {
            log.debug("Prompt cache HIT — hash: {}", hash);
        }

        return result;
    }

    /**
     * Build the environment system message.
     */
    public Message buildEnvironmentMessage() {
        Map<String, String> env = environmentCollector.collect();
        return Message.system(environmentCollector.buildEnvironmentMessage(env));
    }

    /** Get fresh environment variables for module rendering. */
    public Map<String, String> collectVars() {
        return environmentCollector.collect();
    }

    private String sha256(String input) {
        try {
            var md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            var sb = new StringBuilder();
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString().substring(0, 16); // first 16 chars is enough
        } catch (NoSuchAlgorithmException e) {
            return Integer.toHexString(input.hashCode());
        }
    }
}
