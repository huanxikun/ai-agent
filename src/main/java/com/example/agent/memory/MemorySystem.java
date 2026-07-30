package com.example.agent.memory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * S09 persistent memory: file storage, a compact index in SYSTEM, intelligent
 * on-demand loading after compaction, end-of-turn extraction and consolidation.
 */
public final class MemorySystem {
    private static final String INDEX_FILE = "MEMORY.md";
    private static final Set<String> TYPES = Set.of(
            "user",
            "feedback",
            "project",
            "reference"
    );
    private static final Pattern FRONTMATTER =
            Pattern.compile("\\A---\\R(.*?)\\R---\\R?(.*)\\z", Pattern.DOTALL);
    private static final Pattern JSON_ARRAY =
            Pattern.compile("\\[.*]", Pattern.DOTALL);
    private static final Pattern WORD =
            Pattern.compile("[\\p{L}\\p{N}]+");
    private static final int MAX_LOAD_ITEMS = 5;
    private static final int MAX_MEMORY_FILES = 200;
    private static final int MAX_MEMORY_FILE_BYTES = 64 * 1024;
    private static final int MAX_LOADED_FILE_BYTES = 12 * 1024;
    private static final int MAX_SESSION_LOAD_BYTES = 60 * 1024;
    private static final int MAX_INDEX_BYTES = 25 * 1024;
    private static final int MAX_INDEX_LINES = 200;
    private static final int CONSOLIDATE_THRESHOLD = 10;

    private final Path memoryRoot;
    private final Path indexFile;
    private final TextModel model;
    private final ObjectMapper json;

    public MemorySystem(
            Path projectRoot,
            TextModel model,
            ObjectMapper json
    ) {
        this.memoryRoot = projectRoot.toAbsolutePath()
                .normalize()
                .resolve(".memory");
        this.indexFile = memoryRoot.resolve(INDEX_FILE);
        this.model = model;
        this.json = json;
    }

    public synchronized String buildSystemPrompt(String baseInstructions)
            throws IOException {
        String section = promptSection();
        if (section.isBlank()) return baseInstructions.strip();
        return baseInstructions.strip() + "\n\n" + section;
    }

    public synchronized String promptSection() throws IOException {
        String index = readIndex();
        if (index.isBlank()) return "";
        return """
                ## Persistent Memory
                MEMORY.md 索引常驻 system。完整记忆不会预加载；相关记忆会在
                S08 压缩管线完成后按需注入当前模型请求，不参与上下文压缩。
                尊重已加载的用户偏好、反馈、项目事实和引用位置。
                用户明确要求“记住”或表达稳定偏好时，应在最终回答中正确体现。

                可用记忆索引：
                %s
                """.formatted(index);
    }

    public synchronized LoadedMemories loadRelevant(String userMessage)
            throws IOException {
        List<MemoryEntry> entries = listEntries();
        if (entries.isEmpty() || userMessage == null || userMessage.isBlank()) {
            return LoadedMemories.empty();
        }

        List<String> selected;
        try {
            selected = selectWithModel(userMessage, entries);
        } catch (Exception exception) {
            selected = keywordFallback(userMessage, entries);
        }

        List<MemoryEntry> loaded = new ArrayList<>();
        int loadedBytes = 0;
        for (String filename : selected) {
            if (loaded.size() >= MAX_LOAD_ITEMS) break;
            MemoryEntry entry = entries.stream()
                    .filter(item -> item.filename().equals(filename))
                    .findFirst()
                    .orElse(null);
            if (entry == null) continue;
            String content = truncateUtf8(
                    entry.rawContent(),
                    MAX_LOADED_FILE_BYTES
            );
            int bytes = content.getBytes(StandardCharsets.UTF_8).length;
            if (loadedBytes + bytes > MAX_SESSION_LOAD_BYTES) break;
            loaded.add(new MemoryEntry(
                    entry.filename(),
                    entry.name(),
                    entry.description(),
                    entry.type(),
                    content
            ));
            loadedBytes += bytes;
        }
        return new LoadedMemories(List.copyOf(loaded), loadedBytes);
    }

