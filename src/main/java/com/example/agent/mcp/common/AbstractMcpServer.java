package com.example.agent.mcp.common;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Minimal stdio MCP server that supports initialize, ping, tools/list and
 * tools/call over JSON-RPC 2.0 with Content-Length framing.
 */
public abstract class AbstractMcpServer {
    public static final String PROTOCOL_VERSION = "2024-11-05";

    private final String serverName;
    private final String version;
    protected final ObjectMapper json;
    private final Map<String, McpTool> tools = new LinkedHashMap<>();

    protected AbstractMcpServer(
            String serverName,
            String version,
            ObjectMapper json
    ) {
        this.serverName = serverName;
        this.version = version;
        this.json = json;
    }

    public final void register(McpTool tool) {
        if (tools.containsKey(tool.name())) {
            throw new IllegalArgumentException("MCP 工具重复注册：" + tool.name());
        }
        tools.put(tool.name(), tool);
    }

    public final void serve(InputStream input, OutputStream output)
            throws IOException {
        while (true) {
            JsonNode request = readMessage(input);
            if (request == null) return;

            JsonNode response = handleRequest(request);
            if (response != null) {
                writeMessage(output, response);
            }
        }
    }

    public final JsonNode handleRequest(JsonNode request) {
        if (request == null || !request.isObject()) {
            return error(null, -32600, "Invalid Request");
        }

        ObjectNode object = (ObjectNode) request;
        JsonNode id = object.get("id");
        String method = object.path("method").asText("");

        try {
            return switch (method) {
                case "initialize" -> success(id, initializeResult());
                case "ping" -> success(id, json.createObjectNode());
                case "tools/list" -> success(id, toolsListResult());
                case "tools/call" -> success(
                        id,
                        toolCallResult(object.path("params"))
                );
                case "notifications/initialized" -> null;
                default -> error(id, -32601, "Method not found: " + method);
            };
        } catch (IllegalArgumentException exception) {
            return error(id, -32602, exception.getMessage());
        } catch (Exception exception) {
            String message = exception.getMessage();
            if (message == null || message.isBlank()) {
                message = exception.getClass().getSimpleName();
            }
            return error(id, -32000, message);
        }
    }

    protected ObjectNode initializeResult() {
        ObjectNode result = json.createObjectNode();
        result.put("protocolVersion", PROTOCOL_VERSION);
        result.putObject("capabilities")
                .putObject("tools")
                .put("listChanged", false);
        result.putObject("serverInfo")
                .put("name", serverName)
                .put("version", version);
        return result;
    }

    private ObjectNode toolsListResult() {
        ObjectNode result = json.createObjectNode();
        ArrayNode items = result.putArray("tools");
        for (McpTool tool : tools.values()) {
            items.addObject()
                    .put("name", tool.name())
                    .put("description", tool.description())
                    .set("inputSchema", tool.inputSchema().deepCopy());
        }
        return result;
    }

    private ObjectNode toolCallResult(JsonNode params) throws Exception {
        if (!params.isObject()) {
            throw new IllegalArgumentException("tools/call.params 必须是对象");
        }
        String name = requireText((ObjectNode) params, "name");
        McpTool tool = tools.get(name);
        if (tool == null) {
            throw new IllegalArgumentException("未知 MCP 工具：" + name);
        }

        JsonNode argumentsNode = params.path("arguments");
        ObjectNode arguments = argumentsNode.isObject()
                ? (ObjectNode) argumentsNode
                : json.createObjectNode();

        try {
            return toolResult(tool.handler().execute(arguments), false);
        } catch (Exception exception) {
            String message = exception.getMessage();
            if (message == null || message.isBlank()) {
                message = exception.getClass().getSimpleName();
            }
            return toolResult("Tool error: " + message, true);
        }
    }

    private ObjectNode toolResult(String text, boolean isError) {
        ObjectNode result = json.createObjectNode();
        result.putArray("content")
                .addObject()
                .put("type", "text")
                .put("text", text == null ? "" : text);
        result.put("isError", isError);
        return result;
    }

    private ObjectNode success(JsonNode id, JsonNode result) {
        ObjectNode response = json.createObjectNode();
        response.put("jsonrpc", "2.0");
        if (id == null) {
            response.putNull("id");
        } else {
            response.set("id", id.deepCopy());
        }
        response.set("result", result);
        return response;
    }

    private ObjectNode error(JsonNode id, int code, String message) {
        ObjectNode response = json.createObjectNode();
        response.put("jsonrpc", "2.0");
        if (id == null) {
            response.putNull("id");
        } else {
            response.set("id", id.deepCopy());
        }
        response.putObject("error")
                .put("code", code)
                .put("message", message);
        return response;
    }

    private JsonNode readMessage(InputStream input) throws IOException {
        String line = readHeaderLine(input);
        if (line == null) return null;

        int contentLength = -1;
        while (!line.isEmpty()) {
            int separator = line.indexOf(':');
            if (separator <= 0) {
                throw new IOException("Invalid MCP header: " + line);
            }
            String name = line.substring(0, separator).trim();
            String value = line.substring(separator + 1).trim();
            if ("Content-Length".equalsIgnoreCase(name)) {
                contentLength = Integer.parseInt(value);
            }
            line = readHeaderLine(input);
            if (line == null) {
                throw new EOFException("Unexpected EOF while reading headers");
            }
        }

        if (contentLength < 0) {
            throw new IOException("Missing Content-Length header");
        }

        byte[] body = input.readNBytes(contentLength);
        if (body.length != contentLength) {
            throw new EOFException("Unexpected EOF while reading message body");
        }
        return json.readTree(body);
    }

    private void writeMessage(OutputStream output, JsonNode response)
            throws IOException {
        byte[] body = json.writeValueAsBytes(response);
        String header = "Content-Length: "
                + body.length
                + "\r\nContent-Type: application/json\r\n\r\n";
        output.write(header.getBytes(StandardCharsets.US_ASCII));
        output.write(body);
        output.flush();
    }

    private String readHeaderLine(InputStream input) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        while (true) {
            int next = input.read();
            if (next == -1) {
                if (buffer.size() == 0) return null;
                throw new EOFException("Unexpected EOF while reading header");
            }
            if (next == '\n') {
                break;
            }
            if (next != '\r') {
                buffer.write(next);
            }
        }
        return buffer.toString(StandardCharsets.US_ASCII);
    }

    public ObjectNode objectSchema() {
        ObjectNode schema = json.createObjectNode();
        schema.put("type", "object");
        schema.putObject("properties");
        schema.putArray("required");
        schema.put("additionalProperties", false);
        return schema;
    }

    public static String requireText(ObjectNode object, String field) {
        String value = object.path(field).asText("").trim();
        if (value.isEmpty()) {
            throw new IllegalArgumentException(field + " 不能为空");
        }
        return value;
    }

    @FunctionalInterface
    public interface ToolHandler {
        String execute(ObjectNode arguments) throws Exception;
    }

    public record McpTool(
            String name,
            String description,
            ObjectNode inputSchema,
            ToolHandler handler
    ) {
    }
}
