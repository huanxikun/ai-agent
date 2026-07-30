---
name: frontend-ui
description: 检查原生 HTML、CSS、JavaScript 对话界面与局部滚动布局
---

# 前端界面检查

当任务涉及本项目对话框、Step 面板、滚动或浏览器交互时使用本 skill。

## 工作方式

1. 阅读 `public/index.html` 确认语义结构。
2. 阅读 `public/styles.css` 检查布局、固定区域、overflow 和响应式规则。
3. 阅读 `public/app.js` 检查请求、消息渲染、审批与滚动行为。
4. 保持界面无框架、无外部运行时依赖。
5. 修改后至少执行 JavaScript 语法检查，并检查 Sxx 页面标识一致。

## 布局约束

- 对话记录在聊天区域内部滚动
- Step 轨迹在右侧面板内部滚动
- 输入框保持在聊天区域居中
- 不让任一局部列表撑高整个页面
