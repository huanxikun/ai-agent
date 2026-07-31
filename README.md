# My Agent · S13 Background Tasks

S13 在现有 Agent Harness 中加入后台任务能力，让较慢的只读研究任务可以异步执行，Agent 不必阻塞等待结果。

实现参考：

- `D:\vsCode\learn\learn-claude-code\s12_task_system\README.md`
- `D:\vsCode\learn\learn-claude-code\s13_background_tasks\README.md`

## TodoWrite 与 Task System

| | TodoWrite | Task System |
|---|---|---|
| 用途 | 当前执行过程的步骤清单 | 可恢复的长期目标 |
| 存储 | 当前 Java 进程内 | `.tasks/{id}.json` |
| 依赖 | 无 | `blockedBy` |
| 认领 | 无 | `owner` |
| 生命周期 | 当前会话 | 跨会话 |

`todo_write` 没有被替换。Agent 可用 Todo 跟踪眼前步骤，同时用 Task System 保存长期任务及其依赖关系。

## 五个持久任务工具

- `create_task`：创建 `pending` 任务，可同时提供 `description` 和 `blockedBy`。
- `list_tasks`：列出所有任务及状态、owner 和依赖。
- `get_task`：按 ID 读取完整任务信息。
- `claim_task`：依赖全部完成后认领任务，状态变为 `in_progress`。
- `complete_task`：完成已认领任务，状态变为 `completed`，并报告解锁的下游任务。

所有任务工具都通过现有 `PRE_TOOL_USE` / `POST_TOOL_USE` Hook 管线。S06 的 `task` 工具仍表示“启动只读 Subagent”，与这些持久任务工具没有命名冲突。

## Background Tasks

当前仓库没有教学版 s13 里的 `bash` 工具，因此这里把后台执行能力适配到父 Agent 的 `task` 工具：

- `task` 新增 `run_in_background: boolean`
- 显式传入 `run_in_background=true` 时，会把只读 Subagent 放到后台线程执行
- 若模型未显式指定，系统会对明显较慢的大范围研究任务做启发式兜底
- 工具先返回 `background_started` 占位结果
- 后台任务完成后，会以 `<task_notification>` 注入后续轮次
- 通知保留 Subagent 的完整结果，不再只截取前 200 个字符

这样 Agent 可以在 Subagent 后台读取代码时，继续完成当前轮中的其他同步操作。

后台任务提交失败时会立即移除占位状态，避免健康接口中出现永远
`running` 的幽灵任务。

## 执行步数安全边界

父 Agent 和只读 Subagent 支持更长的研究链路，但默认保持有限上限，避免
模型反复调用工具造成死循环和持续 API 消耗：

```dotenv
AGENT_MAX_STEPS=32
SUBAGENT_MAX_STEPS=24
```

两个值都必须大于 0，可以按项目规模调大。`GET /api/health` 的
`stepLimit` 会返回当前生效值。

## 数据结构与状态

每个 JSON 文件包含：

```json
{
  "id": "task_...",
  "subject": "实现 API",
  "description": "实现用户接口并补充测试",
  "status": "pending",
  "owner": null,
  "blockedBy": ["task_..."]
}
```

状态机：

```text
pending ──claim_task──> in_progress ──complete_task──> completed
```

认领时，`blockedBy` 中缺失或未完成的任务都会阻止认领。任务文件采用临时文件加替换的方式保存，避免进程在写入中途失败时留下半个 JSON 文件。

`.tasks/` 是运行时数据目录，已加入 `.gitignore`，不会随着源码提交。

## Runtime System Prompt

S10/S13 的运行时 Prompt 会根据实际注册工具按需加入 `Persistent Task System` 和 `Background Tasks` 片段，明确：

- Task 与 Todo 的语义边界；
- 长期任务先认领再开始；
- 只有真正完成后才能标记完成；
- 依赖任务全部 `completed` 后才可认领。
- 后台工具可以通过 `run_in_background=true` 显式请求异步运行；
- 后台完成后通过 `task_notification` 回注。

