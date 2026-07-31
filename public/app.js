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

if (window.marked) {
  marked.setOptions({ breaks: true });
}

initialize();

async function initialize() {
  autoResize();
  elements.form.addEventListener("submit", (event) => {
    event.preventDefault();
    submitMessage(elements.input.value);
  });
  elements.input.addEventListener("input", autoResize);
  elements.input.addEventListener("keydown", (event) => {
    if (event.key === "Enter" && !event.shiftKey) {
      event.preventDefault();
      submitMessage(elements.input.value);
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
  elements.send.disabled = true;
  elements.input.value = "";
  autoResize();
  elements.intro.classList.add("hidden");
  addMessage("user", message);
  clearTrace();
  const agentMessage = addMessage("agent", "思考中…");

  try {
    const response = await fetch("/api/chat/stream", {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ message })
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
    agentMessage.classList.add("error");
    setMessageText(agentMessage, error.message);
    addTrace("error", "运行失败", error.message);
  } finally {
    busy = false;
    elements.send.disabled = false;
    elements.input.focus();
  }
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
  __approval: { icon: "⏳", label: "等待审批" },
};

function handleStreamEvent(data, agentMessage) {
  if (data.type === "text_delta") {
    if (!agentMessage._streamedText) {
      collapseBubbleLines(agentMessage);
      agentMessage._streamedText = "";
      const el = agentMessage.querySelector(".message-text");
      const body = document.createElement("div");
      body.className = "answer-body";
      el.appendChild(body);
    }
    agentMessage._streamedText += data.text;
    const body = agentMessage.querySelector(".answer-body");
    if (window.marked) {
      body.innerHTML = window.marked.parse(agentMessage._streamedText)
        + '<span class="stream-cursor"></span>';
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

  if (data.type === "result") {
    collapseBubbleLines(agentMessage);
    if (agentMessage._streamedText) {
      setMessageText(agentMessage, data.text);
    } else {
      streamMarkdown(agentMessage, data.text);
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
        agentMessage._streamedText = null;
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

function getToolName(data) {
  if (data.kind === "model") return null;
  if (data.kind === "tool") {
    return data.title.replace(/^工具\s*-\s*/, "").trim();
  }
  if (data.kind === "approval") return "__approval";
  return null;
}

function appendToolCard(agentMessage, toolName) {
  const info = TOOL_INFO[toolName] ?? { icon: "🔧", label: toolName };
  const el = agentMessage.querySelector(".message-text");

  if (!agentMessage._toolCards) agentMessage._toolCards = [];
  agentMessage._toolCards.push(info);

  if (el.children.length === 0) {
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
  card.append(icon, name);
  el.appendChild(card);
}

function collapseBubbleLines(agentMessage) {
  const el = agentMessage.querySelector(".message-text");
  if (agentMessage._toolCards && agentMessage._toolCards.length > 0) {
    const details = document.createElement("details");
    details.className = "tool-trace-details";
    const summary = document.createElement("summary");
    summary.textContent = `工具调用 (${agentMessage._toolCards.length})`;

    const list = document.createElement("div");
    list.className = "tool-card-list";
    for (const info of agentMessage._toolCards) {
      const card = document.createElement("div");
      card.className = "tool-card done";
      const icon = document.createElement("span");
      icon.className = "tool-card-icon";
      icon.textContent = info.icon;
      const name = document.createElement("span");
      name.className = "tool-card-name";
      name.textContent = info.label;
      card.append(icon, name);
      list.appendChild(card);
    }

    details.append(summary, list);
    el.style.whiteSpace = "";
    el.replaceChildren(details);
  } else {
    el.style.whiteSpace = "";
    el.textContent = "";
  }
  agentMessage._toolCards = null;
}

function streamMarkdown(agentMessage, text) {
  cancelStream(agentMessage);
  const el = agentMessage.querySelector(".message-text");
  let body = el.querySelector(".answer-body");
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
    } else {
      body.textContent = partial + "▎";
    }
    elements.messages.scrollTop = elements.messages.scrollHeight;

    if (pos >= text.length) {
      clearInterval(agentMessage._streamTimer);
      agentMessage._streamTimer = null;
      if (window.marked) {
        body.innerHTML = window.marked.parse(text);
      } else {
        body.textContent = text;
      }
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
  const details = el.querySelector(".tool-trace-details");

  let body = el.querySelector(".answer-body");
  if (!body) {
    body = document.createElement("div");
    body.className = "answer-body";
  }
  if (window.marked) {
    body.innerHTML = window.marked.parse(text);
  } else {
    body.textContent = text;
  }
  if (details) {
    el.replaceChildren(details, body);
  } else {
    el.replaceChildren(body);
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
