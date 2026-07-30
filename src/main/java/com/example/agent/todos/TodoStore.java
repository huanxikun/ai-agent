package com.example.agent.todos;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 当前进程内唯一的 Todo 状态。进程重启后清空。
 */
public final class TodoStore {
    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("HH:mm:ss");

    private List<TodoItem> items = List.of();

    public synchronized TodoSummary replace(List<TodoItem> nextItems) {
        items = List.copyOf(nextItems);
        TodoSummary summary = summarize(items);
        printToTerminal(items, summary);
        return summary;
    }

    public synchronized List<TodoItem> snapshot() {
        return List.copyOf(items);
    }

    public synchronized TodoSummary summary() {
        return summarize(items);
    }

    private TodoSummary summarize(List<TodoItem> current) {
        int pending = 0;
        int inProgress = 0;
        int completed = 0;
        for (TodoItem item : current) {
            switch (item.status()) {
                case "pending" -> pending++;
                case "in_progress" -> inProgress++;
                case "completed" -> completed++;
                default -> throw new IllegalStateException(
                        "未知 Todo 状态：" + item.status()
                );
            }
        }
        return new TodoSummary(current.size(), pending, inProgress, completed);
    }

    private void printToTerminal(
            List<TodoItem> current,
            TodoSummary summary
    ) {
        System.out.printf(
                "%n[TodoWrite %s] total=%d pending=%d in_progress=%d completed=%d%n",
                LocalDateTime.now().format(TIME),
                summary.total(),
                summary.pending(),
                summary.inProgress(),
                summary.completed()
        );
        if (current.isEmpty()) {
            System.out.println("  (todo list cleared)");
            return;
        }
        for (TodoItem item : current) {
            System.out.printf(
                    "  %s %s%n",
                    statusMarker(item.status()),
                    item.content()
            );
        }
    }

    private String statusMarker(String status) {
        return switch (status) {
            case "pending" -> "[ ]";
            case "in_progress" -> "[>]";
            case "completed" -> "[x]";
            default -> "[?]";
        };
    }

    public record TodoSummary(
            int total,
            int pending,
            int inProgress,
            int completed
    ) {
    }
}
