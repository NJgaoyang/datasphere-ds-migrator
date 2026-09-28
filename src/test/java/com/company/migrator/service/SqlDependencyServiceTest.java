package com.company.migrator.service;

import com.company.migrator.common.MigrationModels.Settings;
import com.company.migrator.common.SqlDependencyModels.SqlDependencyApplyRequest;
import com.company.migrator.source.DolphinScheduler319Reader;
import com.company.migrator.source.DolphinScheduler319Reader.Snapshot;
import com.company.migrator.source.DolphinScheduler319Reader.TaskRow;
import com.company.migrator.source.DolphinScheduler319Reader.WorkflowRow;
import com.company.migrator.target.DataSphereClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SqlDependencyServiceTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void infersAndAppliesSameWorkflowDagEdge() throws Exception {
        Fixture f = fixture("same_workflow");
        WorkflowRow workflow = workflow(100L, "dwd_flow");
        TaskRow producer = task(100L, 1001L, "build_dwd");
        TaskRow consumer = task(100L, 1002L, "build_dws");
        when(f.source.read(f.settings)).thenReturn(new Snapshot(List.of(), List.of(workflow), List.of(producer, consumer), List.of(), List.of()));
        map(f.jdbc, "TASK", "1001", 1, "DEV_FILE", "21", "build_dwd");
        map(f.jdbc, "TASK", "1002", 1, "DEV_FILE", "22", "build_dws");
        map(f.jdbc, "WORKFLOW", "100", 1, "WORKFLOW", "11", "dwd_flow");
        when(f.target.file(f.settings, 21L)).thenReturn(file(21, "INSERT INTO dwd.dwd_order SELECT * FROM ods.ods_order"));
        when(f.target.file(f.settings, 22L)).thenReturn(file(22, "INSERT INTO dws.dws_order SELECT * FROM dwd.dwd_order"));
        JsonNode targetWorkflow = workflowJson(11, "wf-100", "DRAFT", 21, "n21", 22, "n22");
        when(f.target.workflow(f.settings, 11L)).thenReturn(targetWorkflow);
        when(f.target.validateWorkflow(f.settings, 11L)).thenReturn(mapper.createObjectNode());

        var scan = f.service.scan();
        assertEquals(1, scan.relationCount());
        assertEquals("SAME_WORKFLOW", scan.relations().getFirst().scope());
        assertEquals("READY", scan.relations().getFirst().status());
        assertEquals("dwd.dwd_order", scan.relations().getFirst().tableName());

        var result = f.service.apply(new SqlDependencyApplyRequest(true, false));
        assertTrue(result.success());
        assertEquals(1, result.appliedSameWorkflow());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
        verify(f.target).updateWorkflow(eq(f.settings), eq(11L), payload.capture());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> edges = (List<Map<String, Object>>) payload.getValue().get("edges");
        assertEquals(1, edges.size());
        assertEquals("n21", edges.getFirst().get("sourceNodeCode"));
        assertEquals("n22", edges.getFirst().get("targetNodeCode"));
    }

    @Test
    void appliesCrossWorkflowAsDisabledNativeDependency() throws Exception {
        Fixture f = fixture("cross_workflow");
        WorkflowRow upstream = workflow(100L, "dwd_flow");
        WorkflowRow downstream = workflow(200L, "dws_flow");
        TaskRow producer = task(100L, 1001L, "build_dwd");
        TaskRow consumer = task(200L, 2001L, "build_dws");
        when(f.source.read(f.settings)).thenReturn(new Snapshot(List.of(), List.of(upstream, downstream), List.of(producer, consumer), List.of(), List.of()));
        map(f.jdbc, "TASK", "1001", 1, "DEV_FILE", "21", "build_dwd");
        map(f.jdbc, "TASK", "2001", 1, "DEV_FILE", "31", "build_dws");
        map(f.jdbc, "WORKFLOW", "100", 1, "WORKFLOW", "11", "dwd_flow");
        map(f.jdbc, "WORKFLOW", "200", 1, "WORKFLOW", "12", "dws_flow");
        when(f.target.file(f.settings, 21L)).thenReturn(file(21, "INSERT INTO dwd.dwd_order SELECT * FROM ods.ods_order"));
        when(f.target.file(f.settings, 31L)).thenReturn(file(31, "INSERT INTO dws.dws_order SELECT * FROM dwd.dwd_order"));
        when(f.target.workflow(f.settings, 11L)).thenReturn(workflowJson(11, "wf-up", "DRAFT", 21, "n21"));
        when(f.target.workflow(f.settings, 12L)).thenReturn(workflowJson(12, "wf-down", "DRAFT", 31, "n31"));
        when(f.target.workflowCode(f.settings, 11L)).thenReturn("wf-up");
        when(f.target.workflowCode(f.settings, 12L)).thenReturn("wf-down");

        var scan = f.service.scan();
        assertEquals(1, scan.relationCount());
        assertEquals("CROSS_WORKFLOW", scan.relations().getFirst().scope());
        assertEquals("READY", scan.relations().getFirst().status());

        var result = f.service.apply(new SqlDependencyApplyRequest(false, true));
        assertTrue(result.success());
        assertEquals(1, result.appliedCrossWorkflow());
        verify(f.target).saveWorkflowDependency(f.settings, "wf-down", "wf-up", false, 0, 3600);
    }

    @Test
    void doesNotMixSameLeafAcrossQualifiedDatabases() throws Exception {
        Fixture f = fixture("qualified_tables");
        WorkflowRow w1 = workflow(100L, "db1_flow");
        WorkflowRow w2 = workflow(200L, "db2_flow");
        WorkflowRow w3 = workflow(300L, "consumer_flow");
        TaskRow p1 = task(100L, 1001L, "db1_producer");
        TaskRow p2 = task(200L, 2001L, "db2_producer");
        TaskRow c = task(300L, 3001L, "consumer");
        when(f.source.read(f.settings)).thenReturn(new Snapshot(List.of(), List.of(w1, w2, w3), List.of(p1, p2, c), List.of(), List.of()));
        mapTaskAndWorkflow(f, 100L, 1001L, 11L, 21L, "db1_flow", "db1_producer");
        mapTaskAndWorkflow(f, 200L, 2001L, 12L, 22L, "db2_flow", "db2_producer");
        mapTaskAndWorkflow(f, 300L, 3001L, 13L, 23L, "consumer_flow", "consumer");
        when(f.target.file(f.settings, 21L)).thenReturn(file(21, "INSERT INTO db1.same_name SELECT * FROM ods.a"));
        when(f.target.file(f.settings, 22L)).thenReturn(file(22, "INSERT INTO db2.same_name SELECT * FROM ods.b"));
        when(f.target.file(f.settings, 23L)).thenReturn(file(23, "INSERT INTO ads.result SELECT * FROM db1.same_name"));
        when(f.target.workflow(f.settings, 11L)).thenReturn(workflowJson(11, "w1", "DRAFT", 21, "n21"));
        when(f.target.workflow(f.settings, 12L)).thenReturn(workflowJson(12, "w2", "DRAFT", 22, "n22"));
        when(f.target.workflow(f.settings, 13L)).thenReturn(workflowJson(13, "w3", "DRAFT", 23, "n23"));

        var scan = f.service.scan();
        var relation = scan.relations().stream().filter(r -> r.consumerTaskCode() == 3001L).findFirst().orElseThrow();
        assertEquals(1001L, relation.producerTaskCode());
        assertEquals("READY", relation.status());
    }

    private Fixture fixture(String db) {
        DriverManagerDataSource ds = new DriverManagerDataSource("jdbc:h2:mem:" + db + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE migration_object_map (source_type VARCHAR(40),source_code VARCHAR(128),source_version INT,target_type VARCHAR(40),target_id VARCHAR(128),target_name VARCHAR(255))");
        SettingService settingService = mock(SettingService.class);
        Settings settings = new Settings("jdbc:mysql://source", "source", "secret", "http://datasphere", "admin", "password");
        when(settingService.get()).thenReturn(settings);
        DolphinScheduler319Reader source = mock(DolphinScheduler319Reader.class);
        DataSphereClient target = mock(DataSphereClient.class);
        SqlDependencyService service = new SqlDependencyService(jdbc, settingService, source, target, new SqlTableLineageParser());
        return new Fixture(jdbc, settings, source, target, service);
    }

    private WorkflowRow workflow(long code, String name) {
        return new WorkflowRow(code, 1, name, "", 1L, 0, "[]", "[]");
    }

    private TaskRow task(long workflowCode, long code, String name) {
        return new TaskRow(workflowCode, 1, code, 1, name, "SQL", "{}", "", 1L, 0, 0, "default", null);
    }

    private JsonNode file(long id, String sql) {
        var node = mapper.createObjectNode();
        node.put("id", id); node.put("fileType", "SQL"); node.put("content", sql);
        return node;
    }

    private JsonNode workflowJson(long id, String code, String status, Object... fileAndCode) {
        var root = mapper.createObjectNode();
        root.put("id", id); root.put("name", code); root.put("workflowCode", code); root.put("status", status); root.put("description", "");
        var nodes = root.putArray("nodes");
        long nodeId = 1;
        for (int i = 0; i < fileAndCode.length; i += 2) {
            var n = nodes.addObject();
            n.put("id", nodeId++); n.put("name", String.valueOf(fileAndCode[i + 1])); n.put("nodeType", "SQL");
            n.put("devFileId", ((Number) fileAndCode[i]).longValue()); n.put("configJson", "{}"); n.put("x", 0); n.put("y", 0);
            n.put("nodeCode", String.valueOf(fileAndCode[i + 1]));
        }
        root.putArray("edges");
        return root;
    }

    private void mapTaskAndWorkflow(Fixture f, long workflowCode, long taskCode, long workflowId, long fileId, String workflowName, String taskName) {
        map(f.jdbc, "TASK", String.valueOf(taskCode), 1, "DEV_FILE", String.valueOf(fileId), taskName);
        map(f.jdbc, "WORKFLOW", String.valueOf(workflowCode), 1, "WORKFLOW", String.valueOf(workflowId), workflowName);
    }

    private void map(JdbcTemplate jdbc, String sourceType, String sourceCode, int sourceVersion,
                     String targetType, String targetId, String targetName) {
        jdbc.update("INSERT INTO migration_object_map(source_type,source_code,source_version,target_type,target_id,target_name) VALUES(?,?,?,?,?,?)",
                sourceType, sourceCode, sourceVersion, targetType, targetId, targetName);
    }

    private record Fixture(JdbcTemplate jdbc, Settings settings, DolphinScheduler319Reader source,
                           DataSphereClient target, SqlDependencyService service) { }
}
