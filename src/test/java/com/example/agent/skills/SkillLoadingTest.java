package com.example.agent.skills;

import com.example.agent.hooks.HookContext;
import com.example.agent.hooks.HookEvent;
import com.example.agent.hooks.HookRegistry;
import com.example.agent.todos.TodoStore;
import com.example.agent.tools.ToolHandlers;
import com.example.agent.tools.ToolRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkillLoadingTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path projectRoot;

    @Test
    void systemPromptOnlyListsSummaryAndRefreshesEveryBuild() throws Exception {
        SkillCatalog catalog = new SkillCatalog(projectRoot);
        String emptyPrompt = catalog.buildSystemPrompt("base");
        assertTrue(emptyPrompt.contains("当前没有可用 skill"));

        writeSkill(
                "java-review",
                "审查 Java 后端",
                "# Secret details\n完整且只在运行时加载的规则"
        );
        String prompt = catalog.buildSystemPrompt("base");

        assertTrue(prompt.startsWith("base"));
        assertTrue(prompt.contains("java-review: 审查 Java 后端"));
        assertFalse(prompt.contains("Secret details"));
        assertFalse(prompt.contains("完整且只在运行时加载的规则"));
    }

    @Test
    void loadSkillsReturnsCompleteSkillMarkdownThroughHookedTool()
            throws Exception {
        String markdown = writeSkill(
                "java-review",
                "审查 Java 后端",
                "# Workflow\n1. read\n2. verify\n末尾规则必须保留"
        );
        SkillCatalog catalog = new SkillCatalog(projectRoot);
        HookRegistry hooks = new HookRegistry();
        AtomicInteger preCalls = new AtomicInteger();
        AtomicInteger postCalls = new AtomicInteger();
        hooks.register_hooks(HookEvent.PRE_TOOL_USE, context -> {
            preCalls.incrementAndGet();
            return com.example.agent.hooks.HookResult.allow();
        });
        hooks.register_hooks(HookEvent.POST_TOOL_USE, context -> {
            postCalls.incrementAndGet();
            return com.example.agent.hooks.HookResult.allow();
        });

        ToolRegistry tools = new ToolRegistry(JSON, hooks);
        new ToolHandlers(new TodoStore(), null, catalog, JSON)
                .registerInto(tools);
        assertTrue(tools.hasTool("load_skills"));

        ObjectNode arguments = JSON.createObjectNode();
        arguments.putArray("skills").add("java-review");
        JsonNode output = JSON.readTree(tools.execute(
                "load_skills",
                arguments,
                HookContext.forTool(
                        "run-1",
                        "review backend",
                        "load_skills",
                        arguments,
                        1
                )
        ));

        assertEquals("loaded", output.path("status").asText());
        assertEquals(
                markdown,
                output.path("skills").path(0).path("content").asText()
        );
        assertTrue(
                output.path("skills").path(0).path("content")
                        .asText()
                        .endsWith("末尾规则必须保留")
        );
        assertEquals(1, preCalls.get());
        assertEquals(1, postCalls.get());
    }

    @Test
    void rejectsTraversalUnknownAndDuplicateSkills() throws Exception {
        SkillCatalog catalog = new SkillCatalog(projectRoot);
        writeSkill("safe-skill", "safe", "# Safe");

        assertThrows(
                IllegalArgumentException.class,
                () -> catalog.load(java.util.List.of("../.env"))
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> catalog.load(java.util.List.of("missing"))
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> catalog.load(java.util.List.of("safe-skill", "safe-skill"))
        );
    }

    private String writeSkill(
            String name,
            String description,
            String body
    ) throws Exception {
        Path directory = projectRoot.resolve("skills").resolve(name);
        Files.createDirectories(directory);
        String markdown = """
                ---
                name: %s
                description: %s
                ---

                %s
                """.formatted(name, description, body).stripTrailing();
        Files.writeString(directory.resolve("SKILL.md"), markdown);
        return markdown;
    }
}
