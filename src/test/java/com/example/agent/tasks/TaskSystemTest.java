package com.example.agent.tasks;

import com.example.agent.hooks.HookContext;
import com.example.agent.hooks.HookRegistry;
import com.example.agent.tools.ToolHandlers;
import com.example.agent.tools.ToolRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskSystemTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path projectRoot;

    private TaskStore store;
    private ToolRegistry tools;

    @BeforeEach
    void setUp() {
        store = new TaskStore(projectRoot, JSON);
        tools = new ToolRegistry(JSON, new HookRegistry());
        new ToolHandlers(null, null, null, store, JSON)
                .registerInto(tools);
    }

    @Test
    void registersFiveDedicatedTaskTools() {
        assertEquals(
                List.of(
                        "create_task",
                        "list_tasks",
                        "get_task",
                        "claim_task",
                        "complete_task"
                ),
                tools.toolNames()
        );
        assertFalse(tools.hasTool("todo_write"));
        assertFalse(tools.hasTool("task"));
    }

    @Test
    void persistsTasksAcrossStoreInstances() throws Exception {
        JsonNode created = execute(
                "create_task",
                JSON.createObjectNode()
                        .put("subject", "搭建数据库")
                        .put("description", "建立 schema")
        );
        String taskId = created.path("task").path("id").asText();

        assertTrue(Files.isRegularFile(
                projectRoot.resolve(".tasks").resolve(taskId + ".json")
        ));

        TaskStore restarted = new TaskStore(projectRoot, JSON);
        PersistentTask loaded = restarted.get(taskId);
        assertEquals("搭建数据库", loaded.subject());
        assertEquals("建立 schema", loaded.description());
        assertEquals("pending", loaded.status());
        assertEquals(1, restarted.summary().total());
    }

    @Test
    void enforcesDependenciesAndReportsNewlyUnblockedTasks()
            throws Exception {
        String schemaId = create("数据库 schema", List.of());
        String apiId = create("API", List.of(schemaId));

        JsonNode blocked = execute("claim_task", taskId(apiId));
        assertEquals("rejected", blocked.path("status").asText());
        assertEquals(schemaId, blocked.path("relatedTasks").path(0).asText());
        assertEquals("pending", store.get(apiId).status());

        JsonNode claimed = execute("claim_task", taskId(schemaId));
        assertEquals("updated", claimed.path("status").asText());
        assertEquals("in_progress", store.get(schemaId).status());
        assertEquals("agent", store.get(schemaId).owner());

        JsonNode completed = execute("complete_task", taskId(schemaId));
        assertEquals("completed", store.get(schemaId).status());
        assertEquals(apiId, completed.path("relatedTasks").path(0).asText());
        assertTrue(store.canStart(apiId));

        execute(
                "claim_task",
                taskId(apiId).put("owner", "backend-agent")
        );
        assertEquals("backend-agent", store.get(apiId).owner());
        assertEquals("in_progress", store.get(apiId).status());
    }

    @Test
    void missingDependencyStaysBlockedAndInvalidIdsCannotEscape()
            throws Exception {
        String taskId = create(
                "等待外部任务",
                List.of("task_missing")
        );

        assertFalse(store.canStart(taskId));
        assertEquals(
                "rejected",
                execute("claim_task", taskId(taskId))
                        .path("status")
                        .asText()
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> store.get("../outside")
        );
    }

    @Test
    void completeRequiresAnInProgressTask() throws Exception {
        String taskId = create("尚未开始", List.of());

        JsonNode result = execute("complete_task", taskId(taskId));

        assertEquals("rejected", result.path("status").asText());
        assertEquals("pending", store.get(taskId).status());
    }

    private String create(String subject, List<String> blockedBy)
            throws Exception {
        ObjectNode arguments = JSON.createObjectNode().put("subject", subject);
        blockedBy.forEach(arguments.putArray("blockedBy")::add);
        if (blockedBy.isEmpty()) arguments.remove("blockedBy");
        return execute("create_task", arguments)
                .path("task")
                .path("id")
                .asText();
    }

    private ObjectNode taskId(String taskId) {
        return JSON.createObjectNode().put("task_id", taskId);
    }

    private JsonNode execute(String tool, ObjectNode arguments)
            throws Exception {
        String output = tools.execute(
                tool,
                arguments,
                HookContext.forTool(
                        "task-test",
                        "manage persistent tasks",
                        tool,
                        arguments,
                        1
                )
        );
        return JSON.readTree(output);
    }
}
