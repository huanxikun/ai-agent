# My Agent · S07 Skill Loading

S07 在受限 Subagent 之上加入按需 Skill Loading。每轮新任务都先构建基础 system prompt，只注入可用 Skill 的名称与简介；模型分析任务后，可以调用 `load_skills` 读取相关 Skill 的完整 `SKILL.md`。

## 运行流程

```text
收到任务
  → build system prompt
  → 注入基础规则 + Skill 名称/简介
  → 模型分析任务
  → load_skills（仅在需要时）
  → 完整 SKILL.md 作为 tool result 回填
  → 按 Skill 说明继续执行
```

系统提示在每次父 Agent 或 Subagent 开始运行时重新构建，因此新增或修改 Skill 后无需重启即可在下一次任务中被发现。完整 Skill 内容不会预加载，以免无关知识占用上下文。

## Skill 目录

每个 Skill 位于 `skills/<name>/SKILL.md`：

```markdown
---
name: backend-java
description: 分析 Java Agent 后端、工具注册、Hooks、权限和模型循环
---

# 完整工作说明
...
```

当前示例：

- `backend-java`：Java 后端、Agent Loop、工具、Hooks 与权限研究
- `frontend-ui`：原生前端、对话框、Step 面板与局部滚动检查

Skill 名称只允许小写字母、数字和连字符。单个文件最大 256 KiB，一次最多加载 5 个 Skill；目录穿越、符号链接、未知名称和重复加载请求会被拒绝。

## load_skills 工具

调用：

```json
{
  "skills": ["backend-java"]
}
```

返回：

```json
{
  "status": "loaded",
  "skills": [
    {
      "name": "backend-java",
      "description": "Skill 简介",
      "path": "backend-java/SKILL.md",
      "content": "完整 SKILL.md 内容"
    }
  ]
}
```

`load_skills` 注册在 `ToolHandlers` 中，父 Agent 和 Subagent 均可使用，并统一经过 `PreToolUse` / `PostToolUse` hooks。Subagent 仍然只拥有代码读取工具与 Skill Loading，不能写文件、更新父 Todo 或递归调用 `task`。

## 保留能力

- S03：文件创建、修改、删除与三道权限闸门
- S04：UserPromptScript、PreToolUse、PostToolUse、Stop hooks
- S05：进程内 TodoWrite 与连续三轮 Nag Reminder
- S06：上下文隔离、只读、不可递归的 Subagent
- S07：system prompt 动态构建与按需完整 Skill 加载

## 启动与验证

```powershell
Copy-Item .env.example .env
mvn compile exec:java
```

配置 `DEEPSEEK_API_KEY` 后访问 `http://localhost:3000`。

```powershell
mvn test
node --check public/app.js
```
