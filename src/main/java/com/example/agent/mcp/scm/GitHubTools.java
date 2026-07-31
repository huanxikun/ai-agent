package com.example.agent.mcp.scm;

import com.example.agent.mcp.common.AbstractMcpServer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

public final class GitHubTools {
    private static final String API_BASE = "https://api.github.com";
    private static final int DEFAULT_LIMIT = 20;

    private final AbstractMcpServer server;
    private final ObjectMapper json;
    private final HttpClient client = HttpClient.newHttpClient();

    public GitHubTools(AbstractMcpServer server, ObjectMapper json) {
        this.server = server;
        this.json = json;
    }

    public void registerInto() {
        server.register(listIssuesTool());
        server.register(getIssueTool());
        server.register(listPullRequestsTool());
        server.register(getPullRequestTool());
    }

    private AbstractMcpServer.McpTool listIssuesTool() {
        ObjectNode schema = repoSchema();
        ObjectNode properties = (ObjectNode) schema.path("properties");
        properties.putObject("state")
                .put("type", "string")
                .put("description", "open, closed 或 all");
        properties.putObject("limit")
                .put("type", "integer")
                .put("description", "最大返回条数，默认 20");
        return new AbstractMcpServer.McpTool(
                "github_list_issues",
                "List GitHub issues for a repository. (readOnly)",
                schema,
                arguments -> {
                    String owner = AbstractMcpServer.requireText(arguments, "owner");
                    String repo = AbstractMcpServer.requireText(arguments, "repo");
                    String state = arguments.path("state").asText("open").trim();
                    int limit = Math.max(
                            1,
                            arguments.path("limit").asInt(DEFAULT_LIMIT)
                    );
                    JsonNode response = getJson(
                            "/repos/" + encode(owner) + "/" + encode(repo)
                                    + "/issues?state=" + encode(state)
                                    + "&per_page=" + limit
                    );
                    ArrayNode items = json.createArrayNode();
                    for (JsonNode node : response) {
                        if (node.has("pull_request")) continue;
                        items.addObject()
                                .put("number", node.path("number").asInt())
                                .put("title", node.path("title").asText(""))
                                .put("state", node.path("state").asText(""))
                                .put("url", node.path("html_url").asText(""));
                    }
                    return json.writerWithDefaultPrettyPrinter()
                            .writeValueAsString(items);
                }
        );
    }

    private AbstractMcpServer.McpTool getIssueTool() {
        ObjectNode schema = repoSchema();
        ((ObjectNode) schema.path("properties"))
                .putObject("number")
                .put("type", "integer")
                .put("description", "Issue 编号");
        schema.withArray("required").add("number");

        return new AbstractMcpServer.McpTool(
                "github_get_issue",
                "Get a single GitHub issue. (readOnly)",
                schema,
                arguments -> {
                    String owner = AbstractMcpServer.requireText(arguments, "owner");
                    String repo = AbstractMcpServer.requireText(arguments, "repo");
                    int number = arguments.path("number").asInt(0);
                    if (number <= 0) {
                        throw new IllegalArgumentException("number 必须大于 0");
                    }
                    JsonNode issue = getJson(
                            "/repos/" + encode(owner) + "/" + encode(repo)
                                    + "/issues/" + number
                    );
                    ObjectNode result = json.createObjectNode();
                    result.put("number", issue.path("number").asInt());
                    result.put("title", issue.path("title").asText(""));
                    result.put("state", issue.path("state").asText(""));
                    result.put("url", issue.path("html_url").asText(""));
                    result.put("body", issue.path("body").asText(""));
                    return json.writerWithDefaultPrettyPrinter()
                            .writeValueAsString(result);
                }
        );
    }

    private AbstractMcpServer.McpTool listPullRequestsTool() {
        ObjectNode schema = repoSchema();
        ObjectNode properties = (ObjectNode) schema.path("properties");
        properties.putObject("state")
                .put("type", "string")
                .put("description", "open, closed 或 all");
        properties.putObject("limit")
                .put("type", "integer")
                .put("description", "最大返回条数，默认 20");

        return new AbstractMcpServer.McpTool(
                "github_list_prs",
                "List GitHub pull requests for a repository. (readOnly)",
                schema,
                arguments -> {
                    String owner = AbstractMcpServer.requireText(arguments, "owner");
                    String repo = AbstractMcpServer.requireText(arguments, "repo");
                    String state = arguments.path("state").asText("open").trim();
                    int limit = Math.max(
                            1,
                            arguments.path("limit").asInt(DEFAULT_LIMIT)
                    );
                    JsonNode response = getJson(
                            "/repos/" + encode(owner) + "/" + encode(repo)
                                    + "/pulls?state=" + encode(state)
                                    + "&per_page=" + limit
                    );
                    ArrayNode items = json.createArrayNode();
                    for (JsonNode node : response) {
                        items.addObject()
                                .put("number", node.path("number").asInt())
                                .put("title", node.path("title").asText(""))
                                .put("state", node.path("state").asText(""))
                                .put("url", node.path("html_url").asText(""));
                    }
                    return json.writerWithDefaultPrettyPrinter()
                            .writeValueAsString(items);
                }
        );
    }

    private AbstractMcpServer.McpTool getPullRequestTool() {
        ObjectNode schema = repoSchema();
        ((ObjectNode) schema.path("properties"))
                .putObject("number")
                .put("type", "integer")
                .put("description", "Pull Request 编号");
        schema.withArray("required").add("number");

        return new AbstractMcpServer.McpTool(
                "github_get_pr",
                "Get a single GitHub pull request. (readOnly)",
                schema,
                arguments -> {
                    String owner = AbstractMcpServer.requireText(arguments, "owner");
                    String repo = AbstractMcpServer.requireText(arguments, "repo");
                    int number = arguments.path("number").asInt(0);
                    if (number <= 0) {
                        throw new IllegalArgumentException("number 必须大于 0");
                    }
                    JsonNode pr = getJson(
                            "/repos/" + encode(owner) + "/" + encode(repo)
                                    + "/pulls/" + number
                    );
                    ObjectNode result = json.createObjectNode();
                    result.put("number", pr.path("number").asInt());
                    result.put("title", pr.path("title").asText(""));
                    result.put("state", pr.path("state").asText(""));
                    result.put("url", pr.path("html_url").asText(""));
                    result.put("body", pr.path("body").asText(""));
                    return json.writerWithDefaultPrettyPrinter()
                            .writeValueAsString(result);
                }
        );
    }

    private ObjectNode repoSchema() {
        ObjectNode schema = server.objectSchema();
        ObjectNode properties = (ObjectNode) schema.path("properties");
        properties.putObject("owner")
                .put("type", "string")
                .put("description", "GitHub owner 或组织名");
        properties.putObject("repo")
                .put("type", "string")
                .put("description", "仓库名");
        schema.withArray("required").add("owner").add("repo");
        return schema;
    }

    private JsonNode getJson(String path) throws Exception {
        String token = System.getenv("GITHUB_TOKEN");
        if (token == null || token.isBlank()) {
            throw new IllegalStateException("缺少 GITHUB_TOKEN 环境变量");
        }

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(API_BASE + path))
                .header("Accept", "application/vnd.github+json")
                .header("Authorization", "Bearer " + token.trim())
                .header("X-GitHub-Api-Version", "2022-11-28")
                .GET()
                .build();
        HttpResponse<String> response = client.send(
                request,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
        );
        if (response.statusCode() >= 400) {
            throw new IllegalStateException(
                    "GitHub API 返回 "
                            + response.statusCode()
                            + ": "
                            + response.body()
            );
        }
        return json.readTree(response.body());
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
