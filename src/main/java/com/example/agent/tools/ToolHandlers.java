package com.example.agent.tools;

import com.example.agent.mcp.client.McpManager;
import com.example.agent.subagents.SubagentExecutor;
import com.example.agent.skills.SkillCatalog;
import com.example.agent.tasks.PersistentTask;
import com.example.agent.tasks.TaskStore;
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
 * 非文件类工具处理器：TodoWrite、Subagent、Skill Loading 与持久 Task System。
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
    private final TaskStore taskStore;
    private final McpManager mcpManager;
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
        this(todoStore, subagent, skillCatalog, null, json);
    }

    public ToolHandlers(
            TodoStore todoStore,
            SubagentExecutor subagent,
            SkillCatalog skillCatalog,
            TaskStore taskStore,
            McpManager mcpManager,
            ObjectMapper json
    ) {
        this.todoStore = todoStore;
        this.subagent = subagent;
        this.skillCatalog = skillCatalog;
        this.taskStore = taskStore;
        this.mcpManager = mcpManager;
        this.json = json;
    }

    public ToolHandlers(
            TodoStore todoStore,
            SubagentExecutor subagent,
            SkillCatalog skillCatalog,
            TaskStore taskStore,
            ObjectMapper json
    ) {
        this(todoStore, subagent, skillCatalog, taskStore, null, json);
    }

    public void registerInto(ToolRegistry registry) {
        if (todoStore != null) registry.register(todoWrite());
        if (subagent != null) registry.register(task());
        if (mcpManager != null) registry.register(connectMcp(registry));
        registerSkillInto(registry);
        registerTaskSystemInto(registry);
    }

    public void registerSkillInto(ToolRegistry registry) {
        if (skillCatalog != null) registry.register(loadSkills());
    }

    public void registerTaskSystemInto(ToolRegistry registry) {
        if (taskStore == null) return;
        registry.register(createTask());
        registry.register(listTasks());
        registry.register(getTask());
        registry.register(claimTask());
        registry.register(completeTask());
    }

    private ToolDefinition createTask() {
        ObjectNode parameters = objectParameters();
        ObjectNode properties = (ObjectNode) parameters.path("properties");
        properties.putObject("subject")
                .put("type", "string")
                .put("description", "持久任务的简短标题");
        properties.putObject("description")
                .put("type", "string")
                .put("description", "跨会话恢复工作所需的完整描述");
        properties.putObject("blockedBy")
                .put("type", "array")
                .put("description", "必须先完成的任务 ID 列表")
                .putObject("items")
                .put("type", "string");
        parameters.putArray("required").add("subject");

        return new ToolDefinition(
                "create_task",
                "创建一个持久任务，可用 blockedBy 声明依赖。每个任务保存为 .tasks/{id}.json。",
                parameters,
                (arguments, context) -> {
                    List<String> blockedBy = stringList(
                            arguments.path("blockedBy"),
                            "blockedBy"
                    );
                    PersistentTask task = taskStore.create(
                            arguments.path("subject").asText(""),
                            arguments.path("description").asText(""),
                            blockedBy
                    );
                    System.out.printf(
                            "[Task:create] %s %s%n",
                            task.id(),
                            task.subject()
                    );
                    return json.writeValueAsString(Map.of(
                            "status", "created",
                            "task", task
                    ));
                }
        );
    }

    private ToolDefinition listTasks() {
        return new ToolDefinition(
                "list_tasks",
                "列出磁盘中所有持久任务及其状态、owner 和 blockedBy 依赖。",
                objectParameters(),
                (arguments, context) -> {
                    List<PersistentTask> tasks = taskStore.list();
                    return json.writeValueAsString(Map.of(
                            "count", tasks.size(),
                            "tasks", tasks
                    ));
                }
        );
    }

    private ToolDefinition getTask() {
        ObjectNode parameters = taskIdParameters();
        return new ToolDefinition(
                "get_task",
                "按 ID 读取一个持久任务的完整描述、状态、owner 和依赖。",
                parameters,
                (arguments, context) -> json.writeValueAsString(
                        taskStore.get(arguments.path("task_id").asText(""))
                )
        );
    }

    private ToolDefinition claimTask() {
        ObjectNode parameters = taskIdParameters();
        ObjectNode properties = (ObjectNode) parameters.path("properties");
        properties.putObject("owner")
                .put("type", "string")
                .put("description", "认领任务的 Agent 名称，默认 agent");
        return new ToolDefinition(
                "claim_task",
                "认领依赖已完成的 pending 持久任务，并将其更新为 in_progress。",
                parameters,
                (arguments, context) -> {
                    String owner = arguments.path("owner").asText("agent");
                    TaskStore.ActionResult result = taskStore.claim(
                            arguments.path("task_id").asText(""),
                            owner
                    );
                    System.out.printf(
                            "[Task:claim] %s%n",
                            result.message().replace('\n', ' ')
                    );
                    return json.writeValueAsString(result);
                }
        );
    }

    private ToolDefinition completeTask() {
        return new ToolDefinition(
                "complete_task",
                "完成一个 in_progress 持久任务，并报告因此解锁的下游任务。",
                taskIdParameters(),
                (arguments, context) -> {
                    TaskStore.ActionResult result = taskStore.complete(
                            arguments.path("task_id").asText("")
                    );
                    System.out.printf(
                            "[Task:complete] %s%n",
                            result.message().replace('\n', ' ')
                    );
                    return json.writeValueAsString(result);
                }
        );
    }

    private ObjectNode taskIdParameters() {
        ObjectNode parameters = objectParameters();
        ((ObjectNode) parameters.path("properties"))
                .putObject("task_id")
                .put("type", "string")
                .put("description", "task_ 开头的持久任务 ID");
        parameters.putArray("required").add("task_id");
        return parameters;
    }

    private ObjectNode objectParameters() {
        ObjectNode parameters = json.createObjectNode();
        parameters.put("type", "object");
        parameters.putObject("properties");
        parameters.put("additionalProperties", false);
        return parameters;
    }

    private List<String> stringList(JsonNode node, String field) {
        if (node.isMissingNode() || node.isNull()) return List.of();
        if (!node.isArray()) {
            throw new IllegalArgumentException(field + " 必须是数组");
        }
        List<String> values = new ArrayList<>();
        for (JsonNode item : node) values.add(item.asText(""));
        return List.copyOf(values);
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
        properties.putObject("run_in_background")
                .put("type", "boolean")
                .put("description", "为较慢的只读研究任务启用后台执行，稍后通过 task_notification 注入结果");
        parameters.putArray("required").add("description").add("task");
        parameters.put("additionalProperties", false);

        return new ToolDefinition(
                "task",
                "启动一个上下文隔离的只读 Subagent，适合拆分过大的代码研究任务。支持 run_in_background 后台执行；Subagent 不能再次调用 task。",
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
                },
                true
        );
    }

    private ToolDefinition connectMcp(ToolRegistry registry) {
        ObjectNode parameters = objectParameters();
        ((ObjectNode) parameters.path("properties"))
                .putObject("name")
                .put("type", "string")
                .put("description", "要连接的 MCP server，默认 workspace，可选 filesystem、scm");

        return new ToolDefinition(
                "connect_mcp",
                "连接一个 MCP server，发现其工具并动态加入当前 Agent 工具池。默认连接 workspace 聚合 server。",
                parameters,
                (arguments, context) -> {
                    String requested = arguments.path("name").asText("workspace").trim();
                    McpManager.ConnectResult result = mcpManager.connect(
                            requested,
                            registry
                    );
                    return json.writeValueAsString(Map.of(
                            "status", result.alreadyConnected()
                                    ? "already_connected"
                                    : "connected",
                            "server", result.serverName(),
                            "toolNames", result.toolNames()
                    ));
                }
        );
    }
}
