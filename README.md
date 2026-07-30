# My Agent · S09 Memory

S09 在 S08 Context Compact 之上增加一层不参与压缩的持久 Memory。压缩摘要负责当前任务连续性，Memory 负责跨压缩、跨会话仍不能丢失的用户偏好、反馈、项目事实和引用位置。

实现参考：`D:\vsCode\learn\learn-claude-code\s09_memory\README.md`。

## 存储结构

```text
.memory/
  MEMORY.md
  user-preference-tabs.md
  project-auth-background.md
  reference-pipeline-location.md
```

每条记忆使用 Markdown 和 YAML frontmatter：

```markdown
---
name: user-preference-tabs
description: 用户要求使用 tab 缩进
type: user
---

必须使用 tab 缩进，不能使用空格。
**How to apply:** 编辑代码时始终使用制表符。
```

支持四种类型：

- `user`：稳定用户偏好
- `feedback`：长期工作方式与反馈
- `project`：跨会话仍有用的项目背景
- `reference`：常用入口、文件和外部位置

`.memory/` 已加入 `.gitignore`。它保存在本地项目中并跨服务重启存在，但不会被提交到 Git。

## Index 常驻 System

`MEMORY.md` 每条记忆只保留一行名称、链接和描述：

```markdown
- [user-preference-tabs](user-preference-tabs.md) — 用户要求使用 tab 缩进
```

每次用户请求开始时，Memory 索引会参与 system prompt 构建。完整正文不会预加载，因此索引可以保持轻量并减少无关上下文占用。

索引限制：

- 最多 200 行
- 最大 25 KiB
- 最多扫描 200 个记忆文件

## 压缩之后智能 Loading

加载顺序：

```text
Build System
  → 注入 MEMORY.md 索引
  → 轻量 side-query 根据当前请求选择相关记忆
  → 执行 S08：L3 → L1 → L2 → 可选 L4
  → 将选中的完整记忆注入模型请求副本
  → 调用业务 LLM
```

关键点：

- 每个用户请求只选择一次相关记忆，最多 5 条。
- side-query 只看到当前请求和 `name + description` 目录。
- side-query 失败或返回无效 JSON 时，自动降级到名称与描述关键词匹配。
- 单条加载预算 12 KiB，单请求总预算 60 KiB。
- Memory 内容注入深拷贝后的请求，不修改 Agent 的标准消息历史。
- 因此 Memory 正文不会被 L1/L2/L4 再次裁剪或摘要。
- reactiveCompact 后重试业务 LLM 时，会重新把同一批记忆注入裁剪后的请求副本。

## 每轮结束提取

业务模型返回最终文本、没有继续调用工具时，Memory 提取器从独立的未压缩转录中寻找新信息。

提取器只保存：

- 明确或稳定的用户偏好
- 反复出现的反馈和约束
- 跨会话仍有价值的项目事实
- 文件、系统或外部问题的长期引用位置

不会保存临时进度、工具噪声、猜测、密钥或已存在的重复内容。提取失败不会让正常 Agent 回答失败，Harness 轨迹会记录跳过原因。

未压缩转录与模型上下文分离：S08 可以修改或删除标准消息，而提取器仍能看到原始用户文字和原始工具结果。工具结果进入提取 prompt 前会受到单条预算限制，但本轮原始信息不会先经过 S08 摘要。

## Memory 整理

新增记忆后，如果文件数达到 10 条，会触发一次低频整理：

- 合并重复内容
- 处理明确过时或矛盾的记录
- 优先保留精确用户偏好
- 最多保留 30 条整理结果
- 先在临时目录生成完整结果，再替换正式 Memory 文件

整理结果会重建 `MEMORY.md` 索引。

## 与 S08 的关系

```text
Session context
  ├─ S08 Context Compact
  │    当前目标、近期工具结果、剩余工作
  │
  └─ S09 Persistent Memory
       用户偏好、长期反馈、项目背景、引用位置
       文件完整保存，不参与 Context Compact
```

S08 的 `reactiveCompact` 规则保持不变：只有业务 LLM 实际返回 `prompt_too_long` 或对应 413 时才触发，并且只重试一次。

## Harness 轨迹与健康接口

Harness 新增两类轨迹：

- `Memory · Intelligent Loading`：加载数量、字节数和压缩后注入说明
- `Memory · End-of-turn Extraction`：新增数量和是否执行整理

健康接口包含：

```json
{
  "stage": "s09-memory",
  "memory": {
    "enabled": true,
    "count": 0,
    "intelligentLoading": true,
    "injectedAfterCompaction": true
  }
}
```

## 保留能力

- S03：文件创建、修改、删除与三道权限闸门
- S04：完整 Agent Cycle Hooks
- S05：进程内 TodoWrite 与三轮提醒
- S06：只读、不可递归 Subagent
- S07：按需加载完整 Skill
- S08：分层上下文压缩与业务 LLM 超长应急处理
- S09：文件仓库、索引、智能加载、提取与整理

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
