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
            收到用户需求后，第一步必须调用 todo_write 创建完整的步骤列表，拆解为可执行的子任务。
            每个步骤用简洁的中文描述，状态只能是 pending、in_progress 或 completed，同时最多一个 in_progress。
            开始执行某步骤时标记为 in_progress，完成后立即标记为 completed。
            每次工具调用后都应通过 todo_write 更新当前进度，确保步骤列表反映最新状态。
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

            创建 HTML 页面或 Web 应用（如小游戏、可视化工具）时，文件放在 public/ 目录下。
            用户批准后，工具结果会包含可直接访问的 URL（如 http://localhost:3001/snake.html），
            请将该 URL 以 Markdown 链接格式告知用户：[点击查看](URL)。
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
    private static final String MCP_SECTION = """
            ## MCP Tools
            可先调用 connect_mcp 连接外部 MCP server，再使用动态注入的 mcp__server__tool 工具。
            连接成功后，后续轮次只能调用当前实际注册的 MCP 工具；不要假设未连接 server 的能力。
            """;

    private static final String ASK_USER_SECTION = """
            ## Ask User
            当遇到需要用户决策的问题且有多个选项时，使用 ask_user 工具向用户提问。
            ask_user 会暂停执行，用户可选择一个选项或自行输入回答。
            每次只问一个问题，选项之间互斥且清晰。得到回答后继续推进任务。
            不要用 ask_user 做简单的是非题——那直接在回复中问即可。
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
                tools.mcpServers(),
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
        if (!content.mcpServers().isEmpty()
                || content.enabledTools().contains("connect_mcp")) {
            sections.add(MCP_SECTION + System.lineSeparator()
                    + "已连接 server："
                    + (content.mcpServers().isEmpty()
                    ? "(none)"
                    : String.join(", ", content.mcpServers())));
        }
        if (hasAny(
                content.enabledTools(),
                "create_file",
                "edit_file",
                "delete_file"
        )) {
            sections.add(FILE_MUTATION_SECTION);
        }
        if (content.enabledTools().contains("ask_user")) {
            sections.add(ASK_USER_SECTION);
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
        if (!content.mcpServers().isEmpty()
                || content.enabledTools().contains("connect_mcp")) {
            sections.add("mcp");
        }
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
            List<String> mcpServers,
            String skillSection,
            String memorySection,
            boolean contextCompactEnabled
    ) {
        public PromptContent {
            enabledTools = List.copyOf(enabledTools);
            backgroundTools = List.copyOf(backgroundTools);
            mcpServers = List.copyOf(mcpServers);
            skillSection = skillSection == null ? "" : skillSection;
            memorySection = memorySection == null ? "" : memorySection;
        }
    }

    public record CacheStats(long hits, long misses, int entries) {
    }
}
