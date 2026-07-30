package com.example.agent.context;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/**
 * S08 context compaction pipeline. The zero-API stages always run in the
 * requested L3 -> L1 -> L2 order before the optional L4 summary.
 */
public final class ContextCompactor {
    public static final int DEFAULT_TOKEN_THRESHOLD = 24_000;
    static final int TOOL_RESULT_BUDGET_BYTES = 200 * 1024;
    static final int MESSAGE_LIMIT = 50;
    static final int OLD_TOOL_RESULT_DISTANCE = 10;
    private static final int L4_RECENT_MESSAGES = 5;
    private static final int REACTIVE_CONTENT_BYTES = 8 * 1024;

    private final Path storageRoot;
    private final int tokenThreshold;
    private final Summarizer summarizer;
    private final ObjectMapper json;

    public ContextCompactor(
            Path projectRoot,
            int tokenThreshold,
            Summarizer summarizer,
            ObjectMapper json
    ) {
        if (tokenThreshold < 1) {
            throw new IllegalArgumentException("token threshold 必须大于 0");
        }
        this.storageRoot = projectRoot.toAbsolutePath()
                .normalize()
                .resolve(".agent-context")
                .resolve("tool-results");
        this.tokenThreshold = tokenThreshold;
        this.summarizer = summarizer;
        this.json = json;
    }

    public CompactReport compactBeforeModel(
            ArrayNode messages,
            String runId
    ) throws Exception {
        int offloaded = toolResultBudget(messages, runId);
        int removed = snipCompact(messages);
        int microCompacted = microCompact(messages);
        int tokensBeforeL4 = estimateTokens(messages);
        boolean autoCompacted = false;

        if (tokensBeforeL4 > tokenThreshold) {
            if (summarizer == null) {
                throw new IllegalStateException(
                        "上下文超过阈值，但未配置 L4 summarizer"
                );
            }
            ArrayNode fullContext = messages.deepCopy();
            String summary = summarizer.summarize(fullContext);
            rebuildWithSummary(messages, summary, "L4 autoCompact");
            autoCompacted = true;
        }

        return new CompactReport(
                offloaded,
                removed,
                microCompacted,
                autoCompacted,
                false,
                tokensBeforeL4,
                estimateTokens(messages),
                messages.size()
        );
    }

    public CompactReport reactiveCompact(ArrayNode messages) {
        int beforeTokens = estimateTokens(messages);
        List<JsonNode> recent = tailExcludingFirstSystem(
                messages,
                L4_RECENT_MESSAGES
        );
        String emergencySummary = buildEmergencySummary(messages);

        messages.removeAll();
        messages.addObject()
                .put("role", "system")
                .put(
                        "content",
                        "S08 reactiveCompact：API 拒绝了过长上下文。"
                                + "以下内容经过字节级裁剪。"
                );
        messages.addObject()
                .put("role", "system")
                .put("content", emergencySummary);
        for (JsonNode item : recent) {
            messages.add(sanitizeForReactive(item));
        }
        normalizeToolPairs(messages);

        return new CompactReport(
                0,
                0,
                0,
                false,
                true,
                beforeTokens,
                estimateTokens(messages),
                messages.size()
        );
    }

    public int estimateTokens(ArrayNode messages) {
        try {
            int bytes = json.writeValueAsBytes(messages).length;
            return Math.max(1, (bytes + 2) / 3);
        } catch (IOException exception) {
            throw new IllegalStateException("无法估算上下文 token", exception);
        }
    }

    private int toolResultBudget(ArrayNode messages, String runId)
            throws Exception {
        int offloaded = 0;
        while (toolResultBytes(messages) > TOOL_RESULT_BUDGET_BYTES) {
            ObjectNode largest = largestInlineToolResult(messages);
            if (largest == null) break;
            String content = largest.path("content").asText("");
            byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
            String hash = sha256(bytes);
            Path target = persist(runId, hash, content);
            largest.put(
                    "content",
                    "[L3 toolResultBudget: 完整结果已落盘]"
                            + "\npath: " + displayPath(target)
                            + "\nbytes: " + bytes.length
                            + "\nsha256: " + hash
            );
            offloaded++;
        }
        return offloaded;
    }

    private int snipCompact(ArrayNode messages) {
        if (messages.size() <= MESSAGE_LIMIT) return 0;
        int removed = messages.size() - MESSAGE_LIMIT;
        List<JsonNode> kept = new ArrayList<>(MESSAGE_LIMIT);
        for (int index = 0; index < 3; index++) {
            kept.add(messages.get(index).deepCopy());
        }
        for (int index = messages.size() - 47;
             index < messages.size();
             index++) {
            kept.add(messages.get(index).deepCopy());
        }
        messages.removeAll();
        messages.addAll(kept);
        normalizeToolPairs(messages);
        return removed;
    }

