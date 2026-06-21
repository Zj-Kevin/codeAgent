package com.mewcode.prompt;

import java.util.Map;

/**
 * A composable piece of the system prompt.
 *
 * <p>Modules are ordered by {@link #priority()} and rendered
 * with variable substitution via {@code {}} placeholders.
 */
public interface PromptModule {
    int priority();
    String render(Map<String, String> vars);
    /** True if this module belongs in the cacheable prefix. */
    default boolean isStable() { return true; }
}
