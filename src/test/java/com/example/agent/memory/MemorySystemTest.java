package com.example.agent.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MemorySystemTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path projectRoot;

    @Test
    void extractsFullDetailBuildsIndexAndLoadsAfterCompaction()
            throws Exception {
        AtomicInteger modelCalls = new AtomicInteger();
        MemorySystem memory = new MemorySystem(
                projectRoot,
                (system, prompt, maxTokens) -> {
                    modelCalls.incrementAndGet();
                    if (prompt.contains("Extract only durable")) {
                        assertTrue(prompt.contains(
                                "必须使用 tab 缩进，不能使用空格，这个细节不能丢"
                        ));
                        return """
                                [{
                                  "name":"user-preference-tabs",
                                  "type":"user",
                                  "description":"用户要求使用 tab 缩进",
                                  "body":"必须使用 tab 缩进，不能使用空格。\\n**How:** 编辑代码时始终使用制表符。"
                                }]
                                """;
                    }
                    assertTrue(prompt.contains("Memory catalog"));
                    return "[0]";
                },
                JSON
        );

        ArrayNode transcript = JSON.createArrayNode();
        transcript.addObject()
                .put("role", "user")
                .put(
                        "content",
                        "必须使用 tab 缩进，不能使用空格，这个细节不能丢"
                );
        transcript.addObject()
                .put("role", "assistant")
                .put("content", "我会遵守。");
        MemorySystem.ExtractionResult extraction =
                memory.extractAndConsolidate(transcript);

        assertEquals(1, extraction.extracted());
        assertFalse(extraction.consolidated());
        assertNull(extraction.error());
        Path memoryFile = projectRoot.resolve(
                ".memory/user-preference-tabs.md"
        );
        assertTrue(Files.readString(memoryFile).contains(
                "编辑代码时始终使用制表符"
        ));
        String index = Files.readString(
                projectRoot.resolve(".memory/MEMORY.md")
        );
        assertTrue(index.contains(
                "[user-preference-tabs](user-preference-tabs.md)"
        ));

        String systemPrompt = memory.buildSystemPrompt("base system");
        assertTrue(systemPrompt.contains("用户要求使用 tab 缩进"));
        assertFalse(systemPrompt.contains("编辑代码时始终使用制表符"));

        MemorySystem.LoadedMemories loaded =
                memory.loadRelevant("请创建一个 Python 文件并处理缩进");
        assertEquals(1, loaded.entries().size());
        assertTrue(loaded.entries().get(0).rawContent().contains(
                "不能使用空格"
        ));

        ArrayNode compacted = JSON.createArrayNode();
        compacted.addObject()
                .put("role", "system")
                .put("content", systemPrompt);
        compacted.addObject()
                .put("role", "user")
                .put("content", "创建 Python 文件");
        ArrayNode request = memory.injectAfterCompaction(compacted, loaded);

        assertEquals("创建 Python 文件", compacted.path(1)
                .path("content").asText());
        assertTrue(request.path(1).path("content").asText()
                .startsWith("<relevant_persistent_memories>"));
        assertTrue(request.path(1).path("content").asText()
                .contains("不能使用空格"));

        ArrayNode summarizedContext = JSON.createArrayNode();
        summarizedContext.addObject()
                .put("role", "system")
                .put("content", "base system");
        summarizedContext.addObject()
                .put("role", "assistant")
                .put("content", "L4 summary");
        ArrayNode summaryRequest = memory.injectAfterCompaction(
                summarizedContext,
                loaded
        );
        assertEquals("system", summaryRequest.path(1).path("role").asText());
        assertTrue(summaryRequest.path(1).path("content").asText()
                .contains("不能使用空格"));
        assertEquals(
                "L4 summary",
                summaryRequest.path(2).path("content").asText()
        );
        assertEquals(2, modelCalls.get());
    }

    @Test
    void selectionFallsBackToKeywordsWhenSideQueryFails()
            throws Exception {
        MemorySystem writer = new MemorySystem(
                projectRoot,
                (system, prompt, maxTokens) -> """
                        [{
                          "name":"frontend-scroll",
                          "type":"project",
                          "description":"frontend scroll position",
                          "body":"Keep the chat scrollbar near the Step panel."
                        }]
                        """,
                JSON
        );
        ArrayNode transcript = JSON.createArrayNode();
        transcript.addObject()
                .put("role", "user")
                .put("content", "remember frontend scroll position");
        assertEquals(
                1,
                writer.extractAndConsolidate(transcript).extracted()
        );

        MemorySystem reader = new MemorySystem(
                projectRoot,
                (system, prompt, maxTokens) -> {
                    throw new IllegalStateException("side query unavailable");
                },
                JSON
        );
        MemorySystem.LoadedMemories loaded =
                reader.loadRelevant("check frontend scroll position");

        assertEquals(1, loaded.entries().size());
        assertEquals("frontend-scroll.md", loaded.entries().get(0).filename());
    }

    @Test
    void consolidatesAtThresholdAndKeepsReturnedFullDetail()
            throws Exception {
        AtomicInteger calls = new AtomicInteger();
        MemorySystem memory = new MemorySystem(
                projectRoot,
                (system, prompt, maxTokens) -> {
                    if (calls.getAndIncrement() == 0) {
                        StringBuilder items = new StringBuilder("[");
                        for (int index = 0; index < 10; index++) {
                            if (index > 0) items.append(',');
                            items.append("""
                                    {"name":"fact-%d","type":"project",
                                    "description":"fact %d",
                                    "body":"full detail %d"}
                                    """.formatted(index, index, index));
                        }
                        return items.append(']').toString();
                    }
                    assertTrue(prompt.contains("Deduplicate"));
                    assertTrue(prompt.contains("full detail 9"));
                    return """
                            [{
                              "name":"facts-consolidated",
                              "type":"project",
                              "description":"all durable project facts",
                              "body":"Merged without losing the exact durable details."
                            }]
                            """;
                },
                JSON
        );
        ArrayNode transcript = JSON.createArrayNode();
        transcript.addObject()
                .put("role", "user")
                .put("content", "remember ten durable project facts");

        MemorySystem.ExtractionResult result =
                memory.extractAndConsolidate(transcript);

        assertEquals(10, result.extracted());
        assertTrue(result.consolidated());
        assertEquals(1, memory.count());
        assertTrue(Files.readString(projectRoot.resolve(
                ".memory/facts-consolidated.md"
        )).contains("exact durable details"));
        assertEquals(2, calls.get());
    }
}
