package com.mewcode.prompt;

import com.mewcode.provider.Message;
import org.springframework.stereotype.Component;

/**
 * Builds per-turn injection messages (plan-only reminders, tool hints, etc.).
 */
@Component
public class InjectionBuilder {

    private static final String PREFIX = "[SYSTEM INSTRUCTION] ";

    /**
     * Build a plan-only injection message for the given round.
     * Returns null if planOnly is false.
     */
    public Message buildPlanOnlyInjection(int round, boolean planOnly) {
        if (!planOnly) return null;

        if (round % 5 == 0) {
            // Full instruction
            return Message.user(PREFIX + """
                Plan-only mode is active.
                - READ tools (read_file, glob, grep) are allowed and will execute normally.
                - WRITE tools (write_file, edit_file, bash) are BLOCKED.
                - When a write tool is blocked, describe what you WOULD do instead.
                - The user will review your plan before execution.
                - Do not attempt write tools repeatedly — one description is enough.
                """.strip());
        } else {
            // Condensed reminder
            return Message.user(PREFIX + "Plan-only mode active — write tools blocked.");
        }
    }

    /** Build a general tool reminder injection (optional, use sparingly). */
    public Message buildToolReminder() {
        return Message.user(PREFIX + "Remember: prefer dedicated tools (read_file, glob, grep) over bash for file operations.");
    }
}
