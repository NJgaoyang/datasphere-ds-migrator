from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected 1 match, found {count}")
    return text.replace(old, new, 1)


def regex_once(text: str, pattern: str, repl: str, label: str) -> str:
    new, count = re.subn(pattern, repl, text, count=1, flags=re.S)
    if count != 1:
        raise SystemExit(f"{label}: expected 1 regex match, found {count}")
    return new


service_path = ROOT / "src/main/java/com/company/migrator/service/MigrationService.java"
service = service_path.read_text()

service = replace_once(
    service,
    '''        Map<String, Long> taskWorkflowUseCounts = taskWorkflowUseCounts(snapshot.tasks());
        for (WorkflowRow w : snapshot.workflows()) {
            boolean supported = workflowSupported(w, snapshot.tasks());
            List<TaskRow> workflowTasks = workflowTasks(w, snapshot.tasks());
            boolean developmentOnly = isDevelopmentOnlyWorkflow(workflowTasks, taskWorkflowUseCounts);
            String message = !supported ? "包含暂不支持的任务类型"
                    : developmentOnly ? "纯 SQL 流程（" + workflowTasks.size() + " 个节点）将直接映射为数据开发任务流，不再创建重复编排工作流"
                    : "将创建/复用编排工作流并保持 Offline";
            insertItem(runId, "WORKFLOW", String.valueOf(w.code()), w.version(), w.name(), null,
                    supported ? "DRY_RUN_OK" : "BLOCKED", message, json(w));
            progress(runId, ++processed, total, "DRY_RUN", w.name());
        }
''',
    '''        for (WorkflowRow w : snapshot.workflows()) {
            boolean supported = workflowSupported(w, snapshot.tasks());
            String message = supported
                    ? "将创建/复用 Native Scheduler 工作流并保持 Draft/Offline"
                    : "包含暂不支持的任务类型";
            insertItem(runId, "WORKFLOW", String.valueOf(w.code()), w.version(), w.name(), null,
                    supported ? "DRY_RUN_OK" : "BLOCKED", message, json(w));
            progress(runId, ++processed, total, "DRY_RUN", w.name());
        }
''',
    "dry-run workflow policy",
)

service = replace_once(
    service,
    '"DRY_RUN_OK", "将保存为 disabled Workflow Schedule", json(s));',
    '"DRY_RUN_OK", "将保存为 disabled Native Scheduler Workflow Schedule", json(s));',
    "dry-run schedule message",
)

service = regex_once(
    service,
    r'''        Map<Long, ScheduleRow> scheduleByWorkflow = primarySchedules\(snapshot\.schedules\(\)\);\n        for \(WorkflowRow w : snapshot\.workflows\(\)\) \{\n            ScheduleRow schedule = scheduleByWorkflow\.get\(w\.code\(\)\);.*?\n        \}\n\n        Map<Long, Long> workflowTargetIds = new LinkedHashMap<>\(\);''',
    '''        Map<Long, ScheduleRow> scheduleByWorkflow = primarySchedules(snapshot.schedules());

        Map<Long, Long> workflowTargetIds = new LinkedHashMap<>();''',
    "remove legacy development schedule writes",
)

service = replace_once(
    service,
    '''        Map<Long, List<Long>> developmentWorkflowFileIds = new LinkedHashMap<>();
        Map<String, Long> taskWorkflowUseCounts = taskWorkflowUseCounts(snapshot.tasks());
''',
    '',
    "remove development-only state",
)

service = regex_once(
    service,
    r'''            List<TaskRow> workflowTasks = workflowTasks\(w, snapshot\.tasks\(\)\);\n            if \(isDevelopmentOnlyWorkflow\(workflowTasks, taskWorkflowUseCounts\)\) \{.*?\n                continue;\n            \}\n            Long existing = mappedId''',
    '''            Long existing = mappedId''',
    "remove pure-sql bypass",
)

service = replace_once(
    service,
    '            List<Long> developmentFileIds = developmentWorkflowFileIds.get(schedule.workflowCode());\n',
    '',
    "remove development schedule lookup",
)

