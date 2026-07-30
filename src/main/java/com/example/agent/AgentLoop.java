package com.example.agent;

import com.example.agent.tools.ToolRegistry;
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
    private static final String INSTRUCTIONS = """
            你是一个项目代码回答助手
            
            回答代码问题前，先使用工具检查项目中的真实代码。
            使用list_files 了解结构。
            使用search_code 定位类、方法和关键词
            使用read_file 阅读相关实现
            
            不要根据猜测描述项目代码。
            回答时尽量废除为念路径和行号。
            当前工具都是只读工具，不要声称已经修改文件
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

        int step = 0;
        while (true) {
            step++;
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
                        trace
                );
            }

            for (DeepSeekClient.ToolCall call : response.toolCalls()) {
                toolCalls++;
                trace.add(event("tool", "工具 · " + call.name(), call.arguments().toString()));

                String output = tools.execute(
                        call.name(),
                        call.arguments()
                );
                trace.add(event("done", "工具完成 · " + call.name(), output));

                ObjectNode item = messages.addObject();
                item.put("role", "tool");
                item.put("tool_call_id", call.callId());
                item.put("content", output);
            }
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
            List<Map<String, Object>> trace
    ) {
    }
}
