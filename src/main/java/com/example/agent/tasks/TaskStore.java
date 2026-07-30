package com.example.agent.tasks;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * S12 disk-backed task graph. TodoWrite remains a separate process-local list.
 */
public final class TaskStore {
    private static final Pattern TASK_ID =
            Pattern.compile("task_[A-Za-z0-9_-]{1,96}");
    private static final Set<String> STATUSES = Set.of(
            "pending",
            "in_progress",
            "completed"
    );
    private static final int MAX_SUBJECT_LENGTH = 200;
    private static final int MAX_DESCRIPTION_LENGTH = 10_000;
    private static final int MAX_DEPENDENCIES = 100;

    private final Path taskDirectory;
    private final ObjectMapper json;
    private final SecureRandom random = new SecureRandom();

    public TaskStore(Path projectRoot, ObjectMapper json) {
        this.taskDirectory = projectRoot.toAbsolutePath()
                .normalize()
                .resolve(".tasks");
        this.json = json;
    }

    public synchronized PersistentTask create(
            String subject,
            String description,
            List<String> blockedBy
    ) throws IOException {
        String normalizedSubject = requireText(
                subject,
                "subject",
                MAX_SUBJECT_LENGTH
        );
        String normalizedDescription = description == null
                ? ""
                : description.trim();
        if (normalizedDescription.length() > MAX_DESCRIPTION_LENGTH) {
            throw new IllegalArgumentException(
                    "description 不能超过 " + MAX_DESCRIPTION_LENGTH + " 字符"
            );
        }
        List<String> dependencies = normalizeDependencies(blockedBy);
        Files.createDirectories(taskDirectory);

        String id;
        do {
            id = "task_%d_%04x".formatted(
                    Instant.now().toEpochMilli(),
                    random.nextInt(0x10000)
            );
        } while (Files.exists(taskPath(id)));

        PersistentTask task = new PersistentTask(
                id,
                normalizedSubject,
                normalizedDescription,
                "pending",
                null,
                dependencies
        );
        save(task);
        return task;
    }

    public synchronized List<PersistentTask> list() throws IOException {
        if (!Files.isDirectory(taskDirectory)) return List.of();
        try (Stream<Path> files = Files.list(taskDirectory)) {
            List<PersistentTask> tasks = new ArrayList<>();
            for (Path file : files
                    .filter(path -> path.getFileName()
                            .toString()
                            .matches("task_.*\\.json"))
                    .sorted()
                    .toList()) {
                tasks.add(read(file));
            }
            return List.copyOf(tasks);
        }
    }

    public synchronized PersistentTask get(String taskId) throws IOException {
        return read(taskPath(requireTaskId(taskId)));
    }

    public synchronized boolean canStart(String taskId) throws IOException {
        return unresolvedDependencies(get(taskId)).isEmpty();
    }

    public synchronized ActionResult claim(
            String taskId,
            String owner
    ) throws IOException {
        PersistentTask task = get(taskId);
        if (!"pending".equals(task.status())) {
            return ActionResult.rejected(
                    task,
                    "Task %s is %s, cannot claim"
                            .formatted(task.id(), task.status()),
                    List.of()
            );
        }

        List<String> blockedBy = unresolvedDependencies(task);
        if (!blockedBy.isEmpty()) {
            return ActionResult.rejected(
                    task,
                    "Blocked by: " + blockedBy,
                    blockedBy
            );
        }

        String normalizedOwner = requireText(owner, "owner", 200);
        PersistentTask claimed = new PersistentTask(
                task.id(),
                task.subject(),
                task.description(),
                "in_progress",
                normalizedOwner,
                task.blockedBy()
        );
        save(claimed);
        return ActionResult.updated(
                claimed,
                "Claimed %s (%s)".formatted(claimed.id(), claimed.subject()),
                List.of()
        );
    }

    public synchronized ActionResult complete(String taskId)
            throws IOException {
        PersistentTask task = get(taskId);
        if (!"in_progress".equals(task.status())) {
            return ActionResult.rejected(
                    task,
                    "Task %s is %s, cannot complete"
                            .formatted(task.id(), task.status()),
                    List.of()
            );
        }

        PersistentTask completed = new PersistentTask(
                task.id(),
                task.subject(),
                task.description(),
                "completed",
                task.owner(),
                task.blockedBy()
        );
        save(completed);

        List<String> unblocked = new ArrayList<>();
        for (PersistentTask candidate : list()) {
            if ("pending".equals(candidate.status())
                    && !candidate.blockedBy().isEmpty()
                    && unresolvedDependencies(candidate).isEmpty()) {
                unblocked.add(candidate.id());
            }
        }
        String message = "Completed %s (%s)"
                .formatted(completed.id(), completed.subject());
        if (!unblocked.isEmpty()) {
            message += "\nUnblocked: " + String.join(", ", unblocked);
        }
        return ActionResult.updated(completed, message, unblocked);
    }

