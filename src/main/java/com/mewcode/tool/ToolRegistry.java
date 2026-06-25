package com.mewcode.tool;

import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class ToolRegistry {

    private final ConcurrentHashMap<String, Tool> tools = new ConcurrentHashMap<>();

    public ToolRegistry(List<Tool> toolBeans) {
        for (Tool tool : toolBeans) {
            tools.put(tool.name(), tool);
        }
    }

    /** Get a tool by full name. */
    public Tool get(String name) { return tools.get(name); }

    public boolean isEmpty() { return tools.isEmpty(); }

    /** Get an immutable snapshot of all registered tools. */
    public Collection<Tool> getAll() {
        return List.copyOf(tools.values());
    }

    // ── Dynamic registration ──────────────────────────────────

    /** Register a single tool (e.g. MCP-discovered tool). */
    public void register(Tool tool) {
        tools.put(tool.name(), tool);
    }

    /** Unregister a single tool by its full name. */
    public void unregister(String name) {
        tools.remove(name);
    }

    /** Register a batch of tools at once. */
    public void registerAll(Collection<Tool> batch) {
        for (var tool : batch) {
            tools.put(tool.name(), tool);
        }
    }

    /** Unregister all tools whose names start with the given prefix. */
    public void unregisterByPrefix(String prefix) {
        tools.keySet().removeIf(name -> name.startsWith(prefix));
    }

    /** List tools by prefix. */
    public List<Tool> listByPrefix(String prefix) {
        return tools.entrySet().stream()
            .filter(e -> e.getKey().startsWith(prefix))
            .map(Map.Entry::getValue)
            .toList();
    }

    // ── Format conversion ─────────────────────────────────────

    public List<Map<String, Object>> toAnthropicFormat() {
        var list = new ArrayList<Map<String, Object>>();
        for (var tool : tools.values()) {
            var map = new LinkedHashMap<String, Object>();
            map.put("name", tool.name());
            map.put("description", tool.description());
            map.put("input_schema", tool.inputSchema());
            list.add(map);
        }
        return list;
    }

    public List<Map<String, Object>> toOpenAIFormat() {
        var list = new ArrayList<Map<String, Object>>();
        for (var tool : tools.values()) {
            var func = new LinkedHashMap<String, Object>();
            func.put("name", tool.name());
            func.put("description", tool.description());
            func.put("parameters", tool.inputSchema());
            var map = new LinkedHashMap<String, Object>();
            map.put("type", "function");
            map.put("function", func);
            list.add(map);
        }
        return list;
    }
}
