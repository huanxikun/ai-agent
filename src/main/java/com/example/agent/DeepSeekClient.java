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

    public ModelResponse createResponse(ArrayNode messages) throws Exception {
        if (apiKey.isBlank()) {
            throw new IllegalStateException("请先在 .env 中配置 DEEPSEEK_API_KEY");
        }

        ObjectNode body = json.createObjectNode();
        body.put("model", model);
        body.set("messages", messages);
        body.set("tools", toolDefinitions());
        body.put("tool_choice", "auto");
        body.putObject("thinking").put("type", "disabled");

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/chat/completions"))
                .timeout(Duration.ofSeconds(120))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
                .build();

        HttpResponse<String> response =
                http.send(request, HttpResponse.BodyHandlers.ofString());
        JsonNode payload = json.readTree(response.body());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            String message = payload == null
                    ? "DeepSeek 请求失败：" + response.statusCode()
                    : payload.path("error").path("message").asText(
                            "DeepSeek 请求失败：" + response.statusCode()
                    );
            throw new IllegalStateException(message);
        }

        return parseResponse(payload);
    }

    private ArrayNode toolDefinitions() {
        ArrayNode tools = json.createArrayNode();
        ObjectNode function = tools.addObject()
                .put("type", "function")
                .putObject("function");
        function.put("name", "get_current_time");
        function.put(
                "description",
                "获取指定 IANA 时区的当前日期和时间。返回 timeZone 和 ISO-8601 格式的 value；时区无效时返回错误。"
        );

        ObjectNode parameters = function.putObject("parameters");
        parameters.put("type", "object");
        ObjectNode timeZone = parameters.putObject("properties").putObject("timeZone");
        timeZone.put("type", "string");
        timeZone.put("description", "IANA 时区，例如 Asia/Shanghai");
        parameters.putArray("required").add("timeZone");
        parameters.put("additionalProperties", false);
        return tools;
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
}
