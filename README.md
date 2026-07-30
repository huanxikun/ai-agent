# My Agent · S10 System Prompt

S10 将父 Agent 和 Subagent 的整块硬编码 system prompt 替换为运行时组装：独立 Section 根据真实状态按需拼接，相同状态命中确定性缓存，工具、Skill 或 Memory 发生变化时自动生成新的 prompt。

实现参考：`D:\vsCode\learn\learn-claude-code\s10_system_prompt\README.md`。

## 三个核心方法

实现位于 `SystemPromptAssembler`：

### `update_content`

从真实运行状态生成不可变的 `PromptContent`：

- 当前 Agent 角色：Parent 或 Subagent
- 规范化工作目录
- `ToolRegistry` 中实际注册的工具名称
- 当前可发现的 Skill 名称和描述
- 当前 `.memory/MEMORY.md` 索引
- Context Compact 是否启用

它不会扫描用户消息关键词来猜测能力。例如只有真正注册了 `task`，才会加入委派 Section；只有 Memory 索引真实存在且非空，才会加入 Memory Section。

### `assemble_system_prompt`

按照稳定顺序选择并拼接 Section：

```text
identity
workspace
available_tools
evidence（存在代码读取工具时）
planning（存在 todo_write 时）
delegation（存在 task 时）
file_mutations（存在文件变更工具时）
skills（存在 load_skills 且有 Skill 时）
memory（MEMORY.md 非空时）
context_compact（压缩启用时）
```

Section 使用空行分隔，彼此独立维护。工具列表来自实际注册表，不在 prompt 中维护第二份容易过期的静态清单。

Parent 与 Subagent 共用组装机制，但 identity 和真实工具集不同：

- Parent 可以按注册状态获得 Todo、委派和文件审批说明。
- Subagent 只会看到其只读工具、Skill Loading 和不可递归身份。

### `get_system_prompt`

使用 `PromptContent` 的确定性 JSON 序列化作为缓存键：

- Context 相同：直接返回缓存字符串。
- 工具注册变化：新键，重新组装。
- Skill 名称或描述变化：新键，重新组装。
- `MEMORY.md` 内容变化：新键，重新组装。
- 最多保留 64 个最近使用的 prompt。

缓存只避免进程内重复拼接，不冒充模型服务的 API prompt cache。Section 顺序保持稳定，也为后续 API 级缓存边界保留条件。

## Agent Loop

每个新用户请求先运行一次：

```text
update_content
  → get_system_prompt
  → 创建首条 system 消息
```

每个工具轮次进入下一次业务模型调用前会再次运行：

```text
update_content
  → get_system_prompt
      → 状态不变：cache hit
      → 状态变化：assemble_system_prompt
  → 更新首条 system 消息
  → S08 Context Compact
  → S09 Memory 压缩后注入
  → 业务 LLM
```

因此同一轮中的多数模型调用只做轻量状态读取并命中缓存；如果工具、Skill 或 Memory 索引真的变化，下一次调用立即获得新 prompt。

## 分段内容

### 始终加载

- `identity`：角色、证据原则、回答边界
- `workspace`：实际项目根目录
- `available_tools`：实际注册工具

### 按真实状态加载

- `evidence`
- `planning`
- `delegation`
- `file_mutations`
- `skills`
- `memory`
- `context_compact`

没有 Memory 时，不会加入“当前没有 Memory”的空 Section；没有 Skill 或 `load_skills` 未注册时，也不会加入 Skill Section。

## Harness 可见性

运行轨迹包含：

- 初次 `Build System · Runtime Assembly`
- 每个模型步骤的 `System Prompt · Step N`
- 当前加载的 Section 列表
- 缓存 hit、miss 和 entry 数量

健康接口：

```json
{
  "stage": "s10-system-prompt",
  "systemPrompt": {
    "runtimeAssembly": true,
    "conditionalSections": true,
    "cache": {
      "hits": 0,
      "misses": 0,
      "entries": 0
    }
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
- S09：持久 Memory 的索引、智能加载、提取和整理
- S10：System Prompt 分段、按需拼接和确定性缓存

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
