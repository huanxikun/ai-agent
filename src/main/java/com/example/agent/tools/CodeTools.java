package com.example.agent.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

public final class CodeTools {
    private static final int MAX_FILE_LINES = 400;
    private static final int MAX_SEARCH_RESULTS = 50;

    private final Path projectRoot;
    private final ObjectMapper json;

    public CodeTools(Path projectRoot, ObjectMapper json) {
        this.projectRoot = projectRoot.toAbsolutePath().normalize();
        this.json = json;
    }

    public void registerInto(ToolRegistry registry) {
        registry
                .register(listFilesTool())
                .register(readFileTool())
                .register(searchCodeTool());
    }

    private Path safePath(String value) {
        Path resolved = projectRoot
                .resolve(value == null ? "." : value)
                .normalize()
                .toAbsolutePath();

        if (!resolved.startsWith(projectRoot)) {
            throw new IllegalArgumentException(
                    "不允许访问项目目录之外的路径：" + value
            );
        }

        return resolved;
    }

    private boolean ignored(Path path) {
        String value = projectRoot
                .relativize(path)
                .toString()
                .replace('\\', '/');

        return value.startsWith(".git/")
                || value.startsWith("target/")
                || value.startsWith("node_modules/")
                || value.startsWith(".idea/");
    }

    //读取项目外的文件
    private ToolDefinition listFilesTool() {
        ObjectNode parameters = json.createObjectNode();
        parameters.put("type", "object");

        ObjectNode properties = parameters.putObject("properties");
        properties.putObject("path")
                .put("type", "string")
                .put("description", "相对于项目根目录的路径，默认是 .");

        properties.putObject("maxDepth")
                .put("type", "integer")
                .put("description", "最大递归层级，建议 1 到 6");

        parameters.put("additionalProperties", false);

        return new ToolDefinition(
                "list_files",
                "列出项目中的文件。用于了解项目结构和定位可能相关的代码。",
                parameters,
                arguments -> {
                    String path = arguments.path("path").asText(".");
                    int maxDepth = Math.max(
                            1,
                            Math.min(arguments.path("maxDepth").asInt(4), 6)
                    );

                    Path directory = safePath(path);

                    if (!Files.isDirectory(directory)) {
                        throw new IllegalArgumentException(
                                "不是目录：" + path
                        );
                    }

                    try (Stream<Path> paths = Files.walk(directory, maxDepth)) {
                        return paths
                                .filter(Files::isRegularFile)
                                .filter(file -> !ignored(file))
                                .map(projectRoot::relativize)
                                .map(Path::toString)
                                .sorted()
                                .limit(500)
                                .reduce(
                                        (left, right) -> left + "\n" + right
                                )
                                .orElse("(没有文件)");
                    }
                }
        );
    }

    //实现read_file
    private ToolDefinition readFileTool() {
        ObjectNode parameters = json.createObjectNode();
        parameters.put("type", "object");

        ObjectNode properties = parameters.putObject("properties");
        properties.putObject("path")
                .put("type", "string")
                .put("description", "相对于项目根目录的文件路径");

        properties.putObject("startLine")
                .put("type", "integer")
                .put("description", "起始行号，从 1 开始");

        properties.putObject("endLine")
                .put("type", "integer")
                .put("description", "结束行号，最多读取 400 行");

        parameters.putArray("required").add("path");
        parameters.put("additionalProperties", false);

        return new ToolDefinition(
                "read_file",
                "读取项目中的文本文件，并返回带行号的内容。",
                parameters,
                arguments -> {
                    String relativePath =
                            arguments.path("path").asText();

                    Path file = safePath(relativePath);

                    if (!Files.isRegularFile(file)) {
                        throw new IllegalArgumentException(
                                "文件不存在：" + relativePath
                        );
                    }

                    List<String> lines = Files.readAllLines(
                            file,
                            StandardCharsets.UTF_8
                    );

                    int start = Math.max(
                            1,
                            arguments.path("startLine").asInt(1)
                    );

                    int requestedEnd = arguments.path("endLine")
                            .asInt(start + MAX_FILE_LINES - 1);

                    int end = Math.min(
                            lines.size(),
                            Math.min(
                                    requestedEnd,
                                    start + MAX_FILE_LINES - 1
                            )
                    );

                    if (start > lines.size()) {
                        return "(起始行超过文件长度，文件共 "
                                + lines.size() + " 行)";
                    }

                    StringBuilder result = new StringBuilder();

                    for (int line = start; line <= end; line++) {
                        result.append(String.format(
                                "%4d | %s%n",
                                line,
                                lines.get(line - 1)
                        ));
                    }

                    if (end < lines.size()) {
                        result.append("... 文件共 ")
                                .append(lines.size())
                                .append(" 行");
                    }

                    return result.toString();
                }
        );
    }

    //实现search_code
    private ToolDefinition searchCodeTool() {
        ObjectNode parameters = json.createObjectNode();
        parameters.put("type", "object");

        ObjectNode properties = parameters.putObject("properties");
        properties.putObject("query")
                .put("type", "string")
                .put("description", "要查找的类名、方法名或代码文本");

        properties.putObject("path")
                .put("type", "string")
                .put("description", "搜索目录，默认是整个项目");

        parameters.putArray("required").add("query");
        parameters.put("additionalProperties", false);

        return new ToolDefinition(
                "search_code",
                "在项目文本文件中搜索代码或关键词，返回文件路径、行号和匹配内容。",
                parameters,
                arguments -> {
                    String query = arguments.path("query").asText();
                    String path = arguments.path("path").asText(".");

                    if (query.isBlank()) {
                        throw new IllegalArgumentException(
                                "query 不能为空"
                        );
                    }

                    Path directory = safePath(path);
                    StringBuilder result = new StringBuilder();
                    int matches = 0;

                    try (Stream<Path> paths = Files.walk(directory)) {
                        for (Path file : paths
                                .filter(Files::isRegularFile)
                                .filter(value -> !ignored(value))
                                .toList()) {

                            List<String> lines;

                            try {
                                lines = Files.readAllLines(
                                        file,
                                        StandardCharsets.UTF_8
                                );
                            } catch (IOException exception) {
                                continue;
                            }

                            for (int index = 0;
                                 index < lines.size();
                                 index++) {

                                if (lines.get(index).contains(query)) {
                                    result.append(
                                            projectRoot.relativize(file)
                                    );
                                    result.append(":")
                                            .append(index + 1)
                                            .append(": ")
                                            .append(lines.get(index).trim())
                                            .append("\n");

                                    matches++;

                                    if (matches >= MAX_SEARCH_RESULTS) {
                                        return result
                                                + "... 已达到搜索结果上限";
                                    }
                                }
                            }
                        }
                    }

                    return matches == 0
                            ? "(没有匹配结果)"
                            : result.toString();
                }
        );
    }
}