    /**
     * Injects selected memories into a deep-copied request after S08 compaction.
     * The canonical history is never modified, so memory content is not compacted.
     */
    public ArrayNode injectAfterCompaction(
            ArrayNode compactedMessages,
            LoadedMemories loaded
    ) {
        ArrayNode request = compactedMessages.deepCopy();
        if (loaded == null || loaded.entries().isEmpty()) return request;

        String block = formatLoaded(loaded.entries());
        for (int index = request.size() - 1; index >= 0; index--) {
            JsonNode message = request.get(index);
            if ("user".equals(message.path("role").asText())
                    && message instanceof ObjectNode object) {
                object.put(
                        "content",
                        block + "\n\n" + object.path("content").asText("")
                );
                return request;
            }
        }
        int insertionPoint = !request.isEmpty()
                && "system".equals(request.get(0).path("role").asText())
                ? 1
                : 0;
        request.insertObject(insertionPoint)
                .put("role", "system")
                .put("content", block);
        return request;
    }

    public synchronized ExtractionResult extractAndConsolidate(
            ArrayNode uncompressedTranscript
    ) {
        if (model == null || uncompressedTranscript == null
                || uncompressedTranscript.isEmpty()) {
            return new ExtractionResult(0, false, null);
        }
        int extracted;
        try {
            extracted = extract(uncompressedTranscript);
        } catch (Exception exception) {
            return new ExtractionResult(0, false, exception.getMessage());
        }
        try {
            boolean consolidated = extracted > 0
                    && listEntries().size() >= CONSOLIDATE_THRESHOLD
                    && consolidate();
            return new ExtractionResult(extracted, consolidated, null);
        } catch (Exception exception) {
            return new ExtractionResult(
                    extracted,
                    false,
                    exception.getMessage()
            );
        }
    }

    public synchronized int count() throws IOException {
        return listEntries().size();
    }

    private int extract(ArrayNode transcript) throws Exception {
        List<MemoryEntry> existing = listEntries();
        String existingCatalog = existing.isEmpty()
                ? "(none)"
                : existing.stream()
                        .map(item -> "- " + item.name() + ": "
                                + item.description())
                        .reduce((left, right) -> left + "\n" + right)
                        .orElse("(none)");
        String dialogue = formatTranscript(transcript);
        String prompt = """
                Extract only durable information useful across future sessions:
                user preferences, repeated feedback, project facts, or reference locations.
                Return ONLY a JSON array. Each item must be:
                {"name":"kebab-case","type":"user|feedback|project|reference",
                 "description":"one line","body":"complete markdown detail"}.
                Do not save temporary task progress, tool noise, guesses, secrets,
                credentials, or information already covered. Return [] if nothing new.

                Existing memories:
                %s

                Uncompressed dialogue:
                %s
                """.formatted(existingCatalog, dialogue);
        String response = model.complete(
                "You are a conservative persistent-memory extractor.",
                prompt,
                900
        );
        JsonNode items = parseArray(response);
        if (!items.isArray()) return 0;

        Set<String> existingFiles = new HashSet<>();
        for (MemoryEntry entry : existing) existingFiles.add(entry.filename());
        int written = 0;
        for (JsonNode item : items) {
            if (written >= 10 || existingFiles.size() >= MAX_MEMORY_FILES) break;
            String name = item.path("name").asText("").trim();
            String type = item.path("type").asText("").trim();
            String description = item.path("description").asText("").trim();
            String body = item.path("body").asText("").trim();
            String filename = slugify(name) + ".md";
            if (!validMemory(type, description, body)
                    || existingFiles.contains(filename)) {
                continue;
            }
            writeEntry(name, type, description, body);
            existingFiles.add(filename);
            written++;
        }
        if (written > 0) rebuildIndex();
        return written;
    }