service = regex_once(
    service,
    r'''            \} else if \(developmentFileIds != null\) \{\n                finishItem\(itemId, "SUCCESS", "DEV_FLOW_SCHEDULE", null,\n                        "纯 SQL 流程调度已保存到 " \+ developmentFileIds\.size\(\) \+ " 个数据开发任务，DAG 依赖保持一致且均为 disabled"\);\n            \} else if \(workflowId == null\) \{''',
    '''            } else if (workflowId == null) {''',
    "remove development schedule result",
)

service = replace_once(
    service,
    '''                finishItem(itemId, "SUCCESS", "WORKFLOW_SCHEDULE", String.valueOf(workflowId),
                        "工作流调度已保存；数据开发 SQL 任务同步继承 Cron、参数与 DAG 依赖，均保持 disabled");''',
    '''                finishItem(itemId, "SUCCESS", "WORKFLOW_SCHEDULE", String.valueOf(workflowId),
                        "Native Scheduler 工作流调度已保存并保持 disabled，未自动切生产");''',
    "schedule success message",
)

service = replace_once(
    service,
    '        completeRun(runId, "迁移完成；所有新工作流和调度保持 Offline，未自动切生产");',
    '        completeRun(runId, "迁移完成；DolphinScheduler Workflow 已落为 Native Scheduler 工作流，全部保持 Draft/Offline，调度保持 disabled，未自动切生产");',
    "completion message",
)

service = regex_once(
    service,
    r'''    private Map<String, Long> taskWorkflowUseCounts\(List<TaskRow> tasks\) \{.*?    private Map<Long, ScheduleRow> primarySchedules''',
    '''    private Map<Long, ScheduleRow> primarySchedules''',
    "remove development-only helper methods",
)

service = regex_once(
    service,
    r'''    private List<Long> upstreamFileIds\(WorkflowRow workflow, TaskRow task, List<EdgeRow> edges, Map<String, Long> fileByTask\) \{.*?    private List<Map<String, String>> mergedParams''',
    '''    private List<Map<String, String>> mergedParams''',
    "remove legacy task-schedule upstream helpers",
)

service = regex_once(
    service,
    r'''    private String sourceDatabaseName\(Settings settings, TaskRow task\) \{.*?    private Map<Long, int\[]> parseLocations''',
    '''    private Map<Long, int[]> parseLocations''',
    "remove legacy development schedule conversion helpers",
)

for forbidden in ["saveDevelopmentSchedule(", "DEV_FLOW", "isDevelopmentOnlyWorkflow(", "autoDetectedUpstreams("]:
    if forbidden in service:
        raise SystemExit(f"legacy migration path remains in MigrationService: {forbidden}")

service_path.write_text(service)

client_path = ROOT / "src/main/java/com/company/migrator/target/DataSphereClient.java"
client = client_path.read_text()
client = replace_once(
    client,
    '    private final Map<String, Long> defaultStarRocksDataSourceIds = new ConcurrentHashMap<>();\n',
    '',
    "remove legacy datasource cache",
)
client = regex_once(
    client,
    r'''    public void saveDevelopmentSchedule\(Settings settings, long fileId, String cycleType, String executionTime,.*?    public JsonNode validateWorkflow''',
    '''    public JsonNode validateWorkflow''',
    "remove legacy development schedule client APIs",
)
if "/api/files/" in client and "/schedule" in client and "saveDevelopmentSchedule" in client:
    raise SystemExit("legacy development schedule client still present")
client_path.write_text(client)

