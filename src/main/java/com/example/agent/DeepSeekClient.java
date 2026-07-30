package com.example.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 只负责把 Agent Loop 的消息转换为 DeepSeek Chat Completions 请求。
 */
public final class DeepSeekClient {
    private final String apiKey;
    private final String model;
    private final String baseUrl;
    private final ObjectMapper json;
    private final HttpClient http;

    public DeepSeekClient(String apiKey, String model, String baseUrl, ObjectMapper json) {
        this.apiKey = apiKey;
        this.model = model;
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.json = json;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .build();
    }

    public ModelResponse createResponse(
            ArrayNode messages,
            ArrayNode tools
    ) throws Exception {
        if (apiKey.isBlank()) {
            throw new IllegalStateException("请先在 .env 中配置 DEEPSEEK_API_KEY");
        }

        ObjectNode body = json.createObjectNode();
        body.put("model", model);
        body.set("messages", messages);
        body.set("tools", tools);
        body.put("tool_choice", "auto");
        body.putObject("thinking").put("type", "disabled");

        JsonNode payload = send(body);
        return parseResponse(payload);
    }

    public String summarize(ArrayNode messages) throws Exception {
        if (apiKey.isBlank()) {
            throw new IllegalStateException("请先在 .env 中配置 DEEPSEEK_API_KEY");
        }

        ArrayNode summaryMessages = json.createArrayNode();
        summaryMessages.addObject()
                .put("role", "system")
                .put("content", """
                        你是上下文压缩器。完整总结后续对话，保留：
                        用户目标、已确认事实、关键路径、工具结果、未完成事项、
                        约束、审批状态和错误。不要添加新事实，输出紧凑中文摘要。
                        """);
        summaryMessages.addAll(messages.deepCopy());

        ObjectNode body = json.createObjectNode();
        body.put("model", model);
        body.set("messages", summaryMessages);
        body.putObject("thinking").put("type", "disabled");

        JsonNode payload = send(body);
        JsonNode message = payload.path("choices").path(0).path("message");
        String summary = message.path("content").asText("").trim();
        if (summary.isEmpty()) {
            throw new IllegalStateException("DeepSeek 没有返回上下文摘要");
        }
        return summary;
    }

    private JsonNode send(ObjectNode body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/chat/completions"))
                .timeout(Duration.ofSeconds(120))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
                .build();

        HttpResponse<String> response =
                http.send(request, HttpResponse.BodyHandlers.ofString());
        JsonNode payload = null;
        try {
            payload = json.readTree(response.body());
        } catch (Exception parseError) {
            if (response.statusCode() >= 200
                    && response.statusCode() < 300) {
                throw parseError;
            }
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            String message = payload == null
                    ? "DeepSeek 请求失败：" + response.statusCode()
                    : payload.path("error").path("message").asText(
                            "DeepSeek 请求失败：" + response.statusCode()
                    );
            String code = payload == null
                    ? ""
                    : payload.path("error").path("code").asText("");
            throw new DeepSeekException(response.statusCode(), code, message);
        }
        return payload;
    }

    public static boolean isPromptTooLong(Throwable error) {
        for (Throwable current = error;
             current != null;
             current = current.getCause()) {
            if (current instanceof DeepSeekException deepSeek
                    && (deepSeek.statusCode() == 413
                    || "prompt_too_long".equalsIgnoreCase(
                            deepSeek.code()
                    ))) {
                return true;
            }
            String message = current.getMessage();
            if (message != null) {
                String normalized = message.toLowerCase();
                if (normalized.contains("prompt_too_long")
                        || normalized.contains("prompt too long")
                        || normalized.contains("context length")) {
                    return true;
                }
            }
        }
        return false;
    }

    private ModelResponse parseResponse(JsonNode payload) throws Exception {
        JsonNode message = payload.path("choices").path(0).path("message");
        if (message.isMissingNode()) {
            throw new IllegalStateException("DeepSeek 没有返回有效消息");
        }

        List<ToolCall> calls = new ArrayList<>();
        for (JsonNode item : message.path("tool_calls")) {
            JsonNode function = item.path("function");
            JsonNode arguments = json.readTree(function.path("arguments").asText("{}"));
            calls.add(new ToolCall(
                    item.path("id").asText(),
                    function.path("name").asText(),
                    arguments
            ));
        }

        return new ModelResponse(
                message.path("content").asText(""),
                calls,
                message.deepCopy()
        );
    }

    public record ToolCall(String callId, String name, JsonNode arguments) {
    }

    public record ModelResponse(
            String text,
            List<ToolCall> toolCalls,
            JsonNode assistantMessage
    ) {
    }

    public static final class DeepSeekException
            extends IllegalStateException {
        private final int statusCode;
        private final String code;

        public DeepSeekException(int statusCode, String code, String message) {
            super(message);
            this.statusCode = statusCode;
            this.code = code;
        }

        public int statusCode() {
            return statusCode;
        }

        public String code() {
            return code;
        }
    }
}
