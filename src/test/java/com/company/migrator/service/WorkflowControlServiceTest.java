package com.company.migrator.service;

import com.company.migrator.common.MigrationModels.Settings;
import com.company.migrator.common.WorkflowControlModels.WorkflowControlRequest;
import com.company.migrator.target.DataSphereClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class WorkflowControlServiceTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void oneClickOnlinePublishesMappedDevelopmentTaskThenWorkflowAndScheduler() {
        JdbcTemplate jdbc = jdbc("control_online");
        map(jdbc, "WORKFLOW", "100", "WORKFLOW", "11", "wf_a");
        map(jdbc, "TASK", "200", "DEV_FILE", "21", "sql_a");
        SettingService settings = settings();
        DataSphereClient target = mock(DataSphereClient.class);
        Settings s = targetSettings();
        when(target.workflow(s, 11L)).thenReturn(workflow(11, "wf_a", "DRAFT", 21L));
        when(target.file(s, 21L)).thenReturn(file(21, "sql_a", "DRAFT", "OFFLINE"));
        when(target.fileSchedule(s, 21L)).thenReturn(schedule(0, false));
        when(target.workflowStatus(s, 11L)).thenReturn("DRAFT");
        when(target.workflowSchedule(s, 11L)).thenReturn(schedule(31, false));
        when(target.workflowPreflight(s, 11L)).thenReturn(preflight(true, "通过"));
        WorkflowControlService service = new WorkflowControlService(jdbc, settings, target);

        var result = service.online(new WorkflowControlRequest(List.of(11L)));

        assertEquals(1, result.successCount());
        assertEquals("ONLINE", result.items().getFirst().status());
        var ordered = inOrder(target);
        ordered.verify(target).workflow(s, 11L);
        ordered.verify(target).file(s, 21L);
        ordered.verify(target).fileSchedule(s, 21L);
        ordered.verify(target).onlineFile(s, 21L);
        ordered.verify(target).publishFile(s, 21L);
        ordered.verify(target).validateWorkflow(s, 11L);
        ordered.verify(target).workflowStatus(s, 11L);
        ordered.verify(target).publishWorkflow(s, 11L);
        ordered.verify(target).workflowSchedule(s, 11L);
        ordered.verify(target).workflowPreflight(s, 11L);
        ordered.verify(target).onlineWorkflow(s, 11L);
    }

    @Test
    void blocksOnlineBeforeLifecycleChangeWhenLegacyDevelopmentScheduleEnabled() {
        JdbcTemplate jdbc = jdbc("control_legacy_block");
        map(jdbc, "WORKFLOW", "100", "WORKFLOW", "11", "wf_a");
        map(jdbc, "TASK", "200", "DEV_FILE", "21", "sql_a");
        SettingService settings = settings();
        DataSphereClient target = mock(DataSphereClient.class);
        Settings s = targetSettings();
        when(target.workflow(s, 11L)).thenReturn(workflow(11, "wf_a", "DRAFT", 21L));
        when(target.file(s, 21L)).thenReturn(file(21, "sql_a", "DRAFT", "OFFLINE"));
        when(target.fileSchedule(s, 21L)).thenReturn(schedule(41, true));
        WorkflowControlService service = new WorkflowControlService(jdbc, settings, target);

        var result = service.online(new WorkflowControlRequest(List.of(11L)));

        assertEquals(0, result.successCount());
        assertEquals(1, result.failureCount());
        assertTrue(result.items().getFirst().message().contains("旧 dev_file_schedule"));
        verify(target, never()).onlineFile(any(), anyLong());
        verify(target, never()).publishFile(any(), anyLong());
        verify(target, never()).publishWorkflow(any(), anyLong());
        verify(target, never()).onlineWorkflow(any(), anyLong());
    }

    @Test
    void publishesManualOnlyWorkflowWithoutCreatingOrEnablingDefaultSchedule() {
        JdbcTemplate jdbc = jdbc("control_no_schedule");
        map(jdbc, "WORKFLOW", "100", "WORKFLOW", "11", "wf_manual");
        SettingService settings = settings();
        DataSphereClient target = mock(DataSphereClient.class);
        Settings s = targetSettings();
        when(target.workflow(s, 11L)).thenReturn(workflow(11, "wf_manual", "DRAFT"));
        when(target.workflowStatus(s, 11L)).thenReturn("DRAFT");
        when(target.workflowSchedule(s, 11L)).thenReturn(schedule(0, false));
        WorkflowControlService service = new WorkflowControlService(jdbc, settings, target);

        var result = service.online(new WorkflowControlRequest(List.of(11L)));

        assertEquals(1, result.successCount());
        assertEquals("PUBLISHED", result.items().getFirst().status());
        verify(target).publishWorkflow(s, 11L);
        verify(target, never()).onlineWorkflow(any(), anyLong());
    }

    @Test
    void oneClickOfflineOnlyDisablesNativeSchedulerAndKeepsPublishedDefinition() {
        JdbcTemplate jdbc = jdbc("control_offline");
        map(jdbc, "WORKFLOW", "100", "WORKFLOW", "11", "wf_a");
        SettingService settings = settings();
        DataSphereClient target = mock(DataSphereClient.class);
        Settings s = targetSettings();
        when(target.workflow(s, 11L)).thenReturn(workflow(11, "wf_a", "PUBLISHED"));
        when(target.workflowSchedule(s, 11L)).thenReturn(schedule(31, true));
        WorkflowControlService service = new WorkflowControlService(jdbc, settings, target);

        var result = service.offline(new WorkflowControlRequest(List.of(11L)));

        assertEquals(1, result.successCount());
        verify(target).offlineWorkflow(s, 11L);
        verify(target, never()).offlineFile(any(), anyLong());
    }

    @Test
    void manualRunReturnsInstanceAndRejectsUnmappedWorkflowIds() {
        JdbcTemplate jdbc = jdbc("control_run");
        map(jdbc, "WORKFLOW", "100", "WORKFLOW", "11", "wf_a");
        SettingService settings = settings();
        DataSphereClient target = mock(DataSphereClient.class);
        Settings s = targetSettings();
        when(target.workflow(s, 11L)).thenReturn(workflow(11, "wf_a", "PUBLISHED"));
        ObjectNode run = mapper.createObjectNode();
        run.put("instanceId", "wf-inst-001"); run.put("status", "SUBMITTED"); run.put("message", "调度实例已提交");
        when(target.runWorkflow(s, 11L)).thenReturn(run);
        WorkflowControlService service = new WorkflowControlService(jdbc, settings, target);

        var result = service.run(new WorkflowControlRequest(List.of(11L)));
        assertEquals("wf-inst-001", result.items().getFirst().instanceId());
        assertThrows(IllegalArgumentException.class, () -> service.run(new WorkflowControlRequest(List.of(999L))));
    }

    private SettingService settings() {
        SettingService settings = mock(SettingService.class);
        when(settings.get()).thenReturn(targetSettings());
        return settings;
    }

    private ObjectNode workflow(long id, String name, String status, long... fileIds) {
        ObjectNode node = mapper.createObjectNode();
        node.put("id", id); node.put("name", name); node.put("workflowCode", "WF-" + id); node.put("status", status);
        ArrayNode nodes = node.putArray("nodes");
        for (long fileId : fileIds) nodes.addObject().put("devFileId", fileId);
        return node;
    }

    private ObjectNode file(long id, String name, String status, String lifecycle) {
        ObjectNode node = mapper.createObjectNode();
        node.put("id", id); node.put("name", name); node.put("status", status); node.put("lifecycleStatus", lifecycle);
        return node;
    }

    private ObjectNode schedule(long id, boolean enabled) {
        ObjectNode node = mapper.createObjectNode();
        node.put("id", id); node.put("enabled", enabled);
        return node;
    }

    private ObjectNode preflight(boolean ready, String message) {
        ObjectNode node = mapper.createObjectNode();
        node.put("ready", ready); node.put("message", message);
        return node;
    }

    private void map(JdbcTemplate jdbc, String sourceType, String sourceCode, String targetType, String targetId, String targetName) {
        jdbc.update("INSERT INTO migration_object_map(source_type,source_code,source_version,target_type,target_id,target_name) VALUES(?,?,1,?,?,?)",
                sourceType, sourceCode, targetType, targetId, targetName);
    }

    private JdbcTemplate jdbc(String db) {
        DriverManagerDataSource dataSource = new DriverManagerDataSource("jdbc:h2:mem:" + db + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE migration_object_map (source_type VARCHAR(40),source_code VARCHAR(128),source_version INT,target_type VARCHAR(40),target_id VARCHAR(128),target_name VARCHAR(255))");
        return jdbc;
    }

    private Settings targetSettings() {
        return new Settings("jdbc:mysql://source", "source", "secret", "http://datasphere", "admin", "password");
    }
}