    private int microCompact(ArrayNode messages) {
        int compacted = 0;
        int oldBoundary = Math.max(0, messages.size() - OLD_TOOL_RESULT_DISTANCE);
        for (int index = 0; index < oldBoundary; index++) {
            JsonNode item = messages.get(index);
            if (!"tool".equals(item.path("role").asText())
                    || !(item instanceof ObjectNode object)) {
                continue;
            }
            String content = object.path("content").asText("");
            if (content.startsWith("[L2 microCompact:")
                    || content.startsWith("[L3 toolResultBudget:")) {
                continue;
            }
            object.put(
                    "content",
                    "[L2 microCompact: 旧 tool_result 已压缩，"
                            + content.getBytes(StandardCharsets.UTF_8).length
                            + " bytes]"
            );
            compacted++;
        }
        return compacted;
    }

    private void rebuildWithSummary(
            ArrayNode messages,
            String summary,
            String label
    ) {
        JsonNode firstSystem = messages.isEmpty()
                ? null
                : messages.get(0).deepCopy();
        List<JsonNode> recent = tailExcludingFirstSystem(
                messages,
                L4_RECENT_MESSAGES
        );
        messages.removeAll();
        if (firstSystem != null
                && "system".equals(firstSystem.path("role").asText())) {
            messages.add(firstSystem);
        }
        messages.addObject()
                .put("role", "system")
                .put("content", "[" + label + " 全量摘要]\n" + summary);
        messages.addAll(recent);
        normalizeToolPairs(messages);
    }

    private String buildEmergencySummary(ArrayNode messages) {
        StringBuilder result = new StringBuilder(
                "[reactive summary]\n原消息数："
        ).append(messages.size()).append('\n');
        int limit = Math.min(5, messages.size());
        for (int index = 0; index < limit; index++) {
            JsonNode item = messages.get(index);
            result.append(index + 1)
                    .append(". ")
                    .append(item.path("role").asText("unknown"))
                    .append(": ")
                    .append(truncateUtf8(
                            item.path("content").asText(""),
                            1024
                    ))
                    .append('\n');
        }
        return result.toString().stripTrailing();
    }

    private ObjectNode sanitizeForReactive(JsonNode source) {
        ObjectNode copy = source.deepCopy();
        copy.put(
                "content",
                truncateUtf8(
                        copy.path("content").asText(""),
                        REACTIVE_CONTENT_BYTES
                )
        );
        if ("assistant".equals(copy.path("role").asText())) {
            copy.remove("tool_calls");
        }
        if ("tool".equals(copy.path("role").asText())) {
            String toolCallId = copy.path("tool_call_id").asText("unknown");
            copy.put("role", "system");
            copy.remove("tool_call_id");
            copy.put(
                    "content",
                    "[reactive tool_result " + toolCallId + "]\n"
                            + copy.path("content").asText("")
            );
        }
        return copy;
    }

    private void normalizeToolPairs(ArrayNode messages) {
        for (int index = 0; index < messages.size(); index++) {
            JsonNode item = messages.get(index);
            if (!"assistant".equals(item.path("role").asText())
                    || !item.path("tool_calls").isArray()
                    || !(item instanceof ObjectNode object)) {
                continue;
            }
            boolean complete = true;
            for (JsonNode call : item.path("tool_calls")) {
                if (!hasFollowingToolResult(
                        messages,
                        index,
                        call.path("id").asText()
                )) {
                    complete = false;
                    break;
                }
            }
            if (!complete) {
                object.remove("tool_calls");
                if (object.path("content").asText("").isBlank()) {
                    object.put(
                            "content",
                            "[context compact：不完整的工具调用元数据已移除]"
                    );
                }
            }
        }

        for (int index = 0; index < messages.size(); index++) {
            JsonNode item = messages.get(index);
            if (!"tool".equals(item.path("role").asText())
                    || !(item instanceof ObjectNode object)) {
                continue;
            }
            String callId = object.path("tool_call_id").asText("");
            if (!hasEarlierToolCall(messages, index, callId)) {
                String content = object.path("content").asText("");
                object.put("role", "system");
                object.remove("tool_call_id");
                object.put(
                        "content",
                        "[保留的 tool_result " + callId + "]\n" + content
                );
            }
        }
    }

