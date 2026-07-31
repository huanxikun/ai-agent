package com.example.agent.mcp.workspace;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkspaceMcpServerTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path tempDir;

    @Test
    void workspaceServerAggregatesFilesystemAndScmTools() throws Exception {
        Assumptions.assumeTrue(gitAvailable());
        runGit("init");
        Files.writeString(tempDir.resolve("README.md"), "hello");

        WorkspaceMcpServer server = new WorkspaceMcpServer(tempDir, JSON);

        JsonNode initialize = server.handleRequest(request("initialize"));
        assertTrue(initialize.toString().contains("workspace-mcp-server"));

        JsonNode listed = server.handleRequest(request("tools/list"));
        String serialized = listed.toString();
        assertTrue(serialized.contains("list_files"));
        assertTrue(serialized.contains("read_file"));
        assertTrue(serialized.contains("search_code"));
        assertTrue(serialized.contains("git_status"));
        assertTrue(serialized.contains("git_diff"));
        assertTrue(serialized.contains("git_log"));
        assertTrue(serialized.contains("github_list_prs"));

        JsonNode status = server.handleRequest(toolCall(
                "git_status",
                JSON.createObjectNode()
        ));
        assertTrue(status.toString().contains("README.md"));
    }

    private boolean gitAvailable() {
        try {
            Process process = new ProcessBuilder("git", "--version")
                    .redirectErrorStream(true)
                    .start();
            return process.waitFor() == 0;
        } catch (Exception exception) {
            return false;
        }
    }

    private void runGit(String... args) throws Exception {
        String[] command = new String[args.length + 1];
        command[0] = "git";
        System.arraycopy(args, 0, command, 1, args.length);
        Process process = new ProcessBuilder(command)
                .directory(tempDir.toFile())
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes());
        int code = process.waitFor();
        if (code != 0) {
            throw new IllegalStateException(output);
        }
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
