# My Agent 功能与样式实现文档

## 一、功能实现方式总览

### 1. 流式输出（Streaming Output）
| 项目 | 说明 |
|------|------|
| **实现方式** | SSE（Server-Sent Events） |
| **后端** | Java HttpServer 设置 `Content-Type: text/event-stream`，逐条写入 `data: {...}\n\n` 格式 |
| **前端** | `fetch()` + `ReadableStream` 读取，逐行解析 `data:` 前缀，JSON.parse 后分发事件 |
| **事件类型** | `text_delta`（文本增量）、`text_clear`（清空重发）、`tool_start`/`tool_end`（工具调用）、`user_question`（交互提问）、`result`（最终结果）、`error`（异常） |
| **关键点** | 后端 `setStreamHandler(Consumer<Map>)` 注入流式回调；前端用 `TextDecoder` + buffer 处理 SSE 分块 |

### 2. 停止对话（Abort / Stop）
| 项目 | 说明 |
|------|------|
| **实现方式** | 前端 `AbortController` + 后端 `volatile boolean` 标志 |
| **前端** | `fetch({ signal: abortController.signal })`，点击停止时 `abort()` 取消请求 |
| **后端** | `AgentLoop.stopRequested` volatile 字段，SSE 写入 IOException 时自动调用 `requestStop()` |
| **UI** | 发送按钮切换为红色停止按钮（`.stop-mode` 类） |

### 3. 动态 URL 返回（Dynamic File Serving）
| 项目 | 说明 |
|------|------|
| **实现方式** | Java HttpServer 静态文件服务 + 动态 URL 拼接 |
| **后端** | `CodeTools.buildFileResult()` 检测文件是否在 `public/` 下且为 `.html`，拼接 `http://localhost:{port}/{file}` |
| **前端** | `marked.js` 解析 Markdown 链接，`externalizeLinks()` 后处理添加 `target="_blank"` |
| **关键点** | 静态文件从磁盘实时读取（非缓存），创建文件后立即可访问 |

### 4. 交互式选项（ask_user / Interactive Options）
| 项目 | 说明 |
|------|------|
| **实现方式** | 自定义工具 → SSE 事件 → DOM 按钮渲染 |
| **后端** | `ToolHandlers.askUser()` 工具定义，参数 `question` + `options[{label, description}]` |
| **AgentLoop** | 检测工具返回 `status: "user_question"`，发送 SSE 事件并暂停循环 |
| **前端** | `renderUserQuestion()` 渲染问题文本 + 选项按钮列表 |
| **交互** | 点击选项 → `btn.disabled = true` → `submitMessage(opt.label)` 发送用户回答 |

### 5. 每步计时（Per-tool Timing）
| 项目 | 说明 |
|------|------|
| **实现方式** | 后端记录时间差 + SSE 事件传递 + 前端 `setInterval` 实时跳动 |
| **后端** | `AgentLoop` 在 `tools.execute()` 前后记录 `System.currentTimeMillis()`，通过 `tool_start`/`tool_end` 事件发送 |
| **前端** | `tool_start` 时启动 200ms 间隔定时器，`tool_end` 时冻结最终耗时 |
| **显示** | 工具卡片内右对齐，执行中 accent 色跳动，完成后灰色冻结 |

### 6. 总耗时显示（Total Duration）
| 项目 | 说明 |
|------|------|
| **实现方式** | 纯前端 `setInterval` 计时 |
| **启动** | `startTotalTimer()` 在消息最上方插入时间标签，每 200ms 更新 |
| **停止** | `stopTotalTimer(agentMessage, durationMs)` 使用后端返回的精确 `durationMs` 冻结最终值 |
| **样式** | 执行中 accent 色，完成后 `.done` 类切换为灰色 |

### 7. 工具调用折叠（Tool Card Collapse）
| 项目 | 说明 |
|------|------|
| **实现方式** | DOM `<details>/<summary>` 原生折叠 + 卡片移动 |
| **活跃态** | 工具卡片直接显示在消息体内（`.tool-card.active`），绿色边框 + accent 色 |
| **折叠** | `foldToolCards()` 创建 `<details>` 容器，将活跃卡片 `appendChild` 移入（保留 DOM 引用和计时器） |
| **摘要** | 显示 `N 个工具调用 · Xs`（数量 + 聚合耗时） |

### 8. Markdown 渲染（Markdown Rendering）
| 项目 | 说明 |
|------|------|
| **实现方式** | 第三方库 `marked.js` v12 |
| **配置** | `marked.setOptions({ breaks: true })`，支持换行符转 `<br>` |
| **流式** | 每次 `text_delta` 累积文本后调用 `marked.parse()`，追加 `<span class="stream-cursor">` 光标 |
| **链接处理** | `externalizeLinks()` 遍历所有 `<a>` 标签，添加 `target="_blank" rel="noopener"` |

