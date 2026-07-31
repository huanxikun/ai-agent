package com.example.agent.mcp.client;

import com.example.agent.tools.ToolDefinition;
import com.example.agent.tools.ToolRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class McpManager implements AutoCloseable {
    private static final String DEFAULT_SCM_MAIN =
            "com.example.agent.mcp.scm.ScmMcpServer";
    private static final String DEFAULT_WORKSPACE_MAIN =
            "com.example.agent.mcp.workspace.WorkspaceMcpServer";

    private final Path projectRoot;
    private final ObjectMapper json;
    private final Map<String, String> env;
    private final Map<String, McpServerConfig> configs;
    private final Map<String, McpConnection> connections = new LinkedHashMap<>();
    private final Map<String, String> toolToServer = new LinkedHashMap<>();

    public McpManager(Path projectRoot, ObjectMapper json, Map<String, String> env) throws Exception {
        this.projectRoot = projectRoot.toAbsolutePath().normalize();
        this.json = json;
        this.env = Map.copyOf(env);
        this.configs = loadConfigs();
    }

    public synchronized ConnectResult connect(String serverName, ToolRegistry registry)
            throws Exception {
        String normalized = serverName == null || serverName.isBlank()
                ? "workspace"
                : serverName.trim();
        McpConnection existing = connections.get(normalized);
        if (existing != null) {
            return new ConnectResult(
                    normalized,
                    true,
                    registry.mcpToolNames().stream()
                            .filter(name -> normalized.equals(toolToServer.get(name)))
                            .toList()
            );
        }

        McpServerConfig config = configs.get(normalized);
        if (config == null) {
            throw new IllegalArgumentException(
                    "未知 MCP server：" + normalized + "。可用值："
                            + String.join(", ", configs.keySet())
            );
        }

        ProcessBuilder builder = new ProcessBuilder(buildCommand(config))
                .directory(projectRoot.toFile())
                .redirectError(ProcessBuilder.Redirect.INHERIT);
        builder.environment().putAll(env);
        Process process = builder.start();

        McpConnection connection = new McpConnection(
                normalized,
                process,
                json,
                new LinkedHashMap<>()
        );
        try {
            List<McpToolDescriptor> descriptors = connection.initializeAndList();
            for (McpToolDescriptor descriptor : descriptors) {
                registry.register(new ToolDefinition(
                        descriptor.prefixedName(),
                        descriptor.description(),
                        descriptor.inputSchema(),
                        (arguments, context) -> connection.callTool(
                                descriptor.prefixedName(),
                                arguments
                        ),
                        false,
                        normalized
                ));
                toolToServer.put(descriptor.prefixedName(), normalized);
            }
            connections.put(normalized, connection);
            return new ConnectResult(
                    normalized,
                    false,
                    descriptors.stream()
                            .map(McpToolDescriptor::prefixedName)
                            .toList()
            );
        } catch (Exception exception) {
            connection.close();
            throw exception;
        }
    }

    public synchronized Summary summary() {
        return new Summary(
                List.copyOf(connections.keySet()),
                List.copyOf(toolToServer.keySet()),
                List.copyOf(configs.keySet())
        );
    }

    private Map<String, McpServerConfig> loadConfigs() throws Exception {
        Map<String, McpServerConfig> loaded = new LinkedHashMap<>();
        loaded.put("scm", defaultConfig("scm", DEFAULT_SCM_MAIN));
        loaded.put("workspace", defaultConfig("workspace", DEFAULT_WORKSPACE_MAIN));

        Path configFile = projectRoot.resolve(".mcp.json");
        if (!Files.exists(configFile)) return loaded;

        JsonNode root = json.readTree(Files.readString(configFile));
        JsonNode servers = root.path("servers");
        if (!servers.isObject()) return loaded;

        servers.fields().forEachRemaining(entry -> {
            JsonNode config = entry.getValue();
            String command = config.path("command").asText("").trim();
            if (command.isEmpty()) return;
            List<String> args = new ArrayList<>();
            JsonNode argsNode = config.path("args");
            if (argsNode.isArray()) {
                for (JsonNode item : argsNode) {
                    args.add(replacePlaceholders(item.asText("")));
                }
            }
            loaded.put(entry.getKey(), new McpServerConfig(
                    entry.getKey(),
                    command,
                    args
            ));
        });
        return loaded;
    }

    private McpServerConfig defaultConfig(String name, String mainClass) {
        return new McpServerConfig(
                name,
                javaCommand(),
                List.of(
                        "-cp",
                        System.getProperty("java.class.path"),
                        mainClass,
                        projectRoot.toString()
                )
        );
    }

    private List<String> buildCommand(McpServerConfig config) {
        List<String> command = new ArrayList<>();
        command.add(config.command());
        for (String arg : config.args()) {
            command.add(replacePlaceholders(arg));
        }
        return command;
    }

    private String replacePlaceholders(String raw) {
        return raw.replace("${projectRoot}", projectRoot.toString());
    }

    private String javaCommand() {
        Path executable = Path.of(
                System.getProperty("java.home"),
                "bin",
                isWindows() ? "java.exe" : "java"
        );
        return executable.toString();
    }

    private boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    @Override
    public synchronized void close() {
        connections.values().forEach(McpConnection::close);
        connections.clear();
        toolToServer.clear();
    }

    public record ConnectResult(
            String serverName,
            boolean alreadyConnected,
            List<String> toolNames
    ) {
    }

    public record Summary(
            List<String> connectedServers,
            List<String> connectedTools,
            List<String> availableServers
    ) {
    }
}
