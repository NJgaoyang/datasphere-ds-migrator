package com.company.migrator.common;

import java.util.List;
import java.util.Set;

public final class SqlDependencyModels {
    private SqlDependencyModels() { }

    public record SqlTaskLineage(
            long taskCode, int taskVersion, String taskName,
            long sourceWorkflowCode, String sourceWorkflowName,
            long targetFileId, long targetWorkflowId,
            Set<String> inputTables, Set<String> outputTables) { }

    public record SqlDependencyRelation(
            String key, String tableName, String scope, String status, String message,
            long producerTaskCode, String producerTaskName, long producerFileId,
            long producerWorkflowCode, String producerWorkflowName, long producerTargetWorkflowId,
            long consumerTaskCode, String consumerTaskName, long consumerFileId,
            long consumerWorkflowCode, String consumerWorkflowName, long consumerTargetWorkflowId) { }

    public record SqlDependencyScanResult(
            int taskCount, int relationCount, int readyCount, int existingCount, int conflictCount,
            int sameWorkflowCount, int crossWorkflowCount,
            List<SqlTaskLineage> tasks, List<SqlDependencyRelation> relations, List<String> externalTables) { }

    public record SqlDependencyApplyRequest(Boolean sameWorkflow, Boolean crossWorkflow) {
        public boolean sameWorkflowValue() { return sameWorkflow == null || sameWorkflow; }
        public boolean crossWorkflowValue() { return crossWorkflow == null || crossWorkflow; }
    }

    public record SqlDependencyApplyFailure(String scope, String objectName, String message) { }

    public record SqlDependencyApplyResult(
            boolean success, int appliedSameWorkflow, int appliedCrossWorkflow, int skipped,
            int failureCount, List<SqlDependencyApplyFailure> failures, String message) { }
}
