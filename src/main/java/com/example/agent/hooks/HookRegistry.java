package com.example.agent.hooks;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

public final class HookRegistry {
    private final Map<HookEvent, CopyOnWriteArrayList<HookHandler>> hooks =
            new EnumMap<>(HookEvent.class);

    public HookRegistry() {
        for (HookEvent event : HookEvent.values()) {
            hooks.put(event, new CopyOnWriteArrayList<>());
        }
    }

    public HookRegistry register_hooks(HookEvent event, HookHandler... handlers) {
        if (event == null || handlers == null) {
            throw new IllegalArgumentException("Hook 事件和处理器不能为空");
        }
        for (HookHandler handler : handlers) {
            if (handler == null) throw new IllegalArgumentException("Hook 处理器不能为空");
            hooks.get(event).add(handler);
        }
        return this;
    }

    public List<HookResult> trigger_hooks(
            HookEvent event,
            HookContext context
    ) throws Exception {
        if (event == null || context == null) {
            throw new IllegalArgumentException("Hook 事件和上下文不能为空");
        }

        List<HookResult> results = new ArrayList<>();
        for (HookHandler handler : hooks.get(event)) {
            HookResult result = handler.handle(context);
            if (result == null) result = HookResult.allow();
            results.add(result);
            if (!result.allowed()) {
                throw new HookRejectedException(event, result.message());
            }
        }
        return List.copyOf(results);
    }

    public int registeredCount(HookEvent event) {
        return hooks.get(event).size();
    }
}
