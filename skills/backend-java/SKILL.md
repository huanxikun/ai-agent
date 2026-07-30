---
name: backend-java
description: 分析 Java Agent 后端、工具注册、Hooks、权限和模型循环
---

# Java Agent 后端研究

当任务涉及本项目 Java 后端时使用本 skill。

## 工作方式

1. 先用 `list_files` 确认相关包和文件。
2. 使用 `search_code` 定位类型、构造器和工具名称。
3. 使用 `read_file` 阅读真实实现，不根据目录或类名猜测。
4. 沿调用方向检查：应用装配 → Agent 循环 → ToolRegistry → ToolHandler → Hook。
5. 结论中给出文件路径和行号，并区分已验证事实与推断。

## 检查重点

- 工具是否注册在正确的父/子 Agent 工具集中
- 工具执行是否经过 PreToolUse 与 PostToolUse
- system、assistant、tool 消息顺序是否符合模型 API
- 子 Agent 是否保持只读且不能递归调用 `task`
- 文件能力是否继续经过三道权限闸门
