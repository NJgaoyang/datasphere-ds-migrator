package com.company.migrator.web;

import com.company.migrator.common.MigrationModels.*;
import com.company.migrator.service.MigrationService;
import com.company.migrator.service.SettingService;
import com.company.migrator.source.DolphinScheduler319Reader;
import com.company.migrator.target.DataSphereClient;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api")
public class MigrationConsoleController {
    private final SettingService settings;
    private final MigrationService migrations;
    private final DolphinScheduler319Reader source;
    private final DataSphereClient target;

    public MigrationConsoleController(SettingService settings, MigrationService migrations,
                                      DolphinScheduler319Reader source, DataSphereClient target) {
        this.settings = settings; this.migrations = migrations; this.source = source; this.target = target;
    }

    @GetMapping("/health")
    public Map<String,Object> health() { return Map.of("success", true, "service", "datasphere-ds-migrator", "version", "0.1.0"); }

    @GetMapping("/settings")
    public SettingsView settings() { return settings.view(); }

    @PutMapping("/settings")
    public SettingsView updateSettings(@RequestBody SettingsUpdate request) { return settings.update(request); }

    @PostMapping("/settings/test-source")
    public ConnectionTest testSource() {
        var r = source.test(settings.get());
        return new ConnectionTest(r.success(), r.message(), r.version());
    }

    @PostMapping("/settings/test-target")
    public ConnectionTest testTarget() {
        var r = target.test(settings.get());
        return new ConnectionTest(r.success(), r.message(), r.detail());
    }

    @GetMapping("/runs")
    public List<RunView> runs() { return migrations.listRuns(); }

    @GetMapping("/runs/{runId}")
    public RunView run(@PathVariable long runId) { return migrations.getRun(runId); }

    @GetMapping("/runs/{runId}/items")
    public List<ItemView> items(@PathVariable long runId) { return migrations.items(runId); }

    @GetMapping("/runs/{runId}/issues")
    public List<IssueView> issues(@PathVariable long runId) { return migrations.issues(runId); }

    @GetMapping("/runs/{runId}/events")
    public List<EventView> events(@PathVariable long runId) { return migrations.events(runId); }

    @PostMapping("/runs/analyze")
    public OperationResult analyze() {
        long id = migrations.startAnalyze();
        return new OperationResult(true, "分析任务已创建", id);
    }

    @PostMapping("/runs/migrate")
    public OperationResult migrate(@RequestBody(required = false) StartMigrationRequest request) {
        long id = migrations.startMigration(request);
        return new OperationResult(true, request == null || request.dryRunValue() ? "试运行任务已创建" : "迁移任务已创建", id);
    }

    @PostMapping("/runs/{runId}/cancel")
    public OperationResult cancel(@PathVariable long runId) {
        migrations.cancel(runId);
        return new OperationResult(true, "已请求取消", runId);
    }

    @PostMapping("/issues/{issueId}/resolve")
    public OperationResult resolve(@PathVariable long issueId) {
        migrations.resolveIssue(issueId);
        return new OperationResult(true, "问题已标记为已处理", null);
    }
}
