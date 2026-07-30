package com.example.agent;

import com.example.agent.context.ContextCompactor;
import com.example.agent.hooks.HookContext;
import com.example.agent.hooks.HookEvent;
import com.example.agent.hooks.HookRegistry;
import com.example.agent.skills.SkillCatalog;
import com.example.agent.todos.TodoNagReminder;
import com.example.agent.todos.TodoStore;
import com.example.agent.tools.ToolRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * S08 Agent Cycle：每次构建 system 并按需加载 Skill，同时分层压缩上下文。
 */
public final class AgentLoop {
    private static final int MAX_STEPS = 8;

    private static final String INSTRUCTIONS = """
            你是一个项目代码助手。

            回答代码问题前，先使用工具检查项目中的真实代码。
            使用 list_files 了解结构，使用 search_code 定位关键词，使用 read_file 阅读实现。
            对多步骤任务，先调用 todo_write 建立完整列表；推进任务时持续更新状态。
            Todo 状态只能是 pending、in_progress 或 completed，同时最多一个 in_progress。
            当代码研究任务过大、可以独立拆分时，使用 task 委派给只读 Subagent。
            task 必须是单一、具体的研究任务；不要把简单问题或文件修改委派给 Subagent。
            用户明确要求创建文件时使用 create_file，修改现有文件时使用 edit_file，
            明确要求删除文件时使用 delete_file。

            create_file、edit_file 和 delete_file 只会创建人工审批请求，不会立即更改磁盘。
            工具返回 approval_required 后，不要重复调用同一个变更工具；告知用户在界面批准或拒绝。
            不要根据猜测描述项目代码。
            回答时尽量附上文件路径和行号。
            未收到工具执行成功的结果时，不要声称文件已经修改或删除。
            已有足够证据时，直接给出清晰的中文回答。
            """;

    private final DeepSeekClient model;
    private final ToolRegistry tools;
    private final HookRegistry hooks;
    private final TodoStore todoStore;
    private final SkillCatalog skillCatalog;
    private final ContextCompactor contextCompactor;
    private final ObjectMapper json;

    public AgentLoop(
            DeepSeekClient model,
            ToolRegistry tools,
            HookRegistry hooks,
            TodoStore todoStore,
            ObjectMapper json
    ) {
        this(model, tools, hooks, todoStore, null, null, json);
    }

    public AgentLoop(
            DeepSeekClient model,
            ToolRegistry tools,
            HookRegistry hooks,
            TodoStore todoStore,
            SkillCatalog skillCatalog,
            ObjectMapper json
    ) {
        this(model, tools, hooks, todoStore, skillCatalog, null, json);
    }

    public AgentLoop(
            DeepSeekClient model,
            ToolRegistry tools,
            HookRegistry hooks,
            TodoStore todoStore,
            SkillCatalog skillCatalog,
            ContextCompactor contextCompactor,
            ObjectMapper json
    ) {
        this.model = model;
        this.tools = tools;
        this.hooks = hooks;
        this.todoStore = todoStore;
        this.skillCatalog = skillCatalog;
        this.contextCompactor = contextCompactor;
        this.json = json;
    }

