package com.example.agent.mcp.client;

import com.fasterxml.jackson.databind.node.ObjectNode;

public record McpToolDescriptor(
        String originalName,
        String prefixedName,
        String description,
        ObjectNode inputSchema
) {
}
