package com.example.agent.permissions;

import com.example.agent.hooks.DefaultAgentHooks;
import com.example.agent.hooks.HookContext;
import com.example.agent.hooks.HookRegistry;
import com.example.agent.hooks.PermissionHooks;
import com.example.agent.tools.CodeTools;
import com.example.agent.tools.ToolRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PermissionFlowTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path projectRoot;

    private FilePermissionService permissions;
    private HumanApprovalGate approvals;
    private ToolRegistry tools;
    private HookRegistry hooks;

    @BeforeEach
    void setUp() throws Exception {
        permissions = new FilePermissionService(projectRoot);
        approvals = new HumanApprovalGate();
        hooks = new HookRegistry();
        DefaultAgentHooks.register_hooks(hooks);
        PermissionHooks.register_hooks(hooks, permissions);
        tools = new ToolRegistry(JSON, hooks);
        new CodeTools(projectRoot, approvals, hooks, JSON).registerInto(tools);
    }

    @Test
    void firstGateRejectsPathTraversal() throws Exception {
        Files.writeString(projectRoot.resolve("safe.txt"), "safe");

        SecurityException error = assertThrows(
                SecurityException.class,
                () -> permissions.check("../outside.txt", FileOperation.READ)
        );

        assertTrue(error.getMessage().contains("闸门 1"));
    }

    @Test
    void secondGateRejectsSensitiveFile() throws Exception {
        Files.writeString(projectRoot.resolve(".env"), "SECRET=value");

        SecurityException error = assertThrows(
                SecurityException.class,
                () -> permissions.check(".env", FileOperation.EDIT)
        );

        assertTrue(error.getMessage().contains("闸门 2"));
    }

    @Test
    void editOnlyRunsAfterOneTimeApproval() throws Exception {
        Path file = projectRoot.resolve("Example.java");
        Files.writeString(file, "class Example { int value = 1; }");

        JsonNode request = JSON.readTree(executeTool(
                "edit_file",
                editArguments("Example.java", "value = 1", "value = 2")
        ));

        assertEquals("approval_required", request.path("status").asText());
        assertEquals("class Example { int value = 1; }", Files.readString(file));

        approvals.approve(request.path("approvalId").asText());

        assertEquals("class Example { int value = 2; }", Files.readString(file));
        assertThrows(
                IllegalArgumentException.class,
                () -> approvals.approve(request.path("approvalId").asText())
        );
    }

    @Test
    void createOnlyRunsAfterApprovalAndNeverOverwrites() throws Exception {
        Path file = projectRoot.resolve("Created.java");
        JsonNode request = JSON.readTree(executeTool(
                "create_file",
                createArguments("Created.java", "class Created {}")
        ));

        assertEquals("approval_required", request.path("status").asText());
        assertFalse(Files.exists(file));

        approvals.approve(request.path("approvalId").asText());

        assertEquals("class Created {}", Files.readString(file));
        assertThrows(
                IllegalArgumentException.class,
                () -> executeTool(
                        "create_file",
                        createArguments("Created.java", "overwrite")
                )
        );
        assertEquals("class Created {}", Files.readString(file));
    }

    @Test
    void fileCreatedWhileWaitingCancelsApproval() throws Exception {
        Path file = projectRoot.resolve("Race.txt");
        JsonNode request = JSON.readTree(executeTool(
                "create_file",
                createArguments("Race.txt", "agent content")
        ));

        Files.writeString(file, "someone else's content");

        assertThrows(
                IllegalArgumentException.class,
                () -> approvals.approve(request.path("approvalId").asText())
        );
        assertEquals("someone else's content", Files.readString(file));
    }

    @Test
    void changedFileCancelsApprovedEdit() throws Exception {
        Path file = projectRoot.resolve("Example.java");
        Files.writeString(file, "before");
        JsonNode request = JSON.readTree(executeTool(
                "edit_file",
                editArguments("Example.java", "before", "after")
        ));

        Files.writeString(file, "changed by someone else");

        assertThrows(
                IllegalStateException.class,
                () -> approvals.approve(request.path("approvalId").asText())
        );
        assertEquals("changed by someone else", Files.readString(file));
    }

    @Test
    void rejectedDeleteKeepsFileAndApprovedDeleteRemovesIt() throws Exception {
        Path keep = projectRoot.resolve("keep.txt");
        Files.writeString(keep, "keep");
        JsonNode rejected = JSON.readTree(executeTool(
                "delete_file",
                pathArguments("keep.txt")
        ));
        approvals.reject(rejected.path("approvalId").asText());
        assertTrue(Files.exists(keep));

        Path remove = projectRoot.resolve("remove.txt");
        Files.writeString(remove, "remove", StandardCharsets.UTF_8);
        JsonNode approved = JSON.readTree(executeTool(
                "delete_file",
                pathArguments("remove.txt")
        ));
        approvals.approve(approved.path("approvalId").asText());
        assertFalse(Files.exists(remove));
    }

    @Test
    void humanGateDoesNotExecuteBeforeApproval() throws Exception {
        AtomicInteger executions = new AtomicInteger();
        HumanApprovalGate.ApprovalRequest request = approvals.request(
                FileOperation.EDIT,
                "file.txt",
                "preview",
                () -> {
                    executions.incrementAndGet();
                    return "done";
                }
        );

        assertEquals(0, executions.get());
        approvals.approve(request.approvalId());
        assertEquals(1, executions.get());
    }

    private ObjectNode editArguments(String path, String oldText, String newText) {
        return JSON.createObjectNode()
                .put("path", path)
                .put("oldText", oldText)
                .put("newText", newText);
    }

    private String executeTool(String name, JsonNode arguments) throws Exception {
        return tools.execute(
                name,
                arguments,
                HookContext.forTool(
                        "test-run",
                        "test prompt",
                        name,
                        arguments,
                        1
                )
        );
    }

    private ObjectNode createArguments(String path, String content) {
        return JSON.createObjectNode()
                .put("path", path)
                .put("content", content);
    }

    private ObjectNode pathArguments(String path) {
        return JSON.createObjectNode().put("path", path);
    }
}