### 9. 停止通知（Stop Notice）
| 项目 | 说明 |
|------|------|
| **实现方式** | 前端 DOM 操作 + AbortError 捕获 |
| **触发** | `catch (AbortError)` → `finalizeOnStop()` 清理计时器、折叠卡片、追加停止标签 |
| **样式** | 红色药丸形标签，文字 `已停止` |

---

## 二、样式实现方式

### 整体设计系统

| 技术 | 用途 |
|------|------|
| **CSS Custom Properties（CSS 变量）** | 全局主题色、间距、圆角统一管理 |
| **CSS Grid** | 页面整体布局（topbar + workspace）、workspace 双栏（chat + trace） |
| **CSS Flexbox** | 工具卡片、选项按钮、输入框内部布局 |
| **`@media` 响应式** | 900px 断点折叠为上下布局，620px 断点精简间距 |

### 当前配色方案

```css
:root {
  --bg: #1a1a18;           /* 暖色深灰背景 */
  --panel: #222220;         /* 面板背景 */
  --panel-2: #2a2a27;       /* 二级面板 */
  --line: #353531;          /* 边框色 */
  --text: #ece9e2;          /* 暖白主文字 */
  --muted: #97958d;         /* 次要文字 */
  --muted-2: #75736c;       /* 最弱文字 */
  --accent: #d97757;        /* 珊瑚色强调色 */
  --danger: #e5786e;        /* 红色警告 */
  --success: #7fb582;       /* 绿色完成 */
}
```

### 关键样式技术

| 技术 | 应用场景 |
|------|----------|
| **`<details>/<summary>` + 隐藏默认标记** | 工具折叠、文本折叠，用 `::before { content: "▸" }` 自定义箭头 |
| **CSS `::before` 伪元素** | Agent 头像（珊瑚色圆形）、折叠箭头 |
| **`border-radius: 24px`** | 输入框药丸形状 |
| **`box-shadow` + `backdrop-filter`** | 输入框悬浮阴影 |
| **`@keyframes` 动画** | 流式光标闪烁、消息淡入、脉冲指示器 |
| **`scrollbar-width: thin`** | 细滚动条（6px），与主题色一致 |
| **`transition`** | 所有交互元素 120-200ms 过渡效果 |

---

## 三、如何描述生成类似的前端样式

如果你想用 AI 生成类似本项目的前端样式，可以使用以下描述模板：

### 描述模板

```
我想要一个 [深色/浅色] 主题的 [聊天/对话/工具] 界面，风格参考 [Claude / ChatGPT / 自定义]。

配色方案：
- 背景色：[深灰/纯黑/白色/奶油色]
- 强调色：[珊瑚色 #d97757 / 蓝色 / 绿色 / 紫色]
- 文字色：[暖白 / 冷白 / 深灰]
- 边框：[细线 1px / 无 / 圆角阴影]

布局：
- [左右分栏 / 单栏居中 / 侧边栏+主区域]
- 输入框：[底部固定 / 药丸形圆角 / 方形]
- 发送按钮：[圆形 / 方形 / 图标按钮]

消息样式：
- 用户消息：[右对齐气泡 / 居中 / 全宽]
- AI 消息：[左对齐+头像 / 全宽 / 卡片式]
- 头像：[圆形色块 / 图标 / 首字母]

工具调用：
- 折叠区域：<details>/<summary> 元素
- 箭头：自定义三角形 ▸/▾ 替代浏览器默认样式
- 卡片：小标签，图标+名称+耗时

代码块：
- [深色背景+浅色文字 / 带语言标签 / 带复制按钮]

交互元素：
- 选项按钮：[卡片式 / 列表式]，hover 浮起效果
- 输入框聚焦：边框变色 + 外发光

动画：
- 消息进入：淡入+微上移
- 流式光标：2px 宽色块闪烁
- 按钮点击：微缩放
```

### 具体示例

> 帮我设计一个深色主题聊天界面。背景 #1a1a18，强调色珊瑚色 #d97757。顶部有极简导航栏（52px），左侧主聊天区 + 右侧 trace 面板。用户消息右对齐灰色气泡，AI 消息左对齐带圆形头像。底部输入框药丸形圆角，聚焦时珊瑚色发光。工具调用用 `<details>` 折叠，自定义 ▸ 箭头，卡片内显示图标+名称+耗时。代码块深色背景配圆角。所有交互 200ms 过渡。

---

## 四、技术栈总结

| 层 | 技术 | 用途 |
|----|------|------|
| **后端** | Java 17 + HttpServer | HTTP 服务、SSE 流式端点 |
| **后端** | Jackson JSON | JSON 序列化/反序列化 |
| **后端** | DeepSeek API (SSE) | LLM 流式对话 |
| **前端** | 原生 HTML/JS/CSS | 无框架，零依赖 |
| **前端** | marked.js v12 | Markdown → HTML 渲染 |
| **通信** | SSE (Server-Sent Events) | 后端→前端实时推送 |
| **通信** | fetch + AbortController | 请求发送与取消 |
| **CSS** | Custom Properties + Grid + Flexbox | 主题系统 + 布局 |
