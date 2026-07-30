package com.example.agent.permissions;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 第三道闸门：写入和删除先进入待批准队列，只有一次性令牌获批后才执行。
 */
public final class HumanApprovalGate {
    private static final Duration APPROVAL_TTL = Duration.ofMinutes(10);

    private final Map<String, PendingAction> pending = new ConcurrentHashMap<>();

    public ApprovalRequest request(
            FileOperation operation,
            String path,
            String preview,
            ApprovedAction action
    ) {
        if (!operation.isMutation()) {
            throw new IllegalArgumentException("只有变更操作需要人工批准");
        }

        cleanupExpired();
        String id = UUID.randomUUID().toString();
        Instant expiresAt = Instant.now().plus(APPROVAL_TTL);
        pending.put(id, new PendingAction(operation, path, expiresAt, action));
        return new ApprovalRequest(
                id,
                "approval_required",
                operation.name().toLowerCase(),
                path,
                preview,
                expiresAt.toString(),
                new String[]{
                        "闸门 1：工作区边界已通过",
                        "闸门 2：文件策略已通过",
                        "闸门 3：等待人工批准"
                }
        );
    }

    public ApprovalResult approve(String id) throws Exception {
        PendingAction action = pending.remove(id);
        if (action == null) {
            throw new IllegalArgumentException("审批不存在、已使用或已过期");
        }
        if (Instant.now().isAfter(action.expiresAt())) {
            throw new IllegalArgumentException("审批已过期");
        }

        String detail = action.action().execute();
        return new ApprovalResult(
                "approved",
                action.operation().name().toLowerCase(),
                action.path(),
                detail
        );
    }

    public ApprovalResult reject(String id) {
        PendingAction action = pending.remove(id);
        if (action == null) {
            throw new IllegalArgumentException("审批不存在、已处理或已过期");
        }
        return new ApprovalResult(
                "rejected",
                action.operation().name().toLowerCase(),
                action.path(),
                "用户拒绝了本次文件操作"
        );
    }

    private void cleanupExpired() {
        Instant now = Instant.now();
        pending.entrySet().removeIf(entry -> now.isAfter(entry.getValue().expiresAt()));
    }

    @FunctionalInterface
    public interface ApprovedAction {
        String execute() throws Exception;
    }

    private record PendingAction(
            FileOperation operation,
            String path,
            Instant expiresAt,
            ApprovedAction action
    ) {
    }

    public record ApprovalRequest(
            String approvalId,
            String status,
            String operation,
            String path,
            String preview,
            String expiresAt,
            String[] gates
    ) {
    }

    public record ApprovalResult(
            String status,
            String operation,
            String path,
            String detail
    ) {
    }
}
