package com.example.agent.mcp.filesystem;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FilesystemMcpServerTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path tempDir;

    @Test
    void initializeAndListExposeFilesystemTools() throws Exception {
        FilesystemMcpServer server = new FilesystemMcpServer(tempDir, JSON);

        JsonNode initialize = server.handleRequest(request("initialize"));
        assertEquals(
                "filesystem-mcp-server",
                initialize.path("result").path("serverInfo").path("name").asText()
        );

        JsonNode listed = server.handleRequest(request("tools/list"));
        String serialized = listed.toString();
        assertTrue(serialized.contains("list_files"));
        assertTrue(serialized.contains("read_file"));
        assertTrue(serialized.contains("search_code"));
    }

    @Test
    void readFileAndSearchCodeWorkInsideWorkspace() throws Exception {
        Path file = tempDir.resolve("src/App.java");
        Files.createDirectories(file.getParent());
        Files.writeString(
                file,
                "class App {\n    void run() {}\n}\n"
        );

        FilesystemMcpServer server = new FilesystemMcpServer(tempDir, JSON);

        JsonNode read = server.handleRequest(toolCall(
                "read_file",
                JSON.createObjectNode().put("path", "src/App.java")
        ));
        String readText = read.path("result")
                .path("content")
                .path(0)
                .path("text")
                .asText();
        assertTrue(readText.contains("1 | class App {"));
        assertTrue(readText.contains("2 |     void run() {}"));

        JsonNode search = server.handleRequest(toolCall(
                "search_code",
                JSON.createObjectNode().put("query", "run")
        ));
        String searchText = search.path("result")
                .path("content")
                .path(0)
                .path("text")
                .asText();
        assertTrue(searchText.contains("src/App.java:2"));

        JsonNode escaped = server.handleRequest(toolCall(
                "read_file",
                JSON.createObjectNode().put("path", "../outside.txt")
        ));
        assertTrue(escaped.path("result").path("isError").asBoolean());
        assertFalse(
                escaped.path("result").path("content").path(0).path("text")
                        .asText().isBlank()
        );
    }

    private ObjectNode request(String method) {
        return JSON.createObjectNode()
                .put("jsonrpc", "2.0")
                .put("id", 1)
                .put("method", method);
    }

    private ObjectNode toolCall(String toolName, ObjectNode arguments) {
        return JSON.createObjectNode()
                .put("jsonrpc", "2.0")
                .put("id", 1)
                .put("method", "tools/call")
                .set("params", JSON.createObjectNode()
                        .put("name", toolName)
                        .set("arguments", arguments));
    }
}
