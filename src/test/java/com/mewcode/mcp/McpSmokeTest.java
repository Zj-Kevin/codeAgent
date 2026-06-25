package com.mewcode.mcp;

import com.mewcode.MewCodeApplication;
import com.mewcode.tool.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Starts the full Spring context (including @PostConstruct MCP init)
 * and verifies Context7 tools are registered.
 */
@SpringBootTest(classes = MewCodeApplication.class,
    properties = {"spring.profiles.active=test"})
public class McpSmokeTest {

    @Autowired
    private ToolRegistry toolRegistry;

    @Autowired
    private McpServerManager mcpServerManager;

    @Test
    void contextLoads() {
        // Just verify Spring context starts without error
        assertNotNull(toolRegistry);
        assertNotNull(mcpServerManager);
    }

    @Test
    void mcpServersShouldBeConfigured() {
        var infos = mcpServerManager.getServers();
        System.out.println("\n=== MCP Servers ===");
        for (var info : infos) {
            System.out.printf("  %-15s state=%-10s tools=%d%n",
                info.name(), info.state(), info.toolCount());
        }

        assertFalse(infos.isEmpty(), "Expected at least 1 configured MCP server");
        var connected = infos.stream().filter(i -> "connected".equals(i.state())).count();
        assertTrue(connected > 0, "Expected at least 1 connected server, got " + connected);
    }

    @Test
    void mcpToolsShouldBeInRegistry() {
        var mcpTools = toolRegistry.listByPrefix("mcp__");
        System.out.println("\n=== MCP Tools in Registry (" + mcpTools.size() + ") ===");
        for (var t : mcpTools) {
            System.out.printf("  - %s%n    source=%s  cats=%s%n",
                t.name(), t.source(), t.categories());
        }

        assertFalse(mcpTools.isEmpty(), "Expected MCP tools in registry");
        assertTrue(mcpTools.stream().anyMatch(t -> t.name().contains("context7")),
            "Expected context7 tools");
    }
}