    private boolean consolidate() throws Exception {
        List<MemoryEntry> entries = listEntries();
        StringBuilder catalog = new StringBuilder();
        for (MemoryEntry entry : entries) {
            catalog.append("\n\n## ")
                    .append(entry.filename())
                    .append('\n')
                    .append(entry.rawContent());
        }
        String response = model.complete(
                "You consolidate persistent memory without losing durable details.",
                """
                        Deduplicate and reconcile these memories. Preserve exact user
                        preferences above all. Remove only duplicates or clearly stale
                        contradictions. Return ONLY a JSON array using fields
                        name, type, description, body. Keep at most 30 memories.
                        %s
                        """.formatted(truncateUtf8(catalog.toString(), 60 * 1024)),
                3_000
        );
        JsonNode items = parseArray(response);
        if (!items.isArray() || items.isEmpty()) return false;

        List<MemoryDraft> drafts = new ArrayList<>();
        Set<String> filenames = new HashSet<>();
        for (JsonNode item : items) {
            String name = item.path("name").asText("").trim();
            String type = item.path("type").asText("").trim();
            String description = item.path("description").asText("").trim();
            String body = item.path("body").asText("").trim();
            String filename = slugify(name) + ".md";
            if (!validMemory(type, description, body)
                    || !filenames.add(filename)) {
                continue;
            }
            drafts.add(new MemoryDraft(name, type, description, body));
            if (drafts.size() >= 30) break;
        }
        if (drafts.isEmpty()) return false;

        Path staging = memoryRoot.resolve(
                ".consolidate-" + UUID.randomUUID()
        );
        Files.createDirectories(staging);
        try {
            for (MemoryDraft draft : drafts) {
                writeEntryTo(staging, draft);
            }
            writeIndexTo(staging, readEntriesFrom(staging));
            for (MemoryEntry entry : entries) {
                Files.deleteIfExists(memoryRoot.resolve(entry.filename()));
            }
            Files.deleteIfExists(indexFile);
            try (var files = Files.list(staging)) {
                for (Path file : files.toList()) {
                    Files.move(
                            file,
                            memoryRoot.resolve(file.getFileName()),
                            StandardCopyOption.REPLACE_EXISTING
                    );
                }
            }
            return true;
        } finally {
            if (Files.isDirectory(staging)) {
                try (var files = Files.list(staging)) {
                    for (Path file : files.toList()) {
                        Files.deleteIfExists(file);
                    }
                }
                Files.deleteIfExists(staging);
            }
        }
    }

    private List<String> selectWithModel(
            String userMessage,
            List<MemoryEntry> entries
    ) throws Exception {
        if (model == null) return keywordFallback(userMessage, entries);
        StringBuilder catalog = new StringBuilder();
        for (int index = 0; index < entries.size(); index++) {
            MemoryEntry entry = entries.get(index);
            catalog.append(index)
                    .append(": ")
                    .append(entry.name())
                    .append(" — ")
                    .append(entry.description())
                    .append('\n');
        }
        String response = model.complete(
                "You select only clearly relevant persistent memories.",
                """
                        Select at most 5 relevant memory indices for the current user
                        request. Return ONLY a JSON array of integers such as [0, 3].
                        When uncertain, select nothing.

                        User request:
                        %s

                        Memory catalog:
                        %s
                        """.formatted(
                        truncateUtf8(userMessage, 4_000),
                        catalog
                ),
                200
        );
        JsonNode indices = parseArray(response);
        List<String> result = new ArrayList<>();
        Set<String> unique = new HashSet<>();
        for (JsonNode indexNode : indices) {
            int index = indexNode.asInt(-1);
            if (index >= 0 && index < entries.size()) {
                String filename = entries.get(index).filename();
                if (unique.add(filename)) result.add(filename);
            }
            if (result.size() >= MAX_LOAD_ITEMS) break;
        }
        return result;
    }

    private List<String> keywordFallback(
            String userMessage,
            List<MemoryEntry> entries
    ) {
        Set<String> queryWords = words(userMessage);
        List<ScoredMemory> scored = new ArrayList<>();
        for (MemoryEntry entry : entries) {
            Set<String> memoryWords = words(
                    entry.name() + " " + entry.description()
            );
            int score = 0;
            for (String word : queryWords) {
                if (word.length() >= 2 && memoryWords.contains(word)) score++;
            }
            if (score > 0) scored.add(new ScoredMemory(entry.filename(), score));
        }
        return scored.stream()
                .sorted((left, right) -> Integer.compare(
                        right.score(),
                        left.score()
                ))
                .limit(MAX_LOAD_ITEMS)
                .map(ScoredMemory::filename)
                .toList();
    }

