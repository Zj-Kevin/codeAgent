
package com.mewcode.tool;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads a file from the filesystem, with optional offset and limit.
 */
@Component
public class ReadFileTool implements Tool {

    @Override
    public String name() { return "read_file"; }

    @Override
    public String description() {
        return "Read a file from the local filesystem. "
            + "Use 'offset' and 'limit' to read a specific range of lines "
            + "(especially handy for long files), but it's recommended "
            + "to read the whole file by not providing these parameters. Read without offset/limit first to get complete content.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        var schema = new LinkedHashMap<String, Object>();
        schema.put("type", "object");
        schema.put("properties", Map.of(
            "path", Map.of("type", "string", "description", "Absolute path to the file to read"),
            "offset", Map.of("type", "integer", "description", "Line number to start reading from (0-indexed). Defaults to 0"),
            "limit", Map.of("type", "integer", "description", "Maximum number of lines to read. Defaults to 2000")
        ));
        schema.put("required", List.of("path"));
        return schema;
    }

    @Override
    public ToolResult execute(Map<String, Object> params) {
        String pathStr = (String) params.get("path");
        if (pathStr == null || pathStr.isBlank()) {
            return ToolResult.fail("Missing required parameter: path");
        }

        Path path = Path.of(pathStr);
        if (!Files.exists(path)) {
            return ToolResult.fail("File not found: " + pathStr);
        }
        if (Files.isDirectory(path)) {
            return ToolResult.fail("Path is a directory, not a file: " + pathStr);
        }

        try {
            var lines = Files.readAllLines(path);
            int offset = getIntParam(params, "offset", 0);
            int limit = getIntParam(params, "limit", 2000);

            // Clamp offset
            if (offset < 0) offset = 0;
            if (offset >= lines.size()) {
                return ToolResult.ok("(file has " + lines.size() + " lines, offset " + offset + " is beyond end)");
            }

            int end = Math.min(offset + limit, lines.size());
            var result = new StringBuilder();
            for (int i = offset; i < end; i++) {
                if (!result.isEmpty()) result.append("\n");
                result.append(lines.get(i));
            }
            return ToolResult.ok(result.toString());

        } catch (IOException e) {
            return ToolResult.fail("Error reading " + pathStr + ": " + e.getMessage());
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
