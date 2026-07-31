package com.example.agent.mcp.scm;

import com.example.agent.mcp.common.AbstractMcpServer;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Path;

public final class ScmMcpServer extends AbstractMcpServer {
    private static final String VERSION = "0.1.0";

    public ScmMcpServer(Path repoRoot, ObjectMapper json) {
        super("scm-mcp-server", VERSION, json);
        new GitTools(repoRoot, this).registerInto();
        new GitHubTools(this, json).registerInto();
    }

    public static void main(String[] args) throws Exception {
        Path repoRoot = args.length == 0
                ? Path.of(".")
                : Path.of(args[0]);
        ObjectMapper json = new ObjectMapper();
        System.err.printf(
                "[scm-mcp] repo=%s%n",
                repoRoot.toAbsolutePath().normalize()
        );
        new ScmMcpServer(repoRoot, json)
                .serve(System.in, System.out);
    }
}
