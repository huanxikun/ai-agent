# My Agent · S11 Error Recovery

S11 在 S10 运行时 Prompt、S08 Context Compact 和 S09 Memory Loading 之后，为业务 LLM 调用加入三条独立恢复路径。错误不再默认终止 Agent，而是先分类，再执行有限、可观察的恢复。

实现参考：`D:\vsCode\learn\learn-claude-code\s11_error_recovery\README.md`。

## 调用位置

```text
Runtime System Prompt
  → L3 → L1 → L2 → 可选 L4
  → 注入相关 Memory
  → try: 调用业务 LLM
      ├─ 成功但 max_tokens
      ├─ prompt_too_long / 413
      ├─ 429 / 529
      └─ 其他错误直接上抛
```

Memory 的选择结果每个用户请求只计算一次。瞬态重试、max_tokens 升级和 reactiveCompact 重试都会重新把同一批 Memory 注入当前请求副本，但不会污染或重复写入标准消息历史。

## 路径一：`max_tokens`

DeepSeek 的 `finish_reason=length` 会被统一映射为 `stopReason=max_tokens`。

恢复顺序：

1. 初始输出预算为 8,000。
2. 第一次截断时，不把短输出写入消息，直接升级到 64,000 并重试同一请求。
3. 64K 仍截断时，保存当前截断正文。
4. 注入续写提示，要求直接从中断位置继续，不道歉、不复述。
5. 最多续写 3 次。
6. 达到上限后停止恢复，返回已安全收集的所有片段。

续写轮次仍会重新运行 Runtime Prompt、Context Compact 和 Memory Loading。恢复轮次可以安全扩展原模型步骤上限，确保最多 3 次续写不会因为正好触及步骤边界而被提前截断。

## 路径二：`prompt_too_long`

只有业务 LLM 实际返回以下错误时触发：

- HTTP 413
- `prompt_too_long`
- `prompt too long`
- context-length 错误

恢复动作：

1. 对标准消息执行一次 `reactiveCompact`。
2. 重新注入相关 Memory。
3. 重试业务 LLM。
4. 如果仍然超长，直接上抛，不进行第二次 reactiveCompact。

L4 摘要调用自身失败不会触发这条路径，保持 S08 已确定的边界。

## 路径三：429 / 529

429 Rate Limit 和 529 Overloaded 使用指数退避：

```text
base = min(500ms × 2^attempt, 32000ms)
delay = base + random(0 .. base × 25%)
```

- 最多重试 10 次。
- 服务端返回数值型 `Retry-After` 时优先使用。
- 429 会清零连续 529 计数。
- 任意成功响应会清零连续 529 计数。
- 其他 4xx、5xx 或本地错误不会误重试。

### 529 备用模型

可选配置：

```dotenv
DEEPSEEK_FALLBACK_MODEL=
```

连续 3 次 529 且配置了备用模型后，后续尝试切换到备用模型。未配置时继续使用主模型完成剩余有限重试，不会自行猜测模型名称。

## Recovery State

每次 Parent Agent 或 Subagent 运行都会创建独立状态：

- 当前 `maxTokens`
- 是否完成 8K→64K 升级
- 已使用的续写次数
- 是否执行过 reactiveCompact
- 连续 529 次数
- 当前主模型或备用模型

不同请求之间不会共享错误次数，也不会让一次限流污染下一次任务。

## Harness 可见性

父 Agent 轨迹新增 `recovery` 事件：

- `max_tokens` 的升级、续写和耗尽
- `prompt_too_long → reactiveCompact`
- 429/529 当前尝试次数和等待毫秒
- 连续 529 后的备用模型切换

Subagent 在终端打印同样的恢复状态。

健康接口：

```json
{
  "stage": "s11-error-recovery",
  "errorRecovery": {
    "enabled": true,
    "maxTokensEscalation": "8000->64000",
    "promptTooLongRetries": 1,
    "transientRetries": 10,
    "fallbackModelConfigured": false
  }
}
```

## 保留能力

- S03：文件变更与三道权限闸门
- S04：完整 Agent Cycle Hooks
- S05：TodoWrite 与三轮提醒
- S06：只读、不可递归 Subagent
- S07：按需加载完整 Skill
- S08：分层上下文压缩与 reactiveCompact
- S09：持久 Memory
- S10：运行时 System Prompt 组装和缓存
- S11：max_tokens、prompt_too_long、429/529 三路径恢复

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
