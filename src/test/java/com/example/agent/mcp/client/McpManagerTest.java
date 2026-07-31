package com.example.agent.mcp.client;

import com.example.agent.hooks.HookContext;
import com.example.agent.hooks.HookRegistry;
import com.example.agent.tools.ToolHandlers;
import com.example.agent.tools.ToolRegistry;
import com.example.agent.todos.TodoStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpManagerTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path projectRoot;

    @Test
    void connectMcpRegistersWorkspaceToolsIntoAgentRegistry() throws Exception {
        try (McpManager manager = new McpManager(projectRoot, JSON, System.getenv())) {
            ToolRegistry tools = new ToolRegistry(JSON, new HookRegistry());
            new ToolHandlers(
                    new TodoStore(),
                    null,
                    null,
                    null,
                    manager,
                    JSON
            ).registerInto(tools);

            JsonNode connected = JSON.readTree(executeTool(
                    tools,
                    "connect_mcp",
                    JSON.createObjectNode().put("name", "workspace")
            ));
            assertEquals("connected", connected.path("status").asText());
            assertTrue(tools.hasTool("mcp__workspace__git_status"));
            assertTrue(tools.hasTool("mcp__workspace__github_list_prs"));

            JsonNode alreadyConnected = JSON.readTree(executeTool(
                    tools,
                    "connect_mcp",
                    JSON.createObjectNode().put("name", "workspace")
            ));
            assertEquals("already_connected", alreadyConnected.path("status").asText());
        }
    }

    private String executeTool(
            ToolRegistry tools,
            String name,
            ObjectNode arguments
    ) throws Exception {
        return tools.execute(
                name,
                arguments,
                HookContext.forTool(
                        "mcp-test",
                        "connect mcp",
                        name,
                        arguments,
                        1
                )
        );
    }
}
