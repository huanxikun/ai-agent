package com.example.agent.tools;

import com.example.agent.hooks.HookContext;
import com.fasterxml.jackson.databind.JsonNode;

@FunctionalInterface
public interface ToolHandler {
    String execute(JsonNode arguments, HookContext context) throws Exception;
}
