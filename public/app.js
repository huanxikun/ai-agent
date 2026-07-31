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

const TOOL_HINTS = {
  read_file: "📖 正在读取文件…",
  list_files: "📂 正在浏览文件…",
  search_code: "🔍 正在搜索代码…",
  create_file: "📝 正在创建文件…",
  edit_file: "✏️ 正在编辑文件…",
  delete_file: "🗑️ 正在删除文件…",
  todo_write: "📋 正在规划步骤…",
  connect_mcp: "🔌 正在连接工具服务…",
  task: "🤖 正在执行子任务…",
  load_skills: "📚 正在加载技能…",
};

function handleStreamEvent(data, agentMessage) {
  if (data.type === "result") {
    setMessageText(agentMessage, data.text);
    renderTodos(data.todos ?? []);
    for (const approval of data.approvals ?? []) {
      addApprovalCard(approval);
    }
    elements.stepCount.textContent = data.steps;
    elements.toolCount.textContent = data.toolCalls;
    elements.traceSummary.classList.add("visible");
  } else if (data.type === "error") {
    agentMessage.classList.add("error");
    setMessageText(agentMessage, data.error);
  } else {
    addTrace(data.kind, data.title, data.detail);
    const hint = toolStatusHint(data);
    if (hint) {
      agentMessage.querySelector(".message-text").textContent = hint;
      elements.messages.scrollTop = elements.messages.scrollHeight;
    }
  }
}

function toolStatusHint(data) {
  if (data.kind === "model") return "思考中…";
  if (data.kind === "tool") {
    const name = data.title.replace(/^工具\s*-\s*/, "").trim();
    return TOOL_HINTS[name] ?? `正在调用 ${name}…`;
  }
  if (data.kind === "approval") return "⏳ 等待审批…";
  return null;
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
  if (window.marked) {
    el.innerHTML = window.marked.parse(text);
  } else {
    el.textContent = text;
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
