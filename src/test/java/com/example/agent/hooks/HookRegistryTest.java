package com.example.agent.hooks;

import com.example.agent.permissions.FilePermissionService;
import com.example.agent.tools.ToolDefinition;
import com.example.agent.tools.ToolRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HookRegistryTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path projectRoot;

    @Test
    void registerAndTriggerHooksInRegistrationOrder() throws Exception {
        HookRegistry hooks = new HookRegistry();
        List<String> order = new ArrayList<>();
        hooks.register_hooks(
                HookEvent.USER_PROMPT_SCRIPT,
                context -> {
                    order.add("first");
                    return HookResult.allow();
                },
                context -> {
                    order.add("second");
                    return HookResult.allow();
                }
        );

        List<HookResult> results = hooks.trigger_hooks(
                HookEvent.USER_PROMPT_SCRIPT,
                HookContext.forPrompt("run", "hello")
        );

        assertEquals(List.of("first", "second"), order);
        assertEquals(2, results.size());
    }

    @Test
    void rejectedHookStopsRemainingExtensions() {
        HookRegistry hooks = new HookRegistry();
        List<String> calls = new ArrayList<>();
        hooks.register_hooks(
                HookEvent.PRE_TOOL_USE,
                context -> HookResult.reject("blocked"),
                context -> {
                    calls.add("should not run");
                    return HookResult.allow();
                }
        );

        HookRejectedException error = assertThrows(
                HookRejectedException.class,
                () -> hooks.trigger_hooks(
                        HookEvent.PRE_TOOL_USE,
                        HookContext.forTool(
                                "run",
                                "prompt",
                                "tool",
                                JSON.createObjectNode(),
                                1
                        )
                )
        );

        assertTrue(error.getMessage().contains("PreToolUse"));
        assertTrue(calls.isEmpty());
    }

    @Test
    void toolRegistryTriggersPreAndPostAroundHandler() throws Exception {
        HookRegistry hooks = new HookRegistry();
        List<String> cycle = new ArrayList<>();
        hooks.register_hooks(
                HookEvent.PRE_TOOL_USE,
                context -> {
                    cycle.add("pre");
                    context.put("prepared", true);
                    return HookResult.allow();
                }
        );
        hooks.register_hooks(
                HookEvent.POST_TOOL_USE,
                context -> {
                    cycle.add("post:" + context.output());
                    return HookResult.allow();
                }
        );

        ToolRegistry tools = new ToolRegistry(JSON, hooks);
        ObjectNode parameters = JSON.createObjectNode().put("type", "object");
        tools.register(new ToolDefinition(
                "echo",
                "echo",
                parameters,
                (arguments, context) -> {
                    assertEquals(Boolean.TRUE, context.get("prepared"));
                    cycle.add("handler");
                    return "done";
                }
        ));

        String output = tools.execute(
                "echo",
                JSON.createObjectNode(),
                HookContext.forTool(
                        "run",
                        "prompt",
                        "echo",
                        JSON.createObjectNode(),
                        1
                )
        );

        assertEquals("done", output);
        assertEquals(List.of("pre", "handler", "post:done"), cycle);
    }

    @Test
    void permissionValidationRunsAsPreToolUseExtension() throws Exception {
        Files.writeString(projectRoot.resolve(".env"), "SECRET=value");
        FilePermissionService permissions = new FilePermissionService(projectRoot);
        HookRegistry hooks = new HookRegistry();
        PermissionHooks.register_hooks(hooks, permissions);
        ObjectNode arguments = JSON.createObjectNode().put("path", ".env");
        HookContext context = HookContext.forTool(
                "run",
                "read secret",
                "read_file",
                arguments,
                1
        );

        SecurityException error = assertThrows(
                SecurityException.class,
                () -> hooks.trigger_hooks(HookEvent.PRE_TOOL_USE, context)
        );

        assertTrue(error.getMessage().contains("闸门 2"));
    }

    @Test
    void defaultRegistrationCoversTheWholeAgentCycle() throws Exception {
        HookRegistry hooks = new HookRegistry();
        DefaultAgentHooks.register_hooks(hooks);
        PermissionHooks.register_hooks(
                hooks,
                new FilePermissionService(projectRoot)
        );

        assertEquals(1, hooks.registeredCount(HookEvent.USER_PROMPT_SCRIPT));
        assertEquals(1, hooks.registeredCount(HookEvent.PRE_TOOL_USE));
        assertEquals(1, hooks.registeredCount(HookEvent.POST_TOOL_USE));
        assertEquals(1, hooks.registeredCount(HookEvent.STOP));

        HookContext stop = HookContext.forStop(
                "run",
                "prompt",
                2,
                "completed",
                null
        );
        hooks.trigger_hooks(HookEvent.STOP, stop);
        assertNotNull(stop.get("run.stoppedAt"));
    }
}
