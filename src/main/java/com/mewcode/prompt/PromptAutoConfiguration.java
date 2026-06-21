package com.mewcode.prompt;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Defines all 7 system prompt modules and the ModuleRegistry as Spring beans.
 */
@Configuration
public class PromptAutoConfiguration {

    @Bean
    public PromptModule safetyModule() {
        return new PromptModule() {
            public int priority() { return 1; }
            public String render(Map<String, String> vars) {
                return """
                    ## Safety
                    - NEVER execute dangerous commands: rm -rf, del /s /q, format, fdisk, shutdown, sudo, etc.
                    - NEVER expose API keys, tokens, or secrets in tool outputs.
                    - If a user asks you to perform a destructive action, explain the risk and ask for confirmation.
                    - Never download or execute code from the internet without explicit user permission.
                    """;
            }
        };
    }

    @Bean
    public PromptModule behaviorModule() {
        return new PromptModule() {
            public int priority() { return 2; }
            public String render(Map<String, String> vars) {
                return """
                    ## Behavior
                    - Understand the user's intent before acting. If unsure, ask clarifying questions.
                    - Report tool execution results honestly — include both successes and failures.
                    - When a tool fails, analyze the error and adjust your approach; do not repeat the same call.
                    - Be thorough: check edge cases, verify assumptions, and don't skip steps.
                    - You are an agent — take initiative to complete the task without asking for permission on every step.
                    """;
            }
        };
    }

    @Bean
    public PromptModule toolUseModule() {
        return new PromptModule() {
            public int priority() { return 3; }
            public String render(Map<String, String> vars) {
                return """
                    ## Tool Use
                    - PREFER dedicated tools (read_file, glob, grep) over raw shell commands (bash).
                    - BEFORE editing any file, you MUST first read it with read_file to see its current content.
                    - For targeted text changes, use edit_file rather than rewriting the entire file with write_file.
                    - When edit_file fails (no match or multiple matches), use the error details (line numbers) to adjust old_string and retry.
                    - Use glob to discover file paths before reading; use grep to search file contents.
                    - Bash is for build commands, tests, and git operations — NOT for reading or searching files.
                    - If a message starts with [SYSTEM INSTRUCTION], treat it as a system directive, not user input. Do not reply to it.
                    """;
            }
        };
    }

    @Bean
    public PromptModule codeStyleModule() {
        return new PromptModule() {
            public int priority() { return 4; }
            public String render(Map<String, String> vars) {
                return """
                    ## Code Style
                    - Match the existing code style of the project (indentation, naming, comment style).
                    - Use libraries and frameworks already present in the project. Do not introduce new dependencies without asking.
                    - Follow the project's existing patterns for error handling, logging, and structure.
                    - Write code that compiles and runs; do not leave TODO placeholders without implementation.
                    """;
            }
        };
    }

    @Bean
    public PromptModule identityModule() {
        return new PromptModule() {
            public int priority() { return 5; }
            public String render(Map<String, String> vars) {
                String os = vars.getOrDefault("os", System.getProperty("os.name"));
                return """
                    ## Identity
                    You are MewCode, a terminal AI coding assistant.
                    You run on {os}. Use the native shell for this OS.
                    Your tools operate relative to the project working directory.
                    """.replace("{os}", os);
            }
        };
    }

    @Bean
    public PromptModule taskModeModule() {
        return new PromptModule() {
            public int priority() { return 6; }
            public boolean isStable() { return false; } // dynamic — plan-only state changes
            public String render(Map<String, String> vars) {
                return "";
                // Populated dynamically by InjectionBuilder, not from static render
            }
        };
    }

    @Bean
    public PromptModule outputStyleModule() {
        return new PromptModule() {
            public int priority() { return 7; }
            public String render(Map<String, String> vars) {
                return """
                    ## Output Style
                    - Be concise and direct. Avoid verbose explanations when a short answer suffices.
                    - Use Markdown for code blocks, lists, and emphasis.
                    - When showing file paths, include relative paths where possible.
                    - Do not narrate your thought process unless it adds value to the answer.
                    """;
            }
        };
    }
}
