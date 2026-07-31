package com.example.agent.mcp.workspace;

import com.example.agent.mcp.common.AbstractMcpServer;
import com.example.agent.mcp.filesystem.FilesystemTools;
import com.example.agent.mcp.scm.GitHubTools;
import com.example.agent.mcp.scm.GitTools;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Path;

/**
 * Aggregate MCP server that exposes filesystem, git and GitHub tools through a
 * single stdio endpoint for the agent to connect once.
 */
public final class WorkspaceMcpServer extends AbstractMcpServer {
    private static final String VERSION = "0.1.0";

    public WorkspaceMcpServer(Path workspaceRoot, ObjectMapper json) {
        super("workspace-mcp-server", VERSION, json);
        new FilesystemTools(workspaceRoot, this).registerInto();
        new GitTools(workspaceRoot, this).registerInto();
        new GitHubTools(this, json).registerInto();
    }

    public static void main(String[] args) throws Exception {
        Path workspaceRoot = args.length == 0
                ? Path.of(".")
                : Path.of(args[0]);
        ObjectMapper json = new ObjectMapper();
        System.err.printf(
                "[workspace-mcp] root=%s%n",
                workspaceRoot.toAbsolutePath().normalize()
        );
        new WorkspaceMcpServer(workspaceRoot, json)
                .serve(System.in, System.out);
    }
}
