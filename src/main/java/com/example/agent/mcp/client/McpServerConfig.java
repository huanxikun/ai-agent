package com.example.agent.mcp.client;

import java.util.List;

public record McpServerConfig(
        String name,
        String command,
        List<String> args
) {
    public McpServerConfig {
        args = List.copyOf(args);
    }
}
