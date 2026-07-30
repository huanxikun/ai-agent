package com.example.agent.subagents;

import com.example.agent.DeepSeekClient;
import com.example.agent.context.ContextCompactor;
import com.example.agent.hooks.HookContext;
import com.example.agent.hooks.HookEvent;
import com.example.agent.hooks.HookRegistry;
import com.example.agent.prompts.SystemPromptAssembler;
import com.example.agent.tools.ToolRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.UUID;

/**
 * 使用 S10 运行时 Prompt 的只读、不可递归 Subagent。
 */
public final class Subagent implements SubagentExecutor {
    private static final int MAX_STEPS = 6;

    private final ModelCall model;
    private final ToolRegistry tools;
    private final HookRegistry hooks;
    private final ContextCompactor contextCompactor;
    private final SystemPromptAssembler systemPromptAssembler;
    private final ObjectMapper json;

    public Subagent(
            DeepSeekClient model,
            ToolRegistry tools,
            HookRegistry hooks,
            ContextCompactor contextCompactor,
            SystemPromptAssembler systemPromptAssembler,
            ObjectMapper json
    ) {
        this(
                model::createResponse,
                tools,
                hooks,
                contextCompactor,
                systemPromptAssembler,
                json
        );
    }

    Subagent(
            ModelCall model,
            ToolRegistry tools,
            HookRegistry hooks,
            ContextCompactor contextCompactor,
            SystemPromptAssembler systemPromptAssembler,
            ObjectMapper json
    ) {
        this.model = model;
        this.tools = tools;
        this.hooks = hooks;
        this.contextCompactor = contextCompactor;
        this.systemPromptAssembler = systemPromptAssembler;
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

            for (int step = 1; step <= MAX_STEPS; step++) {
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
                    contextCompactor.reactiveCompact(messages);
                    System.out.println(
                            "[Subagent compact] reactiveCompact 后重试一次"
                    );
                    response = model.createResponse(
                            messages,
                            tools.definitions()
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
                    return new SubagentResult(response.text(), step, toolCalls);
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
                    "Subagent 超过最大步数 " + MAX_STEPS + "，已安全停止"
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
                ArrayNode tools
        ) throws Exception;
    }
}
