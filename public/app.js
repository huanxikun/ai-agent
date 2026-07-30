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
  reset: document.querySelector("#resetButton")
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
    elements.provider.textContent = data.configured
      ? `${data.model} · 三道闸门`
      : "API Key 未配置 · 三道闸门";
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
  const agentMessage = addMessage("agent", "正在处理…");

  try {
    const response = await fetch("/api/chat", {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ message })
    });
    const data = await response.json();
    if (!response.ok) throw new Error(data.error || "请求失败");

    setMessageText(agentMessage, data.text);
    for (const event of data.trace) {
      addTrace(event.kind, event.title, event.detail);
    }
    for (const approval of data.approvals ?? []) {
      addApprovalCard(approval);
    }
    elements.stepCount.textContent = data.steps;
    elements.toolCount.textContent = data.toolCalls;
    elements.traceSummary.classList.add("visible");
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
  messageElement.querySelector(".message-text").textContent = text;
  elements.messages.scrollTop = elements.messages.scrollHeight;
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
