package com.mewcode.security;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads and writes security rules to JSON files.
 */
public class PermissionStore {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** Load rules from a JSON file. Returns empty list if file doesn't exist. */
    public List<SecurityRule> load(Path file) {
        if (!Files.exists(file)) return new ArrayList<>();
        try {
            String content = Files.readString(file);
            if (content.isBlank()) return new ArrayList<>();
            List<RuleEntry> entries = JSON.readValue(content, new TypeReference<>() {});
            return entries.stream().map(e -> new SecurityRule(e.tool, e.pattern,
                SecurityRule.Action.valueOf(e.action.toUpperCase()), file.toString())).toList();
        } catch (IOException e) {
            return new ArrayList<>();
        }
    }

    /** Save a new rule to a JSON file. Creates file + parent dirs if needed. */
    public void save(Path file, SecurityRule rule) throws IOException {
        var rules = new ArrayList<>(load(file));
        // Avoid exact duplicates
        for (var r : rules) {
            if (r.tool().equals(rule.tool()) && r.pattern().equals(rule.pattern())) return;
        }
        rules.add(rule);
        Files.createDirectories(file.getParent());
        List<RuleEntry> entries = rules.stream()
            .map(r -> new RuleEntry(r.tool(), r.pattern(), r.action().name().toLowerCase()))
            .toList();
        JSON.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), entries);
    }

    // Jackson DTO
    public record RuleEntry(String tool, String pattern, String action) {}
}
