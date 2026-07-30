package com.example.agent.hooks;

import com.example.agent.permissions.FileOperation;
import com.example.agent.permissions.FilePermissionService;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * 权限不再写在工具实现中，而是作为 PreToolUse 扩展注册。
 */
public final class PermissionHooks {
    public static final String RESOLVED_PATH = "permission.resolvedPath";

    private static final Map<String, FileOperation> OPERATIONS = Map.of(
            "list_files", FileOperation.LIST,
            "search_code", FileOperation.SEARCH,
            "read_file", FileOperation.READ,
            "create_file", FileOperation.CREATE,
            "edit_file", FileOperation.EDIT,
            "delete_file", FileOperation.DELETE
    );

    private PermissionHooks() {
    }

    public static void register_hooks(
            HookRegistry registry,
            FilePermissionService permissions
    ) {
        registry.register_hooks(
                HookEvent.PRE_TOOL_USE,
                context -> validateFileTool(context, permissions)
        );
    }

    private static HookResult validateFileTool(
            HookContext context,
            FilePermissionService permissions
    ) throws Exception {
        FileOperation operation = OPERATIONS.get(context.toolName());
        if (operation == null) return HookResult.allow("无需文件权限检查");

        String relativePath = context.arguments().path("path").asText(".");
        if (operation == FileOperation.CREATE) {
            permissions.checkCreateContent(
                    context.arguments().path("content").asText(null)
            );
        }
        if (operation == FileOperation.EDIT) {
            String oldText = context.arguments().path("oldText").asText(null);
            String newText = context.arguments().path("newText").asText(null);
            permissions.checkReplacement(oldText, newText);
        }

        Path path = permissions.check(relativePath, operation);
        if (operation == FileOperation.EDIT) {
            validateEditedSize(path, context, permissions);
        }
        context.put(RESOLVED_PATH, path);
        context.put("permission.operation", operation);
        return HookResult.allow("三道闸门的前置权限检查通过");
    }

    private static void validateEditedSize(
            Path path,
            HookContext context,
            FilePermissionService permissions
    ) throws Exception {
        String content = Files.readString(path, StandardCharsets.UTF_8);
        String oldText = context.arguments().path("oldText").asText();
        String newText = context.arguments().path("newText").asText();
        if (content.contains(oldText)) {
            permissions.checkProposedContent(content.replace(oldText, newText));
        }
    }
}
