
package com.mewcode.tool;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Creates or overwrites a file with the given content.
 */
@Component
public class WriteFileTool implements Tool {

    @Override
    public String name() { return "write_file"; }

    @Override
    public String description() {
        return "Writes content to a file, creating parent directories if needed. "
            + "Overwrites the file if it already exists. Use edit_file for targeted changes instead of rewriting entire files.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return Map.of(
            "type", "object",
            "properties", Map.of(
                "path", Map.of("type", "string", "description", "Absolute path to the file to write"),
                "content", Map.of("type", "string", "description", "The content to write to the file")
            ),
            "required", List.of("path", "content")
        );
    }

    @Override
    public ToolResult execute(Map<String, Object> params) {
        String pathStr = (String) params.get("path");
        String content = (String) params.get("content");

        if (pathStr == null || pathStr.isBlank()) {
            return ToolResult.fail("Missing required parameter: path");
        }
        if (content == null) {
            return ToolResult.fail("Missing required parameter: content");
        }

        try {
            Path path = Path.of(pathStr);
            Files.createDirectories(path.getParent());
            Files.writeString(path, content);
            return ToolResult.ok("Wrote " + content.lines().count() + " lines to " + pathStr);
        } catch (IOException e) {
            return ToolResult.fail("Error writing " + pathStr + ": " + e.getMessage());
        }
    }
}
