package com.example.agent.tasks;

import java.util.List;

/**
 * A durable unit of work stored as one JSON file under .tasks/.
 */
public record PersistentTask(
        String id,
        String subject,
        String description,
        String status,
        String owner,
        List<String> blockedBy
) {
    public PersistentTask {
        blockedBy = blockedBy == null ? List.of() : List.copyOf(blockedBy);
    }
}
