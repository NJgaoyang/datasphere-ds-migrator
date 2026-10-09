package com.company.migrator.common;

import java.util.List;

public final class WorkflowControlModels {
    private WorkflowControlModels() { }

    public record WorkflowControlRequest(List<Long> workflowIds) {
        public List<Long> workflowIdsValue() { return workflowIds == null ? List.of() : workflowIds; }
    }

    public record WorkflowControlItem(
            long workflowId,
            String workflowCode,
            String name,
            String projectFolder,
            String definitionStatus,
            boolean scheduleConfigured,
            boolean scheduleEnabled,
            String cronExpression,
            Boolean preflightReady,
            String preflightMessage,
            String message) { }

    public record WorkflowControlSnapshot(
            int totalCount,
            int publishedCount,
            int onlineCount,
            int readyCount,
            List<WorkflowControlItem> workflows) { }

    public record WorkflowActionItem(
            long workflowId,
            String name,
            boolean success,
            String status,
            String message,
            String instanceId) { }

    public record WorkflowActionResult(
            String action,
            int requestedCount,
            int successCount,
            int failureCount,
            List<WorkflowActionItem> items,
            String message) { }
}
