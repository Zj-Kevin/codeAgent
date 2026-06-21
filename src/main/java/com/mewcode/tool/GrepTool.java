
package com.mewcode.tool;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Searches file contents using a regex pattern.
 *
 * <p>Supports three output modes:
 * <ul>
 *   <li>{@code files_with_matches} — only file paths (default)</li>
 *   <li>{@code content} — matching lines with line numbers</li>
 *   <li>{@code count} — match count per file</li>
 * </ul>
 */
@Component
public class GrepTool implements Tool {

    private static final int MAX_RESULTS = 250;
    private static final int MAX_FILE_SIZE = 500_000; // skip files > 500KB

    @Override
    public String name() { return "grep"; }

    @Override
    public String description() {
        return "Search file contents using a regular expression pattern. "
            + "Returns matching lines (with line numbers), file paths, or match counts. "
            + "Filter by glob pattern to limit the search to specific file types. "
            + "Results are capped at " + MAX_RESULTS + " entries.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return Map.of(
            "type", "object",
            "properties", Map.of(
                "pattern", Map.of("type", "string",
                    "description", "The regular expression pattern to search for"),
                "path", Map.of("type", "string",
                    "description", "File or directory to search in. Defaults to current directory"),
                "glob", Map.of("type", "string",
                    "description", "Glob pattern to filter files (e.g., '*.java', '*.{ts,tsx}')"),
                "output_mode", Map.of("type", "string",
                    "description", "Output mode: 'content' (matching lines), "
                        + "'files_with_matches' (paths only, default), 'count' (match counts)"),
                "head_limit", Map.of("type", "integer",
                    "description", "Limit output to first N entries. Defaults to 250")
            ),
            "required", List.of("pattern")
        );
    }

    @Override
    public ToolResult execute(Map<String, Object> params) {
        String patternStr = (String) params.get("pattern");
        String basePath = (String) params.get("path");
        String fileGlob = (String) params.get("glob");
        String outputMode = (String) params.get("output_mode");
        int headLimit = getIntParam(params, "head_limit", MAX_RESULTS);

        if (patternStr == null || patternStr.isBlank()) {
            return ToolResult.fail("Missing required parameter: pattern");
        }

        Pattern regex;
        try {
            regex = Pattern.compile(patternStr);
        } catch (PatternSyntaxException e) {
            return ToolResult.fail("Invalid regex pattern: " + e.getMessage());
        }

        final String mode = (outputMode != null && !outputMode.isBlank())
            ? outputMode : "files_with_matches";

        Path base = (basePath != null && !basePath.isBlank())
            ? Path.of(basePath)
            : Path.of("").toAbsolutePath();

        Path baseDir = Files.isDirectory(base) ? base : base.getParent();
        Path singleFile = Files.isRegularFile(base) ? base : null;

        try {
            var allResults = new ArrayList<String>();

            if (singleFile != null) {
                grepFile(singleFile, regex, mode, allResults);
            } else {
                PathMatcher globMatcher = (fileGlob != null && !fileGlob.isBlank())
                    ? FileSystems.getDefault().getPathMatcher("glob:" + fileGlob)
                    : null;

                Files.walkFileTree(baseDir, EnumSet.of(FileVisitOption.FOLLOW_LINKS), Integer.MAX_VALUE,
                    new SimpleFileVisitor<>() {
                        @Override
                        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                            if (globMatcher != null) {
                                Path rel = baseDir.relativize(file);
                                if (!globMatcher.matches(rel)) return FileVisitResult.CONTINUE;
                            }
                            try {
                                if (Files.size(file) > MAX_FILE_SIZE) return FileVisitResult.CONTINUE;
                            } catch (IOException e) { return FileVisitResult.CONTINUE; }
                            grepFile(file, regex, mode, allResults);
                            return FileVisitResult.CONTINUE;
                        }
                        @Override
                        public FileVisitResult visitFileFailed(Path file, IOException exc) {
                            return FileVisitResult.SKIP_SUBTREE;
                        }
                    });
            }

            // Apply head limit
            int total = allResults.size();
            boolean truncated = total > headLimit;
            int limit = Math.min(total, headLimit);

            var sb = new StringBuilder();
            if (total == 0) {
                sb.append("No matches found for pattern: ").append(patternStr);
            } else {
                sb.append("Found ").append(total).append(" matches");
                if (truncated) sb.append(" (showing ").append(headLimit).append(")");
                sb.append(":\n");
                for (int i = 0; i < limit; i++) {
                    sb.append(allResults.get(i)).append("\n");
                }
                if (truncated) {
                    sb.append("... (").append(total - headLimit).append(" more matches truncated)");
                }
            }
            return ToolResult.ok(sb.toString().stripTrailing());

        } catch (IOException e) {
            return ToolResult.fail("Error searching: " + e.getMessage());
        }
    }

    private void grepFile(Path file, Pattern regex, String mode, List<String> results) {
        // Stop early if we already have enough
        if (results.size() >= MAX_RESULTS * 2) return;

        try {
            var lines = Files.readAllLines(file);
            switch (mode) {
                case "content" -> {
                    for (int i = 0; i < lines.size(); i++) {
                        if (results.size() >= MAX_RESULTS * 2) return;
                        if (regex.matcher(lines.get(i)).find()) {
                            results.add(file + ":" + (i + 1) + ": " + lines.get(i));
                        }
                    }
                }
                case "count" -> {
                    int count = 0;
                    for (var line : lines) {
                        if (regex.matcher(line).find()) count++;
                    }
                    if (count > 0) {
                        results.add(file + ": " + count + " matches");
                    }
                }
                default -> { // files_with_matches
                    for (var line : lines) {
                        if (regex.matcher(line).find()) {
                            results.add(file.toString());
                            return; // one match is enough
                        }
                    }
                }
            }
        } catch (IOException ignored) {
            // Skip unreadable files
        }
    }

    private int getIntParam(Map<String, Object> params, String key, int defaultVal) {
        Object val = params.get(key);
        if (val instanceof Number n) return n.intValue();
        if (val instanceof String s) {
            try { return Integer.parseInt(s); } catch (NumberFormatException ignored) {}
        }
        return defaultVal;
    }
}
