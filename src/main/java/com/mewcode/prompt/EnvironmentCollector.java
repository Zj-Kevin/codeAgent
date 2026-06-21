package com.mewcode.prompt;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Collects runtime environment info: OS, working directory, date, git status.
 */
@Component
public class EnvironmentCollector {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    public Map<String, String> collect() {
        var map = new LinkedHashMap<String, String>();
        map.put("os", System.getProperty("os.name"));
        map.put("cwd", System.getProperty("user.dir"));
        map.put("date", LocalDateTime.now().format(DATE_FMT));

        // Git branch
        String branch = runGit("branch", "--show-current");
        map.put("git_branch", branch.isEmpty() ? "N/A" : branch);

        // Git status (truncated)
        String status = runGit("status", "--porcelain");
        if (status.length() > 200) status = status.substring(0, 200) + "\n...";
        map.put("git_status", status.isEmpty() ? "clean" : status);

        return map;
    }

    /** Build a compact environment message string. */
    public String buildEnvironmentMessage(Map<String, String> env) {
        return String.format("""
            Working directory: %s
            OS: %s
            Date: %s
            Git branch: %s
            Git status: %s
            """,
            env.get("cwd"), env.get("os"), env.get("date"),
            env.get("git_branch"), env.get("git_status")).strip();
    }

    private String runGit(String... args) {
        try {
            String[] cmd = new String[args.length + 1];
            cmd[0] = "git";
            System.arraycopy(args, 0, cmd, 1, args.length);
            var pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            var process = pb.start();
            process.waitFor(5, TimeUnit.SECONDS);
            if (process.exitValue() != 0) return "";
            try (var in = process.getInputStream()) {
                return new String(in.readAllBytes()).strip();
            }
        } catch (IOException | InterruptedException e) {
            return "";
        }
    }
}
