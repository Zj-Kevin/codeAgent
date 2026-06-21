package com.mewcode.prompt;

import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Collects all {@link PromptModule} beans and assembles them into prompt sections.
 */
@Component
public class ModuleRegistry {

    private final List<PromptModule> modules;

    public ModuleRegistry(List<PromptModule> modules) {
        this.modules = modules.stream()
            .sorted(Comparator.comparingInt(PromptModule::priority))
            .toList();
    }

    /** Render all stable modules into a single string. */
    public String renderStable(Map<String, String> vars) {
        var sb = new StringBuilder();
        for (var m : modules) {
            if (!m.isStable()) continue;
            String rendered = m.render(vars);
            if (rendered != null && !rendered.isBlank()) {
                if (!sb.isEmpty()) sb.append("\n\n");
                sb.append(rendered);
            }
        }
        return sb.toString();
    }

    /** Render dynamic modules. */
    public String renderDynamic(Map<String, String> vars) {
        var sb = new StringBuilder();
        for (var m : modules) {
            if (m.isStable()) continue;
            String rendered = m.render(vars);
            if (rendered != null && !rendered.isBlank()) {
                if (!sb.isEmpty()) sb.append("\n");
                sb.append(rendered);
            }
        }
        return sb.toString();
    }

    /** Extract prompt module hash template (stable modules only) for cache tracking. */
    public List<PromptModule> stableModules() {
        return modules.stream().filter(PromptModule::isStable).toList();
    }
}
