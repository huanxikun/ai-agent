# My Agent · S05 TodoWrite

S05 在 S04 Agent Cycle Hooks 上加入进程内 TodoWrite，以及连续三轮未更新 Todo 时自动注入的 Nag Reminder。

## TodoWrite

`ToolHandlers` 注册 `todo_write`，接收完整的 Todo 列表：

```json
{
  "todos": [
    {
      "content": "检查现有工具架构",
      "status": "completed"
    },
    {
      "content": "实现 TodoWrite",
      "status": "in_progress"
    },
    {
      "content": "运行测试",
      "status": "pending"
    }
  ]
}
```

状态只能是：

- `pending`
- `in_progress`
- `completed`

同时最多只能有一个 `in_progress`。每次调用会用新列表替换旧列表；传空数组可以清空。Todo 保存在当前 Java 进程内，服务重启后清空。

每次更新会在终端打印：

```text
[TodoWrite 12:30:00] total=3 pending=1 in_progress=1 completed=1
  [x] 检查现有工具架构
  [>] 实现 TodoWrite
  [ ] 运行测试
```

## Nag Reminder

Agent Loop 按模型调用轮次计数：

```text
模型一轮未调用 todo_write → missed=1
模型一轮未调用 todo_write → missed=2
模型一轮未调用 todo_write → 注入 Nag Reminder，重新计数
```

如果第三轮已经产生最终文本，Agent 不会立即结束，而是把提醒作为新的 system 消息加入上下文，再运行下一轮。调用 `todo_write` 会立即清零连续遗漏次数。

## Agent Cycle

```text
UserPromptScript
  → Model
  → TodoWrite Nag 检查
  → PreToolUse
  → Tool
  → PostToolUse
  → Model
  → Stop
```

S04 的四类 Hook 继续保留：

- `UserPromptScript`
- `PreToolUse`
- `PostToolUse`
- `Stop`

文件创建、修改和删除仍经过路径边界、文件策略和人工审批三道闸门。

## 项目结构

```text
src/main/java/com/example/agent/
  AgentLoop.java                         Agent Cycle 与 Nag 注入
  todos/
    TodoItem.java                        Todo 数据
    TodoStore.java                       进程内状态和终端输出
    TodoNagReminder.java                 三轮遗漏计数
  tools/
    ToolHandlers.java                    todo_write
    CodeTools.java                       文件工具
  hooks/                                 S04 生命周期扩展
  permissions/                           文件权限策略
public/                                  对话、轨迹和审批界面
src/test/                                自动化测试
```

## 启动

```powershell
Copy-Item .env.example .env
mvn compile exec:java
```

配置 `DEEPSEEK_API_KEY` 后访问 `http://localhost:3000`。

## 验证

```powershell
mvn test
node --check public/app.js
```
