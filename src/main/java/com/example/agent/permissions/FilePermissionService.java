package com.example.agent.permissions;

import java.io.IOException;
import java.nio.file.Path;

/**
 * 顺序执行前两道闸门；变更真正落盘前必须重新调用。
 */
public final class FilePermissionService {
    private final PathBoundaryGate boundaryGate;
    private final FilePolicyGate policyGate;

    public FilePermissionService(Path projectRoot) throws IOException {
        this.boundaryGate = new PathBoundaryGate(projectRoot);
        this.policyGate = new FilePolicyGate(boundaryGate.projectRoot());
    }

    public Path check(String relativePath, FileOperation operation) throws IOException {
        Path path = operation == FileOperation.CREATE
                ? boundaryGate.checkNewFile(relativePath)
                : boundaryGate.check(relativePath);
        policyGate.check(path, operation);
        return path;
    }

    public void checkReplacement(String oldText, String newText) {
        policyGate.checkReplacement(oldText, newText);
    }

    public void checkCreateContent(String content) {
        policyGate.checkCreateContent(content);
    }

    public void checkProposedContent(String content) {
        policyGate.checkProposedContent(content);
    }

    public Path projectRoot() {
        return boundaryGate.projectRoot();
    }
}