    public RunResult run(String userMessage) throws Exception {
        long startedAt = System.currentTimeMillis();
        String runId = UUID.randomUUID().toString();
        int toolCalls = 0;
        int lastStep = 0;
        boolean stopTriggered = false;
        TodoNagReminder todoNag = new TodoNagReminder();
        int stepLimit = MAX_STEPS;
        boolean reminderGraceTurnUsed = false;
        List<Map<String, Object>> trace = new ArrayList<>();
        List<JsonNode> approvals = new ArrayList<>();

        try {
            String systemPrompt = skillCatalog == null
                    ? INSTRUCTIONS
                    : skillCatalog.buildSystemPrompt(INSTRUCTIONS);
            ArrayNode messages = json.createArrayNode();
            messages.addObject()
                    .put("role", "system")
                    .put("content", systemPrompt);
            trace.add(event(
                    "system",
                    "Build System · Skill Catalog",
                    skillCatalog == null
                            ? "已注入基础 system prompt"
                            : "已注入基础 system prompt 与可用 Skill 摘要"
            ));

            hooks.trigger_hooks(
                    HookEvent.USER_PROMPT_SCRIPT,
                    HookContext.forPrompt(runId, userMessage)
            );
            trace.add(event(
                    "hook",
                    "Hook · UserPromptScript",
                    "用户输入扩展执行完成"
            ));

            messages.addObject()
                    .put("role", "user")
                    .put("content", userMessage);

            for (int step = 1; step <= stepLimit; step++) {
                lastStep = step;
                compactBeforeModel(messages, runId, step, trace);
                trace.add(event("model", "模型调用 · Step " + step, "模型正在判断下一步"));

                DeepSeekClient.ModelResponse response;
                try {
                    response = model.createResponse(
                            messages,
                            tools.definitions()
                    );
                } catch (Exception exception) {
                    if (contextCompactor == null
                            || !DeepSeekClient.isPromptTooLong(exception)) {
                        throw exception;
                    }
                    ContextCompactor.CompactReport reactive =
                            contextCompactor.reactiveCompact(messages);
                    trace.add(compactEvent(
                            "reactiveCompact",
                            step,
                            reactive
                    ));
                    response = model.createResponse(
                            messages,
                            tools.definitions()
                    );
                }
                messages.add(response.assistantMessage());
                boolean calledTodoWrite = response.toolCalls().stream()
                        .anyMatch(call -> "todo_write".equals(call.name()));
                boolean nagRequired = todoNag.recordRound(calledTodoWrite);

                if (response.toolCalls().isEmpty()) {
                    if (nagRequired) {
                        if (step == stepLimit && !reminderGraceTurnUsed) {
                            stepLimit++;
                            reminderGraceTurnUsed = true;
                        }
                        injectTodoReminder(messages, trace, todoNag);
                        continue;
                    }
                    if (response.text().isBlank()) {
                        throw new IllegalStateException("模型没有返回文本或工具调用");
                    }
                    trace.add(event("done", "运行完成", "模型返回最终答案"));
                    stopTriggered = true;
                    triggerStop(runId, userMessage, step, "completed", null, trace);
                    return new RunResult(
                            response.text(),
                            step,
                            toolCalls,
                            System.currentTimeMillis() - startedAt,
                            trace,
                            approvals
                    );
                }

                for (DeepSeekClient.ToolCall call : response.toolCalls()) {
                    toolCalls++;
                    trace.add(event(
                            "tool",
                            "工具 · " + call.name(),
                            call.arguments().toString()
                    ));
                    HookContext toolContext = HookContext.forTool(
                            runId,
                            userMessage,
                            call.name(),
                            call.arguments(),
                            step
                    );
                    trace.add(event(
                            "hook",
                            "Hook · PreToolUse",
                            call.name()
                    ));

                    String output;
                    try {
                        output = tools.execute(
                                call.name(),
                                call.arguments(),
                                toolContext
                        );
                        trace.add(event(
                                "hook",
                                "Hook · PostToolUse",
                                call.name() + " · success"
                        ));
                    } catch (Exception exception) {
                        trace.add(event(
                                "hook",
                                "Hook · PostToolUse",
                                call.name() + " · failed"
                        ));
                        throw exception;
                    }

                    JsonNode outputNode = tryParseJson(output);
                    if ("approval_required".equals(outputNode.path("status").asText())) {
                        approvals.add(outputNode);
                        trace.add(event(
                                "approval",
                                "等待批准 · " + call.name(),
                                outputNode.path("path").asText()
                        ));
                    } else {
                        trace.add(event("done", "工具完成 · " + call.name(), output));
                    }

                    ObjectNode item = messages.addObject();
                    item.put("role", "tool");
                    item.put("tool_call_id", call.callId());
                    item.put("content", output);
                }

                if (nagRequired) {
                    if (step == stepLimit && !reminderGraceTurnUsed) {
                        stepLimit++;
                        reminderGraceTurnUsed = true;
                    }
                    injectTodoReminder(messages, trace, todoNag);
                }
            }

            throw new IllegalStateException(
                    "Agent 超过最大步数 " + MAX_STEPS + "，已安全停止"
            );
        } catch (Exception exception) {
            if (!stopTriggered) {
                try {
                    triggerStop(
                            runId,
                            userMessage,
                            lastStep,
                            "failed",
                            exception,
                            trace
                    );
                } catch (Exception hookException) {
                    exception.addSuppressed(hookException);
                }
            }
            throw exception;
        }
    }

    private void compactBeforeModel(
            ArrayNode messages,
            String runId,
            int step,
            List<Map<String, Object>> trace
    ) throws Exception {
        if (contextCompactor == null) return;
        ContextCompactor.CompactReport report =
                contextCompactor.compactBeforeModel(messages, runId);
        trace.add(compactEvent("L3→L1→L2→L4", step, report));
    }

    private Map<String, Object> compactEvent(
            String stage,
            int step,
            ContextCompactor.CompactReport report
    ) {
        return event(
                "compact",
                "Context Compact · " + stage + " · Step " + step,
                "L3落盘=" + report.offloadedToolResults()
                        + "，L1删消息=" + report.removedMessages()
                        + "，L2压工具结果="
                        + report.microCompactedToolResults()
                        + "，L4摘要=" + report.autoCompacted()
                        + "，应急=" + report.reactiveCompacted()
                        + "，tokens≈" + report.tokensBefore()
                        + "→" + report.tokensAfter()
                        + "，消息=" + report.messagesAfter()
        );
    }

    private void injectTodoReminder(
            ArrayNode messages,
            List<Map<String, Object>> trace,
            TodoNagReminder todoNag
    ) {
        String reminder = todoNag.message(todoStore.snapshot());
        messages.addObject()
                .put("role", "system")
                .put("content", reminder);
        trace.add(event(
                "nag",
                "Nag Reminder · TodoWrite",
                "连续 3 轮未调用 todo_write，已向模型上下文注入提醒"
        ));
    }

    private void triggerStop(
            String runId,
            String userMessage,
            int step,
            String reason,
            Throwable error,
            List<Map<String, Object>> trace
    ) throws Exception {
        hooks.trigger_hooks(
                HookEvent.STOP,
                HookContext.forStop(runId, userMessage, step, reason, error)
        );
        trace.add(event("hook", "Hook · Stop", reason));
    }

    private JsonNode tryParseJson(String value) {
        try {
            return json.readTree(value);
        } catch (Exception exception) {
            return json.createObjectNode();
        }
    }

    private Map<String, Object> event(String kind, String title, String detail) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("kind", kind);
        value.put("title", title);
        value.put("detail", detail);
        return value;
    }

    public record RunResult(
            String text,
            int steps,
            int toolCalls,
            long durationMs,
            List<Map<String, Object>> trace,
            List<JsonNode> approvals
    ) {
    }
}
