package com.example.agent.hooks;

public final class HookRejectedException extends SecurityException {
    public HookRejectedException(HookEvent event, String message) {
        super(event.displayName() + " Hook 拒绝：" + message);
    }
}
