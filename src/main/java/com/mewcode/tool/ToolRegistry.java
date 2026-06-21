package com.mewcode.tool;

import org.springframework.stereotype.Component;

import java.util.*;

@Component
public class ToolRegistry {

    private final Map<String, Tool> tools;

    public ToolRegistry(List<Tool> toolBeans) {
        Map<String, Tool> map = new LinkedHashMap<>();
        for (Tool tool : toolBeans) map.put(tool.name(), tool);
        this.tools = Collections.unmodifiableMap(map);
    }

    public Tool get(String name) { return tools.get(name); }
    public boolean isEmpty() { return tools.isEmpty(); }

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
