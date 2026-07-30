package com.example.agent.prompts;

import com.example.agent.memory.MemorySystem;
import com.example.agent.skills.SkillCatalog;
import com.example.agent.tools.ToolRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * S10 runtime system prompt: deterministic sections, conditional assembly and
 * a process-local cache keyed by real runtime content.
 */
public final class SystemPromptAssembler {
    private static final int MAX_CACHE_ENTRIES = 64;

    private static final String PARENT_IDENTITY = """
            ## Identity
            你是项目代码 Agent。以真实代码和工具结果为依据推进任务。
            不要根据猜测描述项目，不要声称未成功执行的修改已经完成。
            已有足够证据时，直接给出清晰的中文回答，并尽量附路径和行号。
            """;
    private static final String SUBAGENT_IDENTITY = """
            ## Identity
            你是上下文隔离、只读、不可递归派生的代码研究 Subagent。
            完成父 Agent 委派的单一任务，返回有证据的简洁结论。
            证据不足时明确说明，不得假装拥有未注册的能力。
            """;
    private static final String TODO_SECTION = """
            ## Planning
            多步骤任务先调用 todo_write 建立完整列表，并持续更新状态。
            状态只能是 pending、in_progress 或 completed，同时最多一个 in_progress。
            """;
    private static final String TASK_SECTION = """
            ## Delegation
            代码研究任务过大且可独立拆分时，可用 task 委派只读 Subagent。
            task 必须单一、具体；不要委派简单问题或文件修改。
            """;
    private static final String PERSISTENT_TASK_SECTION = """
            ## Persistent Task System
            长期目标使用 create_task、list_tasks、get_task、claim_task、complete_task 管理。
            任务跨会话保存在 .tasks/{id}.json；blockedBy 的任务全部 completed 后才能认领。
            这与 todo_write 不同：Todo 是当前进程的执行步骤，Task 是可恢复、可认领的持久目标。
            开始任务前先 claim_task，实际完成后才调用 complete_task。
            """;
    private static final String BACKGROUND_TASK_SECTION = """
            ## Background Tasks
            支持后台执行的工具可通过 run_in_background=true 显式请求异步运行。
            若模型未显式指定，Harness 也可能对明显较慢的任务启用后台执行。
            后台工具会先返回 background_started 占位结果，完成后再通过 task_notification 注入结果。
            """;
    private static final String FILE_MUTATION_SECTION = """
            ## File Mutations
            用户明确要求时才使用 create_file、edit_file 或 delete_file。
            这些工具只创建人工审批请求，不会立即更改磁盘。
            返回 approval_required 后不要重复调用，提示用户批准或拒绝。
            """;
    private static final String EVIDENCE_SECTION = """
            ## Evidence
            回答代码问题前使用可用的 list_files、search_code、read_file 检查真实实现。
            工具名称与参数以实际注册的工具定义为准。
            """;
    private static final String COMPACTION_SECTION = """
            ## Context Compact
            Harness 会在每次业务模型调用前运行 L3→L1→L2，并在超阈值时运行 L4。
            只有业务 LLM 返回 prompt_too_long/413 才触发 reactiveCompact。
            """;

    private final Path workspace;
    private final ToolRegistry tools;
    private final SkillCatalog skills;
    private final MemorySystem memory;
    private final boolean contextCompactEnabled;
    private final AgentRole role;
    private final ObjectMapper json;
    private final Map<String, String> cache =
            new LinkedHashMap<>(16, 0.75f, true);
    private long cacheHits;
    private long cacheMisses;

    public SystemPromptAssembler(
            Path workspace,
            ToolRegistry tools,
            SkillCatalog skills,
            MemorySystem memory,
            boolean contextCompactEnabled,
            AgentRole role,
            ObjectMapper json
    ) {
        this.workspace = workspace.toAbsolutePath().normalize();
        this.tools = tools;
        this.skills = skills;
        this.memory = memory;
        this.contextCompactEnabled = contextCompactEnabled;
        this.role = role;
        this.json = json;
    }

    /**
     * Derives content from actual runtime state. This intentionally does not
     * inspect conversation keywords.
     */
    public PromptContent update_content() throws Exception {
        List<String> enabledTools = tools.toolNames();
        String skillSection = skills == null || !tools.hasTool("load_skills")
                ? ""
                : skills.promptSection();
        String memorySection = memory == null
                ? ""
                : memory.promptSection();
        return new PromptContent(
                role,
                workspace.toString(),
                enabledTools,
                tools.backgroundToolNames(),
                skillSection,
                memorySection,
                contextCompactEnabled
        );
    }

