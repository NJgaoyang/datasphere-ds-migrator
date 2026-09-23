package com.company.migrator.service;

import com.company.migrator.source.DolphinScheduler319Reader.TaskRow;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MigrationServiceTest {
    private final MigrationService service = new MigrationService(null, null, null, null, new ObjectMapper());

    @AfterEach
    void close() {
        service.close();
    }

    @Test
    void pureSqlWorkflowWithMultipleUniqueTasksUsesDevelopmentFlow() {
        TaskRow first = task(100, 1, "SQL");
        TaskRow second = task(101, 1, "SQL");
        assertTrue(service.isDevelopmentOnlyWorkflow(
                List.of(first, second),
                Map.of("100:1", 1L, "101:1", 1L)));
    }

    @Test
    void mixedTaskWorkflowKeepsOrchestrationWorkflow() {
        assertFalse(service.isDevelopmentOnlyWorkflow(
                List.of(task(100, 1, "SQL"), task(101, 1, "SHELL")),
                Map.of("100:1", 1L, "101:1", 1L)));
    }

    @Test
    void sharedSqlTaskKeepsOrchestrationWorkflow() {
        assertFalse(service.isDevelopmentOnlyWorkflow(
                List.of(task(100, 1, "SQL")),
                Map.of("100:1", 2L)));
    }

    private TaskRow task(long code, int version, String type) {
        return new TaskRow(900, 1, code, version, "task-" + code, type,
                "{}", null, 800, 0, 0, "default", null);
    }
}
