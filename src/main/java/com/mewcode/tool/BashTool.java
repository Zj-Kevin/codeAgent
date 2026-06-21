
package com.mewcode.tool;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Executes a shell command with a configurable timeout.
 *
 * <p>Interactive commands (those requiring a TTY) are rejected.
 * stdout and stderr are merged into the result.
 */
@Component
public class BashTool implements Tool {

    private static final int DEFAULT_TIMEOUT_MS = 120_000;
    private static final List<String> INTERACTIVE_COMMANDS = List.of(
        "vim", "vi", "nano", "emacs", "less", "more", "top", "htop",
        "ssh", "telnet", "screen", "tmux", "sudo", "su"
    );

    @Override
    public String name() { return "bash"; }

    private static final boolean IS_WINDOWS = System.getProperty("os.name").toLowerCase().contains("win");

    private static final String SHELL_NAME = IS_WINDOWS ? "cmd.exe" : "bash";
    private static final String SHELL_HINT = IS_WINDOWS
        ? "This is Windows. Use cmd commands: dir (not ls), type (not cat), findstr (not grep), move (not mv), del (not rm)"
        : "This is Linux/Mac. Use standard bash commands.";
    private static final String CMD_EXAMPLES = IS_WINDOWS
        ? " Examples: dir *.java, type pom.xml, findstr \"main\" *.java, echo hello > file.txt."
        : " Examples: ls -la, cat pom.xml, grep -r \"main\" src/, echo hello > /tmp/test.txt.";

    @Override
    public String description() {
        return "Execute a shell command. " + SHELL_HINT
            + " Interactive commands (vim, ssh, etc.) are rejected." + CMD_EXAMPLES;
    }

    @Override
    public Map<String, Object> inputSchema() {
        return Map.of(
            "type", "object",
            "properties", Map.of(
                "command", Map.of("type", "string",
                    "description", SHELL_HINT + " The command to execute."),
                "timeout", Map.of("type", "integer",
                    "description", "Timeout in ms. Default 120000 (2 min). Max 600000 (10 min)")
            ),
            "required", List.of("command")
        );
    }

    @Override
    public ToolResult execute(Map<String, Object> params) {
        String command = (String) params.get("command");
        if (command == null || command.isBlank()) {
            return ToolResult.fail("Missing required parameter: command");
        }

        // Reject interactive commands
        String firstWord = command.strip().split("\\s+")[0];
        String baseName = firstWord.replaceAll(".*[/\\\\]", ""); // strip path
        if (INTERACTIVE_COMMANDS.contains(baseName.toLowerCase())) {
            return ToolResult.fail("Interactive command '" + baseName
                + "' is not allowed. Use non-interactive alternatives (e.g., 'cat' instead of 'less').");
        }

        int timeoutMs = getIntParam(params, "timeout", DEFAULT_TIMEOUT_MS);
        if (timeoutMs > 600_000) timeoutMs = 600_000; // cap at 10 min

        try {
            ProcessBuilder pb = new ProcessBuilder();
            if (System.getProperty("os.name").toLowerCase().contains("win")) {
                pb.command("cmd", "/c", command);
            } else {
                pb.command("bash", "-c", command);
            }
            pb.redirectErrorStream(true);
            pb.directory(Path.of(System.getProperty("user.dir")).toFile());

            Process process = pb.start();
            boolean finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS);

            if (!finished) {
                process.destroyForcibly();
                return ToolResult.fail("Command timed out after " + timeoutMs + "ms: " + command);
            }

            String output;
            try (var in = process.getInputStream()) {
                output = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }

            int exitCode = process.exitValue();
            if (output.length() > 100_000) {
                output = output.substring(0, 100_000) + "\n... (output truncated at 100KB)";
            }

            if (exitCode != 0) {
                return ToolResult.ok("(exit code " + exitCode + ")\n" + output);
            }
            return ToolResult.ok(output.isEmpty() ? "(command completed successfully, no output)" : output);

        } catch (IOException e) {
            return ToolResult.fail("Error executing command: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ToolResult.fail("Command was interrupted");
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
