package com.example.agent.tools;

import com.fasterxml.jackson.databind.node.ObjectNode;

public record ToolDefinition(
    String name,
    String description,
    ObjectNode parameters,
    ToolHandler handler

){
}
