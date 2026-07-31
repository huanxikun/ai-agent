const elements = {
  form: document.querySelector("#chatForm"),
  input: document.querySelector("#messageInput"),
  send: document.querySelector("#sendButton"),
  messages: document.querySelector("#messages"),
  intro: document.querySelector("#intro"),
  provider: document.querySelector("#providerStatus"),
  traceEmpty: document.querySelector("#traceEmpty"),
  traceList: document.querySelector("#traceList"),
  traceSummary: document.querySelector("#traceSummary"),
  stepCount: document.querySelector("#stepCount"),
  toolCount: document.querySelector("#toolCount"),
  reset: document.querySelector("#resetButton"),
  todoPanel: document.querySelector("#todoPanel"),
  todoList: document.querySelector("#todoList"),
  todoProgress: document.querySelector("#todoProgress")
};

let busy = false;
let abortController = null;

if (window.marked) {
  marked.setOptions({ breaks: true });
}

function externalizeLinks(element) {
  element.querySelectorAll("a").forEach(a => {
    a.target = "_blank";
    a.rel = "noopener noreferrer";
  });
}

function renderMarkdown(target, text) {
  if (window.marked) {
    target.innerHTML = window.marked.parse(text);
    externalizeLinks(target);
  } else {
    target.textContent = text;
  }
}

function formatDuration(ms) {
  if (ms < 1000) return Math.round(ms) + "ms";
  return (ms / 1000).toFixed(1) + "s";
}

initialize();

async function initialize() {
  autoResize();
  elements.form.addEventListener("submit", (event) => {
    event.preventDefault();
    if (busy) {
      stopAgent();
    } else {
      submitMessage(elements.input.value);
    }
  });
  elements.input.addEventListener("input", autoResize);
  elements.input.addEventListener("keydown", (event) => {
    if (event.key === "Enter" && !event.shiftKey) {
      event.preventDefault();
      if (!busy) submitMessage(elements.input.value);
    }
  });
  elements.reset.addEventListener("click", resetPage);

  try {
    const response = await fetch("/api/health");
    const data = await response.json();
    const stepLimit = data.stepLimit?.agent ?? "unknown";
    elements.provider.textContent = data.configured
      ? `${data.model} · S13 Background Tasks · steps ${stepLimit}`
      : `API Key 未配置 · S13 Background Tasks · steps ${stepLimit}`;
  } catch {
    elements.provider.textContent = "Java 服务未连接";
  }
}

async function submitMessage(rawMessage) {
  const message = rawMessage?.trim();
  if (!message || busy) return;

  busy = true;
  abortController = new AbortController();
  setStopMode(true);
  elements.input.value = "";
  autoResize();
  elements.intro.classList.add("hidden");
  addMessage("user", message);
  clearTrace();
  const agentMessage = addMessage("agent", "思考中…");
  startTotalTimer(agentMessage);

  try {
    const response = await fetch("/api/chat/stream", {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ message }),
      signal: abortController.signal
    });
    if (!response.ok) {
      const data = await response.json();
      throw new Error(data.error || "请求失败");
    }

    const reader = response.body.getReader();
    const decoder = new TextDecoder();
    let buffer = "";

    while (true) {
      const { done, value } = await reader.read();
      if (done) break;

      buffer += decoder.decode(value, { stream: true });
      const blocks = buffer.split("\n\n");
      buffer = blocks.pop();

      for (const block of blocks) {
        const line = block.trim();
        if (!line.startsWith("data: ")) continue;
        const data = JSON.parse(line.slice(6));
        handleStreamEvent(data, agentMessage);
      }
    }
  } catch (error) {
    if (error.name === "AbortError") {
      finalizeOnStop(agentMessage);
    } else {
      agentMessage.classList.add("error");
      setMessageText(agentMessage, error.message);
      addTrace("error", "运行失败", error.message);
    }
  } finally {
    stopTotalTimer(agentMessage);
    busy = false;
    abortController = null;
    setStopMode(false);
    elements.input.focus();
  }
}

