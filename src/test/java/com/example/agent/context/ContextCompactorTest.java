package com.example.agent.context;

import com.example.agent.DeepSeekClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContextCompactorTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path projectRoot;

    @Test
    void l3OffloadsLargestToolResultAndPreservesFullContent()
            throws Exception {
        String largest = "完整工具结果-".repeat(20_000);
        ArrayNode messages = JSON.createArrayNode();
        messages.addObject().put("role", "system").put("content", "system");
        messages.addObject()
                .put("role", "tool")
                .put("tool_call_id", "large")
                .put("content", largest);
        messages.addObject()
                .put("role", "tool")
                .put("tool_call_id", "small")
                .put("content", "small");

        ContextCompactor compactor = compactor(1_000_000, null);
        ContextCompactor.CompactReport report =
                compactor.compactBeforeModel(messages, "run/unsafe");

        assertEquals(1, report.offloadedToolResults());
        assertTrue(messages.path(1).path("content").asText()
                .contains("L3 toolResultBudget"));
        Path storage = projectRoot.resolve(".agent-context/tool-results");
        List<Path> saved;
        try (var files = Files.walk(storage)) {
            saved = files.filter(Files::isRegularFile).toList();
        }
        assertEquals(1, saved.size());
        assertEquals(largest, Files.readString(saved.get(0)));
    }

    @Test
    void l1KeepsFirstThreeAndLastFortySevenMessages() throws Exception {
        ArrayNode messages = JSON.createArrayNode();
        for (int index = 0; index < 60; index++) {
            messages.addObject()
                    .put("role", index == 0 ? "system" : "user")
                    .put("content", "message-" + index);
        }

        ContextCompactor.CompactReport report =
                compactor(1_000_000, null)
                        .compactBeforeModel(messages, "run");

        assertEquals(10, report.removedMessages());
        assertEquals(50, messages.size());
        assertEquals("message-0", content(messages, 0));
        assertEquals("message-2", content(messages, 2));
        assertEquals("message-13", content(messages, 3));
        assertEquals("message-59", content(messages, 49));
    }

    @Test
    void l2ReplacesOnlyOldToolResultText() throws Exception {
        ArrayNode messages = JSON.createArrayNode();
        for (int index = 0; index < 15; index++) {
            messages.addObject()
                    .put("role", "tool")
                    .put("tool_call_id", "call-" + index)
                    .put("content", "result-" + index);
        }

        ContextCompactor.CompactReport report =
                compactor(1_000_000, null)
                        .compactBeforeModel(messages, "run");

        assertEquals(5, report.microCompactedToolResults());
        assertTrue(content(messages, 0).contains("L2 microCompact"));
        assertEquals("result-5", content(messages, 5));
        assertEquals(15, messages.size());
    }

    @Test
    void l4CallsSummarizerOnceOnlyAfterCheapStagesStillExceedThreshold()
            throws Exception {
        ArrayNode messages = JSON.createArrayNode();
        messages.addObject().put("role", "system").put("content", "system");
        messages.addObject()
                .put("role", "user")
                .put("content", "需要完整总结的用户目标");
        AtomicInteger calls = new AtomicInteger();
        ContextCompactor compactor = compactor(1, fullContext -> {
            calls.incrementAndGet();
            assertEquals(2, fullContext.size());
            assertTrue(fullContext.toString().contains("用户目标"));
            return "目标：保留关键事实";
        });

        ContextCompactor.CompactReport report =
                compactor.compactBeforeModel(messages, "run");

        assertTrue(report.autoCompacted());
        assertEquals(1, calls.get());
        assertEquals("system", content(messages, 0));
        assertTrue(content(messages, 1).contains("L4 autoCompact 全量摘要"));
        assertTrue(content(messages, 1).contains("保留关键事实"));
        assertEquals(3, messages.size());
    }

    @Test
    void reactiveCompactUsesByteTrimmingAndKeepsFiveRecentMessages() {
        ArrayNode messages = JSON.createArrayNode();
        for (int index = 0; index < 10; index++) {
            messages.addObject()
                    .put("role", "user")
                    .put("content", ("中文-" + index).repeat(5_000));
        }

        ContextCompactor.CompactReport report =
                compactor(1_000_000, null).reactiveCompact(messages);

        assertTrue(report.reactiveCompacted());
        assertEquals(7, messages.size());
        assertTrue(content(messages, 1).contains("reactive summary"));
        for (int index = 2; index < messages.size(); index++) {
            assertTrue(
                    content(messages, index).getBytes(
                            java.nio.charset.StandardCharsets.UTF_8
                    ).length <= 8 * 1024 + 32
            );
        }
    }

    @Test
    void detectsOnlyPromptLengthFailuresForReactiveFallback() {
        assertTrue(DeepSeekClient.isPromptTooLong(
                new DeepSeekClient.DeepSeekException(413, "", "too large")
        ));
        assertTrue(DeepSeekClient.isPromptTooLong(
                new IllegalStateException("prompt_too_long")
        ));
        assertTrue(DeepSeekClient.isPromptTooLong(
                new DeepSeekClient.DeepSeekException(
                        400,
                        "prompt_too_long",
                        "request rejected"
                )
        ));
        assertFalse(DeepSeekClient.isPromptTooLong(
                new DeepSeekClient.DeepSeekException(500, "", "server error")
        ));
    }

    @Test
    void l4PromptTooLongPropagatesWithoutReactiveCompaction() {
        ArrayNode messages = JSON.createArrayNode();
        messages.addObject().put("role", "system").put("content", "system");
        messages.addObject().put("role", "user").put("content", "long task");
        ContextCompactor compactor = compactor(
                1,
                fullContext -> {
                    throw new DeepSeekClient.DeepSeekException(
                            413,
                            "prompt_too_long",
                            "summary too long"
                    );
                }
        );

        assertThrows(
                DeepSeekClient.DeepSeekException.class,
                () -> compactor.compactBeforeModel(messages, "run")
        );
        assertFalse(messages.toString().contains("reactiveCompact"));
    }

    @Test
    void rejectsInvalidThreshold() {
        assertThrows(
                IllegalArgumentException.class,
                () -> compactor(0, null)
        );
    }

    private ContextCompactor compactor(
            int threshold,
            ContextCompactor.Summarizer summarizer
    ) {
        return new ContextCompactor(
                projectRoot,
                threshold,
                summarizer,
                JSON
        );
    }

    private String content(ArrayNode messages, int index) {
        JsonNode message = messages.get(index);
        return message.path("content").asText();
    }
}
