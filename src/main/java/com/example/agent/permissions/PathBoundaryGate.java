package com.example.agent.permissions;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 第一道闸门：所有文件操作必须留在配置的项目根目录内。
 */
public final class PathBoundaryGate {
    private final Path projectRoot;
    private final Path realProjectRoot;

    public PathBoundaryGate(Path projectRoot) throws IOException {
        this.projectRoot = projectRoot.toAbsolutePath().normalize();
        this.realProjectRoot = this.projectRoot.toRealPath();
    }

    public Path check(String relativePath) throws IOException {
        String value = normalizeInput(relativePath);
        Path candidate = resolveInsideRoot(value);

        if (!Files.exists(candidate)) {
            throw new IllegalArgumentException("路径不存在：" + value);
        }

        Path realCandidate = candidate.toRealPath();
        if (!realCandidate.startsWith(realProjectRoot)) {
            throw new SecurityException("闸门 1 拒绝：符号链接指向项目目录之外：" + value);
        }
        return candidate;
    }

    public Path checkNewFile(String relativePath) throws IOException {
        String value = normalizeInput(relativePath);
        if (".".equals(value)) {
            throw new IllegalArgumentException("新文件路径不能为空");
        }

        Path candidate = resolveInsideRoot(value);
        if (Files.exists(candidate)) {
            throw new IllegalArgumentException("文件已存在，不能使用 create_file：" + value);
        }

        Path parent = candidate.getParent();
        if (parent == null || !Files.isDirectory(parent)) {
            throw new IllegalArgumentException("父目录不存在：" + value);
        }
        Path realParent = parent.toRealPath();
        if (!realParent.startsWith(realProjectRoot)) {
            throw new SecurityException(
                    "闸门 1 拒绝：父目录通过符号链接指向项目之外：" + value
            );
        }
        return candidate;
    }

    private String normalizeInput(String relativePath) {
        return relativePath == null || relativePath.isBlank() ? "." : relativePath;
    }

    private Path resolveInsideRoot(String value) {
        Path candidate = projectRoot.resolve(value).normalize().toAbsolutePath();
        if (!candidate.startsWith(projectRoot)) {
            throw new SecurityException("闸门 1 拒绝：路径超出项目目录：" + value);
        }
        return candidate;
    }

    public Path projectRoot() {
        return projectRoot;
    }
}
