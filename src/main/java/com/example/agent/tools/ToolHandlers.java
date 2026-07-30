package com.example.agent.tools;

import com.example.agent.subagents.SubagentExecutor;
import com.example.agent.skills.SkillCatalog;
import com.example.agent.todos.TodoItem;
import com.example.agent.todos.TodoStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 非文件类工具处理器：TodoWrite、Subagent task 与 S07 load_skills。
 */
public final class ToolHandlers {
    private static final int MAX_TODOS = 100;
    private static final int MAX_CONTENT_LENGTH = 500;
    private static final Set<String> ALLOWED_STATUSES = Set.of(
            "pending",
            "in_progress",
            "completed"
    );

    private final TodoStore todoStore;
    private final SubagentExecutor subagent;
    private final SkillCatalog skillCatalog;
    private final ObjectMapper json;

    public ToolHandlers(TodoStore todoStore, ObjectMapper json) {
        this(todoStore, null, null, json);
    }

    public ToolHandlers(
            TodoStore todoStore,
            SubagentExecutor subagent,
            ObjectMapper json
    ) {
        this(todoStore, subagent, null, json);
    }

    public ToolHandlers(
            TodoStore todoStore,
            SubagentExecutor subagent,
            SkillCatalog skillCatalog,
            ObjectMapper json
    ) {
        this.todoStore = todoStore;
        this.subagent = subagent;
        this.skillCatalog = skillCatalog;
        this.json = json;
    }

    public void registerInto(ToolRegistry registry) {
        if (todoStore != null) registry.register(todoWrite());
        if (subagent != null) registry.register(task());
        registerSkillInto(registry);
    }

    public void registerSkillInto(ToolRegistry registry) {
        if (skillCatalog != null) registry.register(loadSkills());
    }

    private ToolDefinition loadSkills() {
        ObjectNode parameters = json.createObjectNode();
        parameters.put("type", "object");
        ObjectNode properties = parameters.putObject("properties");
        properties.putObject("skills")
                .put("type", "array")
                .put("description", "分析任务后决定加载的 skill 名称列表")
                .put("minItems", 1)
                .put("maxItems", 5)
                .putObject("items")
                .put("type", "string");
        parameters.putArray("required").add("skills");
        parameters.put("additionalProperties", false);

        return new ToolDefinition(
                "load_skills",
                "按需读取一个或多个 skill 的完整 SKILL.md。先分析任务，只加载真正相关的 skill。",
                parameters,
                (arguments, context) -> {
                    JsonNode skillsNode = arguments.path("skills");
                    if (!skillsNode.isArray()) {
                        throw new IllegalArgumentException("skills 必须是数组");
                    }
                    List<String> names = new ArrayList<>();
                    for (JsonNode item : skillsNode) names.add(item.asText(""));
                    return json.writeValueAsString(Map.of(
                            "status", "loaded",
                            "skills", skillCatalog.load(names)
                    ));
                }
        );
    }

    private ToolDefinition todoWrite() {
        ObjectNode parameters = json.createObjectNode();
        parameters.put("type", "object");
        ObjectNode properties = parameters.putObject("properties");
        ArrayNode statuses = properties.putObject("todos")
                .put("type", "array")
                .put("description", "完整的最新 Todo 列表；传空数组可清空列表")
                .put("maxItems", MAX_TODOS)
                .putObject("items")
                .put("type", "object")
                .putObject("properties")
                .putObject("status")
                .put("type", "string")
                .putArray("enum");
        statuses.add("pending").add("in_progress").add("completed");

        ObjectNode item = (ObjectNode) properties.path("todos").path("items");
        ObjectNode itemProperties = (ObjectNode) item.path("properties");
        itemProperties.putObject("content")
                .put("type", "string")
                .put("description", "具体、可执行的任务内容");
        item.putArray("required").add("content").add("status");
        item.put("additionalProperties", false);
        parameters.putArray("required").add("todos");
        parameters.put("additionalProperties", false);

        return new ToolDefinition(
                "todo_write",
                "用带状态的完整列表更新当前进程内 Todo。每次调用会替换旧列表并打印到终端。",
                parameters,
                (arguments, context) -> executeTodoWrite(arguments)
        );
    }

    private String executeTodoWrite(JsonNode arguments) throws Exception {
        JsonNode todosNode = arguments.path("todos");
        if (!todosNode.isArray()) {
            throw new IllegalArgumentException("todos 必须是数组");
        }
        if (todosNode.size() > MAX_TODOS) {
            throw new IllegalArgumentException("Todo 最多 " + MAX_TODOS + " 项");
        }

        List<TodoItem> items = new ArrayList<>();
        Set<String> uniqueContents = new HashSet<>();
        int inProgress = 0;
        for (JsonNode node : todosNode) {
            String content = node.path("content").asText("").trim();
            String status = node.path("status").asText("");
            if (content.isEmpty()) {
                throw new IllegalArgumentException("Todo content 不能为空");
            }
            if (content.length() > MAX_CONTENT_LENGTH) {
                throw new IllegalArgumentException(
                        "Todo content 不能超过 " + MAX_CONTENT_LENGTH + " 字符"
                );
            }
            if (!ALLOWED_STATUSES.contains(status)) {
                throw new IllegalArgumentException(
                        "Todo status 必须是 pending、in_progress 或 completed"
                );
            }
            if (!uniqueContents.add(content)) {
                throw new IllegalArgumentException("Todo 内容不能重复：" + content);
            }
            if ("in_progress".equals(status)) inProgress++;
            items.add(new TodoItem(content, status));
        }
        if (inProgress > 1) {
            throw new IllegalArgumentException("同时最多只能有一个 in_progress Todo");
        }

        TodoStore.TodoSummary summary = todoStore.replace(items);
        return json.writeValueAsString(Map.of(
                "status", "updated",
                "summary", summary,
                "todos", todoStore.snapshot()
        ));
    }

    private ToolDefinition task() {
        ObjectNode parameters = json.createObjectNode();
        parameters.put("type", "object");
        ObjectNode properties = parameters.putObject("properties");
        properties.putObject("description")
                .put("type", "string")
                .put("description", "Subagent 任务的简短名称");
        properties.putObject("task")
                .put("type", "string")
                .put("description", "单一、具体、只读的代码研究任务及期望输出");
        parameters.putArray("required").add("description").add("task");
        parameters.put("additionalProperties", false);

        return new ToolDefinition(
                "task",
                "启动一个上下文隔离的只读 Subagent，适合拆分过大的代码研究任务。Subagent 不能再次调用 task。",
                parameters,
                (arguments, context) -> {
                    String description = arguments.path("description")
                            .asText("")
                            .trim();
                    String task = arguments.path("task")
                            .asText("")
                            .trim();
                    if (description.isEmpty() || description.length() > 200) {
                        throw new IllegalArgumentException(
                                "description 必须为 1 到 200 个字符"
                        );
                    }
                    if (task.isEmpty() || task.length() > 4_000) {
                        throw new IllegalArgumentException(
                                "task 必须为 1 到 4,000 个字符"
                        );
                    }

                    SubagentExecutor.SubagentResult result = subagent.run(
                            description,
                            task,
                            context.runId()
                    );
                    return json.writeValueAsString(Map.of(
                            "status", "completed",
                            "description", description,
                            "result", result.text(),
                            "steps", result.steps(),
                            "toolCalls", result.toolCalls()
                    ));
                }
        );
    }
}
