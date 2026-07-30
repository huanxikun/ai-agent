package com.example.agent.hooks;

public enum HookEvent {
    USER_PROMPT_SCRIPT("UserPromptScript"),
    PRE_TOOL_USE("PreToolUse"),
    POST_TOOL_USE("PostToolUse"),
    STOP("Stop");

    private final String displayName;

    HookEvent(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }
}
