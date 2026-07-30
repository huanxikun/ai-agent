package com.example.agent.todos;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 连续三轮模型响应没有调用 todo_write 时发出一次提醒，然后重新计数。
 */
public final class TodoNagReminder {
    public static final int MISSED_ROUNDS_BEFORE_REMINDER = 3;

    private int missedRounds;

    public boolean recordRound(boolean calledTodoWrite) {
        if (calledTodoWrite) {
            missedRounds = 0;
            return false;
        }

        missedRounds++;
        if (missedRounds < MISSED_ROUNDS_BEFORE_REMINDER) {
            return false;
        }

        missedRounds = 0;
        return true;
    }

    public int missedRounds() {
        return missedRounds;
    }

    public String message(List<TodoItem> currentTodos) {
        String state = currentTodos.isEmpty()
                ? "当前 Todo 列表为空。"
                : currentTodos.stream()
                        .map(item -> "- [" + item.status() + "] " + item.content())
                        .collect(Collectors.joining(
                                "\n",
                                "当前 Todo 列表：\n",
                                ""
                        ));
        return """
                Nag reminder：你已经连续 3 轮没有调用 todo_write。
                对于多步骤任务，请现在调用 todo_write，提交完整的最新 Todo 列表，
                并将每一项状态设为 pending、in_progress 或 completed。
                如果任务确实无需 Todo，请明确说明原因后再完成回答。
                """ + state;
    }
}
