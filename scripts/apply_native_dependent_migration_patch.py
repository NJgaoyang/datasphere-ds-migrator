from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SERVICE = ROOT / 'src/main/java/com/company/migrator/service/MigrationService.java'
CLIENT = ROOT / 'src/main/java/com/company/migrator/target/DataSphereClient.java'
README = ROOT / 'README.md'


def once(text, old, new, label):
    count = text.count(old)
    if count != 1:
        raise SystemExit(f'{label}: expected 1 match, got {count}')
    return text.replace(old, new, 1)

service = SERVICE.read_text()
service = once(service,
'''    private final DataSphereClient target;
    private final ObjectMapper mapper;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
''',
'''    private final DataSphereClient target;
    private final ObjectMapper mapper;
    private final DolphinSchedulerDependentMapper dependentMapper;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
''', 'mapper field')
service = once(service,
'''        this.target = target;
        this.mapper = mapper;
''',
'''        this.target = target;
        this.mapper = mapper;
        this.dependentMapper = new DolphinSchedulerDependentMapper(mapper);
''', 'constructor mapper')

service = once(service,
'''        for (WorkflowRow w : snapshot.workflows()) {
            checkCancelled(runId);
            insertItem(runId, "WORKFLOW", String.valueOf(w.code()), w.version(), w.name(), null,
                    "READY", w.releaseState() == 1 ? "源端已上线；目标端仍将保持 Offline" : "源端未上线", json(w));
            progress(runId, ++processed, total, "ANALYZE_WORKFLOW", "分析工作流：" + w.name());
        }
''',
'''        for (WorkflowRow w : snapshot.workflows()) {
            checkCancelled(runId);
            long itemId = insertItem(runId, "WORKFLOW", String.valueOf(w.code()), w.version(), w.name(), null,
                    "READY", w.releaseState() == 1 ? "源端已上线；目标端仍将保持 Offline" : "源端未上线", json(w));
            DolphinSchedulerDependentMapper.Plan dependencyPlan = dependentMapper.analyze(w, snapshot.tasks(), snapshot.edges());
            if (!dependencyPlan.supported()) {
                issue(runId, itemId, "ERROR", "DEPENDENT_SEMANTICS_UNSUPPORTED", "WORKFLOW", String.valueOf(w.code()), w.name(),
                        "DEPENDENT 无法无损迁移", dependencyPlan.message());
            }
            progress(runId, ++processed, total, "ANALYZE_WORKFLOW", "分析工作流：" + w.name());
        }
''', 'analyze workflow dependency')

service = service.replace('boolean auto = AUTO_TASK_TYPES.contains(type);',
                          'boolean auto = AUTO_TASK_TYPES.contains(type) || "DEPENDENT".equals(type);')
service = service.replace('auto ? "可自动迁移" : "当前版本需人工处理该任务类型"',
                          'auto ? ("DEPENDENT".equals(type) ? "将迁为 Native Scheduler 工作流依赖，不创建执行节点" : "可自动迁移") : "当前版本需人工处理该任务类型"')
service = service.replace('auto ? "DRY_RUN_OK" : "MANUAL_REQUIRED", auto ? "将创建/复用开发文件" : "任务类型需人工处理"',
                          'auto ? "DRY_RUN_OK" : "MANUAL_REQUIRED", auto ? ("DEPENDENT".equals(type) ? "将迁为 disabled 工作流依赖，不创建执行节点" : "将创建/复用开发文件") : "任务类型需人工处理"')

