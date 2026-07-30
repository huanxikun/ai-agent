package com.example.agent;

import com.example.agent.permissions.FilePermissionService;
import com.example.agent.permissions.HumanApprovalGate;
import com.example.agent.hooks.DefaultAgentHooks;
import com.example.agent.hooks.HookEvent;
import com.example.agent.hooks.HookRegistry;
import com.example.agent.hooks.PermissionHooks;
import com.example.agent.tools.CodeTools;
import com.example.agent.tools.ToolRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
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

        ToolRegistry tools = new ToolRegistry(JSON, hooks);
        new CodeTools(projectRoot, approvals, hooks, JSON).registerInto(tools);

        AgentLoop agentLoop = new AgentLoop(
                modelClient,
                tools,
                hooks,
                JSON
        );

        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);

        server.createContext("/api/health", exchange -> {
            if (!"GET".equals(exchange.getRequestMethod())) {
                sendJson(exchange, 405, Map.of("error", "Method not allowed"));
                return;
            }
            sendJson(exchange, 200, Map.of(
                    "ok", true,
                    "model", model,
                    "configured", !apiKey.isBlank(),
                    "stage", "s04-hooks",
                    "hooks", Map.of(
                            HookEvent.USER_PROMPT_SCRIPT.displayName(),
                            hooks.registeredCount(HookEvent.USER_PROMPT_SCRIPT),
                            HookEvent.PRE_TOOL_USE.displayName(),
                            hooks.registeredCount(HookEvent.PRE_TOOL_USE),
                            HookEvent.POST_TOOL_USE.displayName(),
                            hooks.registeredCount(HookEvent.POST_TOOL_USE),
                            HookEvent.STOP.displayName(),
                            hooks.registeredCount(HookEvent.STOP)
                    )
            ));
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

        server.createContext("/api/approvals/", exchange -> {
            if (!"POST".equals(exchange.getRequestMethod())) {
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
}