function startTotalTimer(agentMessage) {
  const body = agentMessage.querySelector(".message-body");
  if (!body) return;
  const badge = document.createElement("div");
  badge.className = "message-duration";
  body.insertBefore(badge, body.firstChild);
  agentMessage._totalStart = Date.now();
  agentMessage._totalInterval = setInterval(() => {
    badge.textContent = "⏱ " + formatDuration(Date.now() - agentMessage._totalStart);
  }, 200);
}

function stopTotalTimer(agentMessage, finalMs) {
  if (agentMessage._totalInterval) {
    clearInterval(agentMessage._totalInterval);
    agentMessage._totalInterval = null;
  } else if (finalMs === undefined) {
    return;
  }
  const badge = agentMessage.querySelector(".message-duration");
  if (badge) {
    const ms = finalMs ?? (agentMessage._totalStart ? Date.now() - agentMessage._totalStart : 0);
    badge.textContent = "⏱ 总计 " + formatDuration(ms);
  }
}

function setStopMode(active) {
  const span = elements.send.querySelector("span");
  const b = elements.send.querySelector("b");
  if (active) {
    elements.send.classList.add("stop-mode");
    elements.send.disabled = false;
    if (span) span.textContent = "停止";
    if (b) b.textContent = "■";
  } else {
    elements.send.classList.remove("stop-mode");
    elements.send.disabled = false;
    if (span) span.textContent = "发送";
    if (b) b.textContent = "↑";
  }
}

function stopAgent() {
  if (abortController) abortController.abort();
}

function finalizeOnStop(agentMessage) {
  // 停止活跃工具卡片的计时器
  agentMessage.querySelectorAll(".tool-card-time").forEach(ts => {
    if (ts._interval) {
      clearInterval(ts._interval);
      ts._interval = null;
    }
  });
  // 折叠正在流式输出的文本
  if (agentMessage._streamedText) {
    foldStreamedText(agentMessage);
    agentMessage._streamedText = null;
  }
  // 折叠活跃的工具卡片
  foldToolCards(agentMessage);

  // 如果消息体没有任何实际内容，添加中断提示
  const body = agentMessage.querySelector(":scope > .message-body");
  if (body) {
    const textBody = body.querySelector(":scope > .answer-body");
    const hasText = textBody && textBody.textContent.trim();
    const hasDetails = body.querySelector(":scope > details");
    if (!hasText && !hasDetails) {
      setMessageText(agentMessage, "*已停止*");
    } else {
      appendStopNotice(agentMessage);
    }
  }
  addTrace("done", "用户中断", "用户主动停止了执行");
}

function appendStopNotice(agentMessage) {
  const body = agentMessage.querySelector(":scope > .message-body");
  if (!body) return;
  const notice = document.createElement("div");
  notice.className = "stop-notice";
  notice.textContent = "⏹ 已停止";
  body.appendChild(notice);
}

const TOOL_INFO = {
  read_file: { icon: "📖", label: "读取文件" },
  list_files: { icon: "📂", label: "浏览文件" },
  search_code: { icon: "🔍", label: "搜索代码" },
  create_file: { icon: "📝", label: "创建文件" },
  edit_file: { icon: "✏️", label: "编辑文件" },
  delete_file: { icon: "🗑️", label: "删除文件" },
  todo_write: { icon: "📋", label: "规划步骤" },
  connect_mcp: { icon: "🔌", label: "连接服务" },
  task: { icon: "🤖", label: "执行子任务" },
  load_skills: { icon: "📚", label: "加载技能" },
  ask_user: { icon: "❓", label: "提问用户" },
  __approval: { icon: "⏳", label: "等待审批" },
};

