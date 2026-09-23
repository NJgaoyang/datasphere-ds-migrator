package com.company.migrator.source;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DolphinScheduler319ReaderTest {

    @Test
    void replacesOnlyDatabaseAndPreservesMysqlParameters() {
        assertEquals(
                "jdbc:mysql://172.17.0.12:3306/dolphinscheduler?useUnicode=true&serverTimezone=Asia/Shanghai",
                DolphinScheduler319Reader.withDatabase(
                        "jdbc:mysql://172.17.0.12:3306/old_db?useUnicode=true&serverTimezone=Asia/Shanghai",
                        "dolphinscheduler"));
        assertEquals(
                "jdbc:mysql://172.17.0.12:3306/information_schema?useSSL=false",
                DolphinScheduler319Reader.withDatabase(
                        "jdbc:mysql://172.17.0.12:3306?useSSL=false",
                        "information_schema"));
    }


    @Test
    void readsHistoricalTaskCodesAcrossWorkflowVersions() throws Exception {
        String url = "jdbc:h2:mem:history;MODE=MySQL;DB_CLOSE_DELAY=-1";
        try (Connection c = DriverManager.getConnection(url, "sa", "")) {
            execute(c, "CREATE TABLE t_ds_process_task_relation(" +
                    "process_definition_code BIGINT, process_definition_version INT, post_task_code BIGINT, post_task_version INT)");
            execute(c, "INSERT INTO t_ds_process_task_relation VALUES (900,1,100,1),(900,2,100,2),(900,1,101,1),(901,1,200,1)");
        }
        var settings = new com.company.migrator.common.MigrationModels.Settings(url, "sa", "", "", "", "");
        var codes = new DolphinScheduler319Reader(new ObjectMapper()).historicalTaskCodes(settings, java.util.Set.of(900L));
        assertEquals(java.util.Set.of(100L, 101L), codes);
    }

    @Test
    void fallsBackToCurrentTaskDefinitionWhenLogVersionIsMissing() throws Exception {
        try (Connection c = database("fallback")) {
            seedWorkflow(c);
            execute(c, "INSERT INTO t_ds_task_definition VALUES " +
                    "(100,3,'current-task','SQL','{}','current',10,1,2,'default',NULL)");

            var rows = new DolphinScheduler319Reader(new ObjectMapper()).readTasks(c);

            assertEquals(1, rows.size());
            assertEquals("current-task", rows.getFirst().name());
            assertEquals(3, rows.getFirst().version());
        }
    }

    @Test
    void prefersHistoricalTaskLogWhenExactVersionExists() throws Exception {
        try (Connection c = database("log-preferred")) {
            seedWorkflow(c);
            execute(c, "INSERT INTO t_ds_task_definition VALUES " +
                    "(100,3,'current-task','SQL','{}','current',10,1,2,'default',NULL)");
            execute(c, "INSERT INTO t_ds_task_definition_log VALUES " +
                    "(100,3,'logged-task','SQL','{}','logged',10,4,5,'worker-a',99)");

            var rows = new DolphinScheduler319Reader(new ObjectMapper()).readTasks(c);

            assertEquals(1, rows.size());
            assertEquals("logged-task", rows.getFirst().name());
            assertEquals(4, rows.getFirst().retryTimes());
            assertEquals(99L, rows.getFirst().environmentCode());
        }
    }

    private Connection database(String name) throws Exception {
        Connection c = DriverManager.getConnection("jdbc:h2:mem:" + name + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        execute(c, "CREATE TABLE t_ds_process_definition(code BIGINT, version INT)");
        execute(c, "CREATE TABLE t_ds_process_task_relation(" +
                "process_definition_code BIGINT, process_definition_version INT, post_task_code BIGINT, post_task_version INT)");
        String taskColumns = "(code BIGINT, version INT, name VARCHAR(200), task_type VARCHAR(50), task_params CLOB, " +
                "description CLOB, project_code BIGINT, fail_retry_times INT, fail_retry_interval INT, worker_group VARCHAR(200), environment_code BIGINT)";
        execute(c, "CREATE TABLE t_ds_task_definition " + taskColumns);
        execute(c, "CREATE TABLE t_ds_task_definition_log " + taskColumns);
        return c;
    }

    private void seedWorkflow(Connection c) throws Exception {
        execute(c, "INSERT INTO t_ds_process_definition VALUES (900,7)");
        execute(c, "INSERT INTO t_ds_process_task_relation VALUES (900,7,100,3)");
    }

    private void execute(Connection c, String sql) throws Exception {
        try (Statement st = c.createStatement()) {
            st.execute(sql);
        }
    }
}
