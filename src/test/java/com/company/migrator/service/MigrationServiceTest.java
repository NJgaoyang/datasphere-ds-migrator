package com.company.migrator.service;

import com.company.migrator.source.DolphinScheduler319Reader.TaskRow;
import com.company.migrator.source.DolphinScheduler319Reader.WorkflowRow;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

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

    @Test
    void migratesGlobalAndLocalParametersAsKeyValuePairs() {
        WorkflowRow workflow = new WorkflowRow(900, 1, "wf", null, 800, 1,
                "[{\"prop\":\"biz_date\",\"value\":\"20261004\"}]", null);
        TaskRow task = new TaskRow(900, 1, 100, 1, "task-100", "SQL",
                "{\"localParams\":[{\"key\":\"region\",\"value\":\"JP\"}]}", null, 800, 0, 0, "default", null);
        assertEquals(List.of(
                Map.of("key", "biz_date", "value", "20261004"),
                Map.of("key", "region", "value", "JP")
        ), service.mergedParams(workflow, task));
    }

    @Test
    void acceptsObjectStyleParameterMap() throws Exception {
        var values = new java.util.LinkedHashMap<String, String>();
        service.mergeParamArray(new ObjectMapper().readTree("{\"region\":\"JP\",\"batch_no\":\"42\"}"), values);
        assertEquals(Map.of("region", "JP", "batch_no", "42"), values);
    }

    private WorkflowRow workflow() {
        return new WorkflowRow(900, 1, "wf", null, 800, 1, null, null);
    }

    private TaskRow task(long code, int version, String type) {
        return new TaskRow(900, 1, code, version, "task-" + code, type,
                "{}", null, 800, 0, 0, "default", null);
    }
}