function handleStreamEvent(data, agentMessage) {
  if (data.type === "text_delta") {
    if (!agentMessage._streamedText) {
      foldToolCards(agentMessage);
      agentMessage._streamedText = "";
      const el = agentMessage.querySelector(".message-text");
      const body = document.createElement("div");
      body.className = "answer-body";
      el.appendChild(body);
    }
    agentMessage._streamedText += data.text;
    const body = agentMessage.querySelector(".message-text > .answer-body");
    if (window.marked) {
      body.innerHTML = window.marked.parse(agentMessage._streamedText)
        + '<span class="stream-cursor"></span>';
      externalizeLinks(body);
    } else {
      body.textContent = agentMessage._streamedText + "▎";
    }
    elements.messages.scrollTop = elements.messages.scrollHeight;
    return;
  }

  if (data.type === "text_clear") {
    agentMessage._streamedText = null;
    return;
  }

  if (data.type === "user_question") {
    if (agentMessage._streamedText) {
      foldStreamedText(agentMessage);
    }
    agentMessage._streamedText = null;
    renderUserQuestion(data, agentMessage);
    return;
  }

  if (data.type === "tool_start") {
    const cards = agentMessage.querySelectorAll(".tool-card.active");
    const lastCard = cards[cards.length - 1];
    if (lastCard) {
      const timeSpan = lastCard.querySelector(".tool-card-time");
      if (timeSpan && !timeSpan._interval) {
        const start = Date.now();
        timeSpan._interval = setInterval(() => {
          timeSpan.textContent = "⏱ " + formatDuration(Date.now() - start);
        }, 200);
      }
    }
    return;
  }

  if (data.type === "tool_end") {
    const activeCards = agentMessage.querySelectorAll(".tool-card.active");
    activeCards.forEach(card => {
      const timeSpan = card.querySelector(".tool-card-time");
      if (timeSpan && timeSpan._interval) {
        clearInterval(timeSpan._interval);
        timeSpan._interval = null;
      }
      card.classList.remove("active");
      card.classList.add("done");
      if (timeSpan) {
        timeSpan.textContent = "⏱ " + formatDuration(data.durationMs ?? 0);
      }
    });
    return;
  }

  if (data.type === "result") {
    // 停止总计时器
    stopTotalTimer(agentMessage, data.durationMs);
    foldToolCards(agentMessage);
    if (!agentMessage._hasUserQuestion) {
      if (agentMessage._streamedText) {
        setMessageText(agentMessage, data.text);
      } else {
        streamMarkdown(agentMessage, data.text);
      }
    }
    renderTodos(data.todos ?? []);
    for (const approval of data.approvals ?? []) {
      addApprovalCard(approval);
    }
    addTrace("done", "任务完成", `共 ${data.steps} 步，${data.toolCalls} 次工具调用`);
    elements.stepCount.textContent = data.steps;
    elements.toolCount.textContent = data.toolCalls;
    elements.traceSummary.classList.add("visible");
  } else if (data.type === "error") {
    agentMessage.classList.add("error");
    cancelStream(agentMessage);
    setMessageText(agentMessage, data.error);
  } else {
    if (data.kind === "tool" || data.kind === "approval") {
      addTrace(data.kind, data.title, data.detail);
      if (agentMessage._streamedText) {
        foldStreamedText(agentMessage);
      }
    }
    const toolName = getToolName(data);
    if (toolName) {
      appendToolCard(agentMessage, toolName);
      elements.messages.scrollTop = elements.messages.scrollHeight;
    }
  }
}

function cancelStream(agentMessage) {
  if (agentMessage._streamTimer) {
    clearInterval(agentMessage._streamTimer);
    agentMessage._streamTimer = null;
  }
}

