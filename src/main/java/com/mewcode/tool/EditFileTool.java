
package com.mewcode.tool;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Performs exact string replacement in a file.
 *
 * <p>The {@code old_string} must match exactly once (including whitespace).
 * On zero or multiple matches, returns a structured error with line numbers
 * so the model can retry with a corrected string.
 */
@Component
public class EditFileTool implements Tool {

    @Override
    public String name() { return "edit_file"; }

    @Override
    public String description() {
        return "Performs exact string replacement in a file. "
            + "The old_string must match exactly one location in the file "
            + "(including whitespace and indentation). "
            + "If it matches zero or multiple times, the tool returns an error "
            + "with details so you can adjust and retry. Before editing, you MUST first read the file with read_file to see its current content.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return Map.of(
            "type", "object",
            "properties", Map.of(
                "path", Map.of("type", "string", "description", "Absolute path to the file to edit"),
                "old_string", Map.of("type", "string", "description", "The exact text to replace"),
                "new_string", Map.of("type", "string", "description", "The text to replace it with")
            ),
            "required", List.of("path", "old_string", "new_string")
        );
    }

    @Override
    public ToolResult execute(Map<String, Object> params) {
        String pathStr = (String) params.get("path");
        String oldStr = (String) params.get("old_string");
        String newStr = (String) params.get("new_string");

        if (pathStr == null || pathStr.isBlank()) return ToolResult.fail("Missing required parameter: path");
        if (oldStr == null) return ToolResult.fail("Missing required parameter: old_string");
        if (newStr == null) return ToolResult.fail("Missing required parameter: new_string");

        try {
            Path path = Path.of(pathStr);
            if (!Files.exists(path)) {
                return ToolResult.fail("File not found: " + pathStr);
            }

            String content = Files.readString(path);

            // Count exact matches
            int index = 0;
            int count = 0;
            int lastMatch = -1;
            while ((index = content.indexOf(oldStr, index)) >= 0) {
                count++;
                lastMatch = index;
                if (count > 50) break; // safety valve
            }

            if (count == 0) {
                // Build context: show nearby lines
                int line = lineNumberAt(content, 0, oldStr);
                return ToolResult.fail(String.format(
                    "old_string not found in %s. No exact character match found. "
                    + "Check whitespace, indentation, and blank lines. "
                    + "The file has %d lines.",
                    pathStr, content.lines().count()));
            }

            if (count > 1) {
                // Find line numbers of each match
                var lineNums = new java.util.ArrayList<Integer>();
                int idx = 0;
                while ((idx = content.indexOf(oldStr, idx)) >= 0) {
                    lineNums.add(lineNumberAt(content, idx, oldStr));
                    idx++;
                    if (lineNums.size() >= count) break;
                }
                return ToolResult.fail(String.format(
                    "old_string matched %d times in %s. It must match exactly once. "
                    + "Matches found near lines: %s. Add more surrounding context "
                    + "to make old_string unique.",
                    count, pathStr, lineNums));
            }

            // Exactly one match — replace
            String newContent = content.replace(oldStr, newStr);
            Files.writeString(path, newContent);

            int line = lineNumberAt(content, content.indexOf(oldStr), oldStr);
            return ToolResult.ok(String.format(
                "Successfully edited %s near line %d. Replaced %d occurrence.",
                pathStr, line, 1));

        } catch (IOException e) {
            return ToolResult.fail("Error editing " + pathStr + ": " + e.getMessage());
        }
    }

    /** Returns the 1-based line number at the given offset. */
    private int lineNumberAt(String content, int offset, String substring) {
        int line = 1;
        int searchUpTo = offset >= 0 ? offset : 0;
        for (int i = 0; i < searchUpTo && i < content.length(); i++) {
            if (content.charAt(i) == '\n') line++;
        }
        return line;
    }
}
