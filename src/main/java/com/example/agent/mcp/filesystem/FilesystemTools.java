package com.example.agent.mcp.filesystem;

import com.example.agent.mcp.common.AbstractMcpServer;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;

public final class FilesystemTools {
    private static final int DEFAULT_LIST_DEPTH = 8;
    private static final int DEFAULT_READ_LINES = 200;
    private static final int DEFAULT_SEARCH_RESULTS = 50;
    private static final long MAX_SEARCH_FILE_BYTES = 512 * 1024;
    private static final Set<String> SKIPPED_DIRS = Set.of(
            ".git",
            "target",
            ".idea",
            "node_modules"
    );

    private final Path workspaceRoot;
    private final AbstractMcpServer server;

    public FilesystemTools(Path workspaceRoot, AbstractMcpServer server) {
        this.workspaceRoot = workspaceRoot.toAbsolutePath().normalize();
        this.server = server;
    }

    public void registerInto() {
        server.register(listFilesTool());
        server.register(readFileTool());
        server.register(searchCodeTool());
    }

    private AbstractMcpServer.McpTool listFilesTool() {
        ObjectNode schema = server.objectSchema();
        ObjectNode properties = (ObjectNode) schema.path("properties");
        properties.putObject("path")
                .put("type", "string")
                .put("description", "相对工作区根目录的路径，默认 .");
        properties.putObject("maxDepth")
                .put("type", "integer")
                .put("description", "最大递归层级，默认 8");
        return new AbstractMcpServer.McpTool(
                "list_files",
                "List files and directories under the workspace root. (readOnly)",
                schema,
                arguments -> {
                    Path target = resolve(arguments.path("path").asText("."));
                    if (!Files.exists(target)) {
                        throw new IllegalArgumentException("路径不存在：" + target);
                    }

                    int maxDepth = Math.max(
                            1,
                            arguments.path("maxDepth").asInt(DEFAULT_LIST_DEPTH)
                    );
                    try (Stream<Path> stream = Files.walk(
                            target,
                            maxDepth,
                            FileVisitOption.FOLLOW_LINKS
                    )) {
                        List<String> lines = stream
                                .sorted(Comparator.naturalOrder())
                                .map(this::formatPath)
                                .toList();
                        return String.join(System.lineSeparator(), lines);
                    }
                }
        );
    }

    private AbstractMcpServer.McpTool readFileTool() {
        ObjectNode schema = server.objectSchema();
        ObjectNode properties = (ObjectNode) schema.path("properties");
        properties.putObject("path")
                .put("type", "string")
                .put("description", "要读取的相对路径");
        properties.putObject("startLine")
                .put("type", "integer")
                .put("description", "起始行号，从 1 开始");
        properties.putObject("endLine")
                .put("type", "integer")
                .put("description", "结束行号，默认最多返回 200 行");
        schema.putArray("required").add("path");

        return new AbstractMcpServer.McpTool(
                "read_file",
                "Read file content from the workspace with line ranges. (readOnly)",
                schema,
                arguments -> {
                    Path target = resolve(
                            AbstractMcpServer.requireText(arguments, "path")
                    );
                    if (!Files.isRegularFile(target)) {
                        throw new IllegalArgumentException("不是普通文件：" + target);
                    }

                    List<String> lines = Files.readAllLines(
                            target,
                            StandardCharsets.UTF_8
                    );
                    int start = Math.max(1, arguments.path("startLine").asInt(1));
                    int end = arguments.path("endLine").asInt(
                            start + DEFAULT_READ_LINES - 1
                    );
                    if (end < start) {
                        throw new IllegalArgumentException("endLine 不能小于 startLine");
                    }

                    StringBuilder builder = new StringBuilder();
                    for (int index = start; index <= end && index <= lines.size(); index++) {
                        if (!builder.isEmpty()) builder.append(System.lineSeparator());
                        builder.append(index)
                                .append(" | ")
                                .append(lines.get(index - 1));
                    }
                    return builder.isEmpty() ? "(no content)" : builder.toString();
                }
        );
    }

    private AbstractMcpServer.McpTool searchCodeTool() {
        ObjectNode schema = server.objectSchema();
        ObjectNode properties = (ObjectNode) schema.path("properties");
        properties.putObject("query")
                .put("type", "string")
                .put("description", "要检索的文本，大小写不敏感");
        properties.putObject("path")
                .put("type", "string")
                .put("description", "可选子目录，默认 .");
        properties.putObject("maxResults")
                .put("type", "integer")
                .put("description", "最大返回结果数，默认 50");
        schema.putArray("required").add("query");

        return new AbstractMcpServer.McpTool(
                "search_code",
                "Search code and text files under the workspace. (readOnly)",
                schema,
                arguments -> {
                    String query = AbstractMcpServer.requireText(arguments, "query");
                    String queryLower = query.toLowerCase(Locale.ROOT);
                    int maxResults = Math.max(
                            1,
                            arguments.path("maxResults").asInt(DEFAULT_SEARCH_RESULTS)
                    );
                    Path base = resolve(arguments.path("path").asText("."));
                    if (!Files.exists(base)) {
                        throw new IllegalArgumentException("路径不存在：" + base);
                    }

                    StringBuilder builder = new StringBuilder();
                    int results = 0;
                    try (Stream<Path> stream = Files.walk(base)) {
                        for (Path file : stream
                                .filter(Files::isRegularFile)
                                .filter(this::shouldSearch)
                                .sorted()
                                .toList()) {
                            if (results >= maxResults) break;
                            List<String> lines;
                            try {
                                lines = Files.readAllLines(
                                        file,
                                        StandardCharsets.UTF_8
                                );
                            } catch (IOException exception) {
                                continue;
                            }
                            for (int lineNumber = 0; lineNumber < lines.size(); lineNumber++) {
                                String line = lines.get(lineNumber);
                                if (!line.toLowerCase(Locale.ROOT).contains(queryLower)) {
                                    continue;
                                }
                                if (!builder.isEmpty()) {
                                    builder.append(System.lineSeparator());
                                }
                                builder.append(relativize(file))
                                        .append(':')
                                        .append(lineNumber + 1)
                                        .append(": ")
                                        .append(line.strip());
                                results++;
                                if (results >= maxResults) break;
                            }
                        }
                    }
                    return builder.isEmpty() ? "(no matches)" : builder.toString();
                }
        );
    }

    private Path resolve(String relativePath) {
        Path resolved = workspaceRoot.resolve(relativePath).normalize();
        if (!resolved.startsWith(workspaceRoot)) {
            throw new IllegalArgumentException("路径越界：" + relativePath);
        }
        return resolved;
    }

    private String formatPath(Path path) {
        if (path.equals(workspaceRoot)) return ".";
        String relative = relativize(path);
        if (Files.isDirectory(path)) {
            return relative + "/";
        }
        return relative;
    }

    private String relativize(Path path) {
        return workspaceRoot.relativize(path).toString().replace('\\', '/');
    }

    private boolean shouldSearch(Path path) {
        try {
            if (Files.size(path) > MAX_SEARCH_FILE_BYTES) return false;
        } catch (IOException exception) {
            return false;
        }

        for (Path part : workspaceRoot.relativize(path)) {
            if (SKIPPED_DIRS.contains(part.toString())) return false;
        }
        return true;
    }
}
