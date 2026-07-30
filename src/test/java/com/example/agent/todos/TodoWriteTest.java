package com.example.agent.todos;

import com.example.agent.hooks.HookContext;
import com.example.agent.hooks.HookRegistry;
import com.example.agent.tools.ToolHandlers;
import com.example.agent.tools.ToolRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TodoWriteTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    private TodoStore store;
    private ToolRegistry tools;

    @BeforeEach
    void setUp() {
        store = new TodoStore();
        tools = new ToolRegistry(JSON, new HookRegistry());
        new ToolHandlers(store, JSON).registerInto(tools);
    }

    @Test
    void toolDefinitionExposesStatusListSchema() {
        JsonNode function = tools.definitions().path(0).path("function");

        assertEquals("todo_write", function.path("name").asText());
        JsonNode item = function.path("parameters")
                .path("properties")
                .path("todos")
                .path("items");
        assertEquals("string", item.path("properties").path("content").path("type").asText());
        assertEquals(
                List.of("pending", "in_progress", "completed"),
                JSON.convertValue(
                        item.path("properties").path("status").path("enum"),
                        List.class
                )
        );
    }

    @Test
    void todoWriteReplacesProcessStateAndPrintsTerminalView() throws Exception {
        PrintStream original = System.out;
        ByteArrayOutputStream terminal = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(terminal, true, StandardCharsets.UTF_8));
            JsonNode result = JSON.readTree(execute(todoArguments(
                    todo("检查架构", "completed"),
                    todo("实现 TodoWrite", "in_progress"),
                    todo("运行测试", "pending")
            )));

            assertEquals("updated", result.path("status").asText());
            assertEquals(3, store.snapshot().size());
            assertEquals(1, store.summary().pending());
            assertEquals(1, store.summary().inProgress());
            assertEquals(1, store.summary().completed());
        } finally {
            System.setOut(original);
        }

        String output = terminal.toString(StandardCharsets.UTF_8);
        assertTrue(output.contains("[TodoWrite"));
        assertTrue(output.contains("[x] 检查架构"));
        assertTrue(output.contains("[>] 实现 TodoWrite"));
        assertTrue(output.contains("[ ] 运行测试"));

        execute(todoArguments(todo("只保留最新列表", "pending")));
        assertEquals(
                List.of(new TodoItem("只保留最新列表", "pending")),
                store.snapshot()
        );
    }

    @Test
    void todoWriteRejectsInvalidState() {
        assertThrows(
                IllegalArgumentException.class,
                () -> execute(todoArguments(
                        todo("任务 A", "in_progress"),
                        todo("任务 B", "in_progress")
                ))
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> execute(todoArguments(todo("任务", "unknown")))
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> execute(todoArguments(
                        todo("重复", "pending"),
                        todo("重复", "completed")
                ))
        );
    }

    @Test
    void nagReminderFiresEveryThirdMissAndTodoWriteResetsIt() {
        TodoNagReminder nag = new TodoNagReminder();

        assertFalse(nag.recordRound(false));
        assertFalse(nag.recordRound(false));
        assertTrue(nag.recordRound(false));
        assertEquals(0, nag.missedRounds());

        assertFalse(nag.recordRound(false));
        assertFalse(nag.recordRound(true));
        assertEquals(0, nag.missedRounds());
        assertTrue(nag.message(store.snapshot()).contains("连续 3 轮"));
    }

    private String execute(ObjectNode arguments) throws Exception {
        return tools.execute(
                "todo_write",
                arguments,
                HookContext.forTool(
                        "todo-test",
                        "update todos",
                        "todo_write",
                        arguments,
                        1
                )
        );
    }

    private ObjectNode todoArguments(ObjectNode... items) {
        ObjectNode arguments = JSON.createObjectNode();
        ArrayNode todos = arguments.putArray("todos");
        for (ObjectNode item : items) todos.add(item);
        return arguments;
    }

    private ObjectNode todo(String content, String status) {
        return JSON.createObjectNode()
                .put("content", content)
                .put("status", status);
    }
}
