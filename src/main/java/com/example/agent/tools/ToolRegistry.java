package com.example.agent.tools;

import com.example.agent.hooks.HookContext;
import com.example.agent.hooks.HookEvent;
import com.example.agent.hooks.HookRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ToolRegistry {
    private final Map<String,ToolDefinition> tools = new LinkedHashMap<>();
    private final ObjectMapper json;
    private final HookRegistry hooks;

    public ToolRegistry(ObjectMapper json, HookRegistry hooks){
        this.json = json;
        this.hooks = hooks;
    }

    public ToolRegistry register(ToolDefinition tool){
        if(tools.containsKey(tool.name())){
            throw new IllegalArgumentException("工具重复注册:"+tool.name());
        }
        tools.put(tool.name(),tool);
        return this;
    }

    public boolean hasTool(String name) {
        return tools.containsKey(name);
    }

    public List<String> toolNames() {
        return List.copyOf(tools.keySet());
    }

    public ArrayNode definitions(){
        ArrayNode definitions = json.createArrayNode();

        for(ToolDefinition tool : tools.values()){
            ObjectNode function = definitions.addObject().put("type","function").putObject("function");
            function.put("name",tool.name());
            function.put("description",tool.description());
            function.set("parameters",tool.parameters());
        }
        return definitions;
    }

    public String execute(
            String name,
            JsonNode arguments,
            HookContext context
    ) throws Exception {
        ToolDefinition tool = tools.get(name);

        if(tool == null){
            throw new IllegalArgumentException("未知工具："+name);
        }

        hooks.trigger_hooks(HookEvent.PRE_TOOL_USE, context);
        try {
            String output = tool.handler().execute(arguments, context);
            context.complete(output);
            hooks.trigger_hooks(HookEvent.POST_TOOL_USE, context);
            return output;
        } catch (Exception exception) {
            context.fail(exception);
            try {
                hooks.trigger_hooks(HookEvent.POST_TOOL_USE, context);
            } catch (Exception hookException) {
                exception.addSuppressed(hookException);
            }
            throw exception;
        }
    }


}