service = once(service,
'''            Long workflowCode = queue.removeFirst();
            WorkflowRow workflow = workflowsByCode.get(workflowCode);
            if (workflow == null) continue;
            for (TaskRow task : workflowTasks(workflow, snapshot.tasks())) {
''',
'''            Long workflowCode = queue.removeFirst();
            WorkflowRow workflow = workflowsByCode.get(workflowCode);
            if (workflow == null) continue;
            DolphinSchedulerDependentMapper.Plan dependencyPlan = dependentMapper.analyze(workflow, snapshot.tasks(), snapshot.edges());
            if (dependencyPlan.supported()) {
                for (DolphinSchedulerDependentMapper.Rule rule : dependencyPlan.rules()) {
                    if (!rule.upstreamWorkflowCode().equals(workflowCode) && expanded.add(rule.upstreamWorkflowCode())) {
                        queue.addLast(rule.upstreamWorkflowCode());
                    }
                }
            }
            for (TaskRow task : workflowTasks(workflow, snapshot.tasks())) {
''', 'scope dependency upstreams')
service = service.replace('自动包含 SQL 上游 ', '自动包含 SQL/DEPENDENT 上游 ')

service = service.replace('boolean supported = workflowSupported(w, snapshot.tasks());',
                          'boolean supported = workflowSupported(w, snapshot.tasks(), snapshot.edges());')
service = service.replace('if (!workflowSupported(w, snapshot.tasks())) {',
                          'if (!workflowSupported(w, snapshot.tasks(), snapshot.edges())) {')
service = service.replace('''            String message = supported
                    ? "将创建/复用 Native Scheduler 工作流并保持 Draft/Offline"
                    : "包含暂不支持的任务类型";
''',
'''            DolphinSchedulerDependentMapper.Plan dependencyPlan = dependentMapper.analyze(w, snapshot.tasks(), snapshot.edges());
            String message = supported
                    ? (dependencyPlan.hasDependency() ? "将创建/复用 Native Scheduler 工作流，并把根 DEPENDENT 迁为 disabled 工作流依赖" : "将创建/复用 Native Scheduler 工作流并保持 Draft/Offline")
                    : dependencyPlan.supported() ? "包含暂不支持的任务类型" : "DEPENDENT 无法无损迁移：" + dependencyPlan.message();
''')

service = once(service,
'''        Map<String, Long> fileByTask = new LinkedHashMap<>();
        for (TaskRow t : tasks.values()) {
            checkCancelled(runId);
            String type = normalizeType(t.taskType());
            if (!AUTO_TASK_TYPES.contains(type)) {
''',
'''        Map<String, Long> fileByTask = new LinkedHashMap<>();
        Map<Long, Long> dependencyTaskItemIds = new LinkedHashMap<>();
        for (TaskRow t : tasks.values()) {
            checkCancelled(runId);
            String type = normalizeType(t.taskType());
            if ("DEPENDENT".equals(type)) {
                long itemId = insertItem(runId, "TASK", String.valueOf(t.code()), t.version(), t.name(), type,
                        "RUNNING", "等待工作流创建后迁为 disabled Native Scheduler 工作流依赖", json(t));
                dependencyTaskItemIds.put(t.code(), itemId);
                progress(runId, ++processed, total, "MIGRATE_TASK", t.name());
                continue;
            }
            if (!AUTO_TASK_TYPES.contains(type)) {
''', 'defer dependent task')

