package com.example.agent.tools;

import com.example.agent.permissions.FileOperation;
import com.example.agent.permissions.FilePermissionService;
import com.example.agent.permissions.FilePolicyGate;
import com.example.agent.permissions.HumanApprovalGate;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

public final class CodeTools {
    private static final int MAX_FILE_LINES = 400;
    private static final int MAX_SEARCH_RESULTS = 50;
    private static final int MAX_PREVIEW_CHARS = 3_000;

    private final Path projectRoot;
    private final ObjectMapper json;
    private final FilePermissionService permissions;
    private final HumanApprovalGate approvals;

    public CodeTools(
            FilePermissionService permissions,
            HumanApprovalGate approvals,
            ObjectMapper json
    ) {
        this.projectRoot = permissions.projectRoot();
        this.permissions = permissions;
        this.approvals = approvals;
        this.json = json;
    }

    public void registerInto(ToolRegistry registry) {
        registry
                .register(listFilesTool())
                .register(readFileTool())
                .register(searchCodeTool())
                .register(createFileTool())
                .register(editFileTool())
                .register(deleteFileTool());
    }

    private boolean ignored(Path path) {
        String value = projectRoot
                .relativize(path)
                .toString()
                .replace('\\', '/');

        return value.startsWith(".git/")
                || value.startsWith("target/")
                || value.startsWith("node_modules/")
                || value.startsWith(".idea/")
                || value.equals(".env")
                || value.startsWith(".env.");
    }

    // 列出项目内的文件。
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

                    Path directory = permissions.check(path, FileOperation.LIST);

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

                    Path file = permissions.check(relativePath, FileOperation.READ);

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

                    Path directory = permissions.check(path, FileOperation.SEARCH);
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

    private ToolDefinition createFileTool() {
        ObjectNode parameters = json.createObjectNode();
        parameters.put("type", "object");
        ObjectNode properties = parameters.putObject("properties");
        properties.putObject("path")
                .put("type", "string")
                .put("description", "相对于项目根目录的新文件路径；父目录必须已经存在");
        properties.putObject("content")
                .put("type", "string")
                .put("description", "新文本文件的完整内容");
        parameters.putArray("required")
                .add("path")
                .add("content");
        parameters.put("additionalProperties", false);

        return new ToolDefinition(
                "create_file",
                "创建新的文本文件，绝不覆盖已有文件。调用只创建审批请求；用户批准后才落盘。",
                parameters,
                arguments -> {
                    String relativePath = arguments.path("path").asText();
                    String content = arguments.path("content").asText(null);
                    permissions.checkCreateContent(content);

                    Path file = permissions.check(relativePath, FileOperation.CREATE);
                    String displayPath = displayPath(file);
                    HumanApprovalGate.ApprovalRequest request = approvals.request(
                            FileOperation.CREATE,
                            displayPath,
                            createPreview(displayPath, content),
                            () -> {
                                Path approvedFile = permissions.check(
                                        relativePath,
                                        FileOperation.CREATE
                                );
                                createAtomically(approvedFile, content);
                                return json.writeValueAsString(Map.of(
                                        "message", "文件创建成功",
                                        "path", displayPath
                                ));
                            }
                    );
                    return json.writeValueAsString(request);
                }
        );
    }

    private ToolDefinition editFileTool() {
        ObjectNode parameters = json.createObjectNode();
        parameters.put("type", "object");
        ObjectNode properties = parameters.putObject("properties");
        properties.putObject("path")
                .put("type", "string")
                .put("description", "相对于项目根目录的现有文本文件路径");
        properties.putObject("oldText")
                .put("type", "string")
                .put("description", "文件中需要被替换的精确文本，必须只出现一次");
        properties.putObject("newText")
                .put("type", "string")
                .put("description", "替换后的文本");
        parameters.putArray("required")
                .add("path")
                .add("oldText")
                .add("newText");
        parameters.put("additionalProperties", false);

        return new ToolDefinition(
                "edit_file",
                "精确替换现有文本文件中的一段内容。调用只会创建审批请求；用户批准后才写入。",
                parameters,
                arguments -> {
                    String relativePath = arguments.path("path").asText();
                    String oldText = arguments.path("oldText").asText(null);
                    String newText = arguments.path("newText").asText(null);
                    permissions.checkReplacement(oldText, newText);

                    Path file = permissions.check(relativePath, FileOperation.EDIT);
                    String before = Files.readString(file, StandardCharsets.UTF_8);
                    int occurrences = countOccurrences(before, oldText);
                    if (occurrences != 1) {
                        throw new IllegalArgumentException(
                                occurrences == 0
                                        ? "oldText 在文件中不存在"
                                        : "oldText 在文件中出现多次，无法安全确定修改位置"
                        );
                    }

                    String after = before.replace(oldText, newText);
                    if (after.getBytes(StandardCharsets.UTF_8).length
                            > FilePolicyGate.MAX_TEXT_FILE_BYTES) {
                        throw new SecurityException("闸门 2 拒绝：修改后的文件超过 1 MiB");
                    }
                    String expectedHash = sha256(before);
                    String displayPath = displayPath(file);

                    HumanApprovalGate.ApprovalRequest request = approvals.request(
                            FileOperation.EDIT,
                            displayPath,
                            editPreview(displayPath, oldText, newText),
                            () -> {
                                Path approvedFile = permissions.check(
                                        relativePath,
                                        FileOperation.EDIT
                                );
                                String current = Files.readString(
                                        approvedFile,
                                        StandardCharsets.UTF_8
                                );
                                if (!sha256(current).equals(expectedHash)) {
                                    throw new IllegalStateException(
                                            "文件在等待审批期间发生变化，本次修改已取消"
                                    );
                                }
                                replaceAtomically(approvedFile, after);
                                return json.writeValueAsString(Map.of(
                                        "message", "文件修改成功",
                                        "path", displayPath
                                ));
                            }
                    );
                    return json.writeValueAsString(request);
                }
        );
    }

