package com.example.agent.tools;

import com.fasterxml.jackson.databind.node.ObjectNode;

public record ToolDefinition(
    String name,
    String description,
    ObjectNode parameters,
    ToolHandler handler,
    boolean supportsBackground

){
    public ToolDefinition(
            String name,
            String description,
            ObjectNode parameters,
            ToolHandler handler
    ) {
        this(name, description, parameters, handler, false);
    }
}
