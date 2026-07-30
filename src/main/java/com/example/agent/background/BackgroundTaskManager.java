package com.example.agent.background;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * S13 background tasks: run slow tool executions asynchronously and inject a
 * later notification when they complete.
 */
public final class BackgroundTaskManager implements AutoCloseable {
    private static final int MAX_TASK_SUMMARY = 200;
    private static final AtomicInteger THREAD_COUNTER = new AtomicInteger();

    private final ExecutorService executor;
    private final ObjectMapper json;
    private final AtomicInteger counter = new AtomicInteger();
    private final ConcurrentMap<String, BackgroundTask> tasks =
            new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<String> completedIds =
            new ConcurrentLinkedQueue<>();

    public BackgroundTaskManager(ObjectMapper json) {
        this(
                Executors.newCachedThreadPool(runnable -> {
                    Thread thread = new Thread(runnable);
                    thread.setName("agent-background-"
                            + THREAD_COUNTER.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                }),
                json
        );
    }

    BackgroundTaskManager(ExecutorService executor, ObjectMapper json) {
        this.executor = Objects.requireNonNull(executor, "executor");
        this.json = Objects.requireNonNull(json, "json");
    }

    public BackgroundStart start(
            String toolName,
            String summary,
            Callable<String> job
    ) {
        String backgroundId = "bg_%04d".formatted(counter.incrementAndGet());
        BackgroundTask task = new BackgroundTask(
                backgroundId,
                toolName,
                normalizeSummary(summary)
        );
        tasks.put(backgroundId, task);
        try {
            executor.submit(() -> {
                try {
                    task.complete(job.call());
                } catch (Exception exception) {
                    task.fail(exception);
                } finally {
                    completedIds.offer(backgroundId);
                }
            });
        } catch (RuntimeException exception) {
            tasks.remove(backgroundId, task);
            throw exception;
        }
        return new BackgroundStart(
                backgroundId,
                toolName,
                task.summary(),
                "running"
        );
    }

    public List<BackgroundNotification> collectNotifications() {
        List<BackgroundNotification> notifications = new ArrayList<>();
        String backgroundId;
        while ((backgroundId = completedIds.poll()) != null) {
            BackgroundTask task = tasks.remove(backgroundId);
            if (task == null) continue;
            notifications.add(task.toNotification());
        }
        return List.copyOf(notifications);
    }

    public BackgroundSummary summary() {
        int running = 0;
        int completed = 0;
        int failed = 0;
        for (BackgroundTask task : tasks.values()) {
            switch (task.status()) {
                case "running" -> running++;
                case "completed" -> completed++;
                case "failed" -> failed++;
                default -> throw new IllegalStateException(
                        "未知后台任务状态：" + task.status()
                );
            }
        }
        return new BackgroundSummary(tasks.size(), running, completed, failed);
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }

    private String normalizeSummary(String summary) {
        String normalized = summary == null ? "" : summary.trim();
        return truncate(
                normalized.isEmpty() ? "后台任务" : normalized,
                MAX_TASK_SUMMARY
        );
    }

    private String summarize(String output, Throwable error) {
        if (error != null) {
            String message = error.getMessage();
            return message == null || message.isBlank()
                    ? error.getClass().getSimpleName()
                    : message;
        }
        if (output == null || output.isBlank()) return "(empty output)";

        try {
            JsonNode node = json.readTree(output);
            for (String field : List.of("message", "result", "status")) {
                String text = node.path(field).asText("").trim();
                if (!text.isEmpty()) return text;
            }
        } catch (Exception ignored) {
            // Fall back to the raw tool output if it is not JSON.
        }
        return output;
    }

    private String truncate(String value, int maxLength) {
        if (value.length() <= maxLength) return value;
        return value.substring(0, maxLength) + "...";
    }

    private final class BackgroundTask {
        private final String id;
        private final String toolName;
        private final String summary;

        private volatile String status = "running";
        private volatile String output;
        private volatile Throwable error;

        private BackgroundTask(String id, String toolName, String summary) {
            this.id = id;
            this.toolName = toolName;
            this.summary = summary;
        }

        private String summary() {
            return summary;
        }

        private String status() {
            return status;
        }

        private void complete(String output) {
            this.output = output;
            this.error = null;
            this.status = "completed";
        }

        private void fail(Throwable error) {
            this.output = null;
            this.error = error;
            this.status = "failed";
        }

        private BackgroundNotification toNotification() {
            return new BackgroundNotification(
                    id,
                    toolName,
                    status,
                    summary,
                    summarize(output, error)
            );
        }
    }

    public record BackgroundStart(
            String id,
            String tool,
            String summary,
            String status
    ) {
    }

    public record BackgroundNotification(
            String id,
            String tool,
            String status,
            String summary,
            String detail
    ) {
    }

    public record BackgroundSummary(
            int total,
            int running,
            int completedPendingDelivery,
            int failedPendingDelivery
    ) {
    }
}
