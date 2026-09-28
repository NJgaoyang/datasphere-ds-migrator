package com.company.migrator.service;

import com.company.migrator.source.DolphinScheduler319Reader.TaskRow;
import com.company.migrator.source.DolphinScheduler319Reader.WorkflowRow;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MigrationServiceTest {
    private final MigrationService service = new MigrationService(null, null, null, null, new ObjectMapper());

    @AfterEach
    void close() {
        service.close();
    }

    @Test
    void pureSqlWorkflowIsSupportedAsNativeWorkflow() {
        WorkflowRow workflow = workflow();
        assertTrue(service.workflowSupported(workflow, List.of(task(100, 1, "SQL"), task(101, 1, "SQL"))));
    }

    @Test
    void mixedSupportedTasksUseNativeWorkflow() {
        WorkflowRow workflow = workflow();
        assertTrue(service.workflowSupported(workflow, List.of(task(100, 1, "SQL"), task(101, 1, "SHELL"), task(102, 1, "PYTHON"))));
    }

    @Test
    void workflowWithoutResolvedTasksIsNotSupported() {
        assertFalse(service.workflowSupported(workflow(), List.of()));
    }

    @Test
    void unsupportedTaskBlocksWorkflowMigration() {
        assertFalse(service.workflowSupported(workflow(), List.of(task(100, 1, "SUB_PROCESS"))));
    }

    private WorkflowRow workflow() {
        return new WorkflowRow(900, 1, "wf", null, 800, 1, null, null);
    }

    private TaskRow task(long code, int version, String type) {
        return new TaskRow(900, 1, code, version, "task-" + code, type,
                "{}", null, 800, 0, 0, "default", null);
    }
}