readme_path = ROOT / "README.md"
readme = readme_path.read_text()
readme = replace_once(
    readme,
    '- 正式迁移目录、开发文件、工作流和 disabled 调度\n- 纯 SQL Workflow 直接映射为数据开发任务 + DAG 依赖，不重复创建编排工作流\n',
    '- 正式迁移目录、开发文件、Native Scheduler 工作流和 disabled 调度\n- SQL / Shell / Python / SeaTunnel Task 迁为开发文件，DolphinScheduler Workflow 统一迁为真实 DataSphere Workflow，DAG 关系保留在工作流中\n- DolphinScheduler Schedule 统一迁到 `/api/scheduler/workflows/{workflowId}/schedule`，不再写入旧 `dev_file_schedule`\n',
    "README capability",
)
readme = replace_once(
    readme,
    '源端仅通过 JDBC 读取；目标端通过 DataSphere REST API 写入。正式迁移不会自动 Publish/Online。\n',
    '源端仅通过 JDBC 读取；目标端只通过 DataSphere REST API 写入。正式迁移不会自动 Publish/Online，也不会启用 Native Scheduler Schedule。生产切换前必须先完成 Dry Run、问题清零和目标对象核对。\n',
    "README safety",
)
readme += '''\n## 生产迁移目标模型\n\n```text\nDolphinScheduler Project  -> DataSphere 一级目录\nDolphinScheduler Task     -> DataSphere 开发文件\nDolphinScheduler Workflow -> DataSphere Native Scheduler Workflow\nDolphinScheduler DAG      -> Workflow Nodes / Edges\nDolphinScheduler Schedule -> Native Scheduler Schedule (disabled)\n```\n\n迁移工具不再创建或更新 `dev_file_schedule`。代码内容继续由数据开发文件承载，生产调度统一由 Native Scheduler 的 `workflow_instance / task_instance` 体系产生运行实例。\n'''
readme_path.write_text(readme)

test_path = ROOT / "src/test/java/com/company/migrator/service/MigrationServiceTest.java"
test_path.write_text('''package com.company.migrator.service;\n\nimport com.company.migrator.source.DolphinScheduler319Reader.TaskRow;\nimport com.company.migrator.source.DolphinScheduler319Reader.WorkflowRow;\nimport com.fasterxml.jackson.databind.ObjectMapper;\nimport org.junit.jupiter.api.AfterEach;\nimport org.junit.jupiter.api.Test;\n\nimport java.util.List;\n\nimport static org.junit.jupiter.api.Assertions.assertFalse;\nimport static org.junit.jupiter.api.Assertions.assertTrue;\n\nclass MigrationServiceTest {\n    private final MigrationService service = new MigrationService(null, null, null, null, new ObjectMapper());\n\n    @AfterEach\n    void close() {\n        service.close();\n    }\n\n    @Test\n    void pureSqlWorkflowIsSupportedAsNativeWorkflow() {\n        WorkflowRow workflow = workflow();\n        assertTrue(service.workflowSupported(workflow, List.of(task(100, 1, "SQL"), task(101, 1, "SQL"))));\n    }\n\n    @Test\n    void mixedSupportedTasksUseNativeWorkflow() {\n        WorkflowRow workflow = workflow();\n        assertTrue(service.workflowSupported(workflow, List.of(task(100, 1, "SQL"), task(101, 1, "SHELL"), task(102, 1, "PYTHON"))));\n    }\n\n    @Test\n    void workflowWithoutResolvedTasksIsNotSupported() {\n        assertFalse(service.workflowSupported(workflow(), List.of()));\n    }\n\n    @Test\n    void unsupportedTaskBlocksWorkflowMigration() {\n        assertFalse(service.workflowSupported(workflow(), List.of(task(100, 1, "SUB_PROCESS"))));\n    }\n\n    private WorkflowRow workflow() {\n        return new WorkflowRow(900, 1, "wf", null, 800, 1, null, null);\n    }\n\n    private TaskRow task(long code, int version, String type) {\n        return new TaskRow(900, 1, code, version, "task-" + code, type,\n                "{}", null, 800, 0, 0, "default", null);\n    }\n}\n''')

app_path = ROOT / "frontend/src/App.vue"
app = app_path.read_text()
app = replace_once(
    app,
    '<div><h2>迁移操作</h2><span>正式迁移不会自动发布或上线工作流</span></div>',
    '<div><h2>迁移操作</h2><span>Workflow 统一迁到 Native Scheduler；正式迁移不会自动发布、上线或启用调度</span></div>',
    "frontend safety hint",
)
app_path.write_text(app)

print("Native Scheduler migration patch applied successfully")
