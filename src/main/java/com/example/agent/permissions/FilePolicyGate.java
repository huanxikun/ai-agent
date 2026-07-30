package com.example.agent.permissions;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;

/**
 * 第二道闸门：保护敏感目录、密钥文件、二进制文件和过大的文件。
 */
public final class FilePolicyGate {
    public static final long MAX_TEXT_FILE_BYTES = 1024 * 1024;
    public static final int MAX_REPLACEMENT_CHARS = 200_000;

    private static final Set<String> PROTECTED_DIRECTORIES = Set.of(
            ".git",
            ".idea",
            "target",
            "node_modules"
    );
    private static final Set<String> PROTECTED_FILE_NAMES = Set.of(
            ".env",
            "id_rsa",
            "id_ed25519"
    );
    private static final Set<String> PROTECTED_EXTENSIONS = Set.of(
            ".key",
            ".pem",
            ".p12",
            ".pfx",
            ".jks",
            ".class",
            ".jar",
            ".exe",
            ".dll",
            ".so",
            ".png",
            ".jpg",
            ".jpeg",
            ".gif",
            ".pdf",
            ".zip"
    );

    private final Path projectRoot;

    public FilePolicyGate(Path projectRoot) {
        this.projectRoot = projectRoot.toAbsolutePath().normalize();
    }

    public void check(Path path, FileOperation operation) throws IOException {
        Path relative = projectRoot.relativize(path.toAbsolutePath().normalize());
        for (Path segment : relative) {
            if (PROTECTED_DIRECTORIES.contains(segment.toString())) {
                throw new SecurityException(
                        "闸门 2 拒绝：受保护目录不可操作：" + relative
                );
            }
        }

        if (operation == FileOperation.LIST || operation == FileOperation.SEARCH) {
            if (!Files.isDirectory(path)) {
                throw new IllegalArgumentException("不是目录：" + relative);
            }
            return;
        }

        String fileName = path.getFileName().toString().toLowerCase(Locale.ROOT);
        checkFileName(relative, fileName);

        if (operation == FileOperation.CREATE) {
            if (Files.exists(path)) {
                throw new IllegalArgumentException("文件已存在：" + relative);
            }
            if (!Files.isDirectory(path.getParent())) {
                throw new IllegalArgumentException("父目录不存在：" + relative);
            }
            return;
        }

        if (!Files.isRegularFile(path)) {
            throw new IllegalArgumentException("不是普通文件：" + relative);
        }
        if (operation.isMutation() && Files.isSymbolicLink(path)) {
            throw new SecurityException("闸门 2 拒绝：不允许修改或删除符号链接");
        }

        long size = Files.size(path);
        if (size > MAX_TEXT_FILE_BYTES) {
            throw new SecurityException(
                    "闸门 2 拒绝：文件超过 1 MiB 限制：" + relative
            );
        }
        if (looksBinary(path)) {
            throw new SecurityException("闸门 2 拒绝：二进制文件不可操作：" + relative);
        }
    }

    private void checkFileName(Path relative, String fileName) {
        if (PROTECTED_FILE_NAMES.contains(fileName)
                || fileName.startsWith(".env.")) {
            throw new SecurityException("闸门 2 拒绝：敏感配置文件不可操作：" + relative);
        }
        for (String extension : PROTECTED_EXTENSIONS) {
            if (fileName.endsWith(extension)) {
                throw new SecurityException("闸门 2 拒绝：该文件类型不可操作：" + relative);
            }
        }
    }

    public void checkReplacement(String oldText, String newText) {
        if (oldText == null || oldText.isEmpty()) {
            throw new IllegalArgumentException("oldText 不能为空");
        }
        if (newText == null) {
            throw new IllegalArgumentException("newText 不能为空");
        }
        if (oldText.length() > MAX_REPLACEMENT_CHARS
                || newText.length() > MAX_REPLACEMENT_CHARS) {
            throw new SecurityException("闸门 2 拒绝：单次替换内容超过 200,000 字符");
        }
    }

    public void checkCreateContent(String content) {
        checkProposedContent(content);
    }

    public void checkProposedContent(String content) {
        if (content == null) {
            throw new IllegalArgumentException("content 不能为空");
        }
        if (content.getBytes(StandardCharsets.UTF_8).length > MAX_TEXT_FILE_BYTES) {
            throw new SecurityException("闸门 2 拒绝：文件内容超过 1 MiB");
        }
        if (content.indexOf('\0') >= 0) {
            throw new SecurityException("闸门 2 拒绝：文件内容疑似二进制");
        }
    }

    private boolean looksBinary(Path path) throws IOException {
        byte[] buffer = new byte[4096];
        try (InputStream input = Files.newInputStream(path)) {
            int count = input.read(buffer);
            for (int index = 0; index < count; index++) {
                if (buffer[index] == 0) return true;
            }
        }
        return false;
    }
}
