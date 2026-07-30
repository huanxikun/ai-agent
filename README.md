# My Agent · s01

这是一个从 `learn-claude-code` 的 `s01 Agent Loop` 思想重新开始的最小项目：

- 前端：原生 HTML、CSS、JavaScript
- 后端：Java 17
- 模型接口：DeepSeek Chat Completions API
- 工具：只有 `get_current_time`
- 核心机制：模型 → 工具 → 模型循环，由模型决定何时停止

当前没有工具注册表、权限系统、Hooks、Todo、记忆、子 Agent 或 MCP。这些能力会在后续章节逐个加入。

## 项目结构

```text
public/
  index.html
  app.js
  styles.css
src/main/java/com/example/agent/
  AgentApplication.java   HTTP 服务和静态页面
  AgentLoop.java          s01 核心循环和时间工具
  DeepSeekClient.java     DeepSeek API 请求与响应转换
pom.xml
```

## 配置

复制环境变量模板：

```powershell
Copy-Item .env.example .env
```

在 `.env` 中填写：

```dotenv
DEEPSEEK_API_KEY=你的_API_Key
```

API Key 只由 Java 后端读取，不会发送到浏览器。默认模型是
`deepseek-v4-flash`，接口地址是 `https://api.deepseek.com`。

## 启动

需要 Java 17 和 Maven：

```powershell
mvn compile exec:java
```

打开：

```text
http://localhost:3000
```

前端只保留代码问答输入和运行轨迹，不再提供时间、时区等快捷功能。

## s01 核心

核心代码位于 `AgentLoop.run()`：

```text
用户消息
  → 调用模型
  → 如果没有工具调用：返回答案
  → 如果有工具调用：执行工具
  → 把工具结果交回模型
  → 继续循环，由模型返回最终文本时停止
```

下一步只在理解并验证这个循环后，再进入 `s02 Tool Use`。
