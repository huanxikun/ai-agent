package com.example.agent.background;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BackgroundTaskManagerTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void runsTaskInBackgroundAndProducesNotification() throws Exception {
        try (BackgroundTaskManager manager = new BackgroundTaskManager(
                Executors.newSingleThreadExecutor(),
                JSON
        )) {
            BackgroundTaskManager.BackgroundStart started = manager.start(
                    "task",
                    "研究 HookRegistry",
                    () -> """
                            {"status":"completed","result":"HookRegistry 已读取完成"}
                            """
            );

            assertEquals("bg_0001", started.id());
            assertEquals("running", started.status());

            List<BackgroundTaskManager.BackgroundNotification> notifications =
                    waitForNotifications(manager);
            assertEquals(1, notifications.size());
            BackgroundTaskManager.BackgroundNotification notification =
                    notifications.get(0);
            assertEquals("bg_0001", notification.id());
            assertEquals("task", notification.tool());
            assertEquals("completed", notification.status());
            assertEquals("研究 HookRegistry", notification.summary());
            assertTrue(notification.detail().contains("HookRegistry"));
            assertEquals(0, manager.summary().total());
        }
    }

    @Test
    void reportsFailureWithoutThrowingDuringCollection() throws Exception {
        try (BackgroundTaskManager manager = new BackgroundTaskManager(
                Executors.newSingleThreadExecutor(),
                JSON
        )) {
            manager.start(
                    "task",
                    "失败任务",
                    () -> {
                        throw new IllegalStateException("subagent boom");
                    }
            );

            BackgroundTaskManager.BackgroundNotification notification =
                    waitForNotifications(manager).get(0);
            assertEquals("failed", notification.status());
            assertTrue(notification.detail().contains("subagent boom"));
        }
    }

    @Test
    void preservesCompleteBackgroundResultInsteadOfDroppingAfter200Characters()
            throws Exception {
        try (BackgroundTaskManager manager = new BackgroundTaskManager(
                Executors.newSingleThreadExecutor(),
                JSON
        )) {
            String result = "完整研究结论-" + "x".repeat(500);
            manager.start(
                    "task",
                    "长结果",
                    () -> JSON.writeValueAsString(
                            java.util.Map.of(
                                    "status", "completed",
                                    "result", result
                            )
                    )
            );

            BackgroundTaskManager.BackgroundNotification notification =
                    waitForNotifications(manager).get(0);
            assertEquals(result, notification.detail());
        }
    }

    @Test
    void rejectedSubmissionDoesNotLeaveGhostRunningTask() {
        var executor = Executors.newSingleThreadExecutor();
        executor.shutdownNow();
        try (BackgroundTaskManager manager =
                     new BackgroundTaskManager(executor, JSON)) {
            assertThrows(
                    RejectedExecutionException.class,
                    () -> manager.start("task", "无法提交", () -> "never")
            );
            assertEquals(0, manager.summary().total());
            assertTrue(manager.collectNotifications().isEmpty());
        }
    }

    private List<BackgroundTaskManager.BackgroundNotification> waitForNotifications(
            BackgroundTaskManager manager
    ) throws Exception {
        for (int attempt = 0; attempt < 50; attempt++) {
            List<BackgroundTaskManager.BackgroundNotification> notifications =
                    manager.collectNotifications();
            if (!notifications.isEmpty()) return notifications;
            Thread.sleep(10);
        }
        throw new AssertionError("后台任务通知未在预期时间内到达");
    }
}
