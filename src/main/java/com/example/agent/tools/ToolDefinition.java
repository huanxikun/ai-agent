package com.example.agent.tools;

import com.fasterxml.jackson.databind.node.ObjectNode;

public record ToolDefinition(
    String name,
    String description,
    ObjectNode parameters,
    ToolHandler handler,
    boolean supportsBackground,
    String mcpServer

){
    public ToolDefinition(
            String name,
            String description,
            ObjectNode parameters,
            ToolHandler handler
    ) {
        this(name, description, parameters, handler, false, null);
    }

    public ToolDefinition(
            String name,
            String description,
            ObjectNode parameters,
            ToolHandler handler,
            boolean supportsBackground
    ) {
        this(name, description, parameters, handler, supportsBackground, null);
    }

    public boolean isMcp() {
        return mcpServer != null && !mcpServer.isBlank();
    }
}
