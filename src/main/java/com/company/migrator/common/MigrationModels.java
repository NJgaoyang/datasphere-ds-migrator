package com.company.migrator.common;

import java.time.LocalDateTime;

public final class MigrationModels {
    private MigrationModels() { }

    public record Settings(
            String sourceJdbcUrl, String sourceUsername, String sourcePassword,
            String targetBaseUrl, String targetToken, String targetOperator) { }

    public record SettingsView(
            String sourceJdbcUrl, String sourceUsername, boolean sourcePasswordConfigured,
            String targetBaseUrl, boolean targetTokenConfigured, String targetOperator) { }

    public record SettingsUpdate(
            String sourceJdbcUrl, String sourceUsername, String sourcePassword,
            String targetBaseUrl, String targetToken, String targetOperator) { }

    public record ConnectionTest(boolean success, String message, String version) { }

    public record StartMigrationRequest(Long analysisRunId, Boolean dryRun) {
        public boolean dryRunValue() { return dryRun == null || dryRun; }
    }

    public record RunView(
            long id, String operation, String status, String phase, int progress,
            String sourceVersion, boolean dryRun, int totalObjects, int processedObjects,
            int successCount, int warningCount, int failureCount, String message,
            LocalDateTime createdAt, LocalDateTime startedAt, LocalDateTime finishedAt) { }

    public record ItemView(
            long id, long runId, String objectType, String sourceCode, Integer sourceVersion,
            String objectName, String taskType, String status, int progress,
            String targetType, String targetId, String message, String payloadJson,
            LocalDateTime createdAt, LocalDateTime updatedAt) { }

    public record IssueView(
            long id, long runId, Long itemId, String severity, String issueCode,
            String objectType, String sourceCode, String objectName, String message,
            String detail, String status, LocalDateTime createdAt, LocalDateTime resolvedAt) { }

    public record EventView(
            long id, long runId, String level, String phase, String message, LocalDateTime createdAt) { }

    public record OperationResult(boolean success, String message, Long runId) { }
}
