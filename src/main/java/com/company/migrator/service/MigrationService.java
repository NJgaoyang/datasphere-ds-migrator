package com.company.migrator.service;

import com.company.migrator.common.MigrationModels.*;
import com.company.migrator.source.DolphinScheduler319Reader;
import com.company.migrator.source.DolphinScheduler319Reader.*;
import com.company.migrator.target.DataSphereClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class MigrationService {
    private static final Set<String> AUTO_TASK_TYPES = Set.of("SQL", "SHELL", "PYTHON", "SEATUNNEL");

    private final JdbcTemplate jdbc;
    private final SettingService settings;
    private final DolphinScheduler319Reader source;
    private final DataSphereClient target;
    private final ObjectMapper mapper;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public MigrationService(JdbcTemplate jdbc, SettingService settings,
                            DolphinScheduler319Reader source, DataSphereClient target,
                            ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.settings = settings;
        this.source = source;
        this.target = target;
        this.mapper = mapper;
    }

    @PreDestroy
    public void close() {
        executor.shutdownNow();
    }

    public long startAnalyze() {
        long runId = createRun("ANALYZE", true, "QUEUED", "WAITING", "等待分析");
        executor.submit(() -> analyze(runId));
        return runId;
    }

    public long startMigration(StartMigrationRequest request) {
        if (request != null && request.analysisRunId() != null) {
            RunView analysis = getRun(request.analysisRunId());
            if (!analysis.status().startsWith("COMPLETED")) {
                throw new IllegalStateException("请先完成分析再执行迁移");
            }
        }
        boolean dryRun = request == null || request.dryRunValue();
        boolean migrateAll = request == null || request.migrateAllValue();
        Set<Long> workflowCodes = request == null ? Set.of() : new LinkedHashSet<>(request.workflowCodesValue());
        if (!migrateAll && workflowCodes.isEmpty()) throw new IllegalArgumentException("至少选择一个工作流");
        long runId = createRun("MIGRATE", dryRun, "QUEUED", "WAITING", dryRun ? "等待试运行" : "等待迁移");
        executor.submit(() -> migrate(runId, dryRun, migrateAll, workflowCodes));
        return runId;
    }

    public void cancel(long runId) {
        jdbc.update("UPDATE migration_run SET cancel_requested=TRUE,message='已请求取消' WHERE id=? AND status IN ('QUEUED','RUNNING')", runId);
        event(runId, "WARN", "CANCEL", "收到取消请求，将在当前对象处理完成后停止");
    }

    public void resolveIssue(long issueId) {
        jdbc.update("UPDATE migration_issue SET status='RESOLVED',resolved_at=CURRENT_TIMESTAMP WHERE id=?", issueId);
    }

    public List<RunView> listRuns() {
        return jdbc.query("SELECT * FROM migration_run ORDER BY id DESC LIMIT 100", (rs, n) -> runRow(rs));
    }

    public RunView getRun(long runId) {
        List<RunView> rows = jdbc.query("SELECT * FROM migration_run WHERE id=?", (rs, n) -> runRow(rs), runId);
        if (rows.isEmpty()) throw new IllegalArgumentException("迁移任务不存在：" + runId);
        return rows.get(0);
    }

    public List<ItemView> items(long runId) {
        return jdbc.query("SELECT * FROM migration_item WHERE run_id=? ORDER BY id",
                (rs, n) -> new ItemView(rs.getLong("id"), rs.getLong("run_id"), rs.getString("object_type"),
                        rs.getString("source_code"), (Integer) rs.getObject("source_version"), rs.getString("object_name"),
                        rs.getString("task_type"), rs.getString("status"), rs.getInt("progress"), rs.getString("target_type"),
                        rs.getString("target_id"), rs.getString("message"), rs.getString("payload_json"),
                        toLocal(rs.getTimestamp("created_at")), toLocal(rs.getTimestamp("updated_at"))), runId);
    }

    public List<IssueView> issues(long runId) {
        return jdbc.query("SELECT * FROM migration_issue WHERE run_id=? ORDER BY CASE severity WHEN 'ERROR' THEN 1 WHEN 'WARN' THEN 2 ELSE 3 END,id",
                (rs, n) -> new IssueView(rs.getLong("id"), rs.getLong("run_id"), nullableLong(rs, "item_id"),
                        rs.getString("severity"), rs.getString("issue_code"), rs.getString("object_type"),
                        rs.getString("source_code"), rs.getString("object_name"), rs.getString("message"),
                        rs.getString("detail"), rs.getString("status"), toLocal(rs.getTimestamp("created_at")),
                        toLocal(rs.getTimestamp("resolved_at"))), runId);
    }

    public List<EventView> events(long runId) {
        return jdbc.query("SELECT * FROM migration_event WHERE run_id=? ORDER BY id DESC LIMIT 200",
                (rs, n) -> new EventView(rs.getLong("id"), rs.getLong("run_id"), rs.getString("level"),
                        rs.getString("phase"), rs.getString("message"), toLocal(rs.getTimestamp("created_at"))), runId);
    }

    private void analyze(long runId) {
        try {
            startRun(runId, "CONNECT_SOURCE", "连接 DolphinScheduler 3.1.9 元数据库");
            Settings s = settings.get();
            SourceCheck check = source.test(s);
            if (!check.success()) throw new IllegalStateException(check.message());
            event(runId, "INFO", "CONNECT_SOURCE", check.message());
            updateRun(runId, 5, "READ_METADATA", "读取项目、工作流、Task、DAG 和调度");
            Snapshot snapshot = source.read(s);
            analyzeSnapshot(runId, snapshot);
        } catch (Exception ex) {
            failRun(runId, ex);
        }
    }

    private void analyzeSnapshot(long runId, Snapshot snapshot) {
        Map<String, TaskRow> uniqueTasks = snapshot.tasks().stream().collect(Collectors.toMap(
                t -> taskKey(t.code(), t.version()), Function.identity(), (a, b) -> a, LinkedHashMap::new));
        int total = snapshot.projects().size() + snapshot.workflows().size() + uniqueTasks.size() + snapshot.schedules().size();
        jdbc.update("UPDATE migration_run SET total_objects=? WHERE id=?", total, runId);
        int processed = 0;

        for (ProjectRow p : snapshot.projects()) {
            checkCancelled(runId);
            insertItem(runId, "PROJECT", String.valueOf(p.code()), 0, p.name(), null,
                    "READY", "将映射为 DataSphere 单项目下一级目录", json(p));
            progress(runId, ++processed, total, "ANALYZE_PROJECT", "分析项目：" + p.name());
        }

        for (WorkflowRow w : snapshot.workflows()) {
            checkCancelled(runId);
            insertItem(runId, "WORKFLOW", String.valueOf(w.code()), w.version(), w.name(), null,
                    "READY", w.releaseState() == 1 ? "源端已上线；目标端仍将保持 Offline" : "源端未上线", json(w));
            progress(runId, ++processed, total, "ANALYZE_WORKFLOW", "分析工作流：" + w.name());
        }

        for (TaskRow t : uniqueTasks.values()) {
            checkCancelled(runId);
            String type = normalizeType(t.taskType());
            boolean auto = AUTO_TASK_TYPES.contains(type);
            long itemId = insertItem(runId, "TASK", String.valueOf(t.code()), t.version(), t.name(), type,
                    auto ? "READY" : "MANUAL_REQUIRED",
                    auto ? "可自动迁移" : "当前版本需人工处理该任务类型", json(t));
            inspectTaskIssues(runId, itemId, t, type, auto);
            progress(runId, ++processed, total, "ANALYZE_TASK", "分析任务：" + t.name());
        }

        Map<Long, Long> scheduleCounts = snapshot.schedules().stream()
                .collect(Collectors.groupingBy(ScheduleRow::workflowCode, Collectors.counting()));
        for (ScheduleRow s : snapshot.schedules()) {
            checkCancelled(runId);
            long itemId = insertItem(runId, "SCHEDULE", String.valueOf(s.id()), 0,
                    "Schedule → " + s.workflowCode(), null, "READY",
                    "Cron: " + s.crontab(), json(s));
            if (scheduleCounts.getOrDefault(s.workflowCode(), 0L) > 1) {
                issue(runId, itemId, "WARN", "MULTIPLE_SCHEDULES", "SCHEDULE", String.valueOf(s.id()),
                        "Schedule → " + s.workflowCode(), "同一工作流存在多个调度，DataSphere 当前仅保留一个 Workflow Schedule",
                        "迁移时默认选择 ID 最小的一条，请人工确认。");
            }
            progress(runId, ++processed, total, "ANALYZE_SCHEDULE", "分析调度：" + s.crontab());
        }

        finishAnalysis(runId, snapshot, uniqueTasks.size());
    }

    private void inspectTaskIssues(long runId, long itemId, TaskRow t, String type, boolean auto) {
        JsonNode params;
        try {
            params = mapper.readTree(t.taskParams() == null || t.taskParams().isBlank() ? "{}" : t.taskParams());
        } catch (Exception ex) {
            issue(runId, itemId, "ERROR", "INVALID_TASK_PARAMS", "TASK", String.valueOf(t.code()), t.name(),
                    "task_params 不是合法 JSON", rootMessage(ex));
            return;
        }
        if (!auto) {
            issue(runId, itemId, "ERROR", "UNSUPPORTED_TASK_TYPE", "TASK", String.valueOf(t.code()), t.name(),
                    "任务类型 " + type + " 暂不自动迁移", unsupportedHint(type));
        }
        if ("SQL".equals(type) && params.path("datasource").asLong(0) > 0) {
            issue(runId, itemId, "WARN", "DATASOURCE_ID_REVIEW", "TASK", String.valueOf(t.code()), t.name(),
                    "SQL Task 使用 DolphinScheduler datasourceId=" + params.path("datasource").asLong(),
                    "若 DataSphere 指向新的 DolphinScheduler 调度集群，需要配置数据源 ID 映射；若仍使用原 3.1.9 集群可原样保留。");
        }
        JsonNode resources = params.path("resourceList");
        if (resources.isArray() && !resources.isEmpty()) {
            issue(runId, itemId, "WARN", "RESOURCE_MIGRATION_REQUIRED", "TASK", String.valueOf(t.code()), t.name(),
                    "任务引用了 DolphinScheduler Resource Center 文件", resources.toString());
        }
        if (t.environmentCode() != null && t.environmentCode() > 0) {
            issue(runId, itemId, "WARN", "ENVIRONMENT_REVIEW", "TASK", String.valueOf(t.code()), t.name(),
                    "任务绑定 environmentCode=" + t.environmentCode(), "需要确认目标调度环境变量是否存在。" );
        }
    }

    private void finishAnalysis(long runId, Snapshot snapshot, int taskCount) {
        int warnings = countIssues(runId, "WARN");
        int errors = countIssues(runId, "ERROR");
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("projects", snapshot.projects().size());
        summary.put("workflows", snapshot.workflows().size());
        summary.put("tasks", taskCount);
        summary.put("edges", snapshot.edges().stream().filter(e -> e.preTaskCode() != 0).count());
        summary.put("schedules", snapshot.schedules().size());
        summary.put("warnings", warnings);
        summary.put("errors", errors);
        String status = errors > 0 || warnings > 0 ? "COMPLETED_WITH_ISSUES" : "COMPLETED";
        jdbc.update("UPDATE migration_run SET status=?,phase='DONE',progress=100,processed_objects=total_objects," +
                        "warning_count=?,failure_count=?,success_count=total_objects-?,message=?,summary_json=?,finished_at=CURRENT_TIMESTAMP WHERE id=?",
                status, warnings, errors, errors,
                errors > 0 ? "分析完成，存在需人工处理的问题" : warnings > 0 ? "分析完成，存在警告" : "分析完成，可执行迁移",
                json(summary), runId);
        event(runId, errors > 0 ? "WARN" : "INFO", "DONE", "分析完成：" + json(summary));
    }

    private String unsupportedHint(String type) {
        return switch (type) {
            case "SUB_PROCESS" -> "子流程需要后续实现 Workflow 引用或展开策略，当前禁止静默扁平化。";
            case "DEPENDENT" -> "DEPENDENT 应转换为 DataSphere 依赖语义，当前先标记人工确认。";
            case "CONDITIONS", "CONDITION", "SWITCH" -> "条件分支需要转换 branchType/条件配置，当前先标记人工确认。";
            default -> "请根据 DataSphere 当前 NodeType 能力选择转换策略。";
        };
    }

    public MigrationScopeView migrationScope() {
        try {
            Snapshot snapshot = source.read(settings.get());
            Map<Long, List<TaskRow>> tasksByWorkflow = snapshot.tasks().stream()
                    .collect(Collectors.groupingBy(TaskRow::workflowCode, LinkedHashMap::new, Collectors.toList()));
            List<ProjectScopeView> projects = new ArrayList<>();
            for (ProjectRow project : snapshot.projects()) {
                List<WorkflowScopeView> workflows = snapshot.workflows().stream()
                        .filter(w -> w.projectCode() == project.code())
                        .map(w -> {
                            List<TaskRow> rows = tasksByWorkflow.getOrDefault(w.code(), List.of()).stream()
                                    .filter(t -> t.workflowVersion() == w.version()).toList();
                            List<String> types = rows.stream().map(t -> normalizeType(t.taskType())).distinct().sorted().toList();
                            long distinctTasks = rows.stream().map(t -> taskKey(t.code(), t.version())).distinct().count();
                            return new WorkflowScopeView(w.code(), w.version(), w.name(), w.releaseState() == 1, (int) distinctTasks, types);
                        }).toList();
                int taskCount = workflows.stream().mapToInt(WorkflowScopeView::taskCount).sum();
                projects.add(new ProjectScopeView(project.code(), project.name(), workflows.size(), taskCount, workflows));
            }
            int workflowCount = projects.stream().mapToInt(ProjectScopeView::workflowCount).sum();
            int taskCount = projects.stream().mapToInt(ProjectScopeView::taskCount).sum();
            return new MigrationScopeView(projects.size(), workflowCount, taskCount, projects);
        } catch (Exception ex) {
            throw new IllegalStateException("读取迁移范围失败：" + rootMessage(ex), ex);
        }
    }

    private void migrate(long runId, boolean dryRun, boolean migrateAll, Set<Long> workflowCodes) {
        try {
            startRun(runId, "CONNECT", dryRun ? "执行迁移试运行" : "连接源端与 DataSphere");
            Settings s = settings.get();
            SourceCheck sourceCheck = source.test(s);
            if (!sourceCheck.success()) throw new IllegalStateException(sourceCheck.message());
            DataSphereClient.TargetCheck targetCheck = target.test(s);
            if (!targetCheck.success()) throw new IllegalStateException(targetCheck.message());
            Snapshot snapshot = source.read(s);
            snapshot = filterSnapshot(snapshot, migrateAll, workflowCodes);
            if (snapshot.workflows().isEmpty()) throw new IllegalStateException("选择范围内没有可迁移工作流");
            event(runId, "INFO", "SCOPE", migrateAll ? "迁移范围：全部工作流" : "迁移范围：已选择 " + snapshot.workflows().size() + " 个工作流");
            if (dryRun) {
                dryRun(runId, snapshot);
                return;
            }
            executeMigration(runId, s, snapshot);
        } catch (CancelledException ex) {
            jdbc.update("UPDATE migration_run SET status='CANCELLED',phase='CANCELLED',message='迁移已取消',finished_at=CURRENT_TIMESTAMP WHERE id=?", runId);
            event(runId, "WARN", "CANCELLED", "迁移任务已取消");
        } catch (Exception ex) {
            failRun(runId, ex);
        }
    }


    private Snapshot filterSnapshot(Snapshot snapshot, boolean migrateAll, Set<Long> workflowCodes) {
        if (migrateAll) return snapshot;
        Set<Long> selected = new LinkedHashSet<>(workflowCodes);
        List<WorkflowRow> workflows = snapshot.workflows().stream().filter(w -> selected.contains(w.code())).toList();
        Set<Long> projectCodes = workflows.stream().map(WorkflowRow::projectCode).collect(Collectors.toCollection(LinkedHashSet::new));
        List<ProjectRow> projects = snapshot.projects().stream().filter(p -> projectCodes.contains(p.code())).toList();
        Set<String> workflowVersions = workflows.stream().map(w -> w.code() + ":" + w.version()).collect(Collectors.toSet());
        List<TaskRow> tasks = snapshot.tasks().stream().filter(t -> workflowVersions.contains(t.workflowCode() + ":" + t.workflowVersion())).toList();
        List<EdgeRow> edges = snapshot.edges().stream().filter(e -> workflowVersions.contains(e.workflowCode() + ":" + e.workflowVersion())).toList();
        List<ScheduleRow> schedules = snapshot.schedules().stream().filter(sc -> selected.contains(sc.workflowCode())).toList();
        return new Snapshot(projects, workflows, tasks, edges, schedules);
    }

    private void dryRun(long runId, Snapshot snapshot) {
        Map<String, TaskRow> tasks = uniqueTasks(snapshot);
        int total = snapshot.projects().size() + tasks.size() + snapshot.workflows().size() + snapshot.schedules().size();
        jdbc.update("UPDATE migration_run SET total_objects=? WHERE id=?", total, runId);
        int processed = 0;
        for (ProjectRow p : snapshot.projects()) {
            insertItem(runId, "PROJECT", String.valueOf(p.code()), 0, p.name(), null, "DRY_RUN_OK", "将创建/复用一级目录", json(p));
            progress(runId, ++processed, total, "DRY_RUN", p.name());
        }
        for (TaskRow t : tasks.values()) {
            String type = normalizeType(t.taskType());
            boolean auto = AUTO_TASK_TYPES.contains(type);
            long itemId = insertItem(runId, "TASK", String.valueOf(t.code()), t.version(), t.name(), type,
                    auto ? "DRY_RUN_OK" : "MANUAL_REQUIRED", auto ? "将创建/复用开发文件" : "任务类型需人工处理", json(t));
            inspectTaskIssues(runId, itemId, t, type, auto);
            progress(runId, ++processed, total, "DRY_RUN", t.name());
        }
        Map<String, Long> taskWorkflowUseCounts = taskWorkflowUseCounts(snapshot.tasks());
        for (WorkflowRow w : snapshot.workflows()) {
            boolean supported = workflowSupported(w, snapshot.tasks());
            List<TaskRow> workflowTasks = workflowTasks(w, snapshot.tasks());
            boolean developmentOnly = isSingleDevelopmentWorkflow(workflowTasks, taskWorkflowUseCounts);
            String message = !supported ? "包含暂不支持的任务类型"
                    : developmentOnly ? "单节点 SQL 流程将直接映射为数据开发任务流，不再创建重复编排工作流"
                    : "将创建/复用编排工作流并保持 Offline";
            insertItem(runId, "WORKFLOW", String.valueOf(w.code()), w.version(), w.name(), null,
                    supported ? "DRY_RUN_OK" : "BLOCKED", message, json(w));
            progress(runId, ++processed, total, "DRY_RUN", w.name());
        }
        for (ScheduleRow s : snapshot.schedules()) {
            insertItem(runId, "SCHEDULE", String.valueOf(s.id()), 0, "Schedule → " + s.workflowCode(), null,
                    "DRY_RUN_OK", "将保存为 disabled Workflow Schedule", json(s));
            progress(runId, ++processed, total, "DRY_RUN", s.crontab());
        }
        completeRun(runId, "试运行完成，未修改 DataSphere");
    }

    private void executeMigration(long runId, Settings s, Snapshot snapshot) {
        long projectId = target.singleProjectId(s);
        Map<Long, Long> folderByProject = new LinkedHashMap<>();
        Map<String, TaskRow> tasks = uniqueTasks(snapshot);
        int total = snapshot.projects().size() + tasks.size() + snapshot.workflows().size() + snapshot.schedules().size();
        jdbc.update("UPDATE migration_run SET total_objects=? WHERE id=?", total, runId);
        int processed = 0;

        for (ProjectRow p : snapshot.projects()) {
            checkCancelled(runId);
            long itemId = insertItem(runId, "PROJECT", String.valueOf(p.code()), 0, p.name(), null, "RUNNING", "创建一级目录", json(p));
            long folderId = target.ensureTopFolder(s, projectId, p.name());
            folderByProject.put(p.code(), folderId);
            saveMap("PROJECT", String.valueOf(p.code()), 0, "FOLDER", String.valueOf(folderId), p.name());
            finishItem(itemId, "SUCCESS", "FOLDER", String.valueOf(folderId), "目录已准备");
            progress(runId, ++processed, total, "MIGRATE_PROJECT", p.name());
        }

        Map<String, Long> fileByTask = new LinkedHashMap<>();
        for (TaskRow t : tasks.values()) {
            checkCancelled(runId);
            String type = normalizeType(t.taskType());
            if (!AUTO_TASK_TYPES.contains(type)) {
                long itemId = insertItem(runId, "TASK", String.valueOf(t.code()), t.version(), t.name(), type,
                        "MANUAL_REQUIRED", "暂不自动迁移该任务类型", json(t));
                issue(runId, itemId, "ERROR", "UNSUPPORTED_TASK_TYPE", "TASK", String.valueOf(t.code()), t.name(),
                        "任务类型 " + type + " 暂不自动迁移", unsupportedHint(type));
                progress(runId, ++processed, total, "MIGRATE_TASK", t.name());
                continue;
            }
            long itemId = insertItem(runId, "TASK", String.valueOf(t.code()), t.version(), t.name(), type, "RUNNING", "创建开发文件", json(t));
            Long existing = mappedId("TASK", String.valueOf(t.code()), t.version(), "DEV_FILE");
            if (existing != null && !target.fileExists(s, existing)) existing = null;
            long fileId;
            if (existing != null) {
                fileId = existing;
            } else {
                Long folderId = folderByProject.get(t.projectCode());
                fileId = target.createFile(s, projectId, folderId, t.name(), type, taskContent(t), t.description());
                saveMap("TASK", String.valueOf(t.code()), t.version(), "DEV_FILE", String.valueOf(fileId), t.name());
            }
            fileByTask.put(taskKey(t.code(), t.version()), fileId);
            finishItem(itemId, "SUCCESS", "DEV_FILE", String.valueOf(fileId), existing == null ? "开发文件已创建" : "复用已有映射");
            progress(runId, ++processed, total, "MIGRATE_TASK", t.name());
        }

        Map<Long, ScheduleRow> scheduleByWorkflow = primarySchedules(snapshot.schedules());
        for (WorkflowRow w : snapshot.workflows()) {
            ScheduleRow schedule = scheduleByWorkflow.get(w.code());
            if (schedule == null) continue;
            CronDisplay display = cronDisplay(schedule.crontab());
            for (TaskRow t : workflowTasks(w, snapshot.tasks())) {
                if (!"SQL".equals(normalizeType(t.taskType()))) continue;
                Long fileId = fileByTask.get(taskKey(t.code(), t.version()));
                if (fileId == null) continue;
                List<Map<String, String>> localParams = mergedParams(w, t);
                List<Long> upstreams = upstreamFileIds(w, t, snapshot.edges(), fileByTask);
                target.saveDevelopmentSchedule(s, fileId, display.cycleType(), display.executionTime(),
                        schedule.crontab(), schedule.timezone(), businessDateParam(localParams), localParams,
                        t.retryTimes(), t.retryIntervalMinutes(), upstreams);
                event(runId, "INFO", "MIGRATE_TASK_SCHEDULE", t.name() + " · " + schedule.crontab() +
                        " · 参数=" + localParams.size() + " · 上游=" + upstreams.size());
            }
        }

        Map<Long, Long> workflowTargetIds = new LinkedHashMap<>();
        Map<Long, Long> developmentWorkflowFileIds = new LinkedHashMap<>();
        Map<String, Long> taskWorkflowUseCounts = taskWorkflowUseCounts(snapshot.tasks());
        for (WorkflowRow w : snapshot.workflows()) {
            checkCancelled(runId);
            long itemId = insertItem(runId, "WORKFLOW", String.valueOf(w.code()), w.version(), w.name(), null, "RUNNING", "创建工作流", json(w));
            if (!workflowSupported(w, snapshot.tasks())) {
                finishItem(itemId, "BLOCKED", null, null, "包含暂不支持的任务，未创建工作流");
                progress(runId, ++processed, total, "MIGRATE_WORKFLOW", w.name());
                continue;
            }
            List<TaskRow> workflowTasks = workflowTasks(w, snapshot.tasks());
            if (isSingleDevelopmentWorkflow(workflowTasks, taskWorkflowUseCounts)) {
                TaskRow onlyTask = workflowTasks.getFirst();
                Long fileId = fileByTask.get(taskKey(onlyTask.code(), onlyTask.version()));
                if (fileId == null) {
                    finishItem(itemId, "BLOCKED", null, null, "单节点开发任务未成功迁移，无法建立任务流映射");
                    progress(runId, ++processed, total, "MIGRATE_WORKFLOW", w.name());
                    continue;
                }
                Long oldWorkflowId = mappedId("WORKFLOW", String.valueOf(w.code()), w.version(), "WORKFLOW");
                if (oldWorkflowId != null) {
                    if (!target.workflowExists(s, oldWorkflowId)) {
                        deleteMap("WORKFLOW", String.valueOf(w.code()), w.version(), "WORKFLOW");
                    } else {
                        String status = target.workflowStatus(s, oldWorkflowId);
                        if ("DRAFT".equalsIgnoreCase(status)) {
                            target.deleteWorkflow(s, oldWorkflowId);
                            deleteMap("WORKFLOW", String.valueOf(w.code()), w.version(), "WORKFLOW");
                            event(runId, "INFO", "CLEAN_DUPLICATE_WORKFLOW", w.name() + " · 已清理旧的单节点草稿编排工作流 #" + oldWorkflowId);
                        } else {
                            issue(runId, itemId, "WARN", "SINGLE_NODE_WORKFLOW_ALREADY_PUBLISHED", "WORKFLOW",
                                    String.valueOf(w.code()), w.name(), "旧的单节点编排工作流已不是草稿，未自动删除",
                                    "目标 Workflow #" + oldWorkflowId + " 状态=" + status + "，请确认后人工清理。数据开发任务流已作为后续迁移目标。");
                        }
                    }
                }
                developmentWorkflowFileIds.put(w.code(), fileId);
                saveMap("WORKFLOW", String.valueOf(w.code()), w.version(), "DEV_FILE", String.valueOf(fileId), w.name());
                finishItem(itemId, "SUCCESS", "DEV_FILE", String.valueOf(fileId),
                        "单节点 SQL 流程已映射为数据开发任务流，不创建重复编排工作流");
                progress(runId, ++processed, total, "MIGRATE_WORKFLOW", w.name());
                continue;
            }
            Long existing = mappedId("WORKFLOW", String.valueOf(w.code()), w.version(), "WORKFLOW");
            if (existing != null && !target.workflowExists(s, existing)) existing = null;
            Map<String, Object> payload = workflowPayload(w, snapshot, fileByTask);
            long workflowId;
            if (existing != null) {
                workflowId = existing;
                target.updateWorkflow(s, workflowId, payload);
            } else {
                workflowId = target.createWorkflow(s, payload);
            }
            saveMap("WORKFLOW", String.valueOf(w.code()), w.version(), "WORKFLOW", String.valueOf(workflowId), w.name());
            workflowTargetIds.put(w.code(), workflowId);
            JsonNode validation = target.validateWorkflow(s, workflowId);
            finishItem(itemId, "SUCCESS", "WORKFLOW", String.valueOf(workflowId),
                    "工作流已创建并校验，保持 Draft/Offline：" + validation.toString());
            progress(runId, ++processed, total, "MIGRATE_WORKFLOW", w.name());
        }

        for (ScheduleRow schedule : snapshot.schedules()) {
            checkCancelled(runId);
            long itemId = insertItem(runId, "SCHEDULE", String.valueOf(schedule.id()), 0,
                    "Schedule → " + schedule.workflowCode(), null, "RUNNING", "保存调度配置", json(schedule));
            Long workflowId = workflowTargetIds.get(schedule.workflowCode());
            Long developmentFileId = developmentWorkflowFileIds.get(schedule.workflowCode());
            ScheduleRow selected = scheduleByWorkflow.get(schedule.workflowCode());
            if (selected == null || selected.id() != schedule.id()) {
                finishItem(itemId, "SKIPPED", null, null, "同一工作流存在多条调度，已优先选择已发布且较新的调度");
                issue(runId, itemId, "WARN", "MULTIPLE_SCHEDULES", "SCHEDULE", String.valueOf(schedule.id()),
                        "Schedule → " + schedule.workflowCode(), "检测到同一工作流多条调度",
                        "已优先迁移 release_state=1 且 ID 较新的调度，请人工确认其余调度。");
            } else if (developmentFileId != null) {
                finishItem(itemId, "SUCCESS", "DEV_FILE_SCHEDULE", String.valueOf(developmentFileId),
                        "单节点 SQL 流程调度已保存到数据开发任务，保持 disabled");
            } else if (workflowId == null) {
                finishItem(itemId, "BLOCKED", null, null, "对应工作流未迁移，跳过调度");
            } else {
                target.saveSchedule(s, workflowId, schedule.crontab(), schedule.timezone(), schedule.failureStrategy(), schedule.workerGroup());
                finishItem(itemId, "SUCCESS", "WORKFLOW_SCHEDULE", String.valueOf(workflowId),
                        "工作流调度已保存；数据开发 SQL 任务同步继承 Cron、参数与 DAG 依赖，均保持 disabled");
            }
            progress(runId, ++processed, total, "MIGRATE_SCHEDULE", schedule.crontab());
        }
        completeRun(runId, "迁移完成；所有新工作流和调度保持 Offline，未自动切生产");
    }

    private Map<String, Object> workflowPayload(WorkflowRow w, Snapshot snapshot, Map<String, Long> fileByTask) {
        List<TaskRow> tasks = snapshot.tasks().stream()
                .filter(t -> t.workflowCode() == w.code() && t.workflowVersion() == w.version()).toList();
        Map<Long, int[]> positions = parseLocations(w.locations());
        List<Map<String, Object>> nodes = new ArrayList<>();
        for (TaskRow t : tasks) {
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("name", t.name());
            node.put("nodeType", normalizeType(t.taskType()));
            node.put("devFileId", fileByTask.get(taskKey(t.code(), t.version())));
            node.put("configJson", taskConfigJson(w, t));
            int[] xy = positions.getOrDefault(t.code(), new int[]{0, 0});
            node.put("x", xy[0]); node.put("y", xy[1]);
            node.put("nodeCode", "DS319_" + t.code());
            nodes.add(node);
        }
        List<Map<String, Object>> edges = new ArrayList<>();
        for (EdgeRow e : snapshot.edges()) {
            if (e.workflowCode() != w.code() || e.workflowVersion() != w.version() || e.preTaskCode() == 0) continue;
            Map<String, Object> edge = new LinkedHashMap<>();
            edge.put("sourceNodeId", null); edge.put("targetNodeId", null);
            edge.put("sourceNodeCode", "DS319_" + e.preTaskCode());
            edge.put("targetNodeCode", "DS319_" + e.postTaskCode());
            edge.put("branchType", "NORMAL");
            edges.add(edge);
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("name", w.name());
        payload.put("description", w.description());
        payload.put("nodes", nodes); payload.put("edges", edges);
        return payload;
    }

    private String taskContent(TaskRow t) {
        JsonNode params = source.parseTaskParams(t);
        String type = normalizeType(t.taskType());
        if ("SQL".equals(type)) return params.path("sql").asText("");
        return params.path("rawScript").asText("");
    }

    private String taskConfigJson(WorkflowRow w, TaskRow t) {
        JsonNode params = source.parseTaskParams(t);
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("sourceWorkflowCode", w.code());
        config.put("sourceTaskCode", t.code());
        config.put("sourceTaskVersion", t.version());
        config.put("workerGroup", t.workerGroup() == null || t.workerGroup().isBlank() ? "default" : t.workerGroup());
        config.put("retryTimes", t.retryTimes());
        config.put("retryIntervalMinutes", t.retryIntervalMinutes());
        config.put("localParams", mergedParams(w, t));
        if (t.environmentCode() != null) config.put("environmentCode", t.environmentCode());
        if ("SQL".equals(normalizeType(t.taskType()))) {
            config.put("sourceDatasourceId", params.path("datasource").asLong(0));
            config.put("type", params.path("type").asText("MYSQL"));
            config.put("sqlType", params.path("sqlType").asInt(0));
            config.put("displayRows", params.path("displayRows").asInt(10));
            config.put("limit", params.path("limit").asInt(0));
        }
        if ("SEATUNNEL".equals(normalizeType(t.taskType()))) {
            config.put("startupScript", params.path("startupScript").asText("seatunnel.sh"));
            config.put("rawScript", params.path("rawScript").asText(""));
        }
        return json(config);
    }

    private List<TaskRow> workflowTasks(WorkflowRow workflow, List<TaskRow> tasks) {
        return tasks.stream().filter(t -> t.workflowCode() == workflow.code() && t.workflowVersion() == workflow.version()).toList();
    }

    private Map<String, Long> taskWorkflowUseCounts(List<TaskRow> tasks) {
        return tasks.stream().collect(Collectors.groupingBy(
                t -> taskKey(t.code(), t.version()), LinkedHashMap::new, Collectors.counting()));
    }

    private boolean isSingleDevelopmentWorkflow(List<TaskRow> workflowTasks, Map<String, Long> taskWorkflowUseCounts) {
        if (workflowTasks.size() != 1) return false;
        TaskRow task = workflowTasks.getFirst();
        return "SQL".equals(normalizeType(task.taskType()))
                && taskWorkflowUseCounts.getOrDefault(taskKey(task.code(), task.version()), 0L) == 1L;
    }

    private Map<Long, ScheduleRow> primarySchedules(List<ScheduleRow> schedules) {
        Map<Long, ScheduleRow> selected = new LinkedHashMap<>();
        for (ScheduleRow candidate : schedules) {
            ScheduleRow current = selected.get(candidate.workflowCode());
            if (current == null || candidate.releaseState() > current.releaseState()
                    || candidate.releaseState() == current.releaseState() && candidate.id() > current.id()) {
                selected.put(candidate.workflowCode(), candidate);
            }
        }
        return selected;
    }

    private List<Long> upstreamFileIds(WorkflowRow workflow, TaskRow task, List<EdgeRow> edges, Map<String, Long> fileByTask) {
        LinkedHashSet<Long> result = new LinkedHashSet<>();
        for (EdgeRow edge : edges) {
            if (edge.workflowCode() != workflow.code() || edge.workflowVersion() != workflow.version()) continue;
            if (edge.preTaskCode() == 0 || edge.postTaskCode() != task.code() || edge.postTaskVersion() != task.version()) continue;
            Long upstream = fileByTask.get(taskKey(edge.preTaskCode(), edge.preTaskVersion()));
            if (upstream != null) result.add(upstream);
        }
        return new ArrayList<>(result);
    }

    private List<Map<String, String>> mergedParams(WorkflowRow workflow, TaskRow task) {
        LinkedHashMap<String, String> values = new LinkedHashMap<>();
        if (workflow.globalParams() != null && !workflow.globalParams().isBlank()) {
            try { mergeParamArray(mapper.readTree(workflow.globalParams()), values); } catch (Exception ignored) { }
        }
        JsonNode taskParams = source.parseTaskParams(task);
        mergeParamArray(taskParams.path("localParams"), values);
        List<Map<String, String>> result = new ArrayList<>();
        values.forEach((key, value) -> result.add(Map.of("key", key, "value", value)));
        return result;
    }

    private void mergeParamArray(JsonNode params, LinkedHashMap<String, String> values) {
        if (params == null || !params.isArray()) return;
        for (JsonNode param : params) {
            String key = param.path("prop").asText(param.path("key").asText("")).trim();
            if (key.isBlank() || !key.matches("[A-Za-z_][A-Za-z0-9_.-]*")) continue;
            values.put(key, param.path("value").asText(""));
        }
    }

    private String businessDateParam(List<Map<String, String>> params) {
        List<String> preferred = List.of("biz_date", "bizdate", "business_date", "businessdate", "dt", "date", "start_dt");
        for (String key : preferred) {
            for (Map<String, String> param : params) if (key.equalsIgnoreCase(param.get("key"))) return param.get("value");
        }
        for (Map<String, String> param : params) {
            String value = param.getOrDefault("value", "");
            if (value.contains("system.biz") || value.startsWith("$[")) return value;
        }
        return "${system.biz.date}";
    }

    private CronDisplay cronDisplay(String cron) {
        String[] fields = cron == null ? new String[0] : cron.trim().split("\\s+");
        if (fields.length < 6) return new CronDisplay("CRON", "00:00");
        Integer minute = cronNumber(fields[1], 0, 59);
        Integer hour = cronNumber(fields[2], 0, 23);
        String dayOfMonth = fields[3], month = fields[4], dayOfWeek = fields[5];
        String executionTime = hour == null || minute == null ? "00:00" : String.format(Locale.ROOT, "%02d:%02d", hour, minute);
        boolean everyday = "*".equals(month) && (("*".equals(dayOfMonth) && "?".equals(dayOfWeek))
                || ("?".equals(dayOfMonth) && "*".equals(dayOfWeek)));
        if (minute != null && "*".equals(fields[2]) && everyday) return new CronDisplay("HOURLY", String.format(Locale.ROOT, "00:%02d", minute));
        if (hour != null && minute != null && everyday) return new CronDisplay("DAILY", executionTime);
        if (hour != null && minute != null && "?".equals(dayOfMonth) && !"*".equals(dayOfWeek) && !"?".equals(dayOfWeek)) return new CronDisplay("WEEKLY", executionTime);
        if (hour != null && minute != null && cronNumber(dayOfMonth, 1, 31) != null && "?".equals(dayOfWeek)) return new CronDisplay("MONTHLY", executionTime);
        return new CronDisplay("CRON", executionTime);
    }

    private Integer cronNumber(String value, int min, int max) {
        try { int number = Integer.parseInt(value); return number >= min && number <= max ? number : null; }
        catch (Exception ignored) { return null; }
    }

    private record CronDisplay(String cycleType, String executionTime) { }

    private Map<Long, int[]> parseLocations(String raw) {
        Map<Long, int[]> result = new HashMap<>();
        if (raw == null || raw.isBlank()) return result;
        try {
            JsonNode root = mapper.readTree(raw);
            if (root.isArray()) for (JsonNode n : root) {
                long code = n.path("taskCode").asLong(n.path("taskCode").asText("0").isBlank() ? 0 : Long.parseLong(n.path("taskCode").asText("0")));
                if (code != 0) result.put(code, new int[]{n.path("x").asInt(0), n.path("y").asInt(0)});
            }
        } catch (Exception ignored) { }
        return result;
    }

    private Map<String, TaskRow> uniqueTasks(Snapshot snapshot) {
        return snapshot.tasks().stream().collect(Collectors.toMap(
                t -> taskKey(t.code(), t.version()), Function.identity(), (a, b) -> a, LinkedHashMap::new));
    }

    private boolean workflowSupported(WorkflowRow w, List<TaskRow> tasks) {
        return tasks.stream().filter(t -> t.workflowCode() == w.code() && t.workflowVersion() == w.version())
                .allMatch(t -> AUTO_TASK_TYPES.contains(normalizeType(t.taskType())));
    }

    private long createRun(String operation, boolean dryRun, String status, String phase, String message) {
        KeyHolder kh = new GeneratedKeyHolder();
        jdbc.update(c -> {
            PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO migration_run(operation,status,phase,progress,dry_run,message) VALUES(?,?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, operation); ps.setString(2, status); ps.setString(3, phase);
            ps.setInt(4, 0); ps.setBoolean(5, dryRun); ps.setString(6, message);
            return ps;
        }, kh);
        return Objects.requireNonNull(kh.getKey()).longValue();
    }

    private long insertItem(long runId, String objectType, String sourceCode, Integer sourceVersion,
                            String objectName, String taskType, String status, String message, String payloadJson) {
        KeyHolder kh = new GeneratedKeyHolder();
        jdbc.update(c -> {
            PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO migration_item(run_id,object_type,source_code,source_version,object_name,task_type,status,progress,message,payload_json) VALUES(?,?,?,?,?,?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, runId); ps.setString(2, objectType); ps.setString(3, sourceCode);
            if (sourceVersion == null) ps.setNull(4, java.sql.Types.INTEGER); else ps.setInt(4, sourceVersion);
            ps.setString(5, objectName); ps.setString(6, taskType); ps.setString(7, status);
            ps.setInt(8, "SUCCESS".equals(status) ? 100 : 0); ps.setString(9, message); ps.setString(10, payloadJson);
            return ps;
        }, kh);
        return Objects.requireNonNull(kh.getKey()).longValue();
    }

    private void issue(long runId, Long itemId, String severity, String issueCode, String objectType,
                       String sourceCode, String objectName, String message, String detail) {
        jdbc.update("INSERT INTO migration_issue(run_id,item_id,severity,issue_code,object_type,source_code,object_name,message,detail,status) VALUES(?,?,?,?,?,?,?,?,?,'OPEN')",
                runId, itemId, severity, issueCode, objectType, sourceCode, objectName, message, detail);
        event(runId, severity, "ISSUE", issueCode + " · " + objectName + " · " + message);
    }

    private void startRun(long runId, String phase, String message) {
        jdbc.update("UPDATE migration_run SET status='RUNNING',phase=?,message=?,started_at=CURRENT_TIMESTAMP WHERE id=?", phase, message, runId);
        event(runId, "INFO", phase, message);
    }

    private void updateRun(long runId, int progress, String phase, String message) {
        jdbc.update("UPDATE migration_run SET progress=?,phase=?,message=? WHERE id=?", progress, phase, message, runId);
        event(runId, "INFO", phase, message);
    }

    private void progress(long runId, int processed, int total, String phase, String message) {
        int pct = total <= 0 ? 100 : Math.min(99, Math.max(1, processed * 100 / total));
        jdbc.update("UPDATE migration_run SET processed_objects=?,progress=?,phase=?,message=? WHERE id=?", processed, pct, phase, message, runId);
    }

    private void finishItem(long itemId, String status, String targetType, String targetId, String message) {
        jdbc.update("UPDATE migration_item SET status=?,progress=?,target_type=?,target_id=?,message=?,updated_at=CURRENT_TIMESTAMP WHERE id=?",
                status, ("SUCCESS".equals(status) || "SKIPPED".equals(status)) ? 100 : 0, targetType, targetId, message, itemId);
    }

    private void completeRun(long runId, String message) {
        int warnings = countIssues(runId, "WARN");
        int errors = countIssues(runId, "ERROR");
        String status = errors > 0 || warnings > 0 ? "COMPLETED_WITH_ISSUES" : "COMPLETED";
        jdbc.update("UPDATE migration_run SET status=?,phase='DONE',progress=100,processed_objects=total_objects," +
                        "success_count=(SELECT COUNT(*) FROM migration_item WHERE run_id=? AND status IN ('SUCCESS','DRY_RUN_OK'))," +
                        "warning_count=?,failure_count=?,message=?,finished_at=CURRENT_TIMESTAMP WHERE id=?",
                status, runId, warnings, errors, message, runId);
        event(runId, errors > 0 ? "WARN" : "INFO", "DONE", message);
    }

    private void failRun(long runId, Throwable ex) {
        String msg = rootMessage(ex);
        jdbc.update("UPDATE migration_run SET status='FAILED',phase='FAILED',failure_count=failure_count+1,message=?,finished_at=CURRENT_TIMESTAMP WHERE id=?", msg, runId);
        event(runId, "ERROR", "FAILED", msg);
    }

    private void event(long runId, String level, String phase, String message) {
        jdbc.update("INSERT INTO migration_event(run_id,level,phase,message) VALUES(?,?,?,?)", runId, level, phase, message);
    }

    private int countIssues(long runId, String severity) {
        Integer value = jdbc.queryForObject("SELECT COUNT(*) FROM migration_issue WHERE run_id=? AND severity=? AND status='OPEN'", Integer.class, runId, severity);
        return value == null ? 0 : value;
    }

    private void checkCancelled(long runId) {
        Boolean cancelled = jdbc.queryForObject("SELECT cancel_requested FROM migration_run WHERE id=?", Boolean.class, runId);
        if (Boolean.TRUE.equals(cancelled)) throw new CancelledException();
    }

    private Long mappedId(String sourceType, String sourceCode, int sourceVersion, String targetType) {
        List<Long> ids = jdbc.query("SELECT target_id FROM migration_object_map WHERE source_type=? AND source_code=? AND source_version=? AND target_type=?",
                (rs, n) -> Long.parseLong(rs.getString(1)), sourceType, sourceCode, sourceVersion, targetType);
        return ids.isEmpty() ? null : ids.get(0);
    }

    private void deleteMap(String sourceType, String sourceCode, int sourceVersion, String targetType) {
        jdbc.update("DELETE FROM migration_object_map WHERE source_type=? AND source_code=? AND source_version=? AND target_type=?",
                sourceType, sourceCode, sourceVersion, targetType);
    }

    private void saveMap(String sourceType, String sourceCode, int sourceVersion, String targetType, String targetId, String targetName) {
        jdbc.update("INSERT INTO migration_object_map(source_type,source_code,source_version,target_type,target_id,target_name) VALUES(?,?,?,?,?,?) " +
                        "ON DUPLICATE KEY UPDATE target_id=VALUES(target_id),target_name=VALUES(target_name),updated_at=CURRENT_TIMESTAMP",
                sourceType, sourceCode, sourceVersion, targetType, targetId, targetName);
    }

    private RunView runRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new RunView(rs.getLong("id"), rs.getString("operation"), rs.getString("status"), rs.getString("phase"),
                rs.getInt("progress"), rs.getString("source_version"), rs.getBoolean("dry_run"), rs.getInt("total_objects"),
                rs.getInt("processed_objects"), rs.getInt("success_count"), rs.getInt("warning_count"), rs.getInt("failure_count"),
                rs.getString("message"), toLocal(rs.getTimestamp("created_at")), toLocal(rs.getTimestamp("started_at")),
                toLocal(rs.getTimestamp("finished_at")));
    }

    private Long nullableLong(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private LocalDateTime toLocal(Timestamp ts) { return ts == null ? null : ts.toLocalDateTime(); }
    private String taskKey(long code, int version) { return code + ":" + version; }
    private String normalizeType(String type) { return type == null ? "UNKNOWN" : type.trim().toUpperCase(Locale.ROOT); }

    private String json(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (Exception ex) { return "{}"; }
    }

    private String rootMessage(Throwable ex) {
        Throwable cursor = ex;
        while (cursor.getCause() != null) cursor = cursor.getCause();
        String message = cursor.getMessage();
        return message == null || message.isBlank() ? cursor.getClass().getSimpleName() : message;
    }

    private static final class CancelledException extends RuntimeException { }
}
