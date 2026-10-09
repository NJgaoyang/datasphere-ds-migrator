package com.company.migrator.service;

import com.company.migrator.common.MigrationModels.Settings;
import com.company.migrator.common.WorkflowControlModels.*;
import com.company.migrator.target.DataSphereClient;
import com.company.migrator.source.DolphinScheduler319Reader;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class WorkflowControlService {
    private final JdbcTemplate jdbc;
    private final SettingService settings;
    private final DataSphereClient target;
    private final DolphinScheduler319Reader source;

    public WorkflowControlService(JdbcTemplate jdbc, SettingService settings, DataSphereClient target, DolphinScheduler319Reader source) {
        this.jdbc = jdbc;
        this.settings = settings;
        this.target = target;
        this.source = source;
    }

    public WorkflowControlSnapshot snapshot() {
        Settings s = settings.get();
        List<WorkflowControlItem> items = new ArrayList<>();
        Map<Long, String> sourceFolders = Map.of();
        try {
            sourceFolders = source.workflowProjectFolders(s);
        } catch (Exception ex) {
            // Keep runtime controls usable if the source DolphinScheduler has been retired.
            // Unknown workflow origins remain selectable under an explicit fallback folder.
        }
        Map<Long, String> foldersByTarget = new HashMap<>();
        for (Map<String, Object> binding : jdbc.queryForList(
                "SELECT source_code,target_id FROM migration_object_map WHERE source_type='WORKFLOW' AND target_type='WORKFLOW'")) {
            try {
                long code = Long.parseLong(String.valueOf(binding.get("source_code")));
                long targetId = Long.parseLong(String.valueOf(binding.get("target_id")));
                String folder = sourceFolders.get(code);
                if (folder != null && !folder.isBlank()) foldersByTarget.put(targetId, folder);
            } catch (NumberFormatException ignored) { }
        }
        for (MappedTarget row : mappedTargets("WORKFLOW")) {
            String folder = foldersByTarget.getOrDefault(row.id(), "未分类项目");
            try {
                JsonNode workflow = target.workflow(s, row.id());
                JsonNode schedule = target.workflowSchedule(s, row.id());
                String status = workflow.path("status").asText("UNKNOWN");
                boolean configured = schedule.path("id").asLong(0) > 0;
                boolean enabled = configured && schedule.path("enabled").asBoolean(false);
                Boolean ready = null;
                String preflightMessage = "发布后执行生产切换检查";
                if ("PUBLISHED".equalsIgnoreCase(status) && configured) {
                    JsonNode preflight = target.workflowPreflight(s, row.id());
                    ready = preflight.path("ready").asBoolean(false);
                    preflightMessage = preflight.path("message").asText("");
                } else if ("PUBLISHED".equalsIgnoreCase(status)) {
                    ready = true;
                    preflightMessage = "无 Native Scheduler 调度，仅保留手动运行";
                }
                items.add(new WorkflowControlItem(row.id(), workflow.path("workflowCode").asText(""),
                        displayName(row, workflow), folder, status, configured, enabled, schedule.path("cronExpression").asText(""),
                        ready, preflightMessage, ""));
            } catch (Exception ex) {
                items.add(new WorkflowControlItem(row.id(), "", row.name(), folder, "MISSING", false, false, "",
                        false, "", rootMessage(ex)));
            }
        }
        int published = (int) items.stream().filter(i -> "PUBLISHED".equalsIgnoreCase(i.definitionStatus())).count();
        int online = (int) items.stream().filter(WorkflowControlItem::scheduleEnabled).count();
        int ready = (int) items.stream().filter(i -> Boolean.TRUE.equals(i.preflightReady())).count();
        return new WorkflowControlSnapshot(items.size(), published, online, ready, List.copyOf(items));
    }

    public WorkflowActionResult online(WorkflowControlRequest request) {
        Settings s = settings.get();
        List<MappedTarget> selected = selectedTargets(request);
        Set<Long> managedFiles = mappedTargets("DEV_FILE").stream().map(MappedTarget::id).collect(Collectors.toSet());
        List<WorkflowActionItem> results = new ArrayList<>();
        for (MappedTarget row : selected) {
            try {
                JsonNode workflow = target.workflow(s, row.id());
                String name = displayName(row, workflow);
                prepareDevelopmentFiles(s, workflow, managedFiles);
                target.validateWorkflow(s, row.id());

                JsonNode schedule = target.workflowSchedule(s, row.id());
                boolean configured = schedule.path("id").asLong(0) > 0;
                // Publication may fail or require approval. Never enable Cron before
                // the published snapshot is actually available.
                if (!"PUBLISHED".equalsIgnoreCase(target.workflowStatus(s, row.id()))) {
                    target.publishWorkflow(s, row.id());
                }
                if (!configured) {
                    results.add(success(row.id(), name, "PUBLISHED", "工作流已发布；未配置 Cron，仅支持手动运行", null));
                    continue;
                }
                JsonNode preflight = target.workflowPreflight(s, row.id());
                if (!preflight.path("ready").asBoolean(false)) {
                    results.add(failure(row.id(), name, "BLOCKED", preflight.path("message").asText("生产切换检查未通过")));
                    continue;
                }
                JsonNode active = target.workflowSchedule(s, row.id());
                if (!active.path("enabled").asBoolean(false)
                        || !active.path("desiredEnabled").asBoolean(false)) {
                    active = target.onlineWorkflow(s, row.id());
                }
                if (!active.path("enabled").asBoolean(false)
                        || !active.path("desiredEnabled").asBoolean(false)) {
                    results.add(failure(row.id(), name, "BLOCKED", "Native Scheduler 启用状态未生效"));
                    continue;
                }
                results.add(success(row.id(), name, "ONLINE", "工作流已发布，源端 Cron 已启用 Native Scheduler", null));
            } catch (Exception ex) {
                results.add(failure(row.id(), row.name(), "FAILED", rootMessage(ex)));
            }
        }
        return result("ONLINE", selected.size(), results, "一键上线");
    }

    public WorkflowActionResult offline(WorkflowControlRequest request) {
        Settings s = settings.get();
        List<MappedTarget> selected = selectedTargets(request);
        List<WorkflowActionItem> results = new ArrayList<>();
        for (MappedTarget row : selected) {
            try {
                JsonNode workflow = target.workflow(s, row.id());
                String name = displayName(row, workflow);
                String status = workflow.path("status").asText("");
                if ("PUBLISHED".equalsIgnoreCase(status)) {
                    // Workflow lifecycle offline also disables effective Cron scheduling.
                    target.unpublishWorkflow(s, row.id());
                } else if (!"OFFLINE".equalsIgnoreCase(status) && !"DRAFT".equalsIgnoreCase(status)) {
                    results.add(failure(row.id(), name, "BLOCKED", "当前工作流状态不支持下线：" + status));
                    continue;
                }
                // Clear desiredEnabled as well: otherwise publishing again can reactivate Cron.
                JsonNode schedule = target.workflowSchedule(s, row.id());
                if (schedule.path("id").asLong(0) > 0
                        && (schedule.path("enabled").asBoolean(false)
                            || schedule.path("desiredEnabled").asBoolean(false))) {
                    target.offlineWorkflow(s, row.id());
                }
                JsonNode refreshed = target.workflow(s, row.id());
                JsonNode inactive = target.workflowSchedule(s, row.id());
                if ("PUBLISHED".equalsIgnoreCase(refreshed.path("status").asText(""))
                        || inactive.path("enabled").asBoolean(false)
                        || inactive.path("desiredEnabled").asBoolean(false)) {
                    results.add(failure(row.id(), name, "BLOCKED", "工作流生命周期或调度状态仍未下线"));
                    continue;
                }
                results.add(success(row.id(), name, "OFFLINE", "工作流已下线，自动调度已关闭", null));
            } catch (Exception ex) {
                results.add(failure(row.id(), row.name(), "FAILED", rootMessage(ex)));
            }
        }
        return result("OFFLINE", selected.size(), results, "一键下线");
    }

    /**
     * Submit every selected workflow independently. Workflow dependency ordering is
     * enforced by DataForge Native Scheduler, not inferred from DIM/DWD names.
     * A failed submission must never block another unrelated workflow.
     */
    public WorkflowActionResult run(WorkflowControlRequest request) {
        Settings s = settings.get();
        List<MappedTarget> selected = selectedTargets(request);
        List<WorkflowActionItem> results = new ArrayList<>();
        for (MappedTarget row : selected) {
            try {
                JsonNode workflow = target.workflow(s, row.id());
                String name = displayName(row, workflow);
                if (!"PUBLISHED".equalsIgnoreCase(workflow.path("status").asText(""))) {
                    results.add(failure(row.id(), name, "BLOCKED", "工作流尚未发布，请先执行一键上线"));
                    continue;
                }
                JsonNode submitted = target.runWorkflow(s, row.id());
                String instanceId = submitted.path("instanceId").asText("");
                if (instanceId.isBlank()) throw new IllegalStateException("DataForge 未返回工作流实例编号");
                results.add(success(row.id(), name, "SUBMITTED",
                        "已独立提交 Native Scheduler；请在 DataForge 工作流实例中查看运行结果", instanceId));
            } catch (Exception ex) {
                results.add(failure(row.id(), row.name(), "FAILED", rootMessage(ex)));
            }
        }
        return result("RUN", selected.size(), results, "工作流批量提交");
    }

    private void prepareDevelopmentFiles(Settings s, JsonNode workflow, Set<Long> managedFiles) {
        LinkedHashSet<Long> fileIds = new LinkedHashSet<>();
        for (JsonNode node : workflow.path("nodes")) {
            JsonNode fileId = node.get("devFileId");
            if (fileId != null && !fileId.isNull() && fileId.asLong(0) > 0) fileIds.add(fileId.asLong());
        }
        for (Long fileId : fileIds) {
            JsonNode file = target.file(s, fileId);
            if ("PUBLISHED".equalsIgnoreCase(file.path("status").asText(""))) continue;
            if (!managedFiles.contains(fileId)) {
                throw new IllegalStateException("工作流引用了非迁移工具管理且尚未发布的开发任务：" +
                        file.path("name").asText("#" + fileId) + "(#" + fileId + ")，请在 DataSphere 手工上线并发布");
            }
            // Current DataForge has no dev_file_schedule: development files only publish fixed versions.
            // Production Cron lives exclusively on Workflow Native Scheduler.
            target.onlineFile(s, fileId);
        }
    }

    private List<MappedTarget> selectedTargets(WorkflowControlRequest request) {
        List<MappedTarget> all = mappedTargets("WORKFLOW");
        List<Long> requested = request == null ? List.of() : request.workflowIdsValue();
        if (requested.isEmpty()) throw new IllegalArgumentException("请至少选择一个已迁移工作流");
        Map<Long, MappedTarget> mapped = all.stream().collect(Collectors.toMap(MappedTarget::id, Function.identity(), (a, b) -> a, LinkedHashMap::new));
        LinkedHashSet<Long> unique = new LinkedHashSet<>(requested);
        List<MappedTarget> selected = new ArrayList<>();
        for (Long id : unique) {
            MappedTarget row = mapped.get(id);
            if (row == null) throw new IllegalArgumentException("Workflow #" + id + " 不属于当前迁移工具映射，拒绝操作");
            selected.add(row);
        }
        return selected;
    }

    private List<MappedTarget> mappedTargets(String targetType) {
        Map<Long, String> targets = new LinkedHashMap<>();
        jdbc.query("SELECT target_id,MAX(target_name) target_name FROM migration_object_map WHERE target_type=? GROUP BY target_id ORDER BY target_id",
                rs -> {
                    long id = Long.parseLong(rs.getString("target_id"));
                    targets.put(id, rs.getString("target_name"));
                }, targetType);
        return targets.entrySet().stream()
                .map(entry -> new MappedTarget(entry.getKey(), entry.getValue() == null || entry.getValue().isBlank()
                        ? targetType + " #" + entry.getKey() : entry.getValue()))
                .toList();
    }

    private String displayName(MappedTarget row, JsonNode workflow) {
        String name = workflow.path("name").asText("").trim();
        return name.isBlank() ? row.name() : name;
    }

    private WorkflowActionResult result(String action, int requested, List<WorkflowActionItem> items, String label) {
        int success = (int) items.stream().filter(WorkflowActionItem::success).count();
        int failure = items.size() - success;
        String message = failure == 0
                ? label + "完成：成功 " + success + " 个"
                : label + "完成：成功 " + success + " 个，失败/阻塞 " + failure + " 个，请查看明细";
        return new WorkflowActionResult(action, requested, success, failure, List.copyOf(items), message);
    }

    private WorkflowActionItem success(long id, String name, String status, String message, String instanceId) {
        return new WorkflowActionItem(id, name, true, status, message, instanceId);
    }

    private WorkflowActionItem failure(long id, String name, String status, String message) {
        return new WorkflowActionItem(id, name, false, status, message, null);
    }

    private String rootMessage(Throwable ex) {
        Throwable cursor = ex;
        while (cursor.getCause() != null) cursor = cursor.getCause();
        return cursor.getMessage() == null ? cursor.getClass().getSimpleName() : cursor.getMessage();
    }

    private record MappedTarget(long id, String name) { }
}
