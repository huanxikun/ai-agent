# My Agent · S12 Task System

S12 在现有 Agent Harness 中加入磁盘持久化的任务图。它用于拆分和追踪跨会话的长期目标，与 S05 的进程内 `todo_write` 同时存在、职责不同。

实现参考：`D:\vsCode\learn\learn-claude-code\s12_task_system\README.md`。

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

S10 的运行时 Prompt 会根据实际注册工具按需加入 `Persistent Task System` 片段，明确：

- Task 与 Todo 的语义边界；
- 长期任务先认领再开始；
- 只有真正完成后才能标记完成；
- 依赖任务全部 `completed` 后才可认领。

只读 Subagent 不注册持久任务工具，保持 S06 的上下文隔离和权限边界。

## 健康接口

`GET /api/health` 会返回：

```json
{
  "stage": "s12-task-system",
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
  }
}
```

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
