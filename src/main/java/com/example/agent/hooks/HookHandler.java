package com.example.agent.hooks;

@FunctionalInterface
public interface HookHandler {
    HookResult handle(HookContext context) throws Exception;
}
