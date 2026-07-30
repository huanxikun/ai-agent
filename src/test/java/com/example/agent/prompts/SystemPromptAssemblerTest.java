package com.example.agent.prompts;

import com.example.agent.hooks.HookRegistry;
import com.example.agent.memory.MemorySystem;
import com.example.agent.skills.SkillCatalog;
import com.example.agent.tools.ToolDefinition;
import com.example.agent.tools.ToolRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SystemPromptAssemblerTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path projectRoot;

    @Test
    void assemblesSectionsFromActualRegisteredTools() throws Exception {
        ToolRegistry tools = new ToolRegistry(JSON, new HookRegistry());
        tools.register(tool("read_file"));
        SystemPromptAssembler assembler = assembler(
                tools,
                null,
                null,
                SystemPromptAssembler.AgentRole.PARENT
        );

        SystemPromptAssembler.PromptContent first =
                assembler.update_content();
        String initial = assembler.assemble_system_prompt(first);

        assertTrue(initial.contains("## Identity"));
        assertTrue(initial.contains(projectRoot.toAbsolutePath().toString()));
        assertTrue(initial.contains("read_file"));
        assertTrue(initial.contains("## Evidence"));
        assertFalse(initial.contains("## Planning"));
        assertFalse(initial.contains("## Delegation"));
        assertFalse(initial.contains("## Persistent Task System"));
        assertFalse(initial.contains("## File Mutations"));

        tools.register(tool("todo_write"));
        tools.register(tool("task"));
        tools.register(tool("create_task"));
        tools.register(tool("edit_file"));
        String expanded = assembler.get_system_prompt(
                assembler.update_content()
        );

        assertTrue(expanded.contains("## Planning"));
        assertTrue(expanded.contains("## Delegation"));
        assertTrue(expanded.contains("## Persistent Task System"));
        assertTrue(expanded.contains("这与 todo_write 不同"));
        assertTrue(expanded.contains("## File Mutations"));
        assertNotEquals(initial, expanded);
    }

    @Test
    void getSystemPromptCachesDeterministicContentAndInvalidatesOnChange()
            throws Exception {
        ToolRegistry tools = new ToolRegistry(JSON, new HookRegistry());
        tools.register(tool("read_file"));
        SystemPromptAssembler assembler = assembler(
                tools,
                null,
                null,
                SystemPromptAssembler.AgentRole.PARENT
        );
        SystemPromptAssembler.PromptContent content =
                assembler.update_content();

        String first = assembler.get_system_prompt(content);
        String second = assembler.get_system_prompt(
                assembler.update_content()
        );

        assertSame(first, second);
        assertEquals(1, assembler.cacheStats().hits());
        assertEquals(1, assembler.cacheStats().misses());
        assertEquals(1, assembler.cacheStats().entries());

        tools.register(tool("search_code"));
        String changed = assembler.get_system_prompt(
                assembler.update_content()
        );
        assertNotEquals(first, changed);
        assertEquals(2, assembler.cacheStats().misses());
        assertEquals(2, assembler.cacheStats().entries());
    }

    @Test
    void updateContentConditionallyLoadsSkillAndMemoryFromRealFiles()
            throws Exception {
        ToolRegistry tools = new ToolRegistry(JSON, new HookRegistry());
        tools.register(tool("load_skills"));
        Path skillDirectory = projectRoot.resolve("skills/java-review");
        Files.createDirectories(skillDirectory);
        Files.writeString(
                skillDirectory.resolve("SKILL.md"),
                """
                        ---
                        name: java-review
                        description: review Java backend
                        ---
                        full private workflow
                        """
        );
        Path memoryDirectory = projectRoot.resolve(".memory");
        Files.createDirectories(memoryDirectory);
        Files.writeString(
                memoryDirectory.resolve("MEMORY.md"),
                "- [tabs](tabs.md) — use tabs\n"
        );
        SkillCatalog skills = new SkillCatalog(projectRoot);
        MemorySystem memory = new MemorySystem(projectRoot, null, JSON);
        SystemPromptAssembler assembler = assembler(
                tools,
                skills,
                memory,
                SystemPromptAssembler.AgentRole.PARENT
        );

        SystemPromptAssembler.PromptContent content =
                assembler.update_content();
        String prompt = assembler.get_system_prompt(content);

        assertTrue(content.skillSection().contains("java-review"));
        assertFalse(content.skillSection().contains("full private workflow"));
        assertTrue(content.memorySection().contains("use tabs"));
        assertTrue(prompt.contains("## Skill Loading"));
        assertTrue(prompt.contains("## Persistent Memory"));

        Files.writeString(
                memoryDirectory.resolve("MEMORY.md"),
                "- [quotes](quotes.md) — use single quotes\n"
        );
        String changed = assembler.get_system_prompt(
                assembler.update_content()
        );
        assertTrue(changed.contains("use single quotes"));
        assertFalse(changed.contains("use tabs"));
        assertEquals(2, assembler.cacheStats().misses());
    }

    @Test
    void subagentRoleUsesReadOnlyIdentityAndActualToolSet()
            throws Exception {
        ToolRegistry tools = new ToolRegistry(JSON, new HookRegistry());
        tools.register(tool("read_file"));
        SystemPromptAssembler assembler = assembler(
                tools,
                null,
                null,
                SystemPromptAssembler.AgentRole.SUBAGENT
        );

        String prompt = assembler.get_system_prompt(
                assembler.update_content()
        );

        assertTrue(prompt.contains("只读"));
        assertTrue(prompt.contains("不可递归"));
        assertFalse(prompt.contains("## Delegation"));
        assertFalse(prompt.contains("create_file"));
    }

    @Test
    void includesBackgroundSectionOnlyWhenRuntimeToolSupportsIt()
            throws Exception {
        ToolRegistry tools = new ToolRegistry(JSON, new HookRegistry());
        tools.register(backgroundTool("task"));
        SystemPromptAssembler assembler = assembler(
                tools,
                null,
                null,
                SystemPromptAssembler.AgentRole.PARENT
        );

        String prompt = assembler.get_system_prompt(
                assembler.update_content()
        );

        assertTrue(prompt.contains("## Background Tasks"));
        assertTrue(prompt.contains("run_in_background"));
        assertTrue(assembler.loadedSections(
                assembler.update_content()
        ).contains("background_tasks"));
    }

    private SystemPromptAssembler assembler(
            ToolRegistry tools,
            SkillCatalog skills,
            MemorySystem memory,
            SystemPromptAssembler.AgentRole role
    ) {
        return new SystemPromptAssembler(
                projectRoot,
                tools,
                skills,
                memory,
                true,
                role,
                JSON
        );
    }

    private ToolDefinition tool(String name) {
        ObjectNode parameters = JSON.createObjectNode();
        parameters.put("type", "object");
        return new ToolDefinition(
                name,
                name,
                parameters,
                (arguments, context) -> "{}"
        );
    }

    private ToolDefinition backgroundTool(String name) {
        ObjectNode parameters = JSON.createObjectNode();
        parameters.put("type", "object");
        return new ToolDefinition(
                name,
                name,
                parameters,
                (arguments, context) -> "{}",
                true
        );
    }
}
