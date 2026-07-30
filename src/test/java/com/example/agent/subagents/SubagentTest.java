package com.example.agent.subagents;

import com.example.agent.DeepSeekClient;
import com.example.agent.context.ContextCompactor;
import com.example.agent.hooks.DefaultAgentHooks;
import com.example.agent.hooks.HookContext;
import com.example.agent.hooks.HookEvent;
import com.example.agent.hooks.HookRegistry;
import com.example.agent.hooks.PermissionHooks;
import com.example.agent.permissions.FilePermissionService;
import com.example.agent.permissions.HumanApprovalGate;
import com.example.agent.prompts.SystemPromptAssembler;
import com.example.agent.recovery.ErrorRecovery;
import com.example.agent.skills.SkillCatalog;
import com.example.agent.todos.TodoStore;
import com.example.agent.tools.CodeTools;
import com.example.agent.tools.ToolHandlers;
import com.example.agent.tools.ToolRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SubagentTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path projectRoot;

    @Test
    void childRegistryContainsOnlyReadToolsAndStillRunsHooks() throws Exception {
        Path backend = projectRoot.resolve("src/Backend.java");
        Files.createDirectories(backend.getParent());
        Files.writeString(backend, "class Backend { void run() {} }");

        HookRegistry hooks = configuredHooks();
        AtomicInteger preCalls = new AtomicInteger();
        AtomicInteger postCalls = new AtomicInteger();
        hooks.register_hooks(
                HookEvent.PRE_TOOL_USE,
                context -> {
                    preCalls.incrementAndGet();
                    return com.example.agent.hooks.HookResult.allow();
                }
        );
        hooks.register_hooks(
                HookEvent.POST_TOOL_USE,
                context -> {
                    postCalls.incrementAndGet();
                    return com.example.agent.hooks.HookResult.allow();
                }
        );

        ToolRegistry childTools = readOnlyTools(hooks);
        assertTrue(childTools.hasTool("list_files"));
        assertTrue(childTools.hasTool("search_code"));
        assertTrue(childTools.hasTool("read_file"));
        assertFalse(childTools.hasTool("task"));
        assertFalse(childTools.hasTool("create_file"));
        assertEquals(3, childTools.definitions().size());

        AtomicInteger modelCalls = new AtomicInteger();
        Subagent subagent = new Subagent(
                (messages, definitions, maxTokens, model) -> {
                    int call = modelCalls.getAndIncrement();
                    if (call == 0) {
                        assertEquals(2, messages.size());
                        assertEquals(
                                "检查 Backend.java",
                                messages.path(1).path("content").asText()
                        );
                        return toolCall(
                                "read-1",
                                "read_file",
                                JSON.createObjectNode().put(
                                        "path",
                                        "src/Backend.java"
                                )
                        );
                    }

                    assertTrue(
                            messages.path(messages.size() - 1)
                                    .path("content")
                                    .asText()
                                    .contains("class Backend")
                    );
                    return finalResponse("Backend.run 位于 src/Backend.java。");
                },
                childTools,
                hooks,
                null,
                runtimePrompt(childTools, null),
                testRecovery(),
                JSON
        );

        SubagentExecutor.SubagentResult result = subagent.run(
                "后端研究",
                "检查 Backend.java",
                "parent-run"
        );

        assertEquals(2, result.steps());
        assertEquals(1, result.toolCalls());
        assertTrue(result.text().contains("Backend.run"));
        assertEquals(1, preCalls.get());
        assertEquals(1, postCalls.get());
    }

    @Test
    void subagentExplicitlyRejectsRecursiveTaskCall() throws Exception {
        HookRegistry hooks = configuredHooks();
        ToolRegistry childTools = readOnlyTools(hooks);
        Subagent subagent = new Subagent(
                (messages, definitions, maxTokens, model) -> toolCall(
                        "recursive-1",
                        "task",
                        JSON.createObjectNode()
                                .put("description", "nested")
                                .put("task", "spawn again")
                ),
                childTools,
                hooks,
                null,
                runtimePrompt(childTools, null),
                testRecovery(),
                JSON
        );

        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> subagent.run("禁止递归", "尝试递归", "parent")
        );

        assertTrue(error.getMessage().contains("禁止递归"));
    }

    @Test
    void parentTaskToolDelegatesToSubagentExecutor() throws Exception {
        AtomicInteger executions = new AtomicInteger();
        SubagentExecutor executor = (description, task, parentRunId) -> {
            executions.incrementAndGet();
            assertEquals("研究 Hooks", description);
            assertEquals("读取 HookRegistry", task);
            assertEquals("parent", parentRunId);
            return new SubagentExecutor.SubagentResult("结论", 2, 1);
        };

        HookRegistry hooks = new HookRegistry();
        ToolRegistry parentTools = new ToolRegistry(JSON, hooks);
        new ToolHandlers(new TodoStore(), executor, JSON).registerInto(parentTools);
        assertTrue(parentTools.hasTool("task"));
        assertTrue(parentTools.supportsBackground("task"));
        assertTrue(parentTools.definitions().toString().contains("run_in_background"));

        ObjectNode arguments = JSON.createObjectNode()
                .put("description", "研究 Hooks")
                .put("task", "读取 HookRegistry");
        JsonNode output = JSON.readTree(parentTools.execute(
                "task",
                arguments,
                HookContext.forTool(
                        "parent",
                        "large task",
                        "task",
                        arguments,
                        1
                )
        ));

        assertEquals(1, executions.get());
        assertEquals("completed", output.path("status").asText());
        assertEquals("结论", output.path("result").asText());
    }

    @Test
    void childCanLoadRelevantSkillBeforeReadingCode() throws Exception {
        Path skillDirectory = projectRoot.resolve("skills/backend-java");
        Files.createDirectories(skillDirectory);
        Files.writeString(
                skillDirectory.resolve("SKILL.md"),
                """
                        ---
                        name: backend-java
                        description: 后端研究流程
                        ---
                        # 完整规则
                        先定位工具注册，再阅读实现。
                        """
        );

        HookRegistry hooks = configuredHooks();
        SkillCatalog catalog = new SkillCatalog(projectRoot);
        ToolRegistry childTools = readOnlyTools(hooks);
        new ToolHandlers(null, null, catalog, JSON)
                .registerSkillInto(childTools);
        assertTrue(childTools.hasTool("load_skills"));
        assertFalse(childTools.hasTool("task"));
        assertFalse(childTools.hasTool("create_file"));
        assertEquals(4, childTools.definitions().size());

        AtomicInteger modelCalls = new AtomicInteger();
        Subagent subagent = new Subagent(
                (messages, definitions, maxTokens, model) -> {
                    int call = modelCalls.getAndIncrement();
                    if (call == 0) {
                        assertTrue(
                                messages.path(0).path("content").asText()
                                        .contains("backend-java: 后端研究流程")
                        );
                        assertFalse(
                                messages.path(0).path("content").asText()
                                        .contains("先定位工具注册")
                        );
                        ObjectNode arguments = JSON.createObjectNode();
                        arguments.putArray("skills").add("backend-java");
                        return toolCall(
                                "skill-1",
                                "load_skills",
                                arguments
                        );
                    }

                    assertTrue(
                            messages.path(messages.size() - 1)
                                    .path("content")
                                    .asText()
                                    .contains("先定位工具注册")
                    );
                    return finalResponse("已按后端 Skill 完成分析。");
                },
                childTools,
                hooks,
                null,
                runtimePrompt(childTools, catalog),
                testRecovery(),
                JSON
        );

        SubagentExecutor.SubagentResult result = subagent.run(
                "加载后端知识",
                "分析工具注册",
                "parent-run"
        );

        assertEquals(2, result.steps());
        assertEquals(1, result.toolCalls());
        assertTrue(result.text().contains("后端 Skill"));
    }

    @Test
    void childReactivelyCompactsAndRetriesOnceOnHttp413()
            throws Exception {
        HookRegistry hooks = configuredHooks();
        ToolRegistry childTools = readOnlyTools(hooks);
        ContextCompactor compactor = new ContextCompactor(
                projectRoot,
                1_000_000,
                messages -> "unused",
                JSON
        );
        AtomicInteger calls = new AtomicInteger();
        Subagent subagent = new Subagent(
                (messages, definitions, maxTokens, model) -> {
                    if (calls.getAndIncrement() == 0) {
                        throw new DeepSeekClient.DeepSeekException(
                                413,
                                "",
                                "payload too large"
                        );
                    }
                    assertTrue(messages.size() <= 7);
                    assertTrue(
                            messages.path(0).path("content").asText()
                                    .contains("reactiveCompact")
                    );
                    return finalResponse("应急压缩后完成。");
                },
                childTools,
                hooks,
                compactor,
                runtimePrompt(childTools, null),
                testRecovery(),
                JSON
        );

        SubagentExecutor.SubagentResult result = subagent.run(
                "应急压缩",
                "处理过长上下文",
                "parent"
        );

        assertEquals(2, calls.get());
        assertEquals(1, result.steps());
        assertTrue(result.text().contains("应急压缩"));
    }

    @Test
    void childEscalatesMaxTokensThenContinuesWithoutLosingPartialText()
            throws Exception {
        HookRegistry hooks = configuredHooks();
        ToolRegistry childTools = readOnlyTools(hooks);
        AtomicInteger calls = new AtomicInteger();
        Subagent subagent = new Subagent(
                (messages, definitions, maxTokens, model) -> {
                    int call = calls.getAndIncrement();
                    if (call == 0) {
                        assertEquals(8_000, maxTokens);
                        return maxTokensResponse("discarded-short-output");
                    }
                    if (call == 1) {
                        assertEquals(64_000, maxTokens);
                        return maxTokensResponse("kept-partial-output");
                    }
                    assertEquals(64_000, maxTokens);
                    assertEquals(
                            ErrorRecovery.CONTINUATION_PROMPT,
                            messages.path(messages.size() - 1)
                                    .path("content")
                                    .asText()
                    );
                    return finalResponse("continued-output");
                },
                childTools,
                hooks,
                null,
                runtimePrompt(childTools, null),
                testRecovery(),
                JSON
        );

        SubagentExecutor.SubagentResult result = subagent.run(
                "输出恢复",
                "生成长内容",
                "parent"
        );

        assertEquals(3, calls.get());
        assertTrue(result.text().contains("kept-partial-output"));
        assertTrue(result.text().contains("continued-output"));
        assertFalse(result.text().contains("discarded-short-output"));
    }

    private HookRegistry configuredHooks() throws Exception {
        HookRegistry hooks = new HookRegistry();
        DefaultAgentHooks.register_hooks(hooks);
        PermissionHooks.register_hooks(
                hooks,
                new FilePermissionService(projectRoot)
        );
        return hooks;
    }

    private ToolRegistry readOnlyTools(HookRegistry hooks) {
        ToolRegistry tools = new ToolRegistry(JSON, hooks);
        new CodeTools(
                projectRoot,
                new HumanApprovalGate(),
                hooks,
                JSON
        ).registerReadOnlyInto(tools);
        return tools;
    }

    private SystemPromptAssembler runtimePrompt(
            ToolRegistry tools,
            SkillCatalog catalog
    ) {
        return new SystemPromptAssembler(
                projectRoot,
                tools,
                catalog,
                null,
                true,
                SystemPromptAssembler.AgentRole.SUBAGENT,
                JSON
        );
    }

    private ErrorRecovery testRecovery() {
        return new ErrorRecovery("primary-model", "");
    }

    private DeepSeekClient.ModelResponse toolCall(
            String callId,
            String name,
            ObjectNode arguments
    ) throws Exception {
        ObjectNode message = JSON.createObjectNode();
        message.put("role", "assistant");
        message.putNull("content");
        ArrayNode calls = message.putArray("tool_calls");
        ObjectNode call = calls.addObject();
        call.put("id", callId);
        call.put("type", "function");
        call.putObject("function")
                .put("name", name)
                .put("arguments", JSON.writeValueAsString(arguments));
        return new DeepSeekClient.ModelResponse(
                "",
                List.of(new DeepSeekClient.ToolCall(callId, name, arguments)),
                message
        );
    }

    private DeepSeekClient.ModelResponse finalResponse(String text) {
        ObjectNode message = JSON.createObjectNode();
        message.put("role", "assistant");
        message.put("content", text);
        return new DeepSeekClient.ModelResponse(text, List.of(), message);
    }

    private DeepSeekClient.ModelResponse maxTokensResponse(String text) {
        ObjectNode message = JSON.createObjectNode();
        message.put("role", "assistant");
        message.put("content", text);
        return new DeepSeekClient.ModelResponse(
                text,
                List.of(),
                message,
                "max_tokens"
        );
    }
}
