package com.example.agent.subagents;

public interface SubagentExecutor {
    SubagentResult run(
            String description,
            String task,
            String parentRunId
    ) throws Exception;

    record SubagentResult(
            String text,
            int steps,
            int toolCalls
    ) {
    }
}
