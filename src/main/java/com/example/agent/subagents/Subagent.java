package com.example.agent.subagents;

import com.example.agent.DeepSeekClient;
import com.example.agent.context.ContextCompactor;
import com.example.agent.hooks.HookContext;
import com.example.agent.hooks.HookEvent;
import com.example.agent.hooks.HookRegistry;
import com.example.agent.skills.SkillCatalog;
import com.example.agent.tools.ToolRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.UUID;

/**
 * 上下文隔离、只读、不可递归派生，并支持 S08 压缩的 Subagent。
 */
public final class Subagent implements SubagentExecutor {
    private static final int MAX_STEPS = 6;
    private static final String INSTRUCTIONS = """
            你是一个只读代码研究 Subagent。

            目标：完成父 Agent 委派的单一研究任务，并返回有证据的简洁结论。
            你可以使用 list_files、search_code 和 read_file 检查项目结构与后端代码。
            你没有 task 工具，不能创建更多 Subagent。
            你没有创建、修改、删除文件或更新父 Agent Todo 的能力。
            工具调用仍受 Hook 和项目路径权限约束。
            回答时附上相关文件路径和行号；证据不足时明确说明。
            """;

    private final ModelCall model;
    private final ToolRegistry tools;
    private final HookRegistry hooks;
    private final SkillCatalog skillCatalog;
    private final ContextCompactor contextCompactor;
    private final ObjectMapper json;

    public Subagent(
            DeepSeekClient model,
            ToolRegistry tools,
            HookRegistry hooks,
            ObjectMapper json
    ) {
        this(model::createResponse, tools, hooks, null, null, json);
    }

    public Subagent(
            DeepSeekClient model,
            ToolRegistry tools,
            HookRegistry hooks,
            SkillCatalog skillCatalog,
            ObjectMapper json
    ) {
        this(model::createResponse, tools, hooks, skillCatalog, null, json);
    }

    public Subagent(
            DeepSeekClient model,
            ToolRegistry tools,
            HookRegistry hooks,
            SkillCatalog skillCatalog,
            ContextCompactor contextCompactor,
            ObjectMapper json
    ) {
        this(
                model::createResponse,
                tools,
                hooks,
                skillCatalog,
                contextCompactor,
                json
        );
    }

    Subagent(
            ModelCall model,
            ToolRegistry tools,
            HookRegistry hooks,
            ObjectMapper json
    ) {
        this(model, tools, hooks, null, null, json);
    }

    Subagent(
            ModelCall model,
            ToolRegistry tools,
            HookRegistry hooks,
            SkillCatalog skillCatalog,
            ObjectMapper json
    ) {
        this(model, tools, hooks, skillCatalog, null, json);
    }

    Subagent(
            ModelCall model,
            ToolRegistry tools,
            HookRegistry hooks,
            SkillCatalog skillCatalog,
            ContextCompactor contextCompactor,
            ObjectMapper json
    ) {
        this.model = model;
        this.tools = tools;
        this.hooks = hooks;
        this.skillCatalog = skillCatalog;
        this.contextCompactor = contextCompactor;
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
            String systemPrompt = skillCatalog == null
                    ? INSTRUCTIONS
                    : skillCatalog.buildSystemPrompt(INSTRUCTIONS);
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
