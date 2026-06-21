package com.mewcode.security;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Static analysis of shell commands — detects known-dangerous patterns.
 */
public class DangerousCommandDetector {

    public enum Level { HIGH, MEDIUM, LOW, SAFE }

    private static final List<Rule> HIGH_RULES = List.of(
        new Rule("rm\\s+-rf\\s+/", "rm -rf /"),
        new Rule("rm\\s+-rf\\s+~", "rm -rf ~"),
        new Rule("del\\s+/[sS]\\s+/[qQ]\\s+[A-Z]:\\\\", "del /s /q drive root"),
        new Rule("rd\\s+/[sS]\\s+/[qQ]\\s+[A-Z]:\\\\", "rd /s /q drive root"),
        new Rule("format\\s+[A-Z]:", "format drive"),
        new Rule("curl.*\\|\\s*(ba)?sh", "curl | sh"),
        new Rule("curl.*\\|\\s*(ba)?sh", "curl | bash"),
        new Rule("wget.*-O\\s*-\\s*\\|", "wget -O - |"),
        new Rule(">\\s*/dev/sd[a-z]", "overwrite disk"),
        new Rule("dd\\s+if=", "dd disk copy"),
        new Rule("mkfs\\.", "mkfs format"),
        new Rule(":.*\\(\\s*\\)\\s*\\{\\s*:\\|:.*\\}\\s*;", "fork bomb"),
        new Rule("chmod\\s+-R\\s+777\\s+/", "chmod -R 777 /"),
        new Rule("sudo\\s+rm\\s+-rf\\s+/", "sudo rm -rf /")
    );

    private static final List<Rule> MEDIUM_RULES = List.of(
        new Rule("\\bkill\\b", "kill process"),
        new Rule("\\btaskkill\\b", "taskkill process"),
        new Rule("\\bchmod\\s+777\\b", "chmod 777"),
        new Rule("\\bchown\\b", "chown"),
        new Rule("\\bsudo\\b", "sudo"),
        new Rule("\\bsu\\s", "su")
    );

    private static final List<Rule> LOW_RULES = List.of(
        new Rule("\\bcurl\\b", "curl network request"),
        new Rule("\\bwget\\b", "wget network request")
    );

    public record DetectionResult(Level level, String description) {}

    /** Analyze a command and return the highest danger level found. */
    public DetectionResult check(String command) {
        if (command == null || command.isBlank()) return new DetectionResult(Level.SAFE, "");

        for (var rule : HIGH_RULES) {
            if (rule.regex.matcher(command).find()) {
                return new DetectionResult(Level.HIGH, rule.description);
            }
        }
        for (var rule : MEDIUM_RULES) {
            if (rule.regex.matcher(command).find()) {
                return new DetectionResult(Level.MEDIUM, rule.description);
            }
        }
        for (var rule : LOW_RULES) {
            if (rule.regex.matcher(command).find()) {
                return new DetectionResult(Level.LOW, rule.description);
            }
        }
        return new DetectionResult(Level.SAFE, "");
    }

    private static class Rule {
        final Pattern regex;
        final String description;
        Rule(String regex, String description) {
            this.regex = Pattern.compile(regex, Pattern.CASE_INSENSITIVE);
            this.description = description;
        }
    }
}