function renderUserQuestion(data, agentMessage) {
  agentMessage._hasUserQuestion = true;
  const el = agentMessage.querySelector(".message-text");
  // 清除"思考中…"
  const hasRealContent = el.querySelector("details, .tool-card, .answer-body");
  if (!hasRealContent) {
    el.textContent = "";
    el.style.whiteSpace = "";
  }

  // 渲染问题文本
  const questionBody = document.createElement("div");
  questionBody.className = "answer-body";
  renderMarkdown(questionBody, data.question);
  el.appendChild(questionBody);

  // 渲染选项卡片
  const options = Array.isArray(data.options) ? data.options : [];
  if (options.length > 0) {
    const card = document.createElement("div");
    card.className = "user-question-card";

    const hint = document.createElement("div");
    hint.className = "user-question-hint";
    hint.textContent = "选择一个选项，或在下方输入框直接回复";
    card.appendChild(hint);

    const btnList = document.createElement("div");
    btnList.className = "user-question-options";

    for (const opt of options) {
      const btn = document.createElement("button");
      btn.type = "button";
      btn.className = "user-question-option";

      const label = document.createElement("span");
      label.className = "option-label";
      label.textContent = opt.label;
      btn.appendChild(label);

      if (opt.description) {
        const desc = document.createElement("span");
        desc.className = "option-desc";
        desc.textContent = opt.description;
        btn.appendChild(desc);
      }

      btn.addEventListener("click", () => {
        // 禁用所有按钮
        btnList.querySelectorAll("button").forEach(b => b.disabled = true);
        btn.classList.add("selected");
        // 发送选项作为用户消息
        submitMessage(opt.label);
      });

      btnList.appendChild(btn);
    }

    card.appendChild(btnList);
    el.appendChild(card);
  }

  elements.messages.scrollTop = elements.messages.scrollHeight;
}

function getToolName(data) {
  if (data.kind === "model") return null;
  if (data.kind === "tool") {
    return data.title.replace(/^工具\s*[·\-]\s*/, "").trim();
  }
  if (data.kind === "approval") return "__approval";
  return null;
}

function appendToolCard(agentMessage, toolName) {
  const info = TOOL_INFO[toolName] ?? { icon: "🔧", label: toolName };
  const el = agentMessage.querySelector(".message-text");

  if (!agentMessage._toolCards) agentMessage._toolCards = [];
  agentMessage._toolCards.push(info);

  const hasRealContent = el.querySelector("details, .tool-card, .answer-body");
  if (!hasRealContent) {
    el.textContent = "";
    el.style.whiteSpace = "";
  }

  const card = document.createElement("div");
  card.className = "tool-card active";
  const icon = document.createElement("span");
  icon.className = "tool-card-icon";
  icon.textContent = info.icon;
  const name = document.createElement("span");
  name.className = "tool-card-name";
  name.textContent = info.label;
  const time = document.createElement("span");
  time.className = "tool-card-time";
  time.textContent = "⏱";
  card.append(icon, name, time);
  el.appendChild(card);
}

function foldToolCards(agentMessage) {
  const el = agentMessage.querySelector(".message-text");
  const existingCards = el.querySelectorAll(":scope > .tool-card");

  if (!agentMessage._toolCards || agentMessage._toolCards.length === 0) {
    if (existingCards.length === 0 && el.children.length === 0) {
      el.textContent = "";
      el.style.whiteSpace = "";
    }
    return;
  }

  const details = document.createElement("details");
  details.className = "tool-trace-details";
  const summary = document.createElement("summary");
  summary.textContent = `工具调用 (${agentMessage._toolCards.length})`;

  const list = document.createElement("div");
  list.className = "tool-card-list";

  // 移动现有卡片而非重新创建（保留计时信息）
  existingCards.forEach(card => {
    card.classList.remove("active");
    card.classList.add("done");
    // 停止卡片上的计时器
    const ts = card.querySelector(".tool-card-time");
    if (ts && ts._interval) {
      clearInterval(ts._interval);
      ts._interval = null;
    }
    list.appendChild(card);
  });

  details.append(summary, list);
  el.appendChild(details);
  agentMessage._toolCards = null;
}

