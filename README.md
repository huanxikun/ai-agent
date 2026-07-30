# My Agent · S06 Subagent

S06 在 TodoWrite 计划能力之上加入受限 Subagent。当父 Agent 判断代码研究任务过大且可以独立拆分时，可以调用 `task` 启动一个上下文隔离的子 Agent。

## task 工具

父 Agent 调用：

```json
{
  "description": "分析后端工具架构",
  "task": "读取后端代码，说明 ToolRegistry、HookRegistry 和 CodeTools 的调用关系，附文件路径和行号。"
}
```

Subagent 完成后返回：

```json
{
  "status": "completed",
  "description": "分析后端工具架构",
  "result": "研究结论",
  "steps": 3,
  "toolCalls": 2
}
```

## Subagent 限制

- 独立的 system/user 消息，不继承父 Agent 对话历史
- 最多执行 6 个模型步骤
- 只注册：
  - `list_files`
  - `search_code`
  - `read_file`
- 不注册 `task`，因此无法递归创建 Subagent
- 不注册 `todo_write`
- 不注册创建、修改或删除文件的工具
- 不继承父 Agent 的待审批操作

上下文隔离和只读工具集避免了额外的写入安全分支；但子 Agent 的每次工具调用仍通过父进程中的同一个 `HookRegistry`，因此 `PreToolUse` 路径边界检查、`PostToolUse` 和 `Stop` 生命周期依然生效。

## 允许读取的代码

Subagent 可以使用只读工具检查项目结构，以及读取项目根目录内的后端 Java 代码。`.git`、`.env`、构建目录、二进制文件和项目目录之外的路径仍会被权限 Hook 拒绝。

## Agent Cycle

```text
Parent Agent
  → todo_write
  → task
      → isolated Subagent context
      → list_files / search_code / read_file
      → PreToolUse / PostToolUse Hooks
      → result
  → Parent Agent synthesis
  → Stop
```

S03 文件权限、S04 Hooks、S05 TodoWrite 和三轮 Nag Reminder 均继续保留。

## 前端布局

- 输入框恢复为聊天区居中布局
- 聊天记录仍使用独立局部滚动
- 聊天滚动条保持在聊天区最右侧，靠近 Step 面板
- Step 面板继续独立滚动

## 项目结构

```text
src/main/java/com/example/agent/
  AgentLoop.java                         父 Agent
  subagents/
    Subagent.java                        隔离的只读子循环
    SubagentExecutor.java                task 执行接口
  tools/
    ToolHandlers.java                    todo_write 与 task
    CodeTools.java                       完整/只读工具注册
  hooks/                                 生命周期与权限 Hook
  permissions/                           文件权限策略
  todos/                                 S05 进程内 Todo
public/                                  对话、轨迹和审批界面
src/test/                                自动化测试
```

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
