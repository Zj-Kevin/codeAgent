package com.mewcode.security;

import java.nio.file.FileSystems;
import java.nio.file.PathMatcher;
import java.util.regex.Pattern;

/**
 * A security rule: tool + pattern → action.
 *
 * <p>For bash, pattern matches against the command string.
 * For all other tools, pattern matches against the file path.
 * Supports glob and plain substring matching.
 */
public record SecurityRule(String tool, String pattern, Action action, String source) {

    public enum Action { ALLOW, DENY, ASK }

    public static SecurityRule allow(String tool, String pattern, String source) {
        return new SecurityRule(tool, pattern, Action.ALLOW, source);
    }

    public static SecurityRule deny(String tool, String pattern, String source) {
        return new SecurityRule(tool, pattern, Action.DENY, source);
    }

    public static SecurityRule ask(String tool, String pattern, String source) {
        return new SecurityRule(tool, pattern, Action.ASK, source);
    }

    /** Check if this rule matches the given tool and value. */
    public boolean matches(String toolName, String value) {
        if (!"*".equals(tool) && !tool.equals(toolName)) return false;
        if (value == null) return false;
        // Try glob match first, then substring
        return globMatch(pattern, value) || value.contains(pattern);
    }

    private static boolean globMatch(String glob, String input) {
        try {
            PathMatcher m = FileSystems.getDefault().getPathMatcher("glob:" + glob);
            // PathMatcher works on paths, so normalize input as a path
            return m.matches(java.nio.file.Path.of(input));
        } catch (Exception e) {
            return false;
        }
    }
}