function foldStreamedText(agentMessage) {
  if (!agentMessage._streamedText) return;

  const el = agentMessage.querySelector(".message-text");
  const body = el.querySelector(":scope > .answer-body");
  if (!body) { agentMessage._streamedText = null; return; }

  body.querySelector(".stream-cursor")?.remove();

  const details = document.createElement("details");
  details.className = "text-section-details";
  const summary = document.createElement("summary");
  summary.textContent = "模型回答";
  details.appendChild(summary);

  el.insertBefore(details, body);
  details.appendChild(body);

  agentMessage._streamedText = null;
}

function streamMarkdown(agentMessage, text) {
  cancelStream(agentMessage);
  const el = agentMessage.querySelector(".message-text");
  let body = el.querySelector(":scope > .answer-body");
  if (!body) {
    body = document.createElement("div");
    body.className = "answer-body";
    el.appendChild(body);
  }
  let pos = 0;
  const chunkSize = 3;

  agentMessage._streamTimer = setInterval(() => {
    pos += chunkSize;
    const partial = text.slice(0, pos);
    if (window.marked) {
      body.innerHTML = window.marked.parse(partial)
        + '<span class="stream-cursor"></span>';
      externalizeLinks(body);
    } else {
      body.textContent = partial + "▎";
    }
    elements.messages.scrollTop = elements.messages.scrollHeight;

    if (pos >= text.length) {
      clearInterval(agentMessage._streamTimer);
      agentMessage._streamTimer = null;
      renderMarkdown(body, text);
    }
  }, 20);
}

function addMessage(role, text) {
  const article = document.createElement("article");
  article.className = `message ${role}`;

  const avatar = document.createElement("div");
  avatar.className = "avatar";
  avatar.textContent = role === "user" ? "YOU" : "AI";

  const body = document.createElement("div");
  body.className = "message-body";

  const meta = document.createElement("div");
  meta.className = "message-meta";
  meta.textContent = role === "user" ? "你" : "Agent";

  const content = document.createElement("div");
  content.className = "message-text";
  content.textContent = text;

  body.append(meta, content);
  article.append(avatar, body);
  elements.messages.append(article);
  elements.messages.scrollTop = elements.messages.scrollHeight;
  return article;
}

function setMessageText(messageElement, text) {
  const el = messageElement.querySelector(".message-text");
  el.style.whiteSpace = "";

  let body = el.querySelector(":scope > .answer-body");
  if (!body) {
    body = document.createElement("div");
    body.className = "answer-body";
    el.appendChild(body);
  }
  if (window.marked) {
    body.innerHTML = window.marked.parse(text);
    externalizeLinks(body);
  } else {
    body.textContent = text;
  }
  elements.messages.scrollTop = elements.messages.scrollHeight;
}

function renderTodos(todos) {
  if (!todos || todos.length === 0) {
    elements.todoPanel.style.display = "none";
    return;
  }
  elements.todoPanel.style.display = "";
  elements.todoList.replaceChildren();

  const icons = { completed: "✓", in_progress: "●", pending: "○" };
  for (const todo of todos) {
    const li = document.createElement("li");
    li.className = `todo-item ${todo.status}`;
    const icon = document.createElement("span");
    icon.className = "todo-icon";
    icon.textContent = icons[todo.status] ?? "○";
    const text = document.createElement("span");
    text.className = "todo-text";
    text.textContent = todo.content;
    li.append(icon, text);
    elements.todoList.append(li);
  }

  const done = todos.filter((t) => t.status === "completed").length;
  elements.todoProgress.textContent = `${done}/${todos.length}`;
}

function addTrace(kind, title, detail) {
  elements.traceEmpty.style.display = "none";
  const item = document.createElement("li");
  item.className = `trace-item ${kind}`;

  const node = document.createElement("span");
  node.className = "trace-node";

  const header = document.createElement("div");
  header.className = "trace-title";
  const label = document.createElement("span");
  label.textContent = title;
  const time = document.createElement("time");
  time.textContent = new Date().toLocaleTimeString("zh-CN", { hour12: false });
  header.append(label, time);

  const detailNode = document.createElement("div");
  detailNode.className = "trace-detail";
  detailNode.textContent = detail;

  item.append(node, header, detailNode);
  elements.traceList.append(item);
}

