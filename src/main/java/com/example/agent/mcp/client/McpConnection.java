package com.example.agent.mcp.client;

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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

final class McpConnection implements AutoCloseable {
    private final String serverName;
    private final Process process;
    private final ObjectMapper json;
    private final InputStream input;
    private final OutputStream output;
    private final AtomicInteger requestIds = new AtomicInteger(1);
    private final Map<String, McpToolDescriptor> toolsByPrefixedName;

    McpConnection(
            String serverName,
            Process process,
            ObjectMapper json,
            Map<String, McpToolDescriptor> toolsByPrefixedName
    ) {
        this.serverName = serverName;
        this.process = process;
        this.json = json;
        this.input = process.getInputStream();
        this.output = process.getOutputStream();
        this.toolsByPrefixedName = toolsByPrefixedName;
    }

    String serverName() {
        return serverName;
    }

    List<McpToolDescriptor> initializeAndList() throws Exception {
        ObjectNode initializeParams = json.createObjectNode();
        initializeParams.put("protocolVersion", "2024-11-05");
        initializeParams.putObject("clientInfo")
                .put("name", "my-agent")
                .put("version", "0.1.0");
        initializeParams.putObject("capabilities");
        call("initialize", initializeParams);
        notifyInitialized();

        JsonNode listed = call("tools/list", json.createObjectNode());
        ArrayNode toolsNode = listed.path("tools").isArray()
                ? (ArrayNode) listed.path("tools")
                : json.createArrayNode();

        List<McpToolDescriptor> tools = new ArrayList<>();
        for (JsonNode node : toolsNode) {
            String originalName = node.path("name").asText("");
            if (originalName.isBlank()) continue;
            String prefixed = "mcp__"
                    + normalizeName(serverName)
                    + "__"
                    + normalizeName(originalName);
            McpToolDescriptor descriptor = new McpToolDescriptor(
                    originalName,
                    prefixed,
                    node.path("description").asText(""),
                    node.path("inputSchema").isObject()
                            ? ((ObjectNode) node.path("inputSchema")).deepCopy()
                            : json.createObjectNode()
            );
            tools.add(descriptor);
            toolsByPrefixedName.put(prefixed, descriptor);
        }
        return List.copyOf(tools);
    }

    String callTool(String prefixedName, JsonNode arguments) throws Exception {
        McpToolDescriptor tool = toolsByPrefixedName.get(prefixedName);
        if (tool == null) {
            throw new IllegalArgumentException("未知 MCP 工具：" + prefixedName);
        }

        ObjectNode params = json.createObjectNode();
        params.put("name", tool.originalName());
        if (arguments != null && arguments.isObject()) {
            params.set("arguments", arguments.deepCopy());
        } else {
            params.putObject("arguments");
        }

        JsonNode result = call("tools/call", params);
        StringBuilder builder = new StringBuilder();
        JsonNode content = result.path("content");
        if (content.isArray()) {
            for (JsonNode item : content) {
                if (!builder.isEmpty()) builder.append(System.lineSeparator());
                builder.append(item.path("text").asText(""));
            }
        }
        if (result.path("isError").asBoolean(false)) {
            throw new IllegalStateException(
                    builder.isEmpty() ? "MCP tool call failed" : builder.toString()
            );
        }
        return builder.toString();
    }

    private void notifyInitialized() throws IOException {
        ObjectNode request = json.createObjectNode();
        request.put("jsonrpc", "2.0");
        request.put("method", "notifications/initialized");
        writeMessage(request);
    }

    private synchronized JsonNode call(String method, ObjectNode params)
            throws Exception {
        ObjectNode request = json.createObjectNode();
        request.put("jsonrpc", "2.0");
        request.put("id", requestIds.getAndIncrement());
        request.put("method", method);
        request.set("params", params);
        writeMessage(request);
        JsonNode response = readMessage();
        if (response == null) {
            throw new EOFException("MCP server disconnected: " + serverName);
        }
        if (response.has("error")) {
            throw new IllegalStateException(
                    response.path("error").path("message")
                            .asText("Unknown MCP error")
            );
        }
        return response.path("result");
    }

    private void writeMessage(JsonNode request) throws IOException {
        byte[] body = json.writeValueAsBytes(request);
        String header = "Content-Length: "
                + body.length
                + "\r\nContent-Type: application/json\r\n\r\n";
        output.write(header.getBytes(StandardCharsets.US_ASCII));
        output.write(body);
        output.flush();
    }

    private JsonNode readMessage() throws IOException {
        String line = readHeaderLine(input);
        if (line == null) return null;

        int contentLength = -1;
        while (!line.isEmpty()) {
            int separator = line.indexOf(':');
            if (separator <= 0) {
                throw new IOException("Invalid MCP header: " + line);
            }
            String headerName = line.substring(0, separator).trim();
            String value = line.substring(separator + 1).trim();
            if ("Content-Length".equalsIgnoreCase(headerName)) {
                contentLength = Integer.parseInt(value);
            }
            line = readHeaderLine(input);
            if (line == null) {
                throw new EOFException("Unexpected EOF while reading MCP headers");
            }
        }

        if (contentLength < 0) {
            throw new IOException("Missing Content-Length header");
        }

        byte[] body = input.readNBytes(contentLength);
        if (body.length != contentLength) {
            throw new EOFException("Unexpected EOF while reading MCP body");
        }
        return json.readTree(body);
    }

    private String readHeaderLine(InputStream stream) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        while (true) {
            int next = stream.read();
            if (next == -1) {
                if (buffer.size() == 0) return null;
                throw new EOFException("Unexpected EOF while reading header");
            }
            if (next == '\n') break;
            if (next != '\r') buffer.write(next);
        }
        return buffer.toString(StandardCharsets.US_ASCII);
    }

    private String normalizeName(String value) {
        return value.replaceAll("[^a-zA-Z0-9_-]", "_");
    }

    @Override
    public void close() {
        process.destroy();
        try {
            if (!process.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
    }
}