    public String assemble_system_prompt(PromptContent content) {
        List<String> sections = new ArrayList<>();
        sections.add(content.role() == AgentRole.SUBAGENT
                ? SUBAGENT_IDENTITY
                : PARENT_IDENTITY);
        sections.add("""
                ## Workspace
                工作目录：%s
                """.formatted(content.workspace()));

        if (!content.enabledTools().isEmpty()) {
            sections.add("""
                    ## Available Tools
                    当前实际注册：%s
                    只能调用此列表中的工具。
                    """.formatted(String.join(", ", content.enabledTools())));
        }
        if (hasAny(
                content.enabledTools(),
                "list_files",
                "search_code",
                "read_file"
        )) {
            sections.add(EVIDENCE_SECTION);
        }
        if (content.enabledTools().contains("todo_write")) {
            sections.add(TODO_SECTION);
        }
        if (content.enabledTools().contains("task")) {
            sections.add(TASK_SECTION);
        }
        if (hasAny(
                content.enabledTools(),
                "create_task",
                "list_tasks",
                "get_task",
                "claim_task",
                "complete_task"
        )) {
            sections.add(PERSISTENT_TASK_SECTION);
        }
        if (!content.backgroundTools().isEmpty()) {
            sections.add(BACKGROUND_TASK_SECTION);
        }
        if (hasAny(
                content.enabledTools(),
                "create_file",
                "edit_file",
                "delete_file"
        )) {
            sections.add(FILE_MUTATION_SECTION);
        }
        if (!content.skillSection().isBlank()) {
            sections.add(content.skillSection());
        }
        if (!content.memorySection().isBlank()) {
            sections.add(content.memorySection());
        }
        if (content.contextCompactEnabled()) {
            sections.add(COMPACTION_SECTION);
        }
        return sections.stream()
                .map(String::strip)
                .reduce((left, right) -> left + "\n\n" + right)
                .orElse("");
    }

    public synchronized String get_system_prompt(PromptContent content)
            throws Exception {
        String key = json.writeValueAsString(content);
        String cached = cache.get(key);
        if (cached != null) {
            cacheHits++;
            return cached;
        }
        String prompt = assemble_system_prompt(content);
        cache.put(key, prompt);
        cacheMisses++;
        if (cache.size() > MAX_CACHE_ENTRIES) {
            String oldest = cache.keySet().iterator().next();
            cache.remove(oldest);
        }
        return prompt;
    }

    public synchronized CacheStats cacheStats() {
        return new CacheStats(cacheHits, cacheMisses, cache.size());
    }

    public List<String> loadedSections(PromptContent content) {
        List<String> sections = new ArrayList<>(
                List.of("identity", "workspace", "tools")
        );
        if (hasAny(
                content.enabledTools(),
                "list_files",
                "search_code",
                "read_file"
        )) sections.add("evidence");
        if (content.enabledTools().contains("todo_write")) {
            sections.add("planning");
        }
        if (content.enabledTools().contains("task")) {
            sections.add("delegation");
        }
        if (hasAny(
                content.enabledTools(),
                "create_task",
                "list_tasks",
                "get_task",
                "claim_task",
                "complete_task"
        )) sections.add("persistent_tasks");
        if (!content.backgroundTools().isEmpty()) sections.add("background_tasks");
        if (hasAny(
                content.enabledTools(),
                "create_file",
                "edit_file",
                "delete_file"
        )) sections.add("file_mutations");
        if (!content.skillSection().isBlank()) sections.add("skills");
        if (!content.memorySection().isBlank()) sections.add("memory");
        if (content.contextCompactEnabled()) sections.add("context_compact");
        return List.copyOf(sections);
    }

    private boolean hasAny(List<String> values, String... candidates) {
        for (String candidate : candidates) {
            if (values.contains(candidate)) return true;
        }
        return false;
    }

    public enum AgentRole {
        PARENT,
        SUBAGENT
    }

    public record PromptContent(
            AgentRole role,
            String workspace,
            List<String> enabledTools,
            List<String> backgroundTools,
            String skillSection,
            String memorySection,
            boolean contextCompactEnabled
    ) {
        public PromptContent {
            enabledTools = List.copyOf(enabledTools);
            backgroundTools = List.copyOf(backgroundTools);
            skillSection = skillSection == null ? "" : skillSection;
            memorySection = memorySection == null ? "" : memorySection;
        }
    }

    public record CacheStats(long hits, long misses, int entries) {
    }
}