service = once(service,
'''        for (ScheduleRow schedule : snapshot.schedules()) {
''',
'''        Map<Long, String> workflowTargetCodes = new LinkedHashMap<>();
        for (Map.Entry<Long, Long> entry : workflowTargetIds.entrySet()) {
            workflowTargetCodes.put(entry.getKey(), target.workflowCode(s, entry.getValue()));
        }
        for (WorkflowRow w : snapshot.workflows()) {
            DolphinSchedulerDependentMapper.Plan dependencyPlan = dependentMapper.analyze(w, snapshot.tasks(), snapshot.edges());
            if (!dependencyPlan.supported() || !dependencyPlan.hasDependency()) continue;
            Long dependencyItemId = dependencyTaskItemIds.get(dependencyPlan.dependentTaskCode());
            String downstreamCode = workflowTargetCodes.get(w.code());
            if (downstreamCode == null) continue;
            boolean dependencyFailed = false;
            for (DolphinSchedulerDependentMapper.Rule rule : dependencyPlan.rules()) {
                String upstreamCode = workflowTargetCodes.get(rule.upstreamWorkflowCode());
                if (upstreamCode == null) {
                    dependencyFailed = true;
                    if (dependencyItemId != null) {
                        issue(runId, dependencyItemId, "ERROR", "DEPENDENT_UPSTREAM_NOT_MIGRATED", "TASK",
                                String.valueOf(dependencyPlan.dependentTaskCode()), w.name(),
                                "上游工作流未成功迁移：" + rule.upstreamWorkflowCode(), "依赖未写入，避免产生错误调度语义");
                    }
                    continue;
                }
                target.saveWorkflowDependency(s, downstreamCode, upstreamCode, false,
                        rule.businessDateOffsetDays(), 3600);
                event(runId, "INFO", "MIGRATE_DEPENDENCY", w.name() + " <- " + rule.upstreamWorkflowCode() +
                        " · 业务日期偏移=" + rule.businessDateOffsetDays() + " · disabled");
            }
            if (dependencyItemId != null) {
                if (dependencyFailed) finishItem(dependencyItemId, "BLOCKED", null, null, "存在未迁移的上游工作流，依赖未完整写入");
                else finishItem(dependencyItemId, "SUCCESS", "WORKFLOW_DEPENDENCY", downstreamCode,
                        "DEPENDENT 已迁为 disabled Native Scheduler 工作流依赖，不创建 SQL/执行节点");
            }
        }

        for (ScheduleRow schedule : snapshot.schedules()) {
''', 'dependency migration phase')

service = once(service,
'''    private Map<String, Object> workflowPayload(WorkflowRow w, Snapshot snapshot, Map<String, Long> fileByTask) {
        List<TaskRow> tasks = snapshot.tasks().stream()
                .filter(t -> t.workflowCode() == w.code() && t.workflowVersion() == w.version()).toList();
        Map<Long, int[]> positions = parseLocations(w.locations());
        List<Map<String, Object>> nodes = new ArrayList<>();
        for (TaskRow t : tasks) {
            Map<String, Object> node = new LinkedHashMap<>();
''',
'''    private Map<String, Object> workflowPayload(WorkflowRow w, Snapshot snapshot, Map<String, Long> fileByTask) {
        List<TaskRow> tasks = snapshot.tasks().stream()
                .filter(t -> t.workflowCode() == w.code() && t.workflowVersion() == w.version()).toList();
        DolphinSchedulerDependentMapper.Plan dependencyPlan = dependentMapper.analyze(w, snapshot.tasks(), snapshot.edges());
        Long dependencyTaskCode = dependencyPlan.supported() ? dependencyPlan.dependentTaskCode() : null;
        Map<Long, int[]> positions = parseLocations(w.locations());
        List<Map<String, Object>> nodes = new ArrayList<>();
        for (TaskRow t : tasks) {
            if (dependentMapper.isDependent(t)) continue;
            Map<String, Object> node = new LinkedHashMap<>();
''', 'skip dependent node')
service = once(service,
'''        for (EdgeRow e : snapshot.edges()) {
            if (e.workflowCode() != w.code() || e.workflowVersion() != w.version() || e.preTaskCode() == 0) continue;
            Map<String, Object> edge = new LinkedHashMap<>();
''',
'''        for (EdgeRow e : snapshot.edges()) {
            if (e.workflowCode() != w.code() || e.workflowVersion() != w.version() || e.preTaskCode() == 0) continue;
            if (dependencyTaskCode != null && (e.preTaskCode() == dependencyTaskCode || e.postTaskCode() == dependencyTaskCode)) continue;
            Map<String, Object> edge = new LinkedHashMap<>();
''', 'skip dependent edges')

