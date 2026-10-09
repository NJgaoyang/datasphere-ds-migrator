package com.company.migrator.service;

import com.company.migrator.common.MigrationModels.CleanupFailure;
import com.company.migrator.common.MigrationModels.CleanupPreview;
import com.company.migrator.common.MigrationModels.CleanupRequest;
import com.company.migrator.common.MigrationModels.CleanupResult;
import com.company.migrator.common.MigrationModels.OfflineResult;
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

    public OfflineResult offlineDevelopment() {
        Settings s = settings.get();
        List<CleanupFailure> failures = new ArrayList<>();
        int offlined = 0;
        int skipped = 0;

        for (MappedTarget row : mappedTargets("DEV_FILE")) {
            try {
                if (!target.fileExists(s, row.id())) {
                    skipped++;
                    continue;
                }
                String lifecycle = target.fileLifecycleStatus(s, row.id());
                if (!"PUBLISHED".equalsIgnoreCase(lifecycle) && !"ONLINE".equalsIgnoreCase(lifecycle)) {
                    skipped++;
                    continue;
                }
                target.offlineFile(s, row.id());
                offlined++;
            } catch (Exception ex) {
                failures.add(new CleanupFailure("数据开发", row.name(), rootMessage(ex)));
            }
        }

        int failureCount = failures.size();
        String message = failureCount == 0
                ? "下线完成；已下线数据开发任务 " + offlined + " 个，未上线或不存在 " + skipped + " 个"
                : "下线完成但有 " + failureCount + " 个任务下线失败，请查看失败明细";
        return new OfflineResult(failureCount == 0, offlined, skipped,
                failureCount, List.copyOf(failures), message);
    }

    public CleanupResult cleanup(CleanupRequest request) {
        if (request == null || request.empty()) throw new IllegalArgumentException("请至少勾选数据开发或任务流");
        Settings s = settings.get();
        List<CleanupFailure> failures = new ArrayList<>();
        int deletedWorkflows = 0;
        int deletedDevelopment = 0;
        int deletedRecycledDevelopment = 0;

        // DataForge workflow references and cross-workflow dependencies must be removed before dev files.
        if (request.workflowsValue()) {
            for (MappedTarget row : mappedTargets("WORKFLOW")) {
                try {
                    if (target.workflowExists(s, row.id())) {
                        String workflowCode = target.workflowCode(s, row.id());
                        String status = target.workflowStatus(s, row.id());
                        if ("PUBLISHED".equalsIgnoreCase(status)) {
                            // Disabling Cron alone does not change the workflow lifecycle.
                            // DataForge rejects deletion of PUBLISHED workflows until offline.
                            target.unpublishWorkflow(s, row.id());
                        }
                        target.deleteWorkflowDependencies(s, workflowCode);
                        target.deleteWorkflow(s, row.id());
                    }
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
                    boolean active = target.fileExists(s, row.id());
                    boolean recycledAtStart = !active && target.recycledFileExists(s, row.id());
                    if (active) {
                        String lifecycle = target.fileLifecycleStatus(s, row.id());
                        if ("PUBLISHED".equalsIgnoreCase(lifecycle) || "ONLINE".equalsIgnoreCase(lifecycle)) {
                            target.offlineFile(s, row.id());
                        }
                        target.deleteFile(s, row.id());
                        target.permanentlyDeleteFile(s, row.id());
                        deletedDevelopment++;
                    } else if (recycledAtStart) {
                        target.permanentlyDeleteFile(s, row.id());
                        deletedRecycledDevelopment++;
                    } else {
                        // The target object was already removed outside the migrator; remove only the stale mapping.
                        deletedDevelopment++;
                    }
                    deleteMapping("DEV_FILE", row.id());
                } catch (Exception ex) {
                    failures.add(new CleanupFailure("数据开发", row.name(), rootMessage(ex)));
                }
            }
        }

        int failureCount = failures.size();
        String message = failureCount == 0
                ? "清除完成；已删除任务流 " + deletedWorkflows + " 个，数据开发任务 " + deletedDevelopment
                    + " 个，回收箱历史任务 " + deletedRecycledDevelopment + " 个"
                : "清除完成但有 " + failureCount + " 个对象未删除，请查看失败明细";
        return new CleanupResult(failureCount == 0, deletedDevelopment, deletedRecycledDevelopment, deletedWorkflows,
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
