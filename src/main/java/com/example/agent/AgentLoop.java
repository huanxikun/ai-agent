package com.example.agent;

import com.example.agent.tools.ToolRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * s03 的核心：模型 -> 工具 -> 权限闸门 -> 模型，直到模型返回最终文本。
 */
public final class AgentLoop {
    private static final int MAX_STEPS = 8;

    private static final String INSTRUCTIONS = """
            你是一个项目代码助手。

            回答代码问题前，先使用工具检查项目中的真实代码。
            使用 list_files 了解结构，使用 search_code 定位关键词，使用 read_file 阅读实现。
            用户明确要求创建文件时使用 create_file，修改现有文件时使用 edit_file，
            明确要求删除文件时使用 delete_file。

            create_file、edit_file 和 delete_file 只会创建人工审批请求，不会立即更改磁盘。
            工具返回 approval_required 后，不要重复调用同一个变更工具；告知用户在界面批准或拒绝。
            不要根据猜测描述项目代码。
            回答时尽量附上文件路径和行号。
            未收到工具执行成功的结果时，不要声称文件已经修改或删除。
            已有足够证据时，直接给出清晰的中文回答。
            """;

    private final DeepSeekClient model;
    private final ToolRegistry tools;
    private final ObjectMapper json;

    public AgentLoop( DeepSeekClient model,ToolRegistry tools,ObjectMapper json){
       this.model = model;
       this.tools = tools;
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
        List<JsonNode> approvals = new ArrayList<>();

        for (int step = 1; step <= MAX_STEPS; step++) {
            trace.add(event("model", "模型调用 · Step " + step, "模型正在判断下一步"));

            DeepSeekClient.ModelResponse response =
                    model.createResponse(
                            messages,
                            tools.definitions()
                    );
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
                        trace,
                        approvals
                );
            }

            for (DeepSeekClient.ToolCall call : response.toolCalls()) {
                toolCalls++;
                trace.add(event("tool", "工具 · " + call.name(), call.arguments().toString()));

                String output = tools.execute(
                        call.name(),
                        call.arguments()
                );
                JsonNode outputNode = tryParseJson(output);
                if ("approval_required".equals(outputNode.path("status").asText())) {
                    approvals.add(outputNode);
                    trace.add(event(
                            "approval",
                            "等待批准 · " + call.name(),
                            outputNode.path("path").asText()
                    ));
                } else {
                    trace.add(event("done", "工具完成 · " + call.name(), output));
                }

                ObjectNode item = messages.addObject();
                item.put("role", "tool");
                item.put("tool_call_id", call.callId());
                item.put("content", output);
            }
        }

        throw new IllegalStateException(
                "Agent 超过最大步数 " + MAX_STEPS + "，已安全停止"
        );
    }

    private JsonNode tryParseJson(String value) {
        try {
            return json.readTree(value);
        } catch (Exception exception) {
            return json.createObjectNode();
        }
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
            List<Map<String, Object>> trace,
            List<JsonNode> approvals
    ) {
    }
}