    private List<MemoryEntry> listEntries() throws IOException {
        if (!Files.isDirectory(memoryRoot)) return List.of();
        return readEntriesFrom(memoryRoot);
    }

    private List<MemoryEntry> readEntriesFrom(Path directory)
            throws IOException {
        List<MemoryEntry> result = new ArrayList<>();
        try (var files = Files.list(directory)) {
            for (Path file : files.sorted().toList()) {
                if (result.size() >= MAX_MEMORY_FILES) break;
                String filename = file.getFileName().toString();
                if (INDEX_FILE.equals(filename)
                        || !filename.endsWith(".md")
                        || Files.isSymbolicLink(file)
                        || !Files.isRegularFile(file)
                        || Files.size(file) > MAX_MEMORY_FILE_BYTES) {
                    continue;
                }
                String raw = Files.readString(file, StandardCharsets.UTF_8);
                ParsedMemory parsed = parseMemory(raw);
                if (parsed == null) continue;
                result.add(new MemoryEntry(
                        filename,
                        parsed.metadata().getOrDefault(
                                "name",
                                filename.substring(0, filename.length() - 3)
                        ),
                        parsed.metadata().getOrDefault("description", ""),
                        parsed.metadata().getOrDefault("type", "project"),
                        raw
                ));
            }
        }
        return List.copyOf(result);
    }

    private void writeEntry(
            String name,
            String type,
            String description,
            String body
    ) throws IOException {
        Files.createDirectories(memoryRoot);
        writeEntryTo(
                memoryRoot,
                new MemoryDraft(name, type, description, body)
        );
    }

    private void writeEntryTo(Path directory, MemoryDraft draft)
            throws IOException {
        String content = """
                ---
                name: %s
                description: %s
                type: %s
                ---

                %s
                """.formatted(
                safeFrontmatter(draft.name()),
                safeFrontmatter(draft.description()),
                draft.type(),
                draft.body()
        ).stripTrailing() + "\n";
        if (content.getBytes(StandardCharsets.UTF_8).length
                > MAX_MEMORY_FILE_BYTES) {
            throw new IllegalArgumentException("单条 memory 超过 64 KiB");
        }
        Files.writeString(
                directory.resolve(slugify(draft.name()) + ".md"),
                content,
                StandardCharsets.UTF_8
        );
    }

    private void rebuildIndex() throws IOException {
        Files.createDirectories(memoryRoot);
        writeIndexTo(memoryRoot, listEntries());
    }

    private void writeIndexTo(
            Path directory,
            List<MemoryEntry> entries
    ) throws IOException {
        StringBuilder index = new StringBuilder();
        int lines = 0;
        for (MemoryEntry entry : entries) {
            String line = "- [" + entry.name() + "](" + entry.filename()
                    + ") — " + entry.description() + "\n";
            if (lines >= MAX_INDEX_LINES
                    || (index.toString() + line)
                            .getBytes(StandardCharsets.UTF_8).length
                    > MAX_INDEX_BYTES) {
                break;
            }
            index.append(line);
            lines++;
        }
        Files.writeString(
                directory.resolve(INDEX_FILE),
                index.toString(),
                StandardCharsets.UTF_8
        );
    }

    private String readIndex() throws IOException {
        if (!Files.isRegularFile(indexFile)
                || Files.isSymbolicLink(indexFile)) {
            return "";
        }
        return truncateUtf8(
                Files.readString(indexFile, StandardCharsets.UTF_8),
                MAX_INDEX_BYTES
        ).strip();
    }

