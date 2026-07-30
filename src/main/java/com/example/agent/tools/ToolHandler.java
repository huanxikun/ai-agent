package com.example.agent.tools;

import com.fasterxml.jackson.databind.JsonNode;

@FunctionalInterface
public interface ToolHandler {
    String execute(JsonNode arguments) throws Exception;
}
