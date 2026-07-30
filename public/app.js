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
  duration: document.querySelector("#duration"),
  reset: document.querySelector("#resetButton")
};

let busy = false;

initialize();

async function initialize() {
  autoResize();
  document.querySelectorAll("[data-prompt]").forEach((button) => {
    button.addEventListener("click", () => submitMessage(button.dataset.prompt));
  });
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
      ? `${data.model} · s01`
      : "s01 · API Key 未配置";
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
    elements.stepCount.textContent = data.steps;
    elements.toolCount.textContent = data.toolCalls;
    elements.duration.textContent = data.durationMs;
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
