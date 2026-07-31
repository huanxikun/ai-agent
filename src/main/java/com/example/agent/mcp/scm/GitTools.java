package com.example.agent.mcp.scm;

import com.example.agent.mcp.common.AbstractMcpServer;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

public final class GitTools {
    private static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(30);
    private static final int DEFAULT_LOG_LIMIT = 20;
    private static final int MAX_OUTPUT_CHARS = 20_000;

    private final Path repoRoot;
    private final AbstractMcpServer server;

    public GitTools(Path repoRoot, AbstractMcpServer server) {
        this.repoRoot = repoRoot.toAbsolutePath().normalize();
        this.server = server;
    }

    public void registerInto() {
        server.register(gitStatusTool());
        server.register(gitDiffTool());
        server.register(gitLogTool());
    }

    private AbstractMcpServer.McpTool gitStatusTool() {
        ObjectNode schema = server.objectSchema();
        return new AbstractMcpServer.McpTool(
                "git_status",
                "Show git working tree status for the repository. (readOnly)",
                schema,
                arguments -> {
                    String output = runGit("status", "--short");
                    return output.isBlank() ? "(clean working tree)" : output;
                }
        );
    }

    private AbstractMcpServer.McpTool gitDiffTool() {
        ObjectNode schema = server.objectSchema();
        ObjectNode properties = (ObjectNode) schema.path("properties");
        properties.putObject("base")
                .put("type", "string")
                .put("description", "可选基线，例如 origin/main");

        return new AbstractMcpServer.McpTool(
                "git_diff",
                "Show git diff for the repository or against a base ref. (readOnly)",
                schema,
                arguments -> {
                    String base = arguments.path("base").asText("").trim();
                    if (!base.isEmpty()) {
                        String output = runGit("diff", base + "...HEAD");
                        return output.isBlank() ? "(no diff)" : output;
                    }

                    String unstaged = runGit("diff");
                    String staged = runGit("diff", "--staged");
                    StringBuilder builder = new StringBuilder();
                    builder.append("## Unstaged")
                            .append(System.lineSeparator())
                            .append(unstaged.isBlank() ? "(no diff)" : unstaged)
                            .append(System.lineSeparator())
                            .append(System.lineSeparator())
                            .append("## Staged")
                            .append(System.lineSeparator())
                            .append(staged.isBlank() ? "(no diff)" : staged);
                    return builder.toString();
                }
        );
    }

    private AbstractMcpServer.McpTool gitLogTool() {
        ObjectNode schema = server.objectSchema();
        ObjectNode properties = (ObjectNode) schema.path("properties");
        properties.putObject("limit")
                .put("type", "integer")
                .put("description", "最大返回提交数，默认 20");

        return new AbstractMcpServer.McpTool(
                "git_log",
                "Show recent git commits. (readOnly)",
                schema,
                arguments -> {
                    int limit = Math.max(
                            1,
                            arguments.path("limit").asInt(DEFAULT_LOG_LIMIT)
                    );
                    String output = runGit("log", "--oneline", "-n", String.valueOf(limit));
                    return output.isBlank() ? "(no commits)" : output;
                }
        );
    }

    private String runGit(String... args) throws Exception {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.addAll(List.of(args));

        Process process = new ProcessBuilder(command)
                .directory(repoRoot.toFile())
                .redirectErrorStream(true)
                .start();

        boolean completed = process.waitFor(
                COMMAND_TIMEOUT.toMillis(),
                TimeUnit.MILLISECONDS
        );
        if (!completed) {
            process.destroyForcibly();
            throw new IllegalStateException("git 命令超时");
        }

        String output = new String(
                process.getInputStream().readAllBytes(),
                StandardCharsets.UTF_8
        ).trim();

        if (process.exitValue() != 0) {
            throw new IllegalStateException(output.isBlank()
                    ? "git 命令失败"
                    : output);
        }
        if (output.length() > MAX_OUTPUT_CHARS) {
            return output.substring(0, MAX_OUTPUT_CHARS) + "\n... (truncated)";
        }
        return output;
    }
}
