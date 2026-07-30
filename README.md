# My Agent · S08 Context Compact

S08 为父 Agent 与 Subagent 加入分层上下文压缩。原则是先运行便宜、确定性的本地操作，只有本地压缩后仍超过阈值才使用一次模型摘要；API 明确拒绝过长提示时，再执行完全不调用模型的应急裁剪。

## 每轮调用顺序

每次调用业务模型之前，无条件按以下顺序执行：

```text
L3 toolResultBudget（0 API）
  → L1 snipCompact（0 API）
  → L2 microCompact（0 API）
  → token 阈值检查
      → 未超限：调用业务模型
      → 仍超限：L4 autoCompact（1 API）→ 调用业务模型
```

如果业务 API 返回 HTTP 413、`prompt_too_long`、`prompt too long` 或 context-length 错误：

```text
reactiveCompact（0 API，UTF-8 字节级裁剪）
  → 保留应急摘要和最近 5 条消息
  → 仅重试业务 API 一次
```

## 四层压缩

### L3 toolResultBudget

- 工具结果总量超过 200 KiB 时，优先选择最大的 tool result。
- 完整内容写入 `.agent-context/tool-results/<run-id>/`。
- 原 tool 消息不删除，只把正文替换为包含落盘路径、字节数和 SHA-256 的占位文本。
- 如果仍超过预算，继续选择当前最大的内联结果，直到回到预算内。

`.agent-context/` 已加入 `.gitignore`，不会进入版本库。

### L1 snipCompact

- 消息超过 50 条时裁掉中间部分。
- 严格保留最前 3 条和最后 47 条。
- 裁剪后会处理断开的 tool-call 关联，避免向 API 发送孤立的工具结果。

### L2 microCompact

- 将距离当前超过 10 条的旧 tool result 正文替换为轻量占位符。
- 保留消息本身、`tool_call_id` 和原始字节数，不删除整条消息。
- 已由 L3 落盘的指针不会被覆盖。

### L4 autoCompact

- 使用 UTF-8 请求字节数除以 3 估算 token。
- 默认阈值为 24,000，可通过环境变量调整：

```dotenv
CONTEXT_TOKEN_THRESHOLD=24000
```

- L3、L1、L2 后仍超过阈值才调用一次摘要 API。
- 摘要覆盖压缩后的完整上下文，要求保留目标、事实、路径、工具结果、待办、约束、审批和错误。
- 摘要完成后保留基础 system prompt、全量摘要与最近 5 条消息。

## reactiveCompact

应急压缩不调用 LLM：

- 从原上下文前 5 条构造确定性的应急摘要。
- 保留最近 5 条消息。
- 每条最近消息按 UTF-8 字节裁到 8 KiB。
- 移除可能断裂的 tool-call 元数据，并把孤立工具结果转换为安全的 system 文本。
- 压缩后仅重试 API 一次，避免无限重试。

## Harness 轨迹

父 Agent 每次模型调用前都会产生 `Context Compact` 轨迹，显示：

- L3 落盘工具结果数
- L1 删除消息数
- L2 压缩旧工具结果数
- 是否触发 L4 或 reactiveCompact
- 压缩前后估算 token
- 压缩后的消息数

Subagent 发生实际压缩时也会输出终端记录。

## 保留能力

- S03：文件创建、修改、删除与三道权限闸门
- S04：完整 Agent Cycle Hooks
- S05：进程内 TodoWrite 与三轮 Nag Reminder
- S06：只读、不可递归 Subagent
- S07：按需加载完整 Skill
- S08：L3→L1→L2→L4 上下文压缩与 reactiveCompact

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
