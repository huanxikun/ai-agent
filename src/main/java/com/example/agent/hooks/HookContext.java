package com.example.agent.hooks;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class HookContext {
    private final String runId;
    private final String userPrompt;
    private final String toolName;
    private final JsonNode arguments;
    private final int step;
    private final Map<String, Object> attributes = new ConcurrentHashMap<>();

    private String output;
    private Throwable error;
    private String stopReason;

    private HookContext(
            String runId,
            String userPrompt,
            String toolName,
            JsonNode arguments,
            int step
    ) {
        this.runId = runId;
        this.userPrompt = userPrompt;
        this.toolName = toolName;
        this.arguments = arguments;
        this.step = step;
    }

    public static HookContext forPrompt(String runId, String userPrompt) {
        return new HookContext(runId, userPrompt, null, null, 0);
    }

    public static HookContext forTool(
            String runId,
            String userPrompt,
            String toolName,
            JsonNode arguments,
            int step
    ) {
        return new HookContext(runId, userPrompt, toolName, arguments, step);
    }

    public static HookContext forStop(
            String runId,
            String userPrompt,
            int step,
            String reason,
            Throwable error
    ) {
        HookContext context = new HookContext(runId, userPrompt, null, null, step);
        context.stopReason = reason;
        context.error = error;
        return context;
    }

    public String runId() {
        return runId;
    }

    public String userPrompt() {
        return userPrompt;
    }

    public String toolName() {
        return toolName;
    }

    public JsonNode arguments() {
        return arguments;
    }

    public int step() {
        return step;
    }

    public String output() {
        return output;
    }

    public Throwable error() {
        return error;
    }

    public String stopReason() {
        return stopReason;
    }

    public void complete(String output) {
        this.output = output;
        this.error = null;
    }

    public void fail(Throwable error) {
        this.error = error;
        this.output = null;
    }

    public void put(String key, Object value) {
        if (value == null) attributes.remove(key);
        else attributes.put(key, value);
    }

    public Object get(String key) {
        return attributes.get(key);
    }

    public <T> T require(String key, Class<T> type) {
        Object value = attributes.get(key);
        if (!type.isInstance(value)) {
            throw new IllegalStateException("Hook 没有提供必需属性：" + key);
        }
        return type.cast(value);
    }
}
