package com.example.agent;

import com.example.agent.background.BackgroundTaskManager;
import com.example.agent.context.ContextCompactor;
import com.example.agent.permissions.FilePermissionService;
import com.example.agent.permissions.HumanApprovalGate;
import com.example.agent.prompts.SystemPromptAssembler;
import com.example.agent.recovery.ErrorRecovery;
import com.example.agent.hooks.DefaultAgentHooks;
import com.example.agent.hooks.HookEvent;
import com.example.agent.hooks.HookRegistry;
import com.example.agent.hooks.PermissionHooks;
import com.example.agent.memory.MemorySystem;
import com.example.agent.mcp.client.McpManager;
import com.example.agent.subagents.Subagent;
import com.example.agent.skills.SkillCatalog;
import com.example.agent.tasks.TaskStore;
import com.example.agent.todos.TodoStore;
import com.example.agent.tools.CodeTools;
import com.example.agent.tools.ToolHandlers;
import com.example.agent.tools.ToolRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.BindException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;

public final class AgentApplication {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Path PUBLIC_DIR = Path.of("public").toAbsolutePath().normalize();

    private AgentApplication() {
    }

    public static void main(String[] args) throws Exception {
        Map<String, String> env = loadEnvironment();
        int port = Integer.parseInt(env.getOrDefault("PORT", "3000"));
        String apiKey = env.getOrDefault("DEEPSEEK_API_KEY", "");
        String model = env.getOrDefault("DEEPSEEK_MODEL", "deepseek-v4-flash");
        String fallbackModel = env.getOrDefault(
                "DEEPSEEK_FALLBACK_MODEL",
                env.getOrDefault("FALLBACK_MODEL_ID", "")
        );
        String baseUrl = env.getOrDefault("DEEPSEEK_BASE_URL", "https://api.deepseek.com");

        DeepSeekClient modelClient =
                new DeepSeekClient(apiKey, model, baseUrl, JSON);
        Path projectRoot = Path.of(
                env.getOrDefault("PROJECT_ROOT", ".")
        ).toAbsolutePath().normalize();

        FilePermissionService permissions = new FilePermissionService(projectRoot);
        HumanApprovalGate approvals = new HumanApprovalGate();
        HookRegistry hooks = new HookRegistry();
        DefaultAgentHooks.register_hooks(hooks);
        PermissionHooks.register_hooks(hooks, permissions);
        TodoStore todoStore = new TodoStore();
        TaskStore taskStore = new TaskStore(projectRoot, JSON);
        SkillCatalog skillCatalog = new SkillCatalog(projectRoot);
        int contextTokenThreshold = parsePositiveInt(
                env.get("CONTEXT_TOKEN_THRESHOLD"),
                ContextCompactor.DEFAULT_TOKEN_THRESHOLD,
                "CONTEXT_TOKEN_THRESHOLD"
        );
        int agentMaxSteps = parsePositiveInt(
                env.get("AGENT_MAX_STEPS"),
                AgentLoop.UNLIMITED_MAX_STEPS,
                "AGENT_MAX_STEPS"
        );
        int subagentMaxSteps = parsePositiveInt(
                env.get("SUBAGENT_MAX_STEPS"),
                Subagent.UNLIMITED_MAX_STEPS,
                "SUBAGENT_MAX_STEPS"
        );
        ContextCompactor contextCompactor = new ContextCompactor(
                projectRoot,
                contextTokenThreshold,
                modelClient::summarize,
                JSON
        );
        MemorySystem memorySystem = new MemorySystem(
                projectRoot,
                modelClient::complete,
                JSON
        );
        BackgroundTaskManager backgroundTasks =
                new BackgroundTaskManager(JSON);
        McpManager mcpManager = new McpManager(projectRoot, JSON, env);
        ErrorRecovery errorRecovery = new ErrorRecovery(
                model,
                fallbackModel
        );

        CodeTools codeTools = new CodeTools(projectRoot, approvals, hooks, JSON, port);
        ToolRegistry subagentTools = new ToolRegistry(JSON, hooks);
        codeTools.registerReadOnlyInto(subagentTools);
        new ToolHandlers(null, null, skillCatalog, JSON)
                .registerSkillInto(subagentTools);
        SystemPromptAssembler subagentPrompt =
                new SystemPromptAssembler(
                        projectRoot,
                        subagentTools,
                        skillCatalog,
                        null,
                        true,
                        SystemPromptAssembler.AgentRole.SUBAGENT,
                        JSON
                );
        Subagent subagent = new Subagent(
                modelClient,
                subagentTools,
                hooks,
                contextCompactor,
                subagentPrompt,
                errorRecovery,
                subagentMaxSteps,
                JSON
        );

        ToolRegistry tools = new ToolRegistry(JSON, hooks);
        codeTools.registerInto(tools);
        new ToolHandlers(
                todoStore,
                subagent,
                skillCatalog,
                taskStore,
                mcpManager,
                JSON
        )
                .registerInto(tools);
        SystemPromptAssembler parentPrompt =
                new SystemPromptAssembler(
                        projectRoot,
                        tools,
                        skillCatalog,
                        memorySystem,
                        true,
                        SystemPromptAssembler.AgentRole.PARENT,
                        JSON
                );

        AgentLoop agentLoop = new AgentLoop(
                modelClient,
                tools,
                hooks,
                todoStore,
                contextCompactor,
                memorySystem,
                parentPrompt,
                errorRecovery,
                backgroundTasks,
                agentMaxSteps,
                JSON
        );

        HttpServer server;
        try {
            server = HttpServer.create(new InetSocketAddress(port), 0);
        } catch (BindException exception) {
            throw new IllegalStateException(
                    "端口 %d 已被占用。请先停止已有服务，或在 .env 中设置其他 PORT。"
                            .formatted(port),
                    exception
            );
        }

        server.createContext("/api/health", exchange -> {
            if (!"GET".equals(exchange.getRequestMethod())) {
                sendJson(exchange, 405, Map.of("error", "Method not allowed"));
                return;
            }
            Map<String, Object> health = new HashMap<>();
            health.put("ok", true);
            health.put("model", model);
            health.put("configured", !apiKey.isBlank());
            health.put("stage", "s19-mcp-client");
            health.put("taskSystem", Map.of(
                    "enabled", true,
                    "persistent", true,
                    "directory", ".tasks",
                    "summary", taskStore.summary()
            ));
            health.put("backgroundTasks", Map.of(
                    "enabled", true,
                    "notificationFormat", "task_notification",
                    "supportedTools", tools.backgroundToolNames(),
                    "summary", backgroundTasks.summary()
            ));
            health.put("memory", Map.of(
                    "enabled", true,
                    "count", memorySystem.count(),
                    "intelligentLoading", true,
                    "injectedAfterCompaction", true
            ));
            health.put("systemPrompt", Map.of(
                    "runtimeAssembly", true,
                    "conditionalSections", true,
                    "cache", parentPrompt.cacheStats()
            ));
            health.put("errorRecovery", Map.of(
                    "enabled", true,
                    "maxTokensEscalation", "8000->64000",
                    "promptTooLongRetries", 1,
                    "transientRetries", ErrorRecovery.MAX_TRANSIENT_RETRIES,
                    "fallbackModelConfigured", !fallbackModel.isBlank()
            ));
            health.put("contextCompact", Map.of(
                    "enabled", true,
                    "tokenThreshold", contextTokenThreshold,
                    "order", "L3->L1->L2->L4",
                    "reactiveFallback", true
            ));
            health.put("stepLimit", Map.of(
                    "agent", agentMaxSteps == 0 ? "unlimited" : agentMaxSteps,
                    "subagent", subagentMaxSteps == 0
                            ? "unlimited"
                            : subagentMaxSteps
            ));
            health.put("skills", Map.of(
                    "available", skillCatalog.discover().size(),
                    "onDemand", true
            ));
            McpManager.Summary mcpSummary = mcpManager.summary();
            health.put("mcp", Map.of(
                    "enabled", true,
                    "availableServers", mcpSummary.availableServers(),
                    "connectedServers", mcpSummary.connectedServers(),
                    "connectedTools", mcpSummary.connectedTools()
            ));
            health.put("todos", todoStore.summary());
            health.put("subagent", Map.of(
                    "enabled", true,
                    "recursiveTaskAllowed", subagentTools.hasTool("task"),
                    "readOnly", true
            ));
            health.put("hooks", Map.of(
                    HookEvent.USER_PROMPT_SCRIPT.displayName(),
                    hooks.registeredCount(HookEvent.USER_PROMPT_SCRIPT),
                    HookEvent.PRE_TOOL_USE.displayName(),
                    hooks.registeredCount(HookEvent.PRE_TOOL_USE),
                    HookEvent.POST_TOOL_USE.displayName(),
                    hooks.registeredCount(HookEvent.POST_TOOL_USE),
                    HookEvent.STOP.displayName(),
                    hooks.registeredCount(HookEvent.STOP)
            ));
            // 动态返回当前网址的 host / port / baseUrl
            String hostHeader = exchange.getRequestHeaders().getFirst("Host");
            int actualPort = port;
            String requestHost = "localhost";
            if (hostHeader != null && !hostHeader.isBlank()) {
                int colon = hostHeader.lastIndexOf(':');
                if (colon < 0) {
                    requestHost = hostHeader;
                } else {
                    requestHost = hostHeader.substring(0, colon);
                }
            }
            health.put("url", Map.of(
                    "host", requestHost,
                    "port", actualPort,
                    "baseUrl", "http://" + requestHost + ":" + actualPort
            ));
            sendJson(exchange, 200, health);
        });

        server.createContext("/api/chat", exchange -> {
            if (!"POST".equals(exchange.getRequestMethod())) {
                sendJson(exchange, 405, Map.of("error", "Method not allowed"));
                return;
            }

            try {
                byte[] body = exchange.getRequestBody().readNBytes(64 * 1024 + 1);
                if (body.length > 64 * 1024) {
                    throw new IllegalArgumentException("请求内容过大");
                }
                JsonNode request = JSON.readTree(body);
                String message = request.path("message").asText("").trim();
                if (message.isEmpty()) {
                    throw new IllegalArgumentException("message 不能为空");
                }
                sendJson(exchange, 200, agentLoop.run(message));
            } catch (Exception exception) {
                sendJson(exchange, 400, Map.of("error", exception.getMessage()));
            }
        });

        server.createContext("/api/chat/stream", exchange -> {
            if (!"POST".equals(exchange.getRequestMethod())) {
                sendJson(exchange, 405, Map.of("error", "Method not allowed"));
                return;
            }

            byte[] body;
            String message;
            try {
                body = exchange.getRequestBody().readNBytes(64 * 1024 + 1);
                if (body.length > 64 * 1024) {
                    throw new IllegalArgumentException("请求内容过大");
                }
                JsonNode request = JSON.readTree(body);
                message = request.path("message").asText("").trim();
                if (message.isEmpty()) {
                    throw new IllegalArgumentException("message 不能为空");
                }
            } catch (Exception exception) {
                sendJson(exchange, 400, Map.of("error", exception.getMessage()));
                return;
            }

            exchange.getResponseHeaders().set(
                    "Content-Type",
                    "text/event-stream; charset=utf-8"
            );
            exchange.getResponseHeaders().set("Cache-Control", "no-cache");
            exchange.getResponseHeaders().set("Connection", "keep-alive");
            exchange.sendResponseHeaders(200, 0);
            OutputStream out = exchange.getResponseBody();

            agentLoop.setStreamHandler(sseEvent -> {
                try {
                    String json = JSON.writeValueAsString(sseEvent);
                    out.write(("data: " + json + "\n\n").getBytes(StandardCharsets.UTF_8));
                    out.flush();
                } catch (IOException ignored) {
                    // 客户端已断开，通知 agent 停止执行
                    agentLoop.requestStop();
                }
            });

            try {
                AgentLoop.RunResult result = agentLoop.run(message);

                Map<String, Object> finalEvent = new HashMap<>();
                finalEvent.put("type", "result");
                finalEvent.put("text", result.text());
                finalEvent.put("steps", result.steps());
                finalEvent.put("toolCalls", result.toolCalls());
                finalEvent.put("durationMs", result.durationMs());
                finalEvent.put("todos", result.todos());
                finalEvent.put("approvals", result.approvals());
                String finalJson = JSON.writeValueAsString(finalEvent);
                out.write(("data: " + finalJson + "\n\n").getBytes(StandardCharsets.UTF_8));
                out.flush();
            } catch (Exception exception) {
                try {
                    Map<String, Object> errorEvent = new HashMap<>();
                    errorEvent.put("type", "error");
                    errorEvent.put("error", exception.getMessage() != null
                            ? exception.getMessage() : "未知错误");
                    String errorJson = JSON.writeValueAsString(errorEvent);
                    out.write(("data: " + errorJson + "\n\n").getBytes(StandardCharsets.UTF_8));
                    out.flush();
                } catch (IOException ignored) {
                    // 客户端已断开
                }
            } finally {
                agentLoop.setStreamHandler(null);
                out.close();
            }
        });

        server.createContext("/api/reset", exchange -> {
            if (!"POST".equals(exchange.getRequestMethod())) {
                sendJson(exchange, 405, Map.of("error", "Method not allowed"));
                return;
            }
            agentLoop.resetConversation();
            sendJson(exchange, 200, Map.of("status", "ok"));
        });

        server.createContext("/api/approvals/", exchange -> {            if (!"POST".equals(exchange.getRequestMethod())) {
                sendJson(exchange, 405, Map.of("error", "Method not allowed"));
                return;
            }

            try {
                String path = exchange.getRequestURI().getPath();
                String approvalId = path.substring("/api/approvals/".length()).trim();
                if (approvalId.isEmpty() || approvalId.contains("/")) {
                    throw new IllegalArgumentException("审批 ID 无效");
                }

                byte[] body = exchange.getRequestBody().readNBytes(4097);
                if (body.length > 4096) {
                    throw new IllegalArgumentException("请求内容过大");
                }
                JsonNode request = JSON.readTree(body);
                String decision = request.path("decision").asText("");

                Object result = switch (decision) {
                    case "approve" -> approvals.approve(approvalId);
                    case "reject" -> approvals.reject(approvalId);
                    default -> throw new IllegalArgumentException(
                            "decision 必须是 approve 或 reject"
                    );
                };
                sendJson(exchange, 200, result);
            } catch (Exception exception) {
                sendJson(exchange, 400, Map.of("error", exception.getMessage()));
            }
        });

        server.createContext("/", AgentApplication::serveStatic);
        server.setExecutor(Executors.newFixedThreadPool(
                Math.max(4, Runtime.getRuntime().availableProcessors())
        ));
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            backgroundTasks.close();
            mcpManager.close();
        }));
        server.start();

        System.out.printf("Agent 已启动：http://localhost:%d%n", port);
        System.out.printf("DeepSeek 模型：%s，API Key：%s%n", model, apiKey.isBlank() ? "未配置" : "已配置");
    }

    private static void serveStatic(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            sendJson(exchange, 405, Map.of("error", "Method not allowed"));
            return;
        }

        String requested = exchange.getRequestURI().getPath();
        if ("/".equals(requested)) requested = "/index.html";
        Path file = PUBLIC_DIR.resolve(requested.substring(1)).normalize();

        if (!file.startsWith(PUBLIC_DIR) || !Files.isRegularFile(file)) {
            sendJson(exchange, 404, Map.of("error", "Not found"));
            return;
        }

        byte[] content = Files.readAllBytes(file);
        exchange.getResponseHeaders().set("Content-Type", contentType(file));
        exchange.sendResponseHeaders(200, content.length);
        exchange.getResponseBody().write(content);
        exchange.close();
    }

    private static void sendJson(HttpExchange exchange, int status, Object value)
            throws IOException {
        byte[] body = JSON.writeValueAsBytes(value);
        exchange.getResponseHeaders().set(
                "Content-Type",
                "application/json; charset=utf-8"
        );
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    private static String contentType(Path file) {
        String name = file.getFileName().toString();
        if (name.endsWith(".html")) return "text/html; charset=utf-8";
        if (name.endsWith(".css")) return "text/css; charset=utf-8";
        if (name.endsWith(".js")) return "text/javascript; charset=utf-8";
        if (name.endsWith(".svg")) return "image/svg+xml";
        return "application/octet-stream";
    }

    private static Map<String, String> loadEnvironment() throws IOException {
        Map<String, String> values = new HashMap<>(System.getenv());
        Path dotenv = Path.of(".env");
        if (!Files.exists(dotenv)) return values;

        for (String rawLine : Files.readAllLines(dotenv, StandardCharsets.UTF_8)) {
            String line = rawLine.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            int separator = line.indexOf('=');
            if (separator <= 0) continue;
            String key = line.substring(0, separator).trim();
            String value = line.substring(separator + 1).trim();
            if (value.length() >= 2
                    && ((value.startsWith("\"") && value.endsWith("\""))
                    || (value.startsWith("'") && value.endsWith("'")))) {
                value = value.substring(1, value.length() - 1);
            }
            values.putIfAbsent(key, value);
        }
        return values;
    }

    private static int parsePositiveInt(
            String rawValue,
            int defaultValue,
            String name
    ) {
        if (rawValue == null || rawValue.isBlank()) return defaultValue;
        try {
            int value = Integer.parseInt(rawValue.trim());
            if (value < 0) throw new NumberFormatException();
            return value;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(
                    name + " 必须是大于等于 0 的整数"
            );
        }
    }

}
