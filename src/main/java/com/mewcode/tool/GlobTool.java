
package com.mewcode.tool;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.stream.Stream;

/**
 * Finds files matching a glob pattern.
 *
 * <p>Supports {@code **} for recursive directory matching.
 * Results are sorted by last-modified time (newest first), capped at 500.
 */
@Component
public class GlobTool implements Tool {

    private static final int MAX_RESULTS = 500;

    @Override
    public String name() { return "glob"; }

    @Override
    public String description() {
        return "Find files matching a glob pattern. "
            + "Supports standard glob patterns like '*.java', '**/*.java'. "
            + "Returns up to " + MAX_RESULTS + " matching file paths, "
            + "sorted by modification time (newest first).";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return Map.of(
            "type", "object",
            "properties", Map.of(
                "pattern", Map.of("type", "string",
                    "description", "The glob pattern to match (e.g., '**/*.java', 'src/**/*.ts')"),
                "path", Map.of("type", "string",
                    "description", "The directory to search in. Defaults to current working directory")
            ),
            "required", List.of("pattern")
        );
    }

    @Override
    public ToolResult execute(Map<String, Object> params) {
        String pattern = (String) params.get("pattern");
        String basePath = (String) params.get("path");

        if (pattern == null || pattern.isBlank()) {
            return ToolResult.fail("Missing required parameter: pattern");
        }

        Path base = (basePath != null && !basePath.isBlank())
            ? Path.of(basePath)
            : Path.of("").toAbsolutePath();

        if (!Files.exists(base)) {
            return ToolResult.fail("Directory not found: " + base);
        }

        try {
            PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + pattern);

            // Collect files
            record FileEntry(Path path, long modified) {}
            var files = new ArrayList<FileEntry>();

            Files.walkFileTree(base, EnumSet.of(FileVisitOption.FOLLOW_LINKS), Integer.MAX_VALUE,
                new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                        Path relative = base.relativize(file);
                        if (matcher.matches(relative) || matcher.matches(file)) {
                            files.add(new FileEntry(file, attrs.lastModifiedTime().toMillis()));
                        }
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFileFailed(Path file, IOException exc) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                });

            // Sort by modified time descending
            files.sort((a, b) -> Long.compare(b.modified, a.modified));

            if (files.isEmpty()) {
                return ToolResult.ok("0 files found matching pattern: " + pattern);
            }

            boolean truncated = files.size() > MAX_RESULTS;
            var result = new StringBuilder();
            result.append("Found ").append(files.size()).append(" files");
            if (truncated) result.append(" (showing first ").append(MAX_RESULTS).append(")");
            result.append(":\n");

            int limit = Math.min(files.size(), MAX_RESULTS);
            for (int i = 0; i < limit; i++) {
                result.append(files.get(i).path).append("\n");
            }
            if (truncated) {
                result.append("... (").append(files.size() - MAX_RESULTS).append(" more files truncated)");
            }
            return ToolResult.ok(result.toString().stripTrailing());

        } catch (IOException e) {
            return ToolResult.fail("Error searching files: " + e.getMessage());
        }
    }
}
