# My Agent · S04 Hooks

S04 将权限校验从文件工具中解耦，加入可注册、可排序、可拒绝的 Agent Cycle Hooks。工具只负责业务操作，权限、输入检查、审计等横切逻辑通过扩展完成。

## Agent Cycle

```text
UserPromptScript
  → Model
  → PreToolUse
  → Tool
  → PostToolUse
  → Model
  → Stop
```

支持四类事件：

- `UserPromptScript`：用户输入进入模型循环前触发
- `PreToolUse`：每次工具执行前触发，可以拒绝执行或向上下文写入已验证数据
- `PostToolUse`：工具成功或失败后都会触发
- `Stop`：Agent 正常完成、失败或达到最大步数时触发

## 注册和触发扩展

`HookRegistry` 按注册顺序执行扩展。任一 Hook 返回 `HookResult.reject(...)` 后，本次事件立即停止。

```java
HookRegistry hooks = new HookRegistry();

hooks.register_hooks(
        HookEvent.PRE_TOOL_USE,
        context -> {
            // 自定义检查、日志、限流或审计
            return HookResult.allow();
        }
);

hooks.trigger_hooks(
        HookEvent.PRE_TOOL_USE,
        HookContext.forTool(
                runId,
                userPrompt,
                toolName,
                arguments,
                step
        )
);
```

项目启动时通过以下方式加载默认扩展：

```java
DefaultAgentHooks.register_hooks(hooks);
PermissionHooks.register_hooks(hooks, permissions);
```

后续添加 Hook 不需要修改 `CodeTools` 或 `AgentLoop` 的权限分支。

## 权限 Hook

`PermissionHooks` 注册到 `PreToolUse`，根据工具名选择操作策略：

- `list_files` → LIST
- `search_code` → SEARCH
- `read_file` → READ
- `create_file` → CREATE
- `edit_file` → EDIT
- `delete_file` → DELETE

它负责工作区边界、敏感文件、文件类型、大小和变更内容检查，并将安全路径写入 `HookContext`。工具只能读取 Hook 提供的路径。

创建、修改和删除仍需要第三道人工审批。批准后会再次触发 `PreToolUse`，因此等待期间的路径和策略变化仍会被拦截。

## 项目结构

```text
src/main/java/com/example/agent/
  AgentLoop.java                         Agent Cycle
  hooks/
    HookEvent.java                       四类生命周期事件
    HookContext.java                     扩展上下文
    HookRegistry.java                    register_hooks / trigger_hooks
    DefaultAgentHooks.java               输入、结果和停止扩展
    PermissionHooks.java                 PreToolUse 权限扩展
  permissions/                           可复用文件权限策略
  tools/                                 文件工具和注册表
public/                                  对话、轨迹和审批界面
src/test/                                Hook 与权限测试
```

## 启动

```powershell
Copy-Item .env.example .env
mvn compile exec:java
```

在 `.env` 中配置 `DEEPSEEK_API_KEY`，然后访问 `http://localhost:3000`。

## 验证

```powershell
mvn test
node --check public/app.js
```
