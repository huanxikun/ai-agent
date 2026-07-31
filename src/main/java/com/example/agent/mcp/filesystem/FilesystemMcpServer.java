package com.example.agent.mcp.filesystem;

import com.example.agent.mcp.common.AbstractMcpServer;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Path;

public final class FilesystemMcpServer extends AbstractMcpServer {
    private static final String VERSION = "0.1.0";

    public FilesystemMcpServer(Path workspaceRoot, ObjectMapper json) {
        super("filesystem-mcp-server", VERSION, json);
        new FilesystemTools(workspaceRoot, this).registerInto();
    }

    public static void main(String[] args) throws Exception {
        Path workspaceRoot = args.length == 0
                ? Path.of(".")
                : Path.of(args[0]);
        ObjectMapper json = new ObjectMapper();
        System.err.printf(
                "[filesystem-mcp] workspace=%s%n",
                workspaceRoot.toAbsolutePath().normalize()
        );
        new FilesystemMcpServer(workspaceRoot, json)
                .serve(System.in, System.out);
    }
}