function addApprovalCard(approval) {
  elements.traceEmpty.style.display = "none";
  const item = document.createElement("li");
  item.className = "trace-item approval";

  const node = document.createElement("span");
  node.className = "trace-node";

  const header = document.createElement("div");
  header.className = "trace-title";
  const title = document.createElement("span");
  const actionNames = {
    create: "请求创建",
    edit: "请求修改",
    delete: "请求删除"
  };
  title.textContent = `${actionNames[approval.operation] ?? "文件操作"} · ${approval.path}`;
  const time = document.createElement("time");
  time.textContent = "等待批准";
  header.append(title, time);

  const card = document.createElement("div");
  card.className = "approval-card";
  const gates = document.createElement("ul");
  gates.className = "gate-list";
  for (const gate of approval.gates ?? []) {
    const row = document.createElement("li");
    row.textContent = gate;
    gates.append(row);
  }

  const preview = document.createElement("pre");
  preview.textContent = approval.preview;

  const result = document.createElement("p");
  result.className = "approval-result";

  const actions = document.createElement("div");
  actions.className = "approval-actions";
  const reject = document.createElement("button");
  reject.type = "button";
  reject.className = "reject";
  reject.textContent = "拒绝";
  const approve = document.createElement("button");
  approve.type = "button";
  approve.className = approval.operation === "delete" ? "danger" : "approve";
  approve.textContent = {
    create: "批准创建",
    edit: "批准修改",
    delete: "批准删除"
  }[approval.operation] ?? "批准";
  actions.append(reject, approve);

  reject.addEventListener("click", () => decideApproval(
    approval,
    "reject",
    { approve, reject, result, item }
  ));
  approve.addEventListener("click", () => decideApproval(
    approval,
    "approve",
    { approve, reject, result, item }
  ));

  card.append(gates, preview, result, actions);
  item.append(node, header, card);
  elements.traceList.append(item);
  elements.traceList.scrollTop = elements.traceList.scrollHeight;
}

async function decideApproval(approval, decision, ui) {
  ui.approve.disabled = true;
  ui.reject.disabled = true;
  ui.result.textContent = decision === "approve" ? "正在执行…" : "正在拒绝…";

  try {
    const response = await fetch(
      `/api/approvals/${encodeURIComponent(approval.approvalId)}`,
      {
        method: "POST",
        headers: { "content-type": "application/json" },
        body: JSON.stringify({ decision })
      }
    );
    const data = await response.json();
    if (!response.ok) throw new Error(data.error || "审批处理失败");

    ui.item.classList.add(data.status === "approved" ? "done" : "rejected");
    ui.result.textContent = data.status === "approved"
      ? `${data.path} 已执行`
      : "本次操作已拒绝";
    ui.approve.remove();
    ui.reject.remove();
    if (data.status === "approved") {
      setTimeout(() => submitMessage("审批已通过，请继续执行任务"), 600);
    }
  } catch (error) {
    ui.result.textContent = error.message;
    ui.approve.disabled = false;
    ui.reject.disabled = false;
  }
}

function clearTrace() {
  elements.traceList.replaceChildren();
  elements.traceEmpty.style.display = "";
  elements.traceSummary.classList.remove("visible");
  elements.todoPanel.style.display = "none";
}

function resetPage() {
  elements.messages.replaceChildren();
  elements.intro.classList.remove("hidden");
  clearTrace();
  elements.input.focus();
}

function autoResize() {
  elements.input.style.height = "auto";
  elements.input.style.height = `${Math.min(elements.input.scrollHeight, 140)}px`;
}