    private boolean hasFollowingToolResult(
            ArrayNode messages,
            int assistantIndex,
            String callId
    ) {
        for (int index = assistantIndex + 1;
             index < messages.size();
             index++) {
            JsonNode next = messages.get(index);
            if (!"tool".equals(next.path("role").asText())) return false;
            if (callId.equals(next.path("tool_call_id").asText())) return true;
        }
        return false;
    }

    private boolean hasEarlierToolCall(
            ArrayNode messages,
            int beforeIndex,
            String callId
    ) {
        for (int index = beforeIndex - 1; index >= 0; index--) {
            JsonNode previous = messages.get(index);
            if ("tool".equals(previous.path("role").asText())) continue;
            if (!"assistant".equals(previous.path("role").asText())) return false;
            for (JsonNode call : previous.path("tool_calls")) {
                if (callId.equals(call.path("id").asText())) return true;
            }
            return false;
        }
        return false;
    }

    private int toolResultBytes(ArrayNode messages) {
        int total = 0;
        for (JsonNode item : messages) {
            if ("tool".equals(item.path("role").asText())) {
                total += item.path("content").asText("")
                        .getBytes(StandardCharsets.UTF_8).length;
            }
        }
        return total;
    }

    private ObjectNode largestInlineToolResult(ArrayNode messages) {
        ObjectNode largest = null;
        int largestBytes = -1;
        for (JsonNode item : messages) {
            if (!"tool".equals(item.path("role").asText())
                    || !(item instanceof ObjectNode object)) {
                continue;
            }
            String content = object.path("content").asText("");
            if (content.startsWith("[L3 toolResultBudget:")) continue;
            int bytes = content.getBytes(StandardCharsets.UTF_8).length;
            if (bytes > largestBytes) {
                largest = object;
                largestBytes = bytes;
            }
        }
        return largest;
    }

    private Path persist(String runId, String hash, String content)
            throws IOException {
        String safeRunId = runId.replaceAll("[^a-zA-Z0-9._-]", "_");
        Path directory = storageRoot.resolve(safeRunId).normalize();
        if (!directory.startsWith(storageRoot)) {
            throw new SecurityException("非法 context run id");
        }
        Files.createDirectories(directory);
        Path target = directory.resolve(
                System.currentTimeMillis() + "-" + hash.substring(0, 12) + ".txt"
        );
        Path temporary = directory.resolve(
                "." + UUID.randomUUID() + ".tmp"
        );
        try {
            Files.writeString(temporary, content, StandardCharsets.UTF_8);
            Files.move(
                    temporary,
                    target,
                    StandardCopyOption.REPLACE_EXISTING
            );
        } finally {
            Files.deleteIfExists(temporary);
        }
        return target;
    }

    private String displayPath(Path target) {
        return storageRoot.getParent().getParent()
                .relativize(target)
                .toString()
                .replace('\\', '/');
    }

    private List<JsonNode> tail(ArrayNode messages, int count) {
        List<JsonNode> result = new ArrayList<>();
        int start = Math.max(0, messages.size() - count);
        for (int index = start; index < messages.size(); index++) {
            result.add(messages.get(index).deepCopy());
        }
        return result;
    }

    private List<JsonNode> tailExcludingFirstSystem(
            ArrayNode messages,
            int count
    ) {
        List<JsonNode> result = new ArrayList<>();
        int minimum = !messages.isEmpty()
                && "system".equals(messages.get(0).path("role").asText())
                ? 1
                : 0;
        int start = Math.max(minimum, messages.size() - count);
        for (int index = start; index < messages.size(); index++) {
            result.add(messages.get(index).deepCopy());
        }
        return result;
    }

    private String truncateUtf8(String value, int maxBytes) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= maxBytes) return value;
        int end = Math.min(value.length(), maxBytes);
        while (end > 0
                && value.substring(0, end)
                        .getBytes(StandardCharsets.UTF_8).length > maxBytes) {
            end--;
        }
        return value.substring(0, end) + "\n[字节级裁剪]";
    }

    private String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(bytes)
        );
    }

    @FunctionalInterface
    public interface Summarizer {
        String summarize(ArrayNode messages) throws Exception;
    }

    public record CompactReport(
            int offloadedToolResults,
            int removedMessages,
            int microCompactedToolResults,
            boolean autoCompacted,
            boolean reactiveCompacted,
            int tokensBefore,
            int tokensAfter,
            int messagesAfter
    ) {
        public boolean changed() {
            return offloadedToolResults > 0
                    || removedMessages > 0
                    || microCompactedToolResults > 0
                    || autoCompacted
                    || reactiveCompacted;
        }
    }
}
