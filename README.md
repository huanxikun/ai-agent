# My Agent · S03 File Actions

一个使用 Java 17 实现的全栈代码 Agent。模型可以读取、搜索项目代码，也可以申请创建、修改或删除文件；任何变更都必须经过三道权限闸门。

## 三道闸门

1. **工作区边界**：路径规范化并解析真实路径，拒绝 `../` 越界和指向项目外部的符号链接。
2. **文件策略**：保护 `.git`、`.idea`、`target`、`node_modules`、`.env`、密钥、二进制文件和超过 1 MiB 的文件。
3. **人工批准**：`create_file`、`edit_file` 和 `delete_file` 只创建一次性审批请求。用户在前端批准后才执行，令牌 10 分钟过期且只能使用一次。

批准操作时会重新通过前两道闸门。文件在等待审批期间发生变化时，操作会自动取消，避免覆盖其他修改。

## 工具

- `list_files`：列出项目文件
- `search_code`：搜索代码与关键词
- `read_file`：读取最多 400 行文本
- `create_file`：创建新的文本文件，父目录必须存在且绝不覆盖已有文件
- `edit_file`：用精确匹配的 `oldText` 替换为 `newText`
- `delete_file`：删除一个现有文本文件

创建、修改和删除不会由模型直接执行。工具返回 `approval_required` 后，前端会显示预览以及“批准/拒绝”按钮。

## 项目结构

```text
public/                                  聊天和审批界面
src/main/java/com/example/agent/
  AgentApplication.java                 HTTP API
  AgentLoop.java                        模型与工具循环
  DeepSeekClient.java                   DeepSeek API 适配器
  tools/                                工具注册与文件工具
  permissions/
    PathBoundaryGate.java               第一道闸门
    FilePolicyGate.java                 第二道闸门
    HumanApprovalGate.java              第三道闸门
src/test/java/                           权限与审批测试
```

## 配置与启动

```powershell
Copy-Item .env.example .env
```

在 `.env` 中填写 `DEEPSEEK_API_KEY`。`PROJECT_ROOT` 决定 Agent 可以访问的项目根目录，默认为当前目录。

```powershell
mvn compile exec:java
```

打开 `http://localhost:3000`。

## API

- `GET /api/health`：服务和模型状态
- `POST /api/chat`：运行 Agent
- `POST /api/approvals/:id`：提交 `{"decision":"approve"}` 或 `{"decision":"reject"}`

## 验证

```powershell
mvn test
node --check public/app.js
```
