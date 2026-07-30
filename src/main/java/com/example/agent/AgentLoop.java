package com.example.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * s01 的全部核心：模型 -> 工具 -> 模型，直到模型返回最终文本。
 */
public final class AgentLoop {
    private static final int MAX_STEPS = 5;

    private static final String INSTRUCTIONS = """
            你是一个简洁、可靠的中文助手。
            当用户询问当前日期或时间时，调用 get_current_time 工具。
            工具返回结果后，根据结果回答，不要编造时间。
            已有足够信息时直接给出最终答案。
            """;

    private final DeepSeekClient model;
    private final ObjectMapper json;

    public AgentLoop(DeepSeekClient model, ObjectMapper json) {
        this.model = model;
        this.json = json;
    }

    public RunResult run(String userMessage) throws Exception {
        long startedAt = System.currentTimeMillis();
        ArrayNode messages = json.createArrayNode();
        messages.addObject()
                .put("role", "system")
                .put("content", INSTRUCTIONS);
        messages.addObject()
                .put("role", "user")
                .put("content", userMessage);
        int toolCalls = 0;
        List<Map<String, Object>> trace = new ArrayList<>();

        for (int step = 1; step <= MAX_STEPS; step++) {
            trace.add(event("model", "模型调用 · Step " + step, "模型正在判断下一步"));

            DeepSeekClient.ModelResponse response =
                    model.createResponse(messages);
            messages.add(response.assistantMessage());

            if (response.toolCalls().isEmpty()) {
                if (response.text().isBlank()) {
                    throw new IllegalStateException("模型没有返回文本或工具调用");
                }
                trace.add(event("done", "运行完成", "模型返回最终答案"));
                return new RunResult(
                        response.text(),
                        step,
                        toolCalls,
                        System.currentTimeMillis() - startedAt,
                        trace
                );
            }

            for (DeepSeekClient.ToolCall call : response.toolCalls()) {
                toolCalls++;
                trace.add(event("tool", "工具 · " + call.name(), call.arguments().toString()));

                String output = executeTool(call);
                trace.add(event("done", "工具完成 · " + call.name(), output));

                ObjectNode item = messages.addObject();
                item.put("role", "tool");
                item.put("tool_call_id", call.callId());
                item.put("content", output);
            }
        }

        throw new IllegalStateException("Agent 超过最大步数，已安全停止");
    }

    private String executeTool(DeepSeekClient.ToolCall call) throws Exception {
        if (!"get_current_time".equals(call.name())) {
            throw new IllegalArgumentException("未知工具：" + call.name());
        }

        String timeZone = call.arguments().path("timeZone").asText("Asia/Shanghai");
        ZoneId zoneId;
        try {
            zoneId = ZoneId.of(timeZone);
        } catch (Exception exception) {
            throw new IllegalArgumentException("无效时区：" + timeZone);
        }

        ObjectNode result = json.createObjectNode();
        result.put("timeZone", timeZone);
        result.put(
                "value",
                ZonedDateTime.now(zoneId).format(DateTimeFormatter.ISO_ZONED_DATE_TIME)
        );
        return json.writeValueAsString(result);
    }

    private Map<String, Object> event(String kind, String title, String detail) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("kind", kind);
        value.put("title", title);
        value.put("detail", detail);
        return value;
    }

    public record RunResult(
            String text,
            int steps,
            int toolCalls,
            long durationMs,
            List<Map<String, Object>> trace
    ) {
    }
}
