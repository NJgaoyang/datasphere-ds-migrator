package com.company.migrator.service;

import com.company.migrator.common.MigrationModels.CleanupFailure;
import com.company.migrator.common.MigrationModels.CleanupPreview;
import com.company.migrator.common.MigrationModels.CleanupRequest;
import com.company.migrator.common.MigrationModels.CleanupResult;
import com.company.migrator.common.MigrationModels.Settings;
import com.company.migrator.target.DataSphereClient;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class CleanupService {
    private final JdbcTemplate jdbc;
    private final SettingService settings;
    private final DataSphereClient target;

    public CleanupService(JdbcTemplate jdbc, SettingService settings, DataSphereClient target) {
        this.jdbc = jdbc;
        this.settings = settings;
        this.target = target;
    }

    public CleanupPreview preview(CleanupRequest request) {
        CleanupRequest selected = request == null ? new CleanupRequest(false, false) : request;
        int development = selected.developmentValue() ? mappedTargets("DEV_FILE").size() : 0;
        int workflows = selected.workflowsValue() ? mappedTargets("WORKFLOW").size() : 0;
        return new CleanupPreview(selected.developmentValue(), selected.workflowsValue(), development, 0,
                workflows, development + workflows);
    }

    public CleanupResult cleanup(CleanupRequest request) {
        if (request == null || request.empty()) throw new IllegalArgumentException("请至少勾选数据开发或任务流");
        Settings s = settings.get();
        List<CleanupFailure> failures = new ArrayList<>();
        int deletedWorkflows = 0;
        int deletedDevelopment = 0;

        // Workflows must be removed first so their dev-file references do not block task cleanup.
        if (request.workflowsValue()) {
            for (MappedTarget row : mappedTargets("WORKFLOW")) {
                try {
                    if (target.workflowExists(s, row.id())) target.deleteWorkflow(s, row.id());
                    deleteMapping("WORKFLOW", row.id());
                    deletedWorkflows++;
                } catch (Exception ex) {
                    failures.add(new CleanupFailure("任务流", row.name(), rootMessage(ex)));
                }
            }
        }

        if (request.developmentValue()) {
            for (MappedTarget row : mappedTargets("DEV_FILE")) {
                try {
                    if (target.fileExists(s, row.id())) {
                        String lifecycle = target.fileLifecycleStatus(s, row.id());
                        if ("ONLINE".equalsIgnoreCase(lifecycle)) {
                            throw new IllegalStateException("开发任务仍为 ONLINE，请先在 DataSphere 下线后再清除");
                        }
                        target.deleteFile(s, row.id());
                    }
                    deleteMapping("DEV_FILE", row.id());
                    deletedDevelopment++;
                } catch (Exception ex) {
                    failures.add(new CleanupFailure("数据开发", row.name(), rootMessage(ex)));
                }
            }
        }

        int failureCount = failures.size();
        String message = failureCount == 0
                ? "清除完成；已删除任务流 " + deletedWorkflows + " 个，数据开发任务 " + deletedDevelopment + " 个"
                : "清除完成但有 " + failureCount + " 个对象未删除，请查看失败明细";
        return new CleanupResult(failureCount == 0, deletedDevelopment, 0, deletedWorkflows,
                failureCount, List.copyOf(failures), message);
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

    private void deleteMapping(String targetType, long targetId) {
        jdbc.update("DELETE FROM migration_object_map WHERE target_type=? AND target_id=?", targetType, String.valueOf(targetId));
    }

    private String rootMessage(Throwable ex) {
        Throwable cursor = ex;
        while (cursor.getCause() != null) cursor = cursor.getCause();
        return cursor.getMessage() == null ? cursor.getClass().getSimpleName() : cursor.getMessage();
    }

    private record MappedTarget(long id, String name) { }
}
