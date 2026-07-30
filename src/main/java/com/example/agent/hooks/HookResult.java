package com.example.agent.hooks;

public record HookResult(boolean allowed, String message) {
    public static HookResult allow() {
        return new HookResult(true, "");
    }

    public static HookResult allow(String message) {
        return new HookResult(true, message);
    }

    public static HookResult reject(String message) {
        return new HookResult(false, message);
    }
}
