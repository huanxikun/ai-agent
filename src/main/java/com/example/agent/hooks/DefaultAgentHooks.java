package com.example.agent.hooks;

import java.time.Instant;

/**
 * 注册 Agent Cycle 的基础扩展。业务可以继续调用 register_hooks 追加处理器。
 */
public final class DefaultAgentHooks {
    private DefaultAgentHooks() {
    }

    public static void register_hooks(HookRegistry registry) {
        registry.register_hooks(
                HookEvent.USER_PROMPT_SCRIPT,
                context -> {
                    if (context.userPrompt() == null || context.userPrompt().isBlank()) {
                        return HookResult.reject("用户输入不能为空");
                    }
                    if (context.userPrompt().length() > 4_000) {
                        return HookResult.reject("用户输入不能超过 4,000 字符");
                    }
                    context.put("prompt.receivedAt", Instant.now());
                    return HookResult.allow("用户输入检查通过");
                }
        );

        registry.register_hooks(
                HookEvent.POST_TOOL_USE,
                context -> {
                    context.put("tool.completedAt", Instant.now());
                    return HookResult.allow(
                            context.error() == null ? "工具执行完成" : "工具执行失败已记录"
                    );
                }
        );

        registry.register_hooks(
                HookEvent.STOP,
                context -> {
                    context.put("run.stoppedAt", Instant.now());
                    return HookResult.allow("Agent Cycle 已停止");
                }
        );
    }
}