    private ParsedMemory parseMemory(String raw) {
        Matcher matcher = FRONTMATTER.matcher(raw);
        if (!matcher.matches()) return null;
        Map<String, String> metadata = new LinkedHashMap<>();
        for (String line : matcher.group(1).split("\\R")) {
            int separator = line.indexOf(':');
            if (separator <= 0) continue;
            metadata.put(
                    line.substring(0, separator).trim(),
                    line.substring(separator + 1)
                            .trim()
                            .replaceAll("^[\"']|[\"']$", "")
            );
        }
        return new ParsedMemory(metadata, matcher.group(2).strip());
    }

    private JsonNode parseArray(String response) throws IOException {
        Matcher matcher = JSON_ARRAY.matcher(response);
        if (!matcher.find()) return json.createArrayNode();
        JsonNode parsed = json.readTree(matcher.group());
        return parsed.isArray() ? parsed : json.createArrayNode();
    }

    private String formatLoaded(List<MemoryEntry> entries) {
        StringBuilder result = new StringBuilder(
                "<relevant_persistent_memories>\n"
        );
        for (MemoryEntry entry : entries) {
            result.append("\n## ")
                    .append(entry.name())
                    .append(" (")
                    .append(entry.type())
                    .append(")\n")
                    .append(entry.rawContent())
                    .append('\n');
        }
        return result.append("</relevant_persistent_memories>").toString();
    }

    private String formatTranscript(ArrayNode transcript) {
        StringBuilder result = new StringBuilder();
        List<Integer> indices = new ArrayList<>();
        if (transcript.size() <= 30) {
            for (int index = 0; index < transcript.size(); index++) {
                indices.add(index);
            }
        } else {
            indices.add(0);
            indices.add(1);
            indices.add(2);
            for (int index = transcript.size() - 27;
                 index < transcript.size();
                 index++) {
                indices.add(index);
            }
        }
        for (int index : indices) {
            JsonNode message = transcript.get(index);
            String content = message.path("content").asText("");
            if (content.isBlank()) continue;
            if ("tool".equals(message.path("role").asText())) {
                content = truncateUtf8(content, 1_024);
            }
            result.append(message.path("role").asText("unknown"))
                    .append(": ")
                    .append(content)
                    .append('\n');
            if (result.toString().getBytes(StandardCharsets.UTF_8).length
                    > 16 * 1024) {
                break;
            }
        }
        return truncateUtf8(result.toString(), 16 * 1024);
    }

    private boolean validMemory(
            String type,
            String description,
            String body
    ) {
        return TYPES.contains(type)
                && !description.isBlank()
                && !body.isBlank()
                && description.length() <= 500;
    }

    private String slugify(String value) {
        String slug = value.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9\\p{IsHan}]+", "-")
                .replaceAll("^-+|-+$", "");
        if (slug.isBlank()) slug = "memory-" + UUID.randomUUID();
        return slug.length() <= 80 ? slug : slug.substring(0, 80);
    }

    private String safeFrontmatter(String value) {
        return value.replace('\r', ' ')
                .replace('\n', ' ')
                .replace("\"", "'");
    }

    private Set<String> words(String value) {
        Set<String> result = new HashSet<>();
        Matcher matcher = WORD.matcher(value.toLowerCase(Locale.ROOT));
        while (matcher.find()) result.add(matcher.group());
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
        return value.substring(0, end) + "\n[内容按加载预算截断]";
    }

    @FunctionalInterface
    public interface TextModel {
        String complete(
                String systemPrompt,
                String userPrompt,
                int maxTokens
        ) throws Exception;
    }

    public record MemoryEntry(
            String filename,
            String name,
            String description,
            String type,
            String rawContent
    ) {
    }

    public record LoadedMemories(List<MemoryEntry> entries, int bytes) {
        public static LoadedMemories empty() {
            return new LoadedMemories(List.of(), 0);
        }
    }

    public record ExtractionResult(
            int extracted,
            boolean consolidated,
            String error
    ) {
    }

    private record ParsedMemory(
            Map<String, String> metadata,
            String body
    ) {
    }

    private record MemoryDraft(
            String name,
            String type,
            String description,
            String body
    ) {
    }

    private record ScoredMemory(String filename, int score) {
    }
}