只读 Subagent 不注册持久任务工具，保持 S06 的上下文隔离和权限边界。

## 健康接口

`GET /api/health` 会返回：

```json
{
  "stage": "s13-background-tasks",
  "taskSystem": {
    "enabled": true,
    "persistent": true,
    "directory": ".tasks",
    "summary": {
      "total": 0,
      "pending": 0,
      "inProgress": 0,
      "completed": 0
    }
  },
  "backgroundTasks": {
    "enabled": true,
    "notificationFormat": "task_notification",
    "supportedTools": ["task"],
    "summary": {
      "total": 0,
      "running": 0,
      "completedPendingDelivery": 0,
      "failedPendingDelivery": 0
    }
  }
}
```

## HTTP API

服务启动后（默认 `http://localhost:3000`，见 `.env` 的 `PORT`），暴露以下端点。所有请求体均为 JSON，全部要求 `Content-Type: application/json`。

| 方法 | 路径 | 请求体 | 说明 |
|---|---|---|---|
| `GET` | `/api/health` | 无 | 子系统健康与配置汇总 |
| `POST` | `/api/chat` | `{"message": "..."}` | 单次对话，返回完整执行结果 |
| `POST` | `/api/chat/stream` | `{"message": "..."}` | SSE 流式对话 |
| `POST` | `/api/approvals/{id}` | `{"decision": "approve\|reject"}` | 对挂起的审批决策 |
| `GET` | `/` | 无 | 前端静态资源（`public/`） |

### POST /api/chat

体限制 64 KiB。`message` 非空，否则返回 `400`：

```json
{ "message": "列出项目文件" }
```

成功返回 `200`，响应为 `AgentLoop.RunResult` 的 JSON 序列化（含 `text`、`steps`、`toolCalls`、`todos`、`approvals` 等）：

```json
{
  "text": "Agent 回复文本",
  "steps": 3,
  "toolCalls": 2,
  "todos": [],
  "approvals": []
}
```

### POST /api/chat/stream

体限制 64 KiB，`message` 非空。响应 `Content-Type: text/event-stream; charset=utf-8`，逐条推送 `data:` 事件，每条 SSE 数据是一个 JSON 对象；结束后追加一条 `type:"result"` 的最终事件（含 `text`、`steps`、`toolCalls`、`todos`、`approvals`）：

```text
data: {"type":"result","text":"...","steps":3,"toolCalls":2,"todos":[],"approvals":[]}
```

执行异常时改为推送 `type:"error"` 事件。

### POST /api/approvals/{id}

体限制 4 KiB，`decision` 只能是 `approve` 或 `reject`。`{id}` 为审批流水号，不能为空且不得含 `/`；非法值或非法 `decision` 返回 `400`。成功返回 `200` 及审批结果。

### 错误与状态码

- 非法 HTTP 方法（如对 `/api/chat` 用 `GET`）：`405 Method not allowed`
- 请求解析失败、体超限、字段缺失/非法：`400`，响应 `{"error": "..."}`
- 端口被占用：启动时直接抛错并提示设置其他 `PORT`

## 保留能力

- S03：文件创建、修改、删除与三道权限闸门
- S04：完整 Agent Cycle Hooks
- S05：TodoWrite 与三轮提醒
- S06：只读、不可递归 Subagent
- S07：按需加载完整 Skill
- S08：分层上下文压缩与 reactiveCompact
- S09：持久 Memory
- S10：运行时 System Prompt 组装和缓存
- S11：max_tokens、prompt_too_long、429/529 三路径恢复
- S12：持久 Task System 与依赖解锁
- S13：Background Tasks（只读 Subagent 后台执行与通知注入）

## 启动与验证

```powershell
Copy-Item .env.example .env
mvn compile exec:java
```

配置 `DEEPSEEK_API_KEY` 后访问 `http://localhost:3000`。
如果 `3000` 端口已被占用，可在 `.env` 中设置 `PORT=3001` 等其他端口后再启动。

```powershell
mvn test
node --check public/app.js
```
