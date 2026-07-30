package com.example.agent.subagents;

import com.example.agent.DeepSeekClient;
import com.example.agent.context.ContextCompactor;
import com.example.agent.hooks.HookContext;
import com.example.agent.hooks.HookEvent;
import com.example.agent.hooks.HookRegistry;
import com.example.agent.prompts.SystemPromptAssembler;
import com.example.agent.recovery.ErrorRecovery;
import com.example.agent.tools.ToolRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.UUID;

/**
 * 使用 S10 Prompt 与 S11 错误恢复的只读、不可递归 Subagent。
 */
public final class Subagent implements SubagentExecutor {
    public static final int DEFAULT_MAX_STEPS = 24;

    private final ModelCall model;
    private final ToolRegistry tools;
    private final HookRegistry hooks;
    private final ContextCompactor contextCompactor;
    private final SystemPromptAssembler systemPromptAssembler;
    private final ErrorRecovery errorRecovery;
    private final int maxSteps;
    private final ObjectMapper json;

    public Subagent(
            DeepSeekClient model,
            ToolRegistry tools,
            HookRegistry hooks,
            ContextCompactor contextCompactor,
            SystemPromptAssembler systemPromptAssembler,
            ErrorRecovery errorRecovery,
            ObjectMapper json
    ) {
        this(
                model::createResponse,
                tools,
                hooks,
                contextCompactor,
                systemPromptAssembler,
                errorRecovery,
                DEFAULT_MAX_STEPS,
                json
        );
    }

    public Subagent(
            DeepSeekClient model,
            ToolRegistry tools,
            HookRegistry hooks,
            ContextCompactor contextCompactor,
            SystemPromptAssembler systemPromptAssembler,
            ErrorRecovery errorRecovery,
            int maxSteps,
            ObjectMapper json
    ) {
        this(
                model::createResponse,
                tools,
                hooks,
                contextCompactor,
                systemPromptAssembler,
                errorRecovery,
                maxSteps,
                json
        );
    }

    Subagent(
            ModelCall model,
            ToolRegistry tools,
            HookRegistry hooks,
            ContextCompactor contextCompactor,
            SystemPromptAssembler systemPromptAssembler,
            ErrorRecovery errorRecovery,
            ObjectMapper json
    ) {
        this(
                model,
                tools,
                hooks,
                contextCompactor,
                systemPromptAssembler,
                errorRecovery,
                DEFAULT_MAX_STEPS,
                json
        );
    }

    Subagent(
            ModelCall model,
            ToolRegistry tools,
            HookRegistry hooks,
            ContextCompactor contextCompactor,
            SystemPromptAssembler systemPromptAssembler,
            ErrorRecovery errorRecovery,
            int maxSteps,
            ObjectMapper json
    ) {
        this.model = model;
        this.tools = tools;
        this.hooks = hooks;
        this.contextCompactor = contextCompactor;
        this.systemPromptAssembler = systemPromptAssembler;
        this.errorRecovery = errorRecovery;
        this.maxSteps = normalizeMaxSteps(maxSteps);
        this.json = json;
    }