    private ToolDefinition deleteFileTool() {
        ObjectNode parameters = json.createObjectNode();
        parameters.put("type", "object");
        ObjectNode properties = parameters.putObject("properties");
        properties.putObject("path")
                .put("type", "string")
                .put("description", "相对于项目根目录、需要删除的现有文本文件路径");
        parameters.putArray("required").add("path");
        parameters.put("additionalProperties", false);

        return new ToolDefinition(
                "delete_file",
                "删除项目中的一个文本文件。调用只会创建审批请求；用户批准后才删除。",
                parameters,
                arguments -> {
                    String relativePath = arguments.path("path").asText();
                    Path file = permissions.check(relativePath, FileOperation.DELETE);
                    String before = Files.readString(file, StandardCharsets.UTF_8);
                    String expectedHash = sha256(before);
                    String displayPath = displayPath(file);
                    long size = Files.size(file);

                    HumanApprovalGate.ApprovalRequest request = approvals.request(
                            FileOperation.DELETE,
                            displayPath,
                            "删除文件：" + displayPath + "\n文件大小：" + size + " bytes",
                            () -> {
                                Path approvedFile = permissions.check(
                                        relativePath,
                                        FileOperation.DELETE
                                );
                                String current = Files.readString(
                                        approvedFile,
                                        StandardCharsets.UTF_8
                                );
                                if (!sha256(current).equals(expectedHash)) {
                                    throw new IllegalStateException(
                                            "文件在等待审批期间发生变化，本次删除已取消"
                                    );
                                }
                                Files.delete(approvedFile);
                                return json.writeValueAsString(Map.of(
                                        "message", "文件删除成功",
                                        "path", displayPath
                                ));
                            }
                    );
                    return json.writeValueAsString(request);
                }
        );
    }

    private String displayPath(Path file) {
        return projectRoot.relativize(file)
                .toString()
                .replace('\\', '/');
    }

    private String editPreview(String path, String oldText, String newText) {
        String preview = "修改文件：" + path
                + "\n\n--- 原内容\n" + oldText
                + "\n\n+++ 新内容\n" + newText;
        if (preview.length() <= MAX_PREVIEW_CHARS) return preview;
        return preview.substring(0, MAX_PREVIEW_CHARS) + "\n... 预览已截断";
    }

    private String createPreview(String path, String content) {
        String preview = "创建文件：" + path + "\n\n+++ 文件内容\n" + content;
        if (preview.length() <= MAX_PREVIEW_CHARS) return preview;
        return preview.substring(0, MAX_PREVIEW_CHARS) + "\n... 预览已截断";
    }

    private int countOccurrences(String content, String query) {
        int count = 0;
        int cursor = 0;
        while ((cursor = content.indexOf(query, cursor)) >= 0) {
            count++;
            cursor += query.length();
        }
        return count;
    }

    private String sha256(String content) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(content.getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(digest);
    }

    private void replaceAtomically(Path file, String content) throws IOException {
        Path temporary = Files.createTempFile(file.getParent(), ".agent-edit-", ".tmp");
        try {
            Files.writeString(temporary, content, StandardCharsets.UTF_8);
            try {
                Files.move(
                        temporary,
                        file,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING
                );
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private void createAtomically(Path file, String content) throws IOException {
        Path temporary = Files.createTempFile(file.getParent(), ".agent-create-", ".tmp");
        try {
            Files.writeString(temporary, content, StandardCharsets.UTF_8);
            // 不使用 REPLACE_EXISTING，目标在审批后出现时必须失败，绝不覆盖。
            Files.move(temporary, file);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
