package com.example.agent;

import com.example.agent.context.ContextCompactor;
import com.example.agent.hooks.HookContext;
import com.example.agent.hooks.HookEvent;
import com.example.agent.hooks.HookRegistry;
import com.example.agent.memory.MemorySystem;
import com.example.agent.prompts.SystemPromptAssembler;
import com.example.agent.recovery.ErrorRecovery;
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
 * S11 Agent Cycle：在 compress + memory load 后对业务 LLM 分类恢复。
 */
public final class AgentLoop {
    private static final int MAX_STEPS = 8;

    private final DeepSeekClient model;
    private final ToolRegistry tools;
    private final HookRegistry hooks;
    private final TodoStore todoStore;
    private final ContextCompactor contextCompactor;
    private final MemorySystem memorySystem;
    private final SystemPromptAssembler systemPromptAssembler;
    private final ErrorRecovery errorRecovery;
    private final ObjectMapper json;

    public AgentLoop(
            DeepSeekClient model,
            ToolRegistry tools,
            HookRegistry hooks,
            TodoStore todoStore,
            ContextCompactor contextCompactor,
            MemorySystem memorySystem,
            SystemPromptAssembler systemPromptAssembler,
            ErrorRecovery errorRecovery,
            ObjectMapper json
    ) {
        this.model = model;
        this.tools = tools;
        this.hooks = hooks;
        this.todoStore = todoStore;
        this.contextCompactor = contextCompactor;
        this.memorySystem = memorySystem;
        this.systemPromptAssembler = systemPromptAssembler;
        this.errorRecovery = errorRecovery;
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
        ErrorRecovery.RecoveryState recoveryState =
                errorRecovery.newState();
        StringBuilder recoveredOutput = new StringBuilder();

        try {
            SystemPromptAssembler.PromptContent initialContent =
                    requirePromptAssembler().update_content();
            String systemPrompt = systemPromptAssembler.get_system_prompt(
                    initialContent
            );
            ArrayNode messages = json.createArrayNode();
            messages.addObject()
                    .put("role", "system")
                    .put("content", systemPrompt);
            trace.add(event(
                    "system",
                    "Build System · Runtime Assembly",
                    "sections="
                            + systemPromptAssembler.loadedSections(
                                    initialContent
                            )
                            + "，cache="
                            + systemPromptAssembler.cacheStats()
            ));

            MemorySystem.LoadedMemories loadedMemories =
                    memorySystem == null
                            ? MemorySystem.LoadedMemories.empty()
                            : memorySystem.loadRelevant(userMessage);
            trace.add(event(
                    "memory",
                    "Memory · Intelligent Loading",
                    "相关记忆=" + loadedMemories.entries().size()
                            + "，加载字节=" + loadedMemories.bytes()
                            + "；将在压缩管线后注入请求副本"
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
            ArrayNode memoryTranscript = json.createArrayNode();
            memoryTranscript.addObject()
                    .put("role", "user")
                    .put("content", userMessage);

            stepLoop:
            for (int step = 1; step <= stepLimit; step++) {
                lastStep = step;
                refreshSystemPrompt(messages, step, trace);
                compactBeforeModel(messages, runId, step, trace);
                trace.add(event("model", "模型调用 · Step " + step, "模型正在判断下一步"));

                DeepSeekClient.ModelResponse response;
                while (true) {
                    response = callBusinessModel(
                            messages,
                            loadedMemories,
                            recoveryState,
                            trace,
                            step
                    );
                    if (!"max_tokens".equals(response.stopReason())) break;

                    ErrorRecovery.MaxTokensAction action =
                            errorRecovery.handleMaxTokens(recoveryState);
                    trace.add(event(
                            "recovery",
                            "Error Recovery · max_tokens",
                            "action=" + action
                                    + "，maxTokens="
                                    + recoveryState.maxTokens()
                                    + "，continuations="
                                    + recoveryState.continuations()
                    ));
                    if (action
                            == ErrorRecovery.MaxTokensAction.ESCALATE_AND_RETRY) {
                        continue;
                    }

                    ObjectNode truncated = truncatedAssistant(response);
                    messages.add(truncated);
                    memoryTranscript.add(truncated.deepCopy());
                    appendRecovered(recoveredOutput, response.text());
                    if (action
                            == ErrorRecovery.MaxTokensAction.APPEND_AND_CONTINUE) {
                        if (step == stepLimit) stepLimit++;
                        messages.addObject()
                                .put("role", "user")
                                .put(
                                        "content",
                                        ErrorRecovery.CONTINUATION_PROMPT
                                );
                        memoryTranscript.addObject()
                                .put("role", "user")
                                .put(
                                        "content",
                                        ErrorRecovery.CONTINUATION_PROMPT
                                );
                        continue stepLoop;
                    }

                    String exhaustedText = recoveredOutput.toString().trim();
                    if (exhaustedText.isEmpty()) {
                        exhaustedText = "[输出达到 token 恢复上限]";
                    }
                    trace.add(event(
                            "recovery",
                            "Error Recovery · max_tokens exhausted",
                            "已完成 1 次升级与 "
                                    + ErrorRecovery.MAX_CONTINUATIONS
                                    + " 次续写"
                    ));
                    extractMemories(memoryTranscript, trace);
                    stopTriggered = true;
                    triggerStop(
                            runId,
                            userMessage,
                            step,
                            "max_tokens_recovery_exhausted",
                            null,
                            trace
                    );
                    return new RunResult(
                            exhaustedText,
                            step,
                            toolCalls,
                            System.currentTimeMillis() - startedAt,
                            trace,
                            approvals
                    );
                }
                messages.add(response.assistantMessage());
                memoryTranscript.add(response.assistantMessage().deepCopy());
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
                    extractMemories(memoryTranscript, trace);
                    stopTriggered = true;
                    triggerStop(runId, userMessage, step, "completed", null, trace);
                    String finalText = recoveredOutput.isEmpty()
                            ? response.text()
                            : recoveredOutput + "\n" + response.text();
                    return new RunResult(
                            finalText.trim(),
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
                    memoryTranscript.add(item.deepCopy());
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

    private DeepSeekClient.ModelResponse callBusinessModel(
            ArrayNode messages,
            MemorySystem.LoadedMemories loadedMemories,
            ErrorRecovery.RecoveryState state,
            List<Map<String, Object>> trace,
            int step
    ) throws Exception {
        try {
            return withTransientRetry(
                    messages,
                    loadedMemories,
                    state,
                    trace,
                    step
            );
        } catch (Exception exception) {
            if (contextCompactor == null
                    || !DeepSeekClient.isPromptTooLong(exception)
                    || state.reactiveCompactAttempted()) {
                throw exception;
            }
            ContextCompactor.CompactReport reactive =
                    contextCompactor.reactiveCompact(messages);
            state.markReactiveCompactAttempted();
            trace.add(compactEvent(
                    "prompt_too_long → reactiveCompact",
                    step,
                    reactive
            ));
            return withTransientRetry(
                    messages,
                    loadedMemories,
                    state,
                    trace,
                    step
            );
        }
    }

    private DeepSeekClient.ModelResponse withTransientRetry(
            ArrayNode messages,
            MemorySystem.LoadedMemories loadedMemories,
            ErrorRecovery.RecoveryState state,
            List<Map<String, Object>> trace,
            int step
    ) throws Exception {
        return errorRecovery.withRetry(
                (maxTokens, requestedModel) -> model.createResponse(
                        requestMessages(messages, loadedMemories),
                        tools.definitions(),
                        maxTokens,
                        requestedModel
                ),
                state,
                recoveryEvent -> trace.add(event(
                        "recovery",
                        "Error Recovery · " + recoveryEvent.kind()
                                + " · Step " + step,
                        "attempt=" + recoveryEvent.attempt()
                                + "，delayMs=" + recoveryEvent.delayMs()
                                + "，" + recoveryEvent.detail()
                ))
        );
    }

    private ObjectNode truncatedAssistant(
            DeepSeekClient.ModelResponse response
    ) {
        ObjectNode message = json.createObjectNode();
        message.put("role", "assistant");
        message.put("content", response.text());
        return message;
    }

    private void appendRecovered(
            StringBuilder recoveredOutput,
            String text
    ) {
        if (text == null || text.isBlank()) return;
        if (!recoveredOutput.isEmpty()) recoveredOutput.append('\n');
        recoveredOutput.append(text);
    }

    private SystemPromptAssembler requirePromptAssembler() {
        if (systemPromptAssembler == null) {
            throw new IllegalStateException(
                    "S10 SystemPromptAssembler 未配置"
            );
        }
        return systemPromptAssembler;
    }

    private void refreshSystemPrompt(
            ArrayNode messages,
            int step,
            List<Map<String, Object>> trace
    ) throws Exception {
        SystemPromptAssembler.PromptContent content =
                requirePromptAssembler().update_content();
        String prompt = systemPromptAssembler.get_system_prompt(content);
        if (!messages.isEmpty()
                && messages.get(0) instanceof ObjectNode systemMessage) {
            systemMessage.put("content", prompt);
        }
        trace.add(event(
                "system",
                "System Prompt · Step " + step,
                "sections=" + systemPromptAssembler.loadedSections(content)
                        + "，cache=" + systemPromptAssembler.cacheStats()
        ));
    }

    private ArrayNode requestMessages(
            ArrayNode compactedMessages,
            MemorySystem.LoadedMemories loadedMemories
    ) {
        if (memorySystem == null) return compactedMessages;
        return memorySystem.injectAfterCompaction(
                compactedMessages,
                loadedMemories
        );
    }

    private void extractMemories(
            ArrayNode memoryTranscript,
            List<Map<String, Object>> trace
    ) {
        if (memorySystem == null) return;
        MemorySystem.ExtractionResult result =
                memorySystem.extractAndConsolidate(memoryTranscript);
        if (result.error() != null) {
            trace.add(event(
                    "memory",
                    "Memory · Extraction partially completed",
                    "新增=" + result.extracted()
                            + "，整理=false，错误=" + result.error()
            ));
            return;
        }
        trace.add(event(
                "memory",
                "Memory · End-of-turn Extraction",
                "新增=" + result.extracted()
                        + "，整理=" + result.consolidated()
        ));
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