    @Override
    public SubagentResult run(
            String description,
            String task,
            String parentRunId
    ) throws Exception {
        String runId = parentRunId + "/subagent/" + UUID.randomUUID();
        int lastStep = 0;
        int toolCalls = 0;
        boolean stopped = false;
        int stepLimit = maxSteps;
        ErrorRecovery.RecoveryState recoveryState =
                errorRecovery.newState();
        StringBuilder recoveredOutput = new StringBuilder();

        System.out.printf(
                "%n[Subagent start] %s%n  task: %s%n",
                description,
                task
        );

        try {
            String systemPrompt =
                    systemPromptAssembler.get_system_prompt(
                            systemPromptAssembler.update_content()
                    );
            ArrayNode messages = json.createArrayNode();
            messages.addObject()
                    .put("role", "system")
                    .put("content", systemPrompt);

            hooks.trigger_hooks(
                    HookEvent.USER_PROMPT_SCRIPT,
                    HookContext.forPrompt(runId, task)
            );

            messages.addObject()
                    .put("role", "user")
                    .put("content", task);

            stepLoop:
            for (int step = 1; step <= stepLimit; step++) {
                lastStep = step;
                String refreshed =
                        systemPromptAssembler.get_system_prompt(
                                systemPromptAssembler.update_content()
                        );
                ((ObjectNode) messages.get(0)).put(
                        "content",
                        refreshed
                );
                if (contextCompactor != null) {
                    ContextCompactor.CompactReport compact =
                            contextCompactor.compactBeforeModel(
                                    messages,
                                    runId
                            );
                    if (compact.changed()) {
                        System.out.printf(
                                "[Subagent compact] L3=%d L1=%d L2=%d L4=%s tokens≈%d→%d%n",
                                compact.offloadedToolResults(),
                                compact.removedMessages(),
                                compact.microCompactedToolResults(),
                                compact.autoCompacted(),
                                compact.tokensBefore(),
                                compact.tokensAfter()
                        );
                    }
                }

                DeepSeekClient.ModelResponse response;
                while (true) {
                    response = callBusinessModel(messages, recoveryState);
                    if (!"max_tokens".equals(response.stopReason())) break;
                    ErrorRecovery.MaxTokensAction action =
                            errorRecovery.handleMaxTokens(recoveryState);
                    System.out.printf(
                            "[Subagent recovery] max_tokens action=%s max=%d continuation=%d%n",
                            action,
                            recoveryState.maxTokens(),
                            recoveryState.continuations()
                    );
                    if (action
                            == ErrorRecovery.MaxTokensAction.ESCALATE_AND_RETRY) {
                        continue;
                    }
                    ObjectNode truncated = json.createObjectNode();
                    truncated.put("role", "assistant");
                    truncated.put("content", response.text());
                    messages.add(truncated);
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
                        continue stepLoop;
                    }
                    triggerStop(
                            runId,
                            task,
                            step,
                            "max_tokens_recovery_exhausted",
                            null
                    );
                    stopped = true;
                    return new SubagentResult(
                            recoveredOutput.toString().trim(),
                            step,
                            toolCalls
                    );
                }
                messages.add(response.assistantMessage());

                if (response.toolCalls().isEmpty()) {
                    if (response.text().isBlank()) {
                        throw new IllegalStateException(
                                "Subagent 没有返回文本或工具调用"
                        );
                    }
                    triggerStop(runId, task, step, "completed", null);
                    stopped = true;
                    System.out.printf(
                            "[Subagent done] steps=%d tools=%d%n",
                            step,
                            toolCalls
                    );
                    String finalText = recoveredOutput.isEmpty()
                            ? response.text()
                            : recoveredOutput + "\n" + response.text();
                    return new SubagentResult(
                            finalText.trim(),
                            step,
                            toolCalls
                    );
                }

                for (DeepSeekClient.ToolCall call : response.toolCalls()) {
                    toolCalls++;
                    if ("task".equals(call.name())) {
                        throw new IllegalStateException(
                                "Subagent 禁止递归调用 task"
                        );
                    }

                    HookContext context = HookContext.forTool(
                            runId,
                            task,
                            call.name(),
                            call.arguments(),
                            step
                    );
                    String output = tools.execute(
                            call.name(),
                            call.arguments(),
                            context
                    );
                    ObjectNode toolMessage = messages.addObject();
                    toolMessage.put("role", "tool");
                    toolMessage.put("tool_call_id", call.callId());
                    toolMessage.put("content", output);
                }
            }

            throw new IllegalStateException(
                    "Subagent 超过最大步数 " + stepLimit + "，已安全停止"
            );
        } catch (Exception exception) {
            if (!stopped) {
                try {
                    triggerStop(
                            runId,
                            task,
                            lastStep,
                            "failed",
                            exception
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
            ErrorRecovery.RecoveryState state
    ) throws Exception {
        try {
            return withTransientRetry(messages, state);
        } catch (Exception exception) {
            if (contextCompactor == null
                    || !DeepSeekClient.isPromptTooLong(exception)
                    || state.reactiveCompactAttempted()) {
                throw exception;
            }
            contextCompactor.reactiveCompact(messages);
            state.markReactiveCompactAttempted();
            System.out.println(
                    "[Subagent recovery] prompt_too_long → reactiveCompact"
            );
            return withTransientRetry(messages, state);
        }
    }

    private DeepSeekClient.ModelResponse withTransientRetry(
            ArrayNode messages,
            ErrorRecovery.RecoveryState state
    ) throws Exception {
        return errorRecovery.withRetry(
                (maxTokens, requestedModel) -> model.createResponse(
                        messages,
                        tools.definitions(),
                        maxTokens,
                        requestedModel
                ),
                state,
                event -> System.out.printf(
                        "[Subagent recovery] %s attempt=%d delayMs=%d %s%n",
                        event.kind(),
                        event.attempt(),
                        event.delayMs(),
                        event.detail()
                )
        );
    }

    private void appendRecovered(StringBuilder output, String text) {
        if (text == null || text.isBlank()) return;
        if (!output.isEmpty()) output.append('\n');
        output.append(text);
    }

    private int normalizeMaxSteps(int value) {
        if (value < 1) {
            throw new IllegalArgumentException("maxSteps 必须大于 0");
        }
        return value;
    }

    private void triggerStop(
            String runId,
            String task,
            int step,
            String reason,
            Throwable error
    ) throws Exception {
        hooks.trigger_hooks(
                HookEvent.STOP,
                HookContext.forStop(runId, task, step, reason, error)
        );
    }

    @FunctionalInterface
    interface ModelCall {
        DeepSeekClient.ModelResponse createResponse(
                ArrayNode messages,
                ArrayNode tools,
                int maxTokens,
                String model
        ) throws Exception;
    }
}