service = once(service,
'''    boolean workflowSupported(WorkflowRow w, List<TaskRow> tasks) {
        List<TaskRow> workflowTasks = tasks.stream()
                .filter(t -> t.workflowCode() == w.code() && t.workflowVersion() == w.version())
                .toList();
        return !workflowTasks.isEmpty()
                && workflowTasks.stream().allMatch(t -> AUTO_TASK_TYPES.contains(normalizeType(t.taskType())));
    }
''',
'''    boolean workflowSupported(WorkflowRow w, List<TaskRow> tasks) {
        return workflowSupported(w, tasks, List.of());
    }

    boolean workflowSupported(WorkflowRow w, List<TaskRow> tasks, List<EdgeRow> edges) {
        List<TaskRow> workflowTasks = tasks.stream()
                .filter(t -> t.workflowCode() == w.code() && t.workflowVersion() == w.version())
                .toList();
        if (workflowTasks.isEmpty()) return false;
        boolean taskTypesSupported = workflowTasks.stream().allMatch(t ->
                AUTO_TASK_TYPES.contains(normalizeType(t.taskType())) || dependentMapper.isDependent(t));
        if (!taskTypesSupported) return false;
        return dependentMapper.analyze(w, tasks, edges).supported();
    }
''', 'workflow support')
SERVICE.write_text(service)

client = CLIENT.read_text()
client = once(client,
'''    public boolean workflowExists(Settings settings, long workflowId) {
        try {
            JsonNode row = data(get(settings, "/api/workflows/" + workflowId));
            return row.path("id").asLong(0) == workflowId;
        } catch (Exception ignored) {
            return false;
        }
    }
''',
'''    public boolean workflowExists(Settings settings, long workflowId) {
        try {
            JsonNode row = data(get(settings, "/api/workflows/" + workflowId));
            return row.path("id").asLong(0) == workflowId;
        } catch (Exception ignored) {
            return false;
        }
    }

    public String workflowCode(Settings settings, long workflowId) {
        JsonNode row = data(get(settings, "/api/workflows/" + workflowId));
        String code = row.path("workflowCode").asText("").trim();
        if (code.isBlank()) throw new IllegalStateException("DataSphere Workflow #" + workflowId + " 未返回 workflowCode");
        return code;
    }
''', 'workflow code client')
client = once(client,
'''    public JsonNode validateWorkflow(Settings settings, long workflowId) {
''',
'''    public void saveWorkflowDependency(Settings settings, String downstreamWorkflowCode, String upstreamWorkflowCode,
                                       boolean enabled, int businessDateOffsetDays, int timeoutSeconds) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("downstreamWorkflowCode", downstreamWorkflowCode);
        payload.put("upstreamWorkflowCode", upstreamWorkflowCode);
        payload.put("enabled", enabled);
        payload.put("businessDateOffsetDays", businessDateOffsetDays);
        payload.put("timeoutSeconds", timeoutSeconds);
        data(post(settings, "/api/scheduler/dependencies", payload));
    }

    public JsonNode validateWorkflow(Settings settings, long workflowId) {
''', 'dependency client')
CLIENT.write_text(client)

readme = README.read_text()
needle = '- DolphinScheduler Schedule 统一迁到 `/api/scheduler/workflows/{workflowId}/schedule`，不再写入旧 `dev_file_schedule`\n'
replacement = needle + '- 同一 Workflow 内依赖只迁为 DAG Edge；安全的根 `DEPENDENT` 节点迁为 disabled Native Scheduler 跨工作流依赖，不再生成“依赖 SQL”或第二份调度\n'
if needle not in readme:
    raise SystemExit('README dependency insertion point missing')
readme = readme.replace(needle, replacement, 1)
README.write_text(readme)

for forbidden in ['saveDevelopmentSchedule(', 'DEV_FLOW_SCHEDULE']:
    if forbidden in service or forbidden in client:
        raise SystemExit('legacy duplicate schedule path remains: ' + forbidden)
print('native DEPENDENT migration patch applied')
