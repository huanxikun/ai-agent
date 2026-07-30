package com.example.agent.skills;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Discovers skill summaries for the system prompt and reads full SKILL.md files
 * only when the model explicitly asks for them.
 */
public final class SkillCatalog {
    private static final Pattern VALID_NAME =
            Pattern.compile("[a-z0-9](?:[a-z0-9-]{0,62}[a-z0-9])?");
    private static final Pattern DESCRIPTION =
            Pattern.compile("(?m)^description:\\s*(.+?)\\s*$");
    private static final long MAX_SKILL_BYTES = 256 * 1024;
    private static final int MAX_SKILLS_PER_LOAD = 5;

    private final Path skillsRoot;

    public SkillCatalog(Path projectRoot) {
        this.skillsRoot = projectRoot.toAbsolutePath()
                .normalize()
                .resolve("skills");
    }

    public List<SkillSummary> discover() throws IOException {
        if (!Files.isDirectory(skillsRoot)) return List.of();

        List<SkillSummary> result = new ArrayList<>();
        try (var entries = Files.list(skillsRoot)) {
            for (Path directory : entries.sorted().toList()) {
                String name = directory.getFileName().toString();
                if (!VALID_NAME.matcher(name).matches()
                        || !Files.isDirectory(directory)
                        || Files.isSymbolicLink(directory)) {
                    continue;
                }
                Path skillFile = directory.resolve("SKILL.md");
                if (!isSafeSkillFile(skillFile)) continue;
                String content = readChecked(skillFile);
                result.add(new SkillSummary(
                        name,
                        readDescription(content),
                        skillsRoot.relativize(skillFile).toString()
                                .replace('\\', '/')
                ));
            }
        }
        return List.copyOf(result);
    }

    public List<LoadedSkill> load(List<String> requestedNames)
            throws IOException {
        if (requestedNames == null || requestedNames.isEmpty()) {
            throw new IllegalArgumentException("skills 不能为空");
        }
        if (requestedNames.size() > MAX_SKILLS_PER_LOAD) {
            throw new IllegalArgumentException(
                    "每次最多加载 " + MAX_SKILLS_PER_LOAD + " 个 skill"
            );
        }

        Map<String, SkillSummary> available = new LinkedHashMap<>();
        for (SkillSummary summary : discover()) {
            available.put(summary.name(), summary);
        }

        List<LoadedSkill> loaded = new ArrayList<>();
        for (String rawName : requestedNames) {
            String name = rawName == null ? "" : rawName.trim();
            if (!VALID_NAME.matcher(name).matches()) {
                throw new IllegalArgumentException("无效的 skill 名称：" + name);
            }
            if (loaded.stream().anyMatch(skill -> skill.name().equals(name))) {
                throw new IllegalArgumentException("skill 不能重复：" + name);
            }
            SkillSummary summary = available.get(name);
            if (summary == null) {
                throw new IllegalArgumentException(
                        "未知 skill：" + name + "；可用：" + available.keySet()
                );
            }

            Path skillFile = skillsRoot.resolve(name).resolve("SKILL.md")
                    .normalize();
            if (!isSafeSkillFile(skillFile)) {
                throw new SecurityException("skill 文件不安全：" + name);
            }
            loaded.add(new LoadedSkill(
                    name,
                    summary.description(),
                    summary.path(),
                    readChecked(skillFile)
            ));
        }
        return List.copyOf(loaded);
    }

    public String buildSystemPrompt(String baseInstructions)
            throws IOException {
        List<SkillSummary> available = discover();
        StringBuilder prompt = new StringBuilder(baseInstructions.strip());
        prompt.append("""


                ## Skill Loading
                先根据用户任务进行分析，再决定是否需要专业 skill。
                不要预加载所有 skill；只在确实相关时调用 load_skills。
                load_skills 的 tool result 会包含完整 SKILL.md，加载后必须遵循其中说明。
                可用 skill：
                """);
        if (available.isEmpty()) {
            prompt.append("- 当前没有可用 skill");
        } else {
            for (SkillSummary skill : available) {
                prompt.append("\n- ")
                        .append(skill.name())
                        .append(": ")
                        .append(skill.description());
            }
        }
        return prompt.toString();
    }

    private boolean isSafeSkillFile(Path skillFile) throws IOException {
        Path normalized = skillFile.toAbsolutePath().normalize();
        return normalized.startsWith(skillsRoot)
                && Files.isRegularFile(normalized)
                && !Files.isSymbolicLink(normalized)
                && Files.size(normalized) <= MAX_SKILL_BYTES;
    }

    private String readChecked(Path skillFile) throws IOException {
        long size = Files.size(skillFile);
        if (size > MAX_SKILL_BYTES) {
            throw new SecurityException("SKILL.md 超过 256 KiB 限制");
        }
        String content = Files.readString(skillFile, StandardCharsets.UTF_8);
        if (content.indexOf('\0') >= 0) {
            throw new SecurityException("SKILL.md 必须是文本文件");
        }
        return content;
    }

    private String readDescription(String content) {
        Matcher matcher = DESCRIPTION.matcher(content);
        if (!matcher.find()) return "未提供描述";
        String value = matcher.group(1).trim();
        if ((value.startsWith("\"") && value.endsWith("\""))
                || (value.startsWith("'") && value.endsWith("'"))) {
            value = value.substring(1, value.length() - 1);
        }
        return value;
    }

    public record SkillSummary(String name, String description, String path) {
    }

    public record LoadedSkill(
            String name,
            String description,
            String path,
            String content
    ) {
    }
}
