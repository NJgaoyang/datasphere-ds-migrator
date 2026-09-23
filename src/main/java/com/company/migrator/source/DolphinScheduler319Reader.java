package com.company.migrator.source;

import com.company.migrator.common.MigrationModels.Settings;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.sql.*;
import java.util.*;

@Component
public class DolphinScheduler319Reader {
    public static final String EXPECTED_VERSION = "3.1.9";
    private static final Set<String> REQUIRED_TABLES = Set.of(
            "t_ds_project", "t_ds_process_definition", "t_ds_task_definition", "t_ds_task_definition_log",
            "t_ds_process_task_relation", "t_ds_schedules", "t_ds_datasource");

    private final ObjectMapper mapper;

    public DolphinScheduler319Reader(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public Connection open(Settings settings) throws SQLException {
        try {
            return DriverManager.getConnection(settings.sourceJdbcUrl(), settings.sourceUsername(), settings.sourcePassword());
        } catch (SQLException ex) {
            if (!isUnknownDatabase(ex)) throw ex;
            String database = discoverSourceDatabase(settings);
            return DriverManager.getConnection(withDatabase(settings.sourceJdbcUrl(), database),
                    settings.sourceUsername(), settings.sourcePassword());
        }
    }

    String discoverSourceDatabase(Settings settings) throws SQLException {
        String serverUrl = withDatabase(settings.sourceJdbcUrl(), "information_schema");
        List<String> candidates = new ArrayList<>();
        String placeholders = String.join(",", Collections.nCopies(REQUIRED_TABLES.size(), "?"));
        String sql = "SELECT table_schema FROM information_schema.tables WHERE table_name IN (" + placeholders + ") " +
                "GROUP BY table_schema HAVING COUNT(DISTINCT table_name)=? ORDER BY table_schema";
        try (Connection c = DriverManager.getConnection(serverUrl, settings.sourceUsername(), settings.sourcePassword());
             PreparedStatement ps = c.prepareStatement(sql)) {
            int index = 1;
            for (String table : REQUIRED_TABLES) ps.setString(index++, table);
            ps.setInt(index, REQUIRED_TABLES.size());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) candidates.add(rs.getString(1));
            }
        }
        if (candidates.size() == 1) return candidates.getFirst();
        if (candidates.isEmpty()) {
            throw new SQLException("配置的 DolphinScheduler 数据库不存在，且同一 MySQL 实例未发现完整的 3.1.9 元数据库");
        }
        throw new SQLException("配置的 DolphinScheduler 数据库不存在，发现多个候选库：" + String.join(", ", candidates) + "，请明确配置库名");
    }

    static String withDatabase(String jdbcUrl, String database) {
        if (jdbcUrl == null || !jdbcUrl.startsWith("jdbc:mysql://")) return jdbcUrl;
        int authorityStart = "jdbc:mysql://".length();
        int query = jdbcUrl.indexOf('?', authorityStart);
        int slash = jdbcUrl.indexOf('/', authorityStart);
        String suffix = query >= 0 ? jdbcUrl.substring(query) : "";
        String authority;
        if (slash >= 0 && (query < 0 || slash < query)) authority = jdbcUrl.substring(0, slash);
        else authority = query >= 0 ? jdbcUrl.substring(0, query) : jdbcUrl;
        return authority + "/" + database + suffix;
    }

    private boolean isUnknownDatabase(SQLException ex) {
        for (SQLException cursor = ex; cursor != null; cursor = cursor.getNextException()) {
            if (cursor.getErrorCode() == 1049) return true;
            String message = cursor.getMessage();
            if (message != null && message.toLowerCase(Locale.ROOT).contains("unknown database")) return true;
        }
        return false;
    }

    public SourceCheck test(Settings settings) {
        try (Connection c = open(settings)) {
            Set<String> found = new HashSet<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT table_name FROM information_schema.tables WHERE table_schema=DATABASE()")) {
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) found.add(rs.getString(1).toLowerCase(Locale.ROOT));
                }
            }
            List<String> missing = REQUIRED_TABLES.stream().filter(t -> !found.contains(t)).sorted().toList();
            if (!missing.isEmpty()) return new SourceCheck(false, "缺少 DolphinScheduler 3.1.9 核心表：" + String.join(", ", missing), "UNKNOWN");
            String database = c.getCatalog();
            String suffix = database == null || database.isBlank() ? "" : "（" + database + "）";
            return new SourceCheck(true, "DolphinScheduler 元数据库连接成功" + suffix + "，3.1.9 核心表结构存在", EXPECTED_VERSION);
        } catch (Exception ex) {
            return new SourceCheck(false, rootMessage(ex), "UNKNOWN");
        }
    }

    public Snapshot read(Settings settings) throws SQLException {
        try (Connection c = open(settings)) {
            return new Snapshot(readProjects(c), readWorkflows(c), readTasks(c), readEdges(c), readSchedules(c));
        }
    }

    private List<ProjectRow> readProjects(Connection c) throws SQLException {
        String sql = "SELECT code,name,description FROM t_ds_project ORDER BY name";
        List<ProjectRow> rows = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) rows.add(new ProjectRow(rs.getLong("code"), rs.getString("name"), rs.getString("description")));
        }
        return rows;
    }

    private List<WorkflowRow> readWorkflows(Connection c) throws SQLException {
        String sql = "SELECT code,version,name,description,project_code,release_state,global_params,locations FROM t_ds_process_definition ORDER BY project_code,name";
        List<WorkflowRow> rows = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) rows.add(new WorkflowRow(
                    rs.getLong("code"), rs.getInt("version"), rs.getString("name"), rs.getString("description"),
                    rs.getLong("project_code"), rs.getInt("release_state"),
                    rs.getString("global_params"), rs.getString("locations")));
        }
        return rows;
    }

    List<TaskRow> readTasks(Connection c) throws SQLException {
        String taskSource = "(" +
                "SELECT code,version,name,task_type,task_params,description,project_code," +
                "fail_retry_times,fail_retry_interval,worker_group,environment_code FROM t_ds_task_definition_log " +
                "UNION ALL " +
                "SELECT current_task.code,current_task.version,current_task.name,current_task.task_type,current_task.task_params," +
                "current_task.description,current_task.project_code,current_task.fail_retry_times,current_task.fail_retry_interval," +
                "current_task.worker_group,current_task.environment_code FROM t_ds_task_definition current_task " +
                "WHERE NOT EXISTS (SELECT 1 FROM t_ds_task_definition_log task_log " +
                "WHERE task_log.code=current_task.code AND task_log.version=current_task.version)" +
                ") t";
        String sql = "SELECT DISTINCT r.process_definition_code,r.process_definition_version," +
                "t.code,t.version,t.name,t.task_type,t.task_params,t.description,t.project_code," +
                "t.fail_retry_times,t.fail_retry_interval,t.worker_group,t.environment_code " +
                "FROM t_ds_process_task_relation r " +
                "JOIN t_ds_process_definition p ON p.code=r.process_definition_code AND p.version=r.process_definition_version " +
                "JOIN " + taskSource + " ON t.code=r.post_task_code AND t.version=r.post_task_version " +
                "WHERE r.post_task_code<>0 ORDER BY r.process_definition_code,t.name";
        List<TaskRow> rows = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) rows.add(new TaskRow(
                    rs.getLong("process_definition_code"), rs.getInt("process_definition_version"),
                    rs.getLong("code"), rs.getInt("version"), rs.getString("name"), rs.getString("task_type"),
                    rs.getString("task_params"), rs.getString("description"), rs.getLong("project_code"),
                    rs.getInt("fail_retry_times"), rs.getInt("fail_retry_interval"),
                    rs.getString("worker_group"), nullableLong(rs, "environment_code")));
        }
        return rows;
    }

    private List<EdgeRow> readEdges(Connection c) throws SQLException {
        String sql = "SELECT r.process_definition_code,r.process_definition_version,r.pre_task_code,r.pre_task_version," +
                "r.post_task_code,r.post_task_version,r.condition_type,r.condition_params " +
                "FROM t_ds_process_task_relation r " +
                "JOIN t_ds_process_definition p ON p.code=r.process_definition_code AND p.version=r.process_definition_version " +
                "ORDER BY r.process_definition_code,r.id";
        List<EdgeRow> rows = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) rows.add(new EdgeRow(
                    rs.getLong("process_definition_code"), rs.getInt("process_definition_version"),
                    rs.getLong("pre_task_code"), rs.getInt("pre_task_version"),
                    rs.getLong("post_task_code"), rs.getInt("post_task_version"),
                    rs.getString("condition_type"), rs.getString("condition_params")));
        }
        return rows;
    }

    private List<ScheduleRow> readSchedules(Connection c) throws SQLException {
        String sql = "SELECT id,process_definition_code,start_time,end_time,timezone_id,crontab,failure_strategy," +
                "release_state,warning_type,warning_group_id,worker_group,environment_code FROM t_ds_schedules ORDER BY id";
        List<ScheduleRow> rows = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) rows.add(new ScheduleRow(
                    rs.getLong("id"), rs.getLong("process_definition_code"), rs.getTimestamp("start_time"),
                    rs.getTimestamp("end_time"), rs.getString("timezone_id"), rs.getString("crontab"),
                    rs.getString("failure_strategy"), rs.getInt("release_state"), rs.getString("warning_type"),
                    nullableLong(rs, "warning_group_id"), rs.getString("worker_group"), nullableLong(rs, "environment_code")));
        }
        return rows;
    }

    public JsonNode parseTaskParams(TaskRow task) {
        try {
            return mapper.readTree(task.taskParams() == null || task.taskParams().isBlank() ? "{}" : task.taskParams());
        } catch (Exception ex) {
            return mapper.createObjectNode();
        }
    }

    private Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private String rootMessage(Throwable ex) {
        Throwable cursor = ex;
        while (cursor.getCause() != null) cursor = cursor.getCause();
        String message = cursor.getMessage();
        return message == null || message.isBlank() ? cursor.getClass().getSimpleName() : message;
    }

    public record SourceCheck(boolean success, String message, String version) { }
    public record ProjectRow(long code, String name, String description) { }
    public record WorkflowRow(long code, int version, String name, String description, long projectCode,
                              int releaseState, String globalParams, String locations) { }
    public record TaskRow(long workflowCode, int workflowVersion, long code, int version, String name,
                          String taskType, String taskParams, String description, long projectCode,
                          int retryTimes, int retryIntervalMinutes, String workerGroup, Long environmentCode) { }
    public record EdgeRow(long workflowCode, int workflowVersion, long preTaskCode, int preTaskVersion,
                          long postTaskCode, int postTaskVersion, String conditionType, String conditionParams) { }
    public record ScheduleRow(long id, long workflowCode, Timestamp startTime, Timestamp endTime,
                              String timezone, String crontab, String failureStrategy, int releaseState,
                              String warningType, Long warningGroupId, String workerGroup, Long environmentCode) { }
    public record Snapshot(List<ProjectRow> projects, List<WorkflowRow> workflows, List<TaskRow> tasks,
                           List<EdgeRow> edges, List<ScheduleRow> schedules) { }
}