    public synchronized TaskSummary summary() {
        try {
            int pending = 0;
            int inProgress = 0;
            int completed = 0;
            for (PersistentTask task : list()) {
                switch (task.status()) {
                    case "pending" -> pending++;
                    case "in_progress" -> inProgress++;
                    case "completed" -> completed++;
                    default -> throw new IllegalStateException(
                            "未知任务状态：" + task.status()
                    );
                }
            }
            return new TaskSummary(
                    pending + inProgress + completed,
                    pending,
                    inProgress,
                    completed
            );
        } catch (IOException exception) {
            throw new UncheckedIOException("读取持久任务失败", exception);
        }
    }

    public Path directory() {
        return taskDirectory;
    }

    private List<String> unresolvedDependencies(PersistentTask task)
            throws IOException {
        List<String> unresolved = new ArrayList<>();
        for (String dependencyId : task.blockedBy()) {
            Path dependencyPath = taskPath(dependencyId);
            if (!Files.isRegularFile(dependencyPath)
                    || !"completed".equals(read(dependencyPath).status())) {
                unresolved.add(dependencyId);
            }
        }
        return List.copyOf(unresolved);
    }

    private List<String> normalizeDependencies(List<String> dependencies) {
        if (dependencies == null) return List.of();
        if (dependencies.size() > MAX_DEPENDENCIES) {
            throw new IllegalArgumentException(
                    "blockedBy 最多 " + MAX_DEPENDENCIES + " 项"
            );
        }
        List<String> normalized = new ArrayList<>();
        Set<String> unique = new HashSet<>();
        for (String dependency : dependencies) {
            String id = requireTaskId(dependency);
            if (!unique.add(id)) {
                throw new IllegalArgumentException(
                        "blockedBy 不能包含重复任务：" + id
                );
            }
            normalized.add(id);
        }
        return List.copyOf(normalized);
    }

    private PersistentTask read(Path path) throws IOException {
        if (!Files.isRegularFile(path)) {
            throw new IllegalArgumentException(
                    "Task %s not found".formatted(
                            path.getFileName().toString()
                                    .replaceFirst("\\.json$", "")
                    )
            );
        }
        PersistentTask task = json.readValue(path.toFile(), PersistentTask.class);
        validateStoredTask(task, path);
        return task;
    }

    private void validateStoredTask(PersistentTask task, Path source) {
        if (task == null || !TASK_ID.matcher(task.id()).matches()) {
            throw new IllegalStateException("任务文件 ID 无效：" + source);
        }
        if (!STATUSES.contains(task.status())) {
            throw new IllegalStateException(
                    "任务状态无效：" + task.id() + " -> " + task.status()
            );
        }
        requireText(task.subject(), "subject", MAX_SUBJECT_LENGTH);
        if (task.description() == null
                || task.description().length() > MAX_DESCRIPTION_LENGTH) {
            throw new IllegalStateException(
                    "任务描述无效：" + task.id()
            );
        }
        normalizeDependencies(task.blockedBy());
    }

    private void save(PersistentTask task) throws IOException {
        Files.createDirectories(taskDirectory);
        Path target = taskPath(task.id());
        Path temporary = Files.createTempFile(
                taskDirectory,
                task.id() + "-",
                ".tmp"
        );
        try {
            json.writerWithDefaultPrettyPrinter()
                    .writeValue(temporary.toFile(), task);
            try {
                Files.move(
                        temporary,
                        target,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING
                );
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(
                        temporary,
                        target,
                        StandardCopyOption.REPLACE_EXISTING
                );
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private Path taskPath(String taskId) {
        return taskDirectory.resolve(taskId + ".json").normalize();
    }

    private String requireTaskId(String taskId) {
        String normalized = taskId == null ? "" : taskId.trim();
        if (!TASK_ID.matcher(normalized).matches()) {
            throw new IllegalArgumentException("task_id 格式无效");
        }
        return normalized;
    }

    private String requireText(String value, String field, int maxLength) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty() || normalized.length() > maxLength) {
            throw new IllegalArgumentException(
                    "%s 必须为 1 到 %d 个字符"
                            .formatted(field, maxLength)
            );
        }
        return normalized;
    }

    public record ActionResult(
            String status,
            String message,
            PersistentTask task,
            List<String> relatedTasks
    ) {
        private static ActionResult updated(
                PersistentTask task,
                String message,
                List<String> relatedTasks
        ) {
            return new ActionResult(
                    "updated",
                    message,
                    task,
                    List.copyOf(relatedTasks)
            );
        }

        private static ActionResult rejected(
                PersistentTask task,
                String message,
                List<String> relatedTasks
        ) {
            return new ActionResult(
                    "rejected",
                    message,
                    task,
                    List.copyOf(relatedTasks)
            );
        }
    }

    public record TaskSummary(
            int total,
            int pending,
            int inProgress,
            int completed
    ) {
    }
}